# 语义组件系统实现总结

## 实现状态

### ✅ 已完全实现

建议中的语义组件系统已经全部实现：

1. ✅ **SemanticPlacementOp** - 语义放置指令
2. ✅ **SemanticPart** - 语义部位枚举
3. ✅ **SemanticRole** - 语义角色枚举
4. ✅ **PaletteCatalog.WeightedBlock** - 加权方块
5. ✅ **SemanticBlockStateResolver** - 语义 → BlockPatch 解析器
6. ✅ **ISkeletonSemanticGenerator** - 语义生成器接口
7. ✅ **LinearPathSemanticGenerator** - LINEAR_PATH 语义生成器
8. ✅ **SkeletonSemanticRegistry** - 语义生成器注册表
9. ✅ **SkeletonBuildPipeline** - 完整流程

> 2026-11-15 核对：原列表中的独立类 `WeightedPicker` 与 `SemanticResolver` **已删除**。
> 2026-11-15 第二轮核对：`SemanticPalette` / `SemanticPaletteRegistry` /
> `DefaultPalettes` / `common.palette.WeightedBlock` **也已删除**（只写死簇，
> 注册后从未被查询）。现行调色板系统是 `PaletteCatalog` / `PaletteRegistry`
> （数据驱动，资源文件 `palette_catalog.json`），风格换皮走
> `SemanticStyleProfile` / `SemanticPaletteResolver`。

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

**WeightedBlock**（加权方块，blockId + weight）：

> 2026-11-15 核对：原独立类 `WeightedPicker`（`common/palette/WeightedPicker.java`）
> **已删除**。加权抽样现由持有 `List<WeightedBlock>` 的调色板与解析器内部完成，
> 不得再引用 `WeightedPicker`。
>
> 2026-11-15 第二轮核对：顶层类 `common/palette/WeightedBlock.java`
> （`record WeightedBlock(String blockId, int weight)`）**已删除**。
> 现行加权方块是 `PaletteCatalog.WeightedBlock`（嵌套类，字段 `id` / `weight`），
> 由 `PaletteRegistry` 从资源加载、`PaletteResolver` 抽样。

### 4. 解析器

**SemanticBlockStateResolver**（现行）：
> 2026-11-15 核对：原 `SemanticResolver`（`server/skeleton/gen/SemanticResolver.java`）
> **已删除**，无生产调用者。现行解析入口是
> `server/skeleton/gen/palette/SemanticBlockStateResolver.resolveToPatches(...)`。
- 输入：patchOrigin + semanticOps + paletteId + random
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
List<BlockPatch> patches = SemanticBlockStateResolver.resolveToPatches(
    origin,
    ops,
    "DEFAULT",  // paletteId
    random
);
```

### 示例 3：完整流程

```java
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
- 新增调色板：只需往 `palette_catalog.json` 资源里加一条，并由
  `PaletteRegistry` 加载
- 新增生成器：实现 ISkeletonSemanticGenerator 并注册

### ✅ 风格统一

- 同一个 Palette 可以用于多个 Generator
- 风格变化只需修改 Palette，不需要修改 Generator

## 与现有系统的关系

### 现有系统（现行）

- `PaletteCatalog` / `PaletteRegistry`：使用字符串键的调色板系统
- `PaletteResolver`：直接返回 BlockState 的解析器
- `SemanticStyleProfile` / `SemanticPaletteResolver`：按 `SemanticPart` 绑定
  BlockState 的风格换皮系统

### 新系统

- `SemanticBlockStateResolver`：返回 BlockPatch 的解析器（取代已删除的 `SemanticResolver`）

**说明**：原"新系统"的 `SemanticPalette` / `SemanticPaletteRegistry` 已删除
（只写死簇）。Skeleton Generator 现行使用的调色板是上面那套
`SemanticStyleProfile` + `PaletteResolver` 体系。

## 初始化

在 mod 初始化时调用：

```java
// 在 FormacraftMod.onInitialize() 或 ServerInitializer.onInitializeServer()
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

