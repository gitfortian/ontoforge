package io.ontoforge.boot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import io.ontoforge.boot.model.NativeSnapshotService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Future;
import java.net.URI;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
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
@ActiveProfiles("test")
@Import(B0TestSecurityConfiguration.class)
class NativeSnapshotTest {
    @LocalServerPort int port;
    @Autowired TestRestTemplate http;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired NativeSnapshotService service;
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
        JsonNode twoTypes=frozen(p,4,UUID.randomUUID(),201);
        assertThat(twoTypes.path("snapshotContent").path("objectTypes").size()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM native_schema_snapshot WHERE source_draft_id=?",
                                      Integer.class,UUID.fromString(p.draft))).isEqualTo(3);
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
    @Test void snapshotConcurrentWorkersSameKeyExactlyOneSnapshot() throws Exception {
        Prepared p=prepared();
        UUID id=UUID.fromString(p.draft), key=UUID.randomUUID();
        CountDownLatch start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)){
            java.util.concurrent.Callable<NativeSnapshotService.FreezeResult> worker=()->{
                if(!start.await(10,TimeUnit.SECONDS))throw new IllegalStateException("Workers never started");
                return service.freeze(id,2,key);
            };
            Future<NativeSnapshotService.FreezeResult> first=pool.submit(worker);
            Future<NativeSnapshotService.FreezeResult> second=pool.submit(worker);
            start.countDown();
            var a=first.get(15,TimeUnit.SECONDS);
            var b=second.get(15,TimeUnit.SECONDS);
            assertThat(a.snapshot().snapshotId()).isEqualTo(b.snapshot().snapshotId());
            assertThat((a.created()?1:0)+(b.created()?1:0)).isEqualTo(1);
            assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM native_schema_snapshot WHERE source_draft_id=?",
                Integer.class,id)).isEqualTo(1);
        }
    }

    @Test void snapshotSharedRfc8785GoldenFixtures() throws Exception {
        // These same fixtures are run through independent Node and Python
        // implementations in CI, not just compared against Jackson output.
        var path=java.nio.file.Path.of("../doc/design/fixtures/ofip-0003-jcs-fixtures.json");
        JsonNode suite=mapper.readTree(java.nio.file.Files.readString(path));
        assertThat(suite.path("profile").asText()).isEqualTo("jcs-rfc8785-v1");
        assertThat(suite.path("vectors").size()).isGreaterThanOrEqualTo(4);
        for(JsonNode vector:suite.path("vectors")) {
            byte[] actual=NativeSnapshotService.canonicalize(vector.path("raw").asText());
            assertThat(new String(actual,java.nio.charset.StandardCharsets.UTF_8))
                .describedAs(vector.path("name").asText())
                .isEqualTo(vector.path("canonical").asText());
            assertThat(NativeSnapshotService.sha256(actual))
                .describedAs(vector.path("name").asText())
                .isEqualTo(vector.path("sha256").asText());
        }
    }

    @Test void snapshotConcurrentDifferentKeysSameRevisionRejectsSecond()throws Exception {
        Prepared p=prepared();
        UUID draftId=UUID.fromString(p.draft);
        CountDownLatch start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)){
            java.util.concurrent.Callable<String> worker=()->{
                if(!start.await(10,TimeUnit.SECONDS))throw new IllegalStateException("Workers never started");
                try{
                    return service.freeze(draftId,2,UUID.randomUUID()).created()?"201":"200";
                }catch(io.ontoforge.boot.model.NativeDraftService.DraftFailure e){
                    return e.http+":"+e.code;
                }
            };
            Future<String> first=pool.submit(worker);
            Future<String> second=pool.submit(worker);
            start.countDown();
            var result=java.util.Set.of(first.get(20,TimeUnit.SECONDS),second.get(20,TimeUnit.SECONDS));
            assertThat(result).containsExactlyInAnyOrder("201","409:SNAPSHOT_REVISION_ALREADY_FROZEN");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM native_schema_snapshot WHERE source_draft_id=?",
                    Integer.class,draftId)).isEqualTo(1);
        }
    }

    @Test void snapshotKeysetPaginationPreservesRevisionOrderAndBadBodyIsRejected()throws Exception {
        Prepared p=prepared();
        String one=frozen(p,2,UUID.randomUUID(),201).path("snapshotId").asText();
        ObjectNode changed=p.doc.deepCopy();
        ((ObjectNode)changed.path("objectTypes").get(0)).put("description","second version");
        call(HttpMethod.PUT,"/drafts/"+p.draft,body(2,changed),200);
        call(HttpMethod.POST,"/drafts/"+p.draft+"/validate",revise(3),200);
        String two=frozen(p,3,UUID.randomUUID(),201).path("snapshotId").asText();
        JsonNode firstPage=call(HttpMethod.GET,"/packages/"+p.pkg+"/snapshots?pageSize=1",null,200);
        assertThat(firstPage.path("items").size()).isEqualTo(1);
        assertThat(firstPage.path("items").get(0).path("snapshotId").asText()).isEqualTo(two);
        String next=firstPage.path("nextCursor").asText();
        assertThat(next).isNotEmpty();
        JsonNode secondPage=call(HttpMethod.GET,"/packages/"+p.pkg+"/snapshots?pageSize=1&cursor="+next,null,200);
        assertThat(secondPage.path("items").size()).isEqualTo(1);
        assertThat(secondPage.path("items").get(0).path("snapshotId").asText()).isEqualTo(one);
        ObjectNode illegal=freezeReq(3,UUID.randomUUID());
        illegal.put("approvedBy","fake-admin");
        assertThat(call(HttpMethod.POST,"/drafts/"+p.draft+"/snapshots",illegal,400)
                .path("code").asText()).isEqualTo("MALFORMED_REQUEST");
    }

    @Test void snapshotCanonicalJcsReferenceDeterminism() {
        String input="{\"b\":2,\"a\":1,\"nested\":{\"z\":true,\"x\":\"hello\"}}";
        String expected="{\"a\":1,\"b\":2,\"nested\":{\"x\":\"hello\",\"z\":true}}";
        assertThat(new String(NativeSnapshotService.canonicalize(input),java.nio.charset.StandardCharsets.UTF_8))
            .isEqualTo(expected);
        assertThat(NativeSnapshotService.sha256(NativeSnapshotService.canonicalize(input)))
            .isEqualTo(NativeSnapshotService.sha256(NativeSnapshotService.canonicalize(expected)));
        // RFC 8785 Section 3, Appendix B: ECMAScript numeric and UTF-16 sorting.
        assertThat(new String(NativeSnapshotService.canonicalize(
           "{\"z\":-0,\"a\":1e30,\"m\":0.000001}"),java.nio.charset.StandardCharsets.UTF_8))
           .isEqualTo("{\"a\":1e+30,\"m\":0.000001,\"z\":0}");
        assertThat(new String(NativeSnapshotService.canonicalize(
           "{\"\\uE000\":2,\"\\uD83D\\uDE00\":1}"),java.nio.charset.StandardCharsets.UTF_8))
           .isEqualTo("{\"😀\":1,\"\":2}");
        String rfc="{\"\\u20ac\":\"Euro Sign\",\"\\r\":\"Carriage Return\",\"1\":\"One\",\"\\u0080\":\"Control\", \"\\ud83d\\ude00\":\"Grinning Face\"}";
        assertThat(new String(NativeSnapshotService.canonicalize(rfc),java.nio.charset.StandardCharsets.UTF_8))
            .contains("Grinning Face");
    }

    /**
     * Fault injection requires the isolated test database table owner. The production
     * trigger is re-enabled in a finally block, even if assertion or update fails.
     * Never use this helper against a user / production database.
     */
    private void injectCorruptionForIsolatedTest(String update, Object... args) {
        jdbc.execute("ALTER TABLE native_schema_snapshot DISABLE TRIGGER native_snapshot_immutable");
        try {
            jdbc.update(update,args);
        } finally {
            jdbc.execute("ALTER TABLE native_schema_snapshot ENABLE TRIGGER native_snapshot_immutable");
        }
    }

    @Test void snapshotCorruptedDigestAndJsonMirrorFailClosedOverHttp() throws Exception {
        Prepared p=prepared();
        UUID key=UUID.randomUUID();
        UUID id=UUID.fromString(frozen(p,2,key,201).path("snapshotId").asText());
        String digest=jdbc.queryForObject(
            "SELECT payload_sha256 FROM native_schema_snapshot WHERE snapshot_id=?",String.class,id);
        String mirror=jdbc.queryForObject(
            "SELECT snapshot_document::text FROM native_schema_snapshot WHERE snapshot_id=?",String.class,id);
        try {
            injectCorruptionForIsolatedTest(
                "UPDATE native_schema_snapshot SET payload_sha256=repeat('0',64) WHERE snapshot_id=?",id);
            assertThat(call(HttpMethod.GET,"/snapshots/"+id,null,500).path("code").asText())
                .isEqualTo("SNAPSHOT_INTEGRITY_FAILURE");
            assertThat(call(HttpMethod.GET,"/drafts/"+p.draft+"/snapshots/by-key/"+key,null,500)
                .path("code").asText()).isEqualTo("SNAPSHOT_INTEGRITY_FAILURE");
            assertThat(call(HttpMethod.GET,"/packages/"+p.pkg+"/snapshots?pageSize=1",null,500)
                .path("code").asText()).isEqualTo("SNAPSHOT_INTEGRITY_FAILURE");
        } finally {
            injectCorruptionForIsolatedTest(
                "UPDATE native_schema_snapshot SET payload_sha256=? WHERE snapshot_id=?",digest,id);
        }
        try {
            injectCorruptionForIsolatedTest(
                "UPDATE native_schema_snapshot SET snapshot_document='{}'::jsonb WHERE snapshot_id=?",id);
            assertThat(call(HttpMethod.GET,"/snapshots/"+id,null,500).path("code").asText())
                .isEqualTo("SNAPSHOT_INTEGRITY_FAILURE");
            assertThat(call(HttpMethod.GET,"/snapshot-diffs?from="+id+"&to="+id,null,500)
                .path("code").asText()).isEqualTo("SNAPSHOT_INTEGRITY_FAILURE");
        } finally {
            injectCorruptionForIsolatedTest(
                "UPDATE native_schema_snapshot SET snapshot_document=?::jsonb WHERE snapshot_id=?",
                mirror,id);
        }
        assertThat(call(HttpMethod.GET,"/snapshots/"+id,null,200).path("payloadSha256").asText())
            .isEqualTo(digest);
    }

    @Test void snapshotDiffEnumSetPolicyRequiredTypeAndPrimaryKeyBoundaries() throws Exception {
        Prepared p=prepared();
        ObjectNode initial=p.doc.deepCopy();
        ObjectNode initialType=(ObjectNode)initial.path("objectTypes").get(0);
        ObjectNode status=prop("status-id","statusId","ENUM",false,true);
        status.putArray("enumValues").add("OPEN").add("CLOSED");
        ((ArrayNode)initialType.path("properties")).add(status);
        call(HttpMethod.PUT,"/drafts/"+p.draft,body(2,initial),200);
        assertThat(call(HttpMethod.POST,"/drafts/"+p.draft+"/validate",revise(3),200)
            .path("status").asText()).isEqualTo("VALID");
        String one=frozen(p,3,UUID.randomUUID(),201).path("snapshotId").asText();

        ObjectNode second=initial.deepCopy();
        ObjectNode secondType=(ObjectNode)second.path("objectTypes").get(0);
        ObjectNode secondStatus=(ObjectNode)secondType.path("properties").get(1);
        secondStatus.putArray("enumValues").add("CLOSED").add("OPEN");
        secondStatus.put("classification","RESTRICTED");
        ((ArrayNode)secondType.path("properties")).add(prop("note-id","noteId","STRING",false,true));
        ((ArrayNode)secondType.path("properties")).add(prop("alternate-id","alternateId","STRING",true,true));
        call(HttpMethod.PUT,"/drafts/"+p.draft,body(3,second),200);
        assertThat(call(HttpMethod.POST,"/drafts/"+p.draft+"/validate",revise(4),200)
            .path("status").asText()).isEqualTo("VALID");
        String two=frozen(p,4,UUID.randomUUID(),201).path("snapshotId").asText();

        String d1=call(HttpMethod.GET,"/snapshot-diffs?from="+one+"&to="+two,null,200)
            .path("changes").toString();
        assertThat(d1).contains("POLICY_REVIEW_REQUIRED","/properties/note-id",
                                 "ADDITIVE","/properties/alternate-id","POTENTIALLY_BREAKING");
        assertThat(d1).doesNotContain("/enumValues"); // Pure ENUM ordering has no semantic change.

        ObjectNode third=second.deepCopy();
        ObjectNode thirdType=(ObjectNode)third.path("objectTypes").get(0);
        thirdType.put("primaryKeyRef","alternate-id");
        ((ObjectNode)thirdType.path("properties").get(0)).put("valueType","INTEGER");
        ObjectNode thirdStatus=(ObjectNode)thirdType.path("properties").get(1);
        thirdStatus.put("required",true);
        thirdStatus.putArray("enumValues").add("CLOSED").add("OPEN").add("PENDING");
        call(HttpMethod.PUT,"/drafts/"+p.draft,body(4,third),200);
        assertThat(call(HttpMethod.POST,"/drafts/"+p.draft+"/validate",revise(5),200)
            .path("status").asText()).isEqualTo("VALID");
        String three=frozen(p,5,UUID.randomUUID(),201).path("snapshotId").asText();
        String d2=call(HttpMethod.GET,"/snapshot-diffs?from="+two+"&to="+three,null,200)
            .path("changes").toString();
        assertThat(d2).contains("/primaryKeyRef","/valueType","/required","/enumValues",
                                "POTENTIALLY_BREAKING");
        assertThat(d2).doesNotContain("POLICY_REVIEW_REQUIRED");
        assertThat(call(HttpMethod.GET,"/snapshots/"+one,null,200)
            .path("sourceRevision").asLong()).isEqualTo(3);
    }

    @Test void snapshotUnsupportedAssetsAreRejectedNotSilentlyDropped() throws Exception {
        Prepared p=prepared();
        ObjectNode withLink=p.doc.deepCopy();
        ObjectNode link=mapper.createObjectNode();
        link.put("id","uses-material");
        ((ArrayNode)withLink.path("linkTypes")).add(link);
        assertThat(call(HttpMethod.PUT,"/drafts/"+p.draft,body(2,withLink),200)
            .path("revision").asLong()).isEqualTo(3);
        assertThat(call(HttpMethod.POST,"/drafts/"+p.draft+"/validate",revise(3),200)
            .path("status").asText()).isEqualTo("INCOMPLETE");
        assertThat(call(HttpMethod.POST,"/drafts/"+p.draft+"/snapshots",
            freezeReq(3,UUID.randomUUID()),409).path("code").asText()).isEqualTo("VALIDATION_NOT_CURRENT");
        ObjectNode withAction=p.doc.deepCopy();
        withAction.putArray("actions");
        assertThat(call(HttpMethod.PUT,"/drafts/"+p.draft,body(3,withAction),422)
            .path("code").asText()).isEqualTo("EDITOR_DOCUMENT_INVALID");
        assertThat(call(HttpMethod.GET,"/drafts/"+p.draft,null,200)
            .path("revision").asLong()).isEqualTo(3);
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM native_schema_snapshot WHERE source_draft_id=?",
            Integer.class,UUID.fromString(p.draft))).isZero();
    }

    private ObjectNode newLink(String id,String api,String source,String target,String cardinality,boolean evidence){
        ObjectNode x=mapper.createObjectNode();
        x.put("id",id);x.put("apiName",api);x.put("sourceTypeRef",source);
        x.put("targetTypeRef",target);x.put("cardinality",cardinality);
        x.put("evidenceRequired",evidence);return x;
    }
    private ObjectNode multiDocument(ObjectNode start){
        ObjectNode doc=start.deepCopy();
        ObjectNode work=obj("work-order","WorkOrder");
        ((ArrayNode)work.get("properties")).add(prop("batch-id","batchId","STRING",true,true));
        ObjectNode order=obj("customer-order","CustomerOrder");
        ((ArrayNode)order.get("properties")).add(prop("batch-id","batchId","STRING",true,true));
        ((ArrayNode)doc.get("objectTypes")).add(work).add(order);
        ((ArrayNode)doc.get("linkTypes"))
            .add(newLink("batch-to-workorder","BatchToWorkOrder","production-batch","work-order","MANY_TO_ONE",true))
            .add(newLink("workorder-to-order","WorkOrderToOrder","work-order","customer-order","MANY_TO_MANY",true));
        return doc;
    }
    @Test void multiObjectLinksPersistValidateFreezeAndDiffWithoutDroppingAssets()throws Exception{
        Prepared p=prepared();
        ObjectNode full=multiDocument(p.doc);
        call(HttpMethod.PUT,"/drafts/"+p.draft,body(2,full),200);
        assertThat(call(HttpMethod.POST,"/drafts/"+p.draft+"/validate",revise(3),200)
            .path("status").asText()).isEqualTo("VALID");
        String first=frozen(p,3,UUID.randomUUID(),201).path("snapshotId").asText();
        JsonNode initial=call(HttpMethod.GET,"/snapshots/"+first,null,200);
        assertThat(initial.path("snapshotContent").path("objectTypes").size()).isEqualTo(3);
        assertThat(initial.path("snapshotContent").path("linkTypes").size()).isEqualTo(2);
        assertThat(initial.path("lifecycle").asText()).isEqualTo("LOCAL_SNAPSHOT_ONLY");
        ObjectNode changed=full.deepCopy();
        ObjectNode relation=(ObjectNode)changed.path("linkTypes").get(0);
        relation.put("cardinality","ONE_TO_ONE");
        relation.put("evidenceRequired",false);
        call(HttpMethod.PUT,"/drafts/"+p.draft,body(3,changed),200);
        assertThat(call(HttpMethod.POST,"/drafts/"+p.draft+"/validate",revise(4),200)
            .path("status").asText()).isEqualTo("VALID");
        String second=frozen(p,4,UUID.randomUUID(),201).path("snapshotId").asText();
        String diff=call(HttpMethod.GET,"/snapshot-diffs?from="+first+"&to="+second,null,200)
            .path("changes").toString();
        assertThat(diff).contains("LINK_TYPE","/linkTypes/batch-to-workorder/cardinality",
            "POTENTIALLY_BREAKING","/linkTypes/batch-to-workorder/evidenceRequired",
            "POLICY_REVIEW_REQUIRED");
        JsonNode original=call(HttpMethod.GET,"/snapshots/"+first,null,200);
        assertThat(original.path("snapshotContent").path("linkTypes").get(0)
            .path("cardinality").asText()).isEqualTo("MANY_TO_ONE");
        assertThat(original.path("payloadSha256").asText()).isEqualTo(initial.path("payloadSha256").asText());
    }
    @Test void incompleteDanglingDuplicateAndUnknownLinksFailClosed()throws Exception{
        Prepared p=prepared();
        ObjectNode full=multiDocument(p.doc);
        ObjectNode broken=(ObjectNode)full.path("linkTypes").get(0);
        broken.remove("targetTypeRef");
        call(HttpMethod.PUT,"/drafts/"+p.draft,body(2,full),200);
        JsonNode incomplete=call(HttpMethod.POST,"/drafts/"+p.draft+"/validate",revise(3),200);
        assertThat(incomplete.path("status").asText()).isEqualTo("INCOMPLETE");
        assertThat(incomplete.path("issues").toString()).contains("LINK_TARGET_REQUIRED");
        assertThat(frozen(p,3,UUID.randomUUID(),409).path("code").asText()).isEqualTo("VALIDATION_NOT_CURRENT");
        broken.put("targetTypeRef","ghost-object");
        ((ArrayNode)full.path("linkTypes")).add(newLink("batch-to-workorder","BatchToWorkOrder",
            "production-batch","work-order","ONE_TO_ONE",true));
        call(HttpMethod.PUT,"/drafts/"+p.draft,body(3,full),200);
        JsonNode invalid=call(HttpMethod.POST,"/drafts/"+p.draft+"/validate",revise(4),200);
        assertThat(invalid.path("status").asText()).isEqualTo("INVALID");
        assertThat(invalid.path("issues").toString()).contains("LINK_TARGET_UNKNOWN","DUPLICATE_LINK_ID","DUPLICATE_LINK_API_NAME");
        ObjectNode polluted=full.deepCopy();
        ((ObjectNode)polluted.path("linkTypes").get(0)).put("fakeAction","deleteFactoryRecord");
        assertThat(call(HttpMethod.PUT,"/drafts/"+p.draft,body(4,polluted),422)
            .path("code").asText()).isEqualTo("EDITOR_DOCUMENT_INVALID");
        assertThat(call(HttpMethod.GET,"/drafts/"+p.draft,null,200)
            .path("editingContent").path("linkTypes").size()).isEqualTo(3);
    }
    @Test void linkIdentityAndPureArrayReorderingHaveStableStructuralDiff()throws Exception{
        Prepared p=prepared();
        ObjectNode full=multiDocument(p.doc);
        call(HttpMethod.PUT,"/drafts/"+p.draft,body(2,full),200);
        call(HttpMethod.POST,"/drafts/"+p.draft+"/validate",revise(3),200);
        String first=frozen(p,3,UUID.randomUUID(),201).path("snapshotId").asText();
        ObjectNode reordered=full.deepCopy();
        ArrayNode links=(ArrayNode)reordered.get("linkTypes");
        JsonNode old=links.get(0).deepCopy();links.remove(0);links.add(old);
        call(HttpMethod.PUT,"/drafts/"+p.draft,body(3,reordered),200);
        call(HttpMethod.POST,"/drafts/"+p.draft+"/validate",revise(4),200);
        String second=frozen(p,4,UUID.randomUUID(),201).path("snapshotId").asText();
        assertThat(call(HttpMethod.GET,"/snapshot-diffs?from="+first+"&to="+second,null,200)
            .path("changes").size()).isZero();
        assertThat(call(HttpMethod.GET,"/snapshots/"+first,null,200)
            .path("snapshotContent").path("linkTypes").size()).isEqualTo(2);
    }

}
