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
