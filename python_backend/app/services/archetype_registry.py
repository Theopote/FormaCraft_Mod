from __future__ import annotations

from dataclasses import dataclass, field
from typing import Dict, List, Optional, Tuple


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
    defaults: ArchetypeDefaults = field(default_factory=ArchetypeDefaults)
    constraints: ArchetypeConstraints = field(default_factory=ArchetypeConstraints)
    scoring: ArchetypeScoring = field(default_factory=ArchetypeScoring)


# v1 Registry: keep it small and extensible (10~30 can cover most user requests)
_REGISTRY: Dict[str, ArchetypeDef] = {
    "tulou": ArchetypeDef(
        id="tulou",
        aliases=("土楼", "福建土楼", "永定土楼", "永定", "tulou", "fujian tulou", "yongding tulou"),
        category="landmark",
        generator_id="tulou",
        defaults=ArchetypeDefaults(diameter=20, floors=3),
        constraints=ArchetypeConstraints(min_diameter=12, max_diameter=80),
    ),
    "eiffel_tower": ArchetypeDef(
        id="eiffel_tower",
        aliases=("埃菲尔铁塔", "埃菲尔塔", "eiffel", "eiffel tower", "tour eiffel"),
        category="landmark",
        generator_id="eiffel_tower",
        defaults=ArchetypeDefaults(diameter=12),
    ),
    "temple_of_heaven": ArchetypeDef(
        id="temple_of_heaven",
        aliases=("天坛", "祈年殿", "temple of heaven", "qiniandian"),
        category="landmark",
        generator_id="temple_of_heaven",
    ),
    "golden_gate_bridge": ArchetypeDef(
        id="golden_gate_bridge",
        aliases=("金门大桥", "golden gate bridge", "golden gate"),
        category="infrastructure",
        generator_id="golden_gate_bridge",
    ),
    "great_wall": ArchetypeDef(
        id="great_wall",
        aliases=("长城", "万里长城", "great wall", "great wall of china"),
        category="fortification",
        generator_id="great_wall",
    ),
    "giant_wild_goose_pagoda": ArchetypeDef(
        id="giant_wild_goose_pagoda",
        aliases=("大慈恩寺", "大雁塔", "giant wild goose pagoda", "dayanta"),
        category="landmark",
        generator_id="giant_wild_goose_pagoda",
    ),
}


def get_archetype_def(archetype_id: str) -> Optional[ArchetypeDef]:
    if not archetype_id:
        return None
    return _REGISTRY.get(archetype_id.strip().lower())


def list_archetype_ids() -> List[str]:
    return list(_REGISTRY.keys())


def all_archetypes() -> List[ArchetypeDef]:
    return list(_REGISTRY.values())


def alias_index() -> Dict[str, str]:
    """
    Map alias(lower) -> archetype_id.
    """
    m: Dict[str, str] = {}
    for a in _REGISTRY.values():
        for alias in a.aliases:
            k = (alias or "").strip().lower()
            if not k:
                continue
            # first win
            if k not in m:
                m[k] = a.id
    return m


