package io.ontoforge.boot.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * M5 source connection is separate from the metadata/application datasource.
 * It is intentionally absent until a dedicated, provisioned read-only login is configured.
 */
@Configuration(proxyBeanMethods = false)
public class BindingSourceConfiguration {
    @Bean(name = "bindingSourceDataSource", destroyMethod = "close")
    @ConditionalOnProperty(prefix = "ontoforge.binding.source", name = "url")
    HikariDataSource bindingSourceDataSource(
            @Value("${ontoforge.binding.source.url}") String url,
            @Value("${ontoforge.binding.source.username:}") String username,
            @Value("${ontoforge.binding.source.password:}") String password,
            @Value("${spring.datasource.url}") String applicationUrl,
            @Value("${spring.datasource.username:}") String applicationUsername,
            @Value("${server.address:127.0.0.1}") String serverAddress) {
        validateSourceEnvironment(url, username, password, applicationUrl, applicationUsername, serverAddress);
        HikariConfig config = new HikariConfig();
        config.setPoolName("ontoforge-m5-source-readonly");
        config.setJdbcUrl(url);
        config.setUsername(username);
        config.setPassword(password);
        config.setReadOnly(true);
        config.setMaximumPoolSize(3);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(3000);
        config.setValidationTimeout(2000);
        config.setInitializationFailTimeout(-1);
        config.setConnectionInitSql("SET default_transaction_read_only = on");
        return new HikariDataSource(config);
    }

    @Bean(name = "bindingSourceJdbcTemplate")
    @ConditionalOnProperty(prefix = "ontoforge.binding.source", name = "url")
    JdbcTemplate bindingSourceJdbcTemplate(
            @org.springframework.beans.factory.annotation.Qualifier("bindingSourceDataSource")
            javax.sql.DataSource dataSource) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.setQueryTimeout(3);
        jdbc.setFetchSize(500);
        return jdbc;
    }

    static void validateSourceEnvironment(String url, String username, String password,
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
