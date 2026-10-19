package io.ontoforge.dao.connection;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * M8.1 application-metadata persistence. A caller-owned Spring transaction
 * locks the head and inserts an immutable revision, operation receipt and CAS.
 * No physical source facts are persisted here.
 */
@Repository
public class LinkBindingRepository {
    private final JdbcTemplate jdbc;

    public LinkBindingRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Head(UUID id, String packageId, UUID snapshotId, String linkTypeId, long revision) {}
    public record Operation(UUID bindingId, long revision, String requestDigest) {}

    private Head head(Map<String,Object> r) {
        return new Head((UUID) r.get("link_binding_id"), (String) r.get("package_id"),
                (UUID) r.get("snapshot_id"), (String) r.get("link_type_id"),
                ((Number) r.get("current_revision")).longValue());
    }

    public Head get(UUID id) {
        var found = jdbc.queryForList(
                "SELECT link_binding_id,package_id,snapshot_id,link_type_id,current_revision FROM runtime_link_binding WHERE link_binding_id=?",
                id);
        return found.isEmpty() ? null : head(found.getFirst());
    }

    public Head find(String pkg, UUID snapshot, String linkType) {
        var found = jdbc.queryForList(
                "SELECT link_binding_id,package_id,snapshot_id,link_type_id,current_revision FROM runtime_link_binding WHERE package_id=? AND snapshot_id=? AND link_type_id=?",
                pkg, snapshot, linkType);
        return found.isEmpty() ? null : head(found.getFirst());
    }

    public Head lock(UUID id) {
        var found = jdbc.queryForList(
                "SELECT link_binding_id,package_id,snapshot_id,link_type_id,current_revision FROM runtime_link_binding WHERE link_binding_id=? FOR UPDATE",
                id);
        return found.isEmpty() ? null : head(found.getFirst());
    }

    public void create(UUID id, String pkg, UUID snapshot, String type, Instant now) {
        jdbc.update("""
                INSERT INTO runtime_link_binding
                 (link_binding_id,package_id,snapshot_id,link_type_id,current_revision,created_at,updated_at)
                VALUES (?,?,?,?,0,?,?)
                """, id,pkg,snapshot,type,Timestamp.from(now),Timestamp.from(now));
    }

    public void saveRevision(UUID id, long revision, UUID snapshot, String pkg, String linkType,
            String payloadSha256, UUID sourceId, long sourceRev, String sourceDigest,
            UUID targetId, long targetRev, String targetDigest,
            String fkOwner, String fkColumn, String otherKey, String sourceFp, String targetFp,
            String cardinality, String quality, int rows, int orphans, int nulls,
            String digest, Instant checked) {
        jdbc.update("""
                INSERT INTO runtime_link_binding_revision (
                  link_binding_id,revision,snapshot_id,package_id,link_type_id,
                  model_payload_sha256,source_binding_id,source_binding_revision,source_binding_digest,
                  target_binding_id,target_binding_revision,target_binding_digest,
                  fk_owner,fk_source_column,referenced_identity_column,
                  source_schema_fingerprint,target_schema_fingerprint,
                  cardinality,quality,checked_rows,orphan_count,null_fk_count,digest,checked_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,id,revision,snapshot,pkg,linkType,payloadSha256,
                sourceId,sourceRev,sourceDigest,targetId,targetRev,targetDigest,
                fkOwner,fkColumn,otherKey,sourceFp,targetFp,cardinality,quality,
                rows,orphans,nulls,digest,Timestamp.from(checked));
    }

    public int advance(UUID id, long expected, long next, Instant now) {
        return jdbc.update("""
                UPDATE runtime_link_binding SET current_revision=?,updated_at=?
                WHERE link_binding_id=? AND current_revision=?
                """,next,Timestamp.from(now),id,expected);
    }

    public Map<String,Object> revision(UUID id, long rev) {
        var rows=jdbc.queryForList("SELECT * FROM runtime_link_binding_revision WHERE link_binding_id=? AND revision=?",id,rev);
        return rows.isEmpty()?null:rows.getFirst();
    }

    public List<Map<String,Object>> revisions(UUID id) {
        return jdbc.queryForList("SELECT * FROM runtime_link_binding_revision WHERE link_binding_id=? ORDER BY revision DESC",id);
    }

    public List<Head> list(String pkg) {
        return jdbc.queryForList("""
                SELECT link_binding_id,package_id,snapshot_id,link_type_id,current_revision
                FROM runtime_link_binding WHERE package_id=? ORDER BY link_type_id,snapshot_id
                """,pkg).stream().map(this::head).toList();
    }

    public Operation operation(String pkg, UUID key) {
        var found=jdbc.queryForList("""
                SELECT link_binding_id,revision,request_digest FROM runtime_link_binding_operation
                WHERE package_id=? AND idempotency_key=?
                """,pkg,key);
        if(found.isEmpty())return null;
        var r=found.getFirst();
        return new Operation((UUID) r.get("link_binding_id"),((Number) r.get("revision")).longValue(),
                (String)r.get("request_digest"));
    }

    public void operation(String pkg, UUID key, UUID id, long revision, String digest) {
        jdbc.update("""
                INSERT INTO runtime_link_binding_operation
                  (package_id,idempotency_key,link_binding_id,revision,request_digest)
                VALUES(?,?,?,?,?)
                """,pkg,key,id,revision,digest);
    }
}
