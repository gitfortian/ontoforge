package io.ontoforge.boot.security;

import io.ontoforge.boot.security.yak.HttpSessionAccounts;
import io.ontoforge.platform.identity.B0AuthorizationService;
import io.ontoforge.platform.identity.LocalAccountService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!mvp-local")
@ConditionalOnProperty(name="ontoforge.security.authentication-mode",havingValue="local-session",matchIfMissing=true)
public class LocalLoginController {
    @org.springframework.beans.factory.annotation.Value("${server.servlet.session.cookie.secure:false}")
    private boolean secureCookie;
    private final LocalAccountService accounts;
    private final HttpSessionAccounts sessions;
    private final B0AuthorizationService authorization;
    private final SecurityContextRepository contexts;
    public LocalLoginController(LocalAccountService accounts,HttpSessionAccounts sessions,B0AuthorizationService authorization,
                                SecurityContextRepository contexts) {
        this.accounts=accounts;this.sessions=sessions;this.authorization=authorization;this.contexts=contexts;
    }
    public record Credentials(String username,String password) {
        @Override public String toString() { return "Credentials[redacted]"; }
    }
    public record ChangePassword(String currentPassword,String newPassword) {
        @Override public String toString(){return "ChangePassword[redacted]";}
    }
    @PostMapping("/api/auth/password")
    public void changePassword(org.springframework.security.core.Authentication actor,@RequestBody ChangePassword body,
                               HttpServletRequest request,HttpServletResponse response) {
        accounts.changePassword(actor,body.currentPassword(),body.newPassword(),request.getRemoteAddr());
        logout(request,response);
    }
    @PostMapping("/api/auth/login")
    public B0AuthorizationService.Identity login(@RequestBody Credentials credentials,HttpServletRequest request,HttpServletResponse response) {
        var principal=accounts.authenticate(credentials.username(),credentials.password(),request.getRemoteAddr());
        sessions.login(request,principal);
        var authentication=UsernamePasswordAuthenticationToken.authenticated(principal,null,List.of());
        var context=SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);SecurityContextHolder.setContext(context);
        contexts.saveContext(context,request,response);
        return authorization.identity(authentication);
    }
    @PostMapping("/api/auth/logout")
    public void logout(HttpServletRequest request,HttpServletResponse response) {
        sessions.logout(request);
        SecurityContextHolder.clearContext();
        Cookie cookie=new Cookie("ONTOFORGE_SESSION","");
        cookie.setPath("/api");cookie.setHttpOnly(true);cookie.setMaxAge(0);cookie.setAttribute("SameSite","Lax");
        cookie.setSecure(secureCookie||request.isSecure());
        response.addCookie(cookie);
        response.setStatus(204);
    }
    @ExceptionHandler(BadCredentialsException.class)
    void invalid(HttpServletResponse response) throws IOException {
        AuthResponses.error(response,401,"INVALID_CREDENTIALS","账号或密码不正确");
    }
    @ExceptionHandler(LockedException.class)
    void throttled(HttpServletResponse response) throws IOException {
        response.setHeader("Retry-After","900");
        AuthResponses.error(response,429,"LOGIN_RATE_LIMITED","登录尝试过多，请稍后重试");
    }
    @ExceptionHandler(io.ontoforge.platform.identity.AccountChangeException.class)
    void conflict(io.ontoforge.platform.identity.AccountChangeException failure,HttpServletResponse response) throws IOException {
        AuthResponses.error(response,409,failure.code(),failure.getMessage());
    }
    @ExceptionHandler(IllegalArgumentException.class)
    void invalidRequest(IllegalArgumentException failure,HttpServletResponse response) throws IOException {
        AuthResponses.error(response,400,"INVALID_ACCOUNT_REQUEST",failure.getMessage());
    }
}
