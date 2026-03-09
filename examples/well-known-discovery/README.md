# Well-Known Discovery Example

KCP supports HTTP-based discovery via `/.well-known/kcp.json` (see SPEC.md section 1.3).

## How it works

An agent performing HTTP-based discovery on a live site:

1. Fetches `https://example.org/.well-known/kcp.json`
2. Reads the `manifest` URL from the response
3. Fetches the full `knowledge.yaml` manifest from that URL

## Sample `kcp.json`

```json
{
  "kcp_version": "0.8",
  "manifest": "/knowledge.yaml",
  "title": "Example Knowledge Base",
  "description": "Architecture decisions, API reference, and onboarding guides.",
  "spec": "https://github.com/Cantara/knowledge-context-protocol"
}
```

## Required fields

| Field | Type | Description |
|-------|------|-------------|
| `manifest` | string | Absolute URL or root-relative path to the `knowledge.yaml` manifest. REQUIRED. |

## Optional fields

| Field | Type | Description |
|-------|------|-------------|
| `kcp_version` | string | Version of the KCP specification. |
| `title` | string | Human-readable name of the project or knowledge base. |
| `description` | string | Brief summary of the knowledge available. |
| `spec` | string | URL of the KCP specification document. |

## Discovery priority

When multiple discovery paths are available, agents SHOULD prefer:

1. `/.well-known/kcp.json` (HTTP-based discovery)
2. Root `knowledge.yaml` (file-based discovery, section 1.1)
3. `llms.txt` `knowledge:` metadata line (section 1.2)
