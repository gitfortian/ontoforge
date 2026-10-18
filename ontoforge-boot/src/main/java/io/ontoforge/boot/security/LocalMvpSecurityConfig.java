package io.ontoforge.boot.security;

import java.net.URI;
import java.util.Locale;
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
    static void requireIsolatedEnvironment(String bindAddress, String jdbcUrl) {
        if(!"127.0.0.1".equals(bindAddress) && !"localhost".equalsIgnoreCase(bindAddress))
            throw new IllegalStateException("mvp-local requires server.address=127.0.0.1");
        URI address;
        try {
            if(jdbcUrl==null || !jdbcUrl.startsWith("jdbc:postgresql://"))
                throw new IllegalArgumentException("invalid JDBC URL");
            address = URI.create(jdbcUrl.substring("jdbc:".length()));
        } catch(IllegalArgumentException e) {
            throw new IllegalStateException("mvp-local needs loopback PostgreSQL test JDBC URL",e);
        }
        String host=address.getHost();
        String path=address.getPath();
        String db=path!=null && path.startsWith("/") ? path.substring(1) : "";
        if(host==null || !("127.0.0.1".equals(host)||"localhost".equalsIgnoreCase(host))
           || !db.matches("(?:ontoforge|ontoforge_test_[a-z0-9_]+)")
           || address.getUserInfo()!=null || address.getFragment()!=null) {
            throw new IllegalStateException("mvp-local REFUSED: only loopback ontoforge / ontoforge_test_* DB");
        }
    }

    @Bean
    SecurityFilterChain localMvpSecurityFilterChain(HttpSecurity http,
             @Value("${server.address:127.0.0.1}") String bindAddress,
             @Value("${spring.datasource.url}") String jdbcUrl) throws Exception {
        requireIsolatedEnvironment(bindAddress,jdbcUrl);
        http.csrf(csrf->csrf.disable())
            .authorizeHttpRequests(authorize->authorize
                .requestMatchers("/api/system/status","/api/me","/api/model/**",
                                 "/api/mvp/quality/**", "/api/runtime/sources/**",
                                 "/api/runtime/bindings", "/api/runtime/bindings/**", "/api/runtime/objects/**").permitAll()
                .anyRequest().denyAll());
        return http.build();
    }
}
