package no.cantara.kcp.simulator;

import no.cantara.kcp.KcpParser;
import no.cantara.kcp.model.AuthMethod;
import no.cantara.kcp.model.KnowledgeManifest;
import no.cantara.kcp.model.KnowledgeUnit;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The Energy Metering Agent: owns 4 knowledge units with escalating access.
 * Uses both KcpParser (for typed model) and raw SnakeYAML (for delegation fields).
 */
public final class EnergyMeteringAgent {

    private static final Yaml RAW_YAML = new Yaml(new SafeConstructor(new LoaderOptions()));

    /** Simulated content for each knowledge unit. */
    private static final Map<String, String> SIMULATED_CONTENT = Map.of(
            "tariff-schedule",
            "Peak rate: 1.45 NOK/kWh (06:00-21:00). Off-peak: 0.38 NOK/kWh. " +
                    "Winter surcharge: +0.12 NOK/kWh (Nov-Mar). Network tariff: 0.55 NOK/kWh fixed.",
            "meter-readings",
            "Household H-7742: January 2026 consumption 1,247 kWh (avg 40.2 kWh/day). " +
                    "Peak usage: 67% of total. Year-over-year change: -8.3%.",
            "billing-history",
            "Invoice #2026-01-7742: 1,247 kWh x blended rate = 1,842.50 NOK. " +
                    "Previous balance: 0.00 NOK. Due date: 2026-02-15. Payment status: pending.",
            "smart-meter-raw",
            "Meter SM-7742-A: 15s telemetry 2026-01-15T14:30:00Z. Voltage: 230.2V, " +
                    "Current: 12.4A, Power factor: 0.97, Frequency: 50.01Hz. " +
                    "Quality events: 0 sags, 0 swells in last 24h."
    );

    private KnowledgeManifest manifest;
    private DelegationConfig rootDelegation;
    private Map<String, DelegationConfig> unitDelegation;

    /**
     * Load the KCP manifest from a file path.
     * Parses it twice: once with KcpParser for the typed model, once raw for delegation.
     */
    @SuppressWarnings("unchecked")
    public void loadManifest(Path path) throws IOException {
        // Typed parse via KcpParser
        manifest = KcpParser.parse(path);

        // Raw parse for delegation fields (not in KcpParser model)
        Map<String, Object> rawData;
        try (InputStream is = Files.newInputStream(path)) {
            rawData = RAW_YAML.load(is);
        }

        rootDelegation = DelegationConfig.fromRootYaml(rawData);

        // Per-unit delegation
        unitDelegation = new HashMap<>();
        List<Map<String, Object>> rawUnits = (List<Map<String, Object>>) rawData.getOrDefault("units", List.of());
        for (Map<String, Object> rawUnit : rawUnits) {
            String id = (String) rawUnit.get("id");
            if (id != null) {
                unitDelegation.put(id, DelegationConfig.fromUnitYaml(rawUnit, rootDelegation));
            }
        }
    }

    /** Print KCP discovery information to the console. */
    public void printDiscovery(ConsoleLog log) {
        log.kcp("Energy Metering Agent reads knowledge.yaml (KCP v" + manifest.kcpVersion() + ")");
        log.kcp("Project: " + manifest.project() + " v" + manifest.version());

        // Trust
        if (manifest.trust() != null) {
            String publisher = manifest.trust().provenance() != null
                    ? manifest.trust().provenance().publisher() : "unknown";
            boolean auditRequired = manifest.trust().audit() != null
                    && Boolean.TRUE.equals(manifest.trust().audit().agentMustLog());
            boolean traceRequired = manifest.trust().audit() != null
                    && Boolean.TRUE.equals(manifest.trust().audit().requireTraceContext());
            log.kcp("Trust: publisher=" + publisher
                    + ", audit=" + (auditRequired ? "required" : "optional")
                    + ", trace_context=" + (traceRequired ? "required" : "optional"));
        }

        // Auth methods
        if (manifest.auth() != null && !manifest.auth().methods().isEmpty()) {
            StringBuilder authStr = new StringBuilder();
            for (AuthMethod m : manifest.auth().methods()) {
                if (!authStr.isEmpty()) authStr.append(" + ");
                authStr.append(m.type());
                if ("oauth2".equals(m.type())) {
                    authStr.append(" (client_credentials)");
                }
                if ("none".equals(m.type())) {
                    authStr.append(" (public fallback)");
                }
            }
            log.kcp("Auth: " + authStr);
        }

        // Delegation (root)
        log.kcp("Delegation: max_depth=" + rootDelegation.maxDepth()
                + ", capability_attenuation=" + (rootDelegation.requireCapabilityAttenuation() ? "required" : "optional")
                + ", audit_chain=" + (rootDelegation.auditChain() ? "required" : "optional"));

        // Units
        log.kcp("Units loaded:");
        int i = 1;
        for (KnowledgeUnit unit : manifest.units()) {
            String access = unit.access() != null ? unit.access() : "public";
            String sensitivity = unit.sensitivity() != null ? unit.sensitivity() : "public";
            DelegationConfig del = unitDelegation.getOrDefault(unit.id(), rootDelegation);
            String hitl = del.humanInTheLoopRequired() ? "required" : "no";
            log.kcp(String.format("  %d. %-22s access=%-14s sensitivity=%-12s HITL=%s",
                    i++, unit.id(), access, sensitivity, hitl));
        }
    }

    public KnowledgeManifest manifest() { return manifest; }
    public DelegationConfig rootDelegation() { return rootDelegation; }
    public Map<String, DelegationConfig> unitDelegation() { return unitDelegation; }

    /** Get simulated content for a unit. */
    public static String getContent(String unitId) {
        return SIMULATED_CONTENT.getOrDefault(unitId, "(no content available)");
    }

    /** Get a short preview of the simulated content. */
    public static String getContentPreview(String unitId) {
        String content = getContent(unitId);
        if (content.length() > 60) {
            return content.substring(0, 57) + "...";
        }
        return content;
    }
}
