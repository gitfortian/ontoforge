package io.ontoforge.boot;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;

/** Runs before DataSource/Flyway initialization, without touching any database. */
final class IsolatedPostgresTestDatabase
        implements ApplicationContextInitializer<ConfigurableApplicationContext> {
    static void requireIsolatedUrl(String url) {
        if (url == null || !url.matches(
                "jdbc:postgresql://(?:127\\.0\\.0\\.1|localhost|\\[::1\\])(?::[0-9]{1,5})?/ontoforge_test_[A-Za-z0-9_]+")) {
            throw new IllegalStateException(
                    "Integration tests require TEST_DB_URL on loopback with database name ontoforge_test_*. "
                    + "Development DB_* connections and JDBC options are not accepted.");
        }
    }

    @Override public void initialize(ConfigurableApplicationContext context) {
        requireIsolatedUrl(context.getEnvironment().getProperty("spring.datasource.url"));
    }

    static void requireFaultInjection(Environment environment) {
        requireIsolatedUrl(environment.getProperty("spring.datasource.url"));
        if (!"true".equals(environment.getProperty("TEST_DB_ALLOW_FAULT_INJECTION"))) {
            throw new IllegalStateException(
                    "Snapshot corruption tests require TEST_DB_ALLOW_FAULT_INJECTION=true on the isolated test database.");
        }
    }
}
