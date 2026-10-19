package io.ontoforge.configuration;

import java.net.URI;

/** Existing local configuration guards, shared by assembly and tests. */
public final class LocalMvpSettings {
    private LocalMvpSettings() {}
    public static void requireIsolatedEnvironment(String bindAddress, String jdbcUrl) {
        if(!"127.0.0.1".equals(bindAddress) && !"localhost".equalsIgnoreCase(bindAddress))
            throw new IllegalStateException("mvp-local requires server.address=127.0.0.1");
        URI address;
        try {
            if(jdbcUrl==null || !jdbcUrl.startsWith("jdbc:postgresql://"))
                throw new IllegalArgumentException("invalid JDBC URL");
            address = URI.create(jdbcUrl.substring("jdbc:".length()));
        } catch(IllegalArgumentException e) {
            throw new IllegalStateException("mvp-local needs loopback PostgreSQL test JDBC URL",e);
        }
        String host=address.getHost();
        String path=address.getPath();
        String db=path!=null && path.startsWith("/") ? path.substring(1) : "";
        if(host==null || !("127.0.0.1".equals(host)||"localhost".equalsIgnoreCase(host))
           || !db.matches("(?:ontoforge|ontoforge_test_[a-z0-9_]+)")
           || address.getUserInfo()!=null || address.getFragment()!=null) {
            throw new IllegalStateException("mvp-local REFUSED: only loopback ontoforge / ontoforge_test_* DB");
        }
    }
}
