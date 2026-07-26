#!/usr/bin/env bash
# Fail if any tracked text/source file contains a raw NUL byte.
#
# Raw NULs in source are a runtime-parity hazard: node parses them fine, but
# bun's transpiler mangles them — the same bytes produced different verdicts
# per runtime in kcp-harness#51. Write backslash-u0000 escapes instead of raw bytes.
set -euo pipefail
cd "$(dirname "$0")/.."

# Self-test first: grep needs -a here — without it, binary-classified files
# silently no-match and the sweep reports a false clean. Prove the detector
# can see a planted NUL before trusting it.
probe="$(mktemp)"
printf 'x\x00y' > "$probe"
if ! grep -qaP '\x00' "$probe"; then
  rm -f "$probe"
  echo "check-nul-bytes: self-test failed — detector cannot see NUL bytes" >&2
  exit 2
fi
rm -f "$probe"

bad="$(git ls-files \
  | grep -E '\.(ts|js|mjs|cjs|json|ya?ml|md|sh|txt|html|css|py|java|rs|toml)$' \
  | xargs -d '\n' --no-run-if-empty grep -alP '\x00' 2>/dev/null || true)"

if [ -n "$bad" ]; then
  echo "Raw NUL bytes found in tracked source files:" >&2
  echo "$bad" >&2
  echo 'Replace raw NUL bytes with backslash-u0000 escape sequences (see Cantara/kcp-harness#51).' >&2
  exit 1
fi
echo "check-nul-bytes: clean"
