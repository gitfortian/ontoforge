package io.ontoforge.configuration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentSettingsTest {
    private static final String KEY = "test-only-secret-key";

    private AgentSettings enabled(String url) {
        return new AgentSettings(true, url, KEY, "configured-model", "demo.synthetic,other.synthetic", "127.0.0.1");
    }

    @Test void disabledDoesNotValidateOrRetainUnusedSecrets() {
        var settings = new AgentSettings(false, "not a URI", KEY, null, null, "0.0.0.0");
        assertThat(settings.enabled()).isFalse();
        assertThat(settings.endpoint()).isNull();
        assertThat(settings.apiKey()).isNull();
        assertThat(settings.allowedPackages()).isEmpty();
        assertThat(settings.toString()).doesNotContain(KEY);
    }

    @Test void preservesConfiguredApiRootAndModelWithoutAssumingAnExtraVersionPrefix() {
        var settings = enabled("https://models.example.test/gateway/v1/");
        assertThat(settings.endpoint().toString()).isEqualTo("https://models.example.test/gateway/v1/chat/completions");
        assertThat(settings.model()).isEqualTo("configured-model");
        assertThat(settings.allowedPackages()).containsExactlyInAnyOrder("demo.synthetic", "other.synthetic");
        assertThat(settings.toString()).doesNotContain(KEY, "models.example.test");
        assertThatThrownBy(() -> settings.allowedPackages().add("new.synthetic")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void permitsHttpOnlyForLiteralLoopbackWithoutDnsResolution() {
        assertThatCode(() -> enabled("http://127.0.0.1:1234/v1")).doesNotThrowAnyException();
        assertThatCode(() -> enabled("http://[::1]:1234/v1")).doesNotThrowAnyException();
        for (String url : new String[]{"http://localhost:1234/v1", "http://192.168.1.2/v1", "http://models.example.test/v1"}) {
            assertThatThrownBy(() -> enabled(url)).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test void rejectsAmbiguousOrCredentialBearingDestinationsWithoutEchoingTheInput() {
        for (String url : new String[]{"https://user:" + KEY + "@models.example.test/v1", "https://models.example.test/v1?key=" + KEY,
                "https://models.example.test/v1#fragment", "https://models.example.test/v1/../admin", "https://models.example.test/v1/./tools",
                "https://models.example.test/v1/%2e%2e/admin", "https://models.example.test/v1//tools", "https://models.example.test:0/v1",
                "https://models.example.test:65536/v1", "file:///v1", "https://models.example.test/v1/chat/completions"}) {
            assertThatThrownBy(() -> enabled(url)).isInstanceOf(IllegalStateException.class)
                    .hasMessageNotContaining(KEY).hasNoCause();
        }
    }

    @Test void requiresExplicitSecretsModelScopeAndLoopbackApplication() {
        for (AgentSettingsInput input : new AgentSettingsInput[]{
                new AgentSettingsInput("", "model", "demo.synthetic", "127.0.0.1"),
                new AgentSettingsInput(KEY, "", "demo.synthetic", "127.0.0.1"),
                new AgentSettingsInput(KEY + "\n", "model", "demo.synthetic", "127.0.0.1"),
                new AgentSettingsInput(KEY + "\u4e2d", "model", "demo.synthetic", "127.0.0.1"),
                new AgentSettingsInput(KEY, "model", "", "127.0.0.1"),
                new AgentSettingsInput(KEY, "model", "demo.synthetic,", "127.0.0.1"),
                new AgentSettingsInput(KEY, "model", "*", "127.0.0.1"),
                new AgentSettingsInput(KEY, "model", "demo.synthetic", "0.0.0.0")}) {
            assertThatThrownBy(() -> new AgentSettings(true, "https://models.example.test/v1", input.key(), input.model(),
                    input.packages(), input.bind())).isInstanceOf(IllegalStateException.class).hasMessageNotContaining(KEY).hasNoCause();
        }
    }

    private record AgentSettingsInput(String key, String model, String packages, String bind) {}
}
