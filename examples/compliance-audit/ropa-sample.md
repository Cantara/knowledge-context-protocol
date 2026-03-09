# GDPR Article 30 ROPA — Patient Data Platform

Generated: 2026-03-09T07:30:03.337774+00:00  
System: `patient-data-platform`  
KCP version: 0.8

## Summary

| Metric | Value |
|--------|-------|
| Processing activities (personal data) | 4 |
| High-risk activities | 2 |
| Third-country transfers | 0 |
| Agent-logged activities | 4 |

## Processing Activities

### Patient Records API (`patient-records`)

**Purpose:** Read and update patient demographic and clinical records  
**Legal basis:** Art. 6(1)(a) + Art. 9(2)(a) — Explicit consent  
**Data categories:** health, personal  
**Recipients:** Named authorised agents/services only  
**Data residency:** EU  
**Third-country transfer:** No  
**Risk level:** High  
**Security measures:**
- Agent must log: True
- Trace context required: True
- Access scope: `patient:read patient:write`

### Appointment Scheduling API (`appointment-scheduler`)

**Purpose:** Schedule and manage patient appointments  
**Legal basis:** Art. 6(1)(b) — Contract performance  
**Data categories:** personal  
**Recipients:** Named authorised agents/services only  
**Data residency:** EU  
**Third-country transfer:** No  
**Risk level:** Medium  
**Security measures:**
- Agent must log: True
- Trace context required: True
- Access scope: `patient:read`

### Anonymised Analytics Dashboard (`analytics-dashboard`)

**Purpose:** View aggregated, anonymised patient flow and outcome statistics  
**Legal basis:** Art. 6(1)(a) + Art. 9(2)(a) — Explicit consent  
**Data categories:** health, personal  
**Recipients:** Authorised internal staff  
**Data residency:** EU  
**Third-country transfer:** No  
**Risk level:** Low  
**Security measures:**
- Agent must log: True
- Trace context required: True
- Access scope: `none`

### Audit Log Export (`audit-log`)

**Purpose:** Export agent access log for NIS2 and GDPR audit purposes  
**Legal basis:** Art. 6(1)(c) — Legal obligation  
**Data categories:** health, personal  
**Recipients:** Named authorised agents/services only  
**Data residency:** EU  
**Third-country transfer:** No  
**Risk level:** High  
**Security measures:**
- Agent must log: True
- Trace context required: True
- Access scope: `audit:read`

