package no.cantara.kcp.mcp;

import no.cantara.kcp.KcpParser;
import no.cantara.kcp.model.KnowledgeManifest;
import no.cantara.kcp.model.KnowledgeUnit;
import no.cantara.kcp.model.Relationship;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Generates path-specific .instructions.md files from a knowledge.yaml manifest.
 * Each file gets YAML frontmatter with {@code applyTo} patterns for Copilot path matching.
 *
 * <p>Java equivalent of the TypeScript split-instructions.ts.</p>
 */
public final class KcpSplitInstructions {

    private KcpSplitInstructions() {}

    /** Strategy for splitting units into separate instruction files. */
    public enum SplitBy {
        /** Group by the top-level directory component of each unit's path. */
        DIRECTORY,
        /** One file per scope value (global, project, module). */
        SCOPE,
        /** One file per unit. */
        UNIT,
        /** Single file with all units. */
        NONE
    }

    /**
     * Generate split instruction files from a knowledge.yaml manifest.
     *
     * <p>Groups units by the chosen strategy, writes one {@code {group}.instructions.md}
     * per group into {@code outputDir}, each with YAML frontmatter containing {@code applyTo}
     * patterns that Copilot uses for path-specific instruction injection.</p>
     *
     * @param manifestPath path to the knowledge.yaml file
     * @param outputDir    directory to write instruction files to (created if absent)
     * @param splitBy      splitting strategy
     * @param audience     optional audience filter (null = include all)
     * @throws IOException if the manifest cannot be read or files cannot be written
     */
    public static void generateSplitInstructions(
            Path manifestPath, Path outputDir, SplitBy splitBy, String audience
    ) throws IOException {
        KnowledgeManifest manifest = KcpParser.parse(manifestPath);
        writeSplitInstructions(manifest, outputDir, splitBy, audience);
    }

    /**
     * Package-private for testing without file I/O on the manifest side.
     */
    static void writeSplitInstructions(
            KnowledgeManifest manifest, Path outputDir, SplitBy splitBy, String audience
    ) throws IOException {
        Files.createDirectories(outputDir);

        List<KnowledgeUnit> units = KcpInstructions.prepareUnits(manifest, audience);

        if (splitBy == SplitBy.NONE) {
            String content = buildInstructionFile(manifest, units, "**");
            Files.writeString(outputDir.resolve("all.instructions.md"), content);
            return;
        }

        Map<String, List<KnowledgeUnit>> groups = groupUnits(units, splitBy);

        for (Map.Entry<String, List<KnowledgeUnit>> entry : groups.entrySet()) {
            String groupName = entry.getKey();
            List<KnowledgeUnit> groupUnits = entry.getValue();
            String applyTo = buildApplyTo(groupUnits, splitBy);
            String safeName = groupName.replaceAll("[^a-zA-Z0-9_-]", "-")
                                       .replaceAll("^-|-$", "");
            if (safeName.isEmpty()) safeName = "root";
            String fileName = safeName + ".instructions.md";
            String content = buildInstructionFile(manifest, groupUnits, applyTo);
            Files.writeString(outputDir.resolve(fileName), content);
        }
    }

    /**
     * Group units by the chosen strategy.
     */
    static Map<String, List<KnowledgeUnit>> groupUnits(
            List<KnowledgeUnit> units, SplitBy splitBy
    ) {
        // Use LinkedHashMap to preserve insertion order
        Map<String, List<KnowledgeUnit>> groups = new LinkedHashMap<>();

        for (KnowledgeUnit unit : units) {
            String key;
            switch (splitBy) {
                case DIRECTORY -> {
                    String path = unit.path();
                    int slashIdx = path.indexOf('/');
                    if (slashIdx < 0) {
                        key = "root";
                    } else {
                        key = path.substring(0, slashIdx);
                    }
                }
                case SCOPE -> key = unit.scope() != null ? unit.scope() : "global";
                case UNIT -> key = unit.id();
                default -> key = "all";
            }
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(unit);
        }

        return groups;
    }

    /**
     * Build the {@code applyTo} glob patterns for a group of units.
     * Combines directory-based and trigger-based patterns (max 5 total).
     */
    static String buildApplyTo(List<KnowledgeUnit> units, SplitBy splitBy) {
        Set<String> patterns = new LinkedHashSet<>();

        if (splitBy == SplitBy.SCOPE) {
            // For scope-based splitting, use broad patterns
            Set<String> scopes = units.stream()
                .map(u -> u.scope() != null ? u.scope() : "global")
                .collect(Collectors.toSet());
            if (scopes.contains("global")) {
                patterns.add("**");
            } else if (scopes.contains("project")) {
                patterns.add("src/**");
            } else {
                // module scope: use directory-based patterns
                for (KnowledgeUnit unit : units) {
                    String dir = parentDir(unit.path());
                    patterns.add(dir.equals(".") ? "**" : dir + "/**");
                }
            }
        } else {
            // directory or unit: use path-based patterns
            for (KnowledgeUnit unit : units) {
                String dir = parentDir(unit.path());
                if (!dir.equals(".")) {
                    patterns.add(dir + "/**");
                }
            }

            // Add trigger-based globs (up to 5 total patterns)
            outer:
            for (KnowledgeUnit unit : units) {
                if (unit.triggers() != null) {
                    for (String trigger : unit.triggers()) {
                        if (patterns.size() >= 5) break outer;
                        patterns.add("**/*" + trigger + "*");
                    }
                }
            }
        }

        // Ensure at least one pattern
        if (patterns.isEmpty()) {
            patterns.add("**");
        }

        return String.join(",", patterns);
    }

    /**
     * Build a single .instructions.md file content with YAML frontmatter.
     */
    static String buildInstructionFile(
            KnowledgeManifest manifest, List<KnowledgeUnit> units, String applyTo
    ) {
        List<String> lines = new ArrayList<>();

        // YAML frontmatter
        lines.add("---");
        lines.add("applyTo: \"" + applyTo + "\"");
        lines.add("---");
        lines.add("");

        // Header comment
        StringBuilder header = new StringBuilder();
        header.append("<!-- Generated by kcp-mcp from knowledge.yaml | project: ")
              .append(manifest.project());
        if (manifest.updated() != null) {
            header.append(" | updated: ").append(manifest.updated());
        }
        header.append(" -->");
        lines.add(header.toString());
        lines.add("");

        // Title
        lines.add("# " + manifest.project() + " \u2014 Knowledge Units");
        lines.add("");

        // Compact table
        lines.add("| ID | Intent | Path | Triggers |");
        lines.add("|----|--------|------|----------|");

        for (KnowledgeUnit unit : units) {
            String triggers = unit.triggers() != null && !unit.triggers().isEmpty()
                ? String.join(", ", unit.triggers()) : "";
            lines.add("| " + unit.id() + " | " + unit.intent() + " | " + unit.path() + " | " + triggers + " |");
        }

        lines.add("");

        // Relationships (only those involving units in this group)
        Set<String> unitIds = units.stream().map(KnowledgeUnit::id).collect(Collectors.toSet());
        List<Relationship> relevantRels = manifest.relationships().stream()
            .filter(r -> unitIds.contains(r.fromId()) || unitIds.contains(r.toId()))
            .toList();

        if (!relevantRels.isEmpty()) {
            lines.add("## Relationships");
            lines.add("");
            for (Relationship rel : relevantRels) {
                lines.add("- " + rel.fromId() + " \u2192 " + rel.toId() + " (" + rel.type() + ")");
            }
            lines.add("");
        }

        return String.join("\n", lines);
    }

    /**
     * Get the parent directory of a path string, similar to Node's dirname().
     * Returns "." if the path has no directory component.
     */
    private static String parentDir(String path) {
        if (path == null) return ".";
        int slashIdx = path.lastIndexOf('/');
        if (slashIdx < 0) return ".";
        return path.substring(0, slashIdx);
    }
}
