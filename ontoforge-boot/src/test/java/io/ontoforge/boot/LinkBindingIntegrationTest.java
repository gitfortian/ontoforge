package io.ontoforge.boot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.ontoforge.business.model.NativeDraftService;
import io.ontoforge.business.model.NativeSnapshotService;
import java.net.URI;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * M8.1 REAL PostgreSQL + Spring HTTP. Synthetic factory facts; not the
 * static UX prototype and not research/pg-runtime's isolated Python POC.
 */
@org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"mvp-local","integration-test"})
@ContextConfiguration(initializers=IsolatedPostgresTestDatabase.class)
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class LinkBindingIntegrationTest {
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry reg) {
        reg.add("ontoforge.binding.source.url",()->System.getenv("TEST_DB_URL"));
        reg.add("ontoforge.binding.source.username",()->"ontoforge_binding_login");
        reg.add("ontoforge.binding.source.password",()->"m5_test_reader_only");
        reg.add("ontoforge.binding.source.allowed-tables",()->"m5_supplier_source,m8_equipment_source,m8_inspection_lot_source");
    }
    @LocalServerPort int port;
    @Autowired TestRestTemplate http;
    @Autowired ObjectMapper mapper;
    @Autowired NativeDraftService drafts;
    @Autowired NativeSnapshotService snapshots;
    @Autowired @Qualifier("jdbcTemplate") JdbcTemplate jdbc;
    private String url(String p){return "http://127.0.0.1:"+port+p;}

    @BeforeEach
    void provisionReadonlyReader() {
        jdbc.execute("""
                DO $$
                BEGIN
                  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='ontoforge_binding_login') THEN
                    CREATE ROLE ontoforge_binding_login LOGIN PASSWORD 'm5_test_reader_only'
                      NOSUPERUSER NOCREATEDB NOCREATEROLE INHERIT;
                  END IF;
                END $$;
                """);
        jdbc.execute("ALTER ROLE ontoforge_binding_login WITH LOGIN PASSWORD 'm5_test_reader_only' NOSUPERUSER NOCREATEDB NOCREATEROLE INHERIT");
        jdbc.execute("GRANT ontoforge_binding_reader TO ontoforge_binding_login");
        // PG17 role-membership INHERIT is captured when membership is granted. 
        // Never provision this fixture login as NOINHERIT: it would revoke the 
        // effective SELECT grants expected by the existing M5–M7 source suite.
    }
    private ResponseEntity<String> post(String path,ObjectNode body){
        var headers=new HttpHeaders();headers.setContentType(MediaType.APPLICATION_JSON);
        return http.postForEntity(URI.create(url(path)),new HttpEntity<>(body.toString(),headers),String.class);
    }
    private ResponseEntity<String> put(String path,ObjectNode body){
        var headers=new HttpHeaders();headers.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange(URI.create(url(path)),HttpMethod.PUT,
                new HttpEntity<>(body.toString(),headers),String.class);
    }
    private ObjectNode property(ArrayNode values,String id,String name,String type) {
        ObjectNode p=values.addObject();
        p.put("id",id);p.put("apiName",name);p.put("valueType",type);
        p.put("required",true);p.put("searchable",true);p.put("classification","INTERNAL");
        return p;
    }
    private static void mapping(ArrayNode a,String prop,String column){
        a.addObject().put("propertyRef",prop).put("sourceColumn",column).put("authority","AUTHORITATIVE");
    }
    private ObjectNode m5Request(String snapshot,String type,String table,String pk,String... mappings) {
        ObjectNode b=mapper.createObjectNode();
        b.put("snapshotId",snapshot);b.put("objectTypeId",type);
        b.put("sourceId","local-pg-sandbox");b.put("schema","public");b.put("table",table);
        b.put("sourceKey",pk);b.put("expectedRevision",0);
        b.putNull("expectedSchemaFingerprint");
        ArrayNode a=b.putArray("fieldMappings");
        for(int i=0;i<mappings.length;i+=2)mapping(a,mappings[i],mappings[i+1]);
        return b;
    }
    private record Setup(String packageId,String snapshot,String sourceId,long sourceRev,String sourceDigest,
                         String targetId,long targetRev,String targetDigest,String sourceFp,String targetFp,
                         String sourceDomain,String targetDomain) {}
    private Setup setup() throws Exception {
        String pkg="manufacturing.m8t"+UUID.randomUUID().toString().replace("-","");
        var draft=drafts.create(pkg);
        ObjectNode editor=mapper.createObjectNode();editor.put("editorSchemaVersion","0.1.0");
        ArrayNode types=editor.putArray("objectTypes");
        ObjectNode eq=types.addObject();eq.put("id","equipment");eq.put("apiName","Equipment");
        eq.put("primaryKeyRef","equipment-code");
        ArrayNode ep=eq.putArray("properties");
        property(ep,"equipment-code","equipmentCode","STRING");
        property(ep,"equipment-name","equipmentName","STRING");
        property(ep,"equipment-plant","equipmentPlant","STRING");
        ObjectNode lot=types.addObject();lot.put("id","inspection-lot");lot.put("apiName","InspectionLot");
        lot.put("primaryKeyRef","lot-code");
        ArrayNode lp=lot.putArray("properties");
        property(lp,"lot-code","lotCode","STRING");
        property(lp,"lot-equipment","equipmentCode","STRING").put("required",false);
        property(lp,"lot-status","lotStatus","STRING");
        ObjectNode link=editor.putArray("linkTypes").addObject();
        link.put("id","equipment-inspections");link.put("apiName","equipmentInspections");
        link.put("sourceTypeRef","equipment");link.put("targetTypeRef","inspection-lot");
        link.put("cardinality","ONE_TO_MANY");link.put("evidenceRequired",true);
        var saved=drafts.save(UUID.fromString(draft.draftId()),draft.revision(),editor);
        assertThat(drafts.validate(UUID.fromString(draft.draftId()),saved.revision()).status()).isEqualTo("VALID");
        String sid=snapshots.freeze(UUID.fromString(draft.draftId()),saved.revision(),UUID.randomUUID()).snapshot().snapshotId();
        ObjectNode a=m5Request(sid,"equipment","m8_equipment_source","equipment_code",
                "equipment-code","equipment_code","equipment-name","equipment_name",
                "equipment-plant","plant");
        ObjectNode b=m5Request(sid,"inspection-lot","m8_inspection_lot_source","lot_code",
                "lot-code","lot_code","lot-equipment","equipment_code","lot-status","status");
        JsonNode p1=mapper.readTree(post("/api/runtime/bindings/preview",a).getBody());
        JsonNode p2=mapper.readTree(post("/api/runtime/bindings/preview",b).getBody());
        assertThat(p1.path("source").path("schemaFingerprint").asText()).hasSize(64);
        assertThat(p2.path("source").path("schemaFingerprint").asText()).hasSize(64);
        String sf=p1.path("source").path("schemaFingerprint").asText();
        String tf=p2.path("source").path("schemaFingerprint").asText();
        a.put("expectedSchemaFingerprint",sf);b.put("expectedSchemaFingerprint",tf);
        var sa=post("/api/runtime/bindings",a);
        var sb=post("/api/runtime/bindings",b);
        assertThat(sa.getStatusCode().value()).as(sa.getBody()).isEqualTo(201);
        assertThat(sb.getStatusCode().value()).as(sb.getBody()).isEqualTo(201);
        JsonNode s=mapper.readTree(sa.getBody()),t=mapper.readTree(sb.getBody());
        return new Setup(pkg,sid,s.path("bindingId").asText(),s.path("revision").asLong(),
                s.path("digest").asText(),t.path("bindingId").asText(),t.path("revision").asLong(),
                t.path("digest").asText(),sf,tf,s.path("identityDomainId").asText(),t.path("identityDomainId").asText());
    }
    private ObjectNode request(Setup s){
        ObjectNode b=mapper.createObjectNode();b.put("contractVersion","local-link-binding-v1");
        b.putObject("modelSourceRef").put("kind","LOCAL_SNAPSHOT").put("snapshotId",s.snapshot);
        b.put("packageId",s.packageId);b.put("linkTypeId","equipment-inspections");
        b.putObject("sourceBindingRef").put("bindingId",s.sourceId).put("revision",s.sourceRev).put("digest",s.sourceDigest);
        b.putObject("targetBindingRef").put("bindingId",s.targetId).put("revision",s.targetRev).put("digest",s.targetDigest);
        b.put("mode","FOREIGN_KEY");b.put("fkOwner","TARGET");b.put("fkSourceColumn","equipment_code");
        b.put("expectedRevision",0);b.put("idempotencyKey",UUID.randomUUID().toString());
        b.putObject("expectedSchemaFingerprints").put("source",s.sourceFp).put("target",s.targetFp);
        b.putObject("validationScope").put("kind","VALIDATION_SCOPE").put("coverage","FULL");
        return b;
    }
    private ObjectNode query(Setup s,String linkId,long revision,String direction,String key,int pageSize){
        ObjectNode json=mapper.createObjectNode();json.put("queryVersion","local-query-v2");
        boolean out="OUT".equals(direction);
        String type=out?"equipment":"inspection-lot";
        String binding=out?s.sourceId:s.targetId;
        long bindRev=out?s.sourceRev:s.targetRev;
        String domain=out?s.sourceDomain:s.targetDomain;
        ObjectNode typeRef=json.putObject("rootTypeRef");
        typeRef.putObject("sourceRef").put("kind","LOCAL_SNAPSHOT").put("snapshotId",s.snapshot);
        typeRef.put("packageId",s.packageId);typeRef.put("objectTypeId",type);
        json.putObject("rootBindingRef").put("bindingId",binding).put("revision",bindRev);
        ObjectNode selector=json.putObject("rootSelector");selector.put("kind","OBJECT_REF");
        ObjectNode object=selector.putObject("objectRef");object.put("packageId",s.packageId);
        object.put("objectTypeId",type);object.put("identityDomainId",domain);
        object.putObject("key").put("valueType","STRING").put("value",key);
        ArrayNode steps=json.putArray("steps");
        steps.addObject().put("kind","TRAVERSE").put("linkTypeRef","equipment-inspections")
                .putObject("linkBindingRef").put("bindingId",linkId).put("revision",revision);
        ObjectNode traverse=(ObjectNode)steps.get(0);
        traverse.put("direction",direction);traverse.put("maxDepth",1);
        steps.addObject().put("kind","PAGE").put("pageSize",pageSize);
        return json;
    }

    @Test void customTypesCanFullValidateSaveRecoverUpdateAndTraverseBothDirections() throws Exception {
        Setup s=setup();
        assertThat(s.sourceDomain).isNotEqualTo(s.targetDomain);
        ObjectNode b=request(s);
        ResponseEntity<String> vr=post("/api/runtime/link-bindings/validate",b);
        assertThat(vr.getStatusCode().value()).as(vr.getBody()).isEqualTo(200);
        JsonNode validation=mapper.readTree(vr.getBody());
        assertThat(validation.path("status").asText()).isEqualTo("VALID_WITH_ORPHANS");
        assertThat(validation.path("checkedRows").asInt()).isEqualTo(8);
        assertThat(validation.path("orphanCount").asInt()).isEqualTo(1);
        assertThat(validation.path("nullFkCount").asInt()).isEqualTo(1);
        ResponseEntity<String> saved=post("/api/runtime/link-bindings",b);
        assertThat(saved.getStatusCode().value()).as(saved.getBody()).isEqualTo(201);
        JsonNode detail=mapper.readTree(saved.getBody());
        String id=detail.path("linkBindingId").asText();
        assertThat(detail.path("revision").asLong()).isEqualTo(1);
        assertThat(detail.path("validationStatus").asText()).isEqualTo("VALID_WITH_ORPHANS");
        var recovered=post("/api/runtime/link-bindings",b);
        assertThat(recovered.getStatusCode().value()).as(recovered.getBody()).isEqualTo(201);
        assertThat(mapper.readTree(recovered.getBody()).path("digest")).isEqualTo(detail.path("digest"));
        ObjectNode collision=b.deepCopy();collision.put("fkSourceColumn","status");
        assertThat(post("/api/runtime/link-bindings",collision).getStatusCode().value()).isEqualTo(409);

        ObjectNode out=query(s,id,1,"OUT","EQ-001",1);
        ResponseEntity<String> outResp=post("/api/runtime/object-sets/traverse",out);
        assertThat(outResp.getStatusCode().value()).as(outResp.getBody()).isEqualTo(200);
        JsonNode data=mapper.readTree(outResp.getBody());
        assertThat(data.path("status").asText()).isEqualTo("SUCCESS");
        assertThat(data.path("objectCount").asInt()).isEqualTo(2);
        assertThat(data.path("linkCount").asInt()).isEqualTo(2);
        assertThat(data.path("objects").size()).isEqualTo(1);
        assertThat(data.path("sourceReadRefs").size()).isEqualTo(2);
        assertThat(data.path("bindingVersions").size()).isEqualTo(3);
        String cursor=data.path("nextCursor").asText();
        assertThat(cursor).startsWith("cursor:v2:");
        ObjectNode paging=mapper.createObjectNode().put("cursor",cursor);
        var page=post("/api/runtime/object-sets/traverse/pages",paging);
        assertThat(page.getStatusCode().value()).as(page.getBody()).isEqualTo(200);
        JsonNode second=mapper.readTree(page.getBody());
        assertThat(second.path("pageNumber").asInt()).isEqualTo(2);
        assertThat(second.path("objects").get(0).path("objectRef").path("key").path("value").asText())
                .isIn("LOT-11","LOT-12");
        ObjectNode in=query(s,id,1,"IN","LOT-11",25);
        JsonNode back=mapper.readTree(post("/api/runtime/object-sets/traverse",in).getBody());
        assertThat(back.path("objectCount").asInt()).isEqualTo(1);
        assertThat(back.path("objects").get(0).path("objectRef").path("key").path("value").asText()).isEqualTo("EQ-001");
        JsonNode nullFk=mapper.readTree(post("/api/runtime/object-sets/traverse",query(s,id,1,"IN","LOT-13",25)).getBody());
        assertThat(nullFk.path("status").asText()).isEqualTo("SUCCESS");
        assertThat(nullFk.path("quality").path("completeness").asText()).isEqualTo("COMPLETE");
        assertThat(nullFk.path("objectCount").asInt()).isZero();
        JsonNode orphan=mapper.readTree(post("/api/runtime/object-sets/traverse",query(s,id,1,"IN","LOT-14",25)).getBody());
        assertThat(orphan.path("status").asText()).isEqualTo("SUCCESS");
        assertThat(orphan.path("quality").path("completeness").asText()).isEqualTo("PARTIAL");
        assertThat(orphan.path("quality").path("answerScope").asText()).isEqualTo("KNOWN_SUBSET");

        b.put("expectedRevision",1);b.put("idempotencyKey",UUID.randomUUID().toString());
        ResponseEntity<String> updated=put("/api/runtime/link-bindings/"+id,b);
        assertThat(updated.getStatusCode().value()).as(updated.getBody()).isEqualTo(200);
        assertThat(mapper.readTree(updated.getBody()).path("revision").asInt()).isEqualTo(2);
        JsonNode replay=mapper.readTree(put("/api/runtime/link-bindings/"+id,b).getBody());
        assertThat(replay.path("revision").asInt()).isEqualTo(2);
        b.put("idempotencyKey",UUID.randomUUID().toString());
        assertThat(put("/api/runtime/link-bindings/"+id,b).getStatusCode().value()).isEqualTo(409);
    }
}
