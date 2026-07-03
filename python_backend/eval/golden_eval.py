"""FormaCraft 生成质量评估（Phase 5：eval 闭环）。

目的：给"描述 → LlmPlan"这条唯一主干一套可量化的回归基准，让后续每次
prompt / schema / 生成器改动都能被结构性断言度量，而不是靠肉眼看单张截图。

用法（在 python_backend 目录下）：

    # 离线：对一批已捕获的 LlmPlan JSON 打分（推荐，无需 API key）
    python -m eval.golden_eval --plans path/to/plans_dir
    python -m eval.golden_eval --plan  path/to/one_plan.json

    # 在线：真实跑一遍 golden prompt（需要配置 LLM，且质量受 Java 端系统
    #        prompt 影响，仅作冒烟用）
    python -m eval.golden_eval --live

设计原则：
- 复用后端真实的 ``validate_llm_plan_dict``（schema 合法性），避免评估与实现漂移。
- 在 schema 之上叠加"可建造性/丰富度"启发式（有门、有窗、无零尺寸、无超大体量…）。
- 断言分两级：``HARD``（不通过则退出码非 0）与 ``SOFT``（仅告警，用于观察质量趋势）。
"""

from __future__ import annotations

import argparse
import json
import os
import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

# 允许以脚本或模块方式运行：把 python_backend 放到 import path。
_THIS = Path(__file__).resolve()
_BACKEND_ROOT = _THIS.parent.parent
if str(_BACKEND_ROOT) not in sys.path:
    sys.path.insert(0, str(_BACKEND_ROOT))


# 与 ARCHITECTURE.md 1.6 的 golden-path 清单保持一致。
GOLDEN_PROMPTS: List[str] = [
    "盖一个 7x7 的小石头房子，带门和窗",
    "盖一座 5 层的方塔，顶部有平台",
    "盖一个带院子的四合院",
    "盖一座天坛",
    "把屋顶换成红色",  # patch 类
]

# 尺寸合理性上限（与 Java OrchestratorClient.validateBuildingSpec 的 200 对齐）。
MAX_DIMENSION = 200

# 语义关键字：用于"可建造性"启发式。
_ENTRANCE_TOKENS = ("DOOR", "ENTRANCE", "GATE", "PORTAL", "ARCH")
_WINDOW_TOKENS = ("WINDOW", "FACADE")
_ROOF_TOKENS = ("ROOF", "DOME", "SPIRE", "EAVE")


@dataclass
class Check:
    name: str
    passed: bool
    hard: bool
    detail: str = ""


@dataclass
class EvalResult:
    label: str
    checks: List[Check] = field(default_factory=list)

    @property
    def hard_failures(self) -> List[Check]:
        return [c for c in self.checks if c.hard and not c.passed]

    @property
    def soft_failures(self) -> List[Check]:
        return [c for c in self.checks if (not c.hard) and not c.passed]

    @property
    def ok(self) -> bool:
        return not self.hard_failures


def _components(plan: Dict[str, Any]) -> List[Dict[str, Any]]:
    comps = plan.get("components")
    return [c for c in comps if isinstance(c, dict)] if isinstance(comps, list) else []


def _slots(plan: Dict[str, Any]) -> List[Dict[str, Any]]:
    layout = plan.get("layout")
    if not isinstance(layout, dict):
        return []
    slots = layout.get("slots")
    return [s for s in slots if isinstance(s, dict)] if isinstance(slots, list) else []


def _component_types(plan: Dict[str, Any]) -> List[str]:
    out: List[str] = []
    for c in _components(plan):
        t = c.get("component_type")
        if isinstance(t, str) and t.strip():
            out.append(t.strip().upper())
    return out


def _any_token(types: List[str], tokens: Tuple[str, ...]) -> bool:
    return any(any(tok in t for tok in tokens) for t in types)


def _schema_ok(plan: Dict[str, Any]) -> Tuple[bool, str]:
    try:
        from app.models.llm_plan import validate_llm_plan_dict, LlmPlanValidationError
    except Exception as e:  # 依赖不可用时退化为浅校验，避免评估器本身崩溃
        mode = plan.get("mode")
        if mode not in ("build", "patch"):
            return False, f"mode invalid ({mode}); schema module unavailable: {e}"
        if not isinstance(plan.get("anchor"), dict):
            return False, "anchor missing; schema module unavailable"
        return True, "shallow-checked (schema module unavailable)"
    try:
        validate_llm_plan_dict(plan)
        return True, ""
    except LlmPlanValidationError as e:  # type: ignore[misc]
        return False, "; ".join(getattr(e, "errors", []) or [str(e)])
    except Exception as e:
        return False, str(e)


def evaluate_plan(plan: Dict[str, Any], label: str = "plan") -> EvalResult:
    res = EvalResult(label=label)
    add = res.checks.append

    schema_ok, schema_detail = _schema_ok(plan)
    add(Check("schema_valid", schema_ok, hard=True, detail=schema_detail))
    if not schema_ok:
        return res  # schema 都不过，后续断言无意义

    mode = plan.get("mode")
    add(Check("has_anchor", isinstance(plan.get("anchor"), dict), hard=True))

    comps = _components(plan)
    slots = _slots(plan)
    types = _component_types(plan)

    if mode == "patch":
        has_block_patch = isinstance(plan.get("patch"), dict) and bool(
            (plan.get("patch") or {}).get("blocks")
        )
        add(Check(
            "patch_has_ops",
            bool(comps) or has_block_patch,
            hard=True,
            detail="patch 需 components[] 或 patch.blocks[]",
        ))
        return res

    # ---- build 模式的可建造性 / 丰富度启发式 ----
    add(Check("has_geometry", bool(comps) or bool(slots), hard=True,
              detail="build 需 components[] 或 layout.slots[]"))

    # 尺寸合理：宽 > 0，且不超过上限。
    zero_or_oversize: List[str] = []
    for i, c in enumerate(comps):
        dims = c.get("dimensions") if isinstance(c.get("dimensions"), dict) else {}
        w = dims.get("width", 0) or 0
        h = dims.get("height", 0) or 0
        d = dims.get("depth", 0) or 0
        if w <= 0:
            zero_or_oversize.append(f"#{i} width={w}")
        if max(w, h, d) > MAX_DIMENSION:
            zero_or_oversize.append(f"#{i} oversize {w}x{h}x{d}")
    add(Check("dimensions_sane", not zero_or_oversize, hard=True,
              detail=", ".join(zero_or_oversize)))

    # SOFT：语义丰富度 —— 有入口 / 有窗 / 有屋顶。
    add(Check("has_entrance", _any_token(types, _ENTRANCE_TOKENS), hard=False,
              detail=f"types={types}"))
    add(Check("has_windows", _any_token(types, _WINDOW_TOKENS), hard=False,
              detail=f"types={types}"))
    add(Check("has_roof", _any_token(types, _ROOF_TOKENS), hard=False,
              detail=f"types={types}"))
    # SOFT：组件数量（过少往往是"空盒子"）。
    add(Check("component_richness", len(comps) >= 2 or len(slots) >= 2, hard=False,
              detail=f"components={len(comps)} slots={len(slots)}"))

    # SOFT：合理性 —— "太矮"（与 Java ComponentPlanCompiler.minHeightForType 对齐）。
    too_short = _too_short_masses(comps)
    add(Check("not_too_short", not too_short, hard=False,
              detail=", ".join(too_short)))

    # SOFT：立面参数取值合法（与 Java 生成器可识别的枚举对齐；非法值会被静默忽略）。
    bad_facade = _invalid_facade_params(comps)
    add(Check("facade_params_valid", not bad_facade, hard=False,
              detail=", ".join(bad_facade)))

    return res


# 与 Java ComponentFacadeStyler / FacadePatternDsl / ComponentPlanCompiler 的可识别取值对齐。
_ALLOWED_FACADE_PROFILE = {"none", "base_plinth", "vertical_pilasters", "pilasters", "mullion_grid", "mullion", "module_grid"}
_ALLOWED_WALL_PATTERN = {"none", "uniform", "gradient", "striped", "random"}
_ALLOWED_FACADE_CUTOUT = {"none", "solid", "lattice", "grille", "perforated", "diagrid", "diagonal", "diamond", "checker", "rose", "circle", "oculus", "arches", "arch"}
_ALLOWED_DETAIL_LEVEL = {"low", "medium", "high"}


def _invalid_facade_params(comps: List[Dict[str, Any]]) -> List[str]:
    out: List[str] = []

    def _check(params: Dict[str, Any], idx: int, keys: Tuple[str, ...], allowed: set, label: str) -> None:
        for k in keys:
            v = params.get(k)
            if isinstance(v, str) and v.strip() and v.strip().lower() not in allowed:
                out.append(f"#{idx} {label}={v}")
                return

    for i, c in enumerate(comps):
        params = c.get("params") if isinstance(c.get("params"), dict) else {}
        if not params:
            continue
        _check(params, i, ("facade_profile", "facadeProfile"), _ALLOWED_FACADE_PROFILE, "facade_profile")
        _check(params, i, ("wall_pattern", "wallPattern"), _ALLOWED_WALL_PATTERN, "wall_pattern")
        _check(params, i, ("facade_cutout", "cutout_pattern", "perforation"), _ALLOWED_FACADE_CUTOUT, "facade_cutout")
        _check(params, i, ("detail_level", "detailLevel", "quality"), _ALLOWED_DETAIL_LEVEL, "detail_level")
    return out


# 与 Java ComponentPlanCompiler.minHeightForType 对齐：主体/塔的合理最小层高。
_MIN_HEIGHT_BY_TYPE = {
    "MASS_MAIN": 4, "MASS_SECONDARY": 4, "MASS_WING": 4,
    "HOUSE": 4, "BUILDING": 4, "TOWER": 6,
}


def _too_short_masses(comps: List[Dict[str, Any]]) -> List[str]:
    out: List[str] = []
    for i, c in enumerate(comps):
        t = c.get("component_type")
        t = t.strip().upper() if isinstance(t, str) else ""
        min_h = _MIN_HEIGHT_BY_TYPE.get(t, 0)
        if min_h <= 0:
            continue
        dims = c.get("dimensions") if isinstance(c.get("dimensions"), dict) else {}
        h = dims.get("height", 0) or 0
        if 0 < h < min_h:
            out.append(f"#{i} {t} height={h}<{min_h}")
    return out


def _print_result(res: EvalResult) -> None:
    status = "PASS" if res.ok else "FAIL"
    print(f"\n=== {res.label}: {status} ===")
    for c in res.checks:
        mark = "✓" if c.passed else ("✗" if c.hard else "!")
        tier = "HARD" if c.hard else "soft"
        line = f"  [{mark}] {c.name} ({tier})"
        if c.detail and not c.passed:
            line += f" — {c.detail}"
        print(line)


def _load_plan_file(path: Path) -> Optional[Dict[str, Any]]:
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except Exception as e:
        print(f"  ! 跳过 {path.name}: 无法解析 JSON ({e})")
        return None
    if not isinstance(data, dict):
        print(f"  ! 跳过 {path.name}: 顶层不是对象")
        return None
    # 兼容带 kind 包裹（Phase 2 后 /build 会注入 kind）——直接用平铺 plan 即可。
    return data


def _summarize(results: List[EvalResult], gate: bool) -> int:
    """打印汇总并返回退出码。gate=True 时 SOFT 告警也计入失败（严格回归门）。"""
    hard = sum(len(r.hard_failures) for r in results)
    soft = sum(len(r.soft_failures) for r in results)
    passed = sum(1 for r in results if r.ok)
    print(f"\n---- 汇总：{passed}/{len(results)} 通过 HARD 断言；"
          f"HARD 失败 {hard} 项，SOFT 告警 {soft} 项"
          + ("（--gate：SOFT 也计入失败）" if gate else "") + " ----")
    if hard > 0:
        return 1
    if gate and soft > 0:
        return 1
    return 0


def run_offline(paths: List[Path], gate: bool = False) -> int:
    results: List[EvalResult] = []
    for p in paths:
        plan = _load_plan_file(p)
        if plan is None:
            continue
        results.append(evaluate_plan(plan, label=p.name))

    if not results:
        print("没有可评估的 plan 文件。")
        return 2

    for r in results:
        _print_result(r)

    return _summarize(results, gate)


def run_live(gate: bool = False) -> int:
    """真实跑一遍 golden prompt。仅冒烟用：Python 侧缺少 Java 组装的系统 prompt，
    生成质量不代表游戏内真实效果。"""
    try:
        from app.models.request import BuildRequest, PlayerInfo, Vec3i, WorldContext
        from app.services.ai_planner import generate_llm_plan
    except Exception as e:
        print(f"无法导入后端模块，跳过 live 评估：{e}")
        return 2

    results: List[EvalResult] = []
    for prompt in GOLDEN_PROMPTS:
        req = BuildRequest(
            player=PlayerInfo(name="eval", pos=Vec3i(x=0, y=64, z=0), facing="SOUTH"),
            world=WorldContext(dimension="minecraft:overworld", biome="minecraft:plains"),
            requestText=prompt,
            userMessage=prompt,
            promptMode="BUILD",
            outputFormat="llmplan",
        )
        try:
            plan = generate_llm_plan(req)
            results.append(evaluate_plan(plan, label=prompt))
        except Exception as e:
            r = EvalResult(label=prompt)
            r.checks.append(Check("generation", False, hard=True, detail=str(e)))
            results.append(r)

    for r in results:
        _print_result(r)
    return _summarize(results, gate)


def main(argv: Optional[List[str]] = None) -> int:
    parser = argparse.ArgumentParser(description="FormaCraft LlmPlan 生成质量评估")
    parser.add_argument("--plans", type=str, help="包含 *.json plan 文件的目录")
    parser.add_argument("--plan", type=str, help="单个 plan JSON 文件")
    parser.add_argument("--live", action="store_true", help="真实跑 golden prompt（需配置 LLM）")
    parser.add_argument("--gate", action="store_true",
                        help="回归门模式：SOFT 告警也计入失败（退出码非 0）")
    args = parser.parse_args(argv)

    if args.live:
        return run_live(gate=args.gate)

    paths: List[Path] = []
    if args.plan:
        paths.append(Path(args.plan))
    if args.plans:
        d = Path(args.plans)
        if d.is_dir():
            paths.extend(sorted(d.glob("*.json")))
        else:
            print(f"目录不存在：{d}")
            return 2

    if not paths:
        print(__doc__)
        print("\nGolden prompts（回归基准）：")
        for i, p in enumerate(GOLDEN_PROMPTS, 1):
            print(f"  {i}. {p}")
        return 0

    return run_offline(paths, gate=args.gate)


if __name__ == "__main__":
    raise SystemExit(main())
