from __future__ import annotations

import json
import os
from typing import Any, Dict, List

from openai import OpenAI

from .models import BuildingRequestModel, BuildingResponseModel, StructureModel


SYSTEM_PROMPT = """
你是一个Minecraft建筑专家助手。根据玩家描述，生成详细的建筑指令。
输出格式必须为JSON：
{
    "building_type": "房屋/城堡/塔楼等",
    "dimensions": {"width": 数字, "height": 数字, "depth": 数字},
    "materials": ["主要材料列表"],
    "structure_plan": [
        {"layer": 1, "blocks": [{"x": 0, "y": 0, "z": 0, "block": "minecraft:stone"}]}
    ],
    "special_features": ["窗户", "门", "装饰等"]
}
只输出符合上述结构的 JSON，不要输出多余文字。
""".strip()


class PlanningAgent:
    """规划 Agent：理解需求，生成高层建筑方案。

    当前阶段：优先调用 LLM；若未配置 API Key 或调用失败，则回退到本地规则方案。
    """

    def __init__(self) -> None:
        api_key = os.getenv("OPENAI_API_KEY")
        self._client = OpenAI(api_key=api_key) if api_key else None
        self._model = os.getenv("OPENAI_MODEL", "gpt-4o-mini")

    def plan(self, request: BuildingRequestModel) -> dict:
        # 如未配置 API Key，则直接使用本地规则方案
        if self._client is None:
            return self._fallback_plan(request)

        # 组合带有会话上下文的信息：session_id + chat_history + 当前请求
        context_parts: List[str] = []
        if request.session_id:
            context_parts.append(f"会话 ID: {request.session_id}")
        if request.chat_history:
            history_text = "\n".join(request.chat_history)
            context_parts.append("历史对话如下（按时间顺序）:\n" + history_text)

        if context_parts:
            user_prompt = "\n\n".join(context_parts) + "\n\n当前玩家的新请求: " + request.prompt
        else:
            user_prompt = request.prompt

        try:
            completion = self._client.chat.completions.create(
                model=self._model,
                messages=[
                    {"role": "system", "content": SYSTEM_PROMPT},
                    {"role": "user", "content": user_prompt},
                ],
                temperature=0.3,
            )
            content = completion.choices[0].message.content or ""
            data = json.loads(content)
            # 简单校验关键字段是否存在；若不合法则走回退方案
            if not isinstance(data, dict) or "building_type" not in data:
                return self._fallback_plan(request)
            return data
        except Exception:
            # 任意异常均回退到本地规则方案，避免影响游戏体验
            return self._fallback_plan(request)

    def _fallback_plan(self, request: BuildingRequestModel) -> Dict[str, Any]:  # noqa: D401
        """本地回退方案：不依赖 LLM 的简易规划逻辑。"""
        return {
            "building_type": "castle",
            "dimensions": {"width": 12, "height": 8, "depth": 12},
            "materials": ["stone"],
            "structure_plan": [],
            "special_features": ["towers"],
        }


class DesignAgent:
    """设计 Agent：细化建筑细节，选择材料等。

    当前阶段：根据规划结果填充更具体的 StructureModel。
    """

    def design(self, plan: dict) -> StructureModel:
        building_type = str(plan.get("building_type", "castle"))
        materials = plan.get("materials") or ["stone"]
        primary_material = str(materials[0])
        towers = 4 if "tower" in building_type or "castle" in building_type else 0
        style = "medieval" if "castle" in building_type else "default"
        return StructureModel(
            type=building_type,
            material=primary_material,
            towers=towers,
            style=style,
        )


class ExecutionAgent:
    """执行 Agent：将设计结果转换为最终响应格式。

    在当前架构下，它主要负责把 StructureModel 包装为 BuildingResponseModel，
    将几层 Agent 串联起来；未来可以在这里生成更细的 structure_plan。
    """

    def execute(self, request: BuildingRequestModel, structure: StructureModel) -> BuildingResponseModel:
        raw = f"Agent plan for prompt: {request.prompt!r} type={structure.type} material={structure.material}"
        return BuildingResponseModel(raw_response=raw, structure=structure)


class AIPipeline:
    """简单的多 Agent 管道，把规划 / 设计 / 执行串联起来。"""

    def __init__(self) -> None:
        self.planning_agent = PlanningAgent()
        self.design_agent = DesignAgent()
        self.execution_agent = ExecutionAgent()

    def run(self, request: BuildingRequestModel) -> BuildingResponseModel:
        plan = self.planning_agent.plan(request)
        structure = self.design_agent.design(plan)
        return self.execution_agent.execute(request, structure)
