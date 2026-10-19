package io.ontoforge.core.compiler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** Deployed M8 query/result schemas must be authoritative and fail closed. */
class M8TraverseSchemaTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private ObjectNode query() {
        ObjectNode q = mapper.createObjectNode();
        q.put("queryVersion", "local-query-v2");
        ObjectNode type = q.putObject("rootTypeRef");
        type.putObject("sourceRef").put("kind", "LOCAL_SNAPSHOT")
                .put("snapshotId", "11111111-1111-4111-8111-111111111111");
        type.put("packageId", "m8.test.pkg");
        type.put("objectTypeId", "factory-cell");
        q.putObject("rootBindingRef").put("bindingId", "22222222-2222-4222-8222-222222222222")
                .put("revision", 1);
        ObjectNode ref = q.putObject("rootSelector").put("kind", "OBJECT_REF")
                .putObject("objectRef");
        ref.put("packageId", "m8.test.pkg");
        ref.put("objectTypeId", "factory-cell");
        ref.put("identityDomainId", "33333333-3333-4333-8333-333333333333");
        ref.putObject("key").put("valueType", "STRING").put("value", "CELL-01");
        ArrayNode steps = q.putArray("steps");
        ObjectNode step = steps.addObject();
        step.put("kind", "TRAVERSE");
        step.put("linkTypeRef", "cell-quality");
        step.putObject("linkBindingRef")
                .put("bindingId", "44444444-4444-4444-8444-444444444444").put("revision", 1);
        step.put("direction", "OUT");
        step.put("maxDepth", 1);
        steps.addObject().put("kind", "PAGE").put("pageSize", 25);
        return q;
    }

    @Test void acceptsTypedCanonicalOneHopQueryWithoutNetworkSchemaFetch() {
        assertThat(M8TraverseSchema.validQuery(query())).isTrue();
    }

    @Test void deniesExtraFieldsStringNumbersAndMultiHop() {
        var extra = query();
        extra.put("unverified", true);
        assertThat(M8TraverseSchema.validQuery(extra)).isFalse();

        var pageString = query();
        ((ObjectNode) pageString.withArray("steps").get(1)).put("pageSize", "25");
        assertThat(M8TraverseSchema.validQuery(pageString)).isFalse();

        var depth = query();
        ((ObjectNode) depth.withArray("steps").get(0)).put("maxDepth", 2);
        assertThat(M8TraverseSchema.validQuery(depth)).isFalse();

        var forgedKey = query();
        ((ObjectNode) forgedKey.path("rootSelector").path("objectRef").path("key"))
                .put("valueType", "BOOLEAN");
        assertThat(M8TraverseSchema.validQuery(forgedKey)).isFalse();
    }

    @Test void verifiesTerminalAggregateAndRejectsMalformedPayloads() {
        var count = query();
        ArrayNode steps = count.withArray("steps");
        steps.remove(1);
        steps.addObject().put("kind", "AGGREGATE").put("operation", "COUNT_DISTINCT");
        assertThat(M8TraverseSchema.validQuery(count)).isTrue();

        var sum = count.deepCopy();
        ((ObjectNode) sum.withArray("steps").get(1)).put("operation", "SUM")
                .put("propertyRef", "quality-amount");
        assertThat(M8TraverseSchema.validQuery(sum)).isTrue();

        ((ObjectNode) sum.withArray("steps").get(1)).remove("propertyRef");
        assertThat(M8TraverseSchema.validQuery(sum)).isFalse();

        assertThat(M8TraverseSchema.validResult(mapper.createObjectNode()
                .put("status", "SUCCESS"))).isFalse();
        assertThat(M8TraverseSchema.validResult(mapper.createObjectNode()
                .put("status", "FAILED").put("unapprovedEvidence", "secret"))).isFalse();
    }
}
