package no.cantara.kcp;

import no.cantara.kcp.model.KnowledgeManifest;
import no.cantara.kcp.model.KnowledgeUnit;
import no.cantara.kcp.model.Relationship;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Parses a knowledge.yaml file into a {@link KnowledgeManifest}.
 */
public class KcpParser {

    private static final Yaml YAML = new Yaml();

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
        String project = (String) data.get("project");
        String version = (String) data.get("version");
        LocalDate updated = parseDate(data.get("updated"));

        List<Map<String, Object>> unitMaps = (List<Map<String, Object>>) data.getOrDefault("units", List.of());
        List<KnowledgeUnit> units = unitMaps.stream().map(KcpParser::parseUnit).toList();

        List<Map<String, Object>> relMaps = (List<Map<String, Object>>) data.getOrDefault("relationships", List.of());
        List<Relationship> relationships = relMaps.stream().map(KcpParser::parseRelationship).toList();

        return new KnowledgeManifest(project, version, updated, units, relationships);
    }

    @SuppressWarnings("unchecked")
    private static KnowledgeUnit parseUnit(Map<String, Object> u) {
        return new KnowledgeUnit(
                (String) u.get("id"),
                (String) u.get("path"),
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
