import sys
from pathlib import Path

from . import parse, validate


def main() -> None:
    if len(sys.argv) < 2:
        print("Usage: python -m kcp <path-to-knowledge.yaml>")
        sys.exit(1)

    path = Path(sys.argv[1])
    if not path.exists():
        print(f"Error: file not found: {path}", file=sys.stderr)
        sys.exit(1)

    try:
        manifest = parse(path)
    except Exception as e:
        print(f"Parse error: {e}", file=sys.stderr)
        sys.exit(1)

    errors = validate(manifest)
    if errors:
        print(f"Validation failed — {len(errors)} error(s):")
        for err in errors:
            print(f"  • {err}")
        sys.exit(1)

    print(
        f"✓ {path} is valid"
        f" — project '{manifest.project}' v{manifest.version}"
        f", {len(manifest.units)} unit(s)"
        f", {len(manifest.relationships)} relationship(s)"
    )


if __name__ == "__main__":
    main()
