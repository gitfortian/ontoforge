package io.ontoforge.boot;

import java.net.URI;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.MediaType;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import static org.assertj.core.api.Assertions.assertThat;

@org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"mvp-local","integration-test"})
@ContextConfiguration(initializers=IsolatedPostgresTestDatabase.class)
class LocalMvpCoreStoryTest {
    @LocalServerPort int port;
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;

    private String url(String route){return "http://127.0.0.1:"+port+route;}
    private ResponseEntity<String> post(String path,String body){
        HttpHeaders headers=new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return http.postForEntity(URI.create(url(path)),new HttpEntity<>(body,headers),String.class);
    }

    @Test void localModeNeedsNoOidcAndActuallyRunsModelAndTwoHopTrace() {
        String status=http.getForObject(url("/api/system/status"),String.class);
        assertThat(status).contains("LOCAL_MVP_ONLY");
        String identity=http.getForObject(url("/api/me"),String.class);
        assertThat(identity).contains("\"issuer\":\"local-mvp\"");
        String packageId="manufacturing.local"+UUID.randomUUID().toString().replace("-","");
        ResponseEntity<String> create=post("/api/model/drafts","{\"packageId\":\""+packageId+"\"}");
        assertThat(create.getStatusCode().value()).isEqualTo(201);
        assertThat(create.getBody()).contains(packageId);
        String listing=http.getForObject(url("/api/model/drafts"),String.class);
        assertThat(listing).contains(packageId);

        ResponseEntity<String> model=post("/api/mvp/quality/model","{}");
        assertThat(model.getStatusCode().value()).describedAs(model.getBody()).isEqualTo(200);
        String sources=http.getForObject(url("/api/mvp/quality/sources"),String.class);
        assertThat(sources).contains("mvp_source_batches");
        ResponseEntity<String> preview=post("/api/mvp/quality/preview","{\"objectTypes\":[],\"links\":[]}");
        assertThat(preview.getStatusCode().value()).describedAs(preview.getBody()).isEqualTo(200);
        assertThat(preview.getBody()).contains("B2026-001");
        ResponseEntity<String> bind=post("/api/mvp/quality/bind","{\"objectTypes\":[],\"links\":[]}");
        assertThat(bind.getStatusCode().value()).describedAs(bind.getBody()).isEqualTo(200);
        String trace=http.getForObject(url("/api/mvp/quality/trace?batchId=B2026-001"),String.class);
        assertThat(trace).contains("B2026-001","WO-1001","ORD-9001");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM mvp_demo_binding",Integer.class)).isGreaterThan(0);
    }

    @Test void unrelatedApiIsNotOpened() {
        var response=http.getForEntity(url("/api/admin"),String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(403);
    }
}
