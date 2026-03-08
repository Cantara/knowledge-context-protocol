package no.cantara.kcp.simulator.legal;

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
 * The Legal Research Agent: owns 4 knowledge units covering legal document review.
 * Uses both KcpParser (for typed model) and raw SnakeYAML (for delegation fields).
 */
public final class LegalResearchAgent {

    private static final Yaml RAW_YAML = new Yaml(new SafeConstructor(new LoaderOptions()));

    /** Simulated content for each knowledge unit. */
    private static final Map<String, String> SIMULATED_CONTENT = Map.of(
            "public-precedents",
            "Rt. 2023-1042: Data breach liability established under GDPR Art. 82. " +
                    "Controller held jointly liable with processor. Damages: 2.1M NOK.",
            "case-briefs",
            "Case 2025-CV-0042 (NovaCorp v. Meridian): Breach of SaaS contract. " +
                    "Key argument: force majeure clause inapplicable to software defects. " +
                    "Strategy: focus on SLA obligations and uptime guarantees.",
            "client-communications",
            "[PRIVILEGED] Email from GC to partner, 2025-11-03: 'The settlement offer " +
                    "of 4.2M NOK is within our authorised range. Recommend counter at 3.5M. " +
                    "Client has approved litigation budget of 800K if negotiation fails.'",
            "sealed-records",
            "[SEALED BY COURT ORDER 2025-08-15] Witness deposition transcript, " +
                    "Case 2024-CR-0189. Contains protected identity of whistleblower. " +
                    "Unsealing requires application to presiding judge."
    );

    private KnowledgeManifest manifest;
    private DelegationConfig rootDelegation;
    private Map<String, DelegationConfig> unitDelegation;

    @SuppressWarnings("unchecked")
    public void loadManifest(Path path) throws IOException {
        manifest = KcpParser.parse(path);

        Map<String, Object> rawData;
        try (InputStream is = Files.newInputStream(path)) {
            rawData = RAW_YAML.load(is);
        }

        rootDelegation = DelegationConfig.fromRootYaml(rawData);

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
        log.kcp("LegalResearchAgent reads knowledge.yaml (KCP v" + manifest.kcpVersion() + ")");
        log.kcp("Project: " + manifest.project() + " v" + manifest.version());

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

        if (manifest.auth() != null && !manifest.auth().methods().isEmpty()) {
            StringBuilder authStr = new StringBuilder();
            for (AuthMethod m : manifest.auth().methods()) {
                if (!authStr.isEmpty()) authStr.append(" + ");
                authStr.append(m.type());
                if ("oauth2".equals(m.type())) authStr.append(" (client_credentials)");
                if ("none".equals(m.type())) authStr.append(" (public fallback)");
            }
            log.kcp("Auth: " + authStr);
        }

        log.kcp("Delegation: max_depth=" + rootDelegation.maxDepth()
                + ", capability_attenuation=" + (rootDelegation.requireCapabilityAttenuation() ? "required" : "optional")
                + ", audit_chain=" + (rootDelegation.auditChain() ? "required" : "optional"));

        log.kcp("Units loaded:");
        int i = 1;
        for (KnowledgeUnit unit : manifest.units()) {
            String access = unit.access() != null ? unit.access() : "public";
            String sensitivity = unit.sensitivity() != null ? unit.sensitivity() : "public";
            DelegationConfig del = unitDelegation.getOrDefault(unit.id(), rootDelegation);
            String hitl = del.humanInTheLoopRequired() ? "required" : "no";
            log.kcp(String.format("  %d. %-25s access=%-14s sensitivity=%-14s HITL=%-8s max_depth=%d",
                    i++, unit.id(), access, sensitivity, hitl, del.maxDepth()));
        }
    }

    public KnowledgeManifest manifest() { return manifest; }
    public DelegationConfig rootDelegation() { return rootDelegation; }
    public Map<String, DelegationConfig> unitDelegation() { return unitDelegation; }

    public static String getContent(String unitId) {
        return SIMULATED_CONTENT.getOrDefault(unitId, "(no content available)");
    }

    public static String getContentPreview(String unitId) {
        String content = getContent(unitId);
        if (content.length() > 60) {
            return content.substring(0, 57) + "...";
        }
        return content;
    }
}
