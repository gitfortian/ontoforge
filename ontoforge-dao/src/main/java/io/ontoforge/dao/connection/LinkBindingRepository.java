package io.ontoforge.dao.connection;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * M8.1 local LinkBinding metadata operations.
 * Must be used inside a caller-owned Spring transaction: insertion of a revision,
 * idempotency operation receipt and CAS advancement of head are ONE atomic write.
 * The repository does not imply source-fact consistency with this metadata transaction.
 */
@Repository
public class LinkBindingRepository {
    private final JdbcTemplate jdbc;

    public LinkBindingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Head(UUID id, String packageId, UUID snapshotId,
                       String linkTypeId, long revision) {}
    public record Operation(UUID linkBindingId, long revision,
                            String requestDigest, String operationType) {}

    private static final RowMapper<Head> HEAD_MAPPER = (rs, rowNum) -> new Head(
            rs.getObject("link_binding_id", UUID.class),
            rs.getString("package_id"),
            rs.getObject("snapshot_id", UUID.class),
            rs.getString("link_type_id"),
            rs.getLong("current_revision"));

    private static final RowMapper<Operation> OP_MAPPER = (rs, rowNum) -> new Operation(
            rs.getObject("link_binding_id", UUID.class),
            rs.getLong("committed_revision"),
            rs.getString("request_digest"),
            rs.getString("operation_type"));

    public void createHead(UUID id, String packageId, UUID snapshotId, String linkTypeId) {
        jdbc.update("""
                INSERT INTO runtime_link_binding
                    (link_binding_id, package_id, snapshot_id, link_type_id, current_revision)
                VALUES (?, ?, ?, ?, 0)
                """, id, packageId, snapshotId, linkTypeId);
    }

    public Head lockHead(UUID id, String packageId) {
        List<Head> matches = jdbc.query("""
                SELECT link_binding_id, package_id, snapshot_id, link_type_id, current_revision
                  FROM runtime_link_binding
                 WHERE link_binding_id=? AND package_id=? FOR UPDATE
                """, HEAD_MAPPER, id, packageId);
        return matches.isEmpty() ? null : matches.getFirst();
    }

    public List<Head> listHeads(String packageId) {
        return jdbc.query("""
                SELECT link_binding_id, package_id, snapshot_id, link_type_id, current_revision
                  FROM runtime_link_binding WHERE package_id=?
                 ORDER BY snapshot_id, link_type_id, link_binding_id
                """, HEAD_MAPPER, packageId);
    }

    public <T> T revision(RowMapper<T> mapper, UUID id, String packageId, long revision) {
        return jdbc.queryForObject("""
                SELECT r.*, h.package_id, h.link_type_id
                  FROM runtime_link_binding_revision r
                  JOIN runtime_link_binding h ON h.link_binding_id=r.link_binding_id
                 WHERE r.link_binding_id=? AND h.package_id=? AND r.revision=?
                """, mapper, id, packageId, revision);
    }

    public <T> List<T> revisions(RowMapper<T> mapper, UUID id, String packageId) {
        return jdbc.query("""
                SELECT r.*, h.package_id, h.link_type_id
                  FROM runtime_link_binding_revision r
                  JOIN runtime_link_binding h ON h.link_binding_id=r.link_binding_id
                 WHERE r.link_binding_id=? AND h.package_id=?
                 ORDER BY r.revision DESC
                """, mapper, id, packageId);
    }

    /**
     * Returns only a receipt scoped to the already authorized package; caller must
     * check current read permission before exposing any returned information.
     */
    public Operation findOperation(String packageId, UUID key) {
        List<Operation> matches = jdbc.query("""
                SELECT link_binding_id, committed_revision, request_digest, operation_type
                  FROM runtime_link_binding_operation
                 WHERE package_id=? AND idempotency_key=?
                """, OP_MAPPER, packageId, key);
        return matches.isEmpty() ? null : matches.getFirst();
    }

    public void insertRevision(UUID id, long revision, UUID snapshotId,
            String modelDigest, String linkTypeHash,
            UUID sourceBindingId, long sourceRevision, String sourceDigest,
            UUID targetBindingId, long targetRevision, String targetDigest,
            String fkOwner, String fkColumn, String referencedColumn,
            String sourceFingerprint, String targetFingerprint,
            String validationScopeJson, String validationQualityJson,
            String validationStatus, Timestamp checkedAt, String contentDigest) {
        jdbc.update("""
                INSERT INTO runtime_link_binding_revision (
                    link_binding_id, revision, snapshot_id, model_payload_sha256, link_type_hash,
                    source_binding_id, source_binding_revision, source_binding_digest,
                    target_binding_id, target_binding_revision, target_binding_digest,
                    fk_owner, fk_source_column, referenced_identity_column,
                    source_schema_fingerprint, target_schema_fingerprint,
                    validation_scope, validation_quality, validation_status,
                    checked_at, content_digest)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                        ?::jsonb, ?::jsonb, ?, ?, ?)
                """, id, revision, snapshotId, modelDigest, linkTypeHash,
                sourceBindingId, sourceRevision, sourceDigest,
                targetBindingId, targetRevision, targetDigest,
                fkOwner, fkColumn, referencedColumn,
                sourceFingerprint, targetFingerprint,
                validationScopeJson, validationQualityJson,
                validationStatus, checkedAt, contentDigest);
    }

    public int advanceHead(UUID id, String packageId, long expectedRevision) {
        return jdbc.update("""
                UPDATE runtime_link_binding
                   SET current_revision=?, updated_at=clock_timestamp()
                 WHERE link_binding_id=? AND package_id=? AND current_revision=?
                """, expectedRevision + 1, id, packageId, expectedRevision);
    }

    public void recordOperation(String packageId, UUID id, UUID idempotencyKey,
                                String operationType, String requestDigest, long revision) {
        jdbc.update("""
                INSERT INTO runtime_link_binding_operation (
                    package_id, link_binding_id, idempotency_key,
                    operation_type, request_digest, committed_revision)
                VALUES (?, ?, ?, ?, ?, ?)
                """, packageId, id, idempotencyKey, operationType, requestDigest, revision);
    }
}
