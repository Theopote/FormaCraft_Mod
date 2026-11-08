"""Plan semantic fidelity gate — reject empty / house-default plans for distinctive typologies."""

from __future__ import annotations

from typing import Any, Dict, List, Optional, Tuple

from ..models.llm_plan import LlmPlanValidationError

# Morphologies that must not collapse into a single residential MASS + gable house.
# Keep markers aligned with request_intent_vocab_v1.json — never treat 重檐 as tower.
_TOWER_LIKE_MARKERS = (
    "佛塔", "古塔", "宝塔", "藏式", "pagoda", "stupa", "dense_eaves_pagoda", "tibetan_stupa",
    "tiered_eave", "finial_spire", "塔刹",
)
_GATE_MARKERS = (
    "山门", "牌坊", "paifang", "gatehouse", "temple gate", "寺庙山门",
)
_GARDEN_MARKERS = (
    "江南园林", "日式庭院", "园林", "courtyard compound",
)
_TEMPLE_MARKERS = (
    "道观", "寺庙", "庙宇", "大殿", "殿堂", "temple", "daoist", "shrine compound",
)

_TOWER_COMPONENT_HINTS = (
    "STRUCTURE", "TOWER",
)
_TOWER_FEATURE_HINTS = (
    "pagoda", "stupa", "tower", "tier", "eave", "finial", "spire",
    "dense_eaves", "tibetan_stupa", "覆钵", "相轮", "佛塔", "古塔", "塔刹",
)
_HOUSE_ROOF_TYPES = frozenset({"gable", "xuanshan", "hip", ""})


def _authoritative_building_class(user_text: str, plan: Optional[Dict[str, Any]]) -> Optional[str]:
    """Prefer merged request_intent (keyword + LLM declared) over raw plan feature strings."""
    from .request_intent import _looks_residential_without_pagoda, resolve_request_intent

    corpus = user_text or ""
    if isinstance(plan, dict):
        meta = plan.get("plan_meta")
        if isinstance(meta, dict):
            ri = meta.get("request_intent")
            if isinstance(ri, dict):
                cls = str(ri.get("building_class") or "").strip().lower()
                conf = float(ri.get("confidence") or 0.0)
                ri_text = str(ri.get("original_text") or "").strip()
                if ri_text:
                    corpus = ri_text
                # Stale LLM tower_like on residential prompts must not force the tower gate.
                if cls == "tower_like" and _looks_residential_without_pagoda(corpus or user_text or ""):
                    cls = "generic"
                # Low-confidence keyword guesses must not force specialized gates.
                if cls and cls != "generic" and conf >= 0.5:
                    return cls
            cls = str(meta.get("building_class") or "").strip().lower()
            if cls == "tower_like" and _looks_residential_without_pagoda(corpus or user_text or ""):
                cls = "generic"
            if cls and cls != "generic":
                return cls
            text = str(meta.get("request_intent_text") or "").strip()
            if text:
                corpus = text
                user_text = text
        hints = plan.get("proportion_hints")
        if isinstance(hints, dict):
            arch = hints.get("architecture_intent")
            if isinstance(arch, dict) and str(arch.get("original_text") or "").strip():
                user_text = str(arch.get("original_text"))
                corpus = user_text
    if user_text or plan:
        intent = resolve_request_intent(user_text or "", plan)
        cls = str(intent.get("building_class") or "").strip().lower()
        conf = float(intent.get("confidence") or 0.0)
        if cls == "tower_like" and _looks_residential_without_pagoda(corpus or user_text or ""):
            return None
        if cls and cls != "generic" and conf >= 0.5:
            return cls
    return None


def detect_semantic_intent(user_text: str, plan: Optional[Dict[str, Any]] = None) -> str:
    """Return a coarse semantic class; merged request_intent wins over LLM feature strings."""
    auth = _authoritative_building_class(user_text, plan)
    if auth:
        return auth

    # Last-resort lexical fallback on USER TEXT only (no 重檐 / bare 庭院).
    # Do NOT scan plan distinguishing_features / style tags — a poisoned LLM plan
    # (e.g. 台北101 tagged as pagoda from chat history) must not self-trigger
    # tower/gate semantics and hard-scaffold replacement.
    raw = user_text or ""
    corpus = raw.lower()

    def _hit(markers: Tuple[str, ...]) -> bool:
        return any(m.lower() in corpus or m in raw for m in markers)

    if _hit(_TOWER_LIKE_MARKERS):
        return "tower_like"
    if _hit(_GATE_MARKERS):
        return "gate"
    if _hit(_TEMPLE_MARKERS):
        return "temple"
    if _hit(_GARDEN_MARKERS):
        return "garden"
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


def _has_native_pagoda_typology(plan: Dict[str, Any]) -> bool:
    """True only for STRUCTURE/TOWER bound to dense_eaves_pagoda / tibetan_stupa."""
    comps = plan.get("components") or []
    if not isinstance(comps, list):
        return False
    for c in comps:
        if not isinstance(c, dict):
            continue
        ctype = str(c.get("component_type") or "").upper()
        if not (ctype.startswith("STRUCTURE") or ctype.startswith("TOWER")):
            continue
        params = c.get("params") if isinstance(c.get("params"), dict) else {}
        typology = str(
            params.get("typology_id")
            or params.get("typology")
            or params.get("structural_typology")
            or ""
        ).lower()
        feat_blob = " ".join(str(f) for f in (c.get("features") or []) if f).lower()
        if any(
            tok in typology or tok in feat_blob
            for tok in ("dense_eaves_pagoda", "tibetan_stupa", "typology:dense_eaves", "typology:tibetan")
        ):
            return True
        if "pagoda" in typology or "stupa" in typology:
            return True
    return False


_TOWER_TYPOLOGY_TOKENS = (
    "dense_eaves_pagoda", "tibetan_stupa", "pagoda", "stupa",
    "gothic_bell_tower", "bell_tower", "clock_tower", "clocktower",
    "watchtower", "keep_tower", "tower",
)


def _structure_tower_typology(plan: Dict[str, Any]) -> bool:
    """True for STRUCTURE/TOWER bound to a tower/pagoda/bell typology (not residential MASS)."""
    comps = plan.get("components") or []
    if not isinstance(comps, list):
        return False
    for c in comps:
        if not isinstance(c, dict):
            continue
        ctype = str(c.get("component_type") or "").upper()
        if not (ctype.startswith("STRUCTURE") or ctype.startswith("TOWER")):
            continue
        params = c.get("params") if isinstance(c.get("params"), dict) else {}
        typology = str(
            params.get("typology_id")
            or params.get("typology")
            or params.get("structural_typology")
            or ""
        ).lower()
        feat_blob = " ".join(str(f) for f in (c.get("features") or []) if f).lower()
        blob = f"{typology} {feat_blob}"
        if any(tok in blob for tok in _TOWER_TYPOLOGY_TOKENS):
            return True
        if any(
            tok in blob
            for tok in ("layered_tower", "stacked_masses", "vertical_stack", "tower_shaft", "spire")
        ):
            return True
        # Tall narrow STRUCTURE without a house kit is tower-like.
        dims = c.get("dimensions") if isinstance(c.get("dimensions"), dict) else {}
        try:
            w = int(dims.get("width") or 0)
            d = int(dims.get("depth") or 0)
            h = int(dims.get("height") or 0)
        except (TypeError, ValueError):
            w = d = h = 0
        footprint = max(w, d)
        if footprint > 0 and h >= max(20, footprint * 2):
            return True
    return False


def _stacked_tower_masses(plan: Dict[str, Any]) -> bool:
    """Accept tall stacked MASS kits (gothic clocktower shaft + belfry) as tower morphology."""
    comps = plan.get("components") or []
    if not isinstance(comps, list):
        return False
    tall_masses = 0
    towerish = 0
    for c in comps:
        if not isinstance(c, dict):
            continue
        ctype = str(c.get("component_type") or "").upper()
        if not (ctype.startswith("MASS") or ctype.startswith("TOWER") or ctype.startswith("STRUCTURE")):
            continue
        dims = c.get("dimensions") if isinstance(c.get("dimensions"), dict) else {}
        try:
            w = int(dims.get("width") or 0)
            d = int(dims.get("depth") or 0)
            h = int(dims.get("height") or 0)
        except (TypeError, ValueError):
            w = d = h = 0
        footprint = max(w, d)
        if footprint > 0 and h >= max(16, footprint * 2):
            tall_masses += 1
        feat_blob = " ".join(str(f) for f in (c.get("features") or []) if f).lower()
        id_blob = str((c.get("params") or {}).get("component_id") or "").lower()
        blob = f"{feat_blob} {id_blob}"
        if any(
            tok in blob
            for tok in (
                "tower", "belfry", "spire", "钟楼", "塔楼", "shaft", "pinnacle",
                "cantilever", "gothic_tower", "clock",
            )
        ):
            towerish += 1
    # Shaft + upper stage, or one very tall towerish mass.
    if tall_masses >= 1 and towerish >= 1:
        return True
    if towerish >= 2:
        return True
    return False


def _has_tower_morphology(plan: Dict[str, Any]) -> bool:
    """Tower/pagoda intent needs STRUCTURE/tower typology — residential MASS kits are not towers."""
    types = set(_component_types(plan))
    if _stacked_tower_masses(plan):
        return True
    # Residential kit with windows/doors is never a pagoda, even if LLM tags features "pagoda".
    if {"FACADE_WINDOWS", "ENTRANCE"} <= types and any(t.startswith("MASS") for t in types):
        if not _structure_tower_typology(plan):
            return False
    if _structure_tower_typology(plan):
        return True
    if _has_native_pagoda_typology(plan):
        return True
    comps = plan.get("components") or []
    if not isinstance(comps, list):
        return False
    for c in comps:
        if not isinstance(c, dict):
            continue
        ctype = str(c.get("component_type") or "").upper()
        params = c.get("params") if isinstance(c.get("params"), dict) else {}
        if ctype.startswith("TOWER"):
            return True
        # Explicit taper tiers without a house kit.
        if params.get("tier_index") is not None or (
            isinstance(params.get("eave_tiers"), (int, float)) and int(params.get("eave_tiers")) >= 2
        ):
            if "FACADE_WINDOWS" not in types:
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
        if types & {"GATE", "GATE_STRUCTURE", "STRUCTURE", "ASSEMBLY"}:
            pass
        elif empty or types <= {"MASS_MAIN", "ROOF", "FOUNDATION", "ENTRANCE", "FACADE_WINDOWS"}:
            # Gate may be a wide shallow MASS — require at least non-default proportions or features.
            shallow_ok = False
            for c in comps or []:
                if not isinstance(c, dict):
                    continue
                dims = c.get("dimensions") if isinstance(c.get("dimensions"), dict) else {}
                try:
                    w, d = int(dims.get("width") or 0), int(dims.get("depth") or 0)
                except (TypeError, ValueError):
                    continue
                if w >= d * 1.5 and w >= 10:
                    shallow_ok = True
                    break
                feats = " ".join(str(f) for f in (c.get("features") or []) if f).lower()
                if "gate" in feats or "山门" in feats or "paifang" in feats:
                    shallow_ok = True
                    break
                params = c.get("params") if isinstance(c.get("params"), dict) else {}
                if params.get("passage_axis"):
                    shallow_ok = True
                    break
            if not shallow_ok:
                errors.append(
                    "semantics: temple gate / paifang intent needs a gate-like wide mass "
                    "or STRUCTURE/ASSEMBLY/GATE, not a generic house kit"
                )
    elif intent == "temple":
        if empty:
            errors.append("semantics: temple intent cannot have empty components[]")
        elif _looks_like_default_house(plan) and not _has_temple_hall_morphology(plan):
            types = set(_component_types(plan))
            if not (types & {"PATH", "COURTYARD", "COURTYARD_SPACE", "ASSEMBLY", "GARDEN", "PAVING", "STRUCTURE", "GATE"}):
                errors.append(
                    "semantics: temple/daoist intent needs a hall with Chinese roof "
                    "or site composition, not only a residential gable house kit"
                )
    elif intent == "garden":
        if empty:
            errors.append("semantics: garden intent cannot have empty components[]")
        elif _looks_like_default_house(plan):
            types = set(_component_types(plan))
            if not (types & {"PATH", "COURTYARD", "COURTYARD_SPACE", "ASSEMBLY", "GARDEN", "PAVING", "STRUCTURE"}):
                errors.append(
                    "semantics: garden/courtyard intent needs site composition "
                    "(PATH/COURTYARD/ASSEMBLY/…), not only a residential house kit"
                )
    return errors


def _has_temple_hall_morphology(plan: Dict[str, Any]) -> bool:
    comps = plan.get("components") if isinstance(plan, dict) else None
    if not isinstance(comps, list):
        return False
    chinese_roofs = (
        "xieshan", "wudian", "xuanshan", "yingshan", "hip", "irimoya",
        "歇山", "庑殿", "入母屋",
    )
    for c in comps:
        if not isinstance(c, dict):
            continue
        ctype = str(c.get("component_type") or "").upper()
        if ctype in {"STRUCTURE", "ASSEMBLY", "GATE"}:
            return True
        feat_raw = " ".join(str(f) for f in (c.get("features") or []) if f)
        feats = feat_raw.lower()
        if any(tok in feats or tok in feat_raw
               for tok in ("temple", "daoist", "shrine", "main_hall", "道观", "殿", "庙")):
            return True
        params = c.get("params") if isinstance(c.get("params"), dict) else {}
        roof = str(params.get("roof_type") or params.get("roofType") or "").lower()
        if any(tok in roof for tok in chinese_roofs):
            return True
    return False


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
