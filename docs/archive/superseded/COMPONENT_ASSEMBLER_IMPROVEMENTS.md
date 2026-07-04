# Component Assembler 改进总结

## 改进内容

根据建议，对三个核心装配器进行了改进：

### ✅ 1. TOWER 装配器（改进版）

**新增功能**：
- ✅ 支持圆塔（cylinder）和方塔（cuboid）
- ✅ 从 params 读取 shape, radius, height
- ✅ 自动从 Skeleton 获取塔位（四角或中心）
- ✅ 使用 TOWER_WALL 语义部位
- ✅ 自动生成塔顶

**参数约定**：
```json
{
  "type": "TOWER",
  "params": {
    "shape": "cylinder | cuboid",
    "radius": 3,
    "height": 14,
    "position": "corner | center | custom"
  }
}
```

**使用示例**：
```java
ComponentSpec tower = new ComponentSpec(ComponentType.TOWER)
    .param("shape", "cylinder")
    .param("radius", 4)
    .param("height", 16);
// 如果不指定 offset，会自动从 Skeleton 获取塔位
```

### ✅ 2. COURTYARD 装配器（改进版）

**新增功能**：
- ✅ 支持圆形（circle）和矩形（rectangle）中庭
- ✅ 从 params 读取 shape, radius, floor
- ✅ 自动从 Skeleton 获取中心点
- ✅ 使用 COURTYARD_FLOOR 语义部位
- ✅ 支持 "empty" floor（不建造地面，表示纯空间）

**参数约定**：
```json
{
  "type": "COURTYARD",
  "params": {
    "shape": "circle | rectangle",
    "radius": 6,
    "floor": "stone | grass | empty"
  }
}
```

**使用示例**：
```java
ComponentSpec courtyard = new ComponentSpec(ComponentType.COURTYARD)
    .param("shape", "circle")
    .param("radius", 8)
    .param("floor", "stone");
// 如果不指定 offset，会自动从 Skeleton 获取中心点
```

### ✅ 3. ROOF_RING 装配器（新增）

**功能**：
- ✅ 生成环形屋顶（土楼、天坛圆殿顶）
- ✅ 支持内半径和外半径
- ✅ 支持檐口（eaves）
- ✅ 自动从 Skeleton 获取中心点和高度
- ✅ 使用 ROOF_SURFACE 语义部位

**参数约定**：
```json
{
  "type": "ROOF_RING",
  "params": {
    "inner_radius": 8,
    "outer_radius": 10,
    "height": 1,
    "eaves": true
  }
}
```

**使用示例**：
```java
ComponentSpec roof = new ComponentSpec(ComponentType.ROOF_RING)
    .param("inner_radius", 8)
    .param("outer_radius", 10)
    .param("height", 1)
    .param("eaves", true);
```

### ✅ 4. SkeletonHelper 工具类（新增）

**功能**：
- `getCenter()` - 获取 Skeleton 中心点
- `getTowerBases()` - 获取塔位列表（四角或中心）
- `getMaxHeight()` - 获取 Skeleton 最大高度

**支持的 Skeleton 类型**：
- RADIAL_RING / RADIAL_SPOKE - 中心就是 origin
- COURTYARD / GRID - 计算矩形中心
- 其他类型 - 返回 origin

### ✅ 5. SemanticPart 扩展

**新增语义部位**：
- `TOWER_WALL` - 塔楼墙体
- `COURTYARD_FLOOR` - 中庭地面
- `ROOF_SURFACE` - 屋顶表面

## 完整示例：土楼

```java
// 创建土楼结构
ExecutableSkeletonPlan skeleton = new ExecutableSkeletonPlan(SkeletonType.RADIAL_RING)
    .put("radius", 20)
    .put("conformTerrain", true);

ComponentPlan components = new ComponentPlan()
    // 外墙
    .add(new ComponentSpec(ComponentType.ENCLOSING_WALL)
        .size(0, 0, 12)
        .tag("defensive"))
    // 中庭（圆形）
    .add(new ComponentSpec(ComponentType.COURTYARD)
        .param("shape", "circle")
        .param("radius", 8)
        .param("floor", "stone")
        .tag("inner"))
    // 环形屋顶
    .add(new ComponentSpec(ComponentType.ROOF_RING)
        .param("inner_radius", 8)
        .param("outer_radius", 10)
        .param("height", 1)
        .param("eaves", true))
    // 四个角楼（自动从 Skeleton 获取位置）
    .add(new ComponentSpec(ComponentType.TOWER)
        .param("shape", "cylinder")
        .param("radius", 3)
        .param("height", 15)
        .tag("corner"));

// 生成
List<BlockPatch> patches = ComponentBuildPipeline.buildComponentAsPatch(
    ctx, skeleton, components, "DEFAULT", origin
);
```

## 设计优势

### ✅ 智能位置推断

- **TOWER**：如果不指定 offset，自动从 Skeleton 获取塔位（四角）
- **COURTYARD**：如果不指定 offset，自动从 Skeleton 获取中心点
- **ROOF_RING**：自动从 Skeleton 获取中心点和高度

### ✅ 参数灵活性

- 支持从 `params` Map 读取参数
- 也支持从 `component.width/depth/height` 读取
- 提供合理的默认值

### ✅ 语义清晰

- 使用专门的语义部位（TOWER_WALL, COURTYARD_FLOOR, ROOF_SURFACE）
- 与通用部位（WALL, FLOOR, ROOF）区分

## 已具备的能力

✅ **土楼** - RADIAL_RING + ENCLOSING_WALL + COURTYARD + ROOF_RING + TOWER
✅ **城堡** - GRID + ENCLOSING_WALL + TOWER + COURTYARD
✅ **天坛** - RADIAL_SPOKE + ROOF_RING + COURTYARD
✅ **中庭式群落** - COURTYARD + ENCLOSING_WALL
✅ **多塔组合** - 自动从 Skeleton 获取塔位

✅ **完全由 AI 控制"组合"，由程序保证"结构正确"**

## 总结

✅ **三个核心装配器已改进**：
- TOWER - 支持圆塔/方塔，自动获取塔位
- COURTYARD - 支持圆形/矩形，自动获取中心
- ROOF_RING - 新增，支持环形屋顶

✅ **工具类已创建**：
- SkeletonHelper - 提供 Skeleton 关键位置查询

✅ **语义部位已扩展**：
- TOWER_WALL, COURTYARD_FLOOR, ROOF_SURFACE

系统现在可以支持更复杂的建筑类型，同时保持代码的清晰和可扩展性！

