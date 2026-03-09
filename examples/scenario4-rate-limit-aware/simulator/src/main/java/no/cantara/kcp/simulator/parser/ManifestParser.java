package no.cantara.kcp.simulator.parser;

import no.cantara.kcp.simulator.model.KnowledgeUnit;
import no.cantara.kcp.simulator.model.RateLimit;
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
 * Parses a KCP knowledge.yaml and extracts units with their effective rate limits.
 * <p>
 * Resolution rule: if a unit declares its own {@code rate_limits.default} block,
 * that block is used. Otherwise the root-level {@code rate_limits.default} applies.
 * If neither exists, the unit has no declared limit ({@link RateLimit#UNLIMITED}).
 */
public final class ManifestParser {

    private static final Yaml YAML = new Yaml(new SafeConstructor(new LoaderOptions()));

    private ManifestParser() {}

    /**
     * Parse a knowledge.yaml file into a list of knowledge units with effective rate limits.
     */
    public static List<KnowledgeUnit> parse(Path path) throws IOException {
        try (InputStream is = Files.newInputStream(path)) {
            return parse(is);
        }
    }

    /**
     * Parse from an input stream.
     */
    @SuppressWarnings("unchecked")
    public static List<KnowledgeUnit> parse(InputStream is) {
        Map<String, Object> data = YAML.load(is);
        return parseFromMap(data);
    }

    /**
     * Parse from a pre-loaded YAML map (useful for testing).
     */
    @SuppressWarnings("unchecked")
    public static List<KnowledgeUnit> parseFromMap(Map<String, Object> data) {
        RateLimit rootDefault = extractRateLimit((Map<String, Object>) data.get("rate_limits"));

        List<Map<String, Object>> rawUnits = (List<Map<String, Object>>)
                data.getOrDefault("units", Collections.emptyList());

        List<KnowledgeUnit> units = new ArrayList<>();
        for (Map<String, Object> raw : rawUnits) {
            units.add(parseUnit(raw, rootDefault));
        }
        return Collections.unmodifiableList(units);
    }

    @SuppressWarnings("unchecked")
    private static KnowledgeUnit parseUnit(Map<String, Object> raw, RateLimit rootDefault) {
        String id = (String) raw.get("id");
        String path = (String) raw.get("path");
        String intent = (String) raw.get("intent");
        String access = (String) raw.getOrDefault("access", "public");
        String sensitivity = (String) raw.getOrDefault("sensitivity", "public");
        List<String> dependsOn = (List<String>) raw.getOrDefault("depends_on", List.of());

        // Unit-level rate_limits override root default entirely
        Map<String, Object> unitRateLimits = (Map<String, Object>) raw.get("rate_limits");
        RateLimit effective;
        if (unitRateLimits != null) {
            effective = extractRateLimit(unitRateLimits);
        } else {
            effective = rootDefault;
        }

        return new KnowledgeUnit(id, path, intent, access, sensitivity, dependsOn, effective);
    }

    @SuppressWarnings("unchecked")
    private static RateLimit extractRateLimit(Map<String, Object> rateLimitsBlock) {
        if (rateLimitsBlock == null) return RateLimit.UNLIMITED;
        Map<String, Object> def = (Map<String, Object>) rateLimitsBlock.get("default");
        if (def == null) return RateLimit.UNLIMITED;
        Integer rpm = def.get("requests_per_minute") instanceof Number n ? n.intValue() : null;
        Integer rpd = def.get("requests_per_day") instanceof Number n ? n.intValue() : null;
        return new RateLimit(rpm, rpd);
    }
}
