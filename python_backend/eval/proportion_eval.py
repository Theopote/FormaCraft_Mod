"""Proportion + enclosure eval helpers for golden_eval."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

_ASSETS = None


def _assets_dir() -> Optional[Path]:
    global _ASSETS
    if _ASSETS is not None:
        return _ASSETS
    here = Path(__file__).resolve()
    for p in [here] + list(here.parents):
        cand = p / "src" / "main" / "resources" / "assets" / "formacraft"
        if cand.is_dir():
            _ASSETS = cand
            return cand
    return None


def load_proportion_card(card_id: str) -> Optional[Dict[str, Any]]:
    assets = _assets_dir()
    if not assets:
        return None
    path = assets / "proportion_cards" / f"{card_id}.json"
    if not path.is_file():
        return None
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
        return data if isinstance(data, dict) else None
    except Exception:
        return None


def resolve_proportion_card_id(prompt: Optional[str]) -> Optional[str]:
    if not prompt:
        return None
    q = prompt.lower()
    if any(k in q for k in ("小房子", "cottage", "石头房子", "7x7", "small house")):
        return "cottage_refined"
    if any(k in q for k in ("城堡", "castle", "fortress", "要塞", "medieval castle")):
        return "castle_wall"
    if any(k in q for k in ("体育场", "stadium", "椭圆", "arena")):
        return "stadium_bowl"
    if any(k in q for k in ("四合院", "siheyuan", "带院子", "合院", "courtyard house")):
        return "siheyuan_courtyard"
    if any(k in q for k in ("方塔", "五层", "5层", "square tower", "five-story tower", "顶部平台")):
        return "square_tower_five_story"
    if any(k in q for k in ("天坛", "祈年殿", "temple of heaven", "qiniandian", "祭天")):
        return "temple_of_heaven"
    return None


def _components(plan: Dict[str, Any]) -> List[Dict[str, Any]]:
    comps = plan.get("components")
    return [c for c in comps if isinstance(c, dict)] if isinstance(comps, list) else []


def _main_mass(comps: List[Dict[str, Any]]) -> Optional[Dict[str, Any]]:
    for c in comps:
        t = str(c.get("component_type") or "").upper()
        if t in ("MASS_MAIN", "HOUSE", "BUILDING"):
            return c
    return comps[0] if comps else None


def _dims(comp: Dict[str, Any]) -> Dict[str, Any]:
    d = comp.get("dimensions")
    return d if isinstance(d, dict) else {}


def _params(comp: Dict[str, Any]) -> Dict[str, Any]:
    p = comp.get("params")
    return p if isinstance(p, dict) else {}


def evaluate_proportions(
    plan: Dict[str, Any],
    prompt: Optional[str] = None,
    card_id: Optional[str] = None,
) -> List[Tuple[str, bool, str]]:
    """Returns list of (name, passed, detail) SOFT checks."""
    cid = card_id or resolve_proportion_card_id(prompt)
    if not cid:
        return []
    card = load_proportion_card(cid)
    if not card:
        return []

    checks: List[Tuple[str, bool, str]] = []
    hints = plan.get("proportion_hints")
    hints = hints if isinstance(hints, dict) else {}

    checks.append((
        "has_proportion_hints",
        bool(hints),
        "proportion_hints present" if hints else f"missing proportion_hints for {cid}",
    ))

    og = card.get("openingGrammar") if isinstance(card.get("openingGrammar"), dict) else {}
    max_void = float(og.get("max_void_ratio", 0.35))
    for i, c in enumerate(_components(plan)):
        params = _params(c)
        vr = params.get("void_ratio", params.get("voidRatio", 0))
        if isinstance(vr, (int, float)) and float(vr) > max_void + 0.05:
            checks.append((
                "void_ratio_typology",
                False,
                f"#{i} void_ratio={vr} > max {max_void} for {cid}",
            ))
            break
    else:
        checks.append(("void_ratio_typology", True, "void_ratio within typology"))

    allowed_aspects = og.get("window_aspect")
    if isinstance(allowed_aspects, list) and allowed_aspects:
        for i, c in enumerate(_components(plan)):
            if str(c.get("component_type") or "").upper() != "FACADE_WINDOWS":
                continue
            params = _params(c)
            aspect = params.get("window_aspect", params.get("windowAspect"))
            if aspect is None:
                aspect = hints.get("window_aspect")
            if aspect is None:
                continue
            ok = str(aspect).strip().lower() in {
                str(a).strip().lower() for a in allowed_aspects if a
            }
            checks.append((
                "window_aspect_typology",
                ok,
                f"#{i} window_aspect={aspect} allowed={allowed_aspects}" if ok
                else f"#{i} window_aspect={aspect} not in {allowed_aspects} for {cid}",
            ))
            break
        else:
            checks.append(("window_aspect_typology", True, "no FACADE_WINDOWS to check"))

    ratios = card.get("ratios") if isinstance(card.get("ratios"), dict) else {}
    main = _main_mass(_components(plan))
    if main:
        dims = _dims(main)
        w = int(dims.get("width") or 0)
        h = int(dims.get("height") or 0)
        d = int(dims.get("depth") or 0)
        htw = ratios.get("height_to_width")
        if isinstance(htw, dict) and w > 0 and h > 0:
            ratio = h / w
            mn, mx = float(htw.get("min", 0)), float(htw.get("max", 99))
            ok = mn <= ratio <= mx
            checks.append((
                "derived_height_to_width",
                ok,
                f"h/w={ratio:.3f} range [{mn},{mx}]",
            ))
        dtw = ratios.get("depth_to_width")
        if isinstance(dtw, dict) and w > 0 and d > 0:
            ratio = d / w
            mn, mx = float(dtw.get("min", 0)), float(dtw.get("max", 99))
            ok = mn <= ratio <= mx
            checks.append((
                "derived_depth_to_width",
                ok,
                f"d/w={ratio:.3f} range [{mn},{mx}]",
            ))

    return checks


_ENCLOSURE_TYPES = frozenset({
    "MASS_MAIN", "MASS_SECONDARY", "MASS_WING", "HOUSE", "BUILDING",
    "WALL", "TOWER", "TOWER_BASE", "CASTLE", "KEEP",
})
_ENTRANCE_TYPES = ("DOOR", "ENTRANCE", "GATE", "PORTAL")
_WINDOW_TYPES = ("WINDOW", "FACADE")


def evaluate_enclosure(plan: Dict[str, Any], prompt: Optional[str] = None) -> List[Tuple[str, bool, str]]:
    checks: List[Tuple[str, bool, str]] = []
    comps = _components(plan)
    types = [str(c.get("component_type") or "").upper() for c in comps]

    enclosure = sum(1 for t in types if t in _ENCLOSURE_TYPES or t.startswith("MASS_") or t.startswith("TOWER"))
    for c in comps:
        if str(c.get("component_type") or "").upper() != "MODULE":
            continue
        params = c.get("params") if isinstance(c.get("params"), dict) else {}
        features = c.get("features") if isinstance(c.get("features"), list) else []
        if params.get("module_id") or params.get("moduleId"):
            enclosure += 1
            continue
        if any(isinstance(f, str) and f.lower().startswith("landmark:") for f in features):
            enclosure += 1
    checks.append((
        "has_enclosure_mass",
        enclosure >= 1,
        f"enclosure_components={enclosure}",
    ))

    has_entrance = any(any(tok in t for tok in _ENTRANCE_TYPES) for t in types)
    checks.append((
        "has_entrance_component",
        has_entrance,
        f"types={types}",
    ))

    has_windows = any(any(tok in t for tok in _WINDOW_TYPES) for t in types)
    q = (prompt or "").lower()
    is_castle = any(k in q for k in ("城堡", "castle", "fortress", "要塞"))
    is_cottage = (
        not is_castle
        and any(k in q for k in ("小房子", "cottage", "7x7", "small house", "带门和窗"))
    )
    if is_cottage:
        checks.append((
            "cottage_has_windows",
            has_windows,
            "cottage prompt expects FACADE_WINDOWS or window-bearing facade",
        ))

    if is_castle:
        has_tower_or_wall = any(t.startswith("TOWER") or t == "WALL" for t in types)
        checks.append((
            "castle_has_defensive_shell",
            has_tower_or_wall and enclosure >= 2,
            f"tower/wall + mass expected; types={types}",
        ))

    is_siheyuan = any(
        k in q for k in ("四合院", "siheyuan", "带院子", "合院", "courtyard house")
    )
    if is_siheyuan:
        has_courtyard = any(t in ("COURTYARD", "COURTYARD_SPACE") for t in types)
        checks.append((
            "siheyuan_has_courtyard",
            has_courtyard,
            "siheyuan prompt expects COURTYARD or COURTYARD_SPACE component",
        ))
        wing_count = sum(
            1 for t in types
            if t.startswith("MASS_") or t in ("MASS_MAIN", "HOUSE", "BUILDING")
        )
        checks.append((
            "siheyuan_wing_masses",
            wing_count >= 2,
            f"siheyuan expects ≥2 enclosing masses; found={wing_count}",
        ))

    is_tower = any(
        k in q for k in ("方塔", "五层", "5层", "square tower", "five-story tower", "顶部平台")
    )
    if is_tower:
        has_platform = any(
            t in ("TERRACE", "PLAZA", "TERRACE_PLAZA", "PLAZA_CORE", "BALCONY") for t in types
        )
        checks.append((
            "tower_has_top_platform",
            has_platform,
            "tower prompt expects TERRACE/PLAZA top platform component",
        ))
        main = _main_mass(comps)
        if main:
            dims = _dims(main)
            w = int(dims.get("width") or 0)
            d = int(dims.get("depth") or 0)
            checks.append((
                "tower_square_mass",
                w > 0 and w == d,
                f"square tower footprint width={w} depth={d}",
            ))

    is_temple = any(
        k in q for k in ("天坛", "祈年殿", "temple of heaven", "qiniandian", "祭天")
    )
    if is_temple:
        has_landmark = any(
            str(c.get("component_type") or "").upper() == "MODULE"
            and (
                any(
                    isinstance(f, str) and "temple_of_heaven" in f.lower()
                    for f in (c.get("features") or [])
                )
                or str((c.get("params") or {}).get("module_id", "")).lower() == "temple_of_heaven"
            )
            for c in comps
        )
        checks.append((
            "temple_has_landmark_module",
            has_landmark,
            "temple of heaven prompt expects MODULE landmark:temple_of_heaven",
        ))

    return checks
