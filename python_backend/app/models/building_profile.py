"""
BuildingProfile — 开放世界建筑研究阶段的结构化输出。

Research Agent 从网络检索 + 规则/LLM 归纳生成；Plan 阶段作为 LlmPlan 生成的约束输入。
"""

from __future__ import annotations

from typing import Any, Dict, List, Optional

from pydantic import BaseModel, Field


class ProfileIdentity(BaseModel):
    name: str = ""
    architect: Optional[str] = None
    year: Optional[int] = None
    style: Optional[str] = None
    confidence: float = Field(default=0.5, ge=0.0, le=1.0)


class ProfileForm(BaseModel):
    footprint: str = "rectangular"
    massing: List[str] = Field(default_factory=list)
    stories: Optional[int] = None
    aspect_ratio: Optional[str] = None


class ProfileStructure(BaseModel):
    roof_types: List[str] = Field(default_factory=list)
    facade: List[str] = Field(default_factory=list)
    distinctive_elements: List[str] = Field(default_factory=list)


class ProfileScaleHints(BaseModel):
    typical_width_blocks: Optional[int] = None
    typical_depth_blocks: Optional[int] = None
    typical_height_blocks: Optional[int] = None


class ProfileMinecraftStrategy(BaseModel):
    skeleton_type: str = "COMPOUND"
    recommended_components: List[str] = Field(
        default_factory=lambda: ["MASS_MAIN", "ROOF", "FACADE_WINDOWS", "ENTRANCE"]
    )
    landmark_module: Optional[str] = None
    notes: Optional[str] = None


class ProfileSource(BaseModel):
    title: str = ""
    url: str = ""


class BuildingProfile(BaseModel):
    query: str = ""
    identity: ProfileIdentity = Field(default_factory=ProfileIdentity)
    form: ProfileForm = Field(default_factory=ProfileForm)
    structure: ProfileStructure = Field(default_factory=ProfileStructure)
    scale_hints: ProfileScaleHints = Field(default_factory=ProfileScaleHints)
    minecraft_strategy: ProfileMinecraftStrategy = Field(default_factory=ProfileMinecraftStrategy)
    sources: List[ProfileSource] = Field(default_factory=list)
    research_notes: Optional[str] = None
    reference_blueprint: Optional[Dict[str, Any]] = None

    def to_prompt_dict(self) -> Dict[str, Any]:
        """Compact dict for LLM prompt injection."""
        return self.model_dump(exclude_none=True)


def validate_building_profile(data: Any) -> BuildingProfile:
    if isinstance(data, BuildingProfile):
        return data
    if not isinstance(data, dict):
        raise ValueError("BuildingProfile must be a dict")
    return BuildingProfile.model_validate(data)


def profile_from_llm_dict(data: Dict[str, Any], *, fallback_query: str = "") -> BuildingProfile:
    """Merge LLM output with defaults; tolerate partial responses."""
    base = BuildingProfile(query=fallback_query or str(data.get("query") or ""))
    if not data:
        return base
    try:
        merged = base.model_dump()
        for key in ("identity", "form", "structure", "scale_hints", "minecraft_strategy"):
            if isinstance(data.get(key), dict):
                merged[key] = {**merged.get(key, {}), **data[key]}
        if data.get("query"):
            merged["query"] = data["query"]
        if isinstance(data.get("sources"), list):
            merged["sources"] = data["sources"]
        if data.get("research_notes"):
            merged["research_notes"] = data["research_notes"]
        if isinstance(data.get("reference_blueprint"), dict):
            merged["reference_blueprint"] = data["reference_blueprint"]
        return BuildingProfile.model_validate(merged)
    except Exception:
        return base
