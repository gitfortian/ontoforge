package io.ontoforge.core.compiler;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.dialect.Dialects;

/**
 * M8.1 authoritative 2020-12 typed contract; schemas are packaged from the
 * approved R2 candidate artifacts as build-time classpath resources.
 *
 * The HTTP/business layer owns safe error codes. Never return validator errors
 * verbatim: they can embed submitted values or schema internals.
 */
public final class M8TraverseSchema {
    private static final Schema QUERY = load("local-query-v2.candidate.schema.json");
    private static final Schema RESULT = load("local-result-v2.candidate.schema.json");
    private static final Schema BRIDGE_RESULT = load("local-result-v2-bridge.candidate.schema.json");

    private M8TraverseSchema() {}

    private static Schema load(String name) {
        try {
            Schema schema = SchemaRegistry.withDialect(Dialects.getDraft202012())
                    .getSchema(SchemaLocation.of("classpath:m8/" + name));
            schema.initializeValidators();
            return schema;
        } catch (Exception error) {
            throw new IllegalStateException("M8 v2 authoritative contract cannot initialize", error);
        }
    }

    public static boolean validQuery(JsonNode request) {
        return request != null && QUERY.validate(request.toString(), InputFormat.JSON).isEmpty();
    }

    public static boolean validBridgeResult(JsonNode result) {
        return result != null && BRIDGE_RESULT.validate(result.toString(), InputFormat.JSON).isEmpty();
    }

    public static boolean validResult(JsonNode result) {
        return result != null && RESULT.validate(result.toString(), InputFormat.JSON).isEmpty();
    }
}
