package io.ontoforge.configuration;

import java.net.URI;

/** Existing local configuration guards, shared by assembly and tests. */
public final class BindingSourceSettings {
    private BindingSourceSettings() {}
    public static void validateSourceEnvironment(String url, String username, String password,
                                          String applicationUrl, String applicationUsername,
                                          String serverAddress) {
        if (url == null || username == null || username.isBlank()
                || password == null || password.isBlank()) {
            throw new IllegalStateException("M5 source requires a dedicated username and password");
        }
        if (username.equals(applicationUsername)) {
            throw new IllegalStateException("M5 source login must differ from the application database login");
        }
        if (url.contains("?") || url.contains("#") || !url.startsWith("jdbc:postgresql://")) {
            throw new IllegalStateException("M5 source URL must not contain JDBC options or embedded credentials");
        }
        URI source;
        URI application;
        try {
            source = URI.create(url.substring("jdbc:".length()));
            application = URI.create(applicationUrl.substring("jdbc:".length()));
        } catch (RuntimeException ex) {
            throw new IllegalStateException("M5 source requires a valid PostgreSQL JDBC URL");
        }
        String host = source.getHost();
        String dbPath = source.getPath();
        String database = dbPath != null && dbPath.startsWith("/") ? dbPath.substring(1) : "";
        String appHost = application.getHost();
        String appPath = application.getPath();
        String appDatabase = appPath != null && appPath.startsWith("/") ? appPath.substring(1) : "";
        int sourcePort = source.getPort() < 0 ? 5432 : source.getPort();
        int applicationPort = application.getPort() < 0 ? 5432 : application.getPort();
        if (host == null || !(host.equals("127.0.0.1") || host.equalsIgnoreCase("localhost"))
                || !database.matches("(?:ontoforge|ontoforge_test_[a-z0-9_]+)")
                || source.getUserInfo() != null || source.getFragment() != null
                || !database.equals(appDatabase) || !host.equalsIgnoreCase(appHost)
                || sourcePort != applicationPort
                || (serverAddress != null && !serverAddress.equals("127.0.0.1")
                    && !serverAddress.equalsIgnoreCase("localhost"))) {
            throw new IllegalStateException("M5 local source requires the same loopback ontoforge sandbox database");
        }
        if (!username.matches("[a-z_][a-z0-9_]{0,62}") || username.equalsIgnoreCase("postgres")) {
            throw new IllegalStateException("M5 source login must be a dedicated non-superuser role");
        }
    }
}
