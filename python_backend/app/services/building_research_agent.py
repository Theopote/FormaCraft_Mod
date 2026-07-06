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

from ..models.building_profile import BuildingProfile, profile_from_llm_dict, RequestClassification
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
    return _env_float("BUILDING_RESEARCH_TIMEOUT_SEC", 15.0)


def _building_research_llm_synth() -> bool:
    raw = _env_str("BUILDING_RESEARCH_LLM_SYNTH", "on")
    return raw not in ("off", "0", "false", "no")


def _building_research_max_queries() -> int:
    return max(1, _env_int("BUILDING_RESEARCH_MAX_QUERIES", 3))


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
    "流动", "曲面", "参数化", "流线型", "deconstruct", "mesh", "网格",
)

# 「X设计的Y」解析 + 建筑师/建筑别名（检索词扩展，非硬编码生成）
_DESIGN_BY_RE = re.compile(
    r"(.{1,24}?)(?:设计|作品|风格|操刀)?的(.{2,40}?)(?:[，。,.!！?？]|$)"
)

_ARCHITECT_HINTS: Dict[str, Dict[str, str]] = {
    "扎哈": {
        "architect": "Zaha Hadid",
        "style": "Deconstructivism",
        "style_profile": "Deconstructivism_Zaha",
    },
    "zaha": {
        "architect": "Zaha Hadid",
        "style": "Deconstructivism",
        "style_profile": "Deconstructivism_Zaha",
    },
    "哈迪德": {
        "architect": "Zaha Hadid",
        "style": "Deconstructivism",
        "style_profile": "Deconstructivism_Zaha",
    },
    "贝聿铭": {
        "architect": "I.M. Pei",
        "style": "Modernism",
        "style_profile": "Modern_Classical",
    },
    "安藤忠雄": {
        "architect": "Tadao Ando",
        "style": "Minimalist concrete",
        "style_profile": "Modern_Minimal",
    },
}

_BUILDING_SEARCH_ALIASES: Dict[str, List[str]] = {
    "西安体育馆": ["西安奥体中心", "西安奥林匹克体育中心"],
    "西安体育场": ["西安奥体中心", "西安奥林匹克体育中心"],
    "国家体育场": ["鸟巢", "北京国家体育场"],
    "圣家族大教堂": ["Sagrada Família", "Basílica de la Sagrada Família", "Barcelona"],
    "圣家堂": ["Sagrada Família", "圣家族大教堂"],
    "巴黎圣母院": ["Notre-Dame de Paris", "Cathédrale Notre-Dame de Paris"],
    "科隆大教堂": ["Cologne Cathedral", "Kölner Dom"],
    "沙特尔": ["Chartres Cathedral", "Cathédrale Notre-Dame de Chartres"],
    "悉尼歌剧院": ["Sydney Opera House", "Jørn Utzon"],
    "卢浮宫": ["Louvre Museum", "Musée du Louvre", "Louvre Pyramid"],
    "苏州博物馆": ["Suzhou Museum", "I.M. Pei"],
    "故宫": ["Forbidden City", "Palace Museum Beijing", "紫禁城"],
    "泰姬陵": ["Taj Mahal", "Agra"],
    "白宫": ["White House", "Washington DC"],
    "古根海姆博物馆": ["Guggenheim Museum Bilbao", "Frank Gehry"],
    "毕尔巴鄂古根海姆": ["Guggenheim Bilbao", "Frank Gehry"],
    "哈利法塔": ["Burj Khalifa", "Dubai"],
    "伏见稻荷神社": ["Fushimi Inari Taisha", "Kyoto", "千本鸟居"],
    "伏见稻荷": ["Fushimi Inari", "千本鸟居"],
    "姬路城": ["Himeji Castle", "White Heron Castle"],
    "乌镇": ["Wuzhen", "江南古镇"],
    "周庄": ["Zhouzhuang", "江南古镇"],
    "迪士尼城堡": ["Disney Castle", "Cinderella Castle"],
    "法门寺": ["Famen Temple", "Famensi", "Shaanxi"],
    "法门寺舍利塔": ["Famen Temple Pagoda", "Famen Stupa", "Relic Pagoda"],
    "佛光寺": ["Foguang Temple", "Wutai Mountain", "East Hall"],
    "佛光寺大殿": ["Foguang Temple Main Hall", "East Main Hall", "Tang timber hall"],
}

_ZAHA_FORM_ELEMENTS = (
    "flowing curves", "organic form", "parametric facade",
    "ribbon glazing", "deconstructivist massing", "流线型曲面", "流动形态", "参数化立面",
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


def _detect_architect_hint(text: str) -> Optional[Dict[str, str]]:
    lower = (text or "").lower()
    for key, hint in _ARCHITECT_HINTS.items():
        if key in lower or key in (text or ""):
            return dict(hint)
    return None


def _parse_designer_building(text: str) -> Tuple[Optional[str], Optional[str]]:
    """解析「扎哈设计的西安体育馆」→ (architect fragment, building fragment)。"""
    t = (text or "").strip()
    m = _DESIGN_BY_RE.search(t)
    if not m:
        return None, None
    designer = m.group(1).strip()
    building = m.group(2).strip()
    if len(building) < 2:
        return None, None
    return designer, building


def _building_search_names(subject: str, user_text: str) -> List[str]:
    """主体名 + 别名（别名优先，便于 Wikipedia 命中）。"""
    names: List[str] = []
    for src in (subject, user_text):
        s = (src or "").strip()
        if s and s not in names:
            names.append(s)
    _, building = _parse_designer_building(user_text)
    if building and building not in names:
        names.append(building)

    prioritized: List[str] = []
    for name in list(names):
        for key, aliases in _BUILDING_SEARCH_ALIASES.items():
            if key in name:
                for alias in aliases:
                    if alias not in prioritized:
                        prioritized.append(alias)
    for name in names:
        if name not in prioritized:
            prioritized.append(name)
    return prioritized[:4]


def _expand_search_queries(subject: str, user_text: str) -> List[str]:
    """在 plan_search_queries 基础上追加高价值检索词（短建筑名/别名优先）。"""
    queries: List[str] = []
    names = _building_search_names(subject, user_text)
    architect_hint = _detect_architect_hint(user_text)

    search_names: List[str] = []
    for name in names:
        if _DESIGN_BY_RE.search(name):
            continue
        if name not in search_names:
            search_names.append(name)

    for name in search_names[:3]:
        if any(ord(c) > 127 for c in name):
            queries.append(f"{name} 建筑 结构 设计")
        else:
            queries.append(f"{name} architecture structure design")

    if architect_hint:
        arch = architect_hint["architect"]
        building = names[0] if names else subject
        if building:
            queries.append(f"{arch} {building} stadium architecture")
        queries.append(f"{arch} architecture style deconstructivism")

    designer, building = _parse_designer_building(user_text)
    if designer and building:
        hint = _detect_architect_hint(designer)
        if hint and building:
            queries.append(f"{hint['architect']} {building} architecture")

    # 去重
    seen: set[str] = set()
    out: List[str] = []
    for q in queries:
        qn = q.strip().lower()
        if qn and qn not in seen:
            seen.add(qn)
            out.append(q.strip())
    return out


def _enrich_profile_from_user_text(profile: BuildingProfile, user_text: str) -> BuildingProfile:
    """搜索失败或不足时，从用户话术注入建筑师/风格/形体约束。"""
    hint = _detect_architect_hint(user_text)
    if not hint:
        return profile

    identity = profile.identity.model_dump()
    if hint.get("architect"):
        identity["architect"] = hint["architect"]
    if hint.get("style"):
        identity["style"] = hint["style"]
    if profile.identity.confidence < 0.55:
        identity["confidence"] = 0.55

    structure = profile.structure.model_dump()
    elements = list(structure.get("distinctive_elements") or [])
    for el in _ZAHA_FORM_ELEMENTS:
        if el not in elements:
            elements.append(el)
    structure["distinctive_elements"] = elements[:10]

    form = profile.form.model_dump()
    if hint.get("style") == "Deconstructivism":
        form["footprint"] = "freeform"
        form["massing"] = list(form.get("massing") or []) + ["curved", "organic"]

    mc = profile.minecraft_strategy.model_dump()
    mc["landmark_module"] = None
    mc["skeleton_type"] = "RADIAL_RING"
    mc["recommended_components"] = [
        "MASS_MAIN", "MASS_SECONDARY", "ROOF", "FACADE_WINDOWS", "ENTRANCE", "PAVING",
    ]
    style_profile = hint.get("style_profile") or ""
    mc["notes"] = (
        f"Architect-led design ({hint.get('architect')}): use style_profile={style_profile}, "
        "compositional curved MASS with params.shape=ellipse/rounded_rect, params.curvature; "
        "Do NOT use landmark:birds_nest_stadium (wrong architect/landmark)."
    )

    notes = profile.research_notes or user_text[:400]
    if hint.get("architect") and hint["architect"] not in notes:
        notes = f"[Architect intent] {hint['architect']} — {hint.get('style', '')}. {notes}"

    return profile.model_copy(update={
        "identity": profile.identity.model_copy(update=identity),
        "form": profile.form.model_copy(update=form),
        "structure": profile.structure.model_copy(update=structure),
        "minecraft_strategy": profile.minecraft_strategy.model_copy(update=mc),
        "research_notes": notes[:1200],
    })


def resolve_landmark_module_for_intent(user_text: str) -> Optional[str]:
    """
    Return a preset landmark module id only when user intent explicitly matches
    a registered archetype proper-noun alias (not generic typology alone).
    """
    text = (user_text or "").strip()
    if not text:
        return None
    try:
        from .archetype_detector import detect_archetype_local

        match = detect_archetype_local(text)
        if match is None or not match.qualifies_for_module_route():
            return None
        from .archetype_registry import get_archetype_def

        defn = get_archetype_def(match.id)
        if defn is None or defn.research_only or not defn.generator_id:
            return None
        return match.id
    except Exception:
        return None


def _compute_fidelity_message(
    profile: BuildingProfile,
    user_text: str,
    resolved_module: Optional[str],
) -> Tuple[str, str]:
    """Return (fidelity_tier, fidelity_message_zh) for player-facing transparency."""
    name = (profile.identity.name or "").strip() or (user_text or "")[:40]
    from .archetype_detector import detect_archetype_local
    from .archetype_registry import get_archetype_def
    from .landmark_alias_matcher import is_approximate_landmark_match

    if resolved_module:
        arch = get_archetype_def(resolved_module)
        if arch and is_approximate_landmark_match(user_text, resolved_module, arch):
            return (
                "style_approximation",
                f"识别为「{name}」，目录无精确模板，已使用近似风格模块 {resolved_module}（保真度：低）",
            )
        match = detect_archetype_local(user_text)
        if match and match.qualifies_for_module_route():
            return (
                "preset_module",
                f"识别为预置地标「{resolved_module}」，使用固定模板生成（保真度：取决于模块与真实建筑吻合度）",
            )
        return (
            "style_approximation",
            f"使用风格模块 {resolved_module}（保真度：低，可能缺少该建筑标志性细节）",
        )

    rc = profile.request_classification
    if rc and not rc.is_specific_real_building:
        return (
            "compositional_generic",
            f"组合式生成（{name or '风格化描述'}；保真度：风格近似，非精确复刻）",
        )

    distinguishing = profile.structure.distinguishing_features or profile.structure.distinctive_elements
    if profile.identity.confidence >= 0.55 and distinguishing:
        feats = "、".join(distinguishing[:3])
        return (
            "researched_compositional",
            f"识别为真实建筑「{name}」，无专属模板，已依据研究资料组合式重建（保真度：中等；重点特征：{feats}）",
        )
    if name and len(name) >= 2:
        return (
            "compositional_generic",
            f"组合式生成「{name}」（保真度：风格化，非精确复刻；建议补充参考资料以提高保真度）",
        )
    return (
        "compositional_generic",
        "组合式生成（保真度：风格化，非精确复刻）",
    )


def resolve_structural_typology_for_intent(user_text: str) -> Optional[str]:
    """Return structural typology id when culture/RAG/keyword resolution succeeds."""
    text = (user_text or "").strip()
    if not text:
        return None
    try:
        from .keyword_culture_retriever import retrieve
        from .typology_retriever import resolve_typology_for_intent

        rag = retrieve(text, topK=1, fewShotK=0)
        stid = rag.get("structuralTypologyId")
        if isinstance(stid, str) and stid.strip():
            return stid.strip()
        hits = rag.get("hits") or []
        culture_id = hits[0].get("id") if hits else None
        match = resolve_typology_for_intent(text, culture_card_id=culture_id)
        return match.typology_id if match else None
    except Exception:
        return None


def finalize_profile_minecraft_strategy(
    profile: BuildingProfile,
    user_text: str,
    *,
    classification: Optional[RequestClassification] = None,
) -> BuildingProfile:
    """
    Authoritative minecraft_strategy after research:
    - structural_typology resolved → typology-first STRUCTURE; landmark_module=null
    - non-migrated preset landmark (MODULE whitelist) → landmark_module + MODULE
    - everything else → landmark_module=null + compositional components
    """
    if classification is not None:
        profile = profile.model_copy(update={"request_classification": classification})
    elif profile.request_classification is None:
        from .building_request_classifier import (
            classify_building_request_local,
            is_request_classification_enabled,
        )

        if is_request_classification_enabled():
            profile = profile.model_copy(
                update={
                    "request_classification": classify_building_request_local(user_text),
                }
            )

    mc = profile.minecraft_strategy.model_copy()
    typology_id = resolve_structural_typology_for_intent(user_text)
    resolved = resolve_landmark_module_for_intent(user_text)
    if typology_id:
        from .typology_registry import get_typology

        defn = get_typology(typology_id)
        mc.structural_typology = typology_id
        try:
            from .keyword_culture_retriever import retrieve
            from .typology_retriever import resolve_typology_for_intent

            rag = retrieve(user_text, topK=1, fewShotK=0)
            hits = rag.get("hits") or []
            culture_id = hits[0].get("id") if hits else None
            match = resolve_typology_for_intent(user_text, culture_card_id=culture_id)
            if match and match.reference_landmark_id:
                mc.reference_landmark = match.reference_landmark_id
        except Exception:
            pass
        if defn:
            mc.skeleton_type = defn.skeleton_type
        mc.landmark_module = None
        components = [
            str(c).upper()
            for c in (mc.recommended_components or [])
            if str(c).upper() != "MODULE"
        ]
        if not components:
            components = ["STRUCTURE", "MASS_MAIN", "ROOF", "FACADE_WINDOWS", "ENTRANCE"]
        elif "STRUCTURE" not in components:
            components = ["STRUCTURE"] + components
        mc.recommended_components = components
        note = (mc.notes or "").strip()
        mc.notes = (
            f"structural_typology={typology_id}: use typology params in LlmPlan; "
            f"landmark_module=null (reference_landmark={mc.reference_landmark or 'none'}). "
            + note
        ).strip()
    elif resolved:
        mc.landmark_module = resolved
        if "MODULE" not in [str(c).upper() for c in (mc.recommended_components or [])]:
            mc.recommended_components = ["MODULE"] + list(mc.recommended_components or [])
        note = (mc.notes or "").strip()
        if resolved not in note:
            mc.notes = (
                f"Preset landmark module available: use MODULE with landmark:{resolved}. "
                + note
            ).strip()
    else:
        mc.landmark_module = None
        components = [
            str(c).upper()
            for c in (mc.recommended_components or [])
            if str(c).upper() != "MODULE"
        ]
        if not components:
            components = ["MASS_MAIN", "ROOF", "FACADE_WINDOWS", "ENTRANCE"]
        mc.recommended_components = components
        note = (mc.notes or "").strip()
        if "landmark_module is null" not in note.lower():
            mc.notes = (
                "landmark_module is null: compose with recommended_components only; "
                "IGNORE Java prompt landmark MODULE routing hints. "
                + note
            ).strip()
    profile = profile.model_copy(update={"minecraft_strategy": mc})
    try:
        from .research_landmark_seeds import apply_research_landmark_seed

        profile, seed_id = apply_research_landmark_seed(profile, user_text)
        if seed_id:
            logger.info("Applied research landmark seed for %r", seed_id)
    except Exception as exc:
        logger.warning("Research landmark seed skipped: %s", exc)
    tier, msg = _compute_fidelity_message(profile, user_text, resolved)
    profile = profile.model_copy(update={"fidelity_tier": tier, "fidelity_message_zh": msg})
    try:
        from .plan_architectural_enrichment import enrich_profile_architectural_detail

        profile = enrich_profile_architectural_detail(profile, user_text)
    except Exception as exc:
        logger.warning("Profile architectural enrichment skipped: %s", exc)
    return profile


def ensure_building_profile_for_plan(
    user_text: str,
    profile: Optional[BuildingProfile],
) -> Optional[BuildingProfile]:
    """
    Guarantee a profile object for open-world plan normalization when research
    was skipped or timed out — never block generation for unknown buildings.
    """
    text = (user_text or "").strip()
    if profile is not None:
        return finalize_profile_minecraft_strategy(profile, text)
    if not text:
        return None

    from .building_request_classifier import (
        classify_building_request_local,
        is_request_classification_enabled,
    )

    if is_request_classification_enabled():
        classification = classify_building_request_local(text)
        if not classification.is_specific_real_building:
            logger.debug(
                "Skipping stub profile for generic typology: %r (%s)",
                text[:80],
                classification.reasoning_hint,
            )
            return None

    subject = _extract_subject(text) or text[:60]
    stub = synthesize_profile_rule_based(subject, text, [])
    if subject and subject != stub.identity.name:
        stub = stub.model_copy(
            update={"identity": stub.identity.model_copy(update={"name": subject})}
        )
    return finalize_profile_minecraft_strategy(stub, text)


def plan_search_queries(
    user_text: str,
    *,
    has_references: bool = False,
    classification: Optional[Any] = None,
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

    from .building_request_classifier import (
        classify_building_request_local,
        is_request_classification_enabled,
    )

    if classification is None and is_request_classification_enabled():
        classification = classify_building_request_local(text)

    if classification is not None:
        from .building_request_classifier import should_research_for_classification

        if not should_research_for_classification(classification, has_references=has_references):
            return False, [], classification.building_name_normalized or ""

    extracted = _extract_subject(text) if text else None
    subject = (
        (getattr(classification, "building_name_normalized", None) or "").strip()
        or extracted
        or (text[:60] if text else "reference building")
    )
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
    expanded = _expand_search_queries(subject, text)
    if expanded:
        queries.extend(expanded)
    elif any(ord(c) > 127 for c in subject):
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
            try:
                from .architecture_researcher import is_relevant_architecture_result
                if not is_relevant_architecture_result(item):
                    continue
            except Exception:
                pass
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
    visual_notes: Optional[str] = None,
    reference_blueprint: Optional[Dict[str, Any]] = None,
    timeout_sec: float = 15.0,
    call_with_timeout: Optional[Callable] = None,
) -> Optional[BuildingProfile]:
    """LLM 归纳（BUILDING_RESEARCH_LLM_SYNTH=on，默认开启）。"""
    if client is None:
        return None

    snippets_block = "\n".join(
        f"- [{r.get('title', 'Ref')}] {r.get('snippet', '')[:400]}"
        for r in search_results[:4]
    ) or "(no search results)"

    system = (
        "You summarize architecture research into a compact BuildingProfile JSON object. "
        "Use ONLY facts from search snippets, user request, and optional reference_blueprint. "
        "Do NOT invent architects or dimensions not supported by evidence. "
        "Output ONLY valid JSON matching keys: query, identity{name,architect,year,style,confidence}, "
        "form{footprint,massing,stories,aspect_ratio}, "
        "structure{roof_types,facade,distinctive_elements,distinguishing_features}, "
        "scale_hints{typical_width_blocks,typical_depth_blocks,typical_height_blocks}, "
        "minecraft_strategy{skeleton_type,structural_typology,reference_landmark,recommended_components,landmark_module,notes}, "
        "research_notes, reference_blueprint (optional, same schema as vision ReferenceBlueprint). "
        "recommended_components must use only: STRUCTURE, MASS_MAIN, ROOF, FACADE_WINDOWS, ENTRANCE, "
        "TOWER, WALL, COURTYARD_SPACE, MODULE, TERRACE. "
        "landmark_module is usually null. When the user names a Chinese pagoda/hall/temple with a known "
        "structural typology, set structural_typology (e.g. dense_eaves_pagoda, tailiang_timber_hall, "
        "radial_terrace_hall) and reference_landmark; keep landmark_module=null. "
        "NEVER set landmark_module for famen_pagoda, giant_wild_goose_pagoda, foguang_temple_hall, "
        "temple_of_heaven, birds_nest_stadium, golden_gate_bridge, gothic_cathedral, or mingqing_courtyard — they are typology-first only. "
        "landmark_module only for explicit non-migrated presets (e.g. "
        "great_wall, eiffel_tower, castle_compound). "
        "For Louvre, White House, Sydney Opera House, Sagrada Família, etc., set landmark_module=null and "
        "list compositional recommended_components. "
        "distinguishing_features (REQUIRED when identity.name is a specific real building): "
        "3-5 traits that make THIS building recognizable vs any generic building of the same style "
        "(e.g. Sagrada: organic hyperboloid towers, tree-like branching columns, mosaic facade — "
        "NOT generic gothic flying buttresses). "
        "Put the same items in distinctive_elements for backward compatibility."
    )
    user = (
        f"User request: {user_text}\n"
        f"Primary subject: {subject}\n\n"
        f"Search snippets:\n{snippets_block}\n"
    )
    if reference_blueprint:
        user += (
            "\nReferenceBlueprint(JSON) from vision — preserve and pass through in output:\n"
            + json.dumps(reference_blueprint, ensure_ascii=False)[:6000]
            + "\n"
        )
    elif visual_notes:
        user += f"\nVisual analysis notes:\n{visual_notes[:1500]}\n"
    user += "\nProduce BuildingProfile JSON."

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
        "Use it to inform accurate LlmPlan generation.",
        "",
        "CRITICAL routing (typology-first):",
        "- When minecraft_strategy.structural_typology is set → output STRUCTURE with "
        "features [\"typology:<id>\"] and params.reference_landmark if provided; "
        "landmark_module MUST stay null.",
        "- Migrated landmarks (famen_pagoda, giant_wild_goose_pagoda, foguang_temple_hall, "
        "temple_of_heaven, birds_nest_stadium, golden_gate_bridge, gothic_cathedral, mingqing_courtyard) NEVER use MODULE — even if Java LANDMARK MODULE blocks suggest it.",
        "- If landmark_module is null → compositional and/or STRUCTURE typology; do NOT use MODULE.",
        "- MODULE allowed ONLY when landmark_module is a non-migrated preset explicitly set in this "
        "profile (e.g. great_wall, eiffel_tower, castle_compound).",
        "- Set proportion_hints.typology to structural_typology when present.",
        "- IGNORE any Java prompt 'LANDMARK MODULE ROUTING' sections that conflict with this profile.",
        "",
        "CRITICAL distinguishing features (override generic style keywords):",
        "- structure.distinguishing_features lists what makes THIS building unique.",
        "- MUST appear in component features/params — priority above generic gothic/modern keywords.",
        "",
    ]
    if profile.request_classification is not None:
        lines.extend([
            "Request classification (Stage 1 — authoritative for research vs generic intent):",
            json.dumps(
                profile.request_classification.model_dump(exclude_none=True),
                ensure_ascii=False,
                indent=2,
            ),
            "",
        ])
    lines.extend([
        "BuildingProfile(JSON):",
        json.dumps(payload, ensure_ascii=False, indent=2),
        "",
    ])
    if profile.fidelity_message_zh:
        lines.extend([
            "Fidelity (tell the player in plan notes / status):",
            profile.fidelity_message_zh,
            "",
        ])
    lines.extend([
        "Planning rules:",
        "- Respect scale_hints when choosing dimensions (blocks).",
        "- Include distinguishing_features in component params/features (highest priority).",
        "- Include distinctive_elements in component params/features where possible.",
        "- If reference_blueprint is present, use its architectural_layers, block_palette,",
        "  and generation_rules as the primary spatial/material guide for LlmPlan components.",
        "- Do NOT invent unregistered component_type values.",
        "- If research_notes conflict with user text, prefer user text for intent.",
        "",
    ])
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

    from .building_request_classifier import (
        classify_building_request,
        should_research_for_classification,
    )

    classification = classify_building_request(
        user_text,
        req=req,
        call_with_timeout=call_with_timeout,
    )

    if search_fn is None:
        if req is not None:
            try:
                from .search_config import make_search_fn

                search_fn = make_search_fn(req)
            except Exception:
                from .architecture_researcher import search_architecture_reference

                search_fn = search_architecture_reference
        else:
            from .architecture_researcher import search_architecture_reference

            search_fn = search_architecture_reference

    if not should_research_for_classification(classification, has_references=has_refs):
        logger.info(
            "Building research skipped: generic typology for %r (hint=%s, source=%s)",
            user_text[:80],
            classification.reasoning_hint,
            classification.source,
        )
        return None

    should_search, queries, subject = plan_search_queries(
        user_text,
        has_references=has_refs,
        classification=classification,
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

        has_full_blueprint = bool(
            visual is not None and visual.reference_blueprint is not None
        )
        profile: Optional[BuildingProfile] = None

        if (
            _building_research_llm_synth()
            and req is not None
            and not has_full_blueprint
            and (should_search or has_refs or (user_text or "").strip())
        ):
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
                        visual_notes=visual.notes if visual else None,
                        reference_blueprint=(
                            visual.reference_blueprint.to_prompt_dict()
                            if visual and visual.reference_blueprint
                            else None
                        ),
                        call_with_timeout=call_with_timeout,
                    )
            except Exception as e:
                logger.warning("LLM synth path unavailable: %s", e)

        if profile is None:
            profile = synthesize_profile_rule_based(subject, user_text, results)

        profile = _enrich_profile_from_user_text(profile, user_text)
        if classification.building_name_normalized and profile.identity.name in (
            "",
            "unknown",
            subject,
        ):
            profile = profile.model_copy(
                update={
                    "identity": profile.identity.model_copy(
                        update={"name": classification.building_name_normalized}
                    )
                }
            )
        profile = finalize_profile_minecraft_strategy(
            profile, user_text, classification=classification,
        )

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
