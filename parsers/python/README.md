# kcp — Knowledge Context Protocol Python Parser

Reference parser and validator for the Knowledge Context Protocol (KCP).

## Install

```bash
pip install kcp
```

## Usage

```python
from kcp import parse, validate

manifest = parse("knowledge.yaml")
result = validate(manifest)

if not result.is_valid:
    print("Errors:", result.errors)
if result.warnings:
    print("Warnings:", result.warnings)
```

The `validate()` function returns a `ValidationResult` with separate `errors`
and `warnings` lists, matching the conformance rules in
[SPEC.md](../../SPEC.md) section 7.

## CLI

```bash
python -m kcp knowledge.yaml
# or, after install:
kcp knowledge.yaml
```
