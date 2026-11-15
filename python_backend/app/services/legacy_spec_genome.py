"""Genome completion for frozen BuildingSpec compatibility routes."""

from typing import Any, Dict, Optional

from ..models.request import BuildRequest
from ..models.building_spec import BuildingSpec
from ..models.building_genome import BuildingGenome
from ..models.composite_spec import CompositeSpec
from ..models.city_spec import CitySpec


def _deep_merge(base: Dict[str, Any], override: Dict[str, Any]) -> Dict[str, Any]:
    out = dict(base or {})
    for k, v in (override or {}).items():
        if isinstance(v, dict) and isinstance(out.get(k), dict):
            out[k] = _deep_merge(out.get(k, {}), v)
        else:
            out[k] = v
    return out


def _semantic_material_from_block(block_id: Optional[str]) -> Optional[str]:
    if not block_id:
        return None
    s = str(block_id).lower()
    if "glass" in s:
        return "glass"
    if "planks" in s or "wood" in s or "log" in s:
        return "wood"
    if "brick" in s or "stone" in s or "deepslate" in s or "cobble" in s:
        return "stone"
    if "terracotta" in s or "clay" in s:
        return "earth"
    if "iron" in s or "copper" in s or "gold" in s or "metal" in s:
        return "metal"
    return "mixed"


def _build_genome_from_spec(req: Optional[BuildRequest], spec: BuildingSpec) -> BuildingGenome:
    g = BuildingGenome()
    if spec is None:
        return g

    fp = spec.footprint
    if fp and fp.shape:
        shape = fp.shape.lower()
        if shape == "circle":
            g.topology.layout = "circular"
            g.symmetry.type = "radial"
        elif shape == "rectangle":
            g.topology.layout = "rectangular"

    if spec.style is not None:
        from .style_profile_registry import get_style_profile
        style = spec.style.value if hasattr(spec.style, "value") else spec.style
        profile = get_style_profile(style)
        cultural = (profile.defaults.get("genome") or {}).get("culturalStyle", {}) if profile else {}
        g.culturalStyle = g.culturalStyle.model_copy(update=cultural)

    if spec.materials is not None:
        g.materials.primary = _semantic_material_from_block(spec.materials.wall)
        g.materials.secondary = _semantic_material_from_block(spec.materials.roof)
        g.materials.accent = _semantic_material_from_block(spec.materials.window)

    if spec.features is not None:
        if spec.features.hasRoof:
            g.modules.append("roof")
        if spec.features.hasWindows:
            g.modules.append("windows")
        if spec.features.hasBalcony:
            g.modules.append("balcony")

    if spec.styleOptions is not None and spec.styleOptions.roofType:
        rt = spec.styleOptions.roofType.lower()
        if rt in ("pyramid", "cone"):
            g.form.progression = "tapering"
        if rt in ("gable", "hipped"):
            g.form.curvature = "straight"

    if spec.height and fp and fp.width and fp.depth:
        footprint = max(1, max(fp.width, fp.depth))
        ratio = spec.height / float(footprint)
        if ratio >= 1.2:
            g.form.progression = g.form.progression or "upward"
        g.structure.massiveness = min(1.0, max(0.2, ratio / 2.5))

    if req is not None:
        g.constraints.insideSelectionOnly = req.selection is not None or req.outline is not None
        g.constraints.respectTerrain = True

    return g


def _default_component_params_for_spec(spec: BuildingSpec) -> Dict[str, Any]:
    if spec is None:
        return {}
    params = {}
    fp = spec.footprint
    if fp and fp.shape:
        params["shape"] = fp.shape.lower()
    if spec.styleOptions is not None and spec.styleOptions.roofType:
        params["roof_type"] = spec.styleOptions.roofType
    if spec.styleOptions is not None:
        params["window_ratio"] = spec.styleOptions.windowRatio
    if spec.floors:
        params["floor_count"] = spec.floors
        params["floor_height"] = max(3, int(spec.height / max(1, spec.floors)))
    if spec.extra is not None:
        plan_type = spec.extra.get("plan_type") or spec.extra.get("planType") or spec.extra.get("footprint_pattern")
        if plan_type:
            params["plan_type"] = plan_type
    return {"MASS_MAIN": params}


def _ensure_genome_for_spec(spec: BuildingSpec, req: Optional[BuildRequest]) -> BuildingSpec:
    if spec is None:
        return spec
    if spec.extra is None:
        spec.extra = {}
    if "genome" not in spec.extra or not isinstance(spec.extra.get("genome"), dict):
        spec.extra["genome"] = _build_genome_from_spec(req, spec).model_dump()
    else:
        default = _build_genome_from_spec(req, spec).model_dump()
        spec.extra["genome"] = _deep_merge(default, spec.extra.get("genome", {}))
    if "component_params" not in spec.extra:
        spec.extra["component_params"] = _default_component_params_for_spec(spec)
    return spec


def _ensure_genome_for_composite_spec(spec: CompositeSpec, req: Optional[BuildRequest]) -> CompositeSpec:
    if spec is None:
        return spec
    for entry in (spec.structures or []):
        if entry is None or getattr(entry, "spec", None) is None:
            continue
        entry.spec = _ensure_genome_for_spec(entry.spec, req)
    return spec


def _ensure_genome_for_city_spec(spec: CitySpec, req: Optional[BuildRequest]) -> CitySpec:
    if spec is None:
        return spec
    for entry in (spec.structures or []):
        if entry is None or getattr(entry, "spec", None) is None:
            continue
        entry.spec = _ensure_genome_for_spec(entry.spec, req)
    return spec
