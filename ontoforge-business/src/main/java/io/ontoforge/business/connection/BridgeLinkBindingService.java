package io.ontoforge.business.connection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.ontoforge.business.connection.LinkBindingService.BindingRef;
import io.ontoforge.business.connection.LinkBindingService.LinkFailure;
import io.ontoforge.business.connection.LinkBindingService.SourceRef;
import io.ontoforge.business.connection.LinkBindingService.ValidationScope;
import io.ontoforge.business.model.NativeSnapshotService;
import io.ontoforge.dao.connection.LinkBindingRepository;
import io.ontoforge.dao.connection.LinkBindingRepository.BridgeColumns;
import io.ontoforge.dao.connection.LinkBindingRepository.BridgeRevision;
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
import java.util.Comparator;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * M8.2: FULL, three-way, server-allowlisted BridgeTable admission and immutable
 * metadata commits. Foreign-key v1 is handled exclusively by LinkBindingService.
 *
 * SourcePG uses a separate READ ONLY / REPEATABLE_READ transaction; metadata
 * CAS and durable idempotency receipt use the existing Spring MetaDB transaction.
 * There is no claim of atomic transactions across the two databases.
 */
@Service
public class BridgeLinkBindingService {
    private static final int MAX_ROWS = 10_000;
    private static final int MAX_JOIN = 10_000;
    private static final long MAX_BYTES = 8L * 1024 * 1024;
    private static final long MAX_NANOS = 3_000_000_000L;
    private static final Pattern IDENT = Pattern.compile("^[a-z][a-z0-9_]{0,62}$");
    private static final Pattern SHA = Pattern.compile("^[0-9a-f]{64}$");
    private static final Pattern TYPE_ID = Pattern.compile("^[a-z][a-z0-9-]{1,63}$");

    private final LinkBindingRepository repository;
    private final DataBindingService bindings;
    private final NativeSnapshotService snapshots;
    private final B0AuthorizationService authorization;
    private final ObjectProvider<JdbcTemplate> sourceProvider;
    private final ObjectMapper mapper;

    public BridgeLinkBindingService(LinkBindingRepository repository, DataBindingService bindings,
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

    public record BridgeSourceRef(String sourceId, String schema, String table,
                                  String sourceFkColumn, String targetFkColumn) {}
    public record Fingerprints(String source, String target, String bridge) {}
    public record Request(String contractVersion, SourceRef modelSourceRef, String packageId,
            String linkTypeId, BindingRef sourceBindingRef, BindingRef targetBindingRef,
            String mode, BridgeSourceRef bridgeSourceRef, long expectedRevision,
            UUID idempotencyKey, Fingerprints expectedSchemaFingerprints,
            ValidationScope validationScope) {}
    public record Quality(long bridgePhysicalRows, long matchedPhysicalRows,
            long uniquePairCount, long duplicatePairRowCount,
            long nullBoth, long nullSourceOnly, long nullTargetOnly,
            long orphanSourceOnly, long orphanTargetOnly, long orphanBoth,
            long sourcePhysicalRows, long targetPhysicalRows, long joinCandidates) {}
    public record Validation(String status, String coverage, Instant checkedAt,
            String sourceSchemaFingerprint, String targetSchemaFingerprint,
            String bridgeSchemaFingerprint, Quality quality, String modelPayloadSha256,
            String linkTypeHash) {}
    public record Detail(String linkBindingId, long revision, String packageId,
            String snapshotId, String linkTypeId, String digest, boolean historical,
            String mappingMode, String validationStatus, BridgeSourceRef bridgeSourceRef,
            String sourceBindingId, long sourceBindingRevision, String sourceBindingDigest,
            String targetBindingId, long targetBindingRevision, String targetBindingDigest,
            String modelPayloadSha256, String linkTypeHash,
            String sourceSchemaFingerprint, String targetSchemaFingerprint,
            String bridgeSchemaFingerprint, JsonNode validationScope, JsonNode validationQuality,
            Instant checkedAt) {}

    private record Context(Request request, NativeSnapshotService.Snapshot snapshot,
            String linkTypeHash, DataBindingService.BindingDetail source,
            DataBindingService.BindingDetail target, DataBindingService.SourceTable sourceTable,
            DataBindingService.SourceTable targetTable, DataBindingService.SourceTable bridgeTable) {}

    private record Pair(String source, String target) {}
    private static final class Budget {
        int rows;
        long bytes;
        void add(String first, String second, long deadline) {
            rows++;
            bytes += size(first) + size(second);
            if (rows > MAX_ROWS || bytes > MAX_BYTES) {
                throw fail(429, "QUERY_BUDGET_EXCEEDED",
                        "三表 FULL 超过物理行/字节预算，不产生可保存资格", "validationScope");
            }
            checkDeadline(deadline);
        }
    }

    public Validation validate(Request request, Authentication auth) {
        return full(context(request, auth), auth);
    }

    @Transactional
    public Detail create(Request request, Authentication auth) {
        validateRequest(request);
        if (request.expectedRevision() != 0 || request.idempotencyKey() == null) {
            throw fail(422, "INVALID_CREATE_CONTRACT",
                    "新关系需要 expectedRevision=0 与操作幂等键", "expectedRevision");
        }
        authorization.requirePackage(auth, request.packageId(), B0AuthorizationService.Operation.WRITE_DRAFT);
        String digest = requestDigest(request);
        repository.lockOperationKey(request.packageId(), request.idempotencyKey());
        LinkBindingRepository.Operation existing = repository.findOperation(
                request.packageId(), request.idempotencyKey());
        if (existing != null) return recover(request, digest, existing, "CREATE", auth);
        repository.lockLinkIdentity(request.packageId(), request.modelSourceRef().snapshotId(),
                request.linkTypeId());
        Context c = context(request, auth);
        Validation validation = full(c, auth);
        if (repository.listHeads(request.packageId()).stream().anyMatch(head ->
                head.snapshotId().equals(request.modelSourceRef().snapshotId())
                && head.linkTypeId().equals(request.linkTypeId()))) {
            throw fail(409, "LINK_BINDING_ALREADY_EXISTS", "同一快照关系已有修订", "linkTypeId");
        }
        UUID id = UUID.randomUUID();
        repository.createHead(id, request.packageId(), request.modelSourceRef().snapshotId(),
                request.linkTypeId());
        persist(c, validation, id, 1L, digest, "CREATE");
        if (repository.advanceHead(id, request.packageId(), 0) != 1) {
            throw fail(409, "BINDING_REVISION_CONFLICT", "关系版本发生并发更新", "expectedRevision");
        }
        return detail(id, request.packageId(), 1L, false, auth);
    }

    @Transactional
    public Detail update(UUID id, Request request, Authentication auth) {
        validateRequest(request);
        if (request.expectedRevision() < 1 || request.idempotencyKey() == null) {
            throw fail(422, "INVALID_UPDATE_CONTRACT",
                    "更新需要 expectedRevision>=1 与操作幂等键", "expectedRevision");
        }
        authorization.requirePackage(auth, request.packageId(), B0AuthorizationService.Operation.WRITE_DRAFT);
        String digest = requestDigest(request);
        repository.lockOperationKey(request.packageId(), request.idempotencyKey());
        LinkBindingRepository.Operation existing = repository.findOperation(
                request.packageId(), request.idempotencyKey());
        if (existing != null) {
            if (!existing.linkBindingId().equals(id)) {
                throw fail(409, "IDEMPOTENCY_KEY_CONFLICT", "幂等键不能跨关系复用", "idempotencyKey");
            }
            return recover(request, digest, existing, "UPDATE", auth);
        }
        LinkBindingRepository.Head head = repository.lockHead(id, request.packageId());
        if (head == null) {
            throw fail(404, "LINK_BINDING_NOT_FOUND", "关系版本不存在或不可见", "linkBindingId");
        }
        if (head.revision() != request.expectedRevision()) {
            throw fail(409, "BINDING_REVISION_CONFLICT", "当前版本已改变", "expectedRevision");
        }
        if (!head.snapshotId().equals(request.modelSourceRef().snapshotId())
                || !head.linkTypeId().equals(request.linkTypeId())) {
            throw fail(422, "LINK_TYPE_MISMATCH", "更新不能改变冻结模型关系身份", "linkTypeId");
        }
        Context c = context(request, auth);
        Validation validation = full(c, auth);
        long next = head.revision() + 1;
        persist(c, validation, id, next, digest, "UPDATE");
        if (repository.advanceHead(id, request.packageId(), head.revision()) != 1) {
            throw fail(409, "BINDING_REVISION_CONFLICT", "并发版本推进失败", "expectedRevision");
        }
        return detail(id, request.packageId(), next, false, auth);
    }

    private Detail recover(Request request, String digest,
            LinkBindingRepository.Operation receipt, String operation, Authentication auth) {
        if (!operation.equals(receipt.operationType())
                || !digest.equals(receipt.requestDigest())) {
            throw fail(409, "IDEMPOTENCY_KEY_CONFLICT", "幂等键请求身份冲突", "idempotencyKey");
        }
        authorization.requirePackage(auth, request.packageId(), B0AuthorizationService.Operation.READ_DRAFT);
        return get(receipt.linkBindingId(), request.packageId(), receipt.revision(), auth);
    }

    public Detail get(UUID id, String packageId, Long selectedRevision, Authentication auth) {
        authorization.requirePackage(auth, packageId, B0AuthorizationService.Operation.READ_DRAFT);
        LinkBindingRepository.Head head = repository.listHeads(packageId).stream()
                .filter(value -> id.equals(value.id())).findFirst().orElse(null);
        if (head == null) {
            throw fail(404, "LINK_BINDING_NOT_FOUND", "关系版本不可见", "linkBindingId");
        }
        long revision = selectedRevision == null ? head.revision() : selectedRevision;
        if (revision < 1 || revision > head.revision()) {
            throw fail(404, "LINK_REVISION_NOT_FOUND", "指定修订不存在", "revision");
        }
        return detail(id, packageId, revision, revision != head.revision(), auth);
    }

    private Detail detail(UUID id, String packageId, long revision,
            boolean historical, Authentication auth) {
        Detail stored;
        try {
            stored = repository.revision((rs, number) -> {
                if (!"BRIDGE_TABLE".equals(rs.getString("mapping_mode"))) {
                    throw fail(422, "LINK_BINDING_MODE_MISMATCH",
                            "本接口仅访问 Bridge 修订", "revision");
                }
                return new Detail(id.toString(), revision, rs.getString("package_id"),
                        rs.getObject("snapshot_id", UUID.class).toString(), rs.getString("link_type_id"),
                        rs.getString("content_digest"), historical, rs.getString("mapping_mode"),
                        rs.getString("validation_status"),
                        new BridgeSourceRef(rs.getString("bridge_source_id"),
                                rs.getString("bridge_schema"), rs.getString("bridge_table"),
                                rs.getString("bridge_source_fk_column"),
                                rs.getString("bridge_target_fk_column")),
                        rs.getObject("source_binding_id", UUID.class).toString(),
                        rs.getLong("source_binding_revision"), rs.getString("source_binding_digest"),
                        rs.getObject("target_binding_id", UUID.class).toString(),
                        rs.getLong("target_binding_revision"), rs.getString("target_binding_digest"),
                        rs.getString("model_payload_sha256"), rs.getString("link_type_hash"),
                        rs.getString("source_schema_fingerprint"),
                        rs.getString("target_schema_fingerprint"),
                        rs.getString("bridge_schema_fingerprint"),
                        parse(rs.getString("validation_scope")), parse(rs.getString("validation_quality")),
                        rs.getTimestamp("checked_at").toInstant());
            }, id, packageId, revision);
        } catch (org.springframework.dao.EmptyResultDataAccessException ex) {
            throw fail(404, "LINK_REVISION_NOT_FOUND", "指定修订不存在", "revision");
        }
        // Do not disclose historical mapping/table/quality after access revoke or DDL drift.
        DataBindingService.BindingDetail source = bindings.getBinding(
                UUID.fromString(stored.sourceBindingId()), stored.sourceBindingRevision(), auth);
        DataBindingService.BindingDetail target = bindings.getBinding(
                UUID.fromString(stored.targetBindingId()), stored.targetBindingRevision(), auth);
        Map<String, DataBindingService.SourceTable> catalog = catalog(packageId, auth);
        if (!Objects.equals(catalog.get(source.sourceTable()) == null ? null
                        : catalog.get(source.sourceTable()).schemaFingerprint(),
                    stored.sourceSchemaFingerprint())
                || !Objects.equals(catalog.get(target.sourceTable()) == null ? null
                        : catalog.get(target.sourceTable()).schemaFingerprint(),
                    stored.targetSchemaFingerprint())
                || !Objects.equals(catalog.get(stored.bridgeSourceRef().table()) == null ? null
                        : catalog.get(stored.bridgeSourceRef().table()).schemaFingerprint(),
                    stored.bridgeSchemaFingerprint())) {
            throw fail(403, "LINK_SOURCE_UNAVAILABLE",
                    "来源权限或结构已变化，旧版本证据不可访问", "linkBindingId");
        }
        return stored;
    }

    private void persist(Context c, Validation v, UUID id, long revision,
            String requestDigest, String operation) {
        Request r = c.request();
        ObjectNode proof = mapper.createObjectNode();
        proof.put("linkBindingId", id.toString());
        proof.put("revision", revision);
        proof.put("snapshotId", c.snapshot().snapshotId());
        proof.put("packageId", r.packageId());
        proof.put("linkTypeHash", c.linkTypeHash());
        proof.put("sourceBindingDigest", c.source().digest());
        proof.put("targetBindingDigest", c.target().digest());
        proof.set("bridgeSourceRef", mapper.valueToTree(r.bridgeSourceRef()));
        proof.set("validation", mapper.valueToTree(v));
        String contentDigest = sha(proof);
        repository.insertBridgeRevision(new BridgeRevision(
                id, revision, r.modelSourceRef().snapshotId(),
                c.snapshot().payloadSha256(), c.linkTypeHash(),
                r.sourceBindingRef().bindingId(), r.sourceBindingRef().revision(), c.source().digest(),
                r.targetBindingRef().bindingId(), r.targetBindingRef().revision(), c.target().digest(),
                v.sourceSchemaFingerprint(), v.targetSchemaFingerprint(),
                "{\"kind\":\"VALIDATION_SCOPE\",\"coverage\":\"FULL\"}",
                mapper.valueToTree(v.quality()).toString(), v.status(),
                Timestamp.from(v.checkedAt()), contentDigest,
                new BridgeColumns(r.bridgeSourceRef().sourceId(), r.bridgeSourceRef().schema(),
                        r.bridgeSourceRef().table(), r.bridgeSourceRef().sourceFkColumn(),
                        r.bridgeSourceRef().targetFkColumn(), v.bridgeSchemaFingerprint())));
        repository.recordOperation(r.packageId(), id, r.idempotencyKey(),
                operation, requestDigest, revision);
    }

    private Context context(Request r, Authentication auth) {
        validateRequest(r);
        authorization.requirePackage(auth, r.packageId(), B0AuthorizationService.Operation.READ_DRAFT);
        authorization.requireSnapshot(auth, r.modelSourceRef().snapshotId(),
                B0AuthorizationService.Operation.READ_SNAPSHOT);
        NativeSnapshotService.Snapshot snapshot = snapshots.get(r.modelSourceRef().snapshotId());
        if (!r.packageId().equals(snapshot.packageId())) {
            throw fail(409, "LINK_SNAPSHOT_MISMATCH", "关系与模型包快照不一致", "modelSourceRef");
        }
        JsonNode selected = null;
        for (JsonNode link : snapshot.snapshotContent().path("linkTypes")) {
            if (r.linkTypeId().equals(link.path("id").asText())) {
                selected = link;
                break;
            }
        }
        if (selected == null || !"MANY_TO_MANY".equals(selected.path("cardinality").asText())) {
            throw fail(422, "UNSUPPORTED_LINK_CARDINALITY",
                    "桥表只能关联已冻结的 MANY_TO_MANY LinkType", "linkTypeId");
        }
        DataBindingService.BindingDetail source = bindings.getBinding(
                r.sourceBindingRef().bindingId(), r.sourceBindingRef().revision(), auth);
        DataBindingService.BindingDetail target = bindings.getBinding(
                r.targetBindingRef().bindingId(), r.targetBindingRef().revision(), auth);
        if (!sameEndpoint(source, snapshot, selected.path("sourceTypeRef").asText())
                || !sameEndpoint(target, snapshot, selected.path("targetTypeRef").asText())
                || !r.sourceBindingRef().digest().equals(source.digest())
                || !r.targetBindingRef().digest().equals(target.digest())) {
            throw fail(409, "LINK_ENDPOINT_VERSION_MISMATCH",
                    "端点 Binding 不匹配冻结 LinkType 或摘要", "sourceBindingRef");
        }
        if (!"VALID".equals(source.validationStatus()) || !"VALID".equals(target.validationStatus())
                || !DataBindingService.SOURCE_ID.equals(source.sourceId())
                || !source.sourceId().equals(target.sourceId())
                || !source.sourceId().equals(r.bridgeSourceRef().sourceId())
                || !"public".equals(source.sourceSchema())
                || !"public".equals(target.sourceSchema())) {
            throw fail(409, "LINK_ENDPOINT_UNAVAILABLE", "关系来源必须属于同一有效来源", "bridgeSourceRef");
        }

        Map<String, DataBindingService.SourceTable> catalog = catalog(r.packageId(), auth);
        DataBindingService.SourceTable sourceTable = catalog.get(source.sourceTable());
        DataBindingService.SourceTable targetTable = catalog.get(target.sourceTable());
        DataBindingService.SourceTable bridgeTable = catalog.get(r.bridgeSourceRef().table());
        if (sourceTable == null || targetTable == null || bridgeTable == null) {
            throw fail(403, "SOURCE_NOT_ALLOWED", "有来源表不可见或已失去只读授权", "bridgeSourceRef");
        }
        if (!Objects.equals(sourceTable.schemaFingerprint(), source.sourceSchemaFingerprint())
                || !Objects.equals(targetTable.schemaFingerprint(), target.sourceSchemaFingerprint())
                || !Objects.equals(sourceTable.schemaFingerprint(), r.expectedSchemaFingerprints().source())
                || !Objects.equals(targetTable.schemaFingerprint(), r.expectedSchemaFingerprints().target())
                || !Objects.equals(bridgeTable.schemaFingerprint(), r.expectedSchemaFingerprints().bridge())) {
            throw fail(409, "LINK_SOURCE_SCHEMA_DRIFT",
                    "三方来源结构发生变化，必须重新 FULL 核验", "expectedSchemaFingerprints");
        }
        DataBindingService.SourceColumn sourceKey = column(sourceTable, source.sourceKey());
        DataBindingService.SourceColumn targetKey = column(targetTable, target.sourceKey());
        DataBindingService.SourceColumn bridgeSource = column(bridgeTable, r.bridgeSourceRef().sourceFkColumn());
        DataBindingService.SourceColumn bridgeTarget = column(bridgeTable, r.bridgeSourceRef().targetFkColumn());
        if (sourceKey == null || targetKey == null || bridgeSource == null || bridgeTarget == null
                || !(sourceKey.primaryKey() || sourceKey.uniqueKey())
                || !(targetKey.primaryKey() || targetKey.uniqueKey())
                || !compatible(bridgeSource.udtName(), sourceKey.udtName())
                || !compatible(bridgeTarget.udtName(), targetKey.udtName())) {
            throw fail(422, "LINK_FK_INCOMPATIBLE",
                    "桥表双 FK 必须映射到已绑定、类型兼容的两个权威 sourceKey", "bridgeSourceRef");
        }
        return new Context(r, snapshot,
                NativeSnapshotService.sha256(NativeSnapshotService.canonicalize(selected.toString())),
                source, target, sourceTable, targetTable, bridgeTable);
    }

    private static boolean sameEndpoint(DataBindingService.BindingDetail binding,
            NativeSnapshotService.Snapshot snapshot, String type) {
        return snapshot.packageId().equals(binding.packageId())
                && snapshot.snapshotId().equals(binding.snapshotId())
                && snapshot.payloadSha256().equals(binding.payloadSha256())
                && type.equals(binding.objectTypeId());
    }
    private static DataBindingService.SourceColumn column(
            DataBindingService.SourceTable table, String column) {
        return table.columns().stream().filter(c -> c.name().equals(column)).findFirst().orElse(null);
    }
    private static boolean compatible(String left, String right) {
        if (Objects.equals(left, right)) return Set.of("text", "varchar", "int4", "int8").contains(left);
        return (Set.of("text", "varchar").contains(left) && Set.of("text", "varchar").contains(right))
                || (Set.of("int4", "int8").contains(left) && Set.of("int4", "int8").contains(right));
    }

    private Map<String, DataBindingService.SourceTable> catalog(String packageId, Authentication auth) {
        Map<String, DataBindingService.SourceTable> result = new HashMap<>();
        for (DataBindingService.SourceTable table : bindings.listTables(packageId, auth)) {
            result.put(table.table(), table);
        }
        return result;
    }

    private Validation full(Context c, Authentication auth) {
        JdbcTemplate source = sourceProvider.getIfAvailable();
        if (source == null || source.getDataSource() == null) {
            throw fail(503, "SOURCE_UNAVAILABLE", "未配置独立只读来源数据库", "bridgeSourceRef");
        }
        long deadline = System.nanoTime() + MAX_NANOS;
        try (Connection db = source.getDataSource().getConnection()) {
            db.setReadOnly(true);
            db.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            db.setAutoCommit(false);
            try {
                List<String> tables = new ArrayList<>(new HashSet<>(List.of(
                        c.sourceTable().table(), c.targetTable().table(), c.bridgeTable().table())));
                tables.sort(Comparator.naturalOrder());
                // AccessShare locks pin schema identity against concurrent DDL
                // for the full validation; SELECT permissions are checked by
                // the actual dedicated source DB principal, not the MetaDB.
                for (String table : tables) {
                    checkDeadline(deadline);
                    try (PreparedStatement lock = db.prepareStatement(
                            "LOCK TABLE public.\"" + table + "\" IN ACCESS SHARE MODE")) {
                        lock.setQueryTimeout(3);
                        lock.execute();
                    }
                }
                // Verify metadata again after establishing physical locks:
                // the first catalog discovery could have raced with DDL.
                Map<String, DataBindingService.SourceTable> locked = catalog(c.request().packageId(), auth);
                for (DataBindingService.SourceTable previous : List.of(
                        c.sourceTable(), c.targetTable(), c.bridgeTable())) {
                    if (locked.get(previous.table()) == null || !Objects.equals(
                            previous.schemaFingerprint(), locked.get(previous.table()).schemaFingerprint())) {
                        throw fail(409, "LINK_SOURCE_SCHEMA_DRIFT",
                                "来源结构在校验前发生变化", "expectedSchemaFingerprints");
                    }
                }
                Budget budget = new Budget();
                Set<String> sourceKeys = readKeys(db, c.sourceTable().table(),
                        c.source().sourceKey(), budget, deadline);
                Set<String> targetKeys = readKeys(db, c.targetTable().table(),
                        c.target().sourceKey(), budget, deadline);
                List<Pair> bridge = readPairs(db, c.bridgeTable().table(),
                        c.request().bridgeSourceRef().sourceFkColumn(),
                        c.request().bridgeSourceRef().targetFkColumn(), budget, deadline);
                long bothNull = 0, sourceNull = 0, targetNull = 0;
                long sourceOrphan = 0, targetOrphan = 0, bothOrphan = 0;
                long matched = 0, joined = 0;
                Set<Pair> uniquePairs = new HashSet<>();
                for (Pair row : bridge) {
                    checkDeadline(deadline);
                    if (row.source() == null && row.target() == null) { bothNull++; continue; }
                    if (row.source() == null) { sourceNull++; continue; }
                    if (row.target() == null) { targetNull++; continue; }
                    boolean left = sourceKeys.contains(row.source());
                    boolean right = targetKeys.contains(row.target());
                    if (!left && !right) { bothOrphan++; continue; }
                    if (!left) { sourceOrphan++; continue; }
                    if (!right) { targetOrphan++; continue; }
                    if (++joined > MAX_JOIN) {
                        throw fail(429, "QUERY_BUDGET_EXCEEDED",
                                "来源关联候选超过统一上限", "validationScope");
                    }
                    matched++;
                    uniquePairs.add(row);
                }
                Quality quality = new Quality(bridge.size(), matched, uniquePairs.size(),
                        matched - uniquePairs.size(), bothNull, sourceNull, targetNull,
                        sourceOrphan, targetOrphan, bothOrphan,
                        sourceKeys.size(), targetKeys.size(), joined);
                if (bothNull + sourceNull + targetNull + sourceOrphan + targetOrphan
                        + bothOrphan + matched != bridge.size()) {
                    throw fail(500, "LINK_QUALITY_INVARIANT", "FULL 物理行账本不守恒", "validationScope");
                }
                return new Validation(sourceOrphan + targetOrphan + bothOrphan == 0
                        ? "VALID" : "VALID_WITH_ORPHANS", "FULL", Instant.now(),
                        c.sourceTable().schemaFingerprint(), c.targetTable().schemaFingerprint(),
                        c.bridgeTable().schemaFingerprint(), quality,
                        c.snapshot().payloadSha256(), c.linkTypeHash());
            } finally {
                db.rollback();
            }
        } catch (SQLException ex) {
            throw fail(503, "SOURCE_UNAVAILABLE",
                    "Bridge FULL 来源读取失败，拒绝保存未完成结果", "bridgeSourceRef");
        }
    }

    private static Set<String> readKeys(Connection db, String table, String key,
            Budget budget, long deadline) throws SQLException {
        List<Pair> rows = fetch(db, table, key, null, budget, deadline);
        Set<String> ids = new HashSet<>();
        for (Pair value : rows) {
            if (value.source() == null || !ids.add(value.source())) {
                throw fail(409, "LINK_ENDPOINT_KEY_CONFLICT",
                        "同一 ObjectType 端点身份键缺失或重复", "sourceKey");
            }
        }
        return ids;
    }

    private static List<Pair> readPairs(Connection db, String table, String sourceKey,
            String targetKey, Budget budget, long deadline) throws SQLException {
        return fetch(db, table, sourceKey, targetKey, budget, deadline);
    }

    private static List<Pair> fetch(Connection db, String table, String firstColumn,
            String secondColumn, Budget budget, long deadline) throws SQLException {
        if (!IDENT.matcher(table).matches() || !IDENT.matcher(firstColumn).matches()
                || (secondColumn != null && !IDENT.matcher(secondColumn).matches())) {
            throw fail(422, "SOURCE_NOT_ALLOWED", "来源列标识符非法", "bridgeSourceRef");
        }
        String query = "SELECT \"" + firstColumn + "\""
                + (secondColumn == null ? "" : ", \"" + secondColumn + "\"")
                + " FROM public.\"" + table + "\" LIMIT ?";
        List<Pair> rows = new ArrayList<>();
        try (PreparedStatement statement = db.prepareStatement(query)) {
            statement.setInt(1, MAX_ROWS + 1);
            statement.setQueryTimeout(3);
            statement.setFetchSize(10);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    Pair row = new Pair(key(result.getObject(1)),
                            secondColumn == null ? null : key(result.getObject(2)));
                    budget.add(row.source(), row.target(), deadline);
                    rows.add(row);
                }
            }
        }
        return rows;
    }

    private static String key(Object raw) {
        if (raw == null) return null;
        if (raw instanceof Number value) {
            return new BigDecimal(value.toString()).stripTrailingZeros().toPlainString();
        }
        return raw.toString();
    }
    private static int size(String value) {
        return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length;
    }
    private static void checkDeadline(long deadline) {
        if (System.nanoTime() >= deadline) {
            throw fail(503, "SOURCE_TIMEOUT", "三方来源 FULL 超过 3 秒", "validationScope");
        }
    }

    private static void validateRequest(Request r) {
        if (r == null || !"local-link-binding-v2".equals(r.contractVersion())
                || r.modelSourceRef() == null || !"LOCAL_SNAPSHOT".equals(r.modelSourceRef().kind())
                || r.modelSourceRef().snapshotId() == null
                || r.packageId() == null || r.packageId().isBlank() || r.packageId().length() > 200
                || r.linkTypeId() == null || !TYPE_ID.matcher(r.linkTypeId()).matches()
                || r.sourceBindingRef() == null || r.targetBindingRef() == null
                || r.sourceBindingRef().bindingId() == null || r.targetBindingRef().bindingId() == null
                || r.sourceBindingRef().revision() < 1 || r.targetBindingRef().revision() < 1
                || !validSha(r.sourceBindingRef().digest()) || !validSha(r.targetBindingRef().digest())
                || !"BRIDGE_TABLE".equals(r.mode()) || r.bridgeSourceRef() == null
                || !DataBindingService.SOURCE_ID.equals(r.bridgeSourceRef().sourceId())
                || !"public".equals(r.bridgeSourceRef().schema())
                || !ident(r.bridgeSourceRef().table()) || !ident(r.bridgeSourceRef().sourceFkColumn())
                || !ident(r.bridgeSourceRef().targetFkColumn())
                || r.bridgeSourceRef().sourceFkColumn().equals(r.bridgeSourceRef().targetFkColumn())
                || r.expectedRevision() < 0 || r.idempotencyKey() == null
                || r.expectedSchemaFingerprints() == null
                || !validSha(r.expectedSchemaFingerprints().source())
                || !validSha(r.expectedSchemaFingerprints().target())
                || !validSha(r.expectedSchemaFingerprints().bridge())
                || r.validationScope() == null
                || !"VALIDATION_SCOPE".equals(r.validationScope().kind())
                || !"FULL".equals(r.validationScope().coverage())) {
            throw fail(422, "MALFORMED_LINK_CONTRACT",
                    "Bridge v2 必须具备完整三方来源与 FULL 校验合同", "request");
        }
    }
    private static boolean ident(String raw) {
        return raw != null && IDENT.matcher(raw).matches();
    }
    private static boolean validSha(String raw) {
        return raw != null && SHA.matcher(raw).matches();
    }
    private String requestDigest(Request r) {
        ObjectNode normalized = mapper.createObjectNode();
        normalized.put("packageId", r.packageId());
        normalized.set("modelSourceRef", mapper.valueToTree(r.modelSourceRef()));
        normalized.put("linkTypeId", r.linkTypeId());
        normalized.set("sourceBindingRef", mapper.valueToTree(r.sourceBindingRef()));
        normalized.set("targetBindingRef", mapper.valueToTree(r.targetBindingRef()));
        normalized.put("mode", r.mode());
        normalized.set("bridgeSourceRef", mapper.valueToTree(r.bridgeSourceRef()));
        normalized.put("expectedRevision", r.expectedRevision());
        normalized.set("expectedSchemaFingerprints", mapper.valueToTree(r.expectedSchemaFingerprints()));
        normalized.set("validationScope", mapper.valueToTree(r.validationScope()));
        return sha(normalized);
    }
    private String sha(JsonNode value) {
        return NativeSnapshotService.sha256(
                NativeSnapshotService.canonicalize(value.toString()));
    }
    private JsonNode parse(String json) {
        try { return mapper.readTree(json); }
        catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            throw fail(500, "LINK_STORAGE_INVALID", "冻结质量证据不可解析", "revision");
        }
    }
    private static LinkFailure fail(int status, String code, String message, String field) {
        return new LinkFailure(status, code, message, field);
    }
}
