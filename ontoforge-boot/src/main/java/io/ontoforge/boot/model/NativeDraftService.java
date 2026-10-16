package io.ontoforge.boot.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.DateTimeException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** PostgreSQL is authoritative: single-statement optimistic writes, no fake in-memory persistence. */
@Service
public class NativeDraftService {
    private static final String FIELDS = "draft_id,package_id,revision,editing_document::text AS editing_document,validation_report::text AS validation_report,created_at,updated_at";
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    public NativeDraftService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc=jdbc;this.mapper=mapper;
    }

    public record DraftDetail(String draftId,String packageId,long revision,String status,
                              JsonNode editingContent,JsonNode validation,Instant createdAt,Instant updatedAt) {}
    public record DraftSummary(String draftId,String packageId,long revision,String validationStatus,
                               int objectTypeCount,Instant updatedAt) {}
    public record DraftPage(List<DraftSummary> items,String nextCursor) {}
    public record ValidationReport(String status,Long validatedRevision,List<DraftValidation.Issue> issues) {}

    public static final class DraftFailure extends RuntimeException {
        public final String code;
        public final int http;
        public final List<DraftValidation.Issue> issues;
        public DraftFailure(int http,String code,String message) {this(http,code,message,List.of());}
        public DraftFailure(int http,String code,String message,List<DraftValidation.Issue> issues) {
            super(message);this.http=http;this.code=code;this.issues=issues;
        }
    }

    private JsonNode parse(String json) {
        if(json==null) return null;
        try{return mapper.readTree(json);}catch(JsonProcessingException e){throw new IllegalStateException("Corrupted persisted JSON");}
    }
    private String json(JsonNode node) {
        try{return mapper.writeValueAsString(node);}catch(JsonProcessingException e){throw new IllegalStateException("Unable to serialize editor document");}
    }
    private DraftDetail map(ResultSet rs,int i) throws SQLException {
        JsonNode val=parse(rs.getString("validation_report"));
        if(val==null)val=notRun();
        return new DraftDetail(rs.getObject("draft_id",UUID.class).toString(),rs.getString("package_id"),
                rs.getLong("revision"),"DRAFT",parse(rs.getString("editing_document")),val,
                rs.getTimestamp("created_at").toInstant(),rs.getTimestamp("updated_at").toInstant());
    }
    private ObjectNode notRun() {
        ObjectNode n=mapper.createObjectNode();
        n.put("status","NOT_RUN");n.putNull("validatedRevision");n.putArray("issues");return n;
    }
    public DraftDetail create(String packageId) {
        if(!DraftValidation.packageIdValid(packageId))
            throw new DraftFailure(422,"MODEL_VALIDATION_FAILED","模型包 ID 必须是有效的域式标识",
                    List.of(new DraftValidation.Issue("/packageId","INVALID_PACKAGE_ID","示例：manufacturing.production")));
        UUID id=UUID.randomUUID();
        try {
            jdbc.update("INSERT INTO native_package_draft (draft_id,package_id,editor_schema_version,editing_document,revision) VALUES (?,?,?,?::jsonb,1)",
                    id,packageId,DraftValidation.EDITOR_VERSION,json(DraftValidation.initialDocument(mapper)));
        }catch(DataIntegrityViolationException e){
            throw new DraftFailure(409,"PACKAGE_DRAFT_EXISTS","该模型包已经有可编辑草稿，请从列表打开已有草稿");
        }
        return get(id);
    }
    public DraftDetail get(UUID id) {
        List<DraftDetail> items=jdbc.query("SELECT "+FIELDS+" FROM native_package_draft WHERE draft_id=?",this::map,id);
        if(items.isEmpty())throw new DraftFailure(404,"DRAFT_NOT_FOUND","该草稿不存在");
        return items.getFirst();
    }
    public DraftPage list(int pageSize,String cursor) {
        if(pageSize<1 || pageSize>50)throw new DraftFailure(400,"MALFORMED_REQUEST","pageSize 必须在 1 到 50 之间");
        List<DraftDetail> items;
        if(cursor==null || cursor.isBlank()) {
            items=jdbc.query("SELECT "+FIELDS+" FROM native_package_draft ORDER BY updated_at DESC,draft_id DESC LIMIT ?",
                    this::map,pageSize+1);
        } else {
            String[] parts;
            try {
                parts=new String(Base64.getUrlDecoder().decode(cursor),StandardCharsets.UTF_8).split("\\|",-1);
                if(parts.length!=2)throw new IllegalArgumentException();
                Instant t=Instant.parse(parts[0]);
                UUID id=UUID.fromString(parts[1]);
                items=jdbc.query("SELECT "+FIELDS+" FROM native_package_draft WHERE (updated_at,draft_id)<(?,?) ORDER BY updated_at DESC,draft_id DESC LIMIT ?",
                        this::map,Timestamp.from(t),id,pageSize+1);
            } catch(IllegalArgumentException | DateTimeException e){throw new DraftFailure(400,"MALFORMED_REQUEST","无效的分页游标");}
        }
        boolean hasMore=items.size()>pageSize;
        List<DraftDetail> limited=hasMore?items.subList(0,pageSize):items;
        List<DraftSummary> summaries=new ArrayList<>();
        for(DraftDetail d:limited) {
            summaries.add(new DraftSummary(d.draftId(),d.packageId(),d.revision(),
                    d.validation().path("status").asText("NOT_RUN"),
                    d.editingContent().path("objectTypes").size(),d.updatedAt()));
        }
        String next=null;
        if(hasMore && !limited.isEmpty()) {
            DraftDetail last=limited.getLast();
            next=Base64.getUrlEncoder().withoutPadding().encodeToString(
                    (last.updatedAt().toString()+"|"+last.draftId()).getBytes(StandardCharsets.UTF_8));
        }
        return new DraftPage(summaries,next);
    }

    private void requireCurrent(UUID id,long expected) {
        DraftDetail current=get(id);
        if(expected<1 || current.revision()!=expected)
            throw new DraftFailure(409,"DRAFT_REVISION_CONFLICT",
                    "草稿已由另一编辑操作修改，请保留本地内容并核对服务器版本");
    }

    public DraftDetail save(UUID id,long expectedRevision,JsonNode document) {
        if(expectedRevision<1) throw new DraftFailure(400,"MALFORMED_REQUEST","expectedRevision 必须是正整数");
        List<DraftValidation.Issue> issues=DraftValidation.checkEditor(document,mapper);
        if(!issues.isEmpty())throw new DraftFailure(422,"EDITOR_DOCUMENT_INVALID","草稿编辑结构不合法",issues);
        List<DraftDetail> written=jdbc.query("""
                UPDATE native_package_draft
                   SET editing_document=?::jsonb,revision=revision+1,
                       validation_report=NULL,validated_revision=NULL,updated_at=clock_timestamp()
                 WHERE draft_id=? AND revision=?
                RETURNING
                """+FIELDS,this::map,json(document),id,expectedRevision);
        if(written.isEmpty()){requireCurrent(id,expectedRevision);throw new DraftFailure(409,"DRAFT_REVISION_CONFLICT","写入并发冲突");}
        return written.getFirst();
    }

    public ValidationReport validate(UUID id,long expectedRevision) {
        if(expectedRevision<1)throw new DraftFailure(400,"MALFORMED_REQUEST","expectedRevision 必须是正整数");
        DraftDetail d=get(id);
        if(d.revision()!=expectedRevision)throw new DraftFailure(409,"DRAFT_REVISION_CONFLICT","只能校验最新 revision");
        DraftValidation.Result result=DraftValidation.validate(d.packageId(),d.editingContent(),mapper);
        ValidationReport report=new ValidationReport(result.status(),expectedRevision,result.issues());
        int written=jdbc.update("""
                UPDATE native_package_draft
                   SET validation_report=?::jsonb,validated_revision=?
                 WHERE draft_id=? AND revision=?
                """,json(mapper.valueToTree(report)),expectedRevision,id,expectedRevision);
        if(written!=1)throw new DraftFailure(409,"DRAFT_REVISION_CONFLICT","校验过程中草稿已经变化，请重新校验");
        return report;
    }
}
