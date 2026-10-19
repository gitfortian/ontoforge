package io.ontoforge.dao.platform;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Reads the persisted installation identity created by Flyway V1. */
@Repository
public class InstallationRepository {
    private final JdbcTemplate jdbc;
    public InstallationRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public String installationId() {
        String id=jdbc.queryForObject("SELECT installation_id::text FROM platform_installation WHERE id = 1",String.class);
        if(id==null)throw new IllegalStateException("Migration is incomplete");
        return id;
    }
}
