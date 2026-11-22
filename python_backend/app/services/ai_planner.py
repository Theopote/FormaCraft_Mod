"""
AI Planner Service — canonical orchestration for LlmPlan.

ACTIVE_COMPAT only (explicit ``outputFormat=buildingspec`` via ``routes/build.py``):
``generate_building_spec`` / ``generate_city_spec`` / ``generate_composite_spec``.
Do not add new default planning entrypoints here; extend ``generate_llm_plan`` instead.
"""
import os
import json
import re
import time
import logging
from typing import Any, Dict, Optional

logger = logging.getLogger(__name__)


from .llm_plan_normalization import _normalize_llm_plan_output
from ..models.request import BuildRequest
from .legacy_spec_fallbacks import (
    _generate_fallback_spec,
    _generate_fallback_city_spec,
    _generate_fallback_composite_spec,
)
from .legacy_spec_genome import _ensure_genome_for_spec, _ensure_genome_for_composite_spec, _ensure_genome_for_city_spec
from .legacy_spec_normalization import _normalize_building_spec_dict, _normalize_composite_spec_dict
from .semantic_plan_prompts import build_semantic_plan_prompts, append_semantic_plan_context
from .semantic_plan_adapters import attach_semantic_plan_to_first_structure, fill_city_semantic_roles
# Compatibility re-exports: callers/tests still patch/import these via ai_planner.
from .planning_request_hints import (
    _derive_structured_intent,
    _infer_scale,
    _infer_terrain,
    _parse_levels_from_text,
)
from .llm_plan_wire_normalization import (
    _normalize_component_type,
    _normalize_features,
    _repair_component_request_strings,
)
from .llm_client import get_client, build_config, resolve_provider
# Compatibility alias: existing callers and test patches keep the same facade.
from .inference_call import (
    call_with_timeout as _call_with_timeout,
    _resolve_timeout_sec,
    _resolve_timeout_sec_for_task,
)
from ..models.building_spec import BuildingSpec
from ..models.llm_plan_json_schema import (
    call_chat_with_llm_plan_response_formats,
    resolve_llmplan_json_schema_mode,
)
from ..models.composite_spec import CompositeSpec
from ..models.city_spec import CitySpec
from ..models.semantic_spatial_plan import SemanticSpatialPlan
from .archetype_detector import detect_archetype_local, should_force_strong_mode


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
# B3 在 LlmPlan 路径注入 chatHistory：on（默认）| off
_LLMPLAN_INJECT_CHAT_HISTORY = (os.getenv("LLMPLAN_CHAT_HISTORY") or "on").strip().lower() != "off"
# B3 在 LlmPlan 路径注入文化 RAG（本地检索，非网络）：on（默认）| off
_LLMPLAN_INJECT_CULTURE_RAG = (os.getenv("LLMPLAN_CULTURE_RAG") or "on").strip().lower() != "off"
# B2 两阶段生成（先体量后细节）：默认 off（会增加一次 LLM 调用/延迟；失败自动回退单阶段）
_LLMPLAN_TWO_STAGE = (os.getenv("LLMPLAN_TWO_STAGE") or "off").strip().lower() == "on"


from .rag_budget import resolve_rag_budget as _resolve_rag_budget
from .building_spec_prompts import (
    build_city_system_prompt,
    build_composite_system_prompt,
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


# LLM sometimes invents skeleton labels outside the schema (e.g. TOWER_SHAFT).


def generate_semantic_spatial_plan(req: BuildRequest) -> Optional[SemanticSpatialPlan]:
    """
    I-layer: Semantic Spatial Plan (zone graph) for clusters/cities.
    Returns None on failure; callers wait for the configured bounded model call.
    """
    client = get_client(req)
    if not client:
        return None

    # IMPORTANT: keep enums aligned with Java side:
    # - SemanticZoneType: CORE/PUBLIC/SEMI_PUBLIC/PRIVATE/SERVICE/TRANSITION/LANDSCAPE/CIRCULATION
    # - TerrainPolicy: PRESERVE_DOMINANT/BALANCED/ENGINEERED
    system_prompt, user_prompt = build_semantic_plan_prompts(req)

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


# Retired Python landmark BuildingSpec templates removed (2026-10-31).
# Geometry for landmarks lives in Java LandmarkModuleRegistry / LlmPlan MODULE routing.
# See docs/PYTHON_JAVA_PLANNING_BOUNDARY.md


def generate_city_spec(req: BuildRequest) -> CitySpec:
    """ACTIVE_COMPAT：仅显式 buildingspec 城市分流调用；新能力走 ``generate_llm_plan``。"""
    client = get_client(req)

    if not client:
        return _ensure_genome_for_city_spec(_generate_fallback_city_spec(req), req)

    # I-layer: best-effort semantic spatial planning before full city generation.
    # This is used both as prompt context and as a source of spec.extra knobs (terrain/semantic scoring).
    semantic_plan = generate_semantic_spatial_plan(req)
    
    system_prompt = build_city_system_prompt()
    
    user_prompt = _build_user_prompt(req)
    user_prompt = append_semantic_plan_context(user_prompt, semantic_plan)
    
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
        fill_city_semantic_roles(spec)

        attach_semantic_plan_to_first_structure(req, semantic_plan, spec.structures)

        return spec
        
    except Exception as e:
        # 有 client 说明用户配置了 LLM；失败应直接报错，让上游给用户明确提示
        raise RuntimeError(f"LLM call failed for city spec: {e}") from e


def generate_composite_spec(req: BuildRequest) -> CompositeSpec:  # ACTIVE_COMPAT: explicit buildingspec only
    """
    兼容入口：调用模型生成 CompositeSpec；新增生成能力走 LlmPlan。
    """
    client = get_client(req)

    # 如果 OpenAI 客户端不可用，使用回退方案
    if not client:
        return _ensure_genome_for_composite_spec(_generate_fallback_composite_spec(req), req)

    # I-layer: best-effort semantic plan as context for composite generation (clusters/courtyards/compounds).
    semantic_plan = generate_semantic_spatial_plan(req)
    
    system_prompt = build_composite_system_prompt()
    
    user_prompt = _build_user_prompt(req)
    user_prompt = append_semantic_plan_context(user_prompt, semantic_plan)
    
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

        attach_semantic_plan_to_first_structure(req, semantic_plan, spec.structures)

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
                 "component_id. Attached components share their host's top-level slot_id. "
                 "Attach supported details to their intended surfaces. "
                 "Do not guess a single host for bridges.")
    user_text = (req.userMessage or "") if req is not None else ""
    if any(token in user_text.lower() for token in ("楼梯", "stair")):
        parts.append(
            "When the request includes an enclosing building, preserve its intended envelope, floors and roof choices. "
            "A stair ASSEMBLY supplies circulation; model any requested enclosure separately. "
            "For linked buildings prefer separate MASS_MAIN shells, plate floors and a plate bridge. "
            "Every STAIR_SYSTEM operation requires direct from/to endpoint objects; flights[] is not supported. "
            "SHELL_BOX requires h>=6 and cannot represent a one-block bridge deck; use a plate component. "
            "Respect requested width, depth, floor count and floor height. "
            "Do not place a column/railing component_request on MASS_MAIN or ROOF: use separate DECOR_DETAIL. "
            "Keep walls continuous except deliberate doors/windows; do not carve an entire perimeter band. "
            "Preserve roof form and roof omissions specified in the request. "
            "Executable circulation contract: for each straight interior flight use component_type=STRUCTURE, "
            "features=['stair:straight_single_run'], params={from:{x,y,z},to:{x,y,z},width:3, "
            "floor:<chosen floor block>,clearHeight:2,landing_length:3}. "
            "For a matching tread family, omit stairs and let Java resolve a registered variant. "
            "Set stairs explicitly when the design calls for another material or an explicit block state. "
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
    # Open-world: never inject landmark MODULE / name-bound routing unless the user
    # explicitly requested preset:<id>. BuildingProfile Stage P owns bound routes.
    from app.services.building_research_agent import resolve_landmark_module_for_intent
    explicit_preset = resolve_landmark_module_for_intent(user_text)
    skip_landmark_routing = building_profile is not None or explicit_preset is None
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

    if _LLMPLAN_INJECT_CULTURE_RAG and not getattr(getattr(building_profile, "minecraft_strategy", None), "model_designed_landmark", False):
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
                        and lm.strip() == explicit_preset
                        and not is_migrated_landmark(lm.strip())
                    ):
                        culture_payload["landmarkModuleId"] = lm.strip()
                except Exception:
                    if not skip_landmark_routing and rag.get("landmarkModuleId") == explicit_preset:
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

            routing = None if skip_landmark_routing else _landmark_routing(user_text)
            if routing and (routing.get("moduleId") or routing.get("referenceLandmark")) == explicit_preset:
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
                # Knowledge only — never forward llmPlanRouting / fixed-executor instructions
                # through ordinary research context (those belong in explicit preset catalogs).
                building_info = {
                    "name": building_kb.get("name"),
                    "nameEn": building_kb.get("nameEn"),
                    "description": building_kb.get("description"),
                    "descriptionZh": building_kb.get("descriptionZh"),
                    "features": building_kb.get("features"),
                    "dimensions": building_kb.get("dimensions"),
                }
                try:
                    from app.services.typology_registry import is_migrated_landmark

                    kb_lm = str(building_kb.get("landmarkModuleId") or "").strip()
                    if (
                        not skip_landmark_routing
                        and kb_lm == explicit_preset
                        and not is_migrated_landmark(kb_lm)
                    ):
                        building_info["landmarkModuleId"] = kb_lm
                except Exception:
                    if not skip_landmark_routing and building_kb.get("landmarkModuleId") == explicit_preset:
                        building_info["landmarkModuleId"] = building_kb.get("landmarkModuleId")
                parts.append("\nBuildingKnowledge(JSON):")
                parts.append(json.dumps(building_info, ensure_ascii=False, indent=2))
        except Exception:
            pass

    return "\n".join(parts).strip()


def _llm_plan_massing_prepass(client, model, system_prompt, user_prompt, req, timeout_sec, *, geometry_evidence=None):
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
        if geometry_evidence:
            user_prompt += (
                "\n\nResearch geometry observations (optional evidence; explicit user requirements and omissions win):\n"
                + json.dumps(geometry_evidence, ensure_ascii=False)
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
    生成 LlmPlan 主干响应。
    按配置研究建筑意图、组装提示词并调用模型；输出经规范化、修复与校验后返回。
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
    image_reference = next(
        (ref for ref in (req.references or []) if ref.type.strip().lower() in {"image_url", "image_base64"}),
        None,
    )

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

            if research_user_text and (image_reference is not None or is_building_research_enabled()):
                # research_building_profile already applies call_with_timeout once around
                # its body; do not wrap again (nested slots / redundant saturation risk).
                building_profile = research_building_profile(
                    research_user_text,
                    req=req,
                    call_with_timeout=_call_with_timeout,
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

    from .vision_analyzer import vision_source_url
    if image_reference is not None and not (
        building_profile is not None and any(
            source.title == "vision" and source.url == vision_source_url(image_reference.normalized_image_url())
            for source in building_profile.sources
        )
    ):
        return {
            "mode": "build", "anchor": req.player.pos.model_dump(), "plan_status": "capability_gap",
            "capability_gap": {
                "code": "E_REFERENCE_IMAGE_UNAVAILABLE", "path": "references",
                "message": "无法分析参考图片。请检查图片链接和视觉模型，或移除参考图后仅使用文字重试。",
            },
        }

    try:
        from .building_research_agent import ensure_building_profile_for_plan
        from .building_plan_stage import apply_research_landmark_override

        building_profile = ensure_building_profile_for_plan(research_user_text, building_profile)
        if building_profile is not None:
            system_prompt = apply_research_landmark_override(system_prompt, building_profile, research_user_text)
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
            from .building_plan_stage import is_research_two_phase_enabled

            geometry_evidence = (
                building_profile.to_prompt_dict().get("geometry_evidence")
                if building_profile is not None and is_research_two_phase_enabled() else None
            )
            skeleton = _llm_plan_massing_prepass(client, model, system_prompt, user_prompt, req, timeout_sec,
                                                geometry_evidence=geometry_evidence)
            if skeleton:
                user_prompt = ("[MASSING SKELETON — preliminary design; revise where the user request or "
                               "supported geometry requires it]\n" + skeleton + "\n\n" + user_prompt)

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
    """ACTIVE_COMPAT：仅显式 ``outputFormat=buildingspec`` 单体分流；新能力走 ``generate_llm_plan``。"""
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
    user_prompt = append_semantic_plan_context(user_prompt, semantic_plan, include_layout_note=True)
    
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
