package io.ontoforge.boot.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Keeps the application metadata database authoritative when optional source
 * connections are enabled. Flyway and application repositories must never
 * silently switch to a read-only external/source datasource.
 */
@Configuration(proxyBeanMethods = false)
public class ApplicationDataSourceConfiguration {
    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource.hikari")
    HikariDataSource dataSource(DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
    }
}
