package io.ontoforge.boot.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.ontoforge.business.agent.AgentException;
import io.ontoforge.business.agent.AgentModelClient;
import io.ontoforge.configuration.AgentSettings;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Small non-streaming Chat Completions transport; never retries or follows a redirect. */
public final class OpenAiChatClient implements AgentModelClient, AutoCloseable {
    private static final int MAX_REQUEST_BYTES = 1024 * 1024;
    private static final int MAX_RESPONSE_BYTES = 256 * 1024;
    private static final long REQUEST_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(15);
    private final AgentSettings settings;
    private final ObjectMapper mapper;
    private final HttpClient http;
    private final ScheduledExecutorService deadlines;

    public OpenAiChatClient(AgentSettings settings, ObjectMapper mapper) {
        this.settings = settings;
        this.mapper = mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        this.http = settings.enabled() ? HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(3)).build() : null;
        this.deadlines = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "ontoforge-model-body-deadline");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override public JsonNode complete(ObjectNode request, long deadlineNanos) {
        if (!settings.enabled()) throw failure(503, "AGENT_DISABLED");
        if (Thread.currentThread().isInterrupted()) throw failure(503, "MODEL_INTERRUPTED");
        long remaining = Math.min(REQUEST_TIMEOUT_NANOS, deadlineNanos - System.nanoTime());
        if (remaining <= 0) throw failure(504, "MODEL_TIMEOUT");
        byte[] payload;
        try {
            if (request == null) throw failure(502, "MODEL_PROTOCOL_ERROR");
            payload = mapper.writeValueAsBytes(request);
        } catch (JsonProcessingException e) {
            throw failure(502, "MODEL_PROTOCOL_ERROR");
        }
        if (payload.length > MAX_REQUEST_BYTES) throw failure(400, "MODEL_REQUEST_TOO_LARGE");
        long attemptDeadline = System.nanoTime() + Math.min(remaining, deadlineNanos - System.nanoTime());
        long requestRemaining = attemptDeadline - System.nanoTime();
        if (requestRemaining <= 0) throw failure(504, "MODEL_TIMEOUT");
        HttpRequest outgoing = HttpRequest.newBuilder(settings.endpoint())
                .timeout(Duration.ofNanos(requestRemaining))
                .header("Authorization", "Bearer " + settings.apiKey())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload)).build();
        try {
            if (Thread.currentThread().isInterrupted()) throw failure(503, "MODEL_INTERRUPTED");
            HttpResponse<InputStream> response = http.send(outgoing, HttpResponse.BodyHandlers.ofInputStream());
            AtomicBoolean timedOut = new AtomicBoolean();
            try (InputStream body = response.body()) {
                ScheduledFuture<?> watchdog = deadlines.schedule(() -> {
                    timedOut.set(true);
                    try { body.close(); } catch (IOException ignored) { }
                }, Math.max(0, attemptDeadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                try {
                    int status = response.statusCode();
                    if (status == 429) throw failure(429, "MODEL_RATE_LIMITED");
                    if (status == 401 || status == 403) throw failure(502, "MODEL_AUTHENTICATION_FAILED");
                    if (status >= 300 && status < 400) throw failure(502, "MODEL_PROTOCOL_ERROR");
                    if (status != 200) throw failure(502, "MODEL_UNAVAILABLE");
                    byte[] bytes = body.readNBytes(MAX_RESPONSE_BYTES + 1);
                    if (timedOut.get() || attemptDeadline - System.nanoTime() <= 0) throw failure(504, "MODEL_TIMEOUT");
                    if (bytes.length > MAX_RESPONSE_BYTES) throw failure(502, "MODEL_RESPONSE_TOO_LARGE");
                    JsonNode parsed = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(bytes);
                    if (parsed == null || !parsed.isObject()) throw failure(502, "MODEL_PROTOCOL_ERROR");
                    if (timedOut.get() || attemptDeadline - System.nanoTime() <= 0) throw failure(504, "MODEL_TIMEOUT");
                    return parsed;
                } catch (IOException e) {
                    throw failure(timedOut.get() || attemptDeadline - System.nanoTime() <= 0 ? 504 : 502,
                            timedOut.get() || attemptDeadline - System.nanoTime() <= 0 ? "MODEL_TIMEOUT" : "MODEL_PROTOCOL_ERROR");
                } finally {
                    watchdog.cancel(false);
                }
            }
        } catch (HttpTimeoutException e) {
            throw failure(504, "MODEL_TIMEOUT");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw failure(503, "MODEL_INTERRUPTED");
        } catch (IOException e) {
            throw failure(attemptDeadline - System.nanoTime() <= 0 ? 504 : 502,
                    attemptDeadline - System.nanoTime() <= 0 ? "MODEL_TIMEOUT" : "MODEL_TRANSPORT_ERROR");
        }
    }

    private static AgentException failure(int http, String code) { return new AgentException(http, code); }

    @Override public void close() {
        deadlines.shutdownNow();
        if (http != null) http.shutdownNow();
    }
}
