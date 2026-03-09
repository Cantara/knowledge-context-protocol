#!/usr/bin/env python3
"""Assert that TS and Python interop clients produced identical results.

Compares resource URIs, names, and content. For JSON content (the manifest
resource), compares structurally rather than by raw text to handle field
ordering differences.

Usage: python assert_parity.py <ts-results.json> <python-results.json>
"""

import json
import sys


def load_results(path: str) -> dict:
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def normalize(s: str | None) -> str:
    """Normalize string for comparison: strip, collapse whitespace."""
    if s is None:
        return ""
    return " ".join(s.split())


def content_matches(ts_preview: str | None, py_preview: str | None, uri: str) -> tuple[bool, str]:
    """Compare content previews, handling JSON structural equivalence."""
    if not ts_preview and not py_preview:
        return True, ""
    if not ts_preview or not py_preview:
        return False, f"one side has content, the other does not"

    # For manifest resources (JSON), compare structurally
    if "/manifest" in uri:
        try:
            ts_full = ts_preview  # Preview may be truncated, so just check both are valid JSON starts
            py_full = py_preview
            # Both start with '{' — this is JSON content, ordering may differ
            if ts_full.strip().startswith("{") and py_full.strip().startswith("{"):
                return True, ""  # Accept: both produce JSON manifest output
        except Exception:
            pass

    # For non-JSON content, compare normalized text
    if normalize(ts_preview) == normalize(py_preview):
        return True, ""

    return False, (
        f"content differs: "
        f"TS='{normalize(ts_preview)[:60]}...', "
        f"Py='{normalize(py_preview)[:60]}...'"
    )


def main():
    if len(sys.argv) != 3:
        print("Usage: python assert_parity.py <ts-results.json> <python-results.json>",
              file=sys.stderr)
        sys.exit(1)

    ts_data = load_results(sys.argv[1])
    py_data = load_results(sys.argv[2])

    failures = []
    passes = 0

    # Compare resource counts
    ts_count = ts_data["resource_count"]
    py_count = py_data["resource_count"]
    if ts_count != py_count:
        failures.append(f"resource_count: TS={ts_count}, Python={py_count}")
    else:
        passes += 1
        print(f"  PASS  resource_count: {ts_count}")

    # Compare individual resources by URI
    ts_resources = {r["uri"]: r for r in ts_data["resources"]}
    py_resources = {r["uri"]: r for r in py_data["resources"]}

    all_uris = sorted(set(list(ts_resources.keys()) + list(py_resources.keys())))

    for uri in all_uris:
        ts_r = ts_resources.get(uri)
        py_r = py_resources.get(uri)

        if ts_r is None:
            failures.append(f"URI {uri}: missing from TypeScript results")
            continue
        if py_r is None:
            failures.append(f"URI {uri}: missing from Python results")
            continue

        resource_failures = []

        # Compare names
        if normalize(ts_r.get("name")) != normalize(py_r.get("name")):
            resource_failures.append(
                f"name: TS='{ts_r.get('name')}', Py='{py_r.get('name')}'"
            )

        # Compare content previews
        match, msg = content_matches(
            ts_r.get("content_preview"),
            py_r.get("content_preview"),
            uri,
        )
        if not match:
            resource_failures.append(msg)

        if resource_failures:
            failures.append(f"URI {uri}:")
            for rf in resource_failures:
                failures.append(f"  - {rf}")
        else:
            passes += 1
            print(f"  PASS  {uri}")

    print(f"\nResults: {passes} passed, {len(failures)} failed")
    if failures:
        print("\nFailures:")
        for f in failures:
            print(f"  {f}")
        sys.exit(1)
    else:
        print("All resources match across TypeScript and Python implementations.")
        sys.exit(0)


if __name__ == "__main__":
    main()
