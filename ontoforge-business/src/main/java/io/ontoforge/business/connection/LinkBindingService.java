package io.ontoforge.business.connection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.ontoforge.business.model.NativeSnapshotService;
import io.ontoforge.dao.connection.LinkBindingRepository;
import io.ontoforge.platform.identity.B0AuthorizationService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * First production M8.1 slice: CUSTOM LinkType + two immutable M5 endpoint bindings
 * -> FULL physical FK verification -> immutable, CAS-advanced LinkBinding revision.
 * No unreviewed M:N bridge, two-hop, user SQL or implicit 'latest' binding versions.
 */
@Service
public class LinkBindingService {
    private static final Pattern IDENT = Pattern.compile("[a-z][a-z0-9_]{0,62}");
    private static final int ROW_BUDGET = 10_000;
    private static final int BYTE_BUDGET = 8 * 1024 * 1024;
    private static final long SOURCE_NANOS = TimeUnit.SECONDS.toNanos(3);
    private final LinkBindingRepository repository;
    private final DataBindingService bindings;
    private final NativeSnapshotService snapshots;
    private final B0AuthorizationService authorization;
    private final ObjectProvider<JdbcTemplate> sourceProvider;
    private final ObjectMapper mapper;

    public LinkBindingService(LinkBindingRepository repository, DataBindingService bindings,
            NativeSnapshotService snapshots, B0AuthorizationService authorization,
            @Qualifier("bindingSourceJdbcTemplate") ObjectProvider<JdbcTemplate> sourceProvider,
            ObjectMapper mapper) {
        this.repository=repository; this.bindings=bindings; this.snapshots=snapshots;
        this.authorization=authorization; this.sourceProvider=sourceProvider; this.mapper=mapper;
    }

    public record ModelSourceRef(String kind, String snapshotId) {}
    public record BindingRef(String bindingId, long revision, String digest) {}
    public record Fingerprints(String source, String target) {}
    public record Scope(String kind, String coverage) {}
    public record Request(String contractVersion, ModelSourceRef modelSourceRef, String packageId,
            String linkTypeId, BindingRef sourceBindingRef, BindingRef targetBindingRef,
            String mode, String fkOwner, String fkSourceColumn, long expectedRevision,
            UUID idempotencyKey, Fingerprints expectedSchemaFingerprints, Scope validationScope) {}
    public record Issue(String code, String fieldPath, String message) {}
    public record ValidationReport(String status, String coverage, Instant checkedAt,
            int checkedRows, int orphanCount, int nullFkCount, String cardinality,
            String sourceSchemaFingerprint, String targetSchemaFingerprint,
            String referencedIdentityColumn, List<Issue> issues) {}
    public record Detail(String linkBindingId, long revision, String packageId, String snapshotId,
            String linkTypeId, String modelPayloadSha256, String sourceBindingId,
            long sourceBindingRevision, String sourceBindingDigest,
            String targetBindingId, long targetBindingRevision, String targetBindingDigest,
            String fkOwner, String fkSourceColumn, String referencedIdentityColumn,
            String sourceSchemaFingerprint, String targetSchemaFingerprint, String cardinality,
            String validationStatus, int checkedRows, int orphanCount, int nullFkCount,
            String digest, boolean historical, Instant checkedAt) {}

    public static final class Failure extends RuntimeException {
        public final int http;
        public final String code;
        public final String fieldPath;
        public Failure(int http,String code,String message,String fieldPath) {
            super(message);this.http=http;this.code=code;this.fieldPath=fieldPath;
        }
    }
    private static Failure fail(int http,String code,String message,String field) {
        return new Failure(http,code,message,field);
    }

    private record Resolved(NativeSnapshotService.Snapshot snapshot, JsonNode link,
            DataBindingService.BindingDetail source, DataBindingService.BindingDetail target,
            DataBindingService.SourceTable sourceTable, DataBindingService.SourceTable targetTable,
            DataBindingService.SourceColumn fkColumn, DataBindingService.SourceColumn referencedColumn, String fkOwner) {}
    private record FactRows(List<Map<String,Object>> source,List<Map<String,Object>> target,int bytes) {}
    private record Count(int rows,int orphan,int nulls,int linked,boolean cardinalityViolation) {}
    private record ReportContext(Resolved resolved,ValidationReport report) {}

    private static UUID uuid(String value,String field) {
        try{return UUID.fromString(value);}catch(RuntimeException ex) {
            throw fail(400,"MALFORMED_REQUEST","必须提供有效 UUID",field);
        }
    }
    private static String nonblank(String value, String field) {
        if(value==null||value.isBlank())throw fail(400,"MALFORMED_REQUEST","缺少必要字段",field);
        return value;
    }
    private static String q(String id) {
        if(id==null||!IDENT.matcher(id).matches())
            throw fail(422,"INVALID_SOURCE_COLUMN","仅支持已经登记的 PostgreSQL 安全标识符","fkSourceColumn");
        return "\""+id+"\"";
    }
    private static String key(Object value) {
        if(value==null)return null;
        if(value instanceof BigDecimal dec)return dec.stripTrailingZeros().toPlainString();
        if(value instanceof Number n)return n.toString();
        return value.toString();
    }
    private String digest(Object data) {
        try {
            byte[] canonical=NativeSnapshotService.canonicalize(mapper.writeValueAsString(data));
            byte[] bytes=MessageDigest.getInstance("SHA-256").digest(canonical);
            return java.util.HexFormat.of().formatHex(bytes);
        }catch(Exception ex){throw new IllegalStateException("Cannot digest canonical LinkBinding contract",ex);}
    }

    private Resolved resolve(Request request,Authentication auth) {
        if(request==null||!"local-link-binding-v1".equals(request.contractVersion())
                ||request.modelSourceRef()==null||!"LOCAL_SNAPSHOT".equals(request.modelSourceRef().kind())
                ||request.sourceBindingRef()==null||request.targetBindingRef()==null
                ||request.expectedSchemaFingerprints()==null||request.validationScope()==null
                ||!"VALIDATION_SCOPE".equals(request.validationScope().kind())
                ||!"FULL".equals(request.validationScope().coverage())
                ||!"FOREIGN_KEY".equals(request.mode())
                ||!("SOURCE".equals(request.fkOwner())||"TARGET".equals(request.fkOwner()))
                ||request.expectedRevision()<0
                ||request.idempotencyKey()==null
                ||request.packageId()==null||request.linkTypeId()==null) {
            throw fail(422,"INVALID_LINK_BINDING_REQUEST","仅支持 Local Snapshot / FOREIGN_KEY / FULL，写入须有幂等 UUID","request");
        }
        UUID snapshotId=uuid(request.modelSourceRef().snapshotId(),"modelSourceRef.snapshotId");
        UUID sb=uuid(request.sourceBindingRef().bindingId(),"sourceBindingRef.bindingId");
        UUID tb=uuid(request.targetBindingRef().bindingId(),"targetBindingRef.bindingId");
        if(request.sourceBindingRef().revision()<1||request.targetBindingRef().revision()<1)
            throw fail(422,"INVALID_BINDING_VERSION","两端必须引用明确的不可变 revision","bindingRef");
        if(request.fkSourceColumn()==null||!IDENT.matcher(request.fkSourceColumn()).matches())
            throw fail(422,"INVALID_SOURCE_COLUMN","FK 必须是单个受控来源列","fkSourceColumn");

        authorization.requirePackage(auth,request.packageId(),B0AuthorizationService.Operation.READ_DRAFT);
        authorization.requireSnapshot(auth,snapshotId,B0AuthorizationService.Operation.READ_SNAPSHOT);
        NativeSnapshotService.Snapshot snapshot=snapshots.get(snapshotId);
        if(!request.packageId().equals(snapshot.packageId()))
            throw fail(403,"ACCESS_DENIED","模型包范围不匹配","packageId");
        JsonNode link=null;
        for(JsonNode item:snapshot.snapshotContent().path("linkTypes")) {
            if(request.linkTypeId().equals(item.path("id").asText())){link=item;break;}
        }
        if(link==null)throw fail(422,"LINK_TYPE_NOT_FOUND","快照不含指定 LinkType","linkTypeId");
        String cardinality=link.path("cardinality").asText();
        if("MANY_TO_MANY".equals(cardinality))
            throw fail(422,"UNSUPPORTED_CARDINALITY","桥表 M:N 仅在 M8.2 实施","linkTypeId");
        String expectedOwner="ONE_TO_MANY".equals(cardinality)?"TARGET":
                "MANY_TO_ONE".equals(cardinality)?"SOURCE":request.fkOwner();
        if(!expectedOwner.equals(request.fkOwner()))
            throw fail(422,"FK_OWNER_CARDINALITY_MISMATCH","FK 所在端与关系基数不一致","fkOwner");
        DataBindingService.BindingDetail source=bindings.getBinding(sb,
                request.sourceBindingRef().revision(),auth);
        DataBindingService.BindingDetail target=bindings.getBinding(tb,
                request.targetBindingRef().revision(),auth);
        if(!source.snapshotId().equals(snapshot.snapshotId())
                ||!target.snapshotId().equals(snapshot.snapshotId())
                ||!source.packageId().equals(snapshot.packageId())
                ||!target.packageId().equals(snapshot.packageId())
                ||!source.objectTypeId().equals(link.path("sourceTypeRef").asText())
                ||!target.objectTypeId().equals(link.path("targetTypeRef").asText())
                ||!source.payloadSha256().equals(snapshot.payloadSha256())
                ||!target.payloadSha256().equals(snapshot.payloadSha256()))
            throw fail(422,"INVALID_ENDPOINT_BINDING","两端 Binding 必须属于同一快照及 LinkType 指定 ObjectType","bindingRef");
        if(request.sourceBindingRef().digest()!=null&&!request.sourceBindingRef().digest().equals(source.digest()))
            throw fail(409,"BINDING_VERSION_DRIFT","SOURCE Binding 内容摘要与冻结引用不一致","sourceBindingRef.digest");
        if(request.targetBindingRef().digest()!=null&&!request.targetBindingRef().digest().equals(target.digest()))
            throw fail(409,"BINDING_VERSION_DRIFT","TARGET Binding 内容摘要与冻结引用不一致","targetBindingRef.digest");
        if(!DataBindingService.SOURCE_ID.equals(source.sourceId())
                ||!source.sourceId().equals(target.sourceId())
                ||!"public".equals(source.sourceSchema())||!"public".equals(target.sourceSchema()))
            throw fail(422,"CROSS_SOURCE_NOT_SUPPORTED","M8.1 只允许同一受控 PostgreSQL 来源","bindingRef");

        List<DataBindingService.SourceTable> tables=bindings.listTables(snapshot.packageId(),auth);
        var sourceTable=tables.stream().filter(t->t.table().equals(source.sourceTable())).findFirst()
                .orElseThrow(()->fail(403,"SOURCE_NOT_ALLOWED","SOURCE 不在当前白名单可见范围内","sourceBindingRef"));
        var targetTable=tables.stream().filter(t->t.table().equals(target.sourceTable())).findFirst()
                .orElseThrow(()->fail(403,"SOURCE_NOT_ALLOWED","TARGET 不在当前白名单可见范围内","targetBindingRef"));
        if(!source.sourceSchemaFingerprint().equals(sourceTable.schemaFingerprint())
                ||!target.sourceSchemaFingerprint().equals(targetTable.schemaFingerprint())
                ||!sourceTable.schemaFingerprint().equals(request.expectedSchemaFingerprints().source())
                ||!targetTable.schemaFingerprint().equals(request.expectedSchemaFingerprints().target()))
            throw fail(409,"LINK_SOURCE_SCHEMA_DRIFT","两端来源结构指纹已经改变；必须重新发现与核验","expectedSchemaFingerprints");

        var ownerTable="SOURCE".equals(request.fkOwner())?sourceTable:targetTable;
        var otherTable="SOURCE".equals(request.fkOwner())?targetTable:sourceTable;
        var otherBinding="SOURCE".equals(request.fkOwner())?target:source;
        var fk=ownerTable.columns().stream().filter(c->c.name().equals(request.fkSourceColumn())).findFirst()
                .orElseThrow(()->fail(422,"INVALID_SOURCE_COLUMN","FK 列不存在","fkSourceColumn"));
        var referenced=otherTable.columns().stream().filter(c->c.name().equals(otherBinding.sourceKey())).findFirst()
                .orElseThrow(()->fail(409,"BINDING_SOURCE_KEY_DRIFT","被引用端的冻结身份列不可见","bindingRef"));
        if(!(referenced.primaryKey()||referenced.uniqueKey()))
            throw fail(422,"SOURCE_KEY_NOT_UNIQUE","被引用身份列必须是单列 PK 或 UNIQUE","bindingRef");
        if(!compatible(fk,referenced))
            throw fail(422,"FK_TYPE_MISMATCH","FK 与被引用身份列的数据库类型不兼容","fkSourceColumn");
        return new Resolved(snapshot,link,source,target,sourceTable,targetTable,fk,referenced,request.fkOwner());
    }
    private static boolean compatible(DataBindingService.SourceColumn a,DataBindingService.SourceColumn b) {
        String x=a.udtName(),y=b.udtName();
        if(x==null||y==null)return false;
        if(x.equals(y))return true;
        return Set.of("varchar","text","bpchar").contains(x)
                &&Set.of("varchar","text","bpchar").contains(y);
    }

    /** Read both source tables on ONE read-only PG connection and ONE MVCC snapshot. */
    private FactRows facts(Resolved r) {
        JdbcTemplate jdbc=sourceProvider.getIfAvailable();
        if(jdbc==null)throw fail(503,"SOURCE_UNAVAILABLE","未配置专用只读 PostgreSQL 来源连接","sourceId");
        long started=System.nanoTime();
        try {
            return jdbc.execute((ConnectionCallback<FactRows>) connection-> {
                boolean previousAuto=connection.getAutoCommit();
                int previousIsolation=connection.getTransactionIsolation();
                boolean previousReadOnly=connection.isReadOnly();
                try {
                    connection.setReadOnly(true);
                    connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                    connection.setAutoCommit(false);
                    var source=scan(connection,r.sourceTable().table(),r.source().sourceKey(),
                            "SOURCE".equals(r.fkOwner()) ? r.fkColumn().name():null,started);
                    var target=scan(connection,r.targetTable().table(),r.target().sourceKey(),
                            "TARGET".equals(r.fkOwner()) ? r.fkColumn().name():null,started);
                    int rows=source.size()+target.size();
                    if(rows>ROW_BUDGET)throw fail(429,"QUERY_BUDGET_EXCEEDED","两端扫描合计超过 10000 行","validationScope");
                    int bytes=estimate(source)+estimate(target);
                    if(bytes>BYTE_BUDGET)throw fail(429,"QUERY_BUDGET_EXCEEDED","读取数据超过 8MiB","validationScope");
                    connection.rollback();
                    return new FactRows(source,target,bytes);
                }finally{
                    connection.rollback();
                    connection.setAutoCommit(previousAuto);
                    connection.setTransactionIsolation(previousIsolation);
                    connection.setReadOnly(previousReadOnly);
                }
            });
        }catch(Failure ex){throw ex;}
        catch(DataAccessException ex){throw fail(503,"SOURCE_READ_FAILED","双端来源读取失败，请验证数据库只读权限与结构","sourceId");}
    }
    private List<Map<String,Object>> scan(Connection c,String table,String pk,String fk,long start) throws SQLException {
        String a=q(pk), b=fk==null?null:q(fk);
        String columns=b==null||pk.equals(fk)?a:a+","+b;
        String sql="SELECT "+columns+" FROM "+q("public")+"."+q(table)+" ORDER BY "+a+" LIMIT 10001";
        try(PreparedStatement s=c.prepareStatement(sql)){
            s.setQueryTimeout(3);
            s.setMaxRows(ROW_BUDGET+1);
            try(ResultSet rs=s.executeQuery()){
                var out=new ArrayList<Map<String,Object>>();
                while(rs.next()){
                    if(System.nanoTime()-start>SOURCE_NANOS)
                        throw fail(503,"SOURCE_TIMEOUT","双端来源读取超过 3 秒","validationScope");
                    Map<String,Object> row=new LinkedHashMap<>();
                    row.put(pk,rs.getObject(pk));
                    if(fk!=null)row.put(fk,rs.getObject(fk));
                    out.add(row);
                    if(out.size()>ROW_BUDGET)throw fail(429,"QUERY_BUDGET_EXCEEDED","单端物理扫描超过预算","validationScope");
                }
                return List.copyOf(out);
            }
        }
    }
    private static int estimate(List<Map<String,Object>> rows) {
        long bytes=0;
        for(var row:rows) for(Object value:row.values())if(value!=null)bytes+=key(value).getBytes(StandardCharsets.UTF_8).length+32;
        return (int)Math.min(Integer.MAX_VALUE,bytes);
    }
    private Count count(Resolved r,FactRows facts) {
        boolean ownerSource="SOURCE".equals(r.fkOwner());
        var owner=ownerSource?facts.source():facts.target();
        var other=ownerSource?facts.target():facts.source();
        String keyColumn=ownerSource?r.target().sourceKey():r.source().sourceKey();
        Map<String,Integer> keys=new HashMap<>();
        for(var item:other) {
            String k=key(item.get(keyColumn));
            if(k!=null&&keys.putIfAbsent(k,1)!=null)
                throw fail(409,"IDENTITY_CONFLICT","被引用端的来源键出现重复身份","bindingRef");
        }
        int orphan=0,nulls=0,linked=0;
        Set<String> fkSeen=new HashSet<>();
        boolean uniqueViolation=false;
        for(var row:owner) {
            String fk=key(row.get(r.fkColumn().name()));
            if(fk==null){nulls++;continue;}
            if(keys.containsKey(fk)){
                linked++;
                if("ONE_TO_ONE".equals(r.link().path("cardinality").asText())&&!fkSeen.add(fk))
                    uniqueViolation=true;
            }else orphan++;
        }
        return new Count(facts.source().size()+facts.target().size(),orphan,nulls,linked,uniqueViolation);
    }
    private ReportContext inspect(Request request,Authentication auth) {
        Resolved r=resolve(request,auth);
        FactRows rows=facts(r);
        Count n=count(r,rows);
        if(n.cardinalityViolation())
            throw fail(409,"CARDINALITY_VIOLATION","当前外键事实不满足 ONE_TO_ONE 基数约束","fkSourceColumn");
        String quality=n.orphan()>0?"VALID_WITH_ORPHANS":"VALID";
        ValidationReport report=new ValidationReport(quality,"FULL",Instant.now(),n.rows(),
                n.orphan(),n.nulls(),r.link().path("cardinality").asText(),
                r.sourceTable().schemaFingerprint(),r.targetTable().schemaFingerprint(),
                "SOURCE".equals(request.fkOwner())?r.target().sourceKey():r.source().sourceKey(),
                List.of());
        return new ReportContext(r,report);
    }

    public ValidationReport validate(Request request, Authentication auth) {
        return inspect(request,auth).report();
    }

    @Transactional
    public Detail create(Request request,Authentication auth) {
        if(request==null||request.expectedRevision()!=0)
            throw fail(400,"MALFORMED_REQUEST","新建 LinkBinding 的 expectedRevision 必须为 0","expectedRevision");
        authorization.requirePackage(auth,nonblank(request.packageId(),"packageId"),
                B0AuthorizationService.Operation.WRITE_DRAFT);
        String requestDigest=digest(request);
        var op=repository.operation(request.packageId(),request.idempotencyKey());
        if(op!=null)return recover(op,requestDigest,null,auth);
        var report=inspect(request,auth);
        authorization.requirePackage(auth,request.packageId(),B0AuthorizationService.Operation.WRITE_DRAFT);
        if(repository.find(request.packageId(),UUID.fromString(request.modelSourceRef().snapshotId()),request.linkTypeId())!=null)
            throw fail(409,"LINK_BINDING_REVISION_CONFLICT","该 LinkType 已存在绑定，请打开当前版本进行修订","linkTypeId");
        UUID id=UUID.randomUUID();
        repository.create(id,request.packageId(),UUID.fromString(request.modelSourceRef().snapshotId()),
                request.linkTypeId(),report.report().checkedAt());
        persist(id,1,request,report);
        if(repository.advance(id,0,1,report.report().checkedAt())!=1)
            throw fail(409,"LINK_BINDING_REVISION_CONFLICT","并发创建版本竞争失败","expectedRevision");
        repository.operation(request.packageId(),request.idempotencyKey(),id,1,requestDigest);
        return detail(id,1,false);
    }

    @Transactional
    public Detail update(UUID id,Request request,Authentication auth) {
        if(request==null||request.expectedRevision()<1)
            throw fail(400,"MALFORMED_REQUEST","更新必须提交正整数 expectedRevision","expectedRevision");
        authorization.requirePackage(auth,nonblank(request.packageId(),"packageId"),
                B0AuthorizationService.Operation.WRITE_DRAFT);
        String requestDigest=digest(request);
        var op=repository.operation(request.packageId(),request.idempotencyKey());
        if(op!=null)return recover(op,requestDigest,id,auth);
        var head=repository.lock(id);
        if(head==null)throw fail(404,"LINK_BINDING_NOT_FOUND","绑定不存在","linkBindingId");
        if(!head.packageId().equals(request.packageId()))
            throw fail(403,"ACCESS_DENIED","不允许访问其他包的 LinkBinding","packageId");
        if(head.revision()!=request.expectedRevision())
            throw fail(409,"LINK_BINDING_REVISION_CONFLICT","保存前已发生并发版本变化","expectedRevision");
        var report=inspect(request,auth);
        if(!head.snapshotId().toString().equals(request.modelSourceRef().snapshotId())
                ||!head.linkTypeId().equals(request.linkTypeId()))
            throw fail(422,"INVALID_LINK_BINDING_REQUEST","修订不允许切换原 head 的快照或 LinkType","linkTypeId");
        long rev=head.revision()+1;
        persist(id,rev,request,report);
        if(repository.advance(id,head.revision(),rev,report.report().checkedAt())!=1)
            throw fail(409,"LINK_BINDING_REVISION_CONFLICT","并发头指针已推进","expectedRevision");
        repository.operation(request.packageId(),request.idempotencyKey(),id,rev,requestDigest);
        return detail(id,rev,false);
    }
    private Detail recover(LinkBindingRepository.Operation op,String digest,UUID requested,Authentication auth){
        if(!op.requestDigest().equals(digest)||requested!=null&&!requested.equals(op.bindingId()))
            throw fail(409,"IDEMPOTENCY_KEY_CONFLICT","幂等 UUID 已绑定不同的请求内容","idempotencyKey");
        return get(op.bindingId(),op.revision(),auth);
    }
    private void persist(UUID id,long rev,Request request,ReportContext ctx) {
        var r=ctx.resolved();var v=ctx.report();
        String content=digest(List.of(id.toString(),rev,request.packageId(),request.linkTypeId(),
                r.snapshot().payloadSha256(),r.source().digest(),r.target().digest(),
                request.fkOwner(),request.fkSourceColumn(),v.sourceSchemaFingerprint(),v.targetSchemaFingerprint(),
                v.status(),v.checkedRows(),v.orphanCount(),v.nullFkCount(),v.checkedAt().toString()));
        repository.saveRevision(id,rev,UUID.fromString(r.snapshot().snapshotId()),r.snapshot().packageId(),
                request.linkTypeId(),r.snapshot().payloadSha256(),UUID.fromString(r.source().bindingId()),
                r.source().revision(),r.source().digest(),UUID.fromString(r.target().bindingId()),
                r.target().revision(),r.target().digest(),request.fkOwner(),request.fkSourceColumn(),
                v.referencedIdentityColumn(),v.sourceSchemaFingerprint(),v.targetSchemaFingerprint(),
                v.cardinality(),v.status(),v.checkedRows(),v.orphanCount(),v.nullFkCount(),content,v.checkedAt());
    }

    private Detail detail(UUID id,long revision,boolean historical) {
        var r=repository.revision(id,revision);
        if(r==null)throw fail(404,"LINK_BINDING_REVISION_NOT_FOUND","未找到指定不可变修订","revision");
        Instant checked=((java.sql.Timestamp)r.get("checked_at")).toInstant();
        return new Detail(id.toString(),revision,(String)r.get("package_id"),
                ((UUID)r.get("snapshot_id")).toString(),(String)r.get("link_type_id"),
                (String)r.get("model_payload_sha256"),((UUID)r.get("source_binding_id")).toString(),
                ((Number)r.get("source_binding_revision")).longValue(),(String)r.get("source_binding_digest"),
                ((UUID)r.get("target_binding_id")).toString(),((Number)r.get("target_binding_revision")).longValue(),
                (String)r.get("target_binding_digest"),(String)r.get("fk_owner"),
                (String)r.get("fk_source_column"),(String)r.get("referenced_identity_column"),
                (String)r.get("source_schema_fingerprint"),(String)r.get("target_schema_fingerprint"),
                (String)r.get("cardinality"),(String)r.get("quality"),
                ((Number)r.get("checked_rows")).intValue(),((Number)r.get("orphan_count")).intValue(),
                ((Number)r.get("null_fk_count")).intValue(),(String)r.get("digest"),historical,checked);
    }

    public Detail get(UUID id,Long revision,Authentication auth) {
        var h=repository.get(id);
        if(h==null)throw fail(404,"LINK_BINDING_NOT_FOUND","未找到指定 LinkBinding","linkBindingId");
        authorization.requirePackage(auth,h.packageId(),B0AuthorizationService.Operation.READ_DRAFT);
        authorization.requireSnapshot(auth,h.snapshotId(),B0AuthorizationService.Operation.READ_SNAPSHOT);
        long use=revision==null?h.revision():revision;
        if(use<1)throw fail(422,"INVALID_BINDING_VERSION","revision 必须大于零","revision");
        return detail(id,use,use!=h.revision());
    }
    public List<Detail> list(String packageId,Authentication auth) {
        authorization.requirePackage(auth,packageId,B0AuthorizationService.Operation.READ_DRAFT);
        return repository.list(packageId).stream().map(h->{
            authorization.requireSnapshot(auth,h.snapshotId(),B0AuthorizationService.Operation.READ_SNAPSHOT);
            return detail(h.id(),h.revision(),false);
        }).toList();
    }
    public List<Detail> revisions(UUID id,Authentication auth) {
        var h=repository.get(id);
        if(h==null)throw fail(404,"LINK_BINDING_NOT_FOUND","绑定不存在","linkBindingId");
        authorization.requirePackage(auth,h.packageId(),B0AuthorizationService.Operation.READ_DRAFT);
        authorization.requireSnapshot(auth,h.snapshotId(),B0AuthorizationService.Operation.READ_SNAPSHOT);
        return repository.revisions(id).stream().map(r->detail(id,((Number)r.get("revision")).longValue(),
                ((Number)r.get("revision")).longValue()!=h.revision())).toList();
    }

    /**
     * Source facts for the separate versioned v2 executor. This is NOT a persisted
     * LinkFact index: both endpoints are reloaded from one read-only PG snapshot.
     * Rows contain only bounded PK/FK columns, never unapproved client SQL.
     */
    public record FactSnapshot(Detail binding, DataBindingService.BindingDetail source,
            DataBindingService.BindingDetail target, List<Map<String,Object>> sourceRows,
            List<Map<String,Object>> targetRows, int sourceBytes, Instant readAt) {}

    public FactSnapshot sourceFacts(UUID id,long revision,Authentication auth) {
        Detail d=revalidate(id,revision,auth);
        var request=new Request("local-link-binding-v1",
                new ModelSourceRef("LOCAL_SNAPSHOT",d.snapshotId()),
                d.packageId(),d.linkTypeId(),
                new BindingRef(d.sourceBindingId(),d.sourceBindingRevision(),d.sourceBindingDigest()),
                new BindingRef(d.targetBindingId(),d.targetBindingRevision(),d.targetBindingDigest()),
                "FOREIGN_KEY",d.fkOwner(),d.fkSourceColumn(),revision,UUID.randomUUID(),
                new Fingerprints(d.sourceSchemaFingerprint(),d.targetSchemaFingerprint()),
                new Scope("VALIDATION_SCOPE","FULL"));
        Resolved r=resolve(request,auth);
        FactRows facts=facts(r);
        Count count=count(r,facts);
        if(count.cardinalityViolation())
            throw fail(409,"CARDINALITY_VIOLATION","来源当前事实不满足绑定的关系基数","linkBindingId");
        return new FactSnapshot(d,r.source(),r.target(),facts.source(),facts.target(),facts.bytes(),Instant.now());
    }

    /** Rebuild authorization, snapshot identity and source fingerprint gates on execute/page. */
    public Detail revalidate(UUID id,long revision,Authentication auth) {
        Detail d=get(id,revision,auth);
        Request current=new Request("local-link-binding-v1",new ModelSourceRef("LOCAL_SNAPSHOT",d.snapshotId()),
                d.packageId(),d.linkTypeId(),
                new BindingRef(d.sourceBindingId(),d.sourceBindingRevision(),d.sourceBindingDigest()),
                new BindingRef(d.targetBindingId(),d.targetBindingRevision(),d.targetBindingDigest()),
                "FOREIGN_KEY",d.fkOwner(),d.fkSourceColumn(),revision,UUID.randomUUID(),
                new Fingerprints(d.sourceSchemaFingerprint(),d.targetSchemaFingerprint()),
                new Scope("VALIDATION_SCOPE","FULL"));
        resolve(current,auth); // read-only metadata, no fresh full scan here
        return d;
    }
}
