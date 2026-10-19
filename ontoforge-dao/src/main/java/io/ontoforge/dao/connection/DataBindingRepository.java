package io.ontoforge.dao.connection;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** Versioned binding storage; row locks and CAS updates use the caller's transaction. */
@Repository
public class DataBindingRepository {
    private final JdbcTemplate jdbc;
    public DataBindingRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public void createHead(UUID bindingId,String packageId,String objectTypeId,UUID identityDomainId,Timestamp createdAt,Timestamp updatedAt) {
        jdbc.update("""
                    INSERT INTO runtime_binding(binding_id, package_id, object_type_id, identity_domain_id,
                                                 current_revision, created_at, updated_at)
                    VALUES (?, ?, ?, ?, 0, ?, ?)
                    """, bindingId,packageId,objectTypeId,identityDomainId,createdAt,updatedAt);
    }

    public void activateInitial(Timestamp updatedAt,UUID bindingId) {
        jdbc.update("UPDATE runtime_binding SET current_revision=1, updated_at=? WHERE binding_id=?", updatedAt,bindingId);
    }

    public <T> T lockHead(RowMapper<T> mapper,UUID bindingId) {
        return jdbc.queryForObject("""
                SELECT binding_id, package_id, object_type_id, identity_domain_id, current_revision
                  FROM runtime_binding WHERE binding_id=? FOR UPDATE
                """, mapper,bindingId);
    }

    public String identityKey(UUID bindingId,long revision) {
        return jdbc.queryForObject("""
                SELECT source_id || ':' || source_schema || '.' || source_table || ':' || source_key
                  FROM runtime_binding_revision WHERE binding_id=? AND revision=?
                """, String.class,bindingId,revision);
    }

    public int advanceHead(long revision,UUID identityDomainId,Timestamp updatedAt,UUID bindingId,long expectedRevision) {
        return jdbc.update("""
                UPDATE runtime_binding SET current_revision=?, identity_domain_id=?, updated_at=?
                 WHERE binding_id=? AND current_revision=?
                """, revision,identityDomainId,updatedAt,bindingId,expectedRevision);
    }

    public <T> List<T> listBindings(RowMapper<T> mapper,String packageId) {
        return jdbc.query("""
                SELECT b.binding_id, b.package_id, b.object_type_id, b.current_revision,
                       r.source_id, r.source_schema, r.source_table, r.validation_status,
                       r.checked_at, r.checked_rows, r.digest
                  FROM runtime_binding b
                  JOIN runtime_binding_revision r
                    ON r.binding_id=b.binding_id AND r.revision=b.current_revision
                 WHERE b.package_id=?
                 ORDER BY b.object_type_id, b.binding_id
                """, mapper,packageId);
    }

    public <T> List<T> listRevisions(RowMapper<T> mapper,UUID bindingId) {
        return jdbc.query("SELECT revision, digest, validation_status, checked_at, checked_rows, created_at "
                + "FROM runtime_binding_revision WHERE binding_id=? ORDER BY revision DESC", mapper,bindingId);
    }

    public void insertRevision(UUID bindingId,long revision,UUID snapshotId,String payloadSha256,String sourceId,String schema,String table,String sourceFingerprint,String sourceKey,UUID identityDomainId,String fieldMappings,String digest,String digestProfile,String validationStatus,Timestamp checkedAt,int checkedRows,String issues,Timestamp createdAt) {
        jdbc.update("""
                INSERT INTO runtime_binding_revision(
                    binding_id, revision, snapshot_id, payload_sha256, source_id, source_schema, source_table,
                    source_schema_fingerprint, source_key, identity_domain_id, field_mappings, digest,
                    digest_profile, validation_status, checked_at, checked_rows, validation_issues, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?::jsonb, ?)
                """, bindingId,revision,snapshotId,payloadSha256,sourceId,schema,table,sourceFingerprint,sourceKey,identityDomainId,fieldMappings,digest,digestProfile,validationStatus,checkedAt,checkedRows,issues,createdAt);
    }

    public <T> T revision(RowMapper<T> mapper,UUID bindingId,long revision) {
        return jdbc.queryForObject("""
                    SELECT r.binding_id, r.revision, r.snapshot_id, b.package_id, b.object_type_id,
                           r.payload_sha256, r.source_id, r.source_schema, r.source_table,
                           r.source_schema_fingerprint, r.source_key, r.identity_domain_id,
                           r.field_mappings::text, r.digest, r.digest_profile, r.validation_status,
                           r.checked_at, r.checked_rows, r.validation_issues::text, r.created_at
                      FROM runtime_binding_revision r JOIN runtime_binding b ON b.binding_id=r.binding_id
                     WHERE r.binding_id=? AND r.revision=?
                    """, mapper,bindingId,revision);
    }

    public <T> T head(RowMapper<T> mapper,UUID bindingId) {
        return jdbc.queryForObject("""
                    SELECT binding_id, package_id, object_type_id, identity_domain_id, current_revision
                      FROM runtime_binding WHERE binding_id=?
                    """, mapper,bindingId);
    }
}
