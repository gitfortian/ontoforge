package io.ontoforge.boot.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.ontoforge.boot.model.NativeDraftService;
import io.ontoforge.boot.model.DraftValidation;
import io.ontoforge.boot.model.NativeDraftService.DraftFailure;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Small, explicitly isolated vertical product slice: Native definitions -> durable
 * table bindings -> parameterized PostgreSQL object/relationship traversal.
 * It is NOT a general ObjectSet planner, connector, release or factory deployment.
 */
@RestController
@RequestMapping("/api/mvp/quality")
public class QualityMvpController {
    private static final String PACKAGE = "manufacturing.quality";
    private static final String SOURCE_MAP = """
      {"objectTypes":[
        {"objectTypeId":"production-batch","table":"mvp_source_batches","key":"batch_id"},
        {"objectTypeId":"work-order","table":"mvp_source_work_orders","key":"work_order_id"},
        {"objectTypeId":"customer-order","table":"mvp_source_orders","key":"order_id"}],
       "links":[
        {"linkTypeId":"batch-workorder","sourceColumn":"work_order_id","targetColumn":"work_order_id"},
        {"linkTypeId":"workorder-order","sourceColumn":"order_id","targetColumn":"order_id"}]}
      """;
    private final JdbcTemplate jdbc;
    private final NativeDraftService drafts;
    private final ObjectMapper json;

    public QualityMvpController(JdbcTemplate jdbc, NativeDraftService drafts, ObjectMapper json) {
        this.jdbc = jdbc; this.drafts = drafts; this.json = json;
    }

    public record Status(String packageId, String draftId, Long revision,
                         String validation, boolean bound, String sampleBatchId) {}
    public record Binding(String packageId, String draftId, long boundRevision,
                          JsonNode sourceMap, int sourceBatchCount) {}
    public record Fact(String objectTypeId, String id, String label, String sourceTable) {}
    public record Edge(String linkTypeId, String from, String to, String sourceColumn) {}
    public record Trace(String batchId, String qualityStatus, List<Fact> objects,
                        List<Edge> links, String provenance) {}

    private UUID existingDraft() {
        return jdbc.query("SELECT draft_id FROM native_package_draft WHERE package_id = ?",
             rs -> rs.next() ? rs.getObject(1,UUID.class) : null, PACKAGE);
    }

    @GetMapping("/status")
    public Status status() {
        UUID id = existingDraft();
        if (id == null) return new Status(PACKAGE,null,null,"NOT_CREATED",false,"B2026-001");
        var draft = drafts.get(id);
        boolean bound = jdbc.query("""
            SELECT 1 FROM mvp_demo_binding
             WHERE package_id=? AND draft_id=? AND bound_revision=?
            """, rs -> rs.next(), PACKAGE, id, draft.revision());
        return new Status(PACKAGE,id.toString(),draft.revision(),
             draft.validation().path("status").asText("NOT_RUN"),bound,"B2026-001");
    }

    @PostMapping("/model")
    public Status prepareModel() {
        UUID id = existingDraft();
        if (id == null) {
            try { id = UUID.fromString(drafts.create(PACKAGE).draftId()); }
            catch(DraftFailure failure) {
                if (!"PACKAGE_DRAFT_EXISTS".equals(failure.code)) throw failure;
                id = existingDraft(); // A concurrent click can only reuse the same package.
                if (id == null) throw failure;
            }
            var draft = drafts.get(id);
            // Never overwrite an existing edited package. A retry is read-only.
            if (draft.revision() == 1 && draft.editingContent().path("objectTypes").isEmpty()) {
                var saved = drafts.save(id,1,exampleModel());
                drafts.validate(id,saved.revision());
            }
        }
        return status();
    }

    @PostMapping("/bind")
    public Binding bind() {
        UUID id = existingDraft();
        if (id == null) throw fail(409,"MODEL_REQUIRED","请先建立示例本体");
        var draft = drafts.get(id);
        // Current model MUST actually define all three source-backed types and both
        // links with their expected topology. No dynamic table/column SQL is accepted.
        if (!"VALID".equals(draft.validation().path("status").asText()) ||
             draft.validation().path("validatedRevision").asLong(-1) != draft.revision() ||
             !compatible(draft.editingContent()))
            throw fail(409,"MODEL_NOT_READY","当前模型已变更或未通过校验，不能绑定示例数据");
        jdbc.update("""
            INSERT INTO mvp_demo_binding(package_id,draft_id,bound_revision,source_map)
            VALUES (?,?,?,?::jsonb)
            ON CONFLICT (package_id) DO UPDATE SET
             draft_id=EXCLUDED.draft_id, bound_revision=EXCLUDED.bound_revision,
             source_map=EXCLUDED.source_map, updated_at=clock_timestamp()
            """,PACKAGE,id,draft.revision(),SOURCE_MAP);
        int count = jdbc.queryForObject("SELECT count(*) FROM mvp_source_batches",Integer.class);
        try { return new Binding(PACKAGE,id.toString(),draft.revision(),json.readTree(SOURCE_MAP),count); }
        catch(Exception exception) { throw new IllegalStateException("Invalid built-in source map",exception); }
    }

    @GetMapping("/trace")
    public Trace trace(@RequestParam String batchId) {
        if (batchId == null || !batchId.matches("[A-Za-z0-9-]{1,64}"))
            throw fail(400,"INVALID_BATCH_ID","批次号仅允许字母、数字和连字符，最多64位");
        UUID id = existingDraft();
        if (id == null) throw fail(409,"BINDING_REQUIRED","请先创建本体并绑定数据");
        var draft = drafts.get(id);
        boolean active = jdbc.query("""
           SELECT 1 FROM mvp_demo_binding
            WHERE package_id=? AND draft_id=? AND bound_revision=?
           """,rs -> rs.next(),PACKAGE,id,draft.revision());
        if (!active || !"VALID".equals(draft.validation().path("status").asText())
                || draft.validation().path("validatedRevision").asLong(-1)!=draft.revision()
                || !compatible(draft.editingContent()))
            throw fail(409,"BINDING_STALE","当前绑定已失效，请校验模型后重新绑定");
        // Only fixed, allowlisted demo sources. Input value is a JDBC parameter:
        // no client-provided table, column, join, SQL or arbitrary executable query.
        List<Trace> rows = jdbc.query("""
            SELECT b.batch_id,b.quality_status,b.work_order_id,w.order_id,o.customer_name
              FROM mvp_source_batches b
              JOIN mvp_source_work_orders w ON b.work_order_id=w.work_order_id
              JOIN mvp_source_orders o ON w.order_id=o.order_id
             WHERE b.batch_id=?
            """,(rs,i)-> {
                String batch=rs.getString("batch_id");
                String work=rs.getString("work_order_id");
                String order=rs.getString("order_id");
                return new Trace(batch,rs.getString("quality_status"),List.of(
                    new Fact("production-batch",batch,"生产批次","mvp_source_batches"),
                    new Fact("work-order",work,"生产工单","mvp_source_work_orders"),
                    new Fact("customer-order",order,rs.getString("customer_name"),"mvp_source_orders")
                ),List.of(
                    new Edge("batch-workorder",batch,work,"mvp_source_batches.work_order_id"),
                    new Edge("workorder-order",work,order,"mvp_source_work_orders.order_id")
                ),"ISOLATED_SYNTHETIC_POSTGRES");
            },batchId);
        if (rows.isEmpty()) throw fail(404,"BATCH_NOT_FOUND","示例数据源中不存在该批次");
        return rows.getFirst();
    }

    private static DraftFailure fail(int code,String key,String message) {
        return new DraftFailure(code,key,message);
    }

    private static boolean compatible(JsonNode doc) {
        for (String id: List.of("production-batch","work-order","customer-order")) {
            boolean exists=false;
            for (JsonNode type:doc.path("objectTypes")) {
                if (id.equals(type.path("id").asText())) { exists=true; break; }
            }
            if (!exists) return false;
        }
        return hasLink(doc,"batch-workorder","production-batch","work-order")
            && hasLink(doc,"workorder-order","work-order","customer-order");
    }

    private static boolean hasLink(JsonNode doc,String id,String source,String target) {
        for (JsonNode link:doc.path("linkTypes"))
            if (id.equals(link.path("id").asText())
              && source.equals(link.path("sourceTypeRef").asText())
              && target.equals(link.path("targetTypeRef").asText()))
                return true;
        return false;
    }

    private ObjectNode exampleModel() {
        ObjectNode doc=json.createObjectNode();
        doc.put("editorSchemaVersion","0.1.0");
        ArrayNode types=doc.putArray("objectTypes");
        addType(types,"production-batch","ProductionBatch","生产批次","batch-id",
          new String[][]{{"batch-id","batchId","批次编号"},{"quality-status","qualityStatus","质量状态"},{"work-order-id","workOrderId","工单编号"}});
        addType(types,"work-order","WorkOrder","生产工单","work-order-id",
          new String[][]{{"work-order-id","workOrderId","工单编号"},{"customer-order-id","customerOrderId","客户订单号"}});
        addType(types,"customer-order","CustomerOrder","客户订单","order-id",
          new String[][]{{"order-id","orderId","客户订单号"},{"customer-name","customerName","客户名称"}});
        ArrayNode links=doc.putArray("linkTypes");
        addLink(links,"batch-workorder","BatchWorkOrder","批次归属工单","production-batch","work-order");
        addLink(links,"workorder-order","WorkOrderOrder","工单关联订单","work-order","customer-order");
        return doc;
    }
    private static void addType(ArrayNode list,String id,String api,String label,String pk,String[][] props) {
        ObjectNode type=list.addObject();
        type.put("id",id);type.put("apiName",api);type.put("label",label);type.put("primaryKeyRef",pk);
        ArrayNode properties=type.putArray("properties");
        for (String[] p:props) {
            ObjectNode property=properties.addObject();
            property.put("id",p[0]);property.put("apiName",p[1]);property.put("label",p[2]);
            property.put("valueType","STRING");property.put("required",true);
            property.put("searchable",true);property.put("classification","INTERNAL");
        }
    }
    private static void addLink(ArrayNode list,String id,String api,String label,String from,String to) {
        ObjectNode link=list.addObject();
        link.put("id",id);link.put("apiName",api);link.put("label",label);
        link.put("sourceTypeRef",from);link.put("targetTypeRef",to);
        link.put("cardinality","MANY_TO_ONE");link.put("evidenceRequired",true);
    }
}
