package io.ontoforge.boot.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.ontoforge.configuration.AgentSettings;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Assembly only; the model loop and ontology tool dispatch belong to Business. */
@Configuration(proxyBeanMethods = false)
public class AgentConfiguration {
    @Bean AgentSettings agentSettings(
            @Value("${ontoforge.agent.enabled:false}") boolean enabled,
            @Value("${ontoforge.agent.base-url:}") String baseUrl,
            @Value("${ontoforge.agent.api-key:}") String apiKey,
            @Value("${ontoforge.agent.model:}") String model,
            @Value("${ontoforge.agent.allowed-packages:}") String allowedPackages,
            @Value("${server.address:127.0.0.1}") String serverAddress) {
        return new AgentSettings(enabled, baseUrl, apiKey, model, allowedPackages, serverAddress);
    }

    @Bean(destroyMethod = "close") OpenAiChatClient agentModelClient(AgentSettings settings, ObjectMapper mapper) {
        return new OpenAiChatClient(settings, mapper);
    }
}
