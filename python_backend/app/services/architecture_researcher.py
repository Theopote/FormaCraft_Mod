"""
Architecture Researcher Service
网络搜索服务：为强类型建筑获取参考资料
"""
import os
import re
import logging
from typing import Optional, List, Dict, TYPE_CHECKING
import requests
from urllib.parse import quote_plus

if TYPE_CHECKING:
    from .search_config import SearchRuntimeConfig

logger = logging.getLogger(__name__)

_WIKI_USER_AGENT = "FormaCraftResearch/1.0 (formacraft; architecture-research@local)"

# 非建筑类搜索结果域名（DuckDuckGo 偶发返回购物/金融页）
_JUNK_URL_FRAGMENTS = (
    "target.com", "amazon.", "ebay.", "moneyvox", "pinterest.com",
    "tripadvisor", "booking.com", "walmart.", "alibaba.com",
)

# 至少命中一项才视为建筑相关
_ARCHITECTURE_SIGNALS = (
    "architect", "architecture", "building", "stadium", "arena", "cathedral",
    "tower", "museum", "bridge", "facade", "structure", "design",
    "建筑", "设计", "体育场", "体育馆", "球场", "立面", "结构", "建筑师",
    "奥体", "奥运", "足球场", "wikipedia.org",
)

# 强类型建筑关键词列表（需要搜索参考资料的建筑类型）
LANDMARK_BUILDINGS = [
    "埃菲尔铁塔", "eiffel tower", "eiffel",
    "巴黎铁塔", "tower eiffel",
    "自由女神像", "statue of liberty",
    "大本钟", "big ben", "westminster",
    "悉尼歌剧院", "sydney opera house",
    "金字塔", "pyramid", "古埃及",
    "长城", "great wall", "中国长城",
    "天坛", "temple of heaven", "天坛公园",
    "故宫", "forbidden city", "紫禁城",
    "布达拉宫", "potala palace",
    "比萨斜塔", "leaning tower of pisa",
    "泰姬陵", "taj mahal",
    "罗马斗兽场", "colosseum", "coliseum",
    "白宫", "white house",
    "大教堂", "cathedral", "gothic cathedral",
    "哥特式", "gothic",
    "巴洛克", "baroque",
    "新古典", "neoclassical",
    "包豪斯", "bauhaus",
    "现代主义", "modernism", "modern architecture",
]

# 建筑风格关键词
ARCHITECTURE_STYLES = [
    "中式", "chinese architecture", "traditional chinese",
    "日式", "japanese architecture", "traditional japanese",
    "欧式", "european architecture",
    "希腊", "greek architecture",
    "罗马", "roman architecture",
    "拜占庭", "byzantine",
    "伊斯兰", "islamic architecture", "mosque",
    "印度", "indian architecture",
    "东南亚", "southeast asian",
]


def _detect_landmark_or_style(text: str, landmark_only: bool = False) -> Optional[str]:
    """
    检测文本中是否包含强类型建筑或建筑风格关键词
    返回匹配的关键词（用于搜索）

    landmark_only=True 时只匹配地标建筑（埃菲尔/故宫…），跳过风格词（中式/欧式…）。
    风格特征应由 style profile / prompt 表达，不值得为其付出联网搜索的延迟。
    """
    text_lower = text.lower()
    
    # 优先匹配地标建筑
    for keyword in LANDMARK_BUILDINGS:
        if keyword.lower() in text_lower:
            return keyword

    if landmark_only:
        return None

    # 然后匹配建筑风格
    for keyword in ARCHITECTURE_STYLES:
        if keyword.lower() in text_lower:
            return keyword
    
    return None


def search_architecture_queries(
    queries: List[str],
    max_results_per_query: int = 3,
) -> List[Dict[str, str]]:
    """
    多 query 搜索并合并去重（供 BuildingResearchAgent 调用）。
    """
    merged: List[Dict[str, str]] = []
    seen: set[str] = set()
    for query in queries:
        for item in search_architecture_reference(query, max_results_per_query):
            url = (item.get("url") or "").strip()
            snippet = (item.get("snippet") or "").strip()[:160]
            key = url or snippet
            if not key or key in seen:
                continue
            seen.add(key)
            merged.append(item)
    return merged


def is_relevant_architecture_result(item: Dict[str, str]) -> bool:
    """过滤购物/金融等无关网页；Wikipedia 与含建筑关键词的结果保留。"""
    title = (item.get("title") or "").strip()
    snippet = (item.get("snippet") or "").strip()
    url = (item.get("url") or "").strip().lower()
    if not title and not snippet:
        return False
    if url and any(j in url for j in _JUNK_URL_FRAGMENTS):
        return False
    blob = f"{title} {snippet} {url}".lower()
    if "wikipedia.org" in blob:
        return True
    return any(sig in blob for sig in _ARCHITECTURE_SIGNALS)


def google_cse_configured(cfg: "SearchRuntimeConfig | None" = None) -> bool:
    if cfg is not None:
        return bool((cfg.google_api_key or "").strip() and (cfg.google_cse_cx or "").strip())
    return bool(
        (os.getenv("GOOGLE_CSE_API_KEY") or "").strip()
        and (os.getenv("GOOGLE_CSE_CX") or "").strip()
    )


def _search_with_google_cse(
    query: str,
    max_results: int,
    cfg: "SearchRuntimeConfig | None" = None,
) -> List[Dict[str, str]]:
    """Google Custom Search JSON API（需 API key + CX）。"""
    if cfg is not None:
        api_key = (cfg.google_api_key or "").strip()
        cx = (cfg.google_cse_cx or "").strip()
    else:
        api_key = (os.getenv("GOOGLE_CSE_API_KEY") or "").strip()
        cx = (os.getenv("GOOGLE_CSE_CX") or "").strip()
    if not api_key or not cx:
        return []
    try:
        resp = requests.get(
            "https://www.googleapis.com/customsearch/v1",
            params={
                "key": api_key,
                "cx": cx,
                "q": query,
                "num": min(max(1, max_results), 10),
            },
            timeout=10,
        )
        resp.raise_for_status()
        items = resp.json().get("items") or []
        out: List[Dict[str, str]] = []
        for item in items:
            out.append({
                "title": item.get("title") or "",
                "snippet": item.get("snippet") or "",
                "url": item.get("link") or "",
            })
        return out[:max_results]
    except Exception as e:
        logger.warning("Google CSE search failed for %r: %s", query[:60], e)
        return []


def tavily_configured(cfg: "SearchRuntimeConfig | None" = None) -> bool:
    if cfg is not None:
        return bool((cfg.tavily_api_key or "").strip())
    return bool((os.getenv("TAVILY_API_KEY") or "").strip())


def serpapi_configured(cfg: "SearchRuntimeConfig | None" = None) -> bool:
    if cfg is not None:
        return bool((cfg.serpapi_api_key or "").strip())
    return bool((os.getenv("SERPAPI_API_KEY") or "").strip())


def _search_with_tavily(
    query: str,
    max_results: int,
    cfg: "SearchRuntimeConfig | None" = None,
) -> List[Dict[str, str]]:
    """Tavily Search API — https://docs.tavily.com/"""
    if cfg is not None and (cfg.tavily_api_key or "").strip():
        api_key = cfg.tavily_api_key.strip()
    else:
        api_key = (os.getenv("TAVILY_API_KEY") or "").strip()
    if not api_key:
        raise ValueError("Tavily API key not set")

    search_q = query
    if "architecture" not in query.lower() and "建筑" not in query:
        search_q = query + " architecture structure design"

    resp = requests.post(
        "https://api.tavily.com/search",
        json={
            "api_key": api_key,
            "query": search_q,
            "search_depth": "basic",
            "max_results": min(max(1, max_results), 10),
            "include_answer": False,
        },
        timeout=12,
    )
    resp.raise_for_status()
    data = resp.json()
    out: List[Dict[str, str]] = []
    for item in data.get("results") or []:
        if not isinstance(item, dict):
            continue
        out.append({
            "title": item.get("title") or "",
            "snippet": item.get("content") or "",
            "url": item.get("url") or "",
        })
    return out[:max_results]


def _search_with_serpapi(
    query: str,
    max_results: int,
    cfg: "SearchRuntimeConfig | None" = None,
) -> List[Dict[str, str]]:
    """SerpAPI Google engine — https://serpapi.com/"""
    if cfg is not None and (cfg.serpapi_api_key or "").strip():
        api_key = cfg.serpapi_api_key.strip()
    else:
        api_key = (os.getenv("SERPAPI_API_KEY") or "").strip()
    if not api_key:
        raise ValueError("SerpAPI key not set")

    search_q = query
    if "architecture" not in query.lower() and "建筑" not in query:
        search_q = query + " architecture structure design"

    resp = requests.get(
        "https://serpapi.com/search.json",
        params={
            "engine": "google",
            "q": search_q,
            "api_key": api_key,
            "num": min(max(1, max_results), 10),
        },
        timeout=12,
    )
    resp.raise_for_status()
    data = resp.json()
    out: List[Dict[str, str]] = []
    for item in data.get("organic_results") or []:
        if not isinstance(item, dict):
            continue
        out.append({
            "title": item.get("title") or "",
            "snippet": item.get("snippet") or "",
            "url": item.get("link") or "",
        })
    return out[:max_results]


def _core_name_tokens(query: str) -> List[str]:
    """从检索词提取建筑主体 token（用于 Wikipedia 命中校验）。"""
    q = (query or "").strip()
    for noise in (
        "建筑", "结构", "设计", "特点", "尺寸", "architecture", "structure",
        "design", "characteristics", "dimensions", "stadium", "building",
    ):
        q = q.replace(noise, " ")
    q = re.sub(r"设计的?", " ", q)
    tokens: List[str] = []
    for part in re.findall(r"[\u4e00-\u9fff]{2,8}", q):
        if part not in tokens:
            tokens.append(part)
    for part in re.findall(r"[A-Za-z][A-Za-z'.-]{2,}", q):
        low = part.lower()
        if low not in ("the", "and", "hadid", "zaha", "architecture") and part not in tokens:
            tokens.append(part)
    return tokens[:6]


def _text_matches_tokens(text: str, tokens: List[str]) -> bool:
    if not tokens:
        return True
    blob = (text or "").lower()
    hits = sum(1 for t in tokens if t.lower() in blob)
    return hits >= 1 if len(tokens) <= 2 else hits >= 2


def _wikipedia_search_query(query: str) -> str:
    """Wikipedia 搜索用短 query（去掉「建筑/结构/design」等噪声词）。"""
    q = (query or "").strip()
    for noise in (
        "建筑 结构 设计 特点 尺寸",
        "architecture structure design characteristics dimensions",
        "building features floor plan",
        "建筑", "结构", "设计", "architecture", "structure", "design",
    ):
        q = q.replace(noise, " ")
    q = re.sub(r"\s+", " ", q).strip()
    return q or (query or "").strip()


def _search_wikipedia(query: str, max_results: int = 2, lang: str = "zh") -> List[Dict[str, str]]:
    """Wikipedia 搜索 + 摘要提取（中文/英文建筑条目质量稳定）。"""
    q = _wikipedia_search_query(query)
    if not q:
        return []
    headers = {"User-Agent": _WIKI_USER_AGENT}
    api = f"https://{lang}.wikipedia.org/w/api.php"
    try:
        sr = requests.get(
            api,
            params={
                "action": "query",
                "list": "search",
                "srsearch": q,
                "format": "json",
                "srlimit": max(max_results * 2, 3),
            },
            headers=headers,
            timeout=8,
        )
        sr.raise_for_status()
        hits = sr.json().get("query", {}).get("search") or []
        if not hits:
            return []

        core_tokens = _core_name_tokens(q)
        filtered_hits = []
        for hit in hits:
            title = hit.get("title") or ""
            snippet = hit.get("snippet") or ""
            if core_tokens and "列表" in title and len(core_tokens) <= 3:
                continue
            if not core_tokens or _text_matches_tokens(f"{title} {snippet}", core_tokens):
                filtered_hits.append(hit)
        if not filtered_hits:
            return []

        titles = "|".join(h["title"] for h in filtered_hits[:max_results])
        ex = requests.get(
            api,
            params={
                "action": "query",
                "prop": "extracts",
                "exintro": 1,
                "explaintext": 1,
                "titles": titles,
                "format": "json",
            },
            headers=headers,
            timeout=8,
        )
        ex.raise_for_status()
        pages = ex.json().get("query", {}).get("pages") or {}
        out: List[Dict[str, str]] = []
        for page in pages.values():
            extract = (page.get("extract") or "").strip()
            title = (page.get("title") or "").strip()
            if not extract or not title:
                continue
            slug = title.replace(" ", "_")
            out.append({
                "title": f"{title} — Wikipedia ({lang})",
                "snippet": extract[:600],
                "url": f"https://{lang}.wikipedia.org/wiki/{quote_plus(slug)}",
            })
        return out[:max_results]
    except Exception as e:
        logger.debug("Wikipedia search failed lang=%s query=%r: %s", lang, q[:60], e)
        return []


def search_architecture_reference(
    query: str,
    max_results: int = 3,
    cfg: "SearchRuntimeConfig | None" = None,
) -> List[Dict[str, str]]:
    """
    搜索建筑参考资料
    
    Args:
        query: 搜索查询（如 "埃菲尔铁塔 建筑结构"）
        max_results: 最大结果数量
        cfg: 可选；来自客户端设置或 search_config.resolve_search_config()
    
    Returns:
        搜索结果列表，每个结果包含 title, snippet, url
    """
    if cfg is None:
        try:
            from .search_config import resolve_search_config
            cfg = resolve_search_config()
        except Exception:
            cfg = None

    provider = (cfg.provider if cfg is not None else "auto").strip().lower()
    if provider not in (
        "auto", "duckduckgo", "bing", "google_cse", "tavily", "serpapi", "wikipedia_only"
    ):
        provider = "auto"

    merged: List[Dict[str, str]] = []
    seen: set[str] = set()

    def _add(batch: List[Dict[str, str]]) -> None:
        for item in batch:
            if not is_relevant_architecture_result(item):
                continue
            key = (item.get("url") or item.get("snippet") or "")[:160]
            if not key or key in seen:
                continue
            seen.add(key)
            merged.append(item)
            if len(merged) >= max_results:
                return

    def _wiki_pass() -> bool:
        langs = ("zh", "en") if any(ord(c) > 127 for c in query) else ("en", "zh")
        for lang in langs:
            _add(_search_wikipedia(query, max_results=max_results, lang=lang))
            if len(merged) >= max_results:
                return True
        return False

    if _wiki_pass():
        return merged[:max_results]

    if provider == "wikipedia_only":
        return merged[:max_results]

    if provider in ("auto", "google_cse") and google_cse_configured(cfg):
        _add(_search_with_google_cse(query, max_results, cfg=cfg))
        if len(merged) >= max_results:
            return merged[:max_results]

    if provider in ("auto", "tavily") and tavily_configured(cfg):
        try:
            _add(_search_with_tavily(query, max_results, cfg=cfg))
            if len(merged) >= max_results:
                return merged[:max_results]
        except Exception as e:
            logger.warning("Tavily search failed: %s", e)
            if provider == "tavily":
                raise

    if provider in ("auto", "serpapi") and serpapi_configured(cfg):
        try:
            _add(_search_with_serpapi(query, max_results, cfg=cfg))
            if len(merged) >= max_results:
                return merged[:max_results]
        except Exception as e:
            logger.warning("SerpAPI search failed: %s", e)
            if provider == "serpapi":
                raise

    if provider == "bing" or (provider == "auto" and cfg is not None and (cfg.bing_api_key or "").strip()):
        try:
            _add(_search_with_bing(query, max_results, cfg=cfg))
            if len(merged) >= max_results:
                return merged[:max_results]
        except Exception as e:
            logger.warning("Bing search failed: %s", e)

    if provider in ("auto", "duckduckgo"):
        try:
            _add(_search_with_duckduckgo(query, max_results))
        except Exception as e:
            logger.warning("DuckDuckGo search failed: %s, trying Bing", e)
            if provider == "auto":
                try:
                    _add(_search_with_bing(query, max_results, cfg=cfg))
                except Exception as e2:
                    logger.warning("Bing search failed: %s", e2)

    return merged[:max_results]


def _search_with_duckduckgo(query: str, max_results: int) -> List[Dict[str, str]]:
    """
    使用 DuckDuckGo 搜索（无需 API key）
    注意：这需要安装 duckduckgo-search 库
    """
    try:
        # 尝试导入 duckduckgo_search
        from duckduckgo_search import DDGS
        
        lower_q = query.lower()
        if "architecture" in lower_q or "建筑" in query or "设计" in query:
            search_q = query
        else:
            search_q = query + " architecture structure design"
        region = "cn-zh" if any(ord(c) > 127 for c in query) else "wt-wt"
        with DDGS() as ddgs:
            results = []
            for result in ddgs.text(
                search_q,
                max_results=max_results,
                region=region,
            ):
                results.append({
                    "title": result.get("title", ""),
                    "snippet": result.get("body", ""),
                    "url": result.get("href", "")
                })
            return results
    except ImportError:
        # 如果未安装 duckduckgo-search，使用简单的 HTTP 请求
        logger.info("duckduckgo_search not installed, using HTTP fallback")
        return _search_with_http(query, max_results)
    except Exception as e:
        logger.error(f"DuckDuckGo search error: {e}")
        raise


def _search_with_http(query: str, max_results: int) -> List[Dict[str, str]]:
    """
    使用 HTTP 请求搜索（简单的 DuckDuckGo HTML 解析）
    这是一个回退方案，结果可能不完整
    """
    try:
        # DuckDuckGo Instant Answer API (简单的 HTTP 请求)
        url = f"https://api.duckduckgo.com/?q={quote_plus(query + ' architecture')}&format=json&no_html=1&skip_disambig=1"
        response = requests.get(url, timeout=5)
        if response.status_code == 200:
            data = response.json()
            results = []
            
            # 提取 Abstract
            if data.get("Abstract"):
                results.append({
                    "title": data.get("Heading", "Architecture Reference"),
                    "snippet": data.get("Abstract", ""),
                    "url": data.get("AbstractURL", "")
                })
            
            # 提取 Related Topics
            for topic in data.get("RelatedTopics", [])[:max_results-1]:
                if isinstance(topic, dict) and "Text" in topic:
                    results.append({
                        "title": topic.get("Text", "").split(" - ")[0] if " - " in topic.get("Text", "") else "Related",
                        "snippet": topic.get("Text", ""),
                        "url": topic.get("FirstURL", "")
                    })
                elif isinstance(topic, str) and len(topic) > 20:
                    results.append({
                        "title": "Reference",
                        "snippet": topic,
                        "url": ""
                    })
            
            return results[:max_results]
    except Exception as e:
        logger.error(f"HTTP search error: {e}")
    
    return []


def _search_with_bing(
    query: str,
    max_results: int,
    cfg: "SearchRuntimeConfig | None" = None,
) -> List[Dict[str, str]]:
    """
    使用 Bing Search API 搜索（需要 API key）
    """
    if cfg is not None and (cfg.bing_api_key or "").strip():
        api_key = cfg.bing_api_key.strip()
    else:
        api_key = (os.getenv("BING_SEARCH_API_KEY") or "").strip()
    if not api_key:
        raise ValueError("Bing Search API key not set")
    
    search_url = "https://api.bing.microsoft.com/v7.0/search"
    headers = {"Ocp-Apim-Subscription-Key": api_key}
    params = {
        "q": query + " architecture structure design dimensions",
        "count": max_results,
        "textDecorations": False,
        "textFormat": "Raw"
    }
    
    response = requests.get(search_url, headers=headers, params=params, timeout=10)
    response.raise_for_status()
    
    results = []
    for item in response.json().get("webPages", {}).get("value", []):
        results.append({
            "title": item.get("name", ""),
            "snippet": item.get("snippet", ""),
            "url": item.get("url", "")
        })
    
    return results


def get_architecture_reference_context(text: str, landmark_only: bool = False) -> Optional[str]:
    """
    为给定的文本生成建筑参考资料上下文
    
    Args:
        text: 用户请求文本
        landmark_only: 只对地标建筑联网搜索（跳过普通风格词），默认 False。
    
    Returns:
        格式化的参考资料上下文（如果没有找到相关信息则返回 None）
    """
    keyword = _detect_landmark_or_style(text, landmark_only=landmark_only)
    if not keyword:
        return None
    
    # 构建搜索查询
    # 针对中文查询，使用中文关键词；针对英文查询，使用英文关键词
    if any(ord(c) > 127 for c in keyword):
        # 中文关键词
        query = f"{keyword} 建筑结构 设计特点 尺寸规格"
    else:
        # 英文关键词
        query = f"{keyword} architecture structure design characteristics dimensions specifications"
    
    logger.info(f"Searching architecture reference for: {keyword} (query: {query})")
    
    # 执行搜索
    results = search_architecture_reference(query, max_results=3)
    
    if not results:
        logger.info(f"No search results found for: {keyword}")
        return None
    
    # 格式化搜索结果作为上下文
    context_parts = [
        f"=== Architecture Reference: {keyword} ===\n",
        "The following information is gathered from architecture references to help generate accurate building plans:\n"
    ]
    
    for i, result in enumerate(results, 1):
        context_parts.append(f"\n[{i}] {result['title']}")
        if result['snippet']:
            # 限制 snippet 长度，避免 token 过多
            snippet = result['snippet'][:300]
            if len(result['snippet']) > 300:
                snippet += "..."
            context_parts.append(f"   {snippet}")
        if result['url']:
            context_parts.append(f"   Source: {result['url']}")
    
    context_parts.append(
        "\n"
        "Please use this reference information to inform your building plan generation:\n"
        "- Ensure structural proportions are accurate\n"
        "- Include characteristic architectural elements\n"
        "- Respect typical dimensions and scaling\n"
        "- Incorporate distinctive design features\n"
    )
    
    return "\n".join(context_parts)
