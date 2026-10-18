package io.ontoforge.boot.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.ontoforge.boot.security.B0AuthorizationService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static io.ontoforge.boot.model.NativeDraftService.DraftFailure;

/** Generic, snapshot-pinned, read-only source binding for M5. */
@Service
public class DataBindingService {
    public static final String SOURCE_ID = "local-pg-sandbox";
    public static final String DIGEST_PROFILE = "jcs-rfc8785-v1";
    private static final int PREVIEW_ROWS = 20;
    private static final int MAX_VALIDATION_ROWS = 10_000;
    private static final Pattern IDENT = Pattern.compile("[a-z][a-z0-9_]{0,62}");

    private final JdbcTemplate appJdbc;
    private final ObjectProvider<JdbcTemplate> sourceProvider;
    private final RuntimeTypeResolver typeResolver;
    private final B0AuthorizationService authorization;
    private final ObjectMapper mapper;
    private final List<String> allowedTables;

    public DataBindingService(JdbcTemplate appJdbc,
            @Qualifier("bindingSourceJdbcTemplate") ObjectProvider<JdbcTemplate> sourceProvider,
            RuntimeTypeResolver typeResolver, B0AuthorizationService authorization,
            ObjectMapper mapper,
            @Value("${ontoforge.binding.source.allowed-tables:m5_supplier_source}") String allowedTables) {
        this.appJdbc = appJdbc;
        this.sourceProvider = sourceProvider;
        this.typeResolver = typeResolver;
        this.authorization = authorization;
        this.mapper = mapper;
        this.allowedTables = Arrays.stream(allowedTables.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).distinct().toList();
        if (this.allowedTables.stream().anyMatch(t -> !IDENT.matcher(t).matches())) {
            throw new IllegalStateException("M5 allowed source table names must be simple identifiers");
        }
    }

    public static final class BindingFailure extends RuntimeException {
        public final int http;
        public final String code;
        public final String fieldPath;
        public BindingFailure(int http, String code, String message, String fieldPath) {
            super(message);
            this.http = http;
            this.code = code;
            this.fieldPath = fieldPath;
        }
    }

    public record SourceColumn(String name, String dataType, String udtName, boolean nullable,
                               int ordinalPosition, boolean primaryKey, boolean uniqueKey) {}
    public record SourceTable(String sourceId, String schema, String table,
                              List<SourceColumn> columns, String schemaFingerprint) {}
    public record FieldMapping(String propertyRef, String sourceColumn, String authority) {}
    public record BindingRequest(String snapshotId, String draftId, Long draftRevision,
                                 String objectTypeId, String sourceId, String schema, String table, String sourceKey,
                                 List<FieldMapping> fieldMappings, String expectedSchemaFingerprint,
                                 long expectedRevision) {}
    public record ValidationIssue(String code, String message, String fieldPath) {}
    public record ValidationResult(String status, Instant checkedAt, int checkedRows,
                                   String schemaFingerprint, List<ValidationIssue> issues) {}
    public record PreviewResult(String packageId, String snapshotId, String draftId, Long draftRevision,
                                String objectTypeId, String objectTypeLabel, String payloadSha256,
                                SourceTable source, List<Map<String, Object>> sourceRows,
                                List<Map<String, Object>> mappedRows) {}
    public record BindingSummary(String bindingId, String packageId, String objectTypeId,
                                 long currentRevision, String sourceId, String sourceSchema,
                                 String sourceTable, String validationStatus, Instant checkedAt,
                                 int checkedRows, String digest) {}
    public record RevisionSummary(long revision, String digest, String validationStatus,
                                  Instant checkedAt, int checkedRows, Instant createdAt) {}
    public record BindingDetail(String bindingId, long revision, String packageId,
                                String objectTypeId, String snapshotId, String payloadSha256,
                                String sourceId, String sourceSchema, String sourceTable,
                                String sourceSchemaFingerprint, String sourceKey,
                                String identityDomainId, List<FieldMapping> fieldMappings,
                                String digest, String digestProfile, String validationStatus,
                                Instant checkedAt, int checkedRows, List<ValidationIssue> issues,
                                boolean historical, Instant createdAt) {}

    private record KeyColumn(String constraintName, String constraintType,
                             String columnName, int ordinalPosition) {}
    private record MappingContext(RuntimeTypeResolver.TypeDescriptor type, SourceTable source,
                                  List<FieldMapping> mappings, Map<String, RuntimeTypeResolver.PropertyDescriptor> properties) {}
    private record Head(UUID bindingId, String packageId, String objectTypeId,
                        UUID identityDomainId, long currentRevision) {}
    private record Revision(UUID bindingId, long revision, UUID snapshotId, String packageId,
                            String objectTypeId, String payloadSha256, String sourceId,
                            String sourceSchema, String sourceTable, String schemaFingerprint,
                            String sourceKey, UUID identityDomainId, String mappingsJson,
                            String digest, String digestProfile, String validationStatus,
                            Instant checkedAt, int checkedRows, String issuesJson, Instant createdAt) {}

    public List<SourceTable> listTables(String packageId, Authentication auth) {
        authorization.requirePackage(auth, packageId, B0AuthorizationService.Operation.READ_DRAFT);
        JdbcTemplate source = sourceJdbc();
        try {
            if (allowedTables.isEmpty()) return List.of();
            String placeholders = String.join(",", java.util.Collections.nCopies(allowedTables.size(), "?"));
            List<String> found = source.query(
                    "SELECT table_name FROM information_schema.tables WHERE table_schema='public' "
                    + "AND table_type='BASE TABLE' AND table_name IN (" + placeholders + ") ORDER BY table_name",
                    (rs, rowNum) -> rs.getString(1), allowedTables.toArray());
            List<SourceTable> tables = new ArrayList<>();
            for (String table : found) tables.add(sourceTable(SOURCE_ID, "public", table, source));
            return List.copyOf(tables);
        } catch (DataAccessException ex) {
            throw sourceFailure(ex);
        }
    }

    public PreviewResult preview(BindingRequest request, Authentication auth) {
        MappingContext context = context(request, auth, false, true);
        List<Map<String, Object>> rows = readRows(context, PREVIEW_ROWS);
        List<Map<String, Object>> mapped = mapRows(context, rows);
        return new PreviewResult(context.type().packageId(), request.snapshotId(), request.draftId(), request.draftRevision(),
                request.objectTypeId(), context.type().label(), context.type().payloadSha256(), context.source(), rows, mapped);
    }

    public ValidationResult validate(BindingRequest request, Authentication auth) {
        MappingContext context = context(request, auth, true, true);
        List<Map<String, Object>> rows = readRows(context, MAX_VALIDATION_ROWS + 1);
        if (rows.size() > MAX_VALIDATION_ROWS) {
            throw fail(429, "QUERY_BUDGET_EXCEEDED", "校验超过 10000 行预算；没有生成通过结论", "validation");
        }
        List<ValidationIssue> issues = inspectRows(context, rows);
        return new ValidationResult(issues.isEmpty() ? "VALID" : "INVALID", Instant.now(),
                rows.size(), context.source().schemaFingerprint(), List.copyOf(issues));
    }

    @Transactional
    public BindingDetail create(BindingRequest request, Authentication auth) {
        if (request.expectedRevision() != 0) {
            throw fail(400, "MALFORMED_REQUEST", "创建绑定时 expectedRevision 必须为 0", "expectedRevision");
        }
        MappingContext context = context(request, auth, true, false);
        authorization.requirePackage(auth, context.type().packageId(), B0AuthorizationService.Operation.WRITE_DRAFT);
        ValidationResult validation = validateContext(context);
        requireValid(validation);
        UUID bindingId = UUID.randomUUID();
        UUID identityDomainId = UUID.randomUUID();
        Instant now = validation.checkedAt();
        try {
            appJdbc.update("""
                    INSERT INTO runtime_binding(binding_id, package_id, object_type_id, identity_domain_id,
                                                 current_revision, created_at, updated_at)
                    VALUES (?, ?, ?, ?, 0, ?, ?)
                    """, bindingId, context.type().packageId(), context.type().objectTypeId(),
                    identityDomainId, Timestamp.from(now), Timestamp.from(now));
        } catch (DataAccessException ex) {
            if (ex.getMessage() != null && ex.getMessage().toLowerCase(Locale.ROOT).contains("uq_runtime_binding_package_type")) {
                throw fail(409, "BINDING_REVISION_CONFLICT", "该 ObjectType 已有当前绑定，请打开现有绑定创建新 revision", "objectTypeId");
            }
            throw ex;
        }
        insertRevision(bindingId, 1, identityDomainId, request, context, validation);
        appJdbc.update("UPDATE runtime_binding SET current_revision=1, updated_at=? WHERE binding_id=?",
                Timestamp.from(now), bindingId);
        return detail(bindingId, 1, false);
    }

    @Transactional
    public BindingDetail update(UUID bindingId, BindingRequest request, Authentication auth) {
        Head head = head(bindingId);
        authorization.requirePackage(auth, head.packageId(), B0AuthorizationService.Operation.WRITE_DRAFT);
        if (request.expectedRevision() < 1) {
            throw fail(400, "MALFORMED_REQUEST", "更新绑定时 expectedRevision 必须为正整数", "expectedRevision");
        }
        Head locked = appJdbc.queryForObject("""
                SELECT binding_id, package_id, object_type_id, identity_domain_id, current_revision
                  FROM runtime_binding WHERE binding_id=? FOR UPDATE
                """, (rs, rowNum) -> new Head(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                    rs.getObject(4, UUID.class), rs.getLong(5)), bindingId);
        if (locked.currentRevision() != request.expectedRevision()) {
            throw fail(409, "BINDING_REVISION_CONFLICT", "绑定版本已被其他编辑更新；当前编辑未覆盖服务端版本", "expectedRevision");
        }
        MappingContext context = context(request, auth, true, false);
        if (!locked.packageId().equals(context.type().packageId())
                || !locked.objectTypeId().equals(context.type().objectTypeId())) {
            throw fail(422, "INVALID_TYPE_REFERENCE", "新 revision 必须继续引用同一模型包和 ObjectType", "objectTypeId");
        }
        ValidationResult validation = validateContext(context);
        requireValid(validation);
        String oldSourceKey = appJdbc.queryForObject("""
                SELECT source_key FROM runtime_binding_revision WHERE binding_id=? AND revision=?
                """, String.class, bindingId, locked.currentRevision());
        UUID identityDomainId = Objects.equals(oldSourceKey, request.sourceKey())
                ? locked.identityDomainId() : UUID.randomUUID();
        long revision = locked.currentRevision() + 1;
        insertRevision(bindingId, revision, identityDomainId, request, context, validation);
        int changed = appJdbc.update("""
                UPDATE runtime_binding SET current_revision=?, identity_domain_id=?, updated_at=?
                 WHERE binding_id=? AND current_revision=?
                """, revision, identityDomainId, Timestamp.from(validation.checkedAt()),
                bindingId, request.expectedRevision());
        if (changed != 1) {
            throw fail(409, "BINDING_REVISION_CONFLICT", "绑定版本已发生变化；当前编辑未覆盖服务端版本", "expectedRevision");
        }
        return detail(bindingId, revision, false);
    }

    public List<BindingSummary> listBindings(String packageId, Authentication auth) {
        authorization.requirePackage(auth, packageId, B0AuthorizationService.Operation.READ_DRAFT);
        return appJdbc.query("""
                SELECT b.binding_id, b.package_id, b.object_type_id, b.current_revision,
                       r.source_id, r.source_schema, r.source_table, r.validation_status,
                       r.checked_at, r.checked_rows, r.digest
                  FROM runtime_binding b
                  JOIN runtime_binding_revision r
                    ON r.binding_id=b.binding_id AND r.revision=b.current_revision
                 WHERE b.package_id=?
                 ORDER BY b.object_type_id, b.binding_id
                """, (rs, rowNum) -> new BindingSummary(
                    rs.getObject("binding_id", UUID.class).toString(), rs.getString("package_id"),
                    rs.getString("object_type_id"), rs.getLong("current_revision"), rs.getString("source_id"),
                    rs.getString("source_schema"), rs.getString("source_table"), rs.getString("validation_status"),
                    instant(rs.getTimestamp("checked_at")), rs.getInt("checked_rows"), rs.getString("digest")),
                packageId);
    }

    public List<RevisionSummary> listRevisions(UUID bindingId, Authentication auth) {
        Head head = head(bindingId);
        authorization.requirePackage(auth, head.packageId(), B0AuthorizationService.Operation.READ_DRAFT);
        return appJdbc.query("SELECT revision, digest, validation_status, checked_at, checked_rows, created_at "
                + "FROM runtime_binding_revision WHERE binding_id=? ORDER BY revision DESC",
                (rs, rowNum) -> new RevisionSummary(rs.getLong("revision"), rs.getString("digest"),
                    rs.getString("validation_status"), instant(rs.getTimestamp("checked_at")),
                    rs.getInt("checked_rows"), instant(rs.getTimestamp("created_at"))), bindingId);
    }

    public BindingDetail getBinding(UUID bindingId, Long requestedRevision, Authentication auth) {
        Head head = head(bindingId);
        authorization.requirePackage(auth, head.packageId(), B0AuthorizationService.Operation.READ_DRAFT);
        long revision = requestedRevision == null ? head.currentRevision() : requestedRevision;
        if (revision < 1) throw fail(400, "MALFORMED_REQUEST", "revision 必须为正整数", "revision");
        return detail(bindingId, revision, revision != head.currentRevision());
    }

    private MappingContext context(BindingRequest request, Authentication auth,
                                   boolean requireFingerprint, boolean allowDraftPreview) {
        validateRequest(request);
        RuntimeTypeResolver.TypeDescriptor type;
        if (request.snapshotId() != null && !request.snapshotId().isBlank()) {
            UUID snapshotId = UUID.fromString(request.snapshotId());
            authorization.requireSnapshot(auth, snapshotId, B0AuthorizationService.Operation.READ_SNAPSHOT);
            type = typeResolver.getSnapshotType(snapshotId, request.objectTypeId());
            if (type.payloadSha256() == null || !type.payloadSha256().matches("[0-9a-f]{64}")) {
                throw fail(422, "INVALID_TYPE_REFERENCE", "服务端无法核验所选快照摘要", "snapshotId");
            }
        } else {
            if (!allowDraftPreview) {
                throw fail(422, "INVALID_TYPE_REFERENCE", "持久绑定只接受 Local Snapshot；请先冻结已校验草稿", "draftId");
            }
            UUID draftId = UUID.fromString(request.draftId());
            authorization.requireDraft(auth, draftId, B0AuthorizationService.Operation.READ_DRAFT);
            type = typeResolver.getDraftType(draftId, request.draftRevision(), request.objectTypeId());
        }
        authorization.requirePackage(auth, type.packageId(), B0AuthorizationService.Operation.READ_DRAFT);
        SourceTable table = sourceTable(request.sourceId(), request.schema(), request.table(), sourceJdbc());
        if (requireFingerprint) {
            if (request.expectedSchemaFingerprint() == null || request.expectedSchemaFingerprint().isBlank()) {
                throw fail(400, "MALFORMED_REQUEST", "请先预览来源并提交来源结构指纹", "expectedSchemaFingerprint");
            }
            if (!request.expectedSchemaFingerprint().equals(table.schemaFingerprint())) {
                throw fail(409, "BINDING_SCHEMA_DRIFT", "来源表结构已变化；请重新发现来源并重新预览映射", "expectedSchemaFingerprint");
            }
        }
        Map<String, RuntimeTypeResolver.PropertyDescriptor> properties = new LinkedHashMap<>();
        for (RuntimeTypeResolver.PropertyDescriptor property : type.properties()) properties.put(property.id(), property);
        List<FieldMapping> mappings = request.fieldMappings() == null ? List.of() : request.fieldMappings();
        if (mappings.size() > 100) throw fail(422, "UNSUPPORTED_MAPPING_TYPE", "单个 ObjectType 最多支持 100 个属性映射", "fieldMappings");
        Set<String> propertyRefs = new HashSet<>();
        for (FieldMapping mapping : mappings) {
            if (mapping == null || mapping.propertyRef() == null || mapping.sourceColumn() == null
                    || !propertyRefs.add(mapping.propertyRef())) {
                throw fail(422, "UNSUPPORTED_MAPPING_TYPE", "每个对象属性只能映射一次", "fieldMappings");
            }
            if (!"AUTHORITATIVE".equals(mapping.authority())) {
                throw fail(422, "UNSUPPORTED_MAPPING_TYPE", "M5 仅支持 AUTHORITATIVE 单源权威映射", "fieldMappings.authority");
            }
            RuntimeTypeResolver.PropertyDescriptor property = properties.get(mapping.propertyRef());
            if (property == null) throw fail(422, "INVALID_TYPE_REFERENCE", "映射引用不属于所选 ObjectType", "fieldMappings.propertyRef");
            SourceColumn column = column(table, mapping.sourceColumn());
            if (!compatible(property, column)) {
                throw fail(422, "UNSUPPORTED_MAPPING_TYPE", "对象属性类型与来源列类型不兼容", "fieldMappings." + mapping.propertyRef());
            }
        }
        RuntimeTypeResolver.PropertyDescriptor pk = properties.get(type.primaryKeyRef());
        FieldMapping keyMapping = mappings.stream().filter(m -> type.primaryKeyRef().equals(m.propertyRef())).findFirst().orElse(null);
        if (pk == null || keyMapping == null || !request.sourceKey().equals(keyMapping.sourceColumn())) {
            throw fail(422, "UNSUPPORTED_MAPPING_TYPE", "来源键必须映射到 ObjectType 的 primaryKeyRef", "sourceKey");
        }
        long nonKeyCount = mappings.stream().filter(m -> !type.primaryKeyRef().equals(m.propertyRef())).count();
        if (nonKeyCount < 2) throw fail(422, "UNSUPPORTED_MAPPING_TYPE", "至少需要两个非主键属性映射", "fieldMappings");
        for (RuntimeTypeResolver.PropertyDescriptor property : type.properties()) {
            if (property.required() && !propertyRefs.contains(property.id())) {
                throw fail(422, "UNSUPPORTED_MAPPING_TYPE", "必填属性必须有权威来源映射", "fieldMappings." + property.id());
            }
        }
        return new MappingContext(type, table, mappings.stream()
                .sorted(java.util.Comparator.comparing(FieldMapping::propertyRef)).toList(), Map.copyOf(properties));
    }

    private ValidationResult validateContext(MappingContext context) {
        List<Map<String, Object>> rows = readRows(context, MAX_VALIDATION_ROWS + 1);
        if (rows.size() > MAX_VALIDATION_ROWS) {
            throw fail(429, "QUERY_BUDGET_EXCEEDED", "校验超过 10000 行预算；没有生成通过结论", "validation");
        }
        List<ValidationIssue> issues = inspectRows(context, rows);
        return new ValidationResult(issues.isEmpty() ? "VALID" : "INVALID", Instant.now(),
                rows.size(), context.source().schemaFingerprint(), List.copyOf(issues));
    }

    private List<ValidationIssue> inspectRows(MappingContext context, List<Map<String, Object>> rows) {
        List<ValidationIssue> issues = new ArrayList<>();
        if (rows.isEmpty()) {
            issues.add(new ValidationIssue("NO_SOURCE_ROWS", "来源表没有可用于校验的记录，不能将空样本判定为通过", "source"));
            return issues;
        }
        Set<String> seen = new HashSet<>();
        for (Map<String, Object> row : rows) {
            Object key = row.get(contextSourceKey(context));
            if (key == null || String.valueOf(key).isBlank()) {
                issues.add(new ValidationIssue("IDENTITY_CONFLICT", "来源身份键存在空值", "sourceKey"));
                break;
            }
            if (!seen.add(String.valueOf(key))) {
                issues.add(new ValidationIssue("IDENTITY_CONFLICT", "来源身份键存在重复值", "sourceKey"));
                break;
            }
            for (FieldMapping mapping : context.mappings()) {
                RuntimeTypeResolver.PropertyDescriptor property = context.properties().get(mapping.propertyRef());
                Object value = row.get(mapping.sourceColumn());
                if (value != null && "DECIMAL".equals(normalizeType(property.valueType()))
                        && Set.of("NaN", "Infinity", "-Infinity", "inf", "-inf").contains(String.valueOf(value))) {
                    issues.add(new ValidationIssue("DECIMAL_VALUE_INVALID", "DECIMAL 来源值必须是有限精确数值", mapping.propertyRef()));
                    break;
                }
                if (property.required() && value == null) {
                    issues.add(new ValidationIssue("REQUIRED_PROPERTY_MISSING", "必填属性存在空值", mapping.propertyRef()));
                    break;
                }
                if (value != null && "ENUM".equals(normalizeType(property.valueType()))
                        && property.enumValues() != null && !property.enumValues().contains(String.valueOf(value))) {
                    issues.add(new ValidationIssue("ENUM_VALUE_OUT_OF_RANGE", "来源值不属于对象属性允许的枚举集合", mapping.propertyRef()));
                    break;
                }
            }
            if (!issues.isEmpty()) break;
        }
        return issues;
    }

    private String contextSourceKey(MappingContext context) {
        return context.mappings().stream()
                .filter(m -> context.type().primaryKeyRef().equals(m.propertyRef()))
                .map(FieldMapping::sourceColumn).findFirst().orElse("");
    }

    private List<Map<String, Object>> readRows(MappingContext context, int limit) {
        JdbcTemplate source = sourceJdbc();
        LinkedHashSet<String> columns = new LinkedHashSet<>();
        columns.add(contextSourceKey(context));
        context.mappings().forEach(m -> columns.add(m.sourceColumn()));
        String selected = String.join(", ", columns.stream().map(DataBindingService::qi).toList());
        String sql = "SELECT " + selected + " FROM " + qt(context.source().table()) + " LIMIT " + limit;
        try {
            return source.query(sql, (rs, rowNum) -> {
                Map<String, Object> row = new LinkedHashMap<>();
                for (String column : columns) row.put(column, safeValue(rs.getObject(column)));
                return row;
            });
        } catch (QueryTimeoutException ex) {
            throw fail(503, "SOURCE_TIMEOUT", "来源查询超时；请保留当前编辑并稍后重试", "source");
        } catch (DataAccessException ex) {
            throw sourceFailure(ex);
        }
    }

    private List<Map<String, Object>> mapRows(MappingContext context, List<Map<String, Object>> rows) {
        List<Map<String, Object>> mapped = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            for (FieldMapping mapping : context.mappings()) item.put(mapping.propertyRef(), row.get(mapping.sourceColumn()));
            mapped.add(item);
        }
        return List.copyOf(mapped);
    }

    private SourceTable sourceTable(String sourceId, String schema, String table, JdbcTemplate source) {
        if (!SOURCE_ID.equals(sourceId)) throw fail(403, "SOURCE_NOT_ALLOWED", "来源不在服务端允许列表中", "sourceId");
        if (!"public".equals(schema) || table == null || !IDENT.matcher(table).matches()
                || !allowedTables.contains(table)) {
            throw fail(403, "SOURCE_NOT_ALLOWED", "只能访问服务端登记的 public BASE TABLE", "table");
        }
        try {
            List<SourceColumn> columns = source.query("""
                    SELECT c.column_name, c.data_type, c.udt_name, c.is_nullable, c.ordinal_position,
                           EXISTS (
                             SELECT 1 FROM information_schema.key_column_usage k
                             JOIN information_schema.table_constraints t
                               ON t.constraint_catalog=k.constraint_catalog
                              AND t.constraint_schema=k.constraint_schema
                              AND t.constraint_name=k.constraint_name
                              AND t.table_schema=k.table_schema AND t.table_name=k.table_name
                            WHERE k.table_schema=c.table_schema AND k.table_name=c.table_name
                              AND k.column_name=c.column_name AND t.constraint_type='PRIMARY KEY'
                              AND (SELECT count(*) FROM information_schema.key_column_usage k2
                                    WHERE k2.constraint_catalog=k.constraint_catalog
                                      AND k2.constraint_schema=k.constraint_schema
                                      AND k2.constraint_name=k.constraint_name
                                      AND k2.table_schema=k.table_schema AND k2.table_name=k.table_name)=1
                           ) AS is_primary,
                           EXISTS (
                             SELECT 1 FROM information_schema.key_column_usage k
                             JOIN information_schema.table_constraints t
                               ON t.constraint_catalog=k.constraint_catalog
                              AND t.constraint_schema=k.constraint_schema
                              AND t.constraint_name=k.constraint_name
                              AND t.table_schema=k.table_schema AND t.table_name=k.table_name
                            WHERE k.table_schema=c.table_schema AND k.table_name=c.table_name
                              AND k.column_name=c.column_name AND t.constraint_type='UNIQUE'
                              AND (SELECT count(*) FROM information_schema.key_column_usage k2
                                    WHERE k2.constraint_catalog=k.constraint_catalog
                                      AND k2.constraint_schema=k.constraint_schema
                                      AND k2.constraint_name=k.constraint_name
                                      AND k2.table_schema=k.table_schema AND k2.table_name=k.table_name)=1
                           ) AS is_unique
                      FROM information_schema.columns c
                     WHERE c.table_schema=? AND c.table_name=?
                     ORDER BY c.ordinal_position
                    """, (rs, rowNum) -> new SourceColumn(rs.getString("column_name"), rs.getString("data_type"),
                    rs.getString("udt_name"), "YES".equals(rs.getString("is_nullable")),
                    rs.getInt("ordinal_position"), rs.getBoolean("is_primary"), rs.getBoolean("is_unique")),
                    schema, table);
            if (columns.isEmpty()) throw fail(404, "SOURCE_TABLE_NOT_FOUND", "允许列表中的来源表不存在或不可见", "table");
            List<KeyColumn> keys = source.query("""
                    SELECT tc.constraint_name, tc.constraint_type, kcu.column_name, kcu.ordinal_position
                      FROM information_schema.table_constraints tc
                      JOIN information_schema.key_column_usage kcu
                        ON kcu.constraint_catalog=tc.constraint_catalog
                       AND kcu.constraint_schema=tc.constraint_schema
                       AND kcu.constraint_name=tc.constraint_name
                       AND kcu.table_schema=tc.table_schema AND kcu.table_name=tc.table_name
                     WHERE tc.table_schema=? AND tc.table_name=?
                       AND tc.constraint_type IN ('PRIMARY KEY','UNIQUE')
                     ORDER BY tc.constraint_name, kcu.ordinal_position
                    """, (rs, rowNum) -> new KeyColumn(rs.getString(1), rs.getString(2),
                    rs.getString(3), rs.getInt(4)), schema, table);
            return new SourceTable(sourceId, schema, table, columns, fingerprint(sourceId, schema, table, columns, keys));
        } catch (BindingFailure ex) {
            throw ex;
        } catch (DataAccessException ex) {
            throw sourceFailure(ex);
        }
    }

    private String fingerprint(String sourceId, String schema, String table,
                               List<SourceColumn> columns, List<KeyColumn> keys) {
        ObjectNode doc = mapper.createObjectNode();
        doc.put("profile", "pg-source-schema-v1");
        doc.put("sourceId", sourceId);
        doc.put("schema", schema);
        doc.put("table", table);
        ArrayNode columnArray = doc.putArray("columns");
        for (SourceColumn column : columns) {
            ObjectNode item = columnArray.addObject();
            item.put("name", column.name());
            item.put("dataType", column.dataType());
            item.put("udtName", column.udtName());
            item.put("nullable", column.nullable());
            item.put("ordinalPosition", column.ordinalPosition());
            item.put("primaryKey", column.primaryKey());
            item.put("uniqueKey", column.uniqueKey());
        }
        ArrayNode constraintArray = doc.putArray("constraints");
        for (KeyColumn key : keys) {
            ObjectNode item = constraintArray.addObject();
            item.put("name", key.constraintName());
            item.put("type", key.constraintType());
            item.put("column", key.columnName());
            item.put("ordinalPosition", key.ordinalPosition());
        }
        return sha256(NativeSnapshotService.canonicalize(doc.toString()));
    }

    private void insertRevision(UUID bindingId, long revision, UUID identityDomainId, BindingRequest request,
                                MappingContext context, ValidationResult validation) {
        UUID snapshotId = UUID.fromString(request.snapshotId());
        String mappingsJson = json(context.mappings());
        String issuesJson = json(validation.issues());
        String digest = revisionDigest(bindingId, revision, identityDomainId, request, context, validation);
        appJdbc.update("""
                INSERT INTO runtime_binding_revision(
                    binding_id, revision, snapshot_id, payload_sha256, source_id, source_schema, source_table,
                    source_schema_fingerprint, source_key, identity_domain_id, field_mappings, digest,
                    digest_profile, validation_status, checked_at, checked_rows, validation_issues, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?::jsonb, ?)
                """, bindingId, revision, snapshotId, context.type().payloadSha256(), SOURCE_ID, "public",
                context.source().table(), context.source().schemaFingerprint(), request.sourceKey(),
                identityDomainId, mappingsJson, digest, DIGEST_PROFILE, validation.status(),
                Timestamp.from(validation.checkedAt()), validation.checkedRows(), issuesJson,
                Timestamp.from(validation.checkedAt()));
    }

    private String revisionDigest(UUID bindingId, long revision, UUID identityDomainId,
                                  BindingRequest request, MappingContext context, ValidationResult validation) {
        ObjectNode doc = mapper.createObjectNode();
        doc.put("contract", "local-binding-v1");
        doc.put("bindingId", bindingId.toString());
        doc.put("revision", revision);
        doc.put("packageId", context.type().packageId());
        doc.put("objectTypeId", context.type().objectTypeId());
        doc.put("snapshotId", request.snapshotId());
        doc.put("payloadSha256", context.type().payloadSha256());
        doc.put("sourceId", SOURCE_ID);
        doc.put("schema", "public");
        doc.put("table", context.source().table());
        doc.put("sourceSchemaFingerprint", context.source().schemaFingerprint());
        doc.put("sourceKey", request.sourceKey());
        doc.put("identityDomainId", identityDomainId.toString());
        ArrayNode mappings = doc.putArray("fieldMappings");
        context.mappings().stream().sorted(java.util.Comparator.comparing(FieldMapping::propertyRef)).forEach(mapping -> {
            ObjectNode item = mappings.addObject();
            item.put("propertyRef", mapping.propertyRef());
            item.put("sourceColumn", mapping.sourceColumn());
            item.put("authority", mapping.authority());
        });
        doc.put("refreshMode", "ON_DEMAND");
        doc.put("identity", "SOURCE_KEY");
        doc.put("validationStatus", validation.status());
        doc.put("checkedAt", validation.checkedAt().toString());
        doc.put("checkedRows", validation.checkedRows());
        doc.set("validationIssues", mapper.valueToTree(validation.issues()));
        return sha256(NativeSnapshotService.canonicalize(doc.toString()));
    }

    private BindingDetail detail(UUID bindingId, long revision, boolean historical) {
        Revision row;
        try {
            row = appJdbc.queryForObject("""
                    SELECT r.binding_id, r.revision, r.snapshot_id, b.package_id, b.object_type_id,
                           r.payload_sha256, r.source_id, r.source_schema, r.source_table,
                           r.source_schema_fingerprint, r.source_key, r.identity_domain_id,
                           r.field_mappings::text, r.digest, r.digest_profile, r.validation_status,
                           r.checked_at, r.checked_rows, r.validation_issues::text, r.created_at
                      FROM runtime_binding_revision r JOIN runtime_binding b ON b.binding_id=r.binding_id
                     WHERE r.binding_id=? AND r.revision=?
                    """, (rs, rowNum) -> new Revision(
                        rs.getObject("binding_id", UUID.class), rs.getLong("revision"),
                        rs.getObject("snapshot_id", UUID.class), rs.getString("package_id"),
                        rs.getString("object_type_id"), rs.getString("payload_sha256"), rs.getString("source_id"),
                        rs.getString("source_schema"), rs.getString("source_table"),
                        rs.getString("source_schema_fingerprint"), rs.getString("source_key"),
                        rs.getObject("identity_domain_id", UUID.class), rs.getString("field_mappings"),
                        rs.getString("digest"), rs.getString("digest_profile"), rs.getString("validation_status"),
                        instant(rs.getTimestamp("checked_at")), rs.getInt("checked_rows"),
                        rs.getString("validation_issues"), instant(rs.getTimestamp("created_at"))),
                    bindingId, revision);
        } catch (EmptyResultDataAccessException ex) {
            throw fail(404, "BINDING_NOT_FOUND", "绑定或指定 revision 不存在", "bindingId");
        }
        return new BindingDetail(row.bindingId().toString(), row.revision(), row.packageId(), row.objectTypeId(),
                row.snapshotId().toString(), row.payloadSha256(), row.sourceId(), row.sourceSchema(),
                row.sourceTable(), row.schemaFingerprint(), row.sourceKey(), row.identityDomainId().toString(),
                readMappings(row.mappingsJson()), row.digest(), row.digestProfile(), row.validationStatus(),
                row.checkedAt(), row.checkedRows(), readIssues(row.issuesJson()), historical, row.createdAt());
    }

    private Head head(UUID bindingId) {
        try {
            return appJdbc.queryForObject("""
                    SELECT binding_id, package_id, object_type_id, identity_domain_id, current_revision
                      FROM runtime_binding WHERE binding_id=?
                    """, (rs, rowNum) -> new Head(rs.getObject(1, UUID.class), rs.getString(2),
                    rs.getString(3), rs.getObject(4, UUID.class), rs.getLong(5)), bindingId);
        } catch (EmptyResultDataAccessException ex) {
            throw fail(404, "BINDING_NOT_FOUND", "绑定不存在", "bindingId");
        }
    }

    private List<FieldMapping> readMappings(String source) {
        try {
            return mapper.readValue(source,
                    mapper.getTypeFactory().constructCollectionType(List.class, FieldMapping.class));
        } catch (JsonProcessingException ex) {
            throw fail(500, "BINDING_STORAGE_INVALID", "绑定版本内容无法读取", "fieldMappings");
        }
    }

    private List<ValidationIssue> readIssues(String source) {
        try {
            return mapper.readValue(source,
                    mapper.getTypeFactory().constructCollectionType(List.class, ValidationIssue.class));
        } catch (JsonProcessingException ex) {
            throw fail(500, "BINDING_STORAGE_INVALID", "绑定校验记录无法读取", "validationIssues");
        }
    }

    private void requireValid(ValidationResult validation) {
        if ("VALID".equals(validation.status())) return;
        ValidationIssue issue = validation.issues().getFirst();
        int status = "IDENTITY_CONFLICT".equals(issue.code()) ? 422 : 422;
        throw fail(status, issue.code(), issue.message(), issue.fieldPath());
    }

    private void validateRequest(BindingRequest request) {
        if (request == null || request.objectTypeId() == null || request.sourceId() == null
                || request.schema() == null || request.table() == null
                || request.sourceKey() == null || request.fieldMappings() == null) {
            throw fail(400, "MALFORMED_REQUEST", "绑定请求缺少必需字段", "request");
        }
        boolean hasSnapshot = request.snapshotId() != null && !request.snapshotId().isBlank();
        boolean hasDraft = request.draftId() != null && !request.draftId().isBlank();
        if (hasSnapshot == hasDraft) {
            throw fail(400, "INVALID_TYPE_REFERENCE", "必须且只能选择一个明确的 Local Snapshot 或 Validated Draft 引用", "typeRef");
        }
        try {
            if (hasSnapshot) UUID.fromString(request.snapshotId());
            if (hasDraft) {
                UUID.fromString(request.draftId());
                if (request.draftRevision() == null || request.draftRevision() < 1) {
                    throw fail(400, "MALFORMED_REQUEST", "Validated Draft 必须指定正整数 revision", "draftRevision");
                }
            }
        } catch (IllegalArgumentException ex) {
            throw fail(400, "MALFORMED_REQUEST", "类型来源 ID 格式不合法", "typeRef");
        }
        if (!IDENT.matcher(request.sourceKey()).matches()) {
            throw fail(400, "MALFORMED_REQUEST", "sourceKey 必须是来源列标识符", "sourceKey");
        }
    }

    private JdbcTemplate sourceJdbc() {
        JdbcTemplate source = sourceProvider.getIfAvailable();
        if (source == null) {
            throw fail(503, "SOURCE_UNAVAILABLE",
                    "尚未配置独立的 M5 来源只读连接；请按本机部署说明配置专用数据库账号", "sourceId");
        }
        return source;
    }

    private static boolean compatible(RuntimeTypeResolver.PropertyDescriptor property, SourceColumn column) {
        String type = normalizeType(property.valueType());
        String data = column.dataType().toLowerCase(Locale.ROOT);
        String udt = column.udtName().toLowerCase(Locale.ROOT);
        return switch (type) {
            case "STRING", "TEXT", "UUID" -> Set.of("text", "character varying", "character", "name").contains(data) || "uuid".equals(udt);
            case "INTEGER", "INT", "LONG" -> Set.of("smallint", "integer", "bigint").contains(data);
            case "DECIMAL", "NUMBER", "NUMERIC" -> Set.of("numeric", "decimal", "real", "double precision").contains(data);
            case "BOOLEAN", "BOOL" -> "boolean".equals(data);
            case "DATETIME", "TIMESTAMP" -> "timestamp with time zone".equals(data);
            case "ENUM" -> Set.of("text", "character varying", "character", "USER-DEFINED".toLowerCase(Locale.ROOT)).contains(data)
                    && property.enumValues() != null && !property.enumValues().isEmpty()
                    && !udt.equals("json") && !udt.equals("jsonb");
            default -> false;
        };
    }

    private static String normalizeType(String value) {
        return value == null ? "" : value.trim().replace('-', '_').toUpperCase(Locale.ROOT);
    }

    private static SourceColumn column(SourceTable table, String name) {
        return table.columns().stream().filter(c -> c.name().equals(name)).findFirst()
                .orElseThrow(() -> fail(422, "UNSUPPORTED_MAPPING_TYPE", "映射引用了来源中不存在的列", "sourceColumn"));
    }

    private static String qi(String identifier) {
        if (identifier == null || !IDENT.matcher(identifier).matches()) {
            throw fail(400, "MALFORMED_REQUEST", "标识符格式不合法", "source");
        }
        return "\"" + identifier + "\"";
    }

    private static String qt(String table) { return "public." + qi(table); }

    private static Object safeValue(Object value) {
        if (value instanceof java.math.BigDecimal decimal) return decimal.toPlainString();
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) return value;
        return String.valueOf(value);
    }

    private static Instant instant(Timestamp timestamp) { return timestamp == null ? null : timestamp.toInstant(); }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException ex) { throw fail(500, "BINDING_STORAGE_INVALID", "无法规范化绑定版本内容", "request"); }
    }

    private static String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", ex);
        }
    }

    private static BindingFailure sourceFailure(DataAccessException ex) {
        if (ex instanceof QueryTimeoutException) {
            return fail(503, "SOURCE_TIMEOUT", "来源查询超时；请保留当前编辑并稍后重试", "source");
        }
        return fail(503, "SOURCE_UNAVAILABLE", "来源数据库暂不可用或拒绝了读取请求", "source");
    }

    private static BindingFailure fail(int status, String code, String message, String field) {
        return new BindingFailure(status, code, message, field);
    }
}
