# Socket → Patch Anchor 对齐系统（H3）实现总结

## 📋 实现内容

根据建议，已成功实现 Socket → Patch Anchor 对齐系统（H3），这是整个"语义 → 几何 → 方块"链路里最关键的一环之一。

## 🎯 核心目标

**H3 目标回顾（一句话）**：

给定一个 Socket + ComponentPlacementSpec + Component 本地锚点定义 → 计算出：

- Patch Origin（世界坐标）
- Rotation（朝向）
- Mirror（是否镜像）
- Local → World 的 Transform

**这一步解决了哪些"之前很难的问题"**：
- ✅ 门窗自动对齐墙（不再需要硬编码方向，内外由 FacingPolicy 控制）
- ✅ 栏杆/女儿墙沿边缘走（socket.center + normal 决定朝向，Patch 直接连续生成）
- ✅ 装饰构件智能贴墙（不需要玩家指定"北/南/东/西"，socket.normal 就是答案）
- ✅ 后续支持这些都很自然（对称、Path 连续布置、Outline 裁剪）

## ✅ 已实现的组件

### 1. ComponentAnchor（构件自身的锚点定义）

**位置**：`src/main/java/com/formacraft/common/component/anchor/ComponentAnchor.java`

**核心功能**：
- 这是构件库必须提供的东西
- 所有坐标都在 Component 本地空间中

**关键字段**：
- `localX / localY / localZ` - 本地原点（通常是构件的几何中心或基准点）
- `facing` - 构件的"朝外法线"（用于对齐 socket.normal）

**设计思想**：
- 门、窗：anchor 在"洞口中心"，facing = OUT
- 柱子：anchor 在底部中心，facing = UP
- 装饰：anchor 在贴墙面，facing = OUT

**关键方法**：
- `fromDefinition(ComponentDefinition.Anchor)` - 从 ComponentDefinition.Anchor 创建 ComponentAnchor

### 2. ComponentInstanceTransform（给 PatchCompiler 用）

**位置**：`src/main/java/com/formacraft/common/component/socket/place/ComponentInstanceTransform.java`

**核心功能**：
- 这是 H3 的最终产物：把"一个抽象的 Socket"，变成"Component 能真正落到世界里的 Patch 原点 + 变换"

**关键字段**：
- `origin` - Patch 原点（世界坐标）
- `facing` - 朝向（世界）
- `mirrorX / mirrorZ` - 是否镜像 X/Z 轴

**关键方法**：
- `toComponentTransform()` - 转换为 ComponentTransform（用于兼容现有代码）

### 3. SocketAnchorResolver（Socket 锚点解析器）

**位置**：`src/main/java/com/formacraft/common/component/socket/place/SocketAnchorResolver.java`

**核心功能**：
- 这是 H3 的"心脏"
- 给定一个 Socket + ComponentPlacementSpec + Component 本地锚点定义
- 计算出：Patch Origin（世界坐标）、Rotation（朝向）、Mirror（是否镜像）、Local → World 的 Transform

**解析流程**：
1. **Socket 世界中心** - 获取 socket.center()
2. **计算旋转** - 把 component.anchor.facing 对齐 socket.normal（根据 FacingPolicy）
3. **计算本地锚点旋转后的偏移** - 旋转 anchor 的本地坐标
4. **Patch 原点** - socketCenter - localOffset
5. **镜像** - v1：默认不开，交给 SymmetryTool

**关键方法**：
- `resolve(Socket, ComponentAnchor, ComponentPlacementSpec)` - 解析 Socket 到 Component 实例变换
- `resolveFacing(Socket, ComponentAnchor, ComponentPlacementSpec)` - 解析朝向（根据 FacingPolicy）
- `rotateLocalOffset(ComponentAnchor, Direction)` - 把 component 的本地 anchor 坐标，旋转到世界朝向
- `rotationSteps(Direction, Direction)` - 返回从 from 旋转到 to 的顺时针步数（0..3）

**FacingPolicy 处理**：
- `NONE` - 使用构件默认朝向
- `DERIVED_FROM_HOST, OUTWARD_NORMAL` - 朝向外法线（socket.normal）
- `ALONG_EDGE` - 沿边缘（使用 socket.tangent，如果有）
- `USER_DEFINED` - 用户定义，使用构件默认朝向

## 📊 使用示例

### 示例 1：门窗自动对齐墙

```java
// 门/窗的 ComponentPlacementSpec
ComponentPlacementSpec doorSpec = new ComponentPlacementSpec();
doorSpec.attachment = AttachmentType.WALL_OPENING;
doorSpec.requiresOpening = true;
doorSpec.facingPolicy = FacingPolicy.OUTWARD_NORMAL;

// 构件的锚点定义
ComponentAnchor anchor = ComponentAnchor.fromDefinition(component.anchor);
// anchor = (1, 1, 0, SOUTH) // 洞口中心，朝外

// Socket（来自 WALL_OPENING）
Socket socket = ...; // socket.normal = NORTH（墙面向北）

// 解析变换
ComponentInstanceTransform transform = SocketAnchorResolver.resolve(
    socket, anchor, doorSpec
);

// 结果：
// - transform.origin = socket.center() - rotated(anchor)
// - transform.facing = NORTH（自动对齐墙法线）
// - 不再需要硬编码方向
```

### 示例 2：栏杆/女儿墙沿边缘走

```java
// 栏杆的 ComponentPlacementSpec
ComponentPlacementSpec railingSpec = new ComponentPlacementSpec();
railingSpec.attachment = AttachmentType.EDGE;
railingSpec.requireEdge = true;
railingSpec.facingPolicy = FacingPolicy.ALONG_EDGE;

// Socket（来自 EDGE_OUTER，沿轮廓）
Socket socket = ...; // socket.normal = UP, socket.tangent = EAST

// 解析变换
ComponentInstanceTransform transform = SocketAnchorResolver.resolve(
    socket, anchor, railingSpec
);

// 结果：
// - transform.facing = EAST（沿边缘走向）
// - Patch 直接连续生成
```

### 示例 3：装饰构件智能贴墙

```java
// 装饰的 ComponentPlacementSpec
ComponentPlacementSpec decorSpec = new ComponentPlacementSpec();
decorSpec.attachment = AttachmentType.WALL_SURFACE;
decorSpec.facingPolicy = FacingPolicy.OUTWARD_NORMAL;

// Socket（来自 WALL_SURFACE）
Socket socket = ...; // socket.normal = SOUTH（墙面向南）

// 解析变换
ComponentInstanceTransform transform = SocketAnchorResolver.resolve(
    socket, anchor, decorSpec
);

// 结果：
// - transform.facing = SOUTH（自动对齐墙法线）
// - 不需要玩家指定"北/南/东/西"，socket.normal 就是答案
```

### 示例 4：完整调用链

```java
// ComponentQuery
ComponentQuery query = ...;

// ComponentRanker
List<ScoredComponent> ranked = ComponentRanker.rank(query, candidates);

// ComponentPlacementSpec
ComponentPlacementSpec spec = bestComponent.placementSpec;

// SocketProvider → List<Socket>
List<Socket> sockets = SocketProviders.collect(world, ctx);

// SocketMatcher (H2)
List<SocketMatchResult> matchResults = SocketMatcher.match(sockets, spec, focus);
Socket bestSocket = matchResults.get(0).socket;

// SocketAnchorResolver (H3) ✅
ComponentAnchor anchor = ComponentAnchor.fromDefinition(bestComponent.anchor);
ComponentInstanceTransform transform = SocketAnchorResolver.resolve(
    bestSocket, anchor, spec
);

// ComponentVariantCompiler
ComponentVariant variant = VariantGenerator.generate(bestComponent, query, random);

// PatchList
List<BlockPatch> patches = ComponentPlanCompiler.compile(
    bestComponent, variant, transform, ...
);
```

## 🔄 完整调用链（现在已经打通）

```
ComponentQuery
   ↓
ComponentRanker
   ↓
ComponentPlacementSpec
   ↓
SocketProvider → List<Socket>
   ↓
SocketMatcher (H2)
   ↓
Best Socket
   ↓
SocketAnchorResolver (H3) ✅
   ↓
ComponentInstanceTransform
   ↓
ComponentVariantCompiler
   ↓
PatchList
```

## ✅ 完成度

| 组件 | 状态 | 说明 |
|------|------|------|
| ComponentAnchor | ✅ 完成 | 构件自身的锚点定义 |
| ComponentInstanceTransform | ✅ 完成 | 给 PatchCompiler 用 |
| SocketAnchorResolver | ✅ 完成 | H3 的"心脏"（核心算法） |
| AutoAssembler（更新） | ✅ 完成 | 使用 SocketAnchorResolver |

## 🎉 总结

已成功实现 Socket → Patch Anchor 对齐系统（H3）：

- ✅ **ComponentAnchor** - 构件自身的锚点定义
- ✅ **ComponentInstanceTransform** - 给 PatchCompiler 用
- ✅ **SocketAnchorResolver** - H3 的"心脏"（核心算法）
- ✅ **AutoAssembler（更新）** - 使用 SocketAnchorResolver

**这一层完成后，系统立刻获得的能力**：
- ✅ 给定一个 Socket + ComponentPlacementSpec + Component 本地锚点定义 → 计算出 Patch Origin、Rotation、Mirror、Local → World 的 Transform
- ✅ 门窗自动对齐墙（不再需要硬编码方向，内外由 FacingPolicy 控制）
- ✅ 栏杆/女儿墙沿边缘走（socket.center + normal 决定朝向，Patch 直接连续生成）
- ✅ 装饰构件智能贴墙（不需要玩家指定"北/南/东/西"，socket.normal 就是答案）
- ✅ 后续支持这些都很自然（对称、Path 连续布置、Outline 裁剪）

**这是整个"语义 → 几何 → 方块"链路里最关键的一环之一。**

---

**实现时间**: 2026-01-14  
**版本**: v1.0  
**状态**: ✅ 核心功能完成，已集成到 AutoAssembler
