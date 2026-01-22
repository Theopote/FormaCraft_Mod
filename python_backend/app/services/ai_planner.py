"""
AI Planner Service
调用大模型生成 BuildingSpec 或 CompositeSpec
"""
import os
import json
import re
import logging
import concurrent.futures
from typing import Any, Dict, List, Optional, Union

logger = logging.getLogger(__name__)

try:
    from openai import OpenAI
    HAS_OPENAI = True
except ImportError:
    HAS_OPENAI = False
    OpenAI = None

from ..models.request import BuildRequest
from .llm_client import get_client, build_config
from ..models.building_spec import (
    BuildingSpec, BuildingType, BuildingStyle, 
    Footprint, Materials, Features, StyleOptions
)
from ..models.building_genome import BuildingGenome
from ..models.composite_spec import CompositeSpec, SubStructure, Vec3i, PathSpec
from ..models.city_spec import CitySpec, Zone, StructurePlan, BridgePlan, Point
from ..models.semantic_spatial_plan import SemanticSpatialPlan
from ..models.skeleton_layout import SkeletonLayout, SkeletonNode, IntVec3
from .archetype_detector import detect_archetype_local, should_force_strong_mode
from .archetype_registry import get_archetype_def

_LLM_CALL_TIMEOUT_SEC = float(os.getenv("LLM_CALL_TIMEOUT_SEC", "45"))
_LLM_CALL_TIMEOUT_DEEPSEEK_SEC = float(os.getenv("LLM_CALL_TIMEOUT_DEEPSEEK_SEC", "60"))
_LLM_CALL_TIMEOUT_REASONER_SEC = float(os.getenv("LLM_CALL_TIMEOUT_REASONER_SEC", "120"))
# LlmPlan tends to be more verbose; allow longer timeouts by default.
# LlmPlan 通常需要更长的处理时间，特别是对于复杂的 prompt 和大型 JSON 输出
_LLM_CALL_TIMEOUT_LLMPLAN_SEC = float(os.getenv("LLM_CALL_TIMEOUT_LLMPLAN_SEC", "180"))  # 增加到 180 秒
# Composite/City 往往比单体 BuildingSpec 更慢；默认给更长时间（可通过环境变量覆盖）
_LLM_CALL_TIMEOUT_COMPOSITE_SEC = float(os.getenv("LLM_CALL_TIMEOUT_COMPOSITE_SEC", "600"))
_LLM_CALL_TIMEOUT_CITY_SEC = float(os.getenv("LLM_CALL_TIMEOUT_CITY_SEC", "600"))


def _int_env(name: str, default: int) -> int:
    try:
        v = os.getenv(name)
        if v is None or str(v).strip() == "":
            return default
        return int(float(str(v).strip()))
    except Exception:
        return default


def _resolve_rag_budget(req: Optional[BuildRequest]) -> dict:
    """
    RAG prompt-injection budget.
    Priority: request.ragBudget.* > env vars > defaults.
    """
    # defaults (conservative)
    topK = _int_env("RAG_TOPK", 3)
    fewShotK = _int_env("RAG_FEWSHOTK", 3)
    maxItems = _int_env("RAG_MAX_ITEMS", 2)
    maxExampleChars = _int_env("RAG_MAX_EXAMPLE_CHARS", 1600)
    maxChars = _int_env("RAG_MAX_CHARS", 6000)

    if req is not None:
        rb = getattr(req, "ragBudget", None)
        if rb is not None:
            for k in ("topK", "fewShotK", "maxItems", "maxExampleChars", "maxChars"):
                try:
                    v = getattr(rb, k, None)
                    if v is None:
                        continue
                    iv = int(v)
                    if k == "topK":
                        topK = max(1, iv)
                    elif k == "fewShotK":
                        fewShotK = max(0, iv)
                    elif k == "maxItems":
                        maxItems = max(0, iv)
                    elif k == "maxExampleChars":
                        maxExampleChars = max(200, iv)
                    elif k == "maxChars":
                        maxChars = max(400, iv)
                except Exception:
                    pass

    return dict(
        topK=topK,
        fewShotK=fewShotK,
        maxItems=maxItems,
        maxExampleChars=maxExampleChars,
        maxChars=maxChars,
    )


def _call_with_timeout(fn, timeout_sec: float):
    """Run a blocking function with a hard timeout; do not block shutdown on timeout."""
    ex = concurrent.futures.ThreadPoolExecutor(max_workers=1)
    fut = None
    try:
        fut = ex.submit(fn)
        return fut.result(timeout=timeout_sec)
    except concurrent.futures.TimeoutError as e:
        if fut is not None:
            try:
                fut.cancel()
            except Exception:
                pass
        raise TimeoutError(f"LLM call timed out after {timeout_sec}s") from e
    finally:
        try:
            ex.shutdown(wait=False, cancel_futures=True)
        except Exception:
            pass


def _clamp_temperature(v: Optional[float], default: float) -> float:
    try:
        if v is None:
            return default
        f = float(v)
        if f < 0.0:
            return 0.0
        if f > 1.0:
            return 1.0
        return f
    except Exception:
        return default


def _resolve_model(req: Optional[BuildRequest], default: str) -> str:
    # 统一从 llm_client 解析（支持 DeepSeek/OpenAI-compatible）
    cfg = build_config(req, default_model=default)
    return cfg.model


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
        style = str(spec.style.value if hasattr(spec.style, "value") else spec.style).lower()
        if "asian" in style:
            g.culturalStyle.region = "chinese"
            g.culturalStyle.era = "traditional"
        elif "medieval" in style:
            g.culturalStyle.region = "european"
            g.culturalStyle.era = "medieval"
        elif "futuristic" in style:
            g.culturalStyle.region = "modern"
            g.culturalStyle.era = "modern"
        elif "modern" in style:
            g.culturalStyle.region = "modern"
            g.culturalStyle.era = "modern"
        elif "rustic" in style:
            g.culturalStyle.region = "rustic"
            g.culturalStyle.era = "traditional"

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


def _attach_archetype_genome(
    spec: BuildingSpec,
    req: Optional[BuildRequest],
    arche: Optional[Any],
    mode: Optional[str],
    include_reason_tags: bool = False,
) -> BuildingSpec:
    spec = _ensure_genome_for_spec(spec, req)
    if spec is None or arche is None:
        spec = _ensure_genome_for_city_spec(spec, req)
        return _ensure_genome_for_spec(spec, req)
    try:
        if spec.extra is None:
            spec.extra = {}
        genome = spec.extra.get("genome")
        if not isinstance(genome, dict):
            genome = _build_genome_from_spec(req, spec).model_dump()
            spec.extra["genome"] = genome
        genome["archetype"] = {
            "id": str(getattr(arche, "id", "generic")),
            "confidence": float(getattr(arche, "confidence", 0.0)),
        }
        if mode:
            spec.extra["archetypeMode"] = mode
        if include_reason_tags:
            reason_tags = getattr(arche, "reason_tags", None)
            if reason_tags is not None:
                spec.extra["archetypeReasonTags"] = list(reason_tags)
    except Exception:
        pass
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


def _fill_component_params(params: Dict[str, Any], comp: Dict[str, Any], genome: Dict[str, Any]) -> None:
    ctype = str(comp.get("component_type", "")).upper()
    features = comp.get("features") or []
    dims = comp.get("dimensions") or {}

    def feat_contains(token: str) -> bool:
        return any(token in str(f).lower() for f in features if f)

    def genome_contains(token: str) -> bool:
        cultural = genome.get("culturalStyle") or {}
        region = str(cultural.get("region") or "").lower()
        keywords = cultural.get("keywords") or []
        if token and token in region:
            return True
        return any(token in str(k).lower() for k in keywords if k)

    if ctype in ("MASS_MAIN", "MASS_SECONDARY", "MASS_WING", "SIDE_WING"):
        if "shape" not in params:
            layout = ((genome.get("topology") or {}).get("layout") or "").lower()
            if "circular" in layout or feat_contains("round") or feat_contains("circle"):
                params["shape"] = "circle"
            elif feat_contains("rounded"):
                params["shape"] = "rounded_rect"
            else:
                params["shape"] = "rectangle"
        if "roof_type" not in params:
            if feat_contains("xieshan") or feat_contains("hip_and_gable") or feat_contains("歇山"):
                params["roof_type"] = "xieshan"
            elif feat_contains("xuanshan") or feat_contains("悬山"):
                params["roof_type"] = "xuanshan"
            elif feat_contains("double_gable") or feat_contains("double gable") or feat_contains("shuangpo") or feat_contains("双坡"):
                params["roof_type"] = "double_gable"
            elif feat_contains("gable"):
                params["roof_type"] = "gable"
            elif feat_contains("hip"):
                params["roof_type"] = "hip"
            elif feat_contains("pyramid"):
                params["roof_type"] = "pyramid"
            elif feat_contains("cone"):
                params["roof_type"] = "cone"
            elif feat_contains("dome") or feat_contains("curved"):
                params["roof_type"] = "dome"
        if "roof_type" not in params:
            if feat_contains("chinese") or feat_contains("hui") or genome_contains("chinese") or genome_contains("hui"):
                params["roof_type"] = "xuanshan"
            elif feat_contains("gothic") or feat_contains("medieval") or genome_contains("gothic") or genome_contains("medieval"):
                params["roof_type"] = "gable"
            elif feat_contains("modern") or genome_contains("modern"):
                params["roof_type"] = "flat"
        if "window_ratio" not in params:
            params["window_ratio"] = 0.6 if feat_contains("glass") or feat_contains("curtain") else 0.35
        if "void_ratio" not in params:
            vr = ((genome.get("structure") or {}).get("voidRatio"))
            params["void_ratio"] = vr if vr is not None else (0.35 if feat_contains("courtyard") else 0.15)
        if "plan_type" not in params:
            plan_alias = params.get("footprint_pattern") or params.get("plan_pattern") or params.get("planPattern")
            if plan_alias:
                params["plan_type"] = plan_alias
            else:
                plan_type = None
                if feat_contains("courtyard") or feat_contains("siheyuan") or "courtyard" in (genome.get("modules") or []):
                    plan_type = "courtyard"
                elif feat_contains("gothic") or feat_contains("cathedral") or feat_contains("cruciform"):
                    plan_type = "cross"
                elif feat_contains("l-shape") or feat_contains("l_shape") or feat_contains("corner_wing"):
                    plan_type = "l_shape"
                elif feat_contains("chinese") or feat_contains("hui") or genome_contains("chinese") or genome_contains("hui"):
                    plan_type = "cut_corners"
                if plan_type:
                    params["plan_type"] = plan_type
        if params.get("plan_type") == "courtyard" and "courtyard_ratio" not in params:
            ratio = params.get("void_ratio")
            if isinstance(ratio, (int, float)):
                params["courtyard_ratio"] = float(max(0.2, min(0.6, ratio)))
        if "setback_ratio" not in params:
            prog = ((genome.get("form") or {}).get("progression") or "").lower()
            if prog in ("stepping", "tapering"):
                params["setback_ratio"] = 0.06
        if "floor_height" not in params and dims.get("height"):
            params["floor_height"] = max(3, int(dims.get("height", 6) / max(1, dims.get("height", 6) // 3)))

    if ctype == "FACADE_WINDOWS":
        if "window_ratio" not in params:
            params["window_ratio"] = 0.5 if feat_contains("large") else 0.35
        if "window_style" not in params:
            if feat_contains("lattice"):
                params["window_style"] = "lattice"
            elif feat_contains("stained"):
                params["window_style"] = "stained"
        if "rhythm" not in params:
            rhythm = ((genome.get("form") or {}).get("rhythm"))
            if rhythm:
                params["rhythm"] = rhythm

    if ctype == "ENTRANCE":
        if "door_width" not in params and dims.get("width"):
            params["door_width"] = max(2, int(dims.get("width", 3)) - 2)
        if "door_height" not in params and dims.get("height"):
            params["door_height"] = min(4, int(dims.get("height", 4)))


_COMPONENT_TYPE_ALIASES = {
    "MAIN_MASS": "MASS_MAIN",
    "BUTTRESS": "WALL",
}

_MASS_COMPONENT_TYPES = {
    "MASS_MAIN",
    "MASS_SECONDARY",
    "MASS_WING",
    "SIDE_WING",
    "MAIN_MASS",
}

_FACADE_COMPONENT_TYPES = {
    "FACADE_WINDOWS",
    "FACADE",
    "WALL_FACADE",
}

_PLANAR_COMPONENT_TYPES = {
    "COURTYARD",
    "COURTYARD_SPACE",
    "PATH",
    "ROAD",
    "PAVING",
    "PLAZA",
    "PLAZA_CORE",
    "TERRACE",
    "TERRACE_PLAZA",
}

_ROOF_COMPONENT_TYPES = {
    "ROOF",
    "ROOF_STRUCTURE",
}


def _normalize_component_type(value: Any) -> str:
    if value is None:
        return ""
    upper = str(value).strip().upper()
    return _COMPONENT_TYPE_ALIASES.get(upper, upper)


def _normalize_features(value: Any) -> List[str]:
    if value is None:
        return []
    if isinstance(value, list):
        out: List[str] = []
        for v in value:
            if v is None:
                continue
            if isinstance(v, dict):
                if "component_request" in v:
                    payload = v.get("component_request")
                    if payload is not None:
                        out.append("component_request:" + json.dumps(payload, ensure_ascii=False))
                        continue
                if "group_request" in v:
                    payload = v.get("group_request")
                    if payload is not None:
                        out.append("group_request:" + json.dumps(payload, ensure_ascii=False))
                        continue
                try:
                    s = json.dumps(v, ensure_ascii=False)
                except Exception:
                    s = str(v)
                if s.strip():
                    out.append(s)
                continue
            s = str(v)
            if s.strip():
                out.append(s)
        return out
    if isinstance(value, dict):
        return _normalize_features([value])
    if isinstance(value, str):
        return [value]
    return []


def _repair_component_request_strings(raw: str) -> str:
    if not raw:
        return raw
    if "component_request:" not in raw and "group_request:" not in raw:
        return raw
    prefixes = ("\"component_request:", "\"group_request:")
    i = 0
    n = len(raw)
    out: List[str] = []
    while i < n:
        matched = None
        for prefix in prefixes:
            if raw.startswith(prefix, i):
                matched = prefix
                break
        if matched is None:
            out.append(raw[i])
            i += 1
            continue
        key = "component_request" if matched.startswith("\"component_request") else "group_request"
        out.append("{\"")
        out.append(key)
        out.append("\":")
        i += len(matched)
        if i >= n or raw[i] != "{":
            if i < n:
                out.append(raw[i])
                i += 1
            continue
        start = i
        depth = 0
        in_str = False
        escape = False
        while i < n:
            ch = raw[i]
            if escape:
                escape = False
            elif ch == "\\":
                escape = True
            elif ch == "\"":
                in_str = not in_str
            elif not in_str:
                if ch == "{":
                    depth += 1
                elif ch == "}":
                    depth -= 1
                    if depth == 0:
                        i += 1
                        break
            i += 1
        out.append(raw[start:i])
        out.append("}")
        if i < n and raw[i] == "\"":
            i += 1
    return "".join(out)


def _coerce_int(value: Any, fallback: Optional[int]) -> Optional[int]:
    try:
        if value is None:
            return fallback
        return int(value)
    except Exception:
        return fallback


def _normalize_vec3(value: Any) -> Dict[str, int]:
    if isinstance(value, dict):
        return {
            "x": _coerce_int(value.get("x"), 0) or 0,
            "y": _coerce_int(value.get("y"), 0) or 0,
            "z": _coerce_int(value.get("z"), 0) or 0,
        }
    if isinstance(value, (list, tuple)) and len(value) >= 3:
        return {
            "x": _coerce_int(value[0], 0) or 0,
            "y": _coerce_int(value[1], 0) or 0,
            "z": _coerce_int(value[2], 0) or 0,
        }
    return {"x": 0, "y": 0, "z": 0}


def _extract_mass_dims(components: List[Any]) -> Optional[Dict[str, int]]:
    for comp in components:
        if not isinstance(comp, dict):
            continue
        ctype = _normalize_component_type(comp.get("component_type"))
        if ctype not in _MASS_COMPONENT_TYPES:
            continue
        dims = comp.get("dimensions")
        if not isinstance(dims, dict):
            continue
        width = _coerce_int(dims.get("width"), None)
        depth = _coerce_int(dims.get("depth"), None)
        height = _coerce_int(dims.get("height"), None)
        if width and width > 0 and depth and depth > 0 and height and height > 0:
            return {"width": width, "depth": depth, "height": height}
    return None


def _param_int(params: Dict[str, Any], *keys: str) -> Optional[int]:
    for key in keys:
        if key in params:
            value = _coerce_int(params.get(key), None)
            if value is not None:
                return value
    return None


def _param_string(params: Dict[str, Any], *keys: str) -> Optional[str]:
    for key in keys:
        if key in params:
            value = params.get(key)
            if value is None:
                continue
            text = str(value).strip()
            if text:
                return text
    return None


def _selection_dimensions(req: Optional[BuildRequest]) -> Optional[Dict[str, int]]:
    if req is None:
        return None
    sel = req.selection or req.brushSelection
    if sel is None:
        return None
    dx = abs(sel.max.x - sel.min.x) + 1
    dy = abs(sel.max.y - sel.min.y) + 1
    dz = abs(sel.max.z - sel.min.z) + 1
    return {"width": max(1, dx), "height": max(1, dy), "depth": max(1, dz)}


def _clamp_dimensions_to_selection(dims: Dict[str, int], selection_dims: Optional[Dict[str, int]]) -> Dict[str, int]:
    if not selection_dims:
        return dims
    return {
        "width": max(1, min(dims.get("width", 1), selection_dims["width"])),
        "height": max(1, min(dims.get("height", 1), selection_dims["height"])),
        "depth": max(1, min(dims.get("depth", 1), selection_dims["depth"])),
    }


def _selection_bounds_relative(req: Optional[BuildRequest], anchor: Optional[Dict[str, int]]) -> Optional[Dict[str, int]]:
    if req is None:
        return None
    sel = req.selection or req.brushSelection
    if sel is None:
        return None
    if anchor is None:
        anchor = {"x": req.player.pos.x, "y": req.player.pos.y, "z": req.player.pos.z}
    min_x = min(sel.min.x, sel.max.x) - anchor["x"]
    max_x = max(sel.min.x, sel.max.x) - anchor["x"]
    min_y = min(sel.min.y, sel.max.y) - anchor["y"]
    max_y = max(sel.min.y, sel.max.y) - anchor["y"]
    min_z = min(sel.min.z, sel.max.z) - anchor["z"]
    max_z = max(sel.min.z, sel.max.z) - anchor["z"]
    return {"min_x": min_x, "max_x": max_x, "min_y": min_y, "max_y": max_y, "min_z": min_z, "max_z": max_z}


def _clamp_value(value: int, min_value: int, max_value: int) -> int:
    return max(min_value, min(max_value, value))


def _clamp_relative_position(
    rp: Dict[str, int],
    dims: Dict[str, int],
    anchor_mode: str,
    bounds: Optional[Dict[str, int]],
) -> Dict[str, int]:
    if not bounds:
        return rp
    width = max(1, dims.get("width", 1))
    depth = max(1, dims.get("depth", 1))
    height = max(1, dims.get("height", 1))
    if anchor_mode == "min_corner":
        min_x = bounds["min_x"]
        max_x = bounds["max_x"] - (width - 1)
        min_z = bounds["min_z"]
        max_z = bounds["max_z"] - (depth - 1)
    else:
        half_x = width // 2
        half_z = depth // 2
        min_x = bounds["min_x"] + half_x
        max_x = bounds["max_x"] - (width - 1 - half_x)
        min_z = bounds["min_z"] + half_z
        max_z = bounds["max_z"] - (depth - 1 - half_z)
    if max_x < min_x:
        max_x = min_x
    if max_z < min_z:
        max_z = min_z
    min_y = bounds["min_y"]
    max_y = bounds["max_y"] - (height - 1)
    if max_y < min_y:
        max_y = min_y
    rp["x"] = _clamp_value(rp.get("x", 0), min_x, max_x)
    rp["y"] = _clamp_value(rp.get("y", 0), min_y, max_y)
    rp["z"] = _clamp_value(rp.get("z", 0), min_z, max_z)
    return rp


def _looks_like_min_corner_anchor(rp: Dict[str, int], dims: Dict[str, int]) -> bool:
    hx = max(1, dims.get("width", 1)) // 2
    hz = max(1, dims.get("depth", 1)) // 2
    return abs((rp.get("x", 0) + hx)) <= 1 and abs((rp.get("z", 0) + hz)) <= 1


def _infer_mass_height(params: Dict[str, Any], fallback: int) -> int:
    floor_height = _param_int(params, "floor_height", "floorHeight")
    floor_count = _param_int(params, "floor_count", "floorCount")
    if floor_height and floor_count:
        return max(3, floor_height * floor_count)
    if floor_height:
        return max(3, floor_height)
    return fallback


def _default_dimensions_for_component(
    ctype: str,
    params: Dict[str, Any],
    mass_dims: Optional[Dict[str, int]],
) -> Dict[str, int]:
    if ctype in _MASS_COMPONENT_TYPES:
        width = mass_dims["width"] if mass_dims else 8
        depth = mass_dims["depth"] if mass_dims else 6
        height = _infer_mass_height(params, mass_dims["height"] if mass_dims else 6)
        return {"width": width, "depth": depth, "height": height}
    if ctype.startswith("TOWER"):
        width = mass_dims["width"] if mass_dims else 6
        depth = mass_dims["depth"] if mass_dims else width
        height = mass_dims["height"] if mass_dims else 12
        return {"width": width, "depth": depth, "height": height}
    if ctype in _ROOF_COMPONENT_TYPES:
        width = mass_dims["width"] if mass_dims else 8
        depth = mass_dims["depth"] if mass_dims else 6
        height = _param_int(params, "roof_height", "roofHeight", "roofHeightBlocks") or 1
        return {"width": width, "depth": depth, "height": max(1, height)}
    if ctype in _FACADE_COMPONENT_TYPES:
        width = mass_dims["width"] if mass_dims else 4
        height = _param_int(params, "floor_height", "floorHeight") or 2
        return {"width": width, "depth": 1, "height": max(1, height)}
    if ctype in ("ENTRANCE", "ENTRANCE_CANOPY", "GATE", "GATE_STRUCTURE"):
        base_width = mass_dims["width"] if mass_dims else 4
        width = max(2, min(base_width, 4))
        height = _param_int(params, "door_height", "doorHeight") or 3
        return {"width": width, "depth": 1, "height": max(2, height)}
    if ctype == "SIGNAGE":
        base_width = mass_dims["width"] if mass_dims else 4
        width = max(2, min(base_width, 6))
        return {"width": width, "depth": 1, "height": 1}
    if ctype in _PLANAR_COMPONENT_TYPES:
        width = mass_dims["width"] if mass_dims else 6
        depth = mass_dims["depth"] if mass_dims else 6
        return {"width": width, "depth": depth, "height": 1}
    width = mass_dims["width"] if mass_dims else 4
    depth = mass_dims["depth"] if mass_dims else 4
    height = mass_dims["height"] if mass_dims else 3
    return {"width": width, "depth": depth, "height": height}


def _normalize_dimensions(
    dims: Any,
    ctype: str,
    params: Dict[str, Any],
    mass_dims: Optional[Dict[str, int]],
) -> Dict[str, int]:
    if not isinstance(dims, dict):
        dims = {}
    defaults = _default_dimensions_for_component(ctype, params, mass_dims)
    width = _coerce_int(dims.get("width"), defaults["width"]) or defaults["width"]
    depth = _coerce_int(dims.get("depth"), defaults["depth"])
    height = _coerce_int(dims.get("height"), defaults["height"])
    if width <= 0:
        width = defaults["width"]
    if depth is None or depth <= 0:
        depth = defaults["depth"]
    if height is None or height <= 0:
        height = defaults["height"]
    if ctype in _FACADE_COMPONENT_TYPES and depth <= 0:
        depth = 1
    if ctype in _PLANAR_COMPONENT_TYPES and height <= 0:
        height = 1
    return {"width": int(width), "depth": int(depth), "height": int(height)}


def _normalize_llm_plan_output(raw: Dict[str, Any], req: BuildRequest) -> Dict[str, Any]:
    if not isinstance(raw, dict):
        return raw
    plan = dict(raw)
    default_genome = BuildingGenome().model_dump()
    if req is not None:
        try:
            # Provide a minimal hint to topology based on selection/outline.
            if req.outline is not None:
                default_genome["constraints"]["insideSelectionOnly"] = True
        except Exception:
            pass

    if not isinstance(plan.get("genome"), dict):
        plan["genome"] = default_genome
    else:
        plan["genome"] = _deep_merge(default_genome, plan.get("genome", {}))

    components = plan.get("components")
    if isinstance(components, list):
        floors_hint = None
        explicit_courtyard = False
        explicit_roof = False
        is_chinese_style = False
        selection_dims = _selection_dimensions(req)
        selection_bounds = _selection_bounds_relative(req, plan.get("anchor") if isinstance(plan.get("anchor"), dict) else None)
        slot_anchors = {}
        layout = plan.get("layout")
        if isinstance(layout, dict):
            slots = layout.get("slots")
            if isinstance(slots, list):
                for slot in slots:
                    if not isinstance(slot, dict):
                        continue
                    slot_id = str(slot.get("slot_id") or "").strip()
                    if not slot_id:
                        continue
                    slot_anchors[slot_id] = _normalize_vec3(slot.get("anchor"))
        if req is not None:
            user_text = req.userMessage or ""
            floors_hint = _parse_levels_from_text(user_text)
            t_lower = user_text.lower()
            explicit_courtyard = any(k in t_lower for k in (
                "courtyard", "中庭", "庭院", "四合院", "院落", "内院"
            ))
            explicit_roof = any(k in t_lower for k in (
                "roof", "屋顶", "屋面", "歇山", "悬山", "双坡", "四坡",
                "gable", "hip", "xuanshan", "xieshan", "double_gable"
            ))
        style_profile = str(plan.get("style_profile") or "").lower()
        cultural = (plan.get("genome") or {}).get("culturalStyle") or {}
        region = str(cultural.get("region") or "").lower()
        is_chinese_style = ("chinese" in style_profile or "hui" in style_profile
                            or "chinese" in region or "hui" in region)
        skip_courtyard_components = is_chinese_style and not explicit_courtyard
        filtered_components = []
        mass_dims = _extract_mass_dims(components)
        for comp in components:
            if not isinstance(comp, dict):
                continue
            ctype = _normalize_component_type(comp.get("component_type"))
            if not ctype:
                continue
            comp["component_type"] = ctype
            comp["features"] = _normalize_features(comp.get("features"))
            params = comp.get("params")
            if not isinstance(params, dict):
                params = {}
            _fill_component_params(params, comp, plan.get("genome", {}))
            comp["params"] = params
            comp["relative_position"] = _normalize_vec3(comp.get("relative_position"))
            dims = _normalize_dimensions(comp.get("dimensions"), ctype, params, mass_dims)
            comp["dimensions"] = dims
            if ctype in ("MASS_MAIN", "MASS_SECONDARY", "MASS_WING", "SIDE_WING", "MAIN_MASS"):
                if ctype == "MASS_MAIN" and not _param_string(params, "anchor_mode", "anchorMode"):
                    rp = comp.get("relative_position") or {}
                    if _looks_like_min_corner_anchor(rp, dims):
                        params["anchor_mode"] = "min_corner"
                        rp["x"] = -(dims["width"] // 2)
                        rp["z"] = -(dims["depth"] // 2)
                        comp["relative_position"] = rp
                if params.get("plan_type") == "courtyard" and is_chinese_style and not explicit_courtyard:
                    params["plan_type"] = "cut_corners"
                    params.pop("courtyard_ratio", None)
                    vr = params.get("void_ratio")
                    if isinstance(vr, (int, float)) and vr > 0.2:
                        params["void_ratio"] = 0.2
                if is_chinese_style and not explicit_roof:
                    rt = str(params.get("roof_type") or "").lower()
                    if rt in ("", "hip", "pyramid", "xieshan"):
                        params["roof_type"] = "xuanshan"
            elif ctype == "ROOF" and is_chinese_style and not explicit_roof:
                rt = str(params.get("roof_type") or "").lower()
                if rt in ("", "hip", "pyramid", "xieshan"):
                    params["roof_type"] = "xuanshan"
            if floors_hint:
                if ctype in ("MASS_MAIN", "MASS_SECONDARY", "MASS_WING", "SIDE_WING", "MAIN_MASS"):
                    params.setdefault("floor_count", floors_hint)
                    params.setdefault("floor_height", 3)
                    dims = comp.get("dimensions") or {}
                    try:
                        height = int(dims.get("height", 0))
                    except Exception:
                        height = 0
                    min_height = max(3, int(params.get("floor_height", 3)) * int(params.get("floor_count", floors_hint)))
                    if height < min_height:
                        dims["height"] = min_height
                        comp["dimensions"] = dims
            dims = _clamp_dimensions_to_selection(comp.get("dimensions") or dims, selection_dims)
            comp["dimensions"] = dims
            anchor_mode = _param_string(params, "anchor_mode", "anchorMode")
            if not anchor_mode:
                if ctype.startswith("TOWER"):
                    anchor_mode = "center"
                elif ctype in _MASS_COMPONENT_TYPES:
                    anchor_mode = "center"
                else:
                    anchor_mode = "min_corner"
            slot_anchor = slot_anchors.get(str(comp.get("slot_id") or ""), {"x": 0, "y": 0, "z": 0})
            rp = comp.get("relative_position") or {"x": 0, "y": 0, "z": 0}
            effective_rp = {
                "x": int(rp.get("x", 0)) + int(slot_anchor.get("x", 0)),
                "y": int(rp.get("y", 0)) + int(slot_anchor.get("y", 0)),
                "z": int(rp.get("z", 0)) + int(slot_anchor.get("z", 0)),
            }
            effective_rp = _clamp_relative_position(effective_rp, dims, anchor_mode, selection_bounds)
            comp["relative_position"] = {
                "x": effective_rp["x"] - int(slot_anchor.get("x", 0)),
                "y": effective_rp["y"] - int(slot_anchor.get("y", 0)),
                "z": effective_rp["z"] - int(slot_anchor.get("z", 0)),
            }
            if mass_dims is None and ctype in _MASS_COMPONENT_TYPES:
                dims = comp.get("dimensions") or dims
                if isinstance(dims, dict):
                    mass_dims = {
                        "width": int(dims.get("width", 8)),
                        "depth": int(dims.get("depth", 6)),
                        "height": int(dims.get("height", 6)),
                    }
            if skip_courtyard_components and ctype == "COURTYARD":
                continue
            filtered_components.append(comp)
        plan["components"] = filtered_components
    return plan


def _build_system_prompt() -> str:
    from ..llm.skeleton_semantics import get_skeleton_semantics_prompt
    
    skeleton_block = get_skeleton_semantics_prompt()
    
    return (
        "You are FormaCraft, an AI architect for Minecraft.\n"
        "Your job is to convert a player's natural language request and world context "
        "into a structured BuildingSpec JSON object.\n\n"
        + skeleton_block + "\n\n"
        "CRITICAL: Understanding User Intent and Specific Buildings:\n"
        "- When the user mentions a specific building name (e.g., '鸟巢体育馆'/'Bird's Nest Stadium', '埃菲尔铁塔'/'Eiffel Tower', '天坛'/'Temple of Heaven'),\n"
        "  you MUST analyze the key architectural features of that building:\n"
        "  * Shape and form (e.g., elliptical, circular, rectangular, organic curves)\n"
        "  * Structural characteristics (e.g., steel frame, mesh structure, tiered platforms)\n"
        "  * Materials and textures (e.g., steel, concrete, glass, traditional materials)\n"
        "  * Scale and proportions (e.g., large stadium, tall tower, wide base)\n"
        "  * Distinctive features (e.g., mesh facade, spiral structure, tiered roofs)\n"
        "- For iconic buildings, you SHOULD:\n"
        "  1. Set type='CUSTOM' (unless it clearly matches HOUSE/TOWER/BRIDGE/CASTLE/WALL)\n"
        "  2. Use extra.assembly with appropriate components and macro parameters to capture the building's form\n"
        "  3. For complex structures, consider using extra.blueprint with blueprint_type matching the building name\n"
        "  4. Set appropriate styleProfileId if a matching style exists (e.g., Deconstructivism_Zaha for Bird's Nest)\n"
        "  5. Use macro parameters like twist, curvature, verticalProfile to create organic/non-rectangular shapes\n"
        "- If the building has a distinctive shape (e.g., elliptical stadium, curved facade), use extra.assembly.macro\n"
        "  with appropriate geometric parameters rather than just a simple rectangular footprint.\n\n"
        "Requirements:\n"
        "- Always respond with pure JSON (no comments, no extra text).\n"
        "- Only use block IDs that exist in vanilla Minecraft.\n"
        "- Respect constraints: keep buildings reasonably sized unless user requests huge.\n"
        "- Footprint should be within the selection area if provided, otherwise use default sizes.\n"
        "- If a brush selection is provided (without a regular selection), buildings should be generated within the brush-selected area on the ground surface.\n"
        "- If an outline is provided, you MUST build ONLY inside the outline.\n"
        "- If protected zones are provided, you MUST NOT place blocks inside any protected zone.\n"
        "- If semantic labels are provided, you MUST respect them:\n"
        "  * Each label binds architectural function to a spatial region.\n"
        "  * When generating components, check if they fall within labeled regions.\n"
        "  * Generate components according to the label's semantic meaning (e.g., 'entrance' → door, steps, decorations).\n"
        "  * The 'range' value indicates how far the label's influence extends.\n"
        "  * Multiple labels can be combined to form complex functional layouts.\n"
        "- Choose reasonable defaults when player does not specify something.\n"
        "- Building types: HOUSE, TOWER, BRIDGE, CASTLE, WALL, CUSTOM\n"
        "- Building styles: MEDIEVAL, MODERN, ASIAN, FUTURISTIC, RUSTIC, DEFAULT\n"
        "- For circular buildings (towers), use footprint.shape='circle' and set radius\n"
        "- For rectangular buildings, use footprint.shape='rectangle' and set width/depth\n"
        "- For L/U/courtyard/cross plans, keep footprint.shape='rectangle' and add footprint.shapeSpec with type+params\n"
        "- Example shapeSpec: {\"type\":\"COURTYARD\",\"params\":{\"width\":20,\"depth\":20,\"wall_thickness\":4},\"rotation\":0}\n"
        "- For elliptical or organic shapes, use footprint.shape='rectangle' with appropriate width/depth,\n"
        "  then use extra.assembly.macro to define the actual shape (e.g., using twist, curvature, verticalProfile)\n\n"
        "Field names are STRICT and must match exactly:\n"
        "- Use 'type' (NOT 'buildingType')\n"
        "- Use 'style' (NOT 'buildingStyle')\n"
        "- Always include 'materials' and 'features' objects (even if empty).\n\n"
        "Your response must include styleOptions with fields:\n"
        "- doorStyle (single/double/arched/none) - for houses and towers\n"
        "- roofType (flat/gable/cone/pyramid/hipped) - for houses and towers\n"
        "- bridgeType (flat/arched/suspension) - for bridges\n"
        "- windowRatio (0.0~1.0) - controls window density on walls\n"
        "- windowStyle (pane/fence/stained) - window appearance\n"
        "- wallPattern (uniform/striped/gradient/random) - wall texture pattern\n"
        "\nOptional extra fields (use when player requests special details):\n"
        "- If player asks for shutters / trapdoor window shutters / 百叶窗 / 木窗扇:\n"
        "  set extra.windowShutter=true and optionally extra.windowShutterOpen=true/false\n"
        "  You may set extra.windowShutterBlock like 'minecraft:oak_trapdoor'\n"
        "\nStyle gene library (optional, strongly recommended when user requests a specific architectural vibe):\n"
        "- You MAY set extra.styleProfileId to pick a fine-grained style profile (data-driven).\n"
        "- If set, it should be a string id from the StyleProfileCatalog provided in the user prompt.\n"
        "- Prefer extra.styleProfileId over coarse 'style' when the user asks for a specific vibe (e.g. cyber/steampunk/imperial).\n"
        "- If you set extra.styleProfileId, you MAY omit extra.paletteId (it will fallback to the style profile default palette).\n"
        "- If you set extra.paletteId, it MUST be one of the palette IDs listed in PaletteCatalog.\n"
        "- NEVER invent unknown styleProfileId/paletteId. If unsure, omit them.\n"
        "\nRAG context (CRITICAL - MUST USE WHEN PROVIDED):\n"
        "- The user prompt may include RAG context blocks that contain valuable architectural knowledge.\n"
        "  When these blocks are present, you MUST actively use them to inform your generation decisions.\n"
        "  Ignoring RAG context when available will result in lower-quality, less culturally accurate buildings.\n"
        "\n"
        "- AssemblyDraft(JSON): A minimal assembly structure (especially macro.style) as a starting point.\n"
        "  * You MUST use macro.style.* fields as strong hints for extra.assembly.macro.style.\n"
        "  * These values are pre-computed based on user intent and should guide your style choices.\n"
        "  * Example: If AssemblyDraft suggests 'density=0.8', incorporate dense decorative elements.\n"
        "\n"
        "- CultureRetrieval(JSON): Contains few-shot examples and hit metadata from architectural knowledge base.\n"
        "  * CRITICAL: You MUST analyze the fewShots array and use them as reference examples for generating extra.assembly structure.\n"
        "  * Each fewShot is a validated architectural example that matches the user's intent - these are proven, working examples.\n"
        "  * Study the structure, style patterns, material choices, and geometric forms in fewShots to generate similar quality output.\n"
        "  * Pay attention to:\n"
        "    - Component arrangements (how components are organized)\n"
        "    - Style patterns (recurring design elements)\n"
        "    - Material choices (block selections that create the desired aesthetic)\n"
        "    - Geometric relationships (how parts relate spatially)\n"
        "  * The hits array shows which cultural/architectural styles match the query - use this to refine styleProfileId.\n"
        "  * Example: If fewShots show '徽派建筑' (Huizhou architecture), generate buildings with characteristic features like white walls, black tiles, and courtyard layouts.\n"
        "  * IMPORTANT: When fewShots are provided, your output should reflect similar patterns and structures - these are your reference templates.\n"
        "\n"
        "- BuildingKnowledge(JSON): Contains detailed architectural features for specific landmark buildings.\n"
        "  * When present, you MUST analyze the building's features (shape, form, structuralType, materials, distinctiveElements)\n"
        "    and use assemblyHints to generate appropriate extra.assembly structure.\n"
        "  * The description fields provide context about the building's key characteristics - use them to ensure accuracy.\n"
        "  * Example: If BuildingKnowledge describes '鸟巢体育馆' with elliptical shape and mesh facade, generate extra.assembly with curved geometry and surface patterns.\n"
        "\n"
        "- StyleProfileCatalog: A list of available style profiles filtered by relevance.\n"
        "  * You MUST select a styleProfileId from this catalog when user requests a specific architectural style.\n"
        "  * The catalog is pre-filtered based on user query - use it instead of inventing style IDs.\n"
        "  * If no catalog is provided, you may omit styleProfileId or use a generic one.\n"
        "\n"
        "- PaletteCatalog: A list of available block palettes filtered by relevance.\n"
        "  * You MUST select a paletteId from this catalog to ensure block choices are valid.\n"
        "  * The catalog contains Minecraft-compatible block combinations optimized for specific styles.\n"
        "  * If styleProfileId is set and has a default palette, you may omit paletteId.\n"
        "\n"
        "- ACTION REQUIRED: When any RAG block is present, explicitly reference it in your reasoning:\n"
        "  * Use AssemblyDraft values → Set corresponding extra.assembly.macro.style fields\n"
        "  * Use CultureRetrieval fewShots → Generate similar structural patterns\n"
        "  * Use BuildingKnowledge features → Apply distinctive architectural elements\n"
        "  * Use StyleProfileCatalog → Select appropriate styleProfileId\n"
        "  * Use PaletteCatalog → Select appropriate paletteId\n"
        "- Do NOT copy RAG blocks into output; only use them to inform your decisions.\n"
        "\nLayout IR (STRONGLY RECOMMENDED when user mentions layout concepts):\n"
        "- When user mentions: symmetry/对称, axis/轴线, courtyard/中庭/庭院, entrance direction/入口方向, corridor/回廊:\n"
        "  You SHOULD set extra.layout as a JSON object with:\n"
        "  - entranceFacing: 'NORTH'|'SOUTH'|'EAST'|'WEST' (main entrance direction, default from player.facing)\n"
        "  - symmetry: 'NONE'|'X'|'Z'|'BOTH' (X=left/right symmetry, Z=front/back symmetry)\n"
        "    * Use 'X' when user says '左右对称' or 'symmetric left-right'\n"
        "    * Use 'Z' when user says '前后对称' or 'symmetric front-back'\n"
        "    * Use 'BOTH' when user says '完全对称' or 'fully symmetric'\n"
        "  - courtyard: true/false (whether building has an inner courtyard/中庭)\n"
        "  - courtyardRatio: 0.0~1.0 (courtyard size relative to footprint, default 0.3)\n"
        "  - plan: 'ring_corridor'|'central_hall'|'linear'|'grid' (layout plan type)\n"
        "    * 'ring_corridor' for 回廊/ring corridor around courtyard\n"
        "    * 'central_hall' for central hall/大厅\n"
        "    * 'linear' for linear arrangement/线性布局\n"
        "    * 'grid' for grid layout/网格布局\n"
        "- These layout fields are consumed by generators to create structured layouts.\n"
        "\nFunctional Zones (STRONGLY RECOMMENDED when user mentions functional areas):\n"
        "- When user mentions: '前店后宅'/'shop in front house in back', '一层商铺二层住宅'/'first floor shop second floor residence',\n"
        "  '仓库+装卸区'/'warehouse + loading area', or any functional division:\n"
        "  You SHOULD set extra.zones as an array of zone objects:\n"
        "  [\n"
        "    {\n"
        "      \"name\": \"shop\" (or \"residence\", \"warehouse\", \"loading\", etc.),\n"
        "      \"type\": \"COMMERCIAL\"|\"RESIDENTIAL\"|\"INDUSTRIAL\"|\"SERVICE\"|\"PUBLIC\",\n"
        "      \"aabb_relative\": {\"min\": {\"x\": 0, \"y\": 0, \"z\": 0}, \"max\": {\"x\": 8, \"y\": 10, \"z\": 6}},\n"
        "      \"rules\": [\"hasWindows\", \"hasDoor\", \"specificMaterial\"] (optional zone-specific rules)\n"
        "    }\n"
        "  ]\n"
        "- Zones define functional areas within a building. Coordinates are relative to building origin.\n"
        "- Multiple zones can overlap vertically (e.g., shop on floor 0, residence on floor 1).\n"
        "- Generators will use zones to apply different materials/features to different areas.\n"
        "\nConnectivity (optional, for complex buildings with multiple connected parts):\n"
        "- When user mentions: corridors/走廊, gates/门, bridges/桥 connecting parts:\n"
        "  You MAY set extra.connectivity as an array:\n"
        "  [\n"
        "    {\"from\": \"zone1\", \"to\": \"zone2\", \"type\": \"corridor\"|\"gate\"|\"bridge\"}\n"
        "  ]\n"
        "- This helps generators create proper connections between functional zones.\n"
        "\nBlueprint mode (advanced, optional):\n"
        "- For complex structures like CASTLE compounds or iconic landmarks, you MAY include extra.blueprint as a semantic component blueprint.\n"
        "- extra.blueprint MUST be valid JSON (no comments).\n"
        "- extra.blueprint MUST include: blueprint_type (string), blueprint_version (int, must be 1).\n"
        "- Recommended: blueprint_type values: castle, tulou, temple_of_heaven, great_wall, eiffel_tower, golden_gate_bridge, giant_wild_goose_pagoda, birds_nest_stadium.\n"
        "- Blueprint should also include: overall_dimensions{x,z,height_max}, components[] (for castle).\n"
        "- When user mentions a specific landmark building, consider using blueprint_type matching the building name (e.g., 'birds_nest_stadium' for Bird's Nest).\n"
        "\nAssembly mode (for parametric/organic shapes):\n"
        "- When the building has organic curves, twisted forms, or non-rectangular geometry (e.g., Bird's Nest elliptical mesh, Zaha Hadid style),\n"
        "  you SHOULD use extra.assembly with:\n"
        "  - macro.style.styleId: appropriate style (e.g., 'Deconstructivism_Zaha' for Bird's Nest)\n"
        "  - macro.twist: for twisted/rotated forms\n"
        "  - macro.roofCurvature: for curved roofs\n"
        "  - macro.verticalProfile: for segmented vertical operations\n"
        "  - components: define SHELL_BOX, ROOF_COVER, SURFACE_PATTERN operations to create the desired form\n"
        "- The assembly system can generate complex geometries that simple footprint shapes cannot represent.\n"
    )


def _build_user_prompt(req: BuildRequest) -> str:
    """将请求摘要放到 prompt 中，给模型参考"""
    parts = [
        f"Player: {req.player.name}",
        f"Position: ({req.player.pos.x}, {req.player.pos.y}, {req.player.pos.z})",
        f"Facing: {req.player.facing}",
        f"Dimension: {req.world.dimension}",
        f"Biome: {req.world.biome or 'Unknown'}",
        f"Request: {req.requestText}",
    ]
    
    if req.selection:
        parts.append(
            f"Selection AABB: min=({req.selection.min.x}, {req.selection.min.y}, {req.selection.min.z}), "
            f"max=({req.selection.max.x}, {req.selection.max.y}, {req.selection.max.z})"
        )
    
    # 笔刷选中区域（如果没有选区，则使用笔刷区域）
    if req.brushSelection and not req.selection:
        parts.append(
            f"Brush Selection AABB: min=({req.brushSelection.min.x}, {req.brushSelection.min.y}, {req.brushSelection.min.z}), "
            f"max=({req.brushSelection.max.x}, {req.brushSelection.max.y}, {req.brushSelection.max.z})"
        )
        parts.append("Note: Buildings should be generated within the brush-selected area on the ground surface.")

    # Hard build constraints (preferred over model guesses).
    # NOTE: This is NOT the full geometry engine; it is a compact, deterministic constraint summary for the LLM.
    if getattr(req, "outline", None):
        o = req.outline
        try:
            if (o.shapeType or "").lower() == "circle" and o.center and o.radius is not None:
                parts.append(
                    "Outline (HARD): circle center=("
                    f"{o.center.x}, {o.center.y}, {o.center.z}"
                    f"), radius={o.radius}, yRange=[{o.minY},{o.maxY}]"
                )
            else:
                vs = o.vertices or []
                if vs:
                    head = "; ".join([f"({p.x},{p.y},{p.z})" for p in vs[:8]])
                    more = "" if len(vs) <= 8 else f" ... (+{len(vs) - 8} more)"
                    parts.append(f"Outline (HARD): polygon vertices={len(vs)} {head}{more}, yRange=[{o.minY},{o.maxY}]")
                else:
                    parts.append(f"Outline (HARD): shapeType={o.shapeType}, yRange=[{o.minY},{o.maxY}]")
        except Exception:
            parts.append("Outline (HARD): provided (failed to summarize)")

    if getattr(req, "protectedZones", None):
        zs = req.protectedZones or []
        if zs:
            parts.append("ProtectedZones (HARD): do NOT place blocks inside these AABBs:")
            for z in zs[:12]:
                parts.append(
                    f"  - min=({z.min.x},{z.min.y},{z.min.z}) max=({z.max.x},{z.max.y},{z.max.z})"
                )
            if len(zs) > 12:
                parts.append(f"  - ... (+{len(zs) - 12} more)")
    
    if req.chatHistory:
        parts.append("\nChat History:")
        for msg in req.chatHistory:
            parts.append(f"  {msg}")

    # Keyword-based cultural retrieval (P0 RAG): culture_cards -> few-shot + assemblyDraft (macro.style)
    try:
        from app.services.keyword_culture_retriever import retrieve_budgeted as _culture_retrieve, retrieve_building_knowledge as _building_knowledge_retrieve
        qtext = (req.requestText or "") + "\n" + (getattr(req, "userMessage", None) or "")
        rag = _culture_retrieve(qtext, **_resolve_rag_budget(req))
        if rag:
            # Extract assemblyDraft separately for better visibility
            assembly_draft = rag.get("assemblyDraft")
            if assembly_draft:
                parts.append("\nAssemblyDraft(JSON):")
                parts.append(json.dumps(assembly_draft, ensure_ascii=False, indent=2))
            
            # Include fewShots and hits in CultureRetrieval block
            if rag.get("fewShots") or rag.get("hits"):
                culture_retrieval = {
                    "hits": rag.get("hits", []),
                    "fewShots": rag.get("fewShots", []),
                }
                parts.append("\nCultureRetrieval(JSON):")
                parts.append(json.dumps(culture_retrieval, ensure_ascii=False, indent=2))
    except Exception:
        pass
    
    # Building knowledge retrieval: specific building features -> detailed architectural information
    try:
        from app.services.keyword_culture_retriever import retrieve_building_knowledge as _building_knowledge_retrieve
        qtext = (req.requestText or "") + "\n" + (getattr(req, "userMessage", None) or "")
        building_kb = _building_knowledge_retrieve(qtext, topK=1)
        if building_kb:
            # Extract relevant information for LLM
            building_info = {
                "name": building_kb.get("name"),
                "nameEn": building_kb.get("nameEn"),
                "description": building_kb.get("description"),
                "descriptionZh": building_kb.get("descriptionZh"),
                "features": building_kb.get("features"),
                "dimensions": building_kb.get("dimensions"),
                "assemblyHints": building_kb.get("assemblyHints"),
            }
            parts.append("\nBuildingKnowledge(JSON):")
            parts.append(json.dumps(building_info, ensure_ascii=False, indent=2))
    except Exception:
        pass

    # Provide data-driven style profile candidates (multiple-choice), so the model can set extra.styleProfileId deterministically.
    try:
        from app.services.style_profile_registry import catalog_prompt_block, palette_prompt_block
        qtext = (req.requestText or "") + "\n" + (getattr(req, "userMessage", None) or "")
        block = catalog_prompt_block(max_items=30, query_text=qtext)
        if block:
            parts.append("\n" + block)
        pblock = palette_prompt_block(max_items=30, query_text=qtext)
        if pblock:
            parts.append("\n" + pblock)
    except Exception:
        pass
    
    return "\n".join(parts)


def _infer_scale(req: BuildRequest) -> str:
    """Infer a coarse cluster scale for planning prompts."""
    text = (req.requestText or "").lower()
    if req.selection is not None:
        dx = abs(req.selection.max.x - req.selection.min.x) + 1
        dz = abs(req.selection.max.z - req.selection.min.z) + 1
        m = max(dx, dz)
        if m <= 48:
            return "SMALL"
        if m <= 96:
            return "MEDIUM"
        return "LARGE"
    if any(k in text for k in ["large", "huge", "gigantic", "大型", "巨型", "超大"]):
        return "LARGE"
    if any(k in text for k in ["small", "tiny", "小型", "迷你"]):
        return "SMALL"
    return "MEDIUM"


def _infer_terrain(req: BuildRequest) -> str:
    """Best-effort terrain hint from user text (we do NOT sample terrain here)."""
    t = (req.requestText or "").lower()
    if any(k in t for k in ["flat", "平坦", "平地", "平原"]):
        return "FLAT"
    if any(k in t for k in ["mountain", "mountainous", "山", "高山", "山地", "峭壁"]):
        return "MOUNTAINOUS"
    if any(k in t for k in ["hill", "hilly", "丘", "丘陵", "起伏", "坡地", "崎岖"]):
        return "HILLY"
    return "UNKNOWN"


def generate_semantic_spatial_plan(req: BuildRequest) -> Optional[SemanticSpatialPlan]:
    """
    I-layer: Semantic Spatial Plan (zone graph) for clusters/cities.
    Returns None on any failure (best-effort, never blocks city generation).
    """
    client = get_client(req)
    if not client:
        return None

    # IMPORTANT: keep enums aligned with Java side:
    # - SemanticZoneType: CORE/PUBLIC/SEMI_PUBLIC/PRIVATE/SERVICE/TRANSITION/LANDSCAPE/CIRCULATION
    # - TerrainPolicy: PRESERVE_DOMINANT/BALANCED/ENGINEERED
    system_prompt = (
        "You are FormaCraft Spatial Planner.\n"
        "Your task is NOT to generate blocks or buildings directly.\n"
        "Your task is to analyze user intent and produce a structured Semantic Spatial Plan for a Minecraft build cluster.\n"
        "You must think like an architect + urban planner. Terrain, circulation, hierarchy, and spatial meaning matter.\n\n"
        "STRICT OUTPUT RULES:\n"
        "- Output MUST be valid JSON only. No markdown. No explanations.\n"
        "- Output MUST match the schema exactly. Do NOT add extra keys.\n"
        "- All enum values MUST be one of the allowed strings listed in the schema.\n\n"
        "SCHEMA:\n"
        "{\n"
        '  "zones": [\n'
        "    {\n"
        '      "id": "string",\n'
        '      "type": "CORE|PUBLIC|SEMI_PUBLIC|PRIVATE|SERVICE|LANDSCAPE|TRANSITION|CIRCULATION",\n'
        '      "priority": 1,\n'
        '      "terrain_policy": "PRESERVE_DOMINANT|BALANCED|ENGINEERED",\n'
        '      "notes": "string"\n'
        "    }\n"
        "  ],\n"
        '  "relations": [\n'
        "    {\n"
        '      "from": "zone_id",\n'
        '      "to": "zone_id",\n'
        '      "relation": "DIRECT|BUFFERED|FORBIDDEN|AXIAL|VISUAL_ONLY",\n'
        '      "notes": "string"\n'
        "    }\n"
        "  ],\n"
        '  "circulation": {\n'
        '    "primary_flow": ["zone_id"],\n'
        '    "secondary_flow": ["zone_id"]\n'
        "  },\n"
        '  "constraints": {\n'
        '    "prefer_axis_alignment": true,\n'
        '    "avoid_direct_private_access": true,\n'
        '    "max_terrain_disturbance": "LOW|MEDIUM|HIGH",\n'
        '    "terrain_budget_blocks": 0\n'
        "  }\n"
        "}\n"
    )

    scale = _infer_scale(req)
    terrain = _infer_terrain(req)
    anchor = req.player.pos

    selection_str = "none"
    if req.selection is not None:
        smin = req.selection.min
        smax = req.selection.max
        selection_str = f"box(min=({smin.x},{smin.y},{smin.z}), max=({smax.x},{smax.y},{smax.z}))"

    user_prompt = (
        "User Intent:\n"
        f"\"{req.requestText}\"\n\n"
        "Context:\n"
        "- Minecraft world\n"
        "- Building cluster (multiple structures possible)\n"
        f"- Scale: {scale}\n"
        f"- Terrain: {terrain}\n"
        f"- Anchor: anchor=({anchor.x},{anchor.y},{anchor.z}) facing={req.player.facing}\n"
        f"- Selection Area: {selection_str}\n\n"
        "Your task:\n"
        "1) Identify the major semantic zones required.\n"
        "2) Assign each zone a semantic type and priority.\n"
        "3) Describe spatial relationships between zones.\n"
        "4) Indicate terrain sensitivity per zone.\n"
        "5) Indicate circulation and access logic.\n\n"
        "Output JSON ONLY matching the schema.\n"
    )

    try:
        model = _resolve_model(req, "gpt-4o-mini")
        # Semantic planning is cheaper than full city planning; keep a moderate timeout.
        timeout_sec = min(_resolve_timeout_sec_for_task("city", req, model), 180.0)
        response = _call_with_timeout(
            lambda: client.chat.completions.create(
                model=model,
                messages=[
                    {"role": "system", "content": system_prompt},
                    {"role": "user", "content": user_prompt},
                ],
                response_format={"type": "json_object"},
                temperature=_clamp_temperature(getattr(req, "temperature", None), 0.2),
            ),
            timeout_sec,
        )
        raw_output = response.choices[0].message.content
        if not raw_output:
            return None
        data = json.loads(raw_output)
        return SemanticSpatialPlan.model_validate(data)
    except Exception:
        return None


def _disturbance_to_policy_and_budget(level: str, scale: str) -> tuple[str, int]:
    lvl = (level or "MEDIUM").strip().upper()
    sc = (scale or "MEDIUM").strip().upper()
    mult = 1.0
    if sc == "SMALL":
        mult = 0.7
    elif sc == "LARGE":
        mult = 1.8

    if lvl == "LOW":
        return "preserve_dominant", int(900 * mult)
    if lvl == "HIGH":
        return "engineered", int(6500 * mult)
    return "balanced", int(2600 * mult)


def _policy_upper_to_lower(policy: Optional[str]) -> Optional[str]:
    if policy is None:
        return None
    p = policy.strip().upper()
    return {
        "PRESERVE_DOMINANT": "preserve_dominant",
        "BALANCED": "balanced",
        "ENGINEERED": "engineered",
    }.get(p)


def _compile_semantic_plan_extra(req: BuildRequest, plan: SemanticSpatialPlan) -> Dict[str, Any]:
    """
    Convert SemanticSpatialPlan -> spec.extra knobs that Java CityBuilder/ClusterLayoutConfig already consumes.
    This avoids protocol changes while making I-layer effective immediately.
    """
    scale = _infer_scale(req)

    # Default from constraints
    policy_s, budget = _disturbance_to_policy_and_budget(plan.constraints.max_terrain_disturbance, scale)
    if plan.constraints.terrain_budget_blocks is not None and plan.constraints.terrain_budget_blocks > 0:
        budget = int(plan.constraints.terrain_budget_blocks)

    # If any high-priority zone is engineered, promote cluster policy.
    for z in plan.zones:
        if z.priority >= 8 and z.terrain_policy == "ENGINEERED":
            policy_s = "engineered"
            break

    extra: Dict[str, Any] = {}
    extra["clusterTerrainPolicy"] = policy_s
    extra["clusterTerrainBudgetBlocks"] = budget
    # be conservative: preserve/balanced avoid water/lava edits by default
    extra["allowWaterEdit"] = (policy_s == "engineered")
    extra["allowLavaEdit"] = False

    # Axis alignment hint: if any AXIAL relation exists, enable axis scoring.
    axial = plan.constraints.prefer_axis_alignment or any(r.relation == "AXIAL" for r in plan.relations)
    if axial:
        extra["axisMode"] = "z"
        extra["axisWeight"] = 0.18

    # Semantic spacing knobs: tighten buffers when user wants privacy/access separation.
    if plan.constraints.avoid_direct_private_access:
        extra["semanticBufferMinDistN"] = 0.22
        extra["semanticBufferWeight"] = 0.55
        extra["semanticServicePrivateMinDistN"] = 0.30
        extra["semanticServicePrivateWeight"] = 0.55
        extra["semanticWeightPrivate"] = 0.30
        extra["semanticWeightService"] = 0.25
        extra["semanticWeightPublic"] = 0.22

    # zoneTerrainRules: map semantic zones -> CitySpec zone types (best-effort)
    # pick the highest-priority policy per semantic type
    best_policy: Dict[str, str] = {}
    best_pri: Dict[str, int] = {}
    for z in plan.zones:
        t = z.type
        pri = int(z.priority)
        if t not in best_pri or pri > best_pri[t]:
            p = _policy_upper_to_lower(z.terrain_policy) or policy_s
            best_pri[t] = pri
            best_policy[t] = p

    def policy_for(semantic_type: str) -> Optional[str]:
        return best_policy.get(semantic_type)

    zone_rules: Dict[str, Any] = {}
    mapping = {
        "PLAZA": policy_for("PUBLIC"),
        "MARKET": policy_for("PUBLIC"),
        "COMMERCIAL": policy_for("PUBLIC"),
        "RESIDENTIAL": policy_for("PRIVATE"),
        "INDUSTRIAL": policy_for("SERVICE"),
        "WALL": policy_for("TRANSITION"),
        "GATE": policy_for("TRANSITION"),
    }
    for zone_type, pol in mapping.items():
        if not pol:
            continue
        rule: Dict[str, Any] = {"policy": pol}
        # optional per-zone budget: small slice of cluster budget
        if budget > 0:
            rule["budget"] = max(0, int(budget * 0.25))
        if pol != "preserve_dominant":
            rule["allowWaterEdit"] = (pol == "engineered")
            rule["allowLavaEdit"] = False
        zone_rules[zone_type] = rule
    if zone_rules:
        extra["zoneTerrainRules"] = zone_rules

    return extra


def _cardinal_facing_from_dxz(dx: int, dz: int) -> str:
    # Default to player's facing if degenerate
    if abs(dx) <= 0 and abs(dz) <= 0:
        return "NORTH"
    # Prefer dominant axis
    if abs(dz) >= abs(dx):
        return "SOUTH" if dz > 0 else "NORTH"
    return "EAST" if dx > 0 else "WEST"


def _default_size_for_zone(zone_type: str, scale: str) -> tuple[int, int]:
    """
    Returns (w, d) for RECTANGLE zones. Keep conservative; actual building specs may override.
    """
    sc = (scale or "MEDIUM").upper()
    base = 12
    if sc == "SMALL":
        base = 10
    elif sc == "LARGE":
        base = 16

    zt = (zone_type or "").upper()
    if zt == "CORE":
        return base + 2, base
    if zt in ("PUBLIC", "SEMI_PUBLIC"):
        return base + 6, base + 4
    if zt == "PRIVATE":
        return base, base
    if zt == "SERVICE":
        return base, base - 2 if base > 6 else base
    if zt == "TRANSITION":
        return base + 2, max(6, base - 4)
    return base, base


def _default_radius_for_core(scale: str) -> int:
    sc = (scale or "MEDIUM").upper()
    if sc == "SMALL":
        return 8
    if sc == "LARGE":
        return 14
    return 10


def _semantic_to_skeleton_layout(req: BuildRequest, plan: SemanticSpatialPlan) -> SkeletonLayout:
    """
    J-layer (v0): Convert SemanticSpatialPlan -> SkeletonLayout.
    This is a light, deterministic layout hint (no world sampling).
    Coordinates are relative to cluster center (0,0,0), matching CitySpec conventions.
    """
    scale = _infer_scale(req)
    t = (req.requestText or "").lower()
    # Hint for iconic circular core (tulou/temple/ring)
    prefer_circular_core = any(k in t for k in ["土楼", "tulou", "天坛", "temple of heaven", "ring", "圆形", "环形"])

    # Index zones by id for quick lookup
    zones_by_id: Dict[str, Any] = {z.id: z for z in (plan.zones or []) if z and z.id}
    # Choose a CORE zone id if present, else highest priority
    core_id: Optional[str] = None
    for z in (plan.zones or []):
        if z.type == "CORE":
            core_id = z.id
            break
    if core_id is None and plan.zones:
        core_id = sorted(plan.zones, key=lambda zz: (-int(zz.priority), zz.id))[0].id

    # Build adjacency from relations (treat as undirected for placement order)
    adj: Dict[str, List[str]] = {}
    axial_edges: List[tuple[str, str]] = []
    for r in (plan.relations or []):
        a = getattr(r, "from_zone", None) or getattr(r, "from", None)
        b = getattr(r, "to_zone", None) or getattr(r, "to", None)
        if not a or not b:
            continue
        adj.setdefault(a, []).append(b)
        adj.setdefault(b, []).append(a)
        if r.relation == "AXIAL":
            axial_edges.append((a, b))

    # Basic placement: CORE at origin, expand neighbors on rings.
    spacing = 18
    if scale == "SMALL":
        spacing = 14
    elif scale == "LARGE":
        spacing = 24

    pos: Dict[str, tuple[int, int]] = {}
    if core_id:
        pos[core_id] = (0, 0)

    # If we have an axial edge involving core, place its counterpart along +Z (forward).
    for a, b in axial_edges:
        if core_id and a == core_id and b not in pos:
            pos[b] = (0, -spacing)  # north
        elif core_id and b == core_id and a not in pos:
            pos[a] = (0, -spacing)

    # BFS from core
    if core_id:
        q = [core_id]
        visited = set(q)
        while q:
            cur = q.pop(0)
            cx, cz = pos.get(cur, (0, 0))
            neigh = adj.get(cur, [])
            # deterministic order by zone priority (higher first)
            neigh_sorted = sorted(
                [n for n in neigh if n in zones_by_id],
                key=lambda nid: (-int(zones_by_id[nid].priority), nid),
            )
            # fan out around current
            fan = [
                (0, -1),  # north
                (1, 0),   # east
                (-1, 0),  # west
                (0, 1),   # south
                (1, -1),
                (-1, -1),
                (1, 1),
                (-1, 1),
            ]
            fi = 0
            for n in neigh_sorted:
                if n in visited:
                    continue
                visited.add(n)
                # find first free slot
                while fi < len(fan):
                    dx, dz = fan[fi]
                    fi += 1
                    nx, nz = cx + dx * spacing, cz + dz * spacing
                    if (nx, nz) in pos.values():
                        continue
                    pos[n] = (nx, nz)
                    break
                q.append(n)

    # Any unplaced zones: place on a loose ring
    ring_i = 0
    for z in (plan.zones or []):
        if z.id in pos:
            continue
        ring_i += 1
        pos[z.id] = (ring_i * 6, -spacing - ring_i * 4)

    # Facing: use player's facing as default, or infer from axial core->other direction
    default_facing = (req.player.facing or "NORTH").strip().upper()
    if default_facing not in ("NORTH", "SOUTH", "EAST", "WEST"):
        default_facing = "NORTH"

    def infer_facing_for_zone(zid: str) -> str:
        # if axial edge touches zid, face toward its axial partner
        for a, b in axial_edges:
            if a == zid and b in pos:
                ax, az = pos[a]
                bx, bz = pos[b]
                return _cardinal_facing_from_dxz(bx - ax, bz - az)
            if b == zid and a in pos:
                bx, bz = pos[b]
                ax, az = pos[a]
                return _cardinal_facing_from_dxz(ax - bx, az - bz)
        # if core exists, non-core face toward core (helps entrances)
        if core_id and zid != core_id and core_id in pos:
            zx, zz = pos[zid]
            cx, cz = pos[core_id]
            return _cardinal_facing_from_dxz(cx - zx, cz - zz)
        return default_facing

    sks: List[SkeletonNode] = []
    for z in (plan.zones or []):
        zx, zz = pos.get(z.id, (0, 0))
        facing = infer_facing_for_zone(z.id)

        if z.type == "CIRCULATION":
            # Minimal: linear from core to this zone if core exists
            pts = []
            if core_id and core_id in pos:
                cx, cz = pos[core_id]
                pts = [
                    IntVec3(x=cx, y=0, z=cz),
                    IntVec3(x=zx, y=0, z=zz),
                ]
            sks.append(
                SkeletonNode(
                    zone=z.id,
                    zoneType=z.type,
                    shape="LINEAR",
                    anchor=IntVec3(x=zx, y=0, z=zz),
                    facing=facing,
                    points=pts or None,
                    notes=z.notes or "",
                )
            )
            continue

        if z.type == "CORE" and prefer_circular_core:
            sks.append(
                SkeletonNode(
                    zone=z.id,
                    zoneType=z.type,
                    shape="CIRCLE",
                    anchor=IntVec3(x=zx, y=0, z=zz),
                    facing=facing,
                    radius=_default_radius_for_core(scale),
                    notes=z.notes or "",
                )
            )
            continue

        # Default: rectangles for most zones
        w, d = _default_size_for_zone(z.type, scale)
        sks.append(
            SkeletonNode(
                zone=z.id,
                zoneType=z.type,
                shape="RECTANGLE",
                anchor=IntVec3(x=zx, y=0, z=zz),
                facing=facing,
                width=w,
                depth=d,
                notes=z.notes or "",
            )
        )

    # Ensure CORE is first (helps debug/consumption)
    def sort_key(node: SkeletonNode) -> tuple[int, str]:
        zz = zones_by_id.get(node.zone)
        t0 = zz.type if zz else ""
        pri = int(zz.priority) if zz else 1
        core_rank = 0 if t0 == "CORE" else 1
        return (core_rank, -pri, node.zone)

    sks_sorted = sorted(sks, key=sort_key)
    return SkeletonLayout(skeletons=sks_sorted)


def _default_schema() -> Dict[str, Any]:
    """
    可以用于 response_format 的 JSON Schema
    这里先不用强 schema，让模型自由一点，后面可以升级成严格结构
    """
    return {
        "type": "object",
        "properties": {
            "type": {"type": "string", "enum": [t.value for t in BuildingType]},
            "style": {"type": "string", "enum": [s.value for s in BuildingStyle]},
            "footprint": {
                "type": "object",
                "properties": {
                    "shape": {"type": "string"},
                    "width": {"type": "integer"},
                    "depth": {"type": "integer"},
                    "radius": {"type": "integer"},
                },
                "required": ["shape"],
            },
            "height": {"type": "integer"},
            "floors": {"type": "integer"},
            "materials": {
                "type": "object",
                "properties": {
                    "wall": {"type": "string"},
                    "roof": {"type": "string"},
                    "floor": {"type": "string"},
                    "window": {"type": "string"},
                    "foundation": {"type": "string"},
                },
            },
            "features": {
                "type": "object",
                "properties": {
                    "hasWindows": {"type": "boolean"},
                    "hasStairs": {"type": "boolean"},
                    "hasDoor": {"type": "boolean"},
                    "hasBalcony": {"type": "boolean"},
                    "hasRoof": {"type": "boolean"},
                    "hasRoofDecoration": {"type": "boolean"},
                    "windowCount": {"type": "integer"},
                    "floorCount": {"type": "integer"},
                },
            },
            "styleOptions": {
                "type": "object",
                "properties": {
                    "doorStyle": {"type": "string", "enum": ["single", "double", "arched", "none"]},
                    "roofType": {"type": "string", "enum": ["flat", "gable", "cone", "pyramid", "hipped"]},
                    "bridgeType": {"type": "string", "enum": ["flat", "arched", "suspension", "beam", "rope"]},
                    "windowRatio": {"type": "number", "minimum": 0.0, "maximum": 1.0},
                    "windowStyle": {"type": "string", "enum": ["pane", "fence", "stained"]},
                    "wallPattern": {"type": "string", "enum": ["uniform", "striped", "gradient", "random"]},
                },
            },
            "notes": {"type": "string"},
            "extra": {"type": "object"},
        },
        "required": ["type", "style", "footprint", "height", "materials", "features"],
    }


def _resolve_timeout_sec(req: Optional[BuildRequest], model: Optional[str]) -> float:
    """
    Some providers/models are slower (e.g. deepseek-reasoner). We allow longer timeouts there.
    """
    base = _LLM_CALL_TIMEOUT_SEC
    try:
        provider = (getattr(req, "llmProvider", None) or "").strip().lower() if req is not None else ""
    except Exception:
        provider = ""
    m = (model or "").strip().lower()

    if provider == "deepseek":
        # deepseek reasoner can easily exceed 45s
        if "reasoner" in m:
            return max(base, _LLM_CALL_TIMEOUT_REASONER_SEC)
        return max(base, _LLM_CALL_TIMEOUT_DEEPSEEK_SEC)

    # default
    return base


def _resolve_timeout_sec_for_task(task: str, req: Optional[BuildRequest], model: Optional[str]) -> float:
    """
    task: building / composite / city / llmplan
    """
    t = (task or "").strip().lower()
    base = _resolve_timeout_sec(req, model)
    if t == "composite":
        return max(base, _LLM_CALL_TIMEOUT_COMPOSITE_SEC)
    if t == "city":
        return max(base, _LLM_CALL_TIMEOUT_CITY_SEC)
    if t == "llmplan":
        # LlmPlan 通常需要更长的处理时间，特别是对于复杂的 prompt 和大型 JSON 输出
        # 确保使用足够的超时时间（默认 180 秒）
        return max(base, _LLM_CALL_TIMEOUT_LLMPLAN_SEC)
    return base


def _should_use_mingqing_courtyard_template(req: Optional[BuildRequest]) -> bool:
    if req is None:
        return False
    text = (getattr(req, "requestText", None) or "") + "\n" + (getattr(req, "userMessage", None) or "")
    s = text.lower()
    if not s.strip():
        return False

    # 用户明确允许随意/自由发挥：不要强行套模板
    if any(k in s for k in ("随意", "随便", "自由发挥", "你决定", "random", "freeform")):
        return False

    # 明清官式院落/四合院/宅院
    has_dynasty = ("明清" in s) or ("ming" in s and "qing" in s) or ("qing" in s) or ("官式" in s)
    has_courtyard = any(k in s for k in ("四合院", "院落", "宅院", "大院", "courtyard"))
    # 如果用户写“主殿/厢房/门楼/院墙”也强烈暗示院落模板
    has_parts = any(k in s for k in ("主殿", "厢房", "门楼", "院墙", "影壁"))
    return (has_dynasty and (has_courtyard or has_parts)) or (has_courtyard and has_parts)


def _should_use_tulou_template(req: Optional[BuildRequest]) -> bool:
    if req is None:
        return False
    text = (getattr(req, "requestText", None) or "") + "\n" + (getattr(req, "userMessage", None) or "")
    s = text.lower()
    if not s.strip():
        return False

    # 用户明确允许随意/自由发挥：不要强行套模板
    if any(k in s for k in ("随意", "随便", "自由发挥", "你决定", "random", "freeform")):
        return False

    # 福建永定土楼 / Tulou（强形象地标）
    return any(k in s for k in ("土楼", "永定", "福建土楼", "tulou"))


def _should_use_eiffel_tower_template(req: Optional[BuildRequest]) -> bool:
    if req is None:
        return False
    text = (getattr(req, "requestText", None) or "") + "\n" + (getattr(req, "userMessage", None) or "")
    s = text.lower()
    if not s.strip():
        return False

    # 用户明确允许随意/自由发挥：不要强行套模板
    if any(k in s for k in ("随意", "随便", "自由发挥", "你决定", "random", "freeform")):
        return False

    return any(k in s for k in ("埃菲尔", "埃菲尔铁塔", "埃菲尔塔", "eiffel", "eiffel tower", "tour eiffel"))


def _should_use_temple_of_heaven_template(req: Optional[BuildRequest]) -> bool:
    if req is None:
        return False
    text = (getattr(req, "requestText", None) or "") + "\n" + (getattr(req, "userMessage", None) or "")
    s = text.lower()
    if not s.strip():
        return False

    if any(k in s for k in ("随意", "随便", "自由发挥", "你决定", "random", "freeform")):
        return False

    return any(k in s for k in ("天坛", "祈年殿", "temple of heaven", "qiniandian"))


def _parse_radius_or_diameter_from_text(s: str) -> tuple[Optional[int], Optional[int]]:
    """
    Returns: (radius, diameter)
    Accepts: 半径 12 / radius 12; 直径 24 / diameter 24
    """
    if not s:
        return None, None
    t = s.lower()
    mr = re.search(r"(?:半径|radius)\s*(?:为|=|:)?\s*(\d{1,3})", t, flags=re.IGNORECASE)
    md = re.search(r"(?:直径|diameter)\s*(?:为|=|:)?\s*(\d{1,3})", t, flags=re.IGNORECASE)
    r = None
    d = None
    try:
        if mr:
            r = int(mr.group(1))
            if r <= 0:
                r = None
    except Exception:
        r = None
    try:
        if md:
            d = int(md.group(1))
            if d <= 0:
                d = None
    except Exception:
        d = None
    return r, d


def _generate_temple_of_heaven_building_spec(req: BuildRequest) -> BuildingSpec:
    text = (req.requestText or "") + "\n" + (req.userMessage or "")

    # base size
    arche_id = "temple_of_heaven"
    r, d = _parse_radius_or_diameter_from_text(text)
    base_radius = r if r is not None else (d // 2 if d is not None else _arche_default_int(arche_id, "baseRadius", 18))
    base_radius = _clamp_with_arche_constraints(arche_id, base_radius, "minBaseRadius", "maxBaseRadius")
    base_radius = max(10, min(60, base_radius))  # safety fallback
    base_diam = base_radius * 2

    # hall radius (usually smaller than base)
    hall_radius = int(round(base_radius * 0.55))
    hall_radius = max(6, min(base_radius - 3, hall_radius))

    # overall height
    height = _parse_height_from_text(text) or _arche_default_int(arche_id, "height", 28)
    height = _clamp_with_arche_constraints(arche_id, height, "minHeight", "maxHeight")
    height = max(18, min(80, height))  # safety fallback

    tiers = 3  # 三层台基（标志性）
    t_low = text.lower()
    if any(k in t_low for k in ("两层台基", "2层台基", "two tiers")):
        tiers = 2
    if any(k in t_low for k in ("三层台基", "3层台基", "three tiers")):
        tiers = 3

    detail_level = "aesthetic"
    if any(k in t_low for k in ("精致", "细节", "更复杂", "more detail", "refined", "ornate")):
        detail_level = "refined"

    materials = Materials(
        wall="minecraft:white_concrete",
        roof="minecraft:cyan_terracotta",
        floor="minecraft:smooth_quartz",
        window="minecraft:glass_pane",
        foundation="minecraft:smooth_quartz",
    )
    features = Features(
        hasWindows=False,
        hasStairs=True,
        hasDoor=True,
        hasBalcony=True,
        hasRoof=True,
        hasRoofDecoration=True,
        windowCount=0,
        floorCount=1,
    )
    style_options = StyleOptions(
        doorStyle="arched",
        roofType="cone",
        bridgeType="flat",
        windowRatio=0.0,
        windowStyle="pane",
        wallPattern="uniform",
    )

    spec = BuildingSpec(
        type=BuildingType.HOUSE,
        style=BuildingStyle.ASIAN,
        footprint=Footprint(shape="circle", radius=base_radius),
        height=height,
        floors=1,
        materials=materials,
        features=features,
        styleOptions=style_options,
        notes="Temple of Heaven (Qiniandian) (landmark archetype, parameterized)",
        extra={
            "landmark": "temple_of_heaven",
            "baseRadius": base_radius,
            "baseDiameter": base_diam,
            "tiers": tiers,
            "hallRadius": hall_radius,
            "hallHeight": int(round(height * 0.62)),
            "detailLevel": detail_level,
            # allow material overrides in generator
            "baseBlock": "minecraft:smooth_quartz",
            "stairBlock": "minecraft:quartz_stairs",
            "pillarBlock": "minecraft:white_concrete",
            "wallBlock": "minecraft:white_concrete",
            "roofBlock": "minecraft:cyan_terracotta",
            "trimBlock": "minecraft:quartz_slab",
            "railBlock": "minecraft:quartz_slab",
            "accentBlock": "minecraft:yellow_terracotta",
        },
    )
    return _ensure_genome_for_spec(spec, req)


def _should_use_great_wall_template(req: Optional[BuildRequest]) -> bool:
    if req is None:
        return False
    text = (getattr(req, "requestText", None) or "") + "\n" + (getattr(req, "userMessage", None) or "")
    s = text.lower()
    if not s.strip():
        return False
    if any(k in s for k in ("随意", "随便", "自由发挥", "你决定", "random", "freeform")):
        return False
    return any(k in s for k in ("长城", "万里长城", "great wall", "great wall of china"))


def _parse_length_from_text(s: str) -> Optional[int]:
    if not s:
        return None
    # 支持：长度 120 / length=80 / 延伸 100
    m = re.search(r"(?:长度|length|延伸|延长)\s*(?:为|=|:)?\s*(\d{1,4})", s, flags=re.IGNORECASE)
    if not m:
        return None
    try:
        v = int(m.group(1))
        return v if v > 0 else None
    except Exception:
        return None


def _parse_thickness_from_text(s: str) -> Optional[int]:
    if not s:
        return None
    m = re.search(r"(?:厚度|宽度|thickness|width)\s*(?:为|=|:)?\s*(\d{1,2})", s, flags=re.IGNORECASE)
    if not m:
        return None
    try:
        v = int(m.group(1))
        return v if v > 0 else None
    except Exception:
        return None


def _parse_tower_spacing_from_text(s: str) -> Optional[int]:
    if not s:
        return None
    # 支持：烽火台间距 40 / 塔间距 32 / tower spacing 30
    m = re.search(r"(?:烽火台间距|塔间距|tower\s*spacing|spacing)\s*(?:为|=|:)?\s*(\d{1,4})", s, flags=re.IGNORECASE)
    if not m:
        return None
    try:
        v = int(m.group(1))
        return v if v > 0 else None
    except Exception:
        return None


def _parse_follow_terrain_from_text(s: str) -> Optional[bool]:
    if not s:
        return None
    t = s.lower()
    if any(k in t for k in ("贴地形", "跟随地形", "沿地形", "follow terrain", "terrain")):
        return True
    if any(k in t for k in ("不贴地形", "不要跟随地形", "不要贴地形", "flat")):
        return False
    return None


def _generate_great_wall_building_spec(req: BuildRequest) -> BuildingSpec:
    text = (req.requestText or "") + "\n" + (req.userMessage or "")

    arche_id = "great_wall"
    length = _parse_length_from_text(text) or _arche_default_int(arche_id, "length", 120)
    length = _clamp_with_arche_constraints(arche_id, length, "minLength", "maxLength")
    length = max(30, min(800, length))  # safety fallback

    height = _parse_height_from_text(text) or _arche_default_int(arche_id, "height", 10)
    height = _clamp_with_arche_constraints(arche_id, height, "minHeight", "maxHeight")
    height = max(6, min(40, height))  # safety fallback

    thickness = _parse_thickness_from_text(text) or _arche_default_int(arche_id, "thickness", 5)
    thickness = _clamp_with_arche_constraints(arche_id, thickness, "minThickness", "maxThickness")
    thickness = max(3, min(15, thickness))  # safety fallback

    facing = _parse_door_facing_from_text(text)  # reuse existing facing parser
    if not facing:
        # try from player facing
        try:
            facing = str(getattr(req.player, "facing", "")).strip().upper() if req and getattr(req, "player", None) else ""
        except Exception:
            facing = ""
    if facing not in ("NORTH", "SOUTH", "EAST", "WEST"):
        facing = "EAST"  # default: extend to east

    tower_spacing = _parse_tower_spacing_from_text(text) or _arche_default_int(arche_id, "towerSpacing", 48)
    tower_spacing = max(20, min(200, tower_spacing))

    follow_terrain = _parse_follow_terrain_from_text(text)
    if follow_terrain is None:
        follow_terrain = _arche_default_bool(arche_id, "followTerrain", True)

    t_low = text.lower()
    detail_level = "aesthetic"
    if any(k in t_low for k in ("精致", "细节", "更复杂", "more detail", "refined", "ornate")):
        detail_level = "refined"

    materials = Materials(
        wall="minecraft:stone_bricks",
        roof="minecraft:stone_bricks",
        floor="minecraft:stone_bricks",
        window="minecraft:iron_bars",
        foundation="minecraft:cobblestone",
    )
    features = Features(
        hasWindows=False,
        hasStairs=True,
        hasDoor=True,
        hasBalcony=True,
        hasRoof=False,
        hasRoofDecoration=True,
        windowCount=0,
        floorCount=1,
    )
    style_options = StyleOptions(
        doorStyle="arched",
        roofType="flat",
        bridgeType="flat",
        windowRatio=0.0,
        windowStyle="fence",
        wallPattern="random",
    )

    # WallGenerator uses footprint.depth as length; GreatWallGenerator will read extras, but keep footprint consistent.
    spec = BuildingSpec(
        type=BuildingType.WALL,
        style=BuildingStyle.MEDIEVAL,
        footprint=Footprint(shape="rectangle", width=thickness, depth=length),
        height=height,
        floors=1,
        materials=materials,
        features=features,
        styleOptions=style_options,
        notes=f"模板：长城（length≈{length}，height≈{height}，thickness≈{thickness}，facing={facing}）。",
        extra={
            "landmark": "great_wall",
            "wallLength": length,
            "wallHeight": height,
            "wallThickness": thickness,
            "facing": facing,
            "towerSpacing": tower_spacing,
            "followTerrain": follow_terrain,
            "detailLevel": detail_level,
            "wallBlock": "minecraft:stone_bricks",
            "mixWallBlocks": True,
            "walkwayBlock": "minecraft:stone_bricks",
            "crenelBlock": "minecraft:stone_brick_wall",
            "towerBlock": "minecraft:stone_bricks",
            "accentBlock": "minecraft:mossy_stone_bricks",
        },
    )
    return _ensure_genome_for_spec(spec, req)


def _should_use_golden_gate_bridge_template(req: Optional[BuildRequest]) -> bool:
    if req is None:
        return False
    text = (getattr(req, "requestText", None) or "") + "\n" + (getattr(req, "userMessage", None) or "")
    s = text.lower()
    if not s.strip():
        return False
    if any(k in s for k in ("随意", "随便", "自由发挥", "你决定", "random", "freeform")):
        return False
    return any(k in s for k in ("金门大桥", "golden gate bridge", "golden gate"))


def _parse_span_from_text(s: str) -> Optional[int]:
    if not s:
        return None
    # 支持：跨度 200 / span 180 / 主跨 220
    m = re.search(r"(?:跨度|主跨|span)\s*(?:为|=|:)?\s*(\d{1,4})", s, flags=re.IGNORECASE)
    if not m:
        return None
    try:
        v = int(m.group(1))
        return v if v > 0 else None
    except Exception:
        return None


def _parse_deck_width_from_text(s: str) -> Optional[int]:
    if not s:
        return None
    # 支持：桥面宽度 9 / deck width 11
    m = re.search(r"(?:桥面宽度|桥宽|deck\s*width|deckwidth)\s*(?:为|=|:)?\s*(\d{1,3})", s, flags=re.IGNORECASE)
    if not m:
        return None
    try:
        v = int(m.group(1))
        return v if v > 0 else None
    except Exception:
        return None


def _parse_tower_height_from_text(s: str) -> Optional[int]:
    if not s:
        return None
    # 支持：塔高 40 / 主塔高度 42 / tower height 45
    m = re.search(r"(?:塔高|主塔高度|tower\s*height)\s*(?:为|=|:)?\s*(\d{1,3})", s, flags=re.IGNORECASE)
    if not m:
        return None
    try:
        v = int(m.group(1))
        return v if v > 0 else None
    except Exception:
        return None


def _generate_golden_gate_bridge_building_spec(req: BuildRequest) -> BuildingSpec:
    text = (req.requestText or "") + "\n" + (req.userMessage or "")

    arche_id = "golden_gate_bridge"
    span = _parse_span_from_text(text) or _parse_length_from_text(text) or _arche_default_int(arche_id, "span", 180)
    span = _clamp_with_arche_constraints(arche_id, span, "minSpan", "maxSpan")
    span = max(60, min(800, span))  # safety fallback

    deck_width = _parse_deck_width_from_text(text) or _parse_thickness_from_text(text) or _arche_default_int(arche_id, "deckWidth", 9)
    deck_width = _clamp_with_arche_constraints(arche_id, deck_width, "minDeckWidth", "maxDeckWidth")
    deck_width = max(5, min(41, deck_width))  # safety fallback
    if deck_width % 2 == 0:
        deck_width += 1

    tower_height = _parse_tower_height_from_text(text) or _arche_default_int(arche_id, "towerHeight", 44)
    tower_height = _clamp_with_arche_constraints(arche_id, tower_height, "minTowerHeight", "maxTowerHeight")
    tower_height = max(18, min(120, tower_height))  # safety fallback

    facing = _parse_door_facing_from_text(text)
    if not facing:
        try:
            facing = str(getattr(req.player, "facing", "")).strip().upper() if req and getattr(req, "player", None) else ""
        except Exception:
            facing = ""
    if facing not in ("NORTH", "SOUTH", "EAST", "WEST"):
        facing = "EAST"

    follow_terrain = _parse_follow_terrain_from_text(text)
    if follow_terrain is None:
        follow_terrain = _arche_default_bool(arche_id, "followTerrain", True)

    t_low = text.lower()
    detail_level = "aesthetic"
    if any(k in t_low for k in ("精致", "细节", "更复杂", "more detail", "refined", "ornate")):
        detail_level = "refined"

    materials = Materials(
        wall="minecraft:red_terracotta",      # towers
        roof="minecraft:red_terracotta",
        floor="minecraft:polished_andesite",  # deck
        window="minecraft:iron_bars",         # hangers
        foundation="minecraft:stone_bricks",
    )
    features = Features(
        hasWindows=False,
        hasStairs=True,
        hasDoor=False,
        hasBalcony=True,
        hasRoof=False,
        hasRoofDecoration=True,
        windowCount=0,
        floorCount=1,
    )
    style_options = StyleOptions(
        doorStyle="none",
        roofType="flat",
        bridgeType="suspension",
        windowRatio=0.0,
        windowStyle="fence",
        wallPattern="uniform",
    )

    spec = BuildingSpec(
        type=BuildingType.BRIDGE,
        style=BuildingStyle.MODERN,
        footprint=Footprint(shape="rectangle", width=deck_width, depth=span),
        height=max(10, tower_height),
        floors=1,
        materials=materials,
        features=features,
        styleOptions=style_options,
        notes=f"模板：金门大桥（span≈{span}，deck≈{deck_width}，tower≈{tower_height}，facing={facing}）。",
        extra={
            "landmark": "golden_gate_bridge",
            "span": span,
            "deckWidth": deck_width,
            "towerHeight": tower_height,
            "facing": facing,
            "followTerrain": follow_terrain,
            "detailLevel": detail_level,
            "towerBlock": "minecraft:red_terracotta",
            "deckBlock": "minecraft:polished_andesite",
            "cableBlock": "minecraft:red_wool",
            "hangerBlock": "minecraft:iron_bars",
            "railBlock": "minecraft:iron_bars",
            "foundationBlock": "minecraft:stone_bricks",
        },
    )
    return _ensure_genome_for_spec(spec, req)


def _should_use_giant_wild_goose_pagoda_template(req: Optional[BuildRequest]) -> bool:
    if req is None:
        return False
    text = (getattr(req, "requestText", None) or "") + "\n" + (getattr(req, "userMessage", None) or "")
    s = text.lower()
    if not s.strip():
        return False
    if any(k in s for k in ("随意", "随便", "自由发挥", "你决定", "random", "freeform")):
        return False
    return any(k in s for k in ("大慈恩寺", "大雁塔", "giant wild goose pagoda", "dayanta", "wild goose pagoda"))


def _should_use_castle_compound_template(req: Optional[BuildRequest]) -> bool:
    if req is None:
        return False
    text = (getattr(req, "requestText", None) or "") + "\n" + (getattr(req, "userMessage", None) or "")
    s = text.lower()
    if not s.strip():
        return False
    if any(k in s for k in ("随意", "随便", "自由发挥", "你决定", "random", "freeform")):
        return False
    return any(k in s for k in ("城堡", "中世纪城堡", "要塞", "堡垒", "castle", "fortress", "keep"))


def _generate_castle_compound_building_spec(req: BuildRequest) -> BuildingSpec:
    text = (req.requestText or "") + "\n" + (req.userMessage or "")
    arche_id = "castle_compound"

    size = _parse_rect_size_from_text(text)
    w, d = size if size else (_arche_default_int(arche_id, "width", 48), _arche_default_int(arche_id, "depth", 36))
    w = _clamp_with_arche_constraints(arche_id, int(w), "minWidth", "maxWidth")
    d = _clamp_with_arche_constraints(arche_id, int(d), "minDepth", "maxDepth")
    w = max(24, min(128, w))
    d = max(24, min(128, d))

    wall_h = _arche_default_int(arche_id, "wallHeight", 6)
    wall_h = _clamp_with_arche_constraints(arche_id, wall_h, "minWallHeight", "maxWallHeight")
    tower_h = _arche_default_int(arche_id, "towerHeight", 18)
    tower_h = _clamp_with_arche_constraints(arche_id, tower_h, "minTowerHeight", "maxTowerHeight")
    follow_terrain = _arche_default_bool(arche_id, "followTerrain", True)

    facing = _parse_door_facing_from_text(text)
    if not facing:
        facing = "SOUTH"

    include_paths = _parse_include_paths_from_text(text)
    if include_paths is None:
        include_paths = _arche_default_bool(arche_id, "includePaths", True)
    path_width = _parse_path_width_from_text(text) or _arche_default_int(arche_id, "pathWidth", 3)
    path_width = _clamp_with_arche_constraints(arche_id, path_width, "minPathWidth", "maxPathWidth")

    t_low = text.lower()
    detail_level = "aesthetic"
    if any(k in t_low for k in ("精致", "细节", "更复杂", "more detail", "refined", "ornate")):
        detail_level = "refined"

    materials = Materials(
        wall="minecraft:stone_bricks",
        roof="minecraft:stone_bricks",
        floor="minecraft:spruce_planks",
        window="minecraft:glass_pane",
        foundation="minecraft:stone_bricks",
    )
    features = Features(
        hasWindows=True,
        hasStairs=True,
        hasDoor=True,
        hasBalcony=True,
        hasRoof=True,
        hasRoofDecoration=True,
        windowCount=0,
        floorCount=max(2, min(6, tower_h // 6)),
    )
    style_options = StyleOptions(
        doorStyle="arched",
        roofType="cone",
        bridgeType="flat",
        windowRatio=0.2,
        windowStyle="pane",
        wallPattern="random",
    )

    spec = BuildingSpec(
        type=BuildingType.CASTLE,
        style=BuildingStyle.MEDIEVAL,
        footprint=Footprint(shape="rectangle", width=w, depth=d),
        height=max(12, tower_h),
        floors=1,
        materials=materials,
        features=features,
        styleOptions=style_options,
        notes=f"模板：城堡复合体（{w}×{d}，wallH={wall_h}，towerH={tower_h}，facing={facing}）。使用 COMPOUND 组合器。",
        extra={
            "template": "castle_compound",
            "landmark": "castle_compound",
            "wallHeight": wall_h,
            "towerHeight": tower_h,
            "wallThickness": 2,
            "gateWidth": 3,
            "facing": facing,
            "followTerrain": follow_terrain,
            "detailLevel": detail_level,
            "includePaths": bool(include_paths),
            "pathWidth": int(path_width),
        },
    )
    return _ensure_genome_for_spec(spec, req)


def _try_generate_castle_blueprint(req: BuildRequest, spec: BuildingSpec) -> None:
    """
    Best-effort: ask LLM for a semantic component blueprint and attach to spec.extra.blueprint.
    Never throws; if anything fails, we keep deterministic castle_compound behavior.
    """
    try:
        client = get_client(req)
        if not client:
            return
        if spec is None:
            return
        if spec.extra is None:
            spec.extra = {}
        # Don't clobber user-provided blueprint
        if "blueprint" in spec.extra and spec.extra.get("blueprint") is not None:
            return

        w = 48
        d = 36
        try:
            if spec.footprint and spec.footprint.width:
                w = int(spec.footprint.width)
            if spec.footprint and spec.footprint.depth:
                d = int(spec.footprint.depth)
        except Exception:
            pass
        facing = str((spec.extra or {}).get("facing") or "SOUTH")

        system_prompt = (
            "You are FormaCraft Blueprint Planner.\n"
            "Your job is to output a semantic component blueprint for a CASTLE compound.\n"
            "IMPORTANT:\n"
            "- Output MUST be valid JSON only. No markdown. No comments.\n"
            "- Output MUST include blueprint_type and blueprint_version.\n"
            "- blueprint_version MUST be 1.\n"
            "- Coordinate system: CORNER (x,z start at 0..overall_dimensions.x/z).\n"
            "- For CUBOID components (KEEP), relative_position is the MIN CORNER.\n"
            "- For CYLINDER components (TOWER), relative_position is the MIN CORNER of its bounding box.\n"
            "- For BOX_FRAME (WALL_CONNECTOR), relative_position is the MIN CORNER of the frame.\n\n"
            "SCHEMA:\n"
            "{\n"
            '  "blueprint_type": "castle",\n'
            '  "blueprint_version": 1,\n'
            '  "project_name": "string",\n'
            '  "coordinate_system": "CORNER",\n'
            '  "overall_dimensions": {"x": int, "z": int, "height_max": int},\n'
            '  "gate": {"side": "NORTH|SOUTH|EAST|WEST", "width": int},\n'
            '  "components": [\n'
            "    {\n"
            '      "type": "KEEP|GATEHOUSE|TOWER|WALL_CONNECTOR",\n'
            '      "shape": "CUBOID|CYLINDER|BOX_FRAME",\n'
            '      "relative_position": {"x": int, "y": int, "z": int},\n'
            '      "dimensions": {"width": int, "depth": int, "height": int} OR {"diameter": int, "height": int},\n'
             '      "features": {"battlements": bool, "flag": bool, "arches": bool, "banner": bool, "bannerColor": "red|black|white|blue|green", "lighting": "none|door|perimeter", "lightingType": "torch|lantern", "spacing": int} OR []\n'
            "    }\n"
            "  ]\n"
            "}\n"
        )

        user_prompt = (
            f"Request: {req.requestText}\n"
            f"Desired overall footprint: {w}x{d}\n"
            f"Facing (gate side): {facing}\n"
            "Make a reasonable medieval castle compound layout:\n"
            "- gate.side must match the facing (gate side)\n"
            "- include a central KEEP\n"
            "- include a GATEHOUSE just inside the gate opening\n"
            "- include four corner TOWERs\n"
            "- include one WALL_CONNECTOR box_frame that represents the perimeter wall rectangle (same overall footprint)\n"
        )

        model = _resolve_model(req, "gpt-4o-mini")
        timeout_sec = _resolve_timeout_sec_for_task("building", req, model)
        response = _call_with_timeout(
            lambda: client.chat.completions.create(
                model=model,
                messages=[
                    {"role": "system", "content": system_prompt},
                    {"role": "user", "content": user_prompt},
                ],
                response_format={"type": "json_object"},
                temperature=_clamp_temperature(getattr(req, "temperature", None), 0.2),
            ),
            timeout_sec,
        )
        raw = response.choices[0].message.content
        if not raw:
            return
        data = json.loads(raw)
        # basic cleanup: strip any accidental comment-like keys
        if isinstance(data, dict):
            # Ensure schema keys exist for Java-side validation.
            if not data.get("blueprint_type"):
                data["blueprint_type"] = "castle"
            if not data.get("blueprint_version"):
                data["blueprint_version"] = 1
            if "components" in data and isinstance(data["components"], list):
                cleaned = []
                for c in data["components"]:
                    if isinstance(c, dict):
                        c2 = {k: v for k, v in c.items() if not str(k).startswith("//")}
                        cleaned.append(c2)
                data["components"] = cleaned
        spec.extra["blueprint"] = data
        # Recommend a fitting palette if user didn't set one.
        if "paletteId" not in spec.extra:
            spec.extra["paletteId"] = "PALETTE_STONE_FORTRESS_A"
    except Exception:
        return


def _should_use_office_district_template(req: Optional[BuildRequest]) -> bool:
    if req is None:
        return False
    text = (getattr(req, "requestText", None) or "") + "\n" + (getattr(req, "userMessage", None) or "")
    s = text.lower()
    if not s.strip():
        return False
    if any(k in s for k in ("随意", "随便", "自由发挥", "你决定", "random", "freeform")):
        return False
    return any(k in s for k in ("办公楼群", "办公园区", "写字楼群", "商业区", "office district", "office park", "business district"))


def _parse_grid_from_text(s: str) -> tuple[Optional[int], Optional[int], Optional[int]]:
    """
    Returns: (rows, cols, spacing)
    Accepts:
    - 3x4 / 3×4
    - rows=3 cols=4
    - 间距 18 / spacing 18
    """
    if not s:
        return None, None, None
    t = s.lower()
    rows = None
    cols = None
    spacing = None
    m = re.search(r"(\d{1,2})\s*(?:x|×)\s*(\d{1,2})", t)
    if m:
        try:
            rows = int(m.group(1))
            cols = int(m.group(2))
        except Exception:
            pass
    m = re.search(r"(?:rows)\s*(?:=|:)?\s*(\d{1,2})", t)
    if m:
        try:
            rows = int(m.group(1))
        except Exception:
            pass
    m = re.search(r"(?:cols|columns)\s*(?:=|:)?\s*(\d{1,2})", t)
    if m:
        try:
            cols = int(m.group(1))
        except Exception:
            pass
    m = re.search(r"(?:间距|spacing)\s*(?:为|=|:)?\s*(\d{1,3})", t)
    if m:
        try:
            spacing = int(m.group(1))
        except Exception:
            pass
    return rows, cols, spacing


def _parse_include_roads_from_text(s: str) -> Optional[bool]:
    if not s:
        return None
    t = s.lower()
    if any(k in t for k in ("不要道路", "无道路", "不需要道路", "no roads", "without roads")):
        return False
    if any(k in t for k in ("道路", "路网", "road", "roads", "street", "streets")):
        return True
    return None


def _parse_road_width_from_text(s: str) -> Optional[int]:
    if not s:
        return None
    m = re.search(r"(?:道路宽度|路宽|road\s*width)\s*(?:为|=|:)?\s*(\d{1,2})", s, flags=re.IGNORECASE)
    if not m:
        return None
    try:
        v = int(m.group(1))
        return v if v > 0 else None
    except Exception:
        return None


def _parse_include_paths_from_text(s: str) -> Optional[bool]:
    if not s:
        return None
    t = s.lower()
    if any(k in t for k in ("不要路", "不铺路", "不要铺路", "无路", "no path", "no paths", "without path", "without paths")):
        return False
    if any(k in t for k in ("铺路", "石板路", "路面", "小路", "path", "paths", "walkway", "walkways")):
        return True
    return None


def _parse_path_width_from_text(s: str) -> Optional[int]:
    if not s:
        return None
    m = re.search(r"(?:路宽|小路宽度|路径宽度|path\s*width)\s*(?:为|=|:)?\s*(\d{1,2})", s, flags=re.IGNORECASE)
    if not m:
        return None
    try:
        v = int(m.group(1))
        return v if v > 0 else None
    except Exception:
        return None


def _generate_office_district_building_spec(req: BuildRequest) -> BuildingSpec:
    text = (req.requestText or "") + "\n" + (req.userMessage or "")
    arche_id = "office_district"

    r_in, c_in, sp_in = _parse_grid_from_text(text)
    rows = r_in or _arche_default_int(arche_id, "rows", 3)
    cols = c_in or _arche_default_int(arche_id, "cols", 4)
    spacing = sp_in or _arche_default_int(arche_id, "spacing", 18)

    rows = _clamp_with_arche_constraints(arche_id, rows, "minRows", "maxRows")
    cols = _clamp_with_arche_constraints(arche_id, cols, "minCols", "maxCols")
    spacing = _clamp_with_arche_constraints(arche_id, spacing, "minSpacing", "maxSpacing")

    block_w = _arche_default_int(arche_id, "blockWidth", 11)
    block_d = _arche_default_int(arche_id, "blockDepth", 11)
    block_h = _arche_default_int(arche_id, "blockHeight", 22)
    block_w = _clamp_with_arche_constraints(arche_id, block_w, "minBlockWidth", "maxBlockWidth")
    block_d = _clamp_with_arche_constraints(arche_id, block_d, "minBlockDepth", "maxBlockDepth")
    block_h = _clamp_with_arche_constraints(arche_id, block_h, "minBlockHeight", "maxBlockHeight")

    include_roads = _parse_include_roads_from_text(text)
    if include_roads is None:
        include_roads = _arche_default_bool(arche_id, "includeRoads", True)
    road_width = _parse_road_width_from_text(text) or _arche_default_int(arche_id, "roadWidth", 3)
    road_width = _clamp_with_arche_constraints(arche_id, road_width, "minRoadWidth", "maxRoadWidth")

    materials = Materials(
        wall="minecraft:light_gray_concrete",
        roof="minecraft:smooth_stone",
        floor="minecraft:smooth_stone",
        window="minecraft:glass_pane",
        foundation="minecraft:stone_bricks",
    )
    features = Features(
        hasWindows=True,
        hasStairs=False,
        hasDoor=True,
        hasBalcony=False,
        hasRoof=True,
        hasRoofDecoration=False,
        windowCount=0,
        floorCount=max(2, min(12, block_h // 4)),
    )
    style_options = StyleOptions(
        doorStyle="single",
        roofType="flat",
        bridgeType="flat",
        windowRatio=0.6,
        windowStyle="pane",
        wallPattern="uniform",
    )

    spec = BuildingSpec(
        type=BuildingType.HOUSE,
        style=BuildingStyle.MODERN,
        footprint=Footprint(shape="rectangle", width=max(24, cols * spacing), depth=max(24, rows * spacing)),
        height=max(12, block_h),
        floors=1,
        materials=materials,
        features=features,
        styleOptions=style_options,
        notes=f"模板：办公楼群（{rows}×{cols}，间距≈{spacing}，单体≈{block_w}×{block_d}×{block_h}）。使用 GRID 骨架。",
        extra={
            "template": "office_district",
            "landmark": "office_district",
            "rows": rows,
            "cols": cols,
            "spacing": spacing,
            "blockWidth": block_w,
            "blockDepth": block_d,
            "blockHeight": block_h,
            "includeRoads": bool(include_roads),
            "roadWidth": int(road_width),
        },
    )
    return _ensure_genome_for_spec(spec, req)

def _parse_levels_from_text(s: str) -> Optional[int]:
    if not s:
        return None
    # 支持：7层/七层/层数 7 / levels 7
    m = re.search(r"(?:层数|levels|storeys|stories)\s*(?:为|=|:)?\s*(\d{1,2})", s, flags=re.IGNORECASE)
    if m:
        try:
            v = int(m.group(1))
            return v if v > 0 else None
        except Exception:
            return None
    m = re.search(r"(\d{1,2})\s*(?:层|floor|storey|story)", s, flags=re.IGNORECASE)
    if m:
        try:
            v = int(m.group(1))
            return v if v > 0 else None
        except Exception:
            return None
    # 中文数字（简化：覆盖常用 1~20）
    t = s.lower()
    m = re.search(r"([一二两三四五六七八九十]{1,3})\s*层", t)
    if m:
        cn = m.group(1)
        mapping = {"一": 1, "二": 2, "两": 2, "三": 3, "四": 4, "五": 5, "六": 6, "七": 7, "八": 8, "九": 9, "十": 10}
        if cn in mapping:
            return mapping[cn]
        if cn.startswith("十") and len(cn) == 2 and cn[1] in mapping:
            return 10 + mapping[cn[1]]
        if len(cn) == 2 and cn[0] in mapping and cn[1] == "十":
            return mapping[cn[0]] * 10
        if len(cn) == 3 and cn[0] in mapping and cn[1] == "十" and cn[2] in mapping:
            return mapping[cn[0]] * 10 + mapping[cn[2]]
    return None


def _generate_giant_wild_goose_pagoda_building_spec(req: BuildRequest) -> BuildingSpec:
    text = (req.requestText or "") + "\n" + (req.userMessage or "")

    arche_id = "giant_wild_goose_pagoda"
    levels = _parse_levels_from_text(text) or _arche_default_int(arche_id, "levels", 7)
    levels = _clamp_with_arche_constraints(arche_id, levels, "minLevels", "maxLevels")
    levels = max(3, min(13, levels))  # safety fallback

    height = _parse_height_from_text(text) or _arche_default_int(arche_id, "height", (levels * 5 + 6))
    height = _clamp_with_arche_constraints(arche_id, height, "minHeight", "maxHeight")
    height = max(18, min(120, height))  # safety fallback

    base_w = _parse_base_width_from_text(text) or _arche_default_int(arche_id, "baseWidth", 17)
    base_w = _clamp_with_arche_constraints(arche_id, base_w, "minBaseWidth", "maxBaseWidth")
    base_w = max(9, min(41, base_w))  # safety fallback
    if base_w % 2 == 0:
        base_w += 1

    facing = _parse_door_facing_from_text(text)
    if not facing:
        try:
            facing = str(getattr(req.player, "facing", "")).strip().upper() if req and getattr(req, "player", None) else ""
        except Exception:
            facing = ""
    if facing not in ("NORTH", "SOUTH", "EAST", "WEST"):
        facing = "SOUTH"

    t_low = text.lower()
    detail_level = "aesthetic"
    if any(k in t_low for k in ("精致", "细节", "更复杂", "more detail", "refined", "ornate")):
        detail_level = "refined"

    materials = Materials(
        wall="minecraft:bricks",
        roof="minecraft:brick_slab",
        floor="minecraft:smooth_stone",
        window="minecraft:air",
        foundation="minecraft:stone_bricks",
    )
    features = Features(
        hasWindows=False,
        hasStairs=True,
        hasDoor=True,
        hasBalcony=True,
        hasRoof=True,
        hasRoofDecoration=True,
        windowCount=0,
        floorCount=levels,
    )
    style_options = StyleOptions(
        doorStyle="arched",
        roofType="hipped",
        bridgeType="flat",
        windowRatio=0.0,
        windowStyle="pane",
        wallPattern="uniform",
    )

    spec = BuildingSpec(
        type=BuildingType.TOWER,
        style=BuildingStyle.ASIAN,
        footprint=Footprint(shape="rectangle", width=base_w, depth=base_w),
        height=height,
        floors=levels,
        materials=materials,
        features=features,
        styleOptions=style_options,
        notes=f"模板：大慈恩寺·大雁塔（levels={levels}，h≈{height}，base≈{base_w}，facing={facing}）。",
        extra={
            "landmark": "giant_wild_goose_pagoda",
            "levels": levels,
            "towerHeight": height,
            "baseWidth": base_w,
            "facing": facing,
            "detailLevel": detail_level,
            "bodyBlock": "minecraft:bricks",
            "trimBlock": "minecraft:stone_brick_slab",
            "eaveBlock": "minecraft:brick_slab",
            "accentBlock": "minecraft:chiseled_stone_bricks",
        },
    )
    return _ensure_genome_for_spec(spec, req)


def _parse_height_from_text(s: str) -> Optional[int]:
    if not s:
        return None
    # 支持：高度 80 / 高80 / height=80
    m = re.search(r"(?:高度|height)\s*(?:为|=|:)?\s*(\d{1,3})", s, flags=re.IGNORECASE)
    if not m:
        m = re.search(r"(?:\bhigh|\bheight)\s*(?:=|:)?\s*(\d{1,3})", s, flags=re.IGNORECASE)
    if not m:
        m = re.search(r"(?:\b高)\s*(\d{1,3})", s, flags=re.IGNORECASE)
    if not m:
        return None
    try:
        v = int(m.group(1))
        return v if v > 0 else None
    except Exception:
        return None


def _parse_base_width_from_text(s: str) -> Optional[int]:
    if not s:
        return None
    # 支持：底座宽度 24 / 底边 30 / base width=20 / span 18
    m = re.search(r"(?:底座宽度|底座宽|底边|底宽|base\s*width|basewidth|span)\s*(?:为|=|:)?\s*(\d{1,3})", s, flags=re.IGNORECASE)
    if not m:
        return None
    try:
        v = int(m.group(1))
        return v if v > 0 else None
    except Exception:
        return None


def _generate_eiffel_tower_building_spec(req: BuildRequest) -> BuildingSpec:
    text = (req.requestText or "") + "\n" + (req.userMessage or "")

    arche_id = "eiffel_tower"
    height = _parse_height_from_text(text) or _arche_default_int(arche_id, "height", 60)
    height = _clamp_with_arche_constraints(arche_id, height, "minHeight", "maxHeight")
    height = max(24, min(180, height))  # safety fallback

    base_w = _parse_base_width_from_text(text) or _arche_default_int(arche_id, "baseWidth", 27)
    base_w = _clamp_with_arche_constraints(arche_id, base_w, "minBaseWidth", "maxBaseWidth")
    base_w = max(14, min(80, base_w))  # safety fallback
    # prefer odd width for nicer symmetry (centered on origin)
    if base_w % 2 == 0:
        base_w += 1

    t_low = text.lower()
    detail_level = "aesthetic"
    if any(k in t_low for k in ("精致", "细节", "更复杂", "more detail", "refined", "ornate")):
        detail_level = "refined"

    materials = Materials(
        wall="minecraft:iron_block",
        roof="minecraft:iron_block",
        floor="minecraft:smooth_stone",
        window="minecraft:glass_pane",
        foundation="minecraft:stone_bricks",
    )
    features = Features(
        hasWindows=False,
        hasStairs=True,
        hasDoor=False,
        hasBalcony=True,
        hasRoof=False,
        hasRoofDecoration=False,
        windowCount=0,
        floorCount=1,
    )
    style_options = StyleOptions(
        doorStyle="none",
        roofType="flat",
        bridgeType="flat",
        windowRatio=0.0,
        windowStyle="pane",
        wallPattern="uniform",
    )

    spec = BuildingSpec(
        type=BuildingType.TOWER,
        style=BuildingStyle.MODERN,
        footprint=Footprint(shape="rectangle", width=base_w, depth=base_w),
        height=height,
        floors=1,
        materials=materials,
        features=features,
        styleOptions=style_options,
        notes="Eiffel Tower (landmark archetype, parameterized)",
        extra={
            "landmark": "eiffel_tower",
            "towerHeight": height,
            "baseWidth": base_w,
            "platformCount": 2,
            "detailLevel": detail_level,
            # allow future overrides in generator
            "legBlock": "minecraft:iron_block",
            "braceBlock": "minecraft:iron_bars",
            "platformBlock": "minecraft:smooth_stone",
            "railBlock": "minecraft:iron_bars",
            "spireBlock": "minecraft:iron_block",
        },
    )
    return _ensure_genome_for_spec(spec, req)


def _parse_diameter_from_text(s: str) -> Optional[int]:
    if not s:
        return None
    # 支持：直径为20 / 直径20 / diameter 20 / 直径=20
    m = re.search(r"(?:直径|diameter)\s*(?:为|=|:)?\s*(\d{1,3})", s, flags=re.IGNORECASE)
    if not m:
        return None
    try:
        d = int(m.group(1))
        if d <= 0:
            return None
        return d
    except Exception:
        return None


def _parse_door_facing_from_text(s: str) -> Optional[str]:
    if not s:
        return None
    t = s.lower()
    # 中文：门朝南/南门/大门朝北 等
    if any(k in t for k in ("朝南", "南门", "面南", "向南")):
        return "SOUTH"
    if any(k in t for k in ("朝北", "北门", "面北", "向北")):
        return "NORTH"
    if any(k in t for k in ("朝东", "东门", "面东", "向东")):
        return "EAST"
    if any(k in t for k in ("朝西", "西门", "面西", "向西")):
        return "WEST"
    # 英文
    if "south" in t:
        return "SOUTH"
    if "north" in t:
        return "NORTH"
    if "east" in t:
        return "EAST"
    if "west" in t:
        return "WEST"
    return None


def _to_int_safe(v: Any) -> Optional[int]:
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


def _to_bool_safe(v: Any) -> Optional[bool]:
    if v is None:
        return None
    if isinstance(v, bool):
        return v
    s = str(v).strip().lower()
    if not s:
        return None
    if s in ("true", "1", "yes", "y", "on"):
        return True
    if s in ("false", "0", "no", "n", "off"):
        return False
    return None


def _arche_default_int(archetype_id: str, key: str, fallback: int) -> int:
    d = get_archetype_def(archetype_id)
    if d and isinstance(d.defaults_map, dict):
        v = _to_int_safe(d.defaults_map.get(key))
        if v is not None:
            return v
    return fallback


def _arche_default_bool(archetype_id: str, key: str, fallback: bool) -> bool:
    d = get_archetype_def(archetype_id)
    if d and isinstance(d.defaults_map, dict):
        v = _to_bool_safe(d.defaults_map.get(key))
        if v is not None:
            return v
    return fallback


def _arche_constraint_int(archetype_id: str, key: str) -> Optional[int]:
    d = get_archetype_def(archetype_id)
    if d and isinstance(d.constraints_map, dict):
        return _to_int_safe(d.constraints_map.get(key))
    return None


def _clamp_with_arche_constraints(archetype_id: str, value: int, min_key: str, max_key: str) -> int:
    mn = _arche_constraint_int(archetype_id, min_key)
    mx = _arche_constraint_int(archetype_id, max_key)
    if mn is not None:
        value = max(mn, value)
    if mx is not None:
        value = min(mx, value)
    return value


def _parse_ring_thickness_from_text(s: str) -> Optional[int]:
    if not s:
        return None
    # 支持：环带厚度 5 / 墙厚 4 / 厚度=6
    m = re.search(r"(?:环带厚度|居住带厚度|墙厚|厚度)\s*(?:为|=|:)?\s*(\d{1,2})", s, flags=re.IGNORECASE)
    if not m:
        return None
    try:
        v = int(m.group(1))
        if v <= 0:
            return None
        return v
    except Exception:
        return None


def _parse_courtyard_from_text(s: str) -> tuple[Optional[int], Optional[float]]:
    """
    Returns: (courtyard_diameter, courtyard_ratio)
    - 内院直径：内院直径为10 / courtyard diameter 12
    - 内院占比：内院占比50% / courtyard ratio 0.5
    """
    if not s:
        return None, None
    t = s.lower()

    # diameter
    m = re.search(r"(?:内院直径|庭院直径|courtyard\s*diameter)\s*(?:为|=|:)?\s*(\d{1,3})", t, flags=re.IGNORECASE)
    if m:
        try:
            d = int(m.group(1))
            if d > 0:
                return d, None
        except Exception:
            pass

    # ratio: 50% / 0.5
    m = re.search(r"(?:内院占比|庭院占比|courtyard\s*ratio)\s*(?:为|=|:)?\s*(\d{1,3})\s*%", t, flags=re.IGNORECASE)
    if m:
        try:
            p = int(m.group(1))
            if 1 <= p <= 99:
                return None, p / 100.0
        except Exception:
            pass
    m = re.search(r"(?:内院占比|庭院占比|courtyard\s*ratio)\s*(?:为|=|:)?\s*(0\.\d+)", t, flags=re.IGNORECASE)
    if m:
        try:
            r = float(m.group(1))
            if 0.1 < r < 0.9:
                return None, r
        except Exception:
            pass

    return None, None


def _parse_window_shutter_from_text(s: str) -> tuple[bool, Optional[bool]]:
    """
    Returns: (enabled, open)
    enabled: 是否需要百叶/窗扇（trapdoor shutter）
    open: 是否要求打开/半开（None 表示不指定）
    """
    if not s:
        return False, None
    t = s.lower()
    enabled = any(k in t for k in ("百叶", "窗扇", "百叶窗", "trapdoor", "shutter", "window shutter", "木窗", "木质窗"))
    if not enabled:
        return False, None
    # open/close hints
    if any(k in t for k in ("半开", "打开", "开启", "open", "opened", "ajar")):
        return True, True
    if any(k in t for k in ("关闭", "关上", "闭合", "closed", "shut", "不开")):
        return True, False
    return True, None


def _parse_shutter_color_from_text(s: str) -> Optional[str]:
    """
    Returns a block id for trapdoor shutter:
    - light -> minecraft:oak_trapdoor
    - dark -> minecraft:dark_oak_trapdoor
    None -> unspecified
    """
    if not s:
        return None
    t = s.lower()
    # light hints
    if any(k in t for k in ("浅色", "浅木", "亮色", "橡木", "oak")) and not any(k in t for k in ("深橡木", "dark oak", "dark_oak", "深色", "深木")):
        return "minecraft:oak_trapdoor"
    # dark hints
    if any(k in t for k in ("深色", "深木", "深橡木", "dark oak", "dark_oak")):
        return "minecraft:dark_oak_trapdoor"
    return None


def _generate_tulou_building_spec(req: BuildRequest) -> BuildingSpec:
    text = (req.requestText or "") + "\n" + (req.userMessage or "")
    arche_id = "tulou"
    diameter = _parse_diameter_from_text(text) or _arche_default_int(arche_id, "diameter", 20)
    diameter = _clamp_with_arche_constraints(arche_id, diameter, "minDiameter", "maxDiameter")
    diameter = max(12, min(80, diameter))  # safety fallback
    radius = max(6, diameter // 2)

    # 层数：可从文本粗略提取，如“3层/三层”；没写则默认 3
    floors = _arche_default_int(arche_id, "floors", 3)
    m = re.search(r"(\d)\s*(?:层|floor)", text, flags=re.IGNORECASE)
    if m:
        try:
            floors = int(m.group(1))
        except Exception:
            floors = _arche_default_int(arche_id, "floors", 3)
    floors = _clamp_with_arche_constraints(arche_id, floors, "minFloors", "maxFloors")
    floors = max(2, min(6, floors))  # safety fallback

    door_facing = _parse_door_facing_from_text(text) or "SOUTH"
    ring_thickness_req = _parse_ring_thickness_from_text(text)
    courtyard_diam, courtyard_ratio = _parse_courtyard_from_text(text)
    shutter_enabled, shutter_open_hint = _parse_window_shutter_from_text(text)
    shutter_block = _parse_shutter_color_from_text(text)

    # 细节偏好：用户当前默认更“观赏性”，但如果明确说“精致/细节更多/更复杂”则切 refined
    t_low = text.lower()
    detail_level = "aesthetic"
    if any(k in t_low for k in ("精致", "细节", "更复杂", "more detail", "refined", "ornate")):
        detail_level = "refined"

    materials = Materials(
        wall="minecraft:mud_bricks",
        roof="minecraft:deepslate_tiles",
        floor="minecraft:spruce_planks",
        window="minecraft:glass_pane",
        foundation="minecraft:stone_bricks",
    )
    features = Features(
        hasWindows=True,
        hasStairs=True,
        hasDoor=True,
        hasBalcony=True,
        hasRoof=True,
        hasRoofDecoration=False,
        windowCount=0,
        floorCount=floors,
    )
    style_options = StyleOptions(
        doorStyle="single",
        roofType="cone",
        bridgeType="flat",
        windowRatio=0.20,
        windowStyle="pane",
        wallPattern="uniform",
    )

    ring_thickness = min(8, max(3, radius // 3))
    if ring_thickness_req is not None:
        ring_thickness = max(3, min(8, int(ring_thickness_req)))

    extra: Dict[str, Any] = {
        "template": "tulou",
        "landmark": "tulou",
        "diameter": radius * 2,
        "ringThickness": ring_thickness,
        "doorFacing": door_facing,
        "detailLevel": detail_level,
    }
    if courtyard_diam is not None:
        # store courtyard radius for generator
        extra["courtyardRadius"] = max(3, min(radius - 3, int(courtyard_diam // 2)))
    if courtyard_ratio is not None:
        extra["courtyardRatio"] = float(courtyard_ratio)
    if shutter_enabled:
        extra["windowShutter"] = True
        # 默认：观赏性更“规整”所以关闭；精致更立体所以半开
        if shutter_open_hint is None:
            extra["windowShutterOpen"] = (detail_level == "refined")
        else:
            extra["windowShutterOpen"] = bool(shutter_open_hint)
        # 默认深色；用户明确“浅色/橡木”则覆盖
        extra["windowShutterBlock"] = shutter_block or "minecraft:dark_oak_trapdoor"

    spec = BuildingSpec(
        type=BuildingType.HOUSE,
        style=BuildingStyle.ASIAN,
        footprint=Footprint(shape="circle", radius=radius),
        height=max(10, floors * 4 + 4),
        floors=floors,
        materials=materials,
        features=features,
        styleOptions=style_options,
        notes=f"模板：福建土楼（直径≈{radius*2}，{floors}层，门朝{door_facing}）。使用确定性模板以保证形态稳定。",
        extra=extra,
    )
    return _ensure_genome_for_spec(spec, req)


def _parse_rect_size_from_text(s: str) -> Optional[tuple[int, int]]:
    if not s:
        return None
    # 支持：20×20 / 20x20 / 20*20 / 20 X 20
    m = re.search(r"(\d{1,3})\s*(?:×|x|\*)\s*(\d{1,3})", s, flags=re.IGNORECASE)
    if not m:
        return None
    try:
        w = int(m.group(1))
        d = int(m.group(2))
        if w <= 0 or d <= 0:
            return None
        return w, d
    except Exception:
        return None


def _generate_mingqing_courtyard_building_spec(req: BuildRequest) -> BuildingSpec:
    text = (req.requestText or "") + "\n" + (req.userMessage or "")
    arche_id = "mingqing_courtyard"
    size = _parse_rect_size_from_text(text)
    # 触发我们 Java 侧 ASIAN courtyard 生成器的最小安全尺寸
    w, d = size if size else (20, 20)
    w = _clamp_with_arche_constraints(arche_id, w, "minWidth", "maxWidth")
    d = _clamp_with_arche_constraints(arche_id, d, "minDepth", "maxDepth")

    # 明清官式 palette（尽量避免风格漂移成 stone+oak）
    materials = Materials(
        wall="minecraft:red_terracotta",
        roof="minecraft:deepslate_tiles",
        floor="minecraft:polished_andesite",
        window="minecraft:glass_pane",
        foundation="minecraft:stone_bricks",
    )
    features = Features(
        hasWindows=True,
        hasStairs=True,
        hasDoor=True,
        hasBalcony=False,
        hasRoof=True,
        hasRoofDecoration=True,
        windowCount=0,
        floorCount=1,
    )
    style_options = StyleOptions(
        doorStyle="double",
        roofType="hipped",
        bridgeType="flat",
        windowRatio=0.18,
        windowStyle="fence",
        wallPattern="uniform",
    )

    include_paths = _parse_include_paths_from_text(text)
    if include_paths is None:
        include_paths = _arche_default_bool(arche_id, "includePaths", True)
    path_width = _parse_path_width_from_text(text) or _arche_default_int(arche_id, "pathWidth", 3)
    path_width = _clamp_with_arche_constraints(arche_id, path_width, "minPathWidth", "maxPathWidth")

    spec = BuildingSpec(
        type=BuildingType.HOUSE,
        style=BuildingStyle.ASIAN,
        footprint=Footprint(shape="rectangle", width=w, depth=d),
        height=10,
        floors=1,
        materials=materials,
        features=features,
        styleOptions=style_options,
        notes=f"模板：明清官式院落（{w}×{d}）。为保证按描述生成，使用确定性模板（除非用户要求随意）。",
        extra={"template": "mingqing_courtyard", "includePaths": bool(include_paths), "pathWidth": int(path_width)},
    )
    return _ensure_genome_for_spec(spec, req)


def _normalize_building_spec_dict(data: Any) -> Any:
    """
    Best-effort normalization for common model output mistakes:
    - buildingType -> type
    - buildingStyle -> style
    - missing required objects: materials/features -> {}
    This prevents hard 502s for minor schema drift.
    """
    if not isinstance(data, dict):
        return data

    # common key aliases
    if "type" not in data and "buildingType" in data:
        data["type"] = data.get("buildingType")
    if "style" not in data and "buildingStyle" in data:
        data["style"] = data.get("buildingStyle")

    # required nested objects (BuildingSpec requires them but nested models have defaults)
    if "materials" not in data or data.get("materials") is None:
        data["materials"] = {}
    if "features" not in data or data.get("features") is None:
        data["features"] = {}

    # footprint is required; provide a minimal default if missing
    if "footprint" not in data or data.get("footprint") is None:
        data["footprint"] = {"shape": "rectangle", "width": 8, "depth": 6}

    # height should exist; default if missing/invalid
    try:
        h = int(data.get("height") if data.get("height") is not None else 0)
        if h <= 0:
            data["height"] = 10
    except Exception:
        data["height"] = 10

    # If still missing type, default to HOUSE so we can proceed.
    if "type" not in data or not data.get("type"):
        data["type"] = "HOUSE"

    # Normalize style gene fields: keep them in extra with stable camelCase keys.
    # (Java side expects extra.styleProfileId / extra.paletteId; aliases are common in LLM output.)
    try:
        extra = data.get("extra")
        if extra is None:
            extra = {}
        if not isinstance(extra, dict):
            extra = {}

        # Promote top-level keys into extra if the model placed them incorrectly.
        for k_top, k_canon in (
            ("styleProfileId", "styleProfileId"),
            ("style_profile_id", "styleProfileId"),
            ("paletteId", "paletteId"),
            ("palette_id", "paletteId"),
        ):
            if k_top in data and data.get(k_top) is not None:
                if k_canon not in extra or extra.get(k_canon) is None:
                    extra[k_canon] = data.get(k_top)

        remapped: Dict[str, Any] = {}
        for k, v in extra.items():
            ks = str(k).strip()
            kl = ks.lower()
            if kl in ("palette_id", "paletteid", "palette"):
                remapped["paletteId"] = v
            elif kl in ("styleprofileid", "style_profile_id", "style_profile", "styleprofile"):
                remapped["styleProfileId"] = v
            else:
                remapped[ks] = v

        # Clean empty values (blank strings / None)
        for kk in ("paletteId", "styleProfileId"):
            if kk in remapped:
                vv = remapped.get(kk)
                if vv is None:
                    remapped.pop(kk, None)
                else:
                    ss = str(vv).strip()
                    if not ss:
                        remapped.pop(kk, None)
                    else:
                        remapped[kk] = ss

        # Validate ids against catalogs (best-effort) and provide deterministic fallback.
        # - If invalid, drop the key and add a debug warning.
        # - If styleProfileId is valid and paletteId is missing, fill paletteId from style default (if it exists & valid).
        try:
            from app.services.style_profile_registry import has_style_profile, has_palette, default_palette_for_style

            debug = remapped.get("debugWarnings")
            if debug is None:
                debug_list = []
            elif isinstance(debug, list):
                debug_list = debug
            else:
                debug_list = [str(debug)]

            spid = remapped.get("styleProfileId")
            if spid is not None and not has_style_profile(spid):
                debug_list.append(f"Invalid extra.styleProfileId='{spid}', dropped.")
                remapped.pop("styleProfileId", None)
                spid = None

            pid = remapped.get("paletteId")
            if pid is not None and not has_palette(pid):
                debug_list.append(f"Invalid extra.paletteId='{pid}', dropped.")
                remapped.pop("paletteId", None)
                pid = None

            if spid is not None and pid is None:
                dp = default_palette_for_style(spid)
                if dp and has_palette(dp):
                    remapped["paletteId"] = dp
                    remapped["paletteIdAutoFromStyle"] = True
                elif dp:
                    debug_list.append(f"Style defaultPalette='{dp}' not found in PaletteCatalog, ignored.")

            if debug_list:
                remapped["debugWarnings"] = debug_list[:20]
        except Exception:
            pass

        # Normalize layout IR into extra.layout
        try:
            layout_in = remapped.get("layout")
            # Promote common top-level or extra-level keys into layout if present
            for k_layout in ("entranceFacing", "symmetry", "plan", "courtyard", "courtyardRatio"):
                if k_layout in remapped and (layout_in is None or not isinstance(layout_in, dict)):
                    layout_in = {}
                if isinstance(layout_in, dict) and k_layout in remapped and k_layout not in layout_in:
                    layout_in[k_layout] = remapped.get(k_layout)

            if layout_in is None and isinstance(data.get("layout"), dict):
                layout_in = data.get("layout")

            if isinstance(layout_in, dict):
                out_layout: Dict[str, Any] = {}
                ef = layout_in.get("entranceFacing") or layout_in.get("entrance_facing") or layout_in.get("entrance")
                if ef is not None:
                    s = str(ef).strip().upper()
                    if s in ("NORTH", "SOUTH", "EAST", "WEST"):
                        out_layout["entranceFacing"] = s

                sym = layout_in.get("symmetry")
                if sym is not None:
                    s = str(sym).strip().upper()
                    if s in ("NONE", "X", "Z", "BOTH"):
                        out_layout["symmetry"] = s

                # plan: a minimal zoning plan that maps to actual generator behavior (best-effort)
                plan = layout_in.get("plan") or layout_in.get("floorPlan") or layout_in.get("floor_plan") or layout_in.get("zoning") or layout_in.get("zoningPlan")
                if plan is not None:
                    p = str(plan).strip().lower()
                    if p in ("none", "no", "false", "0", "off"):
                        out_layout["plan"] = "none"
                    elif p in ("front_back", "frontback", "front-back", "front/back", "前后", "前后分区", "前后布局", "前厅后室"):
                        out_layout["plan"] = "front_back"
                    elif p in ("left_right", "leftright", "left-right", "left/right", "左右", "左右分区", "左右布局"):
                        out_layout["plan"] = "left_right"
                    elif p in ("ring_corridor", "ring", "courtyard_corridor", "courtyard-corridor", "gallery", "cloister",
                               "回廊", "环廊", "环形走廊", "围绕中庭", "回字形", "回字布局", "回字走廊"):
                        out_layout["plan"] = "ring_corridor"

                ct = layout_in.get("courtyard")
                if isinstance(ct, bool):
                    out_layout["courtyard"] = ct
                elif ct is not None:
                    s = str(ct).strip().lower()
                    if s in ("true", "yes", "1"):
                        out_layout["courtyard"] = True
                    elif s in ("false", "no", "0"):
                        out_layout["courtyard"] = False

                cr = layout_in.get("courtyardRatio") or layout_in.get("courtyard_ratio")
                if cr is not None:
                    try:
                        f = float(cr)
                        if f < 0.2:
                            f = 0.2
                        if f > 0.8:
                            f = 0.8
                        out_layout["courtyardRatio"] = f
                    except Exception:
                        pass

                if out_layout:
                    remapped["layout"] = out_layout
        except Exception:
            pass

        data["extra"] = remapped
    except Exception:
        pass

    return data


def _normalize_building_style_value(v: Any) -> Optional[str]:
    if v is None:
        return None
    s = str(v).strip()
    if not s:
        return None
    u = s.upper()
    # valid enum values
    if u in ("MEDIEVAL", "MODERN", "ASIAN", "FUTURISTIC", "RUSTIC", "DEFAULT"):
        return u
    # common LLM drift for Chinese-themed labels
    low = s.lower()
    if "chinese" in low or "ming" in low or "qing" in low or "palace" in low or "asian" in low:
        return "ASIAN"
    if "modern" in low:
        return "MODERN"
    if "medieval" in low:
        return "MEDIEVAL"
    return "DEFAULT"


def _normalize_features_value(v: Any) -> Dict[str, Any]:
    # Features in our schema is an object, but LLMs often output a list like ["windows","doors",...]
    if isinstance(v, dict):
        return v
    flags: Dict[str, Any] = {
        "hasWindows": True,
        "hasStairs": True,
        "hasDoor": True,
        "hasBalcony": False,
        "hasRoof": True,
        "hasRoofDecoration": False,
        "windowCount": 0,
        "floorCount": 1,
    }
    if isinstance(v, list):
        low = {str(x).strip().lower() for x in v if x is not None}
        flags["hasWindows"] = ("windows" in low) or ("window" in low)
        flags["hasDoor"] = ("doors" in low) or ("door" in low) or ("gate" in low) or ("gates" in low)
        flags["hasStairs"] = ("stairs" in low) or ("stair" in low)
        flags["hasRoof"] = ("roof" in low) or ("curved_roof" in low) or ("decorative_eaves" in low)
        flags["hasRoofDecoration"] = ("decorative_cornices" in low) or ("decorative_top" in low) or ("decorative_eaves" in low)
    return flags


def _guess_building_type_from_name(name: str) -> str:
    n = (name or "").strip().lower()
    if not n:
        return "HOUSE"
    if "wall" in n or "enclosure" in n or "courtyard_wall" in n:
        return "WALL"
    if "gate" in n:
        return "HOUSE"
    if "tower" in n:
        return "TOWER"
    if "bridge" in n:
        return "BRIDGE"
    if "hall" in n or "wing" in n:
        return "HOUSE"
    return "HOUSE"


def _normalize_substructure_item(item: Any, idx: int) -> Dict[str, Any]:
    """
    Normalize one element of CompositeSpec.structures.
    Accept either proper {type, spec, offset} or LLM "plan-like" objects.
    """
    if not isinstance(item, dict):
        return {
            "type": "HOUSE",
            "spec": _normalize_building_spec_dict({
                "type": "HOUSE",
                "style": "ASIAN",
                "materials": {},
                "features": {},
                "footprint": {"shape": "rectangle", "width": 8, "depth": 6},
                "height": 6
            }),
            "offset": {"x": idx * 16, "y": 0, "z": 0},
        }

    # offset
    off = item.get("offset") or item.get("pos") or item.get("position") or item.get("relativePos") or item.get("relative_pos")
    if isinstance(off, dict) and all(k in off for k in ("x", "y", "z")):
        offset = {"x": int(off.get("x", 0)), "y": int(off.get("y", 0)), "z": int(off.get("z", 0))}
    else:
        offset = {"x": idx * 16, "y": 0, "z": 0}

    # outer type
    name = str(item.get("name") or "")
    t = item.get("type")
    if t is None or (isinstance(t, str) and not t.strip()):
        t = _guess_building_type_from_name(name)
    t_str = str(t).strip().upper()
    if t_str in ("ASIAN", "MODERN", "MEDIEVAL", "FUTURISTIC", "RUSTIC", "DEFAULT"):
        t_str = "HOUSE"
    # Composite 子结构基本都应该落在已实现的类型；CUSTOM 会在服务端回退导致“塔楼”
    if t_str == "CUSTOM":
        t_str = "HOUSE"

    spec = item.get("spec")
    if isinstance(spec, dict):
        spec = _normalize_building_spec_dict(spec)
        spec["style"] = _normalize_building_style_value(spec.get("style")) or "ASIAN"
        spec["features"] = _normalize_features_value(spec.get("features"))
        if "type" not in spec or not spec.get("type"):
            spec["type"] = t_str
        else:
            st = str(spec.get("type")).strip().upper()
            if st in ("ASIAN", "MODERN", "MEDIEVAL", "FUTURISTIC", "RUSTIC", "DEFAULT"):
                spec["type"] = t_str
            if st == "CUSTOM":
                spec["type"] = t_str
        return {"type": t_str, "spec": spec, "offset": offset}

    # plan-like fallback -> minimal BuildingSpec
    dims = item.get("dimensions") or item.get("dimension") or {}
    if not isinstance(dims, dict):
        dims = {}
    w = int(dims.get("width", 8) or 8)
    d = int(dims.get("depth", 6) or 6)
    h = int(dims.get("height", 6) or 6)
    if w <= 0: w = 8
    if d <= 0: d = 6
    if h <= 0: h = 6

    style = _normalize_building_style_value(item.get("style")) or "ASIAN"
    features = _normalize_features_value(item.get("features"))

    spec_dict: Dict[str, Any] = {
        "type": t_str,
        "style": style,
        "footprint": {"shape": "rectangle", "width": w, "depth": d, "radius": None},
        "height": h,
        "floors": 1,
        "materials": {},
        "features": features,
        "styleOptions": {"roofType": "hipped", "windowRatio": 0.18, "windowStyle": "fence", "wallPattern": "uniform"},
        "notes": "normalized_from_plan",
    }
    spec_dict = _normalize_building_spec_dict(spec_dict)
    spec_dict["style"] = _normalize_building_style_value(spec_dict.get("style")) or "ASIAN"
    spec_dict["features"] = _normalize_features_value(spec_dict.get("features"))

    return {"type": t_str, "spec": spec_dict, "offset": offset}


def _normalize_composite_spec_dict(data: Any) -> Any:
    if not isinstance(data, dict):
        return data

    # tolerate alternative top-level keys
    if "structures" not in data and "buildings" in data:
        data["structures"] = data.get("buildings")
    if "structures" not in data and "components" in data:
        data["structures"] = data.get("components")

    structures = data.get("structures")
    if not isinstance(structures, list):
        structures = []
    data["structures"] = [_normalize_substructure_item(it, i) for i, it in enumerate(structures)]

    # paths is optional
    if "paths" not in data and "roads" in data:
        data["paths"] = data.get("roads")
    if "paths" not in data or data.get("paths") is None:
        data["paths"] = []
    if not isinstance(data["paths"], list):
        data["paths"] = []

    return data


def _generate_fallback_spec(req: BuildRequest) -> BuildingSpec:
    """规则基础的回退方案（当 LLM 不可用时）"""
    request_lower = req.requestText.lower()
    
    # 判断建筑类型
    if "塔" in req.requestText or "tower" in request_lower:
        building_type = BuildingType.TOWER
        height = 20
        footprint = Footprint(shape="circle", radius=6)
    elif "桥" in req.requestText or "bridge" in request_lower:
        building_type = BuildingType.BRIDGE
        height = 1
        footprint = Footprint(shape="rectangle", width=10, depth=3)
    elif "房子" in req.requestText or "house" in request_lower or "房屋" in req.requestText:
        building_type = BuildingType.HOUSE
        height = 4
        footprint = Footprint(shape="rectangle", width=8, depth=6)
    else:
        building_type = BuildingType.HOUSE
        height = 4
        footprint = Footprint(shape="rectangle", width=8, depth=6)
    
    # 判断风格
    style = BuildingStyle.MEDIEVAL
    if "现代" in req.requestText or "modern" in request_lower:
        style = BuildingStyle.MODERN
    elif "乡村" in req.requestText or "rustic" in request_lower:
        style = BuildingStyle.RUSTIC
    
    # 判断材料
    wall_material = "minecraft:stone_bricks"
    if "木头" in req.requestText or "wood" in request_lower or "木" in req.requestText:
        wall_material = "minecraft:oak_planks"
    elif "砖" in req.requestText or "brick" in request_lower:
        wall_material = "minecraft:bricks"
    
    materials = Materials(
        wall=wall_material,
        roof="minecraft:dark_oak_planks",
        floor="minecraft:oak_planks",
        window="minecraft:glass_pane"
    )
    
    features = Features(
        hasWindows=True,
        hasStairs=True,
        hasDoor=True,
        hasRoof=True,
        windowCount=2,
        floorCount=1
    )
    
    # 根据建筑类型设置 styleOptions
    if building_type == BuildingType.BRIDGE:
        style_options = StyleOptions(
            bridgeType="arched" if "拱" in req.requestText or "arch" in request_lower else "flat"
        )
    elif building_type == BuildingType.TOWER:
        style_options = StyleOptions(
            doorStyle="none" if "瞭望" in req.requestText else "single",
            roofType="cone" if "圆锥" in req.requestText or "cone" in request_lower else "flat",
            windowRatio=0.3
        )
    else:  # HOUSE
        style_options = StyleOptions(
            doorStyle="double" if "双" in req.requestText or "double" in request_lower else "single",
            roofType="gable" if "双坡" in req.requestText or "gable" in request_lower else "flat",
            windowRatio=0.35
        )
    
    spec = BuildingSpec(
        type=building_type,
        style=style,
        footprint=footprint,
        height=height,
        floors=1,
        materials=materials,
        features=features,
        styleOptions=style_options,
        notes=f"根据规则生成的{building_type.value}建筑"
    )
    return _ensure_genome_for_spec(spec, req)


def _should_generate_city(req: BuildRequest) -> bool:
    """检测是否应该生成城市级结构"""
    # 如果 promptMode 是 BUILD，且 requestText 包含 LlmPlan 格式的 prompt，不应该生成城市
    # LlmPlan 格式的 prompt 通常包含 "mode", "style_profile", "anchor", "components" 等字段说明
    if req.promptMode == "BUILD":
        request_text = req.requestText or ""
        # 检查是否包含 LlmPlan 格式的特征（Java 端发送的 prompt 包含这些关键词）
        llm_plan_indicators = [
            "LlmPlan", "component_type", "semantic components", 
            "ComponentObject", "SlotObject", "STRUCTURED JSON TEMPLATE"
        ]
        if any(indicator in request_text for indicator in llm_plan_indicators):
            return False
    
    request_lower = req.requestText.lower()
    city_keywords = [
        "城市", "城镇", "city", "town", "settlement", "urban", 
        "城区", "市中心", "广场", "集市", "plaza", "market"
    ]
    return any(keyword in request_lower for keyword in city_keywords)

def _should_generate_composite(req: BuildRequest) -> bool:
    """判断是否应该生成复合结构（排除城市关键词）"""
    if _should_generate_city(req):
        return False  # 城市优先于复合结构
    request_lower = req.requestText.lower()
    composite_keywords = [
        "城墙", "要塞", "复合", "组合", "village", "fort", "compound",
        "城堡", "村庄", "multiple", "several", "many", "多个", "几座", "几栋",
        "围起来", "surround", "enclose",
        # 组团/群落（用户经常会这样描述“建筑群”，如果不识别会退化成单体建筑）
        "群落", "建筑群", "建筑群落", "组团", "组群", "聚落", "多栋", "多座", "院落群"
    ]
    return any(keyword in request_lower for keyword in composite_keywords)


def generate_city_spec(req: BuildRequest) -> CitySpec:
    """
    调用大模型，生成 CitySpec（城市级结构）
    """
    client = get_client(req)

    if not client:
        return _ensure_genome_for_city_spec(_generate_fallback_city_spec(req), req)

    # I-layer: best-effort semantic spatial planning before full city generation.
    # This is used both as prompt context and as a source of spec.extra knobs (terrain/semantic scoring).
    semantic_plan = generate_semantic_spatial_plan(req)
    
    system_prompt = (
        "You are FormaCraft AI City Planner.\n\n"
        "Your job is to generate a complete CitySpec JSON that describes an entire city layout.\n\n"
        "Requirements:\n"
        "- Always respond with pure JSON (no comments, no extra text).\n"
        "- Generate a CitySpec with: cityName, style, size, biome, zones, structures, roads, bridges.\n"
        "- cityName: A creative name for the city.\n"
        "- style: MEDIEVAL / MODERN / ASIAN / FUTURISTIC\n"
        "- size: SMALL / MEDIUM / LARGE\n"
        "- biome: plains / forest / desert / etc.\n"
        "- zones: List of zones (PLAZA, RESIDENTIAL, MARKET, WALL, GATE, INDUSTRIAL, COMMERCIAL)\n"
        "  Each zone has: name, type, radius, center (Point with x, y, z)\n"
        "- structures: List of buildings with type, spec (BuildingSpec), offset (Point), zone (optional)\n"
        "- roads: List of PathSpec connecting zones and structures\n"
        "- bridges: List of BridgePlan with from_pos, to_pos, bridgeType\n"
        "- All coordinates are relative to city center (0, 0, 0).\n"
        "- Avoid overlapping zones.\n"
        "- Only use block IDs that exist in vanilla Minecraft.\n"
        "- Each structure spec must be a complete BuildingSpec with all required fields.\n"
    )
    
    user_prompt = _build_user_prompt(req)
    if semantic_plan is not None:
        try:
            sp_json = json.dumps(semantic_plan.model_dump(by_alias=True), ensure_ascii=False)
            user_prompt = user_prompt + "\n\nSemanticSpatialPlan(JSON):\n" + sp_json + "\n"
        except Exception:
            pass
    
    try:
        model = _resolve_model(req, "gpt-4o-mini")
        timeout_sec = _resolve_timeout_sec_for_task("city", req, model)
        response = _call_with_timeout(
            lambda: client.chat.completions.create(
                model=model,
                messages=[
                    {"role": "system", "content": system_prompt},
                    {"role": "user", "content": user_prompt},
                ],
                response_format={"type": "json_object"},
                temperature=_clamp_temperature(getattr(req, "temperature", None), 0.4),  # city 默认更发散
            ),
            timeout_sec,
        )
        
        raw_output = response.choices[0].message.content
        if not raw_output:
            raise ValueError("Empty response from OpenAI for city spec")
        
        data = json.loads(raw_output)
        spec = CitySpec.model_validate(data)

        # Fill per-structure semanticRole hints (do not override explicit user/LLM overrides).
        # This makes Java-side semantic placement constraints deterministic even when zone inference is weak.
        try:
            zone_type_by_name: Dict[str, str] = {}
            if spec.zones:
                for z in spec.zones:
                    if z and z.name:
                        zone_type_by_name[z.name.strip()] = (z.type or "").strip().upper()

            def role_from_zone_type(t: str) -> str:
                tt = (t or "").strip().upper()
                if tt in ("PLAZA", "MARKET", "COMMERCIAL", "COMMERCIAL_AREA"):
                    return "PUBLIC"
                if tt in ("RESIDENTIAL",):
                    return "PRIVATE"
                if tt in ("INDUSTRIAL",):
                    return "SERVICE"
                if tt in ("WALL", "GATE"):
                    return "TRANSITION"
                return "SEMI_PUBLIC"

            for sp in (spec.structures or []):
                bs = getattr(sp, "spec", None)
                if bs is None:
                    continue
                if bs.extra is None:
                    bs.extra = {}
                if "semanticRole" in bs.extra and bs.extra.get("semanticRole") not in (None, "", "null"):
                    continue
                zn = (sp.zone or "").strip()
                if zn and zn in zone_type_by_name:
                    bs.extra["semanticRole"] = role_from_zone_type(zone_type_by_name.get(zn, ""))
        except Exception:
            pass

        # Compile I-layer plan into spec.extra so Java CityBuilder can consume it immediately.
        if semantic_plan is not None and spec.structures:
            try:
                extra_knobs = _compile_semantic_plan_extra(req, semantic_plan)
                # Inject into the first structure's spec.extra (CityBuilder uses extra0 from first available).
                sp0 = spec.structures[0]
                if sp0.spec.extra is None:
                    sp0.spec.extra = {}
                # Do not clobber explicit user/LLM-provided overrides.
                for k, v in extra_knobs.items():
                    if k not in sp0.spec.extra:
                        sp0.spec.extra[k] = v

                # Also attach an explicit J-layer skeleton layout for debugging and future routing.
                if "skeletonLayout" not in sp0.spec.extra:
                    layout = _semantic_to_skeleton_layout(req, semantic_plan)
                    sp0.spec.extra["skeletonLayout"] = layout.model_dump()
            except Exception:
                pass

        return spec
        
    except Exception as e:
        # 有 client 说明用户配置了 LLM；失败应直接报错，让上游给用户明确提示
        raise RuntimeError(f"LLM call failed for city spec: {e}") from e


def _generate_fallback_city_spec(req: BuildRequest) -> CitySpec:
    """规则基础的回退方案（生成简单的城市结构）"""
    base_spec = _generate_fallback_spec(req)
    
    # 创建简单的城市布局
    zones = [
        Zone(
            name="central_plaza",
            type="PLAZA",
            radius=15,
            center=Point(x=0, y=0, z=0)
        ),
        Zone(
            name="residential_quarter",
            type="RESIDENTIAL",
            radius=25,
            center=Point(x=40, y=0, z=-20)
        )
    ]
    
    # 创建一些建筑
    house_spec = BuildingSpec(
        type=BuildingType.HOUSE,
        style=base_spec.style,
        footprint=Footprint(shape="rectangle", width=8, depth=6),
        height=4,
        floors=1,
        materials=base_spec.materials,
        features=base_spec.features,
        styleOptions=base_spec.styleOptions
    )
    
    tower_spec = BuildingSpec(
        type=BuildingType.TOWER,
        style=base_spec.style,
        footprint=Footprint(shape="circle", radius=5),
        height=12,
        floors=2,
        materials=base_spec.materials,
        features=base_spec.features,
        styleOptions=base_spec.styleOptions
    )
    
    structures = [
        StructurePlan(
            type="HOUSE",
            spec=house_spec,
            offset=Point(x=12, y=0, z=-5),
            zone="residential_quarter"
        ),
        StructurePlan(
            type="HOUSE",
            spec=house_spec,
            offset=Point(x=15, y=0, z=-8),
            zone="residential_quarter"
        ),
        StructurePlan(
            type="TOWER",
            spec=tower_spec,
            offset=Point(x=-10, y=0, z=20),
            zone="central_plaza"
        )
    ]
    
    # 创建道路
    roads = [
        PathSpec(
            from_pos=Point(x=0, y=0, z=0),
            to_pos=Point(x=40, y=0, z=-20),
            width=5,
            material="minecraft:gravel",
            style="default"
        )
    ]
    
    # 创建桥梁（可选）
    bridges = []
    
    spec = CitySpec(
        cityName="Fallback City",
        style=base_spec.style.value if hasattr(base_spec.style, 'value') else str(base_spec.style),
        size="MEDIUM",
        biome="plains",
        zones=zones,
        structures=structures,
        roads=roads,
        bridges=bridges
    )
    return _ensure_genome_for_city_spec(spec, req)


def generate_composite_spec(req: BuildRequest) -> CompositeSpec:
    """
    调用大模型，生成 CompositeSpec（复合结构）
    """
    client = get_client(req)

    # 如果 OpenAI 客户端不可用，使用回退方案
    if not client:
        return _ensure_genome_for_composite_spec(_generate_fallback_composite_spec(req), req)

    # I-layer: best-effort semantic plan as context for composite generation (clusters/courtyards/compounds).
    semantic_plan = generate_semantic_spatial_plan(req)
    
    system_prompt = (
        "You are FormaCraft city planner. Your job is to generate a CompositeSpec JSON "
        "containing multiple structures (towers, houses, bridges, walls) with their relative positions "
        "and paths connecting them.\n\n"
        "Requirements:\n"
        "- Always respond with pure JSON (no comments, no extra text).\n"
        "- Return a CompositeSpec with a 'structures' array and a 'paths' array.\n"
        "- Each structure must have: type (TOWER/HOUSE/BRIDGE/WALL/CASTLE/CUSTOM), spec (BuildingSpec), offset (Vec3i with x, y, z).\n"
        "- Each path must have: from_pos (Vec3i), to_pos (Vec3i), width (int, default 3), "
        "material (string, e.g. 'minecraft:gravel'), style (string, default 'default').\n"
        "- Generate paths to connect structures logically (house to house, house to tower, bridge to gate, etc.).\n"
        "- Use reasonable offsets to position structures relative to the origin.\n"
        "- Only use block IDs that exist in vanilla Minecraft.\n"
        "- Each spec in structures must be a complete BuildingSpec with EXACT field names.\n"
        "- BuildingSpec.type MUST be one of: HOUSE/TOWER/BRIDGE/CASTLE/WALL/CUSTOM (NOT 'ASIAN').\n"
        "- BuildingSpec.style MUST be one of: MEDIEVAL/MODERN/ASIAN/FUTURISTIC/RUSTIC/DEFAULT (NOT 'chinese_palace').\n"
        "- BuildingSpec.features MUST be an object (NOT a list).\n"
    )
    
    user_prompt = _build_user_prompt(req)
    if semantic_plan is not None:
        try:
            sp_json = json.dumps(semantic_plan.model_dump(by_alias=True), ensure_ascii=False)
            user_prompt = user_prompt + "\n\nSemanticSpatialPlan(JSON):\n" + sp_json + "\n"
        except Exception:
            pass
    
    try:
        model = _resolve_model(req, "gpt-4o-mini")
        timeout_sec = _resolve_timeout_sec_for_task("composite", req, model)
        response = _call_with_timeout(
            lambda: client.chat.completions.create(
                model=model,
                messages=[
                    {"role": "system", "content": system_prompt},
                    {"role": "user", "content": user_prompt},
                ],
                response_format={"type": "json_object"},
                temperature=_clamp_temperature(getattr(req, "temperature", None), 0.3),
            ),
            timeout_sec,
        )
        
        raw_output = response.choices[0].message.content
        if not raw_output:
            raise ValueError("Empty response from OpenAI")
        
        data = json.loads(raw_output)
        data = _normalize_composite_spec_dict(data)
        spec = CompositeSpec.model_validate(data)

        # Compile I-layer plan into the first sub-structure's spec.extra (best-effort).
        # Not all composite generators consume these knobs today, but it provides a forward-compatible hook.
        if semantic_plan is not None and spec.structures:
            try:
                extra_knobs = _compile_semantic_plan_extra(req, semantic_plan)
                s0 = spec.structures[0]
                if s0.spec.extra is None:
                    s0.spec.extra = {}
                for k, v in extra_knobs.items():
                    if k not in s0.spec.extra:
                        s0.spec.extra[k] = v

                if "skeletonLayout" not in s0.spec.extra:
                    layout = _semantic_to_skeleton_layout(req, semantic_plan)
                    s0.spec.extra["skeletonLayout"] = layout.model_dump()
            except Exception:
                pass

        spec = _ensure_genome_for_composite_spec(spec, req)
        return spec
        
    except Exception as e:
        raise RuntimeError(f"LLM call failed for composite spec: {e}") from e


def _generate_fallback_composite_spec(req: BuildRequest) -> CompositeSpec:
    """规则基础的回退方案（生成简单的复合结构）"""
    request_lower = req.requestText.lower()
    
    structures = []
    paths = []
    
    # 根据关键词生成不同的复合结构
    if "要塞" in req.requestText or "fort" in request_lower:
        # 要塞：中心大厅 + 4 个角塔
        base_spec = _generate_fallback_spec(req)
        house_spec = BuildingSpec(
            type=BuildingType.HOUSE,
            style=base_spec.style,
            footprint=Footprint(shape="rectangle", width=12, depth=10),
            height=6,
            floors=2,
            materials=base_spec.materials,
            features=base_spec.features,
            styleOptions=base_spec.styleOptions
        )
        tower_spec = BuildingSpec(
            type=BuildingType.TOWER,
            style=base_spec.style,
            footprint=Footprint(shape="circle", radius=5),
            height=15,
            floors=3,
            materials=base_spec.materials,
            features=base_spec.features,
            styleOptions=base_spec.styleOptions
        )
        
        structures.extend([
            SubStructure(
                type="HOUSE",
                spec=house_spec,
                offset=Vec3i(x=0, y=0, z=0)
            ),
            SubStructure(
                type="TOWER",
                spec=tower_spec,
                offset=Vec3i(x=-20, y=0, z=-20)
            ),
            SubStructure(
                type="TOWER",
                spec=tower_spec,
                offset=Vec3i(x=20, y=0, z=-20)
            ),
            SubStructure(
                type="TOWER",
                spec=tower_spec,
                offset=Vec3i(x=-20, y=0, z=20)
            ),
            SubStructure(
                type="TOWER",
                spec=tower_spec,
                offset=Vec3i(x=20, y=0, z=20)
            ),
        ])
        
        # 生成连接路径（连接中心大厅到各个角塔）
        paths = [
            PathSpec(
                from_pos=Vec3i(x=0, y=0, z=0),
                to_pos=Vec3i(x=-20, y=0, z=-20),
                width=3,
                material="minecraft:gravel",
                style="default"
            ),
            PathSpec(
                from_pos=Vec3i(x=0, y=0, z=0),
                to_pos=Vec3i(x=20, y=0, z=-20),
                width=3,
                material="minecraft:gravel",
                style="default"
            ),
            PathSpec(
                from_pos=Vec3i(x=0, y=0, z=0),
                to_pos=Vec3i(x=-20, y=0, z=20),
                width=3,
                material="minecraft:gravel",
                style="default"
            ),
            PathSpec(
                from_pos=Vec3i(x=0, y=0, z=0),
                to_pos=Vec3i(x=20, y=0, z=20),
                width=3,
                material="minecraft:gravel",
                style="default"
            ),
        ]
    else:
        # 默认：简单的房屋 + 塔楼组合
        base_spec = _generate_fallback_spec(req)
        house_spec = BuildingSpec(
            type=BuildingType.HOUSE,
            style=base_spec.style,
            footprint=Footprint(shape="rectangle", width=8, depth=6),
            height=4,
            floors=1,
            materials=base_spec.materials,
            features=base_spec.features,
            styleOptions=base_spec.styleOptions
        )
        tower_spec = BuildingSpec(
            type=BuildingType.TOWER,
            style=base_spec.style,
            footprint=Footprint(shape="circle", radius=5),
            height=12,
            floors=2,
            materials=base_spec.materials,
            features=base_spec.features,
            styleOptions=base_spec.styleOptions
        )
        
        structures.extend([
            SubStructure(
                type="HOUSE",
                spec=house_spec,
                offset=Vec3i(x=0, y=0, z=0)
            ),
            SubStructure(
                type="TOWER",
                spec=tower_spec,
                offset=Vec3i(x=20, y=0, z=0)
            ),
        ])
        
        # 生成连接路径
        paths = [
            PathSpec(
                from_pos=Vec3i(x=0, y=0, z=0),
                to_pos=Vec3i(x=20, y=0, z=0),
                width=3,
                material="minecraft:gravel",
                style="default"
            )
        ]
    
    spec = CompositeSpec(structures=structures, paths=paths)
    return _ensure_genome_for_composite_spec(spec, req)


def generate_llm_plan(req: BuildRequest) -> dict:
    """
    处理 LlmPlan 格式的请求（Java 端的新格式）
    直接返回 LLM 的原始 JSON 输出，不做格式转换
    
    对于强类型建筑（如埃菲尔铁塔、中国古建筑等），会自动搜索参考资料
    """
    client = get_client(req)
    if not client:
        raise ValueError("LLM client not available for LlmPlan generation")
    
    # 使用 requestText 作为完整的 prompt（Java 端已经组装好了）
    # requestText 包含了完整的 system prompt 和 user prompt
    request_text = req.requestText or ""
    
    # 尝试分离 system prompt 和 user prompt
    if "USER REQUEST:" in request_text:
        parts = request_text.split("USER REQUEST:")
        system_prompt = parts[0].strip()
        user_prompt = parts[1].strip() if len(parts) > 1 else req.userMessage or ""
    else:
        # 如果没有明确的分隔符，使用 requestText 作为 user prompt
        system_prompt = ""
        user_prompt = request_text
    
    # 如果没有 user prompt，使用 userMessage
    if not user_prompt:
        user_prompt = req.userMessage or request_text
    
    # ========== 网络搜索增强：为强类型建筑获取参考资料 ==========
    try:
        from .architecture_researcher import get_architecture_reference_context
        
        # 检查是否需要搜索参考资料
        search_query = (req.userMessage or "").strip()
        if not search_query:
            # 从 requestText 中提取用户请求
            if "USER REQUEST:" in request_text:
                search_query = user_prompt
            else:
                search_query = user_prompt[:200]  # 使用前200个字符作为搜索关键词
        
        if search_query:
            reference_context = get_architecture_reference_context(search_query)
            if reference_context:
                # 将参考资料添加到 user prompt 前面
                user_prompt = reference_context + "\n\n" + user_prompt
                logger.info("Added architecture reference context to LlmPlan prompt")
    except ImportError:
        logger.warning("architecture_researcher module not available, skipping reference search")
    except Exception as e:
        # 搜索失败不应该阻塞生成，只记录警告
        logger.warning(f"Failed to get architecture reference: {e}")
    
    try:
        model = _resolve_model(req, "gpt-4o-mini")
        timeout_sec = _resolve_timeout_sec_for_task("llmplan", req, model)
        
        messages = []
        if system_prompt:
            messages.append({"role": "system", "content": system_prompt})
        messages.append({"role": "user", "content": user_prompt})
        
        response = _call_with_timeout(
            lambda: client.chat.completions.create(
                model=model,
                messages=messages,
                response_format={"type": "json_object"},
                temperature=_clamp_temperature(getattr(req, "temperature", None), 0.7),
            ),
            timeout_sec,
        )
        
        raw_output = response.choices[0].message.content
        if not raw_output:
            raise ValueError("Empty response from LLM for LlmPlan")
        
        try:
            plan = json.loads(raw_output)
        except json.JSONDecodeError as e:
            repaired = _repair_component_request_strings(raw_output)
            if repaired != raw_output:
                logger.warning("LlmPlan JSON repair applied after parse error: %s", e)
                plan = json.loads(repaired)
            else:
                raise
        return _normalize_llm_plan_output(plan, req)
    except Exception as e:
        raise RuntimeError(f"LLM call failed for LlmPlan: {e}") from e


def generate_building_spec(req: BuildRequest) -> BuildingSpec:
    """
    调用大模型，把 BuildRequest -> BuildingSpec
    """
    # -----------------------------
    # Archetype / Landmark v1
    # -----------------------------
    # 两段式识别（v1 先启用 Stage-1 本地规则）：快且稳定，避免 AI 胡猜新 archetype
    text_for_archetype = (getattr(req, "requestText", None) or "") + "\n" + (getattr(req, "userMessage", None) or "")
    arche = detect_archetype_local(text_for_archetype)
    force_strong = should_force_strong_mode(text_for_archetype)

    # Strong/Soft 模式判定
    # - 强还原：用户显式要求 或 本地命中强原型且置信度足够
    # - 软灵感：只继承原型关键语义，但不强制专用 generator
    strong = False
    if arche is not None:
        strong = force_strong or (arche.confidence >= 0.85)

    # 已实现的强原型：土楼、埃菲尔铁塔（其它 archetype 已进入 Registry/候选集，后续补专用生成器）
    if arche is not None and arche.id == "tulou" and strong:
        spec = _generate_tulou_building_spec(req)
        return _attach_archetype_genome(spec, req, arche, "LANDMARK_STRONG", True)

    if arche is not None and arche.id == "eiffel_tower" and strong:
        spec = _generate_eiffel_tower_building_spec(req)
        return _attach_archetype_genome(spec, req, arche, "LANDMARK_STRONG", True)

    if arche is not None and arche.id == "temple_of_heaven" and strong:
        spec = _generate_temple_of_heaven_building_spec(req)
        return _attach_archetype_genome(spec, req, arche, "LANDMARK_STRONG", True)

    if arche is not None and arche.id == "great_wall" and strong:
        spec = _generate_great_wall_building_spec(req)
        return _attach_archetype_genome(spec, req, arche, "LANDMARK_STRONG", True)

    if arche is not None and arche.id == "golden_gate_bridge" and strong:
        spec = _generate_golden_gate_bridge_building_spec(req)
        return _attach_archetype_genome(spec, req, arche, "LANDMARK_STRONG", True)

    if arche is not None and arche.id == "giant_wild_goose_pagoda" and strong:
        spec = _generate_giant_wild_goose_pagoda_building_spec(req)
        return _attach_archetype_genome(spec, req, arche, "LANDMARK_STRONG", True)

    if arche is not None and arche.id == "castle_compound" and strong:
        spec = _generate_castle_compound_building_spec(req)
        # Best-effort blueprint augmentation: lets LLM describe semantic components without direct blocks.
        _try_generate_castle_blueprint(req, spec)
        return _attach_archetype_genome(spec, req, arche, "LANDMARK_STRONG", True)

    if arche is not None and arche.id == "office_district" and strong:
        spec = _generate_office_district_building_spec(req)
        return _attach_archetype_genome(spec, req, arche, "LANDMARK_STRONG", True)

    # 兼容旧逻辑：土楼（强形象地标）仍优先使用确定性模板（除非用户明确要求“随意/自由发挥”）
    if _should_use_tulou_template(req):
        spec = _generate_tulou_building_spec(req)
        return _attach_archetype_genome(
            spec,
            req,
            arche if arche is not None and arche.id == "tulou" else None,
            "INSPIRED",
            False,
        )

    # 兼容旧逻辑：埃菲尔铁塔（强形象地标）
    if _should_use_eiffel_tower_template(req):
        spec = _generate_eiffel_tower_building_spec(req)
        return _attach_archetype_genome(
            spec,
            req,
            arche if arche is not None and arche.id == "eiffel_tower" else None,
            "INSPIRED",
            False,
        )

    # 兼容旧逻辑：天坛（祈年殿）（强形象地标）
    if _should_use_temple_of_heaven_template(req):
        spec = _generate_temple_of_heaven_building_spec(req)
        return _attach_archetype_genome(
            spec,
            req,
            arche if arche is not None and arche.id == "temple_of_heaven" else None,
            "INSPIRED",
            False,
        )

    # 兼容旧逻辑：长城（强形象地标）
    if _should_use_great_wall_template(req):
        spec = _generate_great_wall_building_spec(req)
        return _attach_archetype_genome(
            spec,
            req,
            arche if arche is not None and arche.id == "great_wall" else None,
            "INSPIRED",
            False,
        )

    # 兼容旧逻辑：金门大桥（强形象地标）
    if _should_use_golden_gate_bridge_template(req):
        spec = _generate_golden_gate_bridge_building_spec(req)
        return _attach_archetype_genome(
            spec,
            req,
            arche if arche is not None and arche.id == "golden_gate_bridge" else None,
            "INSPIRED",
            False,
        )

    # 兼容旧逻辑：大慈恩寺（大雁塔）（强形象地标）
    if _should_use_giant_wild_goose_pagoda_template(req):
        spec = _generate_giant_wild_goose_pagoda_building_spec(req)
        return _attach_archetype_genome(
            spec,
            req,
            arche if arche is not None and arche.id == "giant_wild_goose_pagoda" else None,
            "INSPIRED",
            False,
        )

    # 兼容旧逻辑：城堡复合体（COMPOUND）
    if _should_use_castle_compound_template(req):
        spec = _generate_castle_compound_building_spec(req)
        return _attach_archetype_genome(
            spec,
            req,
            arche if arche is not None and arche.id == "castle_compound" else None,
            "INSPIRED",
            False,
        )

    # 兼容旧逻辑：办公楼群（GRID/CLUSTER）
    if _should_use_office_district_template(req):
        spec = _generate_office_district_building_spec(req)
        return _attach_archetype_genome(
            spec,
            req,
            arche if arche is not None and arche.id == "office_district" else None,
            "INSPIRED",
            False,
        )

    # 明清官式院落：优先使用确定性模板（除非用户明确要求“随意/自由发挥”）
    if _should_use_mingqing_courtyard_template(req):
        return _generate_mingqing_courtyard_building_spec(req)

    client = get_client(req)

    # 如果 OpenAI 客户端不可用，使用回退方案
    if not client:
        return _generate_fallback_spec(req)
    
    # I-layer: For complex buildings, generate SemanticSpatialPlan to improve spatial organization
    # Complex building indicators: multiple zones, courtyards, functional divisions, large scale
    request_text_lower = (req.requestText or "").lower()
    is_complex_building = any(keyword in request_text_lower for keyword in [
        "courtyard", "中庭", "庭院", "院落", "multiple rooms", "多个房间", "functional", "功能分区",
        "large", "大型", "complex", "复杂", "multilevel", "多层", "wing", "翼楼", "corridor", "走廊",
        "symmetric", "对称", "axis", "轴线", "layout", "布局", "plan", "平面"
    ])
    
    # Also check selection size - large selections may benefit from semantic planning
    if req.selection:
        dx = abs(req.selection.max.x - req.selection.min.x) + 1
        dz = abs(req.selection.max.z - req.selection.min.z) + 1
        if max(dx, dz) > 48:  # Large footprint
            is_complex_building = True
    
    semantic_plan = None
    if is_complex_building:
        # Generate semantic spatial plan for complex buildings
        semantic_plan = generate_semantic_spatial_plan(req)
    
    system_prompt = _build_system_prompt()
    user_prompt = _build_user_prompt(req)
    
    # If semantic plan was generated, add it to the prompt for better spatial organization
    if semantic_plan is not None:
        try:
            import json
            sp_json = json.dumps(semantic_plan.model_dump(by_alias=True), ensure_ascii=False)
            user_prompt = user_prompt + "\n\nSemanticSpatialPlan(JSON):\n" + sp_json + "\n"
            user_prompt = user_prompt + "\nNote: Use the SemanticSpatialPlan to inform zone layout, spatial relationships, and functional organization.\n"
        except Exception:
            pass
    
    try:
        # 使用 OpenAI Chat Completions API
        model = _resolve_model(req, "gpt-4o-mini")
        timeout_sec = _resolve_timeout_sec(req, model)
        response = _call_with_timeout(
            lambda: client.chat.completions.create(
                model=model,
                messages=[
                    {"role": "system", "content": system_prompt},
                    {"role": "user", "content": user_prompt},
                ],
                response_format={"type": "json_object"},
                temperature=_clamp_temperature(getattr(req, "temperature", None), 0.3),
            ),
            timeout_sec,
        )
        
        # 提取 JSON 响应
        raw_output = response.choices[0].message.content
        if not raw_output:
            raise ValueError("Empty response from OpenAI")
        
        data = json.loads(raw_output)
        data = _normalize_building_spec_dict(data)
        
        # 使用 Pydantic 校验 & 转换为 BuildingSpec
        spec = BuildingSpec(**data)
        
        # 后处理：确保合理的默认值
        if spec.height <= 0:
            spec.height = 10
        
        if spec.footprint.shape == "rectangle":
            if spec.footprint.width is None or spec.footprint.width <= 0:
                spec.footprint.width = 8
            if spec.footprint.depth is None or spec.footprint.depth <= 0:
                spec.footprint.depth = 6
        elif spec.footprint.shape == "circle":
            if spec.footprint.radius is None or spec.footprint.radius <= 0:
                spec.footprint.radius = 6
        
        return _ensure_genome_for_spec(spec, req)
        
    except Exception as e:
        raise RuntimeError(f"LLM call failed for building spec: {e}") from e
