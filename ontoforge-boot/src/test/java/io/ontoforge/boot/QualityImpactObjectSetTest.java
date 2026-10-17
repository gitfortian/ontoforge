package io.ontoforge.boot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.ontoforge.boot.model.NativeDraftService;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import java.net.URI;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real PostgreSQL 17 + Spring Boot HTTP integration.  The duplicate physical
 * rows are relationship facts; the resulting ObjectSet-like paths must dedupe.
 */
@org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"mvp-local","integration-test"})
@ContextConfiguration(initializers=IsolatedPostgresTestDatabase.class)
class QualityImpactObjectSetTest {
    @LocalServerPort int port;
    @Autowired TestRestTemplate http;
    @Autowired ObjectMapper mapper;
    @Autowired NativeDraftService drafts;
    @Autowired JdbcTemplate jdbc;

    private static final String SOURCE_MAP="""
        {"batchTable":"demo_impact_lots","batchId":"lot_no",
         "batchStatus":"outcome","batchWorkOrder":"job_ref",
         "workTable":"demo_impact_jobs","workId":"job_no","workOrder":"sales_ref",
         "orderTable":"demo_impact_orders","orderId":"sales_no","orderName":"buyer"}
        """;
    private String base(String path){return "http://127.0.0.1:"+port+"/api/mvp/quality"+path;}
    private ResponseEntity<String> request(String method,String path,String body) {
        if("GET".equals(method))return http.getForEntity(URI.create(base(path)),String.class);
        HttpHeaders headers=new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return http.postForEntity(URI.create(base(path)),
            new HttpEntity<>(body,headers),String.class);
    }
    private JsonNode json(ResponseEntity<String> res,int expected) throws Exception {
        assertThat(res.getStatusCode().value()).describedAs(res.getBody()).isEqualTo(expected);
        return mapper.readTree(res.getBody());
    }
    private void ready() throws Exception {
        JsonNode created=json(request("POST","/model","{}"),200);
        String draftId=created.path("draftId").asText();
        long revision=created.path("revision").asLong();
        HttpHeaders headers=new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> check=http.postForEntity(
            URI.create("http://127.0.0.1:"+port+"/api/model/drafts/"+draftId+"/validate"),
            new HttpEntity<>("{\"expectedRevision\":"+revision+"}",headers),String.class);
        assertThat(check.getStatusCode().value()).describedAs(check.getBody()).isEqualTo(200);
        assertThat(mapper.readTree(check.getBody()).path("status").asText()).isEqualTo("VALID");
        json(request("POST","/bind",SOURCE_MAP),200);
    }
    @Test void allImpactedOrdersPaginationDistinctAndProvenance() throws Exception {
        ready();
        JsonNode page1=json(request("GET","/trace-set?batchId=LOT-900&pageSize=2",null),200);
        assertThat(page1.path("qualityStatus").asText()).isEqualTo("BLOCKED");
        assertThat(page1.path("workOrderCount").asLong()).isEqualTo(2);
        assertThat(page1.path("orderCount").asLong()).isEqualTo(3);
        assertThat(page1.path("pathCount").asLong()).isEqualTo(4);
        assertThat(page1.path("paths").size()).isEqualTo(2);
        assertThat(page1.path("paths").get(0).path("workOrderId").asText()).isEqualTo("JOB-70");
        assertThat(page1.path("paths").get(0).path("orderId").asText()).isEqualTo("SO-91");
        assertThat(page1.path("paths").get(1).path("orderId").asText()).isEqualTo("SO-92");
        assertThat(page1.path("paths").get(0).path("links").get(0)
            .path("sourceColumn").asText()).isEqualTo("demo_impact_lots.job_ref");
        assertThat(page1.path("paths").get(0).path("links").get(1)
            .path("sourceColumn").asText()).isEqualTo("demo_impact_jobs.sales_ref");
        assertThat(page1.path("provenance").asText()).isEqualTo("LOCAL_TEST_POSTGRES");
        assertThat(page1.path("boundRevision").asLong()).isPositive();
        assertThat(page1.path("sourceFingerprint").asText()).hasSize(64);
        String cursor=page1.path("nextCursor").asText();
        assertThat(cursor).isNotBlank();
        JsonNode page2=json(request("GET","/trace-set?batchId=LOT-900&pageSize=2&cursor="+cursor,null),200);
        assertThat(page2.path("paths").size()).isEqualTo(2);
        assertThat(page2.path("paths").get(0).path("workOrderId").asText()).isEqualTo("JOB-71");
        assertThat(page2.path("paths").get(0).path("orderId").asText()).isEqualTo("SO-92");
        assertThat(page2.path("paths").get(1).path("orderId").asText()).isEqualTo("SO-93");
        assertThat(page2.path("nextCursor").isNull()).isTrue();
        assertThat(page2.path("pathCount").asLong()).isEqualTo(4);
        assertThat(json(request("GET","/trace-set?batchId=LOT-900&pageSize=2",null),200))
           .isEqualTo(page1);
        assertThat(json(request("GET","/trace-set?batchId=LOT-901&pageSize=2&cursor="+cursor,null),400)
            .path("code").asText()).isEqualTo("INVALID_TRACE_CURSOR");
        assertThat(json(request("GET","/trace-set?batchId=LOT-900&pageSize=0",null),400)
            .path("code").asText()).isEqualTo("INVALID_PAGE_SIZE");
        assertThat(json(request("GET","/trace-set?batchId=LOT-900&pageSize=51",null),400)
            .path("code").asText()).isEqualTo("INVALID_PAGE_SIZE");
        assertThat(json(request("GET","/trace-set?batchId=LOT-900&cursor=not-json",null),400)
            .path("code").asText()).isEqualTo("INVALID_TRACE_CURSOR");
        // Compatibility: old single-path API must not truncate multiple paths.
        assertThat(json(request("GET","/trace?batchId=LOT-900",null),409)
            .path("code").asText()).isEqualTo("AMBIGUOUS_RELATIONSHIP");
    }
    private void reviseLink(UUID draftId,String linkId,String cardinality) throws Exception {
        var existing=drafts.get(draftId);
        ObjectNode editor=(ObjectNode)existing.editingContent().deepCopy();
        for(JsonNode link:editor.path("linkTypes"))
            if(linkId.equals(link.path("id").asText()))
                ((ObjectNode)link).put("cardinality",cardinality);
        var saved=drafts.save(draftId,existing.revision(),editor);
        assertThat(drafts.validate(draftId,saved.revision()).status()).isEqualTo("VALID");
        json(request("POST","/bind",SOURCE_MAP),200);
    }

    @Test void nativeCardinalityMustPermitOneBatchAndWorkWithManyLinks() throws Exception {
        ready();
        UUID draftId=jdbc.queryForObject(
            "SELECT draft_id FROM native_package_draft WHERE package_id='manufacturing.quality'",
            UUID.class);
        try {
            reviseLink(draftId,"batch-workorder","MANY_TO_ONE");
            assertThat(json(request("GET","/trace-set?batchId=LOT-900",null),409)
                .path("code").asText()).isEqualTo("ONTOLOGY_CARDINALITY_MISMATCH");
            reviseLink(draftId,"batch-workorder","MANY_TO_MANY");
            reviseLink(draftId,"workorder-order","MANY_TO_ONE");
            assertThat(json(request("GET","/trace-set?batchId=LOT-900",null),409)
                .path("code").asText()).isEqualTo("ONTOLOGY_CARDINALITY_MISMATCH");
        } finally {
            reviseLink(draftId,"batch-workorder","MANY_TO_MANY");
            reviseLink(draftId,"workorder-order","MANY_TO_MANY");
        }
        assertThat(json(request("GET","/trace-set?batchId=LOT-900",null),200)
            .path("pathCount").asLong()).isEqualTo(4);
    }


    @Test void typedObjectSetFiltersCountsDuplicatesAndPaginates() throws Exception {
        ready();
        JsonNode page1=json(request("GET",
            "/object-set?objectTypeId=customer-order&filterField=id&operator=prefix&pageSize=2",null),200);
        assertThat(page1.path("totalCount").asLong()).isEqualTo(3);
        assertThat(page1.path("objects").size()).isEqualTo(2);
        assertThat(page1.path("objects").get(0).path("id").asText()).isEqualTo("SO-91");
        assertThat(page1.path("objects").get(0).path("attributeName").asText()).isEqualTo("customerName");
        assertThat(page1.path("objects").get(0).path("attributeSourceColumn").asText())
            .isEqualTo("demo_impact_orders.buyer");
        assertThat(page1.path("objects").get(1).path("id").asText()).isEqualTo("SO-92");
        String cursor=page1.path("nextCursor").asText();
        assertThat(cursor).isNotBlank();
        JsonNode page2=json(request("GET",
            "/object-set?objectTypeId=customer-order&filterField=id&operator=prefix&pageSize=2&cursor="+cursor,null),200);
        assertThat(page2.path("totalCount").asLong()).isEqualTo(3);
        assertThat(page2.path("objects").size()).isEqualTo(1);
        assertThat(page2.path("objects").get(0).path("id").asText()).isEqualTo("SO-93");
        assertThat(page2.path("nextCursor").isNull()).isTrue();
        assertThat(json(request("GET",
            "/object-set?objectTypeId=customer-order&filterField=id&operator=prefix&pageSize=2",null),200))
            .isEqualTo(page1);

        JsonNode customer=json(request("GET",
            "/object-set?objectTypeId=customer-order&filterField=customerName&operator=prefix&filterValue=%E5%8D%8E&pageSize=10",null),200);
        assertThat(customer.path("totalCount").asLong()).isEqualTo(2);
        JsonNode batch=json(request("GET",
            "/object-set?objectTypeId=production-batch&filterField=qualityStatus&operator=eq&filterValue=PASSED",null),200);
        assertThat(batch.path("totalCount").asLong()).isEqualTo(1);
        assertThat(batch.path("objects").get(0).path("id").asText()).isEqualTo("LOT-901");
        JsonNode work=json(request("GET",
            "/object-set?objectTypeId=work-order&filterField=id&operator=eq&filterValue=JOB-70",null),200);
        assertThat(work.path("totalCount").asLong()).isEqualTo(1);
        assertThat(work.path("objects").get(0).path("physicalRowCount").asLong()).isEqualTo(3);
        assertThat(work.path("objects").get(0).path("idSourceColumn").asText())
            .isEqualTo("demo_impact_jobs.job_no");
        assertThat(work.path("objects").get(0).path("attributeName").asText())
            .isEqualTo("workOrderId");
    }

    @Test void invalidObjectQueriesSourceConflictsAndCrossQueryCursorsReject() throws Exception {
        ready();
        assertThat(json(request("GET",
            "/object-set?objectTypeId=production-batch&filterField=id&operator=eq&filterValue=LOT-904",null),409)
            .path("code").asText()).isEqualTo("OBJECTSET_ATTRIBUTE_CONFLICT");
        assertThat(json(request("GET",
            "/object-set?objectTypeId=unknown",null),422)
            .path("code").asText()).isEqualTo("UNSUPPORTED_OBJECT_TYPE");
        assertThat(json(request("GET",
            "/object-set?objectTypeId=production-batch&filterField=batchWorkOrder",null),422)
            .path("code").asText()).isEqualTo("UNSUPPORTED_OBJECTSET_PROPERTY");
        assertThat(json(request("GET",
            "/object-set?objectTypeId=customer-order&operator=regex",null),422)
            .path("code").asText()).isEqualTo("UNSUPPORTED_OBJECTSET_OPERATOR");
        assertThat(json(request("GET",
            "/object-set?objectTypeId=customer-order&pageSize=51",null),400)
            .path("code").asText()).isEqualTo("INVALID_PAGE_SIZE");
        JsonNode first=json(request("GET",
            "/object-set?objectTypeId=customer-order&pageSize=1",null),200);
        String cursor=first.path("nextCursor").asText();
        assertThat(cursor).isNotBlank();
        assertThat(json(request("GET",
            "/object-set?objectTypeId=production-batch&pageSize=1&cursor="+cursor,null),400)
            .path("code").asText()).isEqualTo("INVALID_OBJECTSET_CURSOR");
        assertThat(json(request("GET",
            "/object-set?objectTypeId=customer-order&filterValue=SO-&pageSize=1&cursor="+cursor,null),400)
            .path("code").asText()).isEqualTo("INVALID_OBJECTSET_CURSOR");
        assertThat(json(request("GET",
            "/object-set?objectTypeId=customer-order&cursor=corrupted",null),400)
            .path("code").asText()).isEqualTo("INVALID_OBJECTSET_CURSOR");
    }

    @Test void absentAndDisconnectedAndConflictingBatchFacts() throws Exception {
        ready();
        assertThat(json(request("GET","/trace-set?batchId=LOT-999",null),404)
            .path("code").asText()).isEqualTo("BATCH_NOT_FOUND");
        JsonNode missingWork=json(request("GET","/trace-set?batchId=LOT-901",null),200);
        assertThat(missingWork.path("pathCount").asLong()).isZero();
        assertThat(missingWork.path("missingWorkRefs").asLong()).isEqualTo(1);
        assertThat(missingWork.path("paths").size()).isZero();
        JsonNode missingOrder=json(request("GET","/trace-set?batchId=LOT-903",null),200);
        assertThat(missingOrder.path("pathCount").asLong()).isZero();
        assertThat(missingOrder.path("missingOrderRefs").asLong()).isEqualTo(1);
        assertThat(json(request("GET","/trace-set?batchId=LOT-904",null),409)
            .path("code").asText()).isEqualTo("SOURCE_FACT_CONFLICT");
        assertThat(json(request("GET","/trace-set?batchId=x%27%20OR%201=1",null),400)
            .path("code").asText()).isEqualTo("INVALID_BATCH_ID");
    }
}
