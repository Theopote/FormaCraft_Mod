"""Typology routing baseline — offline plan classification for M8.3 metrics (8.17).

Usage (from python_backend):

    python -m eval.routing_baseline              # print summary
    python -m eval.routing_baseline --write      # regenerate docs/metrics/typology_routing_baseline.md
"""

from __future__ import annotations

import argparse
import json
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional, Set, Tuple

_THIS = Path(__file__).resolve()
_BACKEND_ROOT = _THIS.parent.parent
_REPO_ROOT = _BACKEND_ROOT.parent
_FIXTURES = _THIS.parent / "fixtures"
_DOCS_OUT = _REPO_ROOT / "docs" / "metrics" / "typology_routing_baseline.md"

COMPOSITIONAL_TYPES = {
    "MASS_MAIN", "MASS_SECONDARY", "MASS_WING", "SIDE_WING",
    "ROOF", "ENTRANCE", "FACADE_WINDOWS", "FOUNDATION", "PAVING",
    "TERRACE", "BALCONY", "DECOR", "COURTYARD_SPACE", "TOWER",
    "TOWER_BASE", "TOWER_MID", "TOWER_TOP", "WALL", "WALL_SEGMENT",
    "KEEP", "BRIDGE", "CONNECTOR", "PRIMITIVE", "GEOMETRY", "ASSEMBLY",
}

PATH_PRIORITY = (
    "typology_builder",
    "module",
    "structure_generator",
    "compositional",
    "unknown",
)


def _load_migration_map() -> Dict[str, str]:
    path = (
        _REPO_ROOT
        / "src"
        / "main"
        / "resources"
        / "assets"
        / "formacraft"
        / "structural_typologies"
        / "structural_typologies_v1.json"
    )
    if not path.is_file():
        return {}
    data = json.loads(path.read_text(encoding="utf-8"))
    out: Dict[str, str] = {}
    for card_id, entry in (data.get("migrationMap") or {}).items():
        if isinstance(entry, dict):
            tid = str(entry.get("typologyId") or "").strip()
            if tid:
                out[str(card_id).strip()] = tid
    return out


def _feature_prefix(features: Any, prefix: str) -> Optional[str]:
    if not isinstance(features, list):
        return None
    p = prefix.lower()
    for feat in features:
        if not isinstance(feat, str):
            continue
        low = feat.lower()
        if low.startswith(p):
            return feat[len(prefix) :].strip()
    return None


def _typology_id(comp: Dict[str, Any]) -> Optional[str]:
    feat = _feature_prefix(comp.get("features"), "typology:")
    if feat:
        return feat
    params = comp.get("params") if isinstance(comp.get("params"), dict) else {}
    for key in ("typology_id", "structural_typology", "typologyId", "structuralTypologyId"):
        val = params.get(key)
        if val is not None and str(val).strip():
            return str(val).strip()
    module_id = _landmark_module_id(comp)
    if module_id:
        migrated = _MIGRATION.get(module_id)
        if migrated:
            return migrated
    return None


def _landmark_module_id(comp: Dict[str, Any]) -> Optional[str]:
    feat = _feature_prefix(comp.get("features"), "landmark:")
    if feat:
        return feat
    feat = _feature_prefix(comp.get("features"), "module:")
    if feat:
        return feat
    params = comp.get("params") if isinstance(comp.get("params"), dict) else {}
    mid = params.get("module_id")
    if mid is not None and str(mid).strip():
        return str(mid).strip()
    return None


def _component_type(comp: Dict[str, Any]) -> str:
    return str(comp.get("component_type") or "").strip().upper()


def _has_structure_generator_hint(comp: Dict[str, Any]) -> bool:
    if _feature_prefix(comp.get("features"), "structure_generator:"):
        return True
    if _typology_id(comp):
        return False
    if _feature_prefix(comp.get("features"), "landmark:") or _feature_prefix(comp.get("features"), "module:"):
        return True
    if _component_type(comp) == "STRUCTURE" and not _typology_id(comp):
        return True
    return False


def classify_component(comp: Dict[str, Any]) -> str:
    if _typology_id(comp):
        return "typology_builder"
    module_id = _landmark_module_id(comp)
    if module_id and _component_type(comp) == "MODULE" and module_id not in _MIGRATION:
        return "module"
    if _has_structure_generator_hint(comp):
        return "structure_generator"
    if _component_type(comp) in COMPOSITIONAL_TYPES:
        return "compositional"
    return "unknown"


def classify_plan(plan: Dict[str, Any]) -> Dict[str, Any]:
    components = plan.get("components") if isinstance(plan.get("components"), list) else []
    per_component: List[str] = []
    flags = {p: False for p in PATH_PRIORITY}

    for comp in components:
        if not isinstance(comp, dict):
            continue
        path = classify_component(comp)
        per_component.append(path)
        flags[path] = True

    primary = "unknown"
    for path in PATH_PRIORITY:
        if flags.get(path):
            primary = path
            break

    return {
        "primary_path": primary,
        "component_paths": per_component,
        "flags": flags,
        "component_count": len(per_component),
    }


@dataclass
class PlanSample:
    plan_id: str
    prompt: str
    source: str
    classification: Dict[str, Any]


@dataclass
class BaselineStats:
    total: int = 0
    by_path: Dict[str, int] = field(default_factory=lambda: {p: 0 for p in PATH_PRIORITY})
    samples: List[PlanSample] = field(default_factory=list)

    def add(self, sample: PlanSample) -> None:
        self.total += 1
        primary = sample.classification.get("primary_path", "unknown")
        self.by_path[primary] = self.by_path.get(primary, 0) + 1
        self.samples.append(sample)

    def rate(self, path: str) -> float:
        if self.total <= 0:
            return 0.0
        return 100.0 * self.by_path.get(path, 0) / self.total

    def typology_first_rate(self) -> float:
        if self.total <= 0:
            return 0.0
        good = self.by_path.get("compositional", 0) + self.by_path.get("typology_builder", 0)
        return 100.0 * good / self.total

    def structure_generator_rate(self) -> float:
        if self.total <= 0:
            return 0.0
        legacy = self.by_path.get("structure_generator", 0) + self.by_path.get("module", 0)
        return 100.0 * legacy / self.total


def _load_plan(path: Path) -> Dict[str, Any]:
    data = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(data, dict):
        raise ValueError(f"plan root must be object: {path}")
    return data


def _iter_scenario_plans() -> Iterable[Tuple[str, str, Path]]:
    scenarios_path = _FIXTURES / "scenarios.json"
    if not scenarios_path.is_file():
        return
    scenarios = json.loads(scenarios_path.read_text(encoding="utf-8"))
    if not isinstance(scenarios, list):
        return
    for sc in scenarios:
        if not isinstance(sc, dict):
            continue
        fixture = sc.get("plan_fixture")
        if not isinstance(fixture, str) or not fixture.strip():
            continue
        path = _FIXTURES / fixture
        if path.is_file():
            yield str(sc.get("id") or path.stem), str(sc.get("prompt") or ""), path


def _iter_diversity_plans() -> Iterable[Tuple[str, str, Path]]:
    diversity_dir = _FIXTURES / "diversity"
    if not diversity_dir.is_dir():
        return
    for path in sorted(diversity_dir.rglob("*.json")):
        yield path.stem, "", path


def _iter_orphan_fixture_plans(seen: Set[Path]) -> Iterable[Tuple[str, str, Path]]:
    plans_dir = _FIXTURES / "plans"
    if not plans_dir.is_dir():
        return
    for path in sorted(plans_dir.glob("*.json")):
        if path in seen:
            continue
        meta = {}
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
            if isinstance(data.get("_meta"), dict):
                meta = data["_meta"]
        except Exception:
            pass
        prompt = str(meta.get("prompt") or "")
        yield path.stem, prompt, path


def collect_baseline() -> BaselineStats:
    stats = BaselineStats()
    seen: Set[Path] = set()

    for plan_id, prompt, path in _iter_scenario_plans():
        seen.add(path.resolve())
        plan = _load_plan(path)
        stats.add(PlanSample(plan_id, prompt, str(path.relative_to(_REPO_ROOT)), classify_plan(plan)))

    for plan_id, prompt, path in _iter_diversity_plans():
        seen.add(path.resolve())
        plan = _load_plan(path)
        stats.add(PlanSample(plan_id, prompt or plan_id, str(path.relative_to(_REPO_ROOT)), classify_plan(plan)))

    for plan_id, prompt, path in _iter_orphan_fixture_plans(seen):
        plan = _load_plan(path)
        stats.add(PlanSample(plan_id, prompt, str(path.relative_to(_REPO_ROOT)), classify_plan(plan)))

    return stats


def render_markdown(stats: BaselineStats) -> str:
    now = datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M UTC")
    lines = [
        "# Typology Routing Baseline",
        "",
        f"> Auto-generated by `python -m eval.routing_baseline --write` on {now}.",
        "",
        "## Summary",
        "",
        f"- **Plans analyzed**: {stats.total}",
        f"- **Typology-first rate** (compositional + typology_builder): **{stats.typology_first_rate():.1f}%**",
        f"- **StructureGenerator / MODULE rate** (structure_generator + module): **{stats.structure_generator_rate():.1f}%**",
        "",
        "| Primary path | Count | Share |",
        "|---|---:|---:|",
    ]
    for path in PATH_PRIORITY:
        count = stats.by_path.get(path, 0)
        share = stats.rate(path)
        lines.append(f"| `{path}` | {count} | {share:.1f}% |")

    lines.extend([
        "",
        "## Target (Phase 3)",
        "",
        "- **80% typology-first**: compositional + typology_builder primary path on landmark / archetype prompts.",
        f"- Current baseline: **{stats.typology_first_rate():.1f}%** — gap **{max(0.0, 80.0 - stats.typology_first_rate()):.1f}pp**.",
        "",
        "## Per-plan breakdown",
        "",
        "| Plan ID | Prompt | Primary path | Components | Source |",
        "|---|---|---|---:|---|",
    ])
    for sample in sorted(stats.samples, key=lambda s: s.plan_id):
        prompt = sample.prompt.replace("|", "\\|")[:60]
        if len(sample.prompt) > 60:
            prompt += "…"
        cls = sample.classification
        lines.append(
            f"| `{sample.plan_id}` | {prompt or '-'} | `{cls.get('primary_path')}` "
            f"| {cls.get('component_count', 0)} | `{sample.source}` |"
        )

    lines.extend([
        "",
        "## Log grep (runtime telemetry)",
        "",
        "```powershell",
        "Select-String \"\\[TypologyMetrics\\]\" logs/latest.log",
        "```",
        "",
        "Events: `compositional_hit`, `typology_builder_hit`, `structure_generator_hit`, `module_hit`, `deprecated_module_use`.",
        "",
    ])
    return "\n".join(lines) + "\n"


_MIGRATION = _load_migration_map()


def main(argv: Optional[List[str]] = None) -> int:
    parser = argparse.ArgumentParser(description="Typology routing baseline report")
    parser.add_argument("--write", action="store_true", help="Write docs/metrics/typology_routing_baseline.md")
    args = parser.parse_args(argv)

    stats = collect_baseline()
    md = render_markdown(stats)

    print(f"Plans analyzed: {stats.total}")
    for path in PATH_PRIORITY:
        print(f"  {path}: {stats.by_path.get(path, 0)} ({stats.rate(path):.1f}%)")
    print(f"Typology-first rate: {stats.typology_first_rate():.1f}%")
    print(f"StructureGenerator/MODULE rate: {stats.structure_generator_rate():.1f}%")

    if args.write:
        _DOCS_OUT.parent.mkdir(parents=True, exist_ok=True)
        _DOCS_OUT.write_text(md, encoding="utf-8")
        print(f"Wrote {_DOCS_OUT}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
