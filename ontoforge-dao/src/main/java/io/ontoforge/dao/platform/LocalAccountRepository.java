package io.ontoforge.dao.platform;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Local credentials are never returned by an HTTP adapter. */
@Repository
public class LocalAccountRepository {
    private final JdbcTemplate jdbc;
    public LocalAccountRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Account(UUID accountId, UUID principalId, String username, String displayName,
                          String passwordHash, boolean enabled, boolean systemAdmin,
                          long credentialVersion, long version) {
        @Override public String toString() { return "LocalAccount[" + accountId + "]"; }
    }

    private List<Account> find(String predicate, Object value) {
        return jdbc.query("""
                SELECT a.account_id,a.principal_id,a.username,p.display_name,a.password_hash,
                       a.enabled,a.system_admin,a.credential_version,a.version
                  FROM platform_local_account a JOIN b0_principal p ON p.principal_id=a.principal_id
                 WHERE %s
                """.formatted(predicate),
                (rs,i) -> new Account(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getString(3),
                        rs.getString(4),rs.getString(5),rs.getBoolean(6),rs.getBoolean(7),rs.getLong(8),rs.getLong(9)), value);
    }
    public Account byUsername(String username) { return find("a.username=?",username).stream().findFirst().orElse(null); }
    public Account byId(UUID id) { return find("a.account_id=?",id).stream().findFirst().orElse(null); }
    public Account lockById(UUID id) { return find("a.account_id=? FOR UPDATE OF a",id).stream().findFirst().orElse(null); }
    public List<Account> page(String query,int limit,int offset) {
        return jdbc.query("""
                SELECT a.account_id,a.principal_id,a.username,p.display_name,a.password_hash,
                       a.enabled,a.system_admin,a.credential_version,a.version
                  FROM platform_local_account a JOIN b0_principal p ON p.principal_id=a.principal_id
                 WHERE a.username ILIKE ? OR p.display_name ILIKE ?
                 ORDER BY a.username,a.account_id LIMIT ? OFFSET ?
                """,(rs,i)->new Account(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getString(3),
                        rs.getString(4),rs.getString(5),rs.getBoolean(6),rs.getBoolean(7),rs.getLong(8),rs.getLong(9)),
                "%"+query+"%","%"+query+"%",limit,offset);
    }
    public long count(String query) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM platform_local_account a JOIN b0_principal p ON p.principal_id=a.principal_id
                 WHERE a.username ILIKE ? OR p.display_name ILIKE ?
                """,Long.class,"%"+query+"%","%"+query+"%");
    }
    public boolean update(Account old,String displayName,boolean enabled,boolean admin) {
        boolean revoke=old.enabled()!=enabled || old.systemAdmin()!=admin;
        int changed=jdbc.update("""
                UPDATE platform_local_account SET enabled=?,system_admin=?,version=version+1,
                       credential_version=credential_version+?,updated_at=clock_timestamp()
                 WHERE account_id=? AND version=?
                """,enabled,admin,revoke?1:0,old.accountId(),old.version());
        if(changed==1)jdbc.update("UPDATE b0_principal SET display_name=? WHERE principal_id=?",displayName,old.principalId());
        return changed==1;
    }
    public boolean password(Account old,String hash) {
        return jdbc.update("""
                UPDATE platform_local_account SET password_hash=?,credential_version=credential_version+1,
                       version=version+1,updated_at=clock_timestamp() WHERE account_id=? AND version=?
                """,hash,old.accountId(),old.version())==1;
    }
    public boolean wouldOrphanLocalWorkspace(UUID principal) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM b0_workspace_membership own
                 WHERE own.principal_id=? AND own.role='OWNER' AND NOT EXISTS (
                   SELECT 1 FROM b0_workspace_membership other JOIN platform_local_account a ON a.principal_id=other.principal_id
                    WHERE other.workspace_id=own.workspace_id AND other.role='OWNER' AND a.enabled AND other.principal_id<>?))
                """,Boolean.class,principal,principal));
    }
    public void lockAccounts() { jdbc.queryForObject("SELECT pg_advisory_xact_lock(171815,17)",Object.class); }
    public long count() { return jdbc.queryForObject("SELECT count(*) FROM platform_local_account",Long.class); }
    public void insert(UUID id, UUID principal, String username, String name, String hash, boolean admin) {
        jdbc.update("INSERT INTO b0_principal(principal_id,issuer,subject,display_name) VALUES (?,?,?,?)",
                principal,"urn:ontoforge:local",id.toString(),name);
        jdbc.update("""
                INSERT INTO platform_local_account(account_id,principal_id,username,password_hash,system_admin)
                VALUES (?,?,?,?,?)
                """,id,principal,username,hash,admin);
    }
}
