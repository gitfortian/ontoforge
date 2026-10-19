package io.ontoforge.configuration;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalMvpSecurityConfigTest {
    @Test void onlyLoopbackAndExplicitTestDatabasePermitted() {
        assertThatCode(()->LocalMvpSettings.requireIsolatedEnvironment(
            "127.0.0.1","jdbc:postgresql://127.0.0.1:5432/ontoforge_test_quality")).doesNotThrowAnyException();
        assertThatCode(()->LocalMvpSettings.requireIsolatedEnvironment(
            "localhost","jdbc:postgresql://localhost:5432/ontoforge")).doesNotThrowAnyException();
        assertThatThrownBy(()->LocalMvpSettings.requireIsolatedEnvironment(
            "0.0.0.0","jdbc:postgresql://127.0.0.1:5432/ontoforge_test_quality"))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->LocalMvpSettings.requireIsolatedEnvironment(
            "127.0.0.1","jdbc:postgresql://10.1.2.3:5432/ontoforge_test_quality"))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->LocalMvpSettings.requireIsolatedEnvironment(
            "127.0.0.1","jdbc:postgresql://127.0.0.1:5432/production"))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->LocalMvpSettings.requireIsolatedEnvironment(
            "127.0.0.1","jdbc:postgresql://db.example.com:5432/ontoforge"))
            .isInstanceOf(IllegalStateException.class);
    }
}
