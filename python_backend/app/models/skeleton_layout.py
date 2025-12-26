from __future__ import annotations

from typing import List, Literal, Optional

from pydantic import BaseModel, Field


SkeletonShape = Literal["POINT", "RECTANGLE", "CIRCLE", "POLYGON", "LINEAR"]


class IntVec3(BaseModel):
    x: int
    y: int
    z: int


class SkeletonNode(BaseModel):
    zone: str
    zoneType: Optional[str] = None  # CORE/PUBLIC/SEMI_PUBLIC/PRIVATE/SERVICE/LANDSCAPE/TRANSITION/CIRCULATION (optional)
    shape: SkeletonShape
    anchor: IntVec3
    facing: Optional[str] = None  # NORTH/SOUTH/EAST/WEST (optional)

    # shape params (optional, depending on shape)
    width: Optional[int] = Field(default=None, ge=1)
    depth: Optional[int] = Field(default=None, ge=1)
    radius: Optional[int] = Field(default=None, ge=1)

    # polygon / linear hints
    points: Optional[List[IntVec3]] = None
    notes: str = ""


class SkeletonLayout(BaseModel):
    skeletons: List[SkeletonNode] = Field(default_factory=list)


