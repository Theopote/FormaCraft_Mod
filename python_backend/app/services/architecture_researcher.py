"""
Architecture Researcher Service
网络搜索服务：为强类型建筑获取参考资料
"""
import os
import re
import logging
from typing import Optional, List, Dict
import requests
from urllib.parse import quote_plus

logger = logging.getLogger(__name__)

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


def search_architecture_reference(query: str, max_results: int = 3) -> List[Dict[str, str]]:
    """
    搜索建筑参考资料
    
    Args:
        query: 搜索查询（如 "埃菲尔铁塔 建筑结构"）
        max_results: 最大结果数量
    
    Returns:
        搜索结果列表，每个结果包含 title, snippet, url
    """
    try:
        # 方法1: 使用 DuckDuckGo (免费，无需 API key)
        return _search_with_duckduckgo(query, max_results)
    except Exception as e:
        logger.warning(f"DuckDuckGo search failed: {e}, trying fallback method")
        try:
            # 方法2: 使用 Bing Search API (需要 API key，可选)
            return _search_with_bing(query, max_results)
        except Exception as e2:
            logger.warning(f"Bing search failed: {e2}, returning empty results")
            return []


def _search_with_duckduckgo(query: str, max_results: int) -> List[Dict[str, str]]:
    """
    使用 DuckDuckGo 搜索（无需 API key）
    注意：这需要安装 duckduckgo-search 库
    """
    try:
        # 尝试导入 duckduckgo_search
        from duckduckgo_search import DDGS
        
        with DDGS() as ddgs:
            results = []
            for result in ddgs.text(
                query + " architecture structure design dimensions",
                max_results=max_results
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


def _search_with_bing(query: str, max_results: int) -> List[Dict[str, str]]:
    """
    使用 Bing Search API 搜索（需要 API key）
    """
    api_key = os.getenv("BING_SEARCH_API_KEY")
    if not api_key:
        raise ValueError("BING_SEARCH_API_KEY not set")
    
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
