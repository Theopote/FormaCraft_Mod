#!/usr/bin/env python3
"""batch-B-network: move server-side network handlers to server.network."""

from __future__ import annotations

import shutil
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "src" / "main" / "java" / "com" / "formacraft"

MOVES = [
    (
        SRC / "common" / "network" / "BuildRequestProcessor.java",
        SRC / "server" / "network" / "BuildRequestProcessor.java",
        "com.formacraft.common.network",
        "com.formacraft.server.network",
    ),
    (
        SRC / "common" / "network" / "LlmPlanPreviewBuilder.java",
        SRC / "server" / "network" / "LlmPlanPreviewBuilder.java",
        "com.formacraft.common.network",
        "com.formacraft.server.network",
    ),
]

REPLACEMENTS = [
    ("com.formacraft.common.network.BuildRequestProcessor", "com.formacraft.server.network.BuildRequestProcessor"),
    ("com.formacraft.common.network.LlmPlanPreviewBuilder", "com.formacraft.server.network.LlmPlanPreviewBuilder"),
    ("com.formacraft.client.tool.placement.PlacementContext", "com.formacraft.common.placement.PlacementContext"),
]


def move_files() -> None:
    for src, dst, old_pkg, new_pkg in MOVES:
        if not src.exists():
            print(f"skip missing {src.relative_to(ROOT)}")
            continue
        dst.parent.mkdir(parents=True, exist_ok=True)
        text = src.read_text(encoding="utf-8")
        text = text.replace(f"package {old_pkg};", f"package {new_pkg};", 1)
        dst.write_text(text, encoding="utf-8")
        src.unlink()
        print(f"moved {src.relative_to(ROOT)} -> {dst.relative_to(ROOT)}")


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


def remove_stale(path: Path) -> None:
    if path.exists():
        path.unlink()
        print(f"removed stale {path.relative_to(ROOT)}")


def main() -> None:
    move_files()
    remove_stale(SRC / "common" / "network" / "NetworkOrchestratorProvider.java")
    remove_stale(SRC / "client" / "tool" / "placement" / "PlacementContext.java")
    n = patch_java()
    print(f"patched {n} java files")


if __name__ == "__main__":
    main()
