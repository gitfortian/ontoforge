package io.ontoforge.business.query;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.ontoforge.business.connection.DataBindingService;
import io.ontoforge.business.connection.LinkBindingService;
import io.ontoforge.business.model.NativeSnapshotService;
import io.ontoforge.business.registry.RuntimeTypeResolver;
import io.ontoforge.dao.query.ObjectSetSourceRepository;
import io.ontoforge.platform.identity.B0AuthorizationService;
import java.lang.reflect.Constructor;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Evidence must use the retained execution, never rerun a business/source query. */
class QueryEvidenceTest {
    private static final String PACKAGE = "evidence.test";
    private static final String SNAPSHOT = UUID.randomUUID().toString();
    private static final String BINDING = UUID.randomUUID().toString();
    private static final String TARGET_BINDING = UUID.randomUUID().toString();
    private static final String LINK = UUID.randomUUID().toString();
    private static final String KEY = "evidence-test-only-signing-secret-with-32-characters";

    private final ObjectMapper mapper = new ObjectMapper();
    private final DataBindingService bindings = mock(DataBindingService.class);
    private final RuntimeTypeResolver types = mock(RuntimeTypeResolver.class);
    private final NativeSnapshotService snapshots = mock(NativeSnapshotService.class);
    private final LinkBindingService links = mock(LinkBindingService.class);
    private final B0AuthorizationService authorization = mock(B0AuthorizationService.class);
    private final ObjectSetSourceRepository source = mock(ObjectSetSourceRepository.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<JdbcTemplate> sourceProvider = mock(ObjectProvider.class);
    private final Authentication owner = mock(Authentication.class);
    private final Authentication otherSession = mock(Authentication.class);
    private final Instant executedAt = Instant.now().minusSeconds(1);
    private final Instant expiresAt = executedAt.plusSeconds(300);

    private void allowCurrentVersions() {
        when(authorization.actorKey(owner)).thenReturn("SESSION_AUTHENTICATED:owner");
        when(authorization.actorKey(otherSession)).thenReturn("SESSION_AUTHENTICATED:other");
        when(bindings.getBinding(UUID.fromString(BINDING), 1L, owner)).thenReturn(binding(BINDING));
        when(bindings.getBinding(UUID.fromString(TARGET_BINDING), 1L, owner)).thenReturn(binding(TARGET_BINDING));
        when(bindings.listTables(PACKAGE, owner)).thenReturn(List.of(table("source-fingerprint")));
        when(bindings.revalidateSource(any(), eq(owner), anyLong())).thenReturn(table("source-fingerprint"));
        when(types.getSnapshotType(UUID.fromString(SNAPSHOT), "item")).thenReturn(type());
        when(snapshots.get(UUID.fromString(SNAPSHOT))).thenReturn(new NativeSnapshotService.Snapshot(
                SNAPSHOT, PACKAGE, UUID.randomUUID().toString(), 1L, "0.2.0", "jcs-rfc8785-v1",
                "model-digest", mapper.createObjectNode(), executedAt, "LOCAL_SNAPSHOT_ONLY"));
        LinkBindingService.LinkDetail link = mock(LinkBindingService.LinkDetail.class);
        when(link.digest()).thenReturn("link-digest");
        when(links.get(UUID.fromString(LINK), PACKAGE, 1L, owner)).thenReturn(link);
    }

    @Test
    void singlePageAndAggregateEvidenceKeepOriginalMetadataWithoutScanningValues() throws Exception {
        allowCurrentVersions();
        ObjectSetQueryService service = queryService();
        for (boolean aggregate : List.of(false, true)) {
            String ref = retainQuery(service, expiresAt, aggregate);
            ObjectNode evidence = service.evidence(ref, owner);
            assertThat(evidence.path("executionId").asText()).isEqualTo("original-execution");
            assertThat(evidence.path("queryDigest").asText()).isEqualTo("original-query-digest");
            assertThat(evidence.path("expiresAt").asText()).isEqualTo(expiresAt.toString());
            assertThat(evidence.path("quality").path("freshness").asText()).isEqualTo("UNKNOWN");
            assertThat(evidence.path("sourceReadRef").path("id").asText()).isEqualTo("original-read");
            assertThat(evidence.has("objects")).isFalse();
            assertThat(evidence.has("aggregate")).isFalse();
            evidence.withObject("quality").put("freshness", "fabricated");
            assertThat(service.evidence(ref, owner).path("quality").path("freshness").asText()).isEqualTo("UNKNOWN");
        }
        verifyNoInteractions(source, sourceProvider);
    }

    @Test
    void queryEvidenceRejectsOtherSubjectsBeforeReadingVersionsAndRejectsExpiredHandles() throws Exception {
        allowCurrentVersions();
        ObjectSetQueryService service = queryService();
        String ref = retainQuery(service, expiresAt, false);
        assertQueryFailure(() -> service.evidence(ref, otherSession), 403, "FORBIDDEN");
        verify(bindings, never()).getBinding(any(), eq(1L), any());
        String expired = retainQuery(service, Instant.now().minusSeconds(1), false);
        assertQueryFailure(() -> service.evidence(expired, owner), 410, "RESULT_EXPIRED");
        assertQueryFailure(() -> service.evidence("source-read-id", owner), 400, "INVALID_EVIDENCE_REF");
        assertQueryFailure(() -> service.evidence("local-result:" + UUID.randomUUID(), owner), 410, "RESULT_EXPIRED");
    }

    @Test
    void queryEvidenceInvalidatesFrozenResultAfterRevocationEvenIfGrantReturns() throws Exception {
        allowCurrentVersions();
        ObjectSetQueryService service = queryService();
        String ref = retainQuery(service, expiresAt, false);
        when(bindings.getBinding(UUID.fromString(BINDING), 1L, owner)).thenThrow(new AccessDeniedException("revoked"));
        assertQueryFailure(() -> service.evidence(ref, owner), 403, "FORBIDDEN");
        doReturn(binding(BINDING)).when(bindings).getBinding(UUID.fromString(BINDING), 1L, owner);
        assertQueryFailure(() -> service.evidence(ref, owner), 410, "RESULT_EXPIRED");
        verifyNoInteractions(source, sourceProvider);
    }

    @Test
    void traversalEvidenceUsesStoredSessionActorAndCopiesOnlyOriginalMetadata() throws Exception {
        allowCurrentVersions();
        LinkTraverseService service = traverseService();
        String ref = retainTraversal(service, expiresAt);
        assertThatThrownBy(() -> service.evidence(ref, otherSession))
                .isInstanceOf(LinkTraverseService.TraverseFailure.class)
                .satisfies(error -> assertThat(((LinkTraverseService.TraverseFailure) error).http).isEqualTo(403));
        verifyNoInteractions(bindings);
        ObjectNode evidence = service.evidence(ref, owner);
        assertThat(evidence.path("executionId").asText()).isEqualTo("original-execution");
        assertThat(evidence.path("queryDigest").asText()).isEqualTo("original-query-digest");
        assertThat(evidence.path("sourceReadRefs").get(0).path("id").asText()).isEqualTo("original-read");
        assertThat(evidence.has("objects")).isFalse();
        assertThat(evidence.has("links")).isFalse();
        evidence.withObject("quality").put("freshness", "fabricated");
        assertThat(service.evidence(ref, owner).path("quality").path("freshness").asText()).isEqualTo("UNKNOWN");
        verifyNoInteractions(sourceProvider);
    }

    @Test
    void traversalEvidenceRevocationAndSchemaDriftPermanentlyInvalidateHandles() throws Exception {
        allowCurrentVersions();
        LinkTraverseService service = traverseService();
        String revoked = retainTraversal(service, expiresAt);
        doThrow(new AccessDeniedException("revoked")).when(authorization)
                .requirePackage(owner, PACKAGE, B0AuthorizationService.Operation.READ_DRAFT);
        assertThatThrownBy(() -> service.evidence(revoked, owner)).isInstanceOf(AccessDeniedException.class);
        doNothing().when(authorization).requirePackage(owner, PACKAGE, B0AuthorizationService.Operation.READ_DRAFT);
        assertTraverseFailure(() -> service.evidence(revoked, owner), 410, "RESULT_EXPIRED");
        String drifted = retainTraversal(service, expiresAt);
        when(bindings.revalidateSource(any(), eq(owner), anyLong())).thenReturn(table("changed-fingerprint"));
        assertTraverseFailure(() -> service.evidence(drifted, owner), 409, "LINK_SOURCE_SCHEMA_DRIFT");
        when(bindings.revalidateSource(any(), eq(owner), anyLong())).thenReturn(table("source-fingerprint"));
        assertTraverseFailure(() -> service.evidence(drifted, owner), 410, "RESULT_EXPIRED");
        assertTraverseFailure(() -> service.evidence(retainTraversal(service, Instant.now().minusSeconds(1)), owner), 410, "RESULT_EXPIRED");
        assertTraverseFailure(() -> service.evidence("source-read-id", owner), 400, "INVALID_EVIDENCE_REF");
        verifyNoInteractions(sourceProvider);
    }

    @Test
    void explicitInvalidationIsOwnerOnlyTerminalAndDoesNotReadSourcesOrStoredGrants() throws Exception {
        allowCurrentVersions();
        ObjectSetQueryService query = queryService();
        LinkTraverseService traverse = traverseService();
        String queryRef = retainQuery(query, expiresAt, false);
        String traverseRef = retainTraversal(traverse, expiresAt);
        query.invalidateResult(queryRef, otherSession);
        traverse.invalidateResult(traverseRef, otherSession);
        assertThat(retainedMap(query, "snapshots")).hasSize(1);
        assertThat(retainedMap(traverse, "frozen")).hasSize(1);
        query.invalidateResult(queryRef, owner);
        traverse.invalidateResult(traverseRef, owner);
        // An already missing execution is an idempotent cleanup, never a fresh lookup.
        query.invalidateResult(queryRef, owner);
        traverse.invalidateResult(traverseRef, owner);
        assertQueryFailure(() -> query.evidence(queryRef, owner), 410, "RESULT_EXPIRED");
        assertTraverseFailure(() -> traverse.evidence(traverseRef, owner), 410, "RESULT_EXPIRED");
        verifyNoInteractions(bindings, types, snapshots, links, source, sourceProvider);
    }

    private ObjectSetQueryService queryService() {
        return new ObjectSetQueryService(bindings, types, authorization, mapper, source,
                sourceProvider, "evidence_source", KEY);
    }

    private LinkTraverseService traverseService() {
        return new LinkTraverseService(links, mock(io.ontoforge.business.connection.BridgeLinkBindingService.class),
                bindings, snapshots, authorization, sourceProvider, mapper, KEY);
    }

    private DataBindingService.BindingDetail binding(String id) {
        return new DataBindingService.BindingDetail(id, 1L, PACKAGE, "item", SNAPSHOT, "model-digest",
                "local-pg-sandbox", "public", "evidence_source", "source-fingerprint", "code", "identity-domain",
                List.of(), "binding-digest", "jcs-rfc8785-v1", "VALID", executedAt, 1, List.of(), false, executedAt);
    }

    private RuntimeTypeResolver.TypeDescriptor type() {
        return new RuntimeTypeResolver.TypeDescriptor(new RuntimeTypeResolver.SnapshotTypeSourceRef("LOCAL_SNAPSHOT", SNAPSHOT),
                PACKAGE, 1L, "LOCAL_SNAPSHOT_ONLY", "item", "Item", "Item", "code", List.of(), "model-digest");
    }

    private DataBindingService.SourceTable table(String fingerprint) {
        return new DataBindingService.SourceTable("local-pg-sandbox", "public", "evidence_source", List.of(), fingerprint);
    }

    private String retainQuery(ObjectSetQueryService service, Instant expires, boolean aggregate) throws Exception {
        String id = UUID.randomUUID().toString();
        var versions = List.of(new ObjectSetQueryService.BindingVersion(BINDING, 1L, "binding-digest", "local-pg-sandbox", "OBJECT"));
        var typeRef = new ObjectSetQueryService.TypeSourceRef("LOCAL_SNAPSHOT", SNAPSHOT);
        var lock = new ObjectSetQueryService.VersionLock(typeRef, "model-digest", versions,
                List.of(new ObjectSetQueryService.SchemaFingerprintRef("local-pg-sandbox", "public", "evidence_source", "source-fingerprint")));
        var read = new ObjectSetQueryService.SourceReadRef("original-read", "local-pg-sandbox", "public", "evidence_source",
                List.of("code"), "source-fingerprint", null, null, "REPEATABLE_READ",
                new ObjectSetQueryService.CheckpointEvidence("UNKNOWN", "no watermark"), "original scan");
        Object saved = retained("ObjectSetQueryService$StoredSnapshot", id, "SESSION_AUTHENTICATED:owner", "original-execution",
                "original-query-digest", "local-result:" + id, executedAt, expires, binding(BINDING), type(), "source-fingerprint",
                1, List.of(), aggregate ? new ObjectSetQueryService.AggregateResult("COUNT_DISTINCT", null, "INTEGER", "1", 1) : null,
                versions, typeRef, "SESSION_AUTHENTICATED", lock, read,
                new ObjectSetQueryService.QualityEnvelope("EXACT", "COMPLETE", "UNKNOWN", "REQUESTED_SCOPE"),
                new ObjectSetQueryService.BudgetEvidence(1, 1, 10L, 10L, 100, 1000L, 1000L, 3), 10L);
        retainedMap(service, "snapshots").put(id, saved);
        return "local-result:" + id;
    }

    private String retainTraversal(LinkTraverseService service, Instant expires) throws Exception {
        String id = UUID.randomUUID().toString();
        ObjectNode full = mapper.createObjectNode().put("status", "SUCCESS").put("executionId", "original-execution")
                .put("executedAt", executedAt.toString()).put("queryVersion", "local-query-v2")
                .put("resultSnapshotRef", "local-result:" + id).put("expiresAt", expires.toString())
                .put("queryDigest", "original-query-digest");
        full.putObject("versionLock").put("payloadSha256", "model-digest");
        full.putArray("bindingVersions");
        full.putArray("sourceReadRefs").addObject().put("id", "original-read");
        full.putObject("quality").put("freshness", "UNKNOWN");
        full.putObject("budget").put("physicalRows", 1);
        full.putArray("objects").addObject().put("secret", "frozen value");
        full.putArray("links");
        Object saved = retained("LinkTraverseService$Frozen", id, "SESSION_AUTHENTICATED:owner", expires, full, 100L, 1,
                "OUT", PACKAGE, SNAPSHOT, LINK, 1L, "link-digest", BINDING, 1L, "binding-digest",
                TARGET_BINDING, 1L, "binding-digest", "source-fingerprint", "source-fingerprint");
        retainedMap(service, "frozen").put(id, saved);
        return "local-result:" + id;
    }

    private static Object retained(String nestedClass, Object... arguments) throws Exception {
        Constructor<?> constructor = Class.forName("io.ontoforge.business.query." + nestedClass).getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        return constructor.newInstance(arguments);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> retainedMap(Object service, String field) {
        return (Map<String, Object>) ReflectionTestUtils.getField(service, field);
    }

    private static void assertQueryFailure(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, int http, String code) {
        assertThatThrownBy(action).isInstanceOf(ObjectSetQueryService.QueryException.class).satisfies(error -> {
            assertThat(((ObjectSetQueryService.QueryException) error).http()).isEqualTo(http);
            assertThat(((ObjectSetQueryService.QueryException) error).code()).isEqualTo(code);
        });
    }

    private static void assertTraverseFailure(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, int http, String code) {
        assertThatThrownBy(action).isInstanceOf(LinkTraverseService.TraverseFailure.class).satisfies(error -> {
            assertThat(((LinkTraverseService.TraverseFailure) error).http).isEqualTo(http);
            assertThat(((LinkTraverseService.TraverseFailure) error).code).isEqualTo(code);
        });
    }
}
