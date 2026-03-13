import argparse
import sys
from pathlib import Path

from . import parse, validate


def _cmd_validate(args: argparse.Namespace) -> int:
    """Validate a knowledge.yaml file."""
    path = Path(args.path)
    if not path.exists():
        print(f"Error: file not found: {path}", file=sys.stderr)
        return 1

    try:
        manifest = parse(path)
    except Exception as e:
        print(f"Parse error: {e}", file=sys.stderr)
        return 1

    result = validate(manifest, manifest_dir=str(path.parent))

    if result.warnings:
        for w in result.warnings:
            print(f"  \u26a0 {w}", file=sys.stderr)

    if not result.is_valid:
        print(f"Validation failed \u2014 {len(result.errors)} error(s):")
        for err in result.errors:
            print(f"  \u2022 {err}")
        return 1

    version_str = f" v{manifest.version}" if manifest.version else ""
    print(
        f"\u2713 {path} is valid"
        f" \u2014 project '{manifest.project}'{version_str}"
        f", {len(manifest.units)} unit(s)"
        f", {len(manifest.relationships)} relationship(s)"
    )
    return 0


def _cmd_init(args: argparse.Namespace) -> int:
    """Generate a starter knowledge.yaml."""
    from .init import run_init

    directory = Path(args.directory)
    if not directory.is_dir():
        print(f"Error: not a directory: {directory}", file=sys.stderr)
        return 1

    return run_init(
        directory=directory,
        level=args.level,
        scan=args.scan,
        force=args.force,
    )


def main() -> None:
    # Backwards compat: `python -m kcp <file>` → `python -m kcp validate <file>`
    if len(sys.argv) >= 2 and sys.argv[1] not in ("validate", "init", "-h", "--help"):
        sys.argv.insert(1, "validate")

    parser = argparse.ArgumentParser(
        prog="kcp",
        description="Knowledge Context Protocol — parser, validator, and scaffolding tool",
    )
    subparsers = parser.add_subparsers(dest="command")

    # validate (default behaviour for backwards compat)
    validate_parser = subparsers.add_parser(
        "validate",
        help="Validate a knowledge.yaml file",
    )
    validate_parser.add_argument("path", help="Path to knowledge.yaml")

    # init
    init_parser = subparsers.add_parser(
        "init",
        help="Generate a starter knowledge.yaml by scanning the current directory",
    )
    init_parser.add_argument(
        "directory",
        nargs="?",
        default=".",
        help="Directory to scan (default: current directory)",
    )
    init_parser.add_argument(
        "--level",
        type=int,
        choices=[1, 2, 3],
        default=1,
        help="Conformance level (1=basic, 2=+validated/hints, 3=+triggers)",
    )
    init_parser.add_argument(
        "--scan",
        action="store_true",
        help="Print discovered files before generating",
    )
    init_parser.add_argument(
        "--force",
        action="store_true",
        help="Overwrite existing knowledge.yaml",
    )

    args = parser.parse_args()

    if args.command is None:
        parser.print_help()
        sys.exit(1)

    if args.command == "validate":
        sys.exit(_cmd_validate(args))
    elif args.command == "init":
        sys.exit(_cmd_init(args))
    else:
        parser.print_help()
        sys.exit(1)


if __name__ == "__main__":
    main()
