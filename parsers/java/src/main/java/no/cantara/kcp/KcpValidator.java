package no.cantara.kcp;

import no.cantara.kcp.model.Agent;
import no.cantara.kcp.model.Compliance;
import no.cantara.kcp.model.ContentHash;
import no.cantara.kcp.model.ContentStructure;
import no.cantara.kcp.model.Delegation;
import no.cantara.kcp.model.Discovery;
import no.cantara.kcp.model.ExternalDependency;
import no.cantara.kcp.model.ExternalRelationship;
import no.cantara.kcp.model.GrantCeiling;
import no.cantara.kcp.model.GrantCeilingSource;
import no.cantara.kcp.model.HumanInTheLoop;
import no.cantara.kcp.model.KnowledgeManifest;
import no.cantara.kcp.model.ActionScope;
import no.cantara.kcp.model.DenyScope;
import no.cantara.kcp.model.KnowledgeUnit;
import no.cantara.kcp.model.PlaybookStep;
import no.cantara.kcp.model.AgentIdentity;
import no.cantara.kcp.model.ManifestRef;
import no.cantara.kcp.model.Payment;
import no.cantara.kcp.model.PaymentMethod;
import no.cantara.kcp.model.RateLimits;
import no.cantara.kcp.model.Relationship;
import no.cantara.kcp.model.TaskType;
import no.cantara.kcp.model.Temporal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Validates a parsed {@link KnowledgeManifest} against the KCP specification.
 *
 * <p>Returns a {@link ValidationResult} with separate {@code errors} (must fix) and
 * {@code warnings} (should fix) lists, per the conformance rules in SPEC.md §7.
 */
public class KcpValidator {

    private static final Set<String> VALID_SCOPES = Set.of("global", "project", "module");
    private static final Set<String> VALID_AUDIENCES = Set.of("human", "agent", "developer", "operator", "architect", "devops");
    private static final Set<String> VALID_RELATIONSHIP_TYPES = Set.of("enables", "context", "supersedes", "contradicts", "depends_on", "governs");
    private static final Set<String> VALID_KINDS = Set.of("knowledge", "schema", "service", "policy", "executable", "skill", "playbook");
    private static final Set<String> VALID_ON_FAILURE = Set.of("abort", "continue", "escalate");
    private static final Set<String> VALID_FORMATS = Set.of(
            "markdown", "pdf", "openapi", "json-schema", "jupyter",
            "html", "asciidoc", "rst", "vtt", "yaml", "json", "csv", "text");
    private static final Set<String> VALID_UPDATE_FREQUENCIES = Set.of("hourly", "daily", "weekly", "monthly", "rarely", "never");
    private static final Set<String> VALID_INDEXING_SHORTHANDS = Set.of("open", "read-only", "no-train", "none");
    private static final Set<String> VALID_ACCESS_VALUES = Set.of("public", "authenticated", "restricted");
    private static final Set<String> VALID_SENSITIVITY_VALUES = Set.of("public", "internal", "confidential", "restricted");
    private static final Set<String> VALID_HITL_MECHANISMS = Set.of("oauth_consent", "uma", "custom");
    private static final Set<String> KNOWN_KCP_VERSIONS = Set.of("0.1", "0.2", "0.3", "0.4", "0.5", "0.6", "0.7", "0.8", "0.9", "0.10", "0.11", "0.12", "0.13", "0.14", "0.16", "0.17", "0.18", "0.19", "0.20", "0.21", "0.22", "0.23", "0.24", "0.25", "0.26", "0.27", "0.28", "0.29", "0.30", "0.31", "0.32");
    // content_structure vocabularies (RFC-0016, v0.17). Unknown values warn but pass through.
    private static final Set<String> VALID_CONTENT_MODALITIES = Set.of("prose", "table", "code", "list", "diagram", "reference", "mixed");
    private static final Set<String> VALID_DENSITY = Set.of("sparse", "normal", "dense");
    private static final Set<String> VALID_VERIFICATION_STATUSES = Set.of("rumored", "declared", "observed", "verified", "deprecated");
    private static final Set<String> VALID_DISCOVERY_SOURCES = Set.of("manual", "web_traversal", "openapi", "llm_inference", "manifest-self-description");
    private static final Set<String> VALID_AUTHORITY_VALUES = Set.of("initiative", "requires_approval", "denied");
    private static final Set<String> VALID_VISIBILITY_DEFAULTS = Set.of("public", "internal", "confidential", "restricted");
    private static final Set<String> VALID_MANIFEST_RELATIONSHIPS = Set.of("child", "foundation", "governs", "peer", "archive");
    private static final Set<String> VALID_ON_FAILURE_VALUES = Set.of("skip", "warn", "degrade");
    private static final Set<String> VALID_VERSION_POLICIES = Set.of("exact", "minimum", "compatible");
    // RFC-0019 (draft): allowed content_hash algorithms, keyed to JCA names.
    private static final Map<String, String> HASH_ALGORITHMS = Map.of(
            "sha256", "SHA-256", "sha384", "SHA-384", "sha512", "SHA-512");
    private static final Pattern HEX_PATTERN = Pattern.compile("^[0-9a-fA-F]+$");
    private static final Pattern ID_PATTERN = Pattern.compile("^[a-z0-9.\\-]+$");
    private static final Pattern ALIAS_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9._-]*$");  // §4.2a (v0.26)
    private static final int MAX_TRIGGER_LENGTH = 60;
    private static final int MAX_TRIGGERS_PER_UNIT = 20;

    /**
     * Immutable result of validating a manifest.
     *
     * @param errors   Conditions that make the manifest invalid (MUST fix).
     * @param warnings Conditions that are permitted but suspicious (SHOULD fix).
     */
    public record ValidationResult(List<String> errors, List<String> warnings) {
        public ValidationResult {
            errors = List.copyOf(errors);
            warnings = List.copyOf(warnings);
        }

        public boolean isValid() { return errors.isEmpty(); }
        public boolean hasWarnings() { return !warnings.isEmpty(); }
    }

    /**
     * Validate a manifest without path existence checking.
     */
    public static ValidationResult validate(KnowledgeManifest manifest) {
        return validate(manifest, null);
    }

    /**
     * Validate a manifest, optionally checking that declared paths exist relative
     * to {@code manifestDir}.
     *
     * @param manifest    The parsed manifest to validate.
     * @param manifestDir The directory containing the manifest file, or {@code null}
     *                    to skip path existence checks.
     */
    public static ValidationResult validate(KnowledgeManifest manifest, Path manifestDir) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        // #166: problems the parser noticed and no later stage can reconstruct.
        // Warnings rather than errors — a malformed value or an unknown field leaves a
        // valid manifest that simply does not say what its author thought it said.
        if (manifest.parseDiagnostics() != null) warnings.addAll(manifest.parseDiagnostics());
        Set<String> unitIds = manifest.units().stream().map(KnowledgeUnit::id).collect(Collectors.toSet());

        // Cycle detection (§4.7) — detect and silently ignore cycle-closing edges.
        detectCycles(manifest.units(), unitIds);

        // kcp_version — RECOMMENDED; warn if absent or unknown
        if (manifest.kcpVersion() == null || manifest.kcpVersion().isBlank()) {
            warnings.add("manifest: 'kcp_version' not declared; assuming 0.8");
        } else if (!KNOWN_KCP_VERSIONS.contains(manifest.kcpVersion())) {
            warnings.add("manifest: unknown kcp_version '" + manifest.kcpVersion() +
                    "'; processing as " + KNOWN_KCP_VERSIONS.stream().max(String::compareTo).orElse("0.6"));
        }

        // Required root fields
        if (manifest.project() == null || manifest.project().isBlank()) {
            errors.add("manifest: 'project' is required");
        }
        if (manifest.units().isEmpty()) {
            errors.add("manifest: 'units' must not be empty");
        }

        // Duplicate ID detection (§7: SHOULD warn, use first occurrence)
        Set<String> seenIds = new HashSet<>();

        for (KnowledgeUnit unit : manifest.units()) {
            String p = "unit '" + unit.id() + "'";

            if (unit.id() == null || unit.id().isBlank()) {
                errors.add("unit: 'id' is required");
                continue;
            }

            // Duplicate ID check
            if (!seenIds.add(unit.id())) {
                warnings.add(p + ": duplicate 'id' (first occurrence takes precedence)");
            }

            // ID format check (§4.2: lowercase a-z, digits, hyphens, dots)
            if (!ID_PATTERN.matcher(unit.id()).matches()) {
                warnings.add(p + ": 'id' should contain only lowercase a-z, digits, hyphens, and dots");
            }

            if (unit.path() == null || unit.path().isBlank()) {
                errors.add(p + ": 'path' is required");
            } else if (manifestDir != null) {
                Path resolved = manifestDir.resolve(unit.path());
                if (!Files.exists(resolved)) {
                    warnings.add(p + ": path '" + unit.path() + "' does not exist");
                }
            }
            if (unit.intent() == null || unit.intent().isBlank()) {
                errors.add(p + ": 'intent' is required");
            }
            if (unit.scope() == null || unit.scope().isBlank()) {
                errors.add(p + ": 'scope' is required");
            } else if (!VALID_SCOPES.contains(unit.scope())) {
                errors.add(p + ": 'scope' must be one of " + sorted(VALID_SCOPES) + ", got '" + unit.scope() + "'");
            }

            List<String> invalidAudience = unit.audience().stream()
                    .filter(a -> !VALID_AUDIENCES.contains(a))
                    .toList();
            if (!invalidAudience.isEmpty()) {
                warnings.add(p + ": unknown audience value(s): " + invalidAudience);
            }

            // kind validation (§4.3a)
            if (unit.kind() != null && !VALID_KINDS.contains(unit.kind())) {
                warnings.add(p + ": unknown 'kind' value '" + unit.kind() + "'");
            }

            // format validation (§4.4a)
            if (unit.format() != null && !VALID_FORMATS.contains(unit.format())) {
                warnings.add(p + ": unknown 'format' value '" + unit.format() + "'");
            }

            // update_frequency validation (§4.6b)
            if (unit.updateFrequency() != null && !VALID_UPDATE_FREQUENCIES.contains(unit.updateFrequency())) {
                warnings.add(p + ": unknown 'update_frequency' value '" + unit.updateFrequency() + "'");
            }

            // indexing validation (§4.6c)
            if (unit.indexing() instanceof String idx && !VALID_INDEXING_SHORTHANDS.contains(idx)) {
                warnings.add(p + ": unknown 'indexing' shorthand '" + idx + "'");
            }

            for (String dep : unit.dependsOn()) {
                if (!unitIds.contains(dep)) {
                    warnings.add(p + ": 'depends_on' references unknown unit '" + dep + "'");
                }
            }

            // Trigger constraints (§4.9)
            if (unit.triggers().size() > MAX_TRIGGERS_PER_UNIT) {
                warnings.add(p + ": more than " + MAX_TRIGGERS_PER_UNIT + " triggers (" +
                        unit.triggers().size() + "); excess will be ignored");
            }
            for (String trigger : unit.triggers()) {
                if (trigger.length() > MAX_TRIGGER_LENGTH) {
                    warnings.add(p + ": trigger '" + trigger.substring(0, Math.min(30, trigger.length())) +
                            "...' exceeds " + MAX_TRIGGER_LENGTH + " characters");
                }
            }

            // access validation (§4.11)
            if (unit.access() != null && !VALID_ACCESS_VALUES.contains(unit.access())) {
                warnings.add(p + ": unknown 'access' value '" + unit.access() + "'; treating as 'restricted'");
            }

            // auth_scope validation (§4.11)
            if (unit.authScope() != null && !"restricted".equals(unit.access())) {
                warnings.add(p + ": 'auth_scope' is only meaningful when access is 'restricted'");
            }

            // sensitivity validation (§4.12)
            if (unit.sensitivity() != null && !VALID_SENSITIVITY_VALUES.contains(unit.sensitivity())) {
                warnings.add(p + ": unknown 'sensitivity' value '" + unit.sensitivity() + "'");
            }

            // delegation validation (§3.4)
            validateDelegation(unit.delegation(), manifest.delegation(), p, errors, warnings);

            // compliance validation (§3.5)
            validateCompliance(unit.compliance(), p, errors, warnings);

            // hints validation (§4.10)
            if (unit.hints() instanceof Map<?, ?> hints) {
                if (Boolean.TRUE.equals(hints.get("summary_available")) && hints.get("summary_unit") == null) {
                    warnings.add(p + ": summary_available is true but no summary_unit declared");
                }
                Object summaryUnit = hints.get("summary_unit");
                if (summaryUnit instanceof String su && !unitIds.contains(su)) {
                    warnings.add(p + ": summary_unit references non-existent unit '" + su + "'");
                }
                Object chunkOf = hints.get("chunk_of");
                if (chunkOf instanceof String co && !unitIds.contains(co)) {
                    warnings.add(p + ": chunk_of references non-existent unit '" + co + "'");
                }
                if (hints.get("chunk_index") != null && hints.get("chunk_of") == null) {
                    warnings.add(p + ": chunk_index is present without chunk_of");
                }
            }

            // discovery validation (§RFC-0012)
            validateDiscovery(unit.discovery(), unitIds, p, errors, warnings);

            // authority validation (§RFC-0009)
            validateAuthority(unit.authority(), p, warnings);

            // visibility validation (§RFC-0009)
            validateVisibility(unit.visibility(), p, warnings);

            // not_for validation (RFC-0015, v0.17)
            if (unit.notForStrict() != null && unit.notFor().isEmpty()) {
                warnings.add(p + ": 'not_for_strict' is set but 'not_for' is empty or absent");
            }

            // content_structure validation (RFC-0016, v0.17) — warn on unknown values, pass through
            validateContentStructure(unit.contentStructure(), p, warnings);

            // content_hash validation (RFC-0019, draft) — shape, then recompute
            // against disk when a manifest directory is available (§3.1: "kcp
            // validate recomputes and compares"). A stale hash is an error, not a
            // warning: signing over it would brick the unit for every consumer.
            validateContentHash(unit.contentHash(), unit.path(), manifestDir, p, errors);
        }

        // --- kind: playbook — §4.3b (v0.29, RFC-0027) ---
        //
        // Deliberately a second pass over the units. `uses` may name a unit declared
        // later in the manifest, so resolving it inside the loop above would reject
        // forward references the spec permits.
        Map<String, String> unitKinds = new LinkedHashMap<>();
        Map<String, KnowledgeUnit> unitsById = new LinkedHashMap<>();
        for (KnowledgeUnit u : manifest.units()) {
            unitKinds.put(u.id(), u.kind() != null ? u.kind() : "knowledge");
            unitsById.put(u.id(), u);
        }
        Set<String> declaredLevels = manifest.authorityLevelScale() != null
                ? new HashSet<>(manifest.authorityLevelScale()) : Set.of();

        // §4.3c (RFC-0028): eligibility is a property of the unit that declares it and
        // does NOT compose — a grant on a playbook does not reach the units its steps
        // name. Absent means not eligible: a governed procedure fails closed.
        Set<String> GOVERNED = Set.of("skill", "playbook");
        java.util.function.Predicate<String> isEligible = id -> {
            KnowledgeUnit u = unitsById.get(id);
            return u != null && Boolean.TRUE.equals(u.loadEligible());
        };

        for (KnowledgeUnit unit : manifest.units()) {
            String ctx = "unit '" + unit.id() + "'";
            String kind = unit.kind() != null ? unit.kind() : "knowledge";

            // §4.3c: the grant is defined only for the kinds that act. Declaring it
            // elsewhere is a category error — no renderer may ever mark those kinds
            // eligible (C4), so it cannot mean what the author intended.
            if (unit.loadEligible() != null && !GOVERNED.contains(kind)) {
                errors.add(ctx + ": 'load_eligible' is only defined for kind: skill and"
                        + " kind: playbook, not '" + kind + "' (§4.3c)");
            }

            // §4.3c: a granted skill with no action_scope is authorised to act and
            // bounded in nothing. Restricted to kind: skill — §4.3b makes a playbook's
            // action_scope declarative rather than a grant, so demanding one would
            // require a field that bounds nothing.
            if ("skill".equals(kind) && Boolean.TRUE.equals(unit.loadEligible())
                    && unit.actionScope() == null) {
                errors.add(ctx + ": kind 'skill' with 'load_eligible: true' MUST declare"
                        + " an 'action_scope' — it is authorised to act and bounded in"
                        + " nothing (§4.3c)");
            }

            // §4.3a (v0.31, RFC-0029): the explicit negative scope. Two lints, both
            // warnings — a deny never widens anything, so a slip here fails safe, but a
            // slip is still worth naming: an empty deny prohibits nothing; a token BOTH
            // allowed and forbidden leaves a dead allow (deny overrides allow,
            // fail-closed). A deny that is a narrower glob of an allow is the intended
            // carve-out and is NOT flagged; only an exact-token collision is.
            ActionScope ownScope = unit.actionScope();
            DenyScope ownDeny = ownScope != null ? ownScope.deny() : null;
            if (ownDeny != null) {
                boolean forbidsAnything =
                        (ownDeny.tools() != null && !ownDeny.tools().isEmpty())
                        || (ownDeny.paths() != null && !ownDeny.paths().isEmpty())
                        || (ownDeny.capabilities() != null && !ownDeny.capabilities().isEmpty());
                if (!forbidsAnything) {
                    warnings.add(ctx + ": 'action_scope.deny' is declared but empty — it prohibits nothing (§4.3a)");
                }
                checkDenyOverlap(ctx, "tools", ownScope.tools(), ownDeny.tools(), warnings);
                checkDenyOverlap(ctx, "paths", ownScope.paths(), ownDeny.paths(), warnings);
                checkDenyOverlap(ctx, "capabilities", ownScope.capabilities(), ownDeny.capabilities(), warnings);
            }

            if (!"playbook".equals(kind)) {
                // steps on a non-playbook is a category error, not a silent no-op: the
                // author declared a composition the protocol will never enact.
                if (unit.steps() != null) {
                    warnings.add(ctx + ": declares 'steps' but kind is '" + kind
                            + "'; steps are only enacted for kind: playbook (§4.3b)");
                }
                continue;
            }

            // A playbook MUST declare steps, and the list MUST be non-empty. An empty
            // composition is not a degenerate executable — it is a manifest error.
            if (unit.steps() == null || unit.steps().isEmpty()) {
                errors.add(ctx + ": kind 'playbook' MUST declare a non-empty 'steps' list (§4.3b)");
                continue;
            }

            Set<String> stepIds = new HashSet<>();
            for (PlaybookStep step : unit.steps()) {
                String sctx = ctx + " step '" + step.id() + "'";

                if (!stepIds.add(step.id())) {
                    errors.add(ctx + ": duplicate step id '" + step.id() + "' (§4.3b)");
                }

                if (step.uses() == null && step.action() == null) {
                    errors.add(sctx + ": MUST declare either 'uses' or 'action' (§4.3b)");
                }

                if (step.uses() != null) {
                    String target = unitKinds.get(step.uses());
                    if (target == null) {
                        // An error, not a warning: a resolvable `uses` is the whole
                        // justification for playbook being a distinct kind. A dangling
                        // reference that lints clean reduces the playbook to an
                        // executable with worse ergonomics.
                        errors.add(sctx + ": 'uses' names unit '" + step.uses()
                                + "', which is not declared in this manifest (§4.3b)");
                    } else if ("playbook".equals(target)) {
                        // Nesting is forbidden pending RFC-0027 OQ1. As a warning it
                        // would be no guard: nested playbooks form a combined
                        // depends_on graph the per-playbook cycle check never sees.
                        errors.add(sctx + ": 'uses' names playbook '" + step.uses()
                                + "'; playbook nesting is not permitted (§4.3b, RFC-0027 OQ1)");
                    } else if ("executable".equals(target) || "service".equals(target)
                            || !Set.of("skill", "knowledge", "policy", "schema").contains(target)) {
                        // These kinds can never be eligible (C4), so such a step can
                        // never be enacted — stronger than "should have been a skill".
                        errors.add(sctx + ": 'uses' names '" + step.uses() + "' of kind '"
                                + target + "', which can never be invoke-eligible (§4.3c, C4)");
                    } else if (!"skill".equals(target)) {
                        warnings.add(sctx + ": 'uses' names '" + step.uses() + "' of kind '"
                                + target + "'; SHOULD name a kind: skill unit (§4.3b)");
                    }

                    // §4.3c — the rule this RFC exists for. Eligibility does not compose.
                    if (unitKinds.containsKey(step.uses()) && !isEligible.test(step.uses())) {
                        if (Boolean.TRUE.equals(unit.loadEligible())) {
                            errors.add(sctx + ": 'uses' names '" + step.uses() + "', which is"
                                    + " not invoke-eligible — a grant on a playbook does not"
                                    + " reach the units its steps name, so this playbook"
                                    + " cannot be enacted as written (§4.3c)");
                        } else {
                            // The playbook cannot be enacted at all, so the inner defect
                            // is not reachable; an error would bury the real problem.
                            warnings.add(sctx + ": 'uses' names '" + step.uses() + "', which"
                                    + " is not invoke-eligible; this playbook is itself"
                                    + " ungranted, so fix that first (§4.3c)");
                        }
                    }
                }

                if (step.onFailure() != null && !VALID_ON_FAILURE.contains(step.onFailure())) {
                    errors.add(sctx + ": 'on_failure' must be one of [abort, continue, escalate], got '"
                            + step.onFailure() + "'");
                }

                // Checked against the manifest's declared scale rather than a hardcoded
                // vocabulary — §3.13 makes authority_level_scale a per-manifest
                // declaration, and the v0.27 check already works that way.
                if (step.authorityLevel() != null && !declaredLevels.isEmpty()
                        && !declaredLevels.contains(step.authorityLevel())) {
                    warnings.add(sctx + ": 'authority_level' value '" + step.authorityLevel()
                            + "' is not in the declared 'authority_level_scale' (§3.13)");
                }

                // §4.3b (v0.32, RFC-0030): a step whose used unit's allowlist is entirely
                // contained in the effective deny (playbook deny ∪ skill deny) for a
                // dimension is self-nullified on that dimension — it reads enactable but
                // cannot act. A warning: denying never widens anything, so the slip fails
                // safe, but a dead step is worth naming.
                if (step.uses() != null) {
                    KnowledgeUnit denyTarget = unitsById.get(step.uses());
                    ActionScope targetScope = denyTarget != null ? denyTarget.actionScope() : null;
                    if (targetScope != null) {
                        for (String dim : List.of("tools", "paths", "capabilities")) {
                            List<String> allowed = switch (dim) {
                                case "tools" -> targetScope.tools();
                                case "paths" -> targetScope.paths();
                                default -> targetScope.capabilities();
                            };
                            if (allowed != null && !allowed.isEmpty()
                                    && allowed.stream().allMatch(token -> effectiveDeniesToken(
                                            java.util.Arrays.asList(unit.actionScope(), targetScope), dim, token))) {
                                warnings.add(sctx + ": every '" + dim + "' entry '" + step.uses()
                                        + "' allows is denied by the effective deny (playbook ∪ skill)"
                                        + " — the step is self-nullified on '" + dim + "' (§4.3b, RFC-0030)");
                            }
                        }
                    }
                }

                // A step whose unit can mutate but which declares no ceiling is bounded
                // only by the enacting agent's own grant — looser than intended (§4.3b).
                if (step.authorityLevel() == null && step.uses() != null) {
                    KnowledgeUnit target = unitsById.get(step.uses());
                    ActionScope scope = target != null ? target.actionScope() : null;
                    boolean mutating = scope != null
                            && ((scope.paths() != null && !scope.paths().isEmpty())
                                || scope.spend() != null);
                    if (mutating) {
                        warnings.add(sctx + ": omits 'authority_level' while '" + step.uses()
                                + "' declares a mutating action_scope; the step is bounded"
                                + " only by the enacting agent (§4.3b)");
                    }
                }
            }

            for (PlaybookStep step : unit.steps()) {
                if (step.dependsOn() == null) continue;
                for (String dep : step.dependsOn()) {
                    if (!stepIds.contains(dep)) {
                        errors.add(ctx + " step '" + step.id()
                                + "': depends_on names unknown step '" + dep + "' (§4.3b)");
                    }
                }
            }

            List<String> cycle = findStepCycle(unit.steps());
            if (cycle != null) {
                errors.add(ctx + ": 'depends_on' graph contains a cycle: "
                        + String.join(" -> ", cycle) + " (§4.3b)");
            }

            // §4.3b: the step-scope union is computable only when every step uses a unit
            // and every such unit declares an action_scope. Report a declared scope as
            // unverified rather than passing it silently — a declaration that lints
            // clean reads as checked.
            long inline = unit.steps().stream().filter(s -> s.uses() == null).count();

            // §4.3c: an inline step names no unit, so nothing bounds what it may touch,
            // and a playbook has no computable action_scope of its own. Granting one
            // would make it the only construct in KCP that acts with no scope at all.
            if (inline > 0 && Boolean.TRUE.equals(unit.loadEligible())) {
                errors.add(ctx + ": an invoke-eligible playbook MUST NOT declare inline"
                        + " ('action') steps — " + inline + " found, and an inline step is"
                        + " bounded by nothing (§4.3c)");
            }

            if (inline > 0) {
                warnings.add(ctx + ": " + inline + " of " + unit.steps().size()
                        + " step(s) are inline ('action'); an inline step has no action_scope"
                        + " and is bounded only by its authority_level (§4.3b)");
            }
            if (unit.actionScope() != null) {
                long scopeless = unit.steps().stream()
                        .filter(s -> s.uses() != null)
                        .filter(s -> {
                            KnowledgeUnit t2 = unitsById.get(s.uses());
                            return t2 == null || t2.actionScope() == null;
                        }).count();
                if (inline > 0 || scopeless > 0) {
                    warnings.add(ctx + ": declared 'action_scope' is UNVERIFIED — the"
                            + " step-scope union is not computable (" + inline
                            + " inline step(s), " + scopeless
                            + " step(s) whose unit declares no action_scope) (§4.3b)");
                }
            }
        }

        // Root-level delegation validation
        validateDelegation(manifest.delegation(), null, "manifest", errors, warnings);

        // Root-level compliance validation
        validateCompliance(manifest.compliance(), "manifest", errors, warnings);

        // Root-level discovery validation (§RFC-0012)
        validateDiscovery(manifest.discovery(), unitIds, "manifest", errors, warnings);

        // Root-level authority validation (§RFC-0009)
        validateAuthority(manifest.authority(), "manifest", warnings);

        // Root-level visibility validation (§RFC-0009)
        validateVisibility(manifest.visibility(), "manifest", warnings);

        // Warn if any unit requires auth but no root-level auth block is present (§7)
        boolean hasProtectedUnits = manifest.units().stream()
                .anyMatch(u -> "authenticated".equals(u.access()) || "restricted".equals(u.access()));
        if (hasProtectedUnits && (manifest.auth() == null || manifest.auth().methods().isEmpty())) {
            warnings.add("manifest: units with access 'authenticated' or 'restricted' exist but no 'auth' block is declared");
        }

        // §4.11: 'access' declares the authentication gate only. An auth block whose only
        // method is 'none' can never satisfy a protected unit — the incoherent pattern a
        // payment-as-access confusion produces.
        if (hasProtectedUnits && manifest.auth() != null && !manifest.auth().methods().isEmpty()
                && manifest.auth().methods().stream().allMatch(m -> "none".equals(m.type()))) {
            warnings.add("manifest: units with access 'authenticated' or 'restricted' exist but the 'auth' block declares only method 'none' — no credential can satisfy the gate. If these units are pay-per-request rather than credential-gated, use access 'public' with a 'payment' block (§4.11/§4.14)");
        }

        // Agent attestation requirements validation (§3.2, v0.22)
        var ar = manifest.trust() != null ? manifest.trust().agentRequirements() : null;
        if (ar != null) {
            if (ar.attestationUrl() != null && !ar.attestationUrl().startsWith("https://")) {
                warnings.add("manifest: trust.agent_requirements.attestation_url SHOULD use HTTPS, got '" + ar.attestationUrl() + "'");
            }
            if (ar.attestationJwks() != null && !ar.attestationJwks().startsWith("https://")) {
                warnings.add("manifest: trust.agent_requirements.attestation_jwks SHOULD use HTTPS, got '" + ar.attestationJwks() + "'");
            }
            if (Boolean.TRUE.equals(ar.requireAttestation())
                    && ar.trustedProviders().isEmpty() && ar.attestationUrl() == null) {
                warnings.add("manifest: trust.agent_requirements.require_attestation is true but neither "
                        + "trusted_providers nor attestation_url is declared — the requirement cannot be satisfied");
            }
            if (Boolean.TRUE.equals(ar.propagateToGoverned())) {
                boolean hasGoverns = manifest.relationships().stream().anyMatch(r -> "governs".equals(r.type()))
                        || manifest.manifests().stream().anyMatch(m -> "governs".equals(m.relationship()));
                if (!hasGoverns) {
                    warnings.add("manifest: trust.agent_requirements.propagate_to_governed is true but the "
                            + "manifest declares no 'governs' relationship — nothing to propagate to");
                }
            }
        }

        // Trust provenance / audit validation (§3.2, v0.23)
        if (manifest.trust() != null) {
            var prov = manifest.trust().provenance();
            if (prov != null && prov.publisherDid() != null && !prov.publisherDid().startsWith("did:")) {
                warnings.add("manifest: trust.provenance.publisher_did SHOULD be a DID (start with 'did:'), got '"
                        + prov.publisherDid() + "'");
            }
            var au = manifest.trust().audit();
            if (au != null && Boolean.TRUE.equals(au.providesAccessReceipts()) && au.receiptFormat() == null) {
                warnings.add("manifest: trust.audit.provides_access_receipts is true but no receipt_format is declared");
            }
        }

        for (Relationship rel : manifest.relationships()) {
            String p = "relationship '" + rel.fromId() + "' -> '" + rel.toId() + "'";
            if (!unitIds.contains(rel.fromId())) {
                warnings.add(p + ": 'from' references unknown unit '" + rel.fromId() + "'");
            }
            if (!unitIds.contains(rel.toId())) {
                warnings.add(p + ": 'to' references unknown unit '" + rel.toId() + "'");
            }
            if (!VALID_RELATIONSHIP_TYPES.contains(rel.type())) {
                warnings.add(p + ": 'type' must be one of " + sorted(VALID_RELATIONSHIP_TYPES) + ", got '" + rel.type() + "'");
            }
        }

        // Federation validation (§3.6)
        Set<String> manifestIds = new HashSet<>();
        for (ManifestRef ref : manifest.manifests()) {
            String p = "manifests['" + ref.id() + "']";
            if (ref.id() == null || ref.id().isBlank()) {
                errors.add("manifests: entry missing required 'id'");
                continue;
            }
            if (!ID_PATTERN.matcher(ref.id()).matches()) {
                errors.add(p + ": 'id' must match ^[a-z0-9.\\-]+$, got '" + ref.id() + "'");
            }
            if (!manifestIds.add(ref.id())) {
                errors.add(p + ": duplicate manifest id");
            }
            if (ref.url() == null || ref.url().isBlank()) {
                errors.add(p + ": 'url' is required");
            } else if (!ref.url().startsWith("https://")) {
                errors.add(p + ": 'url' must use HTTPS, got '" + ref.url() + "'");
            }
            if (ref.relationship() != null && !VALID_MANIFEST_RELATIONSHIPS.contains(ref.relationship())) {
                warnings.add(p + ": unknown 'relationship' value '" + ref.relationship() + "'");
            }
            if (ref.updateFrequency() != null && !VALID_UPDATE_FREQUENCIES.contains(ref.updateFrequency())) {
                warnings.add(p + ": unknown 'update_frequency' value '" + ref.updateFrequency() + "'");
            }
            if (ref.versionPolicy() != null && !VALID_VERSION_POLICIES.contains(ref.versionPolicy())) {
                warnings.add(p + ": unknown 'version_policy' value '" + ref.versionPolicy() + "'; treating as 'compatible'");
            }
            if (ref.versionPin() != null && ref.versionPolicy() == null) {
                warnings.add(p + ": 'version_pin' is set but 'version_policy' is not declared; defaulting to 'compatible'");
            }
            // Federation: context and agent_identity (§3.6, RFC-0011, v0.24)
            if (ref.context() != null && ref.context().isEmpty()) {
                warnings.add(p + ": context is present but empty; an entry valid in no environment is likely a mistake "
                        + "(omit context to mean 'all environments')");
            }
            if (ref.agentIdentity() != null) {
                AgentIdentity ai = ref.agentIdentity();
                if (Boolean.TRUE.equals(ai.required()) && (ai.credentialHint() == null || ai.credentialHint().isBlank())) {
                    warnings.add(p + ": agent_identity.required is true but no credential_hint is declared "
                            + "(agents are told a credential is needed but not which kind)");
                }
                if (ai.issuerHint() != null && !ai.issuerHint().isBlank()
                        && ai.credentialHint() != null && !"oauth2".equals(ai.credentialHint())) {
                    warnings.add(p + ": agent_identity.issuer_hint is only meaningful for credential_hint 'oauth2', "
                            + "got '" + ai.credentialHint() + "'");
                }
            }
        }

        // Validate external_depends_on references in units
        for (KnowledgeUnit unit : manifest.units()) {
            String p = "unit '" + unit.id() + "'";
            for (ExternalDependency extDep : unit.externalDependsOn()) {
                String ep = p + ".external_depends_on['" + extDep.manifest() + "/" + extDep.unit() + "']";
                if (extDep.manifest() == null || extDep.manifest().isBlank()) {
                    errors.add(ep + ": 'manifest' is required");
                } else if (!manifestIds.contains(extDep.manifest())) {
                    warnings.add(ep + ": references unknown manifest id '" + extDep.manifest() + "'");
                }
                if (extDep.unit() == null || extDep.unit().isBlank()) {
                    errors.add(ep + ": 'unit' is required");
                }
                if (extDep.onFailure() != null && !VALID_ON_FAILURE_VALUES.contains(extDep.onFailure())) {
                    warnings.add(ep + ": unknown 'on_failure' value '" + extDep.onFailure() + "'; treating as 'skip'");
                }
            }
        }

        // Validate external_relationships
        for (ExternalRelationship extRel : manifest.externalRelationships()) {
            String ep = "external_relationship['" + extRel.fromUnit() + "' -> '" + extRel.toUnit() + "']";
            if (extRel.fromUnit() == null || extRel.fromUnit().isBlank()) {
                errors.add(ep + ": 'from_unit' is required");
            }
            if (extRel.toUnit() == null || extRel.toUnit().isBlank()) {
                errors.add(ep + ": 'to_unit' is required");
            }
            if (extRel.type() == null || extRel.type().isBlank()) {
                errors.add(ep + ": 'type' is required");
            }
            if (extRel.fromManifest() != null && !manifestIds.contains(extRel.fromManifest()) && !extRel.fromManifest().isBlank()) {
                warnings.add(ep + ": 'from_manifest' references unknown manifest id '" + extRel.fromManifest() + "'");
            }
            if (extRel.toManifest() != null && !manifestIds.contains(extRel.toManifest()) && !extRel.toManifest().isBlank()) {
                warnings.add(ep + ": 'to_manifest' references unknown manifest id '" + extRel.toManifest() + "'");
            }
        }

        // --- Temporal validation (§4.22 unit-level; §3.6 manifests[].temporal) ---
        // Root-level temporal provides defaults; unit-level overrides field-by-field.
        String today = LocalDate.now().toString();
        Temporal root = manifest.temporal();
        Map<String, String> unitSuccessor = new HashMap<>();
        for (KnowledgeUnit unit : manifest.units()) {
            Temporal ut = unit.temporal();
            String vf = ut != null && ut.validFrom() != null ? ut.validFrom() : (root != null ? root.validFrom() : null);
            String vu = ut != null && ut.validUntil() != null ? ut.validUntil() : (root != null ? root.validUntil() : null);
            String sb = ut != null && ut.supersededBy() != null ? ut.supersededBy() : (root != null ? root.supersededBy() : null);
            if (vf != null && vu != null && vu.compareTo(vf) < 0) {
                warnings.add("unit '" + unit.id() + "': temporal.valid_until '" + vu + "' precedes valid_from '" + vf
                        + "' (empty validity window — the unit can never be active)");
            }
            if (vu != null && vu.compareTo(today) < 0 && sb == null) {
                warnings.add("unit '" + unit.id() + "': temporal.valid_until '" + vu
                        + "' is in the past and no superseded_by is set (stale unit with no successor)");
            }
            // superseded_by may use namespace:id to target an unresolved include (§4.22).
            if (sb != null && !sb.contains(":")) {
                if (!unitIds.contains(sb)) {
                    warnings.add("unit '" + unit.id() + "': temporal.superseded_by references unknown unit '" + sb + "'");
                } else {
                    unitSuccessor.put(unit.id(), sb);
                }
            }
            Discovery disc = unit.discovery();
            if (disc != null && "verified".equals(disc.verificationStatus()) && disc.verifiedBy() == null) {
                warnings.add("unit '" + unit.id()
                        + "': discovery.verification_status is 'verified' but discovery.verified_by is absent");
            }
        }
        for (String cid : supersededCycleIds(unitSuccessor)) {
            errors.add("temporal.superseded_by cycle detected involving unit '" + cid + "'");
        }
        if (manifest.discovery() != null && "verified".equals(manifest.discovery().verificationStatus())
                && manifest.discovery().verifiedBy() == null) {
            warnings.add("manifest: discovery.verification_status is 'verified' but discovery.verified_by is absent");
        }

        // Federation: manifests[].temporal (§3.6, RFC-0021).
        Map<String, String> refSuccessor = new HashMap<>();
        for (ManifestRef ref : manifest.manifests()) {
            Temporal t = ref.temporal();
            if (t == null) continue;
            if (t.validFrom() != null && t.validUntil() != null && t.validUntil().compareTo(t.validFrom()) < 0) {
                warnings.add("manifests['" + ref.id() + "']: temporal.valid_until '" + t.validUntil()
                        + "' precedes valid_from '" + t.validFrom() + "' (empty validity window)");
            }
            if (t.validUntil() != null && t.validUntil().compareTo(today) < 0 && t.supersededBy() == null) {
                warnings.add("manifests['" + ref.id() + "']: temporal.valid_until '" + t.validUntil()
                        + "' is in the past and no superseded_by is set (stale federation link)");
            }
            if (t.supersededBy() != null) {
                if (!manifestIds.contains(t.supersededBy())) {
                    warnings.add("manifests['" + ref.id() + "']: temporal.superseded_by references unknown manifests[].id '"
                            + t.supersededBy() + "'");
                } else {
                    refSuccessor.put(ref.id(), t.supersededBy());
                }
            }
        }
        for (String cid : supersededCycleIds(refSuccessor)) {
            errors.add("manifests[].temporal.superseded_by cycle detected involving '" + cid + "'");
        }

        // Payment + rate_limits validation (§4.14/§4.15, RFC-0005, v0.25) — root and per-unit.
        validateEconomics("manifest", manifest.payment(), manifest.rateLimits(), warnings);
        for (KnowledgeUnit unit : manifest.units()) {
            validateEconomics("unit '" + unit.id() + "'", unit.payment(), unit.rateLimits(), warnings);
        }

        // Unit aliases (§4.2a, RFC-0023, v0.26): char rule, uniqueness across ids + aliases, cap.
        java.util.Set<String> seenIdentifiers = new java.util.HashSet<>();
        for (KnowledgeUnit unit : manifest.units()) {
            if (unit.id() != null) seenIdentifiers.add(unit.id());
        }
        for (KnowledgeUnit unit : manifest.units()) {
            List<String> aliases = unit.aliases();
            if (aliases == null) continue;
            if (aliases.size() > 100) {
                warnings.add("unit '" + unit.id() + "': declares " + aliases.size() + " aliases (RECOMMENDED max 100)");
            }
            for (String alias : aliases) {
                if (!ALIAS_PATTERN.matcher(alias).matches()) {
                    warnings.add("unit '" + unit.id() + "': alias '" + alias + "' must match "
                            + ALIAS_PATTERN.pattern() + " (lowercase letters, digits, dots, hyphens, underscores)");
                }
                if (!seenIdentifiers.add(alias)) {
                    warnings.add("unit '" + unit.id() + "': alias '" + alias
                            + "' collides with an existing unit id or alias (must be unique across all ids and aliases)");
                }
            }
        }

        // Serving endpoint binding (§3.12, RFC-0024, v0.26): entries MUST be HTTPS.
        if (manifest.serving() != null) {
            java.util.List<Map.Entry<String, List<String>>> lists = List.of(
                    Map.entry("serving.manifest", manifest.serving().manifest() == null ? List.<String>of() : manifest.serving().manifest()),
                    Map.entry("serving.mcp", manifest.serving().mcp() == null ? List.<String>of() : manifest.serving().mcp()));
            for (Map.Entry<String, List<String>> e : lists) {
                for (String url : e.getValue()) {
                    if (url == null || !url.startsWith("https://")) {
                        errors.add("manifest: " + e.getKey() + " entry '" + url + "' must be an HTTPS URL");
                    }
                }
            }
        }

        // §3.13 (RFC-0025, v0.27): authority_level_scale, task_types[], agents[], grant_ceiling.
        Set<String> knownAuthorityLevels = manifest.authorityLevelScale() != null
                ? new HashSet<>(manifest.authorityLevelScale()) : Set.of();

        Set<String> taskTypeIds = new HashSet<>();
        for (TaskType tt : manifest.taskTypes()) {
            String ttId = tt.id() != null ? tt.id() : "";
            if (ttId.isBlank()) {
                errors.add("A task_types[] entry is missing required field 'id'");
            } else if (!taskTypeIds.add(ttId)) {
                errors.add("Duplicate task_types[].id: '" + ttId + "'");
            }
            checkAuthorityLevel("task_types['" + ttId + "']", tt.authorityLevel(), knownAuthorityLevels, warnings);
            if (manifest.authorityLevelScale() != null && tt.authorityLevel() == null && manifest.grantCeiling() == null) {
                warnings.add("task_types['" + ttId + "']: authority_ceiling_undeclared — 'authority_level_scale' is "
                        + "declared at manifest root but this task-type declares neither 'authority_level' nor a 'grant_ceiling'");
            }
        }

        Set<String> agentIds = new HashSet<>();
        for (Agent agent : manifest.agents()) {
            String agentId = agent.id() != null ? agent.id() : "";
            if (agentId.isBlank()) {
                errors.add("An agents[] entry is missing required field 'id'");
            } else if (!agentIds.add(agentId)) {
                errors.add("Duplicate agents[].id: '" + agentId + "'");
            }
            checkAuthorityLevel("agents['" + agentId + "']", agent.authorityLevel(), knownAuthorityLevels, warnings);
        }

        for (KnowledgeUnit unit : manifest.units()) {
            checkAuthorityLevel("Unit '" + unit.id() + "'", unit.authorityLevel(), knownAuthorityLevels, warnings);
        }

        if (manifest.grantCeiling() != null) {
            GrantCeiling gc = manifest.grantCeiling();
            Set<String> sourceIds = new HashSet<>();
            // Visited-set across the reference chain — mirrors supersededCycleIds' discipline
            // (§4.22, §3.11). In the current schema, unit_ref/task_type_ref/agent_ref resolve to a
            // scalar authority_level with no further chaining, so a cycle cannot yet occur — this
            // guard exists to fail closed if a future extension adds nested grant_ceiling references.
            Set<String> visiting = new HashSet<>();

            for (GrantCeilingSource src : gc.sources()) {
                String sp = "grant_ceiling.sources";
                String srcId = src.id() != null ? src.id() : "";
                if (srcId.isBlank()) {
                    errors.add(sp + ": an entry is missing required field 'id'");
                } else if (!sourceIds.add(srcId)) {
                    errors.add(sp + ": duplicate source id '" + srcId + "'");
                }

                int refCount = (src.unitRef() != null ? 1 : 0) + (src.taskTypeRef() != null ? 1 : 0)
                        + (src.agentRef() != null ? 1 : 0);
                if (src.authorityLevel() != null && refCount > 0) {
                    errors.add(sp + "['" + srcId + "']: 'authority_level' is mutually exclusive with "
                            + "unit_ref/task_type_ref/agent_ref");
                }
                if (src.authorityLevel() == null && refCount == 0) {
                    errors.add(sp + "['" + srcId + "']: must declare exactly one of authority_level, unit_ref, "
                            + "task_type_ref, agent_ref");
                }
                checkAuthorityLevel(sp + "['" + srcId + "']", src.authorityLevel(), knownAuthorityLevels, warnings);

                if (src.unitRef() != null) {
                    if (visiting.contains("unit:" + src.unitRef())) {
                        errors.add(sp + "['" + srcId + "']: grant_ceiling reference cycle detected at unit '"
                                + src.unitRef() + "'");
                    } else if (!unitIds.contains(src.unitRef())) {
                        errors.add(sp + "['" + srcId + "']: 'unit_ref' references unknown unit '" + src.unitRef() + "'");
                    }
                }
                if (src.taskTypeRef() != null) {
                    if (visiting.contains("task_type:" + src.taskTypeRef())) {
                        errors.add(sp + "['" + srcId + "']: grant_ceiling reference cycle detected at task_type '"
                                + src.taskTypeRef() + "'");
                    } else if (!taskTypeIds.contains(src.taskTypeRef())) {
                        errors.add(sp + "['" + srcId + "']: 'task_type_ref' references unknown task_types[].id '"
                                + src.taskTypeRef() + "'");
                    }
                }
                if (src.agentRef() != null) {
                    if (visiting.contains("agent:" + src.agentRef())) {
                        errors.add(sp + "['" + srcId + "']: grant_ceiling reference cycle detected at agent '"
                                + src.agentRef() + "'");
                    } else if (!agentIds.contains(src.agentRef())) {
                        errors.add(sp + "['" + srcId + "']: 'agent_ref' references unknown agents[].id '"
                                + src.agentRef() + "'");
                    }
                }
            }

            if (gc.mandatorySources() != null) {
                for (String mandatoryId : gc.mandatorySources()) {
                    if (!sourceIds.contains(mandatoryId)) {
                        errors.add("grant_ceiling.sources: missing mandatory source '" + mandatoryId
                                + "' declared in grant_ceiling.mandatory_sources");
                    }
                }
            }
        }

        return new ValidationResult(errors, warnings);
    }

    private static void checkAuthorityLevel(String ctx, String level, Set<String> knownAuthorityLevels, List<String> warnings) {
        if (level != null && !knownAuthorityLevels.isEmpty() && !knownAuthorityLevels.contains(level)) {
            warnings.add(ctx + ": 'authority_level' value '" + level + "' is not in the declared 'authority_level_scale'");
        }
    }

    /**
     * §4.3a (v0.31, RFC-0029): warn when a token is both allowlisted and forbidden on the same
     * dimension. Deny overrides allow, fail-closed, so the allow entry is dead — the scope reads
     * wider than it enforces. Only exact-token collisions are flagged; a narrower deny glob of a
     * broader allow (schema/** allowed, schema/secrets/** forbidden) is the intended carve-out.
     */
    private static void checkDenyOverlap(String ctx, String dim, List<String> allow, List<String> deny, List<String> warnings) {
        if (deny == null || deny.isEmpty()) return;
        Set<String> allowed = allow != null ? new HashSet<>(allow) : Set.of();
        for (String token : deny) {
            if (allowed.contains(token)) {
                warnings.add(ctx + ": 'action_scope." + dim + "' allows '" + token + "' while 'deny." + dim
                        + "' denies it — the allow entry is neutralized; deny overrides allow, fail-closed (§4.3a)");
            }
        }
    }

    /**
     * §4.3a (v0.31, RFC-0029): does a skill's {@code action_scope.deny} deny {@code token} on
     * {@code dimension}? Fail-closed override — a deny entry denies the token even when the
     * allowlist grants it. Exact-string match. Mirrors {@code deniesToken} in the TypeScript
     * validator, so a runtime enforcer and the validator's overlap lint share one rule.
     */
    public static boolean deniesToken(ActionScope scope, String dimension, String token) {
        if (scope == null || scope.deny() == null) return false;
        List<String> values = switch (dimension) {
            case "tools" -> scope.deny().tools();
            case "paths" -> scope.deny().paths();
            case "capabilities" -> scope.deny().capabilities();
            default -> null;
        };
        if (values == null) return false;
        // §4.3a (v0.32.1): paths are PATTERNS matched structurally — exact comparison
        // would never fire the schema/secrets/** carve-out. tools/capabilities exact.
        if ("paths".equals(dimension)) {
            for (String p : values) {
                if (p.equals(token) || pathGlobMatches(p, token)) return true;
            }
            return false;
        }
        return values.contains(token);
    }

    /**
     * §4.3a (v0.32.1): glob matching for path patterns. {@code **} matches across
     * segment boundaries, {@code *} within a single segment, every other character
     * literally. Mirrors {@code pathGlobMatches} in the TypeScript validator.
     */
    public static boolean pathGlobMatches(String pattern, String path) {
        StringBuilder re = new StringBuilder();
        int i = 0;
        while (i < pattern.length()) {
            if (pattern.startsWith("**", i)) {
                re.append(".*");
                i += 2;
            } else if (pattern.charAt(i) == '*') {
                re.append("[^/]*");
                i += 1;
            } else {
                re.append(java.util.regex.Pattern.quote(String.valueOf(pattern.charAt(i))));
                i += 1;
            }
        }
        return path.matches(re.toString());
    }

    /**
     * §4.3b (v0.32, RFC-0030): does the UNION of deny lists deny {@code token} on
     * {@code dimension}? The effective denylist for a playbook step is the union of the
     * playbook's own {@code action_scope.deny} and the used skill's — a match in either
     * denies, overriding any allow. Union is the only sound composition: adding a source
     * can only refuse more, never less (the scope-axis mirror of the §3.13 lowest-of rule).
     * Mirrors {@code effectiveDeniesToken} in the TypeScript validator.
     */
    public static boolean effectiveDeniesToken(List<ActionScope> scopes, String dimension, String token) {
        for (ActionScope scope : scopes) {
            if (deniesToken(scope, dimension, token)) return true;
        }
        return false;
    }

    /**
     * Resolve one grant_ceiling source to an authority_level, given the manifest to look up
     * unit_ref/task_type_ref/agent_ref against. Returns {@code null} if the source is a reference
     * to an entity with no declared authority_level (non-binding — absence is not a grant, §3.13).
     */
    private static String resolveSourceLevel(KnowledgeManifest manifest, GrantCeilingSource source) {
        // NOTE: deliberately not Stream.map(...).findFirst() — the JDK's FindOps sink calls
        // Optional.of(value) unconditionally on a match, which throws NPE the moment the mapped
        // field (authorityLevel here) is itself null. A plain loop sidesteps that JDK gotcha.
        if (source.authorityLevel() != null) return source.authorityLevel();
        if (source.unitRef() != null) {
            for (KnowledgeUnit u : manifest.units()) {
                if (source.unitRef().equals(u.id())) return u.authorityLevel();
            }
            return null;
        }
        if (source.taskTypeRef() != null) {
            for (TaskType t : manifest.taskTypes()) {
                if (source.taskTypeRef().equals(t.id())) return t.authorityLevel();
            }
            return null;
        }
        if (source.agentRef() != null) {
            for (Agent a : manifest.agents()) {
                if (source.agentRef().equals(a.id())) return a.authorityLevel();
            }
            return null;
        }
        return null;
    }

    /**
     * Result of {@link #computeGrantCeiling}: the effective authority level (or {@code null} if
     * no source is binding), and the full set of source ids that tied for the minimum.
     */
    public record GrantCeilingResult(String effectiveLevel, List<String> bindingSourceIds) {
        public GrantCeilingResult {
            bindingSourceIds = List.copyOf(bindingSourceIds);
        }
    }

    /**
     * Compute the effective authority_level for a manifest's grant_ceiling — the minimum across
     * all resolved sources, with the source(s) that produced it named for the audit trail (§3.13,
     * RFC-0025). Ordering of {@code authority_level_scale} defines the total order used for the
     * minimum; a source that resolves outside the declared scale is ignored (non-binding),
     * matching the "absence of a declared ceiling is not itself a grant" rule.
     */
    public static GrantCeilingResult computeGrantCeiling(KnowledgeManifest manifest) {
        List<String> scale = manifest.authorityLevelScale() != null ? manifest.authorityLevelScale() : List.of();
        Map<String, Integer> rank = new HashMap<>();
        for (int i = 0; i < scale.size(); i++) rank.put(scale.get(i), i);

        GrantCeiling gc = manifest.grantCeiling();
        if (gc == null) return new GrantCeilingResult(null, List.of());

        int minRank = Integer.MAX_VALUE;
        List<Map.Entry<String, Integer>> resolved = new ArrayList<>();
        for (GrantCeilingSource src : gc.sources()) {
            String level = resolveSourceLevel(manifest, src);
            if (level == null || !rank.containsKey(level)) continue; // non-binding
            int r = rank.get(level);
            resolved.add(Map.entry(src.id(), r));
            if (r < minRank) minRank = r;
        }
        if (minRank == Integer.MAX_VALUE) return new GrantCeilingResult(null, List.of());

        final int finalMinRank = minRank;
        List<String> bindingSourceIds = resolved.stream()
                .filter(e -> e.getValue() == finalMinRank)
                .map(Map.Entry::getKey)
                .toList();
        return new GrantCeilingResult(scale.get(minRank), bindingSourceIds);
    }

    // Normative §3.13/§4.17 capping table (SPEC.md §3.13): caps an authority.<action> permission
    // by an effective authority_level. Copied verbatim from the spec's 5x5 table.
    private static final Map<String, Map<String, String>> AUTHORITY_LEVEL_CAPS = Map.of(
            "observe", Map.of(
                    "read", "initiative", "summarize", "requires_approval", "modify", "denied",
                    "share_externally", "denied", "execute", "denied"),
            "explain", Map.of(
                    "read", "initiative", "summarize", "initiative", "modify", "denied",
                    "share_externally", "denied", "execute", "denied"),
            "suggest", Map.of(
                    "read", "initiative", "summarize", "initiative", "modify", "requires_approval",
                    "share_externally", "denied", "execute", "denied"),
            "prepare", Map.of(
                    "read", "initiative", "summarize", "initiative", "modify", "requires_approval",
                    "share_externally", "requires_approval", "execute", "requires_approval"),
            "commit", Map.of(
                    "read", "initiative", "summarize", "initiative", "modify", "initiative",
                    "share_externally", "initiative", "execute", "initiative")
    );
    private static final Map<String, Integer> PERMISSION_RANK = Map.of(
            "denied", 0, "requires_approval", 1, "initiative", 2);

    /**
     * Normative §3.13/§4.17 capping table: caps an {@code authority} action permission by an
     * effective {@code authority_level}. Returns the stricter of the unit's own declared value and
     * the table's cap (using the denied &lt; requires_approval &lt; initiative order), never
     * widening it.
     */
    public static String applyAuthorityCap(String declaredValue, String action, String effectiveLevel) {
        if (effectiveLevel == null || !AUTHORITY_LEVEL_CAPS.containsKey(effectiveLevel)) return declaredValue;
        String cap = AUTHORITY_LEVEL_CAPS.get(effectiveLevel).get(action);
        if (cap == null || declaredValue == null) return declaredValue;
        int capRank = PERMISSION_RANK.getOrDefault(cap, 2);
        int declaredRank = PERMISSION_RANK.getOrDefault(declaredValue, 2);
        return declaredRank <= capRank ? declaredValue : cap;
    }

    private static final Set<String> VALID_PAYMENT_METHOD_TYPES = Set.of("free", "x402", "meter", "subscription");
    private static final Set<String> VALID_BACKOFF = Set.of("linear", "exponential", "none");
    private static final java.util.regex.Pattern DECIMAL_STRING = java.util.regex.Pattern.compile("^\\d+(\\.\\d+)?$");

    private static void validateEconomics(String where, Payment payment, RateLimits rateLimits, List<String> warnings) {
        if (payment != null && payment.methods() != null) {
            for (PaymentMethod m : payment.methods()) {
                if (m.type() != null && !VALID_PAYMENT_METHOD_TYPES.contains(m.type())) {
                    warnings.add(where + ": payment.methods[] has unknown type '" + m.type()
                            + "' (expected free, x402, meter, or subscription)");
                }
                if ("x402".equals(m.type())) {
                    if (m.currency() == null || m.currency().isBlank()) {
                        warnings.add(where + ": payment x402 method is missing required 'currency'");
                    }
                    if (m.pricePerRequest() == null || m.pricePerRequest().isBlank()) {
                        warnings.add(where + ": payment x402 method is missing required 'price_per_request'");
                    } else if (!DECIMAL_STRING.matcher(m.pricePerRequest()).matches()) {
                        warnings.add(where + ": payment x402 price_per_request '" + m.pricePerRequest()
                                + "' SHOULD be a decimal string (e.g. \"0.001\")");
                    }
                }
            }
            String tier = payment.defaultTier();
            boolean hasPaid = payment.methods().stream().anyMatch(m -> m.type() != null && !"free".equals(m.type()));
            if (("metered".equals(tier) || "subscription".equals(tier)) && !payment.methods().isEmpty() && !hasPaid) {
                warnings.add(where + ": payment.default_tier is '" + tier
                        + "' but no paid method (x402/meter/subscription) is declared");
            }
        }
        if (rateLimits != null && rateLimits.backoff() != null && !VALID_BACKOFF.contains(rateLimits.backoff())) {
            warnings.add(where + ": rate_limits.backoff must be one of [linear, exponential, none], got '"
                    + rateLimits.backoff() + "'");
        }
    }

    /**
     * Detect cycles in the depends_on graph using DFS.
     * Per SPEC.md §4.7, cycles are silently ignored (no error or warning).
     *
     * @return The set of edges (as "from-&gt;to" strings) that would close a cycle.
     */
    /**
     * Cycle detection for single-successor {@code superseded_by} chains
     * (§4.22, §3.6). Returns the ids participating in a cycle, sorted.
     */
    private static List<String> supersededCycleIds(Map<String, String> successor) {
        Set<String> cycle = new HashSet<>();
        Map<String, Integer> state = new HashMap<>(); // absent/0 = unvisited, 1 = in-path, 2 = done
        for (String start : successor.keySet()) {
            if (state.getOrDefault(start, 0) == 2) continue;
            List<String> path = new ArrayList<>();
            String node = start;
            while (node != null && successor.containsKey(node) && state.getOrDefault(node, 0) != 2) {
                if (state.getOrDefault(node, 0) == 1) {
                    for (String id : path.subList(path.indexOf(node), path.size())) cycle.add(id);
                    break;
                }
                state.put(node, 1);
                path.add(node);
                node = successor.get(node);
            }
            for (String id : path) if (state.getOrDefault(id, 0) == 1) state.put(id, 2);
        }
        List<String> result = new ArrayList<>(cycle);
        result.sort(null);
        return result;
    }

    static Set<String> detectCycles(List<KnowledgeUnit> units, Set<String> unitIds) {
        Map<String, List<String>> adj = new HashMap<>();
        for (KnowledgeUnit unit : units) {
            List<String> deps = unit.dependsOn().stream()
                    .filter(unitIds::contains)
                    .toList();
            adj.put(unit.id(), deps);
        }

        Set<String> cycleEdges = new HashSet<>();
        Map<String, Integer> state = new HashMap<>();
        for (String id : unitIds) {
            state.put(id, 0);
        }

        for (String id : unitIds) {
            if (state.get(id) == 0) {
                dfs(id, adj, state, cycleEdges);
            }
        }

        return cycleEdges;
    }

    private static void dfs(String node, Map<String, List<String>> adj,
                            Map<String, Integer> state, Set<String> cycleEdges) {
        state.put(node, 1);
        for (String dep : adj.getOrDefault(node, List.of())) {
            int depState = state.getOrDefault(dep, 0);
            if (depState == 1) {
                cycleEdges.add(node + "->" + dep);
            } else if (depState == 0) {
                dfs(dep, adj, state, cycleEdges);
            }
        }
        state.put(node, 2);
    }

    private static void validateDelegation(Delegation delegation, Delegation rootDelegation,
                                              String prefix, List<String> errors, List<String> warnings) {
        if (delegation == null) return;
        // human_in_the_loop is an object per SPEC.md §3.4 — validate approval_mechanism if present
        if (delegation.humanInTheLoop() != null) {
            String mech = delegation.humanInTheLoop().approvalMechanism();
            if (mech != null && !VALID_HITL_MECHANISMS.contains(mech)) {
                errors.add(prefix + ": delegation.human_in_the_loop.approval_mechanism must be one of " +
                        sorted(VALID_HITL_MECHANISMS) + ", got '" + mech + "'");
            }
        }
        if (rootDelegation != null && delegation.maxDepth() != null && rootDelegation.maxDepth() != null) {
            if (delegation.maxDepth() > rootDelegation.maxDepth()) {
                errors.add(prefix + ": unit delegation.max_depth (" + delegation.maxDepth() +
                        ") must not exceed root delegation.max_depth (" + rootDelegation.maxDepth() + ")");
            }
        }
    }

    private static void validateCompliance(Compliance compliance, String prefix,
                                           List<String> errors, List<String> warnings) {
        if (compliance == null) return;
        if (compliance.sensitivity() != null && !VALID_SENSITIVITY_VALUES.contains(compliance.sensitivity())) {
            errors.add(prefix + ": compliance.sensitivity must be one of " +
                    sorted(VALID_SENSITIVITY_VALUES) + ", got '" + compliance.sensitivity() + "'");
        }
    }

    private static void validateDiscovery(Discovery discovery, Set<String> unitIds,
                                          String prefix, List<String> errors, List<String> warnings) {
        if (discovery == null) return;
        String status = discovery.verificationStatus();
        Double confidence = discovery.confidence();

        // verification_status must be a known value
        if (status != null && !VALID_VERIFICATION_STATUSES.contains(status)) {
            warnings.add(prefix + ": discovery.verification_status must be one of " +
                    sorted(VALID_VERIFICATION_STATUSES) + ", got '" + status + "'");
        }

        // source must be a known value
        if (discovery.source() != null && !VALID_DISCOVERY_SOURCES.contains(discovery.source())) {
            warnings.add(prefix + ": discovery.source must be one of " +
                    sorted(VALID_DISCOVERY_SOURCES) + ", got '" + discovery.source() + "'");
        }

        // rumored MUST have confidence < 0.5 (normative — MUST = error)
        if ("rumored".equals(status) && confidence != null && confidence >= 0.5) {
            errors.add(prefix + ": discovery.verification_status=rumored but confidence=" +
                    confidence + " (MUST be < 0.5, rule: rumored-confidence-ceiling)");
        }

        // declared SHOULD have confidence in [0.5, 0.8) (normative, RFC-0018 §5.1)
        if ("declared".equals(status) && confidence != null && (confidence < 0.5 || confidence >= 0.8)) {
            warnings.add(prefix + ": discovery.verification_status=declared but confidence=" +
                    confidence + " (SHOULD be in [0.5, 0.8))");
        }

        // verified SHOULD have confidence >= 0.8 (normative)
        if ("verified".equals(status) && confidence != null && confidence < 0.8) {
            warnings.add(prefix + ": discovery.verification_status=verified but confidence=" +
                    confidence + " (SHOULD be >= 0.8)");
        }

        // verified_at SHOULD NOT be set when status is rumored, declared, or observed
        if (discovery.verifiedAt() != null &&
                ("rumored".equals(status) || "declared".equals(status) || "observed".equals(status))) {
            warnings.add(prefix + ": discovery.verified_at is set but verification_status='" +
                    status + "' (SHOULD only be set for verified units)");
        }

        // contradicted_by must reference a known unit id
        if (discovery.contradictedBy() != null && !unitIds.contains(discovery.contradictedBy())) {
            warnings.add(prefix + ": discovery.contradicted_by references unknown unit '" +
                    discovery.contradictedBy() + "'");
        }
    }

    private static void validateAuthority(no.cantara.kcp.model.Authority authority,
                                          String prefix, List<String> warnings) {
        if (authority == null) return;
        Map<String, String> actions = Map.of(
                "read", authority.read() != null ? authority.read() : "",
                "summarize", authority.summarize() != null ? authority.summarize() : "",
                "modify", authority.modify() != null ? authority.modify() : "",
                "share_externally", authority.shareExternally() != null ? authority.shareExternally() : "",
                "execute", authority.execute() != null ? authority.execute() : ""
        );
        actions.forEach((action, value) -> {
            if (!value.isEmpty() && !VALID_AUTHORITY_VALUES.contains(value)) {
                warnings.add(prefix + ": authority." + action + " must be one of " +
                        sorted(VALID_AUTHORITY_VALUES) + ", got '" + value + "'");
            }
        });
    }

    private static void validateVisibility(no.cantara.kcp.model.Visibility visibility,
                                           String prefix, List<String> warnings) {
        if (visibility == null) return;
        if (visibility.defaultSensitivity() != null &&
                !VALID_VISIBILITY_DEFAULTS.contains(visibility.defaultSensitivity())) {
            warnings.add(prefix + ": visibility.default must be one of " +
                    sorted(VALID_VISIBILITY_DEFAULTS) + ", got '" + visibility.defaultSensitivity() + "'");
        }
    }

    private static void validateContentHash(ContentHash ch, String unitPath, Path manifestDir,
                                            String prefix, List<String> errors) {
        if (ch == null) return;
        if (ch.algorithm() == null || !HASH_ALGORITHMS.containsKey(ch.algorithm())) {
            errors.add(prefix + ": content_hash.algorithm must be one of "
                    + String.join(", ", HASH_ALGORITHMS.keySet().stream().sorted().toList()));
            return;
        }
        if (ch.value() == null || !HEX_PATTERN.matcher(ch.value()).matches()) {
            errors.add(prefix + ": content_hash.value must be a hex digest");
            return;
        }
        if (manifestDir == null || unitPath == null || unitPath.isEmpty()) return;
        Path resolved = manifestDir.resolve(unitPath);
        if (!Files.exists(resolved)) return; // missing path already warned elsewhere
        String observed = computeContentDigest(resolved, ch.algorithm());
        if (!ch.value().toLowerCase().equals(observed)) {
            String observedLabel = observed == null ? "unreadable" : observed.substring(0, 12) + "…";
            errors.add(prefix + ": content_hash does not match content on disk (declared "
                    + ch.value().substring(0, Math.min(12, ch.value().length())) + "…, observed "
                    + observedLabel + "); run kcp sign --update-hashes before signing");
        }
    }

    /**
     * RFC-0019 §3.2 digest: a file hashes its raw bytes; a directory hashes the
     * bytewise-sorted concatenation of {@code relpath \0 hexdigest \n} entries over
     * every regular file beneath it (symlinks not followed, no exclusions). Returns
     * {@code null} when the target is missing or unreadable (fails closed at the
     * caller). Mirrors computeContentDigest in the TypeScript and Python validators.
     */
    public static String computeContentDigest(Path target, String algorithm) {
        String jcaName = HASH_ALGORITHMS.get(algorithm);
        if (jcaName == null) return null;
        try {
            if (Files.isRegularFile(target)) {
                return hexDigest(jcaName, Files.readAllBytes(target));
            }
            if (!Files.isDirectory(target)) return null;
            // resolve a symlinked root before walking (TS readdir and Python
            // scandir follow the root link; children are still lstat-skipped)
            Path root = target.toRealPath();
            List<String> entries = new ArrayList<>();
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isRegularFile()) {
                        entries.add(root.relativize(file).toString().replace('\\', '/'));
                    }
                    return FileVisitResult.CONTINUE; // symlinks not followed (walkFileTree default)
                }
            });
            entries.sort((a, b) -> Arrays.compare(
                    a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8)));
            MessageDigest digest = MessageDigest.getInstance(jcaName);
            for (String entry : entries) {
                String fileHex = hexDigest(jcaName, Files.readAllBytes(root.resolve(entry)));
                digest.update((entry + "\0" + fileHex + "\n").getBytes(StandardCharsets.UTF_8));
            }
            return toHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException | SecurityException e) {
            return null;
        }
    }

    private static String hexDigest(String jcaName, byte[] bytes) throws NoSuchAlgorithmException {
        return toHex(MessageDigest.getInstance(jcaName).digest(bytes));
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static void validateContentStructure(ContentStructure cs, String prefix, List<String> warnings) {
        if (cs == null) return;
        if (cs.primary() != null && !VALID_CONTENT_MODALITIES.contains(cs.primary())) {
            warnings.add(prefix + ": content_structure.primary has unknown value '" +
                    cs.primary() + "'; expected one of " + sorted(VALID_CONTENT_MODALITIES));
        }
        for (String modality : cs.contains()) {
            if (!VALID_CONTENT_MODALITIES.contains(modality)) {
                warnings.add(prefix + ": content_structure.contains has unknown value '" +
                        modality + "'; expected one of " + sorted(VALID_CONTENT_MODALITIES));
            }
        }
        if (cs.density() != null && !VALID_DENSITY.contains(cs.density())) {
            warnings.add(prefix + ": content_structure.density has unknown value '" +
                    cs.density() + "'; expected one of " + sorted(VALID_DENSITY));
        }
    }

    private static List<String> sorted(Set<String> set) {
        return set.stream().sorted().toList();
    }

    /**
     * §4.3b (v0.29): find a cycle in a playbook's explicit {@code depends_on} graph,
     * returning the cycle path for the error message, or null if the graph is acyclic.
     *
     * <p>Only explicit edges are walked. The implicit "after the previous step in
     * declaration order" default cannot produce a cycle — declaration order is total —
     * so materialising it would add edges that are never a defect and would obscure
     * which edges the author actually wrote.
     *
     * <p>Iterative rather than recursive: a manifest is untrusted input, and a deep
     * chain must report a cycle rather than exhaust the stack.
     */
    private static List<String> findStepCycle(List<PlaybookStep> steps) {
        Map<String, List<String>> edges = new LinkedHashMap<>();
        for (PlaybookStep s : steps) {
            edges.put(s.id(), s.dependsOn() != null ? s.dependsOn() : List.of());
        }
        final int WHITE = 0, GREY = 1, BLACK = 2;
        Map<String, Integer> colour = new HashMap<>();
        for (PlaybookStep s : steps) colour.put(s.id(), WHITE);

        for (PlaybookStep root : steps) {
            if (colour.get(root.id()) != WHITE) continue;
            Deque<String> path = new ArrayDeque<>();
            Deque<int[]> cursor = new ArrayDeque<>();
            path.addLast(root.id());
            cursor.addLast(new int[]{0});
            colour.put(root.id(), GREY);
            while (!path.isEmpty()) {
                String current = path.peekLast();
                int[] idx = cursor.peekLast();
                List<String> deps = edges.getOrDefault(current, List.of());
                if (idx[0] >= deps.size()) {
                    colour.put(current, BLACK);
                    path.removeLast();
                    cursor.removeLast();
                    continue;
                }
                String dep = deps.get(idx[0]++);
                if (!edges.containsKey(dep)) continue;  // dangling; reported separately
                int c = colour.get(dep);
                if (c == GREY) {
                    // Grey means dep is on the current path — slice from it.
                    List<String> asList = new ArrayList<>(path);
                    List<String> out = new ArrayList<>(asList.subList(asList.indexOf(dep), asList.size()));
                    out.add(dep);
                    return out;
                }
                if (c == WHITE) {
                    colour.put(dep, GREY);
                    path.addLast(dep);
                    cursor.addLast(new int[]{0});
                }
            }
        }
        return null;
    }
}
