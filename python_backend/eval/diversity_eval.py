"""LlmPlan 多样化（diversity）评估。

衡量同一 prompt 多次生成时 plan 的结构差异，避免「同描述 → 同 blueprint」。

用法（在 python_backend 目录下）：

    # 离线：对一组 plan JSON 计算 diversity 指标
    python -m eval.diversity_eval --plans eval/fixtures/diversity/stadium

    # 通过 golden_eval 入口
    python -m eval.golden_eval --diversity --plans eval/fixtures/diversity/stadium

    # 在线：同一 prompt 采样 N 次（需 LLM）
    python -m eval.diversity_eval --live --prompt "在锚点位置生成现代风格的椭圆形体育场建筑" --samples 3
"""

from __future__ import annotations

import argparse
import json
import math
import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Dict, FrozenSet, Iterable, List, Optional, Sequence, Tuple

_THIS = Path(__file__).resolve()
_BACKEND_ROOT = _THIS.parent.parent
if str(_BACKEND_ROOT) not in sys.path:
    sys.path.insert(0, str(_BACKEND_ROOT))


# ---------------------------------------------------------------------------
# Plan normalization / signature
# ---------------------------------------------------------------------------

def unwrap_plan(data: Dict[str, Any]) -> Dict[str, Any]:
    """支持平铺 plan、{plan: ...} few-shot 包裹、带 _meta 的捕获 fixture。"""
    if not isinstance(data, dict):
        return {}
    inner = data.get("plan")
    if isinstance(inner, dict):
        return inner
    return data


def _components(plan: Dict[str, Any]) -> List[Dict[str, Any]]:
    comps = plan.get("components")
    return [c for c in comps if isinstance(c, dict)] if isinstance(comps, list) else []


def _has_landmark_feature(comp: Dict[str, Any], module_id: Optional[str] = None) -> bool:
    features = comp.get("features")
    if not isinstance(features, list):
        return False
    for f in features:
        if not isinstance(f, str):
            continue
        lower = f.lower()
        if lower.startswith("landmark:"):
            if module_id is None:
                return True
            if lower == f"landmark:{module_id.lower()}":
                return True
    return False


def routing_path(plan: Dict[str, Any]) -> str:
    """粗粒度路由：module / compositional / program / simple."""
    if any(k in plan for k in ("plan_skeleton", "planSkeleton", "plan_program", "planProgram")):
        return "program"
    comps = _components(plan)
    if any(_has_landmark_feature(c) for c in comps):
        return "module"
    if len(comps) >= 3:
        return "compositional"
    if comps:
        return "simple"
    return "empty"


def _param_tokens(params: Dict[str, Any], keys: Tuple[str, ...]) -> List[str]:
    out: List[str] = []
    for k in keys:
        if k not in params:
            continue
        v = params[k]
        if isinstance(v, bool):
            out.append(f"{k}:{'true' if v else 'false'}")
        elif isinstance(v, (int, float)):
            out.append(f"{k}:{v}")
        elif isinstance(v, str) and v.strip():
            out.append(f"{k}:{v.strip().lower()}")
        elif isinstance(v, list) and v:
            out.append(f"{k}:list[{len(v)}]")
    return out


def plan_signature_tokens(plan: Dict[str, Any]) -> FrozenSet[str]:
    """
    可比较的结构化 token 集：用于 Jaccard 距离与去重计数。
    忽略 anchor 绝对坐标（与 prompt 无关的放置噪声）。
    """
    tokens: set[str] = set()
    tokens.add(f"routing:{routing_path(plan)}")

    sp = plan.get("style_profile")
    if isinstance(sp, str) and sp.strip():
        tokens.add(f"style_profile:{sp.strip()}")

    gc = plan.get("global_constraints")
    if isinstance(gc, dict):
        facing = gc.get("facing")
        if isinstance(facing, str) and facing.strip():
            tokens.add(f"global_facing:{facing.strip().upper()}")
        sym = gc.get("symmetry")
        if isinstance(sym, str) and sym.strip():
            tokens.add(f"global_symmetry:{sym.strip().upper()}")

    layout = plan.get("layout")
    if isinstance(layout, dict):
        sk = layout.get("skeleton_type")
        if isinstance(sk, str) and sk.strip():
            tokens.add(f"skeleton:{sk.strip().upper()}")

    attrs = plan.get("style_attributes")
    if isinstance(attrs, dict):
        for k in sorted(attrs.keys()):
            v = attrs[k]
            if isinstance(v, str) and v.strip():
                tokens.add(f"attr:{k}:{v.strip().lower()}")
            elif isinstance(v, list):
                for item in sorted(str(x).lower() for x in v if x is not None):
                    tokens.add(f"attr:{k}:{item}")

    type_counts: Dict[str, int] = {}
    for c in _components(plan):
        t = c.get("component_type")
        if isinstance(t, str) and t.strip():
            key = t.strip().upper()
            type_counts[key] = type_counts.get(key, 0) + 1
    for t, n in sorted(type_counts.items()):
        tokens.add(f"type:{t}x{n}")

    for i, c in enumerate(_components(plan)):
        prefix = f"c{i}"
        ct = c.get("component_type")
        if isinstance(ct, str):
            tokens.add(f"{prefix}:type:{ct.upper()}")

        for feat in c.get("features") or []:
            if isinstance(feat, str) and feat.strip():
                tokens.add(f"{prefix}:feat:{feat.strip().lower()}")

        dims = c.get("dimensions")
        if isinstance(dims, dict):
            w, d, h = dims.get("width"), dims.get("depth"), dims.get("height")
            if all(isinstance(x, (int, float)) for x in (w, d, h)):
                tokens.add(f"{prefix}:dim:{int(w)}x{int(d)}x{int(h)}")

        params = c.get("params")
        if isinstance(params, dict):
            tokens.update(f"{prefix}:{t}" for t in _param_tokens(params, (
                "shape", "facade_profile", "facadeProfile", "roof_type", "roofType",
                "designSeed", "bowlSteepness", "facing", "gateSide", "meshStructure",
                "void_ratio", "voidRatio", "plan_type", "planType", "module_id",
            )))
            masses = params.get("masses")
            if isinstance(masses, list):
                tokens.add(f"{prefix}:masses:{len(masses)}")
                for j, m in enumerate(masses):
                    if isinstance(m, dict):
                        scale = m.get("scale")
                        if isinstance(scale, (int, float)):
                            tokens.add(f"{prefix}:mass{j}:scale:{scale}")

    return frozenset(tokens)


def jaccard_distance(a: FrozenSet[str], b: FrozenSet[str]) -> float:
    if not a and not b:
        return 0.0
    union = a | b
    if not union:
        return 0.0
    inter = a & b
    return 1.0 - (len(inter) / len(union))


def _pairwise_distances(signatures: Sequence[FrozenSet[str]]) -> List[float]:
    dists: List[float] = []
    n = len(signatures)
    for i in range(n):
        for j in range(i + 1, n):
            dists.append(jaccard_distance(signatures[i], signatures[j]))
    return dists


def _primary_module_dims(plan: Dict[str, Any]) -> Optional[Tuple[int, int, int]]:
    for c in _components(plan):
        if c.get("component_type", "").upper() == "MODULE" or _has_landmark_feature(c):
            dims = c.get("dimensions")
            if isinstance(dims, dict):
                w, d, h = dims.get("width"), dims.get("depth"), dims.get("height")
                if all(isinstance(x, (int, float)) for x in (w, d, h)):
                    return int(w), int(d), int(h)
    for c in _components(plan):
        if c.get("component_type", "").upper() == "MASS_MAIN":
            dims = c.get("dimensions")
            if isinstance(dims, dict):
                w, d, h = dims.get("width"), dims.get("depth"), dims.get("height")
                if all(isinstance(x, (int, float)) for x in (w, d, h)):
                    return int(w), int(d), int(h)
    return None


def _extract_numeric_params(plan: Dict[str, Any], key: str) -> List[float]:
    vals: List[float] = []
    for c in _components(plan):
        params = c.get("params")
        if not isinstance(params, dict):
            continue
        v = params.get(key)
        if isinstance(v, bool):
            vals.append(1.0 if v else 0.0)
        elif isinstance(v, (int, float)):
            vals.append(float(v))
    return vals


def _stddev(values: Sequence[float]) -> float:
    if len(values) < 2:
        return 0.0
    mean = sum(values) / len(values)
    var = sum((x - mean) ** 2 for x in values) / len(values)
    return math.sqrt(var)


# ---------------------------------------------------------------------------
# Metrics & thresholds
# ---------------------------------------------------------------------------

@dataclass
class DiversityCheck:
    name: str
    passed: bool
    hard: bool
    detail: str = ""
    value: Optional[float] = None


@dataclass
class DiversityMetrics:
    label: str
    sample_count: int
    unique_signatures: int
    unique_ratio: float
    routing_paths: List[str]
    routing_unique: int
    mean_pairwise_distance: float
    min_pairwise_distance: float
    max_pairwise_distance: float
    design_seed_spread: float
    bowl_steepness_spread: float
    dimension_spread: Dict[str, float] = field(default_factory=dict)
    checks: List[DiversityCheck] = field(default_factory=list)

    @property
    def ok(self) -> bool:
        return not any(c.hard and not c.passed for c in self.checks)


@dataclass
class DiversityThresholds:
    """默认阈值：3+ 样本时要求半数以上不重复，且平均 Jaccard 距离 ≥ 0.12。"""
    min_samples: int = 2
    min_unique_ratio: float = 0.5
    min_mean_distance: float = 0.12
    min_routing_unique: int = 1
    require_routing_diversity: bool = False  # True 时要求多种 routing path


DEFAULT_THRESHOLDS = DiversityThresholds()


def evaluate_diversity(
    plans: Sequence[Dict[str, Any]],
    *,
    label: str = "diversity",
    thresholds: DiversityThresholds = DEFAULT_THRESHOLDS,
) -> DiversityMetrics:
    signatures = [plan_signature_tokens(p) for p in plans]
    n = len(plans)
    unique_sigs = len(set(signatures))
    unique_ratio = unique_sigs / n if n else 0.0

    routes = [routing_path(p) for p in plans]
    routing_unique = len(set(routes))

    dists = _pairwise_distances(signatures)
    mean_d = sum(dists) / len(dists) if dists else 0.0
    min_d = min(dists) if dists else 0.0
    max_d = max(dists) if dists else 0.0

    widths, depths, heights = [], [], []
    for p in plans:
        dims = _primary_module_dims(p)
        if dims:
            widths.append(float(dims[0]))
            depths.append(float(dims[1]))
            heights.append(float(dims[2]))

    dim_spread = {
        "width_std": _stddev(widths),
        "depth_std": _stddev(depths),
        "height_std": _stddev(heights),
    }
    seed_spread = _stddev(_extract_numeric_params_from_plans(plans, "designSeed"))
    bowl_spread = _stddev(_extract_numeric_params_from_plans(plans, "bowlSteepness"))

    checks: List[DiversityCheck] = []

    checks.append(DiversityCheck(
        "min_samples",
        n >= thresholds.min_samples,
        hard=True,
        detail=f"samples={n}, need>={thresholds.min_samples}",
        value=float(n),
    ))

    if n >= thresholds.min_samples:
        checks.append(DiversityCheck(
            "unique_ratio",
            unique_ratio >= thresholds.min_unique_ratio,
            hard=False,
            detail=f"unique={unique_sigs}/{n} ratio={unique_ratio:.3f} "
                   f"(need>={thresholds.min_unique_ratio})",
            value=unique_ratio,
        ))
        checks.append(DiversityCheck(
            "mean_pairwise_distance",
            mean_d >= thresholds.min_mean_distance,
            hard=False,
            detail=f"mean={mean_d:.3f} min={min_d:.3f} max={max_d:.3f} "
                   f"(need mean>={thresholds.min_mean_distance})",
            value=mean_d,
        ))
        checks.append(DiversityCheck(
            "routing_variety",
            routing_unique >= thresholds.min_routing_unique,
            hard=thresholds.require_routing_diversity,
            detail=f"paths={routes} unique={routing_unique}",
            value=float(routing_unique),
        ))
        if seed_spread > 0:
            checks.append(DiversityCheck(
                "design_seed_spread",
                True,
                hard=False,
                detail=f"designSeed std={seed_spread:.1f}",
                value=seed_spread,
            ))
        if bowl_spread > 0:
            checks.append(DiversityCheck(
                "bowl_steepness_spread",
                True,
                hard=False,
                detail=f"bowlSteepness std={bowl_spread:.3f}",
                value=bowl_spread,
            ))

    return DiversityMetrics(
        label=label,
        sample_count=n,
        unique_signatures=unique_sigs,
        unique_ratio=unique_ratio,
        routing_paths=routes,
        routing_unique=routing_unique,
        mean_pairwise_distance=mean_d,
        min_pairwise_distance=min_d,
        max_pairwise_distance=max_d,
        design_seed_spread=seed_spread,
        bowl_steepness_spread=bowl_spread,
        dimension_spread=dim_spread,
        checks=checks,
    )


def _extract_numeric_params_from_plans(plans: Sequence[Dict[str, Any]], key: str) -> List[float]:
    out: List[float] = []
    for p in plans:
        out.extend(_extract_numeric_params(p, key))
    return out


def _load_plan_file(path: Path) -> Optional[Dict[str, Any]]:
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except Exception as e:
        print(f"  ! 跳过 {path.name}: 无法解析 JSON ({e})")
        return None
    if not isinstance(data, dict):
        return None
    return unwrap_plan(data)


def _print_metrics(m: DiversityMetrics) -> None:
    status = "PASS" if m.ok else "FAIL"
    print(f"\n=== Diversity: {m.label} — {status} ===")
    print(f"  samples={m.sample_count}  unique_signatures={m.unique_signatures}  "
          f"unique_ratio={m.unique_ratio:.3f}")
    print(f"  routing={m.routing_paths}  routing_unique={m.routing_unique}")
    print(f"  pairwise_distance: mean={m.mean_pairwise_distance:.3f}  "
          f"min={m.min_pairwise_distance:.3f}  max={m.max_pairwise_distance:.3f}")
    if any(m.dimension_spread.values()):
        print(f"  dimension_spread: {m.dimension_spread}")
    if m.design_seed_spread > 0:
        print(f"  designSeed_std={m.design_seed_spread:.1f}")
    if m.bowl_steepness_spread > 0:
        print(f"  bowlSteepness_std={m.bowl_steepness_spread:.3f}")
    for c in m.checks:
        mark = "OK" if c.passed else ("FAIL" if c.hard else "WARN")
        tier = "HARD" if c.hard else "soft"
        line = f"  [{mark}] {c.name} ({tier})"
        if c.detail:
            line += f" — {c.detail}"
        print(line)


def run_offline_diversity(
    paths: List[Path],
    *,
    label: str = "offline",
    thresholds: DiversityThresholds = DEFAULT_THRESHOLDS,
    gate: bool = False,
) -> int:
    plans: List[Dict[str, Any]] = []
    for p in paths:
        plan = _load_plan_file(p)
        if plan:
            plans.append(plan)

    if not plans:
        print("没有可评估的 plan 文件。")
        return 2

    metrics = evaluate_diversity(plans, label=label, thresholds=thresholds)
    _print_metrics(metrics)

    hard_fail = any(c.hard and not c.passed for c in metrics.checks)
    soft_fail = any(not c.hard and not c.passed for c in metrics.checks)
    if hard_fail:
        return 1
    if gate and soft_fail:
        return 1
    return 0


def _load_diversity_scenarios() -> List[Dict[str, Any]]:
    path = _THIS.parent / "fixtures" / "diversity_scenarios.json"
    if not path.is_file():
        return []
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
        return data if isinstance(data, list) else []
    except Exception:
        return []


def run_diversity_scenarios(gate: bool = False, ci_only: bool = False) -> int:
    scenarios = _load_diversity_scenarios()
    if not scenarios:
        print("未找到 eval/fixtures/diversity_scenarios.json")
        return 2

    exit_code = 0
    for sc in scenarios:
        if not isinstance(sc, dict):
            continue
        if ci_only and not sc.get("ci"):
            continue
        if sc.get("expect_fail"):
            continue
        label = sc.get("id") or "scenario"
        rel_plans = sc.get("plan_fixtures")
        if not isinstance(rel_plans, list):
            continue
        paths = [_THIS.parent / "fixtures" / rel for rel in rel_plans if isinstance(rel, str)]
        th = sc.get("thresholds") if isinstance(sc.get("thresholds"), dict) else {}
        thresholds = DiversityThresholds(
            min_samples=int(th.get("min_samples", DEFAULT_THRESHOLDS.min_samples)),
            min_unique_ratio=float(th.get("min_unique_ratio", DEFAULT_THRESHOLDS.min_unique_ratio)),
            min_mean_distance=float(th.get("min_mean_distance", DEFAULT_THRESHOLDS.min_mean_distance)),
            min_routing_unique=int(th.get("min_routing_unique", DEFAULT_THRESHOLDS.min_routing_unique)),
            require_routing_diversity=bool(th.get("require_routing_diversity", False)),
        )
        code = run_offline_diversity(paths, label=label, thresholds=thresholds, gate=gate)
        exit_code = max(exit_code, code)
    return exit_code


def run_live_diversity(
    prompt: str,
    *,
    samples: int = 3,
    gate: bool = False,
    thresholds: DiversityThresholds = DEFAULT_THRESHOLDS,
) -> int:
    try:
        from app.models.request import BuildRequest, PlayerInfo, Vec3i, WorldContext
        from app.services.ai_planner import generate_llm_plan
    except Exception as e:
        print(f"无法导入后端模块，跳过 live diversity：{e}")
        return 2

    plans: List[Dict[str, Any]] = []
    for i in range(samples):
        req = BuildRequest(
            player=PlayerInfo(name="diversity_eval", pos=Vec3i(x=0, y=64, z=0), facing="SOUTH"),
            world=WorldContext(dimension="minecraft:overworld", biome="minecraft:plains"),
            requestText=prompt,
            userMessage=prompt,
            promptMode="BUILD",
            outputFormat="llmplan",
        )
        try:
            plan = generate_llm_plan(req)
            if isinstance(plan, dict):
                plans.append(plan)
                print(f"  sample {i + 1}/{samples}: routing={routing_path(plan)} "
                      f"types={[c.get('component_type') for c in _components(plan)]}")
        except Exception as e:
            print(f"  sample {i + 1}/{samples} FAILED: {e}")

    if len(plans) < thresholds.min_samples:
        print(f"有效样本不足：{len(plans)}/{samples}")
        return 1

    metrics = evaluate_diversity(
        plans,
        label=f"live | {prompt[:32]}…",
        thresholds=thresholds,
    )
    _print_metrics(metrics)

    hard_fail = any(c.hard and not c.passed for c in metrics.checks)
    soft_fail = any(not c.hard and not c.passed for c in metrics.checks)
    if hard_fail:
        return 1
    if gate and soft_fail:
        return 1
    return 0


def main(argv: Optional[List[str]] = None) -> int:
    parser = argparse.ArgumentParser(description="FormaCraft LlmPlan diversity 评估")
    parser.add_argument("--plans", type=str, help="plan JSON 目录或单个文件")
    parser.add_argument("--scenarios", action="store_true",
                        help="运行 eval/fixtures/diversity_scenarios.json")
    parser.add_argument("--live", action="store_true", help="在线采样同一 prompt 多次")
    parser.add_argument("--prompt", type=str, default="在锚点位置生成现代风格的椭圆形体育场建筑")
    parser.add_argument("--samples", type=int, default=3, help="live 模式采样次数")
    parser.add_argument("--gate", action="store_true", help="SOFT 告警也计入失败")
    parser.add_argument("--min-unique-ratio", type=float, default=DEFAULT_THRESHOLDS.min_unique_ratio)
    parser.add_argument("--min-mean-distance", type=float, default=DEFAULT_THRESHOLDS.min_mean_distance)
    args = parser.parse_args(argv)

    thresholds = DiversityThresholds(
        min_unique_ratio=args.min_unique_ratio,
        min_mean_distance=args.min_mean_distance,
    )

    if args.scenarios:
        return run_diversity_scenarios(gate=args.gate)

    if args.live:
        return run_live_diversity(args.prompt, samples=args.samples, gate=args.gate, thresholds=thresholds)

    if not args.plans:
        print(__doc__)
        return 0

    p = Path(args.plans)
    paths: List[Path] = []
    if p.is_dir():
        paths.extend(sorted(p.glob("*.json")))
    elif p.is_file():
        paths.append(p)
    else:
        print(f"路径不存在：{p}")
        return 2

    return run_offline_diversity(paths, label=p.name, thresholds=thresholds, gate=args.gate)


if __name__ == "__main__":
    raise SystemExit(main())
