package io.ontoforge.boot.security.yak;

import io.ontoforge.platform.identity.LocalAccountPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.time.Duration;
import java.util.UUID;

/**
 * Adapted from Yak Ops HttpSessionAuthenticationManager, Apache-2.0,
 * commit 85338827bdbb745eaa6a017031640674a298a4f9.
 * Retains invalidate-before-login and idle timeout. Uses explicit requests, stores stable
 * account/version only; every request rechecks the database instead of a process session index.
 */
public final class HttpSessionAccounts {
    private static final String ACCOUNT="ontoforge:account-id";
    private static final String VERSION="ontoforge:credential-version";
    private final int idleSeconds;
    public record Login(UUID accountId,long credentialVersion) {}
    public HttpSessionAccounts(Duration idleTimeout) {
        long seconds=idleTimeout.getSeconds();
        if(seconds<1 || seconds>Integer.MAX_VALUE) throw new IllegalArgumentException("Invalid session idle timeout");
        idleSeconds=(int)seconds;
    }
    public void login(HttpServletRequest request,LocalAccountPrincipal principal) {
        logout(request);
        HttpSession session=request.getSession(true);
        session.setMaxInactiveInterval(idleSeconds);
        session.setAttribute(ACCOUNT,principal.accountId());
        session.setAttribute(VERSION,principal.credentialVersion());
    }
    public Login read(HttpServletRequest request) {
        HttpSession session=request.getSession(false);
        if(session==null) return null;
        try {
            return session.getAttribute(ACCOUNT) instanceof UUID id && session.getAttribute(VERSION) instanceof Long version
                    ?new Login(id,version):null;
        } catch(IllegalStateException expired) { return null; }
    }
    public void logout(HttpServletRequest request) {
        HttpSession session=request.getSession(false);
        if(session!=null) try { session.invalidate(); } catch(IllegalStateException expired) { /* already invalid */ }
    }
}
