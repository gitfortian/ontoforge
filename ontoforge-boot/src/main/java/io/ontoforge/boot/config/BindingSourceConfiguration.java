package io.ontoforge.boot.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.ontoforge.configuration.BindingSourceSettings;
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
        BindingSourceSettings.validateSourceEnvironment(url, username, password, applicationUrl, applicationUsername, serverAddress);
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


}
