package io.ontoforge.business.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.ontoforge.business.model.NativeDraftService;
import io.ontoforge.platform.identity.B0AuthorizationService;
import org.springframework.security.core.Authentication;
import io.ontoforge.business.model.NativeDraftService.DraftFailure;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.regex.Pattern;
import java.util.Base64;
import java.util.HexFormat;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import java.util.UUID;
import io.ontoforge.dao.query.QualityRepository.ImpactCounts;
import io.ontoforge.dao.query.QualityRepository.MissingCounts;

/**
 * Small, explicitly isolated vertical product slice: Native definitions -> durable
 * table bindings -> parameterized PostgreSQL object/relationship traversal.
 * It is NOT a general ObjectSet planner, connector, release or factory deployment.
 */
@org.springframework.stereotype.Service
public class QualityMvpService {
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
    private static io.ontoforge.dao.query.QualityRepository.Mapping physicalMapping(Mapping m) {
        return new io.ontoforge.dao.query.QualityRepository.Mapping(m.batchTable(),m.batchId(),m.batchStatus(),
            m.batchWorkOrder(),m.workTable(),m.workId(),m.workOrder(),m.orderTable(),m.orderId(),m.orderName());
    }
    private final io.ontoforge.dao.query.QualityRepository repository;
    private final NativeDraftService drafts;
    private final ObjectMapper json;
    private final B0AuthorizationService authorization;

    public QualityMvpService(io.ontoforge.dao.query.QualityRepository repository, NativeDraftService drafts, ObjectMapper json,
                                B0AuthorizationService authorization) {
        this.repository = repository; this.drafts = drafts; this.json = json; this.authorization=authorization;
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
    private record ImpactRow(String work,String order,String customer,long labelVariants) {}

    public List<TableInfo> sources(Authentication auth){
        readAllowed(auth);
        return sourcesInternal();
    }
    private List<TableInfo> sourcesInternal(){
        Map<String,List<String>> tables=new LinkedHashMap<>();
        repository.sourceColumns((org.springframework.jdbc.core.RowCallbackHandler) rs->{
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
        List<String> values=repository.savedMappings(PACKAGE);
        if(values.isEmpty())throw fail(409,"BINDING_REQUIRED","请先保存来源绑定");
        try{return parseMapping(json.readTree(values.getFirst()));}
        catch(com.fasterxml.jackson.core.JsonProcessingException e){
            throw fail(409,"BINDING_CORRUPT","来源映射损坏");
        }
    }
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
    public SourcePreview preview(JsonNode requested,Authentication auth){
        readAllowed(auth);
        Mapping m=parseMapping(requested);
        validateSource(m);  // The only path from external identifiers into SQL.
        List<SourceSample> batches=sample(m.batchTable(),m.batchId(),
            m.batchStatus(),m.batchWorkOrder());
        List<SourceSample> work=sample(m.workTable(),m.workId(),
            m.workOrder(),m.workOrder());
        List<SourceSample> orders=sample(m.orderTable(),m.orderId(),
            m.orderName(),m.orderId());
        List<LinkedSample> matches=repository.linkedExample(physicalMapping(m),(rs,index)->
            new LinkedSample(rs.getString("batch_id"),rs.getString("work_id"),
                rs.getString("order_id")));
        return new SourcePreview(batches,work,orders,matches.isEmpty()?null:matches.getFirst());
    }

    private List<SourceSample> sample(String table,String id,String attribute,String related){
        return repository.samples(table,id,attribute,related,(rs,index)->new SourceSample(rs.getString("row_id"),
            rs.getString("row_attribute"),rs.getString("row_related")));
    }

    private UUID existingDraft() {
        return repository.existingDraft(PACKAGE);
    }

    public Status status(Authentication auth) {
        readAllowed(auth);
        UUID id = existingDraft();
        if (id == null) return new Status(PACKAGE,null,null,"NOT_CREATED",false,"B2026-001");
        var draft = drafts.get(id);
        boolean bound = repository.bound(PACKAGE,id,draft.revision());
        return new Status(PACKAGE,id.toString(),draft.revision(),
             draft.validation().path("status").asText("NOT_RUN"),bound,"B2026-001");
    }

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

    public Binding bind(JsonNode chosen,Authentication auth){
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
        repository.bind(PACKAGE,id,draft.revision(),encoded);
        int count=repository.sampleCount(mapping.batchTable());
        return new Binding(PACKAGE,id.toString(),draft.revision(),json.valueToTree(mapping),count);
    }

    public Trace trace(String batchId,Authentication auth) {
        readAllowed(auth);
        if (batchId == null || !batchId.matches("[A-Za-z0-9-]{1,64}"))
            throw fail(400,"INVALID_BATCH_ID","批次号仅允许字母、数字和连字符，最多64位");
        Mapping m=verifiedMapping();
        // Identifiers are schema-checked and quoted; batch ID is a bound SQL parameter.
        List<Trace> rows=repository.trace(physicalMapping(m),batchId,(rs,i)->{
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
        });
        if (rows.isEmpty()) throw fail(404,"BATCH_NOT_FOUND","示例数据源中不存在该批次");
        if(rows.size()>1)throw fail(409,"AMBIGUOUS_RELATIONSHIP",
            "一个批次对应多条订单路径，本阶段不截断后冒充完整结果");
        return rows.getFirst();
    }

    private Mapping verifiedMapping() {
        UUID id=existingDraft();
        if (id==null)throw fail(409,"BINDING_REQUIRED","请先创建本体并绑定数据");
        var draft=drafts.get(id);
        boolean active=repository.bound(PACKAGE,id,draft.revision());
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
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public TraceSet traceSet(String batchId,
                             int pageSize,
                             String cursor,
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
        repository.limitStatementTime();
        var physical=physicalMapping(m);
        List<String> statuses=repository.statuses(physical,batchId);
        if(statuses.isEmpty())throw fail(404,"BATCH_NOT_FOUND","测试数据源中不存在该批次");
        if(statuses.size()!=1||statuses.getFirst()==null)
            throw fail(409,"SOURCE_FACT_CONFLICT","同一批次的质量状态不一致或为空，请核验来源事实");

        ImpactCounts totals=repository.impactCounts(physical,batchId);
        if(totals.pathCount()>MAX_RESULT_PATHS)
            throw fail(409,"OBJECTSET_QUERY_BUDGET_EXCEEDED",
                "关联路径超过本机MVP的10000条安全预算；请先缩小来源数据范围");
        // A query result cannot contradict the cardinality in the actual Native
        // model.  Existing MANY_TO_ONE snapshots must be explicitly reviewed
        // and edited before running a 1:N impact search.
        JsonNode definition=drafts.get(existingDraft()).editingContent();
        if(totals.workCount()>1 &&
           !allowsMultipleTargets(definition,"batch-workorder"))
            throw fail(409,"ONTOLOGY_CARDINALITY_MISMATCH",
                "当前本体“批次→工单”关系声明不允许一个批次关联多个工单；请修改LinkType基数、校验并重新绑定");
        if(!allowsMultipleTargets(definition,"workorder-order")) {
            Boolean multiplePerWork=repository.multipleOrdersPerWork(physical,batchId);
            if(Boolean.TRUE.equals(multiplePerWork))
                throw fail(409,"ONTOLOGY_CARDINALITY_MISMATCH",
                    "当前本体“工单→订单”关系声明不允许一个工单关联多个订单；请修改LinkType基数、校验并重新绑定");
        }

        MissingCounts missing=repository.missingReferences(physical,batchId);
        List<ImpactRow> rows=repository.impactRows(physical,batchId,
            previous==null?null:previous.lastWork(),previous==null?null:previous.lastOrder(),pageSize+1,
            (rs,i)->new ImpactRow(rs.getString("work_id"),rs.getString("order_id"),
                rs.getString("customer_name"),rs.getLong("label_variants")));
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


    /**
     * M3: typed, bounded object browsing for the three Native manufacturing types.
     * Relation cardinality is intentionally NOT flattened into a scalar property.
     * This is a small executable ObjectSet read operation, not an arbitrary SQL API.
     */
    public record ObjectSetObject(String id,String attributeName,String attributeValue,
                                  String sourceTable,String idSourceColumn,
                                  String attributeSourceColumn,long physicalRowCount) {}
    public record ObjectSetPage(String objectTypeId,String filterField,String operator,
                                String filterValue,long boundRevision,String sourceFingerprint,
                                long totalCount,List<ObjectSetObject> objects,String nextCursor) {}
    private record ObjectBrowseRole(String table,String idCol,String attributeCol,String attributeName) {}
    private record BrowseCursor(String objectTypeId,String filterField,String operator,
                                String filterValue,long revision,String fingerprint,String lastId) {}
    private record BrowseRow(String id,String attribute,long valueVariants,long missingValues,
                             long sourceRows) {}

    private static ObjectBrowseRole browseRole(String typeId,Mapping mapping){
        return switch(typeId) {
            case "production-batch" -> new ObjectBrowseRole(mapping.batchTable(),
                mapping.batchId(),mapping.batchStatus(),"qualityStatus");
            case "work-order" -> new ObjectBrowseRole(mapping.workTable(),
                mapping.workId(),mapping.workId(),"workOrderId");
            case "customer-order" -> new ObjectBrowseRole(mapping.orderTable(),
                mapping.orderId(),mapping.orderName(),"customerName");
            default -> throw fail(422,"UNSUPPORTED_OBJECT_TYPE",
                "此本机MVP仅支持生产批次、生产工单、客户订单三个Native类型");
        };
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public ObjectSetPage objectSet(String objectTypeId,
                                   String filterField,
                                   String operator,
                                   String filterValue,
                                   int pageSize,
                                   String cursor,
                                   Authentication auth) {
        readAllowed(auth);
        if(pageSize<1||pageSize>50)
            throw fail(400,"INVALID_PAGE_SIZE","每页对象数量必须是1至50");
        if(filterValue==null||filterValue.length()>128)
            throw fail(400,"INVALID_OBJECTSET_FILTER","筛选值不得超过128个字符");
        if(!"eq".equals(operator)&&!"prefix".equals(operator))
            throw fail(422,"UNSUPPORTED_OBJECTSET_OPERATOR","仅支持eq或prefix筛选");
        Mapping mapping=verifiedMapping();
        ObjectBrowseRole role=browseRole(objectTypeId,mapping);
        if(!"id".equals(filterField)&&!role.attributeName().equals(filterField))
            throw fail(422,"UNSUPPORTED_OBJECTSET_PROPERTY",
                "仅支持对象标识符与当前类型的受控标量属性，关联字段不可作为单值属性");
        var nativeDoc=drafts.get(existingDraft());
        if(!hasObject(nativeDoc.editingContent(),objectTypeId))
            throw fail(409,"OBJECT_TYPE_NOT_IN_MODEL","当前有效Native本体不包含目标对象类型");
        long revision=nativeDoc.revision();
        String fp=mappingFingerprint(mapping);
        BrowseCursor last=decodeBrowseCursor(cursor,objectTypeId,filterField,operator,
                                                filterValue,revision,fp);
        repository.limitStatementTime();
        long totalCount=repository.browseCount(role.table(),role.idCol(),role.attributeCol(),
            "id".equals(filterField),operator,filterValue);
        if(totalCount>MAX_RESULT_PATHS)
            throw fail(409,"OBJECTSET_QUERY_BUDGET_EXCEEDED",
                       "匹配对象超过10000个的本机测试安全预算，请缩小筛选条件");
        List<BrowseRow> result=repository.browseRows(role.table(),role.idCol(),role.attributeCol(),
            "id".equals(filterField),operator,filterValue,last==null?null:last.lastId(),pageSize+1,
            (rs,i)->new BrowseRow(rs.getString("object_id"),rs.getString("attribute_value"),
                rs.getLong("variants"),rs.getLong("null_count"),rs.getLong("row_count")));
        for(BrowseRow row:result){
            if(row.valueVariants()!=1||row.missingValues()!=0)
                throw fail(409,"OBJECTSET_ATTRIBUTE_CONFLICT",
                           "来源对象的受控标量属性存在空值或互相冲突的事实，不能安全输出");
        }
        boolean hasMore=result.size()>pageSize;
        List<BrowseRow> page=hasMore?result.subList(0,pageSize):result;
        List<ObjectSetObject> objects=page.stream().map(row->new ObjectSetObject(
            row.id(),role.attributeName(),row.attribute(),role.table(),
            role.table()+"."+role.idCol(),role.table()+"."+role.attributeCol(),
            row.sourceRows())).toList();
        String next=null;
        if(hasMore&&!page.isEmpty())next=encodeBrowseCursor(new BrowseCursor(
            objectTypeId,filterField,operator,filterValue,revision,fp,page.getLast().id()));
        return new ObjectSetPage(objectTypeId,filterField,operator,filterValue,
                                 revision,fp,totalCount,objects,next);
    }

    private static boolean hasObject(JsonNode content,String id){
        for(JsonNode object:content.path("objectTypes"))
            if(id.equals(object.path("id").asText()))return true;
        return false;
    }

    private String encodeBrowseCursor(BrowseCursor value){
        try{return Base64.getUrlEncoder().withoutPadding().encodeToString(
            json.writeValueAsBytes(value));}
        catch(com.fasterxml.jackson.core.JsonProcessingException ex){
            throw new IllegalStateException("Cannot encode ObjectSet cursor",ex);
        }
    }
    private BrowseCursor decodeBrowseCursor(String raw,String typeId,String field,
                                             String operator,String filterValue,
                                             long revision,String fingerprint){
        if(raw==null||raw.isBlank())return null;
        if(raw.length()>2048||!raw.matches("[A-Za-z0-9_-]+"))
            throw fail(400,"INVALID_OBJECTSET_CURSOR","分页游标格式不合法");
        try {
            JsonNode c=json.readTree(Base64.getUrlDecoder().decode(raw));
            if(!c.isObject()||c.size()!=7 ||
                !typeId.equals(c.path("objectTypeId").asText())||
                !field.equals(c.path("filterField").asText())||
                !operator.equals(c.path("operator").asText())||
                !filterValue.equals(c.path("filterValue").asText())||
                revision!=c.path("revision").asLong(-1)||
                !fingerprint.equals(c.path("fingerprint").asText())||
                !c.path("lastId").isTextual()||
                c.path("lastId").asText().length()>1024)
                throw new IllegalArgumentException("cursor/query mismatch");
            return new BrowseCursor(typeId,field,operator,filterValue,revision,
                                    fingerprint,c.path("lastId").asText());
        } catch(IllegalArgumentException|java.io.IOException ex) {
            throw fail(400,"INVALID_OBJECTSET_CURSOR",
                "分页游标已损坏，或筛选/模型/来源绑定已变更，请重新查询");
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

    private static boolean allowsMultipleTargets(JsonNode doc,String linkId) {
        for(JsonNode link:doc.path("linkTypes"))
            if(linkId.equals(link.path("id").asText()))
                return "ONE_TO_MANY".equals(link.path("cardinality").asText())
                    || "MANY_TO_MANY".equals(link.path("cardinality").asText());
        return false;
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
        addLink(links,"batch-workorder","BatchWorkOrder","批次关联工单","production-batch","work-order");
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
        // Real batch/work and work/order linkage can be N:M in manufacturing.
        link.put("cardinality","MANY_TO_MANY");link.put("evidenceRequired",true);
    }
}
