#!/usr/bin/env python3
"""
generate-ropa.py — Generate a GDPR Article 30 ROPA record from a KCP knowledge.yaml

Usage:
    python generate-ropa.py knowledge.yaml > ropa-output.json
    python generate-ropa.py knowledge.yaml --format markdown > ropa-output.md

Article 30 GDPR requires controllers to maintain Records of Processing Activities (ROPA)
covering: processing purpose, data categories, recipients, retention, transfers, safeguards.

KCP fields used:
    compliance.data_categories   → Article 30(1)(c) — categories of data subjects / personal data
    compliance.data_residency    → Article 30(1)(e) — transfers to third countries
    compliance.legal_basis       → Article 6 lawful basis
    compliance.sensitivity       → processing risk classification
    trust.audit.agent_must_log   → Article 30(1)(g) — security measures
    access                       → recipient categories (public / internal / restricted)
"""

import json
import sys
import argparse
from datetime import datetime, timezone

try:
    import yaml
except ImportError:
    print("Install pyyaml: pip install pyyaml", file=sys.stderr)
    sys.exit(1)

SENSITIVITY_RISK = {"low": "Low", "medium": "Medium", "high": "High", "critical": "Very High"}
LEGAL_BASIS_LABELS = {
    "explicit_consent":   "Art. 6(1)(a) + Art. 9(2)(a) — Explicit consent",
    "consent":            "Art. 6(1)(a) — Consent",
    "contract":           "Art. 6(1)(b) — Contract performance",
    "legal_obligation":   "Art. 6(1)(c) — Legal obligation",
    "vital_interests":    "Art. 6(1)(d) — Vital interests",
    "public_task":        "Art. 6(1)(e) — Public task",
    "legitimate_interests": "Art. 6(1)(f) — Legitimate interests",
}
ACCESS_RECIPIENTS = {
    "public":     "General public",
    "internal":   "Authorised internal staff",
    "restricted": "Named authorised agents/services only",
    "private":    "Controller only",
}


def build_ropa_entry(unit: dict, manifest_defaults: dict) -> dict:
    comp = {**manifest_defaults.get("compliance", {}), **unit.get("compliance", {})}
    trust = {**manifest_defaults.get("trust", {}), **unit.get("trust", {})}
    audit = trust.get("audit", {})

    data_cats = comp.get("data_categories", [])
    residency = comp.get("data_residency", [])
    legal_basis_key = comp.get("legal_basis", "")
    sensitivity = comp.get("sensitivity", "")
    access = unit.get("access", "internal")

    # Only include units that process personal data
    personal_data = any(c in data_cats for c in ("personal", "health", "financial", "biometric"))

    return {
        "unit_id": unit["id"],
        "title": unit.get("title", unit["id"]),
        "purpose": unit.get("intent", ""),
        "personal_data": personal_data,
        "data_categories": data_cats,
        "legal_basis": LEGAL_BASIS_LABELS.get(legal_basis_key, legal_basis_key or "Not specified"),
        "recipients": ACCESS_RECIPIENTS.get(access, access),
        "data_residency": residency,
        "third_country_transfer": any(r not in ("EU", "EEA") for r in residency) if residency else False,
        "risk_level": SENSITIVITY_RISK.get(sensitivity, "Unknown"),
        "security_measures": {
            "agent_must_log": audit.get("agent_must_log", False),
            "require_trace_context": audit.get("require_trace_context", False),
            "access_control": unit.get("auth_scope", ""),
        },
    }


def generate_ropa(manifest: dict) -> dict:
    defaults = {
        "compliance": manifest.get("compliance", {}),
        "trust": manifest.get("trust", {}),
    }

    entries = []
    for unit in manifest.get("units", []):
        entry = build_ropa_entry(unit, defaults)
        if entry["personal_data"]:
            entries.append(entry)

    return {
        "ropa_version": "1.0",
        "generated": datetime.now(timezone.utc).isoformat(),
        "system_id": manifest.get("id", "unknown"),
        "system_title": manifest.get("title", ""),
        "kcp_version": manifest.get("kcp_version", ""),
        "article_30_records": entries,
        "summary": {
            "total_processing_activities": len(entries),
            "high_risk_activities": sum(1 for e in entries if e["risk_level"] in ("High", "Very High")),
            "third_country_transfers": sum(1 for e in entries if e["third_country_transfer"]),
            "audit_logged_activities": sum(1 for e in entries if e["security_measures"]["agent_must_log"]),
        },
    }


def format_markdown(ropa: dict) -> str:
    lines = [
        f"# GDPR Article 30 ROPA — {ropa['system_title']}",
        f"\nGenerated: {ropa['generated']}  \nSystem: `{ropa['system_id']}`  \nKCP version: {ropa['kcp_version']}\n",
        "## Summary\n",
        f"| Metric | Value |",
        f"|--------|-------|",
        f"| Processing activities (personal data) | {ropa['summary']['total_processing_activities']} |",
        f"| High-risk activities | {ropa['summary']['high_risk_activities']} |",
        f"| Third-country transfers | {ropa['summary']['third_country_transfers']} |",
        f"| Agent-logged activities | {ropa['summary']['audit_logged_activities']} |",
        "\n## Processing Activities\n",
    ]
    for e in ropa["article_30_records"]:
        lines += [
            f"### {e['title']} (`{e['unit_id']}`)\n",
            f"**Purpose:** {e['purpose']}  ",
            f"**Legal basis:** {e['legal_basis']}  ",
            f"**Data categories:** {', '.join(e['data_categories']) or 'N/A'}  ",
            f"**Recipients:** {e['recipients']}  ",
            f"**Data residency:** {', '.join(e['data_residency']) or 'Not specified'}  ",
            f"**Third-country transfer:** {'Yes ⚠️' if e['third_country_transfer'] else 'No'}  ",
            f"**Risk level:** {e['risk_level']}  ",
            f"**Security measures:**",
            f"- Agent must log: {e['security_measures']['agent_must_log']}",
            f"- Trace context required: {e['security_measures']['require_trace_context']}",
            f"- Access scope: `{e['security_measures']['access_control'] or 'none'}`\n",
        ]
    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser(description="Generate GDPR Article 30 ROPA from KCP knowledge.yaml")
    parser.add_argument("manifest", help="Path to knowledge.yaml")
    parser.add_argument("--format", choices=["json", "markdown"], default="json")
    args = parser.parse_args()

    with open(args.manifest) as f:
        manifest = yaml.safe_load(f)

    ropa = generate_ropa(manifest)

    if args.format == "markdown":
        print(format_markdown(ropa))
    else:
        print(json.dumps(ropa, indent=2))


if __name__ == "__main__":
    main()
