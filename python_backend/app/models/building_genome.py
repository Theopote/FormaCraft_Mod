from __future__ import annotations

from typing import Any, Dict, List, Optional
from pydantic import BaseModel, Field


class Archetype(BaseModel):
    id: str = "generic"
    confidence: float = 0.0


class Topology(BaseModel):
    layout: str = "rectangular"     # rectangular/circular/linear/radial/freeform
    composition: str = "single"     # single/cluster/chain/grid
    axis: str = "none"              # centered/axial/none
    levels: str = "mixed"           # horizontal/vertical/mixed


class Structure(BaseModel):
    type: str = "hybrid"            # solid/frame/hybrid/suspended
    massiveness: Optional[float] = None
    voidRatio: Optional[float] = None
    supports: Optional[str] = None  # central/distributed


class Form(BaseModel):
    repetition: Optional[str] = None
    progression: Optional[str] = None
    curvature: Optional[str] = None
    rhythm: Optional[str] = None


class Symmetry(BaseModel):
    type: str = "none"              # none/bilateral/radial/grid
    order: Optional[int] = None
    mirror: Optional[bool] = None


class Materials(BaseModel):
    primary: Optional[str] = None   # stone/wood/earth/metal/glass/mixed
    secondary: Optional[str] = None
    accent: Optional[str] = None
    textureBias: Optional[str] = None
    extra: Optional[Dict[str, Any]] = None


class CulturalStyle(BaseModel):
    region: Optional[str] = None
    era: Optional[str] = None
    keywords: Optional[List[str]] = None


class Constraints(BaseModel):
    maxHeight: Optional[int] = None
    respectTerrain: Optional[bool] = None
    insideSelectionOnly: Optional[bool] = None
    noModifyZones: Optional[List[str]] = None


class AIHints(BaseModel):
    reference: Optional[str] = None
    priority: Optional[List[str]] = None
    avoid: Optional[List[str]] = None
    extra: Optional[Dict[str, Any]] = None


class BuildingGenome(BaseModel):
    """
    BuildingGenome v1：FormaCraft 建筑 DNA 标准
    - 不描述具体方块/patch
    - 建议挂载方式：BuildingSpec.extra["genome"] = BuildingGenome(dict)
    """
    genomeVersion: str = Field(default="1.0")

    archetype: Archetype = Field(default_factory=Archetype)
    topology: Topology = Field(default_factory=Topology)
    structure: Structure = Field(default_factory=Structure)
    form: Form = Field(default_factory=Form)
    symmetry: Symmetry = Field(default_factory=Symmetry)

    modules: List[str] = Field(default_factory=list)
    materials: Materials = Field(default_factory=Materials)
    culturalStyle: CulturalStyle = Field(default_factory=CulturalStyle)
    constraints: Constraints = Field(default_factory=Constraints)
    aiHints: AIHints = Field(default_factory=AIHints)


