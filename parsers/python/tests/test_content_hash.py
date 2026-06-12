"""Tests for per-unit content hashes (RFC-0019, draft).

The directory-digest test re-implements the §3.2 rule independently so the
Python validator is cross-checked against the normative definition the
same way the TypeScript harness (experiments/rfc-0018-render, case A11)
cross-checks the CLI renderer.
"""

import hashlib
import os

from kcp import parse_dict, validate
from kcp.model import ContentHash
from kcp.validator import compute_content_digest


def _manifest(unit_overrides: dict) -> dict:
    unit = {
        "id": "setup",
        "path": "docs/setup.md",
        "intent": "Development environment description",
        "scope": "project",
        "audience": ["agent"],
    }
    unit.update(unit_overrides)
    return {"kcp_version": "0.17", "project": "test", "version": "1.0.0", "units": [unit]}


def test_parses_content_hash_block():
    manifest = parse_dict(_manifest({
        "content_hash": {"algorithm": "sha256", "value": "ab" * 32},
    }))
    assert manifest.units[0].content_hash == ContentHash(algorithm="sha256", value="ab" * 32)


def test_malformed_block_parses_empty_and_fails_validation():
    manifest = parse_dict(_manifest({"content_hash": "not-a-mapping"}))
    assert manifest.units[0].content_hash == ContentHash()
    result = validate(manifest)
    assert any("content_hash.algorithm" in e for e in result.errors)


def test_shape_errors():
    bad_algorithm = validate(parse_dict(_manifest({
        "content_hash": {"algorithm": "md5", "value": "abc123"},
    })))
    assert any("content_hash.algorithm" in e for e in bad_algorithm.errors)

    bad_value = validate(parse_dict(_manifest({
        "content_hash": {"algorithm": "sha256", "value": "not hex!"},
    })))
    assert any("hex digest" in e for e in bad_value.errors)


def test_recomputes_against_disk(tmp_path):
    docs = tmp_path / "docs"
    docs.mkdir()
    (docs / "setup.md").write_bytes(b"content\n")
    digest = hashlib.sha256(b"content\n").hexdigest()

    fresh = validate(parse_dict(_manifest({
        "content_hash": {"algorithm": "sha256", "value": digest},
    })), manifest_dir=str(tmp_path))
    assert not [e for e in fresh.errors if "content_hash" in e]

    (docs / "setup.md").write_bytes(b"edited\n")
    stale = validate(parse_dict(_manifest({
        "content_hash": {"algorithm": "sha256", "value": digest},
    })), manifest_dir=str(tmp_path))
    assert any("does not match content on disk" in e for e in stale.errors)


def test_directory_digest_matches_independent_implementation(tmp_path):
    tree = tmp_path / "tree"
    (tree / "nested" / "deeper").mkdir(parents=True)
    (tree / "README.md").write_bytes(b"docs\n")
    (tree / ".hidden").write_bytes(b"dotfiles count too\n")
    (tree / "nested" / "empty.txt").write_bytes(b"")
    (tree / "nested" / "deeper" / "å-utf8.md").write_bytes(b"unicode name\n")

    # independent re-implementation of the §3.2 rule
    files = []
    for root, _dirs, names in os.walk(tree):
        for name in names:
            files.append(os.path.relpath(os.path.join(root, name), tree).replace(os.sep, "/"))
    files.sort(key=lambda r: r.encode("utf-8"))
    expected = hashlib.sha256()
    for rel in files:
        with open(tree / rel, "rb") as f:
            file_hex = hashlib.sha256(f.read()).hexdigest()
        expected.update(f"{rel}\0{file_hex}\n".encode("utf-8"))

    assert compute_content_digest(str(tree), "sha256") == expected.hexdigest()


def test_missing_target_is_unreadable(tmp_path):
    assert compute_content_digest(str(tmp_path / "absent"), "sha256") is None
