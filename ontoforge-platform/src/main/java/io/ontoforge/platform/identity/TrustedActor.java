package io.ontoforge.platform.identity;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** The two trusted adapters share stored identity predicates; clients cannot choose this value. */
public record TrustedActor(String issuer, String subject, String accessMode) {
    public static TrustedActor require(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated())
            throw new AccessDeniedException("当前请求没有受信身份");
        if (authentication.getPrincipal() instanceof LocalAccountPrincipal account)
            return new TrustedActor(LocalAccountPrincipal.ISSUER,account.accountId().toString(),"SESSION_AUTHENTICATED");
        if (authentication instanceof JwtAuthenticationToken token) {
            String issuer=token.getToken().getIssuer()==null?"":token.getToken().getIssuer().toString();
            String subject=token.getToken().getSubject();
            if (!issuer.isBlank() && !issuer.equals(LocalAccountPrincipal.ISSUER) && subject!=null && !subject.isBlank())
                return new TrustedActor(issuer,subject,"JWT_AUTHENTICATED");
        }
        throw new AccessDeniedException("当前请求没有受信身份");
    }
    public String actorKey() { return accessMode + ":" + issuer.length() + ":" + issuer + ":" + subject; }
}
