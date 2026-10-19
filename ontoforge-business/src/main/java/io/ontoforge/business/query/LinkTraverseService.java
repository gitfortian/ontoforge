package io.ontoforge.business.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.ontoforge.business.connection.DataBindingService;
import io.ontoforge.business.connection.LinkBindingService;
import io.ontoforge.business.model.NativeSnapshotService;
import io.ontoforge.core.compiler.M8TraverseSchema;
import io.ontoforge.platform.identity.B0AuthorizationService;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.GeneralSecurityException;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

/**
 * M8.1 strict execution slice: one rooted ObjectRef and exactly one Traverse
 * step, terminating in PAGE. Unsupported filters/aggregates fail closed rather
 * than silently changing the requested scope. A bounded signed frozen cursor
 * stores only the validated one-hop result; source facts remain in PostgreSQL.
 * No generic graph traversal, SQL supplied by callers, or M7 v1 mutation.
 */
@Service
public class LinkTraverseService {
    private static final Pattern IDENT = Pattern.compile("[a-z][a-z0-9_]{0,62}");
    private static final int MAX_ROWS = 10_000;
    private static final long MAX_BYTES = 8L * 1024 * 1024;
    private static final int MAX_LINKS = 10_000;
    private static final int DEADLINE_SECONDS = 3;
    private static final int MAX_SNAPSHOTS = 32;
    // Four in-flight builders cap transient source/JsonNode amplification
    // separately from frozen cache retention (32 slots / 16 MiB). This is
    // best-effort admission, NOT a hard JVM heap-byte guarantee.
    private static final int MAX_BUILDERS = 4;
    // Stored JSON node trees can use far more heap than their compact JSON.
    // Keep a separate conservative serialized cap across ALL actors in addition
    // to the per-query ≤8 MiB evidence budget.
    private static final long MAX_FROZEN_JSON_BYTES = 16L * 1024 * 1024;

    private final LinkBindingService links;
    private final DataBindingService bindings;
    private final NativeSnapshotService snapshots;
    private final B0AuthorizationService authorization;
    private final ObjectProvider<JdbcTemplate> sourceProvider;
    private final ObjectMapper mapper;
    private final byte[] signingKey;
    private final Map<String, Frozen> frozen = new ConcurrentHashMap<>();
    private final Semaphore builders = new Semaphore(MAX_BUILDERS, true);

    private record Frozen(String id, String actor, Instant expiresAt, ObjectNode full,
            long jsonBytes, int pageSize, String direction, String pkg, String snapshotId,
            String linkId, long linkRevision, String linkDigest,
            String sourceBindingId, long sourceRevision, String sourceDigest,
            String targetBindingId, long targetRevision, String targetDigest,
            String sourceFingerprint, String targetFingerprint) {}

    public LinkTraverseService(LinkBindingService links, DataBindingService bindings,
            NativeSnapshotService snapshots, B0AuthorizationService authorization,
            @Qualifier("bindingSourceJdbcTemplate") ObjectProvider<JdbcTemplate> sourceProvider,
            ObjectMapper mapper,
            @Value("${ontoforge.objectset.cursor-signing-secret:}") String cursorSigningSecret) {
        this.links = links;
        this.bindings = bindings;
        this.snapshots = snapshots;
        this.authorization = authorization;
        this.sourceProvider = sourceProvider;
        this.mapper = mapper;
        if (cursorSigningSecret == null || cursorSigningSecret.length() < 32) {
            throw new IllegalStateException("M8 cursor signing secret must have >= 32 characters");
        }
        this.signingKey = cursorSigningSecret.getBytes(StandardCharsets.UTF_8);
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

    private record Fact(String key, String fk, Map<String, String> values) {}
    private record Condition(String propertyRef, String operator, String valueType, List<String> values) {}
    private record SortField(String propertyRef, String direction) {}
    private static final int MAX_ROOTS = 50;
    private record SourceRows(List<Fact> source, List<Fact> target, long bytes,
            Instant startedAt, Instant completedAt) {}
    private record Requested(String packageId, String snapshotId, String rootTypeId,
            String rootBindingId, long rootBindingRevision, String rootDomain,
            String rootKeyType, String rootKey, String selectorKind,
            List<Condition> rootConditions, List<Condition> targetConditions,
            List<SortField> sort, List<String> project,
            String aggregateOperation, String aggregateProperty, String direction,
            String linkTypeId, String linkBindingId, long linkBindingRevision,
            int pageSize, int maxRows, int maxJoin, int maxLinks,
            long maxSourceBytes, long maxResultBytes, int timeout) {}
    private static final class Budget {
        int rows;
        long bytes;
        void accept(Fact fact, Requested request, long deadline) {
            rows++;
            bytes += utf8(fact.key()) + utf8(fact.fk());
            for (String value : fact.values().values()) bytes += utf8(value);
            if (rows > request.maxRows() || bytes > request.maxSourceBytes()
                    || System.nanoTime() > deadline) {
                throw fail(System.nanoTime() > deadline ? 503 : 429,
                        System.nanoTime() > deadline ? "QUERY_TIMEOUT" : "QUERY_BUDGET_EXCEEDED",
                        "双端真实来源扫描超过总预算，不会以截断结果返回成功", "limits");
            }
        }
    }

    public ObjectNode execute(JsonNode json, Authentication auth) {
        if (!builders.tryAcquire()) {
            throw fail(429, "QUERY_CONCURRENCY_BUDGET",
                    "并行查询达到限额；未读取来源或返回部分关联，请稍后重试", "limits");
        }
        try {
            return executeAdmitted(json, auth);
        } finally {
            builders.release();
        }
    }

    private ObjectNode executeAdmitted(JsonNode json, Authentication auth) {
        long deadline = System.nanoTime() + DEADLINE_SECONDS * 1_000_000_000L;
        if (!M8TraverseSchema.validQuery(json)) {
            throw fail(422, "TRAVERSE_CONTRACT_INVALID",
                    "查询请求不符合已冻结 v2 JSON Schema", "query");
        }
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
                || ("OBJECT_REF".equals(request.selectorKind())
                && !root.identityDomainId().equals(request.rootDomain()))) {
            throw fail(409, "ROOT_BINDING_MISMATCH", "根 ObjectRef 与当前端点 Binding 身份不符", "rootBindingRef");
        }
        String keyType = primaryKeyType(snapshot.snapshotContent(), root.objectTypeId());
        String outputKeyType = primaryKeyType(snapshot.snapshotContent(), output.objectTypeId());
        String sourceKeyType = primaryKeyType(snapshot.snapshotContent(), sourceBinding.objectTypeId());
        String targetKeyType = primaryKeyType(snapshot.snapshotContent(), targetBinding.objectTypeId());
        if ("OBJECT_REF".equals(request.selectorKind())
                && !keyType.equals(request.rootKeyType())) {
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

        Map<String, String> rootColumns = mappedConditionColumns(root, snapshot.snapshotContent(),
                request.rootConditions(), out ? left : right);
        Map<String, String> targetColumns = mappedConditionColumns(output, snapshot.snapshotContent(),
                request.targetConditions(), out ? right : left);
        Set<String> outputFields = new HashSet<>(request.project());
        for (SortField sort : request.sort()) outputFields.add(sort.propertyRef());
        if (request.aggregateProperty() != null) outputFields.add(request.aggregateProperty());
        targetColumns.putAll(mappedResultColumns(output, snapshot.snapshotContent(),
                outputFields, out ? right : left));
        SourceRows rows = readSource(request, sourceBinding, targetBinding, sourceFk, targetFk,
                out ? rootColumns : targetColumns, out ? targetColumns : rootColumns, deadline);
        Map<String, Fact> leftIndex = uniqueIndex(rows.source());
        Map<String, Fact> rightIndex = uniqueIndex(rows.target());
        Map<String, Fact> rootIndex = out ? leftIndex : rightIndex;
        Set<String> selectedRoots = new HashSet<>();
        if ("OBJECT_REF".equals(request.selectorKind())) {
            if (!rootIndex.containsKey(request.rootKey())) {
                throw fail(404, "ROOT_OBJECT_NOT_FOUND", "当前来源中不存在指定根对象", "rootSelector");
            }
            selectedRoots.add(request.rootKey());
        } else {
            for (Fact row : rootIndex.values()) {
                if (matches(row, request.rootConditions())) {
                    selectedRoots.add(row.key());
                    if (selectedRoots.size() > MAX_ROOTS) {
                        throw fail(429, "ROOT_SCOPE_BUDGET", "根筛选超过 50 个对象，请缩小条件", "rootSelector");
                    }
                }
            }
        }

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
                    if (rootOwner && selectedRoots.contains(source.key())) orphans++;
                    continue;
                }
                if ((out && selectedRoots.contains(source.key()))
                        || (!out && selectedRoots.contains(target.key()))) {
                    candidates++;
                    if (candidates > request.maxJoin()) throw fail(429, "QUERY_BUDGET_EXCEEDED",
                            "Join 候选超过本次上限", "limits");
                    if (!matches(out ? target : source, request.targetConditions())) continue;
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
                    if (rootOwner && selectedRoots.contains(target.key())) orphans++;
                    continue;
                }
                if ((out && selectedRoots.contains(source.key()))
                        || (!out && selectedRoots.contains(target.key()))) {
                    candidates++;
                    if (candidates > request.maxJoin()) throw fail(429, "QUERY_BUDGET_EXCEEDED",
                            "Join 候选超过本次上限", "limits");
                    if (!matches(out ? target : source, request.targetConditions())) continue;
                    addEdge(request, link, sourceBinding, targetBinding, source, target,
                            outputKeyType, sourceKeyType, targetKeyType, out, readSourceId, readTargetId,
                            distinctOutput, distinctLink, resultObjects, resultLinks);
                }
            }
        }
        if (candidates > request.maxJoin() || distinctLink.size() > request.maxLinks()) {
            throw fail(429, "QUERY_BUDGET_EXCEEDED", "一跳候选或 LinkFact 超过预算", "limits");
        }
        // Post-join output is deduplicated by full endpoint identity. Sort is
        // stable with ObjectRef primary-key tie-break, before frozen pagination.
        Map<String, Fact> outputIndex = out ? rightIndex : leftIndex;
        Map<String, String> outputTypes = fieldTypes(snapshot.snapshotContent(), output.objectTypeId());
        for (JsonNode item : resultObjects) {
            String key = item.path("objectRef").path("key").path("value").asText();
            Fact fact = outputIndex.get(key);
            if (fact == null) throw fail(409, "LINK_RESULT_CONFLICT", "结果源对象不存在", "steps");
            ArrayNode props = (ArrayNode) item.path("properties");
            for (String property : request.project()) {
                ObjectNode value = props.addObject();
                String raw = fact.values().get(property);
                value.put("propertyRef", property);
                value.put("valueType", outputTypes.get(property));
                value.put("status", raw == null ? "NULL" : "PRESENT");
                if (raw != null) value.put("value", raw);
                value.put("sourceReadRef", out ? readTargetId : readSourceId);
            }
        }
        if (!request.sort().isEmpty()) {
            List<JsonNode> ordered = new ArrayList<>();
            resultObjects.forEach(ordered::add);
            ordered.sort((a, b) -> sortCompare(
                    outputIndex.get(a.path("objectRef").path("key").path("value").asText()),
                    outputIndex.get(b.path("objectRef").path("key").path("value").asText()),
                    request.sort(), outputTypes));
            resultObjects.removeAll();
            ordered.forEach(resultObjects::add);
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
        sourceRead(sourceReadRefs, readSourceId, sourceBinding, left, sourceFk,
                out ? rootColumns : targetColumns, "SOURCE", rows.startedAt(), rows.completedAt());
        sourceRead(sourceReadRefs, readTargetId, targetBinding, right, targetFk,
                out ? targetColumns : rootColumns, "TARGET", rows.startedAt(), rows.completedAt());

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
        String frozenId = UUID.randomUUID().toString();
        result.put("resultSnapshotRef", "local-result:" + frozenId);
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
        if (request.aggregateOperation() == null) {
            result.put("pageSize", request.pageSize());
            result.put("pageNumber", 1);
        } else {
            result.put("resultKind", "AGGREGATE");
            String operation = request.aggregateOperation();
            ObjectNode aggregate = result.putObject("aggregate");
            aggregate.put("operation", operation);
            aggregate.put("matchedObjects", resultObjects.size());
            if ("COUNT_DISTINCT".equals(operation)) {
                aggregate.put("valueType", "INTEGER");
                aggregate.put("value", Integer.toString(resultObjects.size()));
            } else {
                String property = request.aggregateProperty();
                String type = outputTypes.get(property);
                if (!Set.of("INTEGER", "DECIMAL").contains(type)) {
                    throw fail(422,"INVALID_AGGREGATE_TYPE","SUM 必须引用冻结目标端数值类型","steps");
                }
                BigDecimal sum = BigDecimal.ZERO;
                for (JsonNode object : resultObjects) {
                    String key = object.path("objectRef").path("key").path("value").asText();
                    String value = outputIndex.get(key).values().get(property);
                    if (value == null) continue; // SQL SUM: NULL contributes nothing.
                    try {
                        sum = sum.add(new BigDecimal(value));
                    } catch (NumberFormatException ex) {
                        throw fail(409, "AGGREGATE_SOURCE_TYPE_CONFLICT",
                                "来源数值与冻结属性类型不符，不能给出确定 SUM", "steps");
                    }
                }
                aggregate.put("propertyRef",property);
                aggregate.put("valueType","DECIMAL");
                aggregate.put("value",sum.stripTrailingZeros().toPlainString());
            }
            // AGGREGATE response is a scalar, not an unbounded hidden page.
            result.set("objects",mapper.createArrayNode());
            result.set("links",mapper.createArrayNode());
            result.put("objectCount",0);
            result.put("linkCount",0);
            result.put("pathCount",0);
        }
        long resultBytes = exactSerializedBytes(result);
        if (resultBytes > request.maxResultBytes()) {
            throw fail(429, "QUERY_BUDGET_EXCEEDED", "响应超过 8MiB 预算", "limits");
        }
        // Final current evidence recheck BEFORE a successful payload escapes.
        LinkBindingService.LinkDetail current = links.get(
                UUID.fromString(link.linkBindingId()), request.packageId(), link.revision(), auth);
        if (!current.digest().equals(link.digest())) {
            throw fail(409, "LINK_VERSION_UNAVAILABLE", "查询执行期间关系版本变化", "steps");
        }
        if (System.nanoTime() > deadline) throw fail(503, "QUERY_TIMEOUT", "来源执行整体超时", "limits");
        if (request.aggregateOperation() != null) {
            ensureResultContract(result);
            return result;
        }
        long frozenBytes = result.toString().getBytes(StandardCharsets.UTF_8).length;
        Frozen saved = new Frozen(frozenId, actorKey(auth), now.plusSeconds(300), result.deepCopy(),
                frozenBytes, request.pageSize(), request.direction(), request.packageId(), request.snapshotId(),
                link.linkBindingId(), link.revision(), link.digest(),
                sourceBinding.bindingId(), sourceBinding.revision(), sourceBinding.digest(),
                targetBinding.bindingId(), targetBinding.revision(), targetBinding.digest(),
                left.schemaFingerprint(), right.schemaFingerprint());
        synchronized (frozen) {
            frozen.entrySet().removeIf(e -> !e.getValue().expiresAt().isAfter(Instant.now()));
            // Admission is synchronized with eviction and aggregate allocation.
            // No concurrent caller can bypass either the slot or total byte cap.
            long retained = 0;
            for (Frozen record : frozen.values()) retained += record.jsonBytes();
            if (frozen.size() >= MAX_SNAPSHOTS || frozenBytes > MAX_FROZEN_JSON_BYTES - retained) {
                throw fail(429, "RESULT_SNAPSHOT_BUDGET",
                        "冻结结果总容量已满；请缩小查询或等待旧结果到期", "limits");
            }
            frozen.put(frozenId, saved);
        }
        try {
            return buildPage(saved, 0);
        } catch (RuntimeException ex) {
            frozen.remove(frozenId);
            throw ex;
        }
    }

    /** Opaque cursor only; never accepts an arbitrary client-computed offset. */
    public ObjectNode nextPage(JsonNode body, Authentication auth) {
        if (!fields(body, Set.of("cursor")) || !body.path("cursor").isTextual()
                || body.path("cursor").asText().length() > 512) {
            throw fail(400, "INVALID_CURSOR", "仅接受签名的 v2 cursor", "cursor");
        }
        String token = body.path("cursor").asText();
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3 || !"v2".equals(parts[0])) {
            throw fail(400, "INVALID_CURSOR", "cursor 格式错误", "cursor");
        }
        byte[] raw;
        byte[] supplied;
        try {
            raw = Base64.getUrlDecoder().decode(parts[1]);
            supplied = Base64.getUrlDecoder().decode(parts[2]);
        } catch (IllegalArgumentException e) {
            throw fail(400, "INVALID_CURSOR", "cursor 编码错误", "cursor");
        }
        if (!MessageDigest.isEqual(supplied, sign(raw))) {
            throw fail(400, "INVALID_CURSOR", "cursor 签名失效", "cursor");
        }
        String[] fields = new String(raw, StandardCharsets.UTF_8).split(":", -1);
        if (fields.length != 4) throw fail(400, "INVALID_CURSOR", "cursor 内容不合法", "cursor");
        String id = fields[0];
        int offset;
        long expiresMillis;
        try {
            UUID.fromString(id);
            offset = Integer.parseInt(fields[1]);
            expiresMillis = Long.parseLong(fields[2]);
        } catch (RuntimeException ex) {
            throw fail(400, "INVALID_CURSOR", "cursor 位置不可解析", "cursor");
        }
        if (!fields[3].equals(actorHash(actorKey(auth)))) {
            throw fail(403, "FORBIDDEN", "cursor 不属于当前主体", "cursor");
        }
        Frozen record = frozen.get(id);
        if (record == null || !record.expiresAt().isAfter(Instant.now())
                || expiresMillis != record.expiresAt().toEpochMilli()) {
            frozen.remove(id);
            throw fail(410, "RESULT_EXPIRED", "结果已过期或已失效，必须重新执行", "cursor");
        }
        if (!record.actor().equals(actorKey(auth))) {
            throw fail(403, "FORBIDDEN", "cursor 不属于当前主体", "cursor");
        }
        int maxOffset = record.full().path("objects").size();
        if (offset <= 0 || offset >= maxOffset || offset % record.pageSize() != 0) {
            throw fail(400, "INVALID_CURSOR", "cursor 不对应可翻页的服务端位置", "cursor");
        }
        try {
            revalidate(record, auth);
        } catch (RuntimeException ex) {
            // Once denied or drifted, a previously issued cursor cannot resurrect
            // even if a grant is later restored. Do not leak partial page content.
            frozen.remove(id);
            throw ex;
        }
        return buildPage(record, offset);
    }

    private void revalidate(Frozen saved, Authentication auth) {
        authorization.requirePackage(auth, saved.pkg(), B0AuthorizationService.Operation.READ_DRAFT);
        UUID snapshotId = UUID.fromString(saved.snapshotId());
        authorization.requireSnapshot(auth, snapshotId, B0AuthorizationService.Operation.READ_SNAPSHOT);
        NativeSnapshotService.Snapshot currentSnapshot = snapshots.get(snapshotId);
        if (!currentSnapshot.packageId().equals(saved.pkg())
                || !currentSnapshot.payloadSha256().equals(saved.full().path("versionLock")
                    .path("payloadSha256").asText())) {
            throw fail(409, "LINK_VERSION_UNAVAILABLE", "模型快照身份已变化", "cursor");
        }
        LinkBindingService.LinkDetail link = links.get(UUID.fromString(saved.linkId()),
                saved.pkg(), saved.linkRevision(), auth);
        if (link.historical() || !saved.linkDigest().equals(link.digest())) {
            throw fail(409, "LINK_VERSION_UNAVAILABLE", "关系修订已不再可执行", "cursor");
        }
        DataBindingService.BindingDetail a = bindings.getBinding(UUID.fromString(saved.sourceBindingId()),
                saved.sourceRevision(), auth);
        DataBindingService.BindingDetail b = bindings.getBinding(UUID.fromString(saved.targetBindingId()),
                saved.targetRevision(), auth);
        if (!saved.sourceDigest().equals(a.digest()) || !saved.targetDigest().equals(b.digest())) {
            throw fail(409, "LINK_ENDPOINT_VERSION_MISMATCH", "双端版本证据不再有效", "cursor");
        }
        Map<String, DataBindingService.SourceTable> current = new HashMap<>();
        for (DataBindingService.SourceTable table : bindings.listTables(saved.pkg(), auth)) {
            current.put(table.table(), table);
        }
        if (!current.containsKey(a.sourceTable()) || !current.containsKey(b.sourceTable())) {
            throw fail(403, "SOURCE_NOT_ALLOWED", "来源当前不可读", "cursor");
        }
        if (!saved.sourceFingerprint().equals(current.get(a.sourceTable()).schemaFingerprint())
                || !saved.targetFingerprint().equals(current.get(b.sourceTable()).schemaFingerprint())) {
            throw fail(409, "LINK_SOURCE_SCHEMA_DRIFT", "结果冻结后来源结构发生变化", "cursor");
        }
    }

    private ObjectNode buildPage(Frozen saved, int offset) {
        ObjectNode page = saved.full().deepCopy();
        ArrayNode allObjects = (ArrayNode) saved.full().path("objects");
        ArrayNode allLinks = (ArrayNode) saved.full().path("links");
        ArrayNode objects = mapper.createArrayNode();
        ArrayNode links = mapper.createArrayNode();
        int end = Math.min(offset + saved.pageSize(), allObjects.size());
        // O(pageSize + frozenLinkFacts), NOT O(pageSize * frozenLinkFacts).
        // Index page-relevant edges once; emit their groups in the exact SORT
        // order of output ObjectRefs. Scanning all edges and appending directly
        // would restore physical scan order and contradict the sorted UI table.
        Set<String> pageRefs = new HashSet<>();
        for (int i = offset; i < end; i++) {
            JsonNode object = allObjects.get(i);
            objects.add(object.deepCopy());
            pageRefs.add(object.path("objectRef").toString());
        }
        // OUT/IN is frozen with the query; self-links cannot use type equality.
        boolean out = "OUT".equals(saved.direction());
        Map<String, List<JsonNode>> pageEdges = new HashMap<>();
        for (JsonNode edge : allLinks) {
            String endpoint = (out ? edge.path("target") : edge.path("source")).toString();
            if (pageRefs.contains(endpoint)) {
                pageEdges.computeIfAbsent(endpoint, k -> new ArrayList<>()).add(edge);
            }
        }
        for (JsonNode object : objects) {
            for (JsonNode edge : pageEdges.getOrDefault(object.path("objectRef").toString(), List.of())) {
                links.add(edge.deepCopy());
            }
        }
        page.set("objects", objects);
        page.set("links", links);
        page.put("objectCount", objects.size());
        page.put("linkCount", links.size());
        page.put("pathCount", links.size());
        page.put("pageNumber", offset / saved.pageSize() + 1);
        page.remove("nextCursor");
        if (end < allObjects.size()) {
            String payload = saved.id() + ":" + end + ":" + saved.expiresAt().toEpochMilli()
                    + ":" + actorHash(saved.actor());
            byte[] data = payload.getBytes(StandardCharsets.UTF_8);
            String token = "v2." + Base64.getUrlEncoder().withoutPadding().encodeToString(data)
                    + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(sign(data));
            if (token.length() > 512) throw fail(429, "QUERY_BUDGET_EXCEEDED",
                    "签名 cursor 超出长度预算", "cursor");
            page.put("nextCursor", token);
        }
        ObjectNode budget = (ObjectNode) page.path("budget");
        long actualBytes = exactSerializedBytes(page);
        if (actualBytes > budget.path("maxSerializedResultBytes").asLong()) {
            throw fail(429, "QUERY_BUDGET_EXCEEDED", "当前冻结页超出响应预算", "limits");
        }
        ensureResultContract(page);
        return page;
    }

    /**
     * Account for the byte count field itself. Reporting the length while it is
     * still 0 undercounts 1..8 bytes depending on digit width and corrupts
     * observable query evidence, even if the final separate limit check passed.
     */
    private static long exactSerializedBytes(ObjectNode response) {
        ObjectNode budget = (ObjectNode) response.path("budget");
        for (int n = 0; n < 4; n++) {
            long length = response.toString().getBytes(StandardCharsets.UTF_8).length;
            if (budget.path("serializedResultBytes").asLong() == length) return length;
            budget.put("serializedResultBytes", length);
        }
        throw fail(503, "RESULT_BUDGET_ACCOUNTING",
                "无法计算稳定的序列化响应字节数，拒绝提供不准确预算", "budget");
    }

    private static void ensureResultContract(JsonNode result) {
        if (!M8TraverseSchema.validResult(result)) {
            throw fail(503, "TRAVERSE_RESULT_CONTRACT_DRIFT",
                    "本次响应无法满足已冻结 v2 合同，已拒绝返回对象和来源证据", "result");
        }
    }

    private byte[] sign(byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingKey, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("M8 cursor signing unavailable", ex);
        }
    }
    private static String actorKey(Authentication auth) {
        if (auth instanceof JwtAuthenticationToken jwt) {
            String issuer = jwt.getToken().getIssuer() == null ? "" : jwt.getToken().getIssuer().toString();
            return "jwt:" + issuer + ":" + jwt.getToken().getSubject();
        }
        return "LOCAL_ISOLATED";
    }
    private static String actorHash(String actor) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256")
                    .digest(actor.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(d);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
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
                || !body.path("rootSelector").isObject()
                || !Set.of("OBJECT_REF", "BOUNDED_ROOT_FILTER")
                        .contains(body.path("rootSelector").path("kind").asText())
                || !"LOCAL_SNAPSHOT".equals(body.path("rootTypeRef").path("sourceRef").path("kind").asText())
                || !body.path("steps").isArray()
                || body.path("steps").size() < 2 || body.path("steps").size() > 10
                || !fields(body.path("steps").get(0), Set.of("kind", "linkTypeRef",
                        "linkBindingRef", "direction", "maxDepth"))
                || !fields(body.path("steps").get(0).path("linkBindingRef"),
                        Set.of("bindingId", "revision"))
                || !"TRAVERSE".equals(body.path("steps").get(0).path("kind").asText())
                || !Set.of("PAGE","AGGREGATE")
                        .contains(body.path("steps").get(body.path("steps").size()-1).path("kind").asText())
                || !body.path("steps").get(0).path("maxDepth").isIntegralNumber()
                || body.path("steps").get(0).path("maxDepth").asInt() != 1) {
            throw fail(422, "UNSUPPORTED_TRAVERSE_AST",
                    "当前仅支持单步 TRAVERSE + TARGET FILTER* + PAGE，其他 AST 不会被静默忽略", "steps");
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
            String selectorKind = required(body.path("rootSelector").path("kind"));
            String rootKeyType = null;
            String rootKey = null;
            String rootDomain = null;
            List<Condition> rootConditions = List.of();
            if ("OBJECT_REF".equals(selectorKind)) {
                if (!fields(body.path("rootSelector"), Set.of("kind", "objectRef"))
                        || !fields(root, Set.of("packageId", "objectTypeId", "identityDomainId", "key"))
                        || !fields(root.path("key"), Set.of("valueType", "value"))
                        || !required(root.path("packageId")).equals(required(type.path("packageId")))
                        || !required(root.path("objectTypeId")).equals(required(type.path("objectTypeId")))) {
                    throw new IllegalArgumentException("objectRef");
                }
                rootKeyType = required(root.path("key").path("valueType"));
                if (!Set.of("STRING", "INTEGER").contains(rootKeyType)) throw new IllegalArgumentException("root key type");
                rootKey = required(root.path("key").path("value"));
                rootDomain = required(root.path("identityDomainId"));
                UUID.fromString(rootDomain);
            } else {
                if (!fields(body.path("rootSelector"), Set.of("kind", "conditions"))) {
                    throw new IllegalArgumentException("rootSelector");
                }
                rootConditions = readConditions(body.path("rootSelector").path("conditions"));
            }
            List<Condition> targetConditions = new ArrayList<>();
            List<SortField> sort = new ArrayList<>();
            List<String> project = new ArrayList<>();
            String aggregateOperation = null, aggregateProperty = null;
            int phase = 0;
            for (int i = 1; i < body.path("steps").size()-1; i++) {
                JsonNode part = body.path("steps").get(i);
                String kind = required(part.path("kind"));
                if ("FILTER".equals(kind) && phase == 0
                        && fields(part, Set.of("kind", "scope", "conditions"))
                        && "TARGET".equals(part.path("scope").asText())) {
                    targetConditions.addAll(readConditions(part.path("conditions")));
                    if (targetConditions.size() > 8) throw new IllegalArgumentException("too many conditions");
                } else if ("SORT".equals(kind) && phase < 2 && sort.isEmpty()
                        && fields(part, Set.of("kind", "fields"))) {
                    phase = 2;
                    JsonNode fields = part.path("fields");
                    if (!fields.isArray() || fields.isEmpty() || fields.size() > 3) {
                        throw new IllegalArgumentException("sort fields");
                    }
                    for (JsonNode sortField : fields) {
                        if (!fields(sortField, Set.of("propertyRef", "direction"))) {
                            throw new IllegalArgumentException("sort fields");
                        }
                        String prop = required(sortField.path("propertyRef"));
                        String dir = required(sortField.path("direction"));
                        if (!prop.matches("[a-z][a-z0-9-]{1,63}") || !Set.of("ASC","DESC").contains(dir)
                                || sort.stream().anyMatch(x -> x.propertyRef().equals(prop))) {
                            throw new IllegalArgumentException("sort field invalid");
                        }
                        sort.add(new SortField(prop, dir));
                    }
                } else if ("PROJECT".equals(kind) && phase < 3 && project.isEmpty()
                        && fields(part, Set.of("kind", "propertyRefs"))) {
                    phase = 3;
                    JsonNode names = part.path("propertyRefs");
                    if (!names.isArray() || names.isEmpty() || names.size() > 100) {
                        throw new IllegalArgumentException("projection fields");
                    }
                    for (JsonNode prop : names) {
                        String value = required(prop);
                        if (!value.matches("[a-z][a-z0-9-]{1,63}") || project.contains(value)) {
                            throw new IllegalArgumentException("duplicate projection");
                        }
                        project.add(value);
                    }
                } else {
                    throw new IllegalArgumentException("unsupported step ordering");
                }
            }
            JsonNode terminal = body.path("steps").get(body.path("steps").size()-1);
            boolean aggregate = "AGGREGATE".equals(terminal.path("kind").asText());
            int pageSize = 50;
            if (aggregate) {
                if (!fields(terminal, Set.of("kind","operation","propertyRef"))) {
                    throw new IllegalArgumentException("aggregate fields");
                }
                aggregateOperation = required(terminal.path("operation"));
                if (!Set.of("COUNT_DISTINCT","SUM").contains(aggregateOperation)
                        || "SUM".equals(aggregateOperation) != terminal.has("propertyRef")) {
                    throw new IllegalArgumentException("aggregate operation");
                }
                if (terminal.has("propertyRef")) {
                    aggregateProperty = required(terminal.path("propertyRef"));
                    if (!aggregateProperty.matches("[a-z][a-z0-9-]{1,63}")) {
                        throw new IllegalArgumentException("aggregate property");
                    }
                }
            } else {
                if (!fields(terminal, Set.of("kind","pageSize"))
                        || !terminal.path("pageSize").isIntegralNumber()
                        || !terminal.path("pageSize").canConvertToInt()) {
                    throw new IllegalArgumentException("pageSize");
                }
                pageSize = terminal.path("pageSize").intValue();
            }
            if (pageSize < 1 || pageSize > 50
                    || rootKey != null && rootKey.length() > 4096
                    || !Set.of("OUT","IN").contains(required(step.path("direction")))) {
                throw new IllegalArgumentException("root or page mismatch");
            }
            UUID.fromString(required(type.path("sourceRef").path("snapshotId")));
            UUID.fromString(required(body.path("rootBindingRef").path("bindingId")));
            UUID.fromString(required(step.path("linkBindingRef").path("bindingId")));
            JsonNode rootVersion = body.path("rootBindingRef").path("revision");
            JsonNode linkVersion = step.path("linkBindingRef").path("revision");
            if (!rootVersion.isIntegralNumber() || !rootVersion.canConvertToLong()
                    || !linkVersion.isIntegralNumber() || !linkVersion.canConvertToLong()) {
                throw new IllegalArgumentException("revision");
            }
            long rootRevision = rootVersion.longValue();
            long linkRevision = linkVersion.longValue();
            if (rootRevision < 1 || linkRevision < 1) throw new IllegalArgumentException("revision");
            return new Requested(required(type.path("packageId")), required(type.path("sourceRef").path("snapshotId")),
                    required(type.path("objectTypeId")),
                    required(body.path("rootBindingRef").path("bindingId")), rootRevision,
                    rootDomain, rootKeyType, rootKey, selectorKind, List.copyOf(rootConditions),
                    List.copyOf(targetConditions), List.copyOf(sort), List.copyOf(project),
                    aggregateOperation, aggregateProperty,
                    required(step.path("direction")), required(step.path("linkTypeRef")),
                    required(step.path("linkBindingRef").path("bindingId")), linkRevision,
                    pageSize, maxRows, maxJoin, maxLinks, maxSource, maxResult, timeout);
        } catch (RuntimeException ex) {
            if (ex instanceof TraverseFailure t) throw t;
            throw fail(422, "MALFORMED_TRAVERSE_REQUEST",
                    "根身份、限制或 Link 版本引用不合法", "rootSelector");
        }
    }

    private static List<Condition> readConditions(JsonNode nodes) {
        if (!nodes.isArray() || nodes.isEmpty() || nodes.size() > 8) {
            throw new IllegalArgumentException("conditions must be bounded");
        }
        List<Condition> conditions = new ArrayList<>();
        for (JsonNode node : nodes) {
            if (!fields(node, Set.of("propertyRef", "operator", "literal", "values"))) {
                throw new IllegalArgumentException("condition fields");
            }
            String prop = required(node.path("propertyRef"));
            String op = required(node.path("operator"));
            if (!Set.of("EQ", "IN", "PREFIX", "GT", "GTE", "LT", "LTE", "IS_NULL").contains(op)
                    || !prop.matches("[a-z][a-z0-9-]{1,63}")) {
                throw new IllegalArgumentException("condition operator");
            }
            List<String> values = new ArrayList<>();
            String declaredType = null;
            if ("IS_NULL".equals(op)) {
                if (node.has("literal") || node.has("values")) throw new IllegalArgumentException("null literal");
            } else {
                JsonNode items = node.path("values");
                if ("IN".equals(op) && (!items.isArray() || items.isEmpty() || items.size() > 100)
                        || !"IN".equals(op) && (node.has("values") || !node.has("literal"))) {
                    throw new IllegalArgumentException("condition literal");
                }
                Iterable<JsonNode> literals = "IN".equals(op) ? items : List.of(node.path("literal"));
                for (JsonNode literal : literals) {
                    if (!fields(literal, Set.of("valueType", "value"))
                            || !literal.path("value").isValueNode()) throw new IllegalArgumentException("literal");
                    String t = required(literal.path("valueType"));
                    if (!Set.of("STRING","INTEGER","DECIMAL","BOOLEAN","ENUM","DATE","DATETIME","UUID").contains(t)
                            || declaredType != null && !declaredType.equals(t)) throw new IllegalArgumentException("literal type");
                    JsonNode value = literal.path("value");
                    if ("BOOLEAN".equals(t) && !value.isBoolean()
                            || !"BOOLEAN".equals(t) && !value.isTextual()
                            || value.asText().length() > 4096) throw new IllegalArgumentException("literal value");
                    declaredType = t;
                    values.add(value.asText());
                }
            }
            conditions.add(new Condition(prop, op, declaredType, List.copyOf(values)));
        }
        return conditions;
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
            Map<String, String> sourceColumns, Map<String, String> targetColumns, long deadline) {
        JdbcTemplate configured = sourceProvider.getIfAvailable();
        if (configured == null || configured.getDataSource() == null) {
            throw fail(503, "SOURCE_UNAVAILABLE", "独立来源只读连接不存在", "rootBindingRef");
        }
        Instant readStarted = Instant.now();
        try (Connection connection = configured.getDataSource().getConnection()) {
            connection.setReadOnly(true);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setAutoCommit(false);
            try {
                Budget budget = new Budget();
                List<Fact> left = scan(connection, a.sourceTable(), a.sourceKey(), sourceFk, sourceColumns, budget, r, deadline);
                List<Fact> right = scan(connection, b.sourceTable(), b.sourceKey(), targetFk, targetColumns, budget, r, deadline);
                return new SourceRows(left, right, budget.bytes, readStarted, Instant.now());
            } finally {
                connection.rollback();
            }
        } catch (SQLException ex) {
            throw fail(503, "SOURCE_UNAVAILABLE", "一跳读取失败，不返回部分 LinkFact", "rootBindingRef");
        }
    }

    private static List<Fact> scan(Connection c, String table, String key, String fk,
            Map<String, String> selected, Budget budget, Requested request, long deadline) throws SQLException {
        if (!IDENT.matcher(table).matches() || !IDENT.matcher(key).matches()
                || fk != null && !IDENT.matcher(fk).matches()) {
            throw fail(403, "SOURCE_NOT_ALLOWED", "来源名称无效", "rootBindingRef");
        }
        List<Map.Entry<String, String>> extra = selected.entrySet().stream()
                .sorted(Map.Entry.comparingByKey()).toList();
        StringBuilder sql = new StringBuilder("SELECT \"").append(key).append("\"");
        if (fk != null) sql.append(",\"").append(fk).append("\"");
        for (Map.Entry<String, String> column : extra) {
            if (!IDENT.matcher(column.getValue()).matches()) {
                throw fail(422, "UNSUPPORTED_FILTER_FIELD", "来源列不满足受控数据库标识符约束", "conditions");
            }
            sql.append(",\"").append(column.getValue()).append("\"");
        }
        sql.append(" FROM public.\"").append(table).append("\" ORDER BY \"")
                .append(key).append("\" LIMIT ?");
        List<Fact> data = new ArrayList<>();
        try (PreparedStatement statement = c.prepareStatement(sql.toString())) {
            statement.setQueryTimeout(Math.max(1, request.timeout()));
            statement.setFetchSize(10);
            statement.setInt(1, request.maxRows() + 1);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    if (rs.getObject(1) == null) throw fail(409, "IDENTITY_CONFLICT", "来源对象主键为空", "rootBindingRef");
                    Map<String, String> props = new HashMap<>();
                    int base = fk == null ? 2 : 3;
                    for (int n = 0; n < extra.size(); n++) {
                        props.put(extra.get(n).getKey(), value(rs.getObject(base + n)));
                    }
                    Fact fact = new Fact(value(rs.getObject(1)),
                            fk == null ? null : value(rs.getObject(2)), props);
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
            if (fact.key().isBlank() || fact.key().length() > 4096
                    || index.putIfAbsent(fact.key(), fact) != null) {
                throw fail(409, "IDENTITY_CONFLICT", "来源键存在空白或冲突，不能构造稳定 ObjectRef", "rootBindingRef");
            }
        }
        return index;
    }

    private static Map<String,String> mappedResultColumns(
            DataBindingService.BindingDetail binding, JsonNode payload, Set<String> names,
            DataBindingService.SourceTable table) {
        if (names.isEmpty()) return Map.of();
        List<Condition> required = names.stream().sorted().map(name ->
                new Condition(name,"IS_NULL",null,List.of())).toList();
        return mappedConditionColumns(binding,payload,required,table);
    }

    private static Map<String,String> fieldTypes(JsonNode snapshot, String objectTypeId) {
        Map<String,String> result = new HashMap<>();
        for (JsonNode type : snapshot.path("objectTypes")) {
            if (!objectTypeId.equals(type.path("id").asText())) continue;
            for (JsonNode property : type.path("properties")) {
                result.put(property.path("id").asText(), property.path("valueType").asText());
            }
        }
        return result;
    }

    private static int sortCompare(Fact first, Fact second, List<SortField> fields,
                                   Map<String,String> types) {
        for (SortField field : fields) {
            String left = first.values().get(field.propertyRef());
            String right = second.values().get(field.propertyRef());
            if (left == null && right == null) continue;
            if (left == null) return 1; // NULLS LAST in both directions
            if (right == null) return -1;
            int compared;
            try {
                compared = compare(left,right,types.get(field.propertyRef()));
            } catch (NumberFormatException ex) {
                throw fail(422,"FILTER_TYPE_MISMATCH","排序字段不符合冻结类型","steps");
            }
            if (compared != 0) return "DESC".equals(field.direction()) ? -compared : compared;
        }
        return first.key().compareTo(second.key());
    }

    private static Map<String, String> mappedConditionColumns(
            DataBindingService.BindingDetail binding, JsonNode payload, List<Condition> filters,
            DataBindingService.SourceTable sourceTable) {
        Map<String, String> result = new HashMap<>();
        for (Condition filter : filters) {
            JsonNode property = null;
            for (JsonNode type : payload.path("objectTypes")) {
                if (!type.path("id").asText().equals(binding.objectTypeId())) continue;
                for (JsonNode candidate : type.path("properties")) {
                    if (candidate.path("id").asText().equals(filter.propertyRef())) {
                        property = candidate;
                        break;
                    }
                }
            }
            if (property == null || !property.path("searchable").asBoolean(false)) {
                throw fail(422, "UNSUPPORTED_FILTER_FIELD", "属性不是冻结模型的可检索字段", "conditions");
            }
            String type = property.path("valueType").asText();
            if (!Set.of("STRING","INTEGER","DECIMAL","BOOLEAN","ENUM","UUID","DATE","DATETIME")
                    .contains(type)
                    || filter.valueType() != null && !type.equals(filter.valueType())) {
                throw fail(422, "FILTER_TYPE_MISMATCH", "谓词值与冻结属性类型不匹配", "conditions");
            }
            if ("PREFIX".equals(filter.operator()) && !Set.of("STRING","ENUM").contains(type)
                    || Set.of("GT","GTE","LT","LTE").contains(filter.operator())
                    && !Set.of("INTEGER","DECIMAL").contains(type)) {
                throw fail(422, "UNSUPPORTED_FILTER_OPERATOR", "属性类型不支持比较算子", "conditions");
            }
            String column = null;
            for (DataBindingService.FieldMapping mapping : binding.fieldMappings()) {
                if (mapping.propertyRef().equals(filter.propertyRef())) {
                    column = mapping.sourceColumn();
                    break;
                }
            }
            String selected = column;
            if (selected == null || !IDENT.matcher(selected).matches()
                    || sourceTable.columns().stream().noneMatch(x -> x.name().equals(selected))) {
                throw fail(422, "FILTER_NOT_BOUND", "属性没有有效的当前 M5 来源映射", "conditions");
            }
            result.put(filter.propertyRef(), selected);
        }
        return result;
    }

    private static boolean matches(Fact fact, List<Condition> conditions) {
        for (Condition c : conditions) {
            String actual = fact.values().get(c.propertyRef());
            if ("IS_NULL".equals(c.operator())) {
                if (actual != null) return false;
                continue;
            }
            if (actual == null) return false;
            try {
                boolean accepted = switch (c.operator()) {
                    case "EQ" -> compare(actual,c.values().get(0),c.valueType())==0;
                    case "IN" -> c.values().stream().anyMatch(v -> compare(actual,v,c.valueType())==0);
                    case "PREFIX" -> actual.startsWith(c.values().get(0));
                    case "GT" -> compare(actual,c.values().get(0),c.valueType())>0;
                    case "GTE" -> compare(actual,c.values().get(0),c.valueType())>=0;
                    case "LT" -> compare(actual,c.values().get(0),c.valueType())<0;
                    case "LTE" -> compare(actual,c.values().get(0),c.valueType())<=0;
                    default -> throw new IllegalArgumentException("predicate");
                };
                if (!accepted) return false;
            } catch (NumberFormatException ex) {
                throw fail(422, "FILTER_TYPE_MISMATCH", "来源或查询数值不符合冻结类型", "conditions");
            }
        }
        return true;
    }

    private static int compare(String actual, String expected, String type) {
        if ("INTEGER".equals(type) || "DECIMAL".equals(type)) {
            return new BigDecimal(actual).compareTo(new BigDecimal(expected));
        }
        if ("BOOLEAN".equals(type)) {
            if (!Set.of("true","false").contains(actual.toLowerCase(java.util.Locale.ROOT))) {
                throw fail(422,"FILTER_TYPE_MISMATCH","来源布尔值非法","conditions");
            }
            return Boolean.compare(Boolean.parseBoolean(actual), Boolean.parseBoolean(expected));
        }
        return actual.compareTo(expected);
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
            String fk, Map<String, String> extras, String role,
            Instant startedAt, Instant completedAt) {
        ObjectNode x=out.addObject();
        x.put("id",readId);x.put("role",role);x.put("sourceId",binding.sourceId());
        x.put("schema",binding.sourceSchema());x.put("table",binding.sourceTable());
        ArrayNode cols=x.putArray("columns");cols.add(binding.sourceKey());
        if (fk != null && !binding.sourceKey().equals(fk)) cols.add(fk);
        for (String col : new java.util.TreeSet<>(extras.values())) {
            if (!col.equals(fk) && !col.equals(binding.sourceKey())) cols.add(col);
        }
        x.put("schemaFingerprint",table.schemaFingerprint());
        x.put("transactionIsolation","REPEATABLE_READ");
        ObjectNode check=x.putObject("checkpoint");
        check.put("status","UNKNOWN");check.put("reason","SOURCE_CURRENT_STATE_NOT_HISTORICALLY_ATTESTED");
        x.put("startedAt",startedAt.toString());x.put("completedAt",completedAt.toString());
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
