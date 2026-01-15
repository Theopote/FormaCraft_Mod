# Component Retrieval & Ranking System（构件检索与排序系统）实现总结

## 📋 实现内容

根据建议，已成功实现 Component Retrieval & Ranking System（构件检索与排序系统），这是 AI 选择构件的核心逻辑。

## 🎯 核心思想

**关键共识**：
- ✅ **构件库的第一使用者是 AI，不是玩家**
- ✅ **玩家只是定义这些器官的存在与规则**
- ✅ **ComponentTool 的核心不是 UI，而是规则执行**
- ✅ **玩家直接放构件只是例外路径（<10%）**

**三层角色划分**：
1. **Layer 1: AI 自动使用（80-90%）** - 主要场景，没有 ComponentTool UI 参与
2. **Layer 2: 玩家指令驱动的 AI 使用（Secondary）** - 玩家约束空间，AI 选择构件
3. **Layer 3: 玩家手动放置（<10%）** - 例外场景（精修、演示、教学、Debug）

## ✅ 已实现的组件

### 1. ComponentQuery（查询模型）

**位置**：`src/main/java/com/formacraft/common/component/query/ComponentQuery.java`

**核心功能**：
- AI 选择构件时的查询模型
- 这不是玩家交互，而是 AI 的决策单位

**查询维度**：
- `semantic` - 语义标签（例如：["door", "gothic"]）
- `context` - 放置上下文（placement, side, openingSize, requireSupport）
- `style` - 风格要求（例如："medieval_castle"）
- `constraints` - 约束条件（minSize, maxSize, allowMirror, allowRotation, allowMaterialSwap）

**便捷方法**：
- `semantic(String... tags)` - 创建基础查询（仅语义标签）
- `create(...)` - 创建完整查询

### 2. ComponentScore（构件评分）

**位置**：`src/main/java/com/formacraft/common/component/query/ComponentScore.java`

**评分维度**：
- `semanticScore` - 语义匹配度（0.0 - 1.0）
- `contextScore` - 上下文匹配度（0.0 - 1.0）
- `styleScore` - 风格匹配度（0.0 - 1.0）
- `flexibilityScore` - 可变形程度（0.0 - 1.0）
- `totalScore` - 总分（加权平均）

**关键方法**：
- `calculateTotal()` - 计算总分（使用默认权重：语义 0.3, 上下文 0.4, 风格 0.2, 可变形 0.1）
- `calculateTotal(weights...)` - 使用自定义权重

### 3. ComponentScorer（构件评分器）

**位置**：`src/main/java/com/formacraft/common/component/query/ComponentScorer.java`

**核心功能**：
- 计算构件与查询的匹配评分
- 四个评分维度：语义、上下文、风格、可变形程度

**评分逻辑**：

**语义匹配（semanticScore）**：
- 计算查询标签与构件标签的匹配数量
- 归一化到 0.0 - 1.0

**上下文匹配（contextScore）**：
- 检查放置上下文（placement）是否匹配 Archetype.attachment.allowedContexts
- 检查表面侧（side）是否匹配 Archetype.attachment.allowedSides
- 检查支撑要求（requireSupport）是否匹配

**风格匹配（styleScore）**：
- 检查构件的标签和分类是否与风格匹配
- 支持风格关键词匹配（gothic, chinese, medieval 等）

**可变形程度（flexibilityScore）**：
- 检查镜像、旋转、材质替换约束是否匹配 Archetype.variation
- 检查尺寸约束是否满足（通过检查 VariationSpec 的缩放规则）

### 4. ComponentRetriever（构件检索器）

**位置**：`src/main/java/com/formacraft/common/component/query/ComponentRetriever.java`

**核心功能**：
- 从构件库中检索和排序构件
- 这是 AI 选择构件的核心逻辑

**关键方法**：
- `retrieve(ComponentQuery query, int maxResults)` - 检索构件（返回排序后的候选列表）
- `retrieve(ComponentQuery query)` - 使用默认最大结果数量 10
- `retrieveBest(ComponentQuery query)` - 检索最佳匹配的构件
- `retrieveBySemantic(String... tags)` - 根据语义标签快速检索
- `getStats(List<ComponentScore> results)` - 获取检索结果的统计信息

**检索流程**：
1. 从 ComponentCatalog 获取所有构件
2. 对每个构件评分（使用 ComponentScorer）
3. 排序（按总分降序）
4. 过滤低分结果（总分 < 0.3）
5. 限制结果数量

## 📊 使用示例

### 示例 1：AI 自动选择门

```java
// AI 说"我要一个门"
ComponentQuery query = ComponentQuery.create(
    List.of("door", "entry"),                    // 语义标签
    new ComponentQuery.Context() {               // 上下文
        placement = ContextType.WALL,
        side = SurfaceSide.BOTH,
        requireSupport = true
    },
    "medieval_castle",                           // 风格
    null                                         // 无特殊约束
);

// 检索最佳匹配
ComponentDefinition door = ComponentRetriever.retrieveBest(query);
```

### 示例 2：检索多个候选

```java
// 检索前 5 个候选
List<ComponentScore> candidates = ComponentRetriever.retrieve(query, 5);

// 获取统计信息
RetrievalStats stats = ComponentRetriever.getStats(candidates);
System.out.println("找到 " + stats.count() + " 个候选，平均分: " + stats.avgScore());

// AI 可以从候选列表中选择
for (ComponentScore score : candidates) {
    System.out.println(score); // 显示评分详情
}
```

### 示例 3：快速语义检索

```java
// 快速检索"gothic"风格的构件
List<ComponentScore> results = ComponentRetriever.retrieveBySemantic("gothic", "window");
```

### 示例 4：带约束的查询

```java
// 查询需要特定尺寸和变形能力的构件
ComponentQuery query = ComponentQuery.create(
    List.of("railing"),
    new ComponentQuery.Context() {
        placement = ContextType.EDGE,
        side = SurfaceSide.EXTERIOR
    },
    null,
    new ComponentQuery.Constraints() {
        minSize = new int[]{5, 1, 1},  // 至少 5 格宽
        maxSize = new int[]{20, 1, 1}, // 最多 20 格宽
        allowMirror = true,
        allowMaterialSwap = true
    }
);

List<ComponentScore> results = ComponentRetriever.retrieve(query);
```

## 🔄 系统集成

### 与现有系统的关系

| 系统 | 关系 |
|------|------|
| ComponentCatalog | 从构件库获取所有构件 |
| ComponentStorage | 加载构件定义用于评分 |
| ComponentArchetype | 使用 Archetype 进行上下文和可变形程度评分 |
| AI 系统 | AI 使用 ComponentRetriever 选择构件 |
| PromptAssembler | 可以集成查询逻辑到 Prompt 生成 |

### 数据流

```
[ AI 需要构件 ]
        ↓
[ 构建 ComponentQuery ]
        ↓
ComponentRetriever.retrieve()
        ↓
[ 从 ComponentCatalog 获取所有构件 ]
        ↓
[ 对每个构件评分（ComponentScorer）]
        ↓
[ 排序（按总分降序）]
        ↓
[ 过滤低分结果（< 0.3）]
        ↓
[ 返回排序后的候选列表 ]
        ↓
[ AI 选择最佳匹配或从候选列表选择 ]
```

## 🎯 评分权重

**默认权重**：
- 语义匹配：0.3（30%）
- 上下文匹配：0.4（40%）- **最重要**
- 风格匹配：0.2（20%）
- 可变形程度：0.1（10%）

**设计理由**：
- 上下文匹配最重要，因为构件必须能正确放置
- 语义匹配次之，确保选择正确的构件类型
- 风格匹配用于风格一致性
- 可变形程度用于满足约束条件

## ✅ 完成度

| 组件 | 状态 | 说明 |
|------|------|------|
| ComponentQuery | ✅ 完成 | 查询模型（语义、上下文、风格、约束） |
| ComponentScore | ✅ 完成 | 评分结果（四个维度 + 总分） |
| ComponentScorer | ✅ 完成 | 评分器（语义、上下文、风格、可变形程度） |
| ComponentRetriever | ✅ 完成 | 检索器（检索、排序、过滤） |
| 语义匹配评分 | ✅ 完成 | 标签匹配逻辑 |
| 上下文匹配评分 | ✅ 完成 | 放置上下文、表面侧、支撑要求 |
| 风格匹配评分 | ✅ 完成 | 风格关键词匹配 |
| 可变形程度评分 | ✅ 完成 | 约束条件匹配 |

## 🎉 总结

已成功实现 Component Retrieval & Ranking System（构件检索与排序系统）：

- ✅ **ComponentQuery** - AI 的查询模型
- ✅ **ComponentScorer** - 四维评分系统
- ✅ **ComponentRetriever** - 检索和排序逻辑
- ✅ **AI-first 设计** - 构件库的第一使用者是 AI

**这一层完成后，系统立刻获得的能力**：
- ✅ AI 可以从构件库中自动选择最合适的构件
- ✅ 支持多维度评分（语义、上下文、风格、可变形程度）
- ✅ 返回排序后的候选列表，AI 可以灵活选择
- ✅ 支持快速语义检索和完整查询
- ✅ 为后续 SocketProvider 提供了"服务对象"

**这是 AI 选择构件的核心逻辑，是 Formacraft 从"自动建筑模组"到"AI-first 建筑系统"的关键差异点。**

---

**实现时间**: 2026-01-14  
**版本**: v1.0  
**状态**: ✅ 核心功能完成，可用于 AI 系统集成
