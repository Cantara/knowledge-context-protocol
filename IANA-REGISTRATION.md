# IANA Well-Known URI Registration: `kcp.json`

This document contains the registration request for the `kcp.json` well-known URI suffix,
following [RFC 8615](https://datatracker.ietf.org/doc/html/rfc8615).

---

## Registration Template

```
URI suffix:               kcp.json

Change controller:        Cantara
                          https://github.com/Cantara

Specification document:   Knowledge Context Protocol (KCP) Specification, §1.4
                          https://github.com/Cantara/knowledge-context-protocol/blob/main/SPEC.md

Status:                   Provisional

Related information:      The KCP manifest format (knowledge.yaml) is defined in the
                          same specification document. The well-known URI provides
                          HTTP-based discovery of the manifest location on a given origin.
```

---

## Submission

Send to: <wellknown-uri-review@ietf.org>
Subject: `[Well-Known URI Registration] kcp.json`

---

## Background

The Knowledge Context Protocol (KCP) defines a structured manifest format (`knowledge.yaml`)
that describes the knowledge resources in a project — their intent, dependencies, freshness,
access requirements, and audience — in a way that AI agents can navigate without loading
everything at once.

`/.well-known/kcp.json` provides a standard HTTP discovery endpoint for these manifests.
An agent or crawler that encounters an unfamiliar origin can probe `/.well-known/kcp.json`
to determine whether a KCP manifest exists and where to find it, without requiring prior
knowledge of the site's directory structure.

**Relationship to existing well-known URIs:**

- `agent-card.json` (A2A, registered 2025-08-01) describes what an *agent* can do.
- `kcp.json` describes what *knowledge resources* are available to agents.

These are complementary: A2A answers "what can this agent do?"; KCP answers "what knowledge
can this agent access, under what conditions, and how fresh is it?"

For a worked example showing how `/.well-known/agent.json` and `/.well-known/kcp.json` compose
in a multi-agent system (clinical research domain with escalating access, PII, and
human-in-the-loop), see [`examples/a2a-agent-card/`](./examples/a2a-agent-card/).

**Response format:**

A GET to `/.well-known/kcp.json` returns `application/json` with at minimum:

```json
{
  "kcp_version": "0.10",
  "manifest": "/knowledge.yaml"
}
```

Full field definitions are in §1.4 of the specification.

---

## Status

- [x] Submitted to wellknown-uri-review@ietf.org (2026-03-02)
- [ ] Expert review complete
- [ ] Registered in IANA Well-Known URIs registry
