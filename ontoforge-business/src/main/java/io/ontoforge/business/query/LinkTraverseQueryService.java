package io.ontoforge.business.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.ontoforge.business.connection.DataBindingService;
import io.ontoforge.business.connection.LinkBindingService;
import io.ontoforge.business.connection.LinkBindingService.FactSnapshot;
import io.ontoforge.business.connection.LinkBindingService.Detail;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

/**
 * First real M8.1 local-query-v2 executor. No persistent graph index:
 * one immutable LinkBinding revision, two M5 endpoint revisions and one physical
 * source transaction supplied by LinkBindingService. All result payloads are
 * frozen and subsequent pages must reverify both endpoints + source permissions.
 *
 * Scope deliberately limited to OBJECT_REF or PK-bound BOUNDED_ROOT_FILTER,
 * one TRAVERSE, terminal PAGE. TARGET filters, aggregates, multi-column FK and
 * M8.2/M8.3 are refused rather than silently misexecuted.
 */
@Service
public class LinkTraverseQueryService {
    private static final int MAX_PAGE = 50;
    private static final int MAX_ROWS = 10_000;
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final int MAX_SNAPSHOTS = 10;
    private static final long TTL_SECONDS = 300;
    private static final String PREFIX = "cursor:v2:";
    private final LinkBindingService linkBindings;
    private final ObjectMapper mapper;
    private final byte[] signingKey;
    private final Map<String,Frozen> snapshots=new ConcurrentHashMap<>();

    public LinkTraverseQueryService(LinkBindingService linkBindings,ObjectMapper mapper,
            @Value("${ontoforge.objectset.cursor-signing-secret:}") String secret) {
        this.linkBindings=linkBindings;this.mapper=mapper;
        if(secret==null||secret.length()<32)throw new IllegalStateException("M8.1 cursor secret requires 32 chars");
        this.signingKey=secret.getBytes(StandardCharsets.UTF_8);
    }
    public static final class Failure extends RuntimeException {
        public final int http; public final String code; public final String field; public final String executionId;
        public Failure(int http,String code,String message,String field,String executionId){
            super(message);this.http=http;this.code=code;this.field=field;this.executionId=executionId;
        }
    }
    private static Failure fail(int http,String code,String msg,String field,String executionId){
        return new Failure(http,code,msg,field,executionId);
    }
    public record Key(String valueType,String value){}
    public record Ref(String packageId,String objectTypeId,String identityDomainId,Key key){}
    public record TypeRef(String kind,String snapshotId,String packageId,String objectTypeId){}
    public record BindingVersion(String kind,String bindingId,long revision,String digest,String sourceId){}
    public record SourceFingerprint(String role,String sourceId,String schema,String table,String schemaFingerprint){}
    public record VersionLock(String snapshotId,String payloadSha256,List<BindingVersion> bindingVersions,
            List<SourceFingerprint> sourceSchemaFingerprints){}
    public record Checkpoint(String status,String reason){}
    public record SourceRead(String id,String role,String sourceId,String schema,String table,List<String> columns,
            String schemaFingerprint,String transactionIsolation,Checkpoint checkpoint,
            Instant startedAt,Instant completedAt){}
    public record Item(Ref objectRef,List<Object> properties){}
    public record Link(String linkTypeId,String linkBindingId,long linkBindingRevision,
            Ref source,Ref target,List<String> sourceReadRefs){}
    public record Quality(String accuracy,String completeness,String freshness,String answerScope,
            String scopeKind,int knownOrphanCount){}
    public record Budget(int physicalRows,int joinCandidates,int distinctObjects,int distinctLinks,
            int sourceBytes,int serializedResultBytes,int maxPhysicalRows,int maxJoinCandidates,
            int maxSourceBytes,int maxSerializedResultBytes,int queryTimeoutSeconds){}
    public record Result(String status,String resultKind,String executionId,Instant executedAt,String queryVersion,
            TypeRef rootTypeRef,TypeRef outputTypeRef,VersionLock versionLock,
            List<BindingVersion> bindingVersions,List<SourceRead> sourceReadRefs,
            String queryDigest,String resultSnapshotRef,Instant expiresAt,
            Quality quality,Budget budget,List<Item> objects,List<Link> links,
            int objectCount,int linkCount,int pathCount,int pageSize,int pageNumber,String nextCursor){}
    private record Parsed(UUID id,long revision,String linkType,String direction,int pageSize,
            TypeRef rootType,JsonNode rootSelector,JsonNode rootBindingRef,JsonNode limits){}
    private record Frozen(String id,String actor,UUID bindingId,long revision,String linkType,String executionId,
            Instant executedAt,Instant expiresAt,String digest,TypeRef rootType,TypeRef outputType,
            VersionLock lock,List<BindingVersion> versions,List<SourceRead> reads,Quality quality,
            Budget budget,List<Item> objects,List<Link> links,int pageSize){}
    private record Cursor(String id,int offset,long expiresAt,String actorDigest){}

    private static String actor(Authentication auth){
        return auth==null?"mvp-local":auth.getName();
    }
    private static UUID uuid(String s,String field,String execution) {
        try { return UUID.fromString(s); }
        catch(Exception e){throw fail(400,"MALFORMED_REQUEST","无效的 UUID",field,execution);}
    }
    private static void only(JsonNode n,Set<String> keys,String field,String execution){
        if(n==null||!n.isObject())throw fail(422,"INVALID_QUERY_AST","必须是对象",field,execution);
        n.fieldNames().forEachRemaining(k->{
            if(!keys.contains(k))throw fail(422,"INVALID_QUERY_AST","不允许的字段 "+k,field,execution);
        });
    }
    private static String required(JsonNode n,String key,String execution){
        if(n==null||!n.path(key).isTextual()||n.path(key).asText().isBlank())
            throw fail(422,"INVALID_QUERY_AST","缺少类型化查询字段",key,execution);
        return n.path(key).asText();
    }
    private Parsed parse(JsonNode json,String eid){
        only(json,Set.of("queryVersion","rootTypeRef","rootBindingRef","rootSelector","steps","limits"),"query",eid);
        if(!"local-query-v2".equals(required(json,"queryVersion",eid)))
            throw fail(422,"INVALID_QUERY_VERSION","M8.1 仅执行独立 local-query-v2","queryVersion",eid);
        JsonNode r=json.path("rootTypeRef"), typeSource=r.path("sourceRef");
        only(r,Set.of("sourceRef","packageId","objectTypeId"),"rootTypeRef",eid);
        only(typeSource,Set.of("kind","snapshotId"),"rootTypeRef.sourceRef",eid);
        if(!"LOCAL_SNAPSHOT".equals(required(typeSource,"kind",eid)))
            throw fail(422,"INVALID_TYPE_REFERENCE","必须使用 LOCAL_SNAPSHOT","rootTypeRef",eid);
        var type=new TypeRef("LOCAL_SNAPSHOT",required(typeSource,"snapshotId",eid),
                required(r,"packageId",eid),required(r,"objectTypeId",eid));
        JsonNode ref=json.path("rootBindingRef");
        only(ref,Set.of("bindingId","revision"),"rootBindingRef",eid);
        if(ref.path("revision").asLong(0)<1)
            throw fail(422,"INVALID_BINDING_VERSION","根绑定版本必须明确","rootBindingRef.revision",eid);
        JsonNode selector=json.path("rootSelector");
        only(selector,Set.of("kind","objectRef","conditions"),"rootSelector",eid);
        if(!Set.of("OBJECT_REF","BOUNDED_ROOT_FILTER").contains(selector.path("kind").asText()))
            throw fail(422,"INVALID_ROOT_SELECTOR","仅支持 ObjectRef 或受控有界根条件","rootSelector.kind",eid);
        if(!json.path("steps").isArray()||json.path("steps").size()!=2)
            throw fail(422,"UNSUPPORTED_QUERY_PLAN","首阶段必须恰好一段 TRAVERSE 和一个 PAGE","steps",eid);
        JsonNode step=json.path("steps").get(0),page=json.path("steps").get(1);
        only(step,Set.of("kind","linkTypeRef","linkBindingRef","direction","maxDepth"),"steps[0]",eid);
        only(page,Set.of("kind","pageSize"),"steps[1]",eid);
        if(!"TRAVERSE".equals(step.path("kind").asText())||step.path("maxDepth").asInt()!=1
                ||!"PAGE".equals(page.path("kind").asText())
                ||!("OUT".equals(step.path("direction").asText())||"IN".equals(step.path("direction").asText())))
            throw fail(422,"UNSUPPORTED_QUERY_PLAN","仅支持一步 OUT/IN + PAGE","steps",eid);
        int pageSize=page.path("pageSize").asInt(0);
        if(pageSize<1||pageSize>MAX_PAGE)
            throw fail(422,"QUERY_BUDGET_EXCEEDED","pageSize 必须为 1–50","steps[1].pageSize",eid);
        JsonNode b=step.path("linkBindingRef");
        only(b,Set.of("bindingId","revision"),"steps[0].linkBindingRef",eid);
        if(b.path("revision").asLong(0)<1)
            throw fail(422,"INVALID_BINDING_VERSION","必须使用明确的 LinkBinding revision","steps[0].linkBindingRef",eid);
        JsonNode limits=json.path("limits");
        if(!limits.isMissingNode()){
            only(limits,Set.of("maxPhysicalRows","maxJoinCandidates","maxLinks",
                    "maxSourceBytes","maxSerializedResultBytes","timeoutSeconds"),"limits",eid);
            for(String key:List.of("maxPhysicalRows","maxJoinCandidates","maxLinks",
                    "maxSourceBytes","maxSerializedResultBytes","timeoutSeconds")){
                if(limits.has(key)){
                    int ceiling=key.equals("maxSourceBytes")||key.equals("maxSerializedResultBytes")?MAX_BYTES:
                            key.equals("timeoutSeconds")?3:MAX_ROWS;
                    if(!limits.get(key).canConvertToInt()||limits.get(key).asInt()<1||limits.get(key).asInt()>ceiling)
                        throw fail(422,"QUERY_BUDGET_EXCEEDED","查询预算不得超过服务端限制","limits."+key,eid);
                }
            }
        }
        return new Parsed(uuid(required(b,"bindingId",eid),"linkBindingRef.bindingId",eid),
                b.path("revision").asLong(),required(step,"linkTypeRef",eid),
                step.path("direction").asText(),pageSize,type,selector,ref,limits);
    }

    private static String value(Object v){
        if(v==null)return null;
        if(v instanceof BigDecimal b)return b.stripTrailingZeros().toPlainString();
        return v.toString();
    }
    private static Ref ref(DataBindingService.BindingDetail b,Object key){
        String k=value(key);
        return new Ref(b.packageId(),b.objectTypeId(),b.identityDomainId(),
                new Key(key instanceof Number?"INTEGER":"STRING",k));
    }
    private static String identity(Ref r){
        return String.join("/",r.packageId(),r.objectTypeId(),r.identityDomainId(),
                r.key().valueType(),r.key().value());
    }
    private static String hash(byte[] bytes){
        try{return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(Exception e){throw new IllegalStateException(e);}
    }
    private String sign(String data){
        try{
            Mac m=Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(signingKey,"HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(m.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        }catch(Exception ex){throw new IllegalStateException(ex);}
    }
    private String cursor(Frozen f,int next){
        String body=Base64.getUrlEncoder().withoutPadding().encodeToString(
                (f.id()+"|"+next+"|"+f.expiresAt().toEpochMilli()+"|"+hash(f.actor().getBytes(StandardCharsets.UTF_8)))
                        .getBytes(StandardCharsets.UTF_8));
        return PREFIX+body+"."+sign(body);
    }
    private Cursor readCursor(String token,String eid){
        if(token==null||token.length()>512||!token.startsWith(PREFIX))
            throw fail(400,"INVALID_CURSOR","cursor 格式不合法","cursor",eid);
        String t=token.substring(PREFIX.length());
        String[] p=t.split("\\.",-1);
        if(p.length!=2||!MessageDigest.isEqual(sign(p[0]).getBytes(StandardCharsets.UTF_8),
                p[1].getBytes(StandardCharsets.UTF_8)))
            throw fail(400,"INVALID_CURSOR","cursor 签名无效","cursor",eid);
        try{
            String decoded=new String(Base64.getUrlDecoder().decode(p[0]),StandardCharsets.UTF_8);
            String[] fields=decoded.split("\\|",-1);
            if(fields.length!=4)throw new IllegalArgumentException("wrong cursor");
            return new Cursor(fields[0],Integer.parseInt(fields[1]),Long.parseLong(fields[2]),fields[3]);
        }catch(RuntimeException ex){
            throw fail(400,"INVALID_CURSOR","cursor 格式无效","cursor",eid);
        }
    }
    private void cleanup(){
        Instant now=Instant.now();
        snapshots.entrySet().removeIf(e->!e.getValue().expiresAt().isAfter(now));
    }
    private Result page(Frozen f,int offset) {
        if(offset<0||offset>f.objects().size())
            throw fail(400,"INVALID_CURSOR","分页偏移非法","cursor",f.executionId());
        int end=Math.min(f.objects().size(),offset+f.pageSize());
        var objects=List.copyOf(f.objects().subList(offset,end));
        Set<String> visible=new LinkedHashSet<>();
        objects.forEach(o->visible.add(identity(o.objectRef())));
        boolean out=f.outputType().objectTypeId().equals(f.links().isEmpty()?"":f.links().getFirst().target().objectTypeId());
        var links=f.links().stream().filter(l->visible.contains(identity(
                out?l.target():l.source()))).toList();
        String next=end<f.objects().size()?cursor(f,end):null;
        return new Result("SUCCESS",
                "PAGE",f.executionId(),f.executedAt(),"local-query-v2",f.rootType(),f.outputType(),
                f.lock(),f.versions(),f.reads(),f.digest(),f.id(),f.expiresAt(),f.quality(),
                f.budget(),objects,links,f.objects().size(),f.links().size(),f.links().size(),
                f.pageSize(),offset/f.pageSize()+1,next);
    }
    private static boolean selected(JsonNode selector,Ref root,String rootSourceKey,String eid){
        if("OBJECT_REF".equals(selector.path("kind").asText())){
            JsonNode obj=selector.path("objectRef"),k=obj.path("key");
            if(!obj.path("packageId").asText().equals(root.packageId())
                    ||!obj.path("objectTypeId").asText().equals(root.objectTypeId())
                    ||!obj.path("identityDomainId").asText().equals(root.identityDomainId())
                    ||!k.path("valueType").asText().equals(root.key().valueType()))
                throw fail(422,"OBJECT_REF_IDENTITY_MISMATCH","根 ObjectRef 身份必须完全匹配冻结绑定","rootSelector",eid);
            return k.path("value").asText().equals(root.key().value());
        }
        JsonNode cond=selector.path("conditions");
        if(!cond.isArray()||cond.isEmpty()||cond.size()>8)
            throw fail(422,"INVALID_ROOT_SELECTOR","有界根条件需要 1–8 条合法条件","rootSelector.conditions",eid);
        for(JsonNode x:cond){
            only(x,Set.of("propertyRef","operator","literal","values"),"rootSelector.conditions",eid);
            // Initial runtime only supports a typed equality to the bound source key;
            // other predicates require a separate reviewed bounded typed executor.
            if(!rootSourceKey.equals(x.path("propertyRef").asText())
                    ||!"EQ".equals(x.path("operator").asText())
                    ||!root.key().valueType().equals(x.path("literal").path("valueType").asText()))
                throw fail(422,"UNSUPPORTED_ROOT_FILTER","M8.1 仅支持源主键的类型化 EQ 根条件","rootSelector.conditions",eid);
            if(!root.key().value().equals(x.path("literal").path("value").asText()))return false;
        }
        return true;
    }

    private static int budget(JsonNode limits,String key,int defaultValue){
        return limits.has(key)?limits.path(key).asInt():defaultValue;
    }
    public Result execute(JsonNode query,Authentication auth){
        String eid=UUID.randomUUID().toString();
        cleanup();
        Parsed parsed=parse(query,eid);
        Detail d=linkBindings.revalidate(parsed.id(),parsed.revision(),auth);
        if(!parsed.linkType().equals(d.linkTypeId()))
            throw fail(422,"LINK_TYPE_MISMATCH","TRAVERSE 的 LinkType 不匹配冻结关系","steps[0].linkTypeRef",eid);
        var facts=linkBindings.sourceFacts(parsed.id(),parsed.revision(),auth);
        int rowsRead=facts.sourceRows().size()+facts.targetRows().size();
        if(rowsRead>budget(parsed.limits(),"maxPhysicalRows",MAX_ROWS)
                ||facts.sourceBytes()>budget(parsed.limits(),"maxSourceBytes",MAX_BYTES))
            throw fail(429,"QUERY_BUDGET_EXCEEDED",
                    "来源扫描超过本次请求声明的更严格限额","limits",eid);
        boolean out="OUT".equals(parsed.direction());
        DataBindingService.BindingDetail root=out?facts.source():facts.target();
        DataBindingService.BindingDetail target=out?facts.target():facts.source();
        if(!root.bindingId().equals(parsed.rootBindingRef().path("bindingId").asText())
                ||root.revision()!=parsed.rootBindingRef().path("revision").asLong()
                ||!Objects.equals(root.packageId(),parsed.rootType().packageId())
                ||!Objects.equals(root.snapshotId(),parsed.rootType().snapshotId())
                ||!Objects.equals(root.objectTypeId(),parsed.rootType().objectTypeId()))
            throw fail(422,"ROOT_BINDING_MISMATCH","根类型必须与 OUT/IN 决定的端点 Binding 一致","rootTypeRef",eid);
        var owners="SOURCE".equals(d.fkOwner())?facts.sourceRows():facts.targetRows();
        var referenced="SOURCE".equals(d.fkOwner())?facts.targetRows():facts.sourceRows();
        String otherKey="SOURCE".equals(d.fkOwner())?facts.target().sourceKey():facts.source().sourceKey();
        Map<String,Map<String,Object>> otherByPk=new HashMap<>();
        for(var row:referenced)otherByPk.put(value(row.get(otherKey)),row);
        Map<String,Item> objects=new LinkedHashMap<>();
        Map<String,Link> links=new LinkedHashMap<>();
        Set<String> selectedRoots=new LinkedHashSet<>();
        int orphan=0,join=0;
        for(var row:out?facts.sourceRows():facts.targetRows()){
            Ref rr=ref(root,row.get(root.sourceKey()));
            if(selected(parsed.rootSelector(),rr,root.sourceKey(),eid))selectedRoots.add(identity(rr));
        }
        for(var row:owners){
            Object fkValue=row.get(d.fkSourceColumn());
            if(fkValue==null)continue;
            String fk=value(fkValue);
            Map<String,Object> other=otherByPk.get(fk);
            Ref s="SOURCE".equals(d.fkOwner())?ref(facts.source(),row.get(facts.source().sourceKey()))
                    :other==null?null:ref(facts.source(),other.get(facts.source().sourceKey()));
            Ref t="TARGET".equals(d.fkOwner())?ref(facts.target(),row.get(facts.target().sourceKey()))
                    :other==null?null:ref(facts.target(),other.get(facts.target().sourceKey()));
            Ref candidate=out?s:t;
            if(other==null){
                // Do not pretend orphan target identity is known, or count unrelated roots.
                if(candidate!=null&&selectedRoots.contains(identity(candidate)))orphan++;
                continue;
            }
            if(candidate==null||!selectedRoots.contains(identity(candidate)))continue;
            if(++join>budget(parsed.limits(),"maxJoinCandidates",MAX_ROWS))throw fail(429,"QUERY_BUDGET_EXCEEDED","关系 join 候选超预算","limits",eid);
            Ref dest=out?t:s;
            objects.putIfAbsent(identity(dest),new Item(dest,List.of()));
            String linkKey=identity(s)+"->"+identity(t);
            links.putIfAbsent(linkKey,new Link(d.linkTypeId(),d.linkBindingId(),d.revision(),
                    s,t,List.of("source-read","target-read")));
        }
        if(objects.size()>MAX_ROWS||links.size()>budget(parsed.limits(),"maxLinks",MAX_ROWS))
            throw fail(429,"QUERY_BUDGET_EXCEEDED","结果实体或 LinkFact 超预算","limits",eid);
        var items=objects.values().stream().sorted(Comparator.comparing(o->identity(o.objectRef()))).toList();
        var edges=links.values().stream().sorted(Comparator.comparing(l->identity(l.source())+"->"+identity(l.target()))).toList();
        String outcome=orphan>0?"PARTIAL":"COMPLETE";
        Quality quality=new Quality("EXACT",outcome,"UNKNOWN",orphan>0?"KNOWN_SUBSET":"REQUESTED_SCOPE",
                "TRAVERSAL_SCOPE",orphan);
        Instant now=Instant.now();
        var sourceRef=new SourceRead("source-read","SOURCE",facts.source().sourceId(),
                facts.source().sourceSchema(),facts.source().sourceTable(),
                List.of(facts.source().sourceKey(),d.fkOwner().equals("SOURCE")?d.fkSourceColumn():facts.source().sourceKey()),
                facts.source().sourceSchemaFingerprint(),"REPEATABLE_READ",
                new Checkpoint("UNKNOWN","No verifiable business watermark"),facts.readAt(),now);
        var targetRef=new SourceRead("target-read","TARGET",facts.target().sourceId(),
                facts.target().sourceSchema(),facts.target().sourceTable(),
                List.of(facts.target().sourceKey(),d.fkOwner().equals("TARGET")?d.fkSourceColumn():facts.target().sourceKey()),
                facts.target().sourceSchemaFingerprint(),"REPEATABLE_READ",
                new Checkpoint("UNKNOWN","No verifiable business watermark"),facts.readAt(),now);
        var versions=List.of(
                new BindingVersion("OBJECT",facts.source().bindingId(),facts.source().revision(),
                        facts.source().digest(),facts.source().sourceId()),
                new BindingVersion("OBJECT",facts.target().bindingId(),facts.target().revision(),
                        facts.target().digest(),facts.target().sourceId()),
                new BindingVersion("LINK",d.linkBindingId(),d.revision(),d.digest(),facts.source().sourceId()));
        var lock=new VersionLock(d.snapshotId(),d.modelPayloadSha256(),versions,List.of(
                new SourceFingerprint("SOURCE",facts.source().sourceId(),facts.source().sourceSchema(),
                        facts.source().sourceTable(),facts.source().sourceSchemaFingerprint()),
                new SourceFingerprint("TARGET",facts.target().sourceId(),facts.target().sourceSchema(),
                        facts.target().sourceTable(),facts.target().sourceSchemaFingerprint())));
        TypeRef output=new TypeRef("LOCAL_SNAPSHOT",d.snapshotId(),root.packageId(),target.objectTypeId());
        int bytes;
        try{bytes=mapper.writeValueAsBytes(List.of(items,edges)).length;}
        catch(Exception e){throw new IllegalStateException(e);}
        if(bytes>budget(parsed.limits(),"maxSerializedResultBytes",MAX_BYTES))throw fail(429,"QUERY_BUDGET_EXCEEDED","序列化结果超过本次请求的上限","limits",eid);
        var budget=new Budget(facts.sourceRows().size()+facts.targetRows().size(),join,items.size(),edges.size(),
                facts.sourceBytes(),bytes,MAX_ROWS,MAX_ROWS,MAX_BYTES,MAX_BYTES,3);
        String digest=hash(query.toString().getBytes(StandardCharsets.UTF_8));
        var frozen=new Frozen("local-result:"+UUID.randomUUID(),actor(auth),parsed.id(),parsed.revision(),
                d.linkTypeId(),eid,now,now.plusSeconds(TTL_SECONDS),digest,parsed.rootType(),output,
                lock,versions,List.of(sourceRef,targetRef),quality,budget,items,edges,parsed.pageSize());
        if(snapshots.size()>=MAX_SNAPSHOTS)throw fail(429,"RESULT_SNAPSHOT_BUDGET",
                "结果快照数量已达到当前 Worker 上限","limits",eid);
        snapshots.put(frozen.id(),frozen);
        return page(frozen,0);
    }
    public Result nextPage(JsonNode body,Authentication auth){
        String eid=UUID.randomUUID().toString();
        only(body,Set.of("cursor"),"cursor",eid);
        Cursor c=readCursor(required(body,"cursor",eid),eid);
        if(!MessageDigest.isEqual(c.actorDigest().getBytes(StandardCharsets.UTF_8),
                hash(actor(auth).getBytes(StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8)))
            throw fail(403,"FORBIDDEN","cursor 不属于当前主体","cursor",eid);
        if(Instant.ofEpochMilli(c.expiresAt()).isBefore(Instant.now()))
            throw fail(410,"RESULT_EXPIRED","结果快照已过期","cursor",eid);
        Frozen f=snapshots.get(c.id());
        if(f==null||!f.expiresAt().isAfter(Instant.now())||f.expiresAt().toEpochMilli()!=c.expiresAt())
            throw fail(410,"RESULT_EXPIRED","查询结果已失效或 Worker 重启","cursor",eid);
        if(!f.actor().equals(actor(auth)))
            throw fail(403,"FORBIDDEN","cursor 不属于当前身份","cursor",eid);
        try {
            // Actual current source read guards against revoked PG SELECT, while
            // membership stays frozen in f and never reruns the original query.
            linkBindings.sourceFacts(f.bindingId(),f.revision(),auth);
        }catch(RuntimeException ex){
            snapshots.remove(f.id());
            throw ex;
        }
        return page(f,c.offset());
    }
}
