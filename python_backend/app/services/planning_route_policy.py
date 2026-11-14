"""Request classification for the existing city/composite compatibility routes."""

from ..models.request import BuildRequest


def _should_generate_city(req: BuildRequest) -> bool:
    """检测是否应该生成城市级结构"""
    # 如果 promptMode 是 BUILD，且 requestText 包含 LlmPlan 格式的 prompt，不应该生成城市
    # LlmPlan 格式的 prompt 通常包含 "mode", "style_profile", "anchor", "components" 等字段说明
    if req.promptMode == "BUILD":
        request_text = req.requestText or ""
        # 检查是否包含 LlmPlan 格式的特征（Java 端发送的 prompt 包含这些关键词）
        llm_plan_indicators = [
            "LlmPlan", "component_type", "semantic components",
            "ComponentObject", "SlotObject", "STRUCTURED JSON TEMPLATE"
        ]
        if any(indicator in request_text for indicator in llm_plan_indicators):
            return False

    request_lower = req.requestText.lower()
    city_keywords = [
        "城市", "城镇", "city", "town", "settlement", "urban",
        "城区", "市中心", "广场", "集市", "plaza", "market"
    ]
    return any(keyword in request_lower for keyword in city_keywords)

def _should_generate_composite(req: BuildRequest) -> bool:
    """判断是否应该生成复合结构（排除城市关键词）"""
    if _should_generate_city(req):
        return False  # 城市优先于复合结构
    request_lower = req.requestText.lower()
    composite_keywords = [
        "城墙", "要塞", "复合", "组合", "village", "fort", "compound",
        "城堡", "村庄", "multiple", "several", "many", "多个", "几座", "几栋",
        "围起来", "surround", "enclose",
        # 组团/群落（用户经常会这样描述“建筑群”，如果不识别会退化成单体建筑）
        "群落", "建筑群", "建筑群落", "组团", "组群", "聚落", "多栋", "多座", "院落群"
    ]
    return any(keyword in request_lower for keyword in composite_keywords)
