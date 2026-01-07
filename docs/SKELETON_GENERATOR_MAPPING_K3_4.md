# K3.4：Skeleton → Generator 映射表（Java 级）实现总结

## ✅ 已完全实现

### 核心目标

**LLM 只要给 SkeletonType + 少量参数，你就能稳定地产出 patchList（并且天然支持 PathTool）。**

### 总体架构

```
LLM Blueprint
  -> SkeletonPlan
  -> Generator.generate(origin, plan, ctx)
  -> rawPatchList
  -> PatchFilterPipeline (上一步已完成)
  -> safePatchList
```

### 核心组件

#### 1. SkeletonPlan（LLM 输出的最小骨架数据结构）
- **位置**：`src/main/java/com/formacraft/common/skeleton/SkeletonPlan.java`
- **字段**：
  - `type` - SkeletonType
  - `anchor` - world origin
  - `points` - for path/polyline/ring, etc. (world positions)
  - `params` - numeric/string params (width, radius, height, etc.)
- **便捷方法**：
  - `intParam(key, def)` - 获取整数参数
  - `doubleParam(key, def)` - 获取浮点数参数
  - `strParam(key, def)` - 获取字符串参数

#### 2. GeneratorContext（生成器上下文）
- **位置**：`src/main/java/com/formacraft/common/gen/GeneratorContext.java`
- **字段**：
  - `world` - World
  - `client` - MinecraftClient
  - `palette` - PaletteResolver（风格/选材）
  - `toolContext` - PatchFilterContext（工具状态）
  - `pathTool` - PathTool（路径工具）
  - `terrainStrategy` - TerrainStrategySampler（地形策略采样器）

#### 3. PaletteResolver（语义 → 方块ID 接口）
- **位置**：`src/main/java/com/formacraft/common/gen/PaletteResolver.java`
- **方法**：`pickBlockId(String semanticKey)`
- **语义键示例**：
  - `"road.surface"` - 道路表面
  - `"road.edge"` - 道路边缘
  - `"wall.base"` - 墙体基础
  - `"wall.top"` - 墙体顶部

#### 4. SkeletonGenerator（生成器接口）
- **位置**：`src/main/java/com/formacraft/common/gen/SkeletonGenerator.java`
- **方法**：`generate(BlockPos origin, SkeletonPlan plan, GeneratorContext ctx)`
- **返回**：`List<BlockPatch>`（相对 origin）

#### 5. SkeletonAssemblyRegistry（映射表）
- **位置**：`src/main/java/com/formacraft/common/gen/SkeletonAssemblyRegistry.java`
- **功能**：
  - `register(SkeletonType, Supplier<SkeletonGenerator>)` - 注册 Generator 工厂
  - `create(SkeletonType)` - 创建 Generator
  - `defaultRegistry()` - 创建默认注册表（推荐）

#### 6. TerrainStrategySampler（地形策略采样器）
- **位置**：`src/main/java/com/formacraft/common/terrain/TerrainStrategySampler.java`
- **方法**：`sampleGroundY(World world, int x, int z)`
- **功能**：采样地面高度（第一个非空气方块）

### Generator 实现（3 个）

#### 1. LinearPathRoadGenerator（直线道路）
- **位置**：`src/main/java/com/formacraft/common/gen/impl/LinearPathRoadGenerator.java`
- **功能**：生成直线道路（含简单地形贴合）
- **参数**：
  - `width` - 道路宽度（默认 3）
  - `length` - 前进长度（默认 20）
  - `axis` - 轴向（"X" 或 "Z"，默认 "Z"）

#### 2. PolylineRoadGenerator（折线路径道路）
- **位置**：`src/main/java/com/formacraft/common/gen/impl/PolylineRoadGenerator.java`
- **功能**：生成折线路径道路（✅ PathTool 必接）
- **参数**：
  - `width` - 道路宽度（默认 3）
  - `step` - 采样步长（默认 1）
- **特点**：
  - 优先使用 `plan.points`
  - 否则从 `PathTool.getNodes()` 获取
  - 支持沿路径生成道路

#### 3. RadialRingCourtyardGenerator（环形院落）
- **位置**：`src/main/java/com/formacraft/common/gen/impl/RadialRingCourtyardGenerator.java`
- **功能**：生成环形院落（圆形轮廓 → 立即能看到"城堡/土楼"味道）
- **参数**：
  - `radius` - 半径（默认 10）
  - `wallHeight` - 墙体高度（默认 6）
  - `thickness` - 墙体厚度（默认 2）

#### 4. SimplePaletteResolver（简单调色板解析器）
- **位置**：`src/main/java/com/formacraft/common/gen/impl/SimplePaletteResolver.java`
- **功能**：最小可用实现（使用硬编码映射）
- **默认映射**：
  - `road.surface` → `minecraft:gravel`
  - `road.edge` → `minecraft:stone`
  - `wall.base` → `minecraft:stone_bricks`
  - `wall.top` → `minecraft:chiseled_stone_bricks`

### 使用示例

```java
import com.formacraft.common.gen.*;
import com.formacraft.common.skeleton.SkeletonPlan;
import com.formacraft.common.skeleton.SkeletonType;
import com.formacraft.common.patch.filter.PatchFilterContextBuilder;
import com.formacraft.common.terrain.TerrainStrategySampler;
import net.minecraft.util.math.BlockPos;

// 1. 创建注册表
SkeletonAssemblyRegistry registry = SkeletonAssemblyRegistry.defaultRegistry();

// 2. 创建上下文
PaletteResolver palette = new SimplePaletteResolver();
PatchFilterContext toolContext = PatchFilterContextBuilder.fromDefaultTools();
TerrainStrategySampler terrainStrategy = new TerrainStrategySampler();

GeneratorContext genCtx = new GeneratorContext(
        world,
        client,
        palette,
        toolContext,
        PathTool.INSTANCE,
        terrainStrategy
);

// 3. 创建 SkeletonPlan
SkeletonPlan plan = new SkeletonPlan(
        SkeletonType.PATH_POLYLINE,
        origin,
        null, // 使用 PathTool
        Map.of("width", 3, "step", 1)
);

// 4. 生成 BlockPatch
SkeletonGenerator gen = registry.create(plan.type);
List<BlockPatch> raw = gen.generate(origin, plan, genCtx);

// 5. 应用 FilterPipeline（上一步已完成）
PatchFilterPipeline pipeline = PatchFilterPipeline.createDefault();
List<BlockPatch> safe = pipeline.apply(raw, origin, genCtx.toolContext);
```

### 完整链路

```
LLM Blueprint
  ↓
SkeletonPlan（SkeletonType + params + points）
  ↓
SkeletonAssemblyRegistry.create(type)
  ↓
SkeletonGenerator.generate(origin, plan, ctx)
  ↓
List<BlockPatch>（raw）
  ↓
PatchFilterPipeline.apply(raw, origin, toolContext)
  ↓
List<BlockPatch>（safe）
  ↓
Preview / Apply
```

### 你现在得到了什么

✅ **一个稳定的 Skeleton → Generator 映射层**

✅ **能直接跑的 3 个 generator**：
- 直线道路（LINEAR_PATH）
- 路径道路（PATH_POLYLINE，已接 PathTool）
- 环形院落（RADIAL_RING，土楼/城堡味道）

✅ **天然支持 PathTool**：
- PolylineRoadGenerator 自动从 PathTool 获取路径点
- 支持沿路径生成道路、长城等

## 📝 总结

✅ **K3.4：Skeleton → Generator 映射表（Java 级）已完全实现**

- SkeletonPlan（最小骨架数据结构）✅
- GeneratorContext（生成器上下文）✅
- PaletteResolver（语义 → 方块ID 接口）✅
- SkeletonGenerator（生成器接口）✅
- SkeletonAssemblyRegistry（映射表）✅
- TerrainStrategySampler（地形策略采样器）✅
- 3 个 Generator 实现✅
- SimplePaletteResolver（简单调色板解析器）✅

**系统现在可以从 LLM 的 SkeletonType + 参数稳定地产出 patchList！**

**这是 FormaCraft 从"能生成"到"稳定生成"的关键一步！**

