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
 * The Research Agent: reads and interprets the KCP manifest.
 * Uses both KcpParser (for typed model) and raw SnakeYAML (for delegation fields).
 */
public final class ResearchAgent {

    private static final Yaml RAW_YAML = new Yaml(new SafeConstructor(new LoaderOptions()));

    /** Hardcoded content for simulated units (the actual files don't exist). */
    private static final Map<String, String> SIMULATED_CONTENT = Map.of(
            "public-guidelines",
            "ICH Good Clinical Practice (ICH E6 R2) guidelines, including ethical standards, " +
                    "informed consent requirements, and regulatory submission criteria.",
            "trial-protocols",
            "BEACON-3 Phase III Study \u2014 Inclusion criteria: age 18\u201375, confirmed diagnosis. " +
                    "Exclusion: prior systemic therapy. Current enrollment: 342 participants.",
            "patient-cohort",
            "BEACON-3 cohort demographics: 342 enrolled, 58% female, median age 47. " +
                    "Primary endpoint: progression-free survival at 24 months. " +
                    "Adverse events: grade 3+ in 12% of participants."
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
        log.kcp("Research Agent reads knowledge.yaml (KCP v" + manifest.kcpVersion() + ")");
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
        if (content.length() > 40) {
            return content.substring(0, 37) + "...";
        }
        return content;
    }
}
