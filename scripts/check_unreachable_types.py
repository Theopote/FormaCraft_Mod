"""Report top-level Java types that are unreachable from any live entry point.

``check_dead_code.py`` reports types with *no external reference*. That misses
*mutually-referencing* dead clusters: a group of classes that reference each
other looks "used" to a per-class reference count, yet the whole group is
unreachable from the real entry points.

The graph uses simple-name tokens, not Java import/type resolution. Duplicate
names are retained and traversed conservatively; comments and unrelated tokens
can also introduce edges. A clean scan is not proof that every type is live,
and reported candidates still require call-path/framework-loading review.

This gate closes that gap with a reachability analysis:

  1. Build a reference graph over the top-level types in ``src/main/java``.
  2. Collect the *roots* — the classes the framework actually loads. These are
     read from the real configuration files, not hand-maintained:
       - ``src/main/resources/fabric.mod.json`` entrypoints
       - ``src/main/resources/formacraft.mixins.json`` mixins
       - ``build.gradle`` ``mainClass`` declarations
       - ``config/dead_code_allowlist.json`` (framework-loaded extras)
  3. Walk the graph from the roots. Any type never reached is dead.

A type that is only referenced by another unreachable type is also reported,
which is exactly the mutually-referencing cluster case.

Types that are genuinely loaded by the framework in a way this scanner cannot
see (e.g. a custom classloader scanning a directory) go in
``config/unreachable_allowlist.json``.
"""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA_ROOT = ROOT / "src/main/java"
FABRIC_MOD = ROOT / "src/main/resources/fabric.mod.json"
MIXIN_CONFIG = ROOT / "src/main/resources/formacraft.mixins.json"
BUILD_GRADLE = ROOT / "build.gradle"
DEAD_ALLOWLIST = ROOT / "config/dead_code_allowlist.json"
CONFIG = ROOT / "config/unreachable_allowlist.json"

DECL = re.compile(
    r"^(?:public\s+|final\s+|abstract\s+|sealed\s+|non-sealed\s+|static\s+)*"
    r"(?:class|interface|enum|record)\s+(\w+)",
    re.M,
)
TOKEN = re.compile(r"[A-Za-z_$][A-Za-z0-9_$]*")
GRADLE_MAIN = re.compile(r"mainClass\s*=\s*'([\w.]+)'")


def _load_roots(base: Path | None = None) -> tuple[set[str], list[str]]:
    """Collect framework-loaded root classes and the files they came from."""
    base = base or ROOT
    fabric_mod = base / "src/main/resources/fabric.mod.json"
    mixin_config = base / "src/main/resources/formacraft.mixins.json"
    build_gradle = base / "build.gradle"
    dead_allowlist = base / "config/dead_code_allowlist.json"

    roots: set[str] = set()
    sources: list[str] = []

    if fabric_mod.is_file():
        mod = json.loads(fabric_mod.read_text(encoding="utf-8"))
        for entries in (mod.get("entrypoints") or {}).values():
            for entry in entries:
                roots.add(str(entry).rsplit(".", 1)[-1])
                sources.append("fabric.mod.json entrypoints")

    if mixin_config.is_file():
        cfg = json.loads(mixin_config.read_text(encoding="utf-8"))
        for key in ("mixins", "client", "server"):
            for name in cfg.get(key) or []:
                roots.add(name)
                sources.append(f"formacraft.mixins.json {key}")

    if build_gradle.is_file():
        for match in GRADLE_MAIN.finditer(build_gradle.read_text(encoding="utf-8")):
            roots.add(match.group(1).rsplit(".", 1)[-1])
            sources.append("build.gradle mainClass")

    if dead_allowlist.is_file():
        data = json.loads(dead_allowlist.read_text(encoding="utf-8"))
        for name in data.get("allow") or []:
            roots.add(name)
            sources.append("dead_code_allowlist.json")

    return roots, sorted(set(sources))


def _load_allowlist() -> set[str]:
    if not CONFIG.is_file():
        return set()
    data = json.loads(CONFIG.read_text(encoding="utf-8"))
    return set(data.get("allow") or [])


def scan(root: Path | None = None) -> list[tuple[str, str]]:
    """Return (type_name, relative_path) for types unreachable from the roots."""
    base = (root or ROOT)
    java_root = base / "src/main/java"

    files = sorted(java_root.rglob("*.java"))
    sources = {p: p.read_text(encoding="utf-8", errors="replace") for p in files}

    # Keep all declarations: simple names can occur in different packages.
    decls: dict[str, set[Path]] = {}
    for path, text in sources.items():
        for match in DECL.finditer(text):
            decls.setdefault(match.group(1), set()).add(path)

    # file -> referenced type names (excluding its own declarations)
    file_edges: dict[Path, set[str]] = {}
    for path, text in sources.items():
        tokens = set(TOKEN.findall(text))
        file_edges[path] = {
            tok for tok in tokens if tok in decls and any(p != path for p in decls[tok])
        }

    roots, _ = _load_roots(base)

    reachable: set[str] = set()
    worklist = list(roots)
    while worklist:
        name = worklist.pop()
        if name in reachable or name not in decls:
            continue
        reachable.add(name)
        # This token-based gate cannot resolve imports. Conservatively visit
        # every matching declaration rather than silently dropping duplicates.
        for path in decls[name]:
            worklist.extend(file_edges.get(path, ()))

    results = [
        (name, path.relative_to(base).as_posix())
        for name in sorted(set(decls) - reachable)
        for path in sorted(decls[name])
    ]
    return results


def main() -> int:
    if not FABRIC_MOD.is_file():
        print(f"Missing {FABRIC_MOD}", file=sys.stderr)
        return 1

    allow = _load_allowlist()
    found = scan()
    unexpected = [(name, rel) for name, rel in found if name not in allow]
    if unexpected:
        print(
            "Top-level types unreachable from any framework entry point:",
            file=sys.stderr,
        )
        for name, rel in unexpected:
            print(f"  {rel}: {name}", file=sys.stderr)
        print(
            "  Delete the dead cluster, or allowlist it in "
            "config/unreachable_allowlist.json if the framework loads it "
            "in a way this scanner cannot see.",
            file=sys.stderr,
        )
        return 1

    roots, root_sources = _load_roots()
    print(
        f"Unreachable-type scan OK ({len(found)} unreachable types, all allowlisted; "
        f"roots from {', '.join(root_sources)})"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
