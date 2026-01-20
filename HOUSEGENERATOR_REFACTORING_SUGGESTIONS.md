# HouseGenerator 重构建议：可拆分内容

## 📋 概述

`HouseGenerator` 文件当前包含 894 行代码。虽然已经进行了方法拆分，但仍有大量内容可以提取为独立的工具类或生成器类，以提高代码的可维护性和复用性。

## 🔍 可拆分的内容

### 1. **GenerationContext Record** ⭐⭐⭐
**当前位置**: Line 40-64  
**建议拆分到**: `HouseGenerationContext.java`  
**理由**:
- Record 类可以独立存在
- 可以被其他生成器复用
- 提高可读性

**建议文件路径**: `src/main/java/com/formacraft/server/generator/HouseGenerationContext.java`

---

### 2. **风格选项解析器** ⭐⭐⭐
**当前位置**: 
- `resolveDoorStyle()` (Line 187-195)
- `resolveRoofType()` (Line 200-211)
- `resolveWindowRatio()` (Line 216-242)
- `resolveWallPattern()` (Line 247-255)
- `resolvePaletteId()` (Line 274-285)

**建议拆分到**: `HouseStyleOptionsResolver.java`  
**理由**:
- 这些方法职责单一，都是解析风格选项
- 可以被其他生成器复用
- 逻辑相对独立

**建议文件路径**: `src/main/java/com/formacraft/server/generator/HouseStyleOptionsResolver.java`

**包含的方法**:
```java
public static String resolveDoorStyle(BuildingSpec spec, StyleGenome genome)
public static String resolveRoofType(BuildingSpec spec, StyleGenome genome, StyleProfile profile, BuildingStyle style)
public static double resolveWindowRatio(BuildingSpec spec, StyleGenome genome, StyleProfile profile, BuildingStyle style)
public static String resolveWallPattern(BuildingSpec spec, StyleGenome genome)
public static String resolvePaletteId(BuildingSpec spec, StyleProfile profile)
```

---

### 3. **计算辅助类** ⭐⭐
**当前位置**: 
- `calculateFloorHeight()` (Line 260-269)
- `applyPaletteToRoof()` (Line 290-307)
- `resolveActualRoofType()` (Line 715-759)

**建议拆分到**: `HouseCalculationHelper.java`  
**理由**:
- 纯计算逻辑，无副作用
- 可以被其他生成器复用

**建议文件路径**: `src/main/java/com/formacraft/server/generator/HouseCalculationHelper.java`

---

### 4. **地基生成器** ⭐⭐
**当前位置**: `generateFoundationAndPillars()` (Line 376-415)

**建议拆分到**: `HouseFoundationGenerator.java`  
**理由**:
- 职责明确：只负责生成地基和柱子
- 逻辑独立，不依赖其他生成步骤
- 可以被其他生成器复用

**建议文件路径**: `src/main/java/com/formacraft/server/generator/HouseFoundationGenerator.java`

**包含的方法**:
```java
public static void generateFoundation(List<PlannedBlock> blocks, BlockPos origin, GenerationContext ctx)
public static void generateCornerPillars(List<PlannedBlock> blocks, BlockPos origin, GenerationContext ctx)
```

---

### 5. **门生成器** ⭐⭐
**当前位置**: `processDoor()` (Line 459-496)

**建议拆分到**: `HouseDoorGenerator.java`  
**理由**:
- 门生成逻辑复杂，包含多种门样式（单门、双门、拱门）
- 可以被其他生成器复用
- 便于单独测试和维护

**建议文件路径**: `src/main/java/com/formacraft/server/generator/HouseDoorGenerator.java`

**包含的方法**:
```java
public static boolean tryPlaceDoor(List<PlannedBlock> blocks, BlockPos pos, int x, int y, int z, 
                                    BlockPos origin, GenerationContext ctx)
private static boolean isDoorPosition(int x, int y, int z, GenerationContext ctx)
private static void placeDoorBlock(List<PlannedBlock> blocks, BlockPos pos, int y, 
                                    GenerationContext ctx, boolean leftSide)
```

---

### 6. **窗户生成器** ⭐⭐
**当前位置**: `processWindow()` (Line 501-572)

**建议拆分到**: `HouseWindowGenerator.java`  
**理由**:
- 窗户生成逻辑非常复杂（窗带、窗套、拱形窗、栅窗等）
- 已经有一部分逻辑在 `HouseGeneratorUtils` 中，可以统一管理
- 便于单独测试和维护

**建议文件路径**: `src/main/java/com/formacraft/server/generator/HouseWindowGenerator.java`

**包含的方法**:
```java
public static boolean tryPlaceWindow(List<PlannedBlock> blocks, Set<BlockPos> fenceFramePositions,
                                     BlockPos pos, int x, int y, int z, BlockPos origin, GenerationContext ctx)
private static boolean isInWindowBand(int y, GenerationContext ctx)
private static void placeWindowFrame(List<PlannedBlock> blocks, BlockPos origin, int x, int y, int z, GenerationContext ctx)
```

**注意**: 需要检查 `HouseGeneratorUtils` 中已有的窗口相关方法，避免重复。

---

### 7. **墙体生成器** ⭐
**当前位置**: 
- `generateWalls()` (Line 420-454)
- `processWallCell()` (Line 577-605)

**建议拆分到**: `HouseWallGenerator.java`  
**理由**:
- 墙体生成逻辑相对独立
- 包含墙体花纹、调色板、立面组合等逻辑

**建议文件路径**: `src/main/java/com/formacraft/server/generator/HouseWallGenerator.java`

**包含的方法**:
```java
public static void generateWalls(List<PlannedBlock> blocks, Set<BlockPos> fenceFramePositions,
                                 BlockPos origin, GenerationContext ctx)
private static void processWallCell(List<PlannedBlock> blocks, BlockPos pos, int x, int y, int z,
                                     BlockPos origin, GenerationContext ctx)
```

---

### 8. **地板生成器** ⭐
**当前位置**: `generateFloorsAndCeilings()` (Line 622-658)

**建议拆分到**: `HouseFloorGenerator.java`  
**理由**:
- 地板和天花生成逻辑独立
- 可以被其他生成器复用

**建议文件路径**: `src/main/java/com/formacraft/server/generator/HouseFloorGenerator.java`

**包含的方法**:
```java
public static void generateFloors(List<PlannedBlock> blocks, BlockPos origin, GenerationContext ctx)
public static void generateCeilings(List<PlannedBlock> blocks, BlockPos origin, GenerationContext ctx)
```

---

### 9. **临水码头辅助类** ⭐
**当前位置**: 
- `generateWaterfrontPierIfNeeded()` (Line 834-876)
- `calculateDoorExitPosition()` (Line 881-892)

**建议拆分到**: `HouseWaterfrontHelper.java`  
**理由**:
- 临水码头逻辑独立
- 可能被其他生成器复用（如 `JiangnanWaterTownGenerator`）

**建议文件路径**: `src/main/java/com/formacraft/server/generator/HouseWaterfrontHelper.java`

**包含的方法**:
```java
public static void generateWaterfrontPierIfNeeded(List<PlannedBlock> blocks, ServerWorld world, 
                                                    BuildingSpec spec, BlockPos origin, int width, int depth,
                                                    int height, Direction doorSide, String paletteId)
public static BlockPos calculateDoorExitPosition(BlockPos origin, int width, int depth, Direction doorSide)
```

---

### 10. **地形平整逻辑** ⭐
**当前位置**: `flattenTerrain()` (Line 325-371)

**建议拆分到**: `HouseTerrainFlattener.java` 或保留在当前文件（因为与 HouseGenerator 紧密相关）

**理由**:
- 地形平整逻辑相对独立
- 但与其他生成步骤紧密耦合（需要返回调整后的 origin）

**建议**: 
- 如果其他生成器也需要类似逻辑，可以提取
- 否则可以保留在当前文件中

---

## 📊 拆分优先级

### 高优先级 (⭐⭐⭐)
1. **GenerationContext** - Record 类，拆分简单且有益
2. **HouseStyleOptionsResolver** - 职责清晰，复用性强

### 中优先级 (⭐⭐)
3. **HouseFoundationGenerator** - 逻辑独立，易于拆分
4. **HouseDoorGenerator** - 逻辑复杂，拆分后易于维护
5. **HouseWindowGenerator** - 逻辑复杂，拆分后易于维护
6. **HouseCalculationHelper** - 纯计算逻辑，易于拆分

### 低优先级 (⭐)
7. **HouseWallGenerator** - 可以拆分，但与其他部分耦合较多
8. **HouseFloorGenerator** - 可以拆分，但逻辑相对简单
9. **HouseWaterfrontHelper** - 可以拆分，但使用场景有限

---

## 🔄 拆分后的 HouseGenerator 结构

拆分后，`HouseGenerator` 将变得非常简洁：

```java
public class HouseGenerator implements StructureGenerator {
    @Override
    public GeneratedStructure generate(BuildingSpec spec, BlockPos origin, ServerWorld world) {
        List<PlannedBlock> blocks = new ArrayList<>();
        Set<BlockPos> fenceFramePositions = new HashSet<>();

        // 1. 初始化上下文
        HouseGenerationContext ctx = HouseGenerationContextBuilder.build(spec, origin, world);

        // 2. 清空内部空间
        clearInteriorSpace(blocks, ctx);

        // 3. 地坪平整
        BlockPos adjustedOrigin = flattenTerrain(blocks, ctx);

        // 4-12. 调用各个生成器
        HouseFoundationGenerator.generate(blocks, adjustedOrigin, ctx);
        HouseWallGenerator.generate(blocks, fenceFramePositions, adjustedOrigin, ctx);
        // ... 等等

        return new GeneratedStructure(null, adjustedOrigin, description, blocks);
    }
}
```

---

## 📝 拆分建议

1. **逐步拆分**: 不要一次性拆分所有内容，可以分阶段进行
2. **保持向后兼容**: 确保拆分后功能不变
3. **编写测试**: 拆分前确保有足够的测试覆盖
4. **检查依赖**: 拆分前检查是否有其他类依赖这些方法

---

## ✅ 已存在的独立类

以下类已经存在，说明项目已经在进行模块化：
- `HouseGeneratorUtils.java` - 工具方法
- `HouseMaterialResolver.java` - 材质解析
- `HouseLayoutGenerator.java` - 布局生成
- `HouseRoofGenerator.java` - 屋顶生成
- `HouseDecorator.java` - 装饰生成

这表明拆分方向是正确的，可以继续沿着这个方向进行。
