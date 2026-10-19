package io.ontoforge.boot.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.ontoforge.boot.security.yak.HttpSessionAccounts;
import io.ontoforge.business.agent.AgentException;
import io.ontoforge.business.agent.AgentModelClient;
import io.ontoforge.business.agent.AgentService;
import io.ontoforge.business.agent.AgentTools;
import io.ontoforge.business.query.LinkTraverseService;
import io.ontoforge.business.query.ObjectSetQueryService;
import io.ontoforge.configuration.AgentSettings;
import io.ontoforge.platform.identity.LocalAccountPrincipal;
import io.ontoforge.platform.identity.LocalAccountService;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentControllerTest {
    @Test void revocationAfterEvidenceReadInvalidatesTheOwnersHandleAndPreservesTheAuthenticationFailure() {
        ObjectMapper mapper = new ObjectMapper();
        LocalAccountPrincipal principal = new LocalAccountPrincipal(UUID.randomUUID(), UUID.randomUUID(), 1,
                "test.owner", "Test Owner", false);
        Authentication auth = UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/context/v1/evidence/query:owned-result");
        HttpSessionAccounts sessionManager = new HttpSessionAccounts(Duration.ofMinutes(30));
        sessionManager.login(request, principal);
        @SuppressWarnings("unchecked") ObjectProvider<HttpSessionAccounts> sessions = mock(ObjectProvider.class);
        when(sessions.getIfAvailable()).thenReturn(sessionManager);
        LocalAccountService accounts = mock(LocalAccountService.class);
        when(accounts.current(principal.accountId(), principal.credentialVersion())).thenReturn(principal, null);
        ObjectSetQueryService queries = mock(ObjectSetQueryService.class);
        when(queries.evidence("owned-result", auth)).thenReturn(mapper.createObjectNode()
                .put("packageId", "demo.synthetic").put("executionId", "original-execution"));
        doThrow(new IllegalStateException("cleanup-failure-details")).when(queries).invalidateResult("owned-result", auth);
        AgentSettings settings = new AgentSettings(true, "http://127.0.0.1:1/v1", "test-only-key", "test-model",
                "demo.synthetic", "127.0.0.1");
        ExecutorService workers = Executors.newSingleThreadExecutor();
        try {
            AgentController controller = new AgentController(mock(AgentService.class), mapper, settings, accounts,
                    sessions, mock(Environment.class), workers, queries, mock(LinkTraverseService.class));
            assertThatThrownBy(() -> controller.evidence("query:owned-result", request, auth))
                    .isInstanceOfSatisfying(AgentException.class, failure -> {
                        assertThat(failure.http()).isEqualTo(401);
                        assertThat(failure.code()).isEqualTo("AUTHENTICATION_REQUIRED");
                        assertThat(failure).hasNoCause();
                    });
            verify(accounts, times(2)).current(principal.accountId(), principal.credentialVersion());
            verify(queries).evidence("owned-result", auth);
            verify(queries).invalidateResult("owned-result", auth);
        } finally { workers.shutdownNow(); }
    }

    @Test void timedOutRunCannotSendToTheModelAfterABlockedCheckSwallowsItsInterrupt() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AgentTools tools = mock(AgentTools.class);
        AgentTools.Scope scope = mock(AgentTools.Scope.class);
        Authentication auth = mock(Authentication.class);
        UUID snapshotId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        when(scope.snapshotId()).thenReturn(snapshotId);
        when(scope.packageId()).thenReturn("demo.synthetic");
        when(scope.payloadSha256()).thenReturn("synthetic-digest");
        when(scope.bindings()).thenReturn(List.of());
        when(scope.links()).thenReturn(List.of());
        when(tools.scope(any(), same(auth), eq(Set.of("demo.synthetic")))).thenReturn(scope);
        when(tools.catalog(scope)).thenReturn(mapper.createArrayNode());

        CountDownLatch checking = new CountDownLatch(1);
        CountDownLatch interruptSwallowed = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicLong innerDeadline = new AtomicLong();
        doAnswer(invocation -> {
            innerDeadline.set(invocation.getArgument(2));
            checking.countDown();
            boolean released = false;
            while (!released) {
                try { released = release.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException ignored) {
                    // Represents a driver that ignores cancellation and clears the interrupt flag.
                    assertThat(Thread.currentThread().isInterrupted()).isFalse();
                    interruptSwallowed.countDown();
                }
            }
            return null;
        }).when(tools).recheck(same(scope), same(auth), anyLong());

        AtomicInteger modelRequests = new AtomicInteger();
        AgentModelClient model = (request, deadline) -> {
            modelRequests.incrementAndGet();
            var response = mapper.createObjectNode();
            response.putArray("choices").addObject().put("finish_reason", "stop")
                    .putObject("message").put("role", "assistant").put("content", "Must never be delivered");
            return response;
        };
        AgentService service = new AgentService(tools, model, mapper, true, "test-model", Set.of("demo.synthetic"));
        AgentSettings settings = new AgentSettings(true, "http://127.0.0.1:1/v1", "test-only-key", "test-model",
                "demo.synthetic", "127.0.0.1");
        Environment environment = mock(Environment.class);
        when(environment.acceptsProfiles(any(Profiles.class))).thenReturn(true);
        @SuppressWarnings("unchecked") ObjectProvider<HttpSessionAccounts> sessions = mock(ObjectProvider.class);
        // Delay worker startup so its own budget still has time left when the outer request expires.
        // This isolates outer cancellation from the service's independent deadline checks.
        ExecutorService workers = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(() -> {
                try { TimeUnit.MILLISECONDS.sleep(1000); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                task.run();
            }, "agent-timeout-regression");
            thread.setDaemon(true);
            return thread;
        });
        try {
            AgentController controller = new AgentController(service, mapper, settings, mock(LocalAccountService.class),
                    sessions, environment, workers, mock(ObjectSetQueryService.class), mock(LinkTraverseService.class));
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/agent/v1/runs");
            var body = mapper.createObjectNode().put("runVersion", "local-agent-v1").put("question", "Synthetic question");
            body.putObject("scope").put("snapshotId", snapshotId.toString()).putArray("bindingRefs");
            body.putObject("limits").put("timeoutSeconds", 2);
            request.setContentType("application/json");
            request.setContent(mapper.writeValueAsBytes(body));

            assertThatThrownBy(() -> controller.run(request, auth)).isInstanceOfSatisfying(AgentException.class, failure -> {
                assertThat(failure.http()).isEqualTo(504);
                assertThat(failure.code()).isEqualTo("AGENT_TIMEOUT");
            });
            assertThat(checking.getCount()).isZero();
            assertThat(interruptSwallowed.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(innerDeadline.get() - System.nanoTime()).isGreaterThan(TimeUnit.MILLISECONDS.toNanos(100));
            release.countDown();
            workers.submit(() -> { }).get(3, TimeUnit.SECONDS);
            assertThat(modelRequests).hasValue(0);
        } finally {
            release.countDown();
            workers.shutdownNow();
        }
    }
}
