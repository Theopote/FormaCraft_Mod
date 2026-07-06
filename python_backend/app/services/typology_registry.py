"""Structural typology registry — loads structural_typologies_v1.json."""

from __future__ import annotations

import json
import os
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

_REGISTRY: Dict[str, "TypologyDef"] = {}
_MIGRATION: Dict[str, "MigrationEntry"] = {}
_LOADED = False


@dataclass(frozen=True)
class ReferenceLandmark:
    archetype_id: str
    role: str = "primary"
    notes: str = ""


@dataclass(frozen=True)
class MigrationEntry:
    typology_id: str
    phase: str = "typology_first"
    legacy_module_id: Optional[str] = None
    deprecated_after: Optional[str] = None
    notes: str = ""


@dataclass(frozen=True)
class TypologyDef:
    id: str
    display_name_zh: str
    display_name_en: str
    skeleton_type: str
    style_families: Tuple[str, ...]
    match_keywords: Tuple[str, ...]
    negative_keywords: Tuple[str, ...]
    interpreter_id: str
    legacy_interpreter_id: Optional[str]
    routing_policy: str
    default_params: Dict[str, Any]
    param_schema: Dict[str, Any]
    reference_landmarks: Tuple[ReferenceLandmark, ...]
    proportion_card_ids: Tuple[str, ...]
    culture_card_ids: Tuple[str, ...]
    llm_plan_guidance: str


def _default_json_path() -> Path:
    env = (os.getenv("FORMCRAFT_ASSETS_DIR") or "").strip()
    if env:
        base = Path(env).expanduser().resolve()
        cand = base / "structural_typologies" / "structural_typologies_v1.json"
        if cand.is_file():
            return cand
    here = Path(__file__).resolve()
    for p in [here] + list(here.parents):
        cand = (
            p
            / "src"
            / "main"
            / "resources"
            / "assets"
            / "formacraft"
            / "structural_typologies"
            / "structural_typologies_v1.json"
        )
        if cand.is_file():
            return cand
    repo_root = here.parents[3]
    return (
        repo_root
        / "src"
        / "main"
        / "resources"
        / "assets"
        / "formacraft"
        / "structural_typologies"
        / "structural_typologies_v1.json"
    )


def _str_tuple(v: Any) -> Tuple[str, ...]:
    if not isinstance(v, list):
        return ()
    return tuple(str(x).strip() for x in v if isinstance(x, str) and str(x).strip())


def _load_from_json(path: Path) -> None:
    global _LOADED
    data = json.loads(path.read_text(encoding="utf-8"))
    typologies = data.get("typologies") or []
    for item in typologies:
        if not isinstance(item, dict):
            continue
        tid = str(item.get("id") or "").strip()
        if not tid:
            continue
        dn = item.get("displayName") if isinstance(item.get("displayName"), dict) else {}
        refs: List[ReferenceLandmark] = []
        for r in item.get("referenceLandmarks") or []:
            if not isinstance(r, dict):
                continue
            aid = str(r.get("archetypeId") or "").strip()
            if aid:
                refs.append(
                    ReferenceLandmark(
                        archetype_id=aid,
                        role=str(r.get("role") or "primary"),
                        notes=str(r.get("notes") or ""),
                    )
                )
        _REGISTRY[tid] = TypologyDef(
            id=tid,
            display_name_zh=str(dn.get("zh") or tid),
            display_name_en=str(dn.get("en") or tid),
            skeleton_type=str(item.get("skeletonType") or "COMPOUND"),
            style_families=_str_tuple(item.get("styleFamilies")),
            match_keywords=_str_tuple(item.get("matchKeywords")),
            negative_keywords=_str_tuple(item.get("negativeKeywords")),
            interpreter_id=str(item.get("interpreterId") or tid),
            legacy_interpreter_id=(
                str(item.get("legacyInterpreterId")).strip()
                if item.get("legacyInterpreterId")
                else None
            ),
            routing_policy=str(item.get("routingPolicy") or "typology_first"),
            default_params=dict(item.get("defaultParams") or {}),
            param_schema=dict(item.get("paramSchema") or {}),
            reference_landmarks=tuple(refs),
            proportion_card_ids=_str_tuple(item.get("proportionCardIds")),
            culture_card_ids=_str_tuple(item.get("cultureCardIds")),
            llm_plan_guidance=str(item.get("llmPlanGuidance") or ""),
        )

    migration = data.get("migrationMap") or {}
    if isinstance(migration, dict):
        for legacy_id, entry in migration.items():
            if not isinstance(entry, dict):
                continue
            typ_id = str(entry.get("typologyId") or "").strip()
            if not typ_id:
                continue
            _MIGRATION[str(legacy_id).strip()] = MigrationEntry(
                typology_id=typ_id,
                phase=str(entry.get("phase") or "typology_first"),
                legacy_module_id=(
                    str(entry.get("legacyModuleId")).strip()
                    if entry.get("legacyModuleId")
                    else None
                ),
                deprecated_after=(
                    str(entry.get("deprecatedAfter")).strip()
                    if entry.get("deprecatedAfter")
                    else None
                ),
                notes=str(entry.get("notes") or ""),
            )
    _LOADED = True


def _ensure_loaded() -> None:
    global _LOADED
    if _LOADED:
        return
    path = _default_json_path()
    if not path.is_file():
        _LOADED = True
        return
    _load_from_json(path)


def list_typologies() -> List[TypologyDef]:
    _ensure_loaded()
    return list(_REGISTRY.values())


def get_typology(typology_id: str) -> Optional[TypologyDef]:
    _ensure_loaded()
    if not typology_id:
        return None
    return _REGISTRY.get(typology_id.strip())


def get_migration(legacy_module_id: str) -> Optional[MigrationEntry]:
    _ensure_loaded()
    if not legacy_module_id:
        return None
    return _MIGRATION.get(legacy_module_id.strip())


def typology_for_legacy_module(legacy_module_id: str) -> Optional[str]:
    entry = get_migration(legacy_module_id)
    return entry.typology_id if entry else None


def typology_for_culture_card(culture_card_id: str) -> Optional[str]:
    _ensure_loaded()
    cid = (culture_card_id or "").strip()
    if not cid:
        return None
    for t in _REGISTRY.values():
        if cid in t.culture_card_ids:
            return t.id
    entry = get_migration(cid)
    return entry.typology_id if entry else None


def typology_for_archetype(archetype_id: str) -> Optional[str]:
    _ensure_loaded()
    aid = (archetype_id or "").strip()
    if not aid:
        return None
    for t in _REGISTRY.values():
        for ref in t.reference_landmarks:
            if ref.archetype_id == aid and ref.role == "primary":
                return t.id
    entry = get_migration(aid)
    return entry.typology_id if entry else None
