# Component → Patch 编译器实现总结

## 📋 实现内容

根据建议，已成功实现 Component → Patch 编译器系统，采纳了以下核心思想：

1. ✅ **VoxelPlan 中间表示** - 使用语义块而非具体方块
2. ✅ **ComponentVoxelizer** - ComponentDefinition → VoxelPlan
3. ✅ **PaletteResolver** - 语义块 → BlockState
4. ✅ **PatchDiffGenerator** - VoxelPlan → BlockPatch
5. ✅ **ComponentPlanCompiler** - 主入口，完整编译流程

## ✅ 已实现的组件

### 1. VoxelPlan（中间表示）

**位置**：`src/main/java/com/formacraft/common/compiler/voxel/VoxelPlan.java`

**核心思想**：
- 组件 ≠ 方块集合，而是"可编译的几何语义"
- 使用相对 anchor 的坐标（Vec3i = dx, dy, dz）
- 存储语义块（SemanticBlock），不是具体方块 ID

**关键方法**：
- `put(int dx, int dy, int dz, SemanticBlock block)` - 添加体素
- `blocks()` - 获取所有体素（不可修改）
- `isEmpty()` / `size()` - 状态查询

### 2. SemanticBlock（语义块）

**位置**：`src/main/java/com/formacraft/common/compiler/voxel/SemanticBlock.java`

**核心思想**：
- 绝不出现 minecraft:block_id
- 使用语义部位（semanticPart）和调色板槽位（paletteSlot）
- 由 PaletteResolver 在最后阶段解析为实际 BlockState

**关键方法**：
- `fromBlockEntry(BlockEntry)` - 从 ComponentDefinition.BlockEntry 创建
- `inferSemanticPart(String blockId)` - 从 block ID 推断语义部位
- `inferPaletteSlot(String semanticPart)` - 从语义部位推断调色板槽位

### 3. ComponentVoxelizer（体素化器）

**位置**：`src/main/java/com/formacraft/common/compiler/voxel/ComponentVoxelizer.java`

**核心算法**：
- 遍历 ComponentDefinition.blocks
- 应用 ComponentVariant 的缩放和镜像
- 应用 PlacementContext 的变换（方向、镜像、对称）
- 生成语义块（SemanticBlock）

**关键思想**：
- 组件本身永远是"正向 + 原点"
- 方向、镜像、对称全部由 PlacementContext 决定

**变换顺序**：
1. 应用变体缩放（scaleX, scaleY, scaleZ）
2. 应用变体镜像（mirrorX, mirrorZ）
3. 应用方向变换（facing）

### 4. PaletteResolver（调色板解析器）

**位置**：`src/main/java/com/formacraft/common/compiler/voxel/PaletteResolver.java`

**核心功能**：
- 使用 StylePalette（SemanticStyleProfile）进行语义到方块的映射
- 如果找不到调色板，回退到直接解析 block 字符串
- 支持随机选择（从 PaletteRule 的权重列表中）

**关键方法**：
- `resolve(SemanticBlock, String styleProfileId, Random)` - 解析语义块为 BlockState
- `resolveFromBlockString(String blockString)` - 从 block 字符串解析 BlockState（回退）
- `tryParseSemanticPart(String)` - 将字符串转换为 SemanticPart 枚举

### 5. PatchDiffGenerator（差异生成器）

**位置**：`src/main/java/com/formacraft/common/compiler/voxel/PatchDiffGenerator.java`

**核心思想**：
- 比较 VoxelPlan 中的语义块与世界现状
- 生成差异（diff）：place / replace / remove
- 仅描述差异（Patch），不直接 setBlock

**关键方法**：
- `diff(BlockPos origin, VoxelPlan plan, WorldView world, String styleProfileId, Random)` - 生成 BlockPatch 列表
- `blockStateToId(BlockState)` - 将 BlockState 转换为 block ID 字符串

**差异类型**：
- `place` - 当前位置是空气 → 放置目标方块
- `replace` - 当前位置有方块且不同 → 替换为目标方块
- 跳过 - 当前位置已经是目标方块 → 不生成 patch

### 6. ComponentPlanCompiler（主入口）

**位置**：`src/main/java/com/formacraft/common/compiler/component/ComponentPlanCompiler.java`

**核心职责**：
- 将 ComponentDefinition + ComponentVariant + PlacementContext 转换为 BlockPatch 列表
- 这是"组件系统真正落地为世界修改"的核心

**完整流程**：
```
ComponentDefinition
        ↓
ComponentVoxelizer.voxelize()
        ↓
VoxelPlan (logical blocks)
        ↓
PatchDiffGenerator.diff()
        ↓
List<BlockPatch>
```

**关键方法**：
- `compile(ComponentDefinition, ComponentVariant, PlacementContext, WorldView, String styleProfileId)` - 完整编译
- `compile(ComponentDefinition, ComponentVariant, PlacementContext, WorldView)` - 不使用风格配置
- `compile(ComponentDefinition, ComponentVariant, PlacementContext)` - 最简版本（仅预览）

## 🔄 数据流

### 完整编译流程

```
[ ComponentDefinition（已验证）]
        ↓
[ ComponentVariant（已解析/确定）]
        ↓
[ PlacementContext（anchor / facing / surface）]
        ↓
ComponentVoxelizer.voxelize()
        ↓
[ VoxelPlan（语义块，相对 anchor）]
        ↓
PatchDiffGenerator.diff()
        ↓
[ List<BlockPatch>（差异，相对 origin）]
        ↓
[ Preview / Apply / Undo / Redo / Memory Update ]
```

### 语义块解析流程

```
[ SemanticBlock（semanticPart + paletteSlot）]
        ↓
PaletteResolver.resolve()
        ↓
[ 尝试使用 SemanticStyleProfile ]
        ↓
[ 如果失败，回退到 block 字符串解析 ]
        ↓
[ BlockState ]
```

## 🎯 核心设计思想

### 1. 组件 ≠ 方块集合

**建议**：组件是"可编译的几何语义"

**实现**：
- ✅ 使用 VoxelPlan 作为中间表示
- ✅ 存储语义块（SemanticBlock），不是具体方块 ID
- ✅ 由 PaletteResolver 在最后阶段解析为实际 BlockState

### 2. Patch 天生支持 Undo / Preview

**建议**：因为它是 diff，不是 set

**实现**：
- ✅ PatchDiffGenerator 生成差异（place / replace / remove）
- ✅ 不直接 setBlock，仅描述差异
- ✅ 可用于 Preview / Apply / Undo / Redo / Memory Update

### 3. 工具系统天然可插拔

**建议**：Selection → PlacementContext, Symmetry → transformLocal, ForbiddenZone → PatchFilter

**实现**：
- ✅ PlacementContext 作为放置上下文
- ✅ ComponentVoxelizer 应用 PlacementContext 的变换
- ✅ PatchDiffGenerator 生成差异，可由 PatchFilter 进一步过滤

### 4. 风格系统完美解耦

**建议**：换 Palette ≠ 换几何

**实现**：
- ✅ VoxelPlan 存储语义块，不存储具体方块
- ✅ PaletteResolver 使用 StylePalette 进行映射
- ✅ 可以轻松切换不同的风格配置，而不改变几何结构

## 📊 使用示例

### 示例 1：基本编译

```java
// 准备输入
ComponentDefinition component = ...; // 已验证
ComponentVariant variant = ...; // 已解析
PlacementContext ctx = new PlacementContext(targetPos, hitFace);

// 编译为 BlockPatch
List<BlockPatch> patches = ComponentPlanCompiler.compile(
    component,
    variant,
    ctx,
    world,
    "MEDIEVAL_CASTLE" // styleProfileId
);

// 使用 patches
// - Preview: BuildConfirmPanel.showPatchPreview(origin, patches)
// - Apply: PatchExecutor.apply(world, origin, patches)
// - Filter: PatchFilterPipeline.filter(patches, origin, context)
```

### 示例 2：预览（不使用世界）

```java
// 预览模式（不读取世界状态）
List<BlockPatch> patches = ComponentPlanCompiler.compile(
    component,
    variant,
    ctx
    // 不提供 world，所有操作都是 place
);

// 显示预览
BuildConfirmPanel.INSTANCE.showPatchPreview(origin, patches);
```

### 示例 3：应用变体（缩放、镜像）

```java
// 创建变体
ComponentVariant variant = new ComponentVariant();
variant.params = new ComponentVariant.Params();
variant.params.scale = new ComponentVariant.Params.Scale();
variant.params.scale.x = 2; // 2倍宽度
variant.params.scale.y = 1; // 高度不变
variant.params.scale.z = 2; // 2倍深度
variant.params.mirror = "X"; // 镜像 X 轴

// 编译
List<BlockPatch> patches = ComponentPlanCompiler.compile(
    component,
    variant,
    ctx,
    world,
    null
);
```

## 🔗 与现有系统的对接点

| 模块 | 对接方式 | 说明 |
|------|---------|------|
| BuildConfirmPanel | 直接用 patchList | `showPatchPreview(origin, patches)` |
| PatchPreviewState | 无改动 | 直接使用 BlockPatch 列表 |
| MemoryManager | 保存 Component + Variant | 保存编译输入，而非输出 |
| PromptAssembler | 输出 Component + Variant | AI 输出语义，而非方块 |
| ToolPanel | 一键测试放置 | 调用 `ComponentPlanCompiler.compile()` |
| PatchFilterPipeline | 过滤 BlockPatch | 在编译后应用过滤器 |

## ✅ 完成度

| 组件 | 状态 | 说明 |
|------|------|------|
| VoxelPlan | ✅ 完成 | 中间表示（语义块） |
| SemanticBlock | ✅ 完成 | 语义块定义 |
| ComponentVoxelizer | ✅ 完成 | Component → VoxelPlan |
| PaletteResolver | ✅ 完成 | 语义块 → BlockState |
| PatchDiffGenerator | ✅ 完成 | VoxelPlan → BlockPatch |
| ComponentPlanCompiler | ✅ 完成 | 主入口，完整编译流程 |

## 🎉 总结

已成功实现 Component → Patch 编译器系统：

- ✅ **VoxelPlan 中间表示** - 使用语义块而非具体方块
- ✅ **ComponentVoxelizer** - 应用变体和变换
- ✅ **PaletteResolver** - 语义到方块的映射
- ✅ **PatchDiffGenerator** - 生成差异
- ✅ **ComponentPlanCompiler** - 完整编译流程

**核心思想已实现**：
- ✅ 组件 ≠ 方块集合，而是"可编译的几何语义"
- ✅ Patch 天生支持 Undo / Preview（因为它是 diff）
- ✅ 工具系统天然可插拔（通过 PlacementContext）
- ✅ 风格系统完美解耦（换 Palette ≠ 换几何）

**这是"组件系统真正落地为世界修改"的核心，也是整个 Formacraft 架构里最重要的一环之一。**

---

**实现时间**: 2026-01-14  
**版本**: v1.0  
**状态**: ✅ 核心功能完成，可用于 Preview / Apply / Undo / Redo
