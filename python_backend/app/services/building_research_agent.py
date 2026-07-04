"""
Building Research Agent — 开放世界建筑理解主路径（PR-1）。

流程：QueryPlanner → MultiSourceSearch → ProfileSynthesizer → prompt 注入

不依赖硬编码地标词表；Culture Card / landmark 为旁路加速，非命中前提。
"""

from __future__ import annotations

import json
import logging
import os
import re
from typing import Any, Callable, Dict, List, Optional, Tuple

from ..models.building_profile import BuildingProfile, profile_from_llm_dict
from ..models.request import BuildRequest, ReferenceInput

logger = logging.getLogger(__name__)


def _env_str(name: str, default: str = "") -> str:
    return (os.getenv(name) or default).strip().lower()


def _env_float(name: str, default: float) -> float:
    try:
        return float(os.getenv(name, str(default)))
    except (TypeError, ValueError):
        return default


def _env_int(name: str, default: int) -> int:
    try:
        return int(os.getenv(name, str(default)))
    except (TypeError, ValueError):
        return default


def is_building_research_enabled() -> bool:
    raw = _env_str("BUILDING_RESEARCH")
    if raw == "off":
        return False
    if raw == "on":
        return True
    return _env_str("ARCHITECTURE_SEARCH", "landmark") != "off"


def research_mode() -> str:
    if not is_building_research_enabled():
        return "off"
    mode = _env_str("BUILDING_RESEARCH_MODE", "always")
    if mode in ("always", "named_only", "off"):
        return mode
    return "always"


def _building_research_timeout_sec() -> float:
    return _env_float("BUILDING_RESEARCH_TIMEOUT_SEC", 8.0)


def _building_research_llm_synth() -> bool:
    return _env_str("BUILDING_RESEARCH_LLM_SYNTH", "off") == "on"


def _building_research_max_queries() -> int:
    return max(1, _env_int("BUILDING_RESEARCH_MAX_QUERIES", 2))


def _building_research_max_results() -> int:
    return max(1, _env_int("BUILDING_RESEARCH_MAX_RESULTS", 4))


# ---- 环境开关（文档用；运行时读 env） ----

# 建造意图动词（中英）
_BUILD_VERBS_CN = (
    "建", "盖", "造", "复原", "还原", "复刻", "建造", "打造", "生成", "制作",
    "修", "砌", "搭", "复现", "仿", "照",
)
_BUILD_VERBS_EN = (
    "build", "recreate", "reconstruct", "make", "construct", "replicate",
    "restore", "model", "design",
)

# 非建筑主题的排除词（避免对 patch / 编辑类 prompt 误触发）
_NON_BUILD_HINTS = (
    "换成", "改成", "修改", "删除", "移除", "patch", "replace the roof",
    "change the roof", "把屋顶", "改屋顶",
)

# 建筑特征关键词（从 snippet 提取 distinctive_elements）
_FEATURE_KEYWORDS = (
    "dome", "tower", "spire", "courtyard", "atrium", "facade", "colonnade",
    "arch", "vault", "dome", "pagoda", "minaret", "buttress", "portico",
    "圆顶", "塔", "庭院", "中庭", "立面", "柱廊", "拱", "穹顶", "飞檐",
    "歇山", "琉璃", "钢结构", "玻璃幕墙", "悬挑", "曲线", "椭圆",
)


def _has_build_intent(text: str) -> bool:
    t = (text or "").strip()
    if not t:
        return False
    lower = t.lower()
    if any(h in t or h in lower for h in _NON_BUILD_HINTS):
        return False
    if any(v in t for v in _BUILD_VERBS_CN):
        return True
    if any(re.search(rf"\b{re.escape(v)}\b", lower) for v in _BUILD_VERBS_EN):
        return True
    # 地标直接命名：「埃菲尔铁塔」「Temple of Heaven」
    if re.search(r"[\u4e00-\u9fff]{2,20}(?:建筑|大楼|塔|宫|庙|寺|馆|院|楼)", t):
        return True
    if re.search(r"\b(?:tower|cathedral|palace|temple|stadium|museum|bridge)\b", lower):
        return True
    return False


def _strip_build_prefix(subject: str) -> str:
    s = (subject or "").strip()
    for verb in _BUILD_VERBS_CN:
        if s.startswith(verb):
            s = s[len(verb):].strip()
    for verb in _BUILD_VERBS_EN:
        pat = rf"^{re.escape(verb)}\s+(?:a|an|the|me\s+)?"
        s = re.sub(pat, "", s, flags=re.IGNORECASE).strip()
    # 去掉尺寸、数量等修饰
    s = re.sub(r"^\d+\s*[×xX*]\s*\d+.*?(?=[\u4e00-\u9fff])", "", s)
    s = re.sub(r"^(?:一座|一个|一栋|一间|one|a|an|the)\s*", "", s, flags=re.IGNORECASE)
    s = re.sub(r"[，。,.!！?？]+$", "", s)
    return s.strip()


def _is_edit_or_patch_prompt(text: str) -> bool:
    t = (text or "").strip()
    if not t:
        return False
    lower = t.lower()
    return any(h in t or h in lower for h in _NON_BUILD_HINTS)


def _extract_subject(text: str) -> Optional[str]:
    """从用户话术中提取建筑主体名称。"""
    t = (text or "").strip()
    if not t or _is_edit_or_patch_prompt(t):
        return None

    patterns = [
        r"(?:建造|打造|生成|制作|复原|还原|复刻|建|盖|造|修|搭|仿|照)\s*(?:一个|一座|一栋|一间)?\s*(.{2,48}?)(?:[，。,.!！?？]|$)",
        r"(?:build|recreate|reconstruct|make|construct|replicate|restore|model)\s+(?:a|an|the|me\s+)?(.{2,60}?)(?:[,.!?]|$)",
    ]
    for pat in patterns:
        m = re.search(pat, t, re.IGNORECASE)
        if m:
            subj = _strip_build_prefix(m.group(1))
            if len(subj) >= 2:
                return subj

    # 整句作为主体（短 prompt，且含建筑类型词）
    if len(t) <= 48 and not any(c in t for c in "，。,.!?"):
        if re.search(r"[\u4e00-\u9fff]{2,20}(?:建筑|大楼|塔|宫|庙|寺|馆|院|楼)", t):
            return _strip_build_prefix(t)
        if re.search(r"\b(?:tower|cathedral|palace|temple|stadium|museum|bridge)\b", t, re.IGNORECASE):
            return _strip_build_prefix(t)
    return None


def plan_search_queries(
    user_text: str,
    *,
    has_references: bool = False,
) -> Tuple[bool, List[str], str]:
    """
    QueryPlanner（规则版，零 LLM 延迟）。

    Returns:
        (should_search, queries, primary_subject)
    """
    text = (user_text or "").strip()
    mode = research_mode()
    if mode == "off" or _is_edit_or_patch_prompt(text):
        return False, [], ""
    if not text and not has_references:
        return False, [], ""

    extracted = _extract_subject(text) if text else None
    subject = extracted or (text[:60] if text else "reference building")
    has_intent = _has_build_intent(text) if text else False

    if not has_intent and not extracted:
        if has_references:
            # 仅图片/链接参考：仍触发开放世界研究
            pass
        else:
            return False, [], subject

    if mode == "named_only" and not extracted:
        return False, [], subject

    queries: List[str] = []
    if any(ord(c) > 127 for c in subject):
        queries.append(f"{subject} 建筑结构 设计特点 尺寸")
        queries.append(f"{subject} architecture structure design")
    else:
        queries.append(f"{subject} architecture structure design characteristics dimensions")
        queries.append(f"{subject} building features floor plan")

    # 去重、截断
    seen: set[str] = set()
    out: List[str] = []
    for q in queries:
        qn = q.strip().lower()
        if qn and qn not in seen:
            seen.add(qn)
            out.append(q.strip())
    return True, out[: _building_research_max_queries()], subject


def multi_source_search(
    queries: List[str],
    *,
    max_results_per_query: int = 3,
    search_fn: Optional[Callable[[str, int], List[Dict[str, str]]]] = None,
) -> List[Dict[str, str]]:
    """并行语义的多 query 搜索，合并去重。"""
    if search_fn is None:
        from .architecture_researcher import search_architecture_reference

        search_fn = search_architecture_reference

    merged: List[Dict[str, str]] = []
    seen_urls: set[str] = set()
    seen_snippets: set[str] = set()

    per_q = max(1, max_results_per_query)
    for query in queries:
        try:
            batch = search_fn(query, per_q) or []
        except Exception as e:
            logger.warning("Search failed for query %r: %s", query, e)
            batch = []
        for item in batch:
            url = (item.get("url") or "").strip()
            snippet = (item.get("snippet") or "").strip()[:200]
            key = url or snippet
            if not key or key in seen_urls or snippet in seen_snippets:
                continue
            if url:
                seen_urls.add(url)
            if snippet:
                seen_snippets.add(snippet)
            merged.append(item)
            if len(merged) >= _building_research_max_results():
                return merged
    return merged


def _extract_features_from_text(text: str) -> List[str]:
    lower = text.lower()
    found: List[str] = []
    for kw in _FEATURE_KEYWORDS:
        if kw.lower() in lower or kw in text:
            found.append(kw)
    return found[:8]


def _infer_scale_from_text(text: str) -> Dict[str, Optional[int]]:
    """从摘要中粗略推断尺度（方块数）。"""
    hints: Dict[str, Optional[int]] = {
        "typical_width_blocks": None,
        "typical_depth_blocks": None,
        "typical_height_blocks": None,
    }
    # 米 → 方块（1:1 粗略）
    m = re.search(r"(\d{2,4})\s*(?:m|meters|米)", text, re.IGNORECASE)
    if m:
        val = min(int(m.group(1)), 256)
        hints["typical_width_blocks"] = val
        hints["typical_depth_blocks"] = val
    m_h = re.search(r"(\d{1,3})\s*(?:floors|stories|层)", text, re.IGNORECASE)
    if m_h:
        hints["typical_height_blocks"] = min(int(m_h.group(1)) * 4, 128)
    return hints


def synthesize_profile_rule_based(
    subject: str,
    user_text: str,
    search_results: List[Dict[str, str]],
) -> BuildingProfile:
    """无 LLM 的规则归纳；搜索失败时仍产出最小可用 profile。"""
    combined = " ".join(
        f"{r.get('title', '')} {r.get('snippet', '')}" for r in search_results
    ).strip()
    features = _extract_features_from_text(combined or user_text)
    scale = _infer_scale_from_text(combined)

    confidence = 0.75 if search_results else 0.35
    if not subject and not search_results:
        confidence = 0.2

    sources = [
        {"title": r.get("title") or "", "url": r.get("url") or ""}
        for r in search_results
    ]

    notes = combined[:1200] if combined else user_text[:400]

    return BuildingProfile(
        query=subject or user_text[:60],
        identity={
            "name": subject or "unknown",
            "confidence": confidence,
        },
        form={
            "footprint": "rectangular",
            "massing": [],
            "stories": None,
        },
        structure={
            "roof_types": [],
            "facade": [],
            "distinctive_elements": features,
        },
        scale_hints=scale,
        minecraft_strategy={
            "skeleton_type": "COMPOUND",
            "recommended_components": [
                "MASS_MAIN", "ROOF", "FACADE_WINDOWS", "ENTRANCE",
            ],
            "landmark_module": None,
            "notes": (
                "Use parametric components; approximate distinctive elements from research_notes."
                if features
                else "No web results; rely on LLM pretrained knowledge and user description."
            ),
        },
        sources=sources,
        research_notes=notes or None,
    )


def synthesize_profile_with_llm(
    client: Any,
    model: str,
    subject: str,
    user_text: str,
    search_results: List[Dict[str, str]],
    *,
    timeout_sec: float = 15.0,
    call_with_timeout: Optional[Callable] = None,
) -> Optional[BuildingProfile]:
    """可选 LLM 归纳（BUILDING_RESEARCH_LLM_SYNTH=on）。"""
    if client is None:
        return None

    snippets_block = "\n".join(
        f"- [{r.get('title', 'Ref')}] {r.get('snippet', '')[:400]}"
        for r in search_results[:4]
    ) or "(no search results)"

    system = (
        "You summarize architecture research into a compact BuildingProfile JSON object. "
        "Output ONLY valid JSON matching keys: query, identity{name,architect,year,style,confidence}, "
        "form{footprint,massing,stories,aspect_ratio}, "
        "structure{roof_types,facade,distinctive_elements}, "
        "scale_hints{typical_width_blocks,typical_depth_blocks,typical_height_blocks}, "
        "minecraft_strategy{skeleton_type,recommended_components,landmark_module,notes}, "
        "research_notes. "
        "recommended_components must use only: MASS_MAIN, ROOF, FACADE_WINDOWS, ENTRANCE, "
        "TOWER, WALL, COURTYARD_SPACE, MODULE, TERRACE. "
        "landmark_module is usually null unless a well-known preset applies."
    )
    user = (
        f"User request: {user_text}\n"
        f"Primary subject: {subject}\n\n"
        f"Search snippets:\n{snippets_block}\n\n"
        "Produce BuildingProfile JSON."
    )

    def _call():
        return client.chat.completions.create(
            model=model,
            messages=[
                {"role": "system", "content": system},
                {"role": "user", "content": user},
            ],
            response_format={"type": "json_object"},
            temperature=0.2,
        )

    try:
        if call_with_timeout:
            response = call_with_timeout(_call, timeout_sec)
        else:
            response = _call()
        raw = response.choices[0].message.content
        data = json.loads(raw) if isinstance(raw, str) else {}
        profile = profile_from_llm_dict(data, fallback_query=subject)
        if search_results and not profile.sources:
            profile.sources = [
                {"title": r.get("title") or "", "url": r.get("url") or ""}
                for r in search_results
            ]
        return profile
    except Exception as e:
        logger.warning("LLM profile synthesis failed: %s", e)
        return None


def format_profile_for_prompt(profile: BuildingProfile) -> str:
    """将 BuildingProfile 格式化为 LlmPlan prompt 前置上下文。"""
    payload = profile.to_prompt_dict()
    lines = [
        "=== Building Research Profile (open-world) ===",
        "",
        "The following profile was synthesized from web research and the user request.",
        "Use it to inform accurate LlmPlan generation. Prefer parametric components",
        "from minecraft_strategy.recommended_components unless landmark_module is set.",
        "",
        "BuildingProfile(JSON):",
        json.dumps(payload, ensure_ascii=False, indent=2),
        "",
        "Planning rules:",
        "- Respect scale_hints when choosing dimensions (blocks).",
        "- Include distinctive_elements in component params/features where possible.",
        "- Do NOT invent unregistered component_type values.",
        "- If research_notes conflict with user text, prefer user text for intent.",
        "",
    ]
    return "\n".join(lines)


def research_building_profile(
    user_text: str,
    *,
    req: Optional[BuildRequest] = None,
    references: Optional[List[ReferenceInput]] = None,
    call_with_timeout: Optional[Callable] = None,
    search_fn: Optional[Callable[[str, int], List[Dict[str, str]]]] = None,
) -> Optional[BuildingProfile]:
    """
    完整 Research 流程；失败返回 None（不阻塞 plan 生成）。
    PR-4: references[] 触发 vision 分析并 merge 进 profile。
    """
    refs: List[ReferenceInput] = list(references or [])
    if not refs and req is not None and getattr(req, "references", None):
        refs = list(req.references or [])
    has_refs = bool(refs)

    if not is_building_research_enabled() and not has_refs:
        return None

    should_search, queries, subject = plan_search_queries(
        user_text, has_references=has_refs,
    )
    if not should_search and not has_refs:
        logger.debug("Building research skipped: no search intent for %r", user_text[:80])
        return None

    def _run() -> BuildingProfile:
        nonlocal subject
        visual = None
        if has_refs:
            try:
                from .vision_analyzer import analyze_references, merge_visual_into_profile

                visual = analyze_references(
                    refs,
                    user_text,
                    req=req,
                    call_with_timeout=call_with_timeout,
                )
                if visual and visual.building_name:
                    subject = visual.building_name
            except Exception as e:
                logger.warning("Vision reference analysis failed: %s", e)

        results: List[Dict[str, str]] = []
        if should_search and queries:
            results = multi_source_search(queries, search_fn=search_fn)

        profile: Optional[BuildingProfile] = None

        if _building_research_llm_synth() and req is not None and should_search:
            try:
                from .llm_client import get_client, build_config

                client = get_client(req)
                cfg = build_config(req, default_model="gpt-4o-mini")
                if client:
                    profile = synthesize_profile_with_llm(
                        client,
                        cfg.model,
                        subject,
                        user_text,
                        results,
                        call_with_timeout=call_with_timeout,
                    )
            except Exception as e:
                logger.warning("LLM synth path unavailable: %s", e)

        if profile is None:
            profile = synthesize_profile_rule_based(subject, user_text, results)

        if visual is not None:
            profile = merge_visual_into_profile(profile, visual)

        logger.info(
            "Building research: subject=%r queries=%d results=%d refs=%d confidence=%.2f",
            subject,
            len(queries),
            len(results),
            len(refs),
            profile.identity.confidence,
        )
        return profile

    try:
        if call_with_timeout:
            return call_with_timeout(_run, _building_research_timeout_sec())
        return _run()
    except TimeoutError:
        logger.warning(
            "Building research timed out after %ss; skipping",
            _building_research_timeout_sec(),
        )
        return None
    except Exception as e:
        logger.warning("Building research failed: %s", e)
        return None


def research_building_context(
    user_text: str,
    *,
    req: Optional[BuildRequest] = None,
    call_with_timeout: Optional[Callable] = None,
    search_fn: Optional[Callable[[str, int], List[Dict[str, str]]]] = None,
) -> Optional[str]:
    """Research 流程 → prompt 字符串；供 ai_planner 注入。"""
    profile = research_building_profile(
        user_text,
        req=req,
        call_with_timeout=call_with_timeout,
        search_fn=search_fn,
    )
    if profile is None:
        return None
    return format_profile_for_prompt(profile)
