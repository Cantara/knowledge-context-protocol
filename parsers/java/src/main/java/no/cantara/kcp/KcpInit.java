package no.cantara.kcp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Generates a starter knowledge.yaml by scanning a project directory for
 * well-known knowledge artifacts.
 *
 * <p>Supports three conformance levels:
 * <ul>
 *   <li>Level 1 (default): id, path, intent, scope, audience</li>
 *   <li>Level 2: Level 1 + validated date + hints.token_estimate</li>
 *   <li>Level 3: Level 2 + triggers extracted from filename/heading</li>
 * </ul>
 */
public class KcpInit {

    private static final List<String> WELL_KNOWN_FILES = List.of(
            "README.md",
            "AGENTS.md",
            "CLAUDE.md",
            ".github/copilot-instructions.md",
            "llms.txt"
    );

    private static final List<String> WELL_KNOWN_DIRS = List.of(
            "docs",
            "openapi",
            ".claude"
    );

    private static final Pattern HEADING_PATTERN = Pattern.compile("^#{1,3}\\s+(.+)");
    private static final Pattern H2_H3_PATTERN = Pattern.compile("^#{2,3}\\s+(.+)");
    private static final Pattern SLUG_PATTERN = Pattern.compile("[^a-z0-9]+");
    private static final Pattern WORD_PATTERN = Pattern.compile("[^a-zA-Z0-9]+");

    /**
     * Run the init command.
     *
     * @return exit code (0 = success, 1 = failure)
     */
    public static int run(Path directory, int level, boolean scan, boolean force) {
        Path outputPath = directory.resolve("knowledge.yaml");

        if (Files.exists(outputPath) && !force) {
            System.out.println("Warning: " + outputPath + " already exists. Use --force to overwrite.");
            return 1;
        }

        List<Path> artifacts = discoverArtifacts(directory);

        if (scan) {
            System.out.println("Scanning project...");
            for (Path a : artifacts) {
                Path rel = directory.relativize(a);
                System.out.println("  Found: " + rel);
            }
            System.out.println();
        }

        String content = generateManifest(directory, artifacts, level);
        try {
            Files.writeString(outputPath, content);
        } catch (IOException e) {
            System.err.println("Error writing knowledge.yaml: " + e.getMessage());
            return 1;
        }

        System.out.println("Generated knowledge.yaml with " + artifacts.size() + " units (Level " + level + ").");
        System.out.println("Review and update the 'intent' fields, then validate:");
        System.out.println("  kcp validate knowledge.yaml");

        return 0;
    }

    static List<Path> discoverArtifacts(Path directory) {
        List<Path> found = new ArrayList<>();

        for (String name : WELL_KNOWN_FILES) {
            Path p = directory.resolve(name);
            if (Files.isRegularFile(p)) {
                found.add(p);
            }
        }

        for (String name : WELL_KNOWN_DIRS) {
            Path d = directory.resolve(name);
            if (Files.isDirectory(d)) {
                try (Stream<Path> walk = Files.walk(d)) {
                    List<Path> children = walk
                            .filter(Files::isRegularFile)
                            .filter(p -> matchesDirFilter(name, p))
                            .sorted()
                            .toList();
                    found.addAll(children);
                } catch (IOException ignored) {
                }
            }
        }

        return found;
    }

    private static boolean matchesDirFilter(String dirName, Path file) {
        String fileName = file.getFileName().toString().toLowerCase();
        return switch (dirName) {
            case ".claude" -> fileName.endsWith(".yaml") || fileName.endsWith(".yml") || fileName.endsWith(".md");
            case "openapi" -> fileName.endsWith(".yaml") || fileName.endsWith(".yml") || fileName.endsWith(".json");
            default -> fileName.endsWith(".md") || fileName.endsWith(".txt") || fileName.endsWith(".rst");
        };
    }

    static String generateManifest(Path directory, List<Path> artifacts, int level) {
        String projectName = detectProjectName(directory);
        String description = extractDescription(directory);
        String today = LocalDate.now().toString();

        Set<String> seenIds = new HashSet<>();
        List<Map<String, Object>> units = new ArrayList<>();

        for (Path filepath : artifacts) {
            Path rel = directory.relativize(filepath);
            String baseId = slugify(rel.getFileName().toString());
            String unitId = uniqueId(baseId, seenIds);

            Map<String, Object> unit = new LinkedHashMap<>();
            unit.put("id", unitId);
            unit.put("path", rel.toString().replace('\\', '/'));
            unit.put("intent", intentFromArtifact(filepath));
            unit.put("scope", scopeForArtifact(rel));
            unit.put("audience", audienceForArtifact(filepath));

            if (level >= 2) {
                unit.put("validated", today);
                unit.put("token_estimate", tokenEstimate(filepath));
            }

            if (level >= 3) {
                List<String> triggers = extractTriggers(filepath);
                if (!triggers.isEmpty()) {
                    unit.put("triggers", triggers);
                }
            }

            units.add(unit);
        }

        return formatYaml(projectName, description, today, units, level);
    }

    static String detectProjectName(Path directory) {
        // Try package.json
        Path packageJson = directory.resolve("package.json");
        if (Files.isRegularFile(packageJson)) {
            try {
                String text = Files.readString(packageJson);
                Matcher m = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"").matcher(text);
                if (m.find()) {
                    return m.group(1);
                }
            } catch (IOException ignored) {
            }
        }

        // Try pom.xml
        Path pomXml = directory.resolve("pom.xml");
        if (Files.isRegularFile(pomXml)) {
            try {
                String text = Files.readString(pomXml);
                Matcher m = Pattern.compile("<artifactId>([^<]+)</artifactId>").matcher(text);
                if (m.find()) {
                    return m.group(1);
                }
            } catch (IOException ignored) {
            }
        }

        // Fall back to directory name
        Path dirName = directory.toAbsolutePath().getFileName();
        return dirName != null ? dirName.toString() : "my-project";
    }

    static String extractDescription(Path directory) {
        Path readme = directory.resolve("README.md");
        if (!Files.isRegularFile(readme)) {
            return "";
        }
        try {
            List<String> lines = Files.readAllLines(readme);
            List<String> paraLines = new ArrayList<>();
            boolean pastHeading = false;
            for (String line : lines) {
                String stripped = line.strip();
                if (!pastHeading) {
                    if (stripped.startsWith("#") || stripped.isEmpty()) {
                        continue;
                    }
                    pastHeading = true;
                }
                if (pastHeading) {
                    if (stripped.isEmpty() && !paraLines.isEmpty()) {
                        break;
                    }
                    if (stripped.startsWith("#")) {
                        break;
                    }
                    paraLines.add(stripped);
                }
            }
            String desc = String.join(" ", paraLines).strip();
            if (desc.length() > 200) {
                desc = desc.substring(0, 197) + "...";
            }
            return desc;
        } catch (IOException e) {
            return "";
        }
    }

    private static String slugify(String name) {
        // Remove extension
        int dot = name.lastIndexOf('.');
        String stem = (dot > 0) ? name.substring(0, dot) : name;
        String slug = SLUG_PATTERN.matcher(stem.toLowerCase()).replaceAll("-");
        // Trim leading/trailing dashes
        slug = slug.replaceAll("^-+|-+$", "");
        return slug.isEmpty() ? "unit" : slug;
    }

    private static String uniqueId(String base, Set<String> seen) {
        if (seen.add(base)) {
            return base;
        }
        int i = 2;
        while (!seen.add(base + "-" + i)) {
            i++;
        }
        return base + "-" + i;
    }

    private static String intentFromArtifact(Path filepath) {
        String heading = extractFirstHeading(filepath);
        if (heading != null) {
            return heading;
        }
        return "TODO: describe what question this answers";
    }

    private static String scopeForArtifact(Path relativePath) {
        return relativePath.getNameCount() == 1 ? "global" : "module";
    }

    @SuppressWarnings("unchecked")
    private static List<String> audienceForArtifact(Path filepath) {
        if (filepath.toString().contains(".claude")) {
            return List.of("agent");
        }
        return List.of("human", "agent");
    }

    private static String extractFirstHeading(Path filepath) {
        try {
            List<String> lines = Files.readAllLines(filepath);
            for (String line : lines) {
                Matcher m = HEADING_PATTERN.matcher(line);
                if (m.matches()) {
                    return m.group(1).strip();
                }
            }
        } catch (IOException ignored) {
        }
        return null;
    }

    static List<String> extractTriggers(Path filepath) {
        List<String> triggers = new ArrayList<>();
        // Keywords from filename
        String stem = filepath.getFileName().toString();
        int dot = stem.lastIndexOf('.');
        if (dot > 0) stem = stem.substring(0, dot);
        String[] parts = WORD_PATTERN.split(stem.toLowerCase());
        for (String p : parts) {
            if (p.length() > 2 && !triggers.contains(p)) {
                triggers.add(p);
            }
        }
        // Keywords from first heading
        String heading = extractFirstHeading(filepath);
        if (heading != null) {
            String[] words = WORD_PATTERN.split(heading.toLowerCase());
            for (String w : words) {
                if (w.length() > 2 && !triggers.contains(w)) {
                    triggers.add(w);
                }
            }
        }
        // Return first 3
        return triggers.size() > 3 ? triggers.subList(0, 3) : triggers;
    }

    private static int tokenEstimate(Path filepath) {
        try {
            long size = Files.size(filepath);
            long estimate = size / 4;
            long rounded = Math.round(estimate / 100.0) * 100;
            return (int) Math.max(rounded, 100);
        } catch (IOException e) {
            return 100;
        }
    }

    private static String yamlStr(String value) {
        if (value == null || value.isEmpty()) {
            return "\"\"";
        }
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    @SuppressWarnings("unchecked")
    private static String formatYaml(String projectName, String description, String today,
                                     List<Map<String, Object>> units, int level) {
        StringBuilder sb = new StringBuilder();
        sb.append("kcp_version: \"0.10\"\n");
        sb.append("project: ").append(projectName).append('\n');
        if (description != null && !description.isEmpty()) {
            sb.append("description: ").append(yamlStr(description)).append('\n');
        }
        sb.append("version: \"0.1.0\"\n");
        sb.append("updated: ").append(yamlStr(today)).append('\n');
        sb.append('\n');
        sb.append("units:\n");

        for (Map<String, Object> unit : units) {
            sb.append("  - id: ").append(unit.get("id")).append('\n');
            sb.append("    path: ").append(unit.get("path")).append('\n');
            sb.append("    intent: ").append(yamlStr((String) unit.get("intent"))).append('\n');
            sb.append("    scope: ").append(unit.get("scope")).append('\n');

            List<String> audience = (List<String>) unit.get("audience");
            sb.append("    audience: [").append(String.join(", ", audience)).append("]\n");

            if (unit.containsKey("validated")) {
                sb.append("    validated: ").append(yamlStr((String) unit.get("validated"))).append('\n');
            }

            if (unit.containsKey("token_estimate")) {
                sb.append("    hints:\n");
                sb.append("      token_estimate: ").append(unit.get("token_estimate")).append('\n');
            }

            if (unit.containsKey("triggers")) {
                List<String> triggers = (List<String>) unit.get("triggers");
                sb.append("    triggers: [").append(String.join(", ", triggers)).append("]\n");
            }

            sb.append('\n');
        }

        return sb.toString();
    }
}
