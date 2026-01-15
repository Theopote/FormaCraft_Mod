# SocketMatcher v1 实现总结

## 📋 实现内容

根据建议，已成功实现 SocketMatcher v1 的完整实现，目标非常明确：

在一堆 Socket 中，找出"最适合当前 Component（或 AI 需求）"的那些 Socket，并给出可解释的评分。

这一步一旦完成，门/窗/栏杆/柱子/装饰都会"自动插对位置"，这是 Formacraft 从"能用"到"像专业建筑师"的关键跃迁。

## 🎯 核心目标

**设计目标（对齐你前面的系统）**：

**输入**：
- Socket（来自 SocketProvider v1）
- ComponentPlacementSpec（你前面已经设计的 attachment / context / facing policy）
- 可选：AI 语义偏好（例如 "prefer exterior", "near path"）

**输出**：
- 排好序的 MatchResult
- 每个结果包含：
  - 是否合法
  - 综合评分
  - 失败原因（debug / AI 解释用）

## ✅ 已实现的组件

### 1. SocketMatchReason（Socket 匹配原因）

**位置**：`src/main/java/com/formacraft/common/component/socket/match/SocketMatchReason.java`

**核心功能**：
- 可解释性非常重要
- 用于解释为什么一个 Socket 匹配成功或失败，便于 debug 和 AI 解释

**枚举值**：
- `TYPE_MISMATCH` - Socket 类型不匹配
- `CONTEXT_MISMATCH` - 上下文不匹配（interior/exterior）
- `ATTACHMENT_NOT_ALLOWED` - Attachment 不允许
- `FACING_NOT_ALLOWED` - Facing 不允许
- `SIZE_TOO_SMALL` - 尺寸太小
- `SIZE_TOO_LARGE` - 尺寸太大
- `INTERIOR_NOT_ALLOWED` - 内部不允许
- `EXTERIOR_NOT_ALLOWED` - 外部不允许
- `OCCUPIED` - Socket 已被占用
- `OK` - 匹配成功

### 2. SocketMatchScore（Socket 匹配评分）

**位置**：`src/main/java/com/formacraft/common/component/socket/match/SocketMatchScore.java`

**核心功能**：
- 评分拆分，便于以后调权重
- 将匹配评分拆分为多个维度
- 每个维度独立评分，便于调试和调整权重

**评分维度**：
- `typeScore` - Socket 类型匹配分数
- `sizeScore` - 尺寸匹配分数
- `facingScore` - Facing 匹配分数
- `contextScore` - 上下文匹配分数
- `distanceScore` - 距离分数（越接近 focus 越好）

**关键方法**：
- `total()` - 计算总分

### 3. SocketMatchResult（Socket 匹配结果）

**位置**：`src/main/java/com/formacraft/common/component/socket/match/SocketMatchResult.java`

**核心功能**：
- 包含匹配结果的所有信息

**关键字段**：
- `socket` - Socket
- `valid` - 是否合法
- `score` - 综合评分
- `reasons` - 失败原因（debug / AI 解释用）

### 4. SocketMatcher（核心匹配逻辑）

**位置**：`src/main/java/com/formacraft/common/component/socket/match/SocketMatcher.java`

**核心功能**：
- 这是 v1 的"心脏"
- 在一堆 Socket 中，找出"最适合当前 Component（或 AI 需求）"的那些 Socket

**v1 权重**（以后可以做成 config / style profile）：
- `W_TYPE = 5.0` - Socket 类型匹配权重
- `W_SIZE = 3.0` - 尺寸匹配权重
- `W_FACING = 2.0` - Facing 匹配权重
- `W_CONTEXT = 2.0` - 上下文匹配权重
- `W_DISTANCE = 1.0` - 距离权重

**匹配流程**：
1. **Occupied** - 检查是否被占用
2. **SocketType × AttachmentType** - 检查类型兼容性
3. **Context** - 检查上下文兼容性（Interior / Exterior / Edge）
4. **FacingPolicy** - 检查 Facing 兼容性
5. **Size** - 计算尺寸评分（粗略：用 bounds）
6. **Distance** - 计算距离评分（越接近 focus 越好）

**排序规则**：
- 合法优先
- 其次按 total score 降序

**关键方法**：
- `match(List<Socket>, ComponentPlacementSpec, Vec3d)` - 匹配 Socket 列表与 ComponentPlacementSpec
- `match(List<Socket>, ComponentDefinition, Vec3d)` - 匹配 Socket 列表与 ComponentDefinition

### 5. SocketMatcher（向后兼容）

**位置**：`src/main/java/com/formacraft/common/component/socket/SocketMatcher.java`

**核心功能**：
- 保留用于向后兼容
- 内部使用新的详细匹配逻辑
- 只返回合法的 Socket 列表

## 📊 使用示例

### 示例 1：门 / 窗自动匹配

```java
// 门/窗只会匹配 WALL_OPENING
ComponentPlacementSpec doorSpec = new ComponentPlacementSpec();
doorSpec.attachment = AttachmentType.WALL_OPENING;
doorSpec.requiresOpening = true;

// 收集 Socket
List<Socket> sockets = SocketProviders.collect(world, ctx);

// 匹配
List<SocketMatchResult> results = SocketMatcher.match(sockets, doorSpec, focus);

// 结果：
// - 只会匹配 WALL_OPENING
// - 自动拒绝地面 / 边缘
// - 自动对齐墙法线
```

### 示例 2：栏杆 / 女儿墙自动匹配

```java
// 栏杆/女儿墙只会匹配 EDGE_OUTER
ComponentPlacementSpec railingSpec = new ComponentPlacementSpec();
railingSpec.attachment = AttachmentType.EDGE;
railingSpec.requireEdge = true;

// 匹配
List<SocketMatchResult> results = SocketMatcher.match(sockets, railingSpec, focus);

// 结果：
// - 只会匹配 EDGE_OUTER
// - 沿 Outline / Path 自动走
```

### 示例 3：柱子 / 斗拱自动匹配

```java
// 柱子/斗拱可放在地面、室内、室外
ComponentPlacementSpec columnSpec = new ComponentPlacementSpec();
columnSpec.attachment = AttachmentType.FLOOR; // 或 NONE

// 匹配
List<SocketMatchResult> results = SocketMatcher.match(sockets, columnSpec, focus);

// 结果：
// - AttachmentType.FLOOR 或 FREE
// - 可放在地面、室内、室外
```

### 示例 4：AI 自动装配

```java
// AI 只需说："use door component"
// 后端：
// ComponentQuery → ComponentPlacementSpec
// SocketProviders → sockets
// SocketMatcher → best socket
// Variant → Patch

ComponentQuery query = parseFromJson(llmOutput.component_query);
ComponentPlacementSpec spec = inferPlacementSpec(query);

List<Socket> sockets = SocketProviders.collect(world, ctx);
List<SocketMatchResult> results = SocketMatcher.match(sockets, spec, focus);

// 选择最佳匹配
SocketMatchResult best = results.stream()
    .filter(r -> r.valid)
    .findFirst()
    .orElse(null);

if (best != null) {
    // 生成变体并放置
    ComponentVariant variant = VariantGenerator.generate(component, query, random);
    // ... 放置到 best.socket
}
```

### 示例 5：ComponentTool hover 高亮

```java
// 在 ComponentTool 的 tick() 方法中
ComponentDefinition component = ComponentTool.INSTANCE.getLoadedComponent();
Vec3d focus = hitPosVec;

// 获取匹配结果（带评分）
List<SocketMatchResult> results = SocketHighlighter.getValidSockets(
    component, null, focus
);

// 渲染高亮
SocketHighlighter.renderHighlights(client, results);

// 结果：
// - 合法位置：绿色半透明（根据 score.total() 调整亮度）
// - 非法位置：红色半透明（根据 reasons 显示原因）
```

## 🔄 完整数据流

```
[ Socket 列表（来自 SocketProviders）]
        ↓
SocketMatcher.match()
        ↓
[ 对每个 Socket 进行详细匹配 ]
        ↓
[ 计算各维度评分（type, size, facing, context, distance）]
        ↓
[ 生成 MatchResult（valid, score, reasons）]
        ↓
[ 排序（合法优先，其次按总分降序）]
        ↓
[ MatchResult 列表 ]
        ↓
[ AI 自动装配 / ComponentTool hover 高亮 ]
```

## 🎯 这套 SocketMatcher v1 能立即解决什么？

### ✅ 门 / 窗

- 只会匹配 WALL_OPENING
- 自动拒绝地面 / 边缘
- 自动对齐墙法线

### ✅ 栏杆 / 女儿墙

- 只会匹配 EDGE_OUTER
- 沿 Outline / Path 自动走

### ✅ 柱子 / 斗拱

- AttachmentType.FLOOR 或 FREE
- 可放在地面、室内、室外

### ✅ AI 自动装配

- AI 只需说："use door component"
- 后端：
  - ComponentQuery → ComponentPlacementSpec
  - SocketProviders → sockets
  - SocketMatcher → best socket
  - Variant → Patch

## ✅ 完成度

| 组件 | 状态 | 说明 |
|------|------|------|
| SocketMatchReason | ✅ 完成 | 可解释的匹配原因枚举 |
| SocketMatchScore | ✅ 完成 | 评分拆分（type, size, facing, context, distance） |
| SocketMatchResult | ✅ 完成 | 匹配结果（socket, valid, score, reasons） |
| SocketMatcher（新） | ✅ 完成 | 核心匹配逻辑（详细评分） |
| SocketMatcher（兼容） | ✅ 完成 | 向后兼容方法 |
| SocketHighlighter（更新） | ✅ 完成 | 使用新的详细匹配逻辑 |

## 🎉 总结

已成功实现 SocketMatcher v1 的完整实现：

- ✅ **SocketMatchReason** - 可解释的匹配原因枚举
- ✅ **SocketMatchScore** - 评分拆分（type, size, facing, context, distance）
- ✅ **SocketMatchResult** - 匹配结果（socket, valid, score, reasons）
- ✅ **SocketMatcher（新）** - 核心匹配逻辑（详细评分）
- ✅ **SocketMatcher（兼容）** - 向后兼容方法
- ✅ **SocketHighlighter（更新）** - 使用新的详细匹配逻辑

**这一层完成后，系统立刻获得的能力**：
- ✅ 在一堆 Socket 中，找出"最适合当前 Component（或 AI 需求）"的那些 Socket
- ✅ 给出可解释的评分（便于 debug 和 AI 解释）
- ✅ 门/窗/栏杆/柱子/装饰都会"自动插对位置"
- ✅ 这是 Formacraft 从"能用"到"像专业建筑师"的关键跃迁

**这是 SocketMatcher v1 的完整实现，稳定、好用、可解释。**

---

**实现时间**: 2026-01-14  
**版本**: v1.0  
**状态**: ✅ 核心功能完成，已集成到 SocketMatcher 和 SocketHighlighter
