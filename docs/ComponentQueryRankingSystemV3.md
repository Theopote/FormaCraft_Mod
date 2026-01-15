# ComponentQuery & Ranking System v3（构件查询与排序系统 v3）实现总结

## 📋 实现内容

根据建议，已成功吸纳更简洁、数据驱动的设计，重构 ComponentQuery & Ranking System。

## 🎯 核心改进

**设计目标**：
- ✅ 完全数据驱动
- ✅ 可 JSON 映射（Gson / Jackson）
- ✅ 不包含任何"选择逻辑"
- ✅ 能被 LLM 明确输出
- ✅ 完全确定性（Deterministic）
- ✅ 所有权重集中管理
- ✅ 可以热插拔评分维度
- ✅ 输出可解释

## ✅ 已实现的组件

### 1. ComponentQuery（重构版）

**位置**：`src/main/java/com/formacraft/common/component/query/ComponentQuery.java`

**核心改进**：
- ✅ 使用 `Set<String>` 而不是 `List<String>`（更简洁，自动去重）
- ✅ `Geometry` 简化：使用 `boolean requiresOpening` 和 `Integer openingWidth/Height`，而不是嵌套的 `Opening` 类
- ✅ 所有子结构自动初始化（避免 NPE）
- ✅ 完全数据驱动，无业务逻辑

**结构**：
```java
public class ComponentQuery {
    public Semantic semantic = new Semantic();
    public Context context = new Context();
    public Geometry geometry = new Geometry();
    public Style style = new Style();
    public Constraints constraints = new Constraints();
    public UsageHint usageHint = new UsageHint();
}
```

**关键改进**：
- `Semantic.tags` 和 `Constraints.mustHave/forbiddenTags` 使用 `Set<String>`
- `Geometry` 直接使用 `boolean requiresOpening` 和 `Integer openingWidth/Height`
- 所有字段自动初始化，避免 NPE

### 2. ComponentMetadata（扩展版）

**位置**：`src/main/java/com/formacraft/common/component/query/ComponentMetadata.java`

**新增方法**：
- ✅ `PlacementSpec.isSideAllowed(String side)` - 检查是否允许指定的表面侧
- ✅ `GeometrySpec.getBaseWidth()` / `getBaseHeight()` - 便捷方法获取基础尺寸
- ✅ `isPrimary` / `isVisuallyStrong` - 用于排序的辅助字段

**关键改进**：
- `PlacementSpec` 添加 `edgePreference` 字段
- `GeometrySpec` 添加 `requiresOpening` 字段
- 添加 `isPrimary` 和 `isVisuallyStrong` 字段用于 `usageScore` 计算

### 3. ComponentRanker（新实现）

**位置**：`src/main/java/com/formacraft/common/component/rank/ComponentRanker.java`

**核心特性**：
- ✅ 完全确定性（Deterministic）
- ✅ 所有权重集中管理（v1 硬编码，未来可配置）
- ✅ 可以热插拔评分维度
- ✅ 输出可解释

**评分模型**：
```java
finalScore =
    semanticScore * 0.30 +
    placementScore * 0.25 +
    geometryScore * 0.20 +
    styleScore * 0.15 +
    usageScore * 0.10;
```

**评分逻辑**：

**1️⃣ semanticScore（我是不是你要的那个）**：
- `role` 必须完全匹配，否则返回 0.0
- `tags` 命中率：n / total

**2️⃣ placementScore（放得合不合理）**：
- `placement` 必须匹配 `allowedPlacements`
- `side` 必须通过 `isSideAllowed()` 检查
- `edgeCondition` 匹配 `edgePreference` 时 +0.2

**3️⃣ geometryScore（能不能塞进去）**：
- `requiresOpening` 必须匹配
- `scalable` 必须匹配
- `openingWidth/Height` 尺寸差距（越小越好，超出 `tolerance` 返回 0.0）

**4️⃣ styleScore（看起来像不像一套）**：
- 直接从 `styleAffinity` Map 获取，默认 0.0

**5️⃣ usageScore（主角还是配角）**：
- `primary` + `isPrimary` → +0.2 / -0.1
- `high` visibility + `isVisuallyStrong` → +0.2

**输出结构**：
```java
public record ScoredComponent(ComponentMetadata component, double score) {}
```

### 4. ComponentRetriever（集成新 Ranker）

**位置**：`src/main/java/com/formacraft/common/component/query/ComponentRetriever.java`

**核心改进**：
- ✅ 使用新的 `ComponentRanker.rank()` 方法
- ✅ 转换为 `ComponentScore` 以保持向后兼容
- ✅ 硬过滤阶段保持不变

**流程**：
1. 从 ComponentCatalog 获取所有构件
2. 硬过滤（`hardFilter()`）
3. 多维评分（`ComponentRanker.rank()`）
4. 排序（ComponentRanker 已排序）
5. 过滤低分结果（总分 < 0.3）
6. 限制结果数量

## 📊 使用示例

### 示例 1：创建 ComponentQuery

```java
ComponentQuery query = new ComponentQuery();
query.semantic.role = "door";
query.semantic.tags.add("gothic");
query.semantic.tags.add("stone");
query.context.placement = "wall";
query.context.side = "exterior";
query.geometry.requiresOpening = true;
query.geometry.openingWidth = 2;
query.geometry.openingHeight = 3;
query.geometry.tolerance = 1;
query.style.styleProfile = "Medieval_Castle";
query.usageHint.frequency = "primary";
query.usageHint.visibility = "high";
```

### 示例 2：使用 ComponentRanker

```java
// 获取候选构件元数据
List<ComponentMetadata> candidates = ...;

// 排序
List<ScoredComponent> scored = ComponentRanker.rank(query, candidates);

// 获取最佳匹配
ScoredComponent best = scored.get(0);
ComponentMetadata selected = best.component();
double score = best.score();
```

### 示例 3：JSON 序列化

```json
{
  "semantic": {
    "role": "door",
    "tags": ["gothic", "stone"],
    "importance": ["role", "placement"]
  },
  "context": {
    "placement": "wall",
    "side": "exterior",
    "heightLevel": "ground",
    "edgeCondition": "flat"
  },
  "geometry": {
    "requiresOpening": true,
    "openingWidth": 2,
    "openingHeight": 3,
    "tolerance": 1,
    "scalable": true
  },
  "style": {
    "styleProfile": "Medieval_Castle",
    "materialTone": "dark_stone"
  },
  "constraints": {
    "mustHave": ["structural"],
    "forbiddenTags": ["glass", "modern"]
  },
  "usageHint": {
    "frequency": "primary",
    "visibility": "high"
  }
}
```

## 🔄 完整数据流

```
[ AI 意图："我要一个哥特式石门" ]
        ↓
[ AI 输出 ComponentQuery（JSON）]
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
[ 选择最佳匹配或从候选列表选择 ]
```

## 🎯 设计优势

**1. 完全数据驱动**：
- ComponentQuery 不包含任何业务逻辑
- 可 JSON 序列化/反序列化
- LLM 可以直接输出

**2. 确定性评分**：
- 所有权重集中管理
- 评分逻辑清晰可解释
- 可以热插拔评分维度

**3. 向后兼容**：
- ComponentRetriever 保持原有接口
- ComponentScore 仍然可用
- 现有代码无需修改

**4. 可扩展性**：
- 未来可以添加 SocketScore
- 未来可以添加 MemoryBias（记忆偏好）
- 未来可以添加 ToolBias（选区/轮廓加权）

## ✅ 完成度

| 组件 | 状态 | 说明 |
|------|------|------|
| ComponentQuery（重构版） | ✅ 完成 | 使用 Set，简化 Geometry，自动初始化 |
| ComponentMetadata（扩展版） | ✅ 完成 | 添加 isSideAllowed, getBaseWidth/Height, isPrimary, isVisuallyStrong |
| ComponentRanker（新实现） | ✅ 完成 | 完全确定性，权重集中管理，可热插拔 |
| ComponentRetriever（集成） | ✅ 完成 | 使用新 Ranker，保持向后兼容 |

## 🎉 总结

已成功吸纳建议，重构 ComponentQuery & Ranking System v3：

- ✅ **ComponentQuery（重构版）** - 完全数据驱动，使用 Set，简化 Geometry
- ✅ **ComponentMetadata（扩展版）** - 添加便捷方法和排序辅助字段
- ✅ **ComponentRanker（新实现）** - 完全确定性，权重集中管理，可热插拔
- ✅ **ComponentRetriever（集成）** - 使用新 Ranker，保持向后兼容

**这一层完成后，系统立刻获得的能力**：
- ✅ AI 可以自由描述构件需求（完全数据驱动）
- ✅ 系统可以稳定、可控地选构件（确定性评分）
- ✅ 未来可以扩展：SocketScore、MemoryBias、ToolBias

**这是整个 Formacraft AI-first 架构真正"起飞"的关键齿轮。**

---

**实现时间**: 2026-01-14  
**版本**: v3.0  
**状态**: ✅ 核心功能完成，已集成到 ComponentRetriever
