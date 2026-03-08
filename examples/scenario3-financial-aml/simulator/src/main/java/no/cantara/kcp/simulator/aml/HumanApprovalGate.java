package no.cantara.kcp.simulator.aml;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Human-in-the-loop approval gate. In auto-approve mode (for CI), approvals
 * are granted immediately. In interactive mode, the user is prompted on stdin.
 */
public final class HumanApprovalGate {

    private final boolean autoApprove;
    private final ConsoleLog log;
    private final String defaultApprover;

    public HumanApprovalGate(boolean autoApprove, ConsoleLog log) {
        this.autoApprove = autoApprove;
        this.log = log;
        this.defaultApprover = "researcher@example.com";
    }

    /**
     * Request human approval for accessing a restricted unit.
     *
     * @param unitId      the unit requiring approval
     * @param sensitivity the sensitivity classification
     * @param mechanism   the approval mechanism (e.g. oauth_consent)
     * @return the approver identity, or null if denied
     */
    public String requestApproval(String unitId, String sensitivity, String mechanism) {
        log.hitl("Approval required for unit '" + unitId + "' (sensitivity: " + sensitivity + ", PII data)");

        if (autoApprove) {
            log.hitl("Waiting for approval... (use --auto-approve to skip in CI)");
            log.hitl("Approved by: " + defaultApprover);
            return defaultApprover;
        }

        // Interactive mode
        log.hitl("Waiting for approval... (type 'approve' or 'deny')");
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
            String input = reader.readLine();
            if (input != null && input.trim().equalsIgnoreCase("approve")) {
                log.hitl("Approved by: " + defaultApprover);
                return defaultApprover;
            } else {
                log.hitl("Denied by user");
                return null;
            }
        } catch (Exception e) {
            log.hitl("Error reading input, defaulting to deny: " + e.getMessage());
            return null;
        }
    }
}
