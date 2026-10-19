package io.ontoforge.business.connection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.ontoforge.business.model.NativeSnapshotService;
import io.ontoforge.dao.connection.LinkBindingRepository;
import io.ontoforge.platform.identity.B0AuthorizationService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * M8.1 only: allowlisted LOCAL_SNAPSHOT single-FK link admission, FULL source
 * validation, immutable metadata revisions, CAS and durable operation receipts.
 * This is NOT the one-hop query executor; link facts are never materialized here.
 */
@Service
public class LinkBindingService {
    private static final int MAX_ROWS = 10_000;
    private static final long MAX_BYTES = 8L * 1024 * 1024;
    private static final Pattern IDENT = Pattern.compile("^[a-z][a-z0-9_]{0,62}$");
    private static final Pattern TYPE_ID = Pattern.compile("^[a-z][a-z0-9-]{1,63}$");
    private static final Pattern SHA = Pattern.compile("^[0-9a-f]{64}$");

    private final LinkBindingRepository repository;
    private final DataBindingService bindings;
    private final NativeSnapshotService snapshots;
    private final B0AuthorizationService authorization;
    private final ObjectProvider<JdbcTemplate> sourceProvider;
    private final ObjectMapper mapper;

    public LinkBindingService(LinkBindingRepository repository, DataBindingService bindings,
            NativeSnapshotService snapshots, B0AuthorizationService authorization,
            @Qualifier("bindingSourceJdbcTemplate") ObjectProvider<JdbcTemplate> sourceProvider,
            ObjectMapper mapper) {
        this.repository = repository;
        this.bindings = bindings;
        this.snapshots = snapshots;
        this.authorization = authorization;
        this.sourceProvider = sourceProvider;
        this.mapper = mapper;
    }

    public static final class LinkFailure extends RuntimeException {
        public final int http;
        public final String code;
        public final String fieldPath;
        public LinkFailure(int http, String code, String message, String fieldPath) {
            super(message);
            this.http = http;
            this.code = code;
            this.fieldPath = fieldPath;
        }
    }

    public record BindingRef(UUID bindingId, long revision, String digest) {}
    public record SourceRef(String kind, UUID snapshotId) {}
    public record Fingerprints(String source, String target) {}
    public record ValidationScope(String kind, String coverage) {}
    public record LinkRequest(String contractVersion, SourceRef modelSourceRef, String packageId,
            String linkTypeId, BindingRef sourceBindingRef, BindingRef targetBindingRef,
            String mode, String fkOwner, String fkSourceColumn, long expectedRevision,
            UUID idempotencyKey, Fingerprints expectedSchemaFingerprints,
            ValidationScope validationScope) {}
    public record LinkValidation(String status, String coverage, Instant checkedAt,
            int scannedRows, int matchedLinks, int nullFkCount, int orphanCount,
            String sourceSchemaFingerprint, String targetSchemaFingerprint,
            String referencedIdentityColumn, String modelPayloadSha256,
            String linkTypeHash) {}
    public record LinkDetail(String linkBindingId, long revision, String packageId,
            String snapshotId, String linkTypeId, String digest, boolean historical,
            String validationStatus, String fkOwner, String fkSourceColumn,
            String referencedIdentityColumn, String sourceBindingId, long sourceBindingRevision,
            String sourceBindingDigest, String targetBindingId, long targetBindingRevision,
            String targetBindingDigest, String modelPayloadSha256, String linkTypeHash,
            String sourceSchemaFingerprint, String targetSchemaFingerprint,
            JsonNode validationScope, JsonNode validationQuality,
            Instant checkedAt) {}

    private record Context(LinkRequest request, NativeSnapshotService.Snapshot snapshot,
            JsonNode linkType, String linkTypeHash, DataBindingService.BindingDetail source,
            DataBindingService.BindingDetail target, DataBindingService.SourceTable sourceTable,
            DataBindingService.SourceTable targetTable, String referencedColumn) {}
    private record Fact(String key, String fk) {}
    private record ReadResult(List<Fact> source, List<Fact> target, long sourceBytes) {}

    /** Shared admission budget across BOTH endpoint reads, before retaining each row. */
    private static final class RowBudget {
        private int rows;
        private long bytes;
        void accept(Fact value) {
            rows++;
            bytes += size(value);
            if (rows > MAX_ROWS || bytes > MAX_BYTES) {
                throw fail(429, "QUERY_BUDGET_EXCEEDED",
                        "双端 FULL 来源读取达到上限；不保存不完整校验", "validationScope");
            }
        }
    }
    private record CanonicalRequest(String packageId, String snapshotId, String linkTypeId,
            String sourceBindingId, long sourceRevision, String sourceDigest,
            String targetBindingId, long targetRevision, String targetDigest,
            String fkOwner, String fkColumn, String mode, String sourceFingerprint,
            String targetFingerprint, String validationScope, long expectedRevision) {}

    public LinkValidation validate(LinkRequest request, Authentication auth) {
        Context context = context(request, auth);
        return fullValidation(context);
    }

    @Transactional
    public LinkDetail create(LinkRequest request, Authentication auth) {
        if (request == null || request.expectedRevision() != 0 || request.idempotencyKey() == null) {
            throw fail(422, "INVALID_CREATE_CONTRACT", "创建需要 expectedRevision=0 和操作幂等键", "expectedRevision");
        }
        validateRequest(request);
        authorization.requirePackage(auth, request.packageId(), B0AuthorizationService.Operation.WRITE_DRAFT);
        String digest = requestDigest(request);
        // Always lock this operation UUID before reading its durable receipt.
        // A concurrent same-key retry must wait for the original transaction.
        repository.lockOperationKey(request.packageId(), request.idempotencyKey());
        LinkBindingRepository.Operation existing =
                repository.findOperation(request.packageId(), request.idempotencyKey());
        if (existing != null) return recover(request, digest, existing, "CREATE", auth);
        // Different operation keys creating the same LinkType also serialize.
        // This must precede listHeads / INSERT to remove the check-then-insert race.
        repository.lockLinkIdentity(request.packageId(),
                request.modelSourceRef().snapshotId(), request.linkTypeId());
        Context context = context(request, auth);
        LinkValidation validation = fullValidation(context);
        boolean taken = repository.listHeads(request.packageId()).stream().anyMatch(h ->
                h.snapshotId().toString().equals(request.modelSourceRef().snapshotId().toString())
                        && h.linkTypeId().equals(request.linkTypeId()));
        if (taken) throw fail(409, "LINK_BINDING_ALREADY_EXISTS",
                "此快照关系已有版本，请打开并以 CAS 创建新修订", "linkTypeId");
        UUID id = UUID.randomUUID();
        repository.createHead(id, request.packageId(), request.modelSourceRef().snapshotId(),
                request.linkTypeId());
        persist(context, validation, id, 1, digest, "CREATE");
        if (repository.advanceHead(id, request.packageId(), 0) != 1) {
            throw fail(409, "BINDING_REVISION_CONFLICT", "版本推进失败", "expectedRevision");
        }
        return detail(id, request.packageId(), 1, false);
    }

    @Transactional
    public LinkDetail update(UUID id, LinkRequest request, Authentication auth) {
        validateRequest(request);
        if (request.expectedRevision() < 1 || request.idempotencyKey() == null) {
            throw fail(422, "INVALID_UPDATE_CONTRACT", "修改需要当前修订和幂等键", "expectedRevision");
        }
        authorization.requirePackage(auth, request.packageId(), B0AuthorizationService.Operation.WRITE_DRAFT);
        String digest = requestDigest(request);
        // Always lock this operation UUID before reading its durable receipt.
        // A concurrent same-key retry must wait for the original transaction.
        repository.lockOperationKey(request.packageId(), request.idempotencyKey());
        LinkBindingRepository.Operation existing =
                repository.findOperation(request.packageId(), request.idempotencyKey());
        if (existing != null) {
            if (!existing.linkBindingId().equals(id)) {
                throw fail(409, "IDEMPOTENCY_KEY_CONFLICT", "相同幂等键不能跨关系复用", "idempotencyKey");
            }
            return recover(request, digest, existing, "UPDATE", auth);
        }
        LinkBindingRepository.Head head = repository.lockHead(id, request.packageId());
        if (head == null) throw fail(404, "LINK_BINDING_NOT_FOUND", "关系版本不存在或不可见", "linkBindingId");
        if (head.revision() != request.expectedRevision()) {
            throw fail(409, "BINDING_REVISION_CONFLICT", "版本已更新；当前编辑未覆盖服务器版本", "expectedRevision");
        }
        if (!head.snapshotId().equals(request.modelSourceRef().snapshotId())
                || !head.linkTypeId().equals(request.linkTypeId())) {
            throw fail(422, "LINK_TYPE_MISMATCH", "不可修改既有关系的模型身份", "linkTypeId");
        }
        Context context = context(request, auth);
        LinkValidation validation = fullValidation(context);
        long revision = head.revision() + 1;
        persist(context, validation, id, revision, digest, "UPDATE");
        if (repository.advanceHead(id, request.packageId(), head.revision()) != 1) {
            throw fail(409, "BINDING_REVISION_CONFLICT", "版本竞争失败", "expectedRevision");
        }
        return detail(id, request.packageId(), revision, false);
    }

    public List<LinkBindingRepository.Head> list(String packageId, Authentication auth) {
        authorization.requirePackage(auth, packageId, B0AuthorizationService.Operation.READ_DRAFT);
        return repository.listHeads(packageId);
    }

    public LinkDetail get(UUID id, String packageId, Long requestedRevision, Authentication auth) {
        authorization.requirePackage(auth, packageId, B0AuthorizationService.Operation.READ_DRAFT);
        LinkBindingRepository.Head head = repository.listHeads(packageId).stream()
                .filter(h -> h.id().equals(id)).findFirst().orElse(null);
        if (head == null) throw fail(404, "LINK_BINDING_NOT_FOUND", "关系绑定不存在或不可见", "linkBindingId");
        long revision = requestedRevision == null ? head.revision() : requestedRevision;
        if (revision < 1 || revision > head.revision()) {
            throw fail(404, "LINK_REVISION_NOT_FOUND", "不存在此修订", "revision");
        }
        LinkDetail stored = detail(id, packageId, revision, revision != head.revision());
        // A visible package alone cannot grant continued access to historical
        // source identities after a database SELECT grant has been revoked.
        DataBindingService.BindingDetail sourceBinding = bindings.getBinding(
                UUID.fromString(stored.sourceBindingId()), stored.sourceBindingRevision(), auth);
        DataBindingService.BindingDetail targetBinding = bindings.getBinding(
                UUID.fromString(stored.targetBindingId()), stored.targetBindingRevision(), auth);
        Set<String> visibleTables = new HashSet<>();
        for (DataBindingService.SourceTable table : bindings.listTables(packageId, auth)) {
            visibleTables.add(table.table());
        }
        if (!visibleTables.contains(sourceBinding.sourceTable())
                || !visibleTables.contains(targetBinding.sourceTable())) {
            throw fail(403, "LINK_SOURCE_UNAVAILABLE",
                    "当前无权读取完整关系来源；旧详情不可展示受限证据", "linkBindingId");
        }
        return stored;
    }

    private LinkDetail recover(LinkRequest request, String digest,
            LinkBindingRepository.Operation existing, String operationType, Authentication auth) {
        if (!existing.operationType().equals(operationType) || !existing.requestDigest().equals(digest)) {
            throw fail(409, "IDEMPOTENCY_KEY_CONFLICT", "同一个幂等键已用于不同请求", "idempotencyKey");
        }
        // Successful write can be replayed after an HTTP response is lost, but not
        // by a principal who no longer has package read permission.
        authorization.requirePackage(auth, request.packageId(), B0AuthorizationService.Operation.READ_DRAFT);
        return get(existing.linkBindingId(), request.packageId(), existing.revision(), auth);
    }

    private void persist(Context context, LinkValidation validation, UUID id,
                         long revision, String requestDigest, String operation) {
        LinkRequest request = context.request();
        String content = contentDigest(context, validation, id, revision);
        String quality = toJson(mapper.valueToTree(validation));
        repository.insertRevision(id, revision, request.modelSourceRef().snapshotId(),
                context.snapshot().payloadSha256(), context.linkTypeHash(),
                request.sourceBindingRef().bindingId(), request.sourceBindingRef().revision(), context.source().digest(),
                request.targetBindingRef().bindingId(), request.targetBindingRef().revision(), context.target().digest(),
                request.fkOwner(), request.fkSourceColumn(), context.referencedColumn(),
                validation.sourceSchemaFingerprint(), validation.targetSchemaFingerprint(),
                "{\"kind\":\"VALIDATION_SCOPE\",\"coverage\":\"FULL\"}", quality,
                validation.status(), Timestamp.from(validation.checkedAt()), content);
        repository.recordOperation(request.packageId(), id, request.idempotencyKey(),
                operation, requestDigest, revision);
    }

    private LinkDetail detail(UUID id, String packageId, long revision, boolean historical) {
        try {
            return repository.revision((rs, n) -> new LinkDetail(
                    id.toString(), revision, rs.getString("package_id"),
                    rs.getObject("snapshot_id", UUID.class).toString(), rs.getString("link_type_id"),
                    rs.getString("content_digest"), historical, rs.getString("validation_status"),
                    rs.getString("fk_owner"), rs.getString("fk_source_column"),
                    rs.getString("referenced_identity_column"), rs.getObject("source_binding_id", UUID.class).toString(),
                    rs.getLong("source_binding_revision"), rs.getString("source_binding_digest"),
                    rs.getObject("target_binding_id", UUID.class).toString(),
                    rs.getLong("target_binding_revision"), rs.getString("target_binding_digest"),
                    rs.getString("model_payload_sha256"), rs.getString("link_type_hash"),
                    rs.getString("source_schema_fingerprint"), rs.getString("target_schema_fingerprint"),
                    parseEvidence(rs.getString("validation_scope")),
                    parseEvidence(rs.getString("validation_quality")),
                    rs.getTimestamp("checked_at").toInstant()), id, packageId, revision);
        } catch (org.springframework.dao.EmptyResultDataAccessException ex) {
            throw fail(404, "LINK_REVISION_NOT_FOUND", "版本不可见或不存在", "revision");
        }
    }

    private JsonNode parseEvidence(String json) {
        try {
            return mapper.readTree(json);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw fail(500, "LINK_STORAGE_INVALID", "已保存的关系证据无法验证", "revision");
        }
    }

    private Context context(LinkRequest request, Authentication auth) {
        validateRequest(request);
        authorization.requirePackage(auth, request.packageId(), B0AuthorizationService.Operation.READ_DRAFT);
        authorization.requireSnapshot(auth, request.modelSourceRef().snapshotId(),
                B0AuthorizationService.Operation.READ_SNAPSHOT);
        NativeSnapshotService.Snapshot snapshot = snapshots.get(request.modelSourceRef().snapshotId());
        if (!snapshot.packageId().equals(request.packageId())) {
            throw fail(409, "LINK_SNAPSHOT_MISMATCH", "模型包与冻结快照不匹配", "modelSourceRef");
        }
        JsonNode selected = null;
        for (JsonNode link : snapshot.snapshotContent().path("linkTypes")) {
            if (request.linkTypeId().equals(link.path("id").asText())) {
                selected = link;
                break;
            }
        }
        if (selected == null) throw fail(422, "LINK_TYPE_NOT_FOUND", "快照没有对应关系类型", "linkTypeId");
        String cardinality = selected.path("cardinality").asText();
        if ("MANY_TO_MANY".equals(cardinality)
                || !Set.of("ONE_TO_ONE", "ONE_TO_MANY", "MANY_TO_ONE").contains(cardinality)) {
            throw fail(422, "UNSUPPORTED_LINK_CARDINALITY", "首阶段不支持桥表及多对多", "linkTypeId");
        }
        if (("TARGET".equals(request.fkOwner()) && "MANY_TO_ONE".equals(cardinality))
                || ("SOURCE".equals(request.fkOwner()) && "ONE_TO_MANY".equals(cardinality))) {
            throw fail(422, "INVALID_FK_DIRECTION", "关系基数与 FK 所在端不兼容", "fkOwner");
        }

        DataBindingService.BindingDetail source = bindings.getBinding(request.sourceBindingRef().bindingId(),
                request.sourceBindingRef().revision(), auth);
        DataBindingService.BindingDetail target = bindings.getBinding(request.targetBindingRef().bindingId(),
                request.targetBindingRef().revision(), auth);
        if (!sameEndpoint(source, snapshot, selected.path("sourceTypeRef").asText())
                || !sameEndpoint(target, snapshot, selected.path("targetTypeRef").asText())) {
            throw fail(409, "LINK_ENDPOINT_VERSION_MISMATCH", "两端绑定必须与冻结关系类型严格一致", "sourceBindingRef");
        }
        if ((request.sourceBindingRef().digest() != null
                && !request.sourceBindingRef().digest().equals(source.digest()))
                || (request.targetBindingRef().digest() != null
                && !request.targetBindingRef().digest().equals(target.digest()))) {
            throw fail(409, "BINDING_VERSION_DRIFT", "端点修订摘要不一致", "sourceBindingRef");
        }
        if (!"VALID".equals(source.validationStatus()) || !"VALID".equals(target.validationStatus())
                || !source.sourceId().equals(target.sourceId())
                || !DataBindingService.SOURCE_ID.equals(source.sourceId())) {
            throw fail(409, "LINK_ENDPOINT_UNAVAILABLE", "端点无效或不属于同一受控来源", "sourceBindingRef");
        }
        Map<String, DataBindingService.SourceTable> catalog = new HashMap<>();
        for (DataBindingService.SourceTable t : bindings.listTables(request.packageId(), auth)) {
            catalog.put(t.table(), t);
        }
        DataBindingService.SourceTable sourceTable = catalog.get(source.sourceTable());
        DataBindingService.SourceTable targetTable = catalog.get(target.sourceTable());
        if (sourceTable == null || targetTable == null
                || !source.sourceSchema().equals("public") || !target.sourceSchema().equals("public")) {
            throw fail(403, "SOURCE_NOT_ALLOWED", "关系端点不在当前受控来源列表", "sourceBindingRef");
        }
        if (!sourceTable.schemaFingerprint().equals(source.sourceSchemaFingerprint())
                || !targetTable.schemaFingerprint().equals(target.sourceSchemaFingerprint())
                || !sourceTable.schemaFingerprint().equals(request.expectedSchemaFingerprints().source())
                || !targetTable.schemaFingerprint().equals(request.expectedSchemaFingerprints().target())) {
            throw fail(409, "LINK_SOURCE_SCHEMA_DRIFT", "来源结构已变化，必须重新预演", "expectedSchemaFingerprints");
        }
        DataBindingService.BindingDetail fkBinding =
                "SOURCE".equals(request.fkOwner()) ? source : target;
        DataBindingService.BindingDetail referencedBinding =
                "SOURCE".equals(request.fkOwner()) ? target : source;
        DataBindingService.SourceTable fkTable =
                "SOURCE".equals(request.fkOwner()) ? sourceTable : targetTable;
        DataBindingService.SourceTable referencedTable =
                "SOURCE".equals(request.fkOwner()) ? targetTable : sourceTable;
        DataBindingService.SourceColumn fkColumn = column(fkTable, request.fkSourceColumn());
        DataBindingService.SourceColumn referenced = column(referencedTable, referencedBinding.sourceKey());
        if (fkColumn == null || referenced == null
                || !referenced.primaryKey() && !referenced.uniqueKey()
                || !compatible(fkColumn.udtName(), referenced.udtName())) {
            throw fail(422, "LINK_FK_INCOMPATIBLE", "FK 和对端权威 sourceKey 列类型不兼容", "fkSourceColumn");
        }
        return new Context(request, snapshot, selected,
                sha256(NativeSnapshotService.canonicalize(selected.toString())),
                source, target, sourceTable, targetTable, referencedBinding.sourceKey());
    }

    private static boolean sameEndpoint(DataBindingService.BindingDetail binding,
            NativeSnapshotService.Snapshot snapshot, String type) {
        return binding.packageId().equals(snapshot.packageId())
                && binding.snapshotId().equals(snapshot.snapshotId())
                && binding.payloadSha256().equals(snapshot.payloadSha256())
                && binding.objectTypeId().equals(type);
    }

    private static DataBindingService.SourceColumn column(DataBindingService.SourceTable table, String name) {
        return table.columns().stream().filter(c -> c.name().equals(name)).findFirst().orElse(null);
    }

    private static boolean compatible(String a, String b) {
        if (Objects.equals(a, b)) return Set.of("text", "varchar", "int8", "int4").contains(a);
        return Set.of("text", "varchar").contains(a) && Set.of("text", "varchar").contains(b)
                || Set.of("int4", "int8").contains(a) && Set.of("int4", "int8").contains(b);
    }

    private LinkValidation fullValidation(Context context) {
        JdbcTemplate source = sourceProvider.getIfAvailable();
        if (source == null || source.getDataSource() == null) {
            throw fail(503, "SOURCE_UNAVAILABLE", "独立只读来源尚未配置", "sourceId");
        }
        try (Connection db = source.getDataSource().getConnection()) {
            db.setReadOnly(true);
            db.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            db.setAutoCommit(false);
            try {
                ReadResult rows = readBounded(db, context);
                if (rows.source().size() + rows.target().size() > MAX_ROWS
                        || rows.sourceBytes() > MAX_BYTES) {
                    throw fail(429, "QUERY_BUDGET_EXCEEDED", "双端 FULL 校验超过统一预算", "validationScope");
                }
                Set<String> leftKeys = new HashSet<>();
                Set<String> rightKeys = new HashSet<>();
                for (Fact fact : rows.source()) {
                    if (!leftKeys.add(fact.key())) throw fail(409, "LINK_ENDPOINT_KEY_CONFLICT", "来源身份键冲突", "sourceBindingRef");
                }
                for (Fact fact : rows.target()) {
                    if (!rightKeys.add(fact.key())) throw fail(409, "LINK_ENDPOINT_KEY_CONFLICT", "目标身份键冲突", "targetBindingRef");
                }
                boolean sourceFk = "SOURCE".equals(context.request().fkOwner());
                List<Fact> owner = sourceFk ? rows.source() : rows.target();
                Set<String> referenced = sourceFk ? rightKeys : leftKeys;
                int nullCount = 0;
                int orphanCount = 0;
                int matched = 0;
                Map<String, Integer> reverseCounts = new HashMap<>();
                for (Fact fact : owner) {
                    if (fact.fk() == null) { nullCount++; continue; }
                    if (!referenced.contains(fact.fk())) { orphanCount++; continue; }
                    matched++;
                    int count = reverseCounts.merge(fact.fk(), 1, Integer::sum);
                    if ("ONE_TO_ONE".equals(context.linkType().path("cardinality").asText()) && count > 1) {
                        throw fail(409, "LINK_CARDINALITY_CONFLICT", "关系基数冲突，不能截断后保存", "fkSourceColumn");
                    }
                }
                return new LinkValidation(orphanCount == 0 ? "VALID" : "VALID_WITH_ORPHANS",
                        "FULL", Instant.now(), rows.source().size() + rows.target().size(),
                        matched, nullCount, orphanCount,
                        context.sourceTable().schemaFingerprint(),
                        context.targetTable().schemaFingerprint(), context.referencedColumn(),
                        context.snapshot().payloadSha256(), context.linkTypeHash());
            } finally {
                db.rollback();
            }
        } catch (SQLException ex) {
            throw fail(503, "SOURCE_UNAVAILABLE", "FULL 来源读取失败，未生成可保存资格", "validationScope");
        }
    }

    private ReadResult readBounded(Connection db, Context context) throws SQLException {
        RowBudget budget = new RowBudget();
        boolean sourceFk = "SOURCE".equals(context.request().fkOwner());
        List<Fact> left = fetch(db, context.source().sourceTable(), context.source().sourceKey(),
                sourceFk ? context.request().fkSourceColumn() : null, budget);
        List<Fact> right = fetch(db, context.target().sourceTable(), context.target().sourceKey(),
                sourceFk ? null : context.request().fkSourceColumn(), budget);
        return new ReadResult(left, right, budget.bytes);
    }

    private static List<Fact> fetch(Connection db, String table, String key, String fk,
                                    RowBudget budget) throws SQLException {
        if (!IDENT.matcher(table).matches() || !IDENT.matcher(key).matches()
                || fk != null && !IDENT.matcher(fk).matches()) {
            throw fail(422, "SOURCE_NOT_ALLOWED", "列名不属于受控数据库标识符", "fkSourceColumn");
        }
        String sql = "SELECT \"" + key + "\"" + (fk == null ? "" : ", \"" + fk + "\"")
                + " FROM public.\"" + table + "\" LIMIT ?";
        List<Fact> rows = new ArrayList<>();
        try (PreparedStatement statement = db.prepareStatement(sql)) {
            statement.setInt(1, MAX_ROWS + 1);
            statement.setQueryTimeout(3);
            statement.setFetchSize(10);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    Object keyValue = rs.getObject(1);
                    Object fkValue = fk == null ? null : rs.getObject(2);
                    if (keyValue == null) throw fail(409, "LINK_ENDPOINT_KEY_CONFLICT", "来源身份键不能为空", "sourceKey");
                    Fact fact = new Fact(value(keyValue), value(fkValue));
                    budget.accept(fact); // Fail before retaining excessive source rows/bytes.
                    rows.add(fact);
                }
            }
        }
        return rows;
    }

    private static String value(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return new BigDecimal(n.toString()).stripTrailingZeros().toPlainString();
        return v.toString();
    }
    private static int size(Fact f) {
        return f.key().getBytes(StandardCharsets.UTF_8).length
                + (f.fk() == null ? 0 : f.fk().getBytes(StandardCharsets.UTF_8).length);
    }

    private static void validateRequest(LinkRequest r) {
        if (r == null || !"local-link-binding-v1".equals(r.contractVersion())
                || r.modelSourceRef() == null || !"LOCAL_SNAPSHOT".equals(r.modelSourceRef().kind())
                || r.modelSourceRef().snapshotId() == null
                || r.packageId() == null || r.packageId().isBlank() || r.packageId().length() > 200
                || r.linkTypeId() == null || !TYPE_ID.matcher(r.linkTypeId()).matches()
                || r.sourceBindingRef() == null || r.targetBindingRef() == null
                || r.sourceBindingRef().bindingId() == null || r.targetBindingRef().bindingId() == null
                || r.sourceBindingRef().revision() < 1 || r.targetBindingRef().revision() < 1
                || !"FOREIGN_KEY".equals(r.mode())
                || !Set.of("SOURCE", "TARGET").contains(r.fkOwner() == null ? "" : r.fkOwner())
                || r.fkSourceColumn() == null || !IDENT.matcher(r.fkSourceColumn()).matches()
                || r.expectedRevision() < 0
                || r.expectedSchemaFingerprints() == null
                || r.expectedSchemaFingerprints().source() == null
                || r.expectedSchemaFingerprints().target() == null
                || !SHA.matcher(r.expectedSchemaFingerprints().source()).matches()
                || !SHA.matcher(r.expectedSchemaFingerprints().target()).matches()
                || r.validationScope() == null
                || !"VALIDATION_SCOPE".equals(r.validationScope().kind())
                || !"FULL".equals(r.validationScope().coverage())) {
            throw fail(422, "MALFORMED_LINK_CONTRACT", "仅接受带完整端点与 FULL 校验的版本化 FK 合同", "request");
        }
    }

    private String requestDigest(LinkRequest r) {
        CanonicalRequest normalized = new CanonicalRequest(r.packageId(),
                r.modelSourceRef().snapshotId().toString(), r.linkTypeId(),
                r.sourceBindingRef().bindingId().toString(), r.sourceBindingRef().revision(),
                r.sourceBindingRef().digest(),
                r.targetBindingRef().bindingId().toString(), r.targetBindingRef().revision(),
                r.targetBindingRef().digest(), r.fkOwner(), r.fkSourceColumn(), r.mode(),
                r.expectedSchemaFingerprints().source(), r.expectedSchemaFingerprints().target(),
                r.validationScope().coverage(), r.expectedRevision());
        return sha256(NativeSnapshotService.canonicalize(toJson(mapper.valueToTree(normalized))));
    }

    private String contentDigest(Context context, LinkValidation validation, UUID id, long revision) {
        ObjectNode content = mapper.createObjectNode();
        content.put("bindingId", id.toString());
        content.put("revision", revision);
        content.put("packageId", context.request().packageId());
        content.put("snapshotId", context.snapshot().snapshotId());
        content.put("linkTypeHash", context.linkTypeHash());
        content.put("sourceBindingDigest", context.source().digest());
        content.put("targetBindingDigest", context.target().digest());
        content.put("fkOwner", context.request().fkOwner());
        content.put("fkColumn", context.request().fkSourceColumn());
        content.put("referencedColumn", context.referencedColumn());
        content.set("validation", mapper.valueToTree(validation));
        return sha256(NativeSnapshotService.canonicalize(content.toString()));
    }

    private String toJson(JsonNode value) {
        return value.toString();
    }
    private static String sha256(byte[] bytes) {
        return NativeSnapshotService.sha256(bytes);
    }
    private static LinkFailure fail(int http, String code, String message, String field) {
        return new LinkFailure(http, code, message, field);
    }
}
