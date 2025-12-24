"""
AI Planner Service
调用大模型生成 BuildingSpec 或 CompositeSpec
"""
import os
import json
import re
import concurrent.futures
from typing import Any, Dict, Optional, Union

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
from ..models.composite_spec import CompositeSpec, SubStructure, Vec3i, PathSpec
from ..models.city_spec import CitySpec, Zone, StructurePlan, BridgePlan, Point

_LLM_CALL_TIMEOUT_SEC = float(os.getenv("LLM_CALL_TIMEOUT_SEC", "45"))
_LLM_CALL_TIMEOUT_DEEPSEEK_SEC = float(os.getenv("LLM_CALL_TIMEOUT_DEEPSEEK_SEC", "60"))
_LLM_CALL_TIMEOUT_REASONER_SEC = float(os.getenv("LLM_CALL_TIMEOUT_REASONER_SEC", "120"))
# Composite/City 往往比单体 BuildingSpec 更慢；默认给更长时间（可通过环境变量覆盖）
_LLM_CALL_TIMEOUT_COMPOSITE_SEC = float(os.getenv("LLM_CALL_TIMEOUT_COMPOSITE_SEC", "600"))
_LLM_CALL_TIMEOUT_CITY_SEC = float(os.getenv("LLM_CALL_TIMEOUT_CITY_SEC", "600"))


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


def _build_system_prompt() -> str:
    return (
        "You are FormaCraft, an AI architect for Minecraft.\n"
        "Your job is to convert a player's natural language request and world context "
        "into a structured BuildingSpec JSON object.\n\n"
        "Requirements:\n"
        "- Always respond with pure JSON (no comments, no extra text).\n"
        "- Only use block IDs that exist in vanilla Minecraft.\n"
        "- Respect constraints: keep buildings reasonably sized unless user requests huge.\n"
        "- Footprint should be within the selection area if provided, otherwise use default sizes.\n"
        "- Choose reasonable defaults when player does not specify something.\n"
        "- Building types: HOUSE, TOWER, BRIDGE, CASTLE, WALL, CUSTOM\n"
        "- Building styles: MEDIEVAL, MODERN, ASIAN, FUTURISTIC, RUSTIC, DEFAULT\n"
        "- For circular buildings (towers), use footprint.shape='circle' and set radius\n"
        "- For rectangular buildings, use footprint.shape='rectangle' and set width/depth\n\n"
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
    
    if req.chatHistory:
        parts.append("\nChat History:")
        for msg in req.chatHistory:
            parts.append(f"  {msg}")
    
    return "\n".join(parts)


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
    task: building / composite / city
    """
    t = (task or "").strip().lower()
    base = _resolve_timeout_sec(req, model)
    if t == "composite":
        return max(base, _LLM_CALL_TIMEOUT_COMPOSITE_SEC)
    if t == "city":
        return max(base, _LLM_CALL_TIMEOUT_CITY_SEC)
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
    size = _parse_rect_size_from_text(text)
    # 触发我们 Java 侧 ASIAN courtyard 生成器的最小安全尺寸
    w, d = size if size else (20, 20)
    w = max(16, min(64, w))
    d = max(16, min(64, d))

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

    return BuildingSpec(
        type=BuildingType.HOUSE,
        style=BuildingStyle.ASIAN,
        footprint=Footprint(shape="rectangle", width=w, depth=d),
        height=10,
        floors=1,
        materials=materials,
        features=features,
        styleOptions=style_options,
        notes=f"模板：明清官式院落（{w}×{d}）。为保证按描述生成，使用确定性模板（除非用户要求随意）。",
        extra={"template": "mingqing_courtyard"},
    )


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
    
    return BuildingSpec(
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


def _should_generate_city(req: BuildRequest) -> bool:
    """检测是否应该生成城市级结构"""
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
        return _generate_fallback_city_spec(req)
    
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
        return CitySpec.model_validate(data)
        
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
    
    return CitySpec(
        cityName="Fallback City",
        style=base_spec.style.value if hasattr(base_spec.style, 'value') else str(base_spec.style),
        size="MEDIUM",
        biome="plains",
        zones=zones,
        structures=structures,
        roads=roads,
        bridges=bridges
    )


def generate_composite_spec(req: BuildRequest) -> CompositeSpec:
    """
    调用大模型，生成 CompositeSpec（复合结构）
    """
    client = get_client(req)

    # 如果 OpenAI 客户端不可用，使用回退方案
    if not client:
        return _generate_fallback_composite_spec(req)
    
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
        return CompositeSpec.model_validate(data)
        
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
    
    return CompositeSpec(structures=structures, paths=paths)


def generate_building_spec(req: BuildRequest) -> BuildingSpec:
    """
    调用大模型，把 BuildRequest -> BuildingSpec
    """
    # 明清官式院落：优先使用确定性模板（除非用户明确要求“随意/自由发挥”）
    if _should_use_mingqing_courtyard_template(req):
        return _generate_mingqing_courtyard_building_spec(req)

    client = get_client(req)

    # 如果 OpenAI 客户端不可用，使用回退方案
    if not client:
        return _generate_fallback_spec(req)
    
    system_prompt = _build_system_prompt()
    user_prompt = _build_user_prompt(req)
    
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
        
        return spec
        
    except Exception as e:
        raise RuntimeError(f"LLM call failed for building spec: {e}") from e
