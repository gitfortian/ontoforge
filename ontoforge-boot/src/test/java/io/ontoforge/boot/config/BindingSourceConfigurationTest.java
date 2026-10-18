package io.ontoforge.boot.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BindingSourceConfigurationTest {
    private static final String SOURCE_USER = "ontoforge_binding_login";
    private static final String SOURCE_PASSWORD = "test-only-password";
    private static final String APP_USER = "ontology";

    @Test
    void acceptsOnlySameLoopbackPostgresDatabaseAndPort() {
        assertThatCode(() -> BindingSourceConfiguration.validateSourceEnvironment(
                "jdbc:postgresql://127.0.0.1:5432/ontoforge_test_ci",
                SOURCE_USER, SOURCE_PASSWORD,
                "jdbc:postgresql://127.0.0.1:5432/ontoforge_test_ci",
                APP_USER, "127.0.0.1"))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsDifferentPostgresPortEvenWhenHostAndDatabaseNameMatch() {
        assertThatThrownBy(() -> BindingSourceConfiguration.validateSourceEnvironment(
                "jdbc:postgresql://127.0.0.1:5433/ontoforge_test_ci",
                SOURCE_USER, SOURCE_PASSWORD,
                "jdbc:postgresql://127.0.0.1:5432/ontoforge_test_ci",
                APP_USER, "127.0.0.1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("same loopback ontoforge sandbox database");
    }

    @Test
    void rejectsReusingApplicationDatabaseLoginForSourceReads() {
        assertThatThrownBy(() -> BindingSourceConfiguration.validateSourceEnvironment(
                "jdbc:postgresql://127.0.0.1:5432/ontoforge_test_ci",
                APP_USER, SOURCE_PASSWORD,
                "jdbc:postgresql://127.0.0.1:5432/ontoforge_test_ci",
                APP_USER, "127.0.0.1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must differ from the application database login");
    }
}
