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

    /**
     * Serialize writes carrying the same operation UUID BEFORE looking for its receipt.
     * Both locks are transaction-scoped and use the metadata PostgreSQL connection.
     * Hash collisions only cause harmless extra waiting; database uniqueness remains
     * the final safety constraint.
     */
    public void lockOperationKey(String packageId, UUID idempotencyKey) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtext(?), hashtext(?))",
                rs -> {}, "M8_OP:" + packageId, idempotencyKey.toString());
    }

    /**
     * Serialize CREATEs targeting the same (package, immutable snapshot, LinkType)
     * even if callers supplied different operation UUIDs. Acquire operation lock
     * first, then this identity lock; UPDATE acquires operation lock before Head.
     */
    public void lockLinkIdentity(String packageId, UUID snapshotId, String linkTypeId) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtext(?), hashtext(?))",
                rs -> {}, "M8_LINK:" + packageId, snapshotId + ":" + linkTypeId);
    }

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

    /**
     * M8.2 immutable BridgeTable revision: only the mapped two endpoint identities
     * are Object Bindings. The bridge is a third bounded source-read evidence,
     * never a third Object Binding nor a placeholder FOREIGN_KEY.
     *
     * Caller MUST finish current authorization, three-source FULL, version locks,
     * and content digest before entering the SAME metadata transaction as
     * recordOperation and advanceHead. This method does not claim SourcePG
     * and metadata PG share an atomic transaction.
     */
    public record BridgeColumns(String sourceId, String schema, String table,
            String sourceFkColumn, String targetFkColumn, String schemaFingerprint) {}

    public record BridgeRevision(
            UUID id, long revision, UUID snapshotId, String modelDigest, String linkTypeHash,
            UUID sourceBindingId, long sourceRevision, String sourceDigest,
            UUID targetBindingId, long targetRevision, String targetDigest,
            String sourceFingerprint, String targetFingerprint,
            String validationScopeJson, String validationQualityJson, String validationStatus,
            Timestamp checkedAt, String contentDigest, BridgeColumns bridge) {}

    public void insertBridgeRevision(BridgeRevision r) {
        if (r == null || r.bridge() == null) {
            throw new IllegalArgumentException("Bridge evidence required");
        }
        // Explicitly set the three FK-only fields to NULL, never synthetic
        // values. PostgreSQL V11 enforces the full mutually exclusive mode.
        jdbc.update("""
                INSERT INTO runtime_link_binding_revision (
                    link_binding_id, revision, snapshot_id, model_payload_sha256, link_type_hash,
                    source_binding_id, source_binding_revision, source_binding_digest,
                    target_binding_id, target_binding_revision, target_binding_digest,
                    fk_owner, fk_source_column, referenced_identity_column,
                    source_schema_fingerprint, target_schema_fingerprint,
                    validation_scope, validation_quality, validation_status,
                    checked_at, content_digest,
                    mapping_mode, bridge_source_id, bridge_schema, bridge_table,
                    bridge_source_fk_column, bridge_target_fk_column, bridge_schema_fingerprint)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                        NULL, NULL, NULL, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?,
                        'BRIDGE_TABLE', ?, ?, ?, ?, ?, ?)
                """,
                r.id(), r.revision(), r.snapshotId(), r.modelDigest(), r.linkTypeHash(),
                r.sourceBindingId(), r.sourceRevision(), r.sourceDigest(),
                r.targetBindingId(), r.targetRevision(), r.targetDigest(),
                r.sourceFingerprint(), r.targetFingerprint(),
                r.validationScopeJson(), r.validationQualityJson(), r.validationStatus(),
                r.checkedAt(), r.contentDigest(),
                r.bridge().sourceId(), r.bridge().schema(), r.bridge().table(),
                r.bridge().sourceFkColumn(), r.bridge().targetFkColumn(),
                r.bridge().schemaFingerprint());
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
