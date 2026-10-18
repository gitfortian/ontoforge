package io.ontoforge.boot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.ontoforge.boot.model.DataBindingService;
import io.ontoforge.boot.model.NativeDraftService;
import io.ontoforge.boot.model.NativeSnapshotService;
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

        ObjectNode wrongKeyType = read.deepCopy();
        wrongKeyType.with("key").put("valueType", "INTEGER");
        ResponseEntity<String> invalidKey = post("/api/runtime/objects/read", wrongKeyType);
        assertThat(invalidKey.getStatusCode().value()).isEqualTo(422);
        assertThat(invalidKey.getBody()).contains("INVALID_OBJECT_KEY");

        jdbc.execute("ALTER TABLE public.m5_supplier_source ADD COLUMN m6_test_drift TEXT");
        try {
            ResponseEntity<String> drift = post("/api/runtime/objects/read", read);
            assertThat(drift.getStatusCode().value()).isEqualTo(409);
            assertThat(drift.getBody()).contains("BINDING_SCHEMA_DRIFT");
        } finally {
            jdbc.execute("ALTER TABLE public.m5_supplier_source DROP COLUMN IF EXISTS m6_test_drift");
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
}
