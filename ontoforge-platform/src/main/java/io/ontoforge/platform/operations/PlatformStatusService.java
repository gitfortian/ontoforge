package io.ontoforge.platform.operations;

import io.ontoforge.dao.platform.InstallationRepository;
import java.util.Arrays;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

/** Actual persisted installation and active authentication mode, without invented metrics. */
@Service
public class PlatformStatusService {
    private final InstallationRepository installations;
    private final Environment environment;
    public PlatformStatusService(InstallationRepository installations,Environment environment) {
        this.installations=installations;this.environment=environment;
    }
    public record PlatformStatus(String service,String version,String database,String installationId,
                                 String modelModule,String snapshotModule,String authModule) {}
    public PlatformStatus status() {
        return new PlatformStatus("ontoforge","S0-MVP-0.3","CONNECTED",installations.installationId(),
                "DRAFT_AVAILABLE","LOCAL_SNAPSHOT_AVAILABLE",
                Arrays.asList(environment.getActiveProfiles()).contains("mvp-local")
                    ? "LOCAL_MVP_ONLY" : environment.getProperty("ontoforge.security.authentication-mode","local-session").equals("local-session")
                        ? "SESSION_PACKAGE_AUTHORIZATION" : "JWT_PACKAGE_AUTHORIZATION");
    }
}
