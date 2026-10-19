package io.ontoforge.platform.identity;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import io.ontoforge.dao.platform.AuthorizationRepository;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

/**
 * Explicit opt-in S0 local-only adapter: NOT an identity provider or a production
 * authorization backend. LocalMvpSecurityConfig validates loopback binding + test DB.
 * No JWT/role/policy model is used for this single-developer demo.
 */
@Service
@Profile("mvp-local")
public final class LocalMvpAuthorizationService extends B0AuthorizationService {
    public static final String ISSUER = "local-mvp";
    public LocalMvpAuthorizationService(AuthorizationRepository repository) { super(repository); }

    @Override public Identity identity(Authentication ignored) {
        return new Identity(UUID.fromString("00000000-0000-4000-8000-000000000001"),
             ISSUER, "single-local-developer", "本机 MVP 开发模式",
             List.of(new WorkspaceAccess(
                 UUID.fromString("00000000-0000-4000-8000-000000000002"),
                 "local-mvp","隔离测试工作区",List.of("EDITOR"),List.of())));
    }
    @Override public DraftListingScope draftListingScope(Authentication ignored) {
        return new DraftListingScope(ISSUER, "single-local-developer", true);
    }
    @Override public void requirePackage(Authentication ignored,String packageId,Operation operation) {
        if(packageId==null||packageId.isBlank())throw new IllegalArgumentException("模型包不能为空");
    }
    @Override public void requireDraft(Authentication ignored,UUID draftId,Operation operation) {
        // This is deliberately not a production authorization check.
    }
    @Override public void requireSnapshot(Authentication ignored,UUID snapshotId,Operation operation) {
        // This is deliberately not a production authorization check.
    }
    @Override public Set<String> readablePackageIds(Authentication ignored) { return Set.of(); }
}
