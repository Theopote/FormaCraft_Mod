# Component Socket System（构件插槽系统）实现总结

## 📋 实现内容

根据建议，已成功实现 Component Socket System，这是整个构件体系里最"建筑学"的一层。

## 🎯 核心目标

**核心思想**：
- ✅ 不是构件有方向，而是"它能附着在什么地方"
- ✅ Socket = 建筑表面的"可接受接口"
- ✅ 墙不是"一个面"，墙是一组可被插入的 socket
- ✅ 构件不是"随便贴"，构件是声明：我需要哪种 socket

**关键点**：
- 👉 AI 永远不碰 Socket
- 👉 Socket 是世界几何 + 建筑语义自动推导的
- 👉 没有 NORTH / SOUTH / EAST / WEST（方向不是 socket 的职责）

## 🏗 Socket 系统在 Formacraft 中的位置

### 完整链路

```
User Prompt
   ↓
PromptAssembler
   ↓
ComponentQuery
   ↓
ComponentRanker
   ↓
ComponentDefinition (Archetype)
   ↓
VariantGenerator
   ↓
SocketMatcher   ← ★ 新核心
   ↓
Placement / Patch
```

## ✅ 已实现的组件

### 1. SocketType（插槽类型枚举）

**位置**：`src/main/java/com/formacraft/common/component/socket/SocketType.java`

**8 种类型**：
- `WALL_SURFACE` - 墙面（可贴）
- `WALL_OPENING` - 墙洞（门/窗）
- `EDGE_OUTER` - 外轮廓边缘（栏杆/阳台）
- `ROOF_SLOPE` - 屋面
- `ROOF_RIDGE` - 屋脊
- `FLOOR_SURFACE` - 地面
- `COLUMN_TOP` - 柱顶
- `FREE_ATTACH` - 自由（装饰）

**关键设计**：
- ⚠️ 没有 NORTH / SOUTH / EAST / WEST
- 方向不是 socket 的职责

### 2. Socket（插槽类）

**位置**：`src/main/java/com/formacraft/common/component/socket/Socket.java`

**核心特性**：
- Socket ≠ 点
- Socket 是一个几何 + 语义复合体

**关键字段**：
- `type` - Socket 类型
- `bounds` - Socket 所在的世界坐标区域（Box）
- `normal` - 法线方向（仅用于"内/外"判断，不是"朝向"）
- `context` - 所属建筑语义上下文
- `occupied` - 是否被占用

**normal 的真正含义**：
- ❌ 不是"朝向"
- ✅ 是"外侧 or 内侧"

这正好对应：
- 门窗只有"内 / 外"
- 而不是"东门 / 西门"

### 3. AlignmentPolicy（对齐策略）

**位置**：`src/main/java/com/formacraft/common/component/socket/AlignmentPolicy.java`

**枚举值**：
- `CENTER` - 居中（窗、门）
- `BOTTOM` - 底部对齐（门、壁龛）
- `TOP` - 顶部对齐（飞檐、雨棚）
- `EDGE` - 边缘对齐（栏杆）

### 4. ComponentPlacementSpec（扩展版）

**位置**：`src/main/java/com/formacraft/common/component/placement/ComponentPlacementSpec.java`

**新增字段**：
- `allowedSockets` - 可接受的 socket 类型
- `requireExterior` - 是否必须在外侧
- `requireEdge` - 是否必须在边缘
- `requiresOpening` - 是否必须嵌入（门 / 窗）
- `allowMultiAttach` - 是否允许多个 socket（转角阳台）
- `alignment` - 对齐策略

**关键方法**：
- `inferAllowedSockets()` - 从 AttachmentType 推断 allowedSockets

### 5. SocketProvider（插槽提供者接口）

**位置**：`src/main/java/com/formacraft/common/component/socket/SocketProvider.java`

**核心思想**：
- Socket 不是构件定义的
- Socket 来自"建筑骨架 / Skeleton / Geometry"

**接口方法**：
- `provideSockets(BuildContext ctx)` - 提供 Socket 列表

### 6. WallSocketProvider（墙体插槽提供者）

**位置**：`src/main/java/com/formacraft/common/component/socket/WallSocketProvider.java`

**核心功能**：
- 从建筑骨架 / Skeleton / Geometry 中提取墙体的 Socket
- 生成 `WALL_SURFACE` 和 `WALL_OPENING` Socket

**示例实现**：
```java
// 从 BuildContext 中获取墙体段
for (WallSegment wall : ctx.getWalls()) {
    // 墙面 Socket
    sockets.add(new Socket(
        SocketType.WALL_SURFACE,
        wall.getSurfaceBox(),
        wall.getNormal(),
        wall.getSemanticContext()
    ));

    // 墙洞 Socket（如果有）
    if (wall.hasOpening()) {
        sockets.add(new Socket(
            SocketType.WALL_OPENING,
            wall.getOpeningBox(),
            wall.getNormal(),
            wall.getSemanticContext()
        ));
    }
}
```

### 7. SocketRegistry（插槽注册表）

**位置**：`src/main/java/com/formacraft/common/component/socket/SocketRegistry.java`

**核心功能**：
- 管理所有 SocketProvider
- 自动合并所有 Socket
- 提供 `getAllSockets(BuildContext ctx)` 方法

**初始化**：
- `initialize()` - 注册默认的 SocketProvider（WallSocketProvider）

### 8. SocketMatcher（插槽匹配器）

**位置**：`src/main/java/com/formacraft/common/component/socket/SocketMatcher.java`

**核心功能**：
- 真正的"合法性裁判"
- ComponentTool + AI 共用的核心逻辑

**匹配规则**：
1. 检查是否被占用
2. 检查 Socket 类型是否允许
3. 检查是否必须在外侧
4. 检查是否必须嵌入（门 / 窗）
5. 检查是否必须在边缘
6. 检查是否禁止内部

**关键方法**：
- `match(ComponentVariant, List<Socket>)` - 匹配构件变体与 Socket
- `match(ComponentPlacementSpec, List<Socket>)` - 匹配构件放置规格与 Socket

### 9. SocketSpecAdapter（插槽规格适配器）

**位置**：`src/main/java/com/formacraft/common/component/socket/SocketSpecAdapter.java`

**核心功能**：
- 从 AttachmentSpec 和 ComponentPlacementSpec 推断 Socket 需求
- 自动填充 `allowedSockets` 字段

### 10. SocketHighlighter（插槽高亮器）

**位置**：`src/main/java/com/formacraft/client/tool/socket/SocketHighlighter.java`

**核心功能**：
- ComponentTool 如何利用 Socket
- 高亮合法 socket 区域
- 非法位置：红色 / 不可点击

**行为**：
- 玩家选中一个 Component
- 系统：扫描世界 → SocketProvider → SocketMatcher 过滤 → 高亮合法 socket 区域

### 11. PromptAssembler 集成

**位置**：`src/main/java/com/formacraft/ai/prompt/PromptAssembler.java`

**核心功能**：
- Socket System Prompt（AI 看到约束）
- AI 不需要知道 socket，但需要知道限制结果

**关键说明**：
- AI 不需要知道 socket
- 但需要知道限制结果
- PromptAssembler 自动生成约束信息

## 📊 使用示例

### 示例 1：ComponentTool 高亮合法位置

```java
// 玩家选中一个构件
ComponentDefinition component = ComponentTool.INSTANCE.getLoadedComponent();

// 获取构建上下文
BuildContext ctx = BuildContextResolver.resolve(false);

// 获取合法的 Socket
List<Socket> validSockets = SocketHighlighter.getValidSockets(component, null, ctx);

// 渲染高亮
SocketHighlighter.renderHighlights(client, validSockets);
```

### 示例 2：SocketMatcher 匹配

```java
// 获取所有 Socket
List<Socket> allSockets = SocketRegistry.getAllSockets(ctx);

// 匹配构件与 Socket
ComponentVariant variant = VariantGenerator.generate(component, query, random);
List<Socket> validSockets = SocketMatcher.match(variant, allSockets);

// 选择最佳 Socket（例如：最近的、最大的等）
Socket bestSocket = selectBestSocket(validSockets);
```

### 示例 3：从 AttachmentSpec 推断 Socket

```java
// 构件的 AttachmentSpec
AttachmentSpec attachment = archetype.attachment;

// 自动推断 Socket 类型
Set<SocketType> socketTypes = SocketSpecAdapter.inferSocketTypes(attachment);

// 填充到 ComponentPlacementSpec
ComponentPlacementSpec spec = component.placementSpec;
spec.allowedSockets = socketTypes;
```

## 🔄 完整数据流

```
[ 建筑骨架 / Skeleton / Geometry ]
        ↓
SocketProvider.provideSockets()
        ↓
[ 生成 Socket 列表（WALL_SURFACE, WALL_OPENING, EDGE_OUTER 等）]
        ↓
SocketRegistry.getAllSockets()
        ↓
SocketMatcher.match()
        ↓
[ 过滤出合法的 Socket ]
        ↓
[ ComponentTool 高亮 / AI 选择 ]
        ↓
[ 放置构件到选定的 Socket ]
```

## 🎯 5 条判断的映射

| 你说的情况 | 系统映射 |
|------------|----------|
| 门窗只有内外 | WALL_OPENING + normal |
| 阳台只能外侧 | EDGE_OUTER + requireExterior |
| 柱子无方向 | FLOOR_SURFACE / FREE_ATTACH |
| 栏杆在边缘 | EDGE_OUTER + alignment=EDGE |
| 装饰贴墙 | WALL_SURFACE + semantic filter |

👉 没有一条是"特判"
👉 全部是数据驱动

## ✅ 完成度

| 组件 | 状态 | 说明 |
|------|------|------|
| SocketType | ✅ 完成 | 8 种插槽类型（无方向） |
| Socket | ✅ 完成 | 几何 + 语义复合体 |
| AlignmentPolicy | ✅ 完成 | 对齐策略（CENTER / BOTTOM / TOP / EDGE） |
| ComponentPlacementSpec（扩展） | ✅ 完成 | 声明需要什么 Socket |
| SocketProvider | ✅ 完成 | 插槽提供者接口 |
| WallSocketProvider | ✅ 完成 | 墙体插槽提供者（示例实现） |
| SocketRegistry | ✅ 完成 | 管理所有 SocketProvider |
| SocketMatcher | ✅ 完成 | 合法性裁判 |
| SocketSpecAdapter | ✅ 完成 | 从 AttachmentSpec 推断 Socket |
| SocketHighlighter | ✅ 完成 | ComponentTool 高亮合法位置 |
| PromptAssembler 集成 | ✅ 完成 | AI 看到约束 |

## 🎉 总结

已成功实现 Component Socket System：

- ✅ **SocketType** - 8 种插槽类型（无方向）
- ✅ **Socket** - 几何 + 语义复合体
- ✅ **AlignmentPolicy** - 对齐策略
- ✅ **ComponentPlacementSpec（扩展）** - 声明需要什么 Socket
- ✅ **SocketProvider** - 插槽提供者接口
- ✅ **WallSocketProvider** - 墙体插槽提供者
- ✅ **SocketRegistry** - 管理所有 SocketProvider
- ✅ **SocketMatcher** - 合法性裁判
- ✅ **SocketHighlighter** - ComponentTool 高亮合法位置
- ✅ **PromptAssembler 集成** - AI 看到约束

**这一层完成后，系统立刻获得的能力**：
- ✅ 不是构件有方向，而是"它能附着在什么地方"
- ✅ Socket 是世界几何 + 建筑语义自动推导的
- ✅ AI 永远不碰 Socket，但能看到约束
- ✅ ComponentTool 可以高亮合法位置，看起来非常专业
- ✅ 全部是数据驱动，没有"特判"

**这是整个构件体系里最"建筑学"的一层，是"不是构件有方向，而是它能附着在什么地方"的工程化落地。**

---

**实现时间**: 2026-01-14  
**版本**: v1.0  
**状态**: ✅ 核心功能完成，已集成到 ComponentTool 和 PromptAssembler
