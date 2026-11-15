"""Scan for zero-consumer Java classes: class declared, no other source references it."""
from __future__ import annotations

import re
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA_ROOT = ROOT / "src/main/java"

DECL = re.compile(r"^(?:public\s+|final\s+|abstract\s+|sealed\s+|non-sealed\s+|static\s+)*"
                  r"(?:class|interface|enum|record)\s+(\w+)", re.M)
TOKEN = re.compile(r"[A-Za-z_$][A-Za-z0-9_$]*")


def main() -> int:
    files = sorted(JAVA_ROOT.rglob("*.java"))
    sources = {p: p.read_text(encoding="utf-8", errors="replace") for p in files}
    decls: dict[str, list[Path]] = defaultdict(list)
    for p, text in sources.items():
        for m in DECL.finditer(text):
            decls[m.group(1)].append(p)

    # Token index: token -> set of files containing it
    token_files: dict[str, set[Path]] = defaultdict(set)
    for p, text in sources.items():
        for tok in set(TOKEN.findall(text)):
            token_files[tok].add(p)

    results = []
    for name, owners in decls.items():
        if len(owners) > 1:
            continue
        owner = owners[0]
        holders = token_files.get(name, set())
        external = holders - {owner}
        if not external:
            own = len(TOKEN.findall(" ".join(
                t for t in TOKEN.findall(sources[owner]) if t == name)))
            results.append((own, name, owner.relative_to(ROOT).as_posix()))

    results.sort()
    print(f"Zero-consumer top-level types: {len(results)}")
    for own, name, rel in results:
        print(f"  own_refs={own:3d}  {name:45s} {rel}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
