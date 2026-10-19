package io.ontoforge.boot.security;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RestController;

/** Public mode discovery contains no credential/account directory. */
@RestController
public class AuthContextController {
    private final Environment environment;
    public AuthContextController(Environment environment) { this.environment=environment; }
    private String mode() {
        return Arrays.asList(environment.getActiveProfiles()).contains("mvp-local")?"mvp-local":
                environment.getProperty("ontoforge.security.authentication-mode","local-session");
    }
    @Bean InitializingBean requireAuthenticationMode() {
        return ()->{
            String mode=mode();
            if(!java.util.Set.of("mvp-local","local-session","oidc-jwt").contains(mode))
                throw new IllegalStateException("认证模式仅支持 local-session、oidc-jwt 或显式 mvp-local Profile");
            String address=environment.getProperty("server.address","127.0.0.1");
            if(mode.equals("local-session") && !java.util.Set.of("127.0.0.1","localhost","::1").contains(address)
                    && !environment.getProperty("server.servlet.session.cookie.secure",Boolean.class,false))
                throw new IllegalStateException("非 loopback Session 部署必须配置 HTTPS 和 Secure Cookie");
        };
    }
    @GetMapping("/api/auth/context")
    public ResponseEntity<Map<String,Object>> context(Authentication authentication,
            @RequestAttribute(name="_csrf",required=false) CsrfToken csrf) {
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("mode",mode());
        result.put("authenticated",authentication!=null && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken));
        result.put("capabilities",Map.of("manageAccounts",mode().equals("local-session") && authentication!=null
                && authentication.getPrincipal() instanceof io.ontoforge.platform.identity.LocalAccountPrincipal account && account.systemAdmin()));
        if(mode().equals("local-session") && csrf!=null)
            result.put("csrf",Map.of("token",csrf.getToken(),"headerName",csrf.getHeaderName()));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
    }
}
