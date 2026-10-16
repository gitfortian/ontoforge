package io.ontoforge.boot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import io.ontoforge.boot.model.NativeSnapshotService;
import java.net.URI;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** REAL PG17 + HTTP contracts, separate from the HTML mock UX gate. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class NativeSnapshotTest {
    @LocalServerPort int port;
    @Autowired TestRestTemplate http;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    private String api(String path){return "http://127.0.0.1:"+port+"/api/model"+path;}
    private ResponseEntity<String> call(HttpMethod method,String path,JsonNode body){
        HttpHeaders headers=new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange(URI.create(api(path)),method,
              new HttpEntity<>(body==null?null:body.toString(),headers),String.class);
    }
    private JsonNode call(HttpMethod method,String path,JsonNode body,int expected)throws Exception{
        var r=call(method,path,body);
        assertThat(r.getStatusCode().value()).describedAs(r.getBody()).isEqualTo(expected);
        return mapper.readTree(r.getBody());
    }
    private ObjectNode obj(String id,String api){var x=mapper.createObjectNode();x.put("id",id);x.put("apiName",api);
        x.put("label","生产批次");x.put("description","质量追溯批次");x.put("primaryKeyRef","batch-id");
        x.putArray("properties");return x;}
    private ObjectNode prop(String id,String api,String type,boolean required,boolean searchable){
        var x=mapper.createObjectNode();x.put("id",id);x.put("apiName",api);
        x.put("valueType",type);x.put("required",required);x.put("searchable",searchable);
        x.put("classification","INTERNAL");return x;
    }
    private ObjectNode editor(){
        var d=mapper.createObjectNode();d.put("editorSchemaVersion","0.1.0");d.putArray("objectTypes");d.putArray("linkTypes");
        ObjectNode t=obj("production-batch","ProductionBatch");
        ((ArrayNode)t.get("properties")).add(prop("batch-id","batchId","STRING",true,true));
        ((ArrayNode)d.get("objectTypes")).add(t);
        return d;
    }
    private ObjectNode revise(long n){var x=mapper.createObjectNode();x.put("expectedRevision",n);return x;}
    private ObjectNode body(long n,JsonNode d){var x=revise(n);x.set("editingContent",d);return x;}
    private ObjectNode freezeReq(long n,UUID key){var x=revise(n);x.put("idempotencyKey",key.toString());return x;}
    private String fresh(){return "manufacturing.p"+UUID.randomUUID().toString().replace("-","");}
    private record Prepared(String draft,String pkg,ObjectNode doc) {}
    private Prepared prepared()throws Exception{
        String pkg=fresh();
        ObjectNode create=mapper.createObjectNode();create.put("packageId",pkg);
        String id=call(HttpMethod.POST,"/drafts",create,201).path("draftId").asText();
        ObjectNode doc=editor();
        call(HttpMethod.PUT,"/drafts/"+id,body(1,doc),200);
        assertThat(call(HttpMethod.POST,"/drafts/"+id+"/validate",revise(2),200).path("status").asText()).isEqualTo("VALID");
        return new Prepared(id,pkg,doc);
    }
    private JsonNode frozen(Prepared p,long rev,UUID key,int code)throws Exception{
        return call(HttpMethod.POST,"/drafts/"+p.draft+"/snapshots",freezeReq(rev,key),code);
    }
    @Test void snapshotRealPostgresFreezeIdempotentReloadAndIntegrity()throws Exception{
        String install=jdbc.queryForObject("SELECT installation_id::text FROM platform_installation WHERE id=1",String.class);
        Prepared p=prepared();
        UUID key=UUID.randomUUID();
        JsonNode made=frozen(p,2,key,201);
        String sid=made.path("snapshotId").asText();
        assertThat(made.path("lifecycle").asText()).isEqualTo("LOCAL_SNAPSHOT_ONLY");
        assertThat(made.path("sourceRevision").asLong()).isEqualTo(2);
        assertThat(made.path("payloadSha256").asText()).matches("[0-9a-f]{64}");
        assertThat(made.path("canonicalizationProfile").asText()).isEqualTo("jcs-rfc8785-v1");
        assertThat(frozen(p,2,key,200).path("snapshotId").asText()).isEqualTo(sid);
        assertThat(call(HttpMethod.GET,"/drafts/"+p.draft+"/snapshots/by-key/"+key,null,200).path("snapshotId").asText()).isEqualTo(sid);
        assertThat(call(HttpMethod.GET,"/drafts/"+p.draft+"/snapshots/by-revision/2",null,200).path("snapshotId").asText()).isEqualTo(sid);
        JsonNode detail=call(HttpMethod.GET,"/snapshots/"+sid,null,200);
        assertThat(detail.path("snapshotContent").path("objectTypes").get(0).path("id").asText()).isEqualTo("production-batch");
        byte[] bytes=jdbc.queryForObject("SELECT canonical_payload FROM native_schema_snapshot WHERE snapshot_id=?",
                                        (rs,i)->rs.getBytes(1),UUID.fromString(sid));
        assertThat(NativeSnapshotService.sha256(bytes)).isEqualTo(detail.path("payloadSha256").asText());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM native_schema_snapshot WHERE source_draft_id=?",Integer.class,
                                      UUID.fromString(p.draft))).isEqualTo(1);
        assertThat(call(HttpMethod.GET,"/packages/"+p.pkg+"/snapshots?pageSize=20",null,200).path("items").size()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT installation_id::text FROM platform_installation WHERE id=1",String.class))
            .isEqualTo(install);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE version='3' AND success",Integer.class)).isEqualTo(1);
    }
    @Test void snapshotRevisionCasUnsupportedAndValidationFailures()throws Exception{
        Prepared p=prepared();
        UUID key=UUID.randomUUID();
        assertThat(frozen(p,1,key,409).path("code").asText()).isEqualTo("DRAFT_REVISION_CONFLICT");
        assertThat(frozen(p,2,key,201).path("snapshotId").asText()).isNotEmpty();
        assertThat(frozen(p,3,key,409).path("code").asText()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(frozen(p,2,UUID.randomUUID(),409).path("code").asText()).isEqualTo("SNAPSHOT_REVISION_ALREADY_FROZEN");
        ObjectNode changed=p.doc.deepCopy();
        changed.path("objectTypes").get(0).deepCopy();
        ((ObjectNode)changed.path("objectTypes").get(0)).put("description","updated");
        call(HttpMethod.PUT,"/drafts/"+p.draft,body(2,changed),200);
        assertThat(frozen(p,3,UUID.randomUUID(),409).path("code").asText()).isEqualTo("VALIDATION_NOT_CURRENT");
        call(HttpMethod.POST,"/drafts/"+p.draft+"/validate",revise(3),200);
        JsonNode second=frozen(p,3,UUID.randomUUID(),201);
        assertThat(second.path("snapshotId").asText()).isNotEmpty();
        ObjectNode two=p.doc.deepCopy();
        ObjectNode extra=obj("other-type","OtherType");
        ((ArrayNode)extra.get("properties")).add(prop("batch-id","batchId","STRING",true,true));
        ((ArrayNode)two.get("objectTypes")).add(extra);
        call(HttpMethod.PUT,"/drafts/"+p.draft,body(3,two),200);
        call(HttpMethod.POST,"/drafts/"+p.draft+"/validate",revise(4),200);
        assertThat(frozen(p,4,UUID.randomUUID(),422).path("code").asText()).isEqualTo("UNSUPPORTED_DEFINITION");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM native_schema_snapshot WHERE source_draft_id=?",
                                      Integer.class,UUID.fromString(p.draft))).isEqualTo(2);
    }
    @Test void snapshotDiffTypeScopedRiskAndRejectedCrossPackage()throws Exception{
        Prepared p=prepared();
        String a=frozen(p,2,UUID.randomUUID(),201).path("snapshotId").asText();
        ObjectNode next=p.doc.deepCopy();
        ObjectNode t=(ObjectNode)next.path("objectTypes").get(0);
        t.put("description","updated explanatory text");
        ((ObjectNode)t.path("properties").get(0)).put("searchable",false);
        JsonNode updated=call(HttpMethod.PUT,"/drafts/"+p.draft,body(2,next),200);
        assertThat(updated.path("revision").asLong()).isEqualTo(3);
        call(HttpMethod.POST,"/drafts/"+p.draft+"/validate",revise(3),200);
        String b=frozen(p,3,UUID.randomUUID(),201).path("snapshotId").asText();
        JsonNode diff=call(HttpMethod.GET,"/snapshot-diffs?from="+a+"&to="+b,null,200);
        assertThat(diff.path("compatibilityStatus").asText())
            .isEqualTo("STRUCTURAL_RISK_ONLY_NOT_COMPATIBILITY_CERTIFICATION");
        assertThat(diff.path("changes").toString()).contains("DOC_ONLY","POTENTIALLY_BREAKING","searchable");
        assertThat(call(HttpMethod.GET,"/snapshot-diffs?from="+a+"&to="+a,null,200).path("changes").size()).isZero();
        Prepared different=prepared();
        String c=frozen(different,2,UUID.randomUUID(),201).path("snapshotId").asText();
        assertThat(call(HttpMethod.GET,"/snapshot-diffs?from="+a+"&to="+c,null,422).path("code").asText())
            .isEqualTo("PACKAGE_MISMATCH");
        assertThat(call(HttpMethod.GET,"/snapshots/"+a,null,200).path("snapshotContent").path("objectTypes").get(0)
                      .path("description").asText()).isEqualTo("质量追溯批次");
    }
    @Test void snapshotDatabaseImmutableAndBadRequests()throws Exception{
        Prepared p=prepared();
        String id=frozen(p,2,UUID.randomUUID(),201).path("snapshotId").asText();
        assertThatThrownBy(()->jdbc.update("UPDATE native_schema_snapshot SET package_id=? WHERE snapshot_id=?",
                              "attacker.modified",UUID.fromString(id)))
            .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->jdbc.update("DELETE FROM native_schema_snapshot WHERE snapshot_id=?",UUID.fromString(id)))
            .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(call(HttpMethod.GET,"/snapshots/"+UUID.randomUUID(),null,404).path("code").asText())
            .isEqualTo("SNAPSHOT_NOT_FOUND");
        assertThat(call(HttpMethod.GET,"/packages/"+p.pkg+"/snapshots?pageSize=0",null,400).path("code").asText())
            .isEqualTo("MALFORMED_REQUEST");
        assertThat(call(HttpMethod.GET,"/packages/"+p.pkg+"/snapshots?cursor="+
                     java.util.Base64.getUrlEncoder().encodeToString("not-a-date|x".getBytes()),null,400)
                     .path("code").asText()).isEqualTo("MALFORMED_REQUEST");
    }
    @Test void snapshotCanonicalJcsReferenceDeterminism() {
        String input="{\"b\":2,\"a\":1,\"nested\":{\"z\":true,\"x\":\"hello\"}}";
        String expected="{\"a\":1,\"b\":2,\"nested\":{\"x\":\"hello\",\"z\":true}}";
        assertThat(new String(NativeSnapshotService.canonicalize(input),java.nio.charset.StandardCharsets.UTF_8))
            .isEqualTo(expected);
        assertThat(NativeSnapshotService.sha256(NativeSnapshotService.canonicalize(input)))
            .isEqualTo(NativeSnapshotService.sha256(NativeSnapshotService.canonicalize(expected)));
        String rfc="{\"\\u20ac\":\"Euro Sign\",\"\\r\":\"Carriage Return\",\"1\":\"One\",\"\\u0080\":\"Control\", \"\\ud83d\\ude00\":\"Grinning Face\"}";
        assertThat(new String(NativeSnapshotService.canonicalize(rfc),java.nio.charset.StandardCharsets.UTF_8))
            .contains("Grinning Face");
    }
}
