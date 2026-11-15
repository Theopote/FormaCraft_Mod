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
)
from ..services.planning_route_policy import _should_generate_city, _should_generate_composite
from ..services.intent_clarification import maybe_clarification_response
from ..services.planning_execution import planning_executor

router = APIRouter()


def _maybe_clarify(build_req: BuildRequest) -> dict | None:
    """Return clarification response when user intent is too vague; else None."""
    if (build_req.promptMode or "").strip().upper() != "BUILD":
        return None
    return maybe_clarification_response(build_req)


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
    默认调用 LlmPlan 主干；显式 buildingspec 请求保留兼容路径。

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

        return await planning_executor.run(request, _generate_response, build_req)
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Failed to generate plan: {str(e)}")


def _generate_response(build_req: BuildRequest) -> dict:
    """Dispatch and encode entirely on the planning worker; preserve response contracts.

    Canonical: ``generate_llm_plan``. ACTIVE_COMPAT: ``generate_*_spec`` only when
    ``outputFormat=buildingspec`` — do not add new default routes here.
    """
    try:
        # 1. 如果明确指定了 outputFormat，使用指定的格式
        if build_req.outputFormat == "llmplan":
            clar = _maybe_clarify(build_req)
            if clar:
                return clar
            return _tagged(generate_llm_plan(build_req), "llmplan")
        elif build_req.outputFormat == "buildingspec":
            # 显式旧格式请求保留城市/复合/单体分流。
            if _should_generate_city(build_req):
                return _tagged(generate_city_spec(build_req), "city")
            if _should_generate_composite(build_req):
                return _tagged(generate_composite_spec(build_req), "composite")
            return _tagged(generate_building_spec(build_req), "buildingspec")

        # Implicit/auto requests use the canonical pipeline in every prompt mode.
        clar = _maybe_clarify(build_req)
        if clar:
            return clar
        return _tagged(generate_llm_plan(build_req), "llmplan")
    except LlmPlanValidationError as e:
        # Should be rare: generate_llm_plan degrades to profile fallback / capability_gap.
        raise HTTPException(status_code=502, detail="; ".join(e.errors))
    except Exception as e:
        from ..services.llm_error_humanizer import humanize_llm_exception

        raise HTTPException(status_code=502, detail=humanize_llm_exception(e, build_req))
