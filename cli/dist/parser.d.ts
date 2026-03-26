import type { KnowledgeManifest } from "./model.js";
export declare function validateUnitPath(raw: string): string;
type RawMap = Record<string, unknown>;
/**
 * Parse a plain JavaScript object (from YAML.load output) into a KnowledgeManifest.
 * Mirrors Python's parse_dict() and Java's KcpParser.fromMap().
 */
export declare function parseDict(data: RawMap): KnowledgeManifest;
/**
 * Parse a knowledge.yaml file from disk.
 * Mirrors Python's parse(path) and Java's KcpParser.parse(Path).
 *
 * Uses YAML safe load — no arbitrary type instantiation (SPEC §12).
 */
export declare function parseFile(filePath: string): KnowledgeManifest;
export {};
//# sourceMappingURL=parser.d.ts.map