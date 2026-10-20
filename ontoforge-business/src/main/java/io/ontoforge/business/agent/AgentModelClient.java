package io.ontoforge.business.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Outbound model transport; implementations must enforce the supplied monotonic deadline. */
public interface AgentModelClient {
    JsonNode complete(ObjectNode request, long deadlineNanos);
}
