/* ==========================================================================
   KCP Simulator Player — Pre-recorded terminal animation
   Replays captured output from the 5 KCP Java simulators.
   Zero infrastructure: pure HTML/CSS/JS, no server, no WASM.
   ========================================================================== */

// ---------------------------------------------------------------------------
// Tag detection: classify each raw output line
// ---------------------------------------------------------------------------
function tagLine(text) {
  if (/ADVISORY.VIOLATION/i.test(text)) return 'advisory';
  if (/VIOLATION/i.test(text)) return 'violation';
  if (/\[KCP\]/.test(text)) return 'kcp';
  if (/\[A2A\]/.test(text)) return 'a2a';
  if (/\[AUDIT\]/.test(text)) return 'audit';
  if (/\[HITL\]/.test(text)) return 'hitl';
  if (/LOADED|GRANTED|DELEGATED/.test(text)) return 'success';
  if (/BLOCKED|DENIED/.test(text)) return 'blocked';
  if (/^===|^---|^──|^Phase|^\[WARN\]/.test(text)) return 'phase';
  if (/^\[INFO\]/.test(text)) return 'info';
  return 'info';
}

// ---------------------------------------------------------------------------
// Delay rules (ms)
// ---------------------------------------------------------------------------
function delayFor(text, tag) {
  if (tag === 'phase') return 400;
  if (text.trim() === '') return 100;
  if (tag === 'blocked' || tag === 'violation') return 600;
  return 60;
}

// ---------------------------------------------------------------------------
// Phase detection: extract phase name from header lines
// ---------------------------------------------------------------------------
function detectPhase(text) {
  // "── Phase 1: Agent Discovery (A2A Layer) ────"
  var m = text.match(/Phase\s*(\d+)[:\s]+(.+?)(?:\s*[─—-]+\s*$|\s*$)/);
  if (m) return 'Phase ' + m[1] + ': ' + m[2].replace(/[─—\-]+$/,'').trim();
  // "=== SCENARIO 3: Financial AML..." or "=== SIMULATION SUMMARY ==="
  m = text.match(/^===\s*(.+?)\s*={0,}$/);
  if (m) return m[1];
  // "--- PoliteAgent (self-throttling) ---"
  m = text.match(/^---\s*(.+?)\s*-{0,}$/);
  if (m) return m[1];
  // "── Setup: Agent Discovery ─────"
  m = text.match(/^──\s*(.+?)(?:\s*[─—]+\s*$|\s*$)/);
  if (m) return m[1];
  return null;
}

// ---------------------------------------------------------------------------
// Parse raw output text into structured lines array
// ---------------------------------------------------------------------------
function parseOutput(raw) {
  var lines = raw.split('\n');
  var result = [];
  var currentPhase = null;
  for (var i = 0; i < lines.length; i++) {
    var text = lines[i];
    var phase = detectPhase(text);
    if (phase) currentPhase = phase;
    var tag = tagLine(text);
    result.push({
      text: text,
      tag: tag,
      delay: delayFor(text, tag),
      phase: currentPhase
    });
  }
  return result;
}

// ---------------------------------------------------------------------------
// Raw captured output from each simulator
// ---------------------------------------------------------------------------
var RAW_S1 = [
"=== SCENARIO 1: Smart Energy Metering ===",
"Domain: Utility Grid Operations | KCP v0.6 | A2A Agent Card v1.0.0",
"Agents: GridOrchestratorAgent -> EnergyMeteringAgent",
"",
"── Phase 1: Agent Discovery (A2A Layer) ────────────────────────",
"",
"[A2A]  GridOrchestratorAgent reads agent-card.json",
"[A2A]  Discovered: \"Energy Metering Agent\" at https://grid.example.com/agent",
"[A2A]  Skills: tariff-lookup, consumption-analysis, grid-diagnostics",
"[A2A]  Security: OAuth2 (client_credentials) at https://auth.grid.example.com/oauth2/token",
"[A2A]  Knowledge manifest: /.well-known/kcp.json",
"",
"── Phase 2: Knowledge Discovery (KCP Layer) ────────────────────",
"",
"[KCP]  Energy Metering Agent reads knowledge.yaml (KCP v0.8)",
"[KCP]  Project: energy-metering-agent v1.0.0",
"[KCP]  Trust: publisher=Example Grid Utilities, audit=required, trace_context=required",
"[KCP]  Auth: oauth2 (client_credentials) + none (public fallback)",
"[KCP]  Delegation: max_depth=2, capability_attenuation=required, audit_chain=required",
"[KCP]  Units loaded:",
"[KCP]    1. tariff-schedule        access=public         sensitivity=public       HITL=no",
"[KCP]    2. meter-readings         access=authenticated  sensitivity=internal     HITL=no",
"[KCP]    3. billing-history        access=authenticated  sensitivity=internal     HITL=no",
"[KCP]    4. smart-meter-raw        access=restricted     sensitivity=restricted   HITL=required",
"",
"── Phase 3: Authentication (A2A + OAuth2) ──────────────────────",
"",
"[A2A]  GridOrchestratorAgent requests OAuth2 token (client_credentials flow)",
"[A2A]  Scopes requested: read:meter, grid-engineer, read:billing, read:tariff",
"[A2A]  Token issued: eyJ***sim (expires in 3600s)",
"",
"── Phase 4: Knowledge Access (Escalating Access Levels) ────────",
"",
"[U1]   Requesting unit: tariff-schedule",
"[KCP]  Matched unit: tariff-schedule | access: public | sensitivity: public",
"[KCP]  Access: public -> no credential required",
"[KCP]  Result: LOADED",
"[KCP]  Content: Peak rate: 1.45 NOK/kWh (06:00-21:00). Off-peak: 0.38 NOK...",
"[AUDIT] trace: 00-a39c2acf82d04bb280db27e43d771c36-da944acbc1474bf2-01",
"",
"[U2]   Requesting unit: meter-readings",
"[KCP]  Matched unit: meter-readings | access: authenticated | sensitivity: internal",
"[KCP]  Access: authenticated -> checking credential",
"[KCP]  Token valid: yes | Scope 'read:meter': yes",
"[KCP]  Result: LOADED",
"[KCP]  Content: Household H-7742: January 2026 consumption 1,247 kWh (avg...",
"[AUDIT] trace: 00-09e2bc85b7394e27a44c76d3f9e967c3-fe46f1fb800145e0-01",
"",
"[U3]   Requesting unit: billing-history",
"[KCP]  Matched unit: billing-history | access: authenticated | sensitivity: internal",
"[KCP]  Access: authenticated -> checking credential",
"[KCP]  Token valid: yes | Scope 'read:billing': yes",
"[KCP]  Result: LOADED",
"[KCP]  Content: Invoice #2026-01-7742: 1,247 kWh x blended rate = 1,842.5...",
"[AUDIT] trace: 00-26a695155a6849e9a681edd3da5f5db9-3278059cd86542ac-01",
"",
"[U4]   Requesting unit: smart-meter-raw",
"[KCP]  Matched unit: smart-meter-raw | access: restricted | sensitivity: restricted",
"[KCP]  Access: restricted | auth_scope: grid-engineer | sensitivity: restricted",
"[KCP]  Token valid: yes | Scope 'grid-engineer': yes",
"[KCP]  Delegation: max_depth=1 (unit override)",
"[KCP]  Human-in-the-loop: REQUIRED (approval_mechanism: oauth_consent)",
"[HITL] Approval required for unit 'smart-meter-raw' (sensitivity: restricted, PII data)",
"[HITL] Waiting for approval... (use --auto-approve to skip in CI)",
"[HITL] Approved by: researcher@example.com",
"[KCP]  Result: LOADED (after human approval)",
"[KCP]  Content: Meter SM-7742-A: 15s telemetry 2026-01-15T14:30:00Z. Volt...",
"[AUDIT] trace: 00-80b5bb2038724c1298ca9495cb295976-4d5fdeee46744186-01",
"[AUDIT] unit: smart-meter-raw | human_approval: researcher@example.com",
"",
"=== SIMULATION SUMMARY ===",
"  Units loaded successfully:  4",
"  Access denied:              0",
"  HITL approvals:             1",
"  Audit entries:              4",
"",
"  The A2A Agent Card handled agent discovery and OAuth2 authentication.",
"  The KCP manifest enforced per-unit access at 4 escalating levels.",
"  Together: informed, auditable, policy-aware knowledge access."
].join('\n');

var RAW_S2 = [
"=== SCENARIO 2: Legal Document Review (3-Hop Delegation) ===",
"Domain: Law Firm Document Review | KCP v0.6 | A2A Agent Card v1.0.0",
"Agents: CaseOrchestratorAgent -> LegalResearchAgent -> ExternalCaseLawAgent",
"",
"── Setup: Agent Discovery + KCP Discovery + Authentication ─────",
"",
"[A2A]  CaseOrchestratorAgent reads agent-card.json",
"[A2A]  Discovered: \"Legal Research Agent\" at https://legal.example.com/agent",
"[A2A]  Skills: precedent-search, case-analysis",
"[A2A]  Knowledge manifest: /.well-known/kcp.json",
"",
"[KCP]  LegalResearchAgent reads knowledge.yaml (KCP v0.8)",
"[KCP]  Project: legal-research-agent v1.0.0",
"[KCP]  Trust: publisher=Example Law LLP, audit=required, trace_context=required",
"[KCP]  Auth: oauth2 (client_credentials) + none (public fallback)",
"[KCP]  Delegation: max_depth=3, capability_attenuation=required, audit_chain=required",
"[KCP]  Units loaded:",
"[KCP]    1. public-precedents         access=public         sensitivity=public         HITL=no       max_depth=3",
"[KCP]    2. case-briefs               access=authenticated  sensitivity=internal       HITL=no       max_depth=3",
"[KCP]    3. client-communications     access=restricted     sensitivity=confidential   HITL=required max_depth=2",
"[KCP]    4. sealed-records            access=restricted     sensitivity=restricted     HITL=no       max_depth=0",
"",
"[A2A]  Token issued to CaseOrchestratorAgent: eyJ***sim",
"",
"── Phase 1: Direct Access (CaseOrchestrator -> LegalResearch, depth=1) ─",
"",
"[1a]   Requesting unit: public-precedents",
"[KCP]  Matched unit: public-precedents | access: public | sensitivity: public",
"[KCP]  Access: public -> no credential required",
"[KCP]  Result: LOADED",
"[KCP]  Content: Rt. 2023-1042: Data breach liability established under GD...",
"[AUDIT] trace: 00-6d39a5269dd2400a9b431209e68aeebd-6cce1d9e9152437c-01",
"",
"[1b]   Requesting unit: case-briefs",
"[KCP]  Matched unit: case-briefs | access: authenticated | sensitivity: internal",
"[KCP]  Access: authenticated -> checking credential",
"[KCP]  Token valid: yes | Scope 'read:case': yes",
"[KCP]  Result: LOADED",
"[KCP]  Content: Case 2025-CV-0042 (NovaCorp v. Meridian): Breach of SaaS ...",
"[AUDIT] trace: 00-3007322595164d1ca594a5a5d79f39ee-42758b55f62e4839-01",
"",
"[1c]   Requesting unit: sealed-records (depth=1, orchestrator is delegatee)",
"[KCP]  Matched unit: sealed-records | access: restricted | sensitivity: restricted",
"[KCP]  Delegation check: max_depth=0 for this unit",
"[KCP]  Result: DENIED (Unit has max_depth=0: delegation is not permitted)",
"[AUDIT] trace: 00-179e5eec9875405c8f7bcaee63fe458a-15c5cac5828a417d-01",
"[AUDIT] VIOLATION: delegation attempt on no-delegation unit 'sealed-records'",
"",
"── Phase 2: Delegation Chain (LegalResearch -> ExternalCaseLaw, depth=2) ─",
"",
"[2a]   Delegating 'public-precedents' to ExternalCaseLawAgent (depth=2)",
"[KCP]  Public unit: no delegation constraint applies",
"[KCP]  Result: DELEGATED (public access, depth irrelevant)",
"[AUDIT] trace: 00-7fa479c95737406b816aa640f7b7f1d1-67f000e72f754fcb-01",
"",
"[2b]   Delegating 'case-briefs' to ExternalCaseLawAgent with scope 'read:case'",
"[KCP]  Checking capability attenuation (require_capability_attenuation=true)",
"[KCP]  Result: BLOCKED (Capability attenuation required: delegated scope must be narrower than 'read:case')",
"[AUDIT] trace: 00-c0e9c6ba42424df789aecd1594ef39ae-fed487a8194249de-01",
"[AUDIT] ATTENUATION VIOLATION: same-scope delegation blocked",
"",
"[2b-fix]   Re-delegating 'case-briefs' with narrowed scope 'read:case:external-summary'",
"[KCP]  Narrowed scope: 'read:case' -> 'read:case:external-summary'",
"[KCP]  Result: DELEGATED (attenuated scope, depth=2)",
"[AUDIT] trace: 00-7183c0ae79f247798ef94936a574eb01-7583b97934134521-01",
"",
"[2c]   Delegating 'client-communications' to ExternalCaseLawAgent (depth=2)",
"[KCP]  Delegation depth 2 <= max_depth=2: OK",
"[KCP]  Capability attenuation: 'attorney' -> 'attorney:read-only': OK",
"[KCP]  Human-in-the-loop: REQUIRED (approval_mechanism: custom)",
"[HITL] Approval required for unit 'client-communications' (sensitivity: confidential, PII data)",
"[HITL] Waiting for approval... (use --auto-approve to skip in CI)",
"[HITL] Approved by: researcher@example.com",
"[KCP]  Result: DELEGATED (after human approval, depth=2)",
"[AUDIT] trace: 00-a51923551502462395b7716c0d9c9489-a57dfec39f774356-01",
"[AUDIT] unit: client-communications | human_approval: researcher@example.com | delegated_to: ExternalCaseLawAgent | depth: 2",
"",
"[2d]   Hypothetical: delegate 'client-communications' to depth=3",
"[KCP]  Result: BLOCKED (Depth 3 exceeds max_depth=2)",
"[AUDIT] trace: 00-237a555ff6fd42f08de4862f592fafff-32ccd0218a774dd4-01",
"[AUDIT] DEPTH VIOLATION: depth=3 exceeds max_depth=2 for 'client-communications'",
"",
"=== SIMULATION SUMMARY ===",
"  Units loaded successfully:  5",
"  Access denied (policy):     1",
"  Access denied (delegation): 2",
"  HITL approvals:             1",
"  Audit entries:              8",
"",
"  Key delegation behaviours demonstrated:",
"    - max_depth=0 on sealed-records: absolute no-delegation",
"    - Capability attenuation: equal scope blocked, narrowed scope allowed",
"    - Depth counting: owner=0, first delegatee=1, second=2",
"    - HITL at depth=2 for client-communications (exactly at max_depth)"
].join('\n');

var RAW_S3 = [
"=== SCENARIO 3: Financial AML Intelligence (Adversarial) ===",
"Domain: Anti-Money-Laundering Compliance | KCP v0.8 | A2A Agent Card v1.0.0",
"Agents: ComplianceOrchestrator, TransactionIntelligence, SanctionsScreening, MLRiskScoring, RogueAgent",
"",
"── Setup: Discovery + Authentication ───────────────────────────",
"",
"[A2A]  ComplianceOrchestratorAgent reads agent-card.json",
"[A2A]  Discovered: \"Transaction Intelligence Agent\" at https://aml.example.com/agent",
"[A2A]  Skills: sanctions-screening, transaction-analysis, sar-preparation",
"",
"[KCP]  TransactionIntelligenceAgent reads knowledge.yaml (KCP v0.8)",
"[KCP]  Project: transaction-intelligence-agent v1.0.0",
"[KCP]  Trust: publisher=Example Bank Compliance, audit=required",
"[KCP]  Auth: oauth2 (client_credentials) + none (public fallback)",
"[KCP]  Delegation: max_depth=3, capability_attenuation=required, audit_chain=required",
"[KCP]  Units loaded:",
"[KCP]    1. sanctions-lists        access=public         sensitivity=public         HITL=no       depth=3 regs=none",
"[KCP]    2. transaction-patterns   access=authenticated  sensitivity=internal       HITL=no       depth=3 regs=none",
"[KCP]    3. customer-profiles      access=restricted     sensitivity=confidential   HITL=required depth=2 regs=GDPR,AML5D",
"[KCP]    4. sar-drafts             access=restricted     sensitivity=restricted     HITL=required depth=1 regs=AML5D,FATF",
"[KCP]    5. raw-wire-transfers     access=restricted     sensitivity=restricted     HITL=no       depth=0 regs=GDPR,AML5D,NIS2",
"",
"[A2A]  Token issued to ComplianceOrchestratorAgent: eyJ***sim",
"",
"── Phase 1: Happy Path (public + authenticated access) ─────────",
"",
"[P1-a]   Requesting unit: sanctions-lists",
"[KCP]  Matched unit: sanctions-lists | access: public | sensitivity: public",
"[KCP]  Access: public -> no credential required",
"[KCP]  Result: LOADED",
"[KCP]  Content: OFAC SDN List (updated 2026-03-01): 12,847 entries. EU Co...",
"[AUDIT] trace: 00-8092a72905634b30be6e224e9d7230aa-673ffd81f2664a16-01",
"",
"[P1-b]   Requesting unit: transaction-patterns",
"[KCP]  Matched unit: transaction-patterns | access: authenticated | sensitivity: internal",
"[KCP]  Access: authenticated -> checking credential",
"[KCP]  Token valid: yes | Scope 'read:transactions': yes",
"[KCP]  Result: LOADED",
"[KCP]  Content: Pattern analysis (Jan 2026): 2.4M transactions screened. ...",
"[AUDIT] trace: 00-7424e7663ad74c8884d15bf8ed2782e4-b85fe3c3785244c8-01",
"",
"── Phase 2: HITL Chain (restricted units, human approval required) ─",
"",
"[P2-a]   Requesting unit: customer-profiles",
"[KCP]  Matched unit: customer-profiles | access: restricted | sensitivity: confidential",
"[KCP]  Access: restricted | auth_scope: aml-analyst | sensitivity: confidential",
"[KCP]  Delegation: max_depth=2",
"[KCP]  Human-in-the-loop: REQUIRED (approval_mechanism: oauth_consent)",
"[HITL] Approval required for unit 'customer-profiles' (sensitivity: confidential, PII data)",
"[HITL] Waiting for approval... (use --auto-approve to skip in CI)",
"[HITL] Approved by: researcher@example.com",
"[KCP]  Result: LOADED (after human approval)",
"[KCP]  Content: [PII/GDPR] Customer C-4401: Corporate entity, KYC tier 3 ...",
"[AUDIT] trace: 00-34d7f74f2d17452180bb99a8004a2fe0-2587a5db72e24d18-01",
"[AUDIT] unit: customer-profiles | human_approval: researcher@example.com",
"",
"[P2-b]   Requesting unit: sar-drafts",
"[KCP]  Matched unit: sar-drafts | access: restricted | sensitivity: restricted",
"[KCP]  Access: restricted | auth_scope: compliance-officer | sensitivity: restricted",
"[KCP]  Delegation: max_depth=1",
"[KCP]  Human-in-the-loop: REQUIRED (approval_mechanism: custom)",
"[HITL] Approval required for unit 'sar-drafts' (sensitivity: restricted, PII data)",
"[HITL] Waiting for approval... (use --auto-approve to skip in CI)",
"[HITL] Approved by: researcher@example.com",
"[KCP]  Result: LOADED (after human approval)",
"[KCP]  Content: [RESTRICTED] SAR-2026-0042 DRAFT: Subject C-4401. Suspici...",
"[AUDIT] trace: 00-889753aeba834c67a11855c7811375f5-6cf770fe3dcb4cec-01",
"[AUDIT] unit: sar-drafts | human_approval: researcher@example.com",
"",
"── Phase 3: Legitimate Delegation Chain (depth=1 -> depth=2) ───",
"",
"[P3-a]   Delegating 'transaction-patterns' to SanctionsScreeningAgent (depth=1)",
"[KCP]  Scope narrowed: 'read:transactions' -> 'read:transactions:sanctions-only'",
"[KCP]  Result: DELEGATED to SanctionsScreeningAgent (depth=1)",
"[AUDIT] trace: 00-5859c76a3bc6475eb4ad449f94268888-9762a6ffcf584f03-01",
"",
"[P3-b]   SanctionsScreeningAgent delegates to MLRiskScoringAgent (depth=2)",
"[KCP]  Scope narrowed: 'read:transactions:sanctions-only' -> 'read:transactions:risk-signals'",
"[KCP]  Result: DELEGATED to MLRiskScoringAgent (depth=2)",
"[AUDIT] trace: 00-973123d1840c4a5dbcb6ec957c6cb639-a4abfb5ab9904735-01",
"",
"── Phase 4: RogueAgent -- Delegation Depth Violation ───────────",
"",
"[P4]   RogueAgent claims depth=3 delegatee for 'customer-profiles' (max_depth=2)",
"[KCP]  Result: BLOCKED (Depth 3 exceeds max_depth=2)",
"[AUDIT] trace: 00-39ab514246f24d04bd07fb9ae8ddca8c-f243673524ab4b58-01",
"[AUDIT] VIOLATION: RogueAgent depth=3 exceeds max_depth=2 for 'customer-profiles'",
"",
"── Phase 5: RogueAgent -- Scope Elevation Attempt ──────────────",
"",
"[P5]   RogueAgent has 'read:transactions' token, requests 'customer-profiles' (requires 'aml-analyst')",
"[KCP]  Token scope: [read:transactions]",
"[KCP]  Required scope: aml-analyst",
"[KCP]  Result: BLOCKED (Invalid token or missing scope: aml-analyst)",
"[AUDIT] trace: 00-ad84aa0470984108ba780681e2f387b6-6b111c0ca91b469c-01",
"[AUDIT] VIOLATION: RogueAgent scope elevation attempt -- 'read:transactions' cannot access 'aml-analyst' unit",
"",
"── Phase 6: RogueAgent -- max_depth=0 Violation Attempt ────────",
"",
"[P6]   RogueAgent attempts 'raw-wire-transfers' as delegatee (max_depth=0)",
"[KCP]  Result: BLOCKED (Unit has max_depth=0: this unit cannot be delegated)",
"[AUDIT] trace: 00-3e63cb9bdbbc4e188cbd8971469fb8bd-9a95d2c48a324cb5-01",
"[AUDIT] VIOLATION: RogueAgent delegation attempt on no-delegation unit 'raw-wire-transfers'",
"",
"── Phase 7: Compliance Block (GDPR Data Residency) ─────────────",
"",
"[P7]   Request for 'customer-profiles' with data_residency claim: US",
"[KCP]  Unit 'customer-profiles' has compliance.data_residency.regions: [EU]",
"[KCP]  Result: BLOCKED (Data residency violation: request from 'US' but unit restricted to [EU])",
"[KCP]  Regulation: GDPR",
"[AUDIT] trace: 00-abae6de1fe724b3faaef48e913007d26-ae0ecf76e84c433b-01",
"[AUDIT] COMPLIANCE VIOLATION: GDPR data residency -- US request blocked for EU-only unit",
"",
"[P7-note]   Checking restrictions on 'raw-wire-transfers'",
"[KCP]  Regulations: [GDPR, AML5D, NIS2]",
"[KCP]  Restrictions: [no_ai_training, no_cross_border]",
"[KCP]  Data residency: [EU]",
"[KCP]  Note: 'no_ai_training' restriction is declared but enforcement is application-defined (v0.7 gap)",
"[AUDIT] trace: 00-269971933c3d44a7ab225a4fb25c7263-8932a848366145b4-01",
"",
"── Phase 8: RogueAgent -- Rate Limit Advisory Burst ────────────",
"",
"[P8]   RogueAgent burst-requesting 'customer-profiles' (advisory limit: 5/min)",
"[KCP]  Advisory rate_limits for 'customer-profiles': 5/min, 50/day",
"[KCP]  Advisory: rate_limits is not enforced — requests will succeed but violations are logged",
"[KCP]  Request #1: within advisory limit",
"[KCP]  Request #2: within advisory limit",
"[KCP]  Request #3: within advisory limit",
"[KCP]  Request #4: within advisory limit",
"[KCP]  Request #5: within advisory limit",
"[KCP]  Request #6: ADVISORY_VIOLATION (exceeded rate_limits)",
"[KCP]  Request #7: ADVISORY_VIOLATION (exceeded rate_limits)",
"[KCP]  Request #8: ADVISORY_VIOLATION (exceeded rate_limits)",
"[KCP]  Request #9: ADVISORY_VIOLATION (exceeded rate_limits)",
"[KCP]  Request #10: ADVISORY_VIOLATION (exceeded rate_limits)",
"[KCP]  Result: 10 requests completed, 5 advisory violations",
"[KCP]  RogueAgent exceeded rate_limits advisory for customer-profiles",
"[AUDIT] trace: 00-a9f3c3d9dda9457e9a487dde44882c70-dd5ecd24da2f4ac2-01",
"[AUDIT] RATE_LIMIT_ADVISORY: RogueAgent burst 10 requests to 'customer-profiles' — 5 exceeded advisory 5/min",
"",
"=== SIMULATION SUMMARY ===",
"  Units loaded successfully:    6",
"  Access denied (policy):       1",
"  Access denied (delegation):   2",
"  Access denied (compliance):   1",
"  HITL approvals:               2",
"  Rate limit advisory violations: 5",
"  Audit entries:                12",
"  Violations detected:          4"
].join('\n');

var RAW_S4 = [
"=== SCENARIO 4: Rate-Limit-Aware Agent Simulator ===",
"Manifest: ../knowledge.yaml",
"Requests per unit: 5",
"",
"--- Discovered Units ---",
"  public-docs          access=public         rate_limit=120/min, 10000/day",
"  api-reference        access=authenticated  rate_limit=60/min, 5000/day",
"  internal-guide       access=restricted     rate_limit=10/min, 200/day",
"  compliance-data      access=restricted     rate_limit=2/min, 20/day",
"",
"--- PoliteAgent (self-throttling) ---",
"  Request: unit=public-docs, access=public, request #1",
"  Request: unit=public-docs, access=public, request #2",
"  Request: unit=public-docs, access=public, request #3",
"  Request: unit=public-docs, access=public, request #4",
"  Request: unit=public-docs, access=public, request #5",
"  Request: unit=api-reference, access=authenticated, request #1",
"  Request: unit=api-reference, access=authenticated, request #2",
"  Request: unit=api-reference, access=authenticated, request #3",
"  Request: unit=api-reference, access=authenticated, request #4",
"  Request: unit=api-reference, access=authenticated, request #5",
"  Request: unit=internal-guide, access=restricted, request #1",
"  Request: unit=internal-guide, access=restricted, request #2",
"  Request: unit=internal-guide, access=restricted, request #3",
"  Request: unit=internal-guide, access=restricted, request #4",
"  Request: unit=internal-guide, access=restricted, request #5",
"  Request: unit=compliance-data, access=restricted, request #1",
"  Request: unit=compliance-data, access=restricted, request #2",
"  Throttling: unit=compliance-data, used=2/min, limit=2/min — waiting 60s",
"  Request: unit=compliance-data, access=restricted, request #3",
"  Throttling: unit=compliance-data, used=3/min, limit=2/min — waiting 60s",
"  Request: unit=compliance-data, access=restricted, request #4",
"  Throttling: unit=compliance-data, used=4/min, limit=2/min — waiting 60s",
"  Request: unit=compliance-data, access=restricted, request #5",
"",
"--- GreedyAgent (burst, ignores limits) ---",
"  Request: unit=public-docs, access=public, request #1",
"  Request: unit=public-docs, access=public, request #2",
"  Request: unit=public-docs, access=public, request #3",
"  Request: unit=public-docs, access=public, request #4",
"  Request: unit=public-docs, access=public, request #5",
"  Request: unit=api-reference, access=authenticated, request #1",
"  Request: unit=api-reference, access=authenticated, request #2",
"  Request: unit=api-reference, access=authenticated, request #3",
"  Request: unit=api-reference, access=authenticated, request #4",
"  Request: unit=api-reference, access=authenticated, request #5",
"  Request: unit=internal-guide, access=restricted, request #1",
"  Request: unit=internal-guide, access=restricted, request #2",
"  Request: unit=internal-guide, access=restricted, request #3",
"  Request: unit=internal-guide, access=restricted, request #4",
"  Request: unit=internal-guide, access=restricted, request #5",
"  Request: unit=compliance-data, access=restricted, request #1",
"  Request: unit=compliance-data, access=restricted, request #2",
"  ADVISORY VIOLATION: unit=compliance-data, used=3/min, limit=2/min",
"  ADVISORY VIOLATION: unit=compliance-data, used=4/min, limit=2/min",
"  ADVISORY VIOLATION: unit=compliance-data, used=5/min, limit=2/min",
"",
"=== SUMMARY ===",
"  PoliteAgent:  20 requests, 3 throttle pauses, 0 advisory violations",
"  GreedyAgent:  20 requests, 0 throttle pauses, 3 advisory violations"
].join('\n');

var RAW_S5 = [
"=== SCENARIO 5: Dependency Ordering Simulator ===",
"Manifest: ../knowledge.yaml",
"",
"--- Discovered Units (8) ---",
"  platform-overview         access=public         deps=none",
"  migration-guide           access=public         deps=platform-overview",
"  api-v1-reference          access=public         deps=none",
"  api-v2-reference          access=public         deps=migration-guide  supersedes=api-v1-reference",
"  deployment-guide          access=authenticated  deps=api-v2-reference",
"  legacy-security-policy    access=authenticated  deps=none",
"  zero-trust-policy         access=authenticated  deps=none  supersedes=legacy-security-policy",
"  troubleshooting           access=public         deps=deployment-guide",
"",
"--- Relationships (10) ---",
"  migration-guide -[depends_on]-> platform-overview",
"  api-v2-reference -[depends_on]-> migration-guide",
"  deployment-guide -[depends_on]-> api-v2-reference",
"  troubleshooting -[depends_on]-> deployment-guide",
"  platform-overview -[enables]-> migration-guide",
"  deployment-guide -[enables]-> troubleshooting",
"  api-v2-reference -[supersedes]-> api-v1-reference",
"  zero-trust-policy -[supersedes]-> legacy-security-policy",
"  legacy-security-policy -[contradicts]-> zero-trust-policy",
"  legacy-security-policy -[context]-> zero-trust-policy",
"",
"--- Topological Load Order ---",
"  1. platform-overview",
"  2. api-v1-reference",
"  3. legacy-security-policy",
"  4. zero-trust-policy",
"  5. migration-guide",
"  6. api-v2-reference",
"  7. deployment-guide",
"  8. troubleshooting",
"",
"--- Ingestion ---",
"[INFO]  LOADED: platform-overview",
"[INFO]  LOADED: api-v1-reference",
"[INFO]  LOADED: legacy-security-policy",
"[INFO]  LOADED: zero-trust-policy",
"[INFO]  SUPERSEDES: zero-trust-policy supersedes legacy-security-policy — prefer this version",
"[INFO]  LOADED: migration-guide",
"[INFO]  LOADED: api-v2-reference",
"[INFO]  SUPERSEDES: api-v2-reference supersedes api-v1-reference — prefer this version",
"[INFO]  LOADED: deployment-guide",
"[INFO]  LOADED: troubleshooting",
"[WARN]  CONFLICT: legacy-security-policy contradicts zero-trust-policy",
"",
"=== SUMMARY ===",
"  Loaded:  8",
"  Skipped: 0",
"  Blocked: 0",
"  Warnings: 1"
].join('\n');

// ---------------------------------------------------------------------------
// Build SCENARIOS from raw output
// ---------------------------------------------------------------------------
var SCENARIOS = {
  s1: {
    title: 'Energy Metering',
    subtitle: 'Happy path + HITL gate',
    agents: 2,
    tests: 36,
    lines: parseOutput(RAW_S1)
  },
  s2: {
    title: 'Legal Delegation',
    subtitle: '3-hop chain, max_depth:0 enforcement',
    agents: 3,
    tests: 36,
    lines: parseOutput(RAW_S2)
  },
  s3: {
    title: 'Financial AML',
    subtitle: 'Adversarial \u2014 RogueAgent, 4 attack vectors',
    agents: 5,
    tests: 27,
    lines: parseOutput(RAW_S3)
  },
  s4: {
    title: 'Rate-Limit Aware',
    subtitle: 'PoliteAgent vs GreedyAgent',
    agents: 2,
    tests: 34,
    lines: parseOutput(RAW_S4)
  },
  s5: {
    title: 'Dependency Ordering',
    subtitle: 'Topological sort + cycle detection',
    agents: 1,
    tests: 34,
    lines: parseOutput(RAW_S5)
  }
};

// ---------------------------------------------------------------------------
// SimulatorPlayer
// ---------------------------------------------------------------------------
var SimulatorPlayer = /** @class */ (function () {
  function SimulatorPlayer() {
    this.currentScenario = 's3';
    this.lines = [];
    this.lineIndex = 0;
    this.playing = false;
    this.speed = 1;
    this.timer = null;
    this.stats = { loaded: 0, blocked: 0, violations: 0, hitl: 0, audit: 0 };
    this.init();
  }

  SimulatorPlayer.prototype.init = function () {
    var self = this;

    // Tab switching
    document.querySelectorAll('.sim-tab').forEach(function (tab) {
      tab.addEventListener('click', function () {
        self.loadScenario(tab.dataset.scenario);
      });
    });

    document.getElementById('sim-play-pause').addEventListener('click', function () {
      self.togglePlay();
    });
    document.getElementById('sim-restart').addEventListener('click', function () {
      self.restart();
    });
    document.getElementById('sim-speed').addEventListener('change', function (e) {
      self.speed = Number(e.target.value);
    });
    document.getElementById('sim-jump').addEventListener('change', function (e) {
      if (e.target.value) self.jumpToPhase(e.target.value);
    });

    this.loadScenario('s3');
  };

  SimulatorPlayer.prototype.loadScenario = function (id) {
    this.stop();
    this.currentScenario = id;
    var scenario = SCENARIOS[id];
    this.lines = scenario.lines;
    this.lineIndex = 0;
    this.stats = { loaded: 0, blocked: 0, violations: 0, hitl: 0, audit: 0 };

    // Update tabs
    document.querySelectorAll('.sim-tab').forEach(function (t) {
      t.classList.toggle('active', t.dataset.scenario === id);
    });

    // Update header
    document.querySelector('.sim-title').textContent = scenario.title + ' \u2014 ' + scenario.subtitle;

    // Clear terminal
    document.getElementById('sim-output').innerHTML = '';
    document.getElementById('sim-progress-bar').style.width = '0%';
    document.getElementById('sim-play-pause').textContent = '\u25B6 Play';

    // Update stats panel
    document.getElementById('stat-scenario').textContent = scenario.title;
    document.getElementById('stat-agents').textContent = scenario.agents;
    document.getElementById('stat-loaded').textContent = '0';
    document.getElementById('stat-blocked').textContent = '0';
    document.getElementById('stat-violations').textContent = '0';
    document.getElementById('stat-hitl').textContent = '0';
    document.getElementById('stat-audit').textContent = '0';

    // Populate phase jump dropdown
    var jumpSel = document.getElementById('sim-jump');
    jumpSel.innerHTML = '<option value="">Jump to phase\u2026</option>';
    var seenPhases = {};
    for (var i = 0; i < scenario.lines.length; i++) {
      var line = scenario.lines[i];
      if (line.phase && !seenPhases[line.phase]) {
        seenPhases[line.phase] = true;
        var opt = document.createElement('option');
        opt.value = line.phase;
        opt.textContent = line.phase;
        jumpSel.appendChild(opt);
      }
    }

    // Update GitHub link
    var ghBase = 'https://github.com/Cantara/knowledge-context-protocol/tree/main/examples/';
    var scenarioDir = {
      s1: 'scenario1-energy-metering',
      s2: 'scenario2-legal-delegation',
      s3: 'scenario3-financial-aml',
      s4: 'scenario4-rate-limit-aware',
      s5: 'scenario5-dependency-ordering'
    };
    document.querySelector('.sim-gh-link').href = ghBase + scenarioDir[id];
  };

  SimulatorPlayer.prototype.togglePlay = function () {
    if (this.playing) {
      this.pause();
    } else {
      this.play();
    }
  };

  SimulatorPlayer.prototype.play = function () {
    this.playing = true;
    document.getElementById('sim-play-pause').textContent = '\u23F8 Pause';
    this.scheduleNext();
  };

  SimulatorPlayer.prototype.pause = function () {
    this.playing = false;
    clearTimeout(this.timer);
    document.getElementById('sim-play-pause').textContent = '\u25B6 Play';
  };

  SimulatorPlayer.prototype.stop = function () {
    this.playing = false;
    clearTimeout(this.timer);
  };

  SimulatorPlayer.prototype.restart = function () {
    this.stop();
    this.loadScenario(this.currentScenario);
  };

  SimulatorPlayer.prototype.scheduleNext = function () {
    if (!this.playing || this.lineIndex >= this.lines.length) {
      if (this.lineIndex >= this.lines.length) {
        document.getElementById('sim-play-pause').textContent = '\u25B6 Replay';
        this.playing = false;
      }
      return;
    }
    var line = this.lines[this.lineIndex];
    var delay = Math.max(10, (line.delay || 60) / this.speed);
    var self = this;
    this.timer = setTimeout(function () { self.renderLine(); }, delay);
  };

  SimulatorPlayer.prototype.renderLine = function () {
    if (this.lineIndex >= this.lines.length) return;
    var line = this.lines[this.lineIndex];
    var output = document.getElementById('sim-output');

    // Create line element
    var div = document.createElement('div');
    div.className = 'sim-line-' + line.tag;
    div.textContent = line.text;
    output.appendChild(div);
    output.scrollTop = output.scrollHeight;

    // Update live stats based on text content
    var text = line.text;
    if (/Result:\s*LOADED|LOADED:/.test(text)) this.stats.loaded++;
    if (/Result:\s*BLOCKED|Result:\s*DENIED/.test(text)) this.stats.blocked++;
    if (/VIOLATION/.test(text) && !/ADVISORY.VIOLATION/.test(text)) this.stats.violations++;
    if (/ADVISORY.VIOLATION/.test(text)) this.stats.violations++;
    if (/\[HITL\]/.test(text) && /Approved/.test(text)) this.stats.hitl++;
    if (/\[AUDIT\]/.test(text)) this.stats.audit++;

    document.getElementById('stat-loaded').textContent = this.stats.loaded;
    document.getElementById('stat-blocked').textContent = this.stats.blocked;
    document.getElementById('stat-violations').textContent = this.stats.violations;
    document.getElementById('stat-hitl').textContent = this.stats.hitl;
    document.getElementById('stat-audit').textContent = this.stats.audit;

    // Update progress bar
    var progress = ((this.lineIndex + 1) / this.lines.length) * 100;
    document.getElementById('sim-progress-bar').style.width = progress + '%';

    this.lineIndex++;
    this.scheduleNext();
  };

  SimulatorPlayer.prototype.jumpToPhase = function (phaseName) {
    this.stop();
    var output = document.getElementById('sim-output');
    output.innerHTML = '';
    this.stats = { loaded: 0, blocked: 0, violations: 0, hitl: 0, audit: 0 };

    // Find the first line of the target phase
    var targetIndex = -1;
    for (var i = 0; i < this.lines.length; i++) {
      if (this.lines[i].phase === phaseName && this.lines[i].tag === 'phase') {
        targetIndex = i;
        break;
      }
    }
    if (targetIndex === -1) targetIndex = 0;

    // Fast-forward: render stats (not visually) for all lines before target
    for (var i = 0; i < targetIndex; i++) {
      var text = this.lines[i].text;
      if (/Result:\s*LOADED|LOADED:/.test(text)) this.stats.loaded++;
      if (/Result:\s*BLOCKED|Result:\s*DENIED/.test(text)) this.stats.blocked++;
      if (/VIOLATION/.test(text) && !/ADVISORY.VIOLATION/.test(text)) this.stats.violations++;
      if (/ADVISORY.VIOLATION/.test(text)) this.stats.violations++;
      if (/\[HITL\]/.test(text) && /Approved/.test(text)) this.stats.hitl++;
      if (/\[AUDIT\]/.test(text)) this.stats.audit++;
    }

    // Update stats display
    document.getElementById('stat-loaded').textContent = this.stats.loaded;
    document.getElementById('stat-blocked').textContent = this.stats.blocked;
    document.getElementById('stat-violations').textContent = this.stats.violations;
    document.getElementById('stat-hitl').textContent = this.stats.hitl;
    document.getElementById('stat-audit').textContent = this.stats.audit;

    this.lineIndex = targetIndex;
    document.getElementById('sim-jump').value = '';
    this.play();
  };

  return SimulatorPlayer;
}());

// Initialize when DOM is ready
document.addEventListener('DOMContentLoaded', function () {
  if (document.getElementById('sim-output')) {
    new SimulatorPlayer();
  }
});
