package io.ontoforge.boot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.ontoforge.boot.model.DataBindingService;
import io.ontoforge.boot.model.NativeDraftService;
import io.ontoforge.boot.model.NativeSnapshotService;
import io.ontoforge.boot.model.ObjectSetQueryService;
import java.net.URI;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;
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
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;
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
    @Autowired ObjectSetQueryService objectSetQueries;

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
        assertThat(filtered.path("objects").size()).isEqualTo(1);
        assertThat(filtered.path("objects").get(0).path("objectRef").path("key").path("value").asText()).isEqualTo("SUP-1001");
        assertThat(filtered.path("quality").path("freshness").asText()).isEqualTo("UNKNOWN");
        assertThat(filtered.path("typeSourceRef").path("kind").asText()).isEqualTo("LOCAL_SNAPSHOT");
        assertThat(filtered.path("versionLock").path("payloadSha256").asText()).hasSize(64);
        assertThat(filtered.path("sourceReadRef").path("checkpoint").path("status").asText()).isEqualTo("UNKNOWN");
        assertThat(filtered.path("resultSnapshotRef").asText()).startsWith("local-result:");
        assertThat(filtered.path("objects").get(0).path("properties").toString()).contains("Northwind Components");

        ObjectNode allRowsQuery = objectSetQuery(bindingId, 2);
        ResponseEntity<String> firstResponse = post("/api/runtime/object-sets/query", allRowsQuery);
        assertThat(firstResponse.getStatusCode().value()).as(firstResponse.getBody()).isEqualTo(200);
        JsonNode first = mapper.readTree(firstResponse.getBody());
        assertThat(first.path("totalCount").asInt()).isEqualTo(5);
        assertThat(first.path("objects").size()).isEqualTo(2);
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
            assertThat(second.path("objects").size()).isEqualTo(2);
            assertThat(second.path("objects").toString()).contains(originalName).doesNotContain("Changed after first page");

            // Replaying a cursor is idempotent and must not mint a fresh cursor token
            // for the same snapshot offset on every request.
            ResponseEntity<String> replayResponse = post("/api/runtime/object-sets/pages", nextRequest);
            assertThat(replayResponse.getStatusCode().value()).as(replayResponse.getBody()).isEqualTo(200);
            JsonNode replay = mapper.readTree(replayResponse.getBody());
            assertThat(replay.path("resultSnapshotRef").asText()).isEqualTo(snapshotRef);
            assertThat(replay.path("pageNumber").asInt()).isEqualTo(2);
            assertThat(replay.path("nextCursor").asText()).isEqualTo(second.path("nextCursor").asText());
            assertThat(replay.path("objects").toString()).contains(originalName).doesNotContain("Changed after first page");

            ObjectNode thirdRequest = mapper.createObjectNode().put("cursor", second.path("nextCursor").asText());
            ResponseEntity<String> thirdResponse = post("/api/runtime/object-sets/pages", thirdRequest);
            assertThat(thirdResponse.getStatusCode().value()).as(thirdResponse.getBody()).isEqualTo(200);
            JsonNode third = mapper.readTree(thirdResponse.getBody());
            assertThat(third.path("resultSnapshotRef").asText()).isEqualTo(snapshotRef);
            assertThat(third.path("pageNumber").asInt()).isEqualTo(3);
            assertThat(third.path("objects").size()).isEqualTo(1);
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
            assertThat(drift.getBody()).doesNotContain("\"objects\"", "\"aggregate\"");
        } finally {
            jdbc.execute("ALTER TABLE public.m5_supplier_source DROP COLUMN IF EXISTS m7_test_drift");
        }
    }

    @Test
    void m7ExpiredEndToEndDeadlineFailsClosedBeforeStartingAnotherSourceStatement() throws Exception {
        try (Connection connection = sourceJdbc.getDataSource().getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT 1")) {
            assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(objectSetQueries, "applyRemainingTimeout",
                    connection, statement, System.nanoTime() - 1_000_000L, "m7-deadline-test"))
                    .isInstanceOfSatisfying(ObjectSetQueryService.QueryException.class, exception -> {
                        assertThat(exception.http()).isEqualTo(503);
                        assertThat(exception.code()).isEqualTo("SOURCE_TIMEOUT");
                    });
        }
    }

    @Test
    void m7FrozenMembershipSurvivesInsertAndDeleteBetweenPages() throws Exception {
        String bindingId = createSupplierBindingForM7();
        ObjectNode query = objectSetQuery(bindingId, 2);
        JsonNode first = mapper.readTree(post("/api/runtime/object-sets/query", query).getBody());
        assertThat(first.path("totalCount").asInt()).isEqualTo(5);
        String cursor = first.path("nextCursor").asText();
        Map<String, Object> deletedRow = jdbc.queryForMap(
                "SELECT vendor_code, alternate_code, name, rating, active, onboarded_at, sequence_no, category "
                + "FROM public.m5_supplier_source WHERE vendor_code='SUP-1003'");
        jdbc.update("DELETE FROM public.m5_supplier_source WHERE vendor_code='SUP-1006'");
        try {
            jdbc.update("DELETE FROM public.m5_supplier_source WHERE vendor_code='SUP-1003'");
            jdbc.update("""
                    INSERT INTO public.m5_supplier_source
                      (vendor_code, alternate_code, name, rating, active, onboarded_at, sequence_no, category)
                    SELECT 'SUP-1006', 'ALT-SUP-1006', name, rating, active, onboarded_at, sequence_no + 100000, category
                      FROM public.m5_supplier_source WHERE vendor_code='SUP-1004'
                    """);

            ResponseEntity<String> secondResponse = post("/api/runtime/object-sets/pages",
                    mapper.createObjectNode().put("cursor", cursor));
            assertThat(secondResponse.getStatusCode().value()).as(secondResponse.getBody()).isEqualTo(200);
            JsonNode second = mapper.readTree(secondResponse.getBody());
            assertThat(second.path("totalCount").asInt()).isEqualTo(5);
            assertThat(second.path("objects").get(0).path("objectRef").path("key").path("value").asText()).isEqualTo("SUP-1003");
            assertThat(second.path("objects").get(1).path("objectRef").path("key").path("value").asText()).isEqualTo("SUP-1004");

            ResponseEntity<String> thirdResponse = post("/api/runtime/object-sets/pages",
                    mapper.createObjectNode().put("cursor", second.path("nextCursor").asText()));
            assertThat(thirdResponse.getStatusCode().value()).as(thirdResponse.getBody()).isEqualTo(200);
            JsonNode third = mapper.readTree(thirdResponse.getBody());
            assertThat(third.path("totalCount").asInt()).isEqualTo(5);
            assertThat(third.path("objects").get(0).path("objectRef").path("key").path("value").asText()).isEqualTo("SUP-1005");
            assertThat(third.path("objects").toString()).doesNotContain("SUP-1006");
        } finally {
            jdbc.update("DELETE FROM public.m5_supplier_source WHERE vendor_code='SUP-1006'");
            jdbc.update("""
                    INSERT INTO public.m5_supplier_source
                      (vendor_code, alternate_code, name, rating, active, onboarded_at, sequence_no, category)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    deletedRow.get("vendor_code"), deletedRow.get("alternate_code"), deletedRow.get("name"),
                    deletedRow.get("rating"), deletedRow.get("active"), deletedRow.get("onboarded_at"),
                    deletedRow.get("sequence_no"), deletedRow.get("category"));
        }
    }

    @Test
    void m7SortTieBreakAndSumAreExactForHighPrecisionValues() throws Exception {
        List<Map<String, Object>> originalRatings = jdbc.queryForList(
                "SELECT vendor_code, rating FROM public.m5_supplier_source");
        jdbc.execute("ALTER TABLE public.m5_supplier_source ALTER COLUMN rating TYPE NUMERIC(24,18) USING rating::numeric");
        try {
            String bindingId = createSupplierBindingForM7();
            jdbc.update("UPDATE public.m5_supplier_source SET rating=4.123456789012345678");
            ObjectNode sortedQuery = objectSetQuery(bindingId, 5);
            ObjectNode sort = (ObjectNode) sortedQuery.withArray("steps").get(0);
            ((ObjectNode) sort.withArray("fields").get(0))
                    .put("propertyRef", "supplier-rating").put("direction", "DESC");
            ResponseEntity<String> sortedResponse = post("/api/runtime/object-sets/query", sortedQuery);
            assertThat(sortedResponse.getStatusCode().value()).as(sortedResponse.getBody()).isEqualTo(200);
            JsonNode sorted = mapper.readTree(sortedResponse.getBody());
            assertThat(sorted.path("objects").size()).isEqualTo(5);
            for (int index = 0; index < 5; index++) {
                assertThat(sorted.path("objects").get(index).path("objectRef").path("key").path("value").asText())
                        .isEqualTo("SUP-100" + (index + 1));
            }

            jdbc.update("""
                    UPDATE public.m5_supplier_source
                       SET rating = CASE vendor_code
                         WHEN 'SUP-1001' THEN 0.100000000000000001
                         WHEN 'SUP-1002' THEN 0.100000000000000001
                         WHEN 'SUP-1003' THEN 0.200000000000000002
                         WHEN 'SUP-1004' THEN 0.300000000000000003
                         WHEN 'SUP-1005' THEN 0.400000000000000004
                         ELSE rating END
                    """);
            ObjectNode sumQuery = objectSetRequest(bindingId);
            sumQuery.withArray("steps").addObject()
                    .put("kind", "AGGREGATE").put("operation", "SUM").put("propertyRef", "supplier-rating");
            ResponseEntity<String> sumResponse = post("/api/runtime/object-sets/query", sumQuery);
            assertThat(sumResponse.getStatusCode().value()).as(sumResponse.getBody()).isEqualTo(200);
            JsonNode aggregate = mapper.readTree(sumResponse.getBody()).path("aggregate");
            assertThat(aggregate.path("value").asText()).isEqualTo("1.100000000000000011");
            assertThat(aggregate.path("matchedObjects").asInt()).isEqualTo(5);
        } finally {
            for (Map<String, Object> row : originalRatings) {
                jdbc.update("UPDATE public.m5_supplier_source SET rating=? WHERE vendor_code=?",
                        row.get("rating"), row.get("vendor_code"));
            }
            jdbc.execute("ALTER TABLE public.m5_supplier_source ALTER COLUMN rating TYPE NUMERIC(4,2) USING round(rating::numeric, 2)");
        }
    }

    @Test
    void m7PhysicalRowBudgetFailsClosedWithoutPartialResult() throws Exception {
        String bindingId = createSupplierBindingForM7();
        jdbc.update("DELETE FROM public.m5_supplier_source WHERE vendor_code LIKE 'M7-BUDGET-%'");
        try {
            jdbc.update("""
                    INSERT INTO public.m5_supplier_source
                      (vendor_code, alternate_code, name, rating, active, onboarded_at, sequence_no, category)
                    SELECT 'M7-BUDGET-' || g, 'ALT-M7-BUDGET-' || g, 'budget-row', 1.00, TRUE,
                           TIMESTAMP '2026-01-01 00:00:00', 100000 + g, 'HARDWARE'
                      FROM generate_series(1, 10001) AS g
                    """);
            ResponseEntity<String> response = post("/api/runtime/object-sets/query", objectSetQuery(bindingId, 2));
            assertThat(response.getStatusCode().value()).isEqualTo(429);
            assertThat(response.getBody()).contains("QUERY_BUDGET_EXCEEDED", "10000 行");
            assertThat(response.getBody()).doesNotContain("\"objects\"", "\"aggregate\"");
        } finally {
            jdbc.update("DELETE FROM public.m5_supplier_source WHERE vendor_code LIKE 'M7-BUDGET-%'");
        }
    }

    @Test
    void m7SourceByteBudgetFailsClosedWithoutPartialResult() throws Exception {
        String bindingId = createSupplierBindingForM7();
        String originalName = jdbc.queryForObject(
                "SELECT name FROM public.m5_supplier_source WHERE vendor_code='SUP-1001'", String.class);
        try {
            jdbc.update("UPDATE public.m5_supplier_source SET name=repeat('x', 9 * 1024 * 1024) WHERE vendor_code='SUP-1001'");
            ResponseEntity<String> response = post("/api/runtime/object-sets/query", objectSetQuery(bindingId, 2));
            assertThat(response.getStatusCode().value()).isEqualTo(429);
            assertThat(response.getBody()).contains("QUERY_BUDGET_EXCEEDED", "8 MiB");
            assertThat(response.getBody()).doesNotContain("\"objects\"", "\"aggregate\"");
        } finally {
            jdbc.update("UPDATE public.m5_supplier_source SET name=? WHERE vendor_code='SUP-1001'", originalName);
        }
    }

    @Test
    void m7SerializedResultBudgetFailsClosedWhenSourceScanFitsBudget() throws Exception {
        String bindingId = createSupplierBindingForM7();
        jdbc.update("DELETE FROM public.m5_supplier_source WHERE vendor_code LIKE 'M7-RESULT-%'");
        try {
            jdbc.update("""
                    INSERT INTO public.m5_supplier_source
                      (vendor_code, alternate_code, name, rating, active, onboarded_at, sequence_no, category)
                    SELECT 'M7-RESULT-' || g, 'ALT-M7-RESULT-' || g, repeat('x', 700), 1.00, TRUE,
                           TIMESTAMP '2026-01-01 00:00:00', 200000 + g, 'HARDWARE'
                      FROM generate_series(1, 9995) AS g
                    """);
            ResponseEntity<String> response = post("/api/runtime/object-sets/query", objectSetQuery(bindingId, 2));
            assertThat(response.getStatusCode().value()).isEqualTo(429);
            assertThat(response.getBody()).contains("QUERY_BUDGET_EXCEEDED", "8 MiB 序列化预算");
            assertThat(response.getBody()).doesNotContain("\"objects\"", "\"aggregate\"");
        } finally {
            jdbc.update("DELETE FROM public.m5_supplier_source WHERE vendor_code LIKE 'M7-RESULT-%'");
        }
    }

    @Test
    void m7SourcePermissionRevocationBlocksSubsequentPage() throws Exception {
        String bindingId = createSupplierBindingForM7();
        JsonNode first = mapper.readTree(post("/api/runtime/object-sets/query", objectSetQuery(bindingId, 2)).getBody());
        String cursor = first.path("nextCursor").asText();
        jdbc.execute("REVOKE SELECT ON TABLE public.m5_supplier_source FROM ontoforge_binding_reader");
        try {
            ResponseEntity<String> response = post("/api/runtime/object-sets/pages", mapper.createObjectNode().put("cursor", cursor));
            assertThat(response.getStatusCode().value()).isEqualTo(503);
            assertThat(response.getBody()).contains("SOURCE_UNAVAILABLE");
            assertThat(response.getBody()).doesNotContain("\"objects\"", "\"aggregate\"");
        } finally {
            jdbc.execute("GRANT SELECT ON TABLE public.m5_supplier_source TO ontoforge_binding_reader");
        }
        ResponseEntity<String> replayAfterRestore = post("/api/runtime/object-sets/pages",
                mapper.createObjectNode().put("cursor", cursor));
        assertThat(replayAfterRestore.getStatusCode().value()).isEqualTo(410);
        assertThat(replayAfterRestore.getBody()).contains("RESULT_EXPIRED");
        assertThat(replayAfterRestore.getBody()).doesNotContain("\"objects\"", "\"aggregate\"");
    }

    @SuppressWarnings("unchecked")
    @Test
    void m7SignedCursorsRejectTamperingCrossQueryCrossSubjectAndReturn410AfterExpiryOrRestart() throws Exception {
        String bindingId = createSupplierBindingForM7();
        JsonNode first = mapper.readTree(post("/api/runtime/object-sets/query", objectSetQuery(bindingId, 2)).getBody());
        String cursor = first.path("nextCursor").asText();
        assertThat(cursor).startsWith("cursor:v1:");

        int signatureStart = cursor.lastIndexOf('.') + 1;
        assertThat(signatureStart).isGreaterThan(0);
        // Change a significant character in the signature, not the final base64
        // character whose unused padding bits can decode to the same byte sequence.
        String signatureChar = cursor.substring(signatureStart, signatureStart + 1);
        String replacement = "A".equals(signatureChar) ? "B" : "A";
        String tampered = cursor.substring(0, signatureStart) + replacement + cursor.substring(signatureStart + 1);
        ResponseEntity<String> forged = post("/api/runtime/object-sets/pages", mapper.createObjectNode().put("cursor", tampered));
        assertThat(forged.getStatusCode().value()).isEqualTo(400);
        assertThat(forged.getBody()).contains("INVALID_CURSOR");

        ResponseEntity<String> tooLong = post("/api/runtime/object-sets/pages",
                mapper.createObjectNode().put("cursor", "x".repeat(513)));
        assertThat(tooLong.getStatusCode().value()).isEqualTo(400);
        assertThat(tooLong.getBody()).contains("INVALID_CURSOR");

        ObjectNode crossQuery = objectSetQuery(bindingId, 2);
        crossQuery.put("cursor", cursor);
        ResponseEntity<String> reusedAsQuery = post("/api/runtime/object-sets/query", crossQuery);
        assertThat(reusedAsQuery.getStatusCode().value()).isEqualTo(400);
        assertThat(reusedAsQuery.getBody()).contains("INVALID_QUERY_AST");

        Jwt otherJwt = Jwt.withTokenValue("m7-cursor-other-subject").header("alg", "none")
                .issuer("https://cursor-test.invalid").subject("other-subject").build();
        assertThatThrownBy(() -> objectSetQueries.nextPage(mapper.createObjectNode().put("cursor", cursor),
                new JwtAuthenticationToken(otherJwt)))
                .isInstanceOfSatisfying(ObjectSetQueryService.QueryException.class,
                        exception -> assertThat(exception.http()).isEqualTo(403));

        long originalTtl = ((Number) ReflectionTestUtils.getField(objectSetQueries, "ttlSeconds")).longValue();
        String expiringCursor;
        try {
            ReflectionTestUtils.setField(objectSetQueries, "ttlSeconds", 1L);
            JsonNode expiringFirst = mapper.readTree(post("/api/runtime/object-sets/query", objectSetQuery(bindingId, 2)).getBody());
            expiringCursor = expiringFirst.path("nextCursor").asText();
        } finally {
            ReflectionTestUtils.setField(objectSetQueries, "ttlSeconds", originalTtl);
        }
        Thread.sleep(1250L);
        ResponseEntity<String> expired = post("/api/runtime/object-sets/pages",
                mapper.createObjectNode().put("cursor", expiringCursor));
        assertThat(expired.getStatusCode().value()).isEqualTo(410);
        assertThat(expired.getBody()).contains("RESULT_EXPIRED");
        assertThat(expired.getBody()).doesNotContain("\"objects\"", "\"aggregate\"");

        JsonNode restartFirst = mapper.readTree(post("/api/runtime/object-sets/query", objectSetQuery(bindingId, 2)).getBody());
        String restartCursor = restartFirst.path("nextCursor").asText();
        Map<String, Object> snapshotStore = (Map<String, Object>) ReflectionTestUtils.getField(objectSetQueries, "snapshots");
        Map<String, Object> cursorStore = (Map<String, Object>) ReflectionTestUtils.getField(objectSetQueries, "cursors");
        snapshotStore.clear();
        cursorStore.clear();
        ResponseEntity<String> afterVolatileStoreLoss = post("/api/runtime/object-sets/pages",
                mapper.createObjectNode().put("cursor", restartCursor));
        assertThat(afterVolatileStoreLoss.getStatusCode().value()).isEqualTo(410);
        assertThat(afterVolatileStoreLoss.getBody()).contains("RESULT_EXPIRED", "服务重启后失效");
        assertThat(afterVolatileStoreLoss.getBody()).doesNotContain("\"objects\"", "\"aggregate\"");
    }

    @Test
    void m7AggregateUsesExactValuesAndFailsClosedOnNullOrInvalidAst() throws Exception {
        String bindingId = createSupplierBindingForM7();

        // A searchable but unmapped field must not silently produce a successful empty result.
        ObjectNode unmappedFilter = objectSetQuery(bindingId, 2);
        ObjectNode filter = mapper.createObjectNode();
        filter.put("kind", "FILTER");
        ObjectNode condition = filter.putArray("conditions").addObject();
        condition.put("propertyRef", "supplier-legacy");
        condition.put("operator", "PREFIX");
        condition.putObject("literal").put("valueType", "STRING").put("value", "LEG");
        unmappedFilter.withArray("steps").insert(0, filter);
        ResponseEntity<String> rejectedUnmappedFilter = post("/api/runtime/object-sets/query", unmappedFilter);
        assertThat(rejectedUnmappedFilter.getStatusCode().value()).isEqualTo(422);
        assertThat(rejectedUnmappedFilter.getBody()).contains("INVALID_QUERY_AST", "已绑定");

        ObjectNode unmappedSort = objectSetQuery(bindingId, 2);
        ((ObjectNode) unmappedSort.withArray("steps").get(0).withArray("fields").get(0))
                .put("propertyRef", "supplier-legacy");
        ResponseEntity<String> rejectedUnmappedSort = post("/api/runtime/object-sets/query", unmappedSort);
        assertThat(rejectedUnmappedSort.getStatusCode().value()).isEqualTo(422);
        assertThat(rejectedUnmappedSort.getBody()).contains("INVALID_QUERY_AST", "已绑定");

        ObjectNode unmappedSum = objectSetRequest(bindingId);
        unmappedSum.withArray("steps").addObject()
                .put("kind", "AGGREGATE").put("operation", "SUM").put("propertyRef", "supplier-unmapped-rating");
        ResponseEntity<String> rejectedUnmappedSum = post("/api/runtime/object-sets/query", unmappedSum);
        assertThat(rejectedUnmappedSum.getStatusCode().value()).isEqualTo(422);
        assertThat(rejectedUnmappedSum.getBody()).contains("INVALID_QUERY_AST", "已绑定");

        ObjectNode sum = objectSetRequest(bindingId);
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
            assertThat(unavailable.getBody()).doesNotContain("\"objects\"", "\"aggregate\"");
        } finally {
            jdbc.update("UPDATE public.m5_supplier_source SET rating=4.10 WHERE vendor_code='SUP-1005'");
        }

        ObjectNode invalid = objectSetQuery(bindingId, 2);
        ObjectNode sort = (ObjectNode) invalid.withArray("steps").get(0);
        ((ObjectNode) sort.withArray("fields").get(0)).put("direction", "SIDEWAYS");
        ResponseEntity<String> rejected = post("/api/runtime/object-sets/query", invalid);
        assertThat(rejected.getStatusCode().value()).isEqualTo(422);
        assertThat(rejected.getBody()).contains("INVALID_QUERY_AST");
        assertThat(rejected.getBody()).doesNotContain("\"objects\"", "\"aggregate\"");
    }

    @Test
    void m7RuntimeEnforcesAstContractAndProducesStableBudgetAwareDigest() throws Exception {
        String bindingId = createSupplierBindingForM7();
        ObjectNode base = objectSetQuery(bindingId, 2);
        ResponseEntity<String> firstResponse = post("/api/runtime/object-sets/query", base);
        assertThat(firstResponse.getStatusCode().value()).as(firstResponse.getBody()).isEqualTo(200);
        JsonNode first = mapper.readTree(firstResponse.getBody());

        ObjectNode repeat = objectSetQuery(bindingId, 2);
        ResponseEntity<String> repeatResponse = post("/api/runtime/object-sets/query", repeat);
        assertThat(repeatResponse.getStatusCode().value()).as(repeatResponse.getBody()).isEqualTo(200);
        assertThat(mapper.readTree(repeatResponse.getBody()).path("queryDigest").asText())
                .isEqualTo(first.path("queryDigest").asText());

        ObjectNode lowered = objectSetQuery(bindingId, 2);
        lowered.putObject("limits").put("maxSourceBytes", 8 * 1024 * 1024 - 1);
        ResponseEntity<String> loweredResponse = post("/api/runtime/object-sets/query", lowered);
        assertThat(loweredResponse.getStatusCode().value()).as(loweredResponse.getBody()).isEqualTo(200);
        assertThat(mapper.readTree(loweredResponse.getBody()).path("queryDigest").asText())
                .isNotEqualTo(first.path("queryDigest").asText());
        assertThat(mapper.readTree(loweredResponse.getBody()).path("budget").path("maxSourceBytes").asLong())
                .isEqualTo(8L * 1024 * 1024 - 1);

        ObjectNode lowerRows = objectSetQuery(bindingId, 2);
        lowerRows.putObject("limits").put("maxPhysicalRows", 2);
        ResponseEntity<String> rowBudgetFailure = post("/api/runtime/object-sets/query", lowerRows);
        assertThat(rowBudgetFailure.getStatusCode().value()).isEqualTo(429);
        assertThat(rowBudgetFailure.getBody()).contains("QUERY_BUDGET_EXCEEDED");
        assertThat(rowBudgetFailure.getBody()).doesNotContain("\"objects\"", "\"aggregate\"");

        ObjectNode lowerResultBytes = objectSetQuery(bindingId, 2);
        lowerResultBytes.putObject("limits").put("maxSerializedResultBytes", 1);
        ResponseEntity<String> resultBudgetFailure = post("/api/runtime/object-sets/query", lowerResultBytes);
        assertThat(resultBudgetFailure.getStatusCode().value()).isEqualTo(429);
        assertThat(resultBudgetFailure.getBody()).contains("QUERY_BUDGET_EXCEEDED");
        assertThat(resultBudgetFailure.getBody()).doesNotContain("\"objects\"", "\"aggregate\"");

        ObjectNode escalated = objectSetQuery(bindingId, 2);
        escalated.putObject("limits").put("maxPhysicalRows", 10001);
        ResponseEntity<String> rejectedEscalation = post("/api/runtime/object-sets/query", escalated);
        assertThat(rejectedEscalation.getStatusCode().value()).isEqualTo(422);
        assertThat(rejectedEscalation.getBody()).contains("INVALID_QUERY_AST", "maxPhysicalRows");

        ObjectNode aliasLiteral = objectSetQuery(bindingId, 2);
        ObjectNode aliasFilter = mapper.createObjectNode().put("kind", "FILTER");
        ObjectNode aliasCondition = aliasFilter.putArray("conditions").addObject();
        aliasCondition.put("propertyRef", "supplier-name").put("operator", "EQ");
        aliasCondition.putObject("literal").put("valueType", "TEXT").put("value", "North");
        aliasLiteral.withArray("steps").insert(0, aliasFilter);
        ResponseEntity<String> rejectedAlias = post("/api/runtime/object-sets/query", aliasLiteral);
        assertThat(rejectedAlias.getStatusCode().value()).isEqualTo(422);
        assertThat(rejectedAlias.getBody()).contains("INVALID_QUERY_AST", "规范类型名");

        ObjectNode tooManyConditions = objectSetQuery(bindingId, 2);
        ObjectNode filter = mapper.createObjectNode().put("kind", "FILTER");
        ArrayNode conditions = filter.putArray("conditions");
        for (int i = 0; i < 9; i++) {
            ObjectNode condition = conditions.addObject();
            condition.put("propertyRef", "supplier-name").put("operator", "EQ");
            condition.putObject("literal").put("valueType", "STRING").put("value", "North");
        }
        tooManyConditions.withArray("steps").insert(0, filter);
        ResponseEntity<String> rejectedFilter = post("/api/runtime/object-sets/query", tooManyConditions);
        assertThat(rejectedFilter.getStatusCode().value()).isEqualTo(422);
        assertThat(rejectedFilter.getBody()).contains("INVALID_QUERY_AST", "1–8");
    }

    @Test
    void m7DeduplicatesEquivalentObjectRefsBeforeSumAndRejectsConflictsBeforeFiltering() throws Exception {
        List<Map<String, Object>> originalRows = jdbc.queryForList(
                "SELECT vendor_code, rating FROM public.m5_supplier_source ORDER BY vendor_code");
        List<String> originalKeys = originalRows.stream().map(row -> String.valueOf(row.get("vendor_code"))).toList();
        assertThat(originalKeys).hasSize(5);
        List<String> currentKeys = new java.util.ArrayList<>(originalKeys);
        try {
            for (int i = 0; i < originalKeys.size(); i++) {
                String uuidKey = UUID.randomUUID().toString();
                jdbc.update("UPDATE public.m5_supplier_source SET vendor_code=? WHERE vendor_code=?",
                        uuidKey, originalKeys.get(i));
                currentKeys.set(i, uuidKey);
            }

            // M5 validates canonical lowercase UUID values. After saving the binding, change
            // two unique text keys to case variants of one UUID. M7 normalizes both to the same
            // ObjectRef while the physical source rows remain distinct and schema-stable.
            String bindingId = createSupplierBindingForM7("UUID");
            String duplicateKey = UUID.randomUUID().toString();
            String duplicateUpper = duplicateKey.toUpperCase(java.util.Locale.ROOT);
            jdbc.update("UPDATE public.m5_supplier_source SET vendor_code=? WHERE vendor_code=?",
                    duplicateKey, currentKeys.get(0));
            currentKeys.set(0, duplicateKey);
            jdbc.update("UPDATE public.m5_supplier_source SET vendor_code=? WHERE vendor_code=?",
                    duplicateUpper, currentKeys.get(1));
            currentKeys.set(1, duplicateUpper);

            jdbc.update("UPDATE public.m5_supplier_source SET rating=1.25 WHERE vendor_code IN (?,?)",
                    duplicateKey, duplicateUpper);
            jdbc.update("UPDATE public.m5_supplier_source SET rating=2.00 WHERE vendor_code=?", currentKeys.get(2));
            jdbc.update("UPDATE public.m5_supplier_source SET rating=3.00 WHERE vendor_code=?", currentKeys.get(3));
            jdbc.update("UPDATE public.m5_supplier_source SET rating=4.00 WHERE vendor_code=?", currentKeys.get(4));

            ObjectNode countQuery = objectSetRequest(bindingId);
            countQuery.withArray("steps").addObject().put("kind", "AGGREGATE").put("operation", "COUNT_DISTINCT");
            ResponseEntity<String> countResponse = post("/api/runtime/object-sets/query", countQuery);
            assertThat(countResponse.getStatusCode().value()).as(countResponse.getBody()).isEqualTo(200);
            JsonNode count = mapper.readTree(countResponse.getBody()).path("aggregate");
            assertThat(count.path("value").asText()).isEqualTo("4");
            assertThat(count.path("matchedObjects").asInt()).isEqualTo(4);

            ObjectNode sumQuery = objectSetRequest(bindingId);
            sumQuery.withArray("steps").addObject().put("kind", "AGGREGATE")
                    .put("operation", "SUM").put("propertyRef", "supplier-rating");
            ResponseEntity<String> sumResponse = post("/api/runtime/object-sets/query", sumQuery);
            assertThat(sumResponse.getStatusCode().value()).as(sumResponse.getBody()).isEqualTo(200);
            JsonNode sum = mapper.readTree(sumResponse.getBody()).path("aggregate");
            assertThat(sum.path("value").asText()).isEqualTo("10.25");
            assertThat(sum.path("matchedObjects").asInt()).isEqualTo(4);

            // Conflict detection must precede filtering: the 1.25 predicate must not hide
            // the second physical row for the same canonical ObjectRef whose rating is 9.99.
            jdbc.update("UPDATE public.m5_supplier_source SET rating=9.99 WHERE vendor_code=?", duplicateUpper);
            ObjectNode conflictQuery = objectSetRequest(bindingId);
            ObjectNode filter = conflictQuery.withArray("steps").addObject().put("kind", "FILTER").putArray("conditions").addObject();
            filter.put("propertyRef", "supplier-rating").put("operator", "EQ");
            filter.putObject("literal").put("valueType", "DECIMAL").put("value", "1.25");
            conflictQuery.withArray("steps").addObject().put("kind", "AGGREGATE")
                    .put("operation", "SUM").put("propertyRef", "supplier-rating");
            ResponseEntity<String> conflictResponse = post("/api/runtime/object-sets/query", conflictQuery);
            assertThat(conflictResponse.getStatusCode().value()).isEqualTo(409);
            assertThat(conflictResponse.getBody()).contains("OBJECT_IDENTITY_CONFLICT");
            assertThat(conflictResponse.getBody()).doesNotContain("\"objects\"", "\"aggregate\"");
        } finally {
            for (int i = 0; i < originalKeys.size(); i++) {
                jdbc.update("UPDATE public.m5_supplier_source SET vendor_code=? WHERE vendor_code=?",
                        originalKeys.get(i), currentKeys.get(i));
            }
            for (int i = 0; i < originalRows.size(); i++) {
                jdbc.update("UPDATE public.m5_supplier_source SET rating=? WHERE vendor_code=?",
                        originalRows.get(i).get("rating"), originalKeys.get(i));
            }
        }
    }

    private String createSupplierBindingForM7() throws Exception {
        return createSupplierBindingForM7("STRING");
    }

    private String createSupplierBindingForM7(String primaryKeyType) throws Exception {
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
        addProperty(properties, "supplier-code", "vendorCode", primaryKeyType, true);
        addProperty(properties, "supplier-name", "name", "STRING", true);
        addProperty(properties, "supplier-rating", "rating", "DECIMAL", false);
        addProperty(properties, "supplier-active", "active", "BOOLEAN", true);
        addProperty(properties, "supplier-onboarded", "onboardedAt", "DATETIME", false);
        addProperty(properties, "supplier-sequence", "sequence", "INTEGER", true);
        ObjectNode category = addProperty(properties, "supplier-category", "category", "ENUM", false);
        ArrayNode enumValues = category.putArray("enumValues");
        enumValues.add("ELECTRONICS"); enumValues.add("METAL"); enumValues.add("LOGISTICS"); enumValues.add("HARDWARE");
        addProperty(properties, "supplier-legacy", "legacyCode", "STRING", false);
        addProperty(properties, "supplier-unmapped-rating", "unmappedRating", "DECIMAL", false);
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

    private ObjectNode objectSetRequest(String bindingId) throws Exception {
        ResponseEntity<String> bindingResponse = http.getForEntity(url("/api/runtime/bindings/" + bindingId + "?revision=1"), String.class);
        assertThat(bindingResponse.getStatusCode().value()).as(bindingResponse.getBody()).isEqualTo(200);
        JsonNode detail = mapper.readTree(bindingResponse.getBody());
        ObjectNode query = mapper.createObjectNode();
        query.put("queryVersion", "local-query-v1");
        ObjectNode rootType = query.putObject("rootTypeRef");
        rootType.putObject("sourceRef").put("kind", "LOCAL_SNAPSHOT").put("snapshotId", detail.path("snapshotId").asText());
        rootType.put("packageId", detail.path("packageId").asText());
        rootType.put("objectTypeId", detail.path("objectTypeId").asText());
        query.putObject("bindingRef").put("bindingId", bindingId).put("revision", 1);
        query.putArray("steps");
        return query;
    }

    private ObjectNode objectSetQuery(String bindingId, int pageSize) throws Exception {
        ObjectNode query = objectSetRequest(bindingId);
        ArrayNode steps = query.withArray("steps");
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
