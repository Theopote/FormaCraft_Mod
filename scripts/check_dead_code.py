"""Report top-level Java types that no other production source file references.

Dead code is the main source of confusion for later developers: a class that
looks load-bearing but is never called reads as "the" implementation while the
real path is somewhere else. This gate lists such types so they can be migrated
or deleted explicitly instead of lingering.

Entry points that are referenced by the loader rather than by Java source
(fabric.mod.json entrypoints, mixin configs, Gradle tool tasks) are exempt via
``config/dead_code_allowlist.json``.
"""

from __future__ import annotations

import json
import re
import sys
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA_ROOT = ROOT / "src/main/java"
CONFIG = ROOT / "config/dead_code_allowlist.json"

DECL = re.compile(
    r"^(?:public\s+|final\s+|abstract\s+|sealed\s+|non-sealed\s+|static\s+)*"
    r"(?:class|interface|enum|record)\s+(\w+)",
    re.M,
)
TOKEN = re.compile(r"[A-Za-z_$][A-Za-z0-9_$]*")


def load_allowlist() -> list[str]:
    if not CONFIG.is_file():
        return []
    data = json.loads(CONFIG.read_text(encoding="utf-8"))
    return list(data.get("allow") or [])


def scan(root: Path | None = None) -> list[tuple[int, str, str]]:
    """Return (own_refs, type_name, relative_path) for zero-consumer types."""
    base = (root or ROOT) / "src/main/java"
    files = sorted(base.rglob("*.java"))
    sources = {p: p.read_text(encoding="utf-8", errors="replace") for p in files}
    decls: dict[str, list[Path]] = defaultdict(list)
    for path, text in sources.items():
        for match in DECL.finditer(text):
            decls[match.group(1)].append(path)

    token_files: dict[str, set[Path]] = defaultdict(set)
    for path, text in sources.items():
        for token in set(TOKEN.findall(text)):
            token_files[token].add(path)

    results: list[tuple[int, str, str]] = []
    for name, owners in decls.items():
        if len(owners) > 1:
            continue  # declared in more than one file: not a zero-consumer candidate
        owner = owners[0]
        external = token_files.get(name, set()) - {owner}
        if external:
            continue
        own = len(TOKEN.findall(" ".join(
            token for token in TOKEN.findall(sources[owner]) if token == name)))
        rel = owner.relative_to(root or ROOT).as_posix()
        results.append((own, name, rel))
    results.sort()
    return results


def main() -> int:
    allow = set(load_allowlist())
    results = scan()
    unexpected = [(own, name, rel) for own, name, rel in results if name not in allow]
    if unexpected:
        print("Zero-consumer top-level types (delete or wire them up):", file=sys.stderr)
        for own, name, rel in unexpected:
            print(f"  {rel}: {name}", file=sys.stderr)
        print(
            "  If a type is loaded by the framework rather than Java source, add it to "
            "config/dead_code_allowlist.json.",
            file=sys.stderr,
        )
        return 1
    print(f"Dead-code scan OK ({len(results)} zero-consumer types, all allowlisted)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
