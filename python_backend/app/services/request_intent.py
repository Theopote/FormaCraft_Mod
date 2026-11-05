"""Request intent: keyword hints + LLM declared_intent merge (shared vocab).

Keywords are auxiliary. Strong lexical hits (佛塔/山门/…) can override a weak LLM
declaration; ambiguous tokens (重檐/庭院) never alone force a specialized class.
Negation (不要/无/no) strips positive matches and forbids.
"""

from __future__ import annotations

import hashlib
import re
from typing import Any, Dict, List, Optional, Tuple

from .request_intent_vocab import load_request_intent_vocab

_FOOTPRINT = re.compile(r"(\d{1,2})\s*[×xX*]\s*(\d{1,2})")
_VALID_CLASSES = frozenset({"generic", "tower_like", "gate", "garden", "temple"})


def design_seed_for_text(text: str) -> int:
    """Stable non-zero seed so identical prompts stay stable but different prompts diverge."""
    digest = hashlib.sha1((text or "").encode("utf-8")).hexdigest()
    return (int(digest[:8], 16) % 2_147_483_647) or 1


def style_materials_from_text(text: str) -> Dict[str, str]:
    """Infer wall/roof blocks from style cues when the user did not name materials."""
    t = text or ""
    lower = t.lower()
    if any(k in t or k in lower for k in ("现代", "modern", "玻璃", "glass", "concrete", "混凝土")):
        return {
            "wall": "minecraft:white_concrete",
            "roof": "minecraft:light_gray_concrete",
        }
    if any(k in t or k in lower for k in ("印度", "india", "indian", "sandstone", "砂岩")):
        return {
            "wall": "minecraft:smooth_sandstone",
            "roof": "minecraft:cut_sandstone",
        }
    if any(k in t or k in lower for k in ("藏式", "tibetan", "白塔")):
        return {
            "wall": "minecraft:white_concrete",
            "roof": "minecraft:gold_block",
        }
    if any(k in t or k in lower for k in ("石砖", "stone brick", "brick", "砖")):
        return {
            "wall": "minecraft:stone_bricks",
            "roof": "minecraft:dark_oak_stairs",
        }
    return {}


def _vocab() -> Dict[str, Any]:
    return load_request_intent_vocab()


def _is_negated_at(text: str, start: int) -> bool:
    """True when a negation prefix sits immediately before the match.

    Only the suffix of the preceding window counts — otherwise
    「无窗的中式古塔」would treat 古塔 as negated because 无 appears earlier.
    """
    if start <= 0:
        return False
    window = text[max(0, start - 16) : start]
    compact = re.sub(r"\s+", "", window)
    lower = window.lower()
    # Chinese / CJK: negation must be the immediate (optional particle) suffix.
    if re.search(
        r"(?:不要|别要|无需|不用|不采用|不使用|禁止|避免|无)(?:的|了|着|过)?$",
        compact,
    ):
        return True
    # ASCII: "no "/"without "/"not " must end the preceding window.
    if re.search(
        r"(?:(?:do\s+)?not|don't|dont|no|without)\s+$",
        lower,
        flags=re.IGNORECASE,
    ):
        return True
    return False


def _find_positive_hits(text: str, markers: List[str]) -> List[str]:
    """Return markers that appear in text and are not negated."""
    if not text or not markers:
        return []
    lower = text.lower()
    hits: List[str] = []
    # Longer markers first so 藏式佛塔 wins over 佛塔, 重檐歇山 over 重檐.
    ordered = sorted({str(m) for m in markers if m}, key=lambda m: (-len(m), m))
    occupied: List[Tuple[int, int]] = []
    for marker in ordered:
        needle = marker
        needle_l = marker.lower()
        search_from = 0
        while True:
            if needle.isascii() or needle == needle_l:
                idx = lower.find(needle_l, search_from)
                match_len = len(needle_l)
            else:
                idx = text.find(needle, search_from)
                match_len = len(needle)
                if idx < 0:
                    idx = lower.find(needle_l, search_from)
                    match_len = len(needle_l)
            if idx < 0:
                break
            end = idx + match_len
            if any(not (end <= a or idx >= b) for a, b in occupied):
                search_from = end
                continue
            if _is_negated_at(text, idx):
                search_from = end
                continue
            hits.append(marker)
            occupied.append((idx, end))
            break
    return hits


def _contains_any_raw(text: str, phrases: List[str]) -> bool:
    lower = text.lower()
    for p in phrases:
        if not p:
            continue
        if p.lower() in lower or p in text:
            return True
    return False


def _score_building_class(text: str) -> Tuple[str, float, List[str]]:
    """Return (building_class, confidence, markers) from keywords only."""
    classes = _vocab().get("building_classes") or {}
    best_class = "generic"
    best_score = 0.0
    best_markers: List[str] = []
    conf_cfg = _vocab().get("confidence") or {}
    strong_w = float(conf_cfg.get("strong_hit") or 0.9)
    weak_w = float(conf_cfg.get("weak_hit") or 0.45)

    # Priority order when scores tie: tower > gate > temple > garden
    # (tower/gate are more specific; garden weak markers are noisy).
    priority = ("tower_like", "gate", "temple", "garden")
    for cls in priority:
        spec = classes.get(cls) if isinstance(classes, dict) else None
        if not isinstance(spec, dict):
            continue
        strong = list(spec.get("strong_markers") or [])
        weak = list(spec.get("weak_markers") or [])
        strong_hits = _find_positive_hits(text, strong)
        weak_hits = _find_positive_hits(text, weak)
        if weak_hits:
            suppressors = list(spec.get("weak_suppressors") or [])
            if suppressors and _contains_any_raw(text, suppressors):
                weak_hits = []
        if not strong_hits and not weak_hits:
            continue
        score = strong_w if strong_hits else weak_w
        markers = strong_hits + weak_hits
        if score > best_score:
            best_score = score
            best_class = cls
            best_markers = markers
    return best_class, best_score, best_markers[:12]


def _extract_roof(text: str) -> Tuple[Optional[str], List[str]]:
    """Return (roof_type or None, forbidden_roof_types). Honours negation."""
    roofs = _vocab().get("roof_types") or []
    lower = text.lower()
    # Longer phrases first.
    ordered = sorted(
        [r for r in roofs if isinstance(r, dict) and r.get("phrase")],
        key=lambda r: -len(str(r["phrase"])),
    )
    chosen: Optional[str] = None
    forbidden: List[str] = []
    occupied: List[Tuple[int, int]] = []
    for entry in ordered:
        phrase = str(entry["phrase"])
        canonical = str(entry.get("canonical") or "").strip().lower()
        if not canonical:
            continue
        phrase_l = phrase.lower()
        search_from = 0
        while True:
            if phrase.isascii() or phrase == phrase_l:
                idx = lower.find(phrase_l, search_from)
                match_len = len(phrase_l)
            else:
                idx = text.find(phrase, search_from)
                match_len = len(phrase)
                if idx < 0:
                    idx = lower.find(phrase_l, search_from)
                    match_len = len(phrase_l)
            if idx < 0:
                break
            end = idx + match_len
            if any(not (end <= a or idx >= b) for a, b in occupied):
                search_from = end
                continue
            occupied.append((idx, end))
            if _is_negated_at(text, idx):
                if canonical not in forbidden:
                    forbidden.append(canonical)
            elif chosen is None:
                chosen = canonical
            break
    return chosen, forbidden


def _extract_forbids(text: str) -> List[str]:
    forbids_cfg = _vocab().get("forbids") or {}
    false_friends = _vocab().get("forbid_false_friends") or {}
    out: List[str] = []
    lower = text.lower()
    for key, phrases in forbids_cfg.items():
        friends = [str(f) for f in (false_friends.get(key) or []) if f]
        friend_spans: List[Tuple[int, int]] = []
        for friend in friends:
            fl = friend.lower()
            start = 0
            while True:
                idx = lower.find(fl, start) if friend.isascii() else text.find(friend, start)
                if idx < 0 and not friend.isascii():
                    idx = lower.find(fl, start)
                if idx < 0:
                    break
                friend_spans.append((idx, idx + len(friend if not friend.isascii() else fl)))
                start = idx + 1

        for phrase in phrases or []:
            p = str(phrase)
            if not p:
                continue
            pl = p.lower()
            start = 0
            matched = False
            while True:
                idx = lower.find(pl, start) if p.isascii() else text.find(p, start)
                if idx < 0 and not p.isascii():
                    idx = lower.find(pl, start)
                if idx < 0:
                    break
                end = idx + len(p if (not p.isascii() and p in text[idx:idx + len(p) + 1]) else pl)
                # Skip matches wholly inside a false-friend span (无门 ⊂ 无门槛).
                if any(idx >= a and end <= b for a, b in friend_spans):
                    start = end
                    continue
                matched = True
                break
            if matched:
                out.append(str(key))
                break
    return out


def _extract_materials(text: str) -> Dict[str, str]:
    materials_cfg = _vocab().get("materials") or {}
    out: Dict[str, str] = {}
    for slot, entries in materials_cfg.items():
        if not isinstance(entries, list):
            continue
        for entry in entries:
            if not isinstance(entry, dict):
                continue
            phrases = [str(p) for p in (entry.get("phrases") or []) if p]
            block = str(entry.get("block") or "").strip()
            if not block or not phrases:
                continue
            if _find_positive_hits(text, phrases) or _contains_any_raw(text, phrases):
                out.setdefault(str(slot), block)
                break
    return out


def _tower_subtype(text: str, building_class: str) -> Optional[str]:
    if building_class != "tower_like":
        return None
    subtypes = _vocab().get("tower_subtypes") or {}
    tibetan = list(subtypes.get("tibetan_stupa") or [])
    if _find_positive_hits(text, tibetan) or _contains_any_raw(text, tibetan):
        return "tibetan_stupa"
    return "dense_eaves_pagoda"


def extract_keyword_intent(user_text: str) -> Dict[str, Any]:
    """Keyword-only snapshot (auxiliary). Prefer resolve_request_intent when a plan exists."""
    text = user_text or ""
    building_class, confidence, markers = _score_building_class(text)
    roof_type, roof_forbids = _extract_roof(text)
    forbids = _extract_forbids(text)
    materials = _extract_materials(text)

    width = depth = 0
    for match in _FOOTPRINT.finditer(text):
        a, b = int(match.group(1)), int(match.group(2))
        if 4 <= a <= 48 and 4 <= b <= 48:
            width, depth = a, b
            break

    composition = "single"
    compound_markers = list(_vocab().get("temple_compound_markers") or [])
    if building_class == "garden":
        composition = "compound"
    elif building_class == "temple" and _find_positive_hits(text, compound_markers):
        composition = "compound"
        markers = markers + [m for m in compound_markers if m in text]

    tower_subtype = _tower_subtype(text, building_class)

    return {
        "schema": "formacraft.request_intent.v1",
        "source": "keyword_hint",
        "original_text": text,
        "building_class": building_class,
        "confidence": round(confidence, 3),
        "composition": composition,
        "tower_subtype": tower_subtype,
        "markers": markers[:12],
        "roof_type": roof_type,
        "roof_explicit": roof_type is not None,
        "roof_forbids": roof_forbids,
        "footprint": {"width": width, "depth": depth} if width and depth else None,
        "forbids": forbids,
        "materials": materials,
        "refuse_residential_autofill": building_class != "generic" and confidence >= 0.75,
        "scaffold_eligible": building_class != "generic" and confidence >= float(
            (_vocab().get("confidence") or {}).get("scaffold_min") or 0.75
        ),
    }


def extract_request_intent(user_text: str) -> Dict[str, Any]:
    """Backward-compatible alias: keyword intent without LLM merge."""
    return extract_keyword_intent(user_text)


def _llm_declared_intent(plan: Optional[Dict[str, Any]]) -> Optional[Dict[str, Any]]:
    if not isinstance(plan, dict):
        return None
    meta = plan.get("plan_meta")
    if not isinstance(meta, dict):
        return None
    for key in ("declared_intent", "llm_intent"):
        raw = meta.get(key)
        if isinstance(raw, dict):
            cls = str(raw.get("building_class") or raw.get("class") or "").strip().lower()
            if cls in _VALID_CLASSES:
                try:
                    conf = float(raw.get("confidence") if raw.get("confidence") is not None else 0.7)
                except (TypeError, ValueError):
                    conf = 0.7
                return {"building_class": cls, "confidence": max(0.0, min(1.0, conf)), "source": "llm_declared"}
    # Soft: building_class written by the LLM before we overwrite.
    cls = str(meta.get("llm_building_class") or "").strip().lower()
    if cls in _VALID_CLASSES and cls != "generic":
        return {"building_class": cls, "confidence": 0.65, "source": "llm_building_class"}
    return None


def merge_request_intent(
    keyword: Dict[str, Any],
    llm: Optional[Dict[str, Any]] = None,
) -> Dict[str, Any]:
    """Merge keyword hints with LLM declared intent. LLM is primary unless keywords are strong."""
    conf_cfg = _vocab().get("confidence") or {}
    kw_override = float(conf_cfg.get("keyword_override_min") or 0.8)
    llm_min = float(conf_cfg.get("llm_preferred_min") or 0.55)

    out = dict(keyword)
    kw_class = str(keyword.get("building_class") or "generic")
    kw_conf = float(keyword.get("confidence") or 0.0)

    if llm and isinstance(llm, dict):
        llm_class = str(llm.get("building_class") or "generic")
        llm_conf = float(llm.get("confidence") or 0.0)
        if kw_conf >= kw_override and kw_class != "generic":
            out["building_class"] = kw_class
            out["confidence"] = kw_conf
            out["source"] = "keyword_override"
            out["llm_declared"] = llm
        elif llm_conf >= llm_min and llm_class in _VALID_CLASSES:
            out["building_class"] = llm_class
            out["confidence"] = max(llm_conf, kw_conf * 0.5)
            out["source"] = "llm_declared"
            out["keyword_hint_class"] = kw_class
            out["keyword_hint_confidence"] = kw_conf
            # Recompute tower subtype / composition for the final class.
            text = str(keyword.get("original_text") or "")
            out["tower_subtype"] = _tower_subtype(text, llm_class)
            if llm_class == "garden":
                out["composition"] = "compound"
            elif llm_class != "temple":
                out["composition"] = "single"
        else:
            out["source"] = "keyword_hint"
            out["llm_declared"] = llm
    else:
        out["source"] = "keyword_hint"

    final_class = str(out.get("building_class") or "generic")
    final_conf = float(out.get("confidence") or 0.0)
    scaffold_min = float(conf_cfg.get("scaffold_min") or 0.75)
    out["refuse_residential_autofill"] = final_class != "generic" and final_conf >= scaffold_min
    # Hard template replace only when KEYWORDS strongly support the class.
    # LLM-only tower guesses (e.g. 台北101 poisoned by chat history) must not scaffold.
    out["scaffold_eligible"] = (
        final_class != "generic"
        and kw_class == final_class
        and kw_conf >= scaffold_min
    )
    out["schema"] = "formacraft.request_intent.v1"
    return out


def resolve_request_intent(user_text: str, plan: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
    """Authoritative intent: merge keyword hints with LLM declared_intent when present."""
    keyword = extract_keyword_intent(user_text or "")
    llm = _llm_declared_intent(plan)
    return merge_request_intent(keyword, llm)


def _scaffold_plan_meta(
    intent: Dict[str, Any],
    *,
    building_class: str,
    design_requirements: Optional[Dict[str, str]] = None,
    **extra: Any,
) -> Dict[str, Any]:
    """Strict scaffolds omit residential satellites unless the class needs them."""
    meta: Dict[str, Any] = {
        "request_intent": intent,
        "building_class": building_class,
        "scaffolded_from_request_intent": True,
        "allow_compiler_satellites": False,
    }
    if design_requirements:
        meta["design_requirements"] = dict(design_requirements)
    meta.update(extra)
    return meta


def attach_request_intent(plan: Dict[str, Any], user_text: str) -> Dict[str, Any]:
    """Write plan_meta.request_intent from keyword + LLM declared merge."""
    if not isinstance(plan, dict):
        return plan
    intent = resolve_request_intent(user_text or "", plan)
    meta = plan.get("plan_meta")
    if not isinstance(meta, dict):
        meta = {}
    else:
        meta = dict(meta)
    meta["request_intent"] = intent
    meta["request_intent_text"] = intent.get("original_text") or ""
    meta["building_class"] = intent.get("building_class")
    plan["plan_meta"] = meta

    hints = plan.get("proportion_hints")
    if not isinstance(hints, dict):
        hints = {}
    else:
        hints = dict(hints)
    arch = hints.get("architecture_intent")
    if not isinstance(arch, dict):
        from .architecture_intent import describe_architecture
        arch = describe_architecture(user_text or "")
    else:
        arch = dict(arch)
        if not str(arch.get("original_text") or "").strip():
            arch["original_text"] = user_text or ""
    hints["architecture_intent"] = arch
    plan["proportion_hints"] = hints
    return plan


def scaffold_plan_for_intent(
    intent: Dict[str, Any],
    *,
    anchor: Optional[Dict[str, int]] = None,
    base_plan: Optional[Dict[str, Any]] = None,
) -> Optional[Dict[str, Any]]:
    """Deterministic typology-aware scaffold when LLM plan fails semantic checks.

    ``anchor`` must be the player/world build origin — never silent world (0,64,0).
    When ``base_plan`` is provided, preserve style_profile / materials / dimensions
    from the LLM attempt so scaffolds are not identical clones.
    """
    # Default False: missing flag must not enable hard replace.
    if intent.get("scaffold_eligible") is not True:
        return None

    building_class = str(intent.get("building_class") or "generic")
    if building_class == "generic":
        return None

    text = str(intent.get("original_text") or "")
    fp = intent.get("footprint") if isinstance(intent.get("footprint"), dict) else {}
    width = int(fp.get("width") or 0) or 7
    depth = int(fp.get("depth") or 0) or width
    materials = dict(intent.get("materials") if isinstance(intent.get("materials"), dict) else {})
    # Prefer explicit user materials, then LLM plan materials, then style cues.
    if base_plan and isinstance(base_plan, dict):
        sa = base_plan.get("style_attributes") if isinstance(base_plan.get("style_attributes"), dict) else {}
        if sa.get("wall_material") and "wall" not in materials:
            materials["wall"] = str(sa["wall_material"])
        if sa.get("roof_material") and "roof" not in materials:
            materials["roof"] = str(sa["roof_material"])
        for comp in base_plan.get("components") or []:
            if not isinstance(comp, dict):
                continue
            params = comp.get("params") if isinstance(comp.get("params"), dict) else {}
            dims = comp.get("dimensions") if isinstance(comp.get("dimensions"), dict) else {}
            if params.get("wall_block") and "wall" not in materials:
                materials["wall"] = str(params["wall_block"])
            if params.get("roof_block") and "roof" not in materials:
                materials["roof"] = str(params["roof_block"])
            try:
                w = int(dims.get("width") or 0)
                d = int(dims.get("depth") or 0)
                if w >= 5 and d >= 5 and not (fp.get("width") if isinstance(fp, dict) else None):
                    width, depth = w, d
            except (TypeError, ValueError):
                pass
            # Preserve LLM floor_count / levels so scaffolds are not always 5-tier clones.
            for key in ("floor_count", "floorCount", "levels"):
                if key in params and "floor_count" not in materials:
                    try:
                        materials["_floor_count"] = max(3, min(15, int(params[key])))
                    except (TypeError, ValueError):
                        pass
                    break
    style_mats = style_materials_from_text(text)
    wall = materials.get("wall") or style_mats.get("wall") or "minecraft:stone_bricks"
    roof = materials.get("roof") or style_mats.get("roof") or "minecraft:dark_oak_stairs"
    # Prefer explicit player/world anchor. If omitted, leave unset so normalize
    # injects req.player.pos — never invent silent world (0,64,0).
    origin: Optional[Dict[str, int]] = None
    if isinstance(anchor, dict) and all(k in anchor for k in ("x", "y", "z")):
        try:
            origin = {
                "x": int(anchor["x"]),
                "y": int(anchor["y"]),
                "z": int(anchor["z"]),
            }
        except (TypeError, ValueError):
            origin = None
    style_profile = "DEFAULT"
    if base_plan and isinstance(base_plan, dict) and str(base_plan.get("style_profile") or "").strip():
        style_profile = str(base_plan.get("style_profile")).strip()
    elif any(k in text or k in text.lower() for k in ("现代", "modern")):
        style_profile = "Modern_International"
    seed = design_seed_for_text(text)
    style_attrs = {
        "wall_material": wall,
        "roof_material": roof,
        "palette": {"wall": wall, "roof": roof},
    }
    forbids = list(intent.get("forbids") or [])
    markers = list(intent.get("markers") or [])
    roof_forbids = list(intent.get("roof_forbids") or [])

    def _roof_or_default(default: str) -> str:
        rt = intent.get("roof_type") or default
        if rt in roof_forbids:
            return default if default not in roof_forbids else "hip"
        return str(rt)

    def _with_anchor(plan: Dict[str, Any]) -> Dict[str, Any]:
        if origin is not None:
            plan["anchor"] = origin
        return plan

    if building_class == "tower_like":
        height = max(18, width * 3)
        lower_text = text.lower()
        tibetan = (intent.get("tower_subtype") == "tibetan_stupa") or any(
            tok in text or tok in lower_text
            for tok in ("藏式", "白塔", "覆钵", "相轮", "喇嘛塔", "tibetan", "chorten")
        )
        if tibetan:
            chhatra = 3 + (seed % 5)  # 3..7
            return _with_anchor({
                "mode": "build",
                "style_profile": style_profile,
                "distinguishing_features": markers or ["藏式佛塔", "tibetan_stupa"],
                "style_attributes": style_attrs,
                "components": [
                    {
                        "component_type": "STRUCTURE",
                        "slot_id": "tower",
                        "relative_position": {"x": 0, "y": 0, "z": 0},
                        "dimensions": {"width": width, "depth": depth, "height": height},
                        "features": ["tibetan_stupa", "覆钵", "相轮", "塔刹", "typology:tibetan_stupa"],
                        "params": {
                            "typology_id": "tibetan_stupa",
                            "typology": "tibetan_stupa",
                            "baseWidth": max(7, width if width % 2 == 1 else width + 1),
                            "height": height,
                            "chhatra_count": chhatra,
                            "designSeed": seed,
                            "design_seed": seed,
                            "wall_block": wall if wall else "minecraft:white_concrete",
                            "bodyBlock": wall if wall else "minecraft:white_concrete",
                            **({"window_style": "none", "window_ratio": 0} if "windows" in forbids else {}),
                        },
                    }
                ],
                "plan_meta": _scaffold_plan_meta(
                    intent,
                    building_class="tower_like",
                    design_requirements={"facade": "OMITTED", "entrance": "OMITTED", "roof": "OMITTED"},
                    tower_subtype="tibetan_stupa",
                ),
            })

        floors = 5
        m_floors = re.search(r"([三四五六七八九两\d]{1,2})\s*层", text)
        if m_floors:
            token = m_floors.group(1)
            cn = {"三": 3, "四": 4, "五": 5, "六": 6, "七": 7, "八": 8, "九": 9, "两": 2}
            if token in cn:
                floors = cn[token]
            elif token.isdigit():
                floors = max(3, min(15, int(token)))
        elif "3层" in text:
            floors = 3
        elif materials.get("_floor_count"):
            floors = int(materials["_floor_count"])
        else:
            floors = 3 + (seed % 5)  # 3..7 when unspecified — avoid always-5 clones
        eaves_per_level = 2 if (
            intent.get("roof_type") in ("double_eave_hip", "double_eave_xieshan")
            or ("重檐" in text and "重檐" not in roof_forbids and "double_eave_hip" not in roof_forbids)
            or "双檐" in text
            or "密檐" in text
        ) else 1
        footprint = "square" if (seed % 2 == 0) else "octagon"
        height = max(18, floors * 5 + (seed % 4))
        return _with_anchor({
            "mode": "build",
            "style_profile": style_profile,
            "distinguishing_features": markers or ["pagoda", "dense_eaves_pagoda"],
            "style_attributes": style_attrs,
            "components": [
                {
                    "component_type": "STRUCTURE",
                    "slot_id": "tower",
                    "relative_position": {"x": 0, "y": 0, "z": 0},
                    "dimensions": {"width": width, "depth": depth, "height": height},
                    "features": ["dense_eaves_pagoda", "finial_spire", "塔刹", "typology:dense_eaves_pagoda"]
                    + (["重檐"] if eaves_per_level > 1 else []),
                    "params": {
                        "typology_id": "dense_eaves_pagoda",
                        "typology": "dense_eaves_pagoda",
                        "floor_count": floors,
                        "levels": floors,
                        "eaves_per_level": eaves_per_level,
                        "double_eaves": eaves_per_level > 1,
                        "footprint": footprint,
                        "designSeed": seed,
                        "design_seed": seed,
                        "wall_block": wall,
                        "roof_block": roof,
                        **({"window_style": "none", "window_ratio": 0} if "windows" in forbids else {}),
                    },
                }
            ],
            "plan_meta": _scaffold_plan_meta(
                intent,
                building_class="tower_like",
                design_requirements={"facade": "OMITTED", "entrance": "OMITTED", "roof": "OMITTED"},
                tower_subtype="dense_eaves_pagoda",
            ),
        })

    if building_class == "gate":
        w = max(12, width if width >= depth else width * 2)
        d = max(4, min(depth if depth > 0 else w // 3, w // 2))
        h = max(8, d * 2)
        roof_type = _roof_or_default("xieshan")
        return _with_anchor({
            "mode": "build",
            "style_profile": style_profile,
            "distinguishing_features": markers or ["山门"],
            "style_attributes": style_attrs,
            "components": [
                {
                    "component_type": "GATE",
                    "slot_id": "gate",
                    "relative_position": {"x": 0, "y": 0, "z": 0},
                    "dimensions": {"width": w, "depth": d, "height": h},
                    "features": ["temple_gate", "山门", "paifang"],
                    "params": {
                        "component_id": "gate_main",
                        "wall_block": wall,
                        "roof_block": roof,
                        "roof_type": roof_type,
                        "passage_axis": "z",
                    },
                },
                {
                    "component_type": "ROOF",
                    "slot_id": "gate",
                    "relative_position": {"x": 0, "y": h, "z": 0},
                    "dimensions": {"width": w + 2, "depth": d + 2, "height": max(3, d)},
                    "features": ["gate_roof"],
                    "params": {
                        "component_id": "gate_roof",
                        "host_id": "gate_main",
                        "roof_type": roof_type,
                        "roof_block": roof,
                    },
                },
            ],
            "plan_meta": _scaffold_plan_meta(
                intent,
                building_class="gate",
                design_requirements={"facade": "OMITTED", "entrance": "OMITTED"},
            ),
        })

    if building_class == "temple":
        composition = str(intent.get("composition") or "single")
        roof_type = _roof_or_default("xieshan")
        if composition == "compound":
            site_w = max(16, width * 2 if width else 20)
            site_d = max(16, depth * 2 if depth else 20)
            hall_w = max(7, site_w // 3)
            hall_d = max(5, site_d // 4)
            comps: List[Dict[str, Any]] = [
                {
                    "component_type": "COURTYARD",
                    "slot_id": "yard",
                    "relative_position": {"x": 2, "y": 0, "z": 2},
                    "dimensions": {"width": site_w - 4, "depth": site_d - 4, "height": 1},
                    "features": ["courtyard"],
                    "params": {"component_id": "temple_yard"},
                },
                {
                    "component_type": "PATH",
                    "slot_id": "path",
                    "relative_position": {"x": site_w // 2, "y": 0, "z": 0},
                    "dimensions": {"width": 3, "depth": site_d, "height": 1},
                    "features": ["axis_path"],
                    "params": {"component_id": "temple_path"},
                },
                {
                    "component_type": "MASS_MAIN",
                    "slot_id": "hall",
                    "relative_position": {"x": site_w // 4, "y": 0, "z": site_d // 3},
                    "dimensions": {"width": hall_w, "depth": hall_d, "height": 7},
                    "features": ["main_hall", "temple", "道观"],
                    "params": {
                        "component_id": "temple_hall",
                        "wall_block": wall,
                        "roof_block": roof,
                        "roof_type": roof_type,
                        **({"window_style": "none", "window_ratio": 0} if "windows" in forbids else {}),
                    },
                },
            ]
            return _with_anchor({
                "mode": "build",
                "style_profile": style_profile,
                "distinguishing_features": markers or ["道观", "temple", "compound"],
                "style_attributes": style_attrs,
                "components": comps,
                "plan_meta": _scaffold_plan_meta(
                    intent,
                    building_class="temple",
                    design_requirements={"facade": "OMITTED", "entrance": "OMITTED"},
                    composition="compound",
                ),
            })

        hall_w = max(9, width if width >= 7 else 11)
        hall_d = max(7, depth if depth >= 5 else max(7, hall_w - 2))
        hall_h = 7
        return _with_anchor({
            "mode": "build",
            "style_profile": style_profile,
            "distinguishing_features": markers or ["道观", "temple", "大殿"],
            "style_attributes": style_attrs,
            "components": [
                {
                    "component_type": "MASS_MAIN",
                    "slot_id": "hall",
                    "relative_position": {"x": 0, "y": 0, "z": 0},
                    "dimensions": {"width": hall_w, "depth": hall_d, "height": hall_h},
                    "features": ["main_hall", "temple", "殿"],
                    "params": {
                        "component_id": "temple_hall",
                        "wall_block": wall,
                        "roof_block": roof,
                        "roof_type": roof_type,
                        **({"window_style": "none", "window_ratio": 0} if "windows" in forbids else {}),
                    },
                },
                {
                    "component_type": "ROOF",
                    "slot_id": "hall",
                    "relative_position": {"x": -1, "y": hall_h, "z": -1},
                    "dimensions": {"width": hall_w + 2, "depth": hall_d + 2, "height": 4},
                    "features": ["temple_roof"],
                    "params": {
                        "component_id": "temple_roof",
                        "host_id": "temple_hall",
                        "roof_type": roof_type,
                        "roof_block": roof,
                    },
                },
            ],
            "plan_meta": _scaffold_plan_meta(
                intent,
                building_class="temple",
                design_requirements={"facade": "OMITTED", "entrance": "OMITTED"},
                composition="single",
            ),
        })

    if building_class == "garden":
        site_w = max(16, width * 2)
        site_d = max(16, depth * 2)
        comps = [
            {
                "component_type": "COURTYARD",
                "slot_id": "yard",
                "relative_position": {"x": 2, "y": 0, "z": 2},
                "dimensions": {"width": site_w - 4, "depth": site_d - 4, "height": 1},
                "features": ["courtyard"],
                "params": {},
            },
            {
                "component_type": "PATH",
                "slot_id": "path",
                "relative_position": {"x": site_w // 2, "y": 0, "z": 0},
                "dimensions": {"width": 3, "depth": site_d, "height": 1},
                "features": ["axis_path"],
                "params": {},
            },
            {
                "component_type": "MASS_MAIN",
                "slot_id": "hall",
                "relative_position": {"x": site_w // 4, "y": 0, "z": site_d // 3},
                "dimensions": {"width": max(7, site_w // 3), "depth": max(5, site_d // 4), "height": 6},
                "features": ["main_hall"],
                "params": {
                    "wall_block": wall,
                    "roof_block": roof,
                    "roof_type": _roof_or_default("xieshan"),
                    **({"window_style": "none", "window_ratio": 0} if "windows" in forbids else {}),
                },
            },
        ]
        return _with_anchor({
            "mode": "build",
            "style_profile": style_profile,
            "distinguishing_features": markers or [building_class],
            "style_attributes": style_attrs,
            "components": comps,
            "plan_meta": _scaffold_plan_meta(
                intent,
                building_class=building_class,
                design_requirements={"facade": "OMITTED", "entrance": "OMITTED", "roof": "OMITTED"},
            ),
        })

    return None


def morph_failed_plan_toward_intent(
    plan: Dict[str, Any],
    intent: Dict[str, Any],
    *,
    anchor: Optional[Dict[str, int]] = None,
) -> Optional[Dict[str, Any]]:
    """Soft repair: keep LLM style/materials/dims, rewrite morphology to match intent.

    Prefer this over hard scaffold so AI planning still influences the result.
    """
    if not isinstance(plan, dict):
        return None
    building_class = str(intent.get("building_class") or "generic")
    if building_class != "tower_like":
        return None
    # Reuse scaffold with base_plan preservation — only when keywords allow.
    if intent.get("scaffold_eligible") is not True:
        return None
    return scaffold_plan_for_intent(intent, anchor=anchor, base_plan=plan)
