package io.ontoforge.boot.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Validate the completed Native Candidate against the SAME draft 2020-12 contract as Python. */
public final class NativePackageSchema {
    private NativePackageSchema() {}
    private static final JsonSchema AUTHORITY=loadSchema();

    private static JsonSchema loadSchema() {
        try (InputStream source=NativePackageSchema.class.getClassLoader()
                .getResourceAsStream("native/ontology-package-v0.2.schema.json")) {
            if (source==null) throw new IllegalStateException("Native V0.2 schema is missing from runtime resources");
            return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(source);
        } catch(Exception e) {
            throw new IllegalStateException("Cannot initialize authoritative Native V0.2 schema",e);
        }
    }

    public static List<DraftValidation.Issue> validate(JsonNode candidate) {
        List<DraftValidation.Issue> errors=new ArrayList<>();
        AUTHORITY.validate(candidate).stream()
            .sorted(Comparator.comparing(x->x.getMessage()))
            .forEach(x->errors.add(new DraftValidation.Issue(
                x.getInstanceLocation().toString(), "NATIVE_SCHEMA_FAILED",
                "当前模型不满足 Native V0.2 契约：" + x.getMessage())));
        return errors;
    }
}
