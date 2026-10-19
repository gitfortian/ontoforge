package io.ontoforge.dao.platform;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class WorkspaceRepository {
    private final JdbcTemplate jdbc;
    public WorkspaceRepository(JdbcTemplate jdbc) {this.jdbc=jdbc;}
    public record Workspace(UUID id,String key,String name,long version) {}
    public record Person(UUID principalId,String displayName,String username,boolean eligible,List<String> roles) {}
    public Workspace get(UUID id,boolean lock) {
        return jdbc.query("SELECT workspace_id,workspace_key,display_name,version FROM b0_workspace WHERE workspace_id=?"+(lock?" FOR UPDATE":""),
                (rs,i)->new Workspace(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getLong(4)),id).stream().findFirst().orElse(null);
    }
    public List<Workspace> list(UUID principal,boolean admin) {
        return jdbc.query("""
                SELECT w.workspace_id,w.workspace_key,w.display_name,w.version FROM b0_workspace w
                 WHERE ? OR EXISTS (SELECT 1 FROM b0_workspace_membership m WHERE m.workspace_id=w.workspace_id AND m.principal_id=?)
                 ORDER BY w.workspace_key,w.workspace_id
                """,(rs,i)->new Workspace(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getLong(4)),admin,principal);
    }
    public void create(UUID id,String key,String name,UUID owner) {
        jdbc.update("INSERT INTO b0_workspace(workspace_id,workspace_key,display_name) VALUES (?,?,?)",id,key,name);
        jdbc.update("INSERT INTO b0_workspace_membership(workspace_id,principal_id,role) VALUES (?,?,'OWNER')",id,owner);
    }
    public List<String> roles(UUID workspace,UUID principal) {
        return jdbc.queryForList("SELECT role FROM b0_workspace_membership WHERE workspace_id=? AND principal_id=? ORDER BY role",String.class,workspace,principal);
    }
    public List<String> packages(UUID workspace) {
        return jdbc.queryForList("SELECT package_id FROM b0_package_assignment WHERE workspace_id=? ORDER BY package_id",String.class,workspace);
    }
    public List<Person> members(UUID workspace,boolean session,String issuer) {
        return jdbc.query("""
                SELECT p.principal_id,p.display_name,a.username,
                       CASE WHEN ? THEN COALESCE(a.enabled,false) ELSE p.issuer=? END,array_agg(m.role ORDER BY m.role)
                  FROM b0_workspace_membership m JOIN b0_principal p ON p.principal_id=m.principal_id
                  LEFT JOIN platform_local_account a ON a.principal_id=p.principal_id
                 WHERE m.workspace_id=? GROUP BY p.principal_id,p.display_name,p.issuer,a.username,a.enabled ORDER BY p.display_name,p.principal_id
                """,(rs,i)->new Person(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getBoolean(4),
                        List.of((String[])rs.getArray(5).getArray())),session,issuer,workspace);
    }
    public List<Person> candidates(String query,boolean session,String issuer) {
        return jdbc.query("""
                SELECT p.principal_id,p.display_name,a.username FROM b0_principal p
                  LEFT JOIN platform_local_account a ON a.principal_id=p.principal_id
                 WHERE ((? AND a.enabled) OR (NOT ? AND p.issuer=?))
                   AND (p.display_name ILIKE ? OR a.username ILIKE ?)
                 ORDER BY p.display_name,p.principal_id LIMIT 20
                """,(rs,i)->new Person(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),true,List.of()),
                session,session,issuer,"%"+query+"%","%"+query+"%");
    }
    public boolean eligible(UUID principal,boolean session,String issuer) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM b0_principal p LEFT JOIN platform_local_account a ON a.principal_id=p.principal_id
                 WHERE p.principal_id=? AND ((? AND a.enabled) OR (NOT ? AND p.issuer=?)))
                """,Boolean.class,principal,session,session,issuer));
    }
    public long usableOwners(UUID workspace,UUID excluding,boolean session,String issuer) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM b0_workspace_membership m JOIN b0_principal p ON p.principal_id=m.principal_id
                  LEFT JOIN platform_local_account a ON a.principal_id=p.principal_id
                 WHERE m.workspace_id=? AND m.role='OWNER' AND m.principal_id<>?
                   AND ((? AND a.enabled) OR (NOT ? AND p.issuer=?))
                """,Long.class,workspace,excluding,session,session,issuer);
    }
    public void replaceRoles(UUID workspace,UUID principal,List<String> roles) {
        jdbc.update("DELETE FROM b0_workspace_membership WHERE workspace_id=? AND principal_id=?",workspace,principal);
        for(String role:roles)jdbc.update("INSERT INTO b0_workspace_membership(workspace_id,principal_id,role) VALUES (?,?,?)",workspace,principal,role);
    }
    public boolean revise(UUID workspace,long expected,String displayName) {
        return jdbc.update("UPDATE b0_workspace SET display_name=?,version=version+1 WHERE workspace_id=? AND version=?",displayName,workspace,expected)==1;
    }
    public void lockPackage(String packageId) {PackageIdentityLock.lock(jdbc,packageId);}
    public UUID packageOwner(String packageId) {
        return jdbc.query("SELECT workspace_id FROM b0_package_assignment WHERE package_id=?",(rs,i)->rs.getObject(1,UUID.class),packageId).stream().findFirst().orElse(null);
    }
    public boolean existingModel(String packageId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM native_package_draft WHERE package_id=?)
                    OR EXISTS (SELECT 1 FROM native_schema_snapshot WHERE package_id=?)
                """,Boolean.class,packageId,packageId));
    }
    public void assign(UUID workspace,String packageId) {
        jdbc.update("INSERT INTO b0_package_assignment(workspace_id,package_id) VALUES (?,?)",workspace,packageId);
    }
}
