package io.ontology.system.platform;

import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class BootstrapAdmin implements ApplicationRunner {
  private final JdbcTemplate jdbc;
  private final PasswordEncoder passwordEncoder;
  private final boolean enabled;
  private final String username;
  private final String password;

  public BootstrapAdmin(JdbcTemplate jdbc, PasswordEncoder passwordEncoder,
      @Value("${s0.bootstrap.enabled:false}") boolean enabled,
      @Value("${s0.bootstrap.username:admin}") String username,
      @Value("${s0.bootstrap.password:}") String password) {
    this.jdbc=jdbc; this.passwordEncoder=passwordEncoder;
    this.enabled=enabled;this.username=username;this.password=password;
  }
  @Override @Transactional public void run(ApplicationArguments args) {
    if (!enabled) return;
    if (username.isBlank()||username.length()>80||password.length()<12)
      throw new IllegalStateException("Bootstrap requires a username and password of at least 12 characters");
    Integer count=jdbc.queryForObject("SELECT count(*) FROM app_user",Integer.class);
    if (count!=null && count==0)
      jdbc.update("INSERT INTO app_user(id,username,password_hash) VALUES (?,?,?)",
        UUID.randomUUID(),username.trim(),passwordEncoder.encode(password));
  }
}
