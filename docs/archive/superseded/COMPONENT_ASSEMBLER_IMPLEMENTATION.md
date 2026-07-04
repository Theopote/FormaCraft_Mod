# Component Assembler 系统实现总结

## 实现状态

### ✅ 已完全实现

建议中的 Component Assembler 系统已经全部实现：

1. ✅ **ComponentType** - 建筑语义组件枚举
2. ✅ **ComponentSpec** - 组件参数（尺寸、偏移、参数、标签）
3. ✅ **ComponentPlan** - 组件图（组件列表）
4. ✅ **ComponentAssembler** - 组件装配器接口
5. ✅ **EnclosingWallAssembler** - 围墙装配器（示例实现）
6. ✅ **ComponentAssemblerRegistry** - 装配器注册表
7. ✅ **ComponentAssemblyPipeline** - 组件装配流程
8. ✅ **ComponentBuildPipeline** - 完整建造流程

## 核心组件

### 1. ComponentType（建筑语义组件）

**通用结构**：
- FOUNDATION, FLOOR_PLATE, ROOF, ROOF_RING, ROOF_SPIRE

**墙/围合**：
- ENCLOSING_WALL, INNER_PARTITION, CURTAIN_WALL

**塔/垂直体**：
- TOWER, CORE, KEEP

**开口**：
- GATE, DOOR, WINDOW_BAND

**场地/空间**：
- COURTYARD, PLAZA, TERRACE

**交通**：
- STAIR, RAMP, WALKWAY

**装饰/功能**：
- BALCONY, EAVES, COLUMN_COLONNADE

### 2. ComponentSpec（组件参数）

- `ComponentType type` - 组件类型
- `int offsetX/Y/Z` - 相对 Skeleton 原点的偏移
- `int width/depth/height` - 组件尺寸
- `Map<String, Object> params` - 行为参数
- `Set<String> tags` - 语义标签

### 3. ComponentPlan（组件图）

- `List<ComponentSpec> components` - 组件列表
- 便捷方法：`add()`, `addAll()`, `getComponents()`, `isEmpty()`

### 4. ComponentAssembler（装配器接口）

```java
List<SemanticPlacementOp> assemble(
    GenerationContext ctx,
    ExecutableSkeletonPlan skeleton,
    ComponentSpec component
);
```

### 5. EnclosingWallAssembler（示例实现）

支持多种 Skeleton 类型：
- `RADIAL_RING` - 环形围墙
- `PERIMETER_LOOP` / `ENCLOSURE` - 轮廓围墙
- `COURTYARD` - 矩形围墙
- 其他类型 - 基础围墙（兜底）

## 完整流程

### 流程 1：组件装配

```
ComponentPlan
  ↓
ComponentAssemblyPipeline.assembleAll()
  ↓
List<SemanticPlacementOp>
```

### 流程 2：完整建造

```
SkeletonPlan + ComponentPlan
  ↓
ComponentBuildPipeline.buildComponentAsPatch()
  ↓
ComponentAssemblyPipeline.assembleAll()
  ↓
SemanticResolver.resolveToPatches()
  ↓
List<BlockPatch>
  ↓
Preview / Apply
```

## 使用示例

### 示例 1：创建组件计划

```java
// 创建组件计划
ComponentPlan plan = new ComponentPlan()
    .add(new ComponentSpec(ComponentType.ENCLOSING_WALL)
        .size(0, 0, 8)  // 高度 8
        .tag("defensive"))
    .add(new ComponentSpec(ComponentType.TOWER)
        .offset(10, 0, 10)
        .size(5, 5, 15)
        .tag("corner"))
    .add(new ComponentSpec(ComponentType.COURTYARD)
        .size(12, 12, 0)
        .tag("inner"));
```

### 示例 2：完整建造流程

```java
// 初始化
ComponentAssemblerRegistry.registerDefaults();
DefaultPalettes.bootstrap();

// 创建骨架和组件
ExecutableSkeletonPlan skeleton = new ExecutableSkeletonPlan(SkeletonType.RADIAL_RING)
    .put("radius", 15);

ComponentPlan components = new ComponentPlan()
    .add(new ComponentSpec(ComponentType.ENCLOSING_WALL).size(0, 0, 8))
    .add(new ComponentSpec(ComponentType.COURTYARD).size(10, 10, 0));

// 生成 BlockPatch
List<BlockPatch> patches = ComponentBuildPipeline.buildComponentAsPatch(
    ctx,
    skeleton,
    components,
    "DEFAULT",
    origin
);
```

### 示例 3：LLM 输出格式

```json
{
  "skeleton": {
    "type": "RADIAL_RING",
    "radius": 15
  },
  "components": [
    {
      "type": "ENCLOSING_WALL",
      "height": 8,
      "tags": ["defensive"]
    },
    {
      "type": "COURTYARD",
      "width": 10,
      "depth": 10,
      "tags": ["inner"]
    }
  ]
}
```

## 扩展指南

### 添加新的组件类型

1. **在 ComponentType 中添加枚举值**（如果不存在）

2. **实现 ComponentAssembler**：
```java
public class TowerAssembler implements ComponentAssembler {
    @Override
    public List<SemanticPlacementOp> assemble(
            GenerationContext ctx,
            ExecutableSkeletonPlan skeleton,
            ComponentSpec component
    ) {
        // 实现装配逻辑
    }
}
```

3. **注册装配器**：
```java
ComponentAssemblerRegistry.register(ComponentType.TOWER, new TowerAssembler());
```

## 与现有系统的关系

### 与 MetaAssemblyEngine 的关系

- **Component Assembler**：高级语义装配（ENCLOSING_WALL, TOWER 等）
- **MetaAssemblyEngine**：底层操作序列（CLEAR_BOX, FILL_BOX 等）
- **两者可以共存**：各自负责不同层次

### 与 Skeleton 系统的关系

- **Skeleton**：空间骨架（RADIAL_RING, LINEAR_PATH 等）
- **Component**：语义器官（ENCLOSING_WALL, TOWER 等）
- **Assembler**：将 Component 装配到 Skeleton 上

### 与 Semantic 系统的关系

- **ComponentAssembler** 输出 `SemanticPlacementOp`
- **SemanticResolver** 将 `SemanticPlacementOp` 转换为 `BlockPatch`
- **Palette** 负责风格表现

## 初始化

在 mod 初始化时调用：

```java
// 在 FormacraftMod.onInitialize() 或 ServerInitializer.onInitializeServer()
ComponentAssemblerRegistry.registerDefaults();
DefaultPalettes.bootstrap();
```

## 总结

✅ **完整的 Component Assembler 系统已实现**：
- 组件类型枚举 ✅
- 组件参数和计划 ✅
- 装配器接口和注册表 ✅
- 示例实现（围墙） ✅
- 完整建造流程 ✅

✅ **设计优势**：
- LLM 只需输出组件列表，不需要知道底层实现
- 清晰的层次结构：Skeleton → Component → Semantic → BlockPatch
- 可扩展性强：新增组件类型只需实现新的 Assembler
- 与现有系统完美集成

这正是"AI 规划空间，系统负责落地"的完整实现！

