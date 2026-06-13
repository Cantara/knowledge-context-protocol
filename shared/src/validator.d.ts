import type { KnowledgeManifest, ValidationResult } from "./model.js";
export declare const HASH_ALGORITHMS: string[];
/**
 * RFC-0019 §3.2 digest: a file hashes its raw bytes; a directory hashes
 * the bytewise-sorted concatenation of `relpath \0 hexdigest \n` entries
 * over every regular file beneath it. No exclusions. Returns undefined
 * when the target is missing or unreadable (fails closed at the caller).
 */
export declare function computeContentDigest(target: string, algorithm: string): string | undefined;
export declare function validate(manifest: KnowledgeManifest, manifestDir?: string): ValidationResult;
//# sourceMappingURL=validator.d.ts.map