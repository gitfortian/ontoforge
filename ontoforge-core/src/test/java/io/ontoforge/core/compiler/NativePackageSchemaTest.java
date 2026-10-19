package io.ontoforge.core.compiler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** Shared contract fixture and rejection boundaries across the validator/Jackson major upgrade. */
class NativePackageSchemaTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private ObjectNode candidate() throws Exception {
        return (ObjectNode) mapper.readTree(Path.of("../doc/design/examples/manufacturing-quality-v0.2.json").toFile());
    }

    @Test void sharedNativeDesignExampleStillValidatesWithoutRemoteSchemaFetch() throws Exception {
        assertThat(NativePackageSchema.validate(candidate())).isEmpty();
    }

    @Test void nativeSchemaAllowsUuidObjectIdentityTypeUsedByBindingsAndObjectSet() throws Exception {
        var candidate = candidate();
        var type = (ObjectNode) candidate.path("objectTypes").get(0);
        String primaryKeyRef = type.path("primaryKeyRef").asText();
        boolean changed = false;
        for (var property : type.path("properties")) {
            if (primaryKeyRef.equals(property.path("id").asText())) {
                ((ObjectNode) property).put("valueType", "UUID");
                changed = true;
            }
        }
        assertThat(changed).isTrue();
        assertThat(NativePackageSchema.validate(candidate)).isEmpty();
    }

    @Test void nativeStructureFailuresRetainStableCodeAndNestedInstancePath() throws Exception {
        var candidate = candidate();
        candidate.put("unexpectedAuthority", true);
        candidate.remove("schemaVersion");
        ((ObjectNode) candidate.path("objectTypes").get(0)).put("apiName", 42);
        var issues = NativePackageSchema.validate(candidate);
        assertThat(issues).hasSizeGreaterThanOrEqualTo(3);
        assertThat(issues).allMatch(issue -> issue.code().equals("NATIVE_SCHEMA_FAILED"));
        assertThat(issues).anyMatch(issue -> issue.path().equals("/objectTypes/0/apiName"));
    }
}
