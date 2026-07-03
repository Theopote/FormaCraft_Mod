#!/usr/bin/env python3
"""
One-shot classifier for common → server / common → client boundary violations.

Usage:
    python scripts/classify_common_boundary_deps.py
    python scripts/classify_common_boundary_deps.py --json reports/boundary-classification.json

Categories:
  A  Only references server types that are pure data/DTOs (candidate: move types to common)
  B  Uses server world/runtime capabilities (candidate: move caller from common to server)
  C  Imports client tools/UI state (candidate: ToolConstraintSnapshot DTO from client)

A file can be tagged A+B or B+C when mixed. Primary action is the highest-priority fix.
"""

from __future__ import annotations

import argparse
import json
import re
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
COMMON_SRC = ROOT / "src" / "main" / "java" / "com" / "formacraft" / "common"

IMPORT_RE = re.compile(r"^import\s+(com\.formacraft\.(?:server|client)\.[^;]+);", re.MULTILINE)

# Server types that are structural data / DTOs — belong in common, not server.
DATA_ONLY_SERVER_TYPES: set[str] = {
  # GeneratedStructure + PlannedBlock moved to common.build (batch-A, 2026-07-03)
    "com.formacraft.server.build.BuildReportContext",
    "com.formacraft.server.assembly.AssemblySpec",
    "com.formacraft.server.assembly.validation.AssemblyValidationIssue",
    "com.formacraft.server.assembly.validation.AssemblySpecNormalizeResult",
    "com.formacraft.server.assembly.macro.AssemblyMacroApplyResult",
    "com.formacraft.server.cluster.TerrainFields",
    "com.formacraft.server.cluster.layout.BuildArea",
    "com.formacraft.server.cluster.layout.BuildingPlacement",
    "com.formacraft.server.cluster.layout.BuildingUnit",
    "com.formacraft.server.cluster.layout.Candidate",
    "com.formacraft.server.cluster.layout.ClusterLayoutConfig",
    "com.formacraft.server.skeleton.stack.VerticalStackSkeleton",
    "com.formacraft.server.skeleton.span.SpanSuspensionSkeleton",
    "com.formacraft.server.skeleton.linear.LinearPathSkeleton",
    "com.formacraft.server.skeleton.grid.GridSkeleton",
    "com.formacraft.server.interior.FloorPlanConfig",
    "com.formacraft.server.terrain.TerrainAdaptationMode",
    "com.formacraft.server.terrain.TerrainAdaptationSpec",
    "com.formacraft.server.terrain.TerrainPolicy",
}

# Server types that are runtime / world-touching even if they look like helpers.
RUNTIME_SERVER_PREFIXES: tuple[str, ...] = (
    "com.formacraft.server.generation.",
    "com.formacraft.server.skeleton.",  # interpreters, planners, dispatchers
    "com.formacraft.server.terrain.TerrainAdaptationEngine",
    "com.formacraft.server.terrain.TerrainAdaptationResolver",
    "com.formacraft.server.terrain.TerrainFit",
    "com.formacraft.server.terrain.TerrainPolicyResolver",
    "com.formacraft.server.foundation.",
    "com.formacraft.server.material.PaletteResolver",
    "com.formacraft.server.waterfront.",
    "com.formacraft.server.interior.BspFloorPlanGenerator",
    "com.formacraft.server.assembly.MetaAssembly",
    "com.formacraft.server.assembly.validation.AssemblySpecNormalizer",
    "com.formacraft.server.assembly.validation.AssemblySpecValidator",
    "com.formacraft.server.assembly.macro.AssemblyMacroApplier",
    "com.formacraft.server.cluster.layout.CandidateGenerator",
    "com.formacraft.server.cluster.layout.PlacementSolver",
    "com.formacraft.server.orchestrator.",
    "com.formacraft.server.skeleton.gen.",
)

CLIENT_TOOL_TYPES: set[str] = {
    "com.formacraft.client.tool.SelectionTool",
    "com.formacraft.client.tool.OutlineTool",
    "com.formacraft.client.tool.ProtectedZoneTool",
    "com.formacraft.client.tool.PathTool",
}

WORLD_SIGNALS: tuple[re.Pattern[str], ...] = (
    re.compile(r"\bServerWorld\b"),
    re.compile(r"\bworld\.getBlockState\s*\("),
    re.compile(r"\bworld\.setBlockState\s*\("),
    re.compile(r"\bTerrainScanner\b"),
    re.compile(r"\bTerrainStrategySampler\b"),
    re.compile(r"\bTerrainAdaptationEngine\b"),
)


def rel_path(path: Path) -> str:
    return path.relative_to(ROOT).as_posix()


def parse_imports(text: str) -> tuple[list[str], list[str]]:
    server, client = [], []
    for imp in IMPORT_RE.findall(text):
        if imp.startswith("com.formacraft.server."):
            server.append(imp)
        elif imp.startswith("com.formacraft.client."):
            client.append(imp)
    return server, client


def is_data_only_server_import(imp: str) -> bool:
    if imp in DATA_ONLY_SERVER_TYPES:
        return True
    # Unknown server import defaults to runtime unless explicitly listed as data.
    return False


def is_runtime_server_import(imp: str) -> bool:
    if is_data_only_server_import(imp):
        return False
    if any(imp.startswith(p) for p in RUNTIME_SERVER_PREFIXES):
        return True
    # Any other server import we did not whitelist as data is treated as runtime.
    return True


def has_world_signals(text: str) -> list[str]:
    hits = []
    for pat in WORLD_SIGNALS:
        if pat.search(text):
            hits.append(pat.pattern)
    return hits


def classify_file(path: Path) -> dict:
    text = path.read_text(encoding="utf-8")
    server_imports, client_imports = parse_imports(text)
    world_hits = has_world_signals(text)

    data_imports = [i for i in server_imports if is_data_only_server_import(i)]
    runtime_imports = [i for i in server_imports if is_runtime_server_import(i)]

    reasons: list[str] = []
    categories: list[str] = []

    if client_imports:
        categories.append("C")
        tool_hits = [i for i in client_imports if any(t in i for t in ("client.tool.", "client.ui.", "client.preview."))]
        if tool_hits:
            reasons.append(f"imports client UI/tools: {', '.join(_short(tool_hits))}")
        else:
            reasons.append(f"imports client: {', '.join(_short(client_imports))}")

    if runtime_imports or world_hits:
        categories.append("B")
        if runtime_imports:
            reasons.append(f"runtime server deps: {', '.join(_short(runtime_imports))}")
        if world_hits:
            reasons.append(f"world/terrain signals: {', '.join(world_hits[:3])}")

    if data_imports and not runtime_imports and not world_hits and not client_imports:
        categories.append("A")
        reasons.append(f"only data server deps: {', '.join(_short(data_imports))}")
    elif data_imports and ("B" in categories or "C" in categories):
        if "A" not in categories:
            categories.append("A")
        reasons.append(f"also has data-only deps (下沉候选): {', '.join(_short(data_imports))}")

    if not categories:
        if server_imports:
            categories.append("B")
            reasons.append("server imports present (unclassified — treat as B)")
        else:
            categories.append("NONE")
            reasons.append("no server/client imports")

    primary = "C" if "C" in categories else ("B" if "B" in categories else ("A" if "A" in categories else "NONE"))

    return {
        "file": rel_path(path),
        "primary": primary,
        "categories": categories,
        "reason": "; ".join(reasons),
        "server_imports": server_imports,
        "client_imports": client_imports,
        "data_imports": data_imports,
        "runtime_imports": runtime_imports,
        "world_signals": world_hits,
        "server_import_count": len(server_imports),
        "client_import_count": len(client_imports),
    }


def _short(imports: list[str]) -> list[str]:
    return [i.rsplit(".", 1)[-1] for i in imports]


def suggest_batch(entry: dict) -> str:
    p = entry["primary"]
    if p == "C":
        return "batch-C-tool-snapshot"
    if p == "B" and entry["file"].startswith("src/main/java/com/formacraft/common/generation/structure/"):
        return "batch-B-structure-generators"
    if p == "B" and "network" in entry["file"]:
        return "batch-B-network"
    if p == "B" and "compiler" in entry["file"]:
        return "batch-B-compiler"
    if p == "B" and "assembly" in entry["file"]:
        return "batch-B-assembly"
    if p == "B" and "patch/filter" in entry["file"]:
        return "batch-B-patch-filters"
    if p == "A":
        return "batch-A-data-sink"
    return "batch-B-misc"


def aggregate_data_sink_targets(entries: list[dict]) -> dict[str, int]:
    counts: dict[str, int] = defaultdict(int)
    for e in entries:
        for imp in e["data_imports"]:
            counts[imp] += 1
    return dict(sorted(counts.items(), key=lambda kv: (-kv[1], kv[0])))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--json", type=Path, help="Write full JSON report to this path")
    args = parser.parse_args()

    files = sorted(COMMON_SRC.rglob("*.java"))
    entries = [classify_file(f) for f in files]
    violating = [e for e in entries if e["server_import_count"] or e["client_import_count"]]

    by_primary: dict[str, list[dict]] = defaultdict(list)
    for e in violating:
        by_primary[e["primary"]].append(e)

    total_server_imports = sum(e["server_import_count"] for e in violating)
    total_client_imports = sum(e["client_import_count"] for e in violating)

    report = {
        "summary": {
            "common_java_files_scanned": len(files),
            "files_with_server_imports": sum(1 for e in violating if e["server_import_count"]),
            "files_with_client_imports": sum(1 for e in violating if e["client_import_count"]),
            "files_with_any_violation": len(violating),
            "total_server_import_lines": total_server_imports,
            "total_client_import_lines": total_client_imports,
            "by_primary": {k: len(v) for k, v in sorted(by_primary.items())},
            "data_sink_targets": aggregate_data_sink_targets(violating),
        },
        "entries": sorted(violating, key=lambda e: (e["primary"], e["file"])),
    }

    for e in report["entries"]:
        e["suggested_batch"] = suggest_batch(e)

    print("=" * 72)
    print("FormaCraft common boundary dependency classification")
    print("=" * 72)
    s = report["summary"]
    print(f"Scanned {s['common_java_files_scanned']} common/*.java files")
    print(f"Violations: {s['files_with_any_violation']} files "
          f"({s['total_server_import_lines']} server import lines, "
          f"{s['total_client_import_lines']} client import lines)")
    print(f"By primary category: {s['by_primary']}")
    print()

    for cat in ("A", "B", "C"):
        group = by_primary.get(cat, [])
        if not group:
            continue
        print(f"--- {cat}类 ({len(group)} files) ---")
        for e in sorted(group, key=lambda x: x["file"]):
            flags = "+".join(e["categories"]) if len(e["categories"]) > 1 else e["primary"]
            print(f"  [{flags}] {e['file']}")
            print(f"         {e['reason']}")
            print(f"         → {e['suggested_batch']}")
        print()

    print("--- 数据下沉候选 (server types referenced as data-only) ---")
    for typ, count in report["summary"]["data_sink_targets"].items():
        short = typ.replace("com.formacraft.server.", "server.")
        print(f"  {count:3d} refs  {short}")
    print()

    print("--- 建议第二步 batch 顺序 ---")
    batches = defaultdict(list)
    for e in report["entries"]:
        batches[e["suggested_batch"]].append(e["file"])
    order = [
        "batch-A-data-sink",
        "batch-C-tool-snapshot",
        "batch-B-structure-generators",
        "batch-B-compiler",
        "batch-B-assembly",
        "batch-B-patch-filters",
        "batch-B-network",
        "batch-B-misc",
    ]
    for batch in order:
        if batch in batches:
            print(f"  {batch}: {len(batches[batch])} files")

    if args.json:
        args.json.parent.mkdir(parents=True, exist_ok=True)
        args.json.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        print(f"\nJSON report written to {args.json}")


if __name__ == "__main__":
    main()
