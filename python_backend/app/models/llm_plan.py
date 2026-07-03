"""
Pydantic contract for LlmPlan JSON returned by the LLM (post-normalization).

Aligned with Java com.formacraft.common.llm.dto.LlmPlan and LlmPlanParser rules.
Validation runs in Python after _normalize_llm_plan_output(); failures surface as 502
with concrete field paths — do not rely on Java LlmPlanParser to repair bad output.
"""
from __future__ import annotations

import json
from typing import Any, Dict, List, Literal, Optional

from pydantic import BaseModel, ConfigDict, Field, ValidationError, field_validator, model_validator

from .building_genome import BuildingGenome

_COMPONENT_REQUEST_PREFIX = "component_request:"
_GROUP_REQUEST_PREFIX = "group_request:"

_FACADE_COMPONENT_TYPES = frozenset({
    "FACADE_WINDOWS",
    "FACADE",
    "WALL_FACADE",
})

_PLANAR_COMPONENT_TYPES = frozenset({
    "COURTYARD",
    "COURTYARD_SPACE",
    "PATH",
    "ROAD",
    "PAVING",
    "PLAZA",
    "PLAZA_CORE",
    "TERRACE",
    "TERRACE_PLAZA",
    "FLOOR",
    "GROUND",
    "TERRAIN",
    "PARKING",
    "GARDEN",
})

Facing = Literal["NORTH", "SOUTH", "EAST", "WEST"]
Symmetry = Literal["NONE", "MIRROR_X", "MIRROR_Z", "RADIAL"]
TerrainStrategy = Literal["PRESERVE", "ADAPTIVE", "TERRACE", "FLATTEN"]
LlmPlanMode = Literal["build", "patch"]
SkeletonType = Literal[
    "LINEAR_PATH",
    "RADIAL_RING",
    "GRID",
    "COMPOUND",
    "PATH_POLYLINE",
    "SPAN_SUSPENSION",
    "VERTICAL_TAPER",
    "VERTICAL_STACK",
]


class LlmPlanValidationError(Exception):
    """Raised when normalized LlmPlan JSON fails Pydantic / business-rule validation."""

    def __init__(self, errors: List[str]):
        self.errors = errors
        super().__init__("; ".join(errors))


class Vec3iModel(BaseModel):
    model_config = ConfigDict(extra="forbid")

    x: int
    y: int
    z: int


class DimensionsModel(BaseModel):
    model_config = ConfigDict(extra="forbid")

    width: int
    depth: int
    height: int


class GlobalConstraintsModel(BaseModel):
    model_config = ConfigDict(extra="allow")

    facing: Optional[Facing] = None
    symmetry: Optional[Symmetry] = None
    terrain_strategy: Optional[TerrainStrategy] = None


class SlotModel(BaseModel):
    model_config = ConfigDict(extra="allow")

    slot_id: str = Field(min_length=1)
    anchor: Vec3iModel
    facing: Optional[Facing] = None
    program: Optional[str] = None
    component_preset_id: Optional[str] = None
    component_preset: Optional[str] = None


class LayoutModel(BaseModel):
    model_config = ConfigDict(extra="allow")

    skeleton_type: Optional[SkeletonType] = None
    path_based: Optional[bool] = None
    slots: Optional[List[SlotModel]] = None

    @model_validator(mode="after")
    def validate_slots(self) -> "LayoutModel":
        slots = self.slots
        if not slots:
            return self
        seen: set[str] = set()
        for slot in slots:
            sid = slot.slot_id.strip()
            if sid in seen:
                raise ValueError(f"duplicate slot_id: {sid}")
            seen.add(sid)
        return self


class PatchBlockModel(BaseModel):
    model_config = ConfigDict(extra="allow")

    action: Optional[str] = None
    dx: int
    dy: int
    dz: int
    targetBlock: Optional[str] = None


class PatchBlockSectionModel(BaseModel):
    model_config = ConfigDict(extra="allow")

    origin: Vec3iModel
    blocks: List[PatchBlockModel] = Field(min_length=1)


class StyleAttributesModel(BaseModel):
    model_config = ConfigDict(extra="allow")

    wall_color: Optional[str] = None
    wall_material: Optional[str] = None
    roof_color: Optional[str] = None
    roof_material: Optional[str] = None
    accent_material: Optional[str] = None
    floor_material: Optional[str] = None
    decorative_elements: Optional[List[str]] = None
    custom_attributes: Optional[Dict[str, str]] = None


class ComponentModel(BaseModel):
    model_config = ConfigDict(extra="allow")

    component_type: str = Field(min_length=1)
    slot_id: Optional[str] = None
    relative_position: Vec3iModel
    dimensions: DimensionsModel
    features: List[str] = Field(default_factory=list)
    params: Dict[str, Any] = Field(default_factory=dict)

    @field_validator("features", mode="before")
    @classmethod
    def validate_features(cls, value: Any) -> List[str]:
        if value is None:
            return []
        if not isinstance(value, list):
            raise ValueError("features must be a list")
        out: List[str] = []
        for idx, item in enumerate(value):
            if item is None:
                continue
            if isinstance(item, dict):
                if "component_request" in item:
                    payload = item.get("component_request")
                    if payload is not None and not isinstance(payload, dict):
                        raise ValueError(
                            f"features[{idx}].component_request must be object, "
                            f"not {type(payload).__name__}"
                        )
                if "group_request" in item:
                    payload = item.get("group_request")
                    if payload is not None and not isinstance(payload, dict):
                        raise ValueError(
                            f"features[{idx}].group_request must be object, "
                            f"not {type(payload).__name__}"
                        )
                try:
                    out.append(json.dumps(item, ensure_ascii=False))
                except Exception:
                    out.append(str(item))
                continue
            text = str(item)
            if text.startswith(_COMPONENT_REQUEST_PREFIX):
                _validate_prefixed_json_payload(
                    text,
                    _COMPONENT_REQUEST_PREFIX,
                    f"features[{idx}].component_request",
                )
            elif text.startswith(_GROUP_REQUEST_PREFIX):
                _validate_prefixed_json_payload(
                    text,
                    _GROUP_REQUEST_PREFIX,
                    f"features[{idx}].group_request",
                )
            out.append(text)
        return out

    @model_validator(mode="after")
    def validate_dimensions_for_type(self) -> "ComponentModel":
        ctype = (self.component_type or "").upper()
        dims = self.dimensions
        is_planar = ctype in _PLANAR_COMPONENT_TYPES
        is_facade = ctype in _FACADE_COMPONENT_TYPES

        if dims.width <= 0:
            raise ValueError("dimensions.width must be > 0")

        if is_facade:
            if dims.depth < 0:
                raise ValueError("dimensions.depth must be >= 0 for facade components")
        elif dims.depth <= 0:
            raise ValueError("dimensions.depth must be > 0")

        if is_planar:
            if dims.height < 0:
                raise ValueError("dimensions.height must be >= 0 for planar components")
        elif dims.height <= 0:
            raise ValueError("dimensions.height must be > 0")

        return self


class LlmPlanModel(BaseModel):
    model_config = ConfigDict(extra="allow")

    mode: LlmPlanMode
    style_profile: Optional[str] = None
    anchor: Vec3iModel
    global_constraints: Optional[GlobalConstraintsModel] = None
    layout: Optional[LayoutModel] = None
    components: Optional[List[ComponentModel]] = None
    genome: Optional[BuildingGenome] = None
    style_attributes: Optional[StyleAttributesModel] = None
    target_slot_id: Optional[str] = None
    allowed_area: Optional[str] = None
    patch: Optional[PatchBlockSectionModel] = None
    plan_program: Optional[Dict[str, Any]] = None
    plan_skeleton: Optional[Dict[str, Any]] = None

    @model_validator(mode="after")
    def validate_patch_mode(self) -> "LlmPlanModel":
        if self.mode != "patch":
            return self
        has_components = bool(self.components)
        has_block_patch = bool(self.patch and self.patch.blocks)
        if not has_components and not has_block_patch:
            raise ValueError("mode=patch requires either components[] or patch.blocks[]")
        return self


def _validate_prefixed_json_payload(text: str, prefix: str, field_name: str) -> None:
    payload_text = text[len(prefix):]
    try:
        parsed = json.loads(payload_text)
    except json.JSONDecodeError as exc:
        raise ValueError(f"{field_name} payload is not valid JSON: {exc.msg}") from exc
    if not isinstance(parsed, dict):
        raise ValueError(f"{field_name} must be object, not {type(parsed).__name__}")


def format_pydantic_errors(exc: ValidationError) -> List[str]:
    messages: List[str] = []
    for err in exc.errors():
        path = _format_error_location(err.get("loc", ()))
        msg = str(err.get("msg", "invalid value"))
        if path:
            messages.append(f"{path}: {msg}")
        else:
            messages.append(msg)
    return messages


def _format_error_location(loc: tuple[Any, ...]) -> str:
    parts: List[str] = []
    for item in loc:
        if isinstance(item, int):
            parts.append(f"[{item}]")
        elif not parts:
            parts.append(str(item))
        else:
            parts.append(f".{item}")
    return "".join(parts)


def validate_llm_plan_dict(plan: Dict[str, Any]) -> Dict[str, Any]:
    """
    Validate a normalized LlmPlan dict. Returns the same dict on success.
    Raises LlmPlanValidationError with field paths on failure.
    """
    if not isinstance(plan, dict):
        raise LlmPlanValidationError(["root: must be object"])
    try:
        LlmPlanModel.model_validate(plan)
    except ValidationError as exc:
        raise LlmPlanValidationError(format_pydantic_errors(exc)) from exc
    return plan
