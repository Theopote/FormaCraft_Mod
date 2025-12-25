from __future__ import annotations

import json
import os
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple


@dataclass(frozen=True)
class ArchetypeConstraints:
    # v1 minimal fields (future-proof)
    min_diameter: Optional[int] = None
    max_diameter: Optional[int] = None
    min_height: Optional[int] = None
    max_height: Optional[int] = None


@dataclass(frozen=True)
class ArchetypeDefaults:
    # v1 minimal fields (future-proof)
    diameter: Optional[int] = None
    floors: Optional[int] = None


@dataclass(frozen=True)
class ArchetypeScoring:
    # v1 placeholder scoring weights (future-proof)
    shape: float = 0.4
    ratio: float = 0.3
    signature: float = 0.3


@dataclass(frozen=True)
class ArchetypeDef:
    id: str
    aliases: Tuple[str, ...]
    category: str                      # landmark / infrastructure / fortification / etc
    generator_id: str                  # tulou / eiffel_tower / great_wall ... (maps to Java side)
    # Raw maps loaded from JSON (single source of truth)
    defaults_map: Dict[str, Any] = field(default_factory=dict)
    constraints_map: Dict[str, Any] = field(default_factory=dict)
    # Typed conveniences (best-effort)
    defaults: ArchetypeDefaults = field(default_factory=ArchetypeDefaults)
    constraints: ArchetypeConstraints = field(default_factory=ArchetypeConstraints)
    scoring: ArchetypeScoring = field(default_factory=ArchetypeScoring)


_REGISTRY: Dict[str, ArchetypeDef] = {}
_ALIAS_INDEX: Dict[str, str] = {}
_LOADED: bool = False


def _default_json_path() -> Path:
    """
    Default to the single shared data source in the repo:
      src/main/resources/assets/formacraft/archetypes/archetypes_v1.json
    """
    # python_backend/app/services/archetype_registry.py -> repo root
    here = Path(__file__).resolve()
    repo_root = here.parents[3]  # python_backend/app/services -> repo root
    return repo_root / "src" / "main" / "resources" / "assets" / "formacraft" / "archetypes" / "archetypes_v1.json"


def _load_from_json(path: Path) -> Dict[str, ArchetypeDef]:
    data: Dict[str, Any] = json.loads(path.read_text(encoding="utf-8"))
    arr = data.get("archetypes") or []
    out: Dict[str, ArchetypeDef] = {}
    for item in arr:
        if not isinstance(item, dict):
            continue
        aid = str(item.get("id") or "").strip().lower()
        if not aid:
            continue
        aliases = tuple(str(a).strip() for a in (item.get("aliases") or []) if str(a).strip())
        category = str(item.get("category") or "").strip().lower() or "landmark"
        generator_id = str(item.get("generatorId") or aid).strip().lower() or aid

        defaults_raw = item.get("defaults") or {}
        constraints_raw = item.get("constraints") or {}
        scoring_raw = item.get("scoringWeights") or {}

        defaults = ArchetypeDefaults(
            diameter=_to_int(defaults_raw.get("diameter")),
            floors=_to_int(defaults_raw.get("floors")),
        )
        constraints = ArchetypeConstraints(
            min_diameter=_to_int(constraints_raw.get("minDiameter")),
            max_diameter=_to_int(constraints_raw.get("maxDiameter")),
            min_height=_to_int(constraints_raw.get("minHeight")),
            max_height=_to_int(constraints_raw.get("maxHeight")),
        )
        scoring = ArchetypeScoring(
            shape=_to_float(scoring_raw.get("shape"), 0.4),
            ratio=_to_float(scoring_raw.get("ratio"), 0.3),
            signature=_to_float(scoring_raw.get("signature"), 0.3),
        )
        out[aid] = ArchetypeDef(
            id=aid,
            aliases=aliases,
            category=category,
            generator_id=generator_id,
            defaults_map=dict(defaults_raw) if isinstance(defaults_raw, dict) else {},
            constraints_map=dict(constraints_raw) if isinstance(constraints_raw, dict) else {},
            defaults=defaults,
            constraints=constraints,
            scoring=scoring,
        )
    return out


def _to_int(v: Any) -> Optional[int]:
    try:
        if v is None:
            return None
        if isinstance(v, bool):
            return int(v)
        if isinstance(v, (int, float)):
            return int(v)
        s = str(v).strip()
        if not s:
            return None
        return int(float(s))
    except Exception:
        return None


def _to_float(v: Any, default: float) -> float:
    try:
        if v is None:
            return default
        if isinstance(v, (int, float)):
            return float(v)
        s = str(v).strip()
        if not s:
            return default
        return float(s)
    except Exception:
        return default


def ensure_loaded() -> None:
    global _LOADED, _REGISTRY, _ALIAS_INDEX
    if _LOADED:
        return
    # allow override for deployments
    override = os.getenv("FORMACRAFT_ARCHETYPES_JSON", "").strip()
    path = Path(override) if override else _default_json_path()
    try:
        if path.exists():
            _REGISTRY = _load_from_json(path)
        else:
            _REGISTRY = {}
    except Exception:
        _REGISTRY = {}

    # build alias index
    m: Dict[str, str] = {}
    for a in _REGISTRY.values():
        for alias in a.aliases:
            k = (alias or "").strip().lower()
            if k and k not in m:
                m[k] = a.id
        # also index id itself
        if a.id and a.id not in m:
            m[a.id] = a.id
    _ALIAS_INDEX = m
    _LOADED = True


def get_archetype_def(archetype_id: str) -> Optional[ArchetypeDef]:
    ensure_loaded()
    if not archetype_id:
        return None
    return _REGISTRY.get(archetype_id.strip().lower())


def list_archetype_ids() -> List[str]:
    ensure_loaded()
    return list(_REGISTRY.keys())


def all_archetypes() -> List[ArchetypeDef]:
    ensure_loaded()
    return list(_REGISTRY.values())


def alias_index() -> Dict[str, str]:
    """
    Map alias(lower) -> archetype_id.
    """
    ensure_loaded()
    return dict(_ALIAS_INDEX)


