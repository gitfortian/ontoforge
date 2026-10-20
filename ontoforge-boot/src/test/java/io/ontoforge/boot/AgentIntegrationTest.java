package io.ontoforge.boot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.ontoforge.business.connection.DataBindingService;
import io.ontoforge.business.connection.LinkBindingService;
import io.ontoforge.business.model.NativeDraftService;
import io.ontoforge.business.model.NativeSnapshotService;
import io.ontoforge.business.query.LinkTraverseService;
import io.ontoforge.dao.platform.LocalAccountRepository;
import io.ontoforge.platform.identity.LocalAccountPrincipal;
import io.ontoforge.platform.identity.yak.LocalPasswordEncoder;
import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/** Real PG, Session/CSRF HTTP and outbound model HTTP; no login or query mock. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "ontoforge.security.authentication-mode=local-session",
        "ontoforge.security.bootstrap.enabled=true",
        "ontoforge.security.bootstrap.username=agent-test-bootstrap",
        "server.servlet.session.timeout=5m"})
@ActiveProfiles("integration-test")
@ContextConfiguration(initializers = IsolatedPostgresTestDatabase.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AgentIntegrationTest {
    private static final String RUN_ID = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    private static final String SUPPLY_PACKAGE = "agent.synthetic.supply.p" + RUN_ID;
    private static final String LAB_PACKAGE = "agent.synthetic.laboratory.p" + RUN_ID;
    private static final String SUPPLIERS = "m13_agent_suppliers_" + RUN_ID;
    private static final String ORDERS = "m13_agent_orders_" + RUN_ID;
    private static final String SPECIMENS = "m13_agent_specimens_" + RUN_ID;
    private static final String READER = "m13_reader_" + UUID.randomUUID().toString().replace("-", "");
    private static final String READER_PASSWORD = "reader-test-only-" + UUID.randomUUID();
    private static final String ACCOUNT_PASSWORD = "account-test-only-" + UUID.randomUUID();
    private static final String API_KEY = "model-test-only-secret-" + UUID.randomUUID();
    private static final String MODEL = "configured-stub-model";
    private static final ObjectMapper STUB_JSON = new ObjectMapper();
    private static final List<JsonNode> MODEL_REQUESTS = new CopyOnWriteArrayList<>();
    private static final AtomicReference<Function<JsonNode, StubReply>> PLAN = new AtomicReference<>();
    private static final AtomicReference<Throwable> STUB_FAILURE = new AtomicReference<>();
    private static final ExecutorService STUB_THREADS = Executors.newVirtualThreadPerTaskExecutor();
    private static final HttpServer STUB = startStub();

    private record StubReply(int status, String body) {}
    private record User(UUID accountId, UUID principalId, String username, Authentication authentication) {}
    private record Domain(String packageId, String snapshotId, DataBindingService.BindingDetail binding) {}

    @DynamicPropertySource
    static void configuration(DynamicPropertyRegistry registry) {
        registry.add("ontoforge.security.bootstrap.password", () -> ACCOUNT_PASSWORD);
        registry.add("ontoforge.agent.enabled", () -> true);
        registry.add("ontoforge.agent.base-url", () -> "http://127.0.0.1:" + STUB.getAddress().getPort() + "/v1");
        registry.add("ontoforge.agent.api-key", () -> API_KEY);
        registry.add("ontoforge.agent.model", () -> MODEL);
        registry.add("ontoforge.agent.allowed-packages", () -> SUPPLY_PACKAGE + "," + LAB_PACKAGE);
        registry.add("ontoforge.binding.source.url", () -> System.getenv("TEST_DB_URL"));
        registry.add("ontoforge.binding.source.username", () -> READER);
        registry.add("ontoforge.binding.source.password", () -> READER_PASSWORD);
        registry.add("ontoforge.binding.source.allowed-tables", () -> SUPPLIERS + "," + ORDERS + "," + SPECIMENS);
    }

    @LocalServerPort int port;
    @Autowired ObjectMapper mapper;
    @Autowired @Qualifier("jdbcTemplate") JdbcTemplate jdbc;
    @Autowired NativeDraftService drafts;
    @Autowired NativeSnapshotService snapshots;
    @Autowired DataBindingService bindings;
    @Autowired LinkBindingService linkBindings;
    @Autowired LinkTraverseService traversals;
    @Autowired LocalAccountRepository accounts;
    private UUID workspace;
    private Domain supply;
    private Domain laboratory;
    private DataBindingService.BindingDetail orders;
    private LinkBindingService.LinkDetail supplierOrders;

    @BeforeAll
    void prepareSyntheticDomainsAndDedicatedReader() {
        // This class owns this reader and these synthetic tables. Existing source grants are untouched.
        jdbc.execute("CREATE ROLE " + READER + " LOGIN PASSWORD '" + READER_PASSWORD
                + "' NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS");
        for (String table : List.of(SUPPLIERS, ORDERS, SPECIMENS)) {
            jdbc.execute("CREATE TABLE public." + table
                    + " (code TEXT PRIMARY KEY, label TEXT NOT NULL, amount NUMERIC(12,2) NOT NULL, parent_code TEXT)");
            jdbc.execute("GRANT SELECT ON public." + table + " TO " + READER);
        }
        jdbc.update("INSERT INTO public." + SUPPLIERS + " VALUES ('SUP-1','Synthetic supplier A',10.00,NULL),('SUP-2','Synthetic supplier B',20.00,NULL)");
        jdbc.update("INSERT INTO public." + ORDERS + " VALUES ('ORDER-1','Synthetic order',5.00,'SUP-1')");
        jdbc.update("INSERT INTO public." + SPECIMENS + " VALUES ('SPC-1','Synthetic specimen A',2.00,NULL),('SPC-2','Synthetic specimen B',3.00,NULL)");
        workspace = UUID.randomUUID();
        jdbc.update("INSERT INTO b0_workspace(workspace_id,workspace_key,display_name) VALUES (?,?,?)",
                workspace, "agent-" + workspace, "Synthetic Agent tests");
        for (String pkg : List.of(SUPPLY_PACKAGE, LAB_PACKAGE))
            jdbc.update("INSERT INTO b0_package_assignment(workspace_id,package_id) VALUES (?,?)", workspace, pkg);
        User fixture = user(true);
        supply = createDomain(SUPPLY_PACKAGE, "supplier", "Supplier", SUPPLIERS, true, fixture.authentication());
        laboratory = createDomain(LAB_PACKAGE, "specimen", "Specimen", SPECIMENS, false, fixture.authentication());
        orders = createBinding(supply.snapshotId(), "order", ORDERS, fixture.authentication());
        supplierOrders = linkBindings.create(new LinkBindingService.LinkRequest("local-link-binding-v1",
                new LinkBindingService.SourceRef("LOCAL_SNAPSHOT", UUID.fromString(supply.snapshotId())), SUPPLY_PACKAGE,
                "supplier-orders", bindingRef(supply.binding()), bindingRef(orders), "FOREIGN_KEY", "TARGET", "parent_code",
                0L, UUID.randomUUID(), new LinkBindingService.Fingerprints(supply.binding().sourceSchemaFingerprint(),
                orders.sourceSchemaFingerprint()), new LinkBindingService.ValidationScope("VALIDATION_SCOPE", "FULL")), fixture.authentication());
    }

    @BeforeEach
    void resetModelScript() {
        MODEL_REQUESTS.clear();
        STUB_FAILURE.set(null);
        PLAN.set(request -> new StubReply(500, "unexpected-model-request"));
    }

    @AfterAll
    void cleanupOwnedFixtures() {
        STUB.stop(0);
        STUB_THREADS.shutdownNow();
        for (String table : List.of(ORDERS, SUPPLIERS, SPECIMENS))
            jdbc.execute("DROP TABLE IF EXISTS public." + table);
        jdbc.execute("DROP ROLE IF EXISTS " + READER);
    }

    @Test
    void twoCustomDomainsUseOneToolLoopAndReadOriginalSinglePageAndAggregateEvidence() throws Exception {
        User user = user(true);
        Browser browser = new Browser(); browser.login(user);
        for (Domain domain : List.of(supply, laboratory)) {
            MODEL_REQUESTS.clear();
            ObjectNode query = query(domain, domain == laboratory);
            discoveryQueryScript(query);
            JsonNode result = json(browser.request("POST", "/api/agent/v1/runs", run(domain), true), 200);
            assertThat(result.path("status").asText()).isEqualTo("COMPLETE");
            assertThat(result.path("reasonCode").asText()).isEqualTo("COMPLETED");
            assertThat(result.path("answerKind").asText()).isEqualTo("MODEL_GENERATED");
            assertThat(result.path("executionIds").size()).isEqualTo(1);
            assertThat(result.path("evidenceRefs").size()).isEqualTo(1);
            assertThat(result.path("evidence").get(0).path("quality").path("freshness").asText()).isEqualTo("UNKNOWN");
            assertThat(result.path("budget").path("modelRequests").asInt()).isEqualTo(4);
            assertThat(MODEL_REQUESTS).hasSize(4);
            assertThat(STUB_FAILURE.get()).isNull();
            String ref = result.path("evidenceRefs").get(0).asText();
            String evidencePath = "/api/context/v1/evidence/" + ref;
            JsonNode original = json(browser.request("GET", evidencePath, null, false), 200);
            assertThat(original.path("executionId").asText()).isEqualTo(result.path("executionIds").get(0).asText());
            assertThat(original.path("typeSourceRef").path("snapshotId").asText()).isEqualTo(domain.snapshotId());
            assertThat(original.path("bindingVersions").get(0).path("revision").asLong()).isEqualTo(1L);
            // A later source DML must not reconstruct old evidence or extend its expiry.
            String table = domain == supply ? SUPPLIERS : SPECIMENS;
            jdbc.update("UPDATE public." + table + " SET label=label || ' changed' WHERE code=?", domain == supply ? "SUP-1" : "SPC-1");
            JsonNode afterDml = json(browser.request("GET", evidencePath, null, false), 200);
            assertThat(afterDml).isEqualTo(original);
            User second = user(true); Browser other = new Browser(); other.login(second);
            json(other.request("GET", evidencePath, null, false), 403);
            assertThat(MODEL_REQUESTS).hasSize(4);
        }
    }

    @Test
    void authenticationCsrfExactRoutesAndForeignScopeFailBeforeModelEgress() throws Exception {
        User user = user(true);
        Browser anonymous = new Browser();
        json(anonymous.request("POST", "/api/agent/v1/runs", run(supply), false), 401);
        Browser browser = new Browser(); browser.login(user);
        json(browser.request("POST", "/api/agent/v1/runs", run(supply), false), 403);
        json(browser.request("GET", "/api/agent/v1/runs", null, false), 403);
        json(browser.request("POST", "/api/agent/v1/actions", mapper.createObjectNode(), true), 403);
        ObjectNode mismatched = run(supply);
        ((ObjectNode) mismatched.path("scope").path("bindingRefs").get(0)).put("bindingId", laboratory.binding().bindingId());
        HttpResponse<String> foreign = browser.request("POST", "/api/agent/v1/runs", mismatched, true);
        assertThat(foreign.statusCode()).as(foreign.body()).isBetween(400, 499);
        User ungranted = user(false); Browser outsider = new Browser(); outsider.login(ungranted);
        json(outsider.request("POST", "/api/agent/v1/runs", run(supply), true), 403);
        assertThat(MODEL_REQUESTS).isEmpty();
    }

    @Test
    void upstreamAuthenticationErrorsNeverReturnProviderBodyOrCredentials() throws Exception {
        Browser browser = new Browser(); browser.login(user(true));
        PLAN.set(request -> new StubReply(401, "upstream-sensitive-body " + API_KEY + " " + READER_PASSWORD));
        HttpResponse<String> failed = browser.request("POST", "/api/agent/v1/runs", run(supply), true);
        JsonNode error = json(failed, 502);
        assertThat(error.path("reasonCode").asText()).isEqualTo("MODEL_AUTHENTICATION_FAILED");
        assertThat(failed.body()).doesNotContain("upstream-sensitive-body", API_KEY, READER_PASSWORD, ACCOUNT_PASSWORD);
        assertThat(MODEL_REQUESTS).hasSize(1);
        assertThat(STUB_FAILURE.get()).isNull();
    }

    @Test
    void directEvidenceSourceQualificationFailurePermanentlyInvalidatesOriginalExecution() throws Exception {
        Browser browser = new Browser(); browser.login(user(true));
        discoveryQueryScript(query(supply, false));
        JsonNode result = json(browser.request("POST", "/api/agent/v1/runs", run(supply), true), 200);
        String path = "/api/context/v1/evidence/" + result.path("evidenceRefs").get(0).asText();
        json(browser.request("GET", path, null, false), 200);
        try {
            jdbc.execute("ALTER TABLE public." + SUPPLIERS + " ENABLE ROW LEVEL SECURITY");
            HttpResponse<String> rejected = browser.request("GET", path, null, false);
            JsonNode failure = json(rejected, 503);
            assertThat(failure.path("reasonCode").asText()).isEqualTo("SOURCE_NOT_ALLOWED");
            assertThat(failure.has("sourceReadRef")).isFalse();
            assertThat(failure.has("evidence")).isFalse();
        } finally {
            jdbc.execute("ALTER TABLE public." + SUPPLIERS + " DISABLE ROW LEVEL SECURITY");
        }
        json(browser.request("GET", path, null, false), 410);
        assertThat(MODEL_REQUESTS).hasSize(4);
        assertThat(STUB_FAILURE.get()).isNull();
    }

    @Test
    void revokingMembershipWhileModelWaitsStopsAllFurtherEgressAndFinalDelivery() throws Exception {
        assertInvalidationWhileModelWaits(false);
    }

    @Test
    void loggingOutWhileModelWaitsStopsAllFurtherEgressAndFinalDelivery() throws Exception {
        assertInvalidationWhileModelWaits(true);
    }

    @Test
    void enablingSourceRowSecurityAfterObjectReadStopsFinalAnswerAndFurtherEgress() throws Exception {
        Browser browser = new Browser(); browser.login(user(true));
        CountDownLatch objectWasSent = new CountDownLatch(1), release = new CountDownLatch(1);
        PLAN.set(request -> {
            if (MODEL_REQUESTS.size() == 1) {
                ObjectNode args = mapper.createObjectNode().put("bindingId", supply.binding().bindingId()).put("bindingRevision", 1L);
                args.putObject("key").put("valueType", "STRING").put("value", "SUP-1");
                return tool("object-call", "read_object", args);
            }
            JsonNode object = assertToolReply(request, "object-call", "OBJECT");
            assertThat(object.path("data").path("executionId").asText()).isNotBlank();
            objectWasSent.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("source latch timed out");
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
            return new StubReply(200, "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"Must not be delivered after source eligibility changed.\"}}]}");
        });
        try (ExecutorService callers = Executors.newVirtualThreadPerTaskExecutor()) {
            var pending = callers.submit(() -> browser.request("POST", "/api/agent/v1/runs", run(supply), true));
            try {
                assertThat(objectWasSent.await(8, TimeUnit.SECONDS)).isTrue();
                jdbc.execute("ALTER TABLE public." + SUPPLIERS + " ENABLE ROW LEVEL SECURITY");
            } finally { release.countDown(); }
            HttpResponse<String> response = pending.get(20, TimeUnit.SECONDS);
            JsonNode failure = json(response, 503);
            assertThat(failure.path("reasonCode").asText()).isEqualTo("SOURCE_NOT_ALLOWED");
            assertThat(failure.path("status").asText()).isEqualTo("FAILED");
            assertThat(failure.has("answer")).isFalse();
            assertThat(failure.has("evidence")).isFalse();
            assertThat(MODEL_REQUESTS).hasSize(2);
            assertThat(STUB_FAILURE.get()).isNull();
        } finally {
            jdbc.execute("ALTER TABLE public." + SUPPLIERS + " DISABLE ROW LEVEL SECURITY");
            release.countDown();
        }
    }

    @Test
    void realSessionTraversalAggregateEvidenceIsBoundToOriginalSubjectAndDiesOnRevocation() throws Exception {
        User one = user(true), two = user(true);
        Browser browser = new Browser(), other = new Browser(); browser.login(one); other.login(two);
        ObjectNode query = traversalQuery();
        // Direct Business execution still uses a real stored Session principal and actual PG source reads.
        JsonNode result = traversals.execute(query, one.authentication());
        assertThat(result.path("resultKind").asText()).isEqualTo("AGGREGATE");
        assertThat(result.path("aggregate").path("value").asText()).isEqualTo("1");
        String path = "/api/context/v1/evidence/traverse:" + result.path("resultSnapshotRef").asText();
        JsonNode evidence = json(browser.request("GET", path, null, false), 200);
        assertThat(evidence.path("executionId").asText()).isEqualTo(result.path("executionId").asText());
        assertThat(evidence.path("sourceReadRefs").size()).isEqualTo(2);
        assertThat(evidence.has("objects")).isFalse();
        json(other.request("GET", path, null, false), 403);
        jdbc.update("DELETE FROM b0_workspace_membership WHERE workspace_id=? AND principal_id=?", workspace, one.principalId());
        json(browser.request("GET", path, null, false), 403);
        grant(one.principalId());
        json(browser.request("GET", path, null, false), 410);
        assertThat(MODEL_REQUESTS).isEmpty();
    }

    private void assertInvalidationWhileModelWaits(boolean logout) throws Exception {
        User user = user(true); Browser browser = new Browser(); browser.login(user);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        PLAN.set(request -> {
            entered.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("model latch timed out");
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
            return tool("paused-call", "list_types", mapper.createObjectNode());
        });
        try (ExecutorService callers = Executors.newVirtualThreadPerTaskExecutor()) {
            var pending = callers.submit(() -> browser.request("POST", "/api/agent/v1/runs", run(supply), true));
            try {
                assertThat(entered.await(8, TimeUnit.SECONDS)).isTrue();
                if (logout) json(browser.request("POST", "/api/auth/logout", mapper.createObjectNode(), true), 204);
                else jdbc.update("DELETE FROM b0_workspace_membership WHERE workspace_id=? AND principal_id=?", workspace, user.principalId());
            } finally { release.countDown(); }
            HttpResponse<String> response = pending.get(20, TimeUnit.SECONDS);
            JsonNode failure = json(response, logout ? 401 : 403);
            assertThat(failure.path("status").asText()).isEqualTo("FAILED");
            assertThat(failure.has("answer")).isFalse();
            assertThat(MODEL_REQUESTS).hasSize(1);
            assertThat(STUB_FAILURE.get()).isNull();
        }
    }

    private void discoveryQueryScript(ObjectNode query) {
        PLAN.set(request -> {
            int call = MODEL_REQUESTS.size();
            if (call == 1) return tool("types-call", "list_types", mapper.createObjectNode());
            if (call == 2) {
                assertToolReply(request, "types-call", "METADATA");
                return tool("bindings-call", "list_bindings", mapper.createObjectNode());
            }
            if (call == 3) {
                assertToolReply(request, "bindings-call", "METADATA");
                return tool("query-call", "query_objects", query);
            }
            if (call == 4) {
                JsonNode output = assertToolReply(request, "query-call", "QUERY");
                assertThat(output.path("data").path("executionId").asText()).isNotBlank();
                assertThat(output.path("data").path("quality").path("freshness").asText()).isEqualTo("UNKNOWN");
                return new StubReply(200, "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"Synthetic facts were read; freshness remains UNKNOWN.\"}}]}");
            }
            throw new AssertionError("unexpected model round");
        });
    }

    private JsonNode assertToolReply(JsonNode request, String id, String kind) {
        JsonNode messages = request.path("messages");
        JsonNode message = messages.get(messages.size() - 1);
        assertThat(message.path("role").asText()).isEqualTo("tool");
        assertThat(message.path("tool_call_id").asText()).isEqualTo(id);
        try {
            JsonNode result = mapper.readTree(message.path("content").asText());
            assertThat(result.path("kind").asText()).isEqualTo(kind);
            return result;
        } catch (IOException invalid) { throw new AssertionError(invalid); }
    }

    private ObjectNode run(Domain domain) {
        ObjectNode request = mapper.createObjectNode().put("runVersion", "local-agent-v1")
                .put("question", "Read the selected synthetic domain and explain the evidence and freshness.");
        request.putObject("scope").put("snapshotId", domain.snapshotId()).putArray("bindingRefs")
                .addObject().put("bindingId", domain.binding().bindingId()).put("revision", 1);
        return request;
    }

    private ObjectNode query(Domain domain, boolean aggregate) {
        ObjectNode query = mapper.createObjectNode().put("queryVersion", "local-query-v1");
        query.putObject("rootTypeRef").put("packageId", domain.packageId()).put("objectTypeId", domain.binding().objectTypeId())
                .putObject("sourceRef").put("kind", "LOCAL_SNAPSHOT").put("snapshotId", domain.snapshotId());
        query.putObject("bindingRef").put("bindingId", domain.binding().bindingId()).put("revision", 1);
        if (aggregate) query.putArray("steps").addObject().put("kind", "AGGREGATE").put("operation", "COUNT_DISTINCT");
        else query.putArray("steps").addObject().put("kind", "PAGE").put("pageSize", 10);
        return query;
    }

    private ObjectNode traversalQuery() {
        ObjectNode query = mapper.createObjectNode().put("queryVersion", "local-query-v2");
        query.putObject("rootTypeRef").put("packageId", SUPPLY_PACKAGE).put("objectTypeId", "supplier")
                .putObject("sourceRef").put("kind", "LOCAL_SNAPSHOT").put("snapshotId", supply.snapshotId());
        query.putObject("rootBindingRef").put("bindingId", supply.binding().bindingId()).put("revision", 1);
        ObjectNode ref = query.putObject("rootSelector").put("kind", "OBJECT_REF").putObject("objectRef")
                .put("packageId", SUPPLY_PACKAGE).put("objectTypeId", "supplier").put("identityDomainId", supply.binding().identityDomainId());
        ref.putObject("key").put("valueType", "STRING").put("value", "SUP-1");
        ArrayNode steps = query.putArray("steps");
        ObjectNode traverse = steps.addObject().put("kind", "TRAVERSE").put("linkTypeRef", "supplier-orders")
                .put("direction", "OUT").put("maxDepth", 1);
        traverse.putObject("linkBindingRef").put("bindingId", supplierOrders.linkBindingId()).put("revision", 1);
        steps.addObject().put("kind", "AGGREGATE").put("operation", "COUNT_DISTINCT");
        return query;
    }

    private Domain createDomain(String pkg, String type, String apiName, String table, boolean withOrders, Authentication auth) {
        var draft = drafts.create(pkg);
        ObjectNode editor = mapper.createObjectNode().put("editorSchemaVersion", "0.1.0");
        ArrayNode types = editor.putArray("objectTypes"), links = editor.putArray("linkTypes");
        addType(types, type, apiName, false);
        if (withOrders) {
            addType(types, "order", "Order", true);
            links.addObject().put("id", "supplier-orders").put("apiName", "supplierOrders")
                    .put("sourceTypeRef", "supplier").put("targetTypeRef", "order").put("cardinality", "ONE_TO_MANY").put("evidenceRequired", true);
        }
        var saved = drafts.save(UUID.fromString(draft.draftId()), draft.revision(), editor);
        assertThat(drafts.validate(UUID.fromString(draft.draftId()), saved.revision()).status()).isEqualTo("VALID");
        String snapshot = snapshots.freeze(UUID.fromString(draft.draftId()), saved.revision(), UUID.randomUUID()).snapshot().snapshotId();
        return new Domain(pkg, snapshot, createBinding(snapshot, type, table, auth));
    }

    private void addType(ArrayNode types, String id, String apiName, boolean parent) {
        ArrayNode properties = types.addObject().put("id", id).put("apiName", apiName).put("primaryKeyRef", "code").putArray("properties");
        property(properties, "code", "STRING", true);
        property(properties, "label", "STRING", true);
        property(properties, "amount", "DECIMAL", true);
        if (parent) property(properties, "parent", "STRING", false);
    }

    private void property(ArrayNode properties, String id, String type, boolean required) {
        properties.addObject().put("id", id).put("apiName", id).put("valueType", type)
                .put("required", required).put("searchable", true).put("classification", "INTERNAL");
    }

    private DataBindingService.BindingDetail createBinding(String snapshot, String type, String table, Authentication auth) {
        List<DataBindingService.FieldMapping> mappings = new ArrayList<>();
        for (String field : List.of("code", "label", "amount"))
            mappings.add(new DataBindingService.FieldMapping(field, field, "AUTHORITATIVE"));
        if ("order".equals(type)) mappings.add(new DataBindingService.FieldMapping("parent", "parent_code", "AUTHORITATIVE"));
        var request = new DataBindingService.BindingRequest(snapshot, null, null, type, "local-pg-sandbox", "public", table, "code", mappings, null, 0L);
        String fingerprint = bindings.preview(request, auth).source().schemaFingerprint();
        return bindings.create(new DataBindingService.BindingRequest(snapshot, null, null, type, "local-pg-sandbox", "public", table,
                "code", mappings, fingerprint, 0L), auth);
    }

    private LinkBindingService.BindingRef bindingRef(DataBindingService.BindingDetail binding) {
        return new LinkBindingService.BindingRef(UUID.fromString(binding.bindingId()), binding.revision(), binding.digest());
    }

    private User user(boolean member) {
        UUID account = UUID.randomUUID(), principal = UUID.randomUUID();
        String username = "agent." + account;
        accounts.insert(account, principal, username, "Synthetic Agent user", new LocalPasswordEncoder().encode(ACCOUNT_PASSWORD), false);
        if (member) grant(principal);
        LocalAccountPrincipal identity = new LocalAccountPrincipal(account, principal, 1L, username, "Synthetic Agent user", false);
        return new User(account, principal, username, UsernamePasswordAuthenticationToken.authenticated(identity, null, List.of()));
    }

    private void grant(UUID principal) {
        jdbc.update("INSERT INTO b0_workspace_membership(workspace_id,principal_id,role) VALUES (?,?,'EDITOR')", workspace, principal);
    }

    private JsonNode json(HttpResponse<String> response, int status) throws IOException {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        return response.body().isEmpty() ? mapper.nullNode() : mapper.readTree(response.body());
    }

    private final class Browser {
        private final HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .connectTimeout(Duration.ofSeconds(5)).build();
        private String csrf;
        private String csrfHeader;
        private HttpResponse<String> request(String method, String path, Object body, boolean withCsrf) throws Exception {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json");
            if (withCsrf && csrf != null) request.header(csrfHeader, csrf);
            return client.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
        }
        private void context() throws Exception {
            JsonNode context = json(request("GET", "/api/auth/context", null, false), 200);
            csrf = context.path("csrf").path("token").asText(); csrfHeader = context.path("csrf").path("headerName").asText();
        }
        private void login(User user) throws Exception {
            context(); json(request("POST", "/api/auth/login", Map.of("username", user.username(), "password", ACCOUNT_PASSWORD), true), 200); context();
        }
    }

    private static HttpServer startStub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(STUB_THREADS);
            server.createContext("/v1/chat/completions", exchange -> {
                try {
                    assertThat(exchange.getRequestMethod()).isEqualTo("POST");
                    assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer " + API_KEY);
                    assertThat(exchange.getRequestHeaders().getFirst("Cookie")).isNull();
                    JsonNode request = STUB_JSON.readTree(exchange.getRequestBody());
                    assertThat(request.path("model").asText()).isEqualTo(MODEL);
                    assertThat(request.path("parallel_tool_calls").asBoolean()).isFalse();
                    assertThat(request.path("stream").asBoolean()).isFalse();
                    assertThat(request.toString()).doesNotContain(ACCOUNT_PASSWORD, READER_PASSWORD, API_KEY);
                    MODEL_REQUESTS.add(request.deepCopy());
                    StubReply response = PLAN.get().apply(request);
                    respond(exchange, response.status(), response.body());
                } catch (Throwable failure) {
                    STUB_FAILURE.set(failure);
                    respond(exchange, 500, "stub-failed");
                }
            });
            server.start(); return server;
        } catch (IOException failed) { throw new ExceptionInInitializerError(failed); }
    }

    private static StubReply tool(String id, String name, JsonNode arguments) {
        ObjectNode response = STUB_JSON.createObjectNode();
        ObjectNode message = response.putArray("choices").addObject().put("finish_reason", "tool_calls").putObject("message").put("role", "assistant");
        message.putArray("tool_calls").addObject().put("id", id).put("type", "function").putObject("function")
                .put("name", name).put("arguments", arguments.toString());
        return new StubReply(200, response.toString());
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }
}
