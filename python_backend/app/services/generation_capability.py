"""Stage-B generation capability contract shared with Java GenerationCapabilityCatalog."""

from __future__ import annotations

import json
import logging
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

logger = logging.getLogger(__name__)

_SCHEMA = "formacraft.generation_capability.v1"
_CATALOG: Optional[Dict[str, Any]] = None

_FALLBACK_ALIASES = {
    "irimoya": "xieshan",
    "iri_moya": "xieshan",
    "iri-moya": "xieshan",
    "入母屋": "xieshan",
    "入母屋造": "xieshan",
    "hip_and_gable": "xieshan",
    "hip-gable": "xieshan",
    "hip_gable": "xieshan",
    "歇山": "xieshan",
    "庑殿": "hip",
    "wu_dian": "hip",
    "wudian": "hip",
    "四坡": "hip",
    "悬山": "xuanshan",
    "硬山": "yingshan",
    "双坡": "double_gable",
    "gabled": "gable",
    "hipped": "hip",
    "pyramidal": "pyramid",
    "conical": "cone",
    "domed": "dome",
}

_FALLBACK_HOSTABLE = {
    "MASS_MAIN", "TOWER", "MASS_WING", "SIDE_WING", "MASS_ANCILLARY", "MASS_SECONDARY",
    "TERRACE", "COURTYARD", "COURTYARD_SPACE", "GATE", "GATE_STRUCTURE",
    "STRUCTURE", "MODULE", "FOUNDATION", "PLAZA", "PAVING", "PATH", "ROAD", "KEEP",
}

_FALLBACK_HOSTED = {
    "ROOF", "ROOF_STRUCTURE", "FOUNDATION", "FACADE_WINDOWS", "FACADE", "ENTRANCE",
    "ENTRANCE_CANOPY", "CROWN", "CUPOLA", "DOME", "MASS_SECONDARY", "DECOR_DETAIL",
    "BALCONY", "TERRACE", "STRUCTURE", "ASSEMBLY",
}


def _catalog_paths() -> List[Path]:
    here = Path(__file__).resolve()
    root = here.parents[2]  # formacraft-1.21.10
    return [
        here.parent.parent / "data" / "generation_capability_catalog.json",
        root / "src" / "main" / "resources" / "assets" / "formacraft" / "generation_capability_catalog.json",
        root / "build" / "resources" / "main" / "assets" / "formacraft" / "generation_capability_catalog.json",
    ]


def load_catalog(*, force: bool = False) -> Dict[str, Any]:
    global _CATALOG
    if _CATALOG is not None and not force:
        return _CATALOG
    for path in _catalog_paths():
        if not path.is_file():
            continue
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
            if isinstance(data, dict) and data.get("schema") == _SCHEMA:
                _CATALOG = data
                logger.debug("Loaded generation capability catalog from %s", path)
                return _CATALOG
        except (OSError, json.JSONDecodeError):
            logger.warning("Failed reading capability catalog %s", path, exc_info=True)
    _CATALOG = {
        "schema": _SCHEMA,
        "version": 1,
        "hostable_component_types": sorted(_FALLBACK_HOSTABLE),
        "hosted_component_types": sorted(_FALLBACK_HOSTED),
        "roof": {"aliases": dict(_FALLBACK_ALIASES), "supported_tokens": list(_FALLBACK_ALIASES.values()) + ["gable", "hip", "xieshan", "flat", "none"]},
        "typologies": ["dense_eaves_pagoda", "courtyard_compound", "tailiang_timber_hall"],
        "building_classes": {},
    }
    return _CATALOG


def roof_aliases() -> Dict[str, str]:
    roof = load_catalog().get("roof") if isinstance(load_catalog().get("roof"), dict) else {}
    aliases = roof.get("aliases") if isinstance(roof.get("aliases"), dict) else {}
    merged = dict(_FALLBACK_ALIASES)
    for k, v in aliases.items():
        if k and v:
            merged[str(k)] = str(v)
    return merged


def supported_roof_tokens() -> set[str]:
    roof = load_catalog().get("roof") if isinstance(load_catalog().get("roof"), dict) else {}
    tokens = roof.get("supported_tokens") if isinstance(roof.get("supported_tokens"), list) else []
    out = {str(t).strip().lower() for t in tokens if t}
    out.update(roof_aliases().keys())
    out.update(roof_aliases().values())
    out.add("none")
    return out


def hostable_types() -> set[str]:
    raw = load_catalog().get("hostable_component_types")
    if isinstance(raw, list) and raw:
        return {str(t).upper() for t in raw if t}
    return set(_FALLBACK_HOSTABLE)


def hosted_types() -> set[str]:
    raw = load_catalog().get("hosted_component_types")
    if isinstance(raw, list) and raw:
        return {str(t).upper() for t in raw if t}
    return set(_FALLBACK_HOSTED)


def normalize_roof_type(raw: Any) -> Optional[str]:
    if raw is None:
        return None
    text = str(raw).strip()
    if not text:
        return None
    lower = text.lower()
    if lower == "none":
        return "none"
    aliases = roof_aliases()
    mapped = aliases.get(text) or aliases.get(lower) or lower
    return mapped


def is_executable_roof_type(raw: Any) -> bool:
    normalized = normalize_roof_type(raw)
    if normalized is None or normalized == "none":
        return True
    return normalized.lower() in supported_roof_tokens() or normalized in supported_roof_tokens()


def evaluate_capability(plan: Dict[str, Any]) -> List[str]:
    """Static stage-B errors that should be fixed before Java compile."""
    errors: List[str] = []
    if not isinstance(plan, dict):
        return ["capability: plan is not an object"]
    comps = plan.get("components")
    if not isinstance(comps, list):
        return errors
    hostable = hostable_types()
    hosted = hosted_types()
    ids = {}
    for c in comps:
        if not isinstance(c, dict):
            continue
        params = c.get("params") if isinstance(c.get("params"), dict) else {}
        cid = params.get("component_id")
        if cid:
            ids[str(cid)] = c
    for c in comps:
        if not isinstance(c, dict):
            continue
        ctype = str(c.get("component_type") or "").upper()
        params = c.get("params") if isinstance(c.get("params"), dict) else {}
        for key in ("roof_type", "roofType"):
            if key in params and params[key] is not None:
                raw = params[key]
                if not is_executable_roof_type(raw):
                    errors.append(
                        f"capability: unsupported roof_type={raw!r} on {params.get('component_id') or ctype}"
                    )
        if ctype == "STRUCTURE":
            typology = (
                params.get("typology_id")
                or params.get("structural_typology")
                or params.get("typology")
                or params.get("typologyId")
            )
            feats = [str(f) for f in (c.get("features") or []) if f]
            if not typology:
                for f in feats:
                    lower = f.lower()
                    if lower.startswith("typology:"):
                        typology = f.split(":", 1)[1].strip()
                        break
            if not typology:
                joined = " ".join(feats).lower()
                if not any(tok in joined for tok in ("dense_eaves", "pagoda", "stupa", "typology")):
                    errors.append(
                        f"capability: STRUCTURE {params.get('component_id') or ''} lacks typology_id"
                    )
        if ctype in hosted:
            host = params.get("host_id")
            if host and str(host) not in ids:
                errors.append(
                    f"capability: host_id={host!r} unknown for {params.get('component_id') or ctype}"
                )
            elif host and str(host) in ids:
                host_comp = ids[str(host)]
                host_type = str(host_comp.get("component_type") or "").upper()
                if host_type and host_type not in hostable:
                    errors.append(
                        f"capability: host {host!r} type={host_type} is not hostable"
                    )
    return errors


def apply_stage_b_capability_repair(
    plan: Dict[str, Any],
    user_text: str = "",
) -> Tuple[Dict[str, Any], List[str]]:
    """
    Normalize plan against the capability catalog before semantic/Java gates.
    Returns (plan, remaining_errors). Mutates plan in place when safe.
    """
    if not isinstance(plan, dict):
        return plan, ["capability: plan is not an object"]
    comps = plan.get("components")
    if not isinstance(comps, list):
        return plan, []

    repairs: List[str] = []
    aliases = roof_aliases()

    for c in comps:
        if not isinstance(c, dict):
            continue
        params = c.setdefault("params", {})
        if not isinstance(params, dict):
            continue
        ctype = str(c.get("component_type") or "").upper()

        # Typology alias → typology_id for STRUCTURE routing.
        if ctype in {"STRUCTURE", "MODULE"}:
            if not params.get("typology_id"):
                for key in ("structural_typology", "typology", "typologyId"):
                    if params.get(key):
                        params["typology_id"] = params[key]
                        repairs.append(f"promoted {key}→typology_id")
                        break
            if not params.get("typology_id"):
                for f in c.get("features") or []:
                    if isinstance(f, str) and f.lower().startswith("typology:"):
                        params["typology_id"] = f.split(":", 1)[1].strip()
                        repairs.append("promoted feature typology:→typology_id")
                        break
            if params.get("typology_id") and not params.get("typology"):
                params["typology"] = params["typology_id"]

        # Roof alias normalization on mass and roof components.
        for key in ("roof_type", "roofType"):
            if key not in params or params[key] is None:
                continue
            raw = str(params[key]).strip()
            if not raw:
                continue
            mapped = aliases.get(raw) or aliases.get(raw.lower())
            if mapped and mapped != raw:
                params[key] = mapped
                params["roof_type_mapped_from"] = raw
                repairs.append(f"roof_type {raw}→{mapped}")
            elif not is_executable_roof_type(raw):
                # Last-resort Chinese/Japanese hip-gable mapping for garden/temple plans.
                text = user_text or ""
                if any(tok in text for tok in ("日式", "入母屋", "irimoya", "和风", "庭院")):
                    params[key] = "xieshan"
                    params["roof_type_mapped_from"] = raw
                    repairs.append(f"roof_type {raw}→xieshan (locale fallback)")

        # Gate mass without GATE type: ensure passage_axis when proportions are gate-like.
        if ctype.startswith("MASS") and not params.get("passage_axis"):
            dims = c.get("dimensions") if isinstance(c.get("dimensions"), dict) else {}
            try:
                w, d = int(dims.get("width") or 0), int(dims.get("depth") or 0)
            except (TypeError, ValueError):
                w = d = 0
            feats = " ".join(str(f) for f in (c.get("features") or []) if f)
            gate_cue = any(tok in feats.lower() or tok in feats for tok in ("gate", "山门", "paifang", "temple_gate"))
            if gate_cue and w >= 10 and d > 0 and w >= d * 1.5:
                params["passage_axis"] = "z" if w >= d else "x"
                repairs.append("inferred passage_axis for gate-like mass")

    if repairs:
        meta = plan.setdefault("plan_meta", {})
        if isinstance(meta, dict):
            meta["capability_repairs"] = repairs[:32]
            meta["capability_catalog_schema"] = _SCHEMA

    return plan, evaluate_capability(plan)
