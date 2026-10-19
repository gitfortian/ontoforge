package io.ontoforge.platform.identity;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import io.ontoforge.dao.platform.AuthorizationRepository;
import io.ontoforge.dao.platform.AuthorizationRepository.IdentityRow;
import io.ontoforge.dao.platform.AuthorizationRepository.WorkspaceRow;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Profile;

/** Database-backed package authorization. Client-supplied roles are never consulted. */
@Service
@Profile("!mvp-local")
public class B0AuthorizationService {
    private final AuthorizationRepository repository;

    public B0AuthorizationService(AuthorizationRepository repository) {
        this.repository = repository;
    }

    public enum Operation {
        READ_DRAFT,
        WRITE_DRAFT,
        VALIDATE_DRAFT,
        READ_SNAPSHOT,
        CREATE_SNAPSHOT
    }

    public enum PackageRole {
        VIEWER, EDITOR, REVIEWER, PUBLISHER, ACTIVATOR, OWNER
    }

    public record Identity(
            UUID principalId,
            String issuer,
            String subject,
            String displayName,
            List<WorkspaceAccess> workspaces) {}

    public record WorkspaceAccess(UUID workspaceId, String workspaceKey,
                                  String displayName, List<String> roles,
                                  List<String> packageIds) {}

    public Identity identity(Authentication authentication) {
        JwtAuthenticationToken token = requireJwt(authentication);
        String issuer = token.getToken().getIssuer() == null
                ? ""
                : token.getToken().getIssuer().toString();
        String subject = token.getToken().getSubject();
        if (issuer.isBlank() || subject == null || subject.isBlank()) {
            throw new AccessDeniedException("当前身份缺少受信 issuer/subject");
        }
        List<IdentityRow> principals = repository.principals(issuer, subject);
        if (principals.isEmpty()) {
            throw new AccessDeniedException("当前身份尚未加入砺元工作空间");
        }
        IdentityRow principal = principals.getFirst();

        List<WorkspaceRow> workspaces = repository.workspaces(principal.principalId());

        return new Identity(
                principal.principalId(), issuer, subject, principal.displayName(),
                groupWorkspaceRows(workspaces));
    }

    public record DraftListingScope(String issuer, String subject, boolean localOnly) {}

    public DraftListingScope draftListingScope(Authentication authentication) {
        Identity current = identity(authentication);
        return new DraftListingScope(current.issuer(), current.subject(), false);
    }

    public void requirePackage(Authentication authentication, String packageId, Operation operation) {
        if (packageId == null || packageId.isBlank()) {
            throw new AccessDeniedException("当前请求没有可授权的模型包");
        }
        JwtAuthenticationToken token = requireJwt(authentication);
        String issuer = token.getToken().getIssuer() == null
                ? ""
                : token.getToken().getIssuer().toString();
        String subject = token.getToken().getSubject();
        if (issuer.isBlank() || subject == null || subject.isBlank()) {
            throw new AccessDeniedException("当前身份缺少受信 issuer/subject");
        }
        if (!repository.allowed(issuer, subject, packageId, rolesFor(operation))) {
            throw new AccessDeniedException("当前身份没有该模型包的操作权限");
        }
    }

    public void requireDraft(Authentication authentication, UUID draftId, Operation operation) {
        String packageId = repository.draftPackage(draftId);
        if (packageId == null) {
            // Preserve the existing resource-not-found contract. No resource metadata is returned.
            return;
        }
        requirePackage(authentication, packageId, operation);
    }

    public void requireSnapshot(Authentication authentication, UUID snapshotId, Operation operation) {
        String packageId = repository.snapshotPackage(snapshotId);
        if (packageId == null) {
            // Preserve the existing resource-not-found contract. No snapshot metadata is returned.
            return;
        }
        requirePackage(authentication, packageId, operation);
    }

    public Set<String> readablePackageIds(Authentication authentication) {
        JwtAuthenticationToken token = requireJwt(authentication);
        String issuer = token.getToken().getIssuer() == null
                ? ""
                : token.getToken().getIssuer().toString();
        String subject = token.getToken().getSubject();
        if (issuer.isBlank() || subject == null || subject.isBlank()) {
            throw new AccessDeniedException("当前身份缺少受信 issuer/subject");
        }
        return new LinkedHashSet<>(repository.readablePackageIds(issuer, subject));
    }

    private static JwtAuthenticationToken requireJwt(Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken token)) {
            throw new AccessDeniedException("当前请求没有受信 JWT 身份");
        }
        return token;
    }

    private static String[] rolesFor(Operation operation) {
        return switch (operation) {
            case READ_DRAFT, READ_SNAPSHOT -> new String[]{
                    "EDITOR", "REVIEWER", "PUBLISHER", "OWNER"
            };
            case WRITE_DRAFT, VALIDATE_DRAFT, CREATE_SNAPSHOT -> new String[]{
                    "EDITOR", "OWNER"
            };
        };
    }

    private static Object[] append(Object[] base, Object tail) {
        Object[] result = java.util.Arrays.copyOf(base, base.length + 1);
        result[base.length] = tail;
        return result;
    }

    private static List<WorkspaceAccess> groupWorkspaceRows(List<WorkspaceRow> rows) {
        java.util.Map<UUID, MutableWorkspace> grouped = new java.util.LinkedHashMap<>();
        for (WorkspaceRow row : rows) {
            MutableWorkspace value = grouped.computeIfAbsent(row.workspaceId(),
                    id -> new MutableWorkspace(row.workspaceId(), row.workspaceKey(), row.displayName()));
            value.roles.add(row.role());
            if (row.packageId() != null) {
                value.packageIds.add(row.packageId());
            }
        }
        return grouped.values().stream()
                .map(MutableWorkspace::toRecord)
                .toList();
    }


    private static final class MutableWorkspace {
        private final UUID workspaceId;
        private final String workspaceKey;
        private final String displayName;
        private final Set<String> roles = new LinkedHashSet<>();
        private final Set<String> packageIds = new LinkedHashSet<>();

        private MutableWorkspace(UUID workspaceId, String workspaceKey, String displayName) {
            this.workspaceId = workspaceId;
            this.workspaceKey = workspaceKey;
            this.displayName = displayName;
        }

        private WorkspaceAccess toRecord() {
            return new WorkspaceAccess(workspaceId, workspaceKey, displayName,
                    List.copyOf(roles), List.copyOf(packageIds));
        }
    }
}
