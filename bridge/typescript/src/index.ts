// KCP MCP Bridge — public library surface
// Import this to embed the KCP MCP server in your own application.

export { parseFile, parseDict, validateUnitPath } from "./parser.js";
export { validate } from "./validator.js";
export { createKcpServer } from "./server.js";
export {
  toProjectSlug,
  buildUnitUri,
  buildManifestUri,
  buildUnitResource,
  buildManifestResource,
  buildDescription,
  resolveMimeType,
  manifestToJson,
} from "./mapper.js";
export type {
  KnowledgeManifest,
  KnowledgeUnit,
  Relationship,
  ValidationResult,
  LicenseValue,
  IndexingValue,
} from "./model.js";
export type { KcpServerOptions, KcpMcpServer } from "./server.js";
export type { McpResourceMeta } from "./mapper.js";
