"""
AI Planner Service
LlmPlan 主干规划；BuildingSpec / city / composite 仅保留兼容入口
"""
import os
import json
import re
import time
import logging
from typing import Any, Dict, List, Optional, Union

logger = logging.getLogger(__name__)


from ..models.request import BuildRequest
from .legacy_spec_fallbacks import (
    _generate_fallback_spec,
    _generate_fallback_city_spec,
    _generate_fallback_composite_spec,
)
from .legacy_spec_genome import (
    _deep_merge,
    _semantic_material_from_block,
    _build_genome_from_spec,
    _default_component_params_for_spec,
    _ensure_genome_for_spec,
    _ensure_genome_for_composite_spec,
    _ensure_genome_for_city_spec,
)
from .legacy_spec_normalization import (
    _normalize_building_spec_dict,
    _normalize_building_style_value,
    _normalize_features_value,
    _guess_building_type_from_name,
    _normalize_substructure_item,
    _normalize_composite_spec_dict,
)
from .semantic_plan_adapters import (
    _disturbance_to_policy_and_budget,
    _policy_upper_to_lower,
    _compile_semantic_plan_extra,
    _cardinal_facing_from_dxz,
    _default_size_for_zone,
    _default_radius_for_core,
    _semantic_to_skeleton_layout,
)
from .planning_request_hints import (
    _infer_scale, _infer_terrain, _parse_levels_from_text, _derive_structured_intent,
)
from .planning_route_policy import _should_generate_city, _should_generate_composite
from .llm_client import get_client, build_config, resolve_provider
# Compatibility alias: existing callers and test patches keep the same facade.
from .inference_call import call_with_timeout as _call_with_timeout
from ..models.building_spec import BuildingSpec
from ..models.building_genome import BuildingGenome
from ..models.llm_plan import LlmPlanValidationError, validate_llm_plan_dict
from ..models.llm_plan_json_schema import (
    call_chat_with_llm_plan_response_formats,
    resolve_llmplan_json_schema_mode,
)
from ..models.composite_spec import CompositeSpec
from ..models.city_spec import CitySpec
from ..models.semantic_spatial_plan import SemanticSpatialPlan
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

# ---- Phase 6 延迟治理开关 ----
# 研究开关与旧 ARCHITECTURE_SEARCH 兼容策略由 BuildingResearchAgent 统一解析。
# 可选：llmplan 步骤强制使用的快模型（覆盖客户端 model，用于速度/质量对比）。为空则不覆盖。
_LLMPLAN_MODEL_OVERRIDE = (os.getenv("LLMPLAN_MODEL") or "").strip()
# 已知不支持严格 json_schema 的 provider：auto 模式下直接走 json_object，避免必失的首次尝试
_NON_JSON_SCHEMA_PROVIDERS = {
    "deepseek", "ollama", "lmstudio", "vllm", "llamacpp",
    "gemini", "anthropic", "mistral", "xai", "groq",
    "together", "moonshot", "zhipu", "qwen", "siliconflow",
    "openai_compat",
}

# ---- Track B 输入理解与多步规划 开关 ----
# B1 意图解析/扩写（规则版，零额外网络延迟）：on（默认）| off
_LLMPLAN_INTENT_ENRICH = (os.getenv("LLMPLAN_INTENT_ENRICH") or "on").strip().lower() != "off"
# B3 在 LlmPlan 路径注入 chatHistory（当前主路径未注入）：on（默认）| off
_LLMPLAN_INJECT_CHAT_HISTORY = (os.getenv("LLMPLAN_CHAT_HISTORY") or "on").strip().lower() != "off"
# B3 在 LlmPlan 路径注入文化 RAG（本地检索，非网络）：on（默认）| off
_LLMPLAN_INJECT_CULTURE_RAG = (os.getenv("LLMPLAN_CULTURE_RAG") or "on").strip().lower() != "off"
# B2 两阶段生成（先体量后细节）：默认 off（会增加一次 LLM 调用/延迟；失败自动回退单阶段）
_LLMPLAN_TWO_STAGE = (os.getenv("LLMPLAN_TWO_STAGE") or "off").strip().lower() == "on"


from .rag_budget import int_env as _int_env, resolve_rag_budget as _resolve_rag_budget
from .building_spec_prompts import (
    build_system_prompt as _build_system_prompt,
    build_user_prompt as _build_user_prompt,
    )


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


def _fill_component_params(params: Dict[str, Any], comp: Dict[str, Any], genome: Dict[str, Any]) -> None:
    ctype = str(comp.get("component_type", "")).upper()
    from .style_feature_compiler import component_style_defaults
    for key, value in component_style_defaults(comp.get("_style_hint"), ctype).items():
        params.setdefault(key, value)
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
                if feat_contains("courtyard") or feat_contains("siheyuan"):
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
        if "wall_pattern" not in params and params.get("facade_profile") == "vertical_pilasters":
            params["wall_pattern"] = "gradient"

    if ctype == "ROOF":
        parent_h = dims.get("height")
        if "roof_height" not in params and isinstance(parent_h, int) and parent_h > 0:
            params["roof_height"] = max(2, min(12, parent_h))
        if "overhang" not in params:
            params["overhang"] = 2

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


_VALID_GLOBAL_SYMMETRY = frozenset({"NONE", "MIRROR_X", "MIRROR_Z", "RADIAL"})

_GLOBAL_SYMMETRY_ALIASES: Dict[str, str] = {
    "NONE": "NONE",
    "NO": "NONE",
    "OFF": "NONE",
    "FALSE": "NONE",
    "0": "NONE",
    "X": "MIRROR_X",
    "MIRROR_X": "MIRROR_X",
    "MIRRORX": "MIRROR_X",
    "LEFT_RIGHT": "MIRROR_X",
    "BILATERAL": "MIRROR_X",
    "AXIAL": "MIRROR_X",
    "Z": "MIRROR_Z",
    "MIRROR_Z": "MIRROR_Z",
    "MIRRORZ": "MIRROR_Z",
    "FRONT_BACK": "MIRROR_Z",
    "BOTH": "MIRROR_X",
    "MIRROR_BOTH": "MIRROR_X",
    "FULL": "MIRROR_X",
    "RADIAL": "RADIAL",
    "RADIAL_SYMMETRY": "RADIAL",
    "CIRCULAR": "RADIAL",
    "ROTATIONAL": "RADIAL",
    "GRID": "MIRROR_X",
}


def _symmetry_from_genome_type(genome_type: str) -> Optional[str]:
    t = genome_type.strip().lower()
    if t in ("none", "no", "off"):
        return "NONE"
    if t in ("bilateral", "mirror", "axial"):
        return "MIRROR_X"
    if t in ("radial", "circular", "rotational"):
        return "RADIAL"
    if t == "grid":
        return "MIRROR_X"
    return None


def _normalize_global_symmetry_value(
    value: Any,
    *,
    genome: Optional[Dict[str, Any]] = None,
) -> Optional[str]:
    """Map LLM/genome symmetry aliases to LlmPlan global_constraints.symmetry enum."""
    if value is not None:
        key = str(value).strip().upper().replace("-", "_").replace(" ", "_")
        mapped = _GLOBAL_SYMMETRY_ALIASES.get(key)
        if mapped:
            return mapped
        if key in _VALID_GLOBAL_SYMMETRY:
            return key

    if isinstance(genome, dict):
        sym = genome.get("symmetry")
        if isinstance(sym, dict):
            inferred = _symmetry_from_genome_type(str(sym.get("type") or ""))
            if inferred:
                return inferred
    return None


def _coerce_optional_bool(value: Any) -> Optional[bool]:
    if value is None:
        return None
    if isinstance(value, bool):
        return value
    if isinstance(value, (int, float)) and value in (0, 1):
        return bool(value)
    if isinstance(value, str):
        s = value.strip().lower()
        if s in ("true", "yes", "1", "on"):
            return True
        if s in ("false", "no", "0", "off"):
            return False
        if "|" in s:
            return None
    return None


def _sanitize_genome_dict(genome: Any) -> Dict[str, Any]:
    if not isinstance(genome, dict):
        return BuildingGenome().model_dump()
    g = dict(genome)
    sym = g.get("symmetry")
    if isinstance(sym, dict):
        sym = dict(sym)
        sym["mirror"] = _coerce_optional_bool(sym.get("mirror"))
        order = sym.get("order")
        if order is not None and not isinstance(order, int):
            sym["order"] = _coerce_int(order, None)
        g["symmetry"] = sym
    return g


# LLM sometimes invents skeleton labels outside the schema (e.g. TOWER_SHAFT).
_SKELETON_ALIASES = {
    "TOWER_SHAFT": "VERTICAL_STACK",
    "TOWER": "VERTICAL_STACK",
    "SHAFT": "VERTICAL_STACK",
    "VERTICAL": "VERTICAL_STACK",
    "STACK": "VERTICAL_STACK",
    "TAPER": "VERTICAL_TAPER",
    "LINEAR": "LINEAR_PATH",
    "PATH": "LINEAR_PATH",
    "RADIAL": "RADIAL_RING",
    "RING": "RADIAL_RING",
    "SPOKE": "RADIAL_SPOKE",
    "BRIDGE": "SPAN_SUSPENSION",
    "SUSPENSION": "SPAN_SUSPENSION",
    "COURT": "COURTYARD",
    "YARD": "COURTYARD",
    "LOOP": "PERIMETER_LOOP",
    "WALL": "ENCLOSURE",
    "BAY": "GRID_BAY",
}
_SKELETON_ALLOWED = {
    "LINEAR_PATH", "RADIAL_RING", "RADIAL_SPOKE", "GRID", "GRID_BAY", "COMPOUND",
    "COURTYARD", "PATH_POLYLINE", "SPAN_SUSPENSION", "VERTICAL_TAPER",
    "VERTICAL_STACK", "PERIMETER_LOOP", "ENCLOSURE",
}


def _coerce_skeleton_type(raw: Any) -> Optional[str]:
    if raw is None:
        return None
    text = str(raw).strip().upper().replace("-", "_").replace(" ", "_")
    if not text:
        return None
    if text in _SKELETON_ALLOWED:
        return text
    if text in _SKELETON_ALIASES:
        return _SKELETON_ALIASES[text]
    for alias, canonical in _SKELETON_ALIASES.items():
        if alias in text:
            return canonical
    return "COMPOUND"


def _sanitize_layout_slots(layout: Any) -> Any:
    if not isinstance(layout, dict):
        return layout
    layout = dict(layout)
    if "skeleton_type" in layout:
        coerced = _coerce_skeleton_type(layout.get("skeleton_type"))
        if coerced is None:
            layout.pop("skeleton_type", None)
        else:
            layout["skeleton_type"] = coerced
    slots = layout.get("slots")
    if not isinstance(slots, list):
        return layout
    cleaned: List[Dict[str, Any]] = []
    for i, slot in enumerate(slots):
        if not isinstance(slot, dict):
            continue
        s = dict(slot)
        if s.get("facing") is not None:
            facing = str(s["facing"]).strip().upper()
            facing = {"N": "NORTH", "S": "SOUTH", "E": "EAST", "W": "WEST",
                      "北": "NORTH", "南": "SOUTH", "东": "EAST", "西": "WEST"}.get(facing, facing)
            if facing in ("NORTH", "SOUTH", "EAST", "WEST"):
                s["facing"] = facing
            else:
                # Facing is optional. A courtyard's NONE/ANY must not discard
                # the entire compound in favor of a generic profile building.
                s.pop("facing", None)
        slot_id = str(s.get("slot_id") or "").strip()
        if not slot_id:
            s["slot_id"] = str(s.get("id") or f"slot_{i}").strip()
        anchor = s.get("anchor")
        if not isinstance(anchor, dict):
            s["anchor"] = {"x": 0, "y": 0, "z": 0}
        else:
            s["anchor"] = _normalize_vec3(anchor)
        cp = s.get("component_preset")
        if cp is not None and (not isinstance(cp, str) or not cp.strip()):
            s.pop("component_preset", None)
        cleaned.append(s)
    layout["slots"] = cleaned
    return layout


def _extract_landmark_module_id(comp: Dict[str, Any]) -> Optional[str]:
    for feature in comp.get("features") or []:
        if not isinstance(feature, str):
            continue
        f = feature.strip().lower()
        if f.startswith("landmark:"):
            return f.split(":", 1)[1].strip()
        if f.startswith("module:"):
            return f.split(":", 1)[1].strip()
    params = comp.get("params")
    if isinstance(params, dict):
        module_id = params.get("module_id")
        if isinstance(module_id, str) and module_id.strip():
            return module_id.strip().lower()
    return None


def _sanitize_landmark_modules(
    plan: Dict[str, Any],
    req: Optional[BuildRequest],
    building_profile: Optional[Any] = None,
) -> None:
    from .typology_plan_repair import repair_migrated_landmark_components

    stid: Optional[str] = None
    ref_lm: Optional[str] = None
    if building_profile is not None:
        mc = getattr(building_profile, "minecraft_strategy", None)
        if mc is not None:
            raw_st = getattr(mc, "structural_typology", None)
            raw_ref = getattr(mc, "reference_landmark", None)
            stid = str(raw_st).strip() if raw_st else None
            ref_lm = str(raw_ref).strip() if raw_ref else None

    plan, _ = repair_migrated_landmark_components(
        plan,
        structural_typology=stid,
        reference_landmark=ref_lm,
    )

    components = plan.get("components")
    if not isinstance(components, list):
        return

    from .typology_registry import is_migrated_landmark

    def _strip_remaining_migrated_modules(comps: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
        kept_local: List[Dict[str, Any]] = []
        for comp in comps:
            if not isinstance(comp, dict):
                continue
            actual = _extract_landmark_module_id(comp)
            ctype = str(comp.get("component_type") or "").upper()
            if (ctype == "MODULE" or actual) and actual and is_migrated_landmark(actual):
                continue
            kept_local.append(comp)
        return kept_local

    components = _strip_remaining_migrated_modules(components)
    plan["components"] = components

    expected_id: Optional[str] = None
    profile_authoritative = building_profile is not None

    if building_profile is not None:
        mc = getattr(building_profile, "minecraft_strategy", None)
        lm = getattr(mc, "landmark_module", None) if mc is not None else None
        expected_id = str(lm).strip().lower() if lm else None
    elif req is not None:
        user_text = (req.userMessage or "").strip()
        if user_text:
            from .building_research_agent import resolve_landmark_module_for_intent

            expected_id = resolve_landmark_module_for_intent(user_text)

    if expected_id and is_migrated_landmark(expected_id):
        expected_id = None

    if profile_authoritative and not expected_id:
        filtered = []
        for comp in components:
            if not isinstance(comp, dict):
                continue
            ctype = str(comp.get("component_type") or "").upper()
            if ctype == "MODULE" or _extract_landmark_module_id(comp):
                continue
            filtered.append(comp)
        plan["components"] = filtered
        return

    if not expected_id:
        return

    kept: List[Dict[str, Any]] = []
    module_seen = False
    for comp in components:
        if not isinstance(comp, dict):
            continue
        actual = _extract_landmark_module_id(comp)
        ctype = str(comp.get("component_type") or "").upper()
        if ctype == "MODULE" or actual:
            if actual and actual != expected_id:
                continue
            if module_seen:
                continue
            features = [
                f for f in (comp.get("features") or [])
                if isinstance(f, str)
                and not f.strip().lower().startswith(("landmark:", "module:"))
            ]
            params = dict(comp.get("params") or {})
            params["module_id"] = expected_id
            comp = dict(comp)
            comp["component_type"] = "MODULE"
            comp["features"] = list(features) + [f"landmark:{expected_id}"]
            comp["params"] = params
            module_seen = True
        kept.append(comp)
    if not module_seen:
        kept.insert(
            0,
            {
                "component_type": "MODULE",
                "relative_position": {"x": 0, "y": 0, "z": 0},
                "dimensions": {"width": 32, "depth": 24, "height": 16},
                "features": [f"landmark:{expected_id}"],
                "params": {"module_id": expected_id},
            },
        )
    plan["components"] = kept


def _normalize_llm_plan_output(
    raw: Dict[str, Any],
    req: BuildRequest,
    *,
    building_profile: Optional[Any] = None,
) -> Dict[str, Any]:
    if not isinstance(raw, dict):
        return raw
    from .fbd_capabilities import is_fbd_only_plan
    if is_fbd_only_plan(raw):
        # Legacy enrichment changes geometry/authority and cannot inspect program bodies.
        from copy import deepcopy
        return deepcopy(raw)
    from .plan_semantic_gate import coerce_style_attribute_maps
    plan = coerce_style_attribute_maps(dict(raw))
    # Force player-relative world anchor. Scaffold/fallback used to emit (0,64,0),
    # which places buildings at world spawn (unloaded / invisible to the player).
    if req is not None and getattr(req, "player", None) is not None and getattr(req.player, "pos", None) is not None:
        try:
            player_anchor = {
                "x": int(req.player.pos.x),
                "y": int(req.player.pos.y),
                "z": int(req.player.pos.z),
            }
            existing = plan.get("anchor") if isinstance(plan.get("anchor"), dict) else None
            looks_world_spawn = (
                existing is None
                or (
                    int(existing.get("x", 0)) == 0
                    and int(existing.get("z", 0)) == 0
                    and abs(int(existing.get("y", 64)) - 64) <= 8
                )
            )
            # Always prefer player feet when plan still points at silent world spawn.
            if looks_world_spawn and (
                abs(player_anchor["x"]) > 0
                or abs(player_anchor["z"]) > 0
                or abs(player_anchor["y"] - 64) > 8
            ):
                plan["anchor"] = player_anchor
            elif existing is None:
                plan["anchor"] = player_anchor
        except (TypeError, ValueError, AttributeError):
            pass
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
        plan["genome"] = _sanitize_genome_dict(_deep_merge(default_genome, plan.get("genome", {})))

    layout = plan.get("layout")
    if isinstance(layout, dict):
        plan["layout"] = _sanitize_layout_slots(layout)

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
            from .building_contract import extract_requirements
            floor_requirements = [r for r in extract_requirements(user_text) if r['property'] == 'floor_count']
            if floor_requirements and not any(r['scope'] == 'all_main_masses' for r in floor_requirements):
                floors_hint = None  # A named residence's floors do not belong to its chapel or workshop.
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
            comp["_style_hint"] = str(plan.get("style_profile") or "")
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
        _sanitize_landmark_modules(plan, req, building_profile)

    gc = plan.get("global_constraints")
    if isinstance(gc, dict):
        gc = dict(gc)
        sym = _normalize_global_symmetry_value(
            gc.get("symmetry"),
            genome=plan.get("genome") if isinstance(plan.get("genome"), dict) else None,
        )
        if sym:
            gc["symmetry"] = sym
        elif "symmetry" in gc:
            gc.pop("symmetry", None)
        if gc.get("facing") is not None:
            facing = str(gc["facing"]).strip().upper()
            facing = {
                "N": "NORTH", "S": "SOUTH", "E": "EAST", "W": "WEST",
                "北": "NORTH", "南": "SOUTH", "东": "EAST", "西": "WEST",
                "NONE": "SOUTH", "ANY": "SOUTH", "DEFAULT": "SOUTH",
            }.get(facing, facing)
            if facing in ("NORTH", "SOUTH", "EAST", "WEST"):
                gc["facing"] = facing
            else:
                gc.pop("facing", None)
        plan["global_constraints"] = gc

    if building_profile is not None:
        try:
            from .plan_specificity_guard import apply_profile_features_to_plan

            plan = apply_profile_features_to_plan(plan, building_profile)
        except Exception as exc:
            logger.warning("Plan distinguishing_features sync skipped: %s", exc)

    from .building_contract import apply_building_contract, request_text
    contract_text = request_text(req)
    plan = apply_building_contract(plan, contract_text)

    try:
        from .plan_architectural_enrichment import enrich_llm_plan_architectural_detail

        user_text = (req.userMessage or "").strip() if req is not None else ""
        plan = enrich_llm_plan_architectural_detail(
            plan,
            user_text=user_text,
            profile=building_profile,
        )
        if building_profile is not None:
            from .building_plan_stage import score_plan_profile_alignment

            alignment = score_plan_profile_alignment(plan, building_profile)
            logger.info(
                "Plan-profile alignment passed=%s coverage=%.2f matched=%s missing=%s guard=%s",
                alignment["passed"],
                alignment["feature_coverage"],
                alignment["feature_matched"],
                alignment["feature_missing"],
                plan.get("enrichment_guard"),
            )
    except Exception as exc:
        logger.warning("Architectural plan enrichment skipped: %s", exc)

    if isinstance(plan.get("components"), list):
        for comp in plan["components"]:
            if isinstance(comp, dict):
                comp.pop("_style_hint", None)

    if building_profile is not None:
        fidelity_msg = getattr(building_profile, "fidelity_message_zh", None)
        meta = plan.get("plan_meta") if isinstance(plan.get("plan_meta"), dict) else {}
        preserve_notice = bool(meta.get("preserve_fidelity_notice") or meta.get("scaffolded_from_request_intent"))
        if fidelity_msg and str(fidelity_msg).strip() and not preserve_notice:
            plan["player_fidelity_notice_zh"] = str(fidelity_msg).strip()

    plan = apply_building_contract(plan, contract_text)
    try:
        from .generation_capability import apply_stage_b_capability_repair

        user_text = (req.userMessage or "").strip() if req is not None else contract_text
        plan, cap_errors = apply_stage_b_capability_repair(plan, user_text or "")
        if cap_errors:
            logger.info("Stage-B capability remaining issues: %s", cap_errors[:5])
    except Exception as exc:
        logger.warning("Stage-B capability repair skipped: %s", exc)
    if building_profile is not None:
        from .style_feature_compiler import apply_style_feature_defaults
        plan = apply_style_feature_defaults(plan, building_profile)
        feature_report = plan["proportion_hints"]["style_feature_report"]
        logger.info("Style feature parameter verification: %s", feature_report)
    return plan


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


# Retired Python landmark BuildingSpec templates removed (2026-10-31).
# Geometry for landmarks lives in Java LandmarkModuleRegistry / LlmPlan MODULE routing.
# See docs/PYTHON_JAVA_PLANNING_BOUNDARY.md








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






def _llm_plan_context_block(req: BuildRequest, building_profile: Optional[Any] = None) -> str:
    """
    B3：为 LlmPlan 主路径补齐上下文（chatHistory + 本地文化 RAG），
    与 BuildingSpec 路径保持一致。全部为本地操作，不引入网络延迟。
    """
    parts: list[str] = []
    from .fbd_capabilities import fbd_prompt_block
    fbd_capabilities = fbd_prompt_block(getattr(req, 'userMessage', '') or '')
    if fbd_capabilities:
        parts.append(fbd_capabilities)
    from .building_contract import extract_requirements, request_text
    requirements = extract_requirements(request_text(req))
    if requirements:
        parts.append("User-owned building requirements (hard constraints; style suggestions cannot override): "
                     + json.dumps(requirements, ensure_ascii=False))
    parts.append("For scoped requirements building_N, bind the corresponding MASS_MAIN with params.requirement_scope='building_N'. "
                 "Never bind by array order. Use params.wall_block, params.floor_block and params.roof_block as exact Minecraft block IDs "
                 "for explicit materials. Entrance orientation must use layout.slots[].facing or global_constraints.facing; "
                 "Use roof_type='none', window_style='none', entrance_type='none' for explicit opt-outs on the owning MASS_MAIN. "
                 "Do not add conflicting hosted components. Missing values allow defaults; 'none' forbids that feature. "
                 "When an attached ROOF owns the roof, keep the MASS_MAIN roof_type consistent with it; never use 'none' to suppress duplicate roof generation. "
                 "Likewise, never set window_style or entrance_type to 'none' merely because separate hosted components generate them. "
                 "For multiple buildings with different materials, put exact IDs in each owning component; omit the shared style_attributes material field rather than combining annotated building-specific names. "
                 "entrance params.facing is not the runtime orientation field. User-facing entrance values use Minecraft world directions. "
                 "The legacy generator reverses cardinal names: set the runtime facing field to the requirement runtime_field_value "
                 "(world SOUTH -> NORTH, NORTH -> SOUTH, EAST -> WEST, WEST -> EAST), not to value.")
    parts.append("Give every component a unique params.component_id. MASS_MAIN owns its building. "
                 "For attached roofs, facades and interior stairs, params.host_id must reference that MASS_MAIN's "
                 "component_id; slot_id is a coordinate frame, not identity. Write slot_id at the component top level, never only inside params. "
                 "Use shape='rectangle' and plan_type='none' for rectangular shells, including closed Gothic shells. "
                 "All attached components share their host slot_id. relative_position is LOCAL to that slot; "
                 "never repeat layout.slots[].anchor in component coordinates. "
                 "For a building centered at slot anchor (-15,0,0), its center-anchored MASS_MAIN uses local (0,0,0). "
                 "Residential MASS_MAIN must be hollow with usable rooms; void_ratio describes facade openness, not solid room fill. "
                 "Prefer floor_height=4 for housing without explicit height constraints, with height >= floor_count * floor_height. "
                 "Use one-block floor plates at the host's floor_height multiples, normal residential walls one block thick "
                 "unless the user asks otherwise, and physically attach chimney/eave details to the host roof or walls. "
                 "Do not guess a single host for bridges.")
    user_text = (req.userMessage or "") if req is not None else ""
    if any(token in user_text.lower() for token in ("楼梯", "stair")):
        parts.append(
            "Building exterior is the primary deliverable: retain a full-height hollow MASS_MAIN shell, "
            "ground floor and attached ROOF for EVERY building, even when the request focuses on stairs. "
            "A stair ASSEMBLY is an interior addition, never the entire building. "
            "For linked buildings prefer separate MASS_MAIN shells, plate floors and a plate bridge. "
            "Every STAIR_SYSTEM operation requires direct from/to endpoint objects; flights[] is not supported. "
            "SHELL_BOX requires h>=2 and cannot represent a one-block bridge deck; use a plate component. "
            "Respect requested width/depth/floor count/floor height; relative_position.y is the bottom block Y, "
            "not the vertical center. Use unique slot_id values for separate buildings and their satellites. "
            "Use explicit anchor_mode='min_corner' consistently for placed shells, roofs and floors. "
            "Do not place a column/railing component_request on MASS_MAIN or ROOF: use separate DECOR_DETAIL. "
            "Keep walls continuous except deliberate doors/windows; do not carve an entire perimeter band. "
            "Use simple roof geometry and omit crowns/spires unless requested. "
            "Executable circulation contract: for each straight interior flight use component_type=STRUCTURE, "
            "features=['stair:straight_single_run'], params={from:{x,y,z},to:{x,y,z},width:3, "
            "stairs:'minecraft:oak_stairs',floor:'minecraft:oak_planks',clearHeight:2,landing_length:3}. "
            "Endpoints are local to relative_position and represent floor/tread block Y, not player Y. "
            "Use an axis-aligned run at least as long as the rise; top endpoint must meet the upper floor Y. "
            "Keep the flight and landing wholly inside the shell, with two blocks of headroom. "
            "Floor plates use MASS_SECONDARY params.extrude_mode='plate', dimensions.height=1; "
            "Keep the JSON concise: ordinary native roofs, doors, windows and railings do not need verbose component_request queries. "
            "For stacked tiers use MASS_SECONDARY with tier_index and host_id pointing to the parent building; do not give every tier a ground entrance. "
            "For bridge decks set plate_role=bridge with host_id and target_host_id (two mass component_ids); "
            "optional connection_level sets storey Y. Railings may bind to the bridge component. "
            "never model a floor as a tall solid volume. Stairs carve the floor opening after plates. "
            "Text-only features do not generate stairs or holes. Do not add furniture/ornament when excluded by the user."
        )
    skip_landmark_routing = building_profile is not None
    if any(token in user_text.lower() for token in ("折返", "转折楼梯", "switchback")):
        parts.append("For a switchback staircase use ASSEMBLY params.assembly={stair_type:'switchback',width:3,"
                     "flight_rise:3,flight_run:3,landing_width:7,landing_depth:2,material:{steps:'stone_bricks',landing:'oak_planks'}}. "
                     "Scale rise to half the floor-to-floor height, run>=rise, landing_width=2*width+1. "
                     "The backend compiles this supported contract to STAIR_SYSTEM ops. Never stack SHELL_BOX treads or invent graph ports.")
    if any(token in user_text.lower() for token in ("环形楼梯", "螺旋楼梯", "spiral stair", "ring stair")):
        parts.append("For a circular tower interior stair use ASSEMBLY params.assembly={kind:'ring_staircase',radius:6,"
                     "height:15,floorLevels:[5,10],floorMaterial:'smooth_stone',direction:'clockwise',roof_access:false}. "
                     "Use requested dimensions and occupied floor levels. This contract produces a face-adjacent square spiral "
                     "inside the circular wall, floor openings and two-block headroom. spiral_watchtower is a twisted shell preset, not an interior stair.")

    if _LLMPLAN_INJECT_CHAT_HISTORY and getattr(req, "chatHistory", None):
        parts.append("Chat History (recent turns, oldest first):")
        for msg in req.chatHistory[-8:]:
            parts.append(f"  {msg}")

    if _LLMPLAN_INJECT_CULTURE_RAG:
        try:
            from app.services.keyword_culture_retriever import (
                retrieve_budgeted as _culture_retrieve,
                resolve_landmark_module_routing as _landmark_routing,
                retrieve_building_knowledge as _building_knowledge_retrieve,
            )
            qtext = (req.requestText or "") + "\n" + (getattr(req, "userMessage", None) or "")
            rag = _culture_retrieve(qtext, **_resolve_rag_budget(req))
            if rag:
                assembly_draft = rag.get("assemblyDraft")
                if assembly_draft:
                    parts.append("\nAssemblyDraft(JSON):")
                    parts.append(json.dumps(assembly_draft, ensure_ascii=False, indent=2))
                culture_payload: Dict[str, Any] = {
                    "hits": rag.get("hits", []),
                    "fewShots": rag.get("fewShots", []),
                }
                try:
                    from app.services.typology_registry import is_migrated_landmark

                    lm = rag.get("landmarkModuleId")
                    if (
                        not skip_landmark_routing
                        and isinstance(lm, str)
                        and lm.strip()
                        and not is_migrated_landmark(lm.strip())
                    ):
                        culture_payload["landmarkModuleId"] = lm.strip()
                except Exception:
                    if not skip_landmark_routing and rag.get("landmarkModuleId"):
                        culture_payload["landmarkModuleId"] = rag.get("landmarkModuleId")
                if rag.get("structuralTypologyId"):
                    culture_payload["structuralTypologyId"] = rag.get("structuralTypologyId")
                if rag.get("typology"):
                    culture_payload["typology"] = rag.get("typology")
                if rag.get("llmPlanFewShots"):
                    culture_payload["llmPlanFewShots"] = rag.get("llmPlanFewShots")
                if rag.get("proportionCard"):
                    culture_payload["proportionCard"] = rag.get("proportionCard")
                if culture_payload.get("hits") or culture_payload.get("fewShots") or culture_payload.get("llmPlanFewShots"):
                    parts.append("\nCultureRetrieval(JSON):")
                    parts.append(json.dumps(culture_payload, ensure_ascii=False, indent=2))

            from app.services.proportion_retriever import retrieve_proportion_card, proportion_prompt_block
            _pc_id = None
            if rag and isinstance(rag.get("proportionCardId"), str):
                _pc_id = rag.get("proportionCardId")
            _prop_card = retrieve_proportion_card(qtext, proportion_card_id=_pc_id)
            if not _prop_card:
                from app.services.plan_architectural_enrichment import infer_heuristic_proportion_card

                _prop_card = infer_heuristic_proportion_card(
                    getattr(req, "userMessage", None) or qtext,
                )
            if _prop_card:
                parts.append("\nProportionOntology(JSON) [output proportion_hints in LlmPlan]:")
                parts.append(json.dumps(_prop_card, ensure_ascii=False, indent=2))
                _prop_block = proportion_prompt_block(_prop_card)
                if _prop_block:
                    parts.append(_prop_block)

            try:
                from app.services.typology_retriever import resolve_typology_for_intent, typology_prompt_block

                _culture_id = None
                if rag and rag.get("hits"):
                    _hits = rag.get("hits") or []
                    if _hits and isinstance(_hits[0], dict):
                        _culture_id = _hits[0].get("id")
                _typ_match = resolve_typology_for_intent(qtext, culture_card_id=_culture_id)
                _typ_block = typology_prompt_block(_typ_match)
                if _typ_block:
                    parts.append(_typ_block)
            except Exception:
                pass

            routing = None if skip_landmark_routing else _landmark_routing(qtext)
            if routing:
                tier = str(routing.get("routingTier") or "suggested").lower()
                if tier == "mandatory":
                    label = "MANDATORY"
                elif tier == "suggested":
                    label = "RECOMMENDED"
                else:
                    label = "OPTIONAL"
                parts.append(
                    f"\nLandmarkModuleRouting(JSON) [{label} — follow tier for LlmPlan components[]]:"
                )
                parts.append(json.dumps(routing, ensure_ascii=False, indent=2))

            from app.services.landmark_routing_policy import variation_context_block as _variation_block
            parts.append("\n" + _variation_block())

            building_kb = _building_knowledge_retrieve(qtext, topK=1)
            if building_kb:
                building_info = {
                    "name": building_kb.get("name"),
                    "nameEn": building_kb.get("nameEn"),
                    "description": building_kb.get("description"),
                    "descriptionZh": building_kb.get("descriptionZh"),
                    "features": building_kb.get("features"),
                    "dimensions": building_kb.get("dimensions"),
                    "llmPlanRouting": building_kb.get("llmPlanRouting"),
                }
                try:
                    from app.services.typology_registry import is_migrated_landmark

                    kb_lm = str(building_kb.get("landmarkModuleId") or "").strip()
                    if (
                        not skip_landmark_routing
                        and kb_lm
                        and not is_migrated_landmark(kb_lm)
                    ):
                        building_info["landmarkModuleId"] = kb_lm
                except Exception:
                    if not skip_landmark_routing and building_kb.get("landmarkModuleId"):
                        building_info["landmarkModuleId"] = building_kb.get("landmarkModuleId")
                parts.append("\nBuildingKnowledge(JSON):")
                parts.append(json.dumps(building_info, ensure_ascii=False, indent=2))
            if skip_landmark_routing:
                mc = getattr(building_profile, "minecraft_strategy", None)
                stid = (getattr(mc, "structural_typology", None) or "").strip()
                if stid:
                    parts.append(
                        "\nResearchRouting(JSON) [authoritative — from BuildingProfile]:"
                    )
                    parts.append(
                        json.dumps(
                            {
                                "structural_typology": stid,
                                "reference_landmark": getattr(mc, "reference_landmark", None),
                                "landmark_module": None,
                            },
                            ensure_ascii=False,
                            indent=2,
                        )
                    )
        except Exception:
            pass

    return "\n".join(parts).strip()


def _llm_plan_massing_prepass(client, model, system_prompt, user_prompt, req, timeout_sec):
    """
    B2 两阶段生成 —— 阶段1：只产出"体量/布局/风格骨架"的紧凑 JSON，用于约束阶段2的细化，
    降低单次超长 prompt 的任务过载。任何失败都返回 None 由调用方回退单阶段。
    """
    try:
        instr = (
            "You are MASSING a building BEFORE detailing it. Output a SHORT JSON object with keys: "
            "\"massing\" (overall form, number of floors, rough width/depth/height, any wings/setbacks), "
            "\"style\" (style family + a few defining features), and "
            "\"materials\" (wall / roof / accent). "
            "Do NOT output components, slots, or block ids yet. Keep it concise (< 40 lines)."
        )
        messages = [{"role": "system", "content": instr},
                    {"role": "user", "content": user_prompt}]
        response, _label = call_chat_with_llm_plan_response_formats(
            client,
            model=model,
            messages=messages,
            temperature=_clamp_temperature(getattr(req, "temperature", None), 0.4),
            call_with_timeout=_call_with_timeout,
            timeout_sec=timeout_sec,
            prefer_json_object=True,
        )
        content = response.choices[0].message.content
        if content and content.strip():
            return content.strip()
    except Exception as e:
        logger.warning("LlmPlan two-stage massing pre-pass failed, falling back to single stage: %s", e)
    return None


def generate_llm_plan(req: BuildRequest) -> dict:
    """
    处理 LlmPlan 格式的请求（Java 端的新格式）
    直接返回 LLM 的原始 JSON 输出，不做格式转换
    
    对于强类型建筑（如埃菲尔铁塔、中国古建筑等），会自动搜索参考资料
    """
    client = get_client(req)
    if not client:
        raise ValueError("LLM client not available for LlmPlan generation")
    
    # Backend owns system prompt assembly. Prefer userMessage / explicit fields
    # so user text containing "USER REQUEST:" is never silently truncated.
    from .prompt_assembly import resolve_llm_plan_prompts, research_user_text as _research_user_text
    from .llm_plan_system_prompt import build_llm_plan_system_prompt

    request_text = req.requestText or ""
    system_prompt, user_prompt = resolve_llm_plan_prompts(
        req, default_system=build_llm_plan_system_prompt(req)
    )
    
    # ========== 开放世界建筑研究（PR-1/PR-2：BuildingResearchAgent）==========
    # Stage R: research_building_profile → BuildingProfile
    # Stage P: BUILDING_RESEARCH_TWO_PHASE=on 时在 LLM 调用前注入独立 plan stage prompt
    search_ms = 0.0
    building_profile = None
    research_user_text = _research_user_text(req, user_prompt)

    if research_user_text:
        _t_search = time.monotonic()
        try:
            from .building_research_agent import (
                is_building_research_enabled,
                research_building_profile,
                format_profile_for_prompt,
                ensure_building_profile_for_plan,
            )
            from .building_plan_stage import (
                is_research_two_phase_enabled,
                apply_research_landmark_override,
            )

            if research_user_text and is_building_research_enabled():
                building_profile = _call_with_timeout(
                    lambda: research_building_profile(
                        research_user_text,
                        req=req,
                        call_with_timeout=_call_with_timeout,
                    ),
                    float(os.getenv("BUILDING_RESEARCH_TIMEOUT_SEC", "15")),
                )
        except ImportError:
            logger.warning("building_research_agent not available, skipping research")
        except TimeoutError:
            logger.warning(
                "Building research timed out after %ss; skipping",
                os.getenv("BUILDING_RESEARCH_TIMEOUT_SEC", "15"),
            )
        except Exception as e:
            logger.warning("Failed building research: %s", e)
        search_ms = (time.monotonic() - _t_search) * 1000.0

    try:
        from .building_research_agent import ensure_building_profile_for_plan
        from .building_plan_stage import apply_research_landmark_override

        building_profile = ensure_building_profile_for_plan(research_user_text, building_profile)
        if building_profile is not None:
            system_prompt = apply_research_landmark_override(system_prompt, building_profile)
            from .building_plan_stage import is_research_two_phase_enabled
            from .building_research_agent import format_profile_for_prompt

            if not is_research_two_phase_enabled():
                user_prompt = (
                    format_profile_for_prompt(building_profile, compact_routing=True)
                    + "\n\n"
                    + user_prompt
                )
                logger.info("Added BuildingResearch profile context (single-phase) to LlmPlan prompt")
            else:
                logger.info(
                    "BuildingResearch profile ready for two-phase plan stage: %r",
                    building_profile.identity.name,
                )
    except Exception as e:
        logger.warning("Building profile ensure/override skipped: %s", e)

    # ========== Track B：意图扩写(B1) + 上下文注入(B3) ==========
    # 均为本地操作（规则/本地检索），不引入网络延迟。
    if _LLMPLAN_INTENT_ENRICH:
        try:
            intent_src = (getattr(req, "userMessage", None) or "") or user_prompt
            intent_block = _derive_structured_intent(intent_src)
            if intent_block:
                user_prompt = intent_block + "\n\n" + user_prompt
        except Exception as e:
            logger.warning("LlmPlan intent enrich skipped: %s", e)
    try:
        context_block = _llm_plan_context_block(req, building_profile)
        if context_block:
            user_prompt = context_block + "\n\n" + user_prompt
    except Exception as e:
        logger.warning("LlmPlan context injection skipped: %s", e)

    llm_ms = 0.0
    normalize_ms = 0.0
    try:
        model = _resolve_model(req, "gpt-4o-mini")
        if _LLMPLAN_MODEL_OVERRIDE:
            logger.info("LlmPlan: overriding model %s -> %s (LLMPLAN_MODEL)", model, _LLMPLAN_MODEL_OVERRIDE)
            model = _LLMPLAN_MODEL_OVERRIDE
        timeout_sec = _resolve_timeout_sec_for_task("llmplan", req, model)

        # B2 两阶段：先做体量骨架预跑，再把骨架作为约束注入细化阶段（默认关闭）。
        if _LLMPLAN_TWO_STAGE:
            skeleton = _llm_plan_massing_prepass(client, model, system_prompt, user_prompt, req, timeout_sec)
            if skeleton:
                user_prompt = ("[MASSING SKELETON — refine this into a full LlmPlan; stay consistent "
                               "with the massing/style/materials below]\n" + skeleton + "\n\n" + user_prompt)

        # PR-2: Research → Plan 两阶段 — profile 就绪后注入 Stage P prompt（在 massing 之后、LLM 之前）
        if building_profile is not None:
            try:
                from .building_plan_stage import (
                    is_research_two_phase_enabled,
                    augment_prompts_for_plan_stage,
                )
                if is_research_two_phase_enabled():
                    system_prompt, user_prompt = augment_prompts_for_plan_stage(
                        building_profile,
                        research_user_text,
                        system_prompt,
                        user_prompt,
                    )
                    logger.info("Applied BuildingResearch two-phase plan stage prompts")
            except Exception as e:
                logger.warning("Plan stage augmentation skipped: %s", e)

        from .fbd_capabilities import requests_fbd, fbd_system_prompt, fbd_request_prompt
        if requests_fbd(getattr(req, 'userMessage', '') or ''):
            system_prompt = fbd_system_prompt(req.userMessage)
            user_prompt = fbd_request_prompt(req)
            logger.info("LlmPlan route=fbd explicit request; using dedicated procedural contract")

        messages = []
        if system_prompt:
            messages.append({"role": "system", "content": system_prompt})
        messages.append({"role": "user", "content": user_prompt})

        # 已知不支持严格 json_schema 的 provider：auto 模式下直接走 json_object，
        # 避免一次必失的 json_schema 尝试（否则等于双调用）。
        prefer_json_object = (
            resolve_llmplan_json_schema_mode() == "auto"
            and resolve_provider(req) in _NON_JSON_SCHEMA_PROVIDERS
        )

        _t_llm = time.monotonic()
        response, _response_format_label = call_chat_with_llm_plan_response_formats(
            client,
            model=model,
            messages=messages,
            temperature=_clamp_temperature(os.getenv("LLMPLAN_TEMPERATURE"), 0.3),
            call_with_timeout=_call_with_timeout,
            timeout_sec=timeout_sec,
            prefer_json_object=prefer_json_object,
            transient_retries=1,
        )
        llm_ms = (time.monotonic() - _t_llm) * 1000.0
        
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
        _t_norm = time.monotonic()
        user_text = (req.userMessage or user_prompt or research_user_text or "").strip()

        def _normalize_for_plan(raw_plan: Dict[str, Any], build_req: BuildRequest, **_: Any) -> Dict[str, Any]:
            return _normalize_llm_plan_output(
                raw_plan,
                build_req,
                building_profile=building_profile,
            )

        from .llm_plan_repair import repair_llm_plan_validation

        normalized = repair_llm_plan_validation(
            plan,
            req,
            user_text=user_text,
            building_profile=building_profile,
            normalize_fn=_normalize_for_plan,
            client=client,
            model=model,
            messages=messages,
            call_with_timeout=_call_with_timeout,
            timeout_sec=timeout_sec,
            prefer_json_object=prefer_json_object,
        )
        try:
            from .assembly_plan_repair import maybe_repair_assembly_plan
            normalized = maybe_repair_assembly_plan(
                client,
                model,
                messages,
                normalized,
                user_text or None,
                call_with_timeout=_call_with_timeout,
                timeout_sec=timeout_sec,
                prefer_json_object=prefer_json_object,
                normalize_fn=_normalize_for_plan,
                req=req,
            )
        except Exception as e:
            logger.warning("Assembly plan repair skipped: %s", e)
        try:
            from .assembly_plan_repair import finalize_assembly_plan_or_gap
            normalized = finalize_assembly_plan_or_gap(normalized, user_text or None)
        except Exception as e:
            logger.warning("Assembly capability_gap finalize skipped: %s", e)
        from .building_contract import apply_building_contract, request_text
        from .fbd_capabilities import is_fbd_only_plan
        if normalized.get('plan_status') != 'capability_gap' and not is_fbd_only_plan(normalized):
            normalized = apply_building_contract(normalized, request_text(req), finalize=True)
        normalize_ms = (time.monotonic() - _t_norm) * 1000.0
        logger.info(
            "LlmPlan timing: search=%.0fms llm=%.0fms normalize=%.0fms model=%s fmt=%s",
            search_ms, llm_ms, normalize_ms, model, _response_format_label,
        )
        return normalized
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

    # Landmark geometry is owned by Java LandmarkModuleRegistry / LlmPlan MODULE routing.
    # Python no longer short-circuits to hardcoded BuildingSpec templates (Eiffel, Temple of Heaven, …).
    if arche is not None and strong:
        logger.info(
            "Archetype %s detected (strong) — falling through to LLM BuildingSpec / prefer LlmPlan on Java",
            arche.id,
        )

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
