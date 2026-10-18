package io.ontoforge.boot.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.ontoforge.boot.security.B0AuthorizationService;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;

@Service
public class ObjectSetQueryService {
    private static final Pattern IDENT = Pattern.compile("[a-z][a-z0-9_]{0,62}");
    private static final Pattern INTEGER = Pattern.compile("0|-?[1-9][0-9]*");
    private static final Pattern DECIMAL = Pattern.compile("-?(0|[1-9][0-9]*)(\\.[0-9]+)?");
    private static final int MAX_ROWS = 10_000;
    private static final int MAX_SOURCE_BYTES = 8 * 1024 * 1024;
    private static final int MAX_RESULT_BYTES = 8 * 1024 * 1024;
    private static final int MAX_PAGE_SIZE = 50;
    private static final int MAX_SNAPSHOTS = 10;
    private static final long MAX_TOTAL_SNAPSHOT_BYTES = 80L * 1024 * 1024;
    private static final long TTL_SECONDS = 300;
    private static final int QUERY_TIMEOUT_SECONDS = 3;

    private final DataBindingService bindings;
    private final RuntimeTypeResolver typeResolver;
    private final B0AuthorizationService authorization;
    private final ObjectMapper mapper;
    private final ObjectProvider<JdbcTemplate> sourceProvider;
    private final List<String> allowedTables;
    private final Map<String, StoredSnapshot> snapshots = new ConcurrentHashMap<>();
    private final Map<String, CursorRef> cursors = new ConcurrentHashMap<>();

    public ObjectSetQueryService(DataBindingService bindings, RuntimeTypeResolver typeResolver,
            B0AuthorizationService authorization, ObjectMapper mapper,
            @Qualifier("bindingSourceJdbcTemplate") ObjectProvider<JdbcTemplate> sourceProvider,
            @Value("$" + "{ontoforge.binding.source.allowed-tables:m5_supplier_source}") String allowedTables) {
        this.bindings = bindings;
        this.typeResolver = typeResolver;
        this.authorization = authorization;
        this.mapper = mapper;
        this.sourceProvider = sourceProvider;
        this.allowedTables = Arrays.stream(allowedTables.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).distinct().toList();
        if (this.allowedTables.stream().anyMatch(t -> !IDENT.matcher(t).matches())) {
            throw new IllegalStateException("M7 allowed source table names must be simple identifiers");
        }
    }

    public record QueryFailure(int http, String code, String message, String fieldPath, String executionId) {}

    public static final class QueryException extends RuntimeException {
        private final int http;
        private final String code;
        private final String fieldPath;
        private final String executionId;
        public QueryException(int http, String code, String message, String fieldPath, String executionId) {
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

    public record ObjectKey(String valueType, String value) {}
    public record ObjectRef(String packageId, String objectTypeId, String identityDomainId, ObjectKey key) {}
    public record BindingVersion(String bindingId, long revision, String digest, String sourceId, String kind) {}
    public record TypeSourceRef(String kind, String snapshotId) {}
    public record SchemaFingerprintRef(String sourceId, String schema, String table, String schemaFingerprint) {}
    public record VersionLock(TypeSourceRef typeSourceRef, String payloadSha256,
            List<BindingVersion> bindingVersions, List<SchemaFingerprintRef> sourceSchemaFingerprints) {}
    public record CheckpointEvidence(String status, String reason) {}
    public record SourceReadRef(String id, String sourceId, String schema, String table, List<String> columns,
            String schemaFingerprint, Instant startedAt, Instant completedAt,
            String transactionIsolation, CheckpointEvidence checkpoint, String consistencyNote) {}
    public record PropertyProvenance(String sourceId, String schema, String table, String column,
            String bindingId, long bindingRevision, String sourceReadRef) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PropertyValue(String propertyRef, String valueType, String status, Object value,
            PropertyProvenance provenance) {}
    public record ObjectSetItem(ObjectRef objectRef, List<PropertyValue> properties) {}
    public record QualityEnvelope(String accuracy, String completeness, String freshness, String answerScope) {}
    public record BudgetEvidence(int physicalRows, int distinctObjects, long sourceBytes, long serializedResultBytes,
            int maxPhysicalRows, long maxSourceBytes, long maxSerializedResultBytes, int queryTimeoutSeconds) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AggregateResult(String operation, String propertyRef, String valueType, String value, int matchedObjects) {}
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record QueryResult(String status, String executionId, Instant executedAt, String queryVersion,
            TypeSourceRef typeSourceRef, String payloadSha256, String accessMode, List<BindingVersion> bindingVersions,
            VersionLock versionLock, SourceReadRef sourceReadRef, String queryDigest, String resultSnapshotRef,
            Instant expiresAt, Integer totalCount, Integer pageSize, Integer pageNumber, String nextCursor,
            List<ObjectSetItem> objects, AggregateResult aggregate, QualityEnvelope quality, BudgetEvidence budget) {}
    public record QueryFailureResult(String status, String executionId, String code, String message,
            String fieldPath, QualityEnvelope quality) {}

    private record Literal(String valueType, Object value) {}
    private record Condition(String propertyRef, String operator, List<Literal> literals) {}
    private record SortField(String propertyRef, String direction) {}
    private record AggregateSpec(String operation, String propertyRef) {}
    private record RootTypeRef(String kind, String snapshotId, String packageId, String objectTypeId) {}
    private record ParsedQuery(RootTypeRef rootTypeRef, String bindingId, long bindingRevision, List<Condition> filters,
            List<SortField> sorts, List<String> project, Integer pageSize, AggregateSpec aggregate,
            List<Object> canonicalSteps) {}
    private record TypedValue(String valueType, String status, Object value) {}
    private record Materialized(ObjectRef ref, Map<String, TypedValue> values) {}
    private record RawScan(List<Map<String, Object>> rows, List<String> columns,
            Instant startedAt, Instant completedAt, long sourceBytes) {}
    private record CursorRef(String snapshotId, int offset, String actorKey) {}
    private record DigestMaterial(String queryVersion, RootTypeRef rootTypeRef, String bindingId, long bindingRevision,
            String bindingDigest, String payloadSha256, String sourceSchemaFingerprint, List<Object> steps) {}

    private static final class StoredSnapshot {
        private final String id;
        private final String actorKey;
        private final String executionId;
        private final String queryDigest;
        private final String resultSnapshotRef;
        private final Instant executedAt;
        private final Instant expiresAt;
        private final DataBindingService.BindingDetail binding;
        private final RuntimeTypeResolver.TypeDescriptor type;
        private final String schemaFingerprint;
        private final int pageSize;
        private final List<ObjectSetItem> items;
        private final AggregateResult aggregate;
        private final List<BindingVersion> bindingVersions;
        private final TypeSourceRef typeSourceRef;
        private final String accessMode;
        private final VersionLock versionLock;
        private final SourceReadRef sourceReadRef;
        private final QualityEnvelope quality;
        private final BudgetEvidence budget;
        private final long bytes;
        // Replaying the same opaque cursor must reuse the next-page token instead of
        // allocating an unbounded number of tokens for one snapshot/offset.
        private final Map<Integer, String> cursorsByOffset = new ConcurrentHashMap<>();

        private StoredSnapshot(String id, String actorKey, String executionId, String queryDigest,
                String resultSnapshotRef, Instant executedAt, Instant expiresAt,
                DataBindingService.BindingDetail binding, RuntimeTypeResolver.TypeDescriptor type,
                String schemaFingerprint, int pageSize, List<ObjectSetItem> items, AggregateResult aggregate,
                List<BindingVersion> bindingVersions, TypeSourceRef typeSourceRef, String accessMode,
                VersionLock versionLock, SourceReadRef sourceReadRef, QualityEnvelope quality, BudgetEvidence budget, long bytes) {
            this.id = id; this.actorKey = actorKey; this.executionId = executionId;
            this.queryDigest = queryDigest; this.resultSnapshotRef = resultSnapshotRef;
            this.executedAt = executedAt; this.expiresAt = expiresAt; this.binding = binding; this.type = type;
            this.schemaFingerprint = schemaFingerprint; this.pageSize = pageSize;
            this.items = List.copyOf(items); this.aggregate = aggregate;
            this.bindingVersions = List.copyOf(bindingVersions); this.typeSourceRef = typeSourceRef;
            this.accessMode = accessMode; this.versionLock = versionLock; this.sourceReadRef = sourceReadRef;
            this.quality = quality; this.budget = budget; this.bytes = bytes;
        }
    }

    public QueryResult execute(JsonNode body, Authentication authentication) {
        String executionId = UUID.randomUUID().toString();
        cleanupExpired();
        ParsedQuery query = parse(body, executionId);
        UUID bindingId = parseUuid(query.bindingId(), "bindingRef.bindingId", executionId);
        DataBindingService.BindingDetail binding = getBinding(bindingId, query.bindingRevision(), authentication, executionId);
        if (!"LOCAL_SNAPSHOT".equals(query.rootTypeRef().kind())
                || !Objects.equals(query.rootTypeRef().snapshotId(), binding.snapshotId())
                || !Objects.equals(query.rootTypeRef().packageId(), binding.packageId())
                || !Objects.equals(query.rootTypeRef().objectTypeId(), binding.objectTypeId())) {
            throw fail(409, "BINDING_SNAPSHOT_MISMATCH",
                    "rootTypeRef 必须精确引用该 Binding revision 的 LOCAL_SNAPSHOT、package 与 ObjectType", "rootTypeRef", executionId);
        }
        RuntimeTypeResolver.TypeDescriptor type = resolveType(binding, authentication, executionId);
        DataBindingService.SourceTable sourceTable = currentSourceTable(binding, authentication, executionId);
        if (!Objects.equals(binding.sourceSchemaFingerprint(), sourceTable.schemaFingerprint())) {
            throw fail(409, "BINDING_SCHEMA_DRIFT",
                    "来源结构指纹与固定 Binding revision 不一致；请回到 M5 重新核验绑定", "sourceSchemaFingerprint", executionId);
        }
        Map<String, RuntimeTypeResolver.PropertyDescriptor> properties = propertyMap(type, executionId);
        Map<String, DataBindingService.FieldMapping> mappings = mappingMap(binding, properties, sourceTable, type, executionId);
        validateTypedQuery(query, type, properties, mappings, executionId);

        LinkedHashSet<String> needed = new LinkedHashSet<>();
        needed.add(type.primaryKeyRef());
        query.filters().forEach(c -> needed.add(c.propertyRef()));
        query.sorts().forEach(s -> needed.add(s.propertyRef()));
        needed.addAll(query.project());
        if (query.aggregate() != null && query.aggregate().propertyRef() != null) needed.add(query.aggregate().propertyRef());
        LinkedHashSet<String> columns = new LinkedHashSet<>();
        columns.add(binding.sourceKey());
        for (String ref : needed) {
            DataBindingService.FieldMapping mapping = mappings.get(ref);
            if (mapping != null) columns.add(mapping.sourceColumn());
        }
        RawScan scan = scanSource(binding, sourceTable, List.copyOf(columns), executionId);
        DataBindingService.BindingDetail afterRead = getBinding(bindingId, query.bindingRevision(), authentication, executionId);
        if (!Objects.equals(binding.digest(), afterRead.digest())
                || !Objects.equals(binding.identityDomainId(), afterRead.identityDomainId())
                || !Objects.equals(binding.payloadSha256(), afterRead.payloadSha256())
                || !Objects.equals(binding.snapshotId(), afterRead.snapshotId())) {
            throw fail(409, "BINDING_REVISION_CONFLICT",
                    "执行期间 Binding 版本证据发生变化；拒绝发布查询结果，请重新执行", "bindingRevision", executionId);
        }
        DataBindingService.SourceTable sourceAfter = currentSourceTable(binding, authentication, executionId);
        if (!Objects.equals(sourceTable.schemaFingerprint(), sourceAfter.schemaFingerprint())) {
            throw fail(409, "BINDING_SCHEMA_DRIFT",
                    "查询执行期间来源结构发生变化；拒绝发布结果，请返回绑定工作台重新核验", "sourceSchemaFingerprint", executionId);
        }
        verifySourceReadable(binding, executionId);

        String sourceReadId = UUID.randomUUID().toString();
        SourceReadRef sourceRead = new SourceReadRef(sourceReadId, binding.sourceId(), binding.sourceSchema(),
                binding.sourceTable(), scan.columns(), sourceTable.schemaFingerprint(), scan.startedAt(), scan.completedAt(),
                "REPEATABLE_READ", new CheckpointEvidence("UNKNOWN", "没有可验证的业务新鲜度水位"),
                "单次只读 PostgreSQL REPEATABLE READ 中完成有界来源扫描；结果快照冻结本次成员，不是源历史快照。业务新鲜度没有可信水位，因此为 UNKNOWN。");
        List<Materialized> objects = materialize(scan.rows(), needed, properties, mappings, binding, type, sourceReadId, executionId);
        List<Materialized> filtered = objects.stream().filter(item -> matches(item, query.filters(), properties)).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        filtered.sort(comparator(query.sorts(), properties, type, executionId));

        List<ObjectSetItem> items = new ArrayList<>();
        AggregateResult aggregate = null;
        if (query.aggregate() != null) {
            aggregate = aggregate(filtered, query.aggregate(), properties, executionId);
        } else {
            for (Materialized item : filtered) items.add(toItem(item, query.project(), properties, mappings, binding, sourceReadId));
        }

        String digest = queryDigest(query, binding, type, sourceTable.schemaFingerprint(), executionId);
        List<BindingVersion> versions = List.of(new BindingVersion(binding.bindingId(), binding.revision(),
                binding.digest(), binding.sourceId(), "OBJECT"));
        TypeSourceRef typeSourceRef = new TypeSourceRef("LOCAL_SNAPSHOT", binding.snapshotId());
        VersionLock versionLock = new VersionLock(typeSourceRef, binding.payloadSha256(), versions,
                List.of(new SchemaFingerprintRef(binding.sourceId(), binding.sourceSchema(), binding.sourceTable(),
                        sourceTable.schemaFingerprint())));
        long serializedBytes = serializedBytes(items, aggregate, executionId);
        if (serializedBytes > MAX_RESULT_BYTES) {
            throw fail(429, "QUERY_BUDGET_EXCEEDED",
                    "冻结查询结果超过 8 MiB 序列化预算；未发布部分结果", "result", executionId);
        }
        BudgetEvidence budget = new BudgetEvidence(scan.rows().size(), objects.size(), scan.sourceBytes(),
                serializedBytes, MAX_ROWS, MAX_SOURCE_BYTES, MAX_RESULT_BYTES, QUERY_TIMEOUT_SECONDS);
        Instant now = Instant.now();
        String id = UUID.randomUUID().toString();
        String accessMode = authentication instanceof JwtAuthenticationToken ? "JWT_AUTHENTICATED" : "LOCAL_ISOLATED";
        StoredSnapshot stored = new StoredSnapshot(id, actorKey(authentication), executionId, digest,
                "local-result:" + id, now, now.plusSeconds(TTL_SECONDS), binding, type,
                sourceTable.schemaFingerprint(), query.pageSize() == null ? 1 : query.pageSize(),
                items, aggregate, versions, typeSourceRef, accessMode, versionLock, sourceRead,
                new QualityEnvelope("EXACT", "COMPLETE", "UNKNOWN", "REQUESTED_SCOPE"), budget, serializedBytes);
        publishSnapshot(stored, executionId);
        return pageResult(stored, 0, executionId);
    }

    public QueryResult nextPage(JsonNode body, Authentication authentication) {
        String executionId = UUID.randomUUID().toString();
        cleanupExpired();
        if (body == null || !body.isObject() || !hasOnlyFields(body, Set.of("cursor"))
                || !body.path("cursor").isTextual() || body.path("cursor").asText().isBlank()
                || body.path("cursor").asText().length() > 128) {
            throw fail(400, "INVALID_CURSOR", "后续页只接受有效的服务端 cursor", "cursor", executionId);
        }
        String token = body.path("cursor").asText();
        CursorRef cursor = cursors.get(token);
        if (cursor == null) throw fail(400, "INVALID_CURSOR", "cursor 无效或不属于已签发的查询结果", "cursor", executionId);
        if (!cursor.actorKey().equals(actorKey(authentication))) {
            throw fail(403, "FORBIDDEN", "cursor 不属于当前主体；cursor 本身不授予访问权限", "cursor", executionId);
        }
        StoredSnapshot snapshot = snapshots.get(cursor.snapshotId());
        if (snapshot == null || !snapshot.expiresAt.isAfter(Instant.now())) {
            cursors.remove(token);
            throw fail(410, "RESULT_EXPIRED", "查询结果快照已过期或服务重启后失效，请重新执行查询", "cursor", executionId);
        }
        revalidateForPage(snapshot, authentication, executionId);
        return pageResult(snapshot, cursor.offset(), snapshot.executionId);
    }

    private void revalidateForPage(StoredSnapshot snapshot, Authentication authentication, String executionId) {
        DataBindingService.BindingDetail current = getBinding(UUID.fromString(snapshot.binding.bindingId()),
                snapshot.binding.revision(), authentication, executionId);
        if (!"VALID".equals(current.validationStatus()) || !Objects.equals(current.digest(), snapshot.binding.digest())
                || !Objects.equals(current.identityDomainId(), snapshot.binding.identityDomainId())
                || !Objects.equals(current.payloadSha256(), snapshot.binding.payloadSha256())) {
            invalidateSnapshot(snapshot.id);
            throw fail(409, "BINDING_UNAVAILABLE", "分页期间绑定版本不再可用；旧结果已失效，请重新执行", "bindingRevision", executionId);
        }
        RuntimeTypeResolver.TypeDescriptor currentType = resolveType(current, authentication, executionId);
        if (!Objects.equals(currentType.payloadSha256(), snapshot.type.payloadSha256())) {
            invalidateSnapshot(snapshot.id);
            throw fail(409, "BINDING_UNAVAILABLE", "分页期间 ObjectType 版本不再可用；旧结果已失效", "typeSourceRef", executionId);
        }
        DataBindingService.SourceTable table = currentSourceTable(current, authentication, executionId);
        if (!Objects.equals(table.schemaFingerprint(), snapshot.schemaFingerprint)) {
            invalidateSnapshot(snapshot.id);
            throw fail(409, "BINDING_SCHEMA_DRIFT", "分页期间来源结构发生变化；旧结果已失效，请重新核验绑定", "sourceSchemaFingerprint", executionId);
        }
        verifySourceReadable(current, executionId);
    }

    private QueryResult pageResult(StoredSnapshot snapshot, int offset, String errorExecutionId) {
        if (snapshot.aggregate != null) {
            return new QueryResult("SUCCESS", snapshot.executionId, snapshot.executedAt, "local-query-v1",
                    snapshot.typeSourceRef, snapshot.binding.payloadSha256(), snapshot.accessMode, snapshot.bindingVersions,
                    snapshot.versionLock, snapshot.sourceReadRef, snapshot.queryDigest, snapshot.resultSnapshotRef,
                    snapshot.expiresAt, null, null, null, null, null, snapshot.aggregate, snapshot.quality, snapshot.budget);
        }
        if (offset < 0 || offset > snapshot.items.size() || offset % snapshot.pageSize != 0) {
            throw fail(400, "INVALID_CURSOR", "cursor 对应的页位置不合法", "cursor", errorExecutionId);
        }
        int end = Math.min(snapshot.items.size(), offset + snapshot.pageSize);
        List<ObjectSetItem> page = snapshot.items.subList(offset, end);
        String next = null;
        if (end < snapshot.items.size()) {
            synchronized (snapshot) {
                next = snapshot.cursorsByOffset.get(end);
                if (next == null) {
                    next = "cursor:" + UUID.randomUUID();
                    snapshot.cursorsByOffset.put(end, next);
                    cursors.put(next, new CursorRef(snapshot.id, end, snapshot.actorKey));
                }
            }
        }
        return new QueryResult("SUCCESS", snapshot.executionId, snapshot.executedAt, "local-query-v1",
                snapshot.typeSourceRef, snapshot.binding.payloadSha256(), snapshot.accessMode, snapshot.bindingVersions,
                snapshot.versionLock, snapshot.sourceReadRef, snapshot.queryDigest, snapshot.resultSnapshotRef,
                snapshot.expiresAt, snapshot.items.size(), snapshot.pageSize, offset / snapshot.pageSize + 1,
                next, List.copyOf(page), null, snapshot.quality, snapshot.budget);
    }

    private ParsedQuery parse(JsonNode body, String executionId) {
        if (body == null || !body.isObject() || !hasOnlyFields(body, Set.of("queryVersion", "rootTypeRef", "bindingRef", "steps"))) {
            throw fail(400, "INVALID_QUERY_AST", "请求必须只包含 queryVersion、rootTypeRef、bindingRef、steps", "request", executionId);
        }
        JsonNode root = body.path("rootTypeRef");
        JsonNode sourceRef = root.path("sourceRef");
        JsonNode bindingRef = body.path("bindingRef");
        if (!body.path("queryVersion").isTextual() || !"local-query-v1".equals(body.path("queryVersion").asText())
                || !root.isObject() || !hasOnlyFields(root, Set.of("sourceRef", "packageId", "objectTypeId"))
                || !sourceRef.isObject() || !hasOnlyFields(sourceRef, Set.of("kind", "snapshotId"))
                || !"LOCAL_SNAPSHOT".equals(sourceRef.path("kind").asText())
                || !sourceRef.path("snapshotId").isTextual()
                || !root.path("packageId").isTextual() || root.path("packageId").asText().isBlank()
                || !root.path("objectTypeId").isTextual() || root.path("objectTypeId").asText().isBlank()
                || !bindingRef.isObject() || !hasOnlyFields(bindingRef, Set.of("bindingId", "revision"))
                || !bindingRef.path("bindingId").isTextual() || !bindingRef.path("revision").canConvertToLong()
                || !bindingRef.path("revision").isIntegralNumber() || bindingRef.path("revision").asLong() < 1
                || !body.path("steps").isArray() || body.path("steps").isEmpty() || body.path("steps").size() > 16) {
            throw fail(422, "INVALID_QUERY_AST", "rootTypeRef、bindingRef 或 AST 节点数不符合 local-query-v1 合同", "request", executionId);
        }
        try { UUID.fromString(sourceRef.path("snapshotId").asText()); }
        catch (RuntimeException ex) { throw fail(422, "INVALID_QUERY_AST", "rootTypeRef.sourceRef.snapshotId 必须是 UUID", "rootTypeRef.sourceRef", executionId); }
        List<Condition> filters = new ArrayList<>();
        List<SortField> sorts = new ArrayList<>();
        List<String> project = List.of();
        Integer pageSize = null;
        AggregateSpec aggregate = null;
        List<Object> canonicalSteps = new ArrayList<>();
        int phase = 0;
        int nodeCount = body.path("steps").size();
        boolean terminal = false;
        for (int i = 0; i < body.path("steps").size(); i++) {
            JsonNode step = body.path("steps").get(i);
            String path = "steps[" + i + "]";
            if (!step.isObject() || !step.path("kind").isTextual()) {
                throw fail(422, "INVALID_QUERY_AST", "每个 AST step 必须包含 kind", path, executionId);
            }
            String kind = step.path("kind").asText();
            switch (kind) {
                case "FILTER" -> {
                    if (terminal || phase > 1 || !hasOnlyFields(step, Set.of("kind", "conditions"))
                            || !step.path("conditions").isArray() || step.path("conditions").isEmpty()) {
                        throw fail(422, "INVALID_QUERY_AST", "FILTER 必须位于排序、投影和终止节点之前，并包含 conditions", path, executionId);
                    }
                    phase = 1;
                    List<Condition> group = new ArrayList<>();
                    for (int j = 0; j < step.path("conditions").size(); j++) {
                        if (++nodeCount > 16) throw fail(422, "INVALID_QUERY_AST", "AST 节点总数超过 16", path, executionId);
                        Condition condition = parseCondition(step.path("conditions").get(j), path + ".conditions[" + j + "]", executionId);
                        group.add(condition); filters.add(condition);
                    }
                    canonicalSteps.add(Map.of("kind", "FILTER", "conditions", group));
                }
                case "SORT" -> {
                    if (terminal || phase > 2 || !sorts.isEmpty() || !hasOnlyFields(step, Set.of("kind", "fields"))
                            || !step.path("fields").isArray() || step.path("fields").isEmpty() || step.path("fields").size() > 3) {
                        throw fail(422, "INVALID_QUERY_AST", "SORT 只能出现一次，最多三个字段且必须位于 PROJECT/PAGE 之前", path, executionId);
                    }
                    phase = 2;
                    Set<String> seen = new LinkedHashSet<>();
                    for (int j = 0; j < step.path("fields").size(); j++) {
                        JsonNode field = step.path("fields").get(j);
                        if (!field.isObject() || !hasOnlyFields(field, Set.of("propertyRef", "direction"))
                                || !field.path("propertyRef").isTextual() || !field.path("direction").isTextual()
                                || !Set.of("ASC", "DESC").contains(field.path("direction").asText())
                                || !seen.add(field.path("propertyRef").asText())) {
                            throw fail(422, "INVALID_QUERY_AST", "排序字段必须唯一且 direction 只能是 ASC/DESC", path + ".fields[" + j + "]", executionId);
                        }
                        sorts.add(new SortField(field.path("propertyRef").asText(), field.path("direction").asText()));
                        nodeCount++;
                    }
                    if (nodeCount > 16) throw fail(422, "INVALID_QUERY_AST", "AST 节点总数超过 16", path, executionId);
                    canonicalSteps.add(Map.of("kind", "SORT", "fields", List.copyOf(sorts)));
                }
                case "PROJECT" -> {
                    if (terminal || phase > 3 || !project.isEmpty() || !hasOnlyFields(step, Set.of("kind", "propertyRefs"))
                            || !step.path("propertyRefs").isArray() || step.path("propertyRefs").isEmpty() || step.path("propertyRefs").size() > 100) {
                        throw fail(422, "INVALID_QUERY_AST", "PROJECT 只能出现一次，需选择 1–100 个属性且位于 PAGE 之前", path, executionId);
                    }
                    phase = 3;
                    List<String> refs = new ArrayList<>();
                    for (JsonNode ref : step.path("propertyRefs")) {
                        if (!ref.isTextual() || ref.asText().isBlank() || refs.contains(ref.asText())) {
                            throw fail(422, "INVALID_QUERY_AST", "投影属性必须是非空且不重复的字符串", path, executionId);
                        }
                        refs.add(ref.asText());
                    }
                    project = List.copyOf(refs);
                    canonicalSteps.add(Map.of("kind", "PROJECT", "propertyRefs", project));
                }
                case "PAGE" -> {
                    if (terminal || phase > 3 || !hasOnlyFields(step, Set.of("kind", "pageSize"))
                            || !step.path("pageSize").isIntegralNumber() || !step.path("pageSize").canConvertToInt()
                            || step.path("pageSize").asInt() < 1 || step.path("pageSize").asInt() > MAX_PAGE_SIZE) {
                        throw fail(422, "INVALID_QUERY_AST", "PAGE 必须是最后一步，pageSize 范围为 1–50", path, executionId);
                    }
                    terminal = true; phase = 4; pageSize = step.path("pageSize").asInt();
                    canonicalSteps.add(Map.of("kind", "PAGE", "pageSize", pageSize));
                }
                case "AGGREGATE" -> {
                    if (terminal || phase > 1 || !hasOnlyFields(step, Set.of("kind", "operation", "propertyRef"))
                            || !step.path("operation").isTextual()) {
                        throw fail(422, "INVALID_QUERY_AST", "AGGREGATE 只能接在 FILTER 后并作为最后一步，不能与 SORT/PROJECT/PAGE 混用", path, executionId);
                    }
                    String operation = step.path("operation").asText();
                    String propertyRef = step.path("propertyRef").isTextual() ? step.path("propertyRef").asText() : null;
                    if ("COUNT_DISTINCT".equals(operation)) {
                        if (propertyRef != null || step.has("propertyRef")) throw fail(422, "INVALID_QUERY_AST", "COUNT_DISTINCT 固定统计 ObjectRef，不接受 propertyRef", path, executionId);
                    } else if ("SUM".equals(operation)) {
                        if (propertyRef == null || propertyRef.isBlank()) throw fail(422, "INVALID_QUERY_AST", "SUM 必须指定 propertyRef", path, executionId);
                    } else {
                        throw fail(422, "UNSUPPORTED_QUERY_OPERATOR", "仅支持 COUNT_DISTINCT 与 SUM 聚合", path + ".operation", executionId);
                    }
                    aggregate = new AggregateSpec(operation, propertyRef);
                    terminal = true; phase = 4;
                    Map<String,Object> node = new LinkedHashMap<>();
                    node.put("kind", "AGGREGATE"); node.put("operation", operation);
                    if (propertyRef != null) node.put("propertyRef", propertyRef);
                    canonicalSteps.add(node);
                }
                default -> throw fail(422, "UNSUPPORTED_QUERY_OPERATOR", "当前版本不支持该 AST 节点", path + ".kind", executionId);
            }
        }
        String lastKind = body.path("steps").get(body.path("steps").size() - 1).path("kind").asText();
        if (!terminal || !Set.of("PAGE", "AGGREGATE").contains(lastKind) || (aggregate == null && pageSize == null)) {
            throw fail(422, "INVALID_QUERY_AST", "查询必须以 PAGE 或 AGGREGATE 结束", "steps", executionId);
        }
        if (nodeCount > 16) throw fail(422, "INVALID_QUERY_AST", "AST 节点总数超过 16", "steps", executionId);
        RootTypeRef rootTypeRef = new RootTypeRef(sourceRef.path("kind").asText(), sourceRef.path("snapshotId").asText(),
                root.path("packageId").asText(), root.path("objectTypeId").asText());
        return new ParsedQuery(rootTypeRef, bindingRef.path("bindingId").asText(), bindingRef.path("revision").asLong(),
                List.copyOf(filters), List.copyOf(sorts), project, pageSize, aggregate, List.copyOf(canonicalSteps));
    }

    private Condition parseCondition(JsonNode node, String path, String executionId) {
        if (!node.isObject() || !node.path("propertyRef").isTextual() || !node.path("operator").isTextual()) {
            throw fail(422, "INVALID_QUERY_AST", "筛选条件必须包含 propertyRef 和 operator", path, executionId);
        }
        String operator = node.path("operator").asText();
        if (!Set.of("EQ", "IN", "GT", "GTE", "LT", "LTE", "PREFIX", "IS_NULL").contains(operator)) {
            throw fail(422, "UNSUPPORTED_QUERY_OPERATOR", "不支持的筛选操作符", path + ".operator", executionId);
        }
        List<Literal> literals = new ArrayList<>();
        if ("IS_NULL".equals(operator)) {
            if (!hasOnlyFields(node, Set.of("propertyRef", "operator"))) {
                throw fail(422, "INVALID_QUERY_AST", "IS_NULL 不接受 literal 或 values", path, executionId);
            }
        } else if ("IN".equals(operator)) {
            if (!hasOnlyFields(node, Set.of("propertyRef", "operator", "values"))
                    || !node.path("values").isArray() || node.path("values").isEmpty() || node.path("values").size() > 100) {
                throw fail(422, "INVALID_QUERY_AST", "IN 必须提供 1–100 个 values literal", path + ".values", executionId);
            }
            for (JsonNode value : node.path("values")) literals.add(parseLiteral(value, path + ".values", executionId));
        } else {
            if (!hasOnlyFields(node, Set.of("propertyRef", "operator", "literal")) || !node.has("literal")) {
                throw fail(422, "INVALID_QUERY_AST", "该操作符必须提供一个 literal", path + ".literal", executionId);
            }
            literals.add(parseLiteral(node.path("literal"), path + ".literal", executionId));
        }
        return new Condition(node.path("propertyRef").asText(), operator, List.copyOf(literals));
    }

    private Literal parseLiteral(JsonNode node, String path, String executionId) {
        if (!node.isObject() || !hasOnlyFields(node, Set.of("valueType", "value"))
                || !node.path("valueType").isTextual() || !node.has("value")) {
            throw fail(422, "INVALID_QUERY_AST", "literal 必须包含 valueType 和 value", path, executionId);
        }
        String type = canonicalType(node.path("valueType").asText());
        JsonNode value = node.path("value");
        if ("BOOLEAN".equals(type)) {
            if (!value.isBoolean()) throw fail(422, "INVALID_QUERY_AST", "BOOLEAN literal 必须是 JSON boolean", path, executionId);
            return new Literal(type, value.booleanValue());
        }
        if (!value.isTextual()) throw fail(422, "INVALID_QUERY_AST", "非 BOOLEAN literal 必须使用规范字符串，避免数值精度丢失", path, executionId);
        String text = value.asText();
        validateCanonicalLiteral(type, text, path, executionId);
        return new Literal(type, normalizeLiteral(type, text, path, executionId));
    }

    private void validateTypedQuery(ParsedQuery query, RuntimeTypeResolver.TypeDescriptor type,
            Map<String, RuntimeTypeResolver.PropertyDescriptor> properties,
            Map<String, DataBindingService.FieldMapping> mappings, String executionId) {
        for (Condition condition : query.filters()) {
            RuntimeTypeResolver.PropertyDescriptor property = properties.get(condition.propertyRef());
            if (property == null || !property.searchable() || !mappings.containsKey(condition.propertyRef())) {
                throw fail(422, "INVALID_QUERY_AST", "筛选只能引用当前 ObjectType 中已绑定且可检索的属性", "filters." + condition.propertyRef(), executionId);
            }
            String valueType = canonicalType(property.valueType());
            Set<String> allowed = switch (valueType) {
                case "STRING", "TEXT", "ENUM" -> Set.of("EQ", "IN", "PREFIX", "IS_NULL");
                case "INTEGER", "DECIMAL", "DATETIME", "DATE" -> Set.of("EQ", "IN", "GT", "GTE", "LT", "LTE", "IS_NULL");
                case "BOOLEAN", "UUID" -> Set.of("EQ", "IN", "IS_NULL");
                default -> Set.of();
            };
            if (!allowed.contains(condition.operator())) {
                throw fail(422, "UNSUPPORTED_QUERY_OPERATOR", "该属性类型不支持此筛选操作符", "filters." + condition.propertyRef(), executionId);
            }
            if (!"IS_NULL".equals(condition.operator())) {
                for (Literal literal : condition.literals()) {
                    if (!valueType.equals(literal.valueType())) {
                        throw fail(422, "INVALID_QUERY_AST", "literal valueType 与属性类型不一致", "filters." + condition.propertyRef(), executionId);
                    }
                    if ("ENUM".equals(valueType) && property.enumValues() != null
                            && !property.enumValues().contains(String.valueOf(literal.value()))) {
                        throw fail(422, "INVALID_QUERY_AST", "枚举 literal 不属于该属性允许的值集合", "filters." + condition.propertyRef(), executionId);
                    }
                }
            }
        }
        Set<String> sortRefs = new LinkedHashSet<>();
        for (SortField sort : query.sorts()) {
            if (!properties.containsKey(sort.propertyRef()) || !sortRefs.add(sort.propertyRef())) {
                throw fail(422, "INVALID_QUERY_AST", "排序字段必须引用已定义且不重复的属性", "sort." + sort.propertyRef(), executionId);
            }
            if (!mappings.containsKey(sort.propertyRef())) {
                throw fail(422, "INVALID_QUERY_AST", "排序只能引用已绑定到来源列的属性", "sort." + sort.propertyRef(), executionId);
            }
            if (!isSupportedType(canonicalType(properties.get(sort.propertyRef()).valueType()))) {
                throw fail(422, "UNSUPPORTED_QUERY_OPERATOR", "该属性类型暂不支持排序", "sort." + sort.propertyRef(), executionId);
            }
        }
        for (String ref : query.project()) {
            if (!properties.containsKey(ref)) throw fail(422, "INVALID_QUERY_AST", "投影只能引用当前 ObjectType 的属性", "project." + ref, executionId);
        }
        if (query.aggregate() != null && "SUM".equals(query.aggregate().operation())) {
            RuntimeTypeResolver.PropertyDescriptor property = properties.get(query.aggregate().propertyRef());
            if (property == null || !mappings.containsKey(query.aggregate().propertyRef())) {
                throw fail(422, "INVALID_QUERY_AST", "SUM 必须引用已绑定到来源列的数值属性", "aggregate.propertyRef", executionId);
            }
            if (!Set.of("INTEGER", "DECIMAL").contains(canonicalType(property.valueType()))) {
                throw fail(422, "UNSUPPORTED_QUERY_OPERATOR", "SUM 仅支持 INTEGER 或 DECIMAL 属性", "aggregate.propertyRef", executionId);
            }
        }
    }

    private RawScan scanSource(DataBindingService.BindingDetail binding, DataBindingService.SourceTable table,
            List<String> columns, String executionId) {
        if (!DataBindingService.SOURCE_ID.equals(binding.sourceId()) || !"public".equals(binding.sourceSchema())
                || !allowedTables.contains(binding.sourceTable()) || !IDENT.matcher(binding.sourceTable()).matches()
                || !IDENT.matcher(binding.sourceKey()).matches()) {
            throw fail(403, "SOURCE_NOT_ALLOWED", "Binding 来源不在服务端登记的只读允许列表中", "bindingId", executionId);
        }
        for (String column : columns) {
            if (!IDENT.matcher(column).matches() || table.columns().stream().noneMatch(c -> c.name().equals(column))) {
                throw fail(409, "BINDING_SCHEMA_DRIFT", "Binding 引用的来源列不存在，拒绝按同名列自动重绑", "sourceColumn", executionId);
            }
        }
        JdbcTemplate source = sourceProvider.getIfAvailable();
        if (source == null) throw fail(503, "SOURCE_UNAVAILABLE", "没有配置独立来源只读连接；不能判断查询结果", "source", executionId);
        try {
            return source.execute((ConnectionCallback<RawScan>) connection ->
                    executeScan(connection, binding, table, columns, executionId));
        } catch (QueryException ex) {
            throw ex;
        } catch (QueryTimeoutException ex) {
            throw fail(503, "SOURCE_TIMEOUT", "来源查询超过 3 秒预算；未返回部分结果", "source", executionId);
        } catch (DataAccessException ex) {
            SQLException sql = findSqlException(ex);
            if (sql != null && ("42P01".equals(sql.getSQLState()) || "42703".equals(sql.getSQLState()))) {
                throw fail(409, "BINDING_SCHEMA_DRIFT", "来源表或绑定列在查询期间发生变化；未返回结果", "source", executionId);
            }
            if (sql != null && "57014".equals(sql.getSQLState())) {
                throw fail(503, "SOURCE_TIMEOUT", "来源查询超过 3 秒预算；未返回部分结果", "source", executionId);
            }
            throw fail(503, "SOURCE_UNAVAILABLE", "来源数据库不可用或拒绝只读查询；未返回部分结果", "source", executionId);
        }
    }

    private RawScan executeScan(Connection connection, DataBindingService.BindingDetail binding,
            DataBindingService.SourceTable table, List<String> columns, String executionId) throws SQLException {
        boolean auto = connection.getAutoCommit();
        int isolation = connection.getTransactionIsolation();
        boolean readOnly = connection.isReadOnly();
        try {
            connection.setReadOnly(true);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                statement.execute("SET TRANSACTION READ ONLY");
            }
            Instant started = Instant.now();
            verifySourceReadable(connection, binding, executionId);
            String projection = String.join(", ", columns.stream().map(ObjectSetQueryService::qi).toList());
            String from = " FROM " + qt(binding.sourceSchema(), binding.sourceTable());
            String sizeExpr = columns.stream()
                    .map(c -> "COALESCE(octet_length(" + qi(c) + "::text),0)::bigint")
                    .reduce((a,b) -> a + " + " + b).orElse("0");
            String budgetSql = "WITH candidates AS (SELECT " + projection + from
                    + " ORDER BY " + qi(binding.sourceKey()) + " LIMIT " + (MAX_ROWS + 1) + ") "
                    + "SELECT COUNT(*)::bigint AS row_count, COALESCE(SUM(" + sizeExpr
                    + "),0)::bigint AS source_bytes FROM candidates";
            long rowCount;
            long sourceBytes;
            try (PreparedStatement statement = connection.prepareStatement(budgetSql)) {
                statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                try (ResultSet rs = statement.executeQuery()) {
                    if (!rs.next()) throw new SQLException("M7 source budget query returned no row");
                    rowCount = rs.getLong("row_count");
                    sourceBytes = rs.getLong("source_bytes");
                }
            }
            if (rowCount > MAX_ROWS) throw fail(429, "QUERY_BUDGET_EXCEEDED",
                    "来源物理行数超过 10000 行预算；没有返回部分结果", "source", executionId);
            if (sourceBytes > MAX_SOURCE_BYTES) throw fail(429, "QUERY_BUDGET_EXCEEDED",
                    "本次查询来源扫描超过 8 MiB 预算；没有返回部分结果", "source", executionId);
            String sql = "SELECT " + projection + from + " ORDER BY " + qi(binding.sourceKey())
                    + " LIMIT " + (MAX_ROWS + 1);
            List<Map<String,Object>> rows = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                statement.setFetchSize(50);
                statement.setMaxRows(MAX_ROWS + 1);
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        Map<String,Object> row = new LinkedHashMap<>();
                        for (String column : columns) row.put(column, rs.getObject(column));
                        rows.add(row);
                    }
                }
            }
            if (rows.size() > MAX_ROWS) throw fail(429, "QUERY_BUDGET_EXCEEDED",
                    "来源物理行数超过 10000 行预算；没有返回部分结果", "source", executionId);
            Instant completed = Instant.now();
            connection.commit();
            return new RawScan(List.copyOf(rows), List.copyOf(columns), started, completed, sourceBytes);
        } catch (RuntimeException | SQLException ex) {
            try { connection.rollback(); } catch (SQLException ignored) {}
            if (ex instanceof RuntimeException runtime) throw runtime;
            throw (SQLException) ex;
        } finally {
            try { connection.setAutoCommit(auto); } catch (SQLException ignored) {}
            try { connection.setTransactionIsolation(isolation); } catch (SQLException ignored) {}
            try { connection.setReadOnly(readOnly); } catch (SQLException ignored) {}
        }
    }

    private void verifySourceReadable(DataBindingService.BindingDetail binding, String executionId) {
        JdbcTemplate source = sourceProvider.getIfAvailable();
        if (source == null) {
            throw fail(503, "SOURCE_UNAVAILABLE", "没有配置独立来源只读连接；不能继续分页", "source", executionId);
        }
        try {
            source.execute((ConnectionCallback<Void>) connection -> {
                verifySourceReadable(connection, binding, executionId);
                return null;
            });
        } catch (QueryException ex) {
            throw ex;
        } catch (DataAccessException ex) {
            throw fail(503, "SOURCE_UNAVAILABLE", "分页时来源不可用或拒绝只读查询；旧结果不可继续读取", "source", executionId);
        }
    }

    private void verifySourceReadable(Connection connection, DataBindingService.BindingDetail binding,
            String executionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT has_schema_privilege(current_user, ?, 'USAGE') AS schema_usable,
                       to_regclass(quote_ident(?) || '.' || quote_ident(?)) IS NOT NULL AS relation_exists,
                       CASE WHEN to_regclass(quote_ident(?) || '.' || quote_ident(?)) IS NULL THEN FALSE
                            ELSE has_table_privilege(current_user,
                                 to_regclass(quote_ident(?) || '.' || quote_ident(?)), 'SELECT') END AS can_select
                """)) {
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            statement.setString(1, binding.sourceSchema());
            statement.setString(2, binding.sourceSchema()); statement.setString(3, binding.sourceTable());
            statement.setString(4, binding.sourceSchema()); statement.setString(5, binding.sourceTable());
            statement.setString(6, binding.sourceSchema()); statement.setString(7, binding.sourceTable());
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next() || !rs.getBoolean("schema_usable") || !rs.getBoolean("relation_exists")
                        || !rs.getBoolean("can_select")) {
                    throw fail(503, "SOURCE_UNAVAILABLE", "来源不可用或当前只读账号没有 SELECT 权限；拒绝返回结果", "source", executionId);
                }
            }
        }
    }

    private List<Materialized> materialize(List<Map<String,Object>> rows, Set<String> needed,
            Map<String,RuntimeTypeResolver.PropertyDescriptor> properties,
            Map<String,DataBindingService.FieldMapping> mappings,
            DataBindingService.BindingDetail binding, RuntimeTypeResolver.TypeDescriptor type,
            String sourceReadId, String executionId) {
        Map<String,Materialized> unique = new LinkedHashMap<>();
        RuntimeTypeResolver.PropertyDescriptor pk = properties.get(type.primaryKeyRef());
        for (Map<String,Object> row : rows) {
            Map<String,TypedValue> values = new LinkedHashMap<>();
            for (String ref : needed) {
                RuntimeTypeResolver.PropertyDescriptor property = properties.get(ref);
                if (property == null) continue;
                DataBindingService.FieldMapping mapping = mappings.get(ref);
                if (mapping == null) values.put(ref, new TypedValue(canonicalType(property.valueType()), "MISSING", null));
                else {
                    Object raw = row.get(mapping.sourceColumn());
                    if (raw == null) values.put(ref, new TypedValue(canonicalType(property.valueType()), "NULL", null));
                    else values.put(ref, new TypedValue(canonicalType(property.valueType()), "PRESENT",
                            normalizeSourceValue(property, raw, ref, executionId)));
                }
            }
            TypedValue key = values.get(type.primaryKeyRef());
            if (key == null || !"PRESENT".equals(key.status()) || key.value() == null) {
                throw fail(409, "OBJECT_IDENTITY_CONFLICT", "来源对象缺少可用的规范主键；拒绝返回部分对象", type.primaryKeyRef(), executionId);
            }
            ObjectRef ref = new ObjectRef(binding.packageId(), binding.objectTypeId(), binding.identityDomainId(),
                    new ObjectKey(key.valueType(), String.valueOf(key.value())));
            Materialized candidate = new Materialized(ref, Map.copyOf(values));
            String identity = ref.packageId() + "|" + ref.objectTypeId() + "|" + ref.identityDomainId()
                    + "|" + ref.key().valueType() + "|" + ref.key().value();
            Materialized old = unique.putIfAbsent(identity, candidate);
            if (old != null && !equivalentValues(old.values(), candidate.values(), needed)) {
                throw fail(409, "OBJECT_IDENTITY_CONFLICT",
                        "相同 ObjectRef 对应矛盾来源值；冲突核验先于筛选，拒绝选择任意行", type.primaryKeyRef(), executionId);
            }
        }
        return List.copyOf(unique.values());
    }

    private boolean equivalentValues(Map<String,TypedValue> left, Map<String,TypedValue> right, Set<String> refs) {
        for (String ref : refs) {
            TypedValue a = left.get(ref), b = right.get(ref);
            if (!Objects.equals(a, b)) return false;
        }
        return true;
    }

    private boolean matches(Materialized item, List<Condition> conditions,
            Map<String,RuntimeTypeResolver.PropertyDescriptor> properties) {
        for (Condition condition : conditions) {
            TypedValue value = item.values().get(condition.propertyRef());
            if (value == null) value = new TypedValue(canonicalType(properties.get(condition.propertyRef()).valueType()), "MISSING", null);
            if ("IS_NULL".equals(condition.operator())) {
                if (!"NULL".equals(value.status())) return false;
                continue;
            }
            if (!"PRESENT".equals(value.status())) return false;
            if ("IN".equals(condition.operator())) {
                TypedValue selectedValue = value;
                boolean found = condition.literals().stream().anyMatch(l -> compareValues(selectedValue.valueType(), selectedValue.value(), l.value()) == 0);
                if (!found) return false;
            } else {
                Object literal = condition.literals().getFirst().value();
                int comparison = compareValues(value.valueType(), value.value(), literal);
                boolean match = switch (condition.operator()) {
                    case "EQ" -> comparison == 0;
                    case "GT" -> comparison > 0;
                    case "GTE" -> comparison >= 0;
                    case "LT" -> comparison < 0;
                    case "LTE" -> comparison <= 0;
                    case "PREFIX" -> String.valueOf(value.value()).startsWith(String.valueOf(literal));
                    default -> false;
                };
                if (!match) return false;
            }
        }
        return true;
    }

    private Comparator<Materialized> comparator(List<SortField> sorts,
            Map<String,RuntimeTypeResolver.PropertyDescriptor> properties,
            RuntimeTypeResolver.TypeDescriptor type, String executionId) {
        return (left, right) -> {
            for (SortField sort : sorts) {
                TypedValue a = left.values().get(sort.propertyRef());
                TypedValue b = right.values().get(sort.propertyRef());
                if (a == null) a = new TypedValue(canonicalType(properties.get(sort.propertyRef()).valueType()), "MISSING", null);
                if (b == null) b = new TypedValue(canonicalType(properties.get(sort.propertyRef()).valueType()), "MISSING", null);
                int comparison;
                if (!"PRESENT".equals(a.status()) || !"PRESENT".equals(b.status())) {
                    comparison = Integer.compare(statusRank(a.status()), statusRank(b.status()));
                } else {
                    comparison = compareValues(a.valueType(), a.value(), b.value());
                    if ("DESC".equals(sort.direction())) comparison = -comparison;
                }
                if (comparison != 0) return comparison;
            }
            TypedValue a = left.values().get(type.primaryKeyRef());
            TypedValue b = right.values().get(type.primaryKeyRef());
            if (a == null || b == null) return left.ref().key().value().compareTo(right.ref().key().value());
            return compareValues(a.valueType(), a.value(), b.value());
        };
    }

    private AggregateResult aggregate(List<Materialized> filtered, AggregateSpec spec,
            Map<String,RuntimeTypeResolver.PropertyDescriptor> properties, String executionId) {
        if ("COUNT_DISTINCT".equals(spec.operation())) {
            return new AggregateResult(spec.operation(), null, "INTEGER", Integer.toString(filtered.size()), filtered.size());
        }
        RuntimeTypeResolver.PropertyDescriptor property = properties.get(spec.propertyRef());
        BigDecimal sum = BigDecimal.ZERO;
        for (Materialized item : filtered) {
            TypedValue value = item.values().get(spec.propertyRef());
            if (value == null || !"PRESENT".equals(value.status())) {
                throw fail(422, "AGGREGATE_VALUE_UNAVAILABLE",
                        "SUM 的匹配对象包含 NULL/MISSING；不把缺失值当作 0，也不返回部分聚合", "aggregate." + spec.propertyRef(), executionId);
            }
            try { sum = sum.add(new BigDecimal(String.valueOf(value.value()))); }
            catch (NumberFormatException ex) {
                throw fail(422, "AGGREGATE_VALUE_UNAVAILABLE", "SUM 输入不是可验证的精确数值；没有返回部分聚合", "aggregate." + spec.propertyRef(), executionId);
            }
        }
        String value = sum.signum() == 0 ? "0" : sum.stripTrailingZeros().toPlainString();
        return new AggregateResult("SUM", spec.propertyRef(), canonicalType(property.valueType()), value, filtered.size());
    }

    private ObjectSetItem toItem(Materialized item, List<String> project,
            Map<String,RuntimeTypeResolver.PropertyDescriptor> properties,
            Map<String,DataBindingService.FieldMapping> mappings,
            DataBindingService.BindingDetail binding, String sourceReadId) {
        List<PropertyValue> values = new ArrayList<>();
        for (String ref : project) {
            RuntimeTypeResolver.PropertyDescriptor property = properties.get(ref);
            TypedValue value = item.values().get(ref);
            if (value == null) value = new TypedValue(canonicalType(property.valueType()), "MISSING", null);
            DataBindingService.FieldMapping mapping = mappings.get(ref);
            PropertyProvenance provenance = mapping == null || "MISSING".equals(value.status()) ? null
                    : new PropertyProvenance(binding.sourceId(), binding.sourceSchema(), binding.sourceTable(),
                        mapping.sourceColumn(), binding.bindingId(), binding.revision(), sourceReadId);
            values.add(new PropertyValue(ref, property.valueType(), value.status(),
                    "PRESENT".equals(value.status()) ? value.value() : null, provenance));
        }
        return new ObjectSetItem(item.ref(), List.copyOf(values));
    }

    private DataBindingService.BindingDetail getBinding(UUID bindingId, long revision,
            Authentication authentication, String executionId) {
        try {
            DataBindingService.BindingDetail binding = bindings.getBinding(bindingId, revision, authentication);
            if (!"VALID".equals(binding.validationStatus())) {
                throw fail(409, "BINDING_UNAVAILABLE", "指定 Binding revision 未通过 M5 有界校验", "bindingRevision", executionId);
            }
            return binding;
        } catch (QueryException ex) {
            throw ex;
        } catch (DataBindingService.BindingFailure ex) {
            throw fail(ex.http, ex.code, ex.getMessage(), ex.fieldPath, executionId);
        } catch (NativeDraftService.DraftFailure ex) {
            throw fail(ex.http, ex.code, ex.getMessage(), null, executionId);
        } catch (AccessDeniedException ex) {
            throw fail(403, "FORBIDDEN", "当前主体没有该模型包的查询权限", null, executionId);
        } catch (DataAccessException ex) {
            throw fail(503, "PERSISTENCE_UNAVAILABLE", "Binding 版本存储暂不可用；未返回查询结果", null, executionId);
        }
    }

    private RuntimeTypeResolver.TypeDescriptor resolveType(DataBindingService.BindingDetail binding,
            Authentication authentication, String executionId) {
        try {
            UUID snapshotId = UUID.fromString(binding.snapshotId());
            authorization.requireSnapshot(authentication, snapshotId, B0AuthorizationService.Operation.READ_SNAPSHOT);
            RuntimeTypeResolver.TypeDescriptor type = typeResolver.getSnapshotType(snapshotId, binding.objectTypeId());
            if (!Objects.equals(type.packageId(), binding.packageId())
                    || !Objects.equals(type.objectTypeId(), binding.objectTypeId())
                    || !Objects.equals(type.payloadSha256(), binding.payloadSha256())) {
                throw fail(409, "BINDING_SNAPSHOT_MISMATCH", "Binding 与不可变 Native Snapshot 的包、类型或摘要不一致", "bindingRevision", executionId);
            }
            return type;
        } catch (QueryException ex) {
            throw ex;
        } catch (AccessDeniedException ex) {
            throw fail(403, "FORBIDDEN", "当前主体没有该 Snapshot 的读取权限", null, executionId);
        } catch (NativeDraftService.DraftFailure ex) {
            throw fail(ex.http, ex.code, ex.getMessage(), null, executionId);
        }
    }

    private DataBindingService.SourceTable currentSourceTable(DataBindingService.BindingDetail binding,
            Authentication authentication, String executionId) {
        try {
            List<DataBindingService.SourceTable> tables = bindings.listTables(binding.packageId(), authentication);
            return tables.stream().filter(t -> t.sourceId().equals(binding.sourceId())
                    && t.schema().equals(binding.sourceSchema()) && t.table().equals(binding.sourceTable()))
                    .findFirst().orElseThrow(() -> fail(409, "BINDING_SCHEMA_DRIFT",
                            "Binding 来源表不在当前服务端允许列表或已不存在", "sourceTable", executionId));
        } catch (QueryException ex) {
            throw ex;
        } catch (DataBindingService.BindingFailure ex) {
            throw fail(ex.http, ex.code, ex.getMessage(), ex.fieldPath, executionId);
        } catch (DataAccessException ex) {
            throw fail(503, "SOURCE_UNAVAILABLE", "来源结构核验失败；不能继续读取冻结结果", "source", executionId);
        }
    }

    private Map<String,RuntimeTypeResolver.PropertyDescriptor> propertyMap(
            RuntimeTypeResolver.TypeDescriptor type, String executionId) {
        Map<String,RuntimeTypeResolver.PropertyDescriptor> result = new LinkedHashMap<>();
        for (RuntimeTypeResolver.PropertyDescriptor property : type.properties()) {
            if (result.putIfAbsent(property.id(), property) != null) {
                throw fail(409, "BINDING_UNAVAILABLE", "Native ObjectType 存在重复 Property id", "objectTypeId", executionId);
            }
        }
        if (!result.containsKey(type.primaryKeyRef())) {
            throw fail(409, "BINDING_UNAVAILABLE", "ObjectType 的 primaryKeyRef 不属于其属性集合", "primaryKeyRef", executionId);
        }
        return Map.copyOf(result);
    }

    private Map<String,DataBindingService.FieldMapping> mappingMap(DataBindingService.BindingDetail binding,
            Map<String,RuntimeTypeResolver.PropertyDescriptor> properties,
            DataBindingService.SourceTable table, RuntimeTypeResolver.TypeDescriptor type, String executionId) {
        if (!DataBindingService.SOURCE_ID.equals(binding.sourceId()) || !"public".equals(binding.sourceSchema())
                || !allowedTables.contains(binding.sourceTable()) || !IDENT.matcher(binding.sourceTable()).matches()) {
            throw fail(403, "SOURCE_NOT_ALLOWED", "Binding 来源不在服务端登记的只读允许列表中", "sourceTable", executionId);
        }
        Map<String,DataBindingService.SourceColumn> columns = new LinkedHashMap<>();
        for (DataBindingService.SourceColumn column : table.columns()) columns.put(column.name(), column);
        Map<String,DataBindingService.FieldMapping> mappings = new LinkedHashMap<>();
        for (DataBindingService.FieldMapping mapping : binding.fieldMappings()) {
            if (mapping == null || mapping.propertyRef() == null || mapping.sourceColumn() == null
                    || !"AUTHORITATIVE".equals(mapping.authority()) || !properties.containsKey(mapping.propertyRef())
                    || !IDENT.matcher(mapping.sourceColumn()).matches() || !columns.containsKey(mapping.sourceColumn())
                    || mappings.putIfAbsent(mapping.propertyRef(), mapping) != null) {
                throw fail(409, "BINDING_UNAVAILABLE", "Binding 映射与已保存 ObjectType/来源列合同不一致", "fieldMappings", executionId);
            }
            if (!compatible(properties.get(mapping.propertyRef()), columns.get(mapping.sourceColumn()))) {
                throw fail(409, "BINDING_UNAVAILABLE", "Binding 属性类型与来源列类型不兼容", "fieldMappings." + mapping.propertyRef(), executionId);
            }
        }
        DataBindingService.FieldMapping key = mappings.get(type.primaryKeyRef());
        if (key == null || !binding.sourceKey().equals(key.sourceColumn())) {
            throw fail(409, "BINDING_UNAVAILABLE", "来源身份键没有映射到 ObjectType.primaryKeyRef", "sourceKey", executionId);
        }
        return Map.copyOf(mappings);
    }

    private boolean compatible(RuntimeTypeResolver.PropertyDescriptor property, DataBindingService.SourceColumn column) {
        String type = canonicalType(property.valueType());
        String data = (column.dataType() + " " + column.udtName()).toLowerCase(Locale.ROOT);
        return switch (type) {
            case "STRING", "TEXT", "ENUM" -> data.contains("character") || data.contains("text") || data.contains("varchar");
            case "INTEGER" -> data.contains("int") || data.contains("serial");
            case "DECIMAL" -> data.contains("numeric") || data.contains("decimal");
            case "BOOLEAN" -> data.contains("bool");
            case "DATETIME" -> data.contains("timestamp") || data.contains("timestamptz");
            case "DATE" -> data.contains("date");
            case "UUID" -> data.contains("uuid") || data.contains("character") || data.contains("text");
            default -> false;
        };
    }

    private void validateCanonicalLiteral(String type, String text, String path, String executionId) {
        boolean valid = switch (type) {
            case "STRING", "TEXT", "ENUM" -> text.length() <= 4096;
            case "INTEGER" -> INTEGER.matcher(text).matches();
            case "DECIMAL" -> DECIMAL.matcher(text).matches();
            case "DATETIME" -> {
                try { OffsetDateTime.parse(text); yield true; } catch (RuntimeException ex) { yield false; }
            }
            case "DATE" -> {
                try { LocalDate.parse(text); yield true; } catch (RuntimeException ex) { yield false; }
            }
            case "UUID" -> {
                try { yield UUID.fromString(text).toString().equals(text); } catch (RuntimeException ex) { yield false; }
            }
            default -> false;
        };
        if (!valid) throw fail(422, "INVALID_QUERY_AST", "literal 不符合其 valueType 的规范字面量合同", path, executionId);
    }

    private Object normalizeLiteral(String type, String text, String path, String executionId) {
        try {
            return switch (type) {
                case "INTEGER" -> new BigInteger(text).toString();
                case "DECIMAL" -> new BigDecimal(text).stripTrailingZeros().toPlainString();
                case "DATETIME" -> OffsetDateTime.parse(text).toInstant().toString();
                case "DATE" -> LocalDate.parse(text).toString();
                case "UUID" -> UUID.fromString(text).toString();
                default -> text;
            };
        } catch (RuntimeException ex) {
            throw fail(422, "INVALID_QUERY_AST", "literal 无法规范化", path, executionId);
        }
    }

    private Object normalizeSourceValue(RuntimeTypeResolver.PropertyDescriptor property, Object raw,
            String field, String executionId) {
        String type = canonicalType(property.valueType());
        try {
            return switch (type) {
                case "STRING", "TEXT", "ENUM" -> String.valueOf(raw);
                case "INTEGER" -> new BigDecimal(String.valueOf(raw)).toBigIntegerExact().toString();
                case "DECIMAL" -> {
                    BigDecimal number = new BigDecimal(String.valueOf(raw));
                    yield number.signum() == 0 ? "0" : number.stripTrailingZeros().toPlainString();
                }
                case "BOOLEAN" -> raw instanceof Boolean b ? b : parseBoolean(String.valueOf(raw));
                case "DATETIME" -> {
                    if (raw instanceof OffsetDateTime value) yield value.toInstant().toString();
                    if (raw instanceof Timestamp value) yield value.toInstant().toString();
                    if (raw instanceof Instant value) yield value.toString();
                    if (raw instanceof LocalDateTime value) yield value.toInstant(ZoneOffset.UTC).toString();
                    yield OffsetDateTime.parse(String.valueOf(raw)).toInstant().toString();
                }
                case "DATE" -> raw instanceof LocalDate date ? date.toString() : LocalDate.parse(String.valueOf(raw)).toString();
                case "UUID" -> UUID.fromString(String.valueOf(raw)).toString();
                default -> throw new IllegalArgumentException("unsupported value type");
            };
        } catch (RuntimeException ex) {
            throw fail(409, "BINDING_UNAVAILABLE", "来源值无法按已冻结属性类型进行无损转换；拒绝部分查询", field, executionId);
        }
    }

    private boolean parseBoolean(String value) {
        if ("true".equalsIgnoreCase(value) || "t".equalsIgnoreCase(value)) return true;
        if ("false".equalsIgnoreCase(value) || "f".equalsIgnoreCase(value)) return false;
        throw new IllegalArgumentException("invalid boolean");
    }

    private int compareValues(String type, Object left, Object right) {
        return switch (type) {
            case "INTEGER", "DECIMAL" -> new BigDecimal(String.valueOf(left)).compareTo(new BigDecimal(String.valueOf(right)));
            case "BOOLEAN" -> Boolean.compare((Boolean) left, (Boolean) right);
            case "DATETIME" -> Instant.parse(String.valueOf(left)).compareTo(Instant.parse(String.valueOf(right)));
            case "DATE" -> LocalDate.parse(String.valueOf(left)).compareTo(LocalDate.parse(String.valueOf(right)));
            default -> String.valueOf(left).compareTo(String.valueOf(right));
        };
    }

    private static int statusRank(String status) {
        return switch (status) { case "PRESENT" -> 0; case "NULL" -> 1; default -> 2; };
    }

    private String queryDigest(ParsedQuery query, DataBindingService.BindingDetail binding,
            RuntimeTypeResolver.TypeDescriptor type, String fingerprint, String executionId) {
        try {
            byte[] bytes = mapper.writeValueAsBytes(new DigestMaterial("local-query-v1", query.rootTypeRef(),
                    binding.bindingId(), binding.revision(), binding.digest(), type.payloadSha256(), fingerprint, query.canonicalSteps()));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (JsonProcessingException | NoSuchAlgorithmException ex) {
            throw fail(500, "QUERY_DIGEST_FAILED", "无法生成查询版本摘要", null, executionId);
        }
    }

    private long serializedBytes(List<ObjectSetItem> items, AggregateResult aggregate, String executionId) {
        try {
            return mapper.writeValueAsBytes(Map.of("items", items, "aggregate", aggregate == null ? Map.of() : aggregate)).length;
        } catch (JsonProcessingException ex) {
            throw fail(500, "QUERY_RESULT_SERIALIZATION_FAILED", "无法安全冻结查询结果", null, executionId);
        }
    }

    private synchronized void publishSnapshot(StoredSnapshot snapshot, String executionId) {
        cleanupExpired();
        long used = snapshots.values().stream().mapToLong(s -> s.bytes).sum();
        if (snapshots.size() >= MAX_SNAPSHOTS || used + snapshot.bytes > MAX_TOTAL_SNAPSHOT_BYTES) {
            throw fail(429, "QUERY_BUDGET_EXCEEDED",
                    "本机查询结果快照配额已满；未发布新结果，请等待过期清理后重新执行", "resultSnapshotRef", executionId);
        }
        snapshots.put(snapshot.id, snapshot);
    }

    private synchronized void cleanupExpired() {
        Instant now = Instant.now();
        Set<String> expired = snapshots.values().stream().filter(s -> !s.expiresAt.isAfter(now))
                .map(s -> s.id).collect(java.util.stream.Collectors.toSet());
        expired.forEach(snapshots::remove);
        cursors.entrySet().removeIf(entry -> expired.contains(entry.getValue().snapshotId())
                || !snapshots.containsKey(entry.getValue().snapshotId()));
    }

    private synchronized void invalidateSnapshot(String id) {
        snapshots.remove(id);
        cursors.entrySet().removeIf(entry -> id.equals(entry.getValue().snapshotId()));
    }

    private String actorKey(Authentication authentication) {
        if (authentication instanceof JwtAuthenticationToken token) {
            String issuer = token.getToken().getIssuer() == null ? "" : token.getToken().getIssuer().toString();
            String subject = token.getToken().getSubject() == null ? "" : token.getToken().getSubject();
            return "jwt:" + issuer + ":" + subject;
        }
        return "LOCAL_ISOLATED";
    }

    private static String canonicalType(String type) {
        if (type == null) return "";
        return switch (type.trim().replace('-', '_').toUpperCase(Locale.ROOT)) {
            case "TEXT", "VARCHAR", "CHAR" -> "STRING";
            case "INT", "LONG", "BIGINT", "SMALLINT" -> "INTEGER";
            case "NUMBER", "NUMERIC" -> "DECIMAL";
            case "BOOL" -> "BOOLEAN";
            case "TIMESTAMP", "TIMESTAMPTZ", "TIMESTAMP_WITH_TIME_ZONE", "TIMESTAMP_WITHOUT_TIME_ZONE" -> "DATETIME";
            default -> type.trim().replace('-', '_').toUpperCase(Locale.ROOT);
        };
    }

    private boolean isSupportedType(String type) {
        return Set.of("STRING", "TEXT", "INTEGER", "DECIMAL", "BOOLEAN", "ENUM", "DATETIME", "DATE", "UUID").contains(type);
    }

    private static String qi(String identifier) {
        if (identifier == null || !IDENT.matcher(identifier).matches()) throw new IllegalArgumentException("invalid identifier");
        return "\"" + identifier + "\"";
    }

    private static String qt(String schema, String table) { return qi(schema) + "." + qi(table); }

    private static boolean hasOnlyFields(JsonNode node, Set<String> allowed) {
        var names = node.fieldNames();
        while (names.hasNext()) if (!allowed.contains(names.next())) return false;
        return true;
    }

    private static UUID parseUuid(String value, String path, String executionId) {
        try { return UUID.fromString(value); }
        catch (RuntimeException ex) { throw fail(400, "INVALID_QUERY_AST", "bindingId 必须是 UUID", path, executionId); }
    }

    private static SQLException findSqlException(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof SQLException sql) return sql;
            current = current.getCause();
        }
        return null;
    }

    private static QueryException fail(int http, String code, String message, String field, String executionId) {
        return new QueryException(http, code, message, field, executionId);
    }
}
