package io.ontoforge.boot;

import io.ontoforge.core.compiler.DraftValidation;
import io.ontoforge.core.compiler.NativePackageSchema;
import io.ontoforge.business.model.NativeDraftService;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import static org.assertj.core.api.Assertions.assertThat;

/** Actual HTTP on random Spring Boot port AND CI PostgreSQL 17, not mocks/in-memory database. */
@org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"test", "integration-test"})
@Import(B0TestSecurityConfiguration.class)
@org.springframework.test.context.ContextConfiguration(initializers=IsolatedPostgresTestDatabase.class)
class NativeDraftTest {
    private static final UUID WORKSPACE = UUID.fromString("11111111-1111-4111-8111-111111111111");
    @LocalServerPort int port;
    @Autowired TestRestTemplate http;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtEncoder jwtEncoder;

    @BeforeEach
    void seedB0Access() {
        jdbc.update("INSERT INTO b0_workspace(workspace_id,workspace_key,display_name) VALUES (?,?,?) ON CONFLICT DO NOTHING",
                WORKSPACE,"test-manufacturing","B0 Test Manufacturing");
        jdbc.update("INSERT INTO b0_principal(principal_id,issuer,subject,display_name) VALUES (?,?,?,?) ON CONFLICT (issuer,subject) DO NOTHING",
                UUID.fromString("22222222-2222-4222-8222-222222222222"),
                B0TestSecurityConfiguration.ISSUER,"editor-A","Test Editor");
        jdbc.update("INSERT INTO b0_workspace_membership(workspace_id,principal_id,role) VALUES (?,?,?) ON CONFLICT DO NOTHING",
                WORKSPACE,UUID.fromString("22222222-2222-4222-8222-222222222222"),"EDITOR");
    }

    @Test void saveResponseRetainsOwnRevisionWhenAnotherWriterCommitsImmediatelyAfter() throws Exception {
        UUID id=UUID.fromString(create("manufacturing.p"+UUID.randomUUID().toString().replace("-",""))
                .path("draftId").asText());
        ObjectNode own=editor();own.withArray("objectTypes").add(obj("batch","Batch"));
        ObjectNode rival=own.deepCopy();((ObjectNode)rival.path("objectTypes").get(0)).put("description","rival");
        var service=new io.ontoforge.business.model.NativeDraftService(new io.ontoforge.dao.model.NativeDraftRepository(jdbc),mapper);
        var injected=new java.util.concurrent.atomic.AtomicBoolean();
        // Force a second committed write exactly after the first SQL mutation,
        // before the first service call can perform any follow-up read.
        JdbcTemplate interleaved=new JdbcTemplate(java.util.Objects.requireNonNull(jdbc.getDataSource())) {
            private void afterWrite(String sql) {
                if(sql.stripLeading().startsWith("UPDATE native_package_draft") && injected.compareAndSet(false,true))
                    service.save(id,2,rival);
            }
            @Override public int update(String sql,Object... args) {
                int count=super.update(sql,args);afterWrite(sql);return count;
            }
            @Override public <T> java.util.List<T> query(String sql,org.springframework.jdbc.core.RowMapper<T> rowMapper,Object... args) {
                var rows=super.query(sql,rowMapper,args);afterWrite(sql);return rows;
            }
        };
        var response=new io.ontoforge.business.model.NativeDraftService(new io.ontoforge.dao.model.NativeDraftRepository(interleaved),mapper).save(id,1,own);
        assertThat(injected).isTrue();
        assertThat(response.revision()).isEqualTo(2);
        assertThat(response.editingContent()).isEqualTo(own);
        assertThat(service.get(id).revision()).isEqualTo(3);
        assertThat(service.get(id).editingContent()).isEqualTo(rival);
    }
    private String url(String suffix) {return "http://127.0.0.1:"+port+"/api/model/drafts"+suffix;}
    private JsonNode call(HttpMethod method,String path,ObjectNode body,int expected) throws Exception {
        HttpHeaders headers=new HttpHeaders();headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(B0TestSecurityConfiguration.token(jwtEncoder,"editor-A"));
        HttpEntity<String> entity=new HttpEntity<>(body==null?null:mapper.writeValueAsString(body),headers);
        ResponseEntity<String> response=http.exchange(URI.create(url(path)),method,entity,String.class);
        assertThat(response.getStatusCode().value()).describedAs(response.getBody()).isEqualTo(expected);
        return mapper.readTree(response.getBody());
    }
    private ObjectNode req(String key,String value) {ObjectNode node=mapper.createObjectNode();node.put(key,value);return node;}
    private JsonNode create(String packageId) throws Exception {
        jdbc.update("INSERT INTO b0_package_assignment(workspace_id,package_id) VALUES (?,?) ON CONFLICT DO NOTHING",
                WORKSPACE,packageId);
        return call(HttpMethod.POST,"",req("packageId",packageId),201);
    }
    private ObjectNode editor() {
        ObjectNode node=mapper.createObjectNode();node.put("editorSchemaVersion","0.1.0");
        node.putArray("objectTypes");node.putArray("linkTypes");return node;
    }
    private ObjectNode obj(String id,String api) {
        ObjectNode o=mapper.createObjectNode();o.put("id",id);o.put("apiName",api);
        o.put("label","生产批次");o.putNull("primaryKeyRef");o.putArray("properties");return o;
    }
    private ObjectNode property(String id,String api,String type,boolean required) {
        ObjectNode p=mapper.createObjectNode();p.put("id",id);p.put("apiName",api);
        p.put("valueType",type);p.put("required",required);p.put("searchable",false);
        p.put("classification","INTERNAL");return p;
    }
    private ObjectNode revision(long n) {ObjectNode o=mapper.createObjectNode();o.put("expectedRevision",n);return o;}
    private ObjectNode saveBody(long n,JsonNode editor) {ObjectNode o=revision(n);o.set("editingContent",editor);return o;}
    private String freshName() {return "manufacturing.p"+UUID.randomUUID().toString().replace("-","");}

    @Test void nativeDraftRealPostgresCreateWipSaveReloadValidateAndInvalidate() throws Exception {
        String installationBefore=jdbc.queryForObject("SELECT installation_id::text FROM platform_installation WHERE id=1",String.class);
        String pkg=freshName();JsonNode made=create(pkg);
        String id=made.path("draftId").asText();
        assertThat(made.path("revision").asLong()).isEqualTo(1);
        assertThat(made.path("editingContent").path("objectTypes").size()).isZero();
        ObjectNode wip=editor();ObjectNode type=obj("production-batch","ProductionBatch");
        ((ArrayNode)wip.get("objectTypes")).add(type);
        JsonNode updated=call(HttpMethod.PUT,"/"+id,saveBody(1,wip),200);
        assertThat(updated.path("revision").asLong()).isEqualTo(2);
        JsonNode reopened=call(HttpMethod.GET,"/"+id,null,200);
        assertThat(reopened.path("editingContent").path("objectTypes").get(0).path("apiName").asText()).isEqualTo("ProductionBatch");
        JsonNode incomplete=call(HttpMethod.POST,"/"+id+"/validate",revision(2),200);
        assertThat(incomplete.path("status").asText()).isEqualTo("INCOMPLETE");
        assertThat(incomplete.path("issues").size()).isGreaterThan(0);

        ((ArrayNode)type.get("properties")).add(property("batch-id","batchId","STRING",true));
        type.put("primaryKeyRef","batch-id");
        JsonNode complete=call(HttpMethod.PUT,"/"+id,saveBody(2,wip),200);
        assertThat(complete.path("revision").asLong()).isEqualTo(3);
        assertThat(complete.path("validation").path("status").asText()).isEqualTo("NOT_RUN");
        JsonNode valid=call(HttpMethod.POST,"/"+id+"/validate",revision(3),200);
        assertThat(valid.path("status").asText()).isEqualTo("VALID");
        assertThat(valid.path("validatedRevision").asLong()).isEqualTo(3);
        assertThat(call(HttpMethod.GET,"/"+id,null,200).path("validation").path("status").asText()).isEqualTo("VALID");
        type.put("description","更新描述");
        JsonNode afterChange=call(HttpMethod.PUT,"/"+id,saveBody(3,wip),200);
        assertThat(afterChange.path("validation").path("status").asText()).isEqualTo("NOT_RUN");
        assertThat(afterChange.path("revision").asLong()).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT revision FROM native_package_draft WHERE draft_id=?::uuid",Long.class,id)).isEqualTo(4L);
        assertThat(jdbc.queryForObject("SELECT installation_id::text FROM platform_installation WHERE id=1",String.class)).isEqualTo(installationBefore);
    }

    @Test void nativeDraftConcurrentSaveDuplicatePackageAndReadFailures() throws Exception {
        String pkg=freshName(),id=create(pkg).path("draftId").asText();
        call(HttpMethod.POST,"",req("packageId",pkg),409);
        ObjectNode editor=editor();
        call(HttpMethod.PUT,"/"+id,saveBody(1,editor),200);
        JsonNode conflict=call(HttpMethod.PUT,"/"+id,saveBody(1,editor),409);
        assertThat(conflict.path("code").asText()).isEqualTo("DRAFT_REVISION_CONFLICT");
        call(HttpMethod.POST,"/"+id+"/validate",revision(1),409);
        assertThat(call(HttpMethod.GET,"/"+UUID.randomUUID(),null,404).path("code").asText()).isEqualTo("DRAFT_NOT_FOUND");
        assertThat(call(HttpMethod.GET,"?pageSize=100",null,400).path("code").asText()).isEqualTo("MALFORMED_REQUEST");
        assertThat(call(HttpMethod.GET,"?pageSize=1",null,200).path("items").size()).isEqualTo(1);
        String invalidTimestampCursor=java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                "not-a-time|00000000-0000-0000-0000-000000000000".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        JsonNode invalidTimestamp=call(HttpMethod.GET,"?cursor="+invalidTimestampCursor,null,400);
        assertThat(invalidTimestamp.path("code").asText()).isEqualTo("MALFORMED_REQUEST");
    }

    @Test void nativeDraftRejectsUnsafeEditorAndInvalidNativeProperties() throws Exception {
        String id=create(freshName()).path("draftId").asText();
        ObjectNode bad=editor();bad.put("actions","malicious");
        JsonNode unsafe=call(HttpMethod.PUT,"/"+id,saveBody(1,bad),422);
        assertThat(unsafe.path("code").asText()).isEqualTo("EDITOR_DOCUMENT_INVALID");
        ObjectNode wip=editor();ObjectNode type=obj("production-batch","ProductionBatch");
        ArrayNode props=(ArrayNode)type.get("properties");
        props.add(property("batch-id","batchId","DECIMAL",false));
        type.put("primaryKeyRef","batch-id");
        ((ArrayNode)wip.get("objectTypes")).add(type);
        call(HttpMethod.PUT,"/"+id,saveBody(1,wip),200);
        JsonNode wrongPk=call(HttpMethod.POST,"/"+id+"/validate",revision(2),200);
        assertThat(wrongPk.path("status").asText()).isEqualTo("INVALID");
        assertThat(wrongPk.path("issues").toString()).contains("PK_TYPE","PK_REQUIRED");
        props.removeAll();
        props.add(property("batch-id","batchId","STRING",true));
        props.add(property("batch-id","batchIdDuplicate","STRING",true));
        call(HttpMethod.PUT,"/"+id,saveBody(2,wip),200);
        JsonNode duplicate=call(HttpMethod.POST,"/"+id+"/validate",revision(3),200);
        assertThat(duplicate.path("status").asText()).isEqualTo("INVALID");
        assertThat(duplicate.path("issues").toString()).contains("DUPLICATE_ID");

        ObjectNode badType=editor();ObjectNode o=obj("production-batch","ProductionBatch");
        ((ArrayNode)o.get("properties")).add(property("batch-id","batchId","STRING",true));
        o.put("primaryKeyRef","batch-id");
        ((ArrayNode)badType.get("objectTypes")).add(o);
        ObjectNode second=obj("material-lot","MaterialLot");
        ((ArrayNode)second.get("properties")).add(property("batch-id","batchId","STRING",true));
        second.put("primaryKeyRef","batch-id");
        ((ArrayNode)badType.get("objectTypes")).add(second);
        call(HttpMethod.PUT,"/"+id,saveBody(3,badType),200);
        // Type-scoped property IDs: same Property.id in DIFFERENT ObjectTypes is valid.
        JsonNode cross=call(HttpMethod.POST,"/"+id+"/validate",revision(4),200);
        assertThat(cross.path("status").asText()).isEqualTo("VALID");
        // P2: nullable WIP-only display labels must be omitted from the
        // normalized V0.2 candidate, never accepted as schema-invalid null.
        ObjectNode nullable=editor();
        ObjectNode nullableType=obj("production-batch","ProductionBatch");
        nullableType.putNull("label");
        nullableType.putNull("description");
        ObjectNode nullableProperty=property("batch-id","batchId","STRING",true);
        nullableProperty.putNull("label");
        ((ArrayNode)nullableType.get("properties")).add(nullableProperty);
        nullableType.put("primaryKeyRef","batch-id");
        ((ArrayNode)nullable.get("objectTypes")).add(nullableType);
        call(HttpMethod.PUT,"/"+id,saveBody(4,nullable),200);
        JsonNode nullLabels=call(HttpMethod.POST,"/"+id+"/validate",revision(5),200);
        assertThat(nullLabels.path("status").asText()).isEqualTo("VALID");
        var result=io.ontoforge.core.compiler.DraftValidation.validate(
                "manufacturing.production",nullable,mapper);
        assertThat(result.nativeCandidate()).isNotNull();
        assertThat(result.nativeCandidate().path("objectTypes").get(0).has("label")).isFalse();
        assertThat(result.nativeCandidate().path("objectTypes").get(0).has("description")).isFalse();
        assertThat(result.nativeCandidate().path("objectTypes").get(0).path("properties").get(0).has("label")).isFalse();
        assertThat(io.ontoforge.core.compiler.NativePackageSchema.validate(result.nativeCandidate())).isEmpty();
    }
}
