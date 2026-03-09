#!/usr/bin/env python3
"""KCP Conformance Test Runner — Python implementation.

Loads each YAML fixture from the conformance/fixtures/ directory tree,
parses and validates it with kcp.parser and kcp.validator,
then compares against the co-located .expected.json file.

Comparison rules:
  - valid:              exact boolean match (unless _note mentions cross-impl)
  - errors:             if expected valid is false, actual errors must be non-empty
  - parse_error:        if true, parsing must raise an exception
  - unit_count:         exact integer match
  - relationship_count: exact integer match
  - warnings:           if present and non-empty, actual warnings must be non-empty

Usage: python conformance_runner.py [fixtures-dir]
"""

import json
import sys
from pathlib import Path

# Add the parser to the path
import importlib
import os

def find_parser_root():
    """Find the parsers/python directory relative to this script."""
    # Expected layout: conformance/runners/python/this_file.py
    # Parser at: parsers/python/kcp/
    script_dir = Path(__file__).resolve().parent
    repo_root = script_dir.parent.parent.parent
    parser_dir = repo_root / "parsers" / "python"
    if parser_dir.is_dir():
        return parser_dir
    return None

parser_root = find_parser_root()
if parser_root:
    sys.path.insert(0, str(parser_root))

from kcp.parser import parse, parse_dict
from kcp.validator import validate


passed = 0
failed = 0
skipped = 0


def run_fixture(yaml_path: Path) -> None:
    global passed, failed, skipped

    name = yaml_path.stem
    expected_path = yaml_path.with_suffix("").with_name(name + ".expected.json")

    if not expected_path.exists():
        print(f"  SKIP  {relative_name(yaml_path)} (no .expected.json)")
        skipped += 1
        return

    try:
        with open(expected_path, encoding="utf-8") as f:
            expected = json.load(f)
    except Exception as e:
        print(f"  FAIL  {relative_name(yaml_path)} (cannot read expected: {e})")
        failed += 1
        return

    expect_parse_error = expected.get("parse_error", False)
    parse_error_also_ok = expected.get("parse_error_also_acceptable", False)

    # Attempt to parse
    try:
        manifest = parse(str(yaml_path))
    except Exception as e:
        if expect_parse_error or parse_error_also_ok:
            print(f"  PASS  {relative_name(yaml_path)} (parse error as expected: {e})")
            passed += 1
        else:
            print(f"  FAIL  {relative_name(yaml_path)} (unexpected parse error: {e})")
            failed += 1
        return

    if expect_parse_error:
        print(f"  FAIL  {relative_name(yaml_path)} (expected parse error but parsing succeeded)")
        failed += 1
        return

    # Validate
    result = validate(manifest)

    # Compare
    failures = []

    # Check valid/invalid
    if "valid" in expected:
        expected_valid = expected["valid"]
        note = expected.get("_note", "")
        allow_variance = "cross-impl" in note
        if not allow_variance and result.is_valid != expected_valid:
            failures.append(
                f"valid: expected {expected_valid}, got {result.is_valid} "
                f"(errors: {result.errors})"
            )

    # Check errors non-empty when expected invalid
    if expected.get("valid") is False:
        if not result.errors:
            note = expected.get("_note", "")
            if "cross-impl" not in note:
                failures.append("expected errors to be non-empty")

    # Check unit_count
    if "unit_count" in expected:
        actual_count = len(manifest.units)
        expected_count = expected["unit_count"]
        if actual_count != expected_count:
            failures.append(f"unit_count: expected {expected_count}, got {actual_count}")

    # Check relationship_count
    if "relationship_count" in expected:
        actual_count = len(manifest.relationships)
        expected_count = expected["relationship_count"]
        if actual_count != expected_count:
            failures.append(
                f"relationship_count: expected {expected_count}, got {actual_count}"
            )

    # Check warnings non-empty when expected
    if "warnings" in expected:
        expected_warnings = expected["warnings"]
        if expected_warnings and not result.warnings:
            failures.append("expected warnings to be non-empty")

    if not failures:
        print(f"  PASS  {relative_name(yaml_path)}")
        passed += 1
    else:
        print(f"  FAIL  {relative_name(yaml_path)}")
        for f in failures:
            print(f"        - {f}")
        failed += 1


def relative_name(p: Path) -> str:
    s = str(p)
    idx = s.find("fixtures/")
    if idx >= 0:
        return s[idx:]
    return p.name


def main():
    global passed, failed, skipped

    if len(sys.argv) > 1:
        fixtures_dir = Path(sys.argv[1])
    else:
        fixtures_dir = Path("conformance/fixtures")
        if not fixtures_dir.is_dir():
            fixtures_dir = Path("fixtures")

    if not fixtures_dir.is_dir():
        print(f"Fixtures directory not found: {fixtures_dir.resolve()}", file=sys.stderr)
        sys.exit(1)

    print("KCP Conformance Runner (Python)")
    print(f"Fixtures: {fixtures_dir.resolve()}")
    print("=" * 40)

    yaml_files = sorted(fixtures_dir.rglob("*.yaml"))

    for yaml_file in yaml_files:
        run_fixture(yaml_file)

    print("=" * 40)
    print(f"Results: {passed} passed, {failed} failed, {skipped} skipped")
    sys.exit(1 if failed > 0 else 0)


if __name__ == "__main__":
    main()
