package io.ontoforge.boot.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.ontoforge.business.agent.AgentException;
import io.ontoforge.configuration.AgentSettings;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenAiChatClientTest {
    private static final String KEY = "test-only-provider-secret";
    private static final String FINAL = "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"done\"}}]}";
    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;
    private ExecutorService serverThreads;
    private OpenAiChatClient client;
    private final AtomicInteger calls = new AtomicInteger();

    @BeforeEach void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverThreads = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(serverThreads);
        server.start();
        client = new OpenAiChatClient(new AgentSettings(true, baseUrl(), KEY, "configured-model", "demo.synthetic", "127.0.0.1"), mapper);
    }

    @AfterEach void stopServer() {
        client.close();
        server.stop(0);
        serverThreads.shutdownNow();
    }

    private String baseUrl() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/gateway/v1"; }
    private long deadline() { return System.nanoTime() + TimeUnit.SECONDS.toNanos(5); }
    private ObjectNode request() {
        ObjectNode request = mapper.createObjectNode().put("model", "configured-model").put("stream", false);
        request.putArray("messages").addObject().put("role", "user").put("content", "synthetic question");
        request.putArray("tools");
        return request;
    }
    private void respond(HttpExchange exchange, int status, String text) throws IOException {
        calls.incrementAndGet();
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }
    private void expectFailure(ObjectNode request, long deadline, int status, String code) {
        assertThatThrownBy(() -> client.complete(request, deadline)).isInstanceOfSatisfying(AgentException.class, failure -> {
            assertThat(failure.http()).isEqualTo(status);
            assertThat(failure.code()).isEqualTo(code);
            assertThat(failure).hasNoCause();
            assertThat(failure.getMessage()).doesNotContain(KEY, "upstream-secret");
        });
    }

    @Test void postsOnlyConfiguredDestinationAndBearerWithUnmodifiedToolConversation() throws Exception {
        var actual = new AtomicReference<ObjectNode>();
        var authorization = new AtomicReference<String>();
        var cookie = new AtomicReference<String>();
        var method = new AtomicReference<String>();
        server.createContext("/gateway/v1/chat/completions", exchange -> {
            method.set(exchange.getRequestMethod());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            cookie.set(exchange.getRequestHeaders().getFirst("Cookie"));
            actual.set((ObjectNode) mapper.readTree(exchange.getRequestBody()));
            respond(exchange, 200, FINAL);
        });
        ObjectNode request = request();
        var assistant = request.withArray("messages").addObject().put("role", "assistant");
        assistant.putArray("tool_calls").addObject().put("id", "call_synthetic").put("type", "function")
                .putObject("function").put("name", "read_type").put("arguments", "{\"objectTypeId\":\"supplier\"}");
        request.withArray("messages").addObject().put("role", "tool").put("tool_call_id", "call_synthetic").put("content", "{\"status\":\"KNOWN\"}");
        assertThat(client.complete(request, deadline()).path("choices").get(0).path("message").path("content").asText()).isEqualTo("done");
        assertThat(method.get()).isEqualTo("POST");
        assertThat(authorization.get()).isEqualTo("Bearer " + KEY);
        assertThat(cookie.get()).isNull();
        assertThat(actual.get()).isEqualTo(request);
        assertThat(calls).hasValue(1);
    }

    @Test void disablesAllNetworkAndRejectsOversizedOrExpiredRequestsBeforeSending() {
        server.createContext("/gateway/v1/chat/completions", exchange -> respond(exchange, 200, FINAL));
        try (var disabled = new OpenAiChatClient(new AgentSettings(false, null, null, null, null, null), mapper)) {
            assertThatThrownBy(() -> disabled.complete(request(), deadline())).isInstanceOfSatisfying(AgentException.class,
                    failure -> assertThat(failure.code()).isEqualTo("AGENT_DISABLED"));
        }
        expectFailure(request().put("oversized", "x".repeat(1024 * 1024)), deadline(), 400, "MODEL_REQUEST_TOO_LARGE");
        expectFailure(request(), System.nanoTime() - 1, 504, "MODEL_TIMEOUT");
        assertThat(calls).hasValue(0);
    }

    @Test void mapsProviderFailuresWithoutRetryOrLeakingTheirBodies() {
        var status = new AtomicInteger(429);
        server.createContext("/gateway/v1/chat/completions", exchange -> respond(exchange, status.get(), "upstream-secret " + KEY));
        expectFailure(request(), deadline(), 429, "MODEL_RATE_LIMITED");
        status.set(401);
        expectFailure(request(), deadline(), 502, "MODEL_AUTHENTICATION_FAILED");
        status.set(503);
        expectFailure(request(), deadline(), 502, "MODEL_UNAVAILABLE");
        assertThat(calls).hasValue(3);
    }

    @Test void aCancelledWorkerNeverStartsAModelRequestAndPreservesItsInterrupt() {
        server.createContext("/gateway/v1/chat/completions", exchange -> respond(exchange, 200, FINAL));
        Thread.currentThread().interrupt();
        try {
            expectFailure(request(), deadline(), 503, "MODEL_INTERRUPTED");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(calls).hasValue(0);
        } finally { Thread.interrupted(); }
    }

    @Test void neverFollowsRedirectOrSendsCredentialsToItsDestination() {
        AtomicInteger redirected = new AtomicInteger();
        server.createContext("/gateway/v1/chat/completions", exchange -> {
            exchange.getResponseHeaders().set("Location", "http://127.0.0.1:" + server.getAddress().getPort() + "/credential-sink");
            respond(exchange, 307, "upstream-secret");
        });
        server.createContext("/credential-sink", exchange -> { redirected.incrementAndGet(); respond(exchange, 200, FINAL); });
        expectFailure(request(), deadline(), 502, "MODEL_PROTOCOL_ERROR");
        assertThat(calls).hasValue(1);
        assertThat(redirected).hasValue(0);
    }

    @Test void rejectsOversizedMalformedAndTrailingJsonResponses() {
        AtomicReference<String> response = new AtomicReference<>("x".repeat(256 * 1024 + 1));
        server.createContext("/gateway/v1/chat/completions", exchange -> respond(exchange, 200, response.get()));
        expectFailure(request(), deadline(), 502, "MODEL_RESPONSE_TOO_LARGE");
        response.set("{upstream-secret");
        expectFailure(request(), deadline(), 502, "MODEL_PROTOCOL_ERROR");
        response.set(FINAL + " {}");
        expectFailure(request(), deadline(), 502, "MODEL_PROTOCOL_ERROR");
        response.set("{\"model\":\"first\",\"model\":\"second\"}");
        expectFailure(request(), deadline(), 502, "MODEL_PROTOCOL_ERROR");
        response.set("[]");
        expectFailure(request(), deadline(), 502, "MODEL_PROTOCOL_ERROR");
    }

    @Test void deadlineAlsoClosesAStalledResponseBodyAfterHeadersArrive() throws Exception {
        CountDownLatch headersSent = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        server.createContext("/gateway/v1/chat/completions", exchange -> {
            calls.incrementAndGet();
            exchange.sendResponseHeaders(200, 0);
            try (var output = exchange.getResponseBody()) {
                output.write('{'); output.flush(); headersSent.countDown();
                try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        });
        long start = System.nanoTime();
        try {
            expectFailure(request(), start + TimeUnit.SECONDS.toNanos(1), 504, "MODEL_TIMEOUT");
            assertThat(headersSent.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(System.nanoTime() - start).isLessThan(TimeUnit.SECONDS.toNanos(3));
            assertThat(calls).hasValue(1);
        } finally { release.countDown(); }
    }

    @Test void deadlineLimitsWaitingForResponseHeaders() {
        CountDownLatch release = new CountDownLatch(1);
        server.createContext("/gateway/v1/chat/completions", exchange -> {
            calls.incrementAndGet();
            try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            try { respond(exchange, 200, FINAL); } catch (IOException ignored) { exchange.close(); }
        });
        long start = System.nanoTime();
        try {
            expectFailure(request(), start + TimeUnit.SECONDS.toNanos(1), 504, "MODEL_TIMEOUT");
            assertThat(System.nanoTime() - start).isLessThan(TimeUnit.SECONDS.toNanos(3));
        } finally { release.countDown(); }
    }
}
