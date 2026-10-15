package io.ontology.system.platform;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api")
public class AuthController {
  private final AuthenticationManager manager;
  private final HttpSessionSecurityContextRepository contexts=new HttpSessionSecurityContextRepository();
  public AuthController(AuthenticationManager manager) { this.manager=manager; }
  public record Login(@NotBlank String username,@NotBlank String password) {}
  public record Session(String username) {}
  public record CsrfValue(String token,String headerName) {}
  @GetMapping("/csrf") public CsrfValue csrf(CsrfToken token) {
    return new CsrfValue(token.getToken(),token.getHeaderName());
  }
  @PostMapping("/auth/login") public ResponseEntity<Session> login(
      @RequestBody @Valid Login form,HttpServletRequest req,HttpServletResponse res) {
    try {
      Authentication auth=manager.authenticate(
        UsernamePasswordAuthenticationToken.unauthenticated(form.username(),form.password()));
      req.getSession(true);
      req.changeSessionId();
      var context=SecurityContextHolder.createEmptyContext();
      context.setAuthentication(auth);
      SecurityContextHolder.setContext(context);
      contexts.saveContext(context,req,res);
      return ResponseEntity.ok(new Session(auth.getName()));
    } catch(AuthenticationException ex) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }
  }
  @GetMapping("/auth/me") public Session me(Authentication auth) { return new Session(auth.getName()); }
}
