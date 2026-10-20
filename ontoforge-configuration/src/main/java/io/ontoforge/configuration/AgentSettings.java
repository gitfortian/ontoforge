package io.ontoforge.configuration;

import java.net.URI;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/** Deployment-owned model destination and explicit synthetic-package egress scope. */
public final class AgentSettings {
    private final boolean enabled;
    private final URI endpoint;
    private final String apiKey;
    private final String model;
    private final Set<String> allowedPackages;

    public AgentSettings(boolean enabled, String baseUrl, String apiKey, String model,
                         String allowedPackages, String serverAddress) {
        this.enabled = enabled;
        if (!enabled) {
            this.endpoint = null;
            this.apiKey = null;
            this.model = null;
            this.allowedPackages = Set.of();
            return;
        }
        if (!isLoopbackBind(serverAddress)) {
            throw invalid("Agent requires a loopback application bind address");
        }
        if (!safeText(apiKey, 4096) || apiKey.codePoints().anyMatch(c -> c < 0x21 || c > 0x7e)
                || !safeText(model, 200)) {
            throw invalid("Agent requires an externally supplied API key and model");
        }
        Set<String> packages = Arrays.stream(allowedPackages == null ? new String[0] : allowedPackages.split(",", -1))
                .map(String::trim).collect(Collectors.toUnmodifiableSet());
        if (packages.isEmpty() || packages.size() > 100 || packages.stream().anyMatch(value ->
                value.length() > 160 || !value.matches("[a-z][a-z0-9-]*(\\.[a-z][a-z0-9-]*)+"))) {
            throw invalid("Agent requires an explicit synthetic-package allowlist");
        }
        this.endpoint = endpoint(baseUrl);
        this.apiKey = apiKey;
        this.model = model;
        this.allowedPackages = packages;
    }

    private static URI endpoint(String value) {
        try {
            if (value == null || value.isBlank() || value.length() > 2048) throw invalid("Invalid model base URL");
            URI base = URI.create(value);
            String host = base.getHost();
            String path = base.getRawPath();
            boolean literalLoopback = "127.0.0.1".equals(host) || "[::1]".equals(host) || "::1".equals(host);
            if (base.isOpaque() || host == null || host.isBlank() || base.getRawUserInfo() != null
                    || base.getRawQuery() != null || base.getRawFragment() != null
                    || !("https".equalsIgnoreCase(base.getScheme())
                         || ("http".equalsIgnoreCase(base.getScheme()) && literalLoopback))
                    || base.getPort() == 0 || base.getPort() > 65535
                    || path.contains("%") || path.contains("\\") || path.contains("//")
                    || !base.normalize().getRawPath().equals(path)) {
                throw invalid("Invalid model base URL");
            }
            String root = value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
            if (root.endsWith("/chat/completions")) throw invalid("Model base URL must identify the API root");
            return URI.create(root + "/chat/completions");
        } catch (IllegalArgumentException e) {
            // Deliberately omit the supplied URI and exception cause: it may contain credentials.
            throw invalid("Invalid model base URL");
        }
    }

    private static boolean safeText(String value, int maxLength) {
        return value != null && !value.isBlank() && value.length() <= maxLength
                && value.codePoints().noneMatch(Character::isISOControl);
    }

    private static boolean isLoopbackBind(String value) {
        return "127.0.0.1".equals(value) || "localhost".equalsIgnoreCase(value) || "::1".equals(value);
    }

    private static IllegalStateException invalid(String message) { return new IllegalStateException(message); }
    public boolean enabled() { return enabled; }
    public URI endpoint() { return endpoint; }
    public String apiKey() { return apiKey; }
    public String model() { return model; }
    public Set<String> allowedPackages() { return allowedPackages; }

    @Override public String toString() {
        return "AgentSettings[enabled=" + enabled + ", allowedPackageCount=" + allowedPackages.size() + "]";
    }
}
