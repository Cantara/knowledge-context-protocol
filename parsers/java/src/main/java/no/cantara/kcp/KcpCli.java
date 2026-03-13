package no.cantara.kcp;

import no.cantara.kcp.model.KnowledgeManifest;

import java.nio.file.Path;

/**
 * Command-line interface for KCP validation and scaffolding.
 * <p>
 * Usage:
 * <pre>
 *   java -jar kcp-parser.jar validate &lt;path-to-knowledge.yaml&gt;
 *   java -jar kcp-parser.jar init [--level 1|2|3] [--scan] [--force] [directory]
 *   java -jar kcp-parser.jar &lt;path-to-knowledge.yaml&gt;   (backwards compat)
 * </pre>
 */
public class KcpCli {

    public static void main(String[] args) {
        if (args.length < 1) {
            printUsage();
            System.exit(1);
        }

        String command = args[0];

        if ("init".equals(command)) {
            System.exit(runInit(args));
        } else if ("validate".equals(command)) {
            if (args.length < 2) {
                System.err.println("Usage: kcp validate <path-to-knowledge.yaml>");
                System.exit(1);
            }
            System.exit(runValidate(args[1]));
        } else {
            // Backwards compatibility: treat first arg as a file path
            System.exit(runValidate(args[0]));
        }
    }

    private static int runValidate(String pathStr) {
        Path path = Path.of(pathStr);
        if (!path.toFile().exists()) {
            System.err.println("Error: file not found: " + path);
            return 1;
        }

        KnowledgeManifest manifest;
        try {
            manifest = KcpParser.parse(path);
        } catch (Exception e) {
            System.err.println("Parse error: " + e.getMessage());
            return 1;
        }

        KcpValidator.ValidationResult result = KcpValidator.validate(manifest, path.getParent());
        if (result.hasWarnings()) {
            result.warnings().forEach(w -> System.err.println("  \u26a0 " + w));
        }
        if (!result.isValid()) {
            System.err.println("Validation failed \u2014 " + result.errors().size() + " error(s):");
            result.errors().forEach(e -> System.err.println("  \u2022 " + e));
            return 1;
        }

        String versionStr = manifest.version() != null ? " v" + manifest.version() : "";
        System.out.printf("\u2713 %s is valid \u2014 project '%s'%s, %d unit(s), %d relationship(s)%n",
                path,
                manifest.project(),
                versionStr,
                manifest.units().size(),
                manifest.relationships().size());
        return 0;
    }

    private static int runInit(String[] args) {
        int level = 1;
        boolean scan = false;
        boolean force = false;
        String directory = ".";

        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--level" -> {
                    if (i + 1 >= args.length) {
                        System.err.println("Error: --level requires a value (1, 2, or 3)");
                        return 1;
                    }
                    i++;
                    try {
                        level = Integer.parseInt(args[i]);
                        if (level < 1 || level > 3) {
                            System.err.println("Error: --level must be 1, 2, or 3");
                            return 1;
                        }
                    } catch (NumberFormatException e) {
                        System.err.println("Error: --level must be 1, 2, or 3");
                        return 1;
                    }
                }
                case "--scan" -> scan = true;
                case "--force" -> force = true;
                default -> {
                    if (!args[i].startsWith("-")) {
                        directory = args[i];
                    } else {
                        System.err.println("Unknown option: " + args[i]);
                        return 1;
                    }
                }
            }
        }

        Path dir = Path.of(directory).toAbsolutePath();
        if (!dir.toFile().isDirectory()) {
            System.err.println("Error: not a directory: " + dir);
            return 1;
        }

        return KcpInit.run(dir, level, scan, force);
    }

    private static void printUsage() {
        System.err.println("Usage:");
        System.err.println("  kcp validate <path-to-knowledge.yaml>");
        System.err.println("  kcp init [--level 1|2|3] [--scan] [--force] [directory]");
    }
}
