package io.ontoforge.boot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import static org.assertj.core.api.Assertions.assertThat;

/** M4 real HTTP contracts on isolated PostgreSQL 17; not an in-memory resolver test. */
@org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"test", "integration-test"})
@Import(B0TestSecurityConfiguration.class)
@ContextConfiguration(initializers = IsolatedPostgresTestDatabase.class)
class RuntimeTypeTest {
    private static final UUID WORKSPACE = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID EDITOR = UUID.fromString("22222222-2222-4222-8222-222222222222");

    @LocalServerPort int port;
    @Autowired TestRestTemplate http;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtEncoder jwtEncoder;

    @BeforeEach
    void seedEditorAccess() {
        jdbc.update("INSERT INTO b0_workspace(workspace_id,workspace_key,display_name) VALUES (?,?,?) ON CONFLICT DO NOTHING",
                WORKSPACE, "m4-runtime-type-test", "M4 Runtime Type Test");
        jdbc.update("INSERT INTO b0_principal(principal_id,issuer,subject,display_name) VALUES (?,?,?,?) ON CONFLICT (issuer,subject) DO NOTHING",
                EDITOR, B0TestSecurityConfiguration.ISSUER, "editor-A", "M4 Test Editor");
        jdbc.update("INSERT INTO b0_workspace_membership(workspace_id,principal_id,role) VALUES (?,?,?) ON CONFLICT DO NOTHING",
                WORKSPACE, EDITOR, "EDITOR");
    }

    private ResponseEntity<String> request(HttpMethod method, String path, JsonNode body, String subject) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(B0TestSecurityConfiguration.token(jwtEncoder, subject));
        return http.exchange(URI.create("http://127.0.0.1:" + port + "/api/model" + path), method,
                new HttpEntity<>(body == null ? null : body.toString(), headers), String.class);
    }

    private JsonNode call(HttpMethod method, String path, JsonNode body, int expected) throws Exception {
        ResponseEntity<String> response = request(method, path, body, "editor-A");
        assertThat(response.getStatusCode().value()).describedAs(response.getBody()).isEqualTo(expected);
        return response.getBody() == null || response.getBody().isBlank()
                ? mapper.createObjectNode() : mapper.readTree(response.getBody());
    }

    private String freshPackage() {
        return "manufacturing.m4" + UUID.randomUUID().toString().replace("-", "");
    }

    private ObjectNode editorDocument() {
        ObjectNode doc = mapper.createObjectNode();
        doc.put("editorSchemaVersion", "0.1.0");
        ArrayNode objects = doc.putArray("objectTypes");
        // Deliberately reverse lexical order: the API must sort by stable objectTypeId.
        objects.add(object("supplier-type", "Supplier", "supplier-id", true));
        objects.add(object("purchase-order-type", "PurchaseOrder", "order-id", false));
        doc.putArray("linkTypes");
        return doc;
    }

    private ObjectNode object(String id, String apiName, String primaryKey, boolean supplier) {
        ObjectNode type = mapper.createObjectNode();
        type.put("id", id);
        type.put("apiName", apiName);
        type.put("label", supplier ? "供应商" : "采购单");
        type.put("primaryKeyRef", primaryKey);
        ArrayNode properties = type.putArray("properties");
        properties.add(property(primaryKey, supplier ? "supplierId" : "orderId",
                "STRING", true, false, supplier ? "供应商编号" : "采购单编号"));
        if (supplier) {
            properties.add(property("supplier-name", "supplierName", "STRING", true, true, "供应商名称"));
            ObjectNode status = property("supplier-status", "supplierStatus", "ENUM", false, true, "状态");
            status.putArray("enumValues").add("ACTIVE").add("INACTIVE");
            properties.add(status);
        } else {
            properties.add(property("order-amount", "orderAmount", "DECIMAL", true, false, "采购金额"));
        }
        return type;
    }

    private ObjectNode property(String id, String apiName, String valueType,
                                boolean required, boolean searchable, String label) {
        ObjectNode property = mapper.createObjectNode();
        property.put("id", id);
        property.put("apiName", apiName);
        property.put("label", label);
        property.put("valueType", valueType);
        property.put("required", required);
        property.put("searchable", searchable);
        property.put("classification", "INTERNAL");
        return property;
    }

    private ObjectNode revision(long value) {
        ObjectNode body = mapper.createObjectNode();
        body.put("expectedRevision", value);
        return body;
    }

    private record Prepared(String draftId, String packageId, long revision) {}

    private Prepared validDraft() throws Exception {
        String packageId = freshPackage();
        jdbc.update("INSERT INTO b0_package_assignment(workspace_id,package_id) VALUES (?,?) ON CONFLICT DO NOTHING",
                WORKSPACE, packageId);
        ObjectNode create = mapper.createObjectNode();
        create.put("packageId", packageId);
        String draftId = call(HttpMethod.POST, "/drafts", create, 201).path("draftId").asText();
        ObjectNode save = revision(1);
        save.set("editingContent", editorDocument());
        JsonNode saved = call(HttpMethod.PUT, "/drafts/" + draftId, save, 200);
        assertThat(saved.path("revision").asLong()).isEqualTo(2);
        assertThat(call(HttpMethod.POST, "/drafts/" + draftId + "/validate", revision(2), 200)
                .path("status").asText()).isEqualTo("VALID");
        return new Prepared(draftId, packageId, 2);
    }

    @Test
    void validatedDraftCatalogIsDeterministicAndReturnsHonestPropertyContracts() throws Exception {
        Prepared draft = validDraft();
        JsonNode catalog = call(HttpMethod.GET,
                "/type-sources/drafts/" + draft.draftId() + "/types?revision=2", null, 200);

        assertThat(catalog.path("sourceRef").path("kind").asText()).isEqualTo("VALIDATED_DRAFT");
        assertThat(catalog.path("sourceRef").path("draftId").asText()).isEqualTo(draft.draftId());
        assertThat(catalog.path("sourceRef").path("revision").asLong()).isEqualTo(2);
        assertThat(catalog.path("packageId").asText()).isEqualTo(draft.packageId());
        assertThat(catalog.path("modelSourceRevision").asLong()).isEqualTo(2);
        assertThat(catalog.path("lifecycle").asText()).isEqualTo("VALIDATED_DRAFT_ONLY");
        assertThat(catalog.path("items").size()).isEqualTo(2);
        assertThat(catalog.path("items").get(0).path("objectTypeId").asText()).isEqualTo("purchase-order-type");
        assertThat(catalog.has("nextCursor")).isFalse();

        JsonNode detail = call(HttpMethod.GET,
                "/type-sources/drafts/" + draft.draftId() + "/types/supplier-type?revision=2", null, 200);
        assertThat(detail.path("apiName").asText()).isEqualTo("Supplier");
        assertThat(detail.path("primaryKeyRef").asText()).isEqualTo("supplier-id");
        assertThat(detail.path("properties").size()).isEqualTo(3);
        assertThat(detail.path("properties").get(0).path("required").asBoolean()).isTrue();
        assertThat(detail.path("properties").get(0).path("searchable").asBoolean()).isFalse();
        assertThat(detail.path("properties").get(2).path("enumValues").toString())
                .isEqualTo("[\"ACTIVE\",\"INACTIVE\"]");
        assertThat(detail.has("payloadSha256")).isFalse();
    }

    @Test
    void staleUnvalidatedAndMalformedRefsFailClosed() throws Exception {
        Prepared draft = validDraft();
        JsonNode stale = call(HttpMethod.GET,
                "/type-sources/drafts/" + draft.draftId() + "/types?revision=1", null, 409);
        assertThat(stale.path("code").asText()).isEqualTo("TYPE_SOURCE_REVISION_CONFLICT");

        JsonNode missing = call(HttpMethod.GET,
                "/type-sources/drafts/" + draft.draftId() + "/types/not-a-valid-id?revision=2", null, 400);
        assertThat(missing.path("code").asText()).isEqualTo("MALFORMED_TYPE_REF");

        JsonNode absent = call(HttpMethod.GET,
                "/type-sources/drafts/" + draft.draftId() + "/types/does-not-exist?revision=2", null, 404);
        assertThat(absent.path("code").asText()).isEqualTo("OBJECT_TYPE_NOT_FOUND");

        String packageId = freshPackage();
        jdbc.update("INSERT INTO b0_package_assignment(workspace_id,package_id) VALUES (?,?) ON CONFLICT DO NOTHING",
                WORKSPACE, packageId);
        ObjectNode create = mapper.createObjectNode();
        create.put("packageId", packageId);
        String unvalidatedId = call(HttpMethod.POST, "/drafts", create, 201).path("draftId").asText();
        JsonNode unvalidated = call(HttpMethod.GET,
                "/type-sources/drafts/" + unvalidatedId + "/types?revision=1", null, 409);
        assertThat(unvalidated.path("code").asText()).isEqualTo("TYPE_SOURCE_NOT_VALIDATED");

        ResponseEntity<String> missingRevision = request(HttpMethod.GET,
                "/type-sources/drafts/" + draft.draftId() + "/types", null, "editor-A");
        assertThat(missingRevision.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void localSnapshotCatalogUsesVerifiedImmutableSnapshotAndDigest() throws Exception {
        Prepared draft = validDraft();
        ObjectNode freeze = revision(draft.revision());
        freeze.put("idempotencyKey", UUID.randomUUID().toString());
        JsonNode snapshot = call(HttpMethod.POST,
                "/drafts/" + draft.draftId() + "/snapshots", freeze, 201);
        String snapshotId = snapshot.path("snapshotId").asText();

        JsonNode catalog = call(HttpMethod.GET, "/type-sources/snapshots/" + snapshotId + "/types", null, 200);
        assertThat(catalog.path("sourceRef").path("kind").asText()).isEqualTo("LOCAL_SNAPSHOT");
        assertThat(catalog.path("sourceRef").path("snapshotId").asText()).isEqualTo(snapshotId);
        assertThat(catalog.path("lifecycle").asText()).isEqualTo("LOCAL_SNAPSHOT_ONLY");
        assertThat(catalog.path("items").size()).isEqualTo(2);

        JsonNode detail = call(HttpMethod.GET,
                "/type-sources/snapshots/" + snapshotId + "/types/supplier-type", null, 200);
        assertThat(detail.path("modelSourceRevision").asLong()).isEqualTo(draft.revision());
        assertThat(detail.path("payloadSha256").asText()).matches("[0-9a-f]{64}");
        assertThat(detail.path("lifecycle").asText()).isEqualTo("LOCAL_SNAPSHOT_ONLY");
    }

    @Test
    void packageAuthorizationIsRecheckedForEveryTypeRead() throws Exception {
        Prepared draft = validDraft();
        ResponseEntity<String> response = request(HttpMethod.GET,
                "/type-sources/drafts/" + draft.draftId() + "/types?revision=2", null, "unassigned-user");
        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(mapper.readTree(response.getBody()).path("code").asText()).isEqualTo("ACCESS_DENIED");
    }
}
