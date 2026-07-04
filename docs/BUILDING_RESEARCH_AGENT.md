# Building Research Agent（PR-1）

开放世界建筑理解主路径：任意建筑名 → 网络检索 → 结构化 `BuildingProfile` → 注入 LlmPlan prompt。

Culture Card / landmark 为旁路（eval、boost、精品加速），**不是**命中前提。

## 流程

```
用户 prompt
  → QueryPlanner（规则提取检索词，零 LLM 延迟）
  → MultiSourceSearch（DuckDuckGo / Bing，多 query 合并）
  → ProfileSynthesizer（默认规则归纳；可选 LLM 精炼）
  → format_profile_for_prompt → 前置到 generate_llm_plan user prompt
  → 现有 LlmPlan 生成 + Java 编译
```

## 环境变量

| 变量 | 默认 | 说明 |
|------|------|------|
| `BUILDING_RESEARCH` | （未设时跟随 `ARCHITECTURE_SEARCH`） | `on` / `off` 总开关 |
| `ARCHITECTURE_SEARCH` | `landmark` | `off` 关闭研究；其他值开启（兼容旧配置） |
| `BUILDING_RESEARCH_MODE` | `always` | `always` / `named_only` / `off` |
| `BUILDING_RESEARCH_TIMEOUT_SEC` | `8` | 硬超时，超时跳过不阻塞 |
| `BUILDING_RESEARCH_LLM_SYNTH` | `off` | `on` 时用 LLM 精炼 profile（多一次调用） |
| `BUILDING_RESEARCH_MAX_QUERIES` | `2` | 每请求最多 search query 数 |
| `BUILDING_RESEARCH_MAX_RESULTS` | `4` | 合并后最多保留结果数 |

## BuildingProfile 结构

见 `python_backend/app/models/building_profile.py`。

Plan 阶段应读取：

- `minecraft_strategy.recommended_components` — 组件建议
- `scale_hints` — 典型方块尺寸
- `structure.distinctive_elements` — 特色元素
- `research_notes` — 原始检索摘要

## 与旧 architecture_researcher 的关系

- `architecture_researcher.py` 保留为 **search provider**（`search_architecture_reference`）
- `get_architecture_reference_context()` 仍可用于独立调用，但 **LlmPlan 主路径已改由 BuildingResearchAgent 接管**
- 旧硬编码 `LANDMARK_BUILDINGS` 词表不再门控是否搜索

## CI / 测试

- 单元测试：`tests/test_building_research_agent.py`（mock search，不联网）
- CI golden scenarios **不联网**，仍用 fixture plan
- 可选 weekly live eval 测开放 prompt + live search

## 降级策略

| 失败 | 行为 |
|------|------|
| 搜索超时 | 跳过 research，纯 LLM + Culture RAG |
| 零搜索结果 | 产出低 confidence profile，notes 提示靠预训练知识 |
| patch 编辑 prompt | QueryPlanner 跳过（不误触发） |
| 长系统 prompt 无建造意图 | 跳过 |

## 后续（PR-2+）

- 两阶段 Research → Plan 强制分离
- Vision 参考图 merge 进 profile
- Wikipedia infobox provider
- `BUILDING_RESEARCH_LLM_SYNTH=on` 作为生产默认（评估延迟后）
