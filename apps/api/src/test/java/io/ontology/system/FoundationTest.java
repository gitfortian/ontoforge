package io.ontology.system;

import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FoundationTest {
    @LocalServerPort int port;
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;

    @Test void realPostgresMigrationAndApiStatus() {
        Map<?, ?> result = http.getForObject(URI.create("http://127.0.0.1:" + port + "/api/system/status"), Map.class);
        assertThat(result).isNotNull();
        assertThat(result.get("database")).isEqualTo("CONNECTED");
        assertThat(result.get("modelModule")).isEqualTo("NOT_IMPLEMENTED");
        assertThat(result.get("authModule")).isEqualTo("NOT_IMPLEMENTED");
        assertThat(result.get("version")).isEqualTo("S0-MVP-0.1");
        String actual = jdbc.queryForObject("SELECT installation_id::text FROM platform_installation WHERE id=1", String.class);
        assertThat(result.get("installationId")).isEqualTo(actual);
    }

    @Test void migrationVersionOneExists() {
        Integer applied = jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE version='1' AND success=true", Integer.class);
        assertThat(applied).isEqualTo(1);
    }
}
