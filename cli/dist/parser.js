// KCP YAML parser — TypeScript implementation
// Mirrors the Python parse()/parse_dict() and Java KcpParser.parse()/fromMap() APIs
import { readFileSync } from "node:fs";
import yaml from "js-yaml";
// --- Path safety (SPEC §12) ---
export function validateUnitPath(raw) {
    if (!raw)
        return raw;
    if (raw.startsWith("/") || raw.startsWith("\\")) {
        throw new Error(`Unit path must be relative: "${raw}"`);
    }
    const segments = raw.replace(/\\/g, "/").split("/");
    const resolved = [];
    for (const seg of segments) {
        if (seg === "..") {
            if (resolved.length === 0) {
                throw new Error(`Unit path escapes manifest root: "${raw}"`);
            }
            resolved.pop();
        }
        else if (seg !== "." && seg !== "") {
            resolved.push(seg);
        }
    }
    if (resolved.length === 0 || resolved[0] === "..") {
        throw new Error(`Unit path escapes manifest root: "${raw}"`);
    }
    return raw;
}
// --- Date normalization ---
function toDateString(value) {
    if (value === null || value === undefined)
        return undefined;
    if (value instanceof Date) {
        return value.toISOString().slice(0, 10);
    }
    const s = String(value);
    if (/^\d{4}-\d{2}-\d{2}/.test(s))
        return s.slice(0, 10);
    return s;
}
function asStringArray(value) {
    if (!value)
        return [];
    if (Array.isArray(value))
        return value.map(String);
    return [String(value)];
}
function asLicenseOrIndexing(value) {
    if (value === null || value === undefined)
        return undefined;
    if (typeof value === "string")
        return value;
    if (typeof value === "object" && !Array.isArray(value))
        return value;
    return String(value);
}
// --- Parse a raw YAML map into a KnowledgeUnit ---
function parseUnit(raw) {
    return {
        id: String(raw["id"] ?? ""),
        path: validateUnitPath(String(raw["path"] ?? "")),
        intent: String(raw["intent"] ?? ""),
        scope: String(raw["scope"] ?? "global"),
        audience: asStringArray(raw["audience"]),
        kind: raw["kind"] !== undefined ? String(raw["kind"]) : undefined,
        format: raw["format"] !== undefined ? String(raw["format"]) : undefined,
        content_type: raw["content_type"] !== undefined
            ? String(raw["content_type"])
            : undefined,
        language: raw["language"] !== undefined ? String(raw["language"]) : undefined,
        license: asLicenseOrIndexing(raw["license"]),
        validated: toDateString(raw["validated"]),
        update_frequency: raw["update_frequency"] !== undefined
            ? String(raw["update_frequency"])
            : undefined,
        indexing: asLicenseOrIndexing(raw["indexing"]),
        depends_on: asStringArray(raw["depends_on"]),
        supersedes: raw["supersedes"] !== undefined ? String(raw["supersedes"]) : undefined,
        triggers: asStringArray(raw["triggers"]),
        hints: raw["hints"] !== undefined && typeof raw["hints"] === "object" && !Array.isArray(raw["hints"])
            ? raw["hints"]
            : undefined,
        access: raw["access"] !== undefined ? String(raw["access"]) : undefined,
        auth_scope: raw["auth_scope"] !== undefined ? String(raw["auth_scope"]) : undefined,
        sensitivity: raw["sensitivity"] !== undefined ? String(raw["sensitivity"]) : undefined,
        deprecated: raw["deprecated"] !== undefined ? Boolean(raw["deprecated"]) : undefined,
        payment: raw["payment"] !== undefined && typeof raw["payment"] === "object" && !Array.isArray(raw["payment"])
            ? raw["payment"]
            : undefined,
        rate_limits: parseRateLimits(raw["rate_limits"]),
        delegation: parseDelegation(raw["delegation"]),
        compliance: parseCompliance(raw["compliance"]),
        external_depends_on: (raw["external_depends_on"] ?? []).map(parseExternalDependency),
        requires_capabilities: raw["requires_capabilities"] !== undefined
            ? raw["requires_capabilities"]
            : undefined,
        freshness_policy: parseFreshnessPolicy(raw["freshness_policy"]),
        visibility: parseVisibility(raw["visibility"]),
        authority: parseAuthority(raw["authority"]),
        discovery: parseDiscovery(raw["discovery"]),
    };
}
// --- Parse a raw YAML map into a Relationship ---
function parseRelationship(raw) {
    return {
        from_id: String(raw["from"] ?? ""),
        to_id: String(raw["to"] ?? ""),
        type: String(raw["type"] ?? "context"),
    };
}
// --- Trust and Auth parsing ---
function parseTrust(raw) {
    if (!raw || typeof raw !== "object" || Array.isArray(raw))
        return undefined;
    const data = raw;
    let provenance;
    const provData = data["provenance"];
    if (provData && typeof provData === "object") {
        provenance = {
            publisher: provData["publisher"] !== undefined ? String(provData["publisher"]) : undefined,
            publisher_url: provData["publisher_url"] !== undefined ? String(provData["publisher_url"]) : undefined,
            contact: provData["contact"] !== undefined ? String(provData["contact"]) : undefined,
        };
    }
    let audit;
    const auditData = data["audit"];
    if (auditData && typeof auditData === "object") {
        audit = {
            agent_must_log: auditData["agent_must_log"] !== undefined ? Boolean(auditData["agent_must_log"]) : undefined,
            require_trace_context: auditData["require_trace_context"] !== undefined ? Boolean(auditData["require_trace_context"]) : undefined,
        };
    }
    return { provenance, audit };
}
function parseAuth(raw) {
    if (!raw || typeof raw !== "object" || Array.isArray(raw))
        return undefined;
    const data = raw;
    const rawMethods = data["methods"] ?? [];
    const methods = rawMethods.map((m) => ({
        type: String(m["type"] ?? ""),
        issuer: m["issuer"] !== undefined ? String(m["issuer"]) : undefined,
        scopes: asStringArray(m["scopes"]),
        header: m["header"] !== undefined ? String(m["header"]) : undefined,
        registration_url: m["registration_url"] !== undefined ? String(m["registration_url"]) : undefined,
    }));
    return { methods };
}
function parseDelegation(raw) {
    if (!raw || typeof raw !== "object" || Array.isArray(raw))
        return undefined;
    const data = raw;
    return {
        max_depth: data["max_depth"] !== undefined ? Number(data["max_depth"]) : undefined,
        require_capability_attenuation: data["require_capability_attenuation"] !== undefined
            ? Boolean(data["require_capability_attenuation"])
            : undefined,
        audit_chain: data["audit_chain"] !== undefined ? Boolean(data["audit_chain"]) : undefined,
        human_in_the_loop: (() => {
            const raw = data["human_in_the_loop"];
            if (raw === undefined || raw === null)
                return undefined;
            if (typeof raw === "object" && !Array.isArray(raw)) {
                const h = raw;
                return {
                    required: h["required"] !== undefined ? Boolean(h["required"]) : undefined,
                    approval_mechanism: h["approval_mechanism"] !== undefined ? String(h["approval_mechanism"]) : undefined,
                    docs_url: h["docs_url"] !== undefined ? String(h["docs_url"]) : undefined,
                };
            }
            return undefined;
        })(),
    };
}
function parseCompliance(raw) {
    if (!raw || typeof raw !== "object" || Array.isArray(raw))
        return undefined;
    const data = raw;
    return {
        data_residency: data["data_residency"] !== undefined
            ? asStringArray(data["data_residency"])
            : undefined,
        sensitivity: data["sensitivity"] !== undefined ? String(data["sensitivity"]) : undefined,
        regulations: data["regulations"] !== undefined
            ? asStringArray(data["regulations"])
            : undefined,
        restrictions: data["restrictions"] !== undefined
            ? asStringArray(data["restrictions"])
            : undefined,
    };
}
function parseRateLimits(raw) {
    if (!raw || typeof raw !== "object" || Array.isArray(raw))
        return undefined;
    const data = raw;
    const def = data["default"];
    if (!def || typeof def !== "object" || Array.isArray(def))
        return {};
    const d = def;
    return {
        default: {
            requests_per_minute: d["requests_per_minute"] !== undefined ? Number(d["requests_per_minute"]) : undefined,
            requests_per_day: d["requests_per_day"] !== undefined ? Number(d["requests_per_day"]) : undefined,
        },
    };
}
function parseFreshnessPolicy(raw) {
    if (!raw || typeof raw !== "object" || Array.isArray(raw))
        return undefined;
    const d = raw;
    return {
        max_age_days: d["max_age_days"] !== undefined ? Number(d["max_age_days"]) : undefined,
        on_stale: d["on_stale"] !== undefined ? String(d["on_stale"]) : undefined,
        review_contact: d["review_contact"] !== undefined ? String(d["review_contact"]) : undefined,
    };
}
function parseAuthority(raw) {
    if (!raw || typeof raw !== "object" || Array.isArray(raw))
        return undefined;
    const d = raw;
    const result = {};
    for (const key of Object.keys(d)) {
        if (d[key] !== undefined) {
            result[key] = String(d[key]);
        }
    }
    return result;
}
function parseVisibility(raw) {
    if (!raw || typeof raw !== "object" || Array.isArray(raw))
        return undefined;
    const d = raw;
    const conditions = (() => {
        const rawConds = d["conditions"];
        if (!Array.isArray(rawConds))
            return undefined;
        return rawConds.map((c) => {
            const when = c["when"] ?? {};
            const then = c["then"] ?? {};
            const envRaw = when["environment"];
            const roleRaw = when["agent_role"];
            return {
                when: {
                    environment: envRaw !== undefined
                        ? (Array.isArray(envRaw) ? envRaw.map(String) : String(envRaw))
                        : undefined,
                    agent_role: roleRaw !== undefined
                        ? (Array.isArray(roleRaw) ? roleRaw.map(String) : String(roleRaw))
                        : undefined,
                },
                then: {
                    sensitivity: then["sensitivity"] !== undefined ? String(then["sensitivity"]) : undefined,
                    requires_auth: then["requires_auth"] !== undefined ? Boolean(then["requires_auth"]) : undefined,
                    authority: parseAuthority(then["authority"]),
                },
            };
        });
    })();
    return {
        default: d["default"] !== undefined ? String(d["default"]) : undefined,
        conditions,
    };
}
function parseDiscovery(raw) {
    if (!raw || typeof raw !== "object" || Array.isArray(raw))
        return undefined;
    const d = raw;
    return {
        verification_status: d["verification_status"] !== undefined ? String(d["verification_status"]) : undefined,
        source: d["source"] !== undefined ? String(d["source"]) : undefined,
        observed_at: d["observed_at"] !== undefined ? String(d["observed_at"]) : undefined,
        verified_at: d["verified_at"] !== undefined ? String(d["verified_at"]) : undefined,
        confidence: d["confidence"] !== undefined ? Number(d["confidence"]) : undefined,
        contradicted_by: d["contradicted_by"] !== undefined ? String(d["contradicted_by"]) : undefined,
    };
}
// --- Federation parsing (§3.6) ---
function parseExternalDependency(raw) {
    return {
        manifest: String(raw["manifest"] ?? ""),
        unit: String(raw["unit"] ?? ""),
        on_failure: raw["on_failure"] !== undefined ? String(raw["on_failure"]) : "skip",
    };
}
function parseManifestRef(raw) {
    return {
        id: String(raw["id"] ?? ""),
        url: String(raw["url"] ?? ""),
        label: raw["label"] !== undefined ? String(raw["label"]) : undefined,
        relationship: raw["relationship"] !== undefined ? String(raw["relationship"]) : undefined,
        auth: parseAuth(raw["auth"]),
        update_frequency: raw["update_frequency"] !== undefined ? String(raw["update_frequency"]) : undefined,
        local_mirror: raw["local_mirror"] !== undefined ? String(raw["local_mirror"]) : undefined,
        version_pin: raw["version_pin"] !== undefined ? String(raw["version_pin"]) : undefined,
        version_policy: raw["version_policy"] !== undefined ? String(raw["version_policy"]) : undefined,
    };
}
function parseExternalRelationship(raw) {
    return {
        from_manifest: raw["from_manifest"] !== undefined ? String(raw["from_manifest"]) : undefined,
        from_unit: String(raw["from_unit"] ?? ""),
        to_manifest: raw["to_manifest"] !== undefined ? String(raw["to_manifest"]) : undefined,
        to_unit: String(raw["to_unit"] ?? ""),
        type: String(raw["type"] ?? ""),
    };
}
// --- Public API ---
/**
 * Parse a plain JavaScript object (from YAML.load output) into a KnowledgeManifest.
 * Mirrors Python's parse_dict() and Java's KcpParser.fromMap().
 */
export function parseDict(data) {
    const rawUnits = data["units"] ?? [];
    const rawRels = data["relationships"] ?? [];
    const rawManifests = data["manifests"] ?? [];
    const rawExtRels = data["external_relationships"] ?? [];
    return {
        project: String(data["project"] ?? ""),
        version: String(data["version"] ?? ""),
        kcp_version: data["kcp_version"] !== undefined
            ? String(data["kcp_version"])
            : undefined,
        updated: toDateString(data["updated"]),
        language: data["language"] !== undefined ? String(data["language"]) : undefined,
        license: asLicenseOrIndexing(data["license"]),
        indexing: asLicenseOrIndexing(data["indexing"]),
        hints: data["hints"] !== undefined && typeof data["hints"] === "object" && !Array.isArray(data["hints"])
            ? data["hints"]
            : undefined,
        trust: parseTrust(data["trust"]),
        auth: parseAuth(data["auth"]),
        delegation: parseDelegation(data["delegation"]),
        compliance: parseCompliance(data["compliance"]),
        payment: data["payment"] !== undefined && typeof data["payment"] === "object" && !Array.isArray(data["payment"])
            ? data["payment"]
            : undefined,
        rate_limits: parseRateLimits(data["rate_limits"]),
        units: rawUnits.map(parseUnit),
        relationships: rawRels.map(parseRelationship),
        manifests: rawManifests.map(parseManifestRef),
        external_relationships: rawExtRels.map(parseExternalRelationship),
        freshness_policy: parseFreshnessPolicy(data["freshness_policy"]),
        visibility: parseVisibility(data["visibility"]),
        authority: parseAuthority(data["authority"]),
        discovery: parseDiscovery(data["discovery"]),
    };
}
/**
 * Parse a knowledge.yaml file from disk.
 * Mirrors Python's parse(path) and Java's KcpParser.parse(Path).
 *
 * Uses YAML safe load — no arbitrary type instantiation (SPEC §12).
 */
export function parseFile(filePath) {
    const raw = readFileSync(filePath, "utf-8");
    const data = yaml.load(raw, { schema: yaml.DEFAULT_SCHEMA });
    if (!data || typeof data !== "object" || Array.isArray(data)) {
        throw new Error(`Invalid YAML structure in: ${filePath}`);
    }
    return parseDict(data);
}
//# sourceMappingURL=parser.js.map