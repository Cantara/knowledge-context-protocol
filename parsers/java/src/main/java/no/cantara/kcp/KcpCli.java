package no.cantara.kcp;

import no.cantara.kcp.model.KnowledgeManifest;

import java.nio.file.Path;
import java.util.List;

/**
 * Command-line interface for KCP validation.
 * Usage: java -jar kcp-parser.jar &lt;path-to-knowledge.yaml&gt;
 */
public class KcpCli {

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: java -jar kcp-parser.jar <path-to-knowledge.yaml>");
            System.exit(1);
        }

        Path path = Path.of(args[0]);
        if (!path.toFile().exists()) {
            System.err.println("Error: file not found: " + path);
            System.exit(1);
        }

        KnowledgeManifest manifest;
        try {
            manifest = KcpParser.parse(path);
        } catch (Exception e) {
            System.err.println("Parse error: " + e.getMessage());
            System.exit(1);
            return;
        }

        KcpValidator.ValidationResult result = KcpValidator.validate(manifest);
        if (result.hasWarnings()) {
            result.warnings().forEach(w -> System.err.println("  ⚠ " + w));
        }
        if (!result.isValid()) {
            System.err.println("Validation failed — " + result.errors().size() + " error(s):");
            result.errors().forEach(e -> System.err.println("  • " + e));
            System.exit(1);
        }

        System.out.printf("✓ %s is valid — project '%s' v%s, %d unit(s), %d relationship(s)%n",
                path,
                manifest.project(),
                manifest.version(),
                manifest.units().size(),
                manifest.relationships().size());
    }
}
