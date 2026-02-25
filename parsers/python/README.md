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
errors = validate(manifest)
```

## CLI

```bash
python -m kcp knowledge.yaml
# or, after install:
kcp knowledge.yaml
```
