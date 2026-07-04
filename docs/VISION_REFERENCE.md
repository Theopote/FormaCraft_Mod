# Vision Reference Input（PR-4）

用户可通过**参考图 / 网页链接 / 预计算 JSON** 辅助建模，与开放世界 Research 管线合并。

## 使用方式

### 图片 URL
```
照着这个建 https://example.com/building.jpg
```

### 预计算 ReferenceBlueprint JSON（Gemini 等）
在请求中附带 `references[]`：
```json
{
  "type": "reference_json",
  "content": "{ \"metadata\": { \"project_name\": \"...\", \"dimensions\": {...}, \"style\": \"...\" }, \"block_palette\": {...}, \"architectural_layers\": [...], \"generation_rules\": {...} }"
}
```

也可在 `content` 中直接粘贴完整 JSON（自动识别 `metadata` / `architectural_layers` 字段）。

## ReferenceBlueprint Schema

与 Gemini 多模态归纳对齐，核心字段：

| 字段 | 说明 |
|------|------|
| `metadata` | project_name, dimensions{width_x,height_y,depth_z}, style |
| `block_palette` | 语义分组 → 角色 → minecraft:block_id[] |
| `structural_backbone` | 可选；主轴、塔半径等 |
| `architectural_layers` | 分层 + bounding_box + features |
| `generation_rules` / `detailing_rules` | 纹理混合、飞檐、不对称等生成规则 |

完整 prompt 合约见 `app/models/reference_blueprint.py` → `REFERENCE_BLUEPRINT_VISION_PROMPT`。

样例 fixture：`tests/fixtures/reference_blueprint_pagoda.json`（东方蒸汽朋克塔楼，32×42×32）。

## 管线

```
FormaRequest.references[]
  → vision_analyzer.analyze_references()
       reference_json → 直接 parse
       image_url      → Vision LLM → ReferenceBlueprint JSON
  → merge_visual_into_profile()  → BuildingProfile.reference_blueprint
  → building_research_agent (LLM synth 读 snippets + blueprint)
  → building_plan_stage → LlmPlan
```

## Schema（ReferenceInput）

```json
{
  "type": "image_url | image_base64 | web_url | reference_json",
  "content": "https://... 或 base64 或 JSON 字符串",
  "caption": "optional"
}
```

## 环境变量

| 变量 | 默认 | 说明 |
|------|------|------|
| `VISION_REFERENCE` | `on` | 关闭 vision 分析 |
| `VISION_REFERENCE_TIMEOUT_SEC` | `30` | Vision LLM 超时 |
| `VISION_MAX_TOKENS` | `4096` | Vision JSON 输出上限 |
| `VISION_MODEL` | （空） | 覆盖 vision 模型 |
| `BUILDING_RESEARCH_LLM_SYNTH` | `on` | 搜索后 LLM 归纳 profile |

## 降级

| 情况 | 行为 |
|------|------|
| 用户上传完整 reference_json | 跳过 LLM synth，保留 blueprint 结构 |
| 无 API key / vision 失败 | caption/URL 规则归纳 |
| web_url 非图片 | 抓取页面 snippet |

## 测试

- `tests/test_vision_reference.py`
- `tests/test_reference_blueprint.py`
- `tests/fixtures/reference_blueprint_pagoda.json`
