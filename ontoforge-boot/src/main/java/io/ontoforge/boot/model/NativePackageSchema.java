package io.ontoforge.boot.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.dialect.Dialects;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Validate the completed Native Candidate against the SAME draft 2020-12 contract as Python. */
public final class NativePackageSchema {
    private NativePackageSchema() {}
    private static final Schema AUTHORITY=loadSchema();

    private static Schema loadSchema() {
        try {
            Schema schema=SchemaRegistry.withDialect(Dialects.getDraft202012())
                    .getSchema(SchemaLocation.of("classpath:native/ontology-package-v0.2.schema.json"));
            schema.initializeValidators();
            return schema;
        } catch(Exception e) {
            throw new IllegalStateException("Cannot initialize authoritative Native V0.2 schema",e);
        }
    }

    public static List<DraftValidation.Issue> validate(JsonNode candidate) {
        List<DraftValidation.Issue> errors=new ArrayList<>();
        // Validator 3 uses Jackson 3 internally; keep the existing HTTP/persisted Jackson 2
        // contract at this adapter boundary and feed the validator lossless JSON text.
        AUTHORITY.validate(candidate.toString(), InputFormat.JSON).stream()
            .sorted(Comparator.comparing(x->x.getMessage()))
            .forEach(x->errors.add(new DraftValidation.Issue(
                x.getInstanceLocation().toString(), "NATIVE_SCHEMA_FAILED",
                "当前模型不满足 Native V0.2 契约：" + x.getMessage())));
        return errors;
    }
}
