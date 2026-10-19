package io.ontoforge.platform.identity;

import java.io.Serializable;
import java.util.UUID;

/** Constructed by server credential verification only; contains no password or hash. */
public record LocalAccountPrincipal(UUID accountId, UUID principalId, long credentialVersion,
                                    String username, String displayName, boolean systemAdmin) implements Serializable {
    public static final String ISSUER = "urn:ontoforge:local";
}
