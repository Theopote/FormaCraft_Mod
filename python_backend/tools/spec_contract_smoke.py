"""
Offline smoke tests for FormaCraft spec contract normalization.

Goal:
- Validate that LLM output drift does NOT break style genes.
- Ensure invalid styleProfileId/paletteId are dropped with debugWarnings.
- Ensure paletteId is auto-filled from style default palette when possible.

Run:
  python python_backend/tools/spec_contract_smoke.py
"""

from __future__ import annotations

import json
import sys
from pathlib import Path
from typing import Any, Dict, List, Optional

# Allow running from repo root: make `python_backend/` discoverable so `import app...` works.
_PY_BACKEND_ROOT = Path(__file__).resolve().parents[1]
if str(_PY_BACKEND_ROOT) not in sys.path:
    sys.path.insert(0, str(_PY_BACKEND_ROOT))


def _pick_any_style_with_default_palette() -> Optional[str]:
    from app.services.style_profile_registry import list_style_profiles, default_palette_for_style, has_palette

    for d in list_style_profiles():
        sid = getattr(d, "style_id", None)
        if not sid:
            continue
        pid = default_palette_for_style(sid)
        if pid and has_palette(pid):
            return sid
    return None


def _normalize(data: Dict[str, Any]) -> Dict[str, Any]:
    # We intentionally call the internal normalizer used by /build.
    from app.services.ai_planner import _normalize_building_spec_dict  # type: ignore

    out = _normalize_building_spec_dict(json.loads(json.dumps(data)))  # deep copy via json
    assert isinstance(out, dict)
    return out


def _get_extra(d: Dict[str, Any]) -> Dict[str, Any]:
    x = d.get("extra")
    return x if isinstance(x, dict) else {}


def _assert(cond: bool, msg: str, errors: List[str]) -> None:
    if not cond:
        errors.append(msg)


def main() -> int:
    errors: List[str] = []

    style_id = _pick_any_style_with_default_palette()
    _assert(style_id is not None, "No style profile with a valid defaultPalette found (catalog/palettes mismatch?)", errors)

    # Case 1: styleProfileId set, paletteId missing -> should auto-fill paletteId
    if style_id:
        data1 = {
            "type": "HOUSE",
            "style": "DEFAULT",
            "footprint": {"shape": "rectangle", "width": 8, "depth": 6},
            "height": 10,
            "materials": {},
            "features": {},
            "extra": {"styleProfileId": style_id},
        }
        n1 = _normalize(data1)
        e1 = _get_extra(n1)
        _assert(e1.get("styleProfileId") == style_id, "Case1: styleProfileId should be kept", errors)
        _assert(isinstance(e1.get("paletteId"), str) and e1.get("paletteId"), "Case1: paletteId should be auto-filled", errors)
        _assert(e1.get("paletteIdAutoFromStyle") is True, "Case1: paletteIdAutoFromStyle should be true", errors)

    # Case 2: invalid ids -> should be dropped + debugWarnings present
    data2 = {
        "type": "HOUSE",
        "style": "DEFAULT",
        "footprint": {"shape": "rectangle", "width": 8, "depth": 6},
        "height": 10,
        "materials": {},
        "features": {},
        "extra": {"styleProfileId": "STYLE_DOES_NOT_EXIST", "paletteId": "PALETTE_DOES_NOT_EXIST"},
    }
    n2 = _normalize(data2)
    e2 = _get_extra(n2)
    _assert("styleProfileId" not in e2, "Case2: invalid styleProfileId should be dropped", errors)
    _assert("paletteId" not in e2, "Case2: invalid paletteId should be dropped", errors)
    dw2 = e2.get("debugWarnings")
    _assert(isinstance(dw2, list) and len(dw2) >= 1, "Case2: debugWarnings should be a non-empty list", errors)

    # Case 3: alias keys at top-level should be promoted into extra
    data3 = {
        "type": "HOUSE",
        "style": "DEFAULT",
        "footprint": {"shape": "rectangle", "width": 8, "depth": 6},
        "height": 10,
        "materials": {},
        "features": {},
        "style_profile_id": (style_id or "STYLE_DOES_NOT_EXIST"),
        "palette_id": "PALETTE_DOES_NOT_EXIST",
    }
    n3 = _normalize(data3)
    e3 = _get_extra(n3)
    _assert("styleProfileId" in e3 or style_id is None, "Case3: style_profile_id should promote to extra.styleProfileId", errors)
    # palette_id is invalid in our case, so it should be warned and then paletteId may be auto-filled from style default.
    if style_id:
        _assert(e3.get("styleProfileId") == style_id, "Case3: promoted styleProfileId should match", errors)
    _assert(e3.get("paletteId") != "PALETTE_DOES_NOT_EXIST", "Case3: invalid palette_id should not survive as paletteId", errors)
    dw3 = e3.get("debugWarnings")
    _assert(isinstance(dw3, list) and len(dw3) >= 1, "Case3: debugWarnings should exist", errors)

    # Case 4: layout IR normalization
    data4 = {
        "type": "HOUSE",
        "style": "DEFAULT",
        "footprint": {"shape": "rectangle", "width": 8, "depth": 6},
        "height": 10,
        "materials": {},
        "features": {},
        "extra": {
            "layout": {
                "entranceFacing": "north",
                "symmetry": "x",
                "courtyard": "true",
                "courtyardRatio": "0.45",
            }
        },
    }
    n4 = _normalize(data4)
    e4 = _get_extra(n4)
    l4 = e4.get("layout")
    _assert(isinstance(l4, dict), "Case4: extra.layout should be a dict", errors)
    if isinstance(l4, dict):
        _assert(l4.get("entranceFacing") == "NORTH", "Case4: entranceFacing should normalize to NORTH", errors)
        _assert(l4.get("symmetry") == "X", "Case4: symmetry should normalize to X", errors)
        _assert(l4.get("courtyard") is True, "Case4: courtyard should normalize to True", errors)
        _assert(abs(float(l4.get("courtyardRatio")) - 0.45) < 1e-6, "Case4: courtyardRatio should normalize to 0.45", errors)

    if errors:
        print("FAIL")
        for e in errors:
            print(" - " + e)
        return 1

    print("PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())


