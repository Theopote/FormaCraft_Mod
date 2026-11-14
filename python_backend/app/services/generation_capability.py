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
    # LLM sometimes emits per-storey eave rings as a fake roof_type.
    "eave": "xieshan",
    "eaves": "xieshan",
    "dense_eave": "xieshan",
    "dense_eaves": "xieshan",
    "密檐": "xieshan",
    "重檐": "xieshan",
}

_FALLBACK_HOSTABLE = {
    "MASS_MAIN", "TOWER", "MASS_WING", "SIDE_WING", "MASS_ANCILLARY", "MASS_SECONDARY",
    "TERRACE", "COURTYARD", "COURTYARD_SPACE", "GATE", "GATE_STRUCTURE",
    "STRUCTURE", "MODULE", "FOUNDATION", "PLAZA", "PAVING", "PATH", "ROAD", "KEEP",
    "WALL",
}

_FALLBACK_HOSTED = {
    "ROOF", "ROOF_STRUCTURE", "FOUNDATION", "FACADE_WINDOWS", "FACADE", "ENTRANCE",
    "ENTRANCE_CANOPY", "CROWN", "CUPOLA", "DOME", "MASS_SECONDARY", "DECOR_DETAIL",
    "BALCONY", "TERRACE", "STRUCTURE", "ASSEMBLY",
}


_CATALOG_SOURCE: Optional[str] = None


def _catalog_paths() -> List[Path]:
    """Locate the shared Java resource catalog (authoritative), then optional Python copies."""
    here = Path(__file__).resolve()
    # generation_capability.py → services → app → python_backend → repo root
    py_root = here.parents[2]
    repo_root = here.parents[3]
    return [
        repo_root / "src" / "main" / "resources" / "assets" / "formacraft" / "generation_capability_catalog.json",
        repo_root / "build" / "resources" / "main" / "assets" / "formacraft" / "generation_capability_catalog.json",
        py_root / "data" / "generation_capability_catalog.json",
        here.parent.parent / "data" / "generation_capability_catalog.json",
    ]


def catalog_source_path() -> Optional[str]:
    """Path last used by load_catalog, or None when using in-process fallback."""
    return _CATALOG_SOURCE


def load_catalog(*, force: bool = False, allow_fallback: bool = True) -> Dict[str, Any]:
    global _CATALOG, _CATALOG_SOURCE
    if _CATALOG is not None and not force:
        return _CATALOG
    for path in _catalog_paths():
        if not path.is_file():
            continue
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
            if isinstance(data, dict) and data.get("schema") == _SCHEMA:
                _CATALOG = data
                _CATALOG_SOURCE = str(path)
                logger.info("Loaded generation capability catalog from %s", path)
                return _CATALOG
        except (OSError, json.JSONDecodeError):
            logger.warning("Failed reading capability catalog %s", path, exc_info=True)
    if not allow_fallback:
        raise FileNotFoundError(
            "generation_capability_catalog.json not found under repo assets; "
            f"searched: {[str(p) for p in _catalog_paths()]}"
        )
    logger.error(
        "generation_capability_catalog.json missing — using in-process fallback "
        "(Python/Java capability tables may diverge). searched=%s",
        [str(p) for p in _catalog_paths()],
    )
    _CATALOG_SOURCE = None
    _CATALOG = {
        "schema": _SCHEMA,
        "version": 1,
        "hostable_component_types": sorted(_FALLBACK_HOSTABLE),
        "hosted_component_types": sorted(_FALLBACK_HOSTED),
        "roof": {"aliases": dict(_FALLBACK_ALIASES), "supported_tokens": list(_FALLBACK_ALIASES.values()) + ["gable", "hip", "xieshan", "flat", "none"]},
        "typologies": ["dense_eaves_pagoda", "courtyard_compound", "tailiang_timber_hall"],
        "building_classes": {},
        "_fallback": True,
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
            # Stair/circulation STRUCTURE flights use from/to geometry, not typology_id.
            joined_feats = " ".join(feats).lower()
            is_stair_flight = (
                ("from" in params and "to" in params)
                or any(tok in joined_feats for tok in ("stair:", "stair_", "stairs"))
                or str(params.get("extrude_mode") or "").lower() == "stair"
            )
            # Parametric STRUCTURE (colonnade/gear/cross/spiral/truss) is emitted by
            # StructureComponentGenerator from structure_type — typology_id is optional.
            structure_type = (
                params.get("structure_type")
                or params.get("structureType")
                or params.get("kind")
            )
            parametric_tokens = (
                "colonnade", "column_ring", "radial_colonnade", "columns",
                "gear", "cog", "industrial_gear",
                "cross", "crucifix",
                "spiral", "spiral_stair",
                "truss", "lattice", "x_brace",
            )
            is_parametric = False
            if structure_type is not None:
                st = str(structure_type).strip().lower()
                is_parametric = any(tok in st for tok in parametric_tokens) or bool(st)
            if not is_parametric:
                is_parametric = any(tok in joined_feats for tok in parametric_tokens)
            if not typology:
                for f in feats:
                    lower = f.lower()
                    if lower.startswith("typology:"):
                        typology = f.split(":", 1)[1].strip()
                        break
            if not typology and not is_stair_flight and not is_parametric:
                if not any(tok in joined_feats for tok in ("dense_eaves", "pagoda", "stupa", "typology")):
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
    hostable = hostable_types()
    hosted = hosted_types()

    # Index component_id → component for host remapping.
    id_index: Dict[str, Dict[str, Any]] = {}
    for c in comps:
        if not isinstance(c, dict):
            continue
        params = c.get("params") if isinstance(c.get("params"), dict) else {}
        cid = params.get("component_id")
        if cid:
            id_index[str(cid)] = c

    def _pick_host_remap(wanted: str) -> Optional[str]:
        key = str(wanted).strip().lower()
        if not key:
            return None
        # Exact / case-insensitive id match.
        for cid in id_index:
            if cid.lower() == key:
                return cid
        # Fuzzy: host name appears in component_id (courtyard → garden_courtyard).
        fuzzy = [
            cid
            for cid, comp in id_index.items()
            if key in cid.lower()
            and str(comp.get("component_type") or "").upper() in hostable
        ]
        if fuzzy:
            return sorted(fuzzy, key=len)[0]
        # Role fallback: courtyard-like hosts → first COURTYARD / PATH / MASS.
        if any(tok in key for tok in ("court", "yard", "庭", "院")):
            for prefer in ("COURTYARD", "COURTYARD_SPACE", "PATH", "PLAZA", "MASS_MAIN", "MASS_ANCILLARY"):
                for cid, comp in id_index.items():
                    if str(comp.get("component_type") or "").upper() == prefer:
                        return cid
        # Last resort: any hostable mass.
        for prefer in ("MASS_MAIN", "MASS_ANCILLARY", "GATE", "STRUCTURE"):
            for cid, comp in id_index.items():
                if str(comp.get("component_type") or "").upper() == prefer:
                    return cid
        return None

    for c in comps:
        if not isinstance(c, dict):
            continue
        params = c.setdefault("params", {})
        if not isinstance(params, dict):
            continue
        ctype = str(c.get("component_type") or "").upper()

        # Remap unknown / non-hostable host_id before evaluate_capability hard-fails
        # (e.g. censer→courtyard, or legacy WALL-only hosts when catalog lagged).
        if ctype in hosted:
            host = params.get("host_id")
            if host:
                host_key = str(host)
                host_comp = id_index.get(host_key)
                host_type = (
                    str(host_comp.get("component_type") or "").upper() if host_comp else ""
                )
                needs_remap = host_comp is None or (host_type and host_type not in hostable)
                if needs_remap:
                    remapped = _pick_host_remap(host_key)
                    if remapped and remapped != host_key:
                        params["host_id_mapped_from"] = host_key
                        params["host_id"] = remapped
                        repairs.append(f"host_id {host_key!r}→{remapped!r}")

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
