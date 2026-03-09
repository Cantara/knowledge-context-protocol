package no.cantara.kcp.sim.http.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A2A task request sent by the Orchestrator to the Research Agent.
 *
 * <p>Sent as JSON POST to {@code /a2a/tasks}:
 * <pre>
 * {
 *   "task": "load_knowledge_unit",
 *   "unit_id": "trial-protocols",
 *   "delegation_depth": 0
 * }
 * </pre>
 *
 * <p>The {@code delegation_depth} tracks how many agents have forwarded this
 * request, enforcing the KCP {@code delegation.max_depth} constraint.
 *
 * @param task            the task type (e.g. "load_knowledge_unit")
 * @param unitId          the KCP knowledge unit to access
 * @param delegationDepth current depth in the delegation chain (0 = direct call)
 */
public record A2ATaskRequest(
        @JsonProperty("task") String task,
        @JsonProperty("unit_id") String unitId,
        @JsonProperty("delegation_depth") int delegationDepth
) {}
