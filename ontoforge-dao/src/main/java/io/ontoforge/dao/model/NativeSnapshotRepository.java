package io.ontoforge.dao.model;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** SQL for immutable local snapshots. Row locking joins the caller's existing transaction. */
@Repository
public class NativeSnapshotRepository {
    private static final String FIELDS = "snapshot_id,package_id,source_draft_id,source_revision,idempotency_key,"
            + "candidate_schema_version,canonicalization_profile,canonical_payload,"
            + "snapshot_document::text AS snapshot_document,payload_sha256,created_at";
    private final JdbcTemplate jdbc;
    public NativeSnapshotRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public <T> List<T> lockDraft(UUID draftId, RowMapper<T> mapper) {
        return jdbc.query("""
                SELECT package_id,revision,editing_document::text AS editing,
                       validation_report::text AS report,validated_revision
                  FROM native_package_draft WHERE draft_id=? FOR UPDATE
                """, mapper, draftId);
    }

    public <T> List<T> byId(UUID id, RowMapper<T> mapper) {
        return jdbc.query("SELECT " + FIELDS + " FROM native_schema_snapshot WHERE snapshot_id=?", mapper, id);
    }

    public <T> List<T> byKey(UUID draft, UUID key, RowMapper<T> mapper) {
        return jdbc.query("SELECT " + FIELDS + " FROM native_schema_snapshot WHERE source_draft_id=? AND idempotency_key=?", mapper, draft, key);
    }

    public <T> List<T> byRevision(UUID draft, long revision, RowMapper<T> mapper) {
        return jdbc.query("SELECT " + FIELDS + " FROM native_schema_snapshot WHERE source_draft_id=? AND source_revision=?", mapper, draft, revision);
    }

    public <T> List<T> list(String packageId, int limit, Instant before, UUID beforeId, RowMapper<T> mapper) {
        String sql = "SELECT " + FIELDS + " FROM native_schema_snapshot WHERE package_id=?";
        String order = " ORDER BY created_at DESC,snapshot_id DESC LIMIT ?";
        return before == null ? jdbc.query(sql + order, mapper, packageId, limit)
                : jdbc.query(sql + " AND (created_at,snapshot_id)<(?,?)" + order,
                        mapper, packageId, Timestamp.from(before), beforeId, limit);
    }

    public void insert(UUID id, String packageId, UUID draft, long revision, UUID key,
                       String schema, String profile, byte[] canonical, String document, String digest) {
        jdbc.update("""
            INSERT INTO native_schema_snapshot (
              snapshot_id,package_id,source_draft_id,source_revision,idempotency_key,
              candidate_schema_version,canonicalization_profile,canonical_payload,
              snapshot_document,payload_sha256
            ) VALUES (?,?,?,?,?,?,?,?,?::jsonb,?)
            """, id, packageId, draft, revision, key, schema, profile, canonical, document, digest);
    }
}
