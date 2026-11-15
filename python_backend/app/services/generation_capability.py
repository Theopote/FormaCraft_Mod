"""Stage-B generation capability contract shared with Java GenerationCapabilityCatalog.

Deployment contract
------------------
The **only** authoritative capability contract is the JSON exported by the Java
side (``GenerationCapabilityCatalog`` → ``assets/formacraft/generation_capability_catalog.json``).
Python must not keep a second, hand-maintained truth source.

Therefore:

* When the exported JSON is available it is used **verbatim** — never merged with
  a Python-local table. A Python-only alias would silently widen the capability
  set beyond what the Java runtime can execute.
* The static tables below exist only so a *development/test* checkout without the
  Java assets can still boot. They are a **degraded** mode, controlled by
  ``FORMACRAFT_CAPABILITY_MODE``:

  - ``strict`` (default): the exported catalog is a **required** deployment
    resource. Missing/invalid ⇒ :class:`CapabilityCatalogUnavailable`.
  - ``fallback``: the static snapshot is permitted (warning logged).
  - ``auto``: exported catalog when present; degrade only under pytest/dev.

The static tables are a **snapshot of the Java contract at export time**, not an
independent source of truth. ``test_generation_capability_batch2.py`` locks the
two against each other.
"""

from __future__ import annotations

import json
import logging
import os
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

logger = logging.getLogger(__name__)

_SCHEMA = "formacraft.generation_capability.v1"
_SUPPORTED_VERSION = 1
_ALLOWED_IMPLEMENTATION_STATUSES = frozenset({
    "supported",
    "typology_routed",
    "fbd_or_empty",
    "unsupported",
})
# Stage P may freely emit these; conditional statuses need extra contracts.
_DIRECT_PLAN_STATUSES = frozenset({"supported"})
_CONDITIONAL_PLAN_STATUSES = frozenset({"typology_routed", "fbd_or_empty"})
_DEFAULT_CONDITIONAL_CONTRACTS: Dict[str, Dict[str, str]] = {
    "STRUCTURE": {
        "status": "typology_routed",
        "requires": (
            "typology_id / typology:* feature, OR parametric structure_type "
            "(colonnade/truss/spiral/...); empty typology STRUCTURE is invalid"
        ),
    },
    "MODULE": {
        "status": "typology_routed",
        "requires": "explicit non-migrated preset landmark_module / landmark:* only",
    },
    "ASSEMBLY": {
        "status": "fbd_or_empty",
        "requires": "FBD/assembly program params; otherwise generator returns empty geometry",
    },
}
_CATALOG: Optional[Dict[str, Any]] = None

# ---------------------------------------------------------------------------
# Static fallback snapshot (DEVELOPMENT/TEST ONLY).
#
# Mirrors the Java `GenerationCapabilityCatalog` / `RoofTypeCatalog` tables as
# of the last export. NOT a second truth source: in `strict` mode the backend
# refuses to start without the exported JSON.
#
# NOTE: the alias set here is intentionally the *Java* alias set. The Python
# table historically also carried `eave` / `eaves` / `dense_eave` / `dense_eaves`
# / `密檐` / `重檐`, which the Java `RoofTypeCatalog` does **not** accept as
# executable roof types — keeping them let the planner emit a roof the Java
# compiler cannot build. Eave *tiers* are expressed through `eaves_per_level` /
# `eave_tiers` params and the `dense_eaves_pagoda` typology, never through a fake
# `roof_type`.
# ---------------------------------------------------------------------------

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
    "WALL",
}

_FALLBACK_HOSTED = {
    "ROOF", "ROOF_STRUCTURE", "FOUNDATION", "FACADE_WINDOWS", "FACADE", "ENTRANCE",
    "ENTRANCE_CANOPY", "CROWN", "CUPOLA", "DOME", "MASS_SECONDARY", "DECOR_DETAIL",
    "BALCONY", "TERRACE", "STRUCTURE", "ASSEMBLY",
}

# Mirrors `GenerationCapabilityCatalog.buildingClasses()`.
_FALLBACK_BUILDING_CLASSES: Dict[str, Dict[str, Any]] = {
    "tower_like": {
        "preferred_components": ["STRUCTURE"],
        "preferred_typologies": ["dense_eaves_pagoda", "tibetan_stupa"],
        "composition": "single",
    },
    "gate": {
        "preferred_components": ["GATE", "GATE_STRUCTURE", "STRUCTURE"],
        "accept_mass_proportions": True,
        "composition": "single",
    },
    "temple": {
        "preferred_components": ["MASS_MAIN", "ROOF"],
        "composition_default": "single",
        "composition_compound_triggers": ["建筑群", "院落", "厢房", "配殿", "三进", "两进"],
    },
    "garden": {
        "preferred_components": ["COURTYARD", "PATH", "MASS_MAIN"],
        "composition": "compound",
    },
    "generic": {
        "preferred_components": ["MASS_MAIN", "ROOF", "ENTRANCE", "FACADE_WINDOWS"],
        "composition": "single",
    },
}


_CATALOG_SOURCE: Optional[str] = None


class CapabilityCatalogUnavailable(RuntimeError):
    """The authoritative exported capability catalog is missing or invalid.

    Raised in ``strict`` mode so a mis-deployed backend fails loudly instead of
    silently planning against a divergent capability set.
    """


def capability_mode() -> str:
    """Resolve the effective capability mode (``strict`` / ``fallback`` / ``auto``)."""
    raw = (os.getenv("FORMACRAFT_CAPABILITY_MODE") or "").strip().lower()
    if raw in {"strict", "fallback", "auto"}:
        return raw
    return "strict"


def _fallback_permitted() -> bool:
    """True when the static snapshot may be used instead of the exported catalog."""
    mode = capability_mode()
    if mode == "fallback":
        return True
    if mode == "auto":
        # `auto` only degrades inside a test/dev process.
        return "PYTEST_CURRENT_TEST" in os.environ or os.getenv("FORMACRAFT_DEV_MODE") == "1"
    return False


def _catalog_paths() -> List[Path]:
    """Locate the shared Java resource catalog (authoritative), then optional Python copies."""
    here = Path(__file__).resolve()
    # generation_capability.py → services → app → python_backend → repo root
    py_root = here.parents[2]
    repo_root = here.parents[3]
    candidates: List[Path] = []

    # Explicit deployment override (standalone Python deployment).
    env_path = (os.getenv("FORMACRAFT_CAPABILITY_CATALOG") or "").strip()
    if env_path:
        candidates.append(Path(env_path).expanduser())

    candidates.extend([
        repo_root / "src" / "main" / "resources" / "assets" / "formacraft" / "generation_capability_catalog.json",
        repo_root / "build" / "resources" / "main" / "assets" / "formacraft" / "generation_capability_catalog.json",
        py_root / "data" / "generation_capability_catalog.json",
        here.parent.parent / "data" / "generation_capability_catalog.json",
    ])
    return candidates


def catalog_source_path() -> Optional[str]:
    """Path last used by load_catalog, or None when using in-process fallback."""
    return _CATALOG_SOURCE


def _validate_catalog(data: Any) -> Optional[str]:
    """Validate schema + required sections. Returns an error message, or None if valid."""
    if not isinstance(data, dict):
        return "catalog root is not an object"
    schema = data.get("schema")
    if schema != _SCHEMA:
        return f"unexpected schema {schema!r} (expected {_SCHEMA!r})"
    version = data.get("version")
    if not isinstance(version, int):
        return f"missing/invalid version {version!r}"
    if version != _SUPPORTED_VERSION:
        return f"unsupported version {version} (expected {_SUPPORTED_VERSION})"
    for key in ("hostable_component_types", "hosted_component_types"):
        value = data.get(key)
        if not isinstance(value, list) or not value:
            return f"missing/invalid section {key!r}"
    component_types = data.get("component_types")
    if not isinstance(component_types, dict) or not component_types:
        return "missing/invalid section 'component_types'"
    for type_name, entry in component_types.items():
        if not isinstance(type_name, str) or not type_name.strip():
            return "component_types contains an empty key"
        if not isinstance(entry, dict):
            return f"component_types[{type_name!r}] must be an object"
        status = entry.get("implementation_status")
        if status not in _ALLOWED_IMPLEMENTATION_STATUSES:
            return (
                f"component_types[{type_name!r}].implementation_status="
                f"{status!r} not in {sorted(_ALLOWED_IMPLEMENTATION_STATUSES)}"
            )
        if status in _CONDITIONAL_PLAN_STATUSES:
            contract = entry.get("planning_contract")
            if not isinstance(contract, dict) or not str(contract.get("requires") or "").strip():
                return (
                    f"component_types[{type_name!r}] status={status!r} "
                    "requires a planning_contract.requires string"
                )
    typologies = data.get("typologies")
    if not isinstance(typologies, list) or not typologies:
        return "missing/invalid section 'typologies'"
    building_classes = data.get("building_classes")
    if not isinstance(building_classes, dict) or not building_classes:
        return "missing/invalid section 'building_classes'"
    roof = data.get("roof")
    if not isinstance(roof, dict):
        return "missing/invalid section 'roof'"
    if not isinstance(roof.get("aliases"), dict):
        return "missing/invalid section 'roof.aliases'"
    if not isinstance(roof.get("supported_tokens"), list):
        return "missing/invalid section 'roof.supported_tokens'"
    return None


def load_catalog(*, force: bool = False, allow_fallback: Optional[bool] = None) -> Dict[str, Any]:
    """Load the authoritative capability catalog.

    Raises :class:`CapabilityCatalogUnavailable` when the exported JSON is absent
    or invalid and the static snapshot is not permitted in the current mode.
    """
    global _CATALOG, _CATALOG_SOURCE
    if _CATALOG is not None and not force:
        return _CATALOG

    errors: List[str] = []
    for path in _catalog_paths():
        if not path.is_file():
            continue
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as exc:
            errors.append(f"{path}: unreadable ({exc})")
            logger.warning("Failed reading capability catalog %s", path, exc_info=True)
            continue
        problem = _validate_catalog(data)
        if problem:
            errors.append(f"{path}: {problem}")
            logger.warning("Invalid capability catalog %s: %s", path, problem)
            continue
        _CATALOG = data
        _CATALOG_SOURCE = str(path)
        logger.info("Loaded generation capability catalog from %s", path)
        return _CATALOG

    if allow_fallback is None:
        allow_fallback = _fallback_permitted()
    if not allow_fallback:
        raise CapabilityCatalogUnavailable(
            "generation_capability_catalog.json is a REQUIRED deployment resource "
            "(mode=strict). The Python backend must not plan against a divergent "
            "capability set. Searched: "
            f"{[str(p) for p in _catalog_paths()]}. "
            "Set FORMACRAFT_CAPABILITY_MODE=fallback to allow the development-only "
            "static snapshot, or point FORMACRAFT_CAPABILITY_CATALOG at the exported "
            "file. Errors: " + ("; ".join(errors) if errors else "no candidate file found")
        )

    logger.error(
        "generation_capability_catalog.json missing — using DEVELOPMENT-ONLY static "
        "snapshot (Python/Java capability tables may diverge). searched=%s errors=%s",
        [str(p) for p in _catalog_paths()],
        errors,
    )
    _CATALOG_SOURCE = None
    fallback_component_types = {
        name: {"implementation_status": "supported"}
        for name in sorted(_FALLBACK_HOSTABLE | _FALLBACK_HOSTED)
    }
    for name, meta in _DEFAULT_CONDITIONAL_CONTRACTS.items():
        entry = dict(fallback_component_types.get(name) or {})
        entry["implementation_status"] = meta["status"]
        entry["planning_contract"] = {"requires": meta["requires"]}
        if name == "STRUCTURE":
            entry["planning_contract"]["routes"] = [
                "native_typology",
                "parametric_structure_type",
            ]
        fallback_component_types[name] = entry
    _CATALOG = {
        "schema": _SCHEMA,
        "version": _SUPPORTED_VERSION,
        "hostable_component_types": sorted(_FALLBACK_HOSTABLE),
        "hosted_component_types": sorted(_FALLBACK_HOSTED),
        "component_types": fallback_component_types,
        "roof": {
            "aliases": dict(_FALLBACK_ALIASES),
            "supported_tokens": list(_FALLBACK_ALIASES.values()) + [
                "gable", "hip", "xieshan", "flat", "none",
            ],
        },
        "typologies": [
            "dense_eaves_pagoda", "tibetan_stupa", "tailiang_timber_hall",
            "radial_terrace_hall", "courtyard_compound",
        ],
        "building_classes": _FALLBACK_BUILDING_CLASSES,
        "_fallback": True,
    }
    return _CATALOG


def is_degraded() -> bool:
    """True when the backend is running on the development-only static snapshot."""
    return bool(load_catalog().get("_fallback"))


def roof_aliases() -> Dict[str, str]:
    """Authoritative planner-token → executable roof_type mapping.

    Uses the exported catalog **verbatim** when available. The static snapshot is
    used only in degraded mode — never merged on top of the authoritative set,
    because a Python-only alias would advertise a roof the Java runtime cannot
    execute.
    """
    catalog = load_catalog()
    roof = catalog.get("roof") if isinstance(catalog.get("roof"), dict) else {}
    aliases = roof.get("aliases") if isinstance(roof.get("aliases"), dict) else {}
    return {str(k): str(v) for k, v in aliases.items() if k and v}


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


def listed_component_types() -> Tuple[str, ...]:
    """All registered component_type keys from the capability catalog."""
    raw = load_catalog().get("component_types")
    if isinstance(raw, dict) and raw:
        return tuple(sorted({str(k).upper() for k in raw.keys() if k}))
    # Degraded/incomplete catalogs: union of hostable + hosted tables.
    return tuple(sorted(hostable_types() | hosted_types()))


def _component_type_entries() -> Dict[str, Dict[str, Any]]:
    raw = load_catalog().get("component_types")
    if not isinstance(raw, dict):
        return {}
    out: Dict[str, Dict[str, Any]] = {}
    for key, entry in raw.items():
        name = str(key or "").strip().upper()
        if not name or not isinstance(entry, dict):
            continue
        out[name] = entry
    return out


def plannable_component_types() -> Tuple[str, ...]:
    """Component types Stage P may freely recommend (implementation_status=supported)."""
    entries = _component_type_entries()
    if not entries:
        return listed_component_types()
    direct = [
        name
        for name, entry in entries.items()
        if str(entry.get("implementation_status") or "").strip() in _DIRECT_PLAN_STATUSES
    ]
    return tuple(sorted(direct))


def conditional_component_contracts() -> Dict[str, Dict[str, Any]]:
    """Conditional/routed types and the params they require before planning.

    Strict/authoritative mode derives this set only from the loaded catalog.
    Built-in snapshots are used only when the catalog is explicitly degraded.
    """
    entries = _component_type_entries()
    out: Dict[str, Dict[str, Any]] = {}
    for name, entry in entries.items():
        status = str(entry.get("implementation_status") or "").strip()
        if status not in _CONDITIONAL_PLAN_STATUSES:
            continue
        contract = entry.get("planning_contract")
        if isinstance(contract, dict) and contract.get("requires"):
            requires = str(contract.get("requires"))
            meta: Dict[str, Any] = {"status": status, "requires": requires}
            routes = contract.get("routes")
            if isinstance(routes, list) and routes:
                meta["routes"] = [str(r) for r in routes if r]
            out[name] = meta
            continue
        if is_degraded() and name in _DEFAULT_CONDITIONAL_CONTRACTS:
            out[name] = dict(_DEFAULT_CONDITIONAL_CONTRACTS[name])
        else:
            out[name] = {
                "status": status,
                "requires": f"status={status}; provide required route/params before use",
            }
    if is_degraded():
        for name, fallback in _DEFAULT_CONDITIONAL_CONTRACTS.items():
            out.setdefault(name, dict(fallback))
    return {k: out[k] for k in sorted(out)}


def planning_component_exposure(
    profile: Any = None,
    *,
    user_request: str = "",
) -> Dict[str, Any]:
    """Single-catalog planning view, filtered by request/profile context.

    Model-designed landmarks must not see fixed MODULE / native typology STRUCTURE
    options that later gates will reject. Preset and typology-first contexts keep
    the matching conditional routes.
    """
    direct = list(plannable_component_types())
    conditional = dict(conditional_component_contracts())
    mc = getattr(profile, "minecraft_strategy", None) if profile is not None else None
    model_designed = bool(getattr(mc, "model_designed_landmark", False))
    has_preset = bool(str(getattr(mc, "landmark_module", None) or "").strip())
    text = user_request if isinstance(user_request, str) else ""
    if not has_preset and "preset:" in text.lower():
        has_preset = True
    has_typology = bool(str(getattr(mc, "structural_typology", None) or "").strip()) and not model_designed
    wants_fbd = any(tok in text.lower() for tok in ("fbd", "assembly program", "assembly:"))

    if model_designed and not has_preset:
        conditional.pop("MODULE", None)
        if "STRUCTURE" in conditional:
            conditional["STRUCTURE"] = {
                "status": conditional["STRUCTURE"].get("status") or "typology_routed",
                "requires": (
                    "parametric structure_type only (colonnade/truss/spiral/...); "
                    "typology:* and fixed landmark executors are disallowed for "
                    "model-designed landmarks"
                ),
                "routes": ["parametric_structure_type"],
            }
        if not wants_fbd:
            conditional.pop("ASSEMBLY", None)
    elif has_typology:
        # Typology-first: keep STRUCTURE native route; MODULE stays out unless preset.
        if not has_preset:
            conditional.pop("MODULE", None)
        if not wants_fbd:
            conditional.pop("ASSEMBLY", None)
    elif not has_preset:
        conditional.pop("MODULE", None)
        if not wants_fbd:
            conditional.pop("ASSEMBLY", None)

    return {
        "direct": direct,
        "conditional": conditional,
        "registered": list(listed_component_types()),
        "context": {
            "model_designed_landmark": model_designed,
            "preset_module": has_preset,
            "typology_first": has_typology,
            "explicit_fbd": wants_fbd,
        },
    }


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
            # Keep the token list aligned with StructureComponentGenerator.normalizeKind.
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
            unsupported_structure_type = None
            explicit_structure_type = False
            if structure_type is not None:
                st = str(structure_type).strip().lower()
                if st:
                    explicit_structure_type = True
                    is_parametric = any(tok in st for tok in parametric_tokens)
                    if not is_parametric:
                        unsupported_structure_type = st
            # Explicit structure_type wins: do not recover via features when the declared
            # type is unsupported (Java StructureComponentGenerator also prefers params).
            if not explicit_structure_type and not is_parametric:
                is_parametric = any(tok in joined_feats for tok in parametric_tokens)
            if not typology:
                for f in feats:
                    lower = f.lower()
                    if lower.startswith("typology:"):
                        typology = f.split(":", 1)[1].strip()
                        break
            label = params.get("component_id") or ctype
            if unsupported_structure_type and not typology and not is_stair_flight:
                errors.append(
                    f"capability: unsupported structure_type={unsupported_structure_type!r} on {label}"
                )
            elif not typology and not is_stair_flight and not is_parametric:
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
