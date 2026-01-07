# Bug 修复：Python 后端错误地将 LlmPlan 解析为 CitySpec

## 问题描述

当 Java 端发送 `promptMode: "BUILD"` 的请求时，期望 LLM 输出 `LlmPlan` 格式（包含 `mode`, `style_profile`, `anchor`, `global_constraints`, `layout`, `components` 等字段）。

但是 Python 后端的路由逻辑（`python_backend/app/routes/build.py`）没有检查 `promptMode`，而是根据 `_should_generate_city` 来判断，导致：

1. LLM 输出了 `LlmPlan` 格式的 JSON
2. Python 后端错误地尝试将其解析为 `CitySpec`
3. 因为缺少 `CitySpec` 的必需字段（`cityName`, `style`, `size`, `biome`, `zones`, `structures`, `roads`, `bridges`），导致验证失败

## 错误日志

```
Orchestrator returned status: 502 body={"detail":"LLM call failed for city spec: 8 validation errors for CitySpec
cityName
  Field required [type=missing, input_value={'mode': 'build', 'style_...E_STEPS', 'RAILINGS']}]}, input_type=dict]
...
```

## 根本原因

1. **Java 端**：发送 `promptMode: "BUILD"`，并在 `requestText` 中包含要求 LLM 输出 `LlmPlan` 格式的 prompt
2. **Python 后端**：路由逻辑（`build.py`）没有检查 `promptMode`，而是根据 `_should_generate_city` 来判断
3. **缺少处理**：Python 后端没有处理 `LlmPlan` 格式的代码路径

## 解决方案

### 方案 1：在路由层检查 `promptMode`（推荐）

修改 `python_backend/app/routes/build.py`，在路由逻辑中添加对 `promptMode` 的检查：

```python
# 检查应该生成什么类型的结构（优先级：promptMode > 城市 > 复合 > 单个）
try:
    # 如果 promptMode 是 BUILD 且 requestText 包含 LlmPlan 格式的 prompt，直接返回 LLM 的原始 JSON
    if build_req.promptMode == "BUILD" and "LlmPlan" in build_req.requestText:
        # 需要创建一个新的函数来处理 LlmPlan 格式
        return generate_llm_plan(build_req)
    
    if _should_generate_city(build_req):
        return generate_city_spec(build_req)
    if _should_generate_composite(build_req):
        return generate_composite_spec(build_req)
    return generate_building_spec(build_req)
```

### 方案 2：创建新的 LlmPlan 处理函数

在 `python_backend/app/services/ai_planner.py` 中添加：

```python
def generate_llm_plan(req: BuildRequest) -> dict:
    """
    处理 LlmPlan 格式的请求
    直接返回 LLM 的原始 JSON 输出，不做格式转换
    """
    client = get_client(req)
    if not client:
        raise ValueError("LLM client not available")
    
    # 使用 requestText 作为完整的 prompt（Java 端已经组装好了）
    system_prompt = req.requestText.split("USER REQUEST:")[0] if "USER REQUEST:" in req.requestText else ""
    user_prompt = req.userMessage or req.requestText
    
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
            temperature=_clamp_temperature(getattr(req, "temperature", None), 0.7),
        ),
        timeout_sec,
    )
    
    raw_output = response.choices[0].message.content
    if not raw_output:
        raise ValueError("Empty response from LLM")
    
    # 直接返回解析后的 JSON，不做格式转换
    return json.loads(raw_output)
```

### 方案 3：修改 `_should_generate_city` 逻辑（临时方案）

在 `_should_generate_city` 中添加对 `promptMode` 的检查：

```python
def _should_generate_city(req: BuildRequest) -> bool:
    """检测是否应该生成城市级结构"""
    # 如果 promptMode 是 BUILD，且 requestText 包含 LlmPlan 格式的 prompt，不应该生成城市
    if req.promptMode == "BUILD" and "LlmPlan" in req.requestText:
        return False
    
    request_lower = req.requestText.lower()
    city_keywords = [
        "城市", "城镇", "city", "town", "settlement", "urban", 
        "城区", "市中心", "广场", "集市", "plaza", "market"
    ]
    return any(keyword in request_lower for keyword in city_keywords)
```

## 推荐实施步骤

1. **立即修复**：实施方案 3（修改 `_should_generate_city`），防止误判
2. **长期方案**：实施方案 1 + 方案 2，添加完整的 `LlmPlan` 处理路径

## 注意事项

- `LlmPlan` 格式是 Java 端的新格式，用于支持组件级别的生成
- Python 后端需要支持这个格式，或者至少不应该错误地解析它
- 如果 Python 后端不支持 `LlmPlan`，应该返回原始 JSON 让 Java 端自己处理

