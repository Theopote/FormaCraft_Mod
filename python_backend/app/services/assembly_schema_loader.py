"""
Load exported MetaAssembly AI schema (assets/formacraft/ai-assembly-schema.json).
Python validation is a lightweight pre-check; Java AssemblySpecValidator remains authoritative.
"""
from __future__ import annotations

import json
from functools import lru_cache
from pathlib import Path
from typing import Any, Dict, List, Optional, Set

_REPO_ROOT = Path(__file__).resolve().parents[3]
_SCHEMA_PATH = _REPO_ROOT / "src" / "main" / "resources" / "assets" / "formacraft" / "ai-assembly-schema.json"


@lru_cache(maxsize=1)
def load_assembly_schema() -> Dict[str, Any]:
    if not _SCHEMA_PATH.is_file():
        return _fallback_schema()
    with _SCHEMA_PATH.open("r", encoding="utf-8") as fh:
        data = json.load(fh)
    if not isinstance(data, dict):
        return _fallback_schema()
    return data


def known_preset_ids() -> Set[str]:
    presets = load_assembly_schema().get("presets") or []
    out: Set[str] = set()
    if isinstance(presets, list):
        for item in presets:
            if isinstance(item, dict):
                pid = str(item.get("id") or "").strip()
                if pid:
                    out.add(pid)
    return out or frozenset({
        "spiral_watchtower",
        "suspension_bridge_simple",
        "gothic_shell_box",
    })


def component_ports(comp_type: str) -> Set[str]:
    upper = (comp_type or "").strip().upper()
    for item in load_assembly_schema().get("components") or []:
        if not isinstance(item, dict):
            continue
        ctype = str(item.get("type") or "").strip().upper()
        aliases = {str(a).strip().upper() for a in (item.get("aliases") or []) if a}
        if upper == ctype or upper in aliases or (ctype and ctype in upper):
            ports = item.get("ports") or []
            if isinstance(ports, list):
                return {str(p).strip() for p in ports if p}
    return set(_BUILTIN_PORTS)


def compatibility_rules() -> List[str]:
    rules = load_assembly_schema().get("compatibilityRules") or []
    if isinstance(rules, list):
        return [str(r) for r in rules if r]
    return []


_BUILTIN_PORTS = frozenset({
    "center", "bottom_center", "top_center", "bottom", "top", "mid",
    "north", "south", "east", "west", "nw", "ne", "sw", "se",
    "entrance", "exit", "in", "out",
    "front_left", "front_right", "back_left", "back_right",
})


def _fallback_schema() -> Dict[str, Any]:
    return {
        "schemaVersion": 3,
        "presets": [
            {"id": "spiral_watchtower"},
            {"id": "suspension_bridge_simple"},
            {"id": "gothic_shell_box"},
        ],
        "components": [],
        "compatibilityRules": [],
    }
