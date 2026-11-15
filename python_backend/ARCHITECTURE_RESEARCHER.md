# 建筑参考资料搜索 provider

状态：维护中；更新：2026-11-15。

`app/services/architecture_researcher.py` 提供底层搜索能力。请求分类、查询规划、研究结果合成和提示词注入由 [BuildingResearchAgent](../docs/BUILDING_RESEARCH_AGENT.md) 负责。

## 当前调用链

`ai_planner.generate_llm_plan → building_research_agent.research_building_profile → search_architecture_reference`

研究代理接收玩家意图，组织查询并返回结构化 BuildingProfile；规划器消费该 profile。搜索 provider 返回 `title`、`snippet`、`url`，不决定生成路由，也不自行拼接 LLM 提示词。

## 搜索接口

```python
from app.services.architecture_researcher import search_architecture_reference

results = search_architecture_reference("Suzhou Museum architecture", max_results=3)
```

`cfg` 可传入 `SearchRuntimeConfig`；未传入时使用 `search_config.resolve_search_config()`。客户端搜索设置及环境变量的解析统一由 `search_config.py` 负责。

支持的 provider 为 `auto`、`duckduckgo`、`bing`、`google_cse`、`tavily`、`serpapi`、`wikipedia_only`。当前搜索先尝试 Wikipedia，再按配置继续 provider 链；结果经过建筑相关性过滤和去重。实际搜索链以函数实现及测试为准。

DuckDuckGo 适配使用 `ddgs`，显式指定 `backend="duckduckgo"`，避免其默认聚合搜索改变 provider 选择。旧 `duckduckgo-search` 依赖已移除；从 `requirements.lock` 安装当前版本。缺包时仍保留 HTTP 回退。

## 配置与开发边界

研究启用使用 `BUILDING_RESEARCH`，研究模式使用 `BUILDING_RESEARCH_MODE`，研究总超时使用 `BUILDING_RESEARCH_TIMEOUT_SEC`。明确设置的 on/off 优先于旧 `ARCHITECTURE_SEARCH` 开关。配置样例见 [.env.example](.env.example)。

新增搜索源在 provider 层接入；调整研究触发、查询或证据处理，在 BuildingResearchAgent 及其分类模块中修改。新增生成能力走 LlmPlan 主干，参见 [开发入口](../docs/DEVELOPER_START_HERE.md)。

旧 `get_architecture_reference_context`、固定地标/风格关键词表及无调用者的 `search_architecture_queries` 已删除。不要恢复“固定关键词命中后直接向 prompt 拼搜索摘要”的并行研究流程。

## 离线验证

在 `python_backend` 目录使用项目虚拟环境运行以下测试。搜索传输由 mock 替代，不需要真实 API key。

```powershell
.\.venv\Scripts\python.exe -m pytest -q tests/test_reference_blueprint.py tests/test_search_config.py tests/test_building_research_agent.py tests/test_research_config_authority.py
```
