package io.ontoforge.boot.security;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;

/** Database-backed package authorization. Client-supplied roles are never consulted. */
@Service
public class B0AuthorizationService {
    private final JdbcTemplate jdbc;

    public B0AuthorizationService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
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
        List<IdentityRow> principals = jdbc.query("""
                SELECT principal_id, display_name
                  FROM b0_principal
                 WHERE issuer = ? AND subject = ?
                """,
                (rs, rowNum) -> new IdentityRow(
                        rs.getObject("principal_id", UUID.class),
                        rs.getString("display_name")),
                issuer, subject);
        if (principals.isEmpty()) {
            throw new AccessDeniedException("当前身份尚未加入砺元工作空间");
        }
        IdentityRow principal = principals.getFirst();

        List<WorkspaceAccess> workspaces = jdbc.query("""
                SELECT w.workspace_id, w.workspace_key, w.display_name,
                       wm.role, pa.package_id
                  FROM b0_workspace_membership wm
                  JOIN b0_workspace w ON w.workspace_id = wm.workspace_id
                  LEFT JOIN b0_package_assignment pa ON pa.workspace_id = w.workspace_id
                 WHERE wm.principal_id = ?
                 ORDER BY w.workspace_key, pa.package_id, wm.role
                """,
                (rs, rowNum) -> new WorkspaceRow(
                        rs.getObject("workspace_id", UUID.class),
                        rs.getString("workspace_key"),
                        rs.getString("display_name"),
                        rs.getString("role"),
                        rs.getString("package_id")),
                principal.principalId());

        return new Identity(
                principal.principalId(), issuer, subject, principal.displayName(),
                groupWorkspaceRows(workspaces));
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
        String[] roles = rolesFor(operation);
        String placeholders = String.join(",", java.util.Collections.nCopies(roles.length, "?"));
        Object[] args = new Object[2 + roles.length];
        args[0] = issuer;
        args[1] = subject;
        System.arraycopy(roles, 0, args, 2, roles.length);
        Integer allowed = jdbc.query("""
                SELECT 1
                  FROM b0_principal p
                  JOIN b0_workspace_membership wm ON wm.principal_id = p.principal_id
                  JOIN b0_package_assignment pa ON pa.workspace_id = wm.workspace_id
                 WHERE p.issuer = ?
                   AND p.subject = ?
                   AND pa.package_id = ?
                   AND wm.role IN (PLACEHOLDERS)
                 LIMIT 1
                """.replace("PLACEHOLDERS", placeholders),
                rs -> rs.next() ? 1 : 0,
                append(args, packageId));
        if (allowed == null || allowed != 1) {
            throw new AccessDeniedException("当前身份没有该模型包的操作权限");
        }
    }

    public void requireDraft(Authentication authentication, UUID draftId, Operation operation) {
        String packageId = jdbc.query("""
                SELECT package_id
                  FROM native_package_draft
                 WHERE draft_id = ?
                """,
                rs -> rs.next() ? rs.getString(1) : null,
                draftId);
        if (packageId == null) {
            // Preserve resource-not-found behavior while never authorizing by client input.
            throw new AccessDeniedException("当前身份无法访问该模型草稿");
        }
        requirePackage(authentication, packageId, operation);
    }

    public void requireSnapshot(Authentication authentication, UUID snapshotId, Operation operation) {
        String packageId = jdbc.query("""
                SELECT package_id
                  FROM native_schema_snapshot
                 WHERE snapshot_id = ?
                """,
                rs -> rs.next() ? rs.getString(1) : null,
                snapshotId);
        if (packageId == null) {
            throw new AccessDeniedException("当前身份无法访问该模型快照");
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
        return new LinkedHashSet<>(jdbc.query("""
                SELECT DISTINCT pa.package_id
                  FROM b0_principal p
                  JOIN b0_workspace_membership wm ON wm.principal_id = p.principal_id
                  JOIN b0_package_assignment pa ON pa.workspace_id = wm.workspace_id
                 WHERE p.issuer = ? AND p.subject = ?
                   AND wm.role IN ('EDITOR','REVIEWER','PUBLISHER','OWNER')
                 ORDER BY pa.package_id
                """,
                (rs, rowNum) -> rs.getString(1),
                issuer, subject));
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

    private record IdentityRow(UUID principalId, String displayName) {}
    private record WorkspaceRow(UUID workspaceId, String workspaceKey, String displayName,
                                String role, String packageId) {}

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
