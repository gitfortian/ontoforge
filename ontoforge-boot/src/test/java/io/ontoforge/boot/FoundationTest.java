package io.ontoforge.boot;

import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.assertThat;

@org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@org.springframework.test.context.ActiveProfiles("integration-test")
@org.springframework.test.context.ContextConfiguration(initializers=IsolatedPostgresTestDatabase.class)
class FoundationTest {
    @LocalServerPort int port;
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;

    @Test void realPostgresMigrationAndApiStatus() {
        Map<?, ?> result = http.getForObject(URI.create("http://127.0.0.1:" + port + "/api/system/status"), Map.class);
        assertThat(result).isNotNull();
        assertThat(result.get("service")).isEqualTo("ontoforge");
        assertThat(result.get("database")).isEqualTo("CONNECTED");
        assertThat(result.get("modelModule")).isEqualTo("DRAFT_AVAILABLE");
        assertThat(result.get("snapshotModule")).isEqualTo("LOCAL_SNAPSHOT_AVAILABLE");
        assertThat(result.get("authModule")).isEqualTo("NOT_IMPLEMENTED");
        assertThat(result.get("version")).isEqualTo("S0-MVP-0.3");
        String actual = jdbc.queryForObject("SELECT installation_id::text FROM platform_installation WHERE id=1", String.class);
        assertThat(result.get("installationId")).isEqualTo(actual);
    }

    @Test void migrationVersionOneExists() {
        Integer applied = jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE version='1' AND success=true", Integer.class);
        assertThat(applied).isEqualTo(1);
        Integer draftV2 = jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE version='2' AND success=true", Integer.class);
        assertThat(draftV2).isEqualTo(1);
        Integer snapshotV3=jdbc.queryForObject(
            "SELECT count(*) FROM flyway_schema_history WHERE version='3' AND success=true", Integer.class);
        assertThat(snapshotV3).isEqualTo(1);
    }
}
