package io.ontoforge.boot.security;

import io.ontoforge.configuration.LocalMvpSettings;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Explicit opt-in mode for one local developer using an isolated local PG database.
 * MUST NOT be enabled behind a proxy, port-forward, or outside a test workstation.
 * JWT/OIDC remain enforced by SecurityConfig outside this profile.
 */
@Configuration(proxyBeanMethods=false)
@Profile("mvp-local")
public class LocalMvpSecurityConfig {


    @Bean
    SecurityFilterChain localMvpSecurityFilterChain(HttpSecurity http,
             @Value("${server.address:127.0.0.1}") String bindAddress,
             @Value("${spring.datasource.url}") String jdbcUrl) throws Exception {
        LocalMvpSettings.requireIsolatedEnvironment(bindAddress,jdbcUrl);
        http.csrf(csrf->csrf.disable())
            .authorizeHttpRequests(authorize->authorize
                .requestMatchers("/api/system/status","/api/me","/api/model/**",
                                 "/api/mvp/quality/**", "/api/runtime/sources/**",
                                 "/api/runtime/bindings", "/api/runtime/bindings/**", "/api/runtime/objects/**", "/api/runtime/object-sets/**").permitAll()
                .anyRequest().denyAll());
        return http.build();
    }
}
