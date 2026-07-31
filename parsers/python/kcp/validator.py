import hashlib
import os
import re
from datetime import date
from pathlib import Path
from typing import NamedTuple, Optional

from .model import KnowledgeManifest


def _superseded_cycle_ids(successor: dict[str, str]) -> list[str]:
    """Detect cycles in a single-successor (functional) graph — the shape of
    ``superseded_by`` chains. Returns the ids participating in a cycle, sorted.
    Mirrors the depends_on cycle detection used elsewhere.
    """
    cycle: set[str] = set()
    state: dict[str, int] = {}  # 0/absent = unvisited, 1 = in-path, 2 = done
    for start in successor:
        if state.get(start) == 2:
            continue
        path: list[str] = []
        node: Optional[str] = start
        while node is not None and node in successor and state.get(node) != 2:
            if state.get(node) == 1:
                for i in path[path.index(node):]:
                    cycle.add(i)
                break
            state[node] = 1
            path.append(node)
            node = successor.get(node)
        for i in path:
            if state.get(i) == 1:
                state[i] = 2
    return sorted(cycle)

# RFC-0019 (draft): allowed content_hash algorithms.
HASH_ALGORITHMS = ("sha256", "sha384", "sha512")

_HEX_PATTERN = re.compile(r"^[0-9a-fA-F]+$")


def _walk_regular_files(root: str, rel: str = "") -> list[str]:
    """POSIX-relative paths of all regular files under root; symlinks not followed."""
    out: list[str] = []
    for entry in os.scandir(root):
        entry_rel = f"{rel}/{entry.name}" if rel else entry.name
        if entry.is_dir(follow_symlinks=False):
            out.extend(_walk_regular_files(entry.path, entry_rel))
        elif entry.is_file(follow_symlinks=False):
            out.append(entry_rel)
        # symlinks, sockets, etc. are neither: skipped
    return out


def compute_content_digest(target: str, algorithm: str) -> Optional[str]:
    """RFC-0019 §3.2 digest: a file hashes its raw bytes; a directory hashes
    the bytewise-sorted concatenation of ``relpath\\0hexdigest\\n`` entries
    over every regular file beneath it. No exclusions. Returns ``None`` when
    the target is missing or unreadable (fails closed at the caller).
    Mirrors computeContentDigest in the TypeScript validators.
    """
    def _hash_file(p: str) -> str:
        h = hashlib.new(algorithm)
        with open(p, "rb") as f:
            for chunk in iter(lambda: f.read(65536), b""):
                h.update(chunk)
        return h.hexdigest()

    try:
        try:
            entries = _walk_regular_files(target)
        except NotADirectoryError:
            return _hash_file(target)
        entries.sort(key=lambda r: r.encode("utf-8"))
        digest = hashlib.new(algorithm)
        for entry in entries:
            digest.update(
                f"{entry}\0{_hash_file(os.path.join(target, entry))}\n".encode("utf-8")
            )
        return digest.hexdigest()
    except Exception:
        return None

VALID_SCOPES = {"global", "project", "module"}
VALID_AUDIENCES = {"human", "agent", "developer", "operator", "architect", "devops"}
VALID_RELATIONSHIP_TYPES = {"enables", "context", "supersedes", "contradicts", "depends_on", "governs"}
VALID_KINDS = {"knowledge", "schema", "service", "policy", "executable", "skill", "playbook"}
VALID_ON_FAILURE = {"abort", "continue", "escalate"}
VALID_FORMATS = {
    "markdown", "pdf", "openapi", "json-schema", "jupyter",
    "html", "asciidoc", "rst", "vtt", "yaml", "json", "csv", "text",
}
VALID_UPDATE_FREQUENCIES = {"hourly", "daily", "weekly", "monthly", "rarely", "never"}
VALID_INDEXING_SHORTHANDS = {"open", "read-only", "no-train", "none"}
VALID_ACCESS_VALUES = {"public", "authenticated", "restricted"}
VALID_SENSITIVITY_VALUES = {"public", "internal", "confidential", "restricted"}
# human_in_the_loop is an object per spec §3.4 — no HITL enum, validation done inline
KNOWN_KCP_VERSIONS = {"0.1", "0.2", "0.3", "0.4", "0.5", "0.6", "0.7", "0.8", "0.9", "0.10", "0.11", "0.12", "0.13", "0.14", "0.16", "0.17", "0.18", "0.19", "0.20", "0.21", "0.22", "0.23", "0.24", "0.25", "0.26", "0.27", "0.28", "0.29", "0.30", "0.31", "0.32"}
# content_structure vocabularies (RFC-0016, v0.17). Unknown values warn but pass through.
VALID_CONTENT_MODALITIES = {"prose", "table", "code", "list", "diagram", "reference", "mixed"}
VALID_DENSITY = {"sparse", "normal", "dense"}
VALID_MANIFEST_RELATIONSHIPS = {"child", "foundation", "governs", "peer", "archive"}
VALID_VERIFICATION_STATUSES = {"rumored", "declared", "observed", "verified", "deprecated"}
VALID_DISCOVERY_SOURCES = {"manual", "web_traversal", "openapi", "llm_inference", "manifest-self-description"}
VALID_AUTHORITY_VALUES = {"initiative", "requires_approval", "denied"}
VALID_VISIBILITY_DEFAULTS = {"public", "internal", "confidential", "restricted"}
VALID_ON_FAILURE_VALUES = {"skip", "warn", "degrade"}
VALID_VERSION_POLICIES = {"exact", "minimum", "compatible"}
_ID_PATTERN = re.compile(r"^[a-z0-9.\-]+$")
_MAX_TRIGGER_LENGTH = 60
_MAX_TRIGGERS_PER_UNIT = 20


class ValidationResult(NamedTuple):
    """Result of validating a KCP manifest.

    ``errors``   — conditions that make the manifest invalid (MUST fix).
    ``warnings`` — conditions that are permitted but suspicious (SHOULD fix).
    """
    errors: list[str]
    warnings: list[str]

    @property
    def is_valid(self) -> bool:
        return len(self.errors) == 0


def _detect_cycles(units: list) -> set[tuple[str, str]]:
    """Detect cycles in the depends_on graph using DFS.

    Returns the set of (from_id, to_id) edges that would close a cycle.
    These edges should be silently ignored per SPEC.md section 4.7.
    """
    # Build adjacency list
    adj: dict[str, list[str]] = {}
    unit_ids = {u.id for u in units}
    for unit in units:
        adj[unit.id] = [dep for dep in unit.depends_on if dep in unit_ids]

    cycle_edges: set[tuple[str, str]] = set()
    # Track global visit state: 0 = unvisited, 1 = in current path, 2 = completed
    state: dict[str, int] = {uid: 0 for uid in unit_ids}

    def dfs(node: str, path_set: set[str]) -> None:
        state[node] = 1
        path_set.add(node)
        for dep in adj.get(node, []):
            if state[dep] == 1:
                # dep is in the current DFS path — this edge closes a cycle
                cycle_edges.add((node, dep))
            elif state[dep] == 0:
                dfs(dep, path_set)
        path_set.discard(node)
        state[node] = 2

    for uid in unit_ids:
        if state[uid] == 0:
            dfs(uid, set())

    return cycle_edges


def _find_step_cycle(steps) -> Optional[list]:
    """§4.3b (v0.29): find a cycle in a playbook's explicit ``depends_on`` graph.

    Returns the cycle path for the error message, or None if the graph is acyclic.

    Only explicit edges are walked. The implicit "after the previous step in
    declaration order" default cannot produce a cycle — declaration order is total —
    so materialising it here would add edges that are never a defect and would obscure
    which edges the author actually wrote.

    Iterative rather than recursive: a manifest is untrusted input, and a deep chain
    must report a cycle rather than exhaust the stack.
    """
    edges = {s.id: (s.depends_on or []) for s in steps}
    WHITE, GREY, BLACK = 0, 1, 2
    colour = {s.id: WHITE for s in steps}

    for root in steps:
        if colour[root.id] != WHITE:
            continue
        stack = [[root.id, 0]]
        colour[root.id] = GREY
        while stack:
            frame = stack[-1]
            deps = edges.get(frame[0], [])
            if frame[1] >= len(deps):
                colour[frame[0]] = BLACK
                stack.pop()
                continue
            dep = deps[frame[1]]
            frame[1] += 1
            if dep not in edges:
                continue  # dangling; reported separately
            if colour[dep] == GREY:
                # Grey means dep is on the current stack — slice from it for the path.
                start = next(i for i, f in enumerate(stack) if f[0] == dep)
                return [f[0] for f in stack[start:]] + [dep]
            if colour[dep] == WHITE:
                colour[dep] = GREY
                stack.append([dep, 0])
    return None


def path_glob_matches(pattern: str, path: str) -> bool:
    """§4.3a (v0.32.1): glob matching for path patterns. ``**`` matches across
    segment boundaries, ``*`` within a single segment, every other character
    literally. Mirrors ``pathGlobMatches`` in the TypeScript validator.
    """
    out = []
    i = 0
    while i < len(pattern):
        if pattern.startswith("**", i):
            out.append(".*")
            i += 2
        elif pattern[i] == "*":
            out.append("[^/]*")
            i += 1
        else:
            out.append(re.escape(pattern[i]))
            i += 1
    return re.fullmatch("".join(out), path) is not None


def denies_token(scope, dimension: str, token: str) -> bool:
    """§4.3a (v0.31, RFC-0029): does a skill's ``action_scope.deny`` deny ``token`` on
    ``dimension``? Fail-closed override — a deny entry denies the token even when the
    allowlist grants it. Mirrors ``deniesToken`` in the TypeScript validator, so a
    runtime enforcer and the validator's overlap lint share one rule. Since v0.32.1,
    ``paths`` entries are PATTERNS matched structurally (§4.3a) — exact comparison
    would never fire the ``schema/secrets/**`` carve-out; tools/capabilities remain
    exact tokens.
    """
    if scope is None or getattr(scope, "deny", None) is None:
        return False
    values = getattr(scope.deny, dimension, None)
    if not values:
        return False
    if dimension == "paths":
        return any(p == token or path_glob_matches(p, token) for p in values)
    return token in values


def effective_denies_token(scopes, dimension: str, token: str) -> bool:
    """§4.3b (v0.32, RFC-0030): does the UNION of deny lists deny ``token`` on
    ``dimension``? The effective denylist for a playbook step is the union of the
    playbook's own ``action_scope.deny`` and the used skill's — a match in either
    denies, overriding any allow. Union is the only sound composition: adding a
    source can only refuse more, never less (the scope-axis mirror of the §3.13
    lowest-of rule). Mirrors ``effectiveDeniesToken`` in the TypeScript validator.
    """
    return any(denies_token(scope, dimension, token) for scope in scopes)


def validate(manifest: KnowledgeManifest, manifest_dir: Optional[str] = None) -> ValidationResult:
    """Validate a parsed KnowledgeManifest.

    Args:
        manifest: The parsed manifest to validate.
        manifest_dir: Optional directory containing the manifest file. When provided,
            the validator checks that declared unit paths exist on disk and emits
            warnings for missing paths (SPEC.md section 4.3 / section 7).

    Returns a :class:`ValidationResult` with separate ``errors`` and ``warnings`` lists.
    """
    errors: list[str] = []
    warnings: list[str] = []

    # #166: problems the parser noticed and no later stage can reconstruct. Warnings
    # rather than errors — a malformed value or an unknown field leaves a valid manifest
    # that simply does not say what its author thought it said.
    warnings.extend(manifest.parse_diagnostics or [])
    unit_ids = {u.id for u in manifest.units}

    # Cycle detection (§4.7) — detect and silently ignore cycle-closing edges.
    # No error or warning is required by the spec, but we run the detection
    # so that traversal code can rely on it.
    _detect_cycles(manifest.units)

    # kcp_version — RECOMMENDED; warn if missing or unknown
    if not manifest.kcp_version:
        warnings.append("manifest: 'kcp_version' not declared; assuming 0.8")
    elif manifest.kcp_version not in KNOWN_KCP_VERSIONS:
        warnings.append(
            f"manifest: unknown kcp_version '{manifest.kcp_version}'; "
            f"processing as {max(KNOWN_KCP_VERSIONS)}"
        )

    # Required root fields
    if not manifest.project:
        errors.append("manifest: 'project' is required")
    if not manifest.units:
        errors.append("manifest: 'units' must not be empty")

    # Duplicate ID detection (§7: SHOULD warn, use first occurrence)
    seen_ids: set[str] = set()

    for unit in manifest.units:
        p = f"unit '{unit.id}'"
        if not unit.id:
            errors.append("unit: 'id' is required")
            continue

        # Duplicate ID check
        if unit.id in seen_ids:
            warnings.append(f"{p}: duplicate 'id' (first occurrence takes precedence)")
        seen_ids.add(unit.id)

        # ID format check (§4.2: lowercase a-z, digits, hyphens, dots)
        if not _ID_PATTERN.match(unit.id):
            warnings.append(
                f"{p}: 'id' should contain only lowercase a-z, digits, hyphens, and dots"
            )

        if not unit.path:
            errors.append(f"{p}: 'path' is required")
        elif manifest_dir is not None:
            # Path existence check (§4.3 / §7: SHOULD warn if path does not exist)
            resolved = Path(manifest_dir) / unit.path
            if not resolved.exists():
                warnings.append(f"{p}: path '{unit.path}' does not exist")
        if not unit.intent:
            errors.append(f"{p}: 'intent' is required")
        if not unit.scope:
            errors.append(f"{p}: 'scope' is required")
        elif unit.scope not in VALID_SCOPES:
            errors.append(
                f"{p}: 'scope' must be one of {sorted(VALID_SCOPES)}, got '{unit.scope}'"
            )
        invalid_audience = set(unit.audience) - VALID_AUDIENCES
        if invalid_audience:
            warnings.append(
                f"{p}: unknown audience value(s): {sorted(invalid_audience)}"
            )

        # kind validation (§4.3a)
        if unit.kind is not None and unit.kind not in VALID_KINDS:
            warnings.append(
                f"{p}: unknown 'kind' value '{unit.kind}'"
            )

        # format validation (§4.4a)
        if unit.format is not None and unit.format not in VALID_FORMATS:
            warnings.append(
                f"{p}: unknown 'format' value '{unit.format}'"
            )

        # update_frequency validation (§4.6b)
        if unit.update_frequency is not None and unit.update_frequency not in VALID_UPDATE_FREQUENCIES:
            warnings.append(
                f"{p}: unknown 'update_frequency' value '{unit.update_frequency}'"
            )

        # indexing validation (§4.6c)
        if unit.indexing is not None and isinstance(unit.indexing, str):
            if unit.indexing not in VALID_INDEXING_SHORTHANDS:
                warnings.append(
                    f"{p}: unknown 'indexing' shorthand '{unit.indexing}'"
                )

        for dep in unit.depends_on:
            if dep not in unit_ids:
                warnings.append(f"{p}: 'depends_on' references unknown unit '{dep}'")

        # Trigger constraints (§4.9)
        if len(unit.triggers) > _MAX_TRIGGERS_PER_UNIT:
            warnings.append(
                f"{p}: more than {_MAX_TRIGGERS_PER_UNIT} triggers "
                f"({len(unit.triggers)}); excess will be ignored"
            )
        for trigger in unit.triggers:
            if len(trigger) > _MAX_TRIGGER_LENGTH:
                warnings.append(
                    f"{p}: trigger '{trigger[:30]}...' exceeds {_MAX_TRIGGER_LENGTH} characters"
                )

        # access validation (§4.11)
        if unit.access is not None and unit.access not in VALID_ACCESS_VALUES:
            warnings.append(
                f"{p}: unknown 'access' value '{unit.access}'; treating as 'restricted'"
            )

        # auth_scope validation (§4.11)
        if unit.auth_scope is not None and unit.access != "restricted":
            warnings.append(
                f"{p}: 'auth_scope' is only meaningful when access is 'restricted'"
            )

        # sensitivity validation (§4.12)
        if unit.sensitivity is not None and unit.sensitivity not in VALID_SENSITIVITY_VALUES:
            warnings.append(
                f"{p}: unknown 'sensitivity' value '{unit.sensitivity}'"
            )

        # delegation validation (§3.4)
        if unit.delegation is not None:
            hitl = unit.delegation.human_in_the_loop
            if hitl is not None and not isinstance(hitl, dict):
                errors.append(
                    f"{p}: delegation.human_in_the_loop must be an object "
                    f"(with optional 'required' and 'approval_mechanism' fields), got '{hitl}'"
                )
            if isinstance(hitl, dict) and hitl.get("approval_mechanism") not in (
                    None, "oauth_consent", "uma", "custom"):
                errors.append(
                    f"{p}: delegation.human_in_the_loop.approval_mechanism must be one of "
                    f"['oauth_consent', 'uma', 'custom'], got '{hitl.get('approval_mechanism')}'"
                )
            if (manifest.delegation is not None
                    and unit.delegation.max_depth is not None
                    and manifest.delegation.max_depth is not None
                    and unit.delegation.max_depth > manifest.delegation.max_depth):
                errors.append(
                    f"{p}: unit delegation.max_depth ({unit.delegation.max_depth}) "
                    f"must not exceed root delegation.max_depth ({manifest.delegation.max_depth})"
                )

        # compliance validation (§3.5)
        if unit.compliance is not None:
            if (unit.compliance.sensitivity is not None
                    and unit.compliance.sensitivity not in VALID_SENSITIVITY_VALUES):
                errors.append(
                    f"{p}: compliance.sensitivity must be one of "
                    f"{sorted(VALID_SENSITIVITY_VALUES)}, got '{unit.compliance.sensitivity}'"
                )

        # hints validation (§4.10)
        if unit.hints is not None:
            h = unit.hints
            if h.get("summary_available") is True and not h.get("summary_unit"):
                warnings.append(f"{p}: summary_available is true but no summary_unit declared")
            summary_unit = h.get("summary_unit")
            if isinstance(summary_unit, str) and summary_unit not in unit_ids:
                warnings.append(
                    f"{p}: summary_unit references non-existent unit '{summary_unit}'"
                )
            chunk_of = h.get("chunk_of")
            if isinstance(chunk_of, str) and chunk_of not in unit_ids:
                warnings.append(
                    f"{p}: chunk_of references non-existent unit '{chunk_of}'"
                )
            if h.get("chunk_index") is not None and not h.get("chunk_of"):
                warnings.append(f"{p}: chunk_index is present without chunk_of")

        # discovery validation (§RFC-0012)
        _validate_discovery(unit.discovery, unit_ids, p, errors, warnings)

        # authority validation (§RFC-0009)
        _validate_authority(unit.authority, p, warnings)

        # visibility validation (§RFC-0009)
        _validate_visibility(unit.visibility, p, warnings)

        # not_for validation (RFC-0015, v0.17)
        if unit.not_for_strict is not None and not unit.not_for:
            warnings.append(f"{p}: 'not_for_strict' is set but 'not_for' is empty or absent")

        # content_structure validation (RFC-0016, v0.17) — warn on unknown values, pass through
        _validate_content_structure(unit.content_structure, p, warnings)

        # content_hash validation (RFC-0019, draft) — shape, then recompute
        # against disk when a manifest directory is available (§3.1: "kcp
        # validate recomputes and compares"). A stale hash is an error, not a
        # warning: signing over it would brick the unit for every consumer.
        if unit.content_hash is not None:
            ch = unit.content_hash
            if ch.algorithm is None or ch.algorithm not in HASH_ALGORITHMS:
                errors.append(
                    f"{p}: content_hash.algorithm must be one of {', '.join(HASH_ALGORITHMS)}"
                )
            elif not ch.value or not _HEX_PATTERN.match(ch.value):
                errors.append(f"{p}: content_hash.value must be a hex digest")
            elif manifest_dir is not None and unit.path:
                resolved = Path(manifest_dir) / unit.path
                if resolved.exists():
                    observed = compute_content_digest(str(resolved), ch.algorithm)
                    if observed != ch.value.lower():
                        observed_label = f"{observed[:12]}…" if observed else "unreadable"
                        errors.append(
                            f"{p}: content_hash does not match content on disk "
                            f"(declared {ch.value[:12]}…, observed {observed_label}); "
                            f"run kcp sign --update-hashes before signing"
                        )

    # Root-level delegation validation
    # --- kind: playbook — §4.3b (v0.29, RFC-0027) ---
    #
    # Deliberately a second pass over the units. `uses` may name a unit declared later
    # in the manifest, so resolving it inside the loop above would reject forward
    # references the spec permits.
    unit_kinds = {u.id: (u.kind or "knowledge") for u in manifest.units}
    units_by_id = {u.id: u for u in manifest.units}
    # §4.3c (RFC-0028): eligibility is a property of the unit that declares it and does
    # NOT compose — a grant on a playbook does not reach the units its steps name.
    # Absent means not eligible: a governed procedure fails closed.
    GOVERNED = {"skill", "playbook"}

    def _eligible(uid: str) -> bool:
        u = units_by_id.get(uid)
        return bool(u is not None and u.load_eligible is True)
    declared_levels = set(manifest.authority_level_scale or [])

    for unit in manifest.units:
        ctx = f"unit '{unit.id}'"

        # §4.3c: the grant is defined only for the kinds that act. Declaring it
        # elsewhere is a category error — no renderer may ever mark those kinds
        # eligible (C4), so it cannot mean what the author intended.
        if unit.load_eligible is not None and (unit.kind or "knowledge") not in GOVERNED:
            errors.append(
                f"{ctx}: 'load_eligible' is only defined for kind: skill and "
                f"kind: playbook, not '{unit.kind or 'knowledge'}' (§4.3c)"
            )

        # §4.3c: a granted skill with no action_scope is authorised to act and bounded
        # in nothing. Restricted to kind: skill — §4.3b makes a playbook's action_scope
        # declarative rather than a grant, so demanding one would require a field that
        # bounds nothing; a playbook is bounded by the units its steps use.
        if unit.kind == "skill" and unit.load_eligible is True and unit.action_scope is None:
            errors.append(
                f"{ctx}: kind 'skill' with 'load_eligible: true' MUST declare an "
                f"'action_scope' — it is authorised to act and bounded in nothing (§4.3c)"
            )

        # §4.3a (v0.31, RFC-0029): the explicit negative scope. Two lints, both warnings —
        # a deny never widens anything, so a slip here fails safe, but a slip is still
        # worth naming:
        #  - an empty ``deny`` prohibits nothing (an authoring slip: the author reached
        #    for a prohibition and declared none);
        #  - a token that is BOTH allowed and forbidden. Deny overrides allow, fail-closed,
        #    so the allow entry is dead — the scope reads wider than it enforces. A deny
        #    that is a narrower glob of an allow (schema/** allowed, schema/secrets/**
        #    forbidden) is the intended carve-out and is NOT flagged; only an exact-token
        #    collision is.
        deny = unit.action_scope.deny if unit.action_scope is not None else None
        if deny is not None:
            forbids_anything = bool(deny.tools or deny.paths or deny.capabilities)
            if not forbids_anything:
                warnings.append(
                    f"{ctx}: 'action_scope.deny' is declared but empty — it prohibits nothing (§4.3a)"
                )
            for dim in ("tools", "paths", "capabilities"):
                allowed = set(getattr(unit.action_scope, dim) or [])
                for token in getattr(deny, dim) or []:
                    if token in allowed:
                        warnings.append(
                            f"{ctx}: 'action_scope.{dim}' allows '{token}' while 'deny.{dim}' denies it "
                            f"— the allow entry is neutralized; deny overrides allow, fail-closed (§4.3a)"
                        )

        if (unit.kind or "knowledge") != "playbook":
            # steps on a non-playbook is a category error, not a silent no-op: the
            # author declared a composition the protocol will never enact.
            if unit.steps is not None:
                warnings.append(
                    f"{ctx}: declares 'steps' but kind is "
                    f"'{unit.kind or 'knowledge'}'; steps are only enacted for "
                    f"kind: playbook (§4.3b)"
                )
            continue

        # A playbook MUST declare steps, and the list MUST be non-empty. An empty
        # composition is not a degenerate executable — it is a manifest error.
        if not unit.steps:
            errors.append(
                f"{ctx}: kind 'playbook' MUST declare a non-empty 'steps' list (§4.3b)"
            )
            continue

        step_ids: set[str] = set()
        for step in unit.steps:
            sctx = f"{ctx} step '{step.id}'"

            if step.id in step_ids:
                errors.append(f"{ctx}: duplicate step id '{step.id}' (§4.3b)")
            step_ids.add(step.id)

            if step.uses is None and step.action is None:
                errors.append(f"{sctx}: MUST declare either 'uses' or 'action' (§4.3b)")

            if step.uses is not None:
                target = unit_kinds.get(step.uses)
                if target is None:
                    # An error, not a warning: a resolvable `uses` is the whole
                    # justification for playbook being a distinct kind. A dangling
                    # reference that lints clean reduces the playbook to an
                    # executable with worse ergonomics.
                    errors.append(
                        f"{sctx}: 'uses' names unit '{step.uses}', which is not "
                        f"declared in this manifest (§4.3b)"
                    )
                elif target == "playbook":
                    # Nesting is forbidden pending RFC-0027 OQ1. As a warning it
                    # would be no guard: nested playbooks form a combined depends_on
                    # graph that the per-playbook cycle check never sees.
                    errors.append(
                        f"{sctx}: 'uses' names playbook '{step.uses}'; playbook "
                        f"nesting is not permitted (§4.3b, RFC-0027 OQ1)"
                    )
                elif target in ("executable", "service") or target not in (
                    "skill", "knowledge", "policy", "schema"
                ):
                    # These kinds can never be eligible (C4), so such a step can never
                    # be enacted — stronger than "should have been a skill".
                    errors.append(
                        f"{sctx}: 'uses' names '{step.uses}' of kind '{target}', which "
                        f"can never be invoke-eligible (§4.3c, C4)"
                    )
                elif target != "skill":
                    warnings.append(
                        f"{sctx}: 'uses' names '{step.uses}' of kind '{target}'; "
                        f"SHOULD name a kind: skill unit (§4.3b)"
                    )

                # §4.3c — the rule this RFC exists for. Eligibility does not compose.
                if step.uses in unit_kinds and not _eligible(step.uses):
                    if unit.load_eligible is True:
                        errors.append(
                            f"{sctx}: 'uses' names '{step.uses}', which is not "
                            f"invoke-eligible — a grant on a playbook does not reach the "
                            f"units its steps name, so this playbook cannot be enacted "
                            f"as written (§4.3c)"
                        )
                    else:
                        # The playbook cannot be enacted at all, so the inner defect is
                        # not yet reachable; an error here would bury the real problem.
                        warnings.append(
                            f"{sctx}: 'uses' names '{step.uses}', which is not "
                            f"invoke-eligible; this playbook is itself ungranted, so fix "
                            f"that first (§4.3c)"
                        )

            if step.on_failure is not None and step.on_failure not in VALID_ON_FAILURE:
                errors.append(
                    f"{sctx}: 'on_failure' must be one of "
                    f"[abort, continue, escalate], got '{step.on_failure}'"
                )

            # Checked against the manifest's declared scale rather than a hardcoded
            # vocabulary — §3.13 makes authority_level_scale a per-manifest
            # declaration, and the v0.27 check below already works that way.
            if (
                step.authority_level is not None
                and declared_levels
                and step.authority_level not in declared_levels
            ):
                warnings.append(
                    f"{sctx}: 'authority_level' value '{step.authority_level}' is "
                    f"not in the declared 'authority_level_scale' (§3.13)"
                )

            # §4.3b (v0.32, RFC-0030): a step whose used unit's allowlist is entirely
            # contained in the effective deny (playbook deny ∪ skill deny) for a
            # dimension is self-nullified on that dimension — it reads enactable but
            # cannot act. A warning: denying never widens anything, so the slip fails
            # safe, but a dead step is worth naming.
            if step.uses is not None:
                target_scope = getattr(units_by_id.get(step.uses), "action_scope", None)
                if target_scope is not None:
                    for dim in ("tools", "paths", "capabilities"):
                        allowed = getattr(target_scope, dim, None) or []
                        if allowed and all(
                            effective_denies_token(
                                [unit.action_scope, target_scope], dim, token
                            )
                            for token in allowed
                        ):
                            warnings.append(
                                f"{sctx}: every '{dim}' entry '{step.uses}' allows is "
                                f"denied by the effective deny (playbook ∪ skill) — the "
                                f"step is self-nullified on '{dim}' (§4.3b, RFC-0030)"
                            )

            # A step whose unit can mutate but which declares no ceiling is bounded
            # only by the enacting agent's own grant — looser than intended (§4.3b).
            if step.authority_level is None and step.uses is not None:
                scope = getattr(units_by_id.get(step.uses), "action_scope", None)
                if scope is not None and (scope.paths or scope.spend is not None):
                    warnings.append(
                        f"{sctx}: omits 'authority_level' while '{step.uses}' "
                        f"declares a mutating action_scope; the step is bounded "
                        f"only by the enacting agent (§4.3b)"
                    )

        for step in unit.steps:
            for dep in step.depends_on or []:
                if dep not in step_ids:
                    errors.append(
                        f"{ctx} step '{step.id}': depends_on names unknown step "
                        f"'{dep}' (§4.3b)"
                    )

        cycle = _find_step_cycle(unit.steps)
        if cycle:
            errors.append(
                f"{ctx}: 'depends_on' graph contains a cycle: "
                f"{' -> '.join(cycle)} (§4.3b)"
            )

        # §4.3b: the step-scope union is computable only when every step uses a unit
        # and every such unit declares an action_scope. Report a declared scope as
        # unverified rather than passing it silently — a declaration that lints clean
        # reads as checked.
        inline = sum(1 for s in unit.steps if s.uses is None)

        # §4.3c: an inline step names no unit, so nothing bounds what it may touch, and
        # a playbook has no computable action_scope of its own. Granting one would make
        # it the only construct in KCP that acts with no scope at all. Inline steps stay
        # legal on an ungranted playbook, which is what §4.3b introduced them for.
        if inline and unit.load_eligible is True:
            errors.append(
                f"{ctx}: an invoke-eligible playbook MUST NOT declare inline ('action') "
                f"steps — {inline} found, and an inline step is bounded by nothing (§4.3c)"
            )

        if inline:
            warnings.append(
                f"{ctx}: {inline} of {len(unit.steps)} step(s) are inline "
                f"('action'); an inline step has no action_scope and is bounded "
                f"only by its authority_level (§4.3b)"
            )
        if unit.action_scope is not None:
            scopeless = sum(
                1
                for s in unit.steps
                if s.uses is not None
                and getattr(units_by_id.get(s.uses), "action_scope", None) is None
            )
            if inline or scopeless:
                warnings.append(
                    f"{ctx}: declared 'action_scope' is UNVERIFIED — the step-scope "
                    f"union is not computable ({inline} inline step(s), {scopeless} "
                    f"step(s) whose unit declares no action_scope) (§4.3b)"
                )

    if manifest.delegation is not None:
        hitl = manifest.delegation.human_in_the_loop
        if hitl is not None and not isinstance(hitl, dict):
            errors.append(
                f"manifest: delegation.human_in_the_loop must be an object "
                f"(with optional 'required' and 'approval_mechanism' fields), got '{hitl}'"
            )
        if isinstance(hitl, dict) and hitl.get("approval_mechanism") not in (
                None, "oauth_consent", "uma", "custom"):
            errors.append(
                f"manifest: delegation.human_in_the_loop.approval_mechanism must be one of "
                f"['oauth_consent', 'uma', 'custom'], got '{hitl.get('approval_mechanism')}'"
            )

    # Root-level compliance validation
    if manifest.compliance is not None:
        if (manifest.compliance.sensitivity is not None
                and manifest.compliance.sensitivity not in VALID_SENSITIVITY_VALUES):
            errors.append(
                f"manifest: compliance.sensitivity must be one of "
                f"{sorted(VALID_SENSITIVITY_VALUES)}, got '{manifest.compliance.sensitivity}'"
            )

    # Root-level discovery validation (§RFC-0012)
    _validate_discovery(manifest.discovery, unit_ids, "manifest", errors, warnings)

    # Root-level authority validation (§RFC-0009)
    _validate_authority(manifest.authority, "manifest", warnings)

    # Root-level visibility validation (§RFC-0009)
    _validate_visibility(manifest.visibility, "manifest", warnings)

    # Warn if any unit requires auth but no root-level auth block is present (§7)
    has_protected = any(
        u.access in ("authenticated", "restricted") for u in manifest.units
    )
    if has_protected and (manifest.auth is None or not manifest.auth.methods):
        warnings.append(
            "manifest: units with access 'authenticated' or 'restricted' exist "
            "but no 'auth' block is declared"
        )

    # §4.11: 'access' declares the authentication gate only. An auth block whose
    # only method is 'none' can never satisfy a protected unit — the incoherent
    # pattern a payment-as-access confusion produces.
    if (
        has_protected
        and manifest.auth is not None
        and manifest.auth.methods
        and all(m.type == "none" for m in manifest.auth.methods)
    ):
        warnings.append(
            "manifest: units with access 'authenticated' or 'restricted' exist "
            "but the 'auth' block declares only method 'none' — no credential can "
            "satisfy the gate. If these units are pay-per-request rather than "
            "credential-gated, use access 'public' with a 'payment' block "
            "(§4.11/§4.14)"
        )

    # Agent attestation requirements validation (§3.2, v0.22)
    ar = manifest.trust.agent_requirements if manifest.trust else None
    if ar is not None:
        if ar.attestation_url and not ar.attestation_url.startswith("https://"):
            warnings.append(
                f"manifest: trust.agent_requirements.attestation_url SHOULD use HTTPS, got '{ar.attestation_url}'"
            )
        if ar.attestation_jwks and not ar.attestation_jwks.startswith("https://"):
            warnings.append(
                f"manifest: trust.agent_requirements.attestation_jwks SHOULD use HTTPS, got '{ar.attestation_jwks}'"
            )
        if ar.require_attestation and not ar.trusted_providers and not ar.attestation_url:
            warnings.append(
                "manifest: trust.agent_requirements.require_attestation is true but neither "
                "trusted_providers nor attestation_url is declared — the requirement cannot be satisfied"
            )
        if ar.propagate_to_governed:
            has_governs = any(r.type == "governs" for r in manifest.relationships) or any(
                m.relationship == "governs" for m in manifest.manifests
            )
            if not has_governs:
                warnings.append(
                    "manifest: trust.agent_requirements.propagate_to_governed is true but the "
                    "manifest declares no 'governs' relationship — nothing to propagate to"
                )

    # Trust provenance / audit validation (§3.2, v0.23)
    prov = manifest.trust.provenance if manifest.trust else None
    if prov is not None and prov.publisher_did and not prov.publisher_did.startswith("did:"):
        warnings.append(
            f"manifest: trust.provenance.publisher_did SHOULD be a DID (start with 'did:'), got '{prov.publisher_did}'"
        )
    audit = manifest.trust.audit if manifest.trust else None
    if audit is not None and audit.provides_access_receipts and not audit.receipt_format:
        warnings.append(
            "manifest: trust.audit.provides_access_receipts is true but no receipt_format is declared"
        )

    for rel in manifest.relationships:
        p = f"relationship '{rel.from_id}' -> '{rel.to_id}'"
        if rel.from_id not in unit_ids:
            warnings.append(f"{p}: 'from' references unknown unit '{rel.from_id}'")
        if rel.to_id not in unit_ids:
            warnings.append(f"{p}: 'to' references unknown unit '{rel.to_id}'")
        if rel.type not in VALID_RELATIONSHIP_TYPES:
            warnings.append(
                f"{p}: 'type' must be one of {sorted(VALID_RELATIONSHIP_TYPES)}, got '{rel.type}'"
            )

    # Federation validation (§3.6)
    manifest_ids: set[str] = set()
    for ref in manifest.manifests:
        p = f"manifests['{ref.id}']"
        if not ref.id:
            errors.append("manifests: entry missing required 'id'")
            continue
        if not _ID_PATTERN.match(ref.id):
            errors.append(f"{p}: 'id' must match ^[a-z0-9.\\-]+$, got '{ref.id}'")
        if ref.id in manifest_ids:
            errors.append(f"{p}: duplicate manifest id")
        manifest_ids.add(ref.id)
        if not ref.url:
            errors.append(f"{p}: 'url' is required")
        elif not ref.url.startswith("https://"):
            errors.append(f"{p}: 'url' must use HTTPS, got '{ref.url}'")
        if ref.relationship is not None and ref.relationship not in VALID_MANIFEST_RELATIONSHIPS:
            warnings.append(f"{p}: unknown 'relationship' value '{ref.relationship}'")
        if ref.update_frequency is not None and ref.update_frequency not in VALID_UPDATE_FREQUENCIES:
            warnings.append(f"{p}: unknown 'update_frequency' value '{ref.update_frequency}'")
        if ref.version_policy is not None and ref.version_policy not in VALID_VERSION_POLICIES:
            warnings.append(
                f"{p}: unknown 'version_policy' value '{ref.version_policy}'; treating as 'compatible'"
            )
        if ref.version_pin is not None and ref.version_policy is None:
            warnings.append(
                f"{p}: 'version_pin' is set but 'version_policy' is not declared; defaulting to 'compatible'"
            )
        # Federation: context and agent_identity (§3.6, RFC-0011, v0.24)
        if ref.context is not None and len(ref.context) == 0:
            warnings.append(
                f"{p}: context is present but empty; an entry valid in no environment is likely a mistake "
                "(omit context to mean 'all environments')"
            )
        if ref.agent_identity is not None:
            ai = ref.agent_identity
            if ai.required is True and not ai.credential_hint:
                warnings.append(
                    f"{p}: agent_identity.required is true but no credential_hint is declared "
                    "(agents are told a credential is needed but not which kind)"
                )
            if ai.issuer_hint and ai.credential_hint is not None and ai.credential_hint != "oauth2":
                warnings.append(
                    f"{p}: agent_identity.issuer_hint is only meaningful for credential_hint 'oauth2', "
                    f"got '{ai.credential_hint}'"
                )

    # Validate external_depends_on references in units
    for unit in manifest.units:
        p = f"unit '{unit.id}'"
        for ext_dep in unit.external_depends_on:
            ep = f"{p}.external_depends_on['{ext_dep.manifest}/{ext_dep.unit}']"
            if not ext_dep.manifest:
                errors.append(f"{ep}: 'manifest' is required")
            elif ext_dep.manifest not in manifest_ids:
                warnings.append(f"{ep}: references unknown manifest id '{ext_dep.manifest}'")
            if not ext_dep.unit:
                errors.append(f"{ep}: 'unit' is required")
            if ext_dep.on_failure and ext_dep.on_failure not in VALID_ON_FAILURE_VALUES:
                warnings.append(
                    f"{ep}: unknown 'on_failure' value '{ext_dep.on_failure}'; treating as 'skip'"
                )

    # Validate external_relationships
    for ext_rel in manifest.external_relationships:
        ep = f"external_relationship['{ext_rel.from_unit}' -> '{ext_rel.to_unit}']"
        if not ext_rel.from_unit:
            errors.append(f"{ep}: 'from_unit' is required")
        if not ext_rel.to_unit:
            errors.append(f"{ep}: 'to_unit' is required")
        if not ext_rel.type:
            errors.append(f"{ep}: 'type' is required")
        if ext_rel.from_manifest and ext_rel.from_manifest not in manifest_ids:
            warnings.append(
                f"{ep}: 'from_manifest' references unknown manifest id '{ext_rel.from_manifest}'"
            )
        if ext_rel.to_manifest and ext_rel.to_manifest not in manifest_ids:
            warnings.append(
                f"{ep}: 'to_manifest' references unknown manifest id '{ext_rel.to_manifest}'"
            )

    # --- Temporal validation (§4.22 unit-level; §3.6 manifests[].temporal) ---
    # Root-level temporal provides defaults; unit-level overrides field-by-field.
    today = date.today().isoformat()

    def _effective(t):
        r = manifest.temporal
        vf = (t.valid_from if t and t.valid_from is not None else (r.valid_from if r else None))
        vu = (t.valid_until if t and t.valid_until is not None else (r.valid_until if r else None))
        sb = (t.superseded_by if t and t.superseded_by is not None else (r.superseded_by if r else None))
        return vf, vu, sb

    unit_successor: dict[str, str] = {}
    for unit in manifest.units:
        vf, vu, sb = _effective(unit.temporal)
        if vf and vu and vu < vf:
            warnings.append(
                f"unit '{unit.id}': temporal.valid_until '{vu}' precedes valid_from '{vf}' "
                f"(empty validity window — the unit can never be active)"
            )
        if vu and vu < today and not sb:
            warnings.append(
                f"unit '{unit.id}': temporal.valid_until '{vu}' is in the past and no "
                f"superseded_by is set (stale unit with no successor)"
            )
        # superseded_by may use namespace:id to target an unresolved include (§4.22);
        # only local (non-namespaced) refs are checkable here.
        if sb and ":" not in sb:
            if sb not in unit_ids:
                warnings.append(f"unit '{unit.id}': temporal.superseded_by references unknown unit '{sb}'")
            else:
                unit_successor[unit.id] = sb
        disc = unit.discovery
        if disc and disc.verification_status == "verified" and not disc.verified_by:
            warnings.append(
                f"unit '{unit.id}': discovery.verification_status is 'verified' but "
                f"discovery.verified_by is absent"
            )
    for cid in _superseded_cycle_ids(unit_successor):
        errors.append(f"temporal.superseded_by cycle detected involving unit '{cid}'")
    if (manifest.discovery and manifest.discovery.verification_status == "verified"
            and not manifest.discovery.verified_by):
        warnings.append(
            "manifest: discovery.verification_status is 'verified' but discovery.verified_by is absent"
        )

    # Federation: manifests[].temporal (§3.6, RFC-0021)
    ref_successor: dict[str, str] = {}
    for ref in manifest.manifests:
        t = ref.temporal
        if not t:
            continue
        if t.valid_from and t.valid_until and t.valid_until < t.valid_from:
            warnings.append(
                f"manifests['{ref.id}']: temporal.valid_until '{t.valid_until}' precedes "
                f"valid_from '{t.valid_from}' (empty validity window)"
            )
        if t.valid_until and t.valid_until < today and not t.superseded_by:
            warnings.append(
                f"manifests['{ref.id}']: temporal.valid_until '{t.valid_until}' is in the past "
                f"and no superseded_by is set (stale federation link)"
            )
        if t.superseded_by:
            if t.superseded_by not in manifest_ids:
                warnings.append(
                    f"manifests['{ref.id}']: temporal.superseded_by references unknown "
                    f"manifests[].id '{t.superseded_by}'"
                )
            else:
                ref_successor[ref.id] = t.superseded_by
    for cid in _superseded_cycle_ids(ref_successor):
        errors.append(f"manifests[].temporal.superseded_by cycle detected involving '{cid}'")

    # Payment + rate_limits validation (§4.14/§4.15, RFC-0005, v0.25) — root and per-unit.
    _validate_economics("manifest", manifest.payment, manifest.rate_limits, warnings)
    for unit in manifest.units:
        _validate_economics(f"unit '{unit.id}'", unit.payment, unit.rate_limits, warnings)

    # Unit aliases (§4.2a, RFC-0023, v0.26): char rule, uniqueness across ids + aliases, cap.
    seen_identifiers = {u.id for u in manifest.units if u.id}
    for unit in manifest.units:
        aliases = unit.aliases or []
        if len(aliases) > 100:
            warnings.append(f"unit '{unit.id}': declares {len(aliases)} aliases (RECOMMENDED max 100)")
        for alias in aliases:
            if not _ALIAS_PATTERN.match(alias):
                warnings.append(
                    f"unit '{unit.id}': alias '{alias}' must match {_ALIAS_PATTERN.pattern} "
                    "(lowercase letters, digits, dots, hyphens, underscores)"
                )
            if alias in seen_identifiers:
                warnings.append(
                    f"unit '{unit.id}': alias '{alias}' collides with an existing unit id or alias "
                    "(must be unique across all ids and aliases)"
                )
            else:
                seen_identifiers.add(alias)

    # Serving endpoint binding (§3.12, RFC-0024, v0.26): entries MUST be HTTPS.
    if manifest.serving is not None:
        for key, urls in (("serving.manifest", manifest.serving.manifest), ("serving.mcp", manifest.serving.mcp)):
            for url in urls or []:
                if not str(url).startswith("https://"):
                    errors.append(f"manifest: {key} entry '{url}' must be an HTTPS URL")

    # §3.13 (RFC-0025, v0.27): authority_level_scale, task_types[], agents[], grant_ceiling.
    known_authority_levels = set(manifest.authority_level_scale or [])

    def _check_authority_level(ctx: str, level: Optional[str]) -> None:
        if level is not None and known_authority_levels and level not in known_authority_levels:
            warnings.append(
                f"{ctx}: 'authority_level' value '{level}' is not in the declared 'authority_level_scale'"
            )

    task_type_ids: set[str] = set()
    for tt in manifest.task_types:
        if not tt.id:
            errors.append("A task_types[] entry is missing required field 'id'")
        elif tt.id in task_type_ids:
            errors.append(f"Duplicate task_types[].id: '{tt.id}'")
        else:
            task_type_ids.add(tt.id)
        _check_authority_level(f"task_types['{tt.id}']", tt.authority_level)
        if (
            manifest.authority_level_scale
            and tt.authority_level is None
            and manifest.grant_ceiling is None
        ):
            warnings.append(
                f"task_types['{tt.id}']: authority_ceiling_undeclared — 'authority_level_scale' is "
                "declared at manifest root but this task-type declares neither 'authority_level' "
                "nor a 'grant_ceiling'"
            )

    agent_ids: set[str] = set()
    for agent in manifest.agents:
        if not agent.id:
            errors.append("An agents[] entry is missing required field 'id'")
        elif agent.id in agent_ids:
            errors.append(f"Duplicate agents[].id: '{agent.id}'")
        else:
            agent_ids.add(agent.id)
        _check_authority_level(f"agents['{agent.id}']", agent.authority_level)

    for unit in manifest.units:
        _check_authority_level(f"Unit '{unit.id}'", unit.authority_level)

    if manifest.grant_ceiling is not None:
        gc = manifest.grant_ceiling
        source_ids: set[str] = set()
        # Visited-set across the reference chain — mirrors _superseded_cycle_ids' discipline
        # (§4.22, §3.11). In the current schema, unit_ref/task_type_ref/agent_ref resolve to a
        # scalar authority_level with no further chaining, so a cycle cannot yet occur — this
        # guard exists to fail closed if a future extension adds nested grant_ceiling references.
        visiting: set[str] = set()

        for src in gc.sources:
            sp = "grant_ceiling.sources"
            if not src.id:
                errors.append(f"{sp}: an entry is missing required field 'id'")
            elif src.id in source_ids:
                errors.append(f"{sp}: duplicate source id '{src.id}'")
            else:
                source_ids.add(src.id)

            ref_fields = [v for v in (src.unit_ref, src.task_type_ref, src.agent_ref) if v is not None]
            if src.authority_level is not None and ref_fields:
                errors.append(
                    f"{sp}['{src.id}']: 'authority_level' is mutually exclusive with "
                    "unit_ref/task_type_ref/agent_ref"
                )
            if src.authority_level is None and not ref_fields:
                errors.append(
                    f"{sp}['{src.id}']: must declare exactly one of authority_level, unit_ref, "
                    "task_type_ref, agent_ref"
                )
            _check_authority_level(f"{sp}['{src.id}']", src.authority_level)

            if src.unit_ref is not None:
                if f"unit:{src.unit_ref}" in visiting:
                    errors.append(
                        f"{sp}['{src.id}']: grant_ceiling reference cycle detected at unit '{src.unit_ref}'"
                    )
                elif src.unit_ref not in unit_ids:
                    errors.append(f"{sp}['{src.id}']: 'unit_ref' references unknown unit '{src.unit_ref}'")
            if src.task_type_ref is not None:
                if f"task_type:{src.task_type_ref}" in visiting:
                    errors.append(
                        f"{sp}['{src.id}']: grant_ceiling reference cycle detected at task_type "
                        f"'{src.task_type_ref}'"
                    )
                elif src.task_type_ref not in task_type_ids:
                    errors.append(
                        f"{sp}['{src.id}']: 'task_type_ref' references unknown task_types[].id "
                        f"'{src.task_type_ref}'"
                    )
            if src.agent_ref is not None:
                if f"agent:{src.agent_ref}" in visiting:
                    errors.append(
                        f"{sp}['{src.id}']: grant_ceiling reference cycle detected at agent '{src.agent_ref}'"
                    )
                elif src.agent_ref not in agent_ids:
                    errors.append(
                        f"{sp}['{src.id}']: 'agent_ref' references unknown agents[].id '{src.agent_ref}'"
                    )

        if gc.mandatory_sources:
            for mandatory_id in gc.mandatory_sources:
                if mandatory_id not in source_ids:
                    errors.append(
                        f"grant_ceiling.sources: missing mandatory source '{mandatory_id}' declared "
                        "in grant_ceiling.mandatory_sources"
                    )

    return ValidationResult(errors=errors, warnings=warnings)


def _resolve_source_level(manifest: KnowledgeManifest, source) -> Optional[str]:
    """Resolve one grant_ceiling source to an authority_level, given the manifest to look up
    unit_ref/task_type_ref/agent_ref against. Returns ``None`` if the source is a reference to
    an entity with no declared authority_level (non-binding — absence is not a grant, §3.13).
    """
    if source.authority_level is not None:
        return source.authority_level
    if source.unit_ref is not None:
        return next((u.authority_level for u in manifest.units if u.id == source.unit_ref), None)
    if source.task_type_ref is not None:
        return next((t.authority_level for t in manifest.task_types if t.id == source.task_type_ref), None)
    if source.agent_ref is not None:
        return next((a.authority_level for a in manifest.agents if a.id == source.agent_ref), None)
    return None


class GrantCeilingResult(NamedTuple):
    """Result of :func:`compute_grant_ceiling` — the effective authority_level for a
    manifest's grant_ceiling, and the source id(s) that produced it (for the audit trail).
    """
    effective_level: Optional[str]
    binding_source_ids: list[str]


def compute_grant_ceiling(manifest: KnowledgeManifest) -> GrantCeilingResult:
    """Compute the effective authority_level for a manifest's grant_ceiling — the minimum
    across all resolved sources, with the source(s) that produced it named for the audit
    trail (§3.13, RFC-0025). Ordering of ``authority_level_scale`` defines the total order
    used for the minimum; a source that resolves outside the declared scale is ignored
    (non-binding), matching the "absence of a declared ceiling is not itself a grant" rule.
    """
    scale = manifest.authority_level_scale or []
    rank = {level: i for i, level in enumerate(scale)}
    gc = manifest.grant_ceiling
    if gc is None:
        return GrantCeilingResult(effective_level=None, binding_source_ids=[])

    min_rank: Optional[int] = None
    resolved: list[tuple[str, int]] = []
    for src in gc.sources:
        level = _resolve_source_level(manifest, src)
        if level is None or level not in rank:
            continue  # non-binding
        r = rank[level]
        resolved.append((src.id, r))
        if min_rank is None or r < min_rank:
            min_rank = r

    if min_rank is None:
        return GrantCeilingResult(effective_level=None, binding_source_ids=[])

    return GrantCeilingResult(
        effective_level=scale[min_rank],
        binding_source_ids=[sid for sid, r in resolved if r == min_rank],
    )


# Normative §3.13/§4.17 capping table: caps an `authority` action permission by an effective
# `authority_level`. Returns the stricter of the unit's own declared value and the table's cap
# (using the denied < requires_approval < initiative order), never widening it.
_AUTHORITY_LEVEL_CAPS: dict[str, dict[str, str]] = {
    "observe": {"read": "initiative", "summarize": "requires_approval", "modify": "denied",
                "share_externally": "denied", "execute": "denied"},
    "explain": {"read": "initiative", "summarize": "initiative", "modify": "denied",
                "share_externally": "denied", "execute": "denied"},
    "suggest": {"read": "initiative", "summarize": "initiative", "modify": "requires_approval",
                "share_externally": "denied", "execute": "denied"},
    "prepare": {"read": "initiative", "summarize": "initiative", "modify": "requires_approval",
                "share_externally": "requires_approval", "execute": "requires_approval"},
    "commit": {"read": "initiative", "summarize": "initiative", "modify": "initiative",
               "share_externally": "initiative", "execute": "initiative"},
}
_PERMISSION_RANK: dict[str, int] = {"denied": 0, "requires_approval": 1, "initiative": 2}


def apply_authority_cap(
    declared_value: Optional[str], action: str, effective_level: Optional[str]
) -> Optional[str]:
    """Apply the normative §3.13/§4.17 capping table: cap a declared ``authority.<action>``
    permission value by an effective ``authority_level``. Returns the stricter of the unit's
    own declared value and the table's cap — never widens a declared value.
    """
    if effective_level is None or effective_level not in _AUTHORITY_LEVEL_CAPS:
        return declared_value
    cap = _AUTHORITY_LEVEL_CAPS[effective_level].get(action)
    if cap is None or declared_value is None:
        return declared_value
    cap_rank = _PERMISSION_RANK.get(cap, 2)
    declared_rank = _PERMISSION_RANK.get(declared_value, 2)
    return declared_value if declared_rank <= cap_rank else cap


_VALID_PAYMENT_METHOD_TYPES = {"free", "x402", "meter", "subscription"}
_VALID_BACKOFF = {"linear", "exponential", "none"}
_DECIMAL_STRING = re.compile(r"^\d+(\.\d+)?$")
_ALIAS_PATTERN = re.compile(r"^[a-z0-9][a-z0-9._-]*$")  # §4.2a (v0.26)


def _validate_economics(where: str, payment, rate_limits, warnings: list[str]) -> None:
    """Advisory payment + rate_limits validation (§4.14/§4.15, RFC-0005, v0.25)."""
    if payment is not None:
        methods = payment.methods or []
        for m in methods:
            if m.type and m.type not in _VALID_PAYMENT_METHOD_TYPES:
                warnings.append(
                    f"{where}: payment.methods[] has unknown type '{m.type}' "
                    "(expected free, x402, meter, or subscription)"
                )
            if m.type == "x402":
                if not m.currency:
                    warnings.append(f"{where}: payment x402 method is missing required 'currency'")
                if not m.price_per_request:
                    warnings.append(f"{where}: payment x402 method is missing required 'price_per_request'")
                elif not _DECIMAL_STRING.match(str(m.price_per_request)):
                    warnings.append(
                        f"{where}: payment x402 price_per_request '{m.price_per_request}' "
                        'SHOULD be a decimal string (e.g. "0.001")'
                    )
        if (
            payment.default_tier in ("metered", "subscription")
            and methods
            and not any(m.type and m.type != "free" for m in methods)
        ):
            warnings.append(
                f"{where}: payment.default_tier is '{payment.default_tier}' but no paid method "
                "(x402/meter/subscription) is declared"
            )
    if rate_limits is not None and rate_limits.backoff and rate_limits.backoff not in _VALID_BACKOFF:
        warnings.append(
            f"{where}: rate_limits.backoff must be one of [linear, exponential, none], "
            f"got '{rate_limits.backoff}'"
        )


def _validate_discovery(discovery, unit_ids: set[str], prefix: str, errors: list[str], warnings: list[str]) -> None:
    """Validate a discovery block against the normative rules in §RFC-0012."""
    if discovery is None:
        return
    status = discovery.verification_status
    confidence = discovery.confidence

    # verification_status must be a known value
    if status is not None and status not in VALID_VERIFICATION_STATUSES:
        warnings.append(
            f"{prefix}: discovery.verification_status must be one of "
            f"{sorted(VALID_VERIFICATION_STATUSES)}, got '{status}'"
        )

    # source must be a known value
    if discovery.source is not None and discovery.source not in VALID_DISCOVERY_SOURCES:
        warnings.append(
            f"{prefix}: discovery.source must be one of "
            f"{sorted(VALID_DISCOVERY_SOURCES)}, got '{discovery.source}'"
        )

    # rumored MUST have confidence < 0.5 (normative — MUST = error)
    if status == "rumored" and confidence is not None and confidence >= 0.5:
        errors.append(
            f"{prefix}: discovery.verification_status=rumored but confidence={confidence} "
            f"(MUST be < 0.5, rule: rumored-confidence-ceiling)"
        )

    # declared SHOULD have confidence in [0.5, 0.8) (normative, RFC-0018 §5.1)
    if status == "declared" and confidence is not None and not (0.5 <= confidence < 0.8):
        warnings.append(
            f"{prefix}: discovery.verification_status=declared but confidence={confidence} "
            f"(SHOULD be in [0.5, 0.8))"
        )

    # verified SHOULD have confidence >= 0.8 (normative)
    if status == "verified" and confidence is not None and confidence < 0.8:
        warnings.append(
            f"{prefix}: discovery.verification_status=verified but confidence={confidence} "
            f"(SHOULD be >= 0.8)"
        )

    # verified_at SHOULD NOT be set when status is rumored, declared, or observed
    if discovery.verified_at is not None and status in ("rumored", "declared", "observed"):
        warnings.append(
            f"{prefix}: discovery.verified_at is set but verification_status='{status}' "
            f"(SHOULD only be set for verified units)"
        )

    # contradicted_by must be a string unit id (not a list)
    if discovery.contradicted_by is not None:
        if not isinstance(discovery.contradicted_by, str):
            warnings.append(
                f"{prefix}: discovery.contradicted_by must be a single unit id (string), got {type(discovery.contradicted_by).__name__}"
            )
        elif discovery.contradicted_by not in unit_ids:
            warnings.append(
                f"{prefix}: discovery.contradicted_by references unknown unit '{discovery.contradicted_by}'"
            )


def _validate_authority(authority, prefix: str, warnings: list[str]) -> None:
    """Validate an authority block against the normative rules in §RFC-0009."""
    if authority is None:
        return
    actions = {
        "read": authority.read,
        "summarize": authority.summarize,
        "modify": authority.modify,
        "share_externally": authority.share_externally,
        "execute": authority.execute,
    }
    for action, value in actions.items():
        if value is not None and value not in VALID_AUTHORITY_VALUES:
            warnings.append(
                f"{prefix}: authority.{action} must be one of "
                f"{sorted(VALID_AUTHORITY_VALUES)}, got '{value}'"
            )


def _validate_content_structure(content_structure, prefix: str, warnings: list[str]) -> None:
    """Validate a content_structure block (RFC-0016, v0.17).

    Unknown vocabulary values SHOULD warn but MUST pass through (forward-compat).
    """
    if content_structure is None:
        return
    if (content_structure.primary is not None
            and content_structure.primary not in VALID_CONTENT_MODALITIES):
        warnings.append(
            f"{prefix}: content_structure.primary has unknown value "
            f"'{content_structure.primary}'; expected one of {sorted(VALID_CONTENT_MODALITIES)}"
        )
    for modality in content_structure.contains:
        if modality not in VALID_CONTENT_MODALITIES:
            warnings.append(
                f"{prefix}: content_structure.contains has unknown value "
                f"'{modality}'; expected one of {sorted(VALID_CONTENT_MODALITIES)}"
            )
    if (content_structure.density is not None
            and content_structure.density not in VALID_DENSITY):
        warnings.append(
            f"{prefix}: content_structure.density has unknown value "
            f"'{content_structure.density}'; expected one of {sorted(VALID_DENSITY)}"
        )


def _validate_visibility(visibility, prefix: str, warnings: list[str]) -> None:
    """Validate a visibility block against the normative rules in §RFC-0009."""
    if visibility is None:
        return
    if (visibility.default_sensitivity is not None
            and visibility.default_sensitivity not in VALID_VISIBILITY_DEFAULTS):
        warnings.append(
            f"{prefix}: visibility.default must be one of "
            f"{sorted(VALID_VISIBILITY_DEFAULTS)}, got '{visibility.default_sensitivity}'"
        )
