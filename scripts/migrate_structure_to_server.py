#!/usr/bin/env python3
"""Move common/generation/structure -> server/generation/structure (batch-B)."""

from __future__ import annotations

import shutil
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC_ROOT = ROOT / "src" / "main" / "java" / "com" / "formacraft"
OLD_PKG = "com.formacraft.common.generation.structure"
NEW_PKG = "com.formacraft.server.generation.structure"
OLD_DIR = SRC_ROOT / "common" / "generation" / "structure"
NEW_DIR = SRC_ROOT / "server" / "generation" / "structure"


def move_tree() -> int:
    if not OLD_DIR.exists():
        print(f"source missing: {OLD_DIR}")
        return 0
    NEW_DIR.parent.mkdir(parents=True, exist_ok=True)
    if NEW_DIR.exists():
        raise SystemExit(f"target already exists: {NEW_DIR}")
    shutil.move(str(OLD_DIR), str(NEW_DIR))
    count = sum(1 for _ in NEW_DIR.rglob("*.java"))
    print(f"moved {count} java files to {NEW_DIR.relative_to(ROOT)}")
    return count


def patch_all_java() -> int:
    changed = 0
    java_root = ROOT / "src" / "main" / "java"
    for path in java_root.rglob("*.java"):
        text = path.read_text(encoding="utf-8")
        if OLD_PKG not in text:
            continue
        new_text = text.replace(OLD_PKG, NEW_PKG)
        if new_text != text:
            path.write_text(new_text, encoding="utf-8")
            changed += 1
    return changed


def patch_json_resources() -> int:
    changed = 0
    for base in (ROOT / "src" / "main" / "resources", ROOT / "src" / "test"):
        if not base.exists():
            continue
        for path in base.rglob("*"):
            if path.suffix.lower() not in {".json", ".json5"}:
                continue
            text = path.read_text(encoding="utf-8")
            if OLD_PKG not in text:
                continue
            path.write_text(text.replace(OLD_PKG, NEW_PKG), encoding="utf-8")
            changed += 1
            print(f"  patched resource: {path.relative_to(ROOT)}")
    return changed


def git_add() -> None:
    subprocess.run(["git", "add", "-A", str(OLD_DIR.parent), str(NEW_DIR)], cwd=ROOT, check=False)
    subprocess.run(["git", "add", "-u"], cwd=ROOT, check=False)


def main() -> None:
    n = move_tree()
    files = patch_all_java()
    res = patch_json_resources()
    print(f"patched {files} java files, {res} resource files")
    git_add()
    print("done")


if __name__ == "__main__":
    main()
