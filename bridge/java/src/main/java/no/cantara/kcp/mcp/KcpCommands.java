package no.cantara.kcp.mcp;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Loads kcp-commands YAML manifests and formats them as compact syntax blocks.
 * Java equivalent of the TypeScript commands.ts.
 */
public final class KcpCommands {

    private KcpCommands() {}

    // ── Records ────────────────────────────────────────────────────────────────

    public record KeyFlag(String flag, String description, String useWhen) {}

    public record PreferredInvocation(String invocation, String useWhen) {}

    public record CommandSyntax(
        String usage,
        List<KeyFlag> keyFlags,
        List<PreferredInvocation> preferredInvocations
    ) {}

    public record CommandManifest(
        String command,
        String subcommand,
        String platform,
        String description,
        CommandSyntax syntax
    ) {}

    // ── Loading ────────────────────────────────────────────────────────────────

    /**
     * Build the lookup key for a command manifest.
     * "git commit" if subcommand exists, otherwise just "git".
     */
    static String manifestKey(CommandManifest m) {
        return m.subcommand() != null ? m.command() + " " + m.subcommand() : m.command();
    }

    /**
     * Load all YAML files from a directory into a map.
     * Key: "command subcommand" (e.g. "git commit") or just "command" if no subcommand.
     *
     * Non-YAML files and files that fail to parse are silently skipped.
     */
    public static Map<String, CommandManifest> loadCommandManifests(Path dir) {
        Map<String, CommandManifest> map = new LinkedHashMap<>();

        if (!Files.isDirectory(dir)) return map;

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path entry : stream) {
                String name = entry.getFileName().toString();
                if (!name.endsWith(".yaml") && !name.endsWith(".yml")) continue;

                try (InputStream is = Files.newInputStream(entry)) {
                    Yaml yaml = new Yaml();
                    Object raw = yaml.load(is);
                    if (!(raw instanceof Map<?, ?> data)) continue;

                    @SuppressWarnings("unchecked")
                    Map<String, Object> d = (Map<String, Object>) data;
                    CommandManifest manifest = parseCommandManifest(d);
                    if (manifest != null) {
                        map.put(manifestKey(manifest), manifest);
                    }
                } catch (Exception e) {
                    // Skip files that fail to parse
                }
            }
        } catch (IOException e) {
            // Directory read failed — return empty map
        }

        return map;
    }

    @SuppressWarnings("unchecked")
    private static CommandManifest parseCommandManifest(Map<String, Object> d) {
        if (!d.containsKey("command") || !d.containsKey("syntax")) return null;

        Map<String, Object> syntaxRaw = (Map<String, Object>) d.get("syntax");
        if (syntaxRaw == null) return null;

        List<KeyFlag> keyFlags = new ArrayList<>();
        Object kfRaw = syntaxRaw.get("key_flags");
        if (kfRaw instanceof List<?> kfList) {
            for (Object item : kfList) {
                if (item instanceof Map<?, ?> f) {
                    keyFlags.add(new KeyFlag(
                        str(f.get("flag")),
                        str(f.get("description")),
                        str(f.get("use_when"))
                    ));
                }
            }
        }

        List<PreferredInvocation> preferred = new ArrayList<>();
        Object piRaw = syntaxRaw.get("preferred_invocations");
        if (piRaw instanceof List<?> piList) {
            for (Object item : piList) {
                if (item instanceof Map<?, ?> p) {
                    preferred.add(new PreferredInvocation(
                        str(p.get("invocation")),
                        str(p.get("use_when"))
                    ));
                }
            }
        }

        String command = str(d.get("command"));
        Object subRaw = d.get("subcommand");
        String subcommand = subRaw != null ? str(subRaw) : null;

        return new CommandManifest(
            command,
            subcommand,
            str(d.getOrDefault("platform", "all")),
            str(d.getOrDefault("description", "")),
            new CommandSyntax(
                str(syntaxRaw.getOrDefault("usage", "")),
                List.copyOf(keyFlags),
                List.copyOf(preferred)
            )
        );
    }

    private static String str(Object o) {
        return o != null ? String.valueOf(o) : "";
    }

    // ── Formatting ─────────────────────────────────────────────────────────────

    /**
     * Format a manifest as a compact syntax block.
     *
     * <pre>
     * [kcp] git commit: Record staged changes to the repository
     * Usage: git commit [&lt;options&gt;]
     * Key flags:
     *   -m '&lt;message&gt;': Commit message inline  -> Simple one-line commits
     * Preferred:
     *   git commit -m 'Add feature X'  # Standard single-line commit
     * </pre>
     */
    public static String formatSyntaxBlock(CommandManifest manifest) {
        String name = manifest.subcommand() != null
            ? manifest.command() + " " + manifest.subcommand()
            : manifest.command();

        List<String> lines = new ArrayList<>();
        lines.add("[kcp] " + name + ": " + manifest.description());
        lines.add("Usage: " + manifest.syntax().usage());

        if (!manifest.syntax().keyFlags().isEmpty()) {
            lines.add("Key flags:");
            for (KeyFlag f : manifest.syntax().keyFlags()) {
                lines.add("  " + f.flag() + ": " + f.description() + "  \u2192 " + f.useWhen());
            }
        }

        if (!manifest.syntax().preferredInvocations().isEmpty()) {
            lines.add("Preferred:");
            for (PreferredInvocation p : manifest.syntax().preferredInvocations()) {
                lines.add("  " + p.invocation() + "  # " + p.useWhen());
            }
        }

        return String.join("\n", lines);
    }

    // ── Lookup ─────────────────────────────────────────────────────────────────

    /**
     * Look up a command by query string.
     *
     * Strategy:
     * 1. Exact match: "git commit" -> map.get("git commit")
     * 2. Prefix match: "git" -> first entry whose command matches
     *    (prefers base command over subcommand)
     *
     * @return the matching manifest, or null if not found
     */
    public static CommandManifest lookupCommand(Map<String, CommandManifest> map, String query) {
        String normalized = query.trim().toLowerCase();

        // 1. Exact match
        for (Map.Entry<String, CommandManifest> entry : map.entrySet()) {
            if (entry.getKey().toLowerCase().equals(normalized)) {
                return entry.getValue();
            }
        }

        // 2. Prefix match — extract the base command from the query
        String[] queryParts = normalized.split("\\s+");
        String queryCmd = queryParts[0];

        // Prefer base command (no subcommand) first
        for (CommandManifest manifest : map.values()) {
            if (manifest.command().toLowerCase().equals(queryCmd) && manifest.subcommand() == null) {
                return manifest;
            }
        }

        // Fall back to first subcommand match
        for (CommandManifest manifest : map.values()) {
            if (manifest.command().toLowerCase().equals(queryCmd)) {
                return manifest;
            }
        }

        return null;
    }
}
