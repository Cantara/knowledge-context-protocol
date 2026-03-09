#!/usr/bin/env python3
"""
generate-nis2-log.py — Simulate a NIS2-compliant agent access log from a KCP manifest

NIS2 Directive (EU 2022/2555) Art. 21 requires security incident logging for
essential entities. This script simulates what an agent access log SHOULD contain
when `trust.audit.agent_must_log: true` and `require_trace_context: true` are set.

KCP fields mapped:
    trust.audit.agent_must_log          → whether access must be logged
    trust.audit.require_trace_context   → W3C Trace Context (traceparent) required
    access                              → access decision (granted/denied)
    auth_scope                          → required OAuth2 scope
    compliance.sensitivity              → event severity classification

Usage:
    python generate-nis2-log.py knowledge.yaml > nis2-log-sample.json
"""

import json
import sys
import random
import argparse
from datetime import datetime, timezone, timedelta

try:
    import yaml
except ImportError:
    print("Install pyyaml: pip install pyyaml", file=sys.stderr)
    sys.exit(1)

SENSITIVITY_SEVERITY = {"low": "INFO", "medium": "NOTICE", "high": "WARNING", "critical": "ALERT"}

SIMULATED_AGENTS = [
    {"agent_id": "orchestrator-agent-1", "scopes": ["patient:read", "patient:write", "audit:read"]},
    {"agent_id": "analytics-agent-2",    "scopes": ["audit:read"]},
    {"agent_id": "unknown-agent-3",      "scopes": []},  # will be denied
]


def make_traceparent() -> str:
    version = "00"
    trace_id = "%032x" % random.getrandbits(128)
    parent_id = "%016x" % random.getrandbits(64)
    flags = "01"
    return f"{version}-{trace_id}-{parent_id}-{flags}"


def simulate_access(unit: dict, agent: dict, ts: datetime, manifest_defaults: dict) -> dict:
    comp = {**manifest_defaults.get("compliance", {}), **unit.get("compliance", {})}
    trust = {**manifest_defaults.get("trust", {}), **unit.get("trust", {})}
    audit = trust.get("audit", {})

    required_scopes = set(unit.get("auth_scope", "").split()) if unit.get("auth_scope") else set()
    agent_scopes = set(agent["scopes"])
    access_decision = "granted" if required_scopes.issubset(agent_scopes) else "denied"

    sensitivity = comp.get("sensitivity", "low")
    severity = SENSITIVITY_SEVERITY.get(sensitivity, "INFO")
    if access_decision == "denied":
        severity = "WARNING"

    return {
        "timestamp": ts.isoformat(),
        "traceparent": make_traceparent() if audit.get("require_trace_context") else None,
        "agent_id": agent["agent_id"],
        "unit_id": unit["id"],
        "unit_title": unit.get("title", unit["id"]),
        "access_decision": access_decision,
        "required_scopes": sorted(required_scopes),
        "agent_scopes": sorted(agent_scopes),
        "sensitivity": sensitivity,
        "severity": severity,
        "audit_required": audit.get("agent_must_log", False),
        "nis2_relevant": sensitivity in ("high", "critical") or access_decision == "denied",
    }


def generate_log(manifest: dict, num_events: int = 12) -> dict:
    defaults = {
        "compliance": manifest.get("compliance", {}),
        "trust": manifest.get("trust", {}),
    }

    units = [u for u in manifest.get("units", []) if u.get("access") != "public"]
    events = []
    base_time = datetime.now(timezone.utc) - timedelta(hours=1)

    for i in range(num_events):
        unit = random.choice(units)
        agent = random.choice(SIMULATED_AGENTS)
        ts = base_time + timedelta(seconds=i * 17 + random.randint(0, 10))
        events.append(simulate_access(unit, agent, ts, defaults))

    nis2_events = [e for e in events if e["nis2_relevant"]]
    denied_events = [e for e in events if e["access_decision"] == "denied"]

    return {
        "log_version": "1.0",
        "generated": datetime.now(timezone.utc).isoformat(),
        "system_id": manifest.get("id", "unknown"),
        "directive": "NIS2 Art. 21 — Cybersecurity risk-management measures",
        "events": events,
        "summary": {
            "total_events": len(events),
            "nis2_relevant_events": len(nis2_events),
            "denied_access_attempts": len(denied_events),
            "trace_context_present": sum(1 for e in events if e["traceparent"]),
        },
    }


def main():
    parser = argparse.ArgumentParser(description="Simulate NIS2 agent access log from KCP manifest")
    parser.add_argument("manifest", help="Path to knowledge.yaml")
    parser.add_argument("--events", type=int, default=12, help="Number of simulated events")
    args = parser.parse_args()

    with open(args.manifest) as f:
        manifest = yaml.safe_load(f)

    log = generate_log(manifest, args.events)
    print(json.dumps(log, indent=2))


if __name__ == "__main__":
    main()
