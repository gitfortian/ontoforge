package io.ontoforge.business.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.ontoforge.business.connection.DataBindingService;
import io.ontoforge.business.connection.LinkBindingService;
import io.ontoforge.business.model.NativeSnapshotService;
import io.ontoforge.platform.identity.B0AuthorizationService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

/**
 * M8.1 strict execution slice: one rooted ObjectRef and exactly one Traverse
 * step, terminating in PAGE. The service refuses unsupported filters/operators
 * and pages requiring a cursor instead of returning a misleading truncated page.
 * No generic graph traversal, SQL supplied by callers, or M7 v1 mutation.
 */
@Service
public class LinkTraverseService {
    private static final Pattern IDENT = Pattern.compile("[a-z][a-z0-9_]{0,62}");
    private static final int MAX_ROWS = 10_000;
    private static final long MAX_BYTES = 8L * 1024 * 1024;
    private static final int MAX_LINKS = 10_000;
    private static final int DEADLINE_SECONDS = 3;

    private final LinkBindingService links;
    private final DataBindingService bindings;
    private final NativeSnapshotService snapshots;
    private final B0AuthorizationService authorization;
    private final ObjectProvider<JdbcTemplate> sourceProvider;
    private final ObjectMapper mapper;

    public LinkTraverseService(LinkBindingService links, DataBindingService bindings,
            NativeSnapshotService snapshots, B0AuthorizationService authorization,
            @Qualifier("bindingSourceJdbcTemplate") ObjectProvider<JdbcTemplate> sourceProvider,
            ObjectMapper mapper) {
        this.links = links;
        this.bindings = bindings;
        this.snapshots = snapshots;
        this.authorization = authorization;
        this.sourceProvider = sourceProvider;
        this.mapper = mapper;
    }

    public static final class TraverseFailure extends RuntimeException {
        public final int http;
        public final String code;
        public final String fieldPath;
        public TraverseFailure(int http, String code, String message, String fieldPath) {
            super(message);
            this.http = http;
            this.code = code;
            this.fieldPath = fieldPath;
        }
    }

    private record Fact(String key, String fk) {}
    private record SourceRows(List<Fact> source, List<Fact> target, long bytes) {}
    private record Requested(String packageId, String snapshotId, String rootTypeId,
            String rootBindingId, long rootBindingRevision, String rootDomain,
            String rootKeyType, String rootKey, String direction,
            String linkTypeId, String linkBindingId, long linkBindingRevision,
            int pageSize, int maxRows, int maxJoin, int maxLinks,
            long maxSourceBytes, long maxResultBytes, int timeout) {}
    private static final class Budget {
        int rows;
        long bytes;
        void accept(Fact fact, Requested request, long deadline) {
            rows++;
            bytes += utf8(fact.key()) + utf8(fact.fk());
            if (rows > request.maxRows() || bytes > request.maxSourceBytes()
                    || System.nanoTime() > deadline) {
                throw fail(System.nanoTime() > deadline ? 503 : 429,
                        System.nanoTime() > deadline ? "QUERY_TIMEOUT" : "QUERY_BUDGET_EXCEEDED",
                        "双端真实来源扫描超过总预算，不会以截断结果返回成功", "limits");
            }
        }
    }

    public ObjectNode execute(JsonNode json, Authentication auth) {
        long deadline = System.nanoTime() + DEADLINE_SECONDS * 1_000_000_000L;
        Requested request = parse(json);
        authorization.requirePackage(auth, request.packageId(), B0AuthorizationService.Operation.READ_DRAFT);
        UUID snapshotId = UUID.fromString(request.snapshotId());
        authorization.requireSnapshot(auth, snapshotId, B0AuthorizationService.Operation.READ_SNAPSHOT);
        NativeSnapshotService.Snapshot snapshot = snapshots.get(snapshotId);
        if (!snapshot.packageId().equals(request.packageId())) {
            throw fail(409, "TRAVERSE_SNAPSHOT_MISMATCH", "请求包和不可变模型快照不符", "rootTypeRef");
        }
        LinkBindingService.LinkDetail link = links.get(UUID.fromString(request.linkBindingId()),
                request.packageId(), request.linkBindingRevision(), auth);
        if (link.historical() || !link.snapshotId().equals(request.snapshotId())
                || !link.linkTypeId().equals(request.linkTypeId())
                || !link.modelPayloadSha256().equals(snapshot.payloadSha256())
                || !"FULL".equals(link.validationScope().path("coverage").asText())
                || !Set.of("VALID", "VALID_WITH_ORPHANS").contains(link.validationStatus())) {
            throw fail(409, "LINK_VERSION_UNAVAILABLE",
                    "关系版本历史/快照不一致；请刷新当前 LinkBinding", "steps");
        }
        DataBindingService.BindingDetail sourceBinding = bindings.getBinding(
                UUID.fromString(link.sourceBindingId()), link.sourceBindingRevision(), auth);
        DataBindingService.BindingDetail targetBinding = bindings.getBinding(
                UUID.fromString(link.targetBindingId()), link.targetBindingRevision(), auth);
        if (!link.sourceBindingDigest().equals(sourceBinding.digest())
                || !link.targetBindingDigest().equals(targetBinding.digest())
                || !sourceBinding.snapshotId().equals(request.snapshotId())
                || !targetBinding.snapshotId().equals(request.snapshotId())
                || !sourceBinding.packageId().equals(request.packageId())
                || !targetBinding.packageId().equals(request.packageId())) {
            throw fail(409, "LINK_ENDPOINT_VERSION_MISMATCH",
                    "查询时 LinkBinding 与双端已冻结 Binding 证据不一致", "rootBindingRef");
        }

        boolean out = "OUT".equals(request.direction());
        DataBindingService.BindingDetail root = out ? sourceBinding : targetBinding;
        DataBindingService.BindingDetail output = out ? targetBinding : sourceBinding;
        if (!root.bindingId().equals(request.rootBindingId())
                || root.revision() != request.rootBindingRevision()
                || !root.objectTypeId().equals(request.rootTypeId())
                || !root.identityDomainId().equals(request.rootDomain())) {
            throw fail(409, "ROOT_BINDING_MISMATCH", "根 ObjectRef 与当前端点 Binding 身份不符", "rootBindingRef");
        }
        String keyType = primaryKeyType(snapshot.snapshotContent(), root.objectTypeId());
        String outputKeyType = primaryKeyType(snapshot.snapshotContent(), output.objectTypeId());
        String sourceKeyType = primaryKeyType(snapshot.snapshotContent(), sourceBinding.objectTypeId());
        String targetKeyType = primaryKeyType(snapshot.snapshotContent(), targetBinding.objectTypeId());
        if (!keyType.equals(request.rootKeyType())) {
            throw fail(422, "INVALID_ROOT_OBJECT_REF", "根键类型必须等于冻结 ObjectType 的主键类型", "rootSelector");
        }

        List<DataBindingService.SourceTable> catalog = bindings.listTables(request.packageId(), auth);
        Map<String, DataBindingService.SourceTable> tables = new HashMap<>();
        catalog.forEach(table -> tables.put(table.table(), table));
        DataBindingService.SourceTable left = tables.get(sourceBinding.sourceTable());
        DataBindingService.SourceTable right = tables.get(targetBinding.sourceTable());
        if (left == null || right == null) {
            throw fail(403, "SOURCE_NOT_ALLOWED", "双端来源不在当前受控清单中", "rootBindingRef");
        }
        if (!left.schemaFingerprint().equals(link.sourceSchemaFingerprint())
                || !right.schemaFingerprint().equals(link.targetSchemaFingerprint())
                || !left.schemaFingerprint().equals(sourceBinding.sourceSchemaFingerprint())
                || !right.schemaFingerprint().equals(targetBinding.sourceSchemaFingerprint())) {
            throw fail(409, "LINK_SOURCE_SCHEMA_DRIFT",
                    "一个端点来源结构已变化；不返回旧版本的关联结果", "rootBindingRef");
        }

        String sourceFk = "SOURCE".equals(link.fkOwner()) ? link.fkSourceColumn() : null;
        String targetFk = "TARGET".equals(link.fkOwner()) ? link.fkSourceColumn() : null;
        ensureColumn(left, sourceBinding.sourceKey());
        ensureColumn(right, targetBinding.sourceKey());
        if (sourceFk != null) {
            ensureColumn(left, sourceFk);
            if (!targetBinding.sourceKey().equals(link.referencedIdentityColumn())) {
                throw fail(409, "LINK_MAPPING_DRIFT", "已保存 FK 与被引用身份列不符", "steps");
            }
        } else {
            ensureColumn(right, targetFk);
            if (!sourceBinding.sourceKey().equals(link.referencedIdentityColumn())) {
                throw fail(409, "LINK_MAPPING_DRIFT", "已保存 FK 与被引用身份列不符", "steps");
            }
        }

        SourceRows rows = readSource(request, sourceBinding, targetBinding, sourceFk, targetFk, deadline);
        Map<String, Fact> leftIndex = uniqueIndex(rows.source());
        Map<String, Fact> rightIndex = uniqueIndex(rows.target());
        boolean rootExists = out ? leftIndex.containsKey(request.rootKey())
                : rightIndex.containsKey(request.rootKey());
        if (!rootExists) throw fail(404, "ROOT_OBJECT_NOT_FOUND", "当前来源中不存在指定根对象", "rootSelector");

        String readSourceId = UUID.randomUUID().toString();
        String readTargetId = UUID.randomUUID().toString();
        ArrayNode resultObjects = mapper.createArrayNode();
        ArrayNode resultLinks = mapper.createArrayNode();
        Set<String> distinctOutput = new HashSet<>();
        Set<String> distinctLink = new HashSet<>();
        boolean rootOwner = (out && sourceFk != null) || (!out && targetFk != null);
        int orphans = 0;
        int candidates = 0;
        // Iterate saved SOURCE/TARGET roles; traversal direction only determines
        // membership of the requested root and which endpoint is returned.
        for (Fact source : rows.source()) {
            if (System.nanoTime() > deadline) throw fail(503, "QUERY_TIMEOUT", "一跳执行已超时", "limits");
            if (sourceFk != null) {
                if (source.fk() == null) continue;
                Fact target = rightIndex.get(source.fk());
                if (target == null) {
                    if (rootOwner && source.key().equals(request.rootKey())) orphans++;
                    continue;
                }
                if ((out && source.key().equals(request.rootKey()))
                        || (!out && target.key().equals(request.rootKey()))) {
                    candidates++;
                    addEdge(request, link, sourceBinding, targetBinding, source, target,
                            outputKeyType, sourceKeyType, targetKeyType, out, readSourceId, readTargetId,
                            distinctOutput, distinctLink, resultObjects, resultLinks);
                }
            }
        }
        if (targetFk != null) {
            for (Fact target : rows.target()) {
                if (System.nanoTime() > deadline) throw fail(503, "QUERY_TIMEOUT", "一跳执行已超时", "limits");
                if (target.fk() == null) continue;
                Fact source = leftIndex.get(target.fk());
                if (source == null) {
                    if (rootOwner && target.key().equals(request.rootKey())) orphans++;
                    continue;
                }
                if ((out && source.key().equals(request.rootKey()))
                        || (!out && target.key().equals(request.rootKey()))) {
                    candidates++;
                    addEdge(request, link, sourceBinding, targetBinding, source, target,
                            outputKeyType, sourceKeyType, targetKeyType, out, readSourceId, readTargetId,
                            distinctOutput, distinctLink, resultObjects, resultLinks);
                }
            }
        }
        if (candidates > request.maxJoin() || distinctLink.size() > request.maxLinks()) {
            throw fail(429, "QUERY_BUDGET_EXCEEDED", "一跳候选或 LinkFact 超过预算", "limits");
        }
        if (resultObjects.size() > request.pageSize()) {
            // Cursor snapshot infrastructure is not yet available in this slice.
            // Do NOT produce a partial first page as COMPLETE or misuse M7 cursor.
            throw fail(422, "TRAVERSE_PAGING_NOT_READY",
                    "该根对象关联超出一页，安全结果冻结分页尚未完成；本次拒绝截断", "steps");
        }
        Instant now = Instant.now();
        ArrayNode bindingVersions = mapper.createArrayNode();
        bindingVersion(bindingVersions, sourceBinding.bindingId(), sourceBinding.revision(),
                sourceBinding.digest(), "OBJECT");
        bindingVersion(bindingVersions, targetBinding.bindingId(), targetBinding.revision(),
                targetBinding.digest(), "OBJECT");
        bindingVersion(bindingVersions, link.linkBindingId(), link.revision(), link.digest(), "LINK");
        ArrayNode fingerprints = mapper.createArrayNode();
        fingerprint(fingerprints, sourceBinding, left, "SOURCE");
        fingerprint(fingerprints, targetBinding, right, "TARGET");
        ObjectNode versionLock = mapper.createObjectNode();
        versionLock.put("snapshotId", request.snapshotId());
        versionLock.put("payloadSha256", snapshot.payloadSha256());
        versionLock.set("bindingVersions", bindingVersions.deepCopy());
        versionLock.set("sourceSchemaFingerprints", fingerprints);

        ArrayNode sourceReadRefs = mapper.createArrayNode();
        sourceRead(sourceReadRefs, readSourceId, sourceBinding, left, sourceFk, "SOURCE", now);
        sourceRead(sourceReadRefs, readTargetId, targetBinding, right, targetFk, "TARGET", now);

        ObjectNode result = mapper.createObjectNode();
        result.put("status", "SUCCESS");
        result.put("resultKind", "PAGE");
        result.put("executionId", UUID.randomUUID().toString());
        result.put("executedAt", now.toString());
        result.put("queryVersion", "local-query-v2");
        result.set("rootTypeRef", responseTypeRef(request.snapshotId(), request.packageId(), root.objectTypeId()));
        result.set("outputTypeRef", responseTypeRef(request.snapshotId(), request.packageId(), output.objectTypeId()));
        result.set("versionLock", versionLock);
        result.set("bindingVersions", bindingVersions);
        result.set("sourceReadRefs", sourceReadRefs);
        result.put("queryDigest", NativeSnapshotService.sha256(
                NativeSnapshotService.canonicalize(json.toString())));
        result.put("resultSnapshotRef", "local-result:" + UUID.randomUUID());
        result.put("expiresAt", now.plusSeconds(300).toString());
        ObjectNode quality = result.putObject("quality");
        quality.put("accuracy", "EXACT");
        quality.put("completeness", orphans == 0 ? "COMPLETE" : "PARTIAL");
        quality.put("freshness", "UNKNOWN");
        quality.put("answerScope", orphans == 0 ? "REQUESTED_SCOPE" : "KNOWN_SUBSET");
        quality.put("scopeKind", "TRAVERSAL_SCOPE");
        quality.put("knownOrphanCount", orphans);
        ObjectNode budget = result.putObject("budget");
        budget.put("physicalRows", rows.source().size() + rows.target().size());
        budget.put("joinCandidates", candidates);
        budget.put("distinctObjects", distinctOutput.size());
        budget.put("distinctLinks", distinctLink.size());
        budget.put("sourceBytes", rows.bytes());
        budget.put("serializedResultBytes", 0);
        budget.put("maxPhysicalRows", request.maxRows());
        budget.put("maxJoinCandidates", request.maxJoin());
        budget.put("maxSourceBytes", request.maxSourceBytes());
        budget.put("maxSerializedResultBytes", request.maxResultBytes());
        budget.put("queryTimeoutSeconds", request.timeout());
        result.set("objects", resultObjects);
        result.set("links", resultLinks);
        result.put("objectCount", distinctOutput.size());
        result.put("linkCount", distinctLink.size());
        result.put("pathCount", distinctLink.size());
        result.put("pageSize", request.pageSize());
        result.put("pageNumber", 1);
        long resultBytes = result.toString().getBytes(StandardCharsets.UTF_8).length;
        budget.put("serializedResultBytes", resultBytes);
        if (result.toString().getBytes(StandardCharsets.UTF_8).length > request.maxResultBytes()) {
            throw fail(429, "QUERY_BUDGET_EXCEEDED", "响应超过 8MiB 预算", "limits");
        }
        // Final current evidence recheck BEFORE a successful payload escapes.
        LinkBindingService.LinkDetail current = links.get(
                UUID.fromString(link.linkBindingId()), request.packageId(), link.revision(), auth);
        if (!current.digest().equals(link.digest())) {
            throw fail(409, "LINK_VERSION_UNAVAILABLE", "查询执行期间关系版本变化", "steps");
        }
        if (System.nanoTime() > deadline) throw fail(503, "QUERY_TIMEOUT", "来源执行整体超时", "limits");
        return result;
    }

    private void addEdge(Requested r, LinkBindingService.LinkDetail link,
            DataBindingService.BindingDetail sourceBinding, DataBindingService.BindingDetail targetBinding,
            Fact source, Fact target, String outputKeyType,
            String sourceKeyType, String targetKeyType, boolean out,
            String sourceReadId, String targetReadId, Set<String> objects, Set<String> links,
            ArrayNode resultObjects, ArrayNode resultLinks) {
        String key = source.key() + "\u0000" + target.key();
        if (!links.add(key)) return;
        if (links.size() > r.maxLinks()) throw fail(429, "QUERY_BUDGET_EXCEEDED", "LinkFact 超预算", "limits");
        if (objects.add(out ? target.key() : source.key())) {
            ObjectNode item = resultObjects.addObject();
            item.set("objectRef", objectRef(out ? targetBinding : sourceBinding,
                    out ? target.key() : source.key(), outputKeyType));
            item.putArray("properties");
        }
        ObjectNode fact = resultLinks.addObject();
        fact.put("linkTypeId", link.linkTypeId());
        fact.put("linkBindingId", link.linkBindingId());
        fact.put("linkBindingRevision", link.revision());
        fact.set("source", objectRef(sourceBinding, source.key(), sourceKeyType));
        fact.set("target", objectRef(targetBinding, target.key(), targetKeyType));
        ArrayNode refs = fact.putArray("sourceReadRefs");
        refs.add(sourceReadId);
        refs.add(targetReadId);
    }

    private static ObjectNode objectRef(DataBindingService.BindingDetail binding, String key, String type) {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode ref = mapper.createObjectNode();
        ref.put("packageId", binding.packageId());
        ref.put("objectTypeId", binding.objectTypeId());
        ref.put("identityDomainId", binding.identityDomainId());
        ObjectNode k = ref.putObject("key");
        k.put("valueType", type);
        k.put("value", key);
        return ref;
    }

    private static Requested parse(JsonNode body) {
        if (!fields(body, Set.of("queryVersion", "rootTypeRef", "rootBindingRef",
                "rootSelector", "steps", "limits"))
                || !"local-query-v2".equals(body.path("queryVersion").asText())
                || !fields(body.path("rootTypeRef"), Set.of("sourceRef", "packageId", "objectTypeId"))
                || !fields(body.path("rootTypeRef").path("sourceRef"), Set.of("kind", "snapshotId"))
                || !fields(body.path("rootBindingRef"), Set.of("bindingId", "revision"))
                || !fields(body.path("rootSelector"), Set.of("kind", "objectRef"))
                || !fields(body.path("rootSelector").path("objectRef"),
                        Set.of("packageId", "objectTypeId", "identityDomainId", "key"))
                || !fields(body.path("rootSelector").path("objectRef").path("key"), Set.of("valueType", "value"))
                || !"OBJECT_REF".equals(body.path("rootSelector").path("kind").asText())
                || !"LOCAL_SNAPSHOT".equals(body.path("rootTypeRef").path("sourceRef").path("kind").asText())
                || !body.path("steps").isArray() || body.path("steps").size() != 2
                || !fields(body.path("steps").get(0), Set.of("kind", "linkTypeRef",
                        "linkBindingRef", "direction", "maxDepth"))
                || !fields(body.path("steps").get(0).path("linkBindingRef"),
                        Set.of("bindingId", "revision"))
                || !fields(body.path("steps").get(1), Set.of("kind", "pageSize"))
                || !"TRAVERSE".equals(body.path("steps").get(0).path("kind").asText())
                || !"PAGE".equals(body.path("steps").get(1).path("kind").asText())
                || body.path("steps").get(0).path("maxDepth").asInt() != 1) {
            throw fail(422, "UNSUPPORTED_TRAVERSE_AST",
                    "当前仅支持 OBJECT_REF + 一条 TRAVERSE + PAGE，其他 AST 不会被静默忽略", "steps");
        }
        try {
            JsonNode type = body.path("rootTypeRef");
            JsonNode root = body.path("rootSelector").path("objectRef");
            JsonNode step = body.path("steps").get(0);
            JsonNode limit = body.path("limits");
            if (!limit.isMissingNode() && !fields(limit, Set.of("maxPhysicalRows",
                    "maxJoinCandidates", "maxLinks", "maxSourceBytes",
                    "maxSerializedResultBytes", "timeoutSeconds"))) {
                throw new IllegalArgumentException("limits");
            }
            int maxRows = lowered(limit,"maxPhysicalRows",MAX_ROWS);
            int maxJoin = lowered(limit,"maxJoinCandidates",MAX_ROWS);
            int maxLinks = lowered(limit,"maxLinks",MAX_LINKS);
            long maxSource = lowered(limit,"maxSourceBytes",MAX_BYTES);
            long maxResult = lowered(limit,"maxSerializedResultBytes",MAX_BYTES);
            int timeout = lowered(limit,"timeoutSeconds",DEADLINE_SECONDS);
            String rootKeyType = required(root.path("key").path("valueType"));
            if (!Set.of("STRING", "INTEGER").contains(rootKeyType)) throw new IllegalArgumentException("root key type");
            String rootKey = required(root.path("key").path("value"));
            int pageSize = body.path("steps").get(1).path("pageSize").asInt();
            if (pageSize < 1 || pageSize > 50 || rootKey.length() > 4096
                    || !Set.of("OUT","IN").contains(required(step.path("direction")))
                    || !required(root.path("packageId")).equals(required(type.path("packageId")))
                    || !required(root.path("objectTypeId")).equals(required(type.path("objectTypeId")))) {
                throw new IllegalArgumentException("root or page mismatch");
            }
            UUID.fromString(required(root.path("identityDomainId")));
            UUID.fromString(required(type.path("sourceRef").path("snapshotId")));
            UUID.fromString(required(body.path("rootBindingRef").path("bindingId")));
            UUID.fromString(required(step.path("linkBindingRef").path("bindingId")));
            long rootRevision = body.path("rootBindingRef").path("revision").asLong();
            long linkRevision = step.path("linkBindingRef").path("revision").asLong();
            if (rootRevision < 1 || linkRevision < 1) throw new IllegalArgumentException("revision");
            return new Requested(required(type.path("packageId")), required(type.path("sourceRef").path("snapshotId")),
                    required(type.path("objectTypeId")),
                    required(body.path("rootBindingRef").path("bindingId")), rootRevision,
                    required(root.path("identityDomainId")), rootKeyType, rootKey,
                    required(step.path("direction")), required(step.path("linkTypeRef")),
                    required(step.path("linkBindingRef").path("bindingId")), linkRevision,
                    pageSize, maxRows, maxJoin, maxLinks, maxSource, maxResult, timeout);
        } catch (RuntimeException ex) {
            if (ex instanceof TraverseFailure t) throw t;
            throw fail(422, "MALFORMED_TRAVERSE_REQUEST",
                    "根身份、限制或 Link 版本引用不合法", "rootSelector");
        }
    }

    private static String required(JsonNode n) {
        if (!n.isTextual() || n.asText().isBlank()) throw new IllegalArgumentException("missing value");
        return n.asText();
    }
    private static int lowered(JsonNode limits, String key, int max) {
        JsonNode v = limits.path(key);
        if (v.isMissingNode()) return max;
        if (!v.isIntegralNumber() || v.asLong() < 1 || v.asLong() > max) throw new IllegalArgumentException("limit");
        return v.asInt();
    }
    private static long lowered(JsonNode limits, String key, long max) {
        JsonNode v = limits.path(key);
        if (v.isMissingNode()) return max;
        if (!v.isIntegralNumber() || v.asLong() < 1 || v.asLong() > max) throw new IllegalArgumentException("limit");
        return v.asLong();
    }
    private static boolean fields(JsonNode node, Set<String> allowed) {
        if (!node.isObject()) return false;
        var names = node.fieldNames();
        while (names.hasNext()) if (!allowed.contains(names.next())) return false;
        return true;
    }

    private SourceRows readSource(Requested r, DataBindingService.BindingDetail a,
            DataBindingService.BindingDetail b, String sourceFk, String targetFk,
            long deadline) {
        JdbcTemplate configured = sourceProvider.getIfAvailable();
        if (configured == null || configured.getDataSource() == null) {
            throw fail(503, "SOURCE_UNAVAILABLE", "独立来源只读连接不存在", "rootBindingRef");
        }
        try (Connection connection = configured.getDataSource().getConnection()) {
            connection.setReadOnly(true);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setAutoCommit(false);
            try {
                Budget budget = new Budget();
                List<Fact> left = scan(connection, a.sourceTable(), a.sourceKey(), sourceFk, budget, r, deadline);
                List<Fact> right = scan(connection, b.sourceTable(), b.sourceKey(), targetFk, budget, r, deadline);
                return new SourceRows(left, right, budget.bytes);
            } finally {
                connection.rollback();
            }
        } catch (SQLException ex) {
            throw fail(503, "SOURCE_UNAVAILABLE", "一跳读取失败，不返回部分 LinkFact", "rootBindingRef");
        }
    }

    private static List<Fact> scan(Connection c, String table, String key, String fk,
            Budget budget, Requested request, long deadline) throws SQLException {
        if (!IDENT.matcher(table).matches() || !IDENT.matcher(key).matches()
                || fk != null && !IDENT.matcher(fk).matches()) {
            throw fail(403, "SOURCE_NOT_ALLOWED", "来源名称无效", "rootBindingRef");
        }
        String sql = "SELECT \"" + key + "\"" + (fk == null ? "" : ",\"" + fk + "\"")
                + " FROM public.\"" + table + "\" ORDER BY \"" + key + "\" LIMIT ?";
        List<Fact> data = new ArrayList<>();
        try (PreparedStatement statement = c.prepareStatement(sql)) {
            statement.setQueryTimeout(Math.max(1, request.timeout()));
            statement.setFetchSize(10);
            statement.setInt(1, request.maxRows() + 1);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    if (rs.getObject(1) == null) throw fail(409, "IDENTITY_CONFLICT", "来源对象主键为空", "rootBindingRef");
                    Fact fact = new Fact(value(rs.getObject(1)), fk == null ? null : value(rs.getObject(2)));
                    budget.accept(fact, request, deadline);
                    data.add(fact);
                }
            }
        }
        return data;
    }
    private static String value(Object x) {
        if (x == null) return null;
        if (x instanceof Number n) return new BigDecimal(n.toString()).stripTrailingZeros().toPlainString();
        return x.toString();
    }
    private static long utf8(String x) {
        return x == null ? 0 : x.getBytes(StandardCharsets.UTF_8).length;
    }
    private static Map<String, Fact> uniqueIndex(List<Fact> facts) {
        Map<String, Fact> index = new HashMap<>();
        for (Fact fact : facts) {
            if (fact.key().isBlank() || index.putIfAbsent(fact.key(), fact) != null) {
                throw fail(409, "IDENTITY_CONFLICT", "来源键存在空白或冲突，不能构造稳定 ObjectRef", "rootBindingRef");
            }
        }
        return index;
    }

    private static void ensureColumn(DataBindingService.SourceTable table, String column) {
        if (column == null || table.columns().stream().noneMatch(c -> c.name().equals(column))) {
            throw fail(409, "LINK_MAPPING_DRIFT", "绑定所需来源列不再存在", "rootBindingRef");
        }
    }
    private static String primaryKeyType(JsonNode candidate, String objectTypeId) {
        for (JsonNode type : candidate.path("objectTypes")) {
            if (!type.path("id").asText().equals(objectTypeId)) continue;
            String key = type.path("primaryKeyRef").asText();
            for (JsonNode property : type.path("properties")) {
                if (property.path("id").asText().equals(key)) {
                    String t = property.path("valueType").asText();
                    if (Set.of("STRING", "INTEGER").contains(t)) return t;
                }
            }
        }
        throw fail(422, "UNSUPPORTED_IDENTITY_TYPE", "关系查询只能使用 STRING/INTEGER 主键", "rootTypeRef");
    }
    private static void bindingVersion(ArrayNode out, String id, long revision, String digest, String kind) {
        ObjectNode x = out.addObject();
        x.put("kind",kind);x.put("bindingId",id);x.put("revision",revision);
        x.put("digest",digest);x.put("sourceId",DataBindingService.SOURCE_ID);
    }
    private static void fingerprint(ArrayNode out, DataBindingService.BindingDetail binding,
            DataBindingService.SourceTable table, String role) {
        ObjectNode x=out.addObject();
        x.put("sourceId",binding.sourceId());x.put("schema",binding.sourceSchema());
        x.put("table",binding.sourceTable());x.put("schemaFingerprint",table.schemaFingerprint());
        x.put("role",role);
    }
    private static void sourceRead(ArrayNode out, String readId,
            DataBindingService.BindingDetail binding, DataBindingService.SourceTable table,
            String fk, String role, Instant timestamp) {
        ObjectNode x=out.addObject();
        x.put("id",readId);x.put("role",role);x.put("sourceId",binding.sourceId());
        x.put("schema",binding.sourceSchema());x.put("table",binding.sourceTable());
        ArrayNode cols=x.putArray("columns");cols.add(binding.sourceKey());
        if (fk != null && !binding.sourceKey().equals(fk)) cols.add(fk);
        x.put("schemaFingerprint",table.schemaFingerprint());
        x.put("transactionIsolation","REPEATABLE_READ");
        ObjectNode check=x.putObject("checkpoint");
        check.put("status","UNKNOWN");check.put("reason","SOURCE_CURRENT_STATE_NOT_HISTORICALLY_ATTESTED");
        x.put("startedAt",timestamp.toString());x.put("completedAt",timestamp.toString());
    }
    private static ObjectNode responseTypeRef(String snapshotId,String pkg,String type) {
        ObjectNode result = new ObjectMapper().createObjectNode();
        result.putObject("sourceRef").put("kind","LOCAL_SNAPSHOT").put("snapshotId",snapshotId);
        result.put("packageId",pkg);result.put("objectTypeId",type);
        // Result v2 typeRef is shaped as {kind,snapshotId,packageId,objectTypeId}.
        ObjectNode correct=new ObjectMapper().createObjectNode();
        correct.put("kind","LOCAL_SNAPSHOT");correct.put("snapshotId",snapshotId);
        correct.put("packageId",pkg);correct.put("objectTypeId",type);
        return correct;
    }
    private static TraverseFailure fail(int http, String code, String text, String field) {
        return new TraverseFailure(http,code,text,field);
    }
}
