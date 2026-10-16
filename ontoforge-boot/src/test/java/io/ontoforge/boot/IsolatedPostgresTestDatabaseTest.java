package io.ontoforge.boot;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IsolatedPostgresTestDatabaseTest {
    @Test void rejectsDevelopmentRemoteAndAmbiguousConnectionsBeforeConnecting() {
        for (String url : new String[]{null, "", "${TEST_DB_URL}",
                "jdbc:postgresql://127.0.0.1:5432/ontoforge",
                "jdbc:postgresql://example.com/ontoforge_test_ci",
                "jdbc:postgresql://localhost/ontoforge_test_ci?currentSchema=public",
                "jdbc:postgresql://localhost/ontoforge_test_ci/other"}) {
            assertThatThrownBy(() -> IsolatedPostgresTestDatabase.requireIsolatedUrl(url))
                    .isInstanceOf(IllegalStateException.class);
        }
        assertThatCode(() -> IsolatedPostgresTestDatabase.requireIsolatedUrl(
                "jdbc:postgresql://127.0.0.1:55432/ontoforge_test_local")).doesNotThrowAnyException();
    }

    @Test void faultInjectionRequiresExplicitOptInAsWellAsIsolatedDatabase() {
        MockEnvironment env = new MockEnvironment().withProperty(
                "spring.datasource.url", "jdbc:postgresql://localhost:5432/ontoforge_test_ci");
        assertThatThrownBy(() -> IsolatedPostgresTestDatabase.requireFaultInjection(env))
                .isInstanceOf(IllegalStateException.class);
        env.withProperty("TEST_DB_ALLOW_FAULT_INJECTION", "true");
        assertThatCode(() -> IsolatedPostgresTestDatabase.requireFaultInjection(env)).doesNotThrowAnyException();
        env.withProperty("spring.datasource.url", "jdbc:postgresql://localhost:5432/ontoforge");
        assertThatThrownBy(() -> IsolatedPostgresTestDatabase.requireFaultInjection(env))
                .isInstanceOf(IllegalStateException.class);
    }
}
