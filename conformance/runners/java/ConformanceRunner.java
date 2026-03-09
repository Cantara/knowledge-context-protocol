package no.cantara.kcp.conformance;

import no.cantara.kcp.KcpParser;
import no.cantara.kcp.KcpValidator;
import no.cantara.kcp.model.KnowledgeManifest;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * KCP Conformance Test Runner — Java implementation.
 *
 * Loads each YAML fixture from the conformance/fixtures/ directory tree,
 * parses and validates it with {@link KcpParser} and {@link KcpValidator},
 * then compares against the co-located .expected.json file.
 *
 * <p>Comparison rules:
 * <ul>
 *   <li><b>valid</b>: exact boolean match (unless {@code _note} mentions cross-impl variance)</li>
 *   <li><b>errors</b>: if expected {@code valid} is false, actual errors must be non-empty</li>
 *   <li><b>parse_error</b>: if true, parsing must throw an exception</li>
 *   <li><b>unit_count</b>: exact integer match</li>
 *   <li><b>relationship_count</b>: exact integer match</li>
 *   <li><b>warnings</b>: if present and non-empty, actual warnings must be non-empty</li>
 * </ul>
 *
 * <p>Usage: {@code java ConformanceRunner <fixtures-dir>}
 */
public class ConformanceRunner {

    private static int passed = 0;
    private static int failed = 0;
    private static int skipped = 0;

    public static void main(String[] args) throws Exception {
        Path fixturesDir;
        if (args.length > 0) {
            fixturesDir = Path.of(args[0]);
        } else {
            // Default: look for fixtures relative to this file's expected location
            fixturesDir = Path.of("conformance/fixtures");
            if (!Files.isDirectory(fixturesDir)) {
                fixturesDir = Path.of("fixtures");
            }
        }

        if (!Files.isDirectory(fixturesDir)) {
            System.err.println("Fixtures directory not found: " + fixturesDir.toAbsolutePath());
            System.exit(1);
        }

        System.out.println("KCP Conformance Runner (Java)");
        System.out.println("Fixtures: " + fixturesDir.toAbsolutePath());
        System.out.println("========================================");

        List<Path> yamlFiles = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(fixturesDir)) {
            walk.filter(p -> p.toString().endsWith(".yaml"))
                .sorted(Comparator.comparing(Path::toString))
                .forEach(yamlFiles::add);
        }

        for (Path yamlFile : yamlFiles) {
            runFixture(yamlFile);
        }

        System.out.println("========================================");
        System.out.printf("Results: %d passed, %d failed, %d skipped%n", passed, failed, skipped);
        System.exit(failed > 0 ? 1 : 0);
    }

    private static void runFixture(Path yamlFile) {
        String name = yamlFile.getFileName().toString().replace(".yaml", "");
        Path expectedFile = yamlFile.resolveSibling(name + ".expected.json");

        if (!Files.exists(expectedFile)) {
            System.out.printf("  SKIP  %s (no .expected.json)%n", relativeName(yamlFile));
            skipped++;
            return;
        }

        try {
            Map<String, Object> expected = parseJson(expectedFile);
            boolean expectParseError = Boolean.TRUE.equals(expected.get("parse_error"));
            boolean parseErrorAlsoOk = Boolean.TRUE.equals(expected.get("parse_error_also_acceptable"));

            // Attempt to parse
            KnowledgeManifest manifest;
            try {
                manifest = KcpParser.parse(yamlFile);
            } catch (Exception e) {
                if (expectParseError || parseErrorAlsoOk) {
                    System.out.printf("  PASS  %s (parse error as expected: %s)%n",
                            relativeName(yamlFile), e.getMessage());
                    passed++;
                } else {
                    System.out.printf("  FAIL  %s (unexpected parse error: %s)%n",
                            relativeName(yamlFile), e.getMessage());
                    failed++;
                }
                return;
            }

            if (expectParseError) {
                System.out.printf("  FAIL  %s (expected parse error but parsing succeeded)%n",
                        relativeName(yamlFile));
                failed++;
                return;
            }

            // Validate
            KcpValidator.ValidationResult result = KcpValidator.validate(manifest);

            // Compare
            List<String> failures = new ArrayList<>();

            // Check valid/invalid
            if (expected.containsKey("valid")) {
                boolean expectedValid = (Boolean) expected.get("valid");
                // Allow cross-implementation variance for edge cases
                String note = (String) expected.get("_note");
                boolean allowVariance = note != null && note.contains("cross-impl");
                if (!allowVariance && result.isValid() != expectedValid) {
                    failures.add(String.format("valid: expected %s, got %s (errors: %s)",
                            expectedValid, result.isValid(), result.errors()));
                }
            }

            // Check errors non-empty when expected invalid
            if (expected.containsKey("valid") && !(Boolean) expected.get("valid")) {
                if (result.errors().isEmpty()) {
                    String note = (String) expected.get("_note");
                    if (note == null || !note.contains("cross-impl")) {
                        failures.add("expected errors to be non-empty");
                    }
                }
            }

            // Check unit_count
            if (expected.containsKey("unit_count")) {
                int expectedCount = ((Number) expected.get("unit_count")).intValue();
                if (manifest.units().size() != expectedCount) {
                    failures.add(String.format("unit_count: expected %d, got %d",
                            expectedCount, manifest.units().size()));
                }
            }

            // Check relationship_count
            if (expected.containsKey("relationship_count")) {
                int expectedCount = ((Number) expected.get("relationship_count")).intValue();
                if (manifest.relationships().size() != expectedCount) {
                    failures.add(String.format("relationship_count: expected %d, got %d",
                            expectedCount, manifest.relationships().size()));
                }
            }

            // Check warnings non-empty when expected
            if (expected.containsKey("warnings")) {
                @SuppressWarnings("unchecked")
                List<String> expectedWarnings = (List<String>) expected.get("warnings");
                if (expectedWarnings != null && !expectedWarnings.isEmpty() && result.warnings().isEmpty()) {
                    failures.add("expected warnings to be non-empty");
                }
            }

            if (failures.isEmpty()) {
                System.out.printf("  PASS  %s%n", relativeName(yamlFile));
                passed++;
            } else {
                System.out.printf("  FAIL  %s%n", relativeName(yamlFile));
                for (String f : failures) {
                    System.out.printf("        - %s%n", f);
                }
                failed++;
            }

        } catch (Exception e) {
            System.out.printf("  FAIL  %s (runner error: %s)%n", relativeName(yamlFile), e.getMessage());
            failed++;
        }
    }

    private static String relativeName(Path p) {
        // Show path from fixtures/ onwards
        String s = p.toString();
        int idx = s.indexOf("fixtures/");
        return idx >= 0 ? s.substring(idx) : p.getFileName().toString();
    }

    /**
     * Minimal JSON parser — avoids external dependency.
     * Handles the simple structure of .expected.json files.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseJson(Path path) throws IOException {
        String json = Files.readString(path);
        // Strip comments (none expected but defensive)
        json = json.strip();
        return (Map<String, Object>) parseJsonValue(json, new int[]{0});
    }

    private static Object parseJsonValue(String json, int[] pos) {
        skipWhitespace(json, pos);
        char c = json.charAt(pos[0]);
        if (c == '{') return parseJsonObject(json, pos);
        if (c == '[') return parseJsonArray(json, pos);
        if (c == '"') return parseJsonString(json, pos);
        if (c == 't' || c == 'f') return parseJsonBoolean(json, pos);
        if (c == 'n') { pos[0] += 4; return null; }
        if (c == '-' || Character.isDigit(c)) return parseJsonNumber(json, pos);
        throw new IllegalArgumentException("Unexpected char '" + c + "' at pos " + pos[0]);
    }

    private static Map<String, Object> parseJsonObject(String json, int[] pos) {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        pos[0]++; // skip '{'
        skipWhitespace(json, pos);
        if (json.charAt(pos[0]) == '}') { pos[0]++; return map; }
        while (true) {
            skipWhitespace(json, pos);
            String key = parseJsonString(json, pos);
            skipWhitespace(json, pos);
            pos[0]++; // skip ':'
            Object value = parseJsonValue(json, pos);
            map.put(key, value);
            skipWhitespace(json, pos);
            if (json.charAt(pos[0]) == '}') { pos[0]++; return map; }
            pos[0]++; // skip ','
        }
    }

    private static List<Object> parseJsonArray(String json, int[] pos) {
        List<Object> list = new ArrayList<>();
        pos[0]++; // skip '['
        skipWhitespace(json, pos);
        if (json.charAt(pos[0]) == ']') { pos[0]++; return list; }
        while (true) {
            list.add(parseJsonValue(json, pos));
            skipWhitespace(json, pos);
            if (json.charAt(pos[0]) == ']') { pos[0]++; return list; }
            pos[0]++; // skip ','
        }
    }

    private static String parseJsonString(String json, int[] pos) {
        pos[0]++; // skip opening '"'
        StringBuilder sb = new StringBuilder();
        while (json.charAt(pos[0]) != '"') {
            if (json.charAt(pos[0]) == '\\') {
                pos[0]++;
                char esc = json.charAt(pos[0]);
                switch (esc) {
                    case '"', '\\', '/' -> sb.append(esc);
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    default -> sb.append(esc);
                }
            } else {
                sb.append(json.charAt(pos[0]));
            }
            pos[0]++;
        }
        pos[0]++; // skip closing '"'
        return sb.toString();
    }

    private static Boolean parseJsonBoolean(String json, int[] pos) {
        if (json.startsWith("true", pos[0])) { pos[0] += 4; return true; }
        if (json.startsWith("false", pos[0])) { pos[0] += 5; return false; }
        throw new IllegalArgumentException("Expected boolean at pos " + pos[0]);
    }

    private static Number parseJsonNumber(String json, int[] pos) {
        int start = pos[0];
        if (json.charAt(pos[0]) == '-') pos[0]++;
        while (pos[0] < json.length() && (Character.isDigit(json.charAt(pos[0])) || json.charAt(pos[0]) == '.')) {
            pos[0]++;
        }
        String num = json.substring(start, pos[0]);
        if (num.contains(".")) return Double.parseDouble(num);
        return Integer.parseInt(num);
    }

    private static void skipWhitespace(String json, int[] pos) {
        while (pos[0] < json.length() && Character.isWhitespace(json.charAt(pos[0]))) pos[0]++;
    }
}
