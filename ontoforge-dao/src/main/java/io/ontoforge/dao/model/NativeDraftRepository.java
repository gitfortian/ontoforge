package io.ontoforge.dao.model;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** Draft SQL and atomic revision writes; callers map rows into their own domain contracts. */
@Repository
public class NativeDraftRepository {
    private static final String FIELDS = "draft_id,package_id,revision,editing_document::text AS editing_document,"
            + "validation_report::text AS validation_report,created_at,updated_at";
    private final JdbcTemplate jdbc;

    public NativeDraftRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void create(UUID id, String packageId, String editorVersion, String document) {
        jdbc.update("INSERT INTO native_package_draft (draft_id,package_id,editor_schema_version,editing_document,revision) VALUES (?,?,?,?::jsonb,1)",
                id, packageId, editorVersion, document);
    }

    public <T> List<T> find(UUID id, RowMapper<T> mapper) {
        return jdbc.query("SELECT " + FIELDS + " FROM native_package_draft WHERE draft_id=?", mapper, id);
    }

    public <T> List<T> list(int limit, Instant before, UUID beforeId,
                            String issuer, String subject, RowMapper<T> mapper) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT " + FIELDS + " FROM native_package_draft d WHERE 1=1 ");
        if (before != null) {
            sql.append(" AND (d.updated_at,d.draft_id)<(?,?)");
            args.add(Timestamp.from(before)); args.add(beforeId);
        }
        if (issuer != null) {
            sql.append("""
                 AND EXISTS (
                       SELECT 1 FROM b0_principal p
                         JOIN b0_workspace_membership wm ON wm.principal_id = p.principal_id
                         JOIN b0_package_assignment pa ON pa.workspace_id = wm.workspace_id
                        WHERE p.issuer = ? AND p.subject = ? AND pa.package_id = d.package_id
                          AND wm.role IN ('EDITOR','REVIEWER','PUBLISHER','OWNER')
                 )
                 """);
            args.add(issuer); args.add(subject);
        }
        sql.append(" ORDER BY d.updated_at DESC,d.draft_id DESC LIMIT ?"); args.add(limit);
        return jdbc.query(sql.toString(), mapper, args.toArray());
    }

    public <T> List<T> save(UUID id, long expectedRevision, String document, RowMapper<T> mapper) {
        return jdbc.query("""
                UPDATE native_package_draft
                   SET editing_document=?::jsonb,revision=revision+1,
                       validation_report=NULL,validated_revision=NULL,updated_at=clock_timestamp()
                 WHERE draft_id=? AND revision=?
                RETURNING
                """ + FIELDS, mapper, document, id, expectedRevision);
    }

    public int recordValidation(UUID id, long expectedRevision, String report) {
        return jdbc.update("""
                UPDATE native_package_draft SET validation_report=?::jsonb,validated_revision=?
                 WHERE draft_id=? AND revision=?
                """, report, expectedRevision, id, expectedRevision);
    }
}
