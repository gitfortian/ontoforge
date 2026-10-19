package io.ontoforge.boot;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

/** Real Spring Boot HTTP negative contracts with isolated Flyway V8, no mocked endpoints. */
@org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"mvp-local", "integration-test"})
@ContextConfiguration(initializers = IsolatedPostgresTestDatabase.class)
class LinkBindingHttpIntegrationTest {
    @Autowired TestRestTemplate client;
    @LocalServerPort int port;

    private String url(String path) {
        return "http://127.0.0.1:" + port + "/api/runtime/link-bindings" + path;
    }

    private ResponseEntity<String> post(String path, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return client.postForEntity(url(path), new HttpEntity<>(body, headers), String.class);
    }

    @Test
    void emptyPackageListsNoSyntheticLinks() {
        ResponseEntity<String> response = client.getForEntity(
                url("?packageId=m8.no.config." + UUID.randomUUID()), String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo("[]");
    }

    @Test
    void unknownSqlFieldsAndClientSelectedPkAreRejectedBeforeSourceAccess() {
        ResponseEntity<String> query = post("/validate", """
                {"contractVersion":"local-link-binding-v1","rawSql":"SELECT * FROM secret"}
                """);
        assertThat(query.getStatusCode().value()).isEqualTo(422);
        assertThat(query.getBody()).contains("MALFORMED_LINK_CONTRACT");
        assertThat(query.getBody()).doesNotContain("secret");

        ResponseEntity<String> pk = post("/validate", """
                {"contractVersion":"local-link-binding-v1","referencedIdentityColumn":"any_column"}
                """);
        assertThat(pk.getStatusCode().value()).isEqualTo(422);
        assertThat(pk.getBody()).contains("MALFORMED_LINK_CONTRACT");
    }

    @Test
    void missingSnapshotIsRejectedAndNoHeadOrRevisionIsCreated() {
        UUID snapshot = UUID.randomUUID();
        UUID sourceBinding = UUID.randomUUID();
        UUID targetBinding = UUID.randomUUID();
        String body = """
                {
                  "contractVersion":"local-link-binding-v1",
                  "modelSourceRef":{"kind":"LOCAL_SNAPSHOT","snapshotId":"%s"},
                  "packageId":"m8.snapshot.not.found",
                  "linkTypeId":"equipment-inspections",
                  "sourceBindingRef":{"bindingId":"%s","revision":1},
                  "targetBindingRef":{"bindingId":"%s","revision":1},
                  "mode":"FOREIGN_KEY","fkOwner":"TARGET",
                  "fkSourceColumn":"equipment_code","expectedRevision":0,
                  "idempotencyKey":"%s",
                  "expectedSchemaFingerprints":{"source":"%s","target":"%s"},
                  "validationScope":{"kind":"VALIDATION_SCOPE","coverage":"FULL"}
                }
                """.formatted(snapshot, sourceBinding, targetBinding, UUID.randomUUID(),
                "a".repeat(64), "b".repeat(64));
        ResponseEntity<String> result = post("/validate", body);
        assertThat(result.getStatusCode().value()).isEqualTo(404);
        assertThat(result.getBody()).contains("SNAPSHOT_NOT_FOUND");
        ResponseEntity<String> list = client.getForEntity(
                url("?packageId=m8.snapshot.not.found"), String.class);
        assertThat(list.getStatusCode().value()).isEqualTo(200);
        assertThat(list.getBody()).isEqualTo("[]");
    }

    @Test
    void malformedForeignContractsFailClosedWithoutBindingInformation() {
        ResponseEntity<String> unsupported = post("/validate", """
                {"contractVersion":"SCHEMA_RELEASE","modelSourceRef":{}}
                """);
        assertThat(unsupported.getStatusCode().value()).isEqualTo(422);
        assertThat(unsupported.getBody()).contains("MALFORMED_LINK_CONTRACT");
        ResponseEntity<String> absent = client.getForEntity(
                url("/" + UUID.randomUUID() + "?packageId=m8.missing"), String.class);
        assertThat(absent.getStatusCode().value()).isEqualTo(404);
        assertThat(absent.getBody()).contains("LINK_BINDING_NOT_FOUND");
    }
}
