# Semantic → Palette 映射系统实现总结

## 实现状态

### ✅ 已完全实现

建议中的 Semantic → Palette 映射系统已经全部实现：

1. ✅ **PaletteRule** - 权重随机核心（使用 BlockState）
2. ✅ **SemanticStyleProfile** - 建筑基因（SemanticPart → PaletteRule 映射）
3. ✅ **SemanticStyleProfileRegistry** - 风格配置注册表
4. ✅ **SemanticPaletteResolver** - Semantic → BlockState 执行器
5. ✅ **SemanticBlockStateResolver** - Semantic → BlockPatch（使用 BlockState）
6. ✅ **MedievalCastleProfile** - 示例风格（中世纪城堡）
7. ✅ **DefaultStyleProfiles** - 默认风格配置初始化

## 核心组件

### 1. PaletteRule（权重随机核心）

**功能**：
- 存储 BlockState 和权重的列表
- 提供加权随机选择
- 实现"做旧 / 自然感 / 非贴图化"

**使用示例**：
```java
PaletteRule rule = new PaletteRule()
    .add(Blocks.STONE_BRICKS.getDefaultState(), 70)
    .add(Blocks.CRACKED_STONE_BRICKS.getDefaultState(), 15)
    .add(Blocks.MOSSY_STONE_BRICKS.getDefaultState(), 10)
    .add(Blocks.COBBLESTONE.getDefaultState(), 5);

BlockState state = rule.pick(random);
```

### 2. SemanticStyleProfile（建筑基因）

**功能**：
- 绑定 SemanticPart 到 PaletteRule
- 可 JSON / 可代码 / 可热加载
- 这是"建筑蛋白质"的最小单位映射

**使用示例**：
```java
SemanticStyleProfile style = new SemanticStyleProfile("MEDIEVAL_CASTLE");
style.bind(SemanticPart.WALL, wallRule);
style.bind(SemanticPart.ROOF, roofRule);
```

### 3. SemanticPaletteResolver（最终执行器）

**功能**：
- 将 SemanticPlacementOp 解析为 BlockState
- 这是唯一 setBlock 的地方（通过返回 BlockState）
- 使用 StyleProfile 和 PaletteRule 进行解析

**使用示例**：
```java
SemanticPaletteResolver resolver = SemanticPaletteResolver.create("MEDIEVAL_CASTLE", random);
BlockState state = resolver.resolve(semanticOp);
```

### 4. SemanticBlockStateResolver（BlockPatch 转换）

**功能**：
- 将 SemanticPlacementOp 转换为 BlockPatch
- 使用 BlockState 而不是 String blockId
- 与现有的 BlockPatch 系统兼容

## 完整流程

```
SemanticPlacementOp
  ↓
SemanticPaletteResolver.resolve()
  ↓
PaletteRule.pick()
  ↓
BlockState
  ↓
SemanticBlockStateResolver.resolveToPatches()
  ↓
BlockPatch
  ↓
Preview / Apply
```

## 使用示例

### 示例 1：创建风格配置

```java
// 创建中世纪城堡风格
SemanticStyleProfile style = new SemanticStyleProfile("MEDIEVAL_CASTLE");

// 墙体规则
PaletteRule wallRule = new PaletteRule()
    .add(Blocks.STONE_BRICKS.getDefaultState(), 70)
    .add(Blocks.CRACKED_STONE_BRICKS.getDefaultState(), 15)
    .add(Blocks.MOSSY_STONE_BRICKS.getDefaultState(), 10)
    .add(Blocks.COBBLESTONE.getDefaultState(), 5);

style.bind(SemanticPart.WALL, wallRule);

// 注册
SemanticStyleProfileRegistry.register(style);
```

### 示例 2：使用风格配置

```java
// 初始化
DefaultStyleProfiles.bootstrap();

// 解析语义操作
List<SemanticPlacementOp> ops = ...;

// 使用 BlockState 解析器
List<BlockPatch> patches = SemanticBlockStateResolver.resolveToPatches(
    origin,
    ops,
    "MEDIEVAL_CASTLE",  // profileId
    random
);
```

### 示例 3：直接使用 BlockState

```java
// 创建解析器
SemanticPaletteResolver resolver = SemanticPaletteResolver.create("MEDIEVAL_CASTLE", random);

// 解析每个语义操作
for (SemanticPlacementOp op : semanticOps) {
    BlockState state = resolver.resolve(op);
    world.setBlockState(op.pos(), state, 3);
}
```

## 与现有系统的关系

### 现有系统

1. **SemanticPalette** - 使用 String blockId 的调色板
2. **SemanticResolver** - 使用 SemanticPalette 的解析器
3. **StyleProfile** - 用于 BuildingSpec 的风格配置

### 新系统

1. **SemanticStyleProfile** - 使用 BlockState 的风格配置
2. **PaletteRule** - 使用 BlockState 的权重规则
3. **SemanticPaletteResolver** - 使用 BlockState 的解析器

**两者可以共存**：
- 新系统用于 Skeleton/Component 生成
- 旧系统用于其他生成器（HouseGenerator 等）

## 系统优势

### ✅ AI 可以随意组合建筑

- "中世纪 + 哥特尖顶 + 现代玻璃内庭"
- 只要 Semantic 不变，Palette 就能兜底

### ✅ 同一 Blueprint 每次生成都不完全一样

- 但风格高度一致
- 这就是"基因稳定 + 表型多样"

### ✅ 风格可以热更新 / 用户自定义

- StyleProfileCatalog.json
- 玩家可以自己加"蒸汽朋克""赛博朋克"

### ✅ LLM 永远不直接接触 BlockState

- AI 只关心"这是墙 / 门 / 屋顶 / 台阶"
- 模组自己决定"用什么方块、怎么随机、什么风格"

## 初始化

在 mod 初始化时调用：

```java
// 在 ServerInitializer.onInitializeServer() 或 FormacraftMod.onInitialize()
DefaultStyleProfiles.bootstrap();
```

## 总结

✅ **完整的 Semantic → Palette 映射系统已实现**：
- PaletteRule（权重随机） ✅
- SemanticStyleProfile（风格配置） ✅
- SemanticPaletteResolver（BlockState 解析器） ✅
- SemanticBlockStateResolver（BlockPatch 转换） ✅
- 示例风格（中世纪城堡） ✅

✅ **设计优势**：
- AI 只关心语义，不关心方块
- 风格可以热更新和自定义
- 支持"基因稳定 + 表型多样"
- 这是 FormaCraft 从"会搭"进化到"会美"的分水岭

系统现在可以生成风格统一、自然变化的建筑！

