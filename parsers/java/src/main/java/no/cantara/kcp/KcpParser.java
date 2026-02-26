package no.cantara.kcp;

import no.cantara.kcp.model.KnowledgeManifest;
import no.cantara.kcp.model.KnowledgeUnit;
import no.cantara.kcp.model.Relationship;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Parses a knowledge.yaml file into a {@link KnowledgeManifest}.
 */
public class KcpParser {

    // SafeConstructor disables arbitrary Java type instantiation via YAML tags
    // (e.g. !!javax.script.ScriptEngineManager). SnakeYAML 2.x defaults to safe,
    // but we declare it explicitly so the intent survives refactoring.
    private static final Yaml YAML = new Yaml(new SafeConstructor(new LoaderOptions()));

    public static KnowledgeManifest parse(Path path) throws IOException {
        try (InputStream is = Files.newInputStream(path)) {
            return parse(is);
        }
    }

    public static KnowledgeManifest parse(InputStream is) {
        Map<String, Object> data = YAML.load(is);
        return fromMap(data);
    }

    @SuppressWarnings("unchecked")
    public static KnowledgeManifest fromMap(Map<String, Object> data) {
        String kcpVersion = (String) data.get("kcp_version");
        String project = (String) data.get("project");
        String version = (String) data.get("version");
        LocalDate updated = parseDate(data.get("updated"));

        List<Map<String, Object>> unitMaps = (List<Map<String, Object>>) data.getOrDefault("units", List.of());
        List<KnowledgeUnit> units = unitMaps.stream().map(KcpParser::parseUnit).toList();

        List<Map<String, Object>> relMaps = (List<Map<String, Object>>) data.getOrDefault("relationships", List.of());
        List<Relationship> relationships = relMaps.stream().map(KcpParser::parseRelationship).toList();

        return new KnowledgeManifest(kcpVersion, project, version, updated, units, relationships);
    }

    /**
     * Validates that a unit path does not traverse outside the manifest root.
     * Spec §12 requires parsers to reject paths containing ".." that escape the root.
     */
    static String validateUnitPath(String rawPath) {
        if (rawPath == null) return null;
        // Reject absolute paths
        if (rawPath.startsWith("/") || rawPath.startsWith("\\")) {
            throw new IllegalArgumentException("Unit path must be relative: " + rawPath);
        }
        // Normalise and check for traversal
        try {
            Path normalised = Path.of(rawPath).normalize();
            if (normalised.startsWith("..")) {
                throw new IllegalArgumentException("Unit path escapes manifest root: " + rawPath);
            }
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException("Invalid unit path: " + rawPath, e);
        }
        return rawPath;
    }

    @SuppressWarnings("unchecked")
    private static KnowledgeUnit parseUnit(Map<String, Object> u) {
        return new KnowledgeUnit(
                (String) u.get("id"),
                validateUnitPath((String) u.get("path")),
                (String) u.get("intent"),
                (String) u.getOrDefault("scope", "global"),
                (List<String>) u.getOrDefault("audience", List.of()),
                parseDate(u.get("validated")),
                (List<String>) u.getOrDefault("depends_on", List.of()),
                (String) u.get("supersedes"),
                (List<String>) u.getOrDefault("triggers", List.of())
        );
    }

    private static Relationship parseRelationship(Map<String, Object> r) {
        return new Relationship(
                (String) r.get("from"),
                (String) r.get("to"),
                (String) r.get("type")
        );
    }

    private static LocalDate parseDate(Object value) {
        if (value == null) return null;
        if (value instanceof java.util.Date d) return d.toInstant().atZone(java.time.ZoneOffset.UTC).toLocalDate();
        return LocalDate.parse(value.toString());
    }
}
