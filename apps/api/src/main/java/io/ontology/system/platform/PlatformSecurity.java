package io.ontology.system.platform;

import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

@Configuration
public class PlatformSecurity {
  @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }
  @Bean UserDetailsService userDetailsService(JdbcTemplate jdbc) {
    return name -> jdbc.query("SELECT username,password_hash,enabled FROM app_user WHERE username=?",
      (rs,n) -> User.withUsername(rs.getString("username"))
        .password(rs.getString("password_hash")).disabled(!rs.getBoolean("enabled"))
        .roles("USER").build(), name)
      .stream().findFirst().orElseThrow(() -> new UsernameNotFoundException("Invalid credentials"));
  }
  @Bean AuthenticationManager authenticationManager(UserDetailsService users, PasswordEncoder encoder) {
    DaoAuthenticationProvider provider = new DaoAuthenticationProvider(users);
    provider.setPasswordEncoder(encoder);
    return new ProviderManager(List.of(provider));
  }
  @Bean SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    var csrf = CookieCsrfTokenRepository.withHttpOnlyFalse();
    csrf.setCookiePath("/");
    http.csrf(c -> c.csrfTokenRepository(csrf).csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
      .authorizeHttpRequests(r -> r
        .requestMatchers("/actuator/health","/api/csrf","/api/auth/login").permitAll()
        .anyRequest().authenticated())
      .exceptionHandling(e -> e.authenticationEntryPoint(
        (req,res,ex) -> res.sendError(HttpStatus.UNAUTHORIZED.value())))
      .securityContext(c -> c.securityContextRepository(new HttpSessionSecurityContextRepository()))
      .logout(l -> l.logoutRequestMatcher(new AntPathRequestMatcher("/api/auth/logout","POST"))
        .logoutSuccessHandler((req,res,auth) -> res.setStatus(204)))
      .requestCache(c -> c.disable());
    return http.build();
  }
}
