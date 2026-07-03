#!/usr/bin/env python3
"""Move GeneratedStructure + PlannedBlock from server.build to common.build (batch-A)."""

from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "src" / "main" / "java"

OLD_GS = "com.formacraft.server.build.GeneratedStructure"
OLD_PB = "com.formacraft.server.build.PlannedBlock"
NEW_GS = "com.formacraft.common.build.GeneratedStructure"
NEW_PB = "com.formacraft.common.build.PlannedBlock"

SERVER_BUILD = SRC / "com" / "formacraft" / "server" / "build"
REMOVE = [
    SERVER_BUILD / "GeneratedStructure.java",
    SERVER_BUILD / "PlannedBlock.java",
]


def patch_java_files() -> int:
    changed = 0
    for path in SRC.rglob("*.java"):
        text = path.read_text(encoding="utf-8")
        if OLD_GS not in text and OLD_PB not in text:
            continue
        new_text = text.replace(OLD_GS, NEW_GS).replace(OLD_PB, NEW_PB)
        if new_text != text:
            path.write_text(new_text, encoding="utf-8")
            changed += 1
    return changed


def add_same_package_imports() -> int:
    """server.build classes that used same-package unqualified names."""
    patched = 0
    uses_gs = re.compile(r"\bGeneratedStructure\b")
    uses_pb = re.compile(r"\bPlannedBlock\b")
    import_gs = f"import {NEW_GS};"
    import_pb = f"import {NEW_PB};"

    for path in SERVER_BUILD.rglob("*.java"):
        text = path.read_text(encoding="utf-8")
        if not uses_gs.search(text) and not uses_pb.search(text):
            continue
        lines = text.splitlines(keepends=True)
        pkg_idx = next(i for i, l in enumerate(lines) if l.startswith("package "))
        insert_at = pkg_idx + 1
        while insert_at < len(lines) and lines[insert_at].strip() == "":
            insert_at += 1

        to_add: list[str] = []
        if uses_gs.search(text) and import_gs not in text:
            to_add.append(import_gs)
        if uses_pb.search(text) and import_pb not in text:
            to_add.append(import_pb)
        if not to_add:
            continue

        block = "".join(l + "\n" for l in to_add)
        lines.insert(insert_at, block)
        path.write_text("".join(lines), encoding="utf-8")
        patched += 1
        print(f"  added imports: {path.relative_to(ROOT)}")
    return patched


def main() -> None:
    for p in REMOVE:
        if p.exists():
            p.unlink()
            print(f"removed {p.relative_to(ROOT)}")

    n = patch_java_files()
    print(f"patched {n} java files (FQN replace)")

    print("same-package import fixups in server/build:")
    m = add_same_package_imports()
    print(f"patched {m} server/build files")


if __name__ == "__main__":
    main()
