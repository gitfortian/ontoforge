package io.ontoforge.business.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AgentServiceTest {
    private static final Set<String> PACKAGES = Set.of("demo.synthetic");
    private static final UUID SNAPSHOT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final ObjectMapper mapper = new ObjectMapper();
    private AgentTools tools;
    private AgentTools.Scope scope;
    private Authentication auth;

    @BeforeEach void prepareScope() {
        tools = mock(AgentTools.class);
        scope = mock(AgentTools.Scope.class);
        auth = mock(Authentication.class);
        when(scope.snapshotId()).thenReturn(SNAPSHOT);
        when(scope.packageId()).thenReturn("demo.synthetic");
        when(scope.payloadSha256()).thenReturn("synthetic-snapshot-digest");
        when(scope.bindings()).thenReturn(List.of());
        when(scope.links()).thenReturn(List.of());
        when(tools.scope(any(), same(auth), eq(PACKAGES))).thenReturn(scope);
        when(tools.catalog(scope)).thenReturn(mapper.createArrayNode());
    }

    private AgentService service(AgentModelClient model) { return service(model, true); }
    private AgentService service(AgentModelClient model, boolean enabled) {
        return new AgentService(tools, model, mapper, enabled, "configured-model", PACKAGES);
    }
    private ObjectNode request() {
        ObjectNode request = mapper.createObjectNode().put("runVersion", "local-agent-v1").put("question", "Find synthetic suppliers");
        request.putObject("scope").put("snapshotId", SNAPSHOT.toString()).putArray("bindingRefs");
        return request;
    }
    private ObjectNode response(String finish, String content) {
        ObjectNode response = mapper.createObjectNode();
        ObjectNode choice = response.putArray("choices").addObject().put("finish_reason", finish);
        choice.putObject("message").put("role", "assistant").put("content", content);
        return response;
    }
    private ObjectNode toolCall(String id, String arguments) {
        ObjectNode response = response("tool_calls", "");
        response.withArray("choices").get(0).withObject("message").putArray("tool_calls").addObject()
                .put("id", id).put("type", "function").putObject("function")
                .put("name", "query_objects").put("arguments", arguments);
        return response;
    }
    private ObjectNode queryOutput() {
        ObjectNode output = mapper.createObjectNode().put("kind", "QUERY").put("evidenceRef", "query:frozen-result");
        ObjectNode data = output.putObject("data").put("status", "SUCCEEDED").put("executionId", "actual-execution")
                .put("resultSnapshotRef", "frozen-result").put("queryDigest", "actual-digest");
        data.putObject("quality").put("accuracy", "UNKNOWN").put("completeness", "PARTIAL")
                .put("freshness", "UNKNOWN").put("answerScope", "SELECTED_BINDING_ONLY");
        data.putObject("versionLock").putObject("typeSourceRef").put("kind", "LOCAL_SNAPSHOT").put("snapshotId", SNAPSHOT.toString());
        data.putObject("budget").put("physicalRows", 1);
        data.putArray("objects").addObject().put("label", "untrusted synthetic value");
        return output;
    }
    private void expectFailure(Runnable operation, int http, String code) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(AgentException.class, failure -> {
            assertThat(failure.http()).isEqualTo(http);
            assertThat(failure.code()).isEqualTo(code);
        });
    }
    private void noToolExecution() {
        verify(tools, never()).execute(any(), anyString(), any(), any(), anyLong());
    }

    @Test void replaysMatchingToolCallAndPreservesUnknownQualityInActualEvidence() throws Exception {
        ObjectNode output = queryOutput();
        when(tools.execute(same(scope), eq("query_objects"), any(), same(auth), anyLong())).thenReturn(output);
        AtomicInteger modelCalls = new AtomicInteger();
        AtomicReference<ObjectNode> followup = new AtomicReference<>();
        AgentModelClient model = (request, deadline) -> {
            assertThat(deadline).isGreaterThan(System.nanoTime());
            assertThat(request.path("model").asText()).isEqualTo("configured-model");
            assertThat(request.path("parallel_tool_calls").asBoolean()).isFalse();
            if (modelCalls.getAndIncrement() == 0) return toolCall("call_query", "{\"queryVersion\":\"local-query-v1\"}");
            followup.set(request.deepCopy());
            return response("stop", "A model interpretation; evidence remains partial and freshness unknown.");
        };
        ObjectNode result = service(model).run(request(), auth, () -> { });
        assertThat(result.path("status").asText()).isEqualTo("COMPLETE");
        assertThat(result.path("executionIds")).isEqualTo(mapper.readTree("[\"actual-execution\"]"));
        assertThat(result.path("evidenceRefs")).isEqualTo(mapper.readTree("[\"query:frozen-result\"]"));
        assertThat(result.path("evidence").get(0).path("quality")).isEqualTo(output.path("data").path("quality"));
        assertThat(result.path("evidence").get(0).path("versionLock")).isEqualTo(output.path("data").path("versionLock"));
        ArrayNode messages = (ArrayNode) followup.get().path("messages");
        assertThat(messages.size()).isEqualTo(4);
        assertThat(messages.get(2).path("role").asText()).isEqualTo("assistant");
        assertThat(messages.get(2).path("tool_calls").get(0).path("id").asText()).isEqualTo("call_query");
        assertThat(messages.get(3).path("role").asText()).isEqualTo("tool");
        assertThat(messages.get(3).path("tool_call_id").asText()).isEqualTo("call_query");
        assertThat(mapper.readTree(messages.get(3).path("content").asText())).isEqualTo(output);
        assertThat(result.path("usage").get(0).path("status").asText()).isEqualTo("UNKNOWN");
        assertThat(result.path("budget").path("toolCalls").asInt()).isEqualTo(1);
        verify(tools).execute(same(scope), eq("query_objects"), eq(mapper.readTree("{\"queryVersion\":\"local-query-v1\"}")), same(auth), anyLong());
    }

    @Test void malformedTrailingDuplicateArgumentsAndMultipleCallsFailBeforeDispatch() {
        for (String arguments : new String[]{"{", "{} {}", "{\"x\":1,\"x\":2}", "[]"}) {
            expectFailure(() -> service((request, deadline) -> toolCall("call_bad", arguments)).run(request(), auth, () -> { }),
                    502, "MODEL_PROTOCOL_ERROR");
        }
        ObjectNode response = toolCall("call_one", "{}");
        ArrayNode calls = (ArrayNode) response.path("choices").get(0).path("message").path("tool_calls");
        calls.add(calls.get(0).deepCopy());
        expectFailure(() -> service((request, deadline) -> response).run(request(), auth, () -> { }), 502, "MODEL_PROTOCOL_ERROR");
        noToolExecution();
    }

    @Test void repeatedOpaqueToolIdsAcrossTurnsKeepEachAssistantAndToolResultPaired() {
        when(tools.execute(same(scope), eq("query_objects"), any(), same(auth), anyLong())).thenReturn(queryOutput());
        AtomicInteger modelCalls = new AtomicInteger();
        AtomicReference<ObjectNode> finalRequest = new AtomicReference<>();
        String callId = "call.0:供应商/ref";
        AgentModelClient model = (input, deadline) -> {
            if (modelCalls.getAndIncrement() < 2) return toolCall(callId, "{}");
            finalRequest.set(input.deepCopy());
            return response("stop", "Interpretation from two separately requested tool executions.");
        };
        ObjectNode result = service(model).run(request(), auth, () -> { });
        assertThat(result.path("status").asText()).isEqualTo("COMPLETE");
        assertThat(result.path("budget").path("modelRequests").asInt()).isEqualTo(3);
        assertThat(result.path("budget").path("toolCalls").asInt()).isEqualTo(2);
        ArrayNode messages = (ArrayNode) finalRequest.get().path("messages");
        assertThat(messages.size()).isEqualTo(6);
        for (int assistantIndex : new int[]{2, 4}) {
            JsonNode assistant = messages.get(assistantIndex), tool = messages.get(assistantIndex + 1);
            assertThat(assistant.path("role").asText()).isEqualTo("assistant");
            assertThat(assistant.path("tool_calls").get(0).path("id").asText()).isEqualTo(callId);
            assertThat(tool.path("role").asText()).isEqualTo("tool");
            assertThat(tool.path("tool_call_id").asText()).isEqualTo(callId);
        }
    }

    @Test void blankOversizedAndControlCharacterToolIdsStillFailBeforeDispatch() {
        for (String id : new String[]{" ", "x".repeat(129), "call\nid"}) {
            expectFailure(() -> service((input, deadline) -> toolCall(id, "{}")).run(request(), auth, () -> { }),
                    502, "MODEL_PROTOCOL_ERROR");
        }
        noToolExecution();
    }

    @Test void disabledAndInvalidRequestsNeverResolveScopeOrCallModel() {
        AgentModelClient model = mock(AgentModelClient.class);
        expectFailure(() -> service(model, false).run(request(), auth, () -> { }), 503, "AGENT_DISABLED");
        expectFailure(() -> service(model).run(request().put("apiKey", "caller-key"), auth, () -> { }), 400, "INVALID_AGENT_REQUEST");
        expectFailure(() -> service(model).run(request().put("question", " "), auth, () -> { }), 400, "INVALID_AGENT_REQUEST");
        ObjectNode raisedLimit = request(); raisedLimit.putObject("limits").put("maxModelRequests", 7);
        expectFailure(() -> service(model).run(raisedLimit, auth, () -> { }), 400, "INVALID_AGENT_LIMIT");
        verifyNoInteractions(model);
        verify(tools, never()).scope(any(), any(), any());
        noToolExecution();
    }

    @Test void anUnauthorizedScopeStopsBeforeAnyProviderRequest() {
        AgentModelClient model = mock(AgentModelClient.class);
        when(tools.scope(any(), same(auth), eq(PACKAGES))).thenThrow(new AgentException(403, "AGENT_PACKAGE_NOT_ALLOWED"));
        expectFailure(() -> service(model).run(request(), auth, () -> { }), 403, "AGENT_PACKAGE_NOT_ALLOWED");
        verifyNoInteractions(model);
        noToolExecution();
    }

    @Test void exhaustingTheModelBudgetDoesNotDispatchAQueryOrClaimCompletion() {
        ObjectNode request = request(); request.putObject("limits").put("maxModelRequests", 1);
        ObjectNode result = service((input, deadline) -> toolCall("call_over_budget", "{}")).run(request, auth, () -> { });
        assertThat(result.path("status").asText()).isEqualTo("INCOMPLETE");
        assertThat(result.path("reasonCode").asText()).isEqualTo("AGENT_TOOL_LIMIT");
        assertThat(result.path("factVerification").asText()).isEqualTo("NO_EXECUTION_EVIDENCE");
        noToolExecution();
    }

    @Test void toolBudgetStopsAdditionalQueriesAndKeepsAlreadyCollectedEvidenceIncomplete() {
        when(tools.execute(same(scope), eq("query_objects"), any(), same(auth), anyLong())).thenReturn(queryOutput());
        AtomicInteger modelCalls = new AtomicInteger();
        ObjectNode request = request(); request.putObject("limits").put("maxToolCalls", 1);
        ObjectNode result = service((input, deadline) -> toolCall("call_" + modelCalls.incrementAndGet(), "{}"))
                .run(request, auth, () -> { });
        assertThat(result.path("status").asText()).isEqualTo("INCOMPLETE");
        assertThat(result.path("reasonCode").asText()).isEqualTo("AGENT_TOOL_LIMIT");
        assertThat(result.path("evidence").size()).isEqualTo(1);
        assertThat(result.path("budget").path("toolCalls").asInt()).isEqualTo(1);
        assertThat(modelCalls).hasValue(2);
        verify(tools).execute(same(scope), eq("query_objects"), any(), same(auth), anyLong());
    }

    @Test void refusalAndTokenOrContentLimitsRemainIncompleteEvenWithPlausibleText() {
        for (String finish : new String[]{"length", "content_filter", "stop"}) {
            ObjectNode response = response(finish, "Plausible but incomplete model text");
            if (finish.equals("stop")) response.withArray("choices").get(0).withObject("message").put("refusal", "Unable to answer");
            ObjectNode result = service((input, deadline) -> response).run(request(), auth, () -> { });
            assertThat(result.path("status").asText()).isEqualTo("INCOMPLETE");
            assertThat(result.path("answer").asText()).isEmpty();
        }
        noToolExecution();
    }

    @Test void revocationWhileWaitingForTheModelPreventsToolExecutionAndFinalDelivery() {
        AtomicBoolean active = new AtomicBoolean(true);
        Runnable identity = () -> { if (!active.get()) throw new AgentException(401, "SESSION_REVOKED"); };
        for (ObjectNode response : new ObjectNode[]{toolCall("call_after_revoke", "{}"), response("stop", "Previously authorized answer")}) {
            active.set(true);
            AgentModelClient model = (input, deadline) -> { active.set(false); return response; };
            expectFailure(() -> service(model).run(request(), auth, identity), 401, "SESSION_REVOKED");
        }
        noToolExecution();
    }

    @Test void finalUtf8EnvelopeBudgetCannotBeBypassedByACompleteModelResponse() {
        String answer = "\u4e2d".repeat(22000);
        expectFailure(() -> service((input, deadline) -> response("stop", answer)).run(request(), auth, () -> { }),
                422, "AGENT_OUTPUT_BUDGET_EXCEEDED");
        noToolExecution();
    }
}
