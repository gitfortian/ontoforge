package io.ontoforge.boot.agent;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.ontoforge.boot.security.yak.HttpSessionAccounts;
import io.ontoforge.business.agent.AgentException;
import io.ontoforge.business.agent.AgentService;
import io.ontoforge.business.connection.DataBindingService;
import io.ontoforge.business.connection.LinkBindingService;
import io.ontoforge.business.model.NativeDraftService;
import io.ontoforge.business.object.ObjectRuntimeService;
import io.ontoforge.business.query.LinkTraverseService;
import io.ontoforge.business.query.ObjectSetQueryService;
import io.ontoforge.configuration.AgentSettings;
import io.ontoforge.platform.identity.LocalAccountPrincipal;
import io.ontoforge.platform.identity.LocalAccountService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Function;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Loopback development API. The model receives neither browser identity nor credentials. */
@RestController
public class AgentController {
    private final AgentService service;
    private final ObjectMapper mapper;
    private final AgentSettings settings;
    private final LocalAccountService accounts;
    private final ObjectProvider<HttpSessionAccounts> sessions;
    private final Environment environment;
    private final ExecutorService workers;
    private final ObjectSetQueryService queries;
    private final LinkTraverseService traversals;

    public AgentController(AgentService service, ObjectMapper mapper, AgentSettings settings,
            LocalAccountService accounts, ObjectProvider<HttpSessionAccounts> sessions, Environment environment,
            @Qualifier("agentRuns") ExecutorService workers, ObjectSetQueryService queries, LinkTraverseService traversals) {
        this.service = service; this.mapper = mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        this.settings = settings; this.accounts = accounts; this.sessions = sessions; this.environment = environment;
        this.workers = workers; this.queries = queries; this.traversals = traversals;
    }

    @PostMapping(value = "/api/agent/v1/runs", consumes = "application/json", produces = "application/json")
    public ResponseEntity<ObjectNode> run(HttpServletRequest request, Authentication authentication) throws Exception {
        requireEnabled();
        byte[] input = request.getInputStream().readNBytes(32769);
        if (input.length > 32768) throw new AgentException(413, "AGENT_REQUEST_TOO_LARGE");
        JsonNode body;
        try (JsonParser parser = mapper.createParser(input)) {
            body = mapper.readTree(parser);
            if (body == null || parser.nextToken() != null) throw new AgentException(400, "INVALID_AGENT_REQUEST");
        } catch (AgentException invalid) { throw invalid; }
        catch (Exception invalid) { throw new AgentException(400, "INVALID_AGENT_REQUEST"); }
        JsonNode requested = body.path("limits").path("timeoutSeconds");
        int seconds = requested.isIntegralNumber() && requested.canConvertToInt()
                && requested.intValue() > 0 && requested.intValue() <= 60 ? requested.intValue() : 60;
        JsonNode parsed = body;
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(bounded(
                cancellation -> service.run(parsed, authentication, () -> {
                    cancellation.run(); identity(request, authentication); cancellation.run();
                }), seconds));
    }

    @GetMapping(value = "/api/context/v1/evidence/{reference}", produces = "application/json")
    public ResponseEntity<ObjectNode> evidence(@PathVariable String reference, HttpServletRequest request,
            Authentication authentication) {
        requireEnabled();
        ObjectNode result = bounded(cancellation -> {
            cancellation.run();
            identity(request, authentication);
            ObjectNode value;
            if (reference.startsWith("query:")) value = queries.evidence(reference.substring(6), authentication);
            else if (reference.startsWith("traverse:")) value = traversals.evidence(reference.substring(9), authentication);
            else throw new AgentException(400, "INVALID_EVIDENCE_REF");
            String packageId = value.path("packageId").asText(value.path("rootTypeRef").path("packageId").asText());
            if (!settings.allowedPackages().contains(packageId)) throw new AgentException(403, "AGENT_SCOPE_FORBIDDEN");
            identity(request, authentication);
            if (value.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 65536)
                throw new AgentException(422, "AGENT_OUTPUT_BUDGET_EXCEEDED");
            cancellation.run();
            return value;
        }, 60);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
    }

    private void requireEnabled() { if (!settings.enabled()) throw new AgentException(503, "AGENT_DISABLED"); }

    private void identity(HttpServletRequest request, Authentication authentication) {
        if (Thread.currentThread().isInterrupted()) throw new AgentException(504, "AGENT_TIMEOUT");
        if (environment.acceptsProfiles(Profiles.of("mvp-local"))) return;
        if (authentication == null || !authentication.isAuthenticated()) throw new AgentException(401, "AUTHENTICATION_REQUIRED");
        if (authentication.getPrincipal() instanceof LocalAccountPrincipal principal) {
            HttpSessionAccounts manager = sessions.getIfAvailable();
            HttpSessionAccounts.Login login = manager == null ? null : manager.read(request);
            if (login == null || !login.accountId().equals(principal.accountId())
                    || login.credentialVersion() != principal.credentialVersion()
                    || accounts.current(principal.accountId(), principal.credentialVersion()) == null)
                throw new AgentException(401, "AUTHENTICATION_REQUIRED");
        } else if (authentication instanceof JwtAuthenticationToken jwt) {
            Instant expires = jwt.getToken().getExpiresAt();
            if (expires == null || !expires.isAfter(Instant.now())) throw new AgentException(401, "AUTHENTICATION_REQUIRED");
        } else throw new AgentException(401, "AUTHENTICATION_REQUIRED");
    }

    private ObjectNode bounded(Function<Runnable, ObjectNode> operation, int seconds) {
        AtomicBoolean cancelled = new AtomicBoolean();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        Runnable cancellation = () -> {
            if (cancelled.get() || Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline)
                throw new AgentException(504, "AGENT_TIMEOUT");
        };
        java.util.concurrent.Future<ObjectNode> future;
        try { future = workers.submit(() -> operation.apply(cancellation)); }
        catch (RejectedExecutionException busy) { throw new AgentException(429, "AGENT_CAPACITY_EXCEEDED"); }
        try { return future.get(seconds, TimeUnit.SECONDS); }
        catch (TimeoutException timeout) { cancelled.set(true); future.cancel(true); throw new AgentException(504, "AGENT_TIMEOUT"); }
        catch (InterruptedException interrupted) {
            cancelled.set(true); future.cancel(true); Thread.currentThread().interrupt(); throw new AgentException(504, "AGENT_TIMEOUT");
        } catch (ExecutionException error) {
            if (error.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new AgentException(500, "AGENT_FAILED");
        }
    }
}

@RestControllerAdvice(assignableTypes = AgentController.class)
class AgentErrorAdvice {
    @ExceptionHandler(Exception.class)
    ResponseEntity<ObjectNode> failure(Exception error) {
        int http = 500; String code = "AGENT_FAILED";
        if (error instanceof AgentException ex) { http = ex.http(); code = ex.code(); }
        else if (error instanceof AccessDeniedException) { http = 403; code = "FORBIDDEN"; }
        else if (error instanceof ObjectSetQueryService.QueryException ex) { http = ex.http(); code = ex.code(); }
        else if (error instanceof ObjectRuntimeService.ObjectRuntimeFailure ex) { http = ex.http(); code = ex.code(); }
        else if (error instanceof LinkTraverseService.TraverseFailure ex) { http = ex.http; code = ex.code; }
        else if (error instanceof DataBindingService.BindingFailure ex) { http = ex.http; code = ex.code; }
        else if (error instanceof LinkBindingService.LinkFailure ex) { http = ex.http; code = ex.code; }
        else if (error instanceof NativeDraftService.DraftFailure ex) { http = ex.http; code = ex.code; }
        else if (error instanceof DataAccessException) { http = 503; code = "PERSISTENCE_UNAVAILABLE"; }
        else if (error instanceof IllegalArgumentException) { http = 400; code = "INVALID_AGENT_REQUEST"; }
        ObjectNode body = new ObjectMapper().createObjectNode().put("runVersion", "local-agent-v1")
                .put("requestId", UUID.randomUUID().toString()).put("status", "FAILED").put("reasonCode", code);
        return ResponseEntity.status(http).cacheControl(CacheControl.noStore()).body(body);
    }
}
