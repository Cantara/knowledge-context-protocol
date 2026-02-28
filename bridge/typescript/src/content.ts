// File content reading for MCP resource responses
// Handles text vs binary dispatch and path traversal guard.

import { readFileSync } from "node:fs";
import { resolve, join } from "node:path";
import type { KnowledgeUnit } from "./model.js";
import { isBinaryMime, resolveMimeType } from "./mapper.js";

export interface TextContent {
  type: "text";
  uri: string;
  mimeType: string;
  text: string;
}

export interface BlobContent {
  type: "blob";
  uri: string;
  mimeType: string;
  blob: string; // base64-encoded
}

export type ResourceContent = TextContent | BlobContent;

/**
 * Read the content of a KCP knowledge unit from disk.
 * Returns TextContent for text MIME types, BlobContent (base64) for binary.
 *
 * Throws if the path escapes the manifest root or the file does not exist.
 */
export function readUnitContent(
  manifestDir: string,
  unit: KnowledgeUnit,
  resourceUri: string
): ResourceContent {
  const manifestRoot = resolve(manifestDir);
  const resolved = resolve(join(manifestRoot, unit.path));

  // Security: reject path traversal
  if (!resolved.startsWith(manifestRoot + "/") && resolved !== manifestRoot) {
    throw new Error(`Path traversal rejected for unit '${unit.id}'`);
  }

  const mime = resolveMimeType(unit);

  if (isBinaryMime(mime)) {
    const data = readFileSync(resolved);
    return {
      type: "blob",
      uri: resourceUri,
      mimeType: mime,
      blob: data.toString("base64"),
    };
  } else {
    const text = readFileSync(resolved, "utf-8");
    return {
      type: "text",
      uri: resourceUri,
      mimeType: mime,
      text,
    };
  }
}
