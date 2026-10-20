package io.ontoforge.boot.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.ontoforge.business.agent.AgentModelClient;
import io.ontoforge.business.agent.AgentService;
import io.ontoforge.business.agent.AgentTools;
import io.ontoforge.configuration.AgentSettings;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class AgentRunConfiguration {
    @Bean AgentService agentService(AgentTools tools, AgentModelClient model, ObjectMapper mapper, AgentSettings settings) {
        return new AgentService(tools, model, mapper, settings.enabled(), settings.model(), settings.allowedPackages());
    }

    /** No unbounded queue or background continuation after a caller deadline. */
    @Bean(destroyMethod = "shutdownNow") ExecutorService agentRuns() {
        return new ThreadPoolExecutor(0, 4, 30, TimeUnit.SECONDS, new SynchronousQueue<>(), task -> {
            Thread thread = new Thread(task, "ontoforge-agent-run");
            thread.setDaemon(true); return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    }
}
