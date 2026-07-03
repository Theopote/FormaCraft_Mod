#!/usr/bin/env python3
"""batch-B-compiler + OutlineBlock DTO sink (batch-C network prep)."""

from __future__ import annotations

import shutil
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "src" / "main" / "java" / "com" / "formacraft"

REPLACEMENTS = [
    ("com.formacraft.common.compiler.ComponentPlanCompiler",
     "com.formacraft.server.compiler.ComponentPlanCompiler"),
    ("com.formacraft.client.preview.OutlineBlock",
     "com.formacraft.common.preview.OutlineBlock"),
]


def move_component_plan_compiler() -> None:
    src = SRC / "common" / "compiler" / "ComponentPlanCompiler.java"
    dst = SRC / "server" / "compiler" / "ComponentPlanCompiler.java"
    if not src.exists():
        raise SystemExit(f"missing {src}")
    dst.parent.mkdir(parents=True, exist_ok=True)
    text = src.read_text(encoding="utf-8")
    text = text.replace(
        "package com.formacraft.common.compiler;",
        "package com.formacraft.server.compiler;",
        1,
    )
    dst.write_text(text, encoding="utf-8")
    src.unlink()
    print(f"moved ComponentPlanCompiler -> {dst.relative_to(ROOT)}")


def move_outline_block() -> None:
    src = SRC / "client" / "preview" / "OutlineBlock.java"
    dst = SRC / "common" / "preview" / "OutlineBlock.java"
    if not src.exists():
        raise SystemExit(f"missing {src}")
    dst.parent.mkdir(parents=True, exist_ok=True)
    text = src.read_text(encoding="utf-8")
    text = text.replace(
        "package com.formacraft.client.preview;",
        "package com.formacraft.common.preview;",
        1,
    )
    dst.write_text(text, encoding="utf-8")
    src.unlink()
    print(f"moved OutlineBlock -> {dst.relative_to(ROOT)}")


def patch_java() -> int:
    changed = 0
    java_root = ROOT / "src" / "main" / "java"
    for path in java_root.rglob("*.java"):
        text = path.read_text(encoding="utf-8")
        new_text = text
        for old, new in REPLACEMENTS:
            new_text = new_text.replace(old, new)
        if new_text != text:
            path.write_text(new_text, encoding="utf-8")
            changed += 1
    return changed


def main() -> None:
    move_component_plan_compiler()
    move_outline_block()
    n = patch_java()
    print(f"patched {n} java files")


if __name__ == "__main__":
    main()
