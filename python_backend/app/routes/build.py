from fastapi import APIRouter, HTTPException, Request
from fastapi.encoders import jsonable_encoder

from ..models.request import BuildRequest
from ..models.request_adapter import FormaRequestAdapter
from ..models.llm_plan import LlmPlanValidationError
from ..services.ai_planner import (
    generate_building_spec, 
    generate_composite_spec, 
    generate_city_spec,
    generate_llm_plan,
    _should_generate_composite,
    _should_generate_city
)

router = APIRouter()


def _tagged(obj, kind: str) -> dict:
    """给 /build 响应注入显式判别字段 ``kind``。

    Java 端 OrchestratorClient.parseAiPlanResponse() 据此分发，不再靠脆弱的
    ``body.contains("...")`` 字符串启发式。序列化沿用 FastAPI 默认（by_alias=True），
    因此与之前直接返回 model 的字段命名完全一致，仅多出一个顶层 ``kind``。
    """
    data = dict(obj) if isinstance(obj, dict) else jsonable_encoder(obj)
    data["kind"] = kind
    return data


@router.post("/build")
async def build_endpoint(request: Request) -> dict:
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
                return _tagged(generate_llm_plan(build_req), "llmplan")
            elif build_req.outputFormat == "buildingspec":
                # 跳过城市和复合结构检查，直接生成 BuildingSpec
                if _should_generate_city(build_req):
                    return _tagged(generate_city_spec(build_req), "city")
                if _should_generate_composite(build_req):
                    return _tagged(generate_composite_spec(build_req), "composite")
                return _tagged(generate_building_spec(build_req), "buildingspec")
            
            # 2. outputFormat 为 "auto" 或未设置：BUILD 模式默认走 LlmPlan（与 Java ChatPanel 一致）
            if build_req.promptMode == "BUILD":
                return _tagged(generate_llm_plan(build_req), "llmplan")

            # 2.1 非 BUILD 模式：检查是否需要生成城市或复合结构
            if _should_generate_city(build_req):
                return _tagged(generate_city_spec(build_req), "city")
            if _should_generate_composite(build_req):
                return _tagged(generate_composite_spec(build_req), "composite")
            
            # 2.2 默认生成 BuildingSpec（地标 / 复合 / 城市等非 BUILD 模式）
            return _tagged(generate_building_spec(build_req), "buildingspec")
        except LlmPlanValidationError as e:
            # Should be rare: generate_llm_plan degrades to profile fallback / capability_gap.
            raise HTTPException(status_code=502, detail="; ".join(e.errors))
        except Exception as e:
            from ..services.llm_error_humanizer import humanize_llm_exception

            raise HTTPException(status_code=502, detail=humanize_llm_exception(e, build_req))
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Failed to generate building spec: {str(e)}")
