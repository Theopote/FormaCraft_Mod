#!/usr/bin/env python3
"""batch-B-misc: move server-bound classes out of common."""

from __future__ import annotations

import shutil
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "src" / "main" / "java" / "com" / "formacraft"

MOVES: list[tuple[Path, Path, str, str]] = [
    (
        SRC / "common" / "generation" / "component" / "adaptor" / "StructureGeneratorAdaptor.java",
        SRC / "server" / "generation" / "component" / "adaptor" / "StructureGeneratorAdaptor.java",
        "com.formacraft.common.generation.component.adaptor",
        "com.formacraft.server.generation.component.adaptor",
    ),
    (
        SRC / "common" / "generation" / "component" / "adaptor" / "UnifiedGeneratorRouter.java",
        SRC / "server" / "generation" / "component" / "adaptor" / "UnifiedGeneratorRouter.java",
        "com.formacraft.common.generation.component.adaptor",
        "com.formacraft.server.generation.component.adaptor",
    ),
    (
        SRC / "common" / "init" / "SkeletonSystemInitializer.java",
        SRC / "server" / "init" / "SkeletonSystemInitializer.java",
        "com.formacraft.common.init",
        "com.formacraft.server.init",
    ),
    (
        SRC / "common" / "network" / "NetworkOrchestratorProvider.java",
        SRC / "server" / "network" / "NetworkOrchestratorProvider.java",
        "com.formacraft.common.network",
        "com.formacraft.server.network",
    ),
]

REPLACEMENTS = [
    ("com.formacraft.common.generation.component.adaptor.StructureGeneratorAdaptor",
     "com.formacraft.server.generation.component.adaptor.StructureGeneratorAdaptor"),
    ("com.formacraft.common.generation.component.adaptor.UnifiedGeneratorRouter",
     "com.formacraft.server.generation.component.adaptor.UnifiedGeneratorRouter"),
    ("com.formacraft.common.init.SkeletonSystemInitializer",
     "com.formacraft.server.init.SkeletonSystemInitializer"),
    ("com.formacraft.common.network.NetworkOrchestratorProvider",
     "com.formacraft.server.network.NetworkOrchestratorProvider"),
]


def move_files() -> None:
    for src, dst, old_pkg, new_pkg in MOVES:
        if not src.exists():
            raise SystemExit(f"missing source: {src}")
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


def cleanup_empty_adaptor_dir() -> None:
    adaptor = SRC / "common" / "generation" / "component" / "adaptor"
    if adaptor.exists() and not any(adaptor.iterdir()):
        adaptor.rmdir()
        print(f"removed empty {adaptor.relative_to(ROOT)}")


def main() -> None:
    move_files()
    n = patch_java()
    cleanup_empty_adaptor_dir()
    print(f"patched {n} java files")


if __name__ == "__main__":
    main()
