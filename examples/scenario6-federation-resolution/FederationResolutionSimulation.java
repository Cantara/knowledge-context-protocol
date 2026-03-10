///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS org.yaml:snakeyaml:2.6

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Scenario 6: Federation Resolution Simulation
 * <p>
 * Demonstrates all KCP v0.9 federation features (SPEC.md section 3.6) in a single
 * runnable scenario:
 * <ol>
 *   <li>DAG resolution order (foundation first, then child, skip archive)</li>
 *   <li>local_mirror fallback when remote URL is unreachable</li>
 *   <li>on_failure: degrade behaviour when a sub-manifest is unavailable</li>
 *   <li>external_depends_on cross-manifest unit resolution</li>
 *   <li>governs external_relationship from compliance to payments</li>
 *   <li>list_manifests tool output format</li>
 *   <li>archive manifest detection and skip</li>
 * </ol>
 * <p>
 * Usage (requires JBang or compile manually):
 * <pre>
 *   jbang FederationResolutionSimulation.java
 *   # or
 *   javac -cp snakeyaml-2.6.jar FederationResolutionSimulation.java
 *   java -cp .:snakeyaml-2.6.jar FederationResolutionSimulation
 * </pre>
 */
public class FederationResolutionSimulation {

    private static final Yaml YAML = new Yaml(new SafeConstructor(new LoaderOptions()));

    // --- Simulation state ------------------------------------------------------

    /** Tracks which manifests have been loaded (by id). */
    private final Map<String, ManifestEntry> loadedManifests = new LinkedHashMap<>();

    /** Tracks manifests that failed or were skipped. */
    private final List<String> skippedManifests = new ArrayList<>();
    private final List<String> degradedManifests = new ArrayList<>();

    /** Total unit count across all loaded manifests. */
    private int totalUnits = 0;

    // --- Records for parsed data -----------------------------------------------

    record ManifestRef(String id, String url, String label, String relationship,
                       String updateFrequency, String localMirror) {}

    record ExternalDep(String manifest, String unit, String onFailure) {}

    record ExternalRel(String fromManifest, String fromUnit,
                       String toManifest, String toUnit, String type) {}

    record ManifestEntry(String id, String project, String version,
                         List<Map<String, Object>> units,
                         String relationship, boolean degraded) {}

    // --- Main ------------------------------------------------------------------

    public static void main(String[] args) throws IOException {
        Path base = resolveBaseDir(args);
        new FederationResolutionSimulation().run(base);
    }

    public void run(Path baseDir) throws IOException {
        Path rootManifestPath = baseDir.resolve("knowledge.yaml");
        System.out.println("=== SCENARIO 6: Federation Resolution Simulation ===");
        System.out.println("Root manifest: " + rootManifestPath);
        System.out.println();

        // Step 1: Load and parse the root manifest
        Map<String, Object> root = loadYaml(rootManifestPath);
        String rootProject = (String) root.get("project");
        String rootVersion = str(root.get("version"));

        System.out.println("--- Step 1: Parse Root Manifest ---");
        System.out.printf("  Project: %s  Version: %s  KCP: %s%n",
                rootProject, rootVersion, root.get("kcp_version"));
        System.out.println();

        // Step 2: Discover manifests block
        List<ManifestRef> manifestRefs = parseManifestRefs(root);
        System.out.println("--- Step 2: Discover Sub-Manifests (" + manifestRefs.size() + ") ---");
        for (ManifestRef ref : manifestRefs) {
            System.out.printf("  %-20s relationship=%-12s url=%s%s%n",
                    ref.id(), ref.relationship(),
                    ref.url(),
                    ref.localMirror() != null ? "  local_mirror=" + ref.localMirror() : "");
        }
        System.out.println();

        // Step 3: Resolve in dependency order (foundation first, then child, skip archive)
        System.out.println("--- Step 3: DAG Resolution Order ---");
        List<ManifestRef> resolutionOrder = computeResolutionOrder(manifestRefs);
        for (int i = 0; i < resolutionOrder.size(); i++) {
            ManifestRef ref = resolutionOrder.get(i);
            String action = "archive".equals(ref.relationship()) ? "SKIP (archive)" : "LOAD";
            System.out.printf("  %d. %-20s [%s] %s%n",
                    i + 1, ref.id(), ref.relationship(), action);
        }
        System.out.println();

        // Register root manifest units
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rootUnits = (List<Map<String, Object>>)
                root.getOrDefault("units", List.of());
        loadedManifests.put("root", new ManifestEntry(
                "root", rootProject, rootVersion, rootUnits, "root", false));
        totalUnits += rootUnits.size();

        // Step 4: Resolve each sub-manifest
        System.out.println("--- Step 4: Resolve Sub-Manifests ---");
        for (ManifestRef ref : resolutionOrder) {
            resolveManifest(ref, baseDir);
        }
        System.out.println();

        // Step 5: Resolve external_depends_on
        System.out.println("--- Step 5: Resolve external_depends_on ---");
        resolveExternalDependencies(root);
        System.out.println();

        // Step 6: Show external_relationships (governs)
        System.out.println("--- Step 6: External Relationships ---");
        resolveExternalRelationships(root);
        System.out.println();

        // Step 7: list_manifests tool output
        System.out.println("--- Step 7: list_manifests Tool Output ---");
        printListManifestsTool(root, manifestRefs);
        System.out.println();

        // Summary
        System.out.println("=== SUMMARY ===");
        int loadedCount = (int) loadedManifests.values().stream()
                .filter(e -> !e.degraded()).count();
        int degradedCount = degradedManifests.size();
        int skippedCount = skippedManifests.size();
        System.out.printf("  Loaded %d units from %d manifest(s), " +
                        "skipped %d archive manifest(s), degraded on %d manifest(s)%n",
                totalUnits, loadedCount, skippedCount, degradedCount);
        System.out.println();
        System.out.println("  Loaded manifests:");
        for (ManifestEntry entry : loadedManifests.values()) {
            if (!entry.degraded()) {
                System.out.printf("    %-20s %d unit(s)  [%s]%n",
                        entry.project(), entry.units().size(), entry.relationship());
            }
        }
        if (degradedCount > 0) {
            System.out.println("  Degraded manifests:");
            for (String id : degradedManifests) {
                System.out.printf("    %s (on_failure: degrade)%n", id);
            }
        }
        if (skippedCount > 0) {
            System.out.println("  Skipped manifests:");
            for (String id : skippedManifests) {
                System.out.printf("    %s (archive)%n", id);
            }
        }
    }

    // --- Resolution logic ------------------------------------------------------

    /**
     * Computes resolution order: foundation manifests first, then governs,
     * then child/peer, then archive last (to be skipped).
     */
    private List<ManifestRef> computeResolutionOrder(List<ManifestRef> refs) {
        List<ManifestRef> foundations = new ArrayList<>();
        List<ManifestRef> governs = new ArrayList<>();
        List<ManifestRef> children = new ArrayList<>();
        List<ManifestRef> archives = new ArrayList<>();

        for (ManifestRef ref : refs) {
            switch (ref.relationship() != null ? ref.relationship() : "peer") {
                case "foundation" -> foundations.add(ref);
                case "governs" -> governs.add(ref);
                case "archive" -> archives.add(ref);
                default -> children.add(ref);  // child, peer, unknown
            }
        }

        List<ManifestRef> ordered = new ArrayList<>();
        ordered.addAll(foundations);
        ordered.addAll(governs);
        ordered.addAll(children);
        ordered.addAll(archives);
        return ordered;
    }

    /**
     * Resolves a single sub-manifest: handles archive skip, local_mirror fallback,
     * and simulated remote unavailability.
     */
    @SuppressWarnings("unchecked")
    private void resolveManifest(ManifestRef ref, Path baseDir) {
        // Archive: skip
        if ("archive".equals(ref.relationship())) {
            System.out.printf("  [SKIP] %s — relationship=archive, agent skips loading%n", ref.id());
            skippedManifests.add(ref.id());
            return;
        }

        // Simulate: fraud-detection remote URL is unavailable
        if ("fraud-detection".equals(ref.id())) {
            System.out.printf("  [FETCH] %s — attempting %s%n", ref.id(), ref.url());
            System.out.printf("  [FAIL]  %s — remote URL unreachable (simulated network error)%n", ref.id());
            if (ref.localMirror() != null) {
                System.out.printf("  [MIRROR] %s — no local_mirror configured%n", ref.id());
            } else {
                System.out.printf("  [DEGRADE] %s — no local_mirror, applying on_failure: degrade%n", ref.id());
            }
            degradedManifests.add(ref.id());
            loadedManifests.put(ref.id(), new ManifestEntry(
                    ref.id(), ref.id(), "unknown", List.of(), ref.relationship(), true));
            return;
        }

        // Simulate: compliance-core remote URL is unreachable, fall back to local_mirror
        if (ref.localMirror() != null) {
            Path mirrorPath = baseDir.resolve(ref.localMirror());
            System.out.printf("  [MIRROR] %s — local_mirror exists at %s, loading from mirror%n",
                    ref.id(), ref.localMirror());
            System.out.printf("           (Per SPEC section 3.6: when local_mirror exists, URL is NOT fetched)%n");
            try {
                Map<String, Object> subData = loadYaml(mirrorPath);
                List<Map<String, Object>> units = (List<Map<String, Object>>)
                        subData.getOrDefault("units", List.of());
                String subProject = (String) subData.get("project");
                String subVersion = str(subData.get("version"));
                loadedManifests.put(ref.id(), new ManifestEntry(
                        ref.id(), subProject, subVersion, units, ref.relationship(), false));
                totalUnits += units.size();
                System.out.printf("  [OK]    %s — loaded %d units (project=%s, version=%s)%n",
                        ref.id(), units.size(), subProject, subVersion);
            } catch (IOException e) {
                System.out.printf("  [ERROR] %s — failed to load local_mirror: %s%n",
                        ref.id(), e.getMessage());
            }
        } else {
            // Normal fetch (simulated as loading from sub-manifests directory)
            Path subPath = baseDir.resolve("sub-manifests/" + ref.id() + ".yaml");
            System.out.printf("  [FETCH] %s — simulating fetch from %s%n", ref.id(), ref.url());
            try {
                Map<String, Object> subData = loadYaml(subPath);
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> units = (List<Map<String, Object>>)
                        subData.getOrDefault("units", List.of());
                String subProject = (String) subData.get("project");
                String subVersion = str(subData.get("version"));
                loadedManifests.put(ref.id(), new ManifestEntry(
                        ref.id(), subProject, subVersion, units, ref.relationship(), false));
                totalUnits += units.size();
                System.out.printf("  [OK]    %s — loaded %d units (project=%s, version=%s)%n",
                        ref.id(), units.size(), subProject, subVersion);
            } catch (IOException e) {
                System.out.printf("  [ERROR] %s — failed to load: %s%n",
                        ref.id(), e.getMessage());
            }
        }
    }

    // --- external_depends_on resolution ----------------------------------------

    @SuppressWarnings("unchecked")
    private void resolveExternalDependencies(Map<String, Object> root) {
        List<Map<String, Object>> units = (List<Map<String, Object>>)
                root.getOrDefault("units", List.of());
        for (Map<String, Object> unit : units) {
            String unitId = (String) unit.get("id");
            List<Map<String, Object>> extDeps = (List<Map<String, Object>>)
                    unit.getOrDefault("external_depends_on", List.of());
            for (Map<String, Object> dep : extDeps) {
                ExternalDep ed = new ExternalDep(
                        (String) dep.get("manifest"),
                        (String) dep.get("unit"),
                        (String) dep.getOrDefault("on_failure", "skip"));
                resolveOneExternalDep(unitId, ed);
            }
        }
    }

    private void resolveOneExternalDep(String unitId, ExternalDep dep) {
        ManifestEntry entry = loadedManifests.get(dep.manifest());

        if (entry == null) {
            // Manifest was not loaded (e.g. skipped archive)
            System.out.printf("  %s -> %s/%s : manifest not loaded (on_failure=%s)%n",
                    unitId, dep.manifest(), dep.unit(), dep.onFailure());
            return;
        }

        if (entry.degraded()) {
            // Manifest was degraded (unavailable)
            switch (dep.onFailure()) {
                case "degrade" -> System.out.printf(
                        "  %s -> %s/%s : DEGRADED — manifest unavailable, " +
                                "agent operates with incomplete dependencies%n",
                        unitId, dep.manifest(), dep.unit());
                case "warn" -> System.out.printf(
                        "  %s -> %s/%s : WARNING — manifest unavailable%n",
                        unitId, dep.manifest(), dep.unit());
                default -> System.out.printf(
                        "  %s -> %s/%s : skipped (manifest unavailable, on_failure=skip)%n",
                        unitId, dep.manifest(), dep.unit());
            }
            return;
        }

        // Manifest is loaded — check if the unit exists
        boolean unitFound = entry.units().stream()
                .anyMatch(u -> dep.unit().equals(u.get("id")));
        if (unitFound) {
            System.out.printf("  %s -> %s/%s : RESOLVED (unit found in loaded manifest)%n",
                    unitId, dep.manifest(), dep.unit());
        } else {
            System.out.printf("  %s -> %s/%s : NOT FOUND (on_failure=%s)%n",
                    unitId, dep.manifest(), dep.unit(), dep.onFailure());
        }
    }

    // --- external_relationships resolution -------------------------------------

    @SuppressWarnings("unchecked")
    private void resolveExternalRelationships(Map<String, Object> root) {
        List<Map<String, Object>> extRels = (List<Map<String, Object>>)
                root.getOrDefault("external_relationships", List.of());
        for (Map<String, Object> rel : extRels) {
            ExternalRel er = new ExternalRel(
                    (String) rel.get("from_manifest"),
                    (String) rel.get("from_unit"),
                    (String) rel.get("to_manifest"),
                    (String) rel.get("to_unit"),
                    (String) rel.get("type"));

            String from = formatRef(er.fromManifest(), er.fromUnit());
            String to = formatRef(er.toManifest(), er.toUnit());
            String status;

            // Check if both ends are resolvable
            boolean fromResolvable = isUnitResolvable(er.fromManifest(), er.fromUnit());
            boolean toResolvable = isUnitResolvable(er.toManifest(), er.toUnit());

            if (fromResolvable && toResolvable) {
                status = "ACTIVE";
            } else if (!fromResolvable && !toResolvable) {
                status = "UNRESOLVABLE (both endpoints unavailable)";
            } else {
                status = "PARTIAL (" + (!fromResolvable ? "from" : "to") + " unavailable)";
            }

            System.out.printf("  %s -[%s]-> %s : %s%n", from, er.type(), to, status);
        }
    }

    private boolean isUnitResolvable(String manifestId, String unitId) {
        if (manifestId == null) {
            // Refers to root manifest
            ManifestEntry root = loadedManifests.get("root");
            return root != null && root.units().stream()
                    .anyMatch(u -> unitId.equals(u.get("id")));
        }
        ManifestEntry entry = loadedManifests.get(manifestId);
        if (entry == null || entry.degraded()) return false;
        return entry.units().stream().anyMatch(u -> unitId.equals(u.get("id")));
    }

    private String formatRef(String manifestId, String unitId) {
        if (manifestId == null) return "root/" + unitId;
        return manifestId + "/" + unitId;
    }

    // --- list_manifests tool output --------------------------------------------

    private void printListManifestsTool(Map<String, Object> root, List<ManifestRef> refs) {
        System.out.println("  {");
        System.out.println("    \"tool\": \"list_manifests\",");
        System.out.println("    \"result\": {");
        System.out.printf("      \"root_project\": \"%s\",%n", root.get("project"));
        System.out.printf("      \"root_version\": \"%s\",%n", str(root.get("version")));
        System.out.println("      \"manifests\": [");
        for (int i = 0; i < refs.size(); i++) {
            ManifestRef ref = refs.get(i);
            ManifestEntry entry = loadedManifests.get(ref.id());
            String status;
            if (skippedManifests.contains(ref.id())) {
                status = "skipped";
            } else if (entry != null && entry.degraded()) {
                status = "degraded";
            } else if (entry != null) {
                status = "loaded";
            } else {
                status = "unknown";
            }

            System.out.println("        {");
            System.out.printf("          \"id\": \"%s\",%n", ref.id());
            System.out.printf("          \"label\": \"%s\",%n", ref.label());
            System.out.printf("          \"relationship\": \"%s\",%n", ref.relationship());
            System.out.printf("          \"url\": \"%s\",%n", ref.url());
            if (ref.localMirror() != null) {
                System.out.printf("          \"local_mirror\": \"%s\",%n", ref.localMirror());
            }
            System.out.printf("          \"status\": \"%s\"%n", status);
            System.out.println("        }" + (i < refs.size() - 1 ? "," : ""));
        }
        System.out.println("      ]");
        System.out.println("    }");
        System.out.println("  }");
    }

    // --- YAML loading ----------------------------------------------------------

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadYaml(Path path) throws IOException {
        try (InputStream is = Files.newInputStream(path)) {
            return YAML.load(is);
        }
    }

    @SuppressWarnings("unchecked")
    private List<ManifestRef> parseManifestRefs(Map<String, Object> data) {
        List<Map<String, Object>> raw = (List<Map<String, Object>>)
                data.getOrDefault("manifests", List.of());
        List<ManifestRef> refs = new ArrayList<>();
        for (Map<String, Object> m : raw) {
            refs.add(new ManifestRef(
                    (String) m.get("id"),
                    (String) m.get("url"),
                    (String) m.get("label"),
                    (String) m.get("relationship"),
                    (String) m.get("update_frequency"),
                    (String) m.get("local_mirror")));
        }
        return refs;
    }

    // --- Helpers ---------------------------------------------------------------

    private static String str(Object o) {
        return o != null ? o.toString() : "unknown";
    }

    private static Path resolveBaseDir(String[] args) {
        for (int i = 0; i < args.length; i++) {
            if ("--dir".equals(args[i]) && i + 1 < args.length) {
                return Path.of(args[i + 1]);
            }
            if ("--help".equals(args[i]) || "-h".equals(args[i])) {
                System.out.println("Scenario 6: Federation Resolution Simulation");
                System.out.println("  --dir PATH   Directory containing knowledge.yaml (default: .)");
                System.exit(0);
            }
        }
        // Default: look for knowledge.yaml in current directory or parent
        if (Files.exists(Path.of("knowledge.yaml"))) {
            return Path.of(".");
        }
        if (Files.exists(Path.of("../knowledge.yaml"))) {
            return Path.of("..");
        }
        return Path.of(".");
    }
}
