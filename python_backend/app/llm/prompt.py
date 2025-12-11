"""
LLM Prompt Engine - 生成建筑规格的提示词和调用逻辑
"""
import json
import os
from typing import Optional

from ..models import FormaRequestModel, BuildingSpec, Materials, Features

# 尝试导入 OpenAI（如果可用）
try:
    from openai import OpenAI
    HAS_OPENAI = True
except ImportError:
    HAS_OPENAI = False

SYSTEM_PROMPT = """你是一个 Minecraft 建筑专家助手。根据玩家的描述，生成详细的建筑规格。

输出格式必须是严格的 JSON，符合以下结构：
{
    "type": "tower|house|bridge|castle",
    "style": "medieval|modern|rustic",
    "height": 数字,
    "radius": 数字（仅用于塔楼）,
    "width": 数字,
    "depth": 数字,
    "materials": {
        "wall": "minecraft:stone_bricks|minecraft:oak_planks|...",
        "roof": "minecraft:dark_oak_planks|...",
        "floor": "minecraft:oak_planks|...",
        "foundation": "minecraft:stone|..."
    },
    "features": {
        "hasWindows": true|false,
        "hasStairs": true|false,
        "hasDoor": true|false,
        "hasRoof": true|false,
        "windowCount": 数字,
        "floorCount": 数字
    },
    "notes": "AI 生成的说明文字"
}

只输出 JSON，不要输出任何其他文字。"""


async def generate_building_spec(request: FormaRequestModel) -> BuildingSpec:
    """
    根据玩家请求生成建筑规格
    """
    # 如果配置了 OpenAI API Key，使用 LLM
    api_key = os.getenv("OPENAI_API_KEY")
    if HAS_OPENAI and api_key:
        return await _generate_with_llm(request, api_key)
    else:
        # 回退到规则基础方案
        return _generate_fallback(request)


async def _generate_with_llm(request: FormaRequestModel, api_key: str) -> BuildingSpec:
    """使用 LLM 生成建筑规格"""
    client = OpenAI(api_key=api_key)
    model = os.getenv("OPENAI_MODEL", "gpt-4o-mini")
    
    # 构建用户提示
    user_prompt = f"""玩家位置: {request.player.pos}
世界维度: {request.world.dimension}
生物群系: {request.world.biome or "未知"}

玩家请求: {request.request}

请根据以上信息生成建筑规格。"""
    
    if request.chat_history:
        user_prompt += "\n\n对话历史:\n" + "\n".join(request.chat_history)
    
    try:
        completion = client.chat.completions.create(
            model=model,
            messages=[
                {"role": "system", "content": SYSTEM_PROMPT},
                {"role": "user", "content": user_prompt},
            ],
            temperature=0.3,
            response_format={"type": "json_object"}  # 强制 JSON 输出
        )
        
        content = completion.choices[0].message.content or "{}"
        data = json.loads(content)
        
        # 转换为 BuildingSpec
        return _parse_to_building_spec(data)
    except Exception as e:
        # LLM 调用失败，回退到规则方案
        print(f"LLM call failed: {e}, falling back to rule-based generation")
        return _generate_fallback(request)


def _generate_fallback(request: FormaRequestModel) -> BuildingSpec:
    """规则基础的回退方案"""
    request_lower = request.request.lower()
    
    # 判断建筑类型
    if "塔" in request.request or "tower" in request_lower:
        building_type = "tower"
        height = 20
        radius = 6
        width = 0
        depth = 0
    elif "桥" in request.request or "bridge" in request_lower:
        building_type = "bridge"
        height = 1
        radius = 0
        width = 10
        depth = 3
    elif "房子" in request.request or "house" in request_lower or "房屋" in request.request:
        building_type = "house"
        height = 4
        radius = 0
        width = 8
        depth = 6
    else:
        building_type = "house"
        height = 4
        radius = 0
        width = 8
        depth = 6
    
    # 判断风格
    style = "medieval"
    if "现代" in request.request or "modern" in request_lower:
        style = "modern"
    elif "乡村" in request.request or "rustic" in request_lower:
        style = "rustic"
    
    # 判断材料
    wall_material = "minecraft:stone_bricks"
    if "木头" in request.request or "wood" in request_lower or "木" in request.request:
        wall_material = "minecraft:oak_planks"
    elif "砖" in request.request or "brick" in request_lower:
        wall_material = "minecraft:bricks"
    
    roof_material = "minecraft:dark_oak_planks"
    
    materials = Materials(
        wall=wall_material,
        roof=roof_material,
        floor="minecraft:oak_planks",
        foundation="minecraft:stone"
    )
    
    features = Features(
        hasWindows=True,
        hasStairs=True,
        hasDoor=True,
        hasRoof=True,
        windowCount=2,
        floorCount=1
    )
    
    return BuildingSpec(
        type=building_type,
        style=style,
        height=height,
        radius=radius,
        width=width,
        depth=depth,
        materials=materials,
        features=features,
        notes=f"根据规则生成的{building_type}建筑"
    )


def _parse_to_building_spec(data: dict) -> BuildingSpec:
    """将 LLM 返回的 JSON 解析为 BuildingSpec"""
    # 提取材料信息
    materials_data = data.get("materials", {})
    materials = Materials(
        wall=materials_data.get("wall", "minecraft:stone_bricks"),
        roof=materials_data.get("roof", "minecraft:dark_oak_planks"),
        floor=materials_data.get("floor", "minecraft:oak_planks"),
        foundation=materials_data.get("foundation", "minecraft:stone")
    )
    
    # 提取特性信息
    features_data = data.get("features", {})
    features = Features(
        hasWindows=features_data.get("hasWindows", False),
        hasStairs=features_data.get("hasStairs", False),
        hasDoor=features_data.get("hasDoor", False),
        hasRoof=features_data.get("hasRoof", False),
        windowCount=features_data.get("windowCount", 0),
        floorCount=features_data.get("floorCount", 1)
    )
    
    return BuildingSpec(
        type=data.get("type", "house"),
        style=data.get("style", "medieval"),
        height=data.get("height", 4),
        radius=data.get("radius", 0),
        width=data.get("width", 8),
        depth=data.get("depth", 6),
        materials=materials,
        features=features,
        notes=data.get("notes", "AI 生成的建筑规格")
    )

