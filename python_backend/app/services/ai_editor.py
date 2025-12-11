"""
AI Editor Service
增量编辑 CitySpec 和 BuildingSpec
"""
import os
import json
from typing import Optional

try:
    from openai import OpenAI
    HAS_OPENAI = True
except ImportError:
    HAS_OPENAI = False
    OpenAI = None

from ..models.city_spec import CitySpec
from ..models.building_spec import BuildingSpec

# 初始化 OpenAI 客户端
client: Optional[OpenAI] = None
if HAS_OPENAI:
    api_key = os.getenv("OPENAI_API_KEY")
    if api_key:
        client = OpenAI(api_key=api_key)


def _build_city_edit_system_prompt() -> str:
    """构建城市编辑的系统提示"""
    return """
You are FormaCraft City Editor.

You will receive:
1) The current CitySpec JSON.
2) A player edit command in natural language.

Your task:
- Modify ONLY necessary parts of the CitySpec.
- Preserve all IDs and existing structures unless the user explicitly asks to remove them.
- When adding new structures/roads/bridges, create new unique ids (e.g., tower_3, road_2, bridge_2).
- DO NOT regenerate everything from scratch.
- Keep the same cityName, style, size, biome unless explicitly changed.
- When modifying existing structures, find them by id and update only the specified fields.
- When removing structures, remove them from the list (do not set to null).
- Respond ONLY with the full updated CitySpec JSON.

ID Naming Convention:
- Structures: tower_1, tower_2, house_A, house_B, etc.
- Roads: main_road, path_1, road_2, etc.
- Bridges: bridge_1, main_bridge, etc.

Examples:
1. Command: "Add another tower to the north of the plaza."
   - Add a new StructurePlan with type TOWER.
   - Set offset.z < 0 (north).
   - Use a new id, e.g. "tower_3".

2. Command: "Make the main road wider, 5 blocks."
   - Find the PathSpec with id "main_road".
   - Set width = 5.

3. Command: "Remove the house with id house_A."
   - Remove the StructurePlan with id "house_A" from the structures list.

4. Command: "Change the height of tower_1 to 20 blocks."
   - Find StructurePlan with id "tower_1".
   - Update spec.height = 20.
"""


def _build_building_edit_system_prompt() -> str:
    """构建建筑编辑的系统提示"""
    return """
You are FormaCraft Building Editor.

You will receive:
1) The current BuildingSpec JSON.
2) A player edit command in natural language.

Your task:
- Modify ONLY the fields mentioned in the command.
- Preserve all other fields unchanged.
- DO NOT regenerate the entire spec from scratch.
- Respond ONLY with the full updated BuildingSpec JSON.

Examples:
1. Command: "Change the roof to a gable roof."
   - Update styleOptions.roofType = "gable".

2. Command: "Make the building 5 blocks taller."
   - Update height = current_height + 5.

3. Command: "Change wall material to stone bricks."
   - Update materials.wall = "minecraft:stone_bricks".
"""


def edit_city_spec(current: CitySpec, command: str, blueprint_name: Optional[str] = None) -> CitySpec:
    """
    增量编辑 CitySpec
    
    Args:
        current: 当前的 CitySpec
        command: 玩家的编辑指令（自然语言）
    
    Returns:
        更新后的 CitySpec
    """
    if not client:
        # 如果没有 OpenAI 客户端，返回原始 spec（或实现规则基础的回退）
        return current
    
    system_prompt = _build_city_edit_system_prompt()
    
    # 将当前 CitySpec 转换为 JSON 字符串
    current_json = current.model_dump_json(indent=2)
    
    blueprint_context = ""
    if blueprint_name:
        blueprint_context = f"\nNote: This CitySpec was loaded from blueprint '{blueprint_name}'. Preserve the blueprint's structure and IDs when possible.\n"
    
    user_prompt = f"""
Current CitySpec:

```json
{current_json}
```
{blueprint_context}
Player command:
"{command}"
"""
    
    try:
        response = client.chat.completions.create(
            model=os.getenv("OPENAI_MODEL", "gpt-4o-mini"),
            messages=[
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": user_prompt},
            ],
            response_format={"type": "json_object"},
            temperature=0.3,  # 较低温度以保证稳定性
        )
        
        raw_output = response.choices[0].message.content
        if not raw_output:
            raise ValueError("Empty response from OpenAI")
        
        data = json.loads(raw_output)
        updated = CitySpec.model_validate(data)
        
        return updated
        
    except Exception as e:
        print(f"LLM call for city edit failed: {e}, returning original spec")
        return current  # 失败时返回原始 spec


def edit_building_spec(current: BuildingSpec, command: str) -> BuildingSpec:
    """
    增量编辑 BuildingSpec
    
    Args:
        current: 当前的 BuildingSpec
        command: 玩家的编辑指令（自然语言）
    
    Returns:
        更新后的 BuildingSpec
    """
    if not client:
        return current
    
    system_prompt = _build_building_edit_system_prompt()
    
    current_json = current.model_dump_json(indent=2)
    
    user_prompt = f"""
Current BuildingSpec:

```json
{current_json}
```

Player command:
"{command}"
"""
    
    try:
        response = client.chat.completions.create(
            model=os.getenv("OPENAI_MODEL", "gpt-4o-mini"),
            messages=[
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": user_prompt},
            ],
            response_format={"type": "json_object"},
            temperature=0.3,
        )
        
        raw_output = response.choices[0].message.content
        if not raw_output:
            raise ValueError("Empty response from OpenAI")
        
        data = json.loads(raw_output)
        updated = BuildingSpec.model_validate(data)
        
        return updated
        
    except Exception as e:
        print(f"LLM call for building edit failed: {e}, returning original spec")
        return current

