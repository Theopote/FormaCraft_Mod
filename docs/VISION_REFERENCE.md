# Vision Reference Input（PR-4）

用户可通过**参考图 / 网页链接**辅助建模，与开放世界 Research 管线合并。

## 使用方式（MVP）

在聊天框输入中附带：

- 图片 URL：`照着这个建 https://example.com/building.jpg`
- 维基 / 项目页：`复原 https://zh.wikipedia.org/wiki/苏州博物馆`
- 粘贴 data URI：`data:image/png;base64,...`（客户端自动识别）

Java `ReferenceInputExtractor` 从用户文本提取 `references[]` 并随 `FormaRequest` 发送。

## 管线

```
FormaRequest.references[]
  → vision_analyzer.analyze_references()   （Vision LLM 或规则 fallback）
  → merge_visual_into_profile()
  → building_research_agent.research_building_profile()
  → building_plan_stage (PR-2)
  → LlmPlan
```

## Schema

```json
{
  "type": "image_url | image_base64 | web_url",
  "content": "https://... 或 base64 或 data URI",
  "caption": "optional"
}
```

Python: `app/models/request.py` → `ReferenceInput`  
Java: `ReferenceInput.java` + `ReferenceInputExtractor.java`

## 环境变量

| 变量 | 默认 | 说明 |
|------|------|------|
| `VISION_REFERENCE` | `on` | 关闭 vision 分析 |
| `VISION_REFERENCE_TIMEOUT_SEC` | `20` | Vision LLM 超时 |
| `VISION_MODEL` | （空） | 覆盖 vision 模型，默认 `gpt-4o-mini` |
| `VISION_MAX_BYTES` | `4000000` | 网页抓取上限 |

## 降级

| 情况 | 行为 |
|------|------|
| 无 API key / vision 失败 | caption/URL 规则归纳，不阻塞 |
| 仅 references 无建造动词 | 仍触发 research（`has_references=true`） |
| web_url 非图片 | 抓取页面 snippet 写入 profile notes |

## 测试

- Python: `tests/test_vision_reference.py`
- Java: `ReferenceInputExtractorTest.java`

## 后续

- 客户端独立「粘贴图片」按钮（不依赖 URL 文本）
- 多视角 references[] 合并
- CAD / 平面图 provider
