"""Plan semantic fidelity gate — reject empty / house-default plans for distinctive typologies."""

from __future__ import annotations

from typing import Any, Dict, List, Optional, Tuple

from ..models.llm_plan import LlmPlanValidationError

# Morphologies that must not collapse into a single residential MASS + gable house.
_TOWER_LIKE_MARKERS = (
    "佛塔", "古塔", "宝塔", "藏式", "pagoda", "stupa", "dense_eaves_pagoda",
    "tiered_eave", "finial_spire", "塔刹", "重檐",
)
_GATE_MARKERS = (
    "山门", "牌坊", "paifang", "gatehouse", "temple gate", "寺庙山门",
)
_GARDEN_MARKERS = (
    "园林", "庭院", "garden", "courtyard compound", "江南园林", "日式庭院",
)
_TEMPLE_MARKERS = (
    "道观", "寺庙", "庙宇", "temple", "daoist", "shrine compound",
)

_TOWER_COMPONENT_HINTS = (
    "STRUCTURE", "TOWER", "MODULE", "ASSEMBLY", "CROWN",
)
_TOWER_FEATURE_HINTS = (
    "pagoda", "stupa", "tower", "tier", "eave", "finial", "spire",
    "dense_eaves", "佛塔", "古塔", "塔刹", "重檐",
)
_HOUSE_ROOF_TYPES = frozenset({"gable", "xuanshan", "hip", ""})


def detect_semantic_intent(user_text: str, plan: Optional[Dict[str, Any]] = None) -> str:
    """Return a coarse semantic class for the request/plan corpus."""
    corpus_parts: List[str] = [user_text or ""]
    if isinstance(plan, dict):
        feats = plan.get("distinguishing_features")
        if isinstance(feats, list):
            corpus_parts.extend(str(f) for f in feats if f)
        sa = plan.get("style_attributes")
        if isinstance(sa, dict):
            deco = sa.get("decorative_elements")
            if isinstance(deco, list):
                corpus_parts.extend(str(d) for d in deco if d)
        sp = plan.get("style_profile")
        if sp:
            corpus_parts.append(str(sp))
    corpus = " ".join(corpus_parts).lower()
    raw = " ".join(corpus_parts)

    def _hit(markers: Tuple[str, ...]) -> bool:
        return any(m.lower() in corpus or m in raw for m in markers)

    if _hit(_TOWER_LIKE_MARKERS):
        return "tower_like"
    if _hit(_GATE_MARKERS):
        return "gate"
    if _hit(_GARDEN_MARKERS):
        return "garden"
    if _hit(_TEMPLE_MARKERS):
        return "temple"
    return "generic"


def _component_types(plan: Dict[str, Any]) -> List[str]:
    comps = plan.get("components")
    if not isinstance(comps, list):
        return []
    out: List[str] = []
    for c in comps:
        if isinstance(c, dict):
            t = str(c.get("component_type") or c.get("componentType") or "").upper()
            if t:
                out.append(t)
    return out


def _has_tower_morphology(plan: Dict[str, Any]) -> bool:
    types = _component_types(plan)
    if any(t.startswith(h) or h in t for t in types for h in _TOWER_COMPONENT_HINTS):
        return True
    comps = plan.get("components") or []
    if not isinstance(comps, list):
        return False
    mass_count = sum(1 for t in types if t.startswith("MASS") or t == "MAIN_MASS")
    for c in comps:
        if not isinstance(c, dict):
            continue
        feats = c.get("features") or []
        params = c.get("params") if isinstance(c.get("params"), dict) else {}
        blob = " ".join(str(f) for f in feats if f) + " " + " ".join(
            f"{k}={v}" for k, v in params.items()
        )
        lower = blob.lower()
        if any(h in lower or h in blob for h in _TOWER_FEATURE_HINTS):
            return True
        if params.get("tier_index") is not None or params.get("levels") or params.get("eave_tiers"):
            return True
        typology = str(params.get("typology") or params.get("structural_typology") or "").lower()
        if "pagoda" in typology or "stupa" in typology or "tower" in typology:
            return True
        dims = c.get("dimensions") if isinstance(c.get("dimensions"), dict) else {}
        try:
            w = int(dims.get("width") or 0)
            d = int(dims.get("depth") or 0)
            h = int(dims.get("height") or 0)
        except (TypeError, ValueError):
            w = d = h = 0
        # Tall slender multi-storey body counts as tower-like mass.
        if (c.get("component_type") or "").upper().startswith("MASS") and h >= max(w, d) * 1.5 and h >= 12:
            return True
    # Multiple stacked masses / roofs also count.
    roof_count = sum(1 for t in types if t == "ROOF")
    if mass_count >= 2 and roof_count >= 2:
        return True
    return False


def _looks_like_default_house(plan: Dict[str, Any]) -> bool:
    types = set(_component_types(plan))
    mass_types = {t for t in types if t.startswith("MASS") or t == "MAIN_MASS"}
    if not mass_types:
        return False
    # Single primary mass (+ optional residential satellites) without tower cues.
    if _has_tower_morphology(plan):
        return False
    comps = plan.get("components") or []
    if not isinstance(comps, list):
        return True
    for c in comps:
        if not isinstance(c, dict):
            continue
        ctype = str(c.get("component_type") or "").upper()
        params = c.get("params") if isinstance(c.get("params"), dict) else {}
        if ctype == "ROOF":
            rt = str(params.get("roof_type") or params.get("roofType") or "").lower()
            if rt and rt not in _HOUSE_ROOF_TYPES:
                return False
    # Classic residential kit.
    residential = {"FOUNDATION", "ROOF", "ENTRANCE", "FACADE_WINDOWS"}
    if mass_types and (types & residential or len(mass_types) == 1):
        return True
    return len(mass_types) == 1 and not (types - mass_types - residential)


def _has_alternate_geometry(plan: Dict[str, Any]) -> bool:
    if plan.get("capability_gap") is not None:
        return True
    status = str(plan.get("plan_status") or "").strip().lower()
    if status in ("capability_gap", "error"):
        return True
    if isinstance(plan.get("plan_program"), dict) and plan["plan_program"]:
        return True
    if isinstance(plan.get("plan_skeleton"), dict) and plan["plan_skeleton"]:
        return True
    patch = plan.get("patch")
    if isinstance(patch, dict) and patch.get("blocks"):
        return True
    return False


def evaluate_plan_semantics(
    plan: Dict[str, Any],
    user_text: str = "",
) -> List[str]:
    """Return human-readable semantic errors (empty list = ok)."""
    if not isinstance(plan, dict):
        return ["root: plan must be object"]
    errors: List[str] = []
    mode = str(plan.get("mode") or "build").lower()
    if mode != "build":
        return errors

    comps = plan.get("components")
    empty = not isinstance(comps, list) or len(comps) == 0
    if empty and not _has_alternate_geometry(plan):
        errors.append(
            "components: mode=build requires non-empty components[] "
            "(or plan_program / plan_skeleton / capability_gap)"
        )
        return errors

    intent = detect_semantic_intent(user_text, plan)
    if intent == "tower_like":
        if empty or _looks_like_default_house(plan) or not _has_tower_morphology(plan):
            errors.append(
                "semantics: tower/pagoda intent requires layered tower morphology "
                "(STRUCTURE/typology/stacked masses or tower features); "
                "residential MASS_MAIN+gable is not acceptable"
            )
    elif intent == "gate":
        types = set(_component_types(plan))
        if empty or (types <= {"MASS_MAIN", "ROOF", "FOUNDATION", "ENTRANCE", "FACADE_WINDOWS"}
                     and "STRUCTURE" not in types and "ASSEMBLY" not in types):
            # Gate may be a wide shallow MASS — require at least non-default proportions or features.
            shallow_ok = False
            for c in comps or []:
                if not isinstance(c, dict):
                    continue
                dims = c.get("dimensions") if isinstance(c.get("dimensions"), dict) else {}
                try:
                    w, d, h = int(dims.get("width") or 0), int(dims.get("depth") or 0), int(dims.get("height") or 0)
                except (TypeError, ValueError):
                    continue
                if w >= d * 1.5 and w >= 10:
                    shallow_ok = True
                    break
                feats = " ".join(str(f) for f in (c.get("features") or []) if f).lower()
                if "gate" in feats or "山门" in feats or "paifang" in feats:
                    shallow_ok = True
                    break
            if not shallow_ok:
                errors.append(
                    "semantics: temple gate / paifang intent needs a gate-like wide mass "
                    "or STRUCTURE/ASSEMBLY, not a generic house kit"
                )
    elif intent in ("garden", "temple"):
        if empty:
            errors.append(f"semantics: {intent} intent cannot have empty components[]")
        elif _looks_like_default_house(plan) and intent == "garden":
            # Gardens should not be a lone house; allow if PATH/COURTYARD/ASSEMBLY present.
            types = set(_component_types(plan))
            if not (types & {"PATH", "COURTYARD", "COURTYARD_SPACE", "ASSEMBLY", "GARDEN", "PAVING", "STRUCTURE"}):
                errors.append(
                    "semantics: garden/courtyard intent needs site composition "
                    "(PATH/COURTYARD/ASSEMBLY/…), not only a residential house kit"
                )
    return errors


def assert_plan_semantics(plan: Dict[str, Any], user_text: str = "") -> Dict[str, Any]:
    """Raise LlmPlanValidationError when semantic fidelity fails."""
    errors = evaluate_plan_semantics(plan, user_text)
    if errors:
        raise LlmPlanValidationError(errors)
    return plan


def coerce_style_attribute_maps(plan: Dict[str, Any]) -> Dict[str, Any]:
    """Move palette/materials preset ID strings onto style_profile; keep maps as maps."""
    if not isinstance(plan, dict):
        return plan
    sa = plan.get("style_attributes")
    if not isinstance(sa, dict):
        return plan
    sa = dict(sa)
    changed = False
    for key in ("palette", "materials", "block_palette"):
        val = sa.get(key)
        if isinstance(val, str):
            preset = val.strip()
            if preset:
                if not str(plan.get("style_profile") or "").strip():
                    plan["style_profile"] = preset
                # Drop string form so Java Map fields do not fail deserialization.
                sa.pop(key, None)
                changed = True
        elif val is not None and not isinstance(val, dict):
            sa.pop(key, None)
            changed = True
    if changed:
        plan["style_attributes"] = sa
    return plan
