from fastapi import APIRouter, HTTPException, Request
from typing import Union

from ..models.request import BuildRequest
from ..models.request_adapter import FormaRequestAdapter
from ..models.building_spec import BuildingSpec
from ..models.composite_spec import CompositeSpec
from ..models.city_spec import CitySpec
from ..services.ai_planner import (
    generate_building_spec, 
    generate_composite_spec, 
    generate_city_spec,
    generate_llm_plan,
    _should_generate_composite,
    _should_generate_city
)

router = APIRouter()


@router.post("/build")
async def build_endpoint(request: Request) -> Union[BuildingSpec, CompositeSpec, CitySpec, dict]:
    """
    接收来自 Minecraft 服务器的 BuildRequest，
    调用 AI 生成 BuildingSpec，并返回。
    
    支持多种请求格式：
    1. 标准的 BuildRequest（推荐）
    2. FormaRequestAdapter（适配 Java 端的扁平结构）
    3. 原始字典（自动推断）
    """
    try:
        # 获取原始 JSON 数据
        raw_data = await request.json()
        
        # 尝试作为 FormaRequestAdapter 解析（兼容 Java 端格式）
        try:
            adapter = FormaRequestAdapter(**raw_data)
            build_req = adapter.to_build_request()
        except Exception as e1:
            # 如果失败，尝试作为标准 BuildRequest
            try:
                build_req = BuildRequest(**raw_data)
            except Exception as e2:
                raise HTTPException(
                    status_code=400, 
                    detail=f"Invalid request format. FormaRequestAdapter error: {e1}, BuildRequest error: {e2}"
                )
        
        # 检查应该生成什么类型的结构（优先级：outputFormat > 城市 > 复合 > 单个）
        try:
            # 1. 如果明确指定了 outputFormat，使用指定的格式
            if build_req.outputFormat == "llmplan":
                return generate_llm_plan(build_req)
            elif build_req.outputFormat == "buildingspec":
                # 跳过城市和复合结构检查，直接生成 BuildingSpec
                if _should_generate_city(build_req):
                    return generate_city_spec(build_req)
                if _should_generate_composite(build_req):
                    return generate_composite_spec(build_req)
                return generate_building_spec(build_req)
            
            # 2. 如果 outputFormat 是 "auto" 或未设置，使用智能路由
            # 2.1 检查是否是 LlmPlan 格式（基于 promptMode 和 requestText）
            if build_req.promptMode == "BUILD":
                request_text = build_req.requestText or ""
                # 检查 requestText 是否包含 LlmPlan 格式的特征
                # 因为 PromptAssembler 总是生成 LlmPlan 格式的 prompt，所以默认检查这些特征
                llm_plan_indicators = [
                    "STRUCTURED JSON TEMPLATE",  # PromptAssembler 明确包含这个
                    "component_type", "semantic components",
                    "ComponentObject", "SlotObject",
                    "mode", "style_profile", "components"
                ]
                # 如果包含 LlmPlan 格式的特征，使用 LlmPlan
                if any(indicator in request_text for indicator in llm_plan_indicators):
                    return generate_llm_plan(build_req)
            
            # 2.2 检查是否需要生成城市或复合结构
            if _should_generate_city(build_req):
                return generate_city_spec(build_req)
            if _should_generate_composite(build_req):
                return generate_composite_spec(build_req)
            
            # 2.3 默认生成 BuildingSpec
            return generate_building_spec(build_req)
        except Exception as e:
            # LLM 调用失败时给上游明确错误（由服务端回传到客户端聊天窗口）
            raise HTTPException(status_code=502, detail=str(e))
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Failed to generate building spec: {str(e)}")
