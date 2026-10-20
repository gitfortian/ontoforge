package io.ontoforge.boot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.ontoforge.business.connection.DataBindingService;
import io.ontoforge.business.model.NativeDraftService;
import io.ontoforge.business.model.NativeSnapshotService;
import java.net.URI;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * True PG17 + real HTTP LinkBinding write story on two allowlisted source tables.
 * Not the M8.1 Traverse v2 / React/Chromium story, which remains a separate gate.
 */
@org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"mvp-local", "integration-test"})
@ContextConfiguration(initializers = IsolatedPostgresTestDatabase.class)
class LinkBindingFullIntegrationTest {
    private static final String PASSWORD = "m5_test_reader_only";
    private static final String EQUIPMENT = "m8_equipment_source";
    private static final String INSPECTION = "m8_inspection_source";
    private static final String BRIDGE = "m8_equipment_inspection_bridge";

    @DynamicPropertySource
    static void sources(DynamicPropertyRegistry properties) {
        properties.add("ontoforge.binding.source.url", () -> System.getenv("TEST_DB_URL"));
        properties.add("ontoforge.binding.source.username", () -> "ontoforge_binding_login");
        properties.add("ontoforge.binding.source.password", () -> PASSWORD);
        properties.add("ontoforge.binding.source.allowed-tables", () -> EQUIPMENT + "," + INSPECTION + "," + BRIDGE);
    }

    @Autowired NativeDraftService drafts;
    @Autowired NativeSnapshotService snapshots;
    @Autowired DataBindingService bindings;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired @Qualifier("bindingSourceJdbcTemplate") JdbcTemplate sourceJdbc;
    @Autowired TestRestTemplate http;
    @LocalServerPort int port;

    @BeforeEach
    void setup() {
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
        jdbc.execute("DROP TABLE IF EXISTS public." + BRIDGE);
        jdbc.execute("DROP TABLE IF EXISTS public." + INSPECTION);
        jdbc.execute("DROP TABLE IF EXISTS public." + EQUIPMENT);
        jdbc.execute("""
                CREATE TABLE public.m8_equipment_source (
                    equipment_code TEXT PRIMARY KEY, label TEXT NOT NULL, site TEXT NOT NULL,
                    cost NUMERIC(12,2) NOT NULL)
                """);
        jdbc.execute("""
                CREATE TABLE public.m8_inspection_source (
                    lot_code TEXT PRIMARY KEY, equipment_code TEXT NULL, status TEXT NOT NULL,
                    amount NUMERIC(12,2) NULL)
                """);
        jdbc.update("""
                INSERT INTO public.m8_equipment_source(equipment_code,label,site,cost)
                VALUES ('EQ-001','Lathe 1','A',100.00),('EQ-002','Lathe 2','B',200.00)
                """);
        jdbc.update("""
                INSERT INTO public.m8_inspection_source(lot_code,equipment_code,status,amount)
                VALUES ('LOT-11','EQ-001','PASS',10.00),('LOT-12','EQ-001','PASS',20.00),
                       ('LOT-13',NULL,'HOLD',NULL),('LOT-14','EQ-NO','FAIL',40.00),
                       ('LOT-15','','FAIL',50.00)
                """);
        jdbc.execute("""
                CREATE TABLE public.m8_equipment_inspection_bridge (
                    equipment_code TEXT NULL, lot_code TEXT NULL)
                """);
        jdbc.execute("""
                INSERT INTO public.m8_equipment_inspection_bridge(equipment_code, lot_code)
                VALUES ('EQ-001','LOT-11'),('EQ-001','LOT-11'),('EQ-002','LOT-12'),
                       (NULL,'LOT-13'),('EQ-001',NULL),(NULL,NULL),
                       ('EQ-NO','LOT-11'),('EQ-001','LOT-NO'),('EQ-NO','LOT-NO')
                """);
        jdbc.execute("GRANT SELECT ON public." + BRIDGE + " TO ontoforge_binding_reader");
        jdbc.execute("GRANT SELECT ON public." + EQUIPMENT + " TO ontoforge_binding_reader");
        jdbc.execute("GRANT SELECT ON public." + INSPECTION + " TO ontoforge_binding_reader");
    }

    @AfterEach
    void teardown() {
        jdbc.execute("GRANT SELECT ON public." + BRIDGE + " TO ontoforge_binding_reader");
        jdbc.execute("DROP TABLE IF EXISTS public." + BRIDGE);
        jdbc.execute("DROP TABLE IF EXISTS public." + INSPECTION);
        jdbc.execute("DROP TABLE IF EXISTS public." + EQUIPMENT);
    }

    @Test
    void persistedLinkFullValidationOrphansReplayCasAndSourceDrift() throws Exception {
        String pkg = "m8.test.p" + UUID.randomUUID().toString().replace("-", "");
        NativeDraftService.DraftDetail draft = drafts.create(pkg);
        ObjectNode editor = mapper.createObjectNode();
        editor.put("editorSchemaVersion", "0.1.0");
        ArrayNode types = editor.putArray("objectTypes");
        addType(types, "equipment", "Equipment", "equipment-code", "equipmentCode",
                "equipment-label", "label", "equipment-site", "site");
        addType(types, "inspection-lot", "InspectionLot", "lot-code", "lotCode",
                "equipment-ref", "equipmentRef", "inspection-status", "status");
        decimalProperty(((ObjectNode) types.get(0)).withArray("properties"),
                "equipment-cost", "equipmentCost");
        decimalProperty(((ObjectNode) types.get(1)).withArray("properties"),
                "inspection-amount", "amount");
        ArrayNode links = editor.putArray("linkTypes");
        link(links, "equipment-inspections", "equipmentInspections", "ONE_TO_MANY");
        link(links, "equipment-single-inspection", "equipmentSingleInspection", "ONE_TO_ONE");
        link(links, "equipment-parallel-inspections", "equipmentParallelInspections", "ONE_TO_MANY");
        link(links, "equipment-competing-inspections", "equipmentCompetingInspections", "ONE_TO_MANY");
        ObjectNode reversed = links.addObject();
        reversed.put("id", "inspection-equipment");
        reversed.put("apiName", "inspectionEquipment");
        reversed.put("sourceTypeRef", "inspection-lot");
        reversed.put("targetTypeRef", "equipment");
        reversed.put("cardinality", "MANY_TO_ONE");
        reversed.put("evidenceRequired", true);

        NativeDraftService.DraftDetail saved = drafts.save(
                UUID.fromString(draft.draftId()), draft.revision(), editor);
        assertThat(drafts.validate(UUID.fromString(draft.draftId()), saved.revision()).status())
                .isEqualTo("VALID");
        String snapshotId = snapshots.freeze(UUID.fromString(draft.draftId()),
                saved.revision(), UUID.randomUUID()).snapshot().snapshotId();

        DataBindingService.BindingDetail equipment = createBinding(snapshotId, "equipment",
                EQUIPMENT, "equipment_code",
                new String[][]{{"equipment-code", "equipment_code"},
                        {"equipment-label", "label"}, {"equipment-site", "site"},
                        {"equipment-cost","cost"}});
        DataBindingService.BindingDetail lot = createBinding(snapshotId, "inspection-lot",
                INSPECTION, "lot_code",
                new String[][]{{"lot-code", "lot_code"}, {"equipment-ref", "equipment_code"},
                        {"inspection-status", "status"}, {"inspection-amount","amount"}});
        assertThat(equipment.identityDomainId()).isNotEqualTo(lot.identityDomainId());

        ObjectNode request = mapper.createObjectNode();
        request.put("contractVersion", "local-link-binding-v1");
        request.putObject("modelSourceRef").put("kind", "LOCAL_SNAPSHOT")
                .put("snapshotId", snapshotId);
        request.put("packageId", pkg);
        request.put("linkTypeId", "equipment-inspections");
        ref(request.putObject("sourceBindingRef"), equipment);
        ref(request.putObject("targetBindingRef"), lot);
        request.put("mode", "FOREIGN_KEY");
        request.put("fkOwner", "TARGET");
        request.put("fkSourceColumn", "equipment_code");
        request.put("expectedRevision", 0);
        request.put("idempotencyKey", UUID.randomUUID().toString());
        request.putObject("expectedSchemaFingerprints")
                .put("source", equipment.sourceSchemaFingerprint())
                .put("target", lot.sourceSchemaFingerprint());
        request.putObject("validationScope")
                .put("kind", "VALIDATION_SCOPE").put("coverage", "FULL");

        ResponseEntity<String> preview = post("/validate", request);
        assertThat(preview.getStatusCode().value()).as(preview.getBody()).isEqualTo(200);
        JsonNode validation = mapper.readTree(preview.getBody());
        assertThat(validation.path("status").asText()).isEqualTo("VALID_WITH_ORPHANS");
        assertThat(validation.path("coverage").asText()).isEqualTo("FULL");
        assertThat(validation.path("scannedRows").asInt()).isEqualTo(7);
        assertThat(validation.path("matchedLinks").asInt()).isEqualTo(2);
        assertThat(validation.path("nullFkCount").asInt()).isEqualTo(1);
        assertThat(validation.path("orphanCount").asInt()).isEqualTo(2);

        ResponseEntity<String> firstResponse = post("", request);
        assertThat(firstResponse.getStatusCode().value()).as(firstResponse.getBody()).isEqualTo(201);
        JsonNode first = mapper.readTree(firstResponse.getBody());
        String id = first.path("linkBindingId").asText();
        assertThat(first.path("revision").asLong()).isEqualTo(1L);
        assertThat(first.path("validationStatus").asText()).isEqualTo("VALID_WITH_ORPHANS");
        assertThat(first.path("digest").asText()).matches("[0-9a-f]{64}");
        assertThat(first.path("sourceBindingDigest").asText()).isEqualTo(equipment.digest());
        assertThat(first.path("targetBindingDigest").asText()).isEqualTo(lot.digest());
        assertThat(first.path("modelPayloadSha256").asText()).isEqualTo(equipment.payloadSha256());
        assertThat(first.path("sourceSchemaFingerprint").asText())
                .isEqualTo(equipment.sourceSchemaFingerprint());
        assertThat(first.path("targetSchemaFingerprint").asText())
                .isEqualTo(lot.sourceSchemaFingerprint());
        assertThat(first.path("validationScope").path("coverage").asText()).isEqualTo("FULL");
        assertThat(first.path("validationQuality").path("orphanCount").asInt()).isEqualTo(2);
        assertThat(first.path("validationQuality").path("nullFkCount").asInt()).isEqualTo(1);
        assertThat(first.path("validationQuality").path("matchedLinks").asInt()).isEqualTo(2);


        // L03 negative matrix: exact immutable endpoint evidence and package
        // ownership are checked by the real HTTP service, not a mocked
        // validator. None of the rejected CREATEs may create a Head, revision
        // or durable idempotency receipt.
        ObjectNode foreignPackage = request.deepCopy();
        foreignPackage.put("packageId", "m8.foreign.spoof");
        foreignPackage.put("idempotencyKey", UUID.randomUUID().toString());
        ResponseEntity<String> foreign = post("", foreignPackage);
        assertThat(foreign.getStatusCode().value()).as(foreign.getBody()).isEqualTo(409);
        assertThat(foreign.getBody()).contains("LINK_SNAPSHOT_MISMATCH");
        assertThat(foreign.getBody()).doesNotContain(equipment.digest(), lot.digest());

        ObjectNode wrongDigest = request.deepCopy();
        String alteredDigest = (lot.digest().startsWith("0") ? "1" : "0")
                + lot.digest().substring(1);
        ((ObjectNode) wrongDigest.path("targetBindingRef")).put("digest", alteredDigest);
        wrongDigest.put("idempotencyKey", UUID.randomUUID().toString());
        ResponseEntity<String> corrupt = post("", wrongDigest);
        assertThat(corrupt.getStatusCode().value()).as(corrupt.getBody()).isEqualTo(409);
        assertThat(corrupt.getBody()).contains("BINDING_VERSION_DRIFT");
        assertThat(corrupt.getBody()).doesNotContain(lot.digest(), lot.identityDomainId());

        ObjectNode missingRevision = request.deepCopy();
        ((ObjectNode) missingRevision.path("targetBindingRef")).put("revision", 999999);
        missingRevision.put("idempotencyKey", UUID.randomUUID().toString());
        ResponseEntity<String> absentRevision = post("", missingRevision);
        assertThat(absentRevision.getStatusCode().value()).as(absentRevision.getBody()).isEqualTo(404);
        assertThat(absentRevision.getBody()).doesNotContain(lot.digest(), lot.identityDomainId());

        ObjectNode reversedEndpoints = request.deepCopy();
        reversedEndpoints.set("sourceBindingRef", request.path("targetBindingRef").deepCopy());
        reversedEndpoints.set("targetBindingRef", request.path("sourceBindingRef").deepCopy());
        reversedEndpoints.put("idempotencyKey", UUID.randomUUID().toString());
        ResponseEntity<String> reversedIdentity = post("", reversedEndpoints);
        assertThat(reversedIdentity.getStatusCode().value())
                .as(reversedIdentity.getBody()).isEqualTo(409);
        assertThat(reversedIdentity.getBody()).contains("LINK_ENDPOINT_VERSION_MISMATCH");

        ObjectNode falseFingerprint = request.deepCopy();
        ((ObjectNode) falseFingerprint.path("expectedSchemaFingerprints"))
                .put("source", "0".repeat(64).equals(equipment.sourceSchemaFingerprint())
                        ? "1".repeat(64) : "0".repeat(64));
        falseFingerprint.put("idempotencyKey", UUID.randomUUID().toString());
        ResponseEntity<String> drifted = post("", falseFingerprint);
        assertThat(drifted.getStatusCode().value()).as(drifted.getBody()).isEqualTo(409);
        assertThat(drifted.getBody()).contains("LINK_SOURCE_SCHEMA_DRIFT");

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM runtime_link_binding WHERE package_id=?
                """, Integer.class, pkg)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM runtime_link_binding_revision
                 WHERE link_binding_id=?::uuid
                """, Integer.class, id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM runtime_link_binding_operation
                 WHERE package_id=?
                """, Integer.class, pkg)).isEqualTo(1);

        // Real v2 ObjectRef → OUT and IN, without abusing local-query-v1.
        ObjectNode out = traverseRequest(pkg, snapshotId, equipment, lot, id, "equipment-inspections",
                "OUT", "EQ-001", 50);
        ResponseEntity<String> two = traverse(out);
        assertThat(two.getStatusCode().value()).as(two.getBody()).isEqualTo(200);
        JsonNode twoJson = mapper.readTree(two.getBody());
        assertThat(twoJson.path("status").asText()).isEqualTo("SUCCESS");
        assertThat(twoJson.path("queryVersion").asText()).isEqualTo("local-query-v2");
        assertThat(twoJson.path("objects").size()).isEqualTo(2);
        assertThat(twoJson.path("links").size()).isEqualTo(2);
        assertThat(twoJson.path("sourceReadRefs").size()).isEqualTo(2);
        assertThat(twoJson.path("bindingVersions").size()).isEqualTo(3);
        assertThat(twoJson.path("quality").path("completeness").asText()).isEqualTo("COMPLETE");
        assertThat(twoJson.path("objects").toString()).contains("LOT-11", "LOT-12");
        assertThat(twoJson.path("links").get(0).path("source").path("objectTypeId").asText())
                .isEqualTo("equipment");
        assertThat(twoJson.path("links").get(0).path("target").path("objectTypeId").asText())
                .isEqualTo("inspection-lot");

        ObjectNode in = traverseRequest(pkg, snapshotId, lot, equipment, id, "equipment-inspections",
                "IN", "LOT-11", 50);
        ResponseEntity<String> one = traverse(in);
        assertThat(one.getStatusCode().value()).as(one.getBody()).isEqualTo(200);
        JsonNode oneJson = mapper.readTree(one.getBody());
        assertThat(oneJson.path("objects").size()).isEqualTo(1);
        assertThat(oneJson.path("objects").get(0).path("objectRef").path("key").path("value").asText())
                .isEqualTo("EQ-001");
        assertThat(oneJson.path("links").get(0).path("source").path("objectTypeId").asText())
                .isEqualTo("equipment");

        ObjectNode orphan = traverseRequest(pkg, snapshotId, lot, equipment, id, "equipment-inspections",
                "IN", "LOT-14", 50);
        JsonNode partial = mapper.readTree(traverse(orphan).getBody());
        assertThat(partial.path("status").asText()).isEqualTo("SUCCESS");
        assertThat(partial.path("links").size()).isZero();
        assertThat(partial.path("quality").path("completeness").asText()).isEqualTo("PARTIAL");
        assertThat(partial.path("quality").path("answerScope").asText()).isEqualTo("KNOWN_SUBSET");
        assertThat(partial.path("quality").path("knownOrphanCount").asInt()).isEqualTo(1);

        ObjectNode nullFk = traverseRequest(pkg, snapshotId, lot, equipment, id, "equipment-inspections",
                "IN", "LOT-13", 50);
        JsonNode completeZero = mapper.readTree(traverse(nullFk).getBody());
        assertThat(completeZero.path("quality").path("completeness").asText()).isEqualTo("COMPLETE");
        assertThat(completeZero.path("quality").path("knownOrphanCount").asInt()).isZero();
        assertThat(completeZero.path("links").size()).isZero();
        ObjectNode zeroOut = traverseRequest(pkg, snapshotId, equipment, lot, id, "equipment-inspections",
                "OUT", "EQ-002", 50);
        assertThat(mapper.readTree(traverse(zeroOut).getBody()).path("links").size()).isZero();

        // BOUNDED_ROOT_FILTER is the only root predicate source. Every condition
        // must resolve to a searchable frozen property and explicit M5 source mapping.
        ObjectNode filteredRoots = traverseRequest(pkg, snapshotId, equipment, lot, id,
                "equipment-inspections", "OUT", "EQ-001", 50);
        ObjectNode selector = filteredRoots.putObject("rootSelector");
        selector.put("kind", "BOUNDED_ROOT_FILTER");
        ArrayNode selectors = selector.putArray("conditions");
        selectors.addObject().put("propertyRef","equipment-site").put("operator","EQ")
                .putObject("literal").put("valueType","STRING").put("value","A");
        ResponseEntity<String> bounded = traverse(filteredRoots);
        assertThat(bounded.getStatusCode().value()).as(bounded.getBody()).isEqualTo(200);
        assertThat(mapper.readTree(bounded.getBody()).path("links").size()).isEqualTo(2);
        assertThat(mapper.readTree(bounded.getBody()).path("quality").path("completeness").asText())
                .isEqualTo("COMPLETE");

        ObjectNode noRoots = filteredRoots.deepCopy();
        ((ObjectNode) noRoots.path("rootSelector").path("conditions").get(0).path("literal"))
                .put("value","unmatched-site");
        ResponseEntity<String> emptyRoots = traverse(noRoots);
        assertThat(emptyRoots.getStatusCode().value()).as(emptyRoots.getBody()).isEqualTo(200);
        assertThat(mapper.readTree(emptyRoots.getBody()).path("links").size()).isZero();

        ObjectNode badField = filteredRoots.deepCopy();
        ((ObjectNode) badField.path("rootSelector").path("conditions").get(0))
                .put("propertyRef","nonexistent-column");
        ResponseEntity<String> unsafeField = traverse(badField);
        assertThat(unsafeField.getStatusCode().value()).isEqualTo(422);
        assertThat(unsafeField.getBody()).contains("UNSUPPORTED_FILTER_FIELD");

        ObjectNode wrongType = filteredRoots.deepCopy();
        ((ObjectNode) wrongType.path("rootSelector").path("conditions").get(0).path("literal"))
                .put("valueType","DECIMAL");
        assertThat(traverse(wrongType).getStatusCode().value()).isEqualTo(422);

        // TARGET FILTER applies AFTER one hop, never changes root membership or
        // fabricates orphans. The condition is mapped to inspection.status, not SQL text.
        ObjectNode filteredTargets = traverseRequest(pkg, snapshotId, equipment, lot, id,
                "equipment-inspections", "OUT", "EQ-001", 50);
        ArrayNode stepsFiltered = filteredTargets.withArray("steps");
        ObjectNode terminal = (ObjectNode) stepsFiltered.remove(1);
        ObjectNode tf = stepsFiltered.addObject();
        tf.put("kind","FILTER").put("scope","TARGET");
        tf.putArray("conditions").addObject().put("propertyRef","inspection-status")
                .put("operator","EQ").putObject("literal")
                .put("valueType","STRING").put("value","PASS");
        stepsFiltered.add(terminal);
        ResponseEntity<String> accepted = traverse(filteredTargets);
        assertThat(accepted.getStatusCode().value()).as(accepted.getBody()).isEqualTo(200);
        assertThat(mapper.readTree(accepted.getBody()).path("links").size()).isEqualTo(2);
        ((ObjectNode) tf.path("conditions").get(0).path("literal")).put("value","FAIL");
        JsonNode excluded = mapper.readTree(traverse(filteredTargets).getBody());
        assertThat(excluded.path("links").size()).isZero();
        assertThat(excluded.path("quality").path("completeness").asText()).isEqualTo("COMPLETE");

        // Real SORT + PROJECT: property order is stable across frozen pages,
        // and the projection carries the exact target SourceReadRef provenance.
        ObjectNode sorted = traverseRequest(pkg, snapshotId, equipment, lot, id,
                "equipment-inspections", "OUT", "EQ-001", 1);
        ArrayNode sortedSteps = sorted.withArray("steps");
        ObjectNode sortedPage = (ObjectNode) sortedSteps.remove(1);
        sortedSteps.addObject().put("kind","SORT").putArray("fields")
                .addObject().put("propertyRef","lot-code").put("direction","DESC");
        sortedSteps.addObject().put("kind","PROJECT").putArray("propertyRefs")
                .add("inspection-amount");
        sortedSteps.add(sortedPage);
        ResponseEntity<String> sortedResponse = traverse(sorted);
        assertThat(sortedResponse.getStatusCode().value()).as(sortedResponse.getBody()).isEqualTo(200);
        JsonNode sortedFirst = mapper.readTree(sortedResponse.getBody());
        assertThat(sortedFirst.path("objects").get(0).path("objectRef").path("key").path("value").asText())
                .isEqualTo("LOT-12");
        assertThat(sortedFirst.path("links").get(0).path("target").path("key").path("value").asText())
                .isEqualTo("LOT-12");
        JsonNode amount = sortedFirst.path("objects").get(0).path("properties").get(0);
        assertThat(amount.path("propertyRef").asText()).isEqualTo("inspection-amount");
        assertThat(amount.path("valueType").asText()).isEqualTo("DECIMAL");
        assertThat(new java.math.BigDecimal(amount.path("value").asText()))
                .isEqualByComparingTo("20");
        assertThat(sortedFirst.path("sourceReadRefs").toString()).contains("amount");
        JsonNode sortedSecond = mapper.readTree(traversePage(sortedFirst.path("nextCursor").asText()).getBody());
        assertThat(sortedSecond.path("objects").get(0).path("objectRef").path("key").path("value").asText())
                .isEqualTo("LOT-11");
        assertThat(sortedSecond.path("links").get(0).path("target").path("key").path("value").asText())
                .isEqualTo("LOT-11");
        assertThat(sortedSecond.path("resultSnapshotRef")).isEqualTo(sortedFirst.path("resultSnapshotRef"));

        ObjectNode unknownSort = sorted.deepCopy();
        ((ObjectNode) unknownSort.withArray("steps").get(1).path("fields").get(0))
                .put("propertyRef","secret-property");
        ResponseEntity<String> rejectedSort = traverse(unknownSort);
        assertThat(rejectedSort.getStatusCode().value()).isEqualTo(422);
        assertThat(rejectedSort.getBody()).contains("UNSUPPORTED_FILTER_FIELD");

        ObjectNode countQuery = traverseRequest(pkg, snapshotId, equipment, lot, id,
                "equipment-inspections", "OUT", "EQ-001", 50);
        ArrayNode countSteps = countQuery.withArray("steps");
        countSteps.remove(countSteps.size()-1);
        countSteps.addObject().put("kind","AGGREGATE").put("operation","COUNT_DISTINCT");
        ResponseEntity<String> counted = traverse(countQuery);
        assertThat(counted.getStatusCode().value()).as(counted.getBody()).isEqualTo(200);
        JsonNode countResult = mapper.readTree(counted.getBody());
        assertThat(countResult.path("resultKind").asText()).isEqualTo("AGGREGATE");
        assertThat(countResult.path("aggregate").path("value").asText()).isEqualTo("2");
        assertThat(countResult.path("aggregate").path("matchedObjects").asInt()).isEqualTo(2);
        assertThat(countResult.path("pageSize").isMissingNode()).isTrue();
        assertThat(countResult.path("sourceReadRefs").size()).isEqualTo(2);

        ObjectNode sumQuery = traverseRequest(pkg, snapshotId, equipment, lot, id,
                "equipment-inspections", "OUT", "EQ-001", 50);
        ArrayNode sumSteps = sumQuery.withArray("steps");
        sumSteps.remove(sumSteps.size()-1);
        sumSteps.addObject().put("kind","AGGREGATE").put("operation","SUM")
                .put("propertyRef","inspection-amount");
        ResponseEntity<String> summed = traverse(sumQuery);
        assertThat(summed.getStatusCode().value()).as(summed.getBody()).isEqualTo(200);
        JsonNode sumResult = mapper.readTree(summed.getBody());
        assertExactJsonBudget(sumResult);
        assertThat(sumResult.path("aggregate").path("valueType").asText()).isEqualTo("DECIMAL");
        assertThat(new java.math.BigDecimal(sumResult.path("aggregate").path("value").asText()))
                .isEqualByComparingTo("30");
        assertThat(sumResult.path("sourceReadRefs").toString()).contains("amount");

        ObjectNode badSum = sumQuery.deepCopy();
        ((ObjectNode) badSum.withArray("steps").get(1)).put("propertyRef","inspection-status");
        assertThat(traverse(badSum).getStatusCode().value()).isEqualTo(422);

        // Separate FK-on-SOURCE case: reverse cardinality but DO NOT reverse
        // LinkFact definition based on traversal direction.
        ObjectNode reversedRequest = request.deepCopy();
        reversedRequest.put("linkTypeId", "inspection-equipment");
        reversedRequest.set("sourceBindingRef", request.path("targetBindingRef").deepCopy());
        reversedRequest.set("targetBindingRef", request.path("sourceBindingRef").deepCopy());
        reversedRequest.put("fkOwner", "SOURCE");
        reversedRequest.put("idempotencyKey", UUID.randomUUID().toString());
        reversedRequest.putObject("expectedSchemaFingerprints")
                .put("source", lot.sourceSchemaFingerprint())
                .put("target", equipment.sourceSchemaFingerprint());
        ResponseEntity<String> reverseSave = post("", reversedRequest);
        assertThat(reverseSave.getStatusCode().value()).as(reverseSave.getBody()).isEqualTo(201);
        String reverseId = mapper.readTree(reverseSave.getBody()).path("linkBindingId").asText();
        ObjectNode reverseOut = traverseRequest(pkg, snapshotId, lot, equipment, reverseId,
                "inspection-equipment", "OUT", "LOT-11", 50);
        ResponseEntity<String> reverseOne = traverse(reverseOut);
        assertThat(reverseOne.getStatusCode().value()).as(reverseOne.getBody()).isEqualTo(200);
        assertThat(mapper.readTree(reverseOne.getBody()).path("links").size()).isEqualTo(1);
        JsonNode reverseFact = mapper.readTree(reverseOne.getBody()).path("links").get(0);
        assertThat(reverseFact.path("source").path("objectTypeId").asText()).isEqualTo("inspection-lot");
        assertThat(reverseFact.path("target").path("objectTypeId").asText()).isEqualTo("equipment");
        ObjectNode reverseIn = traverseRequest(pkg, snapshotId, equipment, lot, reverseId,
                "inspection-equipment", "IN", "EQ-001", 50);
        JsonNode reverseMany = mapper.readTree(traverse(reverseIn).getBody());
        assertThat(reverseMany.path("links").size()).isEqualTo(2);
        assertThat(reverseMany.path("quality").path("completeness").asText()).isEqualTo("COMPLETE");
        ObjectNode reverseOrphan = traverseRequest(pkg, snapshotId, lot, equipment, reverseId,
                "inspection-equipment", "OUT", "LOT-14", 50);
        JsonNode reversedPartial = mapper.readTree(traverse(reverseOrphan).getBody());
        assertThat(reversedPartial.path("quality").path("completeness").asText()).isEqualTo("PARTIAL");
        assertThat(reversedPartial.path("quality").path("knownOrphanCount").asInt()).isEqualTo(1);

        // The SAME equipment output is matched by two source LinkFacts.
        // Aggregate SUM must deduplicate by full output ObjectRef, not sum edges.
        ObjectNode distinctSum = traverseRequest(pkg, snapshotId, lot, equipment, reverseId,
                "inspection-equipment", "OUT", "LOT-11", 50);
        ObjectNode multi = distinctSum.putObject("rootSelector");
        multi.put("kind", "BOUNDED_ROOT_FILTER");
        multi.putArray("conditions").addObject().put("propertyRef","inspection-status")
                .put("operator","EQ").putObject("literal")
                .put("valueType","STRING").put("value","PASS");
        ArrayNode manySteps = distinctSum.withArray("steps");
        manySteps.remove(1);
        manySteps.addObject().put("kind","AGGREGATE").put("operation","SUM")
                .put("propertyRef","equipment-cost");
        ResponseEntity<String> distinctRes = traverse(distinctSum);
        assertThat(distinctRes.getStatusCode().value()).as(distinctRes.getBody()).isEqualTo(200);
        JsonNode uniqueSum = mapper.readTree(distinctRes.getBody());
        assertThat(uniqueSum.path("aggregate").path("matchedObjects").asInt()).isEqualTo(1);
        assertThat(new java.math.BigDecimal(uniqueSum.path("aggregate").path("value").asText()))
                .isEqualByComparingTo("100");

        // Real PG17 fanout is checked before success, not sampled down to one
        // false-positive "COMPLETE" row. Both linked candidates exist.
        ObjectNode limitedJoin = traverseRequest(pkg, snapshotId, equipment, lot, id,
                "equipment-inspections", "OUT", "EQ-001", 50);
        limitedJoin.putObject("limits").put("maxJoinCandidates", 1);
        ResponseEntity<String> joinBudget = traverse(limitedJoin);
        assertThat(joinBudget.getStatusCode().value()).isEqualTo(429);
        assertThat(joinBudget.getBody()).contains("QUERY_BUDGET_EXCEEDED");
        assertThat(joinBudget.getBody()).doesNotContain("LOT-11", "LOT-12");
        ObjectNode limitedPhysical = traverseRequest(pkg, snapshotId, equipment, lot, id,
                "equipment-inspections", "OUT", "EQ-001", 50);
        limitedPhysical.putObject("limits").put("maxPhysicalRows", 2);
        ResponseEntity<String> scanned = traverse(limitedPhysical);
        assertThat(scanned.getStatusCode().value()).isEqualTo(429);
        assertThat(scanned.getBody()).doesNotContain("LOT-11", "LOT-12");

        ObjectNode unapprovedField = limitedJoin.deepCopy();
        unapprovedField.put("clientSql", "SELECT * FROM pg_catalog.pg_roles");
        assertThat(traverse(unapprovedField).getStatusCode().value()).isEqualTo(422);

        // Real large one-to-many fanout. The frozen first page must not
        // evaluate 1,000 entire edge lists once PER page ObjectRef; the second
        // page must remain immutable after the physical source rows disappear.
        jdbc.execute("INSERT INTO public." + INSPECTION
                + " SELECT 'FAN-' || lpad(g::text,5,'0'), 'EQ-001','PASS', 1.00"
                + " FROM generate_series(1,1000) AS g");
        try {
            ObjectNode fanout = traverseRequest(pkg, snapshotId, equipment, lot, id,
                    "equipment-inspections", "OUT", "EQ-001", 50);
            ResponseEntity<String> fanoutResponse = traverse(fanout);
            assertThat(fanoutResponse.getStatusCode().value()).as(fanoutResponse.getBody()).isEqualTo(200);
            JsonNode fanFirst = mapper.readTree(fanoutResponse.getBody());
            assertThat(fanFirst.path("quality").path("completeness").asText()).isEqualTo("COMPLETE");
            assertThat(fanFirst.path("budget").path("distinctLinks").asInt()).isEqualTo(1002);
            assertThat(fanFirst.path("objects").size()).isEqualTo(50);
            assertThat(fanFirst.path("links").size()).isEqualTo(50);
            String fanCursor = fanFirst.path("nextCursor").asText();
            assertThat(fanCursor).startsWith("v2.");

            jdbc.execute("DELETE FROM public." + INSPECTION + " WHERE lot_code LIKE 'FAN-%'");
            ResponseEntity<String> fanoutSecond = traversePage(fanCursor);
            assertThat(fanoutSecond.getStatusCode().value()).as(fanoutSecond.getBody()).isEqualTo(200);
            JsonNode fanNext = mapper.readTree(fanoutSecond.getBody());
            assertThat(fanNext.path("objects").size()).isEqualTo(50);
            assertThat(fanNext.path("links").size()).isEqualTo(50);
            assertThat(fanNext.path("resultSnapshotRef")).isEqualTo(fanFirst.path("resultSnapshotRef"));
            assertThat(fanNext.path("objects").get(0).path("objectRef"))
                    .isNotEqualTo(fanFirst.path("objects").get(0).path("objectRef"));
        } finally {
            jdbc.execute("DELETE FROM public." + INSPECTION + " WHERE lot_code LIKE 'FAN-%'");
        }

        ObjectNode paged = traverseRequest(pkg, snapshotId, equipment, lot, id,
                "equipment-inspections", "OUT", "EQ-001", 1);
        ResponseEntity<String> firstPage = traverse(paged);
        assertThat(firstPage.getStatusCode().value()).as(firstPage.getBody()).isEqualTo(200);
        JsonNode firstPageBody = mapper.readTree(firstPage.getBody());
        assertExactJsonBudget(firstPageBody);
        assertThat(firstPageBody.path("objects").size()).isEqualTo(1);
        assertThat(firstPageBody.path("links").size()).isEqualTo(1);
        assertThat(firstPageBody.path("pageNumber").asInt()).isEqualTo(1);
        String nextCursor = firstPageBody.path("nextCursor").asText();
        assertThat(nextCursor).startsWith("v2.");
        ResponseEntity<String> secondPage = traversePage(nextCursor);
        assertThat(secondPage.getStatusCode().value()).as(secondPage.getBody()).isEqualTo(200);
        JsonNode secondPageBody = mapper.readTree(secondPage.getBody());
        assertExactJsonBudget(secondPageBody);
        assertThat(secondPageBody.path("objects").size()).isEqualTo(1);
        assertThat(secondPageBody.path("links").size()).isEqualTo(1);
        assertThat(secondPageBody.path("pageNumber").asInt()).isEqualTo(2);
        assertThat(secondPageBody.path("nextCursor").isMissingNode()).isTrue();
        assertThat(secondPageBody.path("resultSnapshotRef"))
                .isEqualTo(firstPageBody.path("resultSnapshotRef"));
        assertThat(secondPageBody.path("objects").get(0).path("objectRef").path("key").path("value"))
                .isNotEqualTo(firstPageBody.path("objects").get(0).path("objectRef").path("key").path("value"));
        String forged = nextCursor.substring(0, 3)
                + (nextCursor.charAt(3)=='A' ? 'B' : 'A') + nextCursor.substring(4);
        ResponseEntity<String> tampered = traversePage(forged);
        assertThat(tampered.getStatusCode().value()).isEqualTo(400);
        assertThat(tampered.getBody()).contains("INVALID_CURSOR");

        // Source permission revocation kills a previously issued cursor forever.
        jdbc.execute("REVOKE SELECT ON public." + INSPECTION + " FROM ontoforge_binding_reader");
        try {
            ResponseEntity<String> deniedPage = traversePage(nextCursor);
            assertThat(deniedPage.getStatusCode().value()).isEqualTo(403);
            assertThat(deniedPage.getBody()).doesNotContain("LOT-12");
        } finally {
            jdbc.execute("GRANT SELECT ON public." + INSPECTION + " TO ontoforge_binding_reader");
        }
        ResponseEntity<String> cannotResurrect = traversePage(nextCursor);
        assertThat(cannotResurrect.getStatusCode().value()).isEqualTo(410);
        assertThat(cannotResurrect.getBody()).contains("RESULT_EXPIRED");

        ObjectNode extra = out.deepCopy();
        extra.withArray("steps").addObject().put("kind", "PAGE").put("pageSize", 50);
        assertThat(traverse(extra).getStatusCode().value()).isEqualTo(422);
        ObjectNode stringPageSize = out.deepCopy();
        ((ObjectNode) stringPageSize.withArray("steps").get(1)).put("pageSize", "50");
        ResponseEntity<String> wrongPageType = traverse(stringPageSize);
        assertThat(wrongPageType.getStatusCode().value()).isEqualTo(422);
        assertThat(wrongPageType.getBody()).contains("TRAVERSE_CONTRACT_INVALID");
        ObjectNode stringDepth = out.deepCopy();
        ((ObjectNode) stringDepth.withArray("steps").get(0)).put("maxDepth", "1");
        assertThat(traverse(stringDepth).getStatusCode().value()).isEqualTo(422);

        // L15-L16: real near-ceiling fanout, below the combined 10,000-row
        // physical read budget. Never treat PAGE 50 as a reason to truncate
        // join candidates before enforcement. A PostgreSQL ANALYZE plan is
        // evaluated against the same SELECT-only source privilege as M8.
        jdbc.execute("INSERT INTO public." + INSPECTION
                + " SELECT 'CEIL-' || lpad(g::text,5,'0'), 'EQ-001', 'PASS', 1.00"
                + " FROM generate_series(1,9800) AS g");
        try {
            jdbc.execute("ANALYZE public." + INSPECTION);
            String explained = sourceJdbc.queryForObject("""
                    EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)
                    SELECT t.lot_code
                      FROM public.m8_equipment_source e
                      JOIN public.m8_inspection_source t
                        ON t.equipment_code=e.equipment_code
                     WHERE e.equipment_code='EQ-001'
                    """, String.class);
            JsonNode plan = mapper.readTree(explained).get(0);
            assertThat(plan.path("Plan").path("Actual Rows").asInt())
                    .as("EXPLAIN must observe all 9,800 new edges and two original matches")
                    .isEqualTo(9802);
            assertThat(plan.path("Execution Time").asDouble())
                    .as("SQL-side measured PG17 execution should respect the 3-second limit")
                    .isLessThan(3000.0);
            ObjectNode nearingLimit = traverseRequest(pkg, snapshotId, equipment, lot, id,
                    "equipment-inspections", "OUT", "EQ-001", 50);
            nearingLimit.putObject("limits").put("maxJoinCandidates", 9000);
            ResponseEntity<String> rejected = traverse(nearingLimit);
            assertThat(rejected.getStatusCode().value())
                    .as(rejected.getBody()).isEqualTo(429);
            assertThat(rejected.getBody()).contains("QUERY_BUDGET_EXCEEDED");
            JsonNode denied = mapper.readTree(rejected.getBody());
            assertThat(denied.has("objects")).isFalse();
            assertThat(denied.has("links")).isFalse();
            assertThat(denied.has("nextCursor")).isFalse();
        } finally {
            jdbc.execute("DELETE FROM public." + INSPECTION
                    + " WHERE lot_code LIKE 'CEIL-%'");
        }

        ResponseEntity<String> replay = post("", request);
        assertThat(replay.getStatusCode().value()).as(replay.getBody()).isEqualTo(201);
        assertThat(mapper.readTree(replay.getBody()).path("linkBindingId").asText()).isEqualTo(id);
        assertThat(mapper.readTree(replay.getBody()).path("revision").asLong()).isEqualTo(1L);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM runtime_link_binding_revision
                WHERE link_binding_id=?::uuid
                """, Integer.class, id)).isEqualTo(1);

        ObjectNode oneToOne = request.deepCopy();
        oneToOne.put("linkTypeId", "equipment-single-inspection");
        assertThat(post("/validate", oneToOne).getBody()).contains("LINK_CARDINALITY_CONFLICT");

        ObjectNode next = request.deepCopy();
        next.put("expectedRevision", 1);
        next.put("idempotencyKey", UUID.randomUUID().toString());
        ResponseEntity<String> revised = put("/" + id, next);
        assertThat(revised.getStatusCode().value()).as(revised.getBody()).isEqualTo(200);
        assertThat(mapper.readTree(revised.getBody()).path("revision").asLong()).isEqualTo(2L);
        assertThat(put("/" + id, next).getStatusCode().value()).isEqualTo(200);
        assertThat(mapper.readTree(put("/" + id, next).getBody()).path("revision").asLong()).isEqualTo(2L);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM runtime_link_binding_revision WHERE link_binding_id=?::uuid
                """, Integer.class, id)).isEqualTo(2);

        ObjectNode collision = next.deepCopy();
        collision.put("fkSourceColumn", "status");
        ResponseEntity<String> sameKeyDifferentPayload = put("/" + id, collision);
        assertThat(sameKeyDifferentPayload.getStatusCode().value()).isEqualTo(409);
        assertThat(sameKeyDifferentPayload.getBody()).contains("IDEMPOTENCY_KEY_CONFLICT");

        ObjectNode oldCasNewKey = next.deepCopy();
        oldCasNewKey.put("idempotencyKey", UUID.randomUUID().toString());
        ResponseEntity<String> stale = put("/" + id, oldCasNewKey);
        assertThat(stale.getStatusCode().value()).isEqualTo(409);
        assertThat(stale.getBody()).contains("BINDING_REVISION_CONFLICT");

        // Real simultaneous HTTP writers against the SAME metadata PG17 database.
        // Without an operation-key lock, the second identical UPDATE sees no
        // receipt and may return a false stale-CAS 409 after the first commits.
        ObjectNode concurrentUpdate = next.deepCopy();
        concurrentUpdate.put("expectedRevision", 2);
        concurrentUpdate.put("idempotencyKey", UUID.randomUUID().toString());
        try (var workers = Executors.newFixedThreadPool(2)) {
            CountDownLatch go = new CountDownLatch(1);
            var left = workers.submit(() -> {go.await(); return put("/" + id, concurrentUpdate.deepCopy());});
            var right = workers.submit(() -> {go.await(); return put("/" + id, concurrentUpdate.deepCopy());});
            go.countDown();
            ResponseEntity<String> leftResult = left.get(30, TimeUnit.SECONDS);
            ResponseEntity<String> rightResult = right.get(30, TimeUnit.SECONDS);
            assertThat(leftResult.getStatusCode().value()).as(leftResult.getBody()).isEqualTo(200);
            assertThat(rightResult.getStatusCode().value()).as(rightResult.getBody()).isEqualTo(200);
            assertThat(mapper.readTree(leftResult.getBody()).path("revision").asLong()).isEqualTo(3);
            assertThat(mapper.readTree(rightResult.getBody()).path("revision").asLong()).isEqualTo(3);
        }
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM runtime_link_binding_revision WHERE link_binding_id=?::uuid
                """, Integer.class, id)).isEqualTo(3);

        // Different UUIDs racing the same expected revision must not create two
        // immutable revisions or return an unhandled SQL 500.
        ObjectNode competingUpdateA = next.deepCopy();
        competingUpdateA.put("expectedRevision", 3);
        competingUpdateA.put("idempotencyKey", UUID.randomUUID().toString());
        ObjectNode competingUpdateB = competingUpdateA.deepCopy();
        competingUpdateB.put("idempotencyKey", UUID.randomUUID().toString());
        try (var workers = Executors.newFixedThreadPool(2)) {
            CountDownLatch go = new CountDownLatch(1);
            var left = workers.submit(() -> {go.await(); return put("/" + id, competingUpdateA);});
            var right = workers.submit(() -> {go.await(); return put("/" + id, competingUpdateB);});
            go.countDown();
            ResponseEntity<String> leftResult = left.get(30, TimeUnit.SECONDS);
            ResponseEntity<String> rightResult = right.get(30, TimeUnit.SECONDS);
            assertThat(java.util.List.of(leftResult.getStatusCode().value(), rightResult.getStatusCode().value()))
                    .containsExactlyInAnyOrder(200, 409);
            assertThat((leftResult.getStatusCode().value() == 409 ? leftResult : rightResult).getBody())
                    .contains("BINDING_REVISION_CONFLICT");
        }
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM runtime_link_binding_revision WHERE link_binding_id=?::uuid
                """, Integer.class, id)).isEqualTo(4);

        // CREATE same UUID: both clients must recover the same durable Head.
        ObjectNode concurrentCreate = request.deepCopy();
        concurrentCreate.put("linkTypeId", "equipment-parallel-inspections");
        concurrentCreate.put("idempotencyKey", UUID.randomUUID().toString());
        try (var workers = Executors.newFixedThreadPool(2)) {
            CountDownLatch go = new CountDownLatch(1);
            var left = workers.submit(() -> {go.await(); return post("", concurrentCreate.deepCopy());});
            var right = workers.submit(() -> {go.await(); return post("", concurrentCreate.deepCopy());});
            go.countDown();
            ResponseEntity<String> leftResult = left.get(30, TimeUnit.SECONDS);
            ResponseEntity<String> rightResult = right.get(30, TimeUnit.SECONDS);
            assertThat(leftResult.getStatusCode().value()).as(leftResult.getBody()).isEqualTo(201);
            assertThat(rightResult.getStatusCode().value()).as(rightResult.getBody()).isEqualTo(201);
            assertThat(mapper.readTree(leftResult.getBody()).path("linkBindingId").asText())
                    .isEqualTo(mapper.readTree(rightResult.getBody()).path("linkBindingId").asText());
        }
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM runtime_link_binding
                 WHERE package_id=? AND link_type_id='equipment-parallel-inspections'
                """, Integer.class, pkg)).isEqualTo(1);

        // CREATE different UUIDs for the same identity: exactly one Head.
        ObjectNode competingCreateA = request.deepCopy();
        competingCreateA.put("linkTypeId", "equipment-competing-inspections");
        competingCreateA.put("idempotencyKey", UUID.randomUUID().toString());
        ObjectNode competingCreateB = competingCreateA.deepCopy();
        competingCreateB.put("idempotencyKey", UUID.randomUUID().toString());
        try (var workers = Executors.newFixedThreadPool(2)) {
            CountDownLatch go = new CountDownLatch(1);
            var left = workers.submit(() -> {go.await(); return post("", competingCreateA);});
            var right = workers.submit(() -> {go.await(); return post("", competingCreateB);});
            go.countDown();
            ResponseEntity<String> leftResult = left.get(30, TimeUnit.SECONDS);
            ResponseEntity<String> rightResult = right.get(30, TimeUnit.SECONDS);
            assertThat(java.util.List.of(leftResult.getStatusCode().value(), rightResult.getStatusCode().value()))
                    .containsExactlyInAnyOrder(201, 409);
            assertThat((leftResult.getStatusCode().value() == 409 ? leftResult : rightResult).getBody())
                    .contains("LINK_BINDING_ALREADY_EXISTS");
        }
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM runtime_link_binding
                 WHERE package_id=? AND link_type_id='equipment-competing-inspections'
                """, Integer.class, pkg)).isEqualTo(1);

        ResponseEntity<String> historical = http.getForEntity(URI.create(url("/" + id)
                + "?packageId=" + pkg + "&revision=1"), String.class);
        assertThat(historical.getStatusCode().value()).isEqualTo(200);
        JsonNode originalRevision = mapper.readTree(historical.getBody());
        assertThat(originalRevision.path("historical").asBoolean()).isTrue();
        assertThat(originalRevision.path("digest").asText()).isEqualTo(first.path("digest").asText());
        assertThat(originalRevision.path("validationQuality")).isEqualTo(first.path("validationQuality"));
        assertThat(originalRevision.path("sourceBindingDigest").asText())
                .isEqualTo(equipment.digest());

        // Reopen/replay must not leak historical source evidence after SELECT is revoked.
        jdbc.execute("REVOKE SELECT ON public." + INSPECTION + " FROM ontoforge_binding_reader");
        try {
            ResponseEntity<String> hidden = http.getForEntity(URI.create(url("/" + id)
                    + "?packageId=" + pkg + "&revision=1"), String.class);
            assertThat(hidden.getStatusCode().value()).isEqualTo(403);
            assertThat(hidden.getBody()).contains("LINK_SOURCE_UNAVAILABLE");
            assertThat(hidden.getBody()).doesNotContain(equipment.sourceSchemaFingerprint());
            ResponseEntity<String> unknownReplayAfterRevocation = put("/" + id, next);
            assertThat(unknownReplayAfterRevocation.getStatusCode().value()).isEqualTo(403);
            assertThat(unknownReplayAfterRevocation.getBody()).doesNotContain("sourceBindingDigest");
        } finally {
            jdbc.execute("GRANT SELECT ON public." + INSPECTION + " TO ontoforge_binding_reader");
        }

        jdbc.execute("ALTER TABLE public." + INSPECTION + " ADD COLUMN m8_drift TEXT");
        try {
            ResponseEntity<String> drift = post("/validate", request);
            assertThat(drift.getStatusCode().value()).isEqualTo(409);
            assertThat(drift.getBody()).contains("LINK_SOURCE_SCHEMA_DRIFT");
        } finally {
            jdbc.execute("ALTER TABLE public." + INSPECTION + " DROP COLUMN m8_drift");
        }
        assertThatThrownBy(() -> sourceJdbc.update(
                "UPDATE public." + INSPECTION + " SET status=status WHERE lot_code='LOT-11'"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);

        // Demand >8MiB from a single source FK field; fail during streaming,
        // never after holding a full, possibly unbounded source table in memory.
        jdbc.execute("INSERT INTO public." + INSPECTION
                + " (lot_code,equipment_code,status,amount) VALUES ('LOT-HUGE',repeat('Z',8388609),'FAIL',0)");
        try {
            ResponseEntity<String> huge = post("/validate", request);
            assertThat(huge.getStatusCode().value()).isEqualTo(429);
            assertThat(huge.getBody()).contains("QUERY_BUDGET_EXCEEDED");
        } finally {
            jdbc.execute("DELETE FROM public." + INSPECTION + " WHERE lot_code='LOT-HUGE'");
        }

        // Unified budget: BOTH ends together <= 10k physical rows; do not use
        // independent 10k caps or a truncated sample to claim FULL validation.
        jdbc.execute("INSERT INTO public." + INSPECTION
                + " SELECT 'BULK-' || lpad(g::text,5,'0'),NULL,'PASS' FROM generate_series(1,10001) AS g");
        try {
            ResponseEntity<String> overRows = post("/validate", request);
            assertThat(overRows.getStatusCode().value()).isEqualTo(429);
            assertThat(overRows.getBody()).contains("QUERY_BUDGET_EXCEEDED");
        } finally {
            jdbc.execute("DELETE FROM public." + INSPECTION + " WHERE lot_code LIKE 'BULK-%'");
        }

        // Run cache-pressure LAST, after version/CAS/cursor tests. Filling the
        // intentionally tiny service-wide cache earlier would invalidate the
        // independent golden stories, not the business logic under test.
        jdbc.execute("INSERT INTO public." + INSPECTION
                + " SELECT 'PRESS-' || lpad(g::text,5,'0'),'EQ-001','PASS',1.00"
                + " FROM generate_series(1,1000) AS g");
        try {
            // L15-L16: concurrent real HTTP queries compete for ONE service-wide
            // frozen-result cache (32 slots, <=16 MiB retained JSON) and 4 active\n            // builders, not
            // isolated per-thread mock caches. Accepted queries retain signed
            // snapshot identity; rejected queries expose no partial payload.
            ObjectNode concurrentPage = traverseRequest(pkg, snapshotId, equipment, lot, id,
                    "equipment-inspections", "OUT", "EQ-001", 1);
            // The preceding CAS tests intentionally advanced this Link Head to 4.
            ((ObjectNode) concurrentPage.withArray("steps").get(0).path("linkBindingRef"))
                    .put("revision", 4);
            java.util.Set<String> acceptedSnapshotRefs = new java.util.HashSet<>();
            String survivingCursor = null;
            int admittedFrozen = 0;
            int refused = 0;
            int timedOut = 0;
            try (var callers = Executors.newFixedThreadPool(6)) {
                CountDownLatch release = new CountDownLatch(1);
                java.util.List<java.util.concurrent.Future<ResponseEntity<String>>> pending =
                        new java.util.ArrayList<>();
                for (int n = 0; n < 40; n++) {
                    pending.add(callers.submit(() -> {
                        release.await();
                        return traverse(concurrentPage.deepCopy());
                    }));
                }
                release.countDown();
                for (var pendingResponse : pending) {
                    ResponseEntity<String> reply = pendingResponse.get(90, TimeUnit.SECONDS);
                    JsonNode body = mapper.readTree(reply.getBody());
                    if (reply.getStatusCode().value() == 200) {
                        assertExactJsonBudget(body);
                        assertThat(body.path("objects").size()).isEqualTo(1);
                        assertThat(body.path("links").size()).isEqualTo(1);
                        assertThat(body.path("nextCursor").asText()).startsWith("v2.");
                        assertThat(acceptedSnapshotRefs.add(body.path("resultSnapshotRef").asText()))
                                .as("Each accepted query freezes a unique result identity").isTrue();
                        admittedFrozen++;
                        survivingCursor = body.path("nextCursor").asText();
                    } else {
                        assertThat(reply.getStatusCode().value())
                                .as(reply.getBody()).isIn(429, 503);
                        assertThat(body.path("code").asText())
                                .isIn("RESULT_SNAPSHOT_BUDGET", "QUERY_CONCURRENCY_BUDGET", "QUERY_TIMEOUT");
                        assertThat(body.has("objects")).isFalse();
                        assertThat(body.has("links")).isFalse();
                        assertThat(body.has("nextCursor")).isFalse();
                        if (reply.getStatusCode().value() == 429) refused++;
                        else timedOut++;
                    }
                }
            }
            assertThat(admittedFrozen).as("No more than 32 service-wide frozen snapshots")
                    .isBetween(1, 32);
            assertThat(refused).as("Concurrent execution/cache admission must refuse overflow")
                    .isGreaterThan(0);
            assertThat(admittedFrozen + refused + timedOut).isEqualTo(40);
            assertThat(survivingCursor).isNotNull();
            ResponseEntity<String> stillValid = traversePage(survivingCursor);
            assertThat(stillValid.getStatusCode().value()).as(stillValid.getBody()).isEqualTo(200);
            assertThat(mapper.readTree(stillValid.getBody()).path("objects").size()).isEqualTo(1);

        } finally {
            jdbc.execute("DELETE FROM public." + INSPECTION + " WHERE lot_code LIKE 'PRESS-%'");
        }
    }

    private static void assertExactJsonBudget(JsonNode result) {
        long actual = result.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        assertThat(result.path("budget").path("serializedResultBytes").asLong())
                .as("UTF-8 bytes must include the final digit width of the budget field itself")
                .isEqualTo(actual);
    }

    @Test
    void bridgeV2RealPgHttpFullBucketsCasReplayRevokeAndBudget() throws Exception {
        String pkg = "m8.bridge.p" + UUID.randomUUID().toString().replace("-", "");
        NativeDraftService.DraftDetail draft = drafts.create(pkg);
        ObjectNode editor = mapper.createObjectNode();
        editor.put("editorSchemaVersion", "0.1.0");
        ArrayNode types = editor.putArray("objectTypes");
        addType(types, "equipment", "Equipment", "equipment-code", "equipmentCode",
                "equipment-label", "label", "equipment-site", "site");
        addType(types, "inspection-lot", "InspectionLot", "lot-code", "lotCode",
                "equipment-ref", "equipmentRef", "inspection-status", "status");
        decimalProperty((ArrayNode) types.get(1).path("properties"), "inspection-amount", "amount");
        ArrayNode links = editor.putArray("linkTypes");
        link(links, "equipment-inspections-bridge", "equipmentInspectionsBridge", "MANY_TO_MANY");
        var saved = drafts.save(UUID.fromString(draft.draftId()), draft.revision(), editor);
        assertThat(drafts.validate(UUID.fromString(draft.draftId()), saved.revision()).status())
                .isEqualTo("VALID");
        String snap = snapshots.freeze(UUID.fromString(draft.draftId()),
                saved.revision(), UUID.randomUUID()).snapshot().snapshotId();
        var equipment = createBinding(snap, "equipment", EQUIPMENT, "equipment_code",
                new String[][]{{"equipment-code","equipment_code"},
                        {"equipment-label","label"},{"equipment-site","site"}});
        var lot = createBinding(snap, "inspection-lot", INSPECTION, "lot_code",
                new String[][]{{"lot-code","lot_code"},{"equipment-ref","equipment_code"},
                        {"inspection-status","status"}, {"inspection-amount","amount"}});
        String bridgeFp = bindings.listTables(pkg, null).stream().filter(t -> BRIDGE.equals(t.table()))
                .findFirst().orElseThrow().schemaFingerprint();
        ObjectNode request = mapper.createObjectNode();
        request.put("contractVersion", "local-link-binding-v2");
        request.putObject("modelSourceRef").put("kind", "LOCAL_SNAPSHOT").put("snapshotId", snap);
        request.put("packageId", pkg);
        request.put("linkTypeId", "equipment-inspections-bridge");
        ref(request.putObject("sourceBindingRef"), equipment);
        ref(request.putObject("targetBindingRef"), lot);
        request.put("mode", "BRIDGE_TABLE");
        request.putObject("bridgeSourceRef")
                .put("sourceId", "local-pg-sandbox").put("schema", "public")
                .put("table", BRIDGE).put("sourceFkColumn", "equipment_code")
                .put("targetFkColumn", "lot_code");
        request.put("expectedRevision", 0);
        request.put("idempotencyKey", UUID.randomUUID().toString());
        request.putObject("expectedSchemaFingerprints")
                .put("source", equipment.sourceSchemaFingerprint())
                .put("target", lot.sourceSchemaFingerprint()).put("bridge", bridgeFp);
        request.putObject("validationScope").put("kind", "VALIDATION_SCOPE")
                .put("coverage", "FULL");

        ResponseEntity<String> full = post("/validate", request);
        assertThat(full.getStatusCode().value()).as(full.getBody()).isEqualTo(200);
        JsonNode quality = mapper.readTree(full.getBody()).path("quality");
        assertThat(quality.path("bridgePhysicalRows").asInt()).isEqualTo(9);
        assertThat(quality.path("matchedPhysicalRows").asInt()).isEqualTo(3);
        assertThat(quality.path("uniquePairCount").asInt()).isEqualTo(2);
        assertThat(quality.path("duplicatePairRowCount").asInt()).isEqualTo(1);
        for (String key : new String[]{"nullBoth","nullSourceOnly","nullTargetOnly",
                "orphanSourceOnly","orphanTargetOnly","orphanBoth"}) {
            assertThat(quality.path(key).asInt()).as(key).isEqualTo(1);
        }
        assertThat(mapper.readTree(full.getBody()).path("status").asText())
                .isEqualTo("VALID_WITH_ORPHANS");

        ResponseEntity<String> savedResponse = post("", request);
        assertThat(savedResponse.getStatusCode().value()).as(savedResponse.getBody()).isEqualTo(201);
        JsonNode first = mapper.readTree(savedResponse.getBody());
        String id = first.path("linkBindingId").asText();
        assertThat(first.path("mappingMode").asText()).isEqualTo("BRIDGE_TABLE");
        assertThat(first.path("validationQuality").path("duplicatePairRowCount").asInt())
                .isEqualTo(1);
        assertThat(first.path("bridgeSourceRef").path("table").asText()).isEqualTo(BRIDGE);
        assertThat(first.path("digest").asText()).matches("[0-9a-f]{64}");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM runtime_link_binding_revision
                WHERE link_binding_id=?::uuid AND mapping_mode='BRIDGE_TABLE'
                  AND fk_owner IS NULL AND fk_source_column IS NULL
                  AND referenced_identity_column IS NULL
                """, Integer.class, id)).isEqualTo(1);

        ResponseEntity<String> replay = post("", request);
        assertThat(replay.getStatusCode().value()).as(replay.getBody()).isEqualTo(201);
        assertThat(mapper.readTree(replay.getBody()).path("digest").asText())
                .isEqualTo(first.path("digest").asText());

        ObjectNode altered = request.deepCopy();
        altered.put("linkTypeId", "equipment-inspections-bridge");
        ((ObjectNode) altered.path("expectedSchemaFingerprints"))
                .put("bridge", bridgeFp.startsWith("0") ? "1".repeat(64) : "0".repeat(64));
        assertThat(post("", altered).getBody()).contains("IDEMPOTENCY_KEY_CONFLICT");

        ObjectNode unapproved = request.deepCopy();
        unapproved.put("clientSql", "SELECT current_user");
        unapproved.put("idempotencyKey", UUID.randomUUID().toString());
        assertThat(post("/validate", unapproved).getStatusCode().value()).isEqualTo(422);
        ObjectNode fakeForeignKey = request.deepCopy();
        fakeForeignKey.put("fkOwner", "SOURCE");
        assertThat(post("/validate", fakeForeignKey).getStatusCode().value()).isEqualTo(422);

        ObjectNode update = request.deepCopy();
        update.put("expectedRevision", 1);
        update.put("idempotencyKey", UUID.randomUUID().toString());
        ResponseEntity<String> second = put("/" + id, update);
        assertThat(second.getStatusCode().value()).as(second.getBody()).isEqualTo(200);
        assertThat(mapper.readTree(second.getBody()).path("revision").asLong()).isEqualTo(2);
        assertThat(put("/" + id, update).getStatusCode().value()).isEqualTo(200);
        ObjectNode conflict = update.deepCopy();
        conflict.put("idempotencyKey", UUID.randomUUID().toString());
        assertThat(put("/" + id, conflict).getStatusCode().value()).isEqualTo(409);

        // T3: query one frozen Bridge revision against the actual PG17 source.
        // Later physical duplicates and orphan duplicates must not rewrite the
        // immutable FULL revision. They must update CURRENT root-scoped evidence.
        jdbc.execute("INSERT INTO public." + BRIDGE
                + "(equipment_code,lot_code) VALUES "
                + "('EQ-001','LOT-12'),('EQ-001','LOT-12'),"
                + "('EQ-002','LOT-12'),('EQ-001','LOT-NO')");
        ObjectNode out = traverseRequest(pkg, snap, equipment, lot, id,
                "equipment-inspections-bridge", "OUT", "EQ-001", 1);
        ((ObjectNode) out.withArray("steps").get(0).path("linkBindingRef")).put("revision", 2);
        ResponseEntity<String> firstPage = traverse(out);
        assertThat(firstPage.getStatusCode().value()).as(firstPage.getBody()).isEqualTo(200);
        JsonNode one = mapper.readTree(firstPage.getBody());
        assertExactJsonBudget(one);
        assertThat(one.path("linkMappingMode").asText()).isEqualTo("BRIDGE_TABLE");
        assertThat(one.path("bridgeDuplicatePairRowCount").asInt()).isEqualTo(2);
        // 2 SOURCE rows + 5 TARGET rows + 13 BRIDGE rows. Omitting BRIDGE
        // would produce a misleading 7-row budget despite consuming 20 rows.
        assertThat(one.path("budget").path("physicalRows").asInt()).isEqualTo(20);
        // An unrelated EQ-002 duplicate must NOT leak into EQ-001's
        // TRAVERSAL_SCOPE duplicate count (the global count is now 3).
        assertThat(one.path("quality").path("completeness").asText()).isEqualTo("PARTIAL");
        assertThat(one.path("quality").path("knownOrphanCount").asInt()).isEqualTo(1);
        assertThat(one.path("sourceReadRefs").size()).isEqualTo(3);
        assertThat(one.path("versionLock").path("sourceSchemaFingerprints").size()).isEqualTo(3);
        assertThat(one.path("sourceReadRefs").toString()).contains("BRIDGE");
        assertThat(one.path("links").get(0).path("sourceReadRefs").size()).isEqualTo(3);
        assertThat(one.path("links").get(0).path("source").path("objectTypeId").asText())
                .isEqualTo("equipment");
        assertThat(one.path("links").get(0).path("target").path("objectTypeId").asText())
                .isEqualTo("inspection-lot");
        String pageToken = one.path("nextCursor").asText();
        assertThat(pageToken).startsWith("v2.");
        String forgedCursor = pageToken.substring(0, 3)
                + (pageToken.charAt(3) == 'A' ? 'B' : 'A') + pageToken.substring(4);
        ResponseEntity<String> tamperedCursor = traversePage(forgedCursor);
        assertThat(tamperedCursor.getStatusCode().value()).isEqualTo(400);
        assertThat(tamperedCursor.getBody()).doesNotContain("LOT-11", BRIDGE);
        // C09/B11: frozen pages are stable even if CURRENT source facts change
        // after page one. A new execution sees the new facts instead.
        assertThat(jdbc.update("DELETE FROM public." + BRIDGE
                + " WHERE equipment_code='EQ-001' AND lot_code='LOT-12'")).isEqualTo(2);
        JsonNode freshAfterDelete = mapper.readTree(traverse(out).getBody());
        assertThat(freshAfterDelete.path("objects").size()).isEqualTo(1);
        assertThat(freshAfterDelete.path("nextCursor").isMissingNode()).isTrue();
        assertThat(freshAfterDelete.path("budget").path("physicalRows").asInt()).isEqualTo(18);
        ResponseEntity<String> pageTwo = traversePage(pageToken);
        assertThat(pageTwo.getStatusCode().value()).as(pageTwo.getBody()).isEqualTo(200);
        JsonNode two = mapper.readTree(pageTwo.getBody());
        assertExactJsonBudget(two);
        assertThat(two.path("objects").size()).isEqualTo(1);
        assertThat(two.path("resultSnapshotRef")).isEqualTo(one.path("resultSnapshotRef"));
        assertThat(two.path("nextCursor").isMissingNode()).isTrue();
        assertThat(two.path("objects").get(0).path("objectRef").path("key"))
                .isNotEqualTo(one.path("objects").get(0).path("objectRef").path("key"));
        assertThat(two.path("budget").path("physicalRows").asInt()).isEqualTo(20);
        jdbc.execute("INSERT INTO public." + BRIDGE
                + "(equipment_code,lot_code) VALUES ('EQ-001','LOT-12'),('EQ-001','LOT-12')");

        ObjectNode clean = traverseRequest(pkg, snap, equipment, lot, id,
                "equipment-inspections-bridge", "OUT", "EQ-002", 50);
        ((ObjectNode) clean.withArray("steps").get(0).path("linkBindingRef")).put("revision", 2);
        JsonNode scoped = mapper.readTree(traverse(clean).getBody());
        assertThat(scoped.path("quality").path("completeness").asText()).isEqualTo("COMPLETE");
        assertThat(scoped.path("linkCount").asInt()).isEqualTo(1);
        assertThat(scoped.path("bridgeDuplicatePairRowCount").asInt()).isEqualTo(1);
        assertThat(scoped.path("quality").path("knownOrphanCount").asInt()).isZero();
        assertThat(scoped.path("budget").path("physicalRows").asInt()).isEqualTo(20);

        ObjectNode inbound = traverseRequest(pkg, snap, lot, equipment, id,
                "equipment-inspections-bridge", "IN", "LOT-11", 50);
        ((ObjectNode) inbound.withArray("steps").get(0).path("linkBindingRef")).put("revision", 2);
        ResponseEntity<String> inboundResponse = traverse(inbound);
        assertThat(inboundResponse.getStatusCode().value()).as(inboundResponse.getBody()).isEqualTo(200);
        JsonNode reverse = mapper.readTree(inboundResponse.getBody());
        assertThat(reverse.path("quality").path("completeness").asText()).isEqualTo("PARTIAL");
        assertThat(reverse.path("quality").path("knownOrphanCount").asInt()).isEqualTo(1);
        assertThat(reverse.path("linkCount").asInt()).isEqualTo(1);
        assertThat(reverse.path("links").get(0).path("source").path("objectTypeId").asText())
                .isEqualTo("equipment");

        ObjectNode aggregate = out.deepCopy();
        ArrayNode steps = aggregate.withArray("steps");
        steps.remove(steps.size() - 1);
        steps.addObject().put("kind", "AGGREGATE").put("operation", "SUM")
                .put("propertyRef", "inspection-amount");
        ResponseEntity<String> aggregateResponse = traverse(aggregate);
        assertThat(aggregateResponse.getStatusCode().value()).as(aggregateResponse.getBody()).isEqualTo(200);
        JsonNode sum = mapper.readTree(aggregateResponse.getBody());
        assertThat(sum.path("aggregate").path("matchedObjects").asInt()).isEqualTo(2);
        assertThat(sum.path("aggregate").path("value").asText()).isEqualTo("30");
        assertThat(sum.path("quality").path("answerScope").asText()).isEqualTo("KNOWN_SUBSET");
        ((ObjectNode) steps.get(steps.size() - 1)).put("operation", "COUNT_DISTINCT").remove("propertyRef");
        JsonNode distinctCount = mapper.readTree(traverse(aggregate).getBody());
        assertThat(distinctCount.path("aggregate").path("value").asText()).isEqualTo("2");

        ObjectNode limitedJoin = out.deepCopy();
        limitedJoin.putObject("limits").put("maxJoinCandidates", 1);
        ResponseEntity<String> joinBlocked = traverse(limitedJoin);
        assertThat(joinBlocked.getStatusCode().value()).isEqualTo(429);
        assertThat(joinBlocked.getBody()).contains("QUERY_BUDGET_EXCEEDED");
        assertThat(joinBlocked.getBody()).doesNotContain("LOT-11", BRIDGE);
        ObjectNode limitedPhysical = out.deepCopy();
        limitedPhysical.putObject("limits").put("maxPhysicalRows", 2);
        ResponseEntity<String> physicalBlocked = traverse(limitedPhysical);
        assertThat(physicalBlocked.getStatusCode().value()).isEqualTo(429);
        assertThat(physicalBlocked.getBody()).contains("QUERY_BUDGET_EXCEEDED");
        assertThat(physicalBlocked.getBody()).doesNotContain("LOT-11", BRIDGE);
        String revokeCursor = mapper.readTree(traverse(out).getBody()).path("nextCursor").asText();
        assertThat(revokeCursor).startsWith("v2.");

        // A revoked Bridge grant forbids retrieval of past full source/quality.
        jdbc.execute("REVOKE SELECT ON public." + BRIDGE + " FROM ontoforge_binding_reader");
        try {
            ResponseEntity<String> denied = http.getForEntity(URI.create(url("/" + id
                    + "?packageId=" + pkg)), String.class);
            assertThat(denied.getStatusCode().value()).isEqualTo(403);
            assertThat(denied.getBody()).doesNotContain("EQ-001", BRIDGE, bridgeFp);
            ResponseEntity<String> revoked = traversePage(revokeCursor);
            assertThat(revoked.getStatusCode().value()).isIn(403,409,410);
            assertThat(revoked.getBody()).doesNotContain("LOT-11", BRIDGE);
        } finally {
            jdbc.execute("GRANT SELECT ON public." + BRIDGE + " TO ontoforge_binding_reader");
        }
        assertThat(traversePage(revokeCursor).getStatusCode().value()).isEqualTo(410);

        // C06: SQL empty TEXT is not SQL NULL. Although PostgreSQL accepts
        // '' as a PK, M6 ObjectRef prohibits blank value and FULL must 409,
        // not save a malformed identity or mislabel the row as an orphan.
        jdbc.execute("INSERT INTO public." + EQUIPMENT
                + "(equipment_code,label,site,cost) VALUES ('','bad identity','A',0)");
        try {
            ResponseEntity<String> rejected = post("/validate", request);
            assertThat(rejected.getStatusCode().value()).isEqualTo(409);
            assertThat(rejected.getBody()).contains("OBJECT_IDENTITY_UNREPRESENTABLE");
            assertThat(rejected.getBody()).doesNotContain("EQ-001", BRIDGE);
            ResponseEntity<String> invalidTraverse = traverse(out);
            assertThat(invalidTraverse.getStatusCode().value()).isEqualTo(409);
            assertThat(invalidTraverse.getBody()).doesNotContain("LOT-11", BRIDGE);
        } finally {
            jdbc.execute("DELETE FROM public." + EQUIPMENT + " WHERE equipment_code=''");
        }

        // C09: a real Bridge DDL changes its independently frozen schema.
        // The UI must revalidate instead of silently using a saved revision.
        String ddlCursor = mapper.readTree(traverse(out).getBody())
                .path("nextCursor").asText();
        assertThat(ddlCursor).startsWith("v2.");
        jdbc.execute("ALTER TABLE public." + BRIDGE + " ADD COLUMN t5_schema_probe TEXT");
        try {
            ResponseEntity<String> drift = post("/validate", request);
            assertThat(drift.getStatusCode().value()).isEqualTo(409);
            assertThat(drift.getBody()).contains("LINK_SOURCE_SCHEMA_DRIFT");
            ResponseEntity<String> staleDetail = http.getForEntity(
                    URI.create(url("/" + id + "?packageId=" + pkg)), String.class);
            assertThat(staleDetail.getStatusCode().value()).isIn(403, 409);
            assertThat(staleDetail.getBody()).doesNotContain("EQ-001", "LOT-11", BRIDGE);
            ResponseEntity<String> staleTraverse = traverse(out);
            assertThat(staleTraverse.getStatusCode().value()).isIn(403, 409);
            assertThat(staleTraverse.getBody()).doesNotContain("EQ-001", "LOT-11", BRIDGE);
            ResponseEntity<String> driftedPage = traversePage(ddlCursor);
            assertThat(driftedPage.getStatusCode().value()).isIn(403, 409, 410);
            assertThat(driftedPage.getBody()).doesNotContain("EQ-001", "LOT-11", BRIDGE);
        } finally {
            jdbc.execute("ALTER TABLE public." + BRIDGE + " DROP COLUMN t5_schema_probe");
        }
        // After restoring the original schema, a fresh authorized FULL works;
        // retired cursors still remain retired from the preceding revoke.
        assertThat(post("/validate", request).getStatusCode().value()).isEqualTo(200);
        assertThat(traversePage(ddlCursor).getStatusCode().value()).isEqualTo(410);

        // C07/B08: two genuinely concurrent Spring HTTP requests compete
        // for the SAME persisted Head and expectedRevision=2. Exactly one
        // request may advance to immutable revision 3; the loser is 409.
        // This proves same-process PG lock/CAS, NOT multi-JVM recovery.
        ObjectNode concurrentA = update.deepCopy();
        concurrentA.put("expectedRevision", 2);
        concurrentA.put("idempotencyKey", UUID.randomUUID().toString());
        ObjectNode concurrentB = concurrentA.deepCopy();
        concurrentB.put("idempotencyKey", UUID.randomUUID().toString());
        java.util.concurrent.ExecutorService executor =
                java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        try {
            var firstWriter = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try {
                    start.await();
                    return put("/" + id, concurrentA);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(ex);
                }
            }, executor);
            var secondWriter = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try {
                    start.await();
                    return put("/" + id, concurrentB);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(ex);
                }
            }, executor);
            start.countDown();
            ResponseEntity<String> a = firstWriter.get(20, java.util.concurrent.TimeUnit.SECONDS);
            ResponseEntity<String> b = secondWriter.get(20, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(java.util.Set.of(a.getStatusCode().value(), b.getStatusCode().value()))
                    .containsExactlyInAnyOrder(200, 409);
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM runtime_link_binding_revision
                    WHERE link_binding_id=?::uuid AND revision=3
                    """, Integer.class, id)).isEqualTo(1);
            assertThat(jdbc.queryForObject("""
                    SELECT current_revision FROM runtime_link_binding_head WHERE id=?::uuid
                    """, Long.class, id)).isEqualTo(3L);
        } finally {
            executor.shutdownNow();
        }

        // All THREE physical reads must share one 10k budget: no truncation to
        // a misleading success result when a bridge becomes over-budget.
        jdbc.execute("INSERT INTO public." + BRIDGE
                + " SELECT 'EQ-001','LOT-11' FROM generate_series(1,10001)");
        assertThat(post("/validate", request).getStatusCode().value()).isEqualTo(429);
    }

    private DataBindingService.BindingDetail createBinding(String snapshot, String type,
            String table, String key, String[][] fieldColumns) {
        ArrayNode mappingJson = mapper.createArrayNode();
        java.util.List<DataBindingService.FieldMapping> mappings = new java.util.ArrayList<>();
        for (String[] field : fieldColumns) {
            mappings.add(new DataBindingService.FieldMapping(field[0], field[1], "AUTHORITATIVE"));
        }
        DataBindingService.BindingRequest req = new DataBindingService.BindingRequest(
                snapshot, null, null, type, "local-pg-sandbox", "public", table, key,
                mappings, null, 0);
        String fingerprint = bindings.preview(req, null).source().schemaFingerprint();
        return bindings.create(new DataBindingService.BindingRequest(
                snapshot, null, null, type, "local-pg-sandbox", "public", table, key,
                mappings, fingerprint, 0), null);
    }

    private static void ref(ObjectNode node, DataBindingService.BindingDetail d) {
        node.put("bindingId", d.bindingId());
        node.put("revision", d.revision());
        node.put("digest", d.digest());
    }

    private void addType(ArrayNode types, String id, String apiName,
            String pkId, String pkApi, String p1, String p1Api, String p2, String p2Api) {
        ObjectNode obj = types.addObject();
        obj.put("id", id); obj.put("apiName", apiName);
        obj.put("primaryKeyRef", pkId);
        ArrayNode props = obj.putArray("properties");
        property(props, pkId, pkApi, true);
        property(props, p1, p1Api, false);
        property(props, p2, p2Api, true);
    }

    private void property(ArrayNode props, String id, String name, boolean required) {
        ObjectNode p = props.addObject();
        p.put("id", id); p.put("apiName", name); p.put("valueType", "STRING");
        p.put("required", required); p.put("searchable", true);
        p.put("classification", "INTERNAL");
    }

    private void decimalProperty(ArrayNode props, String id, String name) {
        ObjectNode property = props.addObject();
        property.put("id",id);property.put("apiName",name);property.put("valueType","DECIMAL");
        property.put("required",false);property.put("searchable",true);
        property.put("classification","INTERNAL");
    }

    private void link(ArrayNode links, String id, String apiName, String cardinality) {
        ObjectNode node = links.addObject();
        node.put("id", id); node.put("apiName", apiName);
        node.put("sourceTypeRef", "equipment");
        node.put("targetTypeRef", "inspection-lot");
        node.put("cardinality", cardinality);
        node.put("evidenceRequired", true);
    }

    private ObjectNode traverseRequest(String packageId, String snapshotId,
            DataBindingService.BindingDetail root,
            DataBindingService.BindingDetail opposite, String linkId,
            String linkType, String direction, String key, int size) {
        ObjectNode body = mapper.createObjectNode();
        body.put("queryVersion", "local-query-v2");
        ObjectNode type = body.putObject("rootTypeRef");
        type.put("packageId", packageId);
        type.put("objectTypeId", root.objectTypeId());
        type.putObject("sourceRef").put("kind", "LOCAL_SNAPSHOT")
                .put("snapshotId", snapshotId);
        body.putObject("rootBindingRef").put("bindingId", root.bindingId())
                .put("revision", root.revision());
        ObjectNode selector = body.putObject("rootSelector");
        selector.put("kind", "OBJECT_REF");
        ObjectNode ref = selector.putObject("objectRef");
        ref.put("packageId", packageId);
        ref.put("objectTypeId", root.objectTypeId());
        ref.put("identityDomainId", root.identityDomainId());
        ref.putObject("key").put("valueType", "STRING").put("value", key);
        ArrayNode steps = body.putArray("steps");
        ObjectNode traverse = steps.addObject();
        traverse.put("kind", "TRAVERSE");
        traverse.put("linkTypeRef", linkType);
        traverse.putObject("linkBindingRef").put("bindingId", linkId).put("revision", 1);
        traverse.put("direction", direction);
        traverse.put("maxDepth", 1);
        steps.addObject().put("kind", "PAGE").put("pageSize", size);
        return body;
    }

    private ResponseEntity<String> traversePage(String cursor) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ObjectNode body = mapper.createObjectNode().put("cursor", cursor);
        return http.postForEntity(URI.create("http://127.0.0.1:" + port
                        + "/api/runtime/object-sets/traverse/pages"),
                new HttpEntity<>(body.toString(), headers), String.class);
    }

    private ResponseEntity<String> traverse(ObjectNode body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return http.postForEntity(URI.create("http://127.0.0.1:" + port
                        + "/api/runtime/object-sets/traverse"),
                new HttpEntity<>(body.toString(), headers), String.class);
    }

    private String url(String path) {
        return "http://127.0.0.1:" + port + "/api/runtime/link-bindings" + path;
    }
    private ResponseEntity<String> post(String path, ObjectNode body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return http.postForEntity(URI.create(url(path)),
                new HttpEntity<>(body.toString(), headers), String.class);
    }
    private ResponseEntity<String> put(String path, ObjectNode body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange(URI.create(url(path)), HttpMethod.PUT,
                new HttpEntity<>(body.toString(), headers), String.class);
    }
}
