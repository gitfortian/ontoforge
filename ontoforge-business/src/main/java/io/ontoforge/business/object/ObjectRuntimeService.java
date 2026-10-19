package io.ontoforge.business.object;

import io.ontoforge.business.model.NativeDraftService;
import io.ontoforge.business.registry.RuntimeTypeResolver;
import io.ontoforge.business.connection.DataBindingService;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.ontoforge.platform.identity.B0AuthorizationService;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

/**
 * M6 bounded, read-only virtual Object Runtime. Object identity is derived from
 * the immutable binding revision and type contract; values and source evidence
 * are returned separately. No object store or write path is created here.
 */
@Service
public class ObjectRuntimeService {
    private static final Pattern IDENT = Pattern.compile("[a-z][a-z0-9_]{0,62}");
    private static final Pattern CANONICAL_INTEGER = Pattern.compile("0|-?[1-9][0-9]*");
    private static final Pattern UUID_TEXT = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final int MAX_PROPERTIES = 100;
    private static final int MAX_ROWS_PER_IDENTITY = 10_000;
    private static final int MAX_OUTPUT_BYTES = 1024 * 1024;
    private static final long MAX_SOURCE_SCAN_BYTES = 8L * 1024 * 1024;
    private static final int QUERY_TIMEOUT_SECONDS = 3;

    private final DataBindingService bindings;
    private final RuntimeTypeResolver typeResolver;
    private final B0AuthorizationService authorization;
    private final ObjectMapper mapper;
    private final ObjectProvider<JdbcTemplate> sourceProvider;
    private final List<String> allowedTables;
    private final io.ontoforge.dao.query.PostgresSourceRepository sourceRepository;

    public ObjectRuntimeService(DataBindingService bindings, RuntimeTypeResolver typeResolver,
            B0AuthorizationService authorization, ObjectMapper mapper,
            io.ontoforge.dao.query.PostgresSourceRepository sourceRepository,
            @Qualifier("bindingSourceJdbcTemplate") ObjectProvider<JdbcTemplate> sourceProvider,
            @Value("${ontoforge.binding.source.allowed-tables:m5_supplier_source}") String allowedTables) {
        this.bindings = bindings;
        this.typeResolver = typeResolver;
        this.authorization = authorization;
        this.mapper = mapper;
        this.sourceRepository = sourceRepository;
        this.sourceProvider = sourceProvider;
        this.allowedTables = Arrays.stream(allowedTables.split(","))
                .map(String::trim).filter(value -> !value.isEmpty()).distinct().toList();
        if (this.allowedTables.stream().anyMatch(value -> !IDENT.matcher(value).matches())) {
            throw new IllegalStateException("M6 allowed source table names must be simple identifiers");
        }
    }

    public record ObjectKey(String valueType, String value) {}
    public record ObjectReadRequest(String bindingId, Long bindingRevision, ObjectKey key,
                                   List<String> propertyRefs) {}
    public record ObjectRef(String packageId, String objectTypeId, String identityDomainId, ObjectKey key) {}
    public record BindingVersion(String bindingId, long revision, String digest, String sourceId, String kind) {}
    public record SourceReadRef(String id, String sourceId, String schema, String table,
                                List<String> columns, String schemaFingerprint,
                                Instant startedAt, Instant completedAt,
                                String transactionIsolation, String consistencyNote) {}
    public record PropertyProvenance(String sourceId, String schema, String table, String column,
                                     String bindingId, long bindingRevision, String sourceReadRef) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PropertyValue(String propertyRef, String valueType, String status,
                                Object value, PropertyProvenance provenance) {}
    public record QualityEnvelope(String accuracy, String completeness, String freshness, String answerScope) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ObjectReadResult(String status, String executionId, Instant executedAt,
                                   Object typeSourceRef, String payloadSha256, ObjectRef objectRef,
                                   List<PropertyValue> properties, int matchedRows, int foldedDuplicateRows,
                                   List<String> selectedPropertyRefs, List<BindingVersion> bindingVersions,
                                   SourceReadRef sourceReadRef, String accessMode, QualityEnvelope quality) {}
    public record ObjectReadFailure(String status, String executionId, String code,
                                    String message, String fieldPath, QualityEnvelope quality) {}

    public static final class ObjectRuntimeFailure extends RuntimeException {
        private final int http;
        private final String code;
        private final String fieldPath;
        private final String executionId;

        public ObjectRuntimeFailure(int http, String code, String message, String fieldPath, String executionId) {
            super(message);
            this.http = http;
            this.code = code;
            this.fieldPath = fieldPath;
            this.executionId = executionId;
        }
        public int http() { return http; }
        public String code() { return code; }
        public String fieldPath() { return fieldPath; }
        public String executionId() { return executionId; }
    }

    private record KeySpec(String valueType, String value, Object jdbcValue) {}
    private record ValueCandidate(String status, Object value) {}
    private record RawRead(List<Map<String, Object>> rows, DataBindingService.SourceTable source,
                           List<String> columns, Instant startedAt, Instant completedAt) {}

    public ObjectReadResult read(ObjectReadRequest request, Authentication authentication) {
        String executionId = UUID.randomUUID().toString();
        try {
            validateRequest(request, executionId);
            UUID bindingId = parseUuid(request.bindingId(), "bindingId", executionId);
            DataBindingService.BindingDetail binding = bindings.getBinding(bindingId, request.bindingRevision(), authentication);
            if (!"VALID".equals(binding.validationStatus())) {
                throw fail(409, "BINDING_UNAVAILABLE", "指定 Binding revision 未通过有界校验，拒绝读取对象", "bindingRevision", executionId);
            }
            UUID snapshotId = parseUuid(binding.snapshotId(), "snapshotId", executionId);
            authorization.requireSnapshot(authentication, snapshotId, B0AuthorizationService.Operation.READ_SNAPSHOT);
            RuntimeTypeResolver.TypeDescriptor type = typeResolver.getSnapshotType(snapshotId, binding.objectTypeId());
            if (!binding.packageId().equals(type.packageId())
                    || !Objects.equals(binding.payloadSha256(), type.payloadSha256())
                    || !binding.objectTypeId().equals(type.objectTypeId())) {
                throw fail(409, "BINDING_SNAPSHOT_MISMATCH",
                        "Binding revision 与不可变 Native Snapshot 的包、类型或摘要不一致", "bindingRevision", executionId);
            }

            Map<String, RuntimeTypeResolver.PropertyDescriptor> propertiesById = new LinkedHashMap<>();
            for (RuntimeTypeResolver.PropertyDescriptor property : type.properties()) propertiesById.put(property.id(), property);
            RuntimeTypeResolver.PropertyDescriptor primaryKey = propertiesById.get(type.primaryKeyRef());
            if (primaryKey == null) {
                throw fail(409, "BINDING_UNAVAILABLE", "Snapshot 的 primaryKeyRef 不属于当前 ObjectType", "objectTypeId", executionId);
            }
            KeySpec key = keySpec(request.key(), primaryKey, executionId);
            List<RuntimeTypeResolver.PropertyDescriptor> selected = selectProperties(request.propertyRefs(), type.properties(), executionId);
            Map<String, DataBindingService.FieldMapping> mappings =
                    validateMappings(binding, propertiesById, type.primaryKeyRef(), executionId);

            RawRead raw = readSource(binding, type, selected, mappings, key, executionId);
            DataBindingService.BindingDetail afterRead = bindings.getBinding(bindingId, request.bindingRevision(), authentication);
            if (afterRead.revision() != binding.revision()
                    || !Objects.equals(afterRead.digest(), binding.digest())
                    || !Objects.equals(afterRead.identityDomainId(), binding.identityDomainId())
                    || !Objects.equals(afterRead.snapshotId(), binding.snapshotId())
                    || !Objects.equals(afterRead.payloadSha256(), binding.payloadSha256())) {
                throw fail(409, "BINDING_REVISION_CONFLICT",
                        "读取期间 Binding 版本证据发生变化；拒绝发布对象结果，请重新读取", "bindingRevision", executionId);
            }
            if (raw.rows().isEmpty()) {
                throw fail(404, "OBJECT_NOT_FOUND",
                        "授权与来源读取均已完成，但当前身份域中不存在此业务键", "key", executionId);
            }
            for (Map<String, Object> row : raw.rows()) {
                String actual = canonicalSourceKey(row.get(binding.sourceKey()), key, executionId);
                if (!key.value().equals(actual)) {
                    throw fail(409, "OBJECT_IDENTITY_CONFLICT",
                            "来源行的规范键与请求身份不一致，拒绝选择任意记录", "key", executionId);
                }
            }
            validateDuplicateRows(raw.rows(), selected, mappings, type.primaryKeyRef(), executionId);

            String sourceReadId = UUID.randomUUID().toString();
            SourceReadRef sourceReadRef = new SourceReadRef(sourceReadId, binding.sourceId(),
                    binding.sourceSchema(), binding.sourceTable(), raw.columns(), raw.source().schemaFingerprint(),
                    raw.startedAt(), raw.completedAt(), "REPEATABLE_READ",
                    "单次 PostgreSQL REPEATABLE READ 来源读取；没有可靠业务水位，因此不推断业务资料最新性。");
            Map<String, Object> row = raw.rows().getFirst();
            List<PropertyValue> values = new ArrayList<>();
            for (RuntimeTypeResolver.PropertyDescriptor property : selected) {
                DataBindingService.FieldMapping mapping = mappings.get(property.id());
                if (mapping == null) {
                    values.add(new PropertyValue(property.id(), property.valueType(), "MISSING", null, null));
                    continue;
                }
                ValueCandidate candidate = candidate(property, mapping, row.get(mapping.sourceColumn()), executionId);
                if (property.id().equals(type.primaryKeyRef())
                        && (!"PRESENT".equals(candidate.status()) || !Objects.equals(candidate.value(), key.value()))) {
                    throw fail(409, "OBJECT_IDENTITY_CONFLICT",
                            "主键 PropertyValue 与 ObjectRef.key 的规范值不一致，拒绝返回对象", property.id(), executionId);
                }
                PropertyProvenance provenance = new PropertyProvenance(binding.sourceId(), binding.sourceSchema(),
                        binding.sourceTable(), mapping.sourceColumn(), binding.bindingId(), binding.revision(), sourceReadId);
                values.add(new PropertyValue(property.id(), property.valueType(), candidate.status(),
                        candidate.value(), provenance));
            }
            List<String> selectedRefs = selected.stream().map(RuntimeTypeResolver.PropertyDescriptor::id).toList();
            ObjectReadResult result = new ObjectReadResult("SUCCESS", executionId, Instant.now(), type.sourceRef(),
                    type.payloadSha256(), new ObjectRef(binding.packageId(), binding.objectTypeId(),
                    binding.identityDomainId(), new ObjectKey(key.valueType(), key.value())),
                    List.copyOf(values), raw.rows().size(), Math.max(0, raw.rows().size() - 1),
                    selectedRefs, List.of(new BindingVersion(binding.bindingId(), binding.revision(),
                    binding.digest(), binding.sourceId(), "OBJECT")), sourceReadRef,
                    switch (authorization.accessMode(authentication)) {
                        case "JWT_AUTHENTICATED" -> "JWT_AUTHORIZED";
                        case "SESSION_AUTHENTICATED" -> "SESSION_AUTHORIZED";
                        default -> "LOCAL_ISOLATED";
                    },
                    new QualityEnvelope("EXACT", "COMPLETE", "UNKNOWN", "REQUESTED_SCOPE"));
            try {
                if (mapper.writeValueAsBytes(result).length > MAX_OUTPUT_BYTES) {
                    throw fail(429, "QUERY_BUDGET_EXCEEDED", "对象结果超过 1 MiB 输出预算；没有返回部分对象", "result", executionId);
                }
            } catch (JsonProcessingException ex) {
                throw fail(500, "OBJECT_RESULT_SERIALIZATION_FAILED", "对象结果无法安全序列化", null, executionId);
            }
            return result;
        } catch (ObjectRuntimeFailure ex) {
            throw ex;
        } catch (DataBindingService.BindingFailure ex) {
            throw fail(ex.http, ex.code, ex.getMessage(), ex.fieldPath, executionId);
        } catch (NativeDraftService.DraftFailure ex) {
            throw fail(ex.http, ex.code, ex.getMessage(), null, executionId);
        } catch (AccessDeniedException ex) {
            throw fail(403, "FORBIDDEN", "当前主体没有该模型包的对象读取权限", null, executionId);
        } catch (DataAccessException ex) {
            throw fail(503, "PERSISTENCE_UNAVAILABLE", "绑定或模型元数据暂不可用；未返回对象值", null, executionId);
        } catch (IllegalArgumentException ex) {
            throw fail(400, "INVALID_OBJECT_REQUEST", "请求格式不合法", null, executionId);
        }
    }

    private RawRead readSource(DataBindingService.BindingDetail binding, RuntimeTypeResolver.TypeDescriptor type,
            List<RuntimeTypeResolver.PropertyDescriptor> selected,
            Map<String, DataBindingService.FieldMapping> mappings, KeySpec key, String executionId) {
        if (!DataBindingService.SOURCE_ID.equals(binding.sourceId())
                || !"public".equals(binding.sourceSchema())
                || !allowedTables.contains(binding.sourceTable())
                || !IDENT.matcher(binding.sourceTable()).matches()
                || !IDENT.matcher(binding.sourceKey()).matches()) {
            throw fail(409, "BINDING_UNAVAILABLE", "Binding 指向的来源不在服务端登记的只读允许列表中", "bindingId", executionId);
        }
        JdbcTemplate source = sourceProvider.getIfAvailable();
        if (source == null) {
            throw fail(503, "SOURCE_UNAVAILABLE", "没有配置独立的来源只读连接；不能判断对象是否存在", "source", executionId);
        }
        try {
            LinkedHashSet<String> selectedColumns = new LinkedHashSet<>();
            selectedColumns.add(binding.sourceKey());
            for (RuntimeTypeResolver.PropertyDescriptor property : selected) {
                DataBindingService.FieldMapping mapping = mappings.get(property.id());
                if (mapping != null) selectedColumns.add(mapping.sourceColumn());
            }
            var plan=new io.ontoforge.dao.query.PostgresSourceRepository.ReadPlan(binding.sourceSchema(),binding.sourceTable(),
                binding.sourceKey(),List.copyOf(selectedColumns),key.valueType(),key.jdbcValue(),MAX_ROWS_PER_IDENTITY,MAX_SOURCE_SCAN_BYTES,QUERY_TIMEOUT_SECONDS);
            var read=sourceRepository.read(source,plan,metadata->{
                DataBindingService.SourceTable sourceTable=bindings.sourceMetadata(binding.sourceId(),binding.sourceSchema(),binding.sourceTable(),metadata);
                if(!Objects.equals(binding.sourceSchemaFingerprint(),sourceTable.schemaFingerprint()))
                    throw fail(409,"BINDING_SCHEMA_DRIFT","来源结构指纹与指定 Binding revision 不一致；请返回 M5 重新核验映射","sourceSchemaFingerprint",executionId);
                Map<String, DataBindingService.SourceColumn> columnsByName = new LinkedHashMap<>();
                for (DataBindingService.SourceColumn column : sourceTable.columns()) columnsByName.put(column.name(), column);
                for (Map.Entry<String, DataBindingService.FieldMapping> entry : mappings.entrySet()) {
                    DataBindingService.FieldMapping mapping = entry.getValue();
                    RuntimeTypeResolver.PropertyDescriptor property = type.properties().stream()
                            .filter(candidate -> candidate.id().equals(entry.getKey())).findFirst().orElse(null);
                    DataBindingService.SourceColumn column = columnsByName.get(mapping.sourceColumn());
                    if (property == null || column == null || !compatible(property, column)) {
                        throw fail(409, "BINDING_UNAVAILABLE",
                                "Binding 的属性映射无法通过当前 Snapshot 与来源列合同复核", "fieldMappings", executionId);
                    }
                }
                // Read only the explicitly requested property columns plus the identity key.
                // Conflict checks are scoped to requested properties by the accepted OFIP-0008
                // contract; unrequested values are neither materialized nor used to widen the result.
                for (String column : selectedColumns) {
                    if (!columnsByName.containsKey(column) || !IDENT.matcher(column).matches()) {
                        throw fail(409, "BINDING_SCHEMA_DRIFT", "绑定引用的来源列已不存在，拒绝按同名列自动重绑", "sourceColumn", executionId);
                    }
                }
            });
            return new RawRead(read.rows(),bindings.sourceMetadata(binding.sourceId(),binding.sourceSchema(),binding.sourceTable(),read.metadata()),
                read.columns(),read.startedAt(),read.completedAt());
        } catch (io.ontoforge.dao.query.PostgresSourceRepository.ReadFailure ex) {
            int status="QUERY_BUDGET_EXCEEDED".equals(ex.code())?429:"BINDING_SCHEMA_DRIFT".equals(ex.code())?409:503;
            throw fail(status,ex.code(),ex.getMessage(),ex.field(),executionId);
        } catch (ObjectRuntimeFailure ex) {
            throw ex;
        } catch (QueryTimeoutException ex) {
            throw fail(503, "SOURCE_TIMEOUT", "来源读取超时；未返回对象值，请检查来源后重试", "source", executionId);
        } catch (DataAccessException ex) {
            if (isTimeout(ex)) {
                throw fail(503, "SOURCE_TIMEOUT", "来源读取超时；未返回对象值，请检查来源后重试", "source", executionId);
            }
            throw fail(503, "SOURCE_UNAVAILABLE", "来源数据库不可用或拒绝了只读查询；不能判断对象是否存在", "source", executionId);
        }
    }

    private Map<String, DataBindingService.FieldMapping> validateMappings(
            DataBindingService.BindingDetail binding,
            Map<String, RuntimeTypeResolver.PropertyDescriptor> properties,
            String primaryKeyRef, String executionId) {
        Map<String, DataBindingService.FieldMapping> mappings = new LinkedHashMap<>();
        for (DataBindingService.FieldMapping mapping : binding.fieldMappings()) {
            if (mapping == null || mapping.propertyRef() == null || mapping.sourceColumn() == null
                    || !IDENT.matcher(mapping.sourceColumn()).matches()
                    || !"AUTHORITATIVE".equals(mapping.authority())
                    || !properties.containsKey(mapping.propertyRef())
                    || mappings.putIfAbsent(mapping.propertyRef(), mapping) != null) {
                throw fail(409, "BINDING_UNAVAILABLE", "Binding revision 的属性映射不符合已批准的单源权威合同", "fieldMappings", executionId);
            }
        }
        DataBindingService.FieldMapping keyMapping = mappings.get(primaryKeyRef);
        if (keyMapping == null || !binding.sourceKey().equals(keyMapping.sourceColumn())) {
            throw fail(409, "BINDING_UNAVAILABLE", "Binding 的来源身份键没有映射到 ObjectType.primaryKeyRef", "sourceKey", executionId);
        }
        for (RuntimeTypeResolver.PropertyDescriptor property : properties.values()) {
            if (property.required() && !mappings.containsKey(property.id())) {
                throw fail(409, "BINDING_UNAVAILABLE", "Binding 缺少必填属性的权威映射", property.id(), executionId);
            }
        }
        return mappings;
    }

    private List<RuntimeTypeResolver.PropertyDescriptor> selectProperties(
            List<String> requested, List<RuntimeTypeResolver.PropertyDescriptor> properties, String executionId) {
        if (requested == null) {
            if (properties.size() > MAX_PROPERTIES) {
                throw fail(422, "INVALID_OBJECT_REQUEST",
                        "对象类型超过 100 属性的默认读取上限；请显式指定不超过 100 个 propertyRefs", "propertyRefs", executionId);
            }
            return List.copyOf(properties);
        }
        if (requested.size() > MAX_PROPERTIES) {
            throw fail(422, "INVALID_OBJECT_REQUEST", "单次最多请求 100 个属性", "propertyRefs", executionId);
        }
        Set<String> refs = new LinkedHashSet<>();
        for (String ref : requested) {
            if (ref == null || ref.isBlank() || ref.length() > 128 || !refs.add(ref)) {
                throw fail(422, "INVALID_OBJECT_REQUEST", "propertyRefs 不能为空且不能重复", "propertyRefs", executionId);
            }
        }
        for (String ref : refs) {
            if (properties.stream().noneMatch(property -> property.id().equals(ref))) {
                throw fail(422, "PROPERTY_NOT_IN_TYPE", "请求的 propertyRef 不属于当前 ObjectType", "propertyRefs", executionId);
            }
        }
        return properties.stream().filter(property -> refs.contains(property.id())).toList();
    }

    private KeySpec keySpec(ObjectKey key, RuntimeTypeResolver.PropertyDescriptor primaryKey, String executionId) {
        if (key == null || key.valueType() == null || key.value() == null) {
            throw fail(422, "INVALID_OBJECT_KEY", "必须提供明确的 STRING 或 INTEGER 规范键", "key", executionId);
        }
        String primaryType = normalizeType(primaryKey.valueType());
        if (Set.of("STRING", "TEXT").contains(primaryType)) {
            if (!"STRING".equals(key.valueType()) || key.value().isBlank()
                    || key.value().length() > 256 || key.value().indexOf(0) >= 0) {
                throw fail(422, "INVALID_OBJECT_KEY", "STRING 键必须是非空、最多 256 字符的原始文本；不自动 trim 或改写", "key", executionId);
            }
            return new KeySpec("STRING", key.value(), key.value());
        }
        if (Set.of("INTEGER", "INT", "LONG").contains(primaryType)) {
            if (!"INTEGER".equals(key.valueType()) || !CANONICAL_INTEGER.matcher(key.value()).matches()) {
                throw fail(422, "INVALID_OBJECT_KEY", "INTEGER 键必须使用规范十进制形式（无前导零、+ 号或 -0）", "key", executionId);
            }
            try {
                return new KeySpec("INTEGER", key.value(), Long.valueOf(key.value()));
            } catch (NumberFormatException ex) {
                throw fail(422, "INVALID_OBJECT_KEY", "INTEGER 键超出当前 PostgreSQL 整数来源支持范围", "key", executionId);
            }
        }
        throw fail(422, "UNSUPPORTED_IDENTITY_STRATEGY", "首版仅支持单列 STRING / INTEGER 主键", "key", executionId);
    }

    private static String canonicalSourceKey(Object value, KeySpec key, String executionId) {
        if (value == null) {
            throw fail(409, "OBJECT_IDENTITY_CONFLICT", "来源返回了空身份键，拒绝构造对象", "key", executionId);
        }
        if ("STRING".equals(key.valueType())) {
            if (!(value instanceof String text)) {
                throw fail(422, "PROPERTY_TYPE_MISMATCH", "来源身份键不是无损 STRING", "key", executionId);
            }
            return text;
        }
        String integer = integralString(value);
        if (integer == null) {
            throw fail(422, "PROPERTY_TYPE_MISMATCH", "来源身份键不是无损 INTEGER", "key", executionId);
        }
        return integer;
    }

    private static ValueCandidate candidate(RuntimeTypeResolver.PropertyDescriptor property,
            DataBindingService.FieldMapping mapping, Object raw, String executionId) {
        if (mapping == null) return new ValueCandidate("MISSING", null);
        if (raw == null) {
            if (property.required()) {
                throw fail(422, "PROPERTY_TYPE_MISMATCH", "必填属性在来源中为 SQL NULL，拒绝返回部分对象", property.id(), executionId);
            }
            return new ValueCandidate("NULL", null);
        }
        return new ValueCandidate("PRESENT", convert(property, raw, executionId));
    }

    static void validateDuplicateRows(List<Map<String, Object>> rows,
            List<RuntimeTypeResolver.PropertyDescriptor> selected,
            Map<String, DataBindingService.FieldMapping> mappings,
            String primaryKeyRef, String executionId) {
        if (rows.size() < 2) return;
        for (RuntimeTypeResolver.PropertyDescriptor property : selected) {
            DataBindingService.FieldMapping mapping = mappings.get(property.id());
            ValueCandidate first = candidate(property, mapping,
                    mapping == null ? null : rows.getFirst().get(mapping.sourceColumn()), executionId);
            for (int i = 1; i < rows.size(); i++) {
                ValueCandidate next = candidate(property, mapping,
                        mapping == null ? null : rows.get(i).get(mapping.sourceColumn()), executionId);
                if (!Objects.equals(first.status(), next.status()) || !Objects.equals(first.value(), next.value())) {
                    if (property.id().equals(primaryKeyRef)) {
                        throw fail(409, "OBJECT_IDENTITY_CONFLICT",
                                "同一身份键对应的来源行在主键属性上互相矛盾，拒绝选择任意记录", property.id(), executionId);
                    }
                    throw fail(409, "PROPERTY_VALUE_CONFLICT",
                            "同一身份键对应的来源行在请求属性上互相矛盾，拒绝返回部分对象", property.id(), executionId);
                }
            }
        }
    }

    private static Object convert(RuntimeTypeResolver.PropertyDescriptor property, Object raw, String executionId) {
        String type = normalizeType(property.valueType());
        switch (type) {
            case "STRING", "TEXT", "ENUM" -> {
                if (!(raw instanceof String value)) {
                    throw fail(422, "PROPERTY_TYPE_MISMATCH", "来源值不能无损转换为 STRING / ENUM", property.id(), executionId);
                }
                if ("ENUM".equals(type) && (property.enumValues() == null || !property.enumValues().contains(value))) {
                    throw fail(422, "PROPERTY_TYPE_MISMATCH", "来源值不属于该属性允许的枚举集合", property.id(), executionId);
                }
                return value;
            }
            case "UUID" -> {
                String value = raw instanceof UUID uuid ? uuid.toString() : raw instanceof String text ? text : null;
                if (value == null || !UUID_TEXT.matcher(value).matches()) {
                    throw fail(422, "PROPERTY_TYPE_MISMATCH", "UUID 来源值不是标准小写 UUID 文本", property.id(), executionId);
                }
                return value;
            }
            case "INTEGER", "INT", "LONG" -> {
                String value = integralString(raw);
                if (value == null || !CANONICAL_INTEGER.matcher(value).matches()) {
                    throw fail(422, "PROPERTY_TYPE_MISMATCH", "INTEGER 来源值不能无损转换为规范整数", property.id(), executionId);
                }
                return value;
            }
            case "DECIMAL", "NUMBER", "NUMERIC" -> {
                if (!(raw instanceof BigDecimal decimal)) {
                    throw fail(422, "PROPERTY_TYPE_MISMATCH", "DECIMAL 来源值必须来自精确 PostgreSQL NUMERIC，禁止浮点往返", property.id(), executionId);
                }
                return decimal.toPlainString();
            }
            case "BOOLEAN", "BOOL" -> {
                if (!(raw instanceof Boolean value)) {
                    throw fail(422, "PROPERTY_TYPE_MISMATCH", "BOOLEAN 来源值不能无损转换", property.id(), executionId);
                }
                return value;
            }
            case "DATETIME", "TIMESTAMP" -> {
                if (raw instanceof OffsetDateTime value) return value.toInstant().toString();
                if (raw instanceof Timestamp value) return value.toInstant().toString();
                if (raw instanceof Instant value) return value.toString();
                throw fail(422, "PROPERTY_TYPE_MISMATCH", "DATETIME 必须带时区；不猜测无时区时间", property.id(), executionId);
            }
            default -> throw fail(422, "PROPERTY_TYPE_MISMATCH", "当前 Object Runtime 不支持该属性值类型", property.id(), executionId);
        }
    }

    private static String integralString(Object value) {
        if (value instanceof BigInteger integer) return integer.toString();
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            return String.valueOf(value);
        }
        if (value instanceof BigDecimal decimal) {
            try { return decimal.toBigIntegerExact().toString(); }
            catch (ArithmeticException ignored) { return null; }
        }
        return null;
    }

    private static boolean compatible(RuntimeTypeResolver.PropertyDescriptor property, DataBindingService.SourceColumn column) {
        String type = normalizeType(property.valueType());
        String data = column.dataType().toLowerCase(Locale.ROOT);
        String udt = column.udtName().toLowerCase(Locale.ROOT);
        return switch (type) {
            case "STRING", "TEXT" -> Set.of("text", "character varying").contains(data);
            case "UUID" -> Set.of("text", "character varying").contains(data) || "uuid".equals(udt);
            case "INTEGER", "INT", "LONG" -> Set.of("smallint", "integer", "bigint").contains(data);
            case "DECIMAL", "NUMBER", "NUMERIC" -> Set.of("numeric", "decimal").contains(data);
            case "BOOLEAN", "BOOL" -> "boolean".equals(data);
            case "DATETIME", "TIMESTAMP" -> "timestamp with time zone".equals(data);
            case "ENUM" -> Set.of("text", "character varying").contains(data)
                    && property.enumValues() != null && !property.enumValues().isEmpty();
            default -> false;
        };
    }

    private static String normalizeType(String value) {
        return value == null ? "" : value.trim().replace('-', '_').toUpperCase(Locale.ROOT);
    }

    private static void validateRequest(ObjectReadRequest request, String executionId) {
        if (request == null || request.bindingId() == null || request.bindingId().isBlank()
                || request.bindingRevision() == null || request.bindingRevision() < 1 || request.key() == null) {
            throw fail(400, "INVALID_OBJECT_REQUEST", "必须指定 bindingId、正整数 bindingRevision 与 key", "request", executionId);
        }
    }

    private static UUID parseUuid(String value, String field, String executionId) {
        try { return UUID.fromString(value); }
        catch (RuntimeException ex) { throw fail(400, "INVALID_OBJECT_REQUEST", field + " 必须是 UUID", field, executionId); }
    }

    private static ObjectRuntimeFailure fail(int http, String code, String message, String fieldPath, String executionId) {
        return new ObjectRuntimeFailure(http, code, message, fieldPath, executionId);
    }

    private static String qi(String identifier) {
        if (identifier == null || !IDENT.matcher(identifier).matches()) throw new IllegalArgumentException("Invalid source identifier");
        return "\"" + identifier + "\"";
    }

    private static String qt(String table) { return "\"public\"." + qi(table); }

    private static String sha256(byte[] bytes) {
        return io.ontoforge.common.ContentDigests.sha256(bytes);
    }

    private static void putNullable(ObjectNode object, String field, Integer value) {
        if (value == null) object.putNull(field);
        else object.put(field, value);
    }

    static boolean isTimeout(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof SQLException sql && "57014".equals(sql.getSQLState())) return true;
            current = current.getCause();
        }
        return false;
    }

}
