package no.cantara.kcp.mcp;

import no.cantara.kcp.KcpParser;
import no.cantara.kcp.model.KnowledgeManifest;
import no.cantara.kcp.model.KnowledgeUnit;
import no.cantara.kcp.model.Relationship;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Generates copilot-instructions.md content from a knowledge.yaml manifest.
 * Java equivalent of the TypeScript instructions.ts.
 *
 * <p>Supports three output formats:
 * <ul>
 *   <li>{@link Format#FULL} — verbose heading blocks per unit (original)</li>
 *   <li>{@link Format#COMPACT} — markdown table with one row per unit</li>
 *   <li>{@link Format#AGENT} — compact table with navigation instructions for AI agents</li>
 * </ul>
 */
public final class KcpInstructions {

    private KcpInstructions() {}

    /** Output format for generated instructions. */
    public enum Format {
        FULL, COMPACT, AGENT
    }

    static final Map<String, Double> SCOPE_PRIORITY = Map.of(
        "global",  1.0,
        "project", 0.7,
        "module",  0.5
    );

    /**
     * Generate copilot-instructions.md content from a knowledge.yaml file.
     * Uses {@link Format#FULL} format for backward compatibility.
     *
     * @param manifestPath path to the knowledge.yaml file
     * @param audience     optional audience filter (null = include all)
     * @return formatted markdown content
     * @throws IOException if the manifest cannot be read
     */
    public static String generateInstructions(Path manifestPath, String audience) throws IOException {
        return generateInstructions(manifestPath, audience, Format.FULL);
    }

    /**
     * Generate copilot-instructions.md content from a knowledge.yaml file.
     *
     * @param manifestPath path to the knowledge.yaml file
     * @param audience     optional audience filter (null = include all)
     * @param format       output format
     * @return formatted markdown content
     * @throws IOException if the manifest cannot be read
     */
    public static String generateInstructions(Path manifestPath, String audience, Format format) throws IOException {
        KnowledgeManifest manifest = KcpParser.parse(manifestPath);
        return formatInstructions(manifest, audience, format);
    }

    /**
     * Format a parsed manifest into copilot-instructions.md content.
     * Package-private for direct testing without file I/O.
     * Uses {@link Format#FULL} for backward compatibility.
     */
    static String formatInstructions(KnowledgeManifest manifest, String audience) {
        return formatInstructions(manifest, audience, Format.FULL);
    }

    /**
     * Format a parsed manifest into copilot-instructions.md content.
     * Package-private for direct testing without file I/O.
     *
     * <p>Units are sorted by scope priority (global first) then alphabetically by id.
     * If an audience filter is provided, only units matching that audience are included.</p>
     *
     * @param manifest the parsed knowledge manifest
     * @param audience optional audience filter (null = include all)
     * @param format   output format
     * @return formatted markdown content
     */
    static String formatInstructions(KnowledgeManifest manifest, String audience, Format format) {
        List<KnowledgeUnit> units = prepareUnits(manifest, audience);

        return switch (format) {
            case COMPACT -> formatCompact(manifest, units);
            case AGENT   -> formatAgentInstructions(manifest, units);
            case FULL    -> formatFull(manifest, units);
        };
    }

    /**
     * Generate a .agent.md file from a knowledge.yaml manifest.
     *
     * <p>Produces GitHub Copilot agent format with YAML frontmatter, knowledge unit
     * table, relationships, and navigation instructions.</p>
     *
     * <p>If maxChars is set and positive, truncates by dropping module-scope units first,
     * then project-scope units, keeping global units and the instructions block.</p>
     *
     * @param manifestPath path to the knowledge.yaml file
     * @param audience     optional audience filter (null = include all)
     * @param maxChars     max chars for agent file (0 or negative = no limit)
     * @return formatted agent markdown content
     * @throws IOException if the manifest cannot be read
     */
    public static String generateAgentFile(Path manifestPath, String audience, int maxChars) throws IOException {
        KnowledgeManifest manifest = KcpParser.parse(manifestPath);
        return formatAgentFile(manifest, audience, maxChars);
    }

    /**
     * Format a parsed manifest into .agent.md content.
     * Package-private for direct testing without file I/O.
     *
     * @param manifest the parsed knowledge manifest
     * @param audience optional audience filter (null = include all)
     * @param maxChars max chars for agent file (0 or negative = no limit)
     * @return formatted agent markdown content
     */
    static String formatAgentFile(KnowledgeManifest manifest, String audience, int maxChars) {
        List<KnowledgeUnit> units = prepareUnits(manifest, audience);

        if (maxChars > 0) {
            // Try with all units; if too long, drop module-scope first, then project-scope
            String[] scopeOrder = {"module", "project", "global"};
            String content = buildAgentContent(manifest, units);
            for (String dropScope : scopeOrder) {
                if (content.length() <= maxChars) break;
                String drop = dropScope;
                units = units.stream()
                    .filter(u -> !drop.equals(u.scope()))
                    .collect(Collectors.toList());
                content = buildAgentContent(manifest, units);
            }
            return content.length() > maxChars ? content.substring(0, maxChars) : content;
        }

        return buildAgentContent(manifest, units);
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    /**
     * Filter and sort units for instruction generation.
     */
    static List<KnowledgeUnit> prepareUnits(KnowledgeManifest manifest, String audience) {
        List<KnowledgeUnit> units = new ArrayList<>(manifest.units());
        if (audience != null && !audience.isEmpty()) {
            units = units.stream()
                .filter(u -> u.audience() != null && u.audience().contains(audience))
                .collect(Collectors.toList());
        }

        // Sort by scope priority descending, then alphabetical by id
        units.sort((a, b) -> {
            double priA = SCOPE_PRIORITY.getOrDefault(a.scope() != null ? a.scope() : "global", 0.5);
            double priB = SCOPE_PRIORITY.getOrDefault(b.scope() != null ? b.scope() : "global", 0.5);
            int priDiff = Double.compare(priB, priA);
            if (priDiff != 0) return priDiff;
            return a.id().compareTo(b.id());
        });
        return units;
    }

    /**
     * Build the header comment block used across formats.
     */
    private static String headerComment(KnowledgeManifest manifest) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!-- Generated by kcp-mcp from knowledge.yaml | project: ").append(manifest.project());
        if (manifest.updated() != null) {
            sb.append(" | updated: ").append(manifest.updated());
        }
        sb.append(" -->");
        return sb.toString();
    }

    /**
     * Format relationships as markdown list lines.
     */
    private static List<String> formatRelationships(KnowledgeManifest manifest) {
        if (manifest.relationships() == null || manifest.relationships().isEmpty()) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        lines.add("## Relationships");
        lines.add("");
        for (Relationship rel : manifest.relationships()) {
            lines.add("- " + rel.fromId() + " \u2192 " + rel.toId() + " (" + rel.type() + ")");
        }
        lines.add("");
        return lines;
    }

    /**
     * Full verbose format (original) -- ### heading blocks per unit.
     */
    private static String formatFull(KnowledgeManifest manifest, List<KnowledgeUnit> units) {
        List<String> lines = new ArrayList<>();

        // Header comment
        lines.add("<!-- Generated by kcp-mcp --generate-instructions from knowledge.yaml -->");

        StringBuilder meta = new StringBuilder();
        meta.append("<!-- Project: ").append(manifest.project());
        meta.append(" | Version: ").append(manifest.version());
        if (manifest.kcpVersion() != null && !manifest.kcpVersion().isEmpty()) {
            meta.append(" | KCP: ").append(manifest.kcpVersion());
        }
        if (manifest.updated() != null) {
            meta.append(" | Updated: ").append(manifest.updated());
        }
        meta.append(" -->");
        lines.add(meta.toString());
        lines.add("");

        // Title
        lines.add("# AI Assistant Instructions: " + manifest.project());
        lines.add("");
        lines.add("This repository uses the Knowledge Context Protocol (KCP) to structure knowledge for AI assistants.");
        lines.add("");

        // Units section
        lines.add("## Available Knowledge Units");
        lines.add("");

        for (KnowledgeUnit unit : units) {
            lines.add("### " + unit.id());
            lines.add("**Intent:** " + unit.intent());

            String audienceStr = unit.audience() != null
                ? String.join(", ", unit.audience())
                : "";
            lines.add("**Path:** `" + unit.path() + "` | **Scope:** " + unit.scope()
                + " | **Audience:** " + audienceStr);

            if (unit.triggers() != null && !unit.triggers().isEmpty()) {
                lines.add("*Triggers: " + String.join(", ", unit.triggers()) + "*");
            }
            if (unit.dependsOn() != null && !unit.dependsOn().isEmpty()) {
                lines.add("*Requires: " + String.join(", ", unit.dependsOn()) + "*");
            }
            lines.add("");
            lines.add("---");
            lines.add("");
        }

        // Relationships section
        List<Relationship> relationships = manifest.relationships();
        if (relationships != null && !relationships.isEmpty()) {
            lines.add("## Relationships");
            lines.add("");
            for (Relationship rel : relationships) {
                lines.add("- **" + rel.fromId() + "** " + rel.type() + " **" + rel.toId() + "**");
            }
            lines.add("");
        }

        return String.join("\n", lines);
    }

    /**
     * Compact format -- markdown table with one row per unit.
     */
    private static String formatCompact(KnowledgeManifest manifest, List<KnowledgeUnit> units) {
        List<String> lines = new ArrayList<>();

        lines.add(headerComment(manifest));
        lines.add("");
        lines.add("# Project Knowledge Map");
        lines.add("");
        lines.add("| ID | Intent | Path | Triggers |");
        lines.add("|----|--------|------|----------|");

        for (KnowledgeUnit unit : units) {
            String triggers = unit.triggers() != null && !unit.triggers().isEmpty()
                ? String.join(", ", unit.triggers()) : "";
            lines.add("| " + unit.id() + " | " + unit.intent() + " | " + unit.path() + " | " + triggers + " |");
        }

        lines.add("");
        lines.addAll(formatRelationships(manifest));

        return String.join("\n", lines);
    }

    /**
     * Agent format -- compact table with navigation instructions for AI agents.
     */
    private static String formatAgentInstructions(KnowledgeManifest manifest, List<KnowledgeUnit> units) {
        List<String> lines = new ArrayList<>();

        lines.add(headerComment(manifest));
        lines.add("");
        lines.add("# " + manifest.project() + " \u2014 Knowledge Navigator");
        lines.add("");
        lines.add("You are a project knowledge navigator. Use the units below to answer questions.");
        lines.add("When asked about a topic, find matching units by triggers/intent, then read the file.");
        lines.add("");
        lines.add("## Knowledge Units");
        lines.add("");
        lines.add("| ID | Intent | Path | Triggers |");
        lines.add("|----|--------|------|----------|");

        for (KnowledgeUnit unit : units) {
            String triggers = unit.triggers() != null && !unit.triggers().isEmpty()
                ? String.join(", ", unit.triggers()) : "";
            lines.add("| " + unit.id() + " | " + unit.intent() + " | " + unit.path() + " | " + triggers + " |");
        }

        lines.add("");
        lines.addAll(formatRelationships(manifest));

        lines.add("## Instructions");
        lines.add("");
        lines.add("1. Scan the table for units matching the user's question (check triggers and intent)");
        lines.add("2. Use the read tool to fetch the file at the unit's path");
        lines.add("3. Summarize and mention related units");
        lines.add("");

        return String.join("\n", lines);
    }

    /**
     * Build the actual .agent.md content string (with YAML frontmatter).
     */
    private static String buildAgentContent(KnowledgeManifest manifest, List<KnowledgeUnit> units) {
        List<String> lines = new ArrayList<>();

        // YAML frontmatter
        lines.add("---");
        lines.add("name: kcp-expert");
        lines.add("description: \"Navigate this project's documented knowledge \u2014 ask me about any topic\"");
        lines.add("tools: [\"read\", \"search\"]");
        lines.add("---");
        lines.add("");
        lines.add("You are a project knowledge navigator. Use the units below to answer questions.");
        lines.add("When asked about a topic, find matching units by triggers/intent, then read the file.");
        lines.add("");
        lines.add("## Knowledge Units");
        lines.add("");
        lines.add("| ID | Intent | Path | Triggers |");
        lines.add("|----|--------|------|----------|");

        for (KnowledgeUnit unit : units) {
            String triggers = unit.triggers() != null && !unit.triggers().isEmpty()
                ? String.join(", ", unit.triggers()) : "";
            lines.add("| " + unit.id() + " | " + unit.intent() + " | " + unit.path() + " | " + triggers + " |");
        }

        lines.add("");

        // Relationships
        if (manifest.relationships() != null && !manifest.relationships().isEmpty()) {
            lines.add("## Relationships");
            lines.add("");
            for (Relationship rel : manifest.relationships()) {
                lines.add("- " + rel.fromId() + " \u2192 " + rel.toId() + " (" + rel.type() + ")");
            }
            lines.add("");
        }

        lines.add("## Instructions");
        lines.add("");
        lines.add("1. Scan the table for units matching the user's question (check triggers and intent)");
        lines.add("2. Use the read tool to fetch the file at the unit's path");
        lines.add("3. Summarize and mention related units");
        lines.add("");

        return String.join("\n", lines);
    }
}
