# Building Research Agent（PR-1）

状态：维护中；入口复核：2026-11-15。

开放世界建筑理解主路径：任意建筑名 → 网络检索 → **LLM 归纳** → 结构化 `BuildingProfile` → 注入 LlmPlan prompt。

Culture Card / landmark 为旁路（eval、boost、精品加速），**不是**命中前提。

## 实际入口与职责

`ai_planner.generate_llm_plan` 调用 `research_building_profile` 获取结构化 BuildingProfile，再按单阶段或两阶段规划方式消费它。`format_profile_for_prompt` 只负责格式化已有 profile，不执行搜索。旧 `research_building_context` 包装入口已删除。

搜索传输归 `architecture_researcher.search_architecture_reference`，provider 配置归 `search_config.py`。不要在搜索层恢复固定关键词分类或直接拼规划提示词；底层接口见 [搜索 provider 说明](../python_backend/ARCHITECTURE_RESEARCHER.md)。

## 流程

```
用户 prompt / references[]
  → QueryPlanner（规则拆检索词，零 LLM 延迟）
  → MultiSourceSearch
       Wikipedia → Google CSE（可选）→ DuckDuckGo → Bing
  → ProfileSynthesizer
       默认：BUILDING_RESEARCH_LLM_SYNTH=on → LLM 读 snippets + 用户话术 → BuildingProfile
       降级：规则归纳（搜索/LLM 均失败时）
  → Vision / reference_json → ReferenceBlueprint merge（PR-4）
  → format_profile_for_prompt → Stage P LlmPlan
```

## 环境变量

| 变量 | 默认 | 说明 |
|------|------|------|
| `BUILDING_RESEARCH` | （未设时跟随 `ARCHITECTURE_SEARCH`） | 显式 `on` / `off` 优先于旧开关 |
| `ARCHITECTURE_SEARCH` | `landmark` | 未显式设置新开关时，`off` 关闭研究；其他值启用，旧模式名不选择研究模式 |
| `BUILDING_RESEARCH_MODE` | `always` | `always` / `named_only` / `off` |
| `BUILDING_RESEARCH_TIMEOUT_SEC` | `15` | 规划器包裹研究调用的等待上限；超时跳过研究结果，不代表后台工作已被取消 |
| `BUILDING_RESEARCH_LLM_SYNTH` | **`on`** | `off` 关闭 LLM 归纳，仅用规则 |
| `BUILDING_RESEARCH_MAX_QUERIES` | `3` | 每请求最多 search query 数 |
| `BUILDING_RESEARCH_MAX_RESULTS` | `4` | 合并后最多保留结果数 |
| `GOOGLE_CSE_API_KEY` | （空） | Google Custom Search API key |
| `GOOGLE_CSE_CX` | （空） | Programmable Search Engine CX id |

### Google CSE 配置

1. [Google Cloud Console](https://console.cloud.google.com/) 启用 Custom Search API
2. [Programmable Search Engine](https://programmablesearchengine.google.com/) 创建搜索引擎（可搜全网）
3. 设置环境变量：
   ```bash
   set GOOGLE_CSE_API_KEY=your_api_key
   set GOOGLE_CSE_CX=your_search_engine_id
   ```

未配置时自动跳过 Google，使用 Wikipedia + DuckDuckGo。

## BuildingProfile 结构

见 `python_backend/app/models/building_profile.py`。

Plan 阶段应读取：

- `minecraft_strategy.recommended_components` — 组件建议
- `scale_hints` — 典型方块尺寸
- `structure.distinctive_elements` — 特色元素
- `research_notes` — LLM 归纳摘要 + 搜索原文
- **`reference_blueprint`** — Vision/用户 JSON 结构化蓝图（见 `reference_blueprint.py`）

## 与硬编码词表的关系

`_ARCHITECT_HINTS` / `_BUILDING_SEARCH_ALIASES` 仅为检索词扩展兜底；**建筑知识主路径是 LLM synth + reference_blueprint**，不应再依赖代码内建筑百科。

## CI / 测试

- `tests/test_building_research_agent.py` — QueryPlanner mock search
- `tests/test_reference_blueprint.py` — JSON schema + Google CSE mock
- `tests/fixtures/reference_blueprint_pagoda.json` — 东方蒸汽朋克塔楼样例

## 降级策略

| 失败 | 行为 |
|------|------|
| 研究总等待超时 | 返回/使用空研究结果；规划器后续仍可应用本地 profile 补全，不保证继续 LLM synth |
| 零搜索结果 | LLM 靠预训练 + 用户描述 |
| 用户上传 `reference_json` | 跳过 LLM synth，直传 blueprint |
| patch 编辑 prompt | QueryPlanner 跳过 |

## 相关文档

- [VISION_REFERENCE.md](VISION_REFERENCE.md) — 参考图 / reference_json
- PR-2: `building_plan_stage.py` — Research → Plan 两阶段
