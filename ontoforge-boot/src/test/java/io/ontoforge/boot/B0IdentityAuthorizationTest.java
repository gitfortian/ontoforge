package io.ontoforge.boot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/** B0 real PostgreSQL + signed JWT HTTP authorization tests. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(B0TestSecurityConfiguration.class)
class B0IdentityAuthorizationTest {
    private static final UUID WORKSPACE = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID EDITOR = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID VIEWER = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID OWNER = UUID.fromString("44444444-4444-4444-8444-444444444444");

    @Autowired TestRestTemplate http;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtEncoder jwtEncoder;
    @org.springframework.beans.factory.annotation.Value("${local.server.port}") int port;

    @BeforeEach
    void seedAccess() {
        jdbc.update("INSERT INTO b0_workspace(workspace_id,workspace_key,display_name) VALUES (?,?,?) ON CONFLICT DO NOTHING",
                WORKSPACE, "b0-test", "B0 Test Workspace");
        upsertPrincipal(EDITOR, "editor-A", "Test Editor");
        upsertPrincipal(VIEWER, "viewer-A", "Test Viewer");
        upsertPrincipal(OWNER, "owner-A", "Test Owner");
        jdbc.update("INSERT INTO b0_workspace_membership(workspace_id,principal_id,role) VALUES (?,?,?) ON CONFLICT DO NOTHING",
                WORKSPACE, EDITOR, "EDITOR");
        jdbc.update("INSERT INTO b0_workspace_membership(workspace_id,principal_id,role) VALUES (?,?,?) ON CONFLICT DO NOTHING",
                WORKSPACE, VIEWER, "VIEWER");
        jdbc.update("INSERT INTO b0_workspace_membership(workspace_id,principal_id,role) VALUES (?,?,?) ON CONFLICT DO NOTHING",
                WORKSPACE, OWNER, "OWNER");
    }

    private void upsertPrincipal(UUID id, String subject, String displayName) {
        jdbc.update("""
                INSERT INTO b0_principal(principal_id,issuer,subject,display_name)
                VALUES (?,?,?,?)
                ON CONFLICT (issuer,subject)
                DO UPDATE SET display_name=EXCLUDED.display_name
                """, id, B0TestSecurityConfiguration.ISSUER, subject, displayName);
    }

    private String url(String path) {
        return "http://127.0.0.1:" + port + path;
    }

    private ResponseEntity<String> call(
            HttpMethod method, String path, String token, String body, String... spoofHeaders) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        for (int i = 0; i + 1 < spoofHeaders.length; i += 2) {
            headers.set(spoofHeaders[i], spoofHeaders[i + 1]);
        }
        return http.exchange(URI.create(url(path)), method,
                new HttpEntity<>(body, headers), String.class);
    }

    private String editorToken() {
        return B0TestSecurityConfiguration.token(jwtEncoder, "editor-A");
    }

    private String viewerToken() {
        return B0TestSecurityConfiguration.token(jwtEncoder, "viewer-A");
    }

    private String outsiderToken() {
        return B0TestSecurityConfiguration.token(jwtEncoder, "outsider-A");
    }

    private String createAuthorizedDraft() throws Exception {
        String packageId = "manufacturing.b0" + UUID.randomUUID().toString().replace("-", "");
        jdbc.update("INSERT INTO b0_package_assignment(workspace_id,package_id) VALUES (?,?)",
                WORKSPACE, packageId);
        ObjectNode body = mapper.createObjectNode();
        body.put("packageId", packageId);
        ResponseEntity<String> response = call(HttpMethod.POST, "/api/model/drafts",
                editorToken(), mapper.writeValueAsString(body));
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        return mapper.readTree(response.getBody()).path("draftId").asText();
    }

    @Test
    void unauthenticatedModelApiIsRejected() {
        ResponseEntity<String> response = call(HttpMethod.GET, "/api/model/drafts", null, null);
        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(response.getBody()).contains("AUTHENTICATION_REQUIRED");
    }

    @Test
    void editorCanReadAndCreateAuthorizedPackageDraft() throws Exception {
        String packageId = "manufacturing.editor" + UUID.randomUUID().toString().replace("-", "");
        jdbc.update("INSERT INTO b0_package_assignment(workspace_id,package_id) VALUES (?,?)",
                WORKSPACE, packageId);
        ObjectNode body = mapper.createObjectNode();
        body.put("packageId", packageId);

        ResponseEntity<String> create = call(HttpMethod.POST, "/api/model/drafts",
                editorToken(), mapper.writeValueAsString(body));
        assertThat(create.getStatusCode().value()).isEqualTo(201);

        ResponseEntity<String> list = call(HttpMethod.GET, "/api/model/drafts?pageSize=20",
                editorToken(), null);
        assertThat(list.getStatusCode().value()).isEqualTo(200);
        assertThat(list.getBody()).contains(packageId);
    }

    @Test
    void viewerCannotReadRestrictedDraftOrWriteIt() throws Exception {
        String draftId = createAuthorizedDraft();
        ResponseEntity<String> read = call(HttpMethod.GET, "/api/model/drafts/" + draftId,
                viewerToken(), null);
        assertThat(read.getStatusCode().value()).isEqualTo(403);
        assertThat(read.getBody()).contains("ACCESS_DENIED");

        ObjectNode body = mapper.createObjectNode();
        body.put("expectedRevision", 1);
        ObjectNode content = mapper.createObjectNode();
        content.put("editorSchemaVersion", "0.1.0");
        content.putArray("objectTypes");
        content.putArray("linkTypes");
        body.set("editingContent", content);
        ResponseEntity<String> save = call(HttpMethod.PUT, "/api/model/drafts/" + draftId,
                viewerToken(), mapper.writeValueAsString(body));
        assertThat(save.getStatusCode().value()).isEqualTo(403);
    }

    @Test
    void outsiderCannotDiscoverDraftContent() throws Exception {
        String draftId = createAuthorizedDraft();
        ResponseEntity<String> response = call(HttpMethod.GET, "/api/model/drafts/" + draftId,
                outsiderToken(), null);
        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(response.getBody()).doesNotContain("manufacturing.b0");
    }

    @Test
    void clientSuppliedIdentityHeadersDoNotElevateViewer() throws Exception {
        String draftId = createAuthorizedDraft();
        ResponseEntity<String> response = call(HttpMethod.GET, "/api/model/drafts/" + draftId,
                viewerToken(), null, "X-User-Id", "editor-A", "X-Role", "EDITOR");
        assertThat(response.getStatusCode().value()).isEqualTo(403);
    }

    @Test
    void wrongIssuerAudienceAndExpiredTokensAreRejected() {
        String wrongIssuer = B0TestSecurityConfiguration.token(
                jwtEncoder, "editor-A", "https://attacker.example", List.of(B0TestSecurityConfiguration.AUDIENCE),
                Instant.now().plusSeconds(300));
        String wrongAudience = B0TestSecurityConfiguration.token(
                jwtEncoder, "editor-A", B0TestSecurityConfiguration.ISSUER, List.of("other-service"),
                Instant.now().plusSeconds(300));
        String expired = B0TestSecurityConfiguration.token(
                jwtEncoder, "editor-A", B0TestSecurityConfiguration.ISSUER,
                List.of(B0TestSecurityConfiguration.AUDIENCE), Instant.now().minusSeconds(30));

        for (String token : List.of(wrongIssuer, wrongAudience, expired)) {
            ResponseEntity<String> response = call(HttpMethod.GET, "/api/model/drafts", token, null);
            assertThat(response.getStatusCode().value()).isEqualTo(401);
            assertThat(response.getBody()).contains("AUTHENTICATION_REQUIRED");
        }
    }

    @Test
    void revokedEditorMembershipImmediatelyRemovesPackageAccess() throws Exception {
        String draftId = createAuthorizedDraft();
        ResponseEntity<String> before = call(HttpMethod.GET, "/api/model/drafts/" + draftId,
                editorToken(), null);
        assertThat(before.getStatusCode().value()).isEqualTo(200);

        jdbc.update("DELETE FROM b0_workspace_membership WHERE workspace_id=? AND principal_id=? AND role='EDITOR'",
                WORKSPACE, EDITOR);
        ResponseEntity<String> after = call(HttpMethod.GET, "/api/model/drafts/" + draftId,
                editorToken(), null);
        assertThat(after.getStatusCode().value()).isEqualTo(403);
    }

    @Test
    void meReturnsOnlyServerDerivedIdentityAndAssignments() throws Exception {
        String packageId = "manufacturing.me" + UUID.randomUUID().toString().replace("-", "");
        jdbc.update("INSERT INTO b0_package_assignment(workspace_id,package_id) VALUES (?,?)",
                WORKSPACE, packageId);
        ResponseEntity<String> response = call(HttpMethod.GET, "/api/me", editorToken(), null);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode result = mapper.readTree(response.getBody());
        assertThat(result.path("subject").asText()).isEqualTo("editor-A");
        assertThat(result.path("issuer").asText()).isEqualTo(B0TestSecurityConfiguration.ISSUER);
        assertThat(result.path("workspaces").get(0).path("packageIds").toString()).contains(packageId);
        assertThat(result.toString()).doesNotContain("X-Role");
    }

    @Test
    void systemStatusRemainsPublicButReportsB0Capability() throws Exception {
        ResponseEntity<String> response = call(HttpMethod.GET, "/api/system/status", null, null);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("JWT_PACKAGE_AUTHORIZATION");
        assertThat(response.getBody()).doesNotContain("principal");
        assertThat(response.getBody()).doesNotContain("role");
    }
}
