package io.ontoforge.boot.security;

import io.ontoforge.boot.security.yak.HttpSessionAccounts;
import io.ontoforge.platform.identity.LocalAccountService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Session contains identifiers only; reload enabled/version/admin state before every authorization. */
final class LocalSessionAuthenticationFilter extends OncePerRequestFilter {
    private final LocalAccountService accounts;
    private final HttpSessionAccounts sessions;
    LocalSessionAuthenticationFilter(LocalAccountService accounts,HttpSessionAccounts sessions) {
        this.accounts=accounts;this.sessions=sessions;
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
            throws ServletException,IOException {
        var login=sessions.read(request);
        if(login!=null) {
            var account=accounts.current(login.accountId(),login.credentialVersion());
            if(account==null) { sessions.logout(request);SecurityContextHolder.clearContext(); }
            else {
                var context=SecurityContextHolder.createEmptyContext();
                context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(account,null,List.of()));
                SecurityContextHolder.setContext(context);
            }
        }
        chain.doFilter(request,response);
    }
}
