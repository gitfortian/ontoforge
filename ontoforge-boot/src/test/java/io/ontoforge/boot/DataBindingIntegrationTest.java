package io.ontoforge.boot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.ontoforge.boot.model.DataBindingService;
import io.ontoforge.boot.model.NativeDraftService;
import io.ontoforge.boot.model.NativeSnapshotService;
import java.net.URI;
import java.util.List;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"mvp-local", "integration-test"})
@ContextConfiguration(initializers = IsolatedPostgresTestDatabase.class)
class DataBindingIntegrationTest {
    private static final String READER_PASSWORD = "m5_test_reader_only";

    @DynamicPropertySource
    static void sourceProperties(DynamicPropertyRegistry registry) {
        registry.add("ontoforge.binding.source.url", () -> System.getenv("TEST_DB_URL"));
        registry.add("ontoforge.binding.source.username", () -> "ontoforge_binding_login");
        registry.add("ontoforge.binding.source.password", () -> READER_PASSWORD);
        registry.add("ontoforge.binding.source.allowed-tables", () -> "m5_supplier_source");
    }

    @LocalServerPort int port;
    @Autowired TestRestTemplate http;
    @Autowired @Qualifier("jdbcTemplate") JdbcTemplate jdbc;
    @Autowired @Qualifier("bindingSourceJdbcTemplate") JdbcTemplate sourceJdbc;
    @Autowired ObjectMapper mapper;
    @Autowired NativeDraftService drafts;
    @Autowired NativeSnapshotService snapshots;

    private String url(String path) { return "http://127.0.0.1:" + port + path; }

    @BeforeEach
    void provisionDedicatedReaderRole() {
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
        jdbc.execute("REVOKE ALL PRIVILEGES ON ALL TABLES IN SCHEMA public FROM ontoforge_binding_login");
        jdbc.execute("REVOKE ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public FROM ontoforge_binding_login");
        jdbc.execute("GRANT ontoforge_binding_reader TO ontoforge_binding_login");
    }

    @Test
    void sourceAccountHasRealSelectOnlyPrivilegesAndCatalogIsAllowlisted() throws Exception {
        assertThat(jdbc.queryForObject("SELECT rolsuper FROM pg_roles WHERE rolname='ontoforge_binding_login'", Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("SELECT rolsuper OR rolcanlogin OR rolcreatedb OR rolcreaterole OR rolbypassrls FROM pg_roles WHERE rolname='ontoforge_binding_reader'", Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("SELECT has_table_privilege('ontoforge_binding_reader','public.m5_supplier_source','SELECT')", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT has_table_privilege('ontoforge_binding_reader','public.m5_supplier_source','INSERT')", Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("SELECT has_table_privilege('ontoforge_binding_login','public.m5_supplier_source','SELECT')", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT has_table_privilege('ontoforge_binding_login','public.m5_supplier_source','INSERT')", Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("SELECT has_table_privilege('ontoforge_binding_login','public.m5_supplier_source','UPDATE')", Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("SELECT has_table_privilege('ontoforge_binding_login','public.m5_supplier_source','DELETE')", Boolean.class)).isFalse();
        assertThatThrownBy(() -> sourceJdbc.update(
                "UPDATE public.m5_supplier_source SET name=name WHERE vendor_code='__M5_NO_MATCH__'"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> sourceJdbc.update(
                "DELETE FROM public.m5_supplier_source WHERE vendor_code='__M5_NO_MATCH__'"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        try {
            assertThatThrownBy(() -> sourceJdbc.execute(
                    "ALTER TABLE public.m5_supplier_source ADD COLUMN m5_denied_ddl TEXT"))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
        } finally {
            jdbc.execute("ALTER TABLE public.m5_supplier_source DROP COLUMN IF EXISTS m5_denied_ddl");
        }
        assertThat(sourceJdbc.queryForObject("SELECT count(*) FROM public.m5_supplier_source", Integer.class)).isEqualTo(5);
        assertThatThrownBy(() -> sourceJdbc.update(
                "INSERT INTO public.m5_supplier_source(vendor_code,name) VALUES ('M5-DENIED','should-not-write')"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);

        ResponseEntity<String> catalog = http.getForEntity(URI.create(url(
                "/api/runtime/sources/local-pg-sandbox/tables?packageId=m5.test.catalog")), String.class);
        assertThat(catalog.getStatusCode().value()).isEqualTo(200);
        assertThat(catalog.getBody()).contains("m5_supplier_source", "schemaFingerprint", "vendor_code");
        JsonNode catalogJson = mapper.readTree(catalog.getBody());
        boolean vendorCodeIsPrimary = false;
        for (JsonNode column : catalogJson.get(0).path("columns")) {
            if ("vendor_code".equals(column.path("name").asText())) {
                vendorCodeIsPrimary = column.path("primaryKey").asBoolean();
            }
        }
        assertThat(vendorCodeIsPrimary).isTrue();
        ResponseEntity<String> rejected = http.getForEntity(URI.create(url(
                "/api/runtime/sources/unknown/tables?packageId=m5.test.catalog")), String.class);
        assertThat(rejected.getStatusCode().value()).isEqualTo(403);
    }

    @Test
    void customObjectTypeCanPreviewValidateSaveReopenAndCreateCasRevision() throws Exception {
        String packageId = "m5.test.p" + UUID.randomUUID().toString().replace("-", "");
        NativeDraftService.DraftDetail draft = drafts.create(packageId);
        ObjectNode editor = mapper.createObjectNode();
        editor.put("editorSchemaVersion", "0.1.0");
        ArrayNode types = editor.putArray("objectTypes");
        editor.putArray("linkTypes");
        ObjectNode supplier = types.addObject();
        supplier.put("id", "supplier");
        supplier.put("apiName", "Supplier");
        supplier.put("label", "供应商");
        supplier.put("primaryKeyRef", "supplier-code");
        ArrayNode properties = supplier.putArray("properties");
        addProperty(properties, "supplier-code", "supplierCode", "STRING", true);
        addProperty(properties, "supplier-name", "name", "STRING", true);
        addProperty(properties, "supplier-rating", "rating", "DECIMAL", false);
        addProperty(properties, "supplier-active", "active", "BOOLEAN", true);
        addProperty(properties, "supplier-onboarded", "onboardedAt", "DATETIME", false);
        addProperty(properties, "supplier-sequence", "sequence", "INTEGER", true);
        ObjectNode category = addProperty(properties, "supplier-category", "category", "ENUM", false);
        ArrayNode enumValues = category.putArray("enumValues");
        enumValues.add("ELECTRONICS"); enumValues.add("METAL"); enumValues.add("LOGISTICS"); enumValues.add("HARDWARE");

        NativeDraftService.DraftDetail saved = drafts.save(UUID.fromString(draft.draftId()), draft.revision(), editor);
        NativeDraftService.ValidationReport report = drafts.validate(UUID.fromString(draft.draftId()), saved.revision());
        assertThat(report.status()).isEqualTo("VALID");

        ObjectNode draftPreviewRequest = bindingRequest(null);
        draftPreviewRequest.remove("snapshotId");
        draftPreviewRequest.put("draftId", draft.draftId());
        draftPreviewRequest.put("draftRevision", saved.revision());
        ResponseEntity<String> draftPreview = post("/api/runtime/bindings/preview", draftPreviewRequest);
        assertThat(draftPreview.getStatusCode().value()).as("Draft preview error: " + draftPreview.getBody()).isEqualTo(200);
        String draftFingerprint = mapper.readTree(draftPreview.getBody()).path("source").path("schemaFingerprint").asText();
        draftPreviewRequest.put("expectedSchemaFingerprint", draftFingerprint);
        ResponseEntity<String> draftValidation = post("/api/runtime/bindings/validate", draftPreviewRequest);
        assertThat(draftValidation.getStatusCode().value()).isEqualTo(200);
        assertThat(mapper.readTree(draftValidation.getBody()).path("status").asText()).isEqualTo("VALID");
        ResponseEntity<String> draftSave = post("/api/runtime/bindings", draftPreviewRequest);
        assertThat(draftSave.getStatusCode().value()).isEqualTo(422);
        assertThat(draftSave.getBody()).contains("INVALID_TYPE_REFERENCE");

        NativeSnapshotService.FreezeResult frozen = snapshots.freeze(
                UUID.fromString(draft.draftId()), saved.revision(), UUID.randomUUID());
        String snapshotId = frozen.snapshot().snapshotId();

        ObjectNode request = bindingRequest(snapshotId);
        ResponseEntity<String> preview = post("/api/runtime/bindings/preview", request);
        assertThat(preview.getStatusCode().value()).isEqualTo(200);
        JsonNode previewBody = mapper.readTree(preview.getBody());
        assertThat(previewBody.path("sourceRows").size()).isEqualTo(5);
        assertThat(previewBody.path("mappedRows").toString()).contains("Northwind Components");
        assertThat(previewBody.path("mappedRows").get(0).path("supplier-sequence").asText())
                .isEqualTo("9007199254740993");
        String fingerprint = previewBody.path("source").path("schemaFingerprint").asText();
        request.put("expectedSchemaFingerprint", fingerprint);
        assertThat(previewBody.path("sourceRows").get(0).path("vendor_code").asText()).isEqualTo("SUP-1001");

        ObjectNode nonUniqueKey = request.deepCopy();
        nonUniqueKey.put("sourceKey", "name");
        for (JsonNode item : nonUniqueKey.withArray("fieldMappings")) {
            if ("supplier-code".equals(item.path("propertyRef").asText())) {
                ((ObjectNode) item).put("sourceColumn", "name");
            }
        }
        ResponseEntity<String> rejectedKey = post("/api/runtime/bindings/validate", nonUniqueKey);
        assertThat(rejectedKey.getStatusCode().value()).isEqualTo(422);
        assertThat(rejectedKey.getBody()).contains("SOURCE_KEY_NOT_UNIQUE");

        jdbc.execute("ALTER TABLE public.m5_supplier_source ALTER COLUMN rating TYPE DOUBLE PRECISION USING rating::double precision");
        try {
            ResponseEntity<String> rejectedFloat = post("/api/runtime/bindings/preview", request);
            assertThat(rejectedFloat.getStatusCode().value()).isEqualTo(422);
            assertThat(rejectedFloat.getBody()).contains("UNSUPPORTED_MAPPING_TYPE");
        } finally {
            jdbc.execute("ALTER TABLE public.m5_supplier_source ALTER COLUMN rating TYPE NUMERIC(4,2) USING round(rating::numeric, 2)");
        }

        jdbc.execute("ALTER TABLE public.m5_supplier_source ALTER COLUMN rating TYPE NUMERIC(10,4) USING rating::numeric");
        try {
            ResponseEntity<String> precisionDrift = post("/api/runtime/bindings/validate", request);
            assertThat(precisionDrift.getStatusCode().value()).isEqualTo(409);
            assertThat(precisionDrift.getBody()).contains("BINDING_SCHEMA_DRIFT");
        } finally {
            jdbc.execute("ALTER TABLE public.m5_supplier_source ALTER COLUMN rating TYPE NUMERIC(4,2) USING round(rating::numeric, 2)");
        }

        ResponseEntity<String> validation = post("/api/runtime/bindings/validate", request);
        assertThat(validation.getStatusCode().value()).isEqualTo(200);
        assertThat(mapper.readTree(validation.getBody()).path("status").asText()).isEqualTo("VALID");

        ResponseEntity<String> created = post("/api/runtime/bindings", request);
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        JsonNode first = mapper.readTree(created.getBody());
        String bindingId = first.path("bindingId").asText();
        assertThat(first.path("revision").asLong()).isEqualTo(1);
        assertThat(first.path("digest").asText()).matches("[0-9a-f]{64}");

        ResponseEntity<String> listing = http.getForEntity(URI.create(url(
                "/api/runtime/bindings?packageId=" + packageId)), String.class);
        assertThat(listing.getStatusCode().value()).isEqualTo(200);
        assertThat(mapper.readTree(listing.getBody()).size()).isEqualTo(1);

        ResponseEntity<String> reopened = http.getForEntity(URI.create(url(
                "/api/runtime/bindings/" + bindingId + "?revision=1")), String.class);
        assertThat(reopened.getStatusCode().value()).isEqualTo(200);
        assertThat(mapper.readTree(reopened.getBody()).path("snapshotId").asText()).isEqualTo(snapshotId);

        request.put("expectedRevision", 1);
        jdbc.execute("ALTER TABLE public.m5_supplier_source ADD COLUMN m5_test_drift TEXT");
        try {
            ResponseEntity<String> drift = post("/api/runtime/bindings/validate", request);
            assertThat(drift.getStatusCode().value()).isEqualTo(409);
            assertThat(drift.getBody()).contains("BINDING_SCHEMA_DRIFT");
        } finally {
            jdbc.execute("ALTER TABLE public.m5_supplier_source DROP COLUMN IF EXISTS m5_test_drift");
        }
        request.put("sourceKey", "alternate_code");
        for (JsonNode item : request.withArray("fieldMappings")) {
            if ("supplier-code".equals(item.path("propertyRef").asText())) {
                ((ObjectNode) item).put("sourceColumn", "alternate_code");
            }
        }
        ResponseEntity<String> updated = put("/api/runtime/bindings/" + bindingId, request);
        assertThat(updated.getStatusCode().value()).isEqualTo(200);
        JsonNode second = mapper.readTree(updated.getBody());
        assertThat(second.path("revision").asLong()).isEqualTo(2);
        assertThat(second.path("identityDomainId").asText())
                .isNotEqualTo(first.path("identityDomainId").asText());
        JsonNode preservedFirst = mapper.readTree(http.getForEntity(URI.create(url(
                "/api/runtime/bindings/" + bindingId + "?revision=1")), String.class).getBody());
        assertThat(preservedFirst.path("digest").asText()).isEqualTo(first.path("digest").asText());
        assertThat(preservedFirst.path("identityDomainId").asText()).isEqualTo(first.path("identityDomainId").asText());

        ResponseEntity<String> stale = put("/api/runtime/bindings/" + bindingId, request);
        assertThat(stale.getStatusCode().value()).isEqualTo(409);
        assertThat(stale.getBody()).contains("BINDING_REVISION_CONFLICT");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM runtime_binding_revision WHERE binding_id=?::uuid", Integer.class, bindingId))
                .isEqualTo(2);
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE runtime_binding_revision SET checked_rows=0 WHERE binding_id=?::uuid AND revision=1", bindingId))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test
    void customObjectRuntimeReadsTypedValuesAndEvidenceAndRejectsUnknownOrDriftedObjects() throws Exception {
        String packageId = "m6.test.p" + UUID.randomUUID().toString().replace("-", "");
        NativeDraftService.DraftDetail draft = drafts.create(packageId);
        ObjectNode editor = mapper.createObjectNode();
        editor.put("editorSchemaVersion", "0.1.0");
        ArrayNode types = editor.putArray("objectTypes");
        editor.putArray("linkTypes");
        ObjectNode supplier = types.addObject();
        supplier.put("id", "supplier");
        supplier.put("apiName", "Supplier");
        supplier.put("label", "供应商");
        supplier.put("primaryKeyRef", "supplier-code");
        ArrayNode properties = supplier.putArray("properties");
        addProperty(properties, "supplier-code", "vendorCode", "STRING", true);
        addProperty(properties, "supplier-name", "name", "STRING", true);
        addProperty(properties, "supplier-rating", "rating", "DECIMAL", false);
        addProperty(properties, "supplier-active", "active", "BOOLEAN", true);
        addProperty(properties, "supplier-onboarded", "onboardedAt", "DATETIME", false);
        addProperty(properties, "supplier-sequence", "sequence", "INTEGER", true);
        ObjectNode category = addProperty(properties, "supplier-category", "category", "ENUM", false);
        ArrayNode enumValues = category.putArray("enumValues");
        enumValues.add("ELECTRONICS"); enumValues.add("METAL"); enumValues.add("LOGISTICS"); enumValues.add("HARDWARE");
        addProperty(properties, "supplier-legacy", "legacyCode", "STRING", false);

        NativeDraftService.DraftDetail saved = drafts.save(UUID.fromString(draft.draftId()), draft.revision(), editor);
        assertThat(drafts.validate(UUID.fromString(draft.draftId()), saved.revision()).status()).isEqualTo("VALID");
        NativeSnapshotService.FreezeResult frozen = snapshots.freeze(
                UUID.fromString(draft.draftId()), saved.revision(), UUID.randomUUID());
        String snapshotId = frozen.snapshot().snapshotId();

        ObjectNode request = bindingRequest(snapshotId);
        ResponseEntity<String> preview = post("/api/runtime/bindings/preview", request);
        assertThat(preview.getStatusCode().value()).as(preview.getBody()).isEqualTo(200);
        request.put("expectedSchemaFingerprint", mapper.readTree(preview.getBody())
                .path("source").path("schemaFingerprint").asText());
        ResponseEntity<String> validation = post("/api/runtime/bindings/validate", request);
        assertThat(validation.getStatusCode().value()).as(validation.getBody()).isEqualTo(200);
        assertThat(mapper.readTree(validation.getBody()).path("status").asText()).isEqualTo("VALID");
        ResponseEntity<String> created = post("/api/runtime/bindings", request);
        assertThat(created.getStatusCode().value()).as(created.getBody()).isEqualTo(201);
        String bindingId = mapper.readTree(created.getBody()).path("bindingId").asText();

        ObjectNode read = mapper.createObjectNode();
        read.put("bindingId", bindingId);
        read.put("bindingRevision", 1);
        ObjectNode key = read.putObject("key");
        key.put("valueType", "STRING");
        key.put("value", "SUP-1001");
        ArrayNode refs = read.putArray("propertyRefs");
        for (String ref : List.of("supplier-code", "supplier-name", "supplier-rating", "supplier-active",
                "supplier-onboarded", "supplier-sequence", "supplier-category", "supplier-legacy")) refs.add(ref);

        ResponseEntity<String> response = post("/api/runtime/objects/read", read);
        assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(200);
        JsonNode result = mapper.readTree(response.getBody());
        assertThat(result.path("status").asText()).isEqualTo("SUCCESS");
        assertThat(result.path("executionId").asText()).matches("[a-f0-9-]{36}");
        assertThat(result.path("objectRef").path("objectTypeId").asText()).isEqualTo("supplier");
        assertThat(result.path("objectRef").path("key").path("value").asText()).isEqualTo("SUP-1001");
        assertThat(result.path("sourceReadRef").path("transactionIsolation").asText()).isEqualTo("REPEATABLE_READ");
        assertThat(result.path("sourceReadRef").path("schemaFingerprint").asText()).matches("[0-9a-f]{64}");
        assertThat(result.path("payloadSha256").asText()).matches("[0-9a-f]{64}");
        assertThat(result.path("bindingVersions").get(0).path("revision").asLong()).isEqualTo(1);
        assertThat(result.path("quality").path("accuracy").asText()).isEqualTo("EXACT");
        assertThat(result.path("quality").path("freshness").asText()).isEqualTo("UNKNOWN");
        assertThat(objectProperty(result, "supplier-name").path("value").asText()).isEqualTo("Northwind Components");
        assertThat(objectProperty(result, "supplier-rating").path("value").asText()).isEqualTo("4.75");
        assertThat(objectProperty(result, "supplier-active").path("value").asBoolean()).isTrue();
        assertThat(objectProperty(result, "supplier-sequence").path("value").asText()).isEqualTo("9007199254740993");
        assertThat(objectProperty(result, "supplier-onboarded").path("value").asText()).isEqualTo("2026-01-15T09:00:00Z");
        assertThat(objectProperty(result, "supplier-category").path("value").asText()).isEqualTo("ELECTRONICS");
        assertThat(objectProperty(result, "supplier-legacy").path("status").asText()).isEqualTo("MISSING");
        assertThat(objectProperty(result, "supplier-legacy").path("value").isMissingNode()).isTrue();

        // A later Binding revision may change the identity mapping. Reads pinned to revision 1
        // must keep using its immutable source key and identity domain.
        ObjectNode revisionTwoRequest = request.deepCopy();
        revisionTwoRequest.put("expectedRevision", 1);
        revisionTwoRequest.put("sourceKey", "alternate_code");
        for (JsonNode item : revisionTwoRequest.withArray("fieldMappings")) {
            if ("supplier-code".equals(item.path("propertyRef").asText())) {
                ((ObjectNode) item).put("sourceColumn", "alternate_code");
            }
        }
        ResponseEntity<String> revisionTwoResponse = put("/api/runtime/bindings/" + bindingId, revisionTwoRequest);
        assertThat(revisionTwoResponse.getStatusCode().value()).as(revisionTwoResponse.getBody()).isEqualTo(200);
        JsonNode revisionTwo = mapper.readTree(revisionTwoResponse.getBody());
        assertThat(revisionTwo.path("revision").asLong()).isEqualTo(2);
        assertThat(revisionTwo.path("identityDomainId").asText())
                .isNotEqualTo(result.path("objectRef").path("identityDomainId").asText());

        ResponseEntity<String> historicalRead = post("/api/runtime/objects/read", read);
        assertThat(historicalRead.getStatusCode().value()).as(historicalRead.getBody()).isEqualTo(200);
        JsonNode historicalResult = mapper.readTree(historicalRead.getBody());
        assertThat(historicalResult.path("bindingVersions").get(0).path("revision").asLong()).isEqualTo(1);
        assertThat(historicalResult.path("objectRef").path("key").path("value").asText()).isEqualTo("SUP-1001");
        assertThat(objectProperty(historicalResult, "supplier-name").path("value").asText()).isEqualTo("Northwind Components");

        ObjectNode currentRead = read.deepCopy();
        currentRead.put("bindingRevision", 2);
        currentRead.with("key").put("value", "ALT-SUP-1001");
        ResponseEntity<String> currentResponse = post("/api/runtime/objects/read", currentRead);
        assertThat(currentResponse.getStatusCode().value()).as(currentResponse.getBody()).isEqualTo(200);
        JsonNode currentResult = mapper.readTree(currentResponse.getBody());
        assertThat(currentResult.path("bindingVersions").get(0).path("revision").asLong()).isEqualTo(2);
        assertThat(currentResult.path("objectRef").path("identityDomainId").asText())
                .isEqualTo(revisionTwo.path("identityDomainId").asText());
        assertThat(currentResult.path("objectRef").path("key").path("value").asText()).isEqualTo("ALT-SUP-1001");

        ObjectNode duplicateRefs = read.deepCopy();
        ArrayNode duplicatePropertyRefs = duplicateRefs.putArray("propertyRefs");
        duplicatePropertyRefs.add("supplier-name");
        duplicatePropertyRefs.add("supplier-name");
        ResponseEntity<String> duplicateProperties = post("/api/runtime/objects/read", duplicateRefs);
        assertThat(duplicateProperties.getStatusCode().value()).isEqualTo(422);
        assertThat(duplicateProperties.getBody()).contains("INVALID_OBJECT_REQUEST");

        // The output cap and source scan cap are separate: a 2 MiB value passes source
        // preflight but must not escape the 1 MiB serialized response limit; a 9 MiB value
        // must be rejected in PostgreSQL before JDBC materializes it in the application.
        String originalName = jdbc.queryForObject(
                "SELECT name FROM public.m5_supplier_source WHERE vendor_code='SUP-1001'", String.class);
        try {
            jdbc.update("UPDATE public.m5_supplier_source SET name=repeat('x', 2 * 1024 * 1024) WHERE vendor_code='SUP-1001'");
            ResponseEntity<String> oversizedOutput = post("/api/runtime/objects/read", read);
            assertThat(oversizedOutput.getStatusCode().value()).isEqualTo(429);
            assertThat(oversizedOutput.getBody()).contains("对象结果超过 1 MiB");
            assertThat(oversizedOutput.getBody()).doesNotContain("objectRef", "properties");

            jdbc.update("UPDATE public.m5_supplier_source SET name=repeat('x', 9 * 1024 * 1024) WHERE vendor_code='SUP-1001'");

            // An unrequested large field is not projected or budgeted; explicitly requesting it is.
            ObjectNode narrowRead = read.deepCopy();
            ArrayNode narrowRefs = narrowRead.putArray("propertyRefs");
            narrowRefs.add("supplier-code");
            ResponseEntity<String> narrowResponse = post("/api/runtime/objects/read", narrowRead);
            assertThat(narrowResponse.getStatusCode().value()).as(narrowResponse.getBody()).isEqualTo(200);
            JsonNode narrowResult = mapper.readTree(narrowResponse.getBody());
            assertThat(narrowResult.path("selectedPropertyRefs").size()).isEqualTo(1);
            assertThat(narrowResult.path("properties").size()).isEqualTo(1);

            ResponseEntity<String> oversizedSource = post("/api/runtime/objects/read", read);
            assertThat(oversizedSource.getStatusCode().value()).isEqualTo(429);
            assertThat(oversizedSource.getBody()).contains("对象来源扫描超过 8 MiB");
            assertThat(oversizedSource.getBody()).doesNotContain("objectRef", "properties");
        } finally {
            jdbc.update("UPDATE public.m5_supplier_source SET name=? WHERE vendor_code='SUP-1001'", originalName);
        }

        jdbc.update("UPDATE public.m5_supplier_source SET rating=NULL WHERE vendor_code='SUP-1005'");
        try {
            ObjectNode nullableRead = read.deepCopy();
            nullableRead.with("key").put("value", "SUP-1005");
            ResponseEntity<String> nullableResponse = post("/api/runtime/objects/read", nullableRead);
            assertThat(nullableResponse.getStatusCode().value()).as(nullableResponse.getBody()).isEqualTo(200);
            JsonNode nullableResult = mapper.readTree(nullableResponse.getBody());
            assertThat(objectProperty(nullableResult, "supplier-rating").path("status").asText()).isEqualTo("NULL");
            assertThat(objectProperty(nullableResult, "supplier-rating").path("value").isMissingNode()).isTrue();
            assertThat(objectProperty(nullableResult, "supplier-active").path("value").asBoolean()).isFalse();
            assertThat(objectProperty(nullableResult, "supplier-legacy").path("status").asText()).isEqualTo("MISSING");
        } finally {
            jdbc.update("UPDATE public.m5_supplier_source SET rating=4.10 WHERE vendor_code='SUP-1005'");
        }

        ObjectNode notFound = read.deepCopy();
        notFound.with("key").put("value", "SUP-9999");
        ResponseEntity<String> missingResponse = post("/api/runtime/objects/read", notFound);
        assertThat(missingResponse.getStatusCode().value()).isEqualTo(404);
        JsonNode missing = mapper.readTree(missingResponse.getBody());
        assertThat(missing.path("code").asText()).isEqualTo("OBJECT_NOT_FOUND");
        assertThat(missing.path("objectRef").isMissingNode()).isTrue();
        assertThat(missing.path("properties").isMissingNode()).isTrue();
        assertThat(missing.path("executionId").asText()).isNotBlank();

        ObjectNode preservedWhitespace = read.deepCopy();
        preservedWhitespace.with("key").put("value", " SUP-1001 ");
        ResponseEntity<String> whitespaceResponse = post("/api/runtime/objects/read", preservedWhitespace);
        assertThat(whitespaceResponse.getStatusCode().value()).isEqualTo(404);
        assertThat(whitespaceResponse.getBody()).contains("OBJECT_NOT_FOUND");

        ObjectNode wrongKeyType = read.deepCopy();
        wrongKeyType.with("key").put("valueType", "INTEGER");
        ResponseEntity<String> invalidKey = post("/api/runtime/objects/read", wrongKeyType);
        assertThat(invalidKey.getStatusCode().value()).isEqualTo(422);
        assertThat(invalidKey.getBody()).contains("INVALID_OBJECT_KEY");

        ObjectNode oversizedPropertyRefs = read.deepCopy();
        ArrayNode tooManyRefs = oversizedPropertyRefs.putArray("propertyRefs");
        for (int i = 0; i < 101; i++) tooManyRefs.add("property-" + i);
        ResponseEntity<String> tooManyProperties = post("/api/runtime/objects/read", oversizedPropertyRefs);
        assertThat(tooManyProperties.getStatusCode().value()).isEqualTo(422);
        assertThat(tooManyProperties.getBody()).contains("INVALID_OBJECT_REQUEST");

        ObjectNode forgedContext = read.deepCopy();
        forgedContext.putObject("authorizationContext").put("role", "OWNER");
        ResponseEntity<String> rejectedContext = post("/api/runtime/objects/read", forgedContext);
        assertThat(rejectedContext.getStatusCode().value()).isEqualTo(400);
        assertThat(rejectedContext.getBody()).contains("INVALID_OBJECT_REQUEST");

        jdbc.execute("ALTER TABLE public.m5_supplier_source ADD COLUMN m6_test_drift TEXT");
        try {
            ResponseEntity<String> drift = post("/api/runtime/objects/read", read);
            assertThat(drift.getStatusCode().value()).isEqualTo(409);
            assertThat(drift.getBody()).contains("BINDING_SCHEMA_DRIFT");
        } finally {
            jdbc.execute("ALTER TABLE public.m5_supplier_source DROP COLUMN IF EXISTS m6_test_drift");
        }

        // A revoked source SELECT grant must be reported as unavailable, not as a missing
        // object or schema drift, and must never produce a partial successful object.
        jdbc.execute("REVOKE SELECT ON TABLE public.m5_supplier_source FROM ontoforge_binding_reader");
        try {
            ResponseEntity<String> unavailable = post("/api/runtime/objects/read", read);
            assertThat(unavailable.getStatusCode().value()).isEqualTo(503);
            assertThat(unavailable.getBody()).contains("SOURCE_UNAVAILABLE");
            assertThat(unavailable.getBody()).doesNotContain("objectRef", "properties");
        } finally {
            jdbc.execute("GRANT SELECT ON TABLE public.m5_supplier_source TO ontoforge_binding_reader");
        }
    }

    private JsonNode objectProperty(JsonNode object, String propertyRef) {
        for (JsonNode property : object.path("properties")) {
            if (propertyRef.equals(property.path("propertyRef").asText())) return property;
        }
        return mapper.createObjectNode();
    }

    private ObjectNode addProperty(ArrayNode properties, String id, String apiName, String type, boolean required) {
        ObjectNode property = properties.addObject();
        property.put("id", id); property.put("apiName", apiName); property.put("valueType", type);
        property.put("required", required); property.put("searchable", true); property.put("classification", "INTERNAL");
        return property;
    }

    private ObjectNode bindingRequest(String snapshotId) {
        ObjectNode request = mapper.createObjectNode();
        request.put("snapshotId", snapshotId);
        request.put("objectTypeId", "supplier");
        request.put("sourceId", DataBindingService.SOURCE_ID);
        request.put("schema", "public");
        request.put("table", "m5_supplier_source");
        request.put("sourceKey", "vendor_code");
        request.putNull("expectedSchemaFingerprint");
        request.put("expectedRevision", 0);
        ArrayNode mappings = request.putArray("fieldMappings");
        mapping(mappings, "supplier-code", "vendor_code");
        mapping(mappings, "supplier-name", "name");
        mapping(mappings, "supplier-rating", "rating");
        mapping(mappings, "supplier-active", "active");
        mapping(mappings, "supplier-onboarded", "onboarded_at");
        mapping(mappings, "supplier-sequence", "sequence_no");
        mapping(mappings, "supplier-category", "category");
        return request;
    }

    private void mapping(ArrayNode mappings, String propertyRef, String sourceColumn) {
        ObjectNode mapping = mappings.addObject();
        mapping.put("propertyRef", propertyRef);
        mapping.put("sourceColumn", sourceColumn);
        mapping.put("authority", "AUTHORITATIVE");
    }

    private ResponseEntity<String> post(String path, ObjectNode body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return http.postForEntity(URI.create(url(path)), new HttpEntity<>(body.toString(), headers), String.class);
    }

    private ResponseEntity<String> put(String path, ObjectNode body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange(URI.create(url(path)), HttpMethod.PUT,
                new HttpEntity<>(body.toString(), headers), String.class);
    }


    @Test
    void m7CustomSupplierObjectSetSupportsFilteringProjectionAndFrozenPaging() throws Exception {
        String bindingId = createSupplierBindingForM7();
        ObjectNode query = objectSetQuery(bindingId, 2);
        ArrayNode steps = query.withArray("steps");
        ObjectNode filter = mapper.createObjectNode();
        filter.put("kind", "FILTER");
        ObjectNode condition = filter.putArray("conditions").addObject();
        condition.put("propertyRef", "supplier-name");
        condition.put("operator", "PREFIX");
        condition.putObject("literal").put("valueType", "STRING").put("value", "North");
        steps.insert(0, filter);

        ResponseEntity<String> filteredResponse = post("/api/runtime/object-sets/query", query);
        assertThat(filteredResponse.getStatusCode().value()).as(filteredResponse.getBody()).isEqualTo(200);
        JsonNode filtered = mapper.readTree(filteredResponse.getBody());
        assertThat(filtered.path("queryVersion").asText()).isEqualTo("local-query-v1");
        assertThat(filtered.path("totalCount").asInt()).isEqualTo(1);
        assertThat(filtered.path("items").size()).isEqualTo(1);
        assertThat(filtered.path("items").get(0).path("objectRef").path("key").path("value").asText()).isEqualTo("SUP-1001");
        assertThat(filtered.path("quality").path("freshness").asText()).isEqualTo("UNKNOWN");
        assertThat(filtered.path("resultSnapshotRef").asText()).startsWith("local-result:");
        assertThat(filtered.path("items").get(0).path("properties").toString()).contains("Northwind Components");

        ObjectNode allRowsQuery = objectSetQuery(bindingId, 2);
        ResponseEntity<String> firstResponse = post("/api/runtime/object-sets/query", allRowsQuery);
        assertThat(firstResponse.getStatusCode().value()).as(firstResponse.getBody()).isEqualTo(200);
        JsonNode first = mapper.readTree(firstResponse.getBody());
        assertThat(first.path("totalCount").asInt()).isEqualTo(5);
        assertThat(first.path("items").size()).isEqualTo(2);
        assertThat(first.path("pageNumber").asInt()).isEqualTo(1);
        assertThat(first.path("nextCursor").asText()).startsWith("cursor:");
        String snapshotRef = first.path("resultSnapshotRef").asText();
        String originalName = jdbc.queryForObject(
                "SELECT name FROM public.m5_supplier_source WHERE vendor_code='SUP-1003'", String.class);
        try {
            jdbc.update("UPDATE public.m5_supplier_source SET name='Changed after first page' WHERE vendor_code='SUP-1003'");
            ObjectNode nextRequest = mapper.createObjectNode().put("cursor", first.path("nextCursor").asText());
            ResponseEntity<String> secondResponse = post("/api/runtime/object-sets/pages", nextRequest);
            assertThat(secondResponse.getStatusCode().value()).as(secondResponse.getBody()).isEqualTo(200);
            JsonNode second = mapper.readTree(secondResponse.getBody());
            assertThat(second.path("resultSnapshotRef").asText()).isEqualTo(snapshotRef);
            assertThat(second.path("pageNumber").asInt()).isEqualTo(2);
            assertThat(second.path("items").size()).isEqualTo(2);
            assertThat(second.path("items").toString()).contains(originalName).doesNotContain("Changed after first page");
            ObjectNode thirdRequest = mapper.createObjectNode().put("cursor", second.path("nextCursor").asText());
            ResponseEntity<String> thirdResponse = post("/api/runtime/object-sets/pages", thirdRequest);
            assertThat(thirdResponse.getStatusCode().value()).as(thirdResponse.getBody()).isEqualTo(200);
            JsonNode third = mapper.readTree(thirdResponse.getBody());
            assertThat(third.path("resultSnapshotRef").asText()).isEqualTo(snapshotRef);
            assertThat(third.path("pageNumber").asInt()).isEqualTo(3);
            assertThat(third.path("items").size()).isEqualTo(1);
            assertThat(third.path("nextCursor").isMissingNode() || third.path("nextCursor").isNull()).isTrue();
        } finally {
            jdbc.update("UPDATE public.m5_supplier_source SET name=? WHERE vendor_code='SUP-1003'", originalName);
        }

        ObjectNode driftQuery = objectSetQuery(bindingId, 2);
        JsonNode driftFirst = mapper.readTree(post("/api/runtime/object-sets/query", driftQuery).getBody());
        jdbc.execute("ALTER TABLE public.m5_supplier_source ADD COLUMN m7_test_drift TEXT");
        try {
            ObjectNode next = mapper.createObjectNode().put("cursor", driftFirst.path("nextCursor").asText());
            ResponseEntity<String> drift = post("/api/runtime/object-sets/pages", next);
            assertThat(drift.getStatusCode().value()).isEqualTo(409);
            assertThat(drift.getBody()).contains("BINDING_SCHEMA_DRIFT");
            assertThat(drift.getBody()).doesNotContain("\"items\"", "\"aggregate\"");
        } finally {
            jdbc.execute("ALTER TABLE public.m5_supplier_source DROP COLUMN IF EXISTS m7_test_drift");
        }
    }

    @Test
    void m7AggregateUsesExactValuesAndFailsClosedOnNullOrInvalidAst() throws Exception {
        String bindingId = createSupplierBindingForM7();
        ObjectNode sum = mapper.createObjectNode();
        sum.put("queryVersion", "local-query-v1");
        sum.put("bindingId", bindingId);
        sum.put("bindingRevision", 1);
        sum.putArray("steps").addObject().put("kind", "AGGREGATE")
                .put("operation", "SUM").put("propertyRef", "supplier-rating");
        ResponseEntity<String> sumResponse = post("/api/runtime/object-sets/query", sum);
        assertThat(sumResponse.getStatusCode().value()).as(sumResponse.getBody()).isEqualTo(200);
        JsonNode aggregate = mapper.readTree(sumResponse.getBody());
        assertThat(aggregate.path("aggregate").path("operation").asText()).isEqualTo("SUM");
        assertThat(aggregate.path("aggregate").path("valueType").asText()).isEqualTo("DECIMAL");
        assertThat(new java.math.BigDecimal(aggregate.path("aggregate").path("value").asText())).isPositive();

        jdbc.update("UPDATE public.m5_supplier_source SET rating=NULL WHERE vendor_code='SUP-1005'");
        try {
            ResponseEntity<String> unavailable = post("/api/runtime/object-sets/query", sum);
            assertThat(unavailable.getStatusCode().value()).isEqualTo(422);
            assertThat(unavailable.getBody()).contains("AGGREGATE_VALUE_UNAVAILABLE");
            assertThat(unavailable.getBody()).doesNotContain("\"items\"", "\"aggregate\"");
        } finally {
            jdbc.update("UPDATE public.m5_supplier_source SET rating=4.10 WHERE vendor_code='SUP-1005'");
        }

        ObjectNode invalid = objectSetQuery(bindingId, 2);
        ObjectNode sort = (ObjectNode) invalid.withArray("steps").get(0);
        sort.withArray("fields").get(0).with("direction").asText();
        ((ObjectNode) sort.withArray("fields").get(0)).put("direction", "SIDEWAYS");
        ResponseEntity<String> rejected = post("/api/runtime/object-sets/query", invalid);
        assertThat(rejected.getStatusCode().value()).isEqualTo(422);
        assertThat(rejected.getBody()).contains("INVALID_QUERY_AST");
        assertThat(rejected.getBody()).doesNotContain("\"items\"", "\"aggregate\"");
    }

    private String createSupplierBindingForM7() throws Exception {
        String packageId = "m7.test.p" + UUID.randomUUID().toString().replace("-", "");
        NativeDraftService.DraftDetail draft = drafts.create(packageId);
        ObjectNode editor = mapper.createObjectNode();
        editor.put("editorSchemaVersion", "0.1.0");
        ArrayNode types = editor.putArray("objectTypes");
        editor.putArray("linkTypes");
        ObjectNode supplier = types.addObject();
        supplier.put("id", "supplier");
        supplier.put("apiName", "Supplier");
        supplier.put("label", "供应商");
        supplier.put("primaryKeyRef", "supplier-code");
        ArrayNode properties = supplier.putArray("properties");
        addProperty(properties, "supplier-code", "vendorCode", "STRING", true);
        addProperty(properties, "supplier-name", "name", "STRING", true);
        addProperty(properties, "supplier-rating", "rating", "DECIMAL", false);
        addProperty(properties, "supplier-active", "active", "BOOLEAN", true);
        addProperty(properties, "supplier-onboarded", "onboardedAt", "DATETIME", false);
        addProperty(properties, "supplier-sequence", "sequence", "INTEGER", true);
        ObjectNode category = addProperty(properties, "supplier-category", "category", "ENUM", false);
        ArrayNode enumValues = category.putArray("enumValues");
        enumValues.add("ELECTRONICS"); enumValues.add("METAL"); enumValues.add("LOGISTICS"); enumValues.add("HARDWARE");
        addProperty(properties, "supplier-legacy", "legacyCode", "STRING", false);
        NativeDraftService.DraftDetail saved = drafts.save(UUID.fromString(draft.draftId()), draft.revision(), editor);
        assertThat(drafts.validate(UUID.fromString(draft.draftId()), saved.revision()).status()).isEqualTo("VALID");
        NativeSnapshotService.FreezeResult frozen = snapshots.freeze(
                UUID.fromString(draft.draftId()), saved.revision(), UUID.randomUUID());
        ObjectNode request = bindingRequest(frozen.snapshot().snapshotId());
        ResponseEntity<String> preview = post("/api/runtime/bindings/preview", request);
        assertThat(preview.getStatusCode().value()).as(preview.getBody()).isEqualTo(200);
        request.put("expectedSchemaFingerprint", mapper.readTree(preview.getBody()).path("source").path("schemaFingerprint").asText());
        ResponseEntity<String> validation = post("/api/runtime/bindings/validate", request);
        assertThat(validation.getStatusCode().value()).as(validation.getBody()).isEqualTo(200);
        assertThat(mapper.readTree(validation.getBody()).path("status").asText()).isEqualTo("VALID");
        ResponseEntity<String> created = post("/api/runtime/bindings", request);
        assertThat(created.getStatusCode().value()).as(created.getBody()).isEqualTo(201);
        return mapper.readTree(created.getBody()).path("bindingId").asText();
    }

    private ObjectNode objectSetQuery(String bindingId, int pageSize) {
        ObjectNode query = mapper.createObjectNode();
        query.put("queryVersion", "local-query-v1");
        query.put("bindingId", bindingId);
        query.put("bindingRevision", 1);
        ArrayNode steps = query.putArray("steps");
        ObjectNode sort = steps.addObject();
        sort.put("kind", "SORT");
        ObjectNode field = sort.putArray("fields").addObject();
        field.put("propertyRef", "supplier-code");
        field.put("direction", "ASC");
        ObjectNode project = steps.addObject();
        project.put("kind", "PROJECT");
        ArrayNode refs = project.putArray("propertyRefs");
        refs.add("supplier-name");
        refs.add("supplier-rating");
        ObjectNode page = steps.addObject();
        page.put("kind", "PAGE");
        page.put("pageSize", pageSize);
        return query;
    }

}
