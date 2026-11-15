"""Normalize model output for the frozen BuildingSpec/CompositeSpec routes."""

from typing import Any, Dict, Optional


def _normalize_building_spec_dict(data: Any) -> Any:
    """
    Best-effort normalization for common model output mistakes:
    - buildingType -> type
    - buildingStyle -> style
    - missing required objects: materials/features -> {}
    This prevents hard 502s for minor schema drift.
    """
    if not isinstance(data, dict):
        return data

    # common key aliases
    if "type" not in data and "buildingType" in data:
        data["type"] = data.get("buildingType")
    if "style" not in data and "buildingStyle" in data:
        data["style"] = data.get("buildingStyle")

    # required nested objects (BuildingSpec requires them but nested models have defaults)
    if "materials" not in data or data.get("materials") is None:
        data["materials"] = {}
    if "features" not in data or data.get("features") is None:
        data["features"] = {}

    # footprint is required; provide a minimal default if missing
    if "footprint" not in data or data.get("footprint") is None:
        data["footprint"] = {"shape": "rectangle", "width": 8, "depth": 6}

    # height should exist; default if missing/invalid
    try:
        h = int(data.get("height") if data.get("height") is not None else 0)
        if h <= 0:
            data["height"] = 10
    except Exception:
        data["height"] = 10

    # If still missing type, default to HOUSE so we can proceed.
    if "type" not in data or not data.get("type"):
        data["type"] = "HOUSE"

    # Normalize style gene fields: keep them in extra with stable camelCase keys.
    # (Java side expects extra.styleProfileId / extra.paletteId; aliases are common in LLM output.)
    try:
        extra = data.get("extra")
        if extra is None:
            extra = {}
        if not isinstance(extra, dict):
            extra = {}

        # Promote top-level keys into extra if the model placed them incorrectly.
        for k_top, k_canon in (
            ("styleProfileId", "styleProfileId"),
            ("style_profile_id", "styleProfileId"),
            ("paletteId", "paletteId"),
            ("palette_id", "paletteId"),
        ):
            if k_top in data and data.get(k_top) is not None:
                if k_canon not in extra or extra.get(k_canon) is None:
                    extra[k_canon] = data.get(k_top)

        remapped: Dict[str, Any] = {}
        for k, v in extra.items():
            ks = str(k).strip()
            kl = ks.lower()
            if kl in ("palette_id", "paletteid", "palette"):
                remapped["paletteId"] = v
            elif kl in ("styleprofileid", "style_profile_id", "style_profile", "styleprofile"):
                remapped["styleProfileId"] = v
            else:
                remapped[ks] = v

        # Clean empty values (blank strings / None)
        for kk in ("paletteId", "styleProfileId"):
            if kk in remapped:
                vv = remapped.get(kk)
                if vv is None:
                    remapped.pop(kk, None)
                else:
                    ss = str(vv).strip()
                    if not ss:
                        remapped.pop(kk, None)
                    else:
                        remapped[kk] = ss

        # Validate ids against catalogs (best-effort) and provide deterministic fallback.
        # - If invalid, drop the key and add a debug warning.
        # - If styleProfileId is valid and paletteId is missing, fill paletteId from style default (if it exists & valid).
        try:
            from app.services.style_profile_registry import has_style_profile, has_palette, default_palette_for_style

            debug = remapped.get("debugWarnings")
            if debug is None:
                debug_list = []
            elif isinstance(debug, list):
                debug_list = debug
            else:
                debug_list = [str(debug)]

            spid = remapped.get("styleProfileId")
            if spid is not None and not has_style_profile(spid):
                debug_list.append(f"Invalid extra.styleProfileId='{spid}', dropped.")
                remapped.pop("styleProfileId", None)
                spid = None

            pid = remapped.get("paletteId")
            if pid is not None and not has_palette(pid):
                debug_list.append(f"Invalid extra.paletteId='{pid}', dropped.")
                remapped.pop("paletteId", None)
                pid = None

            if spid is not None and pid is None:
                dp = default_palette_for_style(spid)
                if dp and has_palette(dp):
                    remapped["paletteId"] = dp
                    remapped["paletteIdAutoFromStyle"] = True
                elif dp:
                    debug_list.append(f"Style defaultPalette='{dp}' not found in PaletteCatalog, ignored.")

            if debug_list:
                remapped["debugWarnings"] = debug_list[:20]
        except Exception:
            pass

        # Normalize layout IR into extra.layout
        try:
            layout_in = remapped.get("layout")
            # Promote common top-level or extra-level keys into layout if present
            for k_layout in ("entranceFacing", "symmetry", "plan", "courtyard", "courtyardRatio"):
                if k_layout in remapped and (layout_in is None or not isinstance(layout_in, dict)):
                    layout_in = {}
                if isinstance(layout_in, dict) and k_layout in remapped and k_layout not in layout_in:
                    layout_in[k_layout] = remapped.get(k_layout)

            if layout_in is None and isinstance(data.get("layout"), dict):
                layout_in = data.get("layout")

            if isinstance(layout_in, dict):
                out_layout: Dict[str, Any] = {}
                ef = layout_in.get("entranceFacing") or layout_in.get("entrance_facing") or layout_in.get("entrance")
                if ef is not None:
                    s = str(ef).strip().upper()
                    if s in ("NORTH", "SOUTH", "EAST", "WEST"):
                        out_layout["entranceFacing"] = s

                sym = layout_in.get("symmetry")
                if sym is not None:
                    s = str(sym).strip().upper()
                    if s in ("NONE", "X", "Z", "BOTH"):
                        out_layout["symmetry"] = s

                # plan: a minimal zoning plan that maps to actual generator behavior (best-effort)
                plan = layout_in.get("plan") or layout_in.get("floorPlan") or layout_in.get("floor_plan") or layout_in.get("zoning") or layout_in.get("zoningPlan")
                if plan is not None:
                    p = str(plan).strip().lower()
                    if p in ("none", "no", "false", "0", "off"):
                        out_layout["plan"] = "none"
                    elif p in ("front_back", "frontback", "front-back", "front/back", "前后", "前后分区", "前后布局", "前厅后室"):
                        out_layout["plan"] = "front_back"
                    elif p in ("left_right", "leftright", "left-right", "left/right", "左右", "左右分区", "左右布局"):
                        out_layout["plan"] = "left_right"
                    elif p in ("ring_corridor", "ring", "courtyard_corridor", "courtyard-corridor", "gallery", "cloister",
                               "回廊", "环廊", "环形走廊", "围绕中庭", "回字形", "回字布局", "回字走廊"):
                        out_layout["plan"] = "ring_corridor"

                ct = layout_in.get("courtyard")
                if isinstance(ct, bool):
                    out_layout["courtyard"] = ct
                elif ct is not None:
                    s = str(ct).strip().lower()
                    if s in ("true", "yes", "1"):
                        out_layout["courtyard"] = True
                    elif s in ("false", "no", "0"):
                        out_layout["courtyard"] = False

                cr = layout_in.get("courtyardRatio") or layout_in.get("courtyard_ratio")
                if cr is not None:
                    try:
                        f = float(cr)
                        if f < 0.2:
                            f = 0.2
                        if f > 0.8:
                            f = 0.8
                        out_layout["courtyardRatio"] = f
                    except Exception:
                        pass

                if out_layout:
                    remapped["layout"] = out_layout
        except Exception:
            pass

        data["extra"] = remapped
    except Exception:
        pass

    return data


def _normalize_building_style_value(v: Any) -> Optional[str]:
    if v is None:
        return None
    s = str(v).strip()
    if not s:
        return None
    u = s.upper()
    # valid enum values
    if u in ("MEDIEVAL", "MODERN", "ASIAN", "FUTURISTIC", "RUSTIC", "DEFAULT"):
        return u
    # common LLM drift for Chinese-themed labels
    low = s.lower()
    if "chinese" in low or "ming" in low or "qing" in low or "palace" in low or "asian" in low:
        return "ASIAN"
    if "modern" in low:
        return "MODERN"
    if "medieval" in low:
        return "MEDIEVAL"
    return "DEFAULT"


def _normalize_features_value(v: Any) -> Dict[str, Any]:
    # Features in our schema is an object, but LLMs often output a list like ["windows","doors",...]
    if isinstance(v, dict):
        return v
    flags: Dict[str, Any] = {
        "hasWindows": True,
        "hasStairs": True,
        "hasDoor": True,
        "hasBalcony": False,
        "hasRoof": True,
        "hasRoofDecoration": False,
        "windowCount": 0,
        "floorCount": 1,
    }
    if isinstance(v, list):
        low = {str(x).strip().lower() for x in v if x is not None}
        flags["hasWindows"] = ("windows" in low) or ("window" in low)
        flags["hasDoor"] = ("doors" in low) or ("door" in low) or ("gate" in low) or ("gates" in low)
        flags["hasStairs"] = ("stairs" in low) or ("stair" in low)
        flags["hasRoof"] = ("roof" in low) or ("curved_roof" in low) or ("decorative_eaves" in low)
        flags["hasRoofDecoration"] = ("decorative_cornices" in low) or ("decorative_top" in low) or ("decorative_eaves" in low)
    return flags


def _guess_building_type_from_name(name: str) -> str:
    n = (name or "").strip().lower()
    if not n:
        return "HOUSE"
    if "wall" in n or "enclosure" in n or "courtyard_wall" in n:
        return "WALL"
    if "gate" in n:
        return "HOUSE"
    if "tower" in n:
        return "TOWER"
    if "bridge" in n:
        return "BRIDGE"
    if "hall" in n or "wing" in n:
        return "HOUSE"
    return "HOUSE"


def _normalize_substructure_item(item: Any, idx: int) -> Dict[str, Any]:
    """
    Normalize one element of CompositeSpec.structures.
    Accept either proper {type, spec, offset} or LLM "plan-like" objects.
    """
    if not isinstance(item, dict):
        return {
            "type": "HOUSE",
            "spec": _normalize_building_spec_dict({
                "type": "HOUSE",
                "style": "ASIAN",
                "materials": {},
                "features": {},
                "footprint": {"shape": "rectangle", "width": 8, "depth": 6},
                "height": 6
            }),
            "offset": {"x": idx * 16, "y": 0, "z": 0},
        }

    # offset
    off = item.get("offset") or item.get("pos") or item.get("position") or item.get("relativePos") or item.get("relative_pos")
    if isinstance(off, dict) and all(k in off for k in ("x", "y", "z")):
        offset = {"x": int(off.get("x", 0)), "y": int(off.get("y", 0)), "z": int(off.get("z", 0))}
    else:
        offset = {"x": idx * 16, "y": 0, "z": 0}

    # outer type
    name = str(item.get("name") or "")
    t = item.get("type")
    if t is None or (isinstance(t, str) and not t.strip()):
        t = _guess_building_type_from_name(name)
    t_str = str(t).strip().upper()
    if t_str in ("ASIAN", "MODERN", "MEDIEVAL", "FUTURISTIC", "RUSTIC", "DEFAULT"):
        t_str = "HOUSE"
    # Composite 子结构基本都应该落在已实现的类型；CUSTOM 会在服务端回退导致“塔楼”
    if t_str == "CUSTOM":
        t_str = "HOUSE"

    spec = item.get("spec")
    if isinstance(spec, dict):
        spec = _normalize_building_spec_dict(spec)
        spec["style"] = _normalize_building_style_value(spec.get("style")) or "ASIAN"
        spec["features"] = _normalize_features_value(spec.get("features"))
        if "type" not in spec or not spec.get("type"):
            spec["type"] = t_str
        else:
            st = str(spec.get("type")).strip().upper()
            if st in ("ASIAN", "MODERN", "MEDIEVAL", "FUTURISTIC", "RUSTIC", "DEFAULT"):
                spec["type"] = t_str
            if st == "CUSTOM":
                spec["type"] = t_str
        return {"type": t_str, "spec": spec, "offset": offset}

    # plan-like fallback -> minimal BuildingSpec
    dims = item.get("dimensions") or item.get("dimension") or {}
    if not isinstance(dims, dict):
        dims = {}
    w = int(dims.get("width", 8) or 8)
    d = int(dims.get("depth", 6) or 6)
    h = int(dims.get("height", 6) or 6)
    if w <= 0: w = 8
    if d <= 0: d = 6
    if h <= 0: h = 6

    style = _normalize_building_style_value(item.get("style")) or "ASIAN"
    features = _normalize_features_value(item.get("features"))

    spec_dict: Dict[str, Any] = {
        "type": t_str,
        "style": style,
        "footprint": {"shape": "rectangle", "width": w, "depth": d, "radius": None},
        "height": h,
        "floors": 1,
        "materials": {},
        "features": features,
        "styleOptions": {"roofType": "hipped", "windowRatio": 0.18, "windowStyle": "fence", "wallPattern": "uniform"},
        "notes": "normalized_from_plan",
    }
    spec_dict = _normalize_building_spec_dict(spec_dict)
    spec_dict["style"] = _normalize_building_style_value(spec_dict.get("style")) or "ASIAN"
    spec_dict["features"] = _normalize_features_value(spec_dict.get("features"))

    return {"type": t_str, "spec": spec_dict, "offset": offset}


def _normalize_composite_spec_dict(data: Any) -> Any:
    if not isinstance(data, dict):
        return data

    # tolerate alternative top-level keys
    if "structures" not in data and "buildings" in data:
        data["structures"] = data.get("buildings")
    if "structures" not in data and "components" in data:
        data["structures"] = data.get("components")

    structures = data.get("structures")
    if not isinstance(structures, list):
        structures = []
    data["structures"] = [_normalize_substructure_item(it, i) for i, it in enumerate(structures)]

    # paths is optional
    if "paths" not in data and "roads" in data:
        data["paths"] = data.get("roads")
    if "paths" not in data or data.get("paths") is None:
        data["paths"] = []
    if not isinstance(data["paths"], list):
        data["paths"] = []

    return data
