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
    "在锚点位置生成现代风格的椭圆形体育场建筑",
    "盖一座中世纪石头城堡，有塔楼和城墙",
]

# gate 模式下按 scenario 标签提升为 HARD 的 SOFT 检查（与 eval/ci_gate.py 对齐）
_SCENARIO_HARD_BY_TAG: Dict[str, frozenset] = {
    "high_frequency": frozenset({
        "has_proportion_hints",
        "has_entrance",
        "has_windows",
        "has_roof",
        "has_enclosure_mass",
        "has_entrance_component",
        "component_richness",
        "facade_params_valid",
    }),
    "stadium": frozenset({
        "elliptical_shape_params",
        "stadium_seating_expressed",
        "genome_shape_consistency",
    }),
    "castle": frozenset({
        "has_proportion_hints",
        "has_enclosure_mass",
        "castle_has_defensive_shell",
    }),
    "siheyuan": frozenset({
        "has_proportion_hints",
        "has_courtyard_component",
        "siheyuan_enclosure_layout",
        "siheyuan_courtyard_skeleton",
        "has_entrance",
        "has_enclosure_mass",
        "component_richness",
    }),
    "tower": frozenset({
        "has_proportion_hints",
        "tower_vertical_skeleton",
        "tower_square_footprint",
        "tower_five_floors",
        "tower_top_platform",
        "has_entrance",
        "has_windows",
        "component_richness",
    }),
    "temple": frozenset({
        "has_proportion_hints",
        "temple_landmark_or_radial_mass",
        "temple_radial_layout",
        "temple_circular_form",
        "temple_tier_expressed",
    }),
    "patch": frozenset({
        "patch_has_ops",
        "patch_targets_roof",
        "patch_roof_color_red",
        "patch_minimal_scope",
        "patch_no_full_rebuild",
    }),
    "primitive": frozenset({
        "facade_params_valid",
    }),
    "assembly": frozenset({
        "assembly_primary_component",
        "assembly_graph_dsl_valid",
        "no_nested_assembly_in_mass",
        "assembly_no_conflicting_mass_stack",
    }),
    "capability_gap": frozenset({
        "capability_gap_status",
        "capability_gap_object",
        "capability_gap_code",
        "capability_gap_message",
    }),
    "typology": frozenset({
        "has_proportion_hints",
        "typology_structure_route",
        "typology_proportion_hints",
        "typology_skeleton_match",
        "no_migrated_module",
    }),
}


def promote_scenario_hard_checks(result: EvalResult, scenario: Dict[str, Any]) -> None:
    """gate 模式：按 scenario 标签 / proportion_card 将关键 SOFT 升为 HARD。"""
    names: set[str] = set()
    tags = scenario.get("tags")
    tag_set: set[str] = set()
    if isinstance(tags, list):
        tag_set = {str(t) for t in tags if t}
        for tag in tag_set:
            if tag == "high_frequency" and (
                "castle" in tag_set or "siheyuan" in tag_set or "tower" in tag_set
                or "temple" in tag_set or "patch" in tag_set
            ):
                continue
            names.update(_SCENARIO_HARD_BY_TAG.get(tag, ()))
        if "typology" in tag_set:
            names.update(_SCENARIO_HARD_BY_TAG.get("typology", ()))
    card = scenario.get("proportion_card")
    if card == "cottage_refined":
        names.update(_SCENARIO_HARD_BY_TAG.get("high_frequency", ()))
    elif card == "castle_wall":
        names.update(_SCENARIO_HARD_BY_TAG.get("castle", ()))
    elif card == "siheyuan_courtyard":
        names.update(_SCENARIO_HARD_BY_TAG.get("siheyuan", ()))
    elif card == "square_tower_five_story":
        names.update(_SCENARIO_HARD_BY_TAG.get("tower", ()))
    elif card == "temple_of_heaven":
        names.update(_SCENARIO_HARD_BY_TAG.get("temple", ()))
    elif card in (
        "famen_pagoda", "giant_wild_goose_pagoda", "foguang_temple_hall",
        "stadium_bowl", "suspension_bridge", "gothic_cathedral_hall",
        "courtyard_compound", "radial_fortress", "setback_tower",
    ):
        names.update(_SCENARIO_HARD_BY_TAG.get("typology", ()))
    hard_names = scenario.get("hard_checks")
    if isinstance(hard_names, list):
        names.update(str(n) for n in hard_names if n)
    for check in result.checks:
        if check.name in names:
            check.hard = True


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


def _has_plan_program(plan: Dict[str, Any]) -> bool:
    """是否走 PlanProgram/PlanSkeleton 路径（C1 主干；几何来自 2D 骨架→挤出）。
    兼容 snake_case 与 camelCase：后端模型是 snake_case，但 LLM/Java 也可能吐 camelCase
    并落到 extra 字段里。"""
    for key in ("plan_skeleton", "planSkeleton", "plan_program", "planProgram"):
        v = plan.get(key)
        if isinstance(v, dict) and v:
            return True
    return False


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


def _param_string(params: Dict[str, Any], *keys: str) -> Optional[str]:
    for k in keys:
        v = params.get(k)
        if isinstance(v, str) and v.strip():
            return v.strip().lower()
    return None


def _param_float(params: Dict[str, Any], *keys: str, default: float = 0.0) -> float:
    for k in keys:
        v = params.get(k)
        if isinstance(v, (int, float)):
            return float(v)
    return default


def _has_landmark_feature(c: Dict[str, Any], module_id: str) -> bool:
    features = c.get("features")
    if not isinstance(features, list):
        return False
    needle = module_id.lower()
    for f in features:
        if not isinstance(f, str):
            continue
        lower = f.lower()
        if lower.startswith("landmark:") and needle in lower:
            return True
        if lower.startswith("module:") and needle in lower:
            return True
    return False


def _has_typology_feature(c: Dict[str, Any], typology_id: str) -> bool:
    features = c.get("features")
    if isinstance(features, list):
        needle = f"typology:{typology_id}".lower()
        for f in features:
            if isinstance(f, str) and f.lower().strip() == needle:
                return True
    params = c.get("params") if isinstance(c.get("params"), dict) else {}
    for key in ("typology_id", "structural_typology", "typologyId", "structuralTypologyId"):
        if str(params.get(key) or "").strip().lower() == typology_id.lower():
            return True
    return False


def _has_stadium_bowl_route(comps: List[Dict[str, Any]]) -> bool:
    for c in comps:
        ctype = str(c.get("component_type") or "").upper()
        if ctype == "MODULE" and _has_landmark_feature(c, "birds_nest_stadium"):
            return True
        if ctype == "STRUCTURE" and _has_typology_feature(c, "stadium_bowl"):
            return True
    return False


def _genome_layout(plan: Dict[str, Any]) -> Optional[str]:
    genome = plan.get("genome")
    if not isinstance(genome, dict):
        return None
    topology = genome.get("topology")
    if not isinstance(topology, dict):
        return None
    layout = topology.get("layout")
    return layout.strip().lower() if isinstance(layout, str) and layout.strip() else None


def _prompt_contains(prompt: Optional[str], tokens: Tuple[str, ...]) -> bool:
    if not prompt:
        return False
    lower = prompt.lower()
    return any(t.lower() in lower for t in tokens)


def evaluate_intent(plan: Dict[str, Any], prompt: Optional[str]) -> List[Check]:
    """SOFT：按用户 prompt 意图检查 plan 是否「像描述所说」——Week 1 质量闭环核心。"""
    if not prompt or plan.get("mode") == "patch":
        return []

    checks: List[Check] = []
    comps = _components(plan)
    types = _component_types(plan)

    # ---- 体育场 / 椭圆场景 ----
    is_stadium = _prompt_contains(
        prompt,
        ("体育场", "体育馆", "stadium", "arena", "球场"),
    )
    is_elliptical = _prompt_contains(
        prompt,
        ("椭圆", "elliptical", "oval", "椭圆形"),
    )

    if is_stadium or is_elliptical:
        has_stadium_route = _has_stadium_bowl_route(comps)
        checks.append(Check(
            "stadium_landmark_or_curved_mass",
            has_stadium_route or _stadium_has_curved_mass(comps),
            hard=False,
            detail="体育场/椭圆意图：应用 STRUCTURE+typology:stadium_bowl，"
                   "或 MASS params.shape=circle|rounded_rect + 非方 footprint",
        ))

        layout = plan.get("layout") if isinstance(plan.get("layout"), dict) else {}
        sk = layout.get("skeleton_type") if isinstance(layout.get("skeleton_type"), str) else ""
        g_layout = _genome_layout(plan)
        radial_ok = (
            sk.upper() in ("RADIAL_RING", "COMPOUND")
            or g_layout in ("circular", "radial", "elliptical", "oval")
        )
        checks.append(Check(
            "stadium_radial_layout",
            radial_ok,
            hard=False,
            detail=f"skeleton_type={sk!r} genome.layout={g_layout!r}",
        ))

        has_field = (
            "PAVING" in types
            or _any_mass_void(comps, min_void=0.15)
            or has_stadium_route
        )
        checks.append(Check(
            "stadium_inner_field",
            has_field,
            hard=False,
            detail="应有内场（PAVING / void_ratio / landmark 模块），而非实心盒子",
        ))

        if is_elliptical:
            checks.append(Check(
                "elliptical_shape_params",
                has_stadium_route or _elliptical_shape_ok(comps),
                hard=False,
                detail="椭圆意图：typology stadium_bowl 或 MASS shape=circle/rounded_rect 且 width≠depth",
            ))

        checks.append(Check(
            "stadium_seating_expressed",
            has_stadium_route
            or _has_tiered_masses(comps)
            or sum(1 for t in types if t.startswith("MASS_")) >= 2,
            hard=False,
            detail="看台/分层应通过 typology stadium_bowl、mparams.masses[] 或多 MASS 体块表达，"
                   "单靠 features 文本无效",
        ))

        checks.append(Check(
            "stadium_high_fidelity_ellipse",
            (not is_elliptical) or has_stadium_route or _has_plan_program(plan) or _has_tiered_masses(comps),
            hard=False,
            detail="真椭圆/碗状需 stadium_bowl typology 或 plan_program；"
                   "MassMain 的 rounded_rect 仅为圆角矩形近似",
        ))

        checks.append(Check(
            "stadium_paving_or_landmark_field",
            has_stadium_route or "PAVING" in types,
            hard=False,
            detail="内场宜显式 PAVING 组件或 typology stadium_bowl 路由，void_ratio alone 不足以形成场地感",
        ))

        checks.append(Check(
            "genome_shape_consistency",
            _genome_shape_consistent(plan, comps),
            hard=False,
            detail="genome 写 circular/curved 时 params.shape 应对齐（非裸 rectangle）",
        ))

    # ---- ASSEMBLY / 螺旋 / 自由几何 ----
    is_assembly_intent = _prompt_contains(
        prompt,
        (
            "assembly", "assemble", "metaassembly",
            "螺旋", "螺旋塔", "瞭望塔", "旋转", "扭转",
            "spiral", "helix", "twist", "watchtower", "lookout",
            "自由几何", "自由形体", "非矩形", "非矩形体量", "异形",
            "freeform", "free-form", "non-rectangular",
            "不要地标", "no landmark",
        ),
    )
    if is_assembly_intent:
        has_assembly = any(
            str(c.get("component_type") or "").upper() == "ASSEMBLY"
            for c in comps
        )
        if _is_capability_gap_plan(plan):
            checks.append(Check(
                "assembly_intent_honest_gap",
                True,
                hard=False,
                detail="ASSEMBLY 意图以 capability_gap 显式失败（优于空 plan）",
            ))
        else:
            checks.append(Check(
                "assembly_primary_component",
                has_assembly,
                hard=False,
                detail="ASSEMBLY/螺旋/自由几何意图：主体应使用 component_type=ASSEMBLY",
            ))

        nested = _nested_assembly_in_mass(comps)
        checks.append(Check(
            "no_nested_assembly_in_mass",
            not nested,
            hard=False,
            detail=", ".join(nested) or "params.assembly 不应嵌在 MASS_* 内",
        ))

        if _prompt_contains(prompt, ("螺旋", "spiral", "helix", "twist", "扭转", "旋转")):
            if not _is_capability_gap_plan(plan):
                checks.append(Check(
                    "spiral_twist_expressed",
                    has_assembly and _assembly_has_twist(comps),
                    hard=False,
                    detail="螺旋意图：ASSEMBLY graph/ops 中 SHELL_BOX 等应含 twistTurns",
                ))

        checks.append(Check(
            "assembly_no_conflicting_mass_stack",
            not _assembly_slot_has_mass_stack(comps),
            hard=False,
            detail="ASSEMBLY slot 不应叠加 MASS_SECONDARY + FACADE_WINDOWS + ROOF",
        ))

    is_bridge = _prompt_contains(
        prompt,
        ("桥", "bridge", "悬索", "suspension", "cable-stayed", "斜拉"),
    )
    if is_bridge and is_assembly_intent:
        has_bridge_preset = any(
            str(c.get("component_type") or "").upper() == "ASSEMBLY"
            and isinstance((c.get("params") or {}).get("assembly"), dict)
            and str(((c.get("params") or {}).get("assembly") or {}).get("preset") or "").strip()
            in ("suspension_bridge_simple",)
            for c in comps
        )
        checks.append(Check(
            "bridge_assembly_preset",
            has_bridge_preset,
            hard=False,
            detail="桥梁/悬索意图：优先 preset=suspension_bridge_simple",
        ))

    # ---- 四合院 / 围合院落 ----
    is_siheyuan = _prompt_contains(
        prompt,
        ("四合院", "siheyuan", "带院子", "合院", "courtyard house", "chinese courtyard"),
    )
    if is_siheyuan:
        has_courtyard = any(
            t in ("COURTYARD", "COURTYARD_SPACE") for t in types
        )
        checks.append(Check(
            "has_courtyard_component",
            has_courtyard,
            hard=False,
            detail="四合院意图：需显式 COURTYARD 或 COURTYARD_SPACE 组件",
        ))

        layout = plan.get("layout") if isinstance(plan.get("layout"), dict) else {}
        sk = layout.get("skeleton_type") if isinstance(layout.get("skeleton_type"), str) else ""
        checks.append(Check(
            "siheyuan_courtyard_skeleton",
            sk.upper() in ("COURTYARD", "COMPOUND"),
            hard=False,
            detail=f"skeleton_type={sk!r}（期望 COURTYARD 或 COMPOUND）",
        ))

        wing_count = sum(
            1 for t in types
            if t.startswith("MASS_") or t in ("MASS_MAIN", "HOUSE", "BUILDING")
        )
        checks.append(Check(
            "siheyuan_enclosure_layout",
            has_courtyard and wing_count >= 2,
            hard=False,
            detail=f"courtyard={has_courtyard} enclosing_masses={wing_count}（期望中庭 + ≥2 厢房/正房）",
        ))

    # ---- 五层方塔 / 竖向塔楼 ----
    is_tower = _prompt_contains(
        prompt,
        ("方塔", "五层", "5层", "5 层", "square tower", "five-story tower", "tower platform", "顶部平台"),
    )
    if is_tower:
        layout = plan.get("layout") if isinstance(plan.get("layout"), dict) else {}
        sk = layout.get("skeleton_type") if isinstance(layout.get("skeleton_type"), str) else ""
        checks.append(Check(
            "tower_vertical_skeleton",
            sk.upper() in ("VERTICAL_STACK", "VERTICAL_TAPER", "COMPOUND"),
            hard=False,
            detail=f"skeleton_type={sk!r}（期望 VERTICAL_STACK / VERTICAL_TAPER）",
        ))

        square_ok = False
        floor_ok = False
        hints = plan.get("proportion_hints") if isinstance(plan.get("proportion_hints"), dict) else {}
        for c in comps:
            t = str(c.get("component_type") or "").upper()
            if t not in ("MASS_MAIN", "TOWER", "HOUSE", "BUILDING") and not t.startswith("MASS_"):
                continue
            dims = c.get("dimensions") if isinstance(c.get("dimensions"), dict) else {}
            w = int(dims.get("width") or 0)
            d = int(dims.get("depth") or 0)
            h = int(dims.get("height") or 0)
            params = c.get("params") if isinstance(c.get("params"), dict) else {}
            shape = _param_string(params, "shape", "footprint_shape", "footprintShape")
            if w > 0 and w == d and shape in ("rectangle", "rect", "square", None):
                square_ok = True
            fh = _param_float(params, "floor_height", "floorHeight")
            if fh <= 0:
                fh = float(hints.get("floor_height") or 3)
            fc = hints.get("floor_count")
            if isinstance(fc, (int, float)) and fc >= 5:
                floor_ok = True
            elif fh > 0 and h >= int(fh * 5 - 1):
                floor_ok = True
            feats = c.get("features") if isinstance(c.get("features"), list) else []
            if any(isinstance(f, str) and "five" in f.lower() for f in feats):
                floor_ok = True
        checks.append(Check(
            "tower_square_footprint",
            square_ok,
            hard=False,
            detail="方塔意图：主塔体 width=depth 且 shape=rectangle/square",
        ))
        checks.append(Check(
            "tower_five_floors",
            floor_ok,
            hard=False,
            detail="五层意图：height≥5×floor_height 或 proportion_hints.floor_count≥5",
        ))

        has_platform = any(
            t in ("TERRACE", "PLAZA", "TERRACE_PLAZA", "PLAZA_CORE", "BALCONY")
            for t in types
        )
        checks.append(Check(
            "tower_top_platform",
            has_platform,
            hard=False,
            detail="顶部平台：需 TERRACE / PLAZA 等顶层平台组件",
        ))

    # ---- 天坛 / 径向祭祀建筑 ----
    is_temple = _prompt_contains(
        prompt,
        ("天坛", "祈年殿", "temple of heaven", "qiniandian", "祭天", "heaven temple"),
    )
    if is_temple:
        has_landmark = any(
            c.get("component_type", "").upper() == "MODULE"
            and _has_landmark_feature(c, "temple_of_heaven")
            for c in comps
        )
        has_typology = any(
            c.get("component_type", "").upper() == "STRUCTURE"
            and any(
                isinstance(f, str) and "radial_terrace_hall" in f.lower()
                for f in (c.get("features") or [])
            )
            for c in comps
        )
        params_landmark = False
        for c in comps:
            if c.get("component_type", "").upper() != "MODULE":
                continue
            params = c.get("params") if isinstance(c.get("params"), dict) else {}
            mid = params.get("module_id", params.get("moduleId"))
            if isinstance(mid, str) and "temple_of_heaven" in mid.lower():
                params_landmark = True
        has_landmark = has_landmark or params_landmark or has_typology

        checks.append(Check(
            "temple_landmark_or_radial_mass",
            has_landmark or _stadium_has_curved_mass(comps),
            hard=False,
            detail="天坛意图：应用 STRUCTURE+typology:radial_terrace_hall 或 MODULE+landmark:temple_of_heaven，"
                   "或 MASS shape=circle + 径向布局",
        ))

        layout = plan.get("layout") if isinstance(plan.get("layout"), dict) else {}
        sk = layout.get("skeleton_type") if isinstance(layout.get("skeleton_type"), str) else ""
        g_layout = _genome_layout(plan)
        gc = plan.get("global_constraints") if isinstance(plan.get("global_constraints"), dict) else {}
        sym = gc.get("symmetry") if isinstance(gc.get("symmetry"), str) else ""
        radial_ok = (
            sk.upper() in ("RADIAL_RING", "RADIAL_SPOKE", "COMPOUND")
            or g_layout in ("circular", "radial")
            or sym.upper() == "RADIAL"
        )
        checks.append(Check(
            "temple_radial_layout",
            radial_ok,
            hard=False,
            detail=f"skeleton_type={sk!r} genome.layout={g_layout!r} symmetry={sym!r}",
        ))

        circular_ok = has_landmark
        if not circular_ok:
            for c in comps:
                if c.get("component_type", "").upper() not in (
                    "MASS_MAIN", "MASS_SECONDARY", "MASS_WING", "DOME", "ROOF"
                ):
                    continue
                params = c.get("params") if isinstance(c.get("params"), dict) else {}
                shape = _param_string(params, "shape", "footprint_shape", "footprintShape")
                if shape in ("circle", "circular", "round"):
                    circular_ok = True
                    break
        checks.append(Check(
            "temple_circular_form",
            circular_ok,
            hard=False,
            detail="圆形殿宇：landmark 模块或 MASS/DOME shape=circle",
        ))

        hints = plan.get("proportion_hints") if isinstance(plan.get("proportion_hints"), dict) else {}
        tier_ok = has_landmark
        tc = hints.get("tier_count")
        if isinstance(tc, (int, float)) and tc >= 2:
            tier_ok = True
        if sum(1 for t in types if t in ("ROOF", "DOME", "SPIRE")) >= 2:
            tier_ok = True
        if _has_tiered_masses(comps):
            tier_ok = True
        for c in comps:
            params = c.get("params") if isinstance(c.get("params"), dict) else {}
            tiers = params.get("tiers", params.get("tier_count", params.get("tierCount")))
            if isinstance(tiers, (int, float)) and tiers >= 2:
                tier_ok = True
        checks.append(Check(
            "temple_tier_expressed",
            tier_ok,
            hard=False,
            detail="三重檐/台基：landmark、proportion_hints.tier_count≥2、多层 ROOF 或 params.tiers",
        ))

    return checks


def evaluate_typology_fixture_alignment(plan: Dict[str, Any], prompt: Optional[str]) -> List[Check]:
    """Golden fixtures with _meta.structural_typology must stay typology-first."""
    meta = plan.get("_meta") if isinstance(plan.get("_meta"), dict) else {}
    typology_id = str(meta.get("structural_typology") or "").strip()
    if not typology_id:
        return []

    landmark = str(meta.get("reference_landmark") or "").strip()
    expected_skeleton = str(meta.get("skeleton_type") or "").strip()
    comps = _components(plan)
    checks: List[Check] = []

    has_route = any(
        str(c.get("component_type") or "").upper() == "STRUCTURE"
        and _has_typology_feature(c, typology_id)
        for c in comps
    )
    checks.append(Check(
        "typology_structure_route",
        has_route,
        hard=False,
        detail=f"expected STRUCTURE + typology:{typology_id}",
    ))

    has_migrated_module = any(
        str(c.get("component_type") or "").upper() == "MODULE"
        and (
            (landmark and _has_landmark_feature(c, landmark))
            or _has_landmark_feature(c, typology_id)
        )
        for c in comps
    )
    checks.append(Check(
        "no_migrated_module",
        not has_migrated_module,
        hard=False,
        detail="typology golden must not emit MODULE for migrated landmark",
    ))

    hints = plan.get("proportion_hints") if isinstance(plan.get("proportion_hints"), dict) else {}
    hints_typ = str(hints.get("typology") or "").strip()
    checks.append(Check(
        "typology_proportion_hints",
        hints_typ == typology_id,
        hard=False,
        detail=f"proportion_hints.typology should be {typology_id}",
    ))
    checks.append(Check(
        "has_proportion_hints",
        bool(hints),
        hard=False,
        detail="typology fixture expects proportion_hints",
    ))

    layout = plan.get("layout") if isinstance(plan.get("layout"), dict) else {}
    sk = str(layout.get("skeleton_type") or "").strip()
    if expected_skeleton:
        checks.append(Check(
            "typology_skeleton_match",
            sk == expected_skeleton,
            hard=False,
            detail=f"layout.skeleton_type should be {expected_skeleton}",
        ))

    return checks


def _stadium_has_curved_mass(comps: List[Dict[str, Any]]) -> bool:
    for c in comps:
        if c.get("component_type", "").upper() not in ("MASS_MAIN", "MASS_SECONDARY", "MASS_WING", "HOUSE", "BUILDING"):
            continue
        params = c.get("params") if isinstance(c.get("params"), dict) else {}
        shape = _param_string(params, "shape", "footprint_shape", "footprintShape")
        if shape in ("circle", "circular", "round", "rounded_rect", "rounded", "roundrect", "round_rect"):
            return True
    return False


def _elliptical_shape_ok(comps: List[Dict[str, Any]]) -> bool:
    for c in comps:
        if c.get("component_type", "").upper() != "MASS_MAIN":
            continue
        params = c.get("params") if isinstance(c.get("params"), dict) else {}
        shape = _param_string(params, "shape", "footprint_shape", "footprintShape")
        dims = c.get("dimensions") if isinstance(c.get("dimensions"), dict) else {}
        w = dims.get("width", 0) or 0
        d = dims.get("depth", 0) or 0
        if shape in ("circle", "circular", "round"):
            return True
        if shape in ("rounded_rect", "rounded", "roundrect", "round_rect") and w > 0 and d > 0 and w != d:
            return True
    return False


def _any_mass_void(comps: List[Dict[str, Any]], min_void: float) -> bool:
    for c in comps:
        params = c.get("params") if isinstance(c.get("params"), dict) else {}
        if _param_float(params, "void_ratio", "voidRatio") >= min_void:
            return True
        plan_type = _param_string(params, "plan_type", "planType")
        if plan_type in ("courtyard", "ring", "donut"):
            return True
    return False


def _has_tiered_masses(comps: List[Dict[str, Any]]) -> bool:
    for c in comps:
        params = c.get("params") if isinstance(c.get("params"), dict) else {}
        masses = params.get("masses")
        if isinstance(masses, list) and len(masses) >= 2:
            return True
    return False


def _genome_shape_consistent(plan: Dict[str, Any], comps: List[Dict[str, Any]]) -> bool:
    genome = plan.get("genome")
    if not isinstance(genome, dict):
        return True
    form = genome.get("form") if isinstance(genome.get("form"), dict) else {}
    topology = genome.get("topology") if isinstance(genome.get("topology"), dict) else {}
    curvature = form.get("curvature")
    layout = topology.get("layout")
    wants_curved = (
        (isinstance(curvature, str) and curvature.lower() in ("curved", "mixed"))
        or (isinstance(layout, str) and layout.lower() in ("circular", "radial", "elliptical", "oval"))
    )
    if not wants_curved:
        return True
    for c in comps:
        if c.get("component_type", "").upper() != "MASS_MAIN":
            continue
        params = c.get("params") if isinstance(c.get("params"), dict) else {}
        shape = _param_string(params, "shape", "footprint_shape", "footprintShape")
        if shape in ("rectangle", "rect", "square", None):
            return False
        if shape in ("circle", "circular", "round", "rounded_rect", "rounded", "roundrect", "round_rect"):
            return True
    return bool(comps)


def _is_capability_gap_plan(plan: Dict[str, Any]) -> bool:
    return str(plan.get("plan_status") or "").strip().lower() == "capability_gap"


def _evaluate_capability_gap_plan(plan: Dict[str, Any], res: EvalResult) -> EvalResult:
    add = res.checks.append
    gap = plan.get("capability_gap") if isinstance(plan.get("capability_gap"), dict) else {}
    message = str(gap.get("message") or plan.get("error") or "").strip()
    suggestions = gap.get("suggestions")
    add(Check("capability_gap_status", _is_capability_gap_plan(plan), hard=True))
    add(Check("capability_gap_object", isinstance(gap, dict) and bool(gap), hard=True))
    add(Check("capability_gap_code", bool(str(gap.get("code") or "").strip()), hard=True))
    add(Check("capability_gap_message", bool(message), hard=True))
    add(Check(
        "capability_gap_suggestions",
        isinstance(suggestions, list) and len(suggestions) > 0,
        hard=False,
        detail=f"suggestions={len(suggestions) if isinstance(suggestions, list) else 0}",
    ))
    add(Check("has_anchor", isinstance(plan.get("anchor"), dict), hard=True))
    return res


def evaluate_plan(plan: Dict[str, Any], label: str = "plan", prompt: Optional[str] = None) -> EvalResult:
    res = EvalResult(label=label)
    add = res.checks.append

    schema_ok, schema_detail = _schema_ok(plan)
    add(Check("schema_valid", schema_ok, hard=True, detail=schema_detail))
    if not schema_ok:
        return res  # schema 都不过，后续断言无意义

    if _is_capability_gap_plan(plan):
        return _evaluate_capability_gap_plan(plan, res)

    mode = plan.get("mode")
    add(Check("has_anchor", isinstance(plan.get("anchor"), dict), hard=True))

    comps = _components(plan)
    slots = _slots(plan)
    types = _component_types(plan)

    if mode == "patch":
        try:
            from eval.patch_eval import evaluate_patch_intent
            for name, ok, detail in evaluate_patch_intent(plan, prompt):
                add(Check(name, ok, hard=False, detail=detail))
        except Exception:
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
    has_program = _has_plan_program(plan)
    add(Check("has_geometry", bool(comps) or bool(slots) or has_program, hard=True,
              detail="build 需 components[] / layout.slots[] / plan_skeleton|plan_program"))

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
    # 仅对 components/slots 几何有意义；plan_skeleton/plan_program 走 2D→挤出，
    # 组件级 token 检查不适用，跳过以免产生噪声告警。
    if comps or slots:
        add(Check("has_entrance", _any_token(types, _ENTRANCE_TOKENS), hard=False,
                  detail=f"types={types}"))
        add(Check("has_windows", _any_token(types, _WINDOW_TOKENS), hard=False,
                  detail=f"types={types}"))
        add(Check("has_roof", _any_token(types, _ROOF_TOKENS), hard=False,
                  detail=f"types={types}"))
        # SOFT：组件数量（过少往往是"空盒子"）。
        add(Check("component_richness", len(comps) >= 2 or len(slots) >= 2, hard=False,
                  detail=f"components={len(comps)} slots={len(slots)}"))
    elif has_program:
        add(Check("plan_program_path", True, hard=False,
                  detail="几何来自 plan_skeleton/plan_program（跳过组件级语义检查）"))

    # SOFT：合理性 —— "太矮"（与 Java ComponentPlanCompiler.minHeightForType 对齐）。
    too_short = _too_short_masses(comps)
    add(Check("not_too_short", not too_short, hard=False,
              detail=", ".join(too_short)))

    # SOFT：立面参数取值合法（与 Java 生成器可识别的枚举对齐；非法值会被静默忽略）。
    bad_facade = _invalid_facade_params(comps)
    add(Check("facade_params_valid", not bad_facade, hard=False,
              detail=", ".join(bad_facade)))

    bad_assembly = _invalid_assembly_params(comps)
    add(Check("assembly_params_valid", not bad_assembly, hard=False,
              detail=", ".join(bad_assembly)))

    try:
        from app.services.assembly_plan_validator import validate_assembly_plan
        asm_issues = validate_assembly_plan(plan)
        hard_asm = [i for i in asm_issues if i.severity == "ERROR"]
        add(Check("assembly_graph_dsl_valid", not hard_asm, hard=False,
                  detail=", ".join(f"{i.code}@{i.path}" for i in hard_asm[:5])))
    except Exception:
        pass

    # SOFT：按用户 prompt 的意图对齐（Week 1+）。
    for ic in evaluate_intent(plan, prompt):
        add(ic)
    for ic in evaluate_typology_fixture_alignment(plan, prompt):
        add(ic)

    # SOFT：比例 + 围合逻辑（P0 typology cards）
    try:
        from eval.proportion_eval import evaluate_enclosure, evaluate_proportions
        for name, ok, detail in evaluate_proportions(plan, prompt):
            add(Check(name, ok, hard=False, detail=detail))
        for name, ok, detail in evaluate_enclosure(plan, prompt):
            add(Check(name, ok, hard=False, detail=detail))
    except Exception:
        pass

    return res


# 与 Java ComponentFacadeStyler / FacadePatternDsl / ComponentPlanCompiler 的可识别取值对齐。
_ALLOWED_FACADE_PROFILE = {"none", "base_plinth", "vertical_pilasters", "pilasters", "mullion_grid", "mullion", "module_grid"}
_ALLOWED_WALL_PATTERN = {"none", "uniform", "gradient", "striped", "random"}
# 直接对齐 FacadePatternDsl 的 contains(...) 子串（避免误判 arch_window / perforated 等复合词）。
_ALLOWED_FACADE_CUTOUT = {"none", "solid", "lattice", "grille", "perfor", "diagrid", "diagonal", "diamond", "checker", "rose", "circle", "oculus", "arch"}
_ALLOWED_DETAIL_LEVEL = {"low", "medium", "high"}
# assembly_facade 是布尔类开关；接受 bool / 0-1 / 常见真假字符串。
_ALLOWED_ASSEMBLY_FACADE_STR = {"true", "false", "1", "0", "auto", "on", "off", "yes", "no"}
_ALLOWED_WINDOW_ASPECT = {
    "square", "horizontal_strip", "vertical_strip", "ribbon_glazing",
    "arrow_slit", "punch_window", "full_height", "horizontal_band", "default", "none",
}


def _invalid_facade_params(comps: List[Dict[str, Any]]) -> List[str]:
    out: List[str] = []

    def _check(params: Dict[str, Any], idx: int, keys: Tuple[str, ...], allowed: set, label: str,
               substring: bool = False) -> None:
        # substring=True 对齐 Java 侧的 String.contains 语义（如 facade_profile / facade_cutout），
        # 只要取值包含任一已知 token 即视为可识别；否则要求精确匹配（如 wall_pattern / detail_level）。
        for k in keys:
            v = params.get(k)
            if not (isinstance(v, str) and v.strip()):
                continue
            vl = v.strip().lower()
            recognized = any(tok in vl for tok in allowed) if substring else (vl in allowed)
            if not recognized:
                out.append(f"#{idx} {label}={v}")
                return

    def _check_bool(params: Dict[str, Any], idx: int, keys: Tuple[str, ...], label: str) -> None:
        for k in keys:
            v = params.get(k)
            if v is None:
                continue
            if isinstance(v, bool):
                return
            if isinstance(v, int) and v in (0, 1):
                return
            if isinstance(v, str) and v.strip().lower() in _ALLOWED_ASSEMBLY_FACADE_STR:
                return
            out.append(f"#{idx} {label}={v!r}")
            return

    for i, c in enumerate(comps):
        params = c.get("params") if isinstance(c.get("params"), dict) else {}
        if not params:
            continue
        _check(params, i, ("facade_profile", "facadeProfile"), _ALLOWED_FACADE_PROFILE, "facade_profile", substring=True)
        _check(params, i, ("wall_pattern", "wallPattern"), _ALLOWED_WALL_PATTERN, "wall_pattern")
        _check(params, i, ("facade_cutout", "cutout_pattern", "perforation"), _ALLOWED_FACADE_CUTOUT, "facade_cutout", substring=True)
        _check(params, i, ("detail_level", "detailLevel", "quality"), _ALLOWED_DETAIL_LEVEL, "detail_level")
        _check(params, i, ("window_aspect", "windowAspect"), _ALLOWED_WINDOW_ASPECT, "window_aspect")
        _check_bool(params, i, ("assembly_facade", "assemblyFacade"), "assembly_facade")
    return out


def _invalid_assembly_params(comps: List[Dict[str, Any]]) -> List[str]:
    """ASSEMBLY 组件必须携带 params.assembly（或 graph/macro/ops 根字段）。"""
    out: List[str] = []
    for i, c in enumerate(comps):
        t = c.get("component_type")
        t = t.strip().upper() if isinstance(t, str) else ""
        if t != "ASSEMBLY":
            continue
        params = c.get("params") if isinstance(c.get("params"), dict) else {}
        assembly = params.get("assembly") if isinstance(params.get("assembly"), dict) else None
        has_root = any(k in params for k in ("ops", "components", "graph", "macro", "preset", "presetId"))
        if assembly is None and not has_root:
            out.append(f"#{i} ASSEMBLY missing params.assembly")
            continue
        payload = assembly if assembly is not None else params
        if isinstance(payload, dict) and (payload.get("preset") or payload.get("presetId")):
            continue
        if not any(k in payload for k in ("ops", "components", "graph", "macro")):
            out.append(f"#{i} ASSEMBLY assembly payload empty")
    return out


def _nested_assembly_in_mass(comps: List[Dict[str, Any]]) -> List[str]:
    out: List[str] = []
    for i, c in enumerate(comps):
        t = str(c.get("component_type") or "").upper()
        if not t.startswith("MASS_") and t not in ("MASS_MAIN", "MAIN_MASS"):
            continue
        params = c.get("params") if isinstance(c.get("params"), dict) else {}
        if isinstance(params.get("assembly"), dict):
            out.append(f"#{i} {t} nested params.assembly")
    return out


def _assembly_payload(c: Dict[str, Any]) -> Optional[Dict[str, Any]]:
    params = c.get("params") if isinstance(c.get("params"), dict) else {}
    assembly = params.get("assembly")
    if isinstance(assembly, dict):
        return assembly
    if any(k in params for k in ("ops", "components", "graph", "macro")):
        return params
    return None


def _assembly_has_twist(comps: List[Dict[str, Any]]) -> bool:
    for c in comps:
        if str(c.get("component_type") or "").upper() != "ASSEMBLY":
            continue
        payload = _assembly_payload(c)
        if not payload:
            continue
        preset_params = payload.get("presetParams") if isinstance(payload.get("presetParams"), dict) else {}
        if preset_params.get("twistTurns") is not None or preset_params.get("twist_turns") is not None:
            return True
        if payload.get("preset") or payload.get("presetId"):
            return True
        graph = payload.get("graph") if isinstance(payload.get("graph"), dict) else {}
        components = graph.get("components") if isinstance(graph.get("components"), list) else []
        for comp in components:
            if not isinstance(comp, dict):
                continue
            if comp.get("twistTurns") is not None or comp.get("twist_turns") is not None:
                return True
        ops = payload.get("ops") if isinstance(payload.get("ops"), list) else []
        for op in ops:
            if isinstance(op, dict) and (
                op.get("twistTurns") is not None or op.get("twist_turns") is not None
            ):
                return True
    return False


def _assembly_slot_has_mass_stack(comps: List[Dict[str, Any]]) -> bool:
    by_slot: Dict[str, List[str]] = {}
    for c in comps:
        slot = c.get("slot_id")
        slot_key = str(slot) if slot else "__global__"
        t = str(c.get("component_type") or "").upper()
        by_slot.setdefault(slot_key, []).append(t)
    conflict_types = {"MASS_SECONDARY", "FACADE_WINDOWS", "ENTRANCE", "ROOF", "ROOF_STRUCTURE"}
    for types in by_slot.values():
        if "ASSEMBLY" not in types:
            continue
        if any(t in conflict_types for t in types):
            return True
        mass_count = sum(1 for t in types if t.startswith("MASS_") or t in ("MASS_MAIN", "MAIN_MASS"))
        if mass_count >= 1:
            return True
    return False


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
        mark = "OK" if c.passed else ("FAIL" if c.hard else "WARN")
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


def _load_scenarios() -> List[Dict[str, Any]]:
    path = _THIS.parent / "fixtures" / "scenarios.json"
    if not path.is_file():
        return []
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
        return data if isinstance(data, list) else []
    except Exception:
        return []


def run_scenarios(gate: bool = False, ci_only: bool = False) -> int:
    """评估 fixtures/scenarios.json 中记录的 prompt + 捕获 plan。"""
    scenarios = _load_scenarios()
    if not scenarios:
        print("未找到 eval/fixtures/scenarios.json")
        return 2

    results: List[EvalResult] = []
    for sc in scenarios:
        if not isinstance(sc, dict):
            continue
        if ci_only and not sc.get("ci", True):
            continue
        prompt = sc.get("prompt") if isinstance(sc.get("prompt"), str) else ""
        rel = sc.get("plan_fixture") if isinstance(sc.get("plan_fixture"), str) else ""
        plan_path = _THIS.parent / "fixtures" / rel
        plan = _load_plan_file(plan_path)
        if plan is None:
            continue
        label = sc.get("id") or plan_path.name
        result = evaluate_plan(plan, label=f"{label} | {prompt[:24]}…", prompt=prompt)
        if gate or ci_only:
            promote_scenario_hard_checks(result, sc)
        results.append(result)

    if not results:
        print("scenario fixtures 无法加载。")
        return 2

    for r in results:
        _print_result(r)
    return _summarize(results, gate)


def run_offline(paths: List[Path], gate: bool = False, prompt: Optional[str] = None) -> int:
    results: List[EvalResult] = []
    for p in paths:
        plan = _load_plan_file(p)
        if plan is None:
            continue
        results.append(evaluate_plan(plan, label=p.name, prompt=prompt))

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
            results.append(evaluate_plan(plan, label=prompt, prompt=prompt))
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
    parser.add_argument("--scenarios", action="store_true",
                        help="评估 eval/fixtures/scenarios.json（Week 1 捕获 plan + prompt）")
    parser.add_argument("--ci-only", action="store_true",
                        help="与 --scenarios 联用：仅评估 ci:true 场景")
    parser.add_argument("--diversity", action="store_true",
                        help="对多个 plan 计算 diversity 指标（需 --plans 目录或多个 JSON）")
    parser.add_argument("--diversity-scenarios", action="store_true",
                        help="运行 eval/fixtures/diversity_scenarios.json")
    parser.add_argument("--live-diversity", action="store_true",
                        help="在线：同一 prompt 采样多次并测 diversity（需 LLM）")
    parser.add_argument("--samples", type=int, default=3,
                        help="--live-diversity 采样次数")
    parser.add_argument("--min-unique-ratio", type=float, default=0.5,
                        help="diversity 最低 unique_ratio（默认 0.5）")
    parser.add_argument("--min-mean-distance", type=float, default=0.12,
                        help="diversity 最低平均 Jaccard 距离（默认 0.12）")
    parser.add_argument("--prompt", type=str,
                        help="与 --plan 联用：按用户描述做意图 SOFT 断言；"
                             "与 --live-diversity 联用：指定采样 prompt")
    args = parser.parse_args(argv)

    if args.scenarios:
        return run_scenarios(gate=args.gate, ci_only=args.ci_only)

    if args.diversity_scenarios:
        from eval.diversity_eval import run_diversity_scenarios
        return run_diversity_scenarios(gate=args.gate)

    if args.live_diversity:
        from eval.diversity_eval import DiversityThresholds, run_live_diversity
        prompt = args.prompt or "在锚点位置生成现代风格的椭圆形体育场建筑"
        th = DiversityThresholds(
            min_unique_ratio=args.min_unique_ratio,
            min_mean_distance=args.min_mean_distance,
        )
        return run_live_diversity(prompt, samples=args.samples, gate=args.gate, thresholds=th)

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

    if args.diversity:
        if not paths:
            print("--diversity 需要 --plans 目录或 --plan 文件")
            return 2
        from eval.diversity_eval import DiversityThresholds, run_offline_diversity
        th = DiversityThresholds(
            min_unique_ratio=args.min_unique_ratio,
            min_mean_distance=args.min_mean_distance,
        )
        return run_offline_diversity(paths, label="golden_eval", thresholds=th, gate=args.gate)

    if not paths:
        print(__doc__)
        print("\nGolden prompts（回归基准）：")
        for i, p in enumerate(GOLDEN_PROMPTS, 1):
            print(f"  {i}. {p}")
        return 0

    return run_offline(paths, gate=args.gate, prompt=args.prompt)


if __name__ == "__main__":
    raise SystemExit(main())
