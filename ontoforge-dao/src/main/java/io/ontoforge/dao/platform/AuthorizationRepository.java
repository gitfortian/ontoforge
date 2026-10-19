package io.ontoforge.dao.platform;

import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Trusted stored membership lookups; client headers and roles are not database predicates. */
@Repository
public class AuthorizationRepository {
    private final JdbcTemplate jdbc;
    public AuthorizationRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public record IdentityRow(UUID principalId, String displayName) {}
    public record WorkspaceRow(UUID workspaceId, String workspaceKey, String displayName,
                                String role, String packageId) {}

    public List<IdentityRow> principals(String issuer, String subject) {
        return jdbc.query("SELECT principal_id,display_name FROM b0_principal WHERE issuer=? AND subject=?",
                (rs, i) -> new IdentityRow(rs.getObject(1, UUID.class), rs.getString(2)), issuer, subject);
    }

    public List<WorkspaceRow> workspaces(UUID principal) {
        return jdbc.query("""
                SELECT w.workspace_id,w.workspace_key,w.display_name,wm.role,pa.package_id
                  FROM b0_workspace_membership wm JOIN b0_workspace w ON w.workspace_id=wm.workspace_id
                  LEFT JOIN b0_package_assignment pa ON pa.workspace_id=w.workspace_id
                 WHERE wm.principal_id=? ORDER BY w.workspace_key,pa.package_id,wm.role
                """, (rs,i) -> new WorkspaceRow(rs.getObject(1,UUID.class),rs.getString(2),
                        rs.getString(3),rs.getString(4),rs.getString(5)), principal);
    }

    public boolean allowed(String issuer, String subject, String packageId, String[] roles) {
        String placeholders=String.join(",", Collections.nCopies(roles.length,"?"));
        Object[] args=new Object[3+roles.length]; args[0]=issuer;args[1]=subject;args[2]=packageId;
        System.arraycopy(roles,0,args,3,roles.length);
        return Boolean.TRUE.equals(jdbc.query("""
                SELECT 1 FROM b0_principal p
                  JOIN b0_workspace_membership wm ON wm.principal_id=p.principal_id
                  JOIN b0_package_assignment pa ON pa.workspace_id=wm.workspace_id
                 WHERE p.issuer=? AND p.subject=? AND pa.package_id=? AND wm.role IN (PLACEHOLDERS)
                 LIMIT 1
                """.replace("PLACEHOLDERS",placeholders), (org.springframework.jdbc.core.ResultSetExtractor<Boolean>) rs -> rs.next(), args));
    }

    public String draftPackage(UUID id) {
        return jdbc.query("SELECT package_id FROM native_package_draft WHERE draft_id=?",
                rs -> rs.next() ? rs.getString(1) : null, id);
    }
    public String snapshotPackage(UUID id) {
        return jdbc.query("SELECT package_id FROM native_schema_snapshot WHERE snapshot_id=?",
                rs -> rs.next() ? rs.getString(1) : null, id);
    }
    public List<String> readablePackageIds(String issuer, String subject) {
        return jdbc.query("""
                SELECT DISTINCT pa.package_id FROM b0_principal p
                  JOIN b0_workspace_membership wm ON wm.principal_id=p.principal_id
                  JOIN b0_package_assignment pa ON pa.workspace_id=wm.workspace_id
                 WHERE p.issuer=? AND p.subject=? AND wm.role IN ('EDITOR','REVIEWER','PUBLISHER','OWNER')
                 ORDER BY pa.package_id
                """, (rs,i) -> rs.getString(1), issuer, subject);
    }
}
