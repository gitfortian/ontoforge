package io.ontoforge.boot.security;

import io.ontoforge.boot.security.yak.HttpSessionAccounts;
import io.ontoforge.platform.identity.LocalAccountService;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;

@Configuration(proxyBeanMethods=false)
@Profile("!mvp-local")
@ConditionalOnProperty(name="ontoforge.security.authentication-mode",havingValue="local-session",matchIfMissing=true)
@EnableMethodSecurity
public class LocalSessionSecurityConfig {
    @Bean SecurityContextRepository localSessionContexts() { return new HttpSessionSecurityContextRepository(); }
    @Bean HttpSessionAccounts localSessions(@Value("${server.servlet.session.timeout:30m}") Duration timeout) {
        return new HttpSessionAccounts(timeout);
    }
    @Bean ApplicationRunner bootstrapLocalAccounts(LocalAccountService accounts,
            @Value("${ontoforge.security.bootstrap.enabled:false}") boolean enabled,
            @Value("${ontoforge.security.bootstrap.username:}") String username,
            @Value("${ontoforge.security.bootstrap.password:}") String password,
            @Value("${ontoforge.security.bootstrap.allow-development-default-password:false}") boolean allowDevelopmentDefaultPassword) {
        return args->accounts.bootstrap(enabled,username,password,allowDevelopmentDefaultPassword);
    }
    @Bean SecurityFilterChain localSessionSecurity(HttpSecurity http,LocalAccountService accounts,HttpSessionAccounts sessions,
                                                  SecurityContextRepository contexts)
            throws Exception {
        http.securityContext(context->context.securityContextRepository(contexts))
            .sessionManagement(session->session.sessionCreationPolicy(SessionCreationPolicy.NEVER))
            .requestCache(cache->cache.disable()).formLogin(form->form.disable()).httpBasic(basic->basic.disable()).logout(logout->logout.disable())
            .authorizeHttpRequests(auth->auth
                .requestMatchers(org.springframework.http.HttpMethod.POST,"/api/agent/v1/runs").authenticated()
                .requestMatchers(org.springframework.http.HttpMethod.GET,"/api/context/v1/evidence/*").authenticated()
                .requestMatchers("/api/system/status","/api/auth/context","/api/auth/login","/api/auth/logout").permitAll()
                .requestMatchers("/api/platform/workspaces","/api/platform/workspaces/**",
                        "/api/auth/password","/api/platform/accounts","/api/platform/accounts/**",
                        "/api/me","/api/model/**","/api/mvp/quality/**","/api/runtime/sources/**",
                        "/api/runtime/bindings","/api/runtime/bindings/**","/api/runtime/objects/**","/api/runtime/object-sets/**").authenticated()
                .anyRequest().denyAll())
            .exceptionHandling(errors->errors
                .authenticationEntryPoint((request,response,exception)->AuthResponses.error(response,401,"AUTHENTICATION_REQUIRED","需要登录后才能访问该资源"))
                .accessDeniedHandler((request,response,exception)->{
                    var authentication=org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
                    boolean anonymous=authentication==null || authentication instanceof org.springframework.security.authentication.AnonymousAuthenticationToken;
                    if(anonymous && !request.getRequestURI().startsWith("/api/auth/"))
                        AuthResponses.error(response,401,"AUTHENTICATION_REQUIRED","会话已失效，请重新登录");
                    else AuthResponses.error(response,403,"ACCESS_DENIED","当前请求无权执行或 CSRF 校验失败");
                }))
            .addFilterAfter(new LocalSessionAuthenticationFilter(accounts,sessions),SecurityContextHolderFilter.class);
        return http.build();
    }
}
