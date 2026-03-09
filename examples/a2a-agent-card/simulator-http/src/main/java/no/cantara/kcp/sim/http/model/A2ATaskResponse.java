package no.cantara.kcp.sim.http.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * A2A task response returned by the Research Agent.
 *
 * <p>Contains the KCP policy decision plus (if allowed) content preview:
 * <pre>
 * {
 *   "allowed": true,
 *   "unit_id": "trial-protocols",
 *   "reason": "authenticated: valid token with scope read:protocols",
 *   "content_preview": "BEACON-3 Phase III Study ...",
 *   "delegation": { "max_depth": 2, "current_depth": 0 },
 *   "compliance": { "sensitivity": "internal", "audit_required": true }
 * }
 * </pre>
 *
 * @param allowed        whether access was granted
 * @param unitId         the requested knowledge unit
 * @param reason         human-readable explanation of the decision
 * @param contentPreview truncated content (only if allowed)
 * @param delegation     delegation chain metadata
 * @param compliance     compliance/audit metadata
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record A2ATaskResponse(
        @JsonProperty("allowed") boolean allowed,
        @JsonProperty("unit_id") String unitId,
        @JsonProperty("reason") String reason,
        @JsonProperty("content_preview") String contentPreview,
        @JsonProperty("delegation") Map<String, Object> delegation,
        @JsonProperty("compliance") Map<String, Object> compliance
) {

    /** Convenience factory for a denied response. */
    public static A2ATaskResponse denied(String unitId, String reason) {
        return new A2ATaskResponse(false, unitId, reason, null, null, null);
    }

    /** Convenience factory for a granted response with content. */
    public static A2ATaskResponse granted(String unitId, String reason, String contentPreview,
                                           Map<String, Object> delegation, Map<String, Object> compliance) {
        return new A2ATaskResponse(true, unitId, reason, contentPreview, delegation, compliance);
    }
}
