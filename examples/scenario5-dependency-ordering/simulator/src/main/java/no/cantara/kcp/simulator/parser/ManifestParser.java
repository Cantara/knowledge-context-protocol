package no.cantara.kcp.simulator.parser;

import no.cantara.kcp.simulator.model.KnowledgeUnit;
import no.cantara.kcp.simulator.model.Relationship;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Parses a KCP knowledge.yaml and extracts units and relationships.
 */
public final class ManifestParser {

    private static final Yaml YAML = new Yaml(new SafeConstructor(new LoaderOptions()));

    private ManifestParser() {}

    /**
     * Parsed result containing both units and relationships.
     */
    public record ParsedManifest(List<KnowledgeUnit> units, List<Relationship> relationships) {}

    public static ParsedManifest parse(Path path) throws IOException {
        try (InputStream is = Files.newInputStream(path)) {
            return parse(is);
        }
    }

    @SuppressWarnings("unchecked")
    public static ParsedManifest parse(InputStream is) {
        Map<String, Object> data = YAML.load(is);
        return parseFromMap(data);
    }

    @SuppressWarnings("unchecked")
    public static ParsedManifest parseFromMap(Map<String, Object> data) {
        List<Map<String, Object>> rawUnits = (List<Map<String, Object>>)
                data.getOrDefault("units", Collections.emptyList());

        List<KnowledgeUnit> units = new ArrayList<>();
        for (Map<String, Object> raw : rawUnits) {
            units.add(parseUnit(raw));
        }

        List<Map<String, Object>> rawRels = (List<Map<String, Object>>)
                data.getOrDefault("relationships", Collections.emptyList());

        List<Relationship> relationships = new ArrayList<>();
        for (Map<String, Object> raw : rawRels) {
            relationships.add(parseRelationship(raw));
        }

        return new ParsedManifest(
                Collections.unmodifiableList(units),
                Collections.unmodifiableList(relationships));
    }

    @SuppressWarnings("unchecked")
    private static KnowledgeUnit parseUnit(Map<String, Object> raw) {
        return new KnowledgeUnit(
                (String) raw.get("id"),
                (String) raw.get("path"),
                (String) raw.get("intent"),
                (String) raw.getOrDefault("access", "public"),
                (String) raw.getOrDefault("sensitivity", "public"),
                (List<String>) raw.getOrDefault("depends_on", List.of()),
                (String) raw.get("supersedes"),
                Boolean.TRUE.equals(raw.get("deprecated"))
        );
    }

    private static Relationship parseRelationship(Map<String, Object> raw) {
        return new Relationship(
                (String) raw.get("from"),
                (String) raw.get("to"),
                (String) raw.get("type")
        );
    }
}
