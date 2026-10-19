package io.ontoforge.business.model;

import io.ontoforge.core.compiler.DraftValidation;
import io.ontoforge.core.compiler.NativePackageSchema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static io.ontoforge.business.model.NativeDraftService.DraftFailure;

/**
 * S0.3 local schema snapshot, NOT a published / approved ontology release.
 * Canonical UTF-8 bytes are authoritative; JSONB is only a query mirror.
 */
@Service
public class NativeSnapshotService {
    public static final String PROFILE=io.ontoforge.core.compiler.NativeCanonicalization.PROFILE;
    private final io.ontoforge.dao.model.NativeSnapshotRepository repository;
    private final ObjectMapper mapper;

    public NativeSnapshotService(io.ontoforge.dao.model.NativeSnapshotRepository repository,ObjectMapper mapper){this.repository=repository;this.mapper=mapper;}

    public record Snapshot(String snapshotId,String packageId,String sourceDraftId,long sourceRevision,
                           String candidateSchemaVersion,String canonicalizationProfile,
                           String payloadSha256,JsonNode snapshotContent,Instant createdAt,String lifecycle) {}
    public record SnapshotSummary(String snapshotId,String packageId,long sourceRevision,
                                  String payloadSha256,Instant createdAt,String lifecycle) {}
    public record SnapshotPage(List<SnapshotSummary> items,String nextCursor) {}
    public record FreezeResult(Snapshot snapshot,boolean created) {}
    public record Difference(String entityKind,String objectTypeId,String propertyId,String path,
                             String changeKind,JsonNode before,JsonNode after,String riskLevel,String reasonCode) {}
    public record SnapshotDiff(String fromSnapshotId,String toSnapshotId,String packageId,
                               boolean sameSnapshot,List<Difference> changes,String compatibilityStatus) {}

    private record Stored(UUID id,String pkg,UUID draft,long rev,UUID key,String schema,
                          String profile,byte[] canonical,String document,String digest,Instant created) {}
    private record LockedDraft(String pkg,long revision,String editing,String validation,Long validatedRevision) {}

    private static DraftFailure fail(int status,String code,String message){
        return new DraftFailure(status,code,message);
    }
    private JsonNode json(String text){
        try {return mapper.readTree(text);} catch(Exception e){
            throw fail(500,"SNAPSHOT_INTEGRITY_FAILURE","快照内容无法验证，请保留日志并联系维护者");
        }
    }
    private String serialize(JsonNode json) {
        try{return mapper.writeValueAsString(json);}
        catch(Exception e){throw fail(422,"CANDIDATE_INVALID","无法序列化完整本体候选");}
    }
    public static byte[] canonicalize(String raw) {
        try {
            return io.ontoforge.core.compiler.NativeCanonicalization.canonicalize(raw);
        } catch (Exception e) {
            throw fail(422,"CANDIDATE_INVALID","RFC 8785 内容规范化失败：候选含不支持的 Unicode/JSON");
        }
    }
    public static String sha256(byte[] input) {
        return io.ontoforge.core.compiler.NativeCanonicalization.digest(input);
    }
    private Stored stored(ResultSet rs,int i)throws SQLException{
        return new Stored(rs.getObject("snapshot_id",UUID.class),rs.getString("package_id"),
          rs.getObject("source_draft_id",UUID.class),rs.getLong("source_revision"),
          rs.getObject("idempotency_key",UUID.class),rs.getString("candidate_schema_version"),
          rs.getString("canonicalization_profile"),rs.getBytes("canonical_payload"),
          rs.getString("snapshot_document"),rs.getString("payload_sha256"),
          rs.getTimestamp("created_at").toInstant());
    }
    private Snapshot verify(Stored row){
        if(!PROFILE.equals(row.profile)||!DraftValidation.NATIVE_VERSION.equals(row.schema)
            ||row.canonical==null||row.canonical.length==0
            ||!Objects.equals(sha256(row.canonical),row.digest)){
            throw fail(500,"SNAPSHOT_INTEGRITY_FAILURE","快照摘要或版本配置不一致，拒绝出具有效内容");
        }
        try {
            String canonText=new String(row.canonical,StandardCharsets.UTF_8);
            JsonNode node=mapper.readTree(canonText);
            if(!Arrays.equals(canonicalize(canonText),row.canonical)
                ||!node.equals(json(row.document))
                ||!NativePackageSchema.validate(node).isEmpty()
                ||!node.path("packageId").asText().equals(row.pkg)){
                throw fail(500,"SNAPSHOT_INTEGRITY_FAILURE","快照字节、镜像或 Native 合同不一致");
            }
            return new Snapshot(row.id.toString(),row.pkg,row.draft.toString(),row.rev,row.schema,
                    row.profile,row.digest,node,row.created,"LOCAL_SNAPSHOT_ONLY");
        } catch(DraftFailure e){
            if(e.http==500)throw e;
            throw fail(500,"SNAPSHOT_INTEGRITY_FAILURE","旧快照不能使用当前摘要算法复核");
        } catch(Exception e){
            throw fail(500,"SNAPSHOT_INTEGRITY_FAILURE","快照完整性核验失败");
        }
    }
    private Snapshot require(List<Stored> rows){
        if(rows.isEmpty())throw fail(404,"SNAPSHOT_NOT_FOUND","未找到指定本地模型快照");
        return verify(rows.getFirst());
    }

    @Transactional
    public FreezeResult freeze(UUID draftId,long expectedRevision,UUID idempotencyKey){
        if(expectedRevision<1)throw fail(400,"MALFORMED_REQUEST","expectedRevision 必须大于零");
        // Replays remain discoverable even after the editable draft advances.
        Snapshot old=byKeyOrNull(draftId,idempotencyKey);
        if(old!=null) return sameKey(old,expectedRevision);
        var locked=repository.lockDraft(draftId,(rs,i)->new LockedDraft(rs.getString("package_id"),rs.getLong("revision"),
                    rs.getString("editing"),rs.getString("report"),
                    (Long)rs.getObject("validated_revision")));
        if(locked.isEmpty())throw fail(404,"DRAFT_NOT_FOUND","指定草稿不存在");
        LockedDraft draft=locked.getFirst();
        // Recheck after acquiring the PostgreSQL row lock. Two workers may
        // initially observe no key before either starts the insert.
        old=byKeyOrNull(draftId,idempotencyKey);
        if(old!=null)return sameKey(old,expectedRevision);
        if(draft.revision!=expectedRevision)
            throw fail(409,"DRAFT_REVISION_CONFLICT","该草稿已由其他编辑者修改，请先核对 revision");
        if(draft.validatedRevision==null||draft.validatedRevision!=expectedRevision
           ||draft.validation==null||!"VALID".equals(json(draft.validation).path("status").asText())
           ||json(draft.validation).path("validatedRevision").asLong(-1)!=expectedRevision)
            throw fail(409,"VALIDATION_NOT_CURRENT","保存并完整校验当前草稿 revision 后才能冻结");
        JsonNode editor=json(draft.editing);
        if(!editor.isObject()||!editor.path("objectTypes").isArray()
           ||!editor.path("linkTypes").isArray()
           ||!DraftValidation.checkEditor(editor,mapper).isEmpty())
            throw fail(422,"UNSUPPORTED_DEFINITION","无法安全处理完整模型包，拒绝裁剪对象与关系资产");
        var candidate=DraftValidation.validate(draft.pkg,editor,mapper);
        if(!"VALID".equals(candidate.status())||candidate.nativeCandidate()==null
           ||!NativePackageSchema.validate(candidate.nativeCandidate()).isEmpty())
            throw fail(422,"CANDIDATE_INVALID","当前 Native 定义校验失败，不能冻结");
        JsonNode pkg=candidate.nativeCandidate();
        for(String key:List.of("imports","bindings","linkBindings","objectSets","functions",
                               "rules","decisionStrategies","policies","actions")){
            if(!pkg.path(key).isArray()||!pkg.path(key).isEmpty())
                throw fail(422,"UNSUPPORTED_DEFINITION","包含当前不能冻结的模型资产: "+key);
        }
        byte[] canonical=canonicalize(serialize(pkg));
        if(canonical.length>262144)throw fail(422,"CANDIDATE_INVALID","快照超出 256 KiB 限制");
        var prior=repository.byRevision(draftId,expectedRevision,this::stored);
        if(!prior.isEmpty())throw fail(409,"SNAPSHOT_REVISION_ALREADY_FROZEN",
                                   "此 revision 已有快照，请打开历史按修订查询");
        UUID id=UUID.randomUUID();
        repository.insert(id,draft.pkg,draftId,expectedRevision,idempotencyKey,
            DraftValidation.NATIVE_VERSION,PROFILE,canonical,
            new String(canonical,StandardCharsets.UTF_8),sha256(canonical));
        return new FreezeResult(get(id),true);
    }
    private FreezeResult sameKey(Snapshot s,long rev){
        if(s.sourceRevision()!=rev)throw fail(409,"IDEMPOTENCY_KEY_REUSED",
            "同一幂等键对应了另一来源修订，禁止重复使用");
        return new FreezeResult(s,false);
    }
    private Snapshot byKeyOrNull(UUID draftId,UUID key){
        var rows=repository.byKey(draftId,key,this::stored);
        return rows.isEmpty()?null:verify(rows.getFirst());
    }

    @Transactional(readOnly=true)
    public Snapshot get(UUID id){return require(repository.byId(id,this::stored));}
    @Transactional(readOnly=true)
    public Snapshot byKey(UUID draftId,UUID key) {
        return require(repository.byKey(draftId,key,this::stored));
    }
    @Transactional(readOnly=true)
    public Snapshot byRevision(UUID draftId,long revision){
        if(revision<1)throw fail(400,"MALFORMED_REQUEST","revision 必须大于零");
        return require(repository.byRevision(draftId,revision,this::stored));
    }
    @Transactional(readOnly=true)
    public SnapshotPage list(String packageId,int pageSize,String cursor){
        if(!DraftValidation.packageIdValid(packageId))
            throw fail(400,"MALFORMED_REQUEST","无效模型包 ID");
        if(pageSize<1||pageSize>50)throw fail(400,"MALFORMED_REQUEST","pageSize 必须在 1 到 50");
        List<Stored> items;
        if(cursor==null||cursor.isBlank()){
            items=repository.list(packageId,pageSize+1,null,null,this::stored);
        }else{
            Instant at;UUID id;
            try{
                String decoded=new String(Base64.getUrlDecoder().decode(cursor),StandardCharsets.UTF_8);
                String[] parts=decoded.split("\\|",-1);
                if(parts.length!=2)throw new IllegalArgumentException();
                at=Instant.parse(parts[0]);id=UUID.fromString(parts[1]);
            }catch(IllegalArgumentException|DateTimeException e){
                throw fail(400,"MALFORMED_REQUEST","快照分页游标不合法");
            }
            items=repository.list(packageId,pageSize+1,at,id,this::stored);
        }
        boolean more=items.size()>pageSize;
        var shown=more?items.subList(0,pageSize):items;
        List<SnapshotSummary> summaries=new ArrayList<>();
        for(Stored s:shown){
            Snapshot safe=verify(s);
            summaries.add(new SnapshotSummary(safe.snapshotId,safe.packageId,safe.sourceRevision,
                                              safe.payloadSha256,safe.createdAt,safe.lifecycle));
        }
        String next=null;
        if(more&&!shown.isEmpty()){
            Stored last=shown.getLast();
            next=Base64.getUrlEncoder().withoutPadding().encodeToString(
                   (last.created.toString()+"|"+last.id.toString()).getBytes(StandardCharsets.UTF_8));
        }
        return new SnapshotPage(summaries,next);
    }

    @Transactional(readOnly=true)
    public SnapshotDiff diff(UUID from,UUID to){
        Snapshot a=get(from),b=get(to);
        if(!a.packageId().equals(b.packageId()))
            throw fail(422,"PACKAGE_MISMATCH","只允许比较同一模型包的快照");
        List<Difference> rows=new ArrayList<>();
        if(!from.equals(to)){
            Map<String,JsonNode> at=types(a.snapshotContent());
            Map<String,JsonNode> bt=types(b.snapshotContent());
            var typeIds=new java.util.TreeSet<String>();
            typeIds.addAll(at.keySet());typeIds.addAll(bt.keySet());
            for(String typeId:typeIds){
                JsonNode x=at.get(typeId),y=bt.get(typeId);
                if(x==null||y==null){
                    rows.add(new Difference("OBJECT_TYPE",typeId,null,"/objectTypes/"+typeId,
                        x==null?"ADDED":"REMOVED",x,y,x==null?"ADDITIVE":"POTENTIALLY_BREAKING","TYPE_IDENTITY_CHANGED"));
                    continue;
                }
                for(String field:List.of("apiName","label","description","primaryKeyRef")){
                    compareField(rows,"OBJECT_TYPE",typeId,null,"/objectTypes/"+typeId+"/"+field,
                                 x.get(field),y.get(field),field);
                }
                Map<String,JsonNode> ap=properties(x),bp=properties(y);
                var propIds=new java.util.TreeSet<String>();propIds.addAll(ap.keySet());propIds.addAll(bp.keySet());
                for(String pid:propIds){
                    JsonNode v=ap.get(pid),w=bp.get(pid);
                    String base="/objectTypes/"+typeId+"/properties/"+pid;
                    if(v==null||w==null){
                        rows.add(new Difference("PROPERTY",typeId,pid,base,v==null?"ADDED":"REMOVED",
                            v,w,v==null&& !w.path("required").asBoolean(false)?"ADDITIVE":"POTENTIALLY_BREAKING",
                            "PROPERTY_IDENTITY_CHANGED"));
                        continue;
                    }
                    for(String field:List.of("apiName","label","valueType","required","searchable",
                                             "classification","enumValues")){
                        JsonNode before=v.get(field),after=w.get(field);
                        // Native ENUM values are set-like; order-only changes do not alter structural meaning.
                        if("enumValues".equals(field)&&before!=null&&after!=null&&
                           sortedArray(before).equals(sortedArray(after)))continue;
                        compareField(rows,"PROPERTY",typeId,pid,base+"/"+field,before,after,field);
                    }
                }
            }
        }
        if(!from.equals(to)){
            Map<String,JsonNode> oldLinks=links(a.snapshotContent()),newLinks=links(b.snapshotContent());
            var linkIds=new java.util.TreeSet<String>();
            linkIds.addAll(oldLinks.keySet());linkIds.addAll(newLinks.keySet());
            for(String lid:linkIds){
                JsonNode old=oldLinks.get(lid),now=newLinks.get(lid);
                String path="/linkTypes/"+lid;
                if(old==null||now==null){
                    rows.add(new Difference("LINK_TYPE",null,null,path,old==null?"ADDED":"REMOVED",
                        old,now,old==null?"ADDITIVE":"POTENTIALLY_BREAKING","LINK_IDENTITY_CHANGED"));
                    continue;
                }
                for(String field:List.of("apiName","label","sourceTypeRef","targetTypeRef","cardinality","evidenceRequired")){
                    JsonNode before=old.get(field),after=now.get(field);
                    if(Objects.equals(before,after))continue;
                    if("evidenceRequired".equals(field)&&before!=null&&before.asBoolean(false)
                        &&after!=null&&!after.asBoolean(true)){
                        rows.add(new Difference("LINK_TYPE",null,null,path+"/"+field,"MODIFIED",
                            before,after,"POLICY_REVIEW_REQUIRED","LINK_EVIDENCE_RELAXED"));
                    }else compareField(rows,"LINK_TYPE",null,null,path+"/"+field,before,after,field);
                }
            }
        }
        rows.sort(Comparator.comparing(Difference::path).thenComparing(Difference::changeKind));
        return new SnapshotDiff(a.snapshotId(),b.snapshotId(),a.packageId(),from.equals(to),rows,
                                "STRUCTURAL_RISK_ONLY_NOT_COMPATIBILITY_CERTIFICATION");
    }
    private static Map<String,JsonNode> types(JsonNode document){
        Map<String,JsonNode> ret=new HashMap<>();
        for(JsonNode n:document.path("objectTypes"))ret.put(n.path("id").asText(),n);
        return ret;
    }
    private static Map<String,JsonNode> links(JsonNode document){
        Map<String,JsonNode> ret=new HashMap<>();
        for(JsonNode n:document.path("linkTypes"))ret.put(n.path("id").asText(),n);
        return ret;
    }
    private static Map<String,JsonNode> properties(JsonNode obj){
        Map<String,JsonNode> ret=new HashMap<>();
        for(JsonNode n:obj.path("properties"))ret.put(n.path("id").asText(),n);
        return ret;
    }
    private static Set<String> sortedArray(JsonNode n){
        java.util.TreeSet<String> ret=new java.util.TreeSet<>();
        if(n.isArray())for(JsonNode item:n)ret.add(item.toString());
        return ret;
    }
    private static void compareField(List<Difference> out,String kind,String type,String pid,
                                     String path,JsonNode a,JsonNode b,String field){
        if(Objects.equals(a,b))return;
        String risk=switch(field){
            case "label","description" -> "DOC_ONLY";
            case "classification" -> "POLICY_REVIEW_REQUIRED";
            case "required" -> b!=null&&b.asBoolean(false)?"POTENTIALLY_BREAKING":"ADDITIVE";
            case "searchable" -> a!=null&&a.asBoolean(false)&&b!=null&&!b.asBoolean(true)
                                          ?"POTENTIALLY_BREAKING":"ADDITIVE";
            default -> "POTENTIALLY_BREAKING";
        };
        out.add(new Difference(kind,type,pid,path,"MODIFIED",a,b,risk,"SCHEMA_FIELD_CHANGED"));
    }
}
