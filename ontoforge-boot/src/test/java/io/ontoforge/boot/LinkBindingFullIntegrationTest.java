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

    @DynamicPropertySource
    static void sources(DynamicPropertyRegistry properties) {
        properties.add("ontoforge.binding.source.url", () -> System.getenv("TEST_DB_URL"));
        properties.add("ontoforge.binding.source.username", () -> "ontoforge_binding_login");
        properties.add("ontoforge.binding.source.password", () -> PASSWORD);
        properties.add("ontoforge.binding.source.allowed-tables", () -> EQUIPMENT + "," + INSPECTION);
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
        jdbc.execute("DROP TABLE IF EXISTS public." + INSPECTION);
        jdbc.execute("DROP TABLE IF EXISTS public." + EQUIPMENT);
        jdbc.execute("""
                CREATE TABLE public.m8_equipment_source (
                    equipment_code TEXT PRIMARY KEY, label TEXT NOT NULL, site TEXT NOT NULL)
                """);
        jdbc.execute("""
                CREATE TABLE public.m8_inspection_source (
                    lot_code TEXT PRIMARY KEY, equipment_code TEXT NULL, status TEXT NOT NULL)
                """);
        jdbc.update("""
                INSERT INTO public.m8_equipment_source(equipment_code,label,site)
                VALUES ('EQ-001','Lathe 1','A'),('EQ-002','Lathe 2','B')
                """);
        jdbc.update("""
                INSERT INTO public.m8_inspection_source(lot_code,equipment_code,status)
                VALUES ('LOT-11','EQ-001','PASS'),('LOT-12','EQ-001','PASS'),
                       ('LOT-13',NULL,'HOLD'),('LOT-14','EQ-NO','FAIL'),
                       ('LOT-15','','FAIL')
                """);
        jdbc.execute("GRANT SELECT ON public." + EQUIPMENT + " TO ontoforge_binding_reader");
        jdbc.execute("GRANT SELECT ON public." + INSPECTION + " TO ontoforge_binding_reader");
    }

    @AfterEach
    void teardown() {
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
        ArrayNode links = editor.putArray("linkTypes");
        link(links, "equipment-inspections", "equipmentInspections", "ONE_TO_MANY");
        link(links, "equipment-single-inspection", "equipmentSingleInspection", "ONE_TO_ONE");

        NativeDraftService.DraftDetail saved = drafts.save(
                UUID.fromString(draft.draftId()), draft.revision(), editor);
        assertThat(drafts.validate(UUID.fromString(draft.draftId()), saved.revision()).status())
                .isEqualTo("VALID");
        String snapshotId = snapshots.freeze(UUID.fromString(draft.draftId()),
                saved.revision(), UUID.randomUUID()).snapshot().snapshotId();

        DataBindingService.BindingDetail equipment = createBinding(snapshotId, "equipment",
                EQUIPMENT, "equipment_code",
                new String[][]{{"equipment-code", "equipment_code"},
                        {"equipment-label", "label"}, {"equipment-site", "site"}});
        DataBindingService.BindingDetail lot = createBinding(snapshotId, "inspection-lot",
                INSPECTION, "lot_code",
                new String[][]{{"lot-code", "lot_code"}, {"equipment-ref", "equipment_code"},
                        {"inspection-status", "status"}});
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
                + " (lot_code,equipment_code,status) VALUES ('LOT-HUGE',repeat('Z',8388609),'FAIL')");
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

    private void link(ArrayNode links, String id, String apiName, String cardinality) {
        ObjectNode node = links.addObject();
        node.put("id", id); node.put("apiName", apiName);
        node.put("sourceTypeRef", "equipment");
        node.put("targetTypeRef", "inspection-lot");
        node.put("cardinality", cardinality);
        node.put("evidenceRequired", true);
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
