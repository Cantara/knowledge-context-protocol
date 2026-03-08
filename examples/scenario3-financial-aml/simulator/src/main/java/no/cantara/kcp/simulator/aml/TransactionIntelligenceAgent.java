package no.cantara.kcp.simulator.aml;

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
 * The Transaction Intelligence Agent: owns 5 knowledge units covering AML data.
 * Dual-parses the manifest for typed model + delegation/compliance fields.
 */
public final class TransactionIntelligenceAgent {

    private static final Yaml RAW_YAML = new Yaml(new SafeConstructor(new LoaderOptions()));

    private static final Map<String, String> SIMULATED_CONTENT = Map.of(
            "sanctions-lists",
            "OFAC SDN List (updated 2026-03-01): 12,847 entries. EU Consolidated List: 9,234 entries. " +
                    "Recent additions: 3 entities (financial sector, jurisdiction: RU). " +
                    "Cross-reference rate: 94.2% match with UN Security Council list.",
            "transaction-patterns",
            "Pattern analysis (Jan 2026): 2.4M transactions screened. " +
                    "Structuring alerts: 47 (threshold: 10K EUR). Layering suspects: 12. " +
                    "Geographic anomalies: 8 (RU->CY->MT->NL chain detected 3x). " +
                    "Risk score distribution: 89% low, 8% medium, 3% high.",
            "customer-profiles",
            "[PII/GDPR] Customer C-4401: Corporate entity, KYC tier 3 (enhanced). " +
                    "Beneficial owner: redacted (PEP connection flagged). Risk rating: HIGH. " +
                    "Account opened: 2024-06-15. Transaction volume: 4.2M EUR (12 months). " +
                    "Country: Netherlands. UBO structure: 3 layers.",
            "sar-drafts",
            "[RESTRICTED] SAR-2026-0042 DRAFT: Subject C-4401. Suspicious pattern: " +
                    "structured deposits (9,800 EUR x 14 in 30 days = 137,200 EUR). " +
                    "Layering via CY shell company. FATF typology: Trade-Based ML indicator. " +
                    "Recommendation: FILE with national FIU within 24h.",
            "raw-wire-transfers",
            "[RESTRICTED/NO-DELEGATION] Wire W-20260115-001: SWIFT MT103. " +
                    "Sender: IBAN NL42EXAM0123456789. Receiver: IBAN CY17002001280000001200527600. " +
                    "Amount: 98,500 EUR. Purpose: 'consulting services'. " +
                    "Intermediary: EXAMBKCYXXX. Value date: 2026-01-15."
    );

    private KnowledgeManifest manifest;
    private DelegationConfig rootDelegation;
    private Map<String, DelegationConfig> unitDelegation;
    private Map<String, ComplianceConfig> unitCompliance;

    @SuppressWarnings("unchecked")
    public void loadManifest(Path path) throws IOException {
        manifest = KcpParser.parse(path);

        Map<String, Object> rawData;
        try (InputStream is = Files.newInputStream(path)) {
            rawData = RAW_YAML.load(is);
        }

        rootDelegation = DelegationConfig.fromRootYaml(rawData);

        unitDelegation = new HashMap<>();
        unitCompliance = new HashMap<>();
        List<Map<String, Object>> rawUnits = (List<Map<String, Object>>) rawData.getOrDefault("units", List.of());
        for (Map<String, Object> rawUnit : rawUnits) {
            String id = (String) rawUnit.get("id");
            if (id != null) {
                unitDelegation.put(id, DelegationConfig.fromUnitYaml(rawUnit, rootDelegation));
                unitCompliance.put(id, ComplianceConfig.fromUnitYaml(rawUnit));
            }
        }
    }

    public void printDiscovery(ConsoleLog log) {
        log.kcp("TransactionIntelligenceAgent reads knowledge.yaml (KCP v" + manifest.kcpVersion() + ")");
        log.kcp("Project: " + manifest.project() + " v" + manifest.version());

        if (manifest.trust() != null) {
            String publisher = manifest.trust().provenance() != null
                    ? manifest.trust().provenance().publisher() : "unknown";
            boolean auditRequired = manifest.trust().audit() != null
                    && Boolean.TRUE.equals(manifest.trust().audit().agentMustLog());
            log.kcp("Trust: publisher=" + publisher
                    + ", audit=" + (auditRequired ? "required" : "optional"));
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
            ComplianceConfig comp = unitCompliance.getOrDefault(unit.id(), ComplianceConfig.EMPTY);
            String hitl = del.humanInTheLoopRequired() ? "required" : "no";
            String regs = comp.regulations().isEmpty() ? "none" : String.join(",", comp.regulations());
            log.kcp(String.format("  %d. %-22s access=%-14s sensitivity=%-14s HITL=%-8s depth=%d regs=%s",
                    i++, unit.id(), access, sensitivity, hitl, del.maxDepth(), regs));
        }
    }

    public KnowledgeManifest manifest() { return manifest; }
    public DelegationConfig rootDelegation() { return rootDelegation; }
    public Map<String, DelegationConfig> unitDelegation() { return unitDelegation; }
    public Map<String, ComplianceConfig> unitCompliance() { return unitCompliance; }

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
