# ComponentQuery & Ranking System v2（构件查询与排序系统 v2）实现总结

## 📋 实现内容

根据建议，已成功扩展 ComponentQuery & Ranking System，使其成为整个 Formacraft AI-first 架构真正"起飞"的关键齿轮。

## 🎯 核心目标

**我们要解决的问题不是**：
- "有没有这个构件？"

**而是**：
- "在当前上下文中，哪个构件最合适？"

**ComponentQuery & Ranking 的目标**：
- ✅ 给 AI 自由描述的空间
- ✅ 给系统确定性的筛选和排序逻辑
- ✅ 给结果可解释、可控、可演进的评分

## 🏗 整体架构

```
AI 意图
  ↓
ComponentQuery（语义 + 上下文 + 约束）
  ↓
ComponentRetriever（硬过滤：200 → 20 候选）
  ↓
ComponentRanker（多维打分）
  ↓
Top-N Components（给 Generator / Variant 用）
```

**关键点**：
- 👉 AI 不选具体组件 ID
- 👉 AI 只描述"我想要什么样的构件"

## ✅ 已实现的组件

### 1. ComponentQuery（扩展版）

**位置**：`src/main/java/com/formacraft/common/component/query/ComponentQuery.java`

**核心结构**（JSON 示例）：
```json
{
  "semantic": {
    "role": "door",
    "tags": ["gothic", "arched", "heavy"],
    "importance": ["role", "placement"]
  },
  "context": {
    "placement": "wall",
    "side": "exterior",
    "height_level": "ground",
    "edge_condition": "flat"
  },
  "geometry": {
    "opening": {
      "width": 2,
      "height": 3,
      "tolerance": 1
    },
    "scalable": true
  },
  "style": {
    "style_profile": "Medieval_Castle",
    "material_tone": "dark_stone"
  },
  "constraints": {
    "forbidden_tags": ["glass", "modern"],
    "must_have": ["structural"]
  },
  "usage_hint": {
    "frequency": "primary",
    "visibility": "high"
  }
}
```

**每一部分的作用**：

**🧠 semantic（我是什么）**：
- `role` - 角色（门/窗/柱/栏杆/装饰）
- `tags` - 风格、感觉、历史语义
- `importance` - 用于调权重（例如门比装饰更重要）
- 👉 这是**最强过滤条件**

**📍 context（我要放在哪里）**：
- `placement` - wall / roof / edge / ground / interior
- `side` - interior / exterior / both
- `height_level` - ground / mid / roof
- `edge_condition` - corner / flat / convex
- 👉 大量构件会在这一层直接被淘汰

**📐 geometry（我大概多大）**：
- `opening` - 洞口类构件
- `scalable` - 是否允许变体缩放
- `tolerance` - 给 AI 留余地（很重要）
- 👉 防止 AI 选到"完全不可能适配"的构件

**🎨 style（风格一致性）**：
- `style_profile` - 来自 StyleProfileCatalog
- `material_tone` - 色系 / 材质倾向
- 👉 保证整体建筑"统一感"

**⛔ constraints（硬性约束）**：
- `forbidden_tags` - 明确不要
- `must_have` - 必须具备
- 👉 这是 Patch Filter 的前置阶段

**🔎 usage_hint（排序辅助）**：
- `frequency` - primary / secondary / decorative
- `visibility` - high / low
- 👉 用于在多个可行构件中选"最像主角"的那个

### 2. ComponentMetadata（构件库侧信息）

**位置**：`src/main/java/com/formacraft/common/component/query/ComponentMetadata.java`

**核心功能**：
- 这不是 AI 写的，而是系统定义的"事实"
- 用于 ComponentRetriever 和 ComponentRanker 的筛选和评分

**结构**：
```json
{
  "component_id": "gothic_stone_door_A",
  "semantic": {
    "role": "door",
    "tags": ["gothic", "arched", "stone", "heavy"]
  },
  "placement_spec": {
    "allowed_placements": ["wall"],
    "side_policy": "exterior_only",
    "requires_opening": true
  },
  "geometry_spec": {
    "base_size": { "w": 2, "h": 3 },
    "scalable_axes": ["height"],
    "max_scale": 1.5,
    "min_scale": 0.8
  },
  "style_affinity": {
    "Medieval_Castle": 0.9,
    "Gothic": 1.0,
    "Modern": 0.1
  },
  "variant_capability": {
    "material_swap": true,
    "segment_repeat": false
  }
}
```

**关键方法**：
- `fromComponent(ComponentDefinition, ComponentArchetype)` - 从构件定义和原型创建元数据

### 3. ComponentRetriever（硬过滤阶段）

**位置**：`src/main/java/com/formacraft/common/component/query/ComponentRetriever.java`

**核心功能**：
- 从 200 个构件 → 20 个候选
- 硬过滤条件（必须通过），没有"评分"，只有"能不能用"

**硬过滤条件**：
1. `semantic.role` 匹配
2. `placement_spec.allowed_placements` 包含
3. `side_policy` 不冲突
4. `requires_opening` 与 `geometry.opening` 是否满足
5. `forbidden_tags` 不命中
6. `must_have` 必须命中

**流程**：
1. 从 ComponentCatalog 获取所有构件
2. 硬过滤（`hardFilter()`）
3. 多维评分（`ComponentRanker.rank()`）
4. 排序（按总分降序）
5. 过滤低分结果（总分 < 0.3）
6. 限制结果数量

### 4. ComponentRanker（多维评分系统）

**位置**：`src/main/java/com/formacraft/common/component/query/ComponentRanker.java`

**核心功能**：
- 这是整个系统的"智慧核心"
- 多维评分：语义、放置、几何、风格、使用

**评分模型**：
```
finalScore =
    semanticScore * 0.30 +
    placementScore * 0.25 +
    geometryScore * 0.20 +
    styleScore * 0.15 +
    usageScore * 0.10;
```

**评分逻辑**：

**1️⃣ semanticScore（我是不是你要的那个）**：
- `role` 完全匹配：0.6
- `tags` 命中率：0.4 * (n / total)

**2️⃣ placementScore（放得合不合理）**：
- `placement` 完全匹配：0.5
- `side_policy` 完全匹配：0.3
- `edge_condition` 符合：+0.2

**3️⃣ geometryScore（能不能塞进去）**：
- `opening` 尺寸差距（越小越好）：0.6
- 是否需要极端缩放（惩罚）：0.3
- `scalable_axes` 是否支持：0.1

**4️⃣ styleScore（看起来像不像一套）**：
- `style_affinity[style_profile]`：0.7
- `material_tone` 相似度：0.3

**5️⃣ usageScore（主角还是配角）**：
- `primary` + `visibility=high` → +0.3, +0.2
- `decorative` → -0.2
- `visibility=low` → -0.1

### 5. PromptAssembler 集成

**位置**：`src/main/java/com/formacraft/ai/prompt/PromptAssembler.java`

**核心功能**：
- 在 `componentLibraryBlock()` 中添加 ComponentQuery 使用说明
- 在 JSON 模板中添加 `ComponentQueryObject` 结构
- 让 AI 明确输出 ComponentQuery 结构（而不是直接写构件 ID）

**关键说明**：
- AI 永远不知道构件 ID
- AI 只描述"我想要什么样的构件"
- 系统自动选择最合适的构件

## 📊 使用示例

### 示例 1：AI 输出 ComponentQuery

```json
{
  "components": [
    {
      "component_type": "DOOR",
      "slot_id": "main_entrance",
      "relative_position": { "x": 0, "y": 0, "z": 0 },
      "dimensions": { "width": 2, "depth": 1, "height": 3 },
      "component_query": {
        "semantic": {
          "role": "door",
          "tags": ["gothic", "stone", "heavy"],
          "importance": ["role", "placement"]
        },
        "context": {
          "placement": "wall",
          "side": "exterior",
          "height_level": "ground"
        },
        "geometry": {
          "opening": {
            "width": 2,
            "height": 3,
            "tolerance": 1
          },
          "scalable": true
        },
        "style": {
          "style_profile": "Medieval_Castle",
          "material_tone": "dark_stone"
        },
        "constraints": {
          "forbidden_tags": ["glass", "modern"],
          "must_have": ["structural"]
        },
        "usage_hint": {
          "frequency": "primary",
          "visibility": "high"
        }
      }
    }
  ]
}
```

### 示例 2：系统处理 ComponentQuery

```java
// AI 输出包含 component_query
ComponentQuery query = parseFromJson(componentObject.component_query);

// 系统自动检索和评分
List<ComponentScore> candidates = ComponentRetriever.retrieve(query, 5);

// 选择最佳匹配
ComponentDefinition selected = ComponentRetriever.retrieveBest(query);

// 或使用 Top-N 进行变体生成
for (ComponentScore score : candidates) {
    ComponentDefinition component = ComponentStorage.loadComponent(score.componentId);
    // 生成变体...
}
```

## 🔄 完整数据流

```
[ AI 意图："我要一个哥特式石门" ]
        ↓
[ AI 输出 ComponentQuery ]
        ↓
ComponentRetriever.retrieve()
        ↓
[ 硬过滤：200 个 → 20 个候选 ]
        ↓
ComponentRanker.rank()
        ↓
[ 多维评分：语义 0.30 + 放置 0.25 + 几何 0.20 + 风格 0.15 + 使用 0.10 ]
        ↓
[ 排序（按总分降序）]
        ↓
[ 过滤低分（< 0.3）]
        ↓
[ 返回 Top-N 候选 ]
        ↓
[ AI 选择最佳匹配或从候选列表选择 ]
        ↓
[ 生成变体（VariantGenerator）]
        ↓
[ 编译为 Patch（ComponentPlanCompiler）]
```

## 🎯 与 AI 的"心理契约"

**AI 永远不知道**：
- 构件 ID
- 构件排名算法
- 为什么选了 A 没选 B

**但 AI 会感觉到**：
- "我说要哥特式石门，系统真的懂。"

## ✅ 完成度

| 组件 | 状态 | 说明 |
|------|------|------|
| ComponentQuery（扩展版） | ✅ 完成 | 语义、上下文、几何、风格、约束、使用提示 |
| ComponentMetadata | ✅ 完成 | 构件库侧信息（语义、放置、几何、风格、变体） |
| ComponentRetriever（硬过滤） | ✅ 完成 | 硬过滤阶段（200 → 20 候选） |
| ComponentRanker | ✅ 完成 | 多维评分系统（5 个维度） |
| PromptAssembler 集成 | ✅ 完成 | AI 输出 ComponentQuery 结构 |

## 🎉 总结

已成功扩展 ComponentQuery & Ranking System v2：

- ✅ **ComponentQuery（扩展版）** - 完整的查询模型（6 个维度）
- ✅ **ComponentMetadata** - 构件库侧信息
- ✅ **ComponentRetriever（硬过滤）** - 从 200 → 20 候选
- ✅ **ComponentRanker** - 多维评分系统（5 个维度，加权平均）
- ✅ **PromptAssembler 集成** - AI 输出 ComponentQuery 结构

**这一层完成后，系统立刻获得的能力**：
- ✅ AI 可以自由描述构件需求，而不需要知道具体构件 ID
- ✅ 系统自动筛选和排序，确保选择最合适的构件
- ✅ 支持多维度评分，结果可解释、可控、可演进
- ✅ 为后续 VariantGenerator 和 PatchCompiler 提供了清晰的输入

**这是整个 Formacraft AI-first 架构真正"起飞"的关键齿轮。**

---

**实现时间**: 2026-01-14  
**版本**: v2.0  
**状态**: ✅ 核心功能完成，已集成到 PromptAssembler
