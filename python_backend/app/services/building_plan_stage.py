"""
Building Plan Stage (PR-2) — Research → Plan 两阶段分离。

Stage R: building_research_agent.research_building_profile()
Stage P: 本模块 — 将 BuildingProfile 约束注入 LlmPlan 生成（独立 prompt 合约）
"""

from __future__ import annotations

import json
import os
from typing import Any, Dict, List, Optional, Tuple

from ..models.building_profile import BuildingProfile

# 与 Java ComponentGeneratorRegistry / LlmPlan prompt 对齐的可注册构件类型
REGISTERED_COMPONENT_TYPES: Tuple[str, ...] = (
    "MASS_MAIN",
    "MASS_SECONDARY",
    "MASS_ANCILLARY",
    "ROOF",
    "FACADE_WINDOWS",
    "FACADE",
    "ENTRANCE",
    "TOWER",
    "WALL",
    "COURTYARD",
    "COURTYARD_SPACE",
    "PATH",
    "ROAD",
    "TERRACE",
    "MODULE",
    "GATE",
    "KEEP",
    "PLAZA",
    "PLAZA_CORE",
)

PLAN_STAGE_SYSTEM_ADDON = (
    "PLAN STAGE (after Building Research):\n"
    "- You receive a BuildingProfile from open-world research; convert it to a valid LlmPlan JSON.\n"
    "- Use ONLY registered component_type values listed in the user message.\n"
    "- Prefer minecraft_strategy.recommended_components; map distinctive_elements to params/features.\n"
    "- Respect scale_hints for dimensions (width/depth/height in blocks).\n"
    "- Set layout.skeleton_type from minecraft_strategy when present.\n"
    "- Use MODULE + feature landmark:xxx ONLY if profile.minecraft_strategy.landmark_module is non-null.\n"
    "- Do NOT output block ids; semantic components only.\n"
    "- global_constraints.symmetry MUST be one of: NONE, MIRROR_X, MIRROR_Z, RADIAL "
    "(NOT genome.symmetry.type values like bilateral/radial).\n"
    "- If profile and user request conflict, prefer the user request for intent and profile for form/style.\n"
)


def is_research_two_phase_enabled() -> bool:
    raw = (os.getenv("BUILDING_RESEARCH_TWO_PHASE") or "on").strip().lower()
    return raw not in ("off", "0", "false", "no")


PLAN_STAGE_MARKER = "PLAN STAGE (after Building Research)"
RESEARCH_OVERRIDE_MARKER = "OPEN-WORLD RESEARCH OVERRIDE"


def research_landmark_override_block(profile: BuildingProfile) -> str:
    """Authoritative override for Java-side landmark MODULE prompt leakage."""
    lm = (profile.minecraft_strategy.landmark_module or "").strip()
    if lm:
        return f"""
========================================
{RESEARCH_OVERRIDE_MARKER} (authoritative)
========================================
BuildingProfile.minecraft_strategy.landmark_module = "{lm}"
You MUST output exactly ONE MODULE component with features ["landmark:{lm}"].
Ignore conflicting landmark hints from earlier prompt sections.

"""
    return f"""
========================================
{RESEARCH_OVERRIDE_MARKER} (authoritative)
========================================
BuildingProfile.minecraft_strategy.landmark_module = null
Do NOT output MODULE or landmark:* features.
IGNORE all "LANDMARK MODULE ROUTING (MANDATORY/RECOMMENDED)" sections above.
Compose using minecraft_strategy.recommended_components and distinctive_elements only.

"""


def apply_research_landmark_override(system_prompt: str, profile: BuildingProfile) -> str:
    block = research_landmark_override_block(profile).strip()
    base = (system_prompt or "").strip()
    if RESEARCH_OVERRIDE_MARKER in base:
        return base
    return f"{block}\n\n{base}" if base else block


def _strip_research_profile_block(text: str) -> str:
    """移除 PR-1 单阶段注入的 Building Research Profile 文本块，保留其余上下文。"""
    start = text.find("=== Building Research Profile")
    if start < 0:
        return text
    before = text[:start].rstrip()
    tail = text[start:]
    rules = tail.find("Planning rules:")
    if rules >= 0:
        lines = tail[rules:].split("\n")
        end = rules
        for i, line in enumerate(lines):
            end += len(line) + (1 if i < len(lines) - 1 else 0)
            if i > 0 and line.strip() == "":
                break
        after = tail[end:].lstrip("\n")
    else:
        close = tail.find("===", len("=== Building Research Profile"))
        chunk = tail[close + 3:].lstrip("\n") if close >= 0 else tail
        parts = [p for p in chunk.split("\n\n") if p.strip()]
        after = "\n\n".join(parts[1:]) if len(parts) > 1 else ""
    if before and after:
        return f"{before}\n\n{after}".strip()
    return (before or after).strip()


def plan_stage_system_augmentation() -> str:
    return PLAN_STAGE_SYSTEM_ADDON


def build_plan_stage_user_block(
    profile: BuildingProfile,
    user_request: str,
    *,
    include_registered_types: bool = True,
) -> str:
    """Stage P 专用 user 块：Profile JSON + 规划合约 + 原始用户请求。"""
    profile_json = json.dumps(profile.to_prompt_dict(), ensure_ascii=False, indent=2)
    lines = [
        "=== STAGE P: LlmPlan from BuildingProfile ===",
        "",
        "Research is complete. Produce the final LlmPlan JSON using the profile below.",
        "",
        "BuildingProfile(JSON):",
        profile_json,
        "",
        "Planning checklist:",
        "1. layout.skeleton_type ← minecraft_strategy.skeleton_type",
        "2. components[] ← recommended_components + distinctive_elements",
        "3. dimensions ← scale_hints (blocks); use reasonable defaults if null",
        "4. style_profile ← identity.style when available; if identity.architect is set, match that architect's style profile",
        "5. MODULE/landmark only when landmark_module is set in profile (never birds_nest for Zaha/architect-led)",
        "6. If reference_blueprint is present, map architectural_layers → components[] with matching dimensions",
        "7. Use block_palette roles in style_attributes / params.material hints",
        "8. Apply generation_rules / detailing_rules in params and features",
        "9. If research_notes contain [Visual], prioritize visual observations for form/materials",
        "",
    ]
    if include_registered_types:
        lines.append(
            "Registered component_type values (ONLY these): "
            + ", ".join(REGISTERED_COMPONENT_TYPES)
        )
        lines.append("")
    lines.extend([
        "USER REQUEST (authoritative for intent):",
        user_request.strip() or "(none)",
        "",
        "Output: single valid LlmPlan JSON object.",
    ])
    return "\n".join(lines)


def augment_prompts_for_plan_stage(
    profile: BuildingProfile,
    user_request: str,
    system_prompt: str,
    user_prompt: str,
) -> Tuple[str, str]:
    """
    两阶段模式：用 Stage P 块替换/前置 research 文本，避免 R+P 混在一段模糊 prompt 里。
    """
    stage_block = build_plan_stage_user_block(profile, user_request)

    cleaned = _strip_research_profile_block(user_prompt)

    new_user = stage_block
    if cleaned.strip():
        new_user = stage_block + "\n\n--- Additional context ---\n\n" + cleaned.strip()

    new_system = system_prompt.strip()
    addon = plan_stage_system_augmentation()
    if PLAN_STAGE_MARKER not in new_system:
        new_system = (new_system + "\n\n" + addon).strip() if new_system else addon
    new_system = apply_research_landmark_override(new_system, profile)

    return new_system, new_user


def _component_types_in_plan(plan: Dict[str, Any]) -> List[str]:
    out: List[str] = []
    for comp in plan.get("components") or []:
        if isinstance(comp, dict):
            ct = str(comp.get("component_type") or "").upper()
            if ct:
                out.append(ct)
    return out


def evaluate_plan_profile_alignment(
    plan: Dict[str, Any],
    profile: BuildingProfile,
) -> List[Tuple[str, bool, str]]:
    """
    离线断言：plan 是否反映 BuildingProfile（用于测试 / 可选 soft eval）。
    Returns list of (name, passed, detail).
    """
    results: List[Tuple[str, bool, str]] = []
    components = _component_types_in_plan(plan)

    # skeleton
    expected_skel = (profile.minecraft_strategy.skeleton_type or "").upper()
    actual_skel = str((plan.get("layout") or {}).get("skeleton_type") or "").upper()
    if expected_skel:
        ok = actual_skel == expected_skel
        results.append((
            "plan_reflects_skeleton",
            ok,
            f"expected={expected_skel} actual={actual_skel or 'missing'}",
        ))

    # recommended components overlap
    recommended = [
        str(c).upper()
        for c in (profile.minecraft_strategy.recommended_components or [])
    ]
    if recommended:
        overlap = [c for c in recommended if c in components]
        ok = len(overlap) >= 1
        results.append((
            "plan_uses_recommended_components",
            ok,
            f"recommended={recommended} found={components} overlap={overlap}",
        ))

    # landmark module
    lm = profile.minecraft_strategy.landmark_module
    if lm:
        feat = f"landmark:{lm}".lower()
        has_lm = False
        for c in plan.get("components") or []:
            if not isinstance(c, dict):
                continue
            if str(c.get("component_type") or "").upper() != "MODULE":
                continue
            if feat in str(c.get("feature") or "").lower():
                has_lm = True
                break
            for f in c.get("features") or []:
                if feat in str(f).lower():
                    has_lm = True
                    break
            if has_lm:
                break
        results.append((
            "plan_uses_landmark_module",
            has_lm,
            f"expected feature containing {feat!r}",
        ))

    # scale hints (soft — within 2x)
    sh = profile.scale_hints
    masses = [
        c for c in (plan.get("components") or [])
        if isinstance(c, dict)
        and str(c.get("component_type") or "").upper().startswith("MASS")
    ]
    if masses and sh.typical_width_blocks:
        dims = masses[0].get("dimensions") or {}
        w = int(dims.get("width") or 0)
        target = int(sh.typical_width_blocks)
        if w > 0 and target > 0:
            ratio = w / target
            ok = 0.25 <= ratio <= 4.0
            results.append((
                "plan_scale_near_hints",
                ok,
                f"width={w} hint={target} ratio={ratio:.2f}",
            ))

    # distinctive elements — at least plan has multiple components when profile has features
    distinct = profile.structure.distinctive_elements or []
    if distinct:
        ok = len(components) >= 2
        results.append((
            "plan_expresses_complexity",
            ok,
            f"distinctive_elements={len(distinct)} component_count={len(components)}",
        ))

    return results


def alignment_hard_failures(
    results: List[Tuple[str, bool, str]],
    *,
    hard_names: Optional[frozenset[str]] = None,
) -> List[str]:
    hard = hard_names or frozenset({
        "plan_reflects_skeleton",
        "plan_uses_recommended_components",
        "plan_uses_landmark_module",
    })
    return [name for name, ok, detail in results if name in hard and not ok]
