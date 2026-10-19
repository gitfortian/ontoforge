package io.ontoforge.dao.platform;

import org.springframework.jdbc.core.JdbcTemplate;

/** Inside the caller's write transaction, serializes first Draft creation with package allocation. */
public final class PackageIdentityLock {
    private PackageIdentityLock() {}
    public static void lock(JdbcTemplate jdbc,String packageId) {
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended('ontoforge:package:' || ?,0))",Object.class,packageId);
    }
}
