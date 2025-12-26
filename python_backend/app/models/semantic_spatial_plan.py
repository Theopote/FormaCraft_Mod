from __future__ import annotations

from typing import List, Literal, Optional

from pydantic import BaseModel, Field


SemanticZoneType = Literal[
    "CORE",
    "PUBLIC",
    "SEMI_PUBLIC",
    "PRIVATE",
    "SERVICE",
    "LANDSCAPE",
    "TRANSITION",
    "CIRCULATION",
]

TerrainPolicy = Literal[
    "PRESERVE_DOMINANT",
    "BALANCED",
    "ENGINEERED",
]

RelationType = Literal[
    "DIRECT",
    "BUFFERED",
    "FORBIDDEN",
    "AXIAL",
    "VISUAL_ONLY",
]

DisturbanceLevel = Literal["LOW", "MEDIUM", "HIGH"]


class SemanticZone(BaseModel):
    id: str
    type: SemanticZoneType
    priority: int = Field(ge=1, le=10)
    terrain_policy: TerrainPolicy
    notes: str = ""


class SemanticRelation(BaseModel):
    from_zone: str = Field(alias="from")
    to_zone: str = Field(alias="to")
    relation: RelationType
    notes: str = ""

    class Config:
        populate_by_name = True


class CirculationPlan(BaseModel):
    primary_flow: List[str] = Field(default_factory=list)
    secondary_flow: List[str] = Field(default_factory=list)


class SemanticConstraints(BaseModel):
    prefer_axis_alignment: bool = False
    avoid_direct_private_access: bool = True
    max_terrain_disturbance: DisturbanceLevel = "MEDIUM"
    # optional numeric budget (blocks) to avoid ambiguous LOW/MEDIUM/HIGH mappings
    terrain_budget_blocks: Optional[int] = Field(default=None, ge=0)


class SemanticSpatialPlan(BaseModel):
    zones: List[SemanticZone] = Field(default_factory=list)
    relations: List[SemanticRelation] = Field(default_factory=list)
    circulation: CirculationPlan = Field(default_factory=CirculationPlan)
    constraints: SemanticConstraints = Field(default_factory=SemanticConstraints)


