# KCP Compliance Audit Example

Demonstrates how a KCP `knowledge.yaml` manifest can drive automated compliance reporting
for **GDPR Article 30** and **NIS2 Art. 21**.

## Why this matters

Compliance teams spend weeks manually auditing what systems process personal data, under
what legal basis, and with what safeguards. A KCP manifest already contains this information
as machine-readable metadata — making automated, always-current audit reports possible.

## KCP fields used

| KCP field | Compliance mapping |
|-----------|-------------------|
| `compliance.data_categories` | GDPR Art. 30(1)(c) — categories of personal data |
| `compliance.data_residency` | GDPR Art. 30(1)(e) — third-country transfers |
| `compliance.legal_basis` | GDPR Art. 6 — lawful basis for processing |
| `compliance.sensitivity` | Risk classification / NIS2 severity level |
| `trust.audit.agent_must_log` | GDPR Art. 30(1)(g) + NIS2 Art. 21 — security measures |
| `trust.audit.require_trace_context` | W3C Trace Context — auditability of agent actions |
| `access` / `auth_scope` | Recipient categories, access control evidence |

## Files

| File | Purpose |
|------|---------|
| `knowledge.yaml` | Sample KCP manifest for a healthcare platform |
| `generate-ropa.py` | Generates GDPR Article 30 ROPA (JSON or Markdown) |
| `generate-nis2-log.py` | Simulates a NIS2-compliant agent access log |
| `ropa-sample.json` | Sample ROPA output (JSON) |
| `ropa-sample.md` | Sample ROPA output (Markdown) |
| `nis2-log-sample.json` | Sample NIS2 access log (JSON) |

## Usage

```bash
# Install dependency
pip install pyyaml

# Generate GDPR Article 30 ROPA
python3 generate-ropa.py knowledge.yaml                        # JSON
python3 generate-ropa.py knowledge.yaml --format markdown      # Markdown

# Simulate NIS2 agent access log
python3 generate-nis2-log.py knowledge.yaml
python3 generate-nis2-log.py knowledge.yaml --events 50
```

## Sample ROPA output (excerpt)

```json
{
  "system_id": "patient-data-platform",
  "article_30_records": [
    {
      "unit_id": "patient-records",
      "purpose": "Read and update patient demographic and clinical records",
      "legal_basis": "Art. 6(1)(a) + Art. 9(2)(a) — Explicit consent",
      "data_categories": ["health", "personal"],
      "recipients": "Named authorised agents/services only",
      "data_residency": ["EU"],
      "third_country_transfer": false,
      "risk_level": "High",
      "security_measures": {
        "agent_must_log": true,
        "require_trace_context": true,
        "access_scope": "patient:read patient:write"
      }
    }
  ]
}
```

## Integration with CI/CD

```yaml
# .github/workflows/compliance.yml
- name: Generate ROPA
  run: |
    pip install pyyaml
    python3 examples/compliance-audit/generate-ropa.py knowledge.yaml \
      --format markdown > compliance-report.md
- name: Upload ROPA
  uses: actions/upload-artifact@v4
  with:
    name: gdpr-ropa
    path: compliance-report.md
```

This produces an up-to-date ROPA on every commit — so your audit trail is never stale.
