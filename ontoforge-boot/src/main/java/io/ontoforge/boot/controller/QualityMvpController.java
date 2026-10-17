package io.ontoforge.boot.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.ontoforge.boot.model.NativeDraftService;
import io.ontoforge.boot.security.B0AuthorizationService;
import org.springframework.security.core.Authentication;
import io.ontoforge.boot.model.DraftValidation;
import io.ontoforge.boot.model.NativeDraftService.DraftFailure;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.regex.Pattern;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HexFormat;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.web.bind.annotation.RequestBody;
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
    private static final int MAX_RESULT_PATHS = 10000;
    private static final int MAX_PAGE_SIZE = 50;
    private static final Pattern IDENT=Pattern.compile("[a-z][a-z0-9_]{0,62}");
    public record Mapping(String batchTable,String batchId,String batchStatus,String batchWorkOrder,
        String workTable,String workId,String workOrder,
        String orderTable,String orderId,String orderName){}
    public record TableInfo(String table,List<String> columns){}
    private static final Mapping DEFAULT=new Mapping("mvp_source_batches","batch_id","quality_status",
        "work_order_id","mvp_source_work_orders","work_order_id","order_id",
        "mvp_source_orders","order_id","customer_name");
    private static boolean permitted(String t){return t!=null&&IDENT.matcher(t).matches()
        &&(t.startsWith("mvp_source_")||t.startsWith("demo_"));}
    private static String qi(String t){return "\"" + t + "\"";}
    private static String qt(String t){return "public."+qi(t);}
    private final JdbcTemplate jdbc;
    private final NativeDraftService drafts;
    private final ObjectMapper json;
    private final B0AuthorizationService authorization;

    public QualityMvpController(JdbcTemplate jdbc, NativeDraftService drafts, ObjectMapper json,
                                B0AuthorizationService authorization) {
        this.jdbc = jdbc; this.drafts = drafts; this.json = json; this.authorization=authorization;
    }
    private void readAllowed(Authentication auth) {
        authorization.requirePackage(auth,PACKAGE,B0AuthorizationService.Operation.READ_DRAFT);
    }
    private void writeAllowed(Authentication auth) {
        authorization.requirePackage(auth,PACKAGE,B0AuthorizationService.Operation.WRITE_DRAFT);
    }

    public record Status(String packageId, String draftId, Long revision,
                         String validation, boolean bound, String sampleBatchId) {}
    public record Binding(String packageId, String draftId, long boundRevision,
                          JsonNode sourceMap, int sourceBatchCount) {}
    public record Fact(String objectTypeId, String id, String label, String sourceTable) {}
    public record Edge(String linkTypeId, String from, String to, String sourceColumn) {}
    public record Trace(String batchId, String qualityStatus, List<Fact> objects,
                        List<Edge> links, String provenance) {}


    /** One semantic path is keyed by (batch, work order, customer order).
     * Repeated physical source rows are de-duplicated as link evidence, not as separate objects.
     */
    public record ImpactPath(String workOrderId, String orderId, String customerName,
                             List<Fact> objects, List<Edge> links) {}
    public record TraceSet(String batchId, String qualityStatus, long boundRevision,
                           String sourceFingerprint, long workOrderCount, long orderCount,
                           long pathCount, long missingWorkRefs, long missingOrderRefs,
                           List<ImpactPath> paths, String nextCursor, String provenance) {}
    private record ImpactCursor(String batchId, long revision, String sourceFingerprint,
                                String lastWork, String lastOrder) {}
    private record ImpactCounts(long pathCount,long workCount,long orderCount) {}
    private record MissingCounts(long workRefs,long orderRefs) {}
    private record ImpactRow(String work,String order,String customer,long labelVariants) {}

    @GetMapping("/sources")
    public List<TableInfo> sources(Authentication auth){
        readAllowed(auth);
        return sourcesInternal();
    }
    private List<TableInfo> sourcesInternal(){
        Map<String,List<String>> tables=new LinkedHashMap<>();
        jdbc.query("""
            SELECT c.table_name,c.column_name,c.data_type FROM information_schema.columns c
             JOIN information_schema.tables t ON t.table_name=c.table_name
               AND t.table_schema=c.table_schema
             WHERE c.table_schema='public' AND t.table_type='BASE TABLE'
             ORDER BY c.table_name,c.ordinal_position
        """,(org.springframework.jdbc.core.RowCallbackHandler) rs->{
            String table=rs.getString(1),type=rs.getString(3);
            if(permitted(table)&&List.of("text","character varying","character",
                "uuid","integer","bigint").contains(type))
                tables.computeIfAbsent(table,k->new ArrayList<>()).add(rs.getString(2));
        });
        return tables.entrySet().stream().limit(100)
           .map(e->new TableInfo(e.getKey(),List.copyOf(e.getValue()))).toList();
    }
    private Mapping parseMapping(JsonNode input){
        if(input==null||input.isNull()||(input.has("objectTypes")&&input.has("links")))return DEFAULT;
        String[] names={"batchTable","batchId","batchStatus","batchWorkOrder",
            "workTable","workId","workOrder","orderTable","orderId","orderName"};
        if(!input.isObject()||input.size()!=names.length)
            throw fail(422,"INVALID_SOURCE_MAPPING","必须填写10项来源表和字段");
        List<String> v=new ArrayList<>();
        for(String key:names){
            if(!input.path(key).isTextual()||!IDENT.matcher(input.path(key).asText()).matches())
                throw fail(422,"INVALID_SOURCE_MAPPING","非法的来源标识符："+key);
            v.add(input.path(key).asText());
        }
        return new Mapping(v.get(0),v.get(1),v.get(2),v.get(3),v.get(4),
            v.get(5),v.get(6),v.get(7),v.get(8),v.get(9));
    }
    private void validateSource(Mapping m){
        Map<String,List<String>> tables=new LinkedHashMap<>();
        for(TableInfo t:sourcesInternal())tables.put(t.table(),t.columns());
        // A single table playing multiple semantic roles is unsupported in this MVP.
        if(List.of(m.batchTable(),m.workTable(),m.orderTable()).stream().distinct().count()!=3)
            throw fail(422,"SOURCE_SCHEMA_MISMATCH","请选择三张不同的测试表");
        Map<String,List<String>> required=Map.of(
            m.batchTable(),List.of(m.batchId(),m.batchStatus(),m.batchWorkOrder()),
            m.workTable(),List.of(m.workId(),m.workOrder()),
            m.orderTable(),List.of(m.orderId(),m.orderName()));
        for(var entry:required.entrySet())
            if(!permitted(entry.getKey())||!tables.containsKey(entry.getKey())
                    ||!tables.get(entry.getKey()).containsAll(entry.getValue()))
                throw fail(422,"SOURCE_SCHEMA_MISMATCH",
                    "来源表或字段不可访问（仅允许 public 的 demo_ / mvp_source_ 测试表）");
    }
    private Mapping savedMapping(){
        List<String> values=jdbc.query("SELECT source_map::text FROM mvp_demo_binding WHERE package_id=?",
            (rs,i)->rs.getString(1),PACKAGE);
        if(values.isEmpty())throw fail(409,"BINDING_REQUIRED","请先保存来源绑定");
        try{return parseMapping(json.readTree(values.getFirst()));}
        catch(com.fasterxml.jackson.core.JsonProcessingException e){
            throw fail(409,"BINDING_CORRUPT","来源映射损坏");
        }
    }
    @GetMapping("/binding")
    public Mapping binding(Authentication auth){
        readAllowed(auth);return savedMapping();
    }

    public record SourceSample(String id,String attribute,String relatedId) {}
    public record LinkedSample(String batchId,String workOrderId,String orderId) {}
    public record SourcePreview(List<SourceSample> batches,List<SourceSample> workOrders,
         List<SourceSample> orders,LinkedSample linkedExample) {}

    /** No write occurs: inspect up to three rows from each explicitly allowlisted test
     * source and find at most one real two-hop match before a user saves the mapping.
     * This is intentionally NOT a data-quality score or a claim that all rows link.
     */
    @PostMapping("/preview")
    public SourcePreview preview(@RequestBody JsonNode requested,Authentication auth){
        readAllowed(auth);
        Mapping m=parseMapping(requested);
        validateSource(m);  // The only path from external identifiers into SQL.
        List<SourceSample> batches=sample(m.batchTable(),m.batchId(),
            m.batchStatus(),m.batchWorkOrder());
        List<SourceSample> work=sample(m.workTable(),m.workId(),
            m.workOrder(),m.workOrder());
        List<SourceSample> orders=sample(m.orderTable(),m.orderId(),
            m.orderName(),m.orderId());
        String sql="SELECT b."+qi(m.batchId())+"::text AS batch_id,"
            +"w."+qi(m.workId())+"::text AS work_id,"
            +"o."+qi(m.orderId())+"::text AS order_id"
            +" FROM "+qt(m.batchTable())+" b"
            +" JOIN "+qt(m.workTable())+" w ON b."+qi(m.batchWorkOrder())
               +"::text=w."+qi(m.workId())+"::text"
            +" JOIN "+qt(m.orderTable())+" o ON w."+qi(m.workOrder())
               +"::text=o."+qi(m.orderId())+"::text LIMIT 1";
        List<LinkedSample> matches=jdbc.query(sql,(rs,index)->
            new LinkedSample(rs.getString("batch_id"),rs.getString("work_id"),
                rs.getString("order_id")));
        return new SourcePreview(batches,work,orders,matches.isEmpty()?null:matches.getFirst());
    }

    private List<SourceSample> sample(String table,String id,String attribute,String related){
        String sql="SELECT "+qi(id)+"::text AS row_id,"
            +qi(attribute)+"::text AS row_attribute,"
            +qi(related)+"::text AS row_related FROM "+qt(table)+" LIMIT 3";
        return jdbc.query(sql,(rs,index)->new SourceSample(rs.getString("row_id"),
            rs.getString("row_attribute"),rs.getString("row_related")));
    }

    private UUID existingDraft() {
        return jdbc.query("SELECT draft_id FROM native_package_draft WHERE package_id = ?",
             rs -> rs.next() ? rs.getObject(1,UUID.class) : null, PACKAGE);
    }

    @GetMapping("/status")
    public Status status(Authentication auth) {
        readAllowed(auth);
        UUID id = existingDraft();
        if (id == null) return new Status(PACKAGE,null,null,"NOT_CREATED",false,"B2026-001");
        var draft = drafts.get(id);
        boolean bound = jdbc.query("""
            SELECT 1 FROM mvp_demo_binding
             WHERE package_id=? AND draft_id=? AND bound_revision=?
            """, (org.springframework.jdbc.core.ResultSetExtractor<Boolean>) rs -> rs.next(), PACKAGE, id, draft.revision());
        return new Status(PACKAGE,id.toString(),draft.revision(),
             draft.validation().path("status").asText("NOT_RUN"),bound,"B2026-001");
    }

    @PostMapping("/model")
    public Status prepareModel(Authentication auth) {
        writeAllowed(auth);
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
        return status(auth);
    }

    @PostMapping("/bind")
    public Binding bind(@RequestBody(required=false) JsonNode chosen,Authentication auth){
        writeAllowed(auth);
        UUID id=existingDraft();
        if(id==null)throw fail(409,"MODEL_REQUIRED","请先建立示例本体");
        var draft=drafts.get(id);
        if(!"VALID".equals(draft.validation().path("status").asText())||
            draft.validation().path("validatedRevision").asLong(-1)!=draft.revision()||
            !compatible(draft.editingContent()))
            throw fail(409,"MODEL_NOT_READY","模型未通过当前修订校验，不能绑定");
        Mapping mapping=parseMapping(chosen);
        validateSource(mapping);
        String encoded;
        try{encoded=json.writeValueAsString(mapping);}
        catch(com.fasterxml.jackson.core.JsonProcessingException e){
            throw new IllegalStateException("Mapping encoding failed",e);
        }
        jdbc.update("""
            INSERT INTO mvp_demo_binding(package_id,draft_id,bound_revision,source_map)
              VALUES (?,?,?,?::jsonb) ON CONFLICT(package_id) DO UPDATE SET
              draft_id=EXCLUDED.draft_id,bound_revision=EXCLUDED.bound_revision,
              source_map=EXCLUDED.source_map,updated_at=clock_timestamp()
        """,PACKAGE,id,draft.revision(),encoded);
        int count=jdbc.queryForObject("SELECT count(*) FROM (SELECT 1 FROM "+
            qt(mapping.batchTable())+" LIMIT 1000) t",Integer.class);
        return new Binding(PACKAGE,id.toString(),draft.revision(),json.valueToTree(mapping),count);
    }

    @GetMapping("/trace")
    public Trace trace(@RequestParam String batchId,Authentication auth) {
        readAllowed(auth);
        if (batchId == null || !batchId.matches("[A-Za-z0-9-]{1,64}"))
            throw fail(400,"INVALID_BATCH_ID","批次号仅允许字母、数字和连字符，最多64位");
        Mapping m=verifiedMapping();
        // Identifiers are schema-checked and quoted; batch ID is a bound SQL parameter.
        String sql="SELECT b."+qi(m.batchId())+"::text AS batch_id,"
            +"b."+qi(m.batchStatus())+"::text AS quality_status,"
            +"w."+qi(m.workId())+"::text AS work_order_id,"
            +"o."+qi(m.orderId())+"::text AS order_id,"
            +"o."+qi(m.orderName())+"::text AS customer_name"
            +" FROM "+qt(m.batchTable())+" b"
            +" JOIN "+qt(m.workTable())+" w ON b."+qi(m.batchWorkOrder())+
              "::text=w."+qi(m.workId())+"::text"
            +" JOIN "+qt(m.orderTable())+" o ON w."+qi(m.workOrder())+
              "::text=o."+qi(m.orderId())+"::text"
            +" WHERE b."+qi(m.batchId())+"::text=? LIMIT 2";
        List<Trace> rows=jdbc.query(sql,(rs,i)->{
            String batch=rs.getString("batch_id"),work=rs.getString("work_order_id"),
              order=rs.getString("order_id");
            return new Trace(batch,rs.getString("quality_status"),List.of(
                new Fact("production-batch",batch,"生产批次",m.batchTable()),
                new Fact("work-order",work,"生产工单",m.workTable()),
                new Fact("customer-order",order,rs.getString("customer_name"),m.orderTable())
            ),List.of(
                new Edge("batch-workorder",batch,work,m.batchTable()+"."+m.batchWorkOrder()),
                new Edge("workorder-order",work,order,m.workTable()+"."+m.workOrder())
            ),m.batchTable().startsWith("mvp_source_")?
                "ISOLATED_SYNTHETIC_POSTGRES":"LOCAL_TEST_POSTGRES");
        },batchId);
        if (rows.isEmpty()) throw fail(404,"BATCH_NOT_FOUND","示例数据源中不存在该批次");
        if(rows.size()>1)throw fail(409,"AMBIGUOUS_RELATIONSHIP",
            "一个批次对应多条订单路径，本阶段不截断后冒充完整结果");
        return rows.getFirst();
    }

    private Mapping verifiedMapping() {
        UUID id=existingDraft();
        if (id==null)throw fail(409,"BINDING_REQUIRED","请先创建本体并绑定数据");
        var draft=drafts.get(id);
        boolean active=jdbc.query("""
                SELECT 1 FROM mvp_demo_binding
                 WHERE package_id=? AND draft_id=? AND bound_revision=?
                """,(org.springframework.jdbc.core.ResultSetExtractor<Boolean>) rs->rs.next(),
                PACKAGE,id,draft.revision());
        if(!active || !"VALID".equals(draft.validation().path("status").asText())
                || draft.validation().path("validatedRevision").asLong(-1)!=draft.revision()
                || !compatible(draft.editingContent()))
            throw fail(409,"BINDING_STALE","当前绑定已失效，请校验模型后重新绑定");
        Mapping m=savedMapping();
        try {validateSource(m);}
        catch(DraftFailure ex) {
            throw fail(409,"BINDING_SCHEMA_DRIFT",
                "已保存的来源表或字段发生变化，请重新配置绑定");
        }
        return m;
    }

    /**
     * A bounded, read-only ObjectSet-like result for the fixed manufacturing quality scenario.
     * Not a generic ObjectSet planner.  All source identifiers are validated/quoted; all
     * request values and cursor keys are JDBC parameters.  Each HTTP response uses one
     * repeatable-read PostgreSQL snapshot; cross-request source mutations are not frozen.
     */
    @GetMapping("/trace-set")
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public TraceSet traceSet(@RequestParam String batchId,
                             @RequestParam(defaultValue="10") int pageSize,
                             @RequestParam(required=false) String cursor,
                             Authentication authentication) {
        readAllowed(authentication);
        if(batchId==null||!batchId.matches("[A-Za-z0-9-]{1,64}"))
            throw fail(400,"INVALID_BATCH_ID","批次号仅允许字母、数字和连字符，最多64位");
        if(pageSize<1||pageSize>MAX_PAGE_SIZE)
            throw fail(400,"INVALID_PAGE_SIZE","每页只允许1至50条关系路径");
        Mapping m=verifiedMapping();
        long revision=drafts.get(existingDraft()).revision();
        String fingerprint=mappingFingerprint(m);
        ImpactCursor previous=decodeCursor(cursor,batchId,revision,fingerprint);

        // Bound the complexity of the *whole* query; a page of 50 must never
        // imply that there are only 50 affected orders.
        jdbc.execute("SET LOCAL statement_timeout = '3000ms'");
        String batchCol="b."+qi(m.batchId())+"::text";
        String workCol="w."+qi(m.workId())+"::text";
        String orderCol="o."+qi(m.orderId())+"::text";
        String from=" FROM "+qt(m.batchTable())+" b"
             +" LEFT JOIN "+qt(m.workTable())+" w ON b."+qi(m.batchWorkOrder())
             +"::text="+workCol
             +" LEFT JOIN "+qt(m.orderTable())+" o ON w."+qi(m.workOrder())
             +"::text="+orderCol;

        List<String> statuses=jdbc.query("SELECT DISTINCT b."+qi(m.batchStatus())
            +"::text AS status FROM "+qt(m.batchTable())+" b WHERE "+batchCol
            +"=? LIMIT 2",(rs,i)->rs.getString("status"),batchId);
        if(statuses.isEmpty())throw fail(404,"BATCH_NOT_FOUND","测试数据源中不存在该批次");
        if(statuses.size()!=1||statuses.getFirst()==null)
            throw fail(409,"SOURCE_FACT_CONFLICT","同一批次的质量状态不一致或为空，请核验来源事实");

        String where=" WHERE "+batchCol+"=?";
        String distinctPaths="SELECT DISTINCT "+workCol+" AS work_id,"
            +orderCol+" AS order_id"+from+where
            +" AND "+workCol+" IS NOT NULL AND "+orderCol+" IS NOT NULL";
        ImpactCounts totals=jdbc.query("""
            SELECT count(*) AS paths, count(DISTINCT work_id) AS works,
                   count(DISTINCT order_id) AS orders FROM (
            """+distinctPaths+") links",
            rs->{rs.next();return new ImpactCounts(rs.getLong("paths"),rs.getLong("works"),rs.getLong("orders"));},
            batchId);
        if(totals.pathCount()>MAX_RESULT_PATHS)
            throw fail(409,"OBJECTSET_QUERY_BUDGET_EXCEEDED",
                "关联路径超过本机MVP的10000条安全预算；请先缩小来源数据范围");

        MissingCounts missing=jdbc.query("SELECT "
            +"COUNT(DISTINCT CASE WHEN "+workCol+" IS NULL THEN b."
                 +qi(m.batchWorkOrder())+"::text ELSE NULL END) AS work_missing,"
            +"COUNT(DISTINCT CASE WHEN "+workCol+" IS NOT NULL AND "+orderCol
                 +" IS NULL THEN w."+qi(m.workOrder())+"::text ELSE NULL END) AS order_missing"
            +from+where,rs->{rs.next();return new MissingCounts(
                rs.getLong("work_missing"),rs.getLong("order_missing"));},batchId);

        String filter=previous==null?"":" AND ("+workCol+","+orderCol+")>(?,?)";
        String select="SELECT "+workCol+" AS work_id,"+orderCol+" AS order_id,"
            +"MIN(o."+qi(m.orderName())+"::text) AS customer_name,"
            +"COUNT(DISTINCT o."+qi(m.orderName())+"::text) AS label_variants"
            +from+where+" AND "+workCol+" IS NOT NULL AND "+orderCol+" IS NOT NULL"
            +filter+" GROUP BY 1,2 ORDER BY 1,2 LIMIT ?";
        Object[] args=previous==null?new Object[]{batchId,pageSize+1}:
            new Object[]{batchId,previous.lastWork(),previous.lastOrder(),pageSize+1};
        List<ImpactRow> rows=jdbc.query(select,(rs,i)->new ImpactRow(rs.getString("work_id"),
            rs.getString("order_id"),rs.getString("customer_name"),rs.getLong("label_variants")),args);
        for(ImpactRow row:rows)
            if(row.customer()==null||row.labelVariants()!=1)
                throw fail(409,"SOURCE_FACT_CONFLICT",
                    "同一客户订单存在不一致或空的显示属性，不能生成可信影响清单");
        boolean more=rows.size()>pageSize;
        List<ImpactRow> page=more?rows.subList(0,pageSize):rows;
        List<ImpactPath> paths=page.stream().map(row->new ImpactPath(row.work(),
            row.order(),row.customer(),List.of(
                new Fact("production-batch",batchId,"生产批次",m.batchTable()),
                new Fact("work-order",row.work(),"生产工单",m.workTable()),
                new Fact("customer-order",row.order(),row.customer(),m.orderTable())),
            List.of(new Edge("batch-workorder",batchId,row.work(),
                         m.batchTable()+"."+m.batchWorkOrder()),
                    new Edge("workorder-order",row.work(),row.order(),
                         m.workTable()+"."+m.workOrder())))).toList();
        String next=null;
        if(more&&!page.isEmpty()) {
            ImpactRow last=page.getLast();
            next=encodeCursor(new ImpactCursor(batchId,revision,fingerprint,last.work(),last.order()));
        }
        return new TraceSet(batchId,statuses.getFirst(),revision,fingerprint,
            totals.workCount(),totals.orderCount(),totals.pathCount(),
            missing.workRefs(),missing.orderRefs(),paths,next,
            m.batchTable().startsWith("mvp_source_")?
                "ISOLATED_SYNTHETIC_POSTGRES":"LOCAL_TEST_POSTGRES");
    }

    private String mappingFingerprint(Mapping mapping) {
        try {
            byte[] bytes=MessageDigest.getInstance("SHA-256").digest(
                json.writeValueAsBytes(mapping));
            return HexFormat.of().formatHex(bytes);
        } catch(NoSuchAlgorithmException|com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException("Cannot fingerprint binding",ex);
        }
    }
    private String encodeCursor(ImpactCursor value) {
        try{return Base64.getUrlEncoder().withoutPadding().encodeToString(
            json.writeValueAsBytes(value));}
        catch(com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException("Cannot encode trace cursor",ex);
        }
    }
    private ImpactCursor decodeCursor(String raw,String batchId,long revision,String fingerprint) {
        if(raw==null||raw.isBlank())return null;
        if(raw.length()>1536 || !raw.matches("[A-Za-z0-9_-]+"))
            throw fail(400,"INVALID_TRACE_CURSOR","分页游标格式不合法");
        try {
            JsonNode data=json.readTree(Base64.getUrlDecoder().decode(raw));
            if(!data.isObject() || data.size()!=5 ||
               !batchId.equals(data.path("batchId").asText()) ||
               revision!=data.path("revision").asLong(-1) ||
               !fingerprint.equals(data.path("sourceFingerprint").asText()) ||
               !data.path("lastWork").isTextual() || !data.path("lastOrder").isTextual() ||
               data.path("lastWork").asText().length()>1024 ||
               data.path("lastOrder").asText().length()>1024)
                throw new IllegalArgumentException("cursor mismatch");
            return new ImpactCursor(batchId,revision,fingerprint,
                data.path("lastWork").asText(),data.path("lastOrder").asText());
        } catch(IllegalArgumentException|java.io.IOException ex) {
            throw fail(400,"INVALID_TRACE_CURSOR","分页游标无效，或绑定/模型修订已变更，请重新查询");
        }
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
