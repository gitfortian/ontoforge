package io.ontoforge.boot.controller;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** S0 MVP: a factual, read-only health overview. Never returns invented model metrics. */
@RestController
@RequestMapping("/api/system")
public class SystemStatusController {
    private final JdbcTemplate jdbc;
    public SystemStatusController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record PlatformStatus(String service, String version, String database,
                                 String installationId, String modelModule,
                                 String snapshotModule, String authModule) {}

    @GetMapping("/status")
    public PlatformStatus status() {
        String installationId = jdbc.queryForObject(
                "SELECT installation_id::text FROM platform_installation WHERE id = 1",
                String.class);
        if (installationId == null) throw new IllegalStateException("Migration is incomplete");
        return new PlatformStatus("ontoforge", "S0-MVP-0.3", "CONNECTED",
                installationId, "DRAFT_AVAILABLE", "LOCAL_SNAPSHOT_AVAILABLE", "JWT_PACKAGE_AUTHORIZATION");
    }
}
