package io.ontology.system.platform;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** S0 MVP: a factual, read-only health overview. Never returns invented model metrics. */
@RestController
@RequestMapping("/api/system")
public class SystemController {
    private final JdbcTemplate jdbc;
    public SystemController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record PlatformStatus(String service, String version, String database,
                                 String installationId, String modelModule,
                                 String authModule) {}

    @GetMapping("/status")
    public PlatformStatus status() {
        String installationId = jdbc.queryForObject(
                "SELECT installation_id::text FROM platform_installation WHERE id = 1",
                String.class);
        if (installationId == null) throw new IllegalStateException("Migration is incomplete");
        return new PlatformStatus("ontology-system", "S0-MVP-0.1", "CONNECTED",
                installationId, "NOT_IMPLEMENTED", "NOT_IMPLEMENTED");
    }
}
