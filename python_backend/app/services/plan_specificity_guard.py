"""
Guards against homogenizing open-world plans with generic classical enrichment.

When a BuildingProfile or user intent carries non-classical / distinctive features,
skip pilaster+cornice+gable templates and instead preserve or inject profile tokens.
"""

from __future__ import annotations

import json
import re
from typing import Any, Dict, List, Optional, Tuple

from ..models.building_profile import BuildingProfile

_NON_CLASSICAL_MARKERS = (
    "zaha", "hadid", "gehry", "guggenheim", "deconstruct", "parametric", "organic",
    "freeform", "curvilinear", "curved shell", "shell structure", "tensile",
    "hyperboloid", "mesh facade", "lattice", "steel mesh", "bird's nest", "birds nest",
    "sydney opera", "opera house", "sails", "shell roof", "cantilever", "suspended",
    "解构", "参数化", "曲面", "壳体", "网格", "悬挑", "悉尼歌剧院", "鸟巢",
    "体育场", "stadium", "arena", "bowl", "elliptical",
    "foster", "norman foster", "calatrava", "santiago calatrava",
    "tensile structure", "membrane", "cable-net", "exoskeleton",
)

_CLASSICAL_STYLE_MARKERS = (
    "classical", "neoclassical", "renaissance", "baroque", "beaux", "palladian",
    "gothic cathedral", "roman temple", "parthenon", "pantheon", "capitol",
    "古典", "文艺复兴", "巴洛克", "新古典", "哥特式大教堂",
)

_TOKEN_STOPWORDS = frozenset({
    "the", "and", "with", "for", "building", "structure", "style", "design",
    "建筑", "结构", "风格", "设计", "特点", "元素",
})


def extract_distinguishing_tokens(profile: Optional[BuildingProfile]) -> List[str]:
    """Normalize profile distinguishing/distinctive strings into matchable tokens."""
    if profile is None:
        return []
    struct = profile.structure
    raw: List[str] = []
    raw.extend(struct.distinguishing_features or [])
    raw.extend(struct.distinctive_elements or [])
    raw.extend(struct.facade or [])
    raw.extend(struct.roof_types or [])
    if profile.form.massing:
        raw.extend(profile.form.massing)
    tokens: List[str] = []
    seen: set[str] = set()
    for item in raw:
        if not item or not str(item).strip():
            continue
        text = str(item).strip()
        if len(text) >= 4 and text.lower() not in _TOKEN_STOPWORDS:
            key = text.lower()
            if key not in seen:
                seen.add(key)
                tokens.append(text)
        for part in re.split(r"[,;/|、，；]+", text):
            part = part.strip()
            if len(part) >= 3 and part.lower() not in _TOKEN_STOPWORDS:
                key = part.lower()
                if key not in seen:
                    seen.add(key)
                    tokens.append(part)
    return tokens[:16]


def plan_text_blob(plan: Dict[str, Any]) -> str:
    """Flatten plan fields likely to carry distinctive feature encoding."""
    parts: List[str] = []
    parts.append(str(plan.get("style_profile") or ""))
    df = plan.get("distinguishing_features")
    if isinstance(df, list):
        parts.extend(str(x) for x in df if x)
    sa = plan.get("style_attributes")
    if isinstance(sa, dict):
        parts.append(json.dumps(sa, ensure_ascii=False))
    ph = plan.get("proportion_hints")
    if isinstance(ph, dict):
        parts.append(json.dumps(ph, ensure_ascii=False))
    genome = plan.get("genome")
    if isinstance(genome, dict):
        parts.append(json.dumps(genome, ensure_ascii=False))
    for comp in plan.get("components") or []:
        if not isinstance(comp, dict):
            continue
        parts.append(str(comp.get("component_type") or ""))
        parts.extend(str(f) for f in (comp.get("features") or []) if f)
        params = comp.get("params")
        if isinstance(params, dict):
            parts.append(json.dumps(params, ensure_ascii=False))
    return " ".join(parts).lower()


def _token_matches(blob: str, token: str) -> bool:
    t = token.lower().strip()
    if not t:
        return False
    norm = blob.replace("_", " ")
    if t in norm:
        return True
    words = [w for w in re.split(r"\s+", t) if len(w) >= 3]
    if len(words) >= 2:
        return all(bool(re.search(rf"\b{re.escape(w)}\b", norm)) for w in words)
    if len(words) == 1:
        return bool(re.search(rf"\b{re.escape(words[0])}\b", norm))
    return False


def score_feature_token_coverage(
    plan: Dict[str, Any],
    tokens: List[str],
) -> Tuple[float, List[str], List[str]]:
    if not tokens:
        return 1.0, [], []
    blob = plan_text_blob(plan)
    matched: List[str] = []
    missing: List[str] = []
    for token in tokens:
        if _token_matches(blob, token):
            matched.append(token)
        else:
            missing.append(token)
    score = len(matched) / max(1, len(tokens))
    return score, matched, missing


def should_skip_classical_enrichment(
    user_text: str,
    profile: Optional[BuildingProfile],
    plan: Optional[Dict[str, Any]] = None,
) -> Tuple[bool, str]:
    """
    Return (skip, reason). When True, do not inject generic pilaster/gable/cornice templates.
    """
    blob_parts = [user_text or ""]
    if profile is not None:
        blob_parts.extend([
            profile.query or "",
            profile.identity.name or "",
            profile.identity.style or "",
            profile.research_notes or "",
            " ".join(profile.structure.distinguishing_features or []),
            " ".join(profile.structure.distinctive_elements or []),
            " ".join(profile.form.massing or []),
        ])
    if plan is not None:
        blob_parts.append(plan_text_blob(plan))
    blob = " ".join(blob_parts).lower()

    for marker in _NON_CLASSICAL_MARKERS:
        if marker in blob:
            return True, f"non_classical_marker:{marker}"

    tokens = extract_distinguishing_tokens(profile)
    if tokens:
        non_classical_tokens = sum(
            1 for t in tokens if any(m in t.lower() for m in _NON_CLASSICAL_MARKERS)
        )
        if non_classical_tokens >= 1 and not any(m in blob for m in _CLASSICAL_STYLE_MARKERS):
            return True, "distinguishing_features_non_classical"

    if profile is not None and profile.form.footprint in ("freeform", "circular", "organic"):
        if not any(m in blob for m in _CLASSICAL_STYLE_MARKERS):
            return True, f"footprint:{profile.form.footprint}"

    massing = " ".join(profile.form.massing or []).lower() if profile else ""
    if massing and any(x in massing for x in ("organic", "curved", "freeform", "shell", "mesh")):
        if not any(m in blob for m in _CLASSICAL_STYLE_MARKERS):
            return True, "massing_non_classical"

    return False, ""


def apply_profile_features_to_plan(
    plan: Dict[str, Any],
    profile: Optional[BuildingProfile],
) -> Dict[str, Any]:
    """Deterministically surface distinguishing features into plan fields Java consumes."""
    if profile is None:
        return plan
    tokens = extract_distinguishing_tokens(profile)
    if not tokens:
        return plan

    existing = plan.get("distinguishing_features")
    merged_top: List[str] = []
    if isinstance(existing, list):
        for item in existing:
            if item and str(item).strip() and str(item) not in merged_top:
                merged_top.append(str(item))
    for token in tokens[:8]:
        if token not in merged_top:
            merged_top.append(token)
    plan["distinguishing_features"] = merged_top[:12]

    sa = plan.get("style_attributes")
    if not isinstance(sa, dict):
        sa = {}
        plan["style_attributes"] = sa
    deco = list(sa.get("decorative_elements") or [])
    for token in tokens[:8]:
        if token not in deco:
            deco.append(token)
    sa["decorative_elements"] = deco[:12]

    plan.pop("research_feature_hints", None)

    for comp in plan.get("components") or []:
        if not isinstance(comp, dict):
            continue
        ctype = str(comp.get("component_type") or "").upper()
        if ctype not in ("MASS_MAIN", "MASS_SECONDARY", "ROOF", "STRUCTURE", "CROWN"):
            continue
        feats = list(comp.get("features") or [])
        for token in tokens[:6]:
            slug = re.sub(r"[^a-z0-9_]+", "_", token.lower()).strip("_")
            if slug and slug not in [str(f).lower() for f in feats]:
                feats.append(slug)
        comp["features"] = feats

        params = comp.get("params")
        if not isinstance(params, dict):
            params = {}
            comp["params"] = params
        if tokens and not params.get("distinctive_features"):
            params["distinctive_features"] = tokens[:8]

    return plan
