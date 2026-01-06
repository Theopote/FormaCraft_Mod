# 语义组件系统实现总结

## 实现状态

### ✅ 已完全实现

建议中的语义组件系统已经全部实现：

1. ✅ **SemanticPlacementOp** - 语义放置指令
2. ✅ **SemanticPart** - 语义部位枚举
3. ✅ **SemanticRole** - 语义角色枚举
4. ✅ **WeightedBlock** - 加权方块
5. ✅ **WeightedPicker** - 加权随机选择器
6. ✅ **SemanticPalette** - 语义调色板
7. ✅ **SemanticPaletteRegistry** - 调色板注册表
8. ✅ **SemanticResolver** - 语义解析器（SemanticPlacementOp → BlockPatch）
9. ✅ **DefaultPalettes** - 默认调色板
10. ✅ **ISkeletonSemanticGenerator** - 语义生成器接口
11. ✅ **LinearPathSemanticGenerator** - LINEAR_PATH 语义生成器
12. ✅ **SkeletonSemanticRegistry** - 语义生成器注册表
13. ✅ **SkeletonBuildPipeline** - 完整流程

## 核心组件

### 1. 语义枚举

**SemanticPart**（语义部位）：
- 通用结构：FOUNDATION, WALL, PILLAR, BEAM, FLOOR, ROOF, TRIM
- 道路/桥：PATH_BASE, PATH_EDGE, STAIRS, RAILING
- 开口：DOORWAY, WINDOW
- 防御类：BATTLEMENT, TOWER_CORE, TOWER_TRIM
- 装饰/光源：LIGHT, DECOR

**SemanticRole**（语义角色）：
- FILL, EDGE, CORNER, TRIM, SUPPORT, DETAIL, OPENING

### 2. 语义放置指令

**SemanticPlacementOp**：
- `BlockPos pos` - 位置
- `SemanticPart part` - 语义部位
- `SemanticRole role` - 语义角色
- `Set<String> tags` - 可选标签

### 3. 调色板系统

**WeightedBlock**：加权方块（blockId + weight）

**WeightedPicker**：加权随机选择器

**SemanticPalette**：
- part → blocks 映射
- part + role → blocks 覆盖映射

**SemanticPaletteRegistry**：调色板注册表

### 4. 解析器

**SemanticResolver**：
- 输入：origin + semanticOps + paletteId + random
- 输出：List<BlockPatch>
- 功能：将语义放置操作转换为 BlockPatch

### 5. 生成器系统

**ISkeletonSemanticGenerator**：语义生成器接口

**LinearPathSemanticGenerator**：
- 输出 SemanticPlacementOp 而不是 BlockPatch
- 支持边缘检测（PATH_BASE + EDGE role）
- 支持边缘装饰（PATH_EDGE + TRIM role）

**SkeletonSemanticRegistry**：语义生成器注册表

### 6. 完整流程

**SkeletonBuildPipeline**：
- Skeleton → SemanticOps → Patch
- 完整的端到端流程

## 使用示例

### 示例 1：使用语义生成器

```java
// 创建语义生成器
LinearPathSemanticGenerator gen = new LinearPathSemanticGenerator();

// 创建计划
ExecutableSkeletonPlan plan = new ExecutableSkeletonPlan(SkeletonType.LINEAR_PATH)
    .put("length", 42)
    .put("width", 5)
    .put("facing", "EAST")
    .put("conformTerrain", true);

// 生成语义操作
List<SemanticPlacementOp> ops = gen.generateSemantic(ctx, plan);
```

### 示例 2：解析为 BlockPatch

```java
// 解析语义操作为 BlockPatch
List<BlockPatch> patches = SemanticResolver.resolveToPatches(
    origin,
    ops,
    "DEFAULT",  // paletteId
    random
);
```

### 示例 3：完整流程

```java
// 初始化默认调色板
DefaultPalettes.bootstrap();

// 注册语义生成器
SkeletonSemanticRegistry.registerDefaults();

// 完整流程：Skeleton → SemanticOps → Patch
List<BlockPatch> patches = SkeletonBuildPipeline.buildSkeletonAsPatch(
    ctx,
    plan,
    "DEFAULT",  // paletteId
    origin
);
```

## 设计优势

### ✅ 分离关注点

- **Generator**：只负责拓扑落地（输出语义操作）
- **Palette**：负责风格差异 + 做旧随机
- **Resolver**：统一转换为 BlockPatch

### ✅ 可扩展性

- 新增 SemanticPart：只需在枚举中添加
- 新增调色板：只需注册到 SemanticPaletteRegistry
- 新增生成器：实现 ISkeletonSemanticGenerator 并注册

### ✅ 风格统一

- 同一个 Palette 可以用于多个 Generator
- 风格变化只需修改 Palette，不需要修改 Generator

## 与现有系统的关系

### 现有系统

- `PaletteCatalog` / `PaletteRegistry`：使用字符串键的调色板系统
- `PaletteResolver`：直接返回 BlockState 的解析器

### 新系统

- `SemanticPalette` / `SemanticPaletteRegistry`：使用枚举的语义调色板
- `SemanticResolver`：返回 BlockPatch 的解析器

**两者可以共存**：
- 新系统用于 Skeleton Generator
- 旧系统用于其他生成器（HouseGenerator 等）

## 初始化

在 mod 初始化时调用：

```java
// 在 FormacraftMod.onInitialize() 或 ServerInitializer.onInitializeServer()
DefaultPalettes.bootstrap();
SkeletonSemanticRegistry.registerDefaults();
```

## 总结

✅ **完整的语义组件系统已实现**：
- 语义枚举 ✅
- 语义放置指令 ✅
- 调色板系统 ✅
- 解析器 ✅
- 生成器系统 ✅
- 完整流程 ✅

✅ **设计原则已遵循**：
- LLM 输出 Skeleton + 语义组件（而非 setBlock）
- Generator 只负责拓扑落地
- Palette 负责风格差异 + 做旧随机
- 最终统一落到 BlockPatch（已有 Preview / Diff / Apply）

这正是"AI 规划空间，系统负责落地"的完整实现！

