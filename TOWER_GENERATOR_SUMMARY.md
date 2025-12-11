# TowerGenerator 完整实现总结

## 功能特性

TowerGenerator 是 FormaCraft 的第一个完整「可用建筑生成器」，具备以下功能：

### ✅ 核心功能

1. **支持 AI 生成的 BuildingSpec**
   - 完全兼容 Python 后端返回的 BuildingSpec JSON
   - 自动解析所有参数

2. **支持圆形塔楼**
   - 使用距离公式生成圆形外墙
   - 支持 medieval 和 modern 风格

3. **外墙生成**
   - 圆形外墙，厚度为 1 方块
   - 使用 `dist >= radius - 0.5 && dist <= radius + 0.5` 判断

4. **层高控制**
   - 根据 `spec.getHeight()` 设置总高度
   - 根据 `spec.getFloors()` 分楼层

5. **窗户规则**
   - 每隔 4 格开一个窗
   - 在 `y % 4 == 2` 的层，且 `x` 或 `z` 是 4 的倍数时开窗
   - 可通过 `spec.getFeatures().hasWindows()` 控制

6. **楼板生成**
   - 按 `floors` 参数分楼层
   - 每层楼板在圆形内部生成
   - 使用 `spec.getMaterials().getFloor()` 材质

7. **内部楼梯**
   - 螺旋楼梯，每层 4 级
   - 使用 4 个方向的偏移形成螺旋
   - 可通过 `spec.getFeatures().hasStairs()` 控制

8. **塔顶**
   - 支持平顶（默认）
   - 支持锥形屋顶（通过 `extra.roofType = "cone"` 配置）
   - 使用 `spec.getMaterials().getRoof()` 材质

## 参数映射

| BuildingSpec 字段 | 用途 | 默认值 |
|------------------|------|--------|
| `spec.getHeight()` | 总高度 | 8 |
| `spec.getFootprint().getRadius()` | 塔楼半径 | 6 |
| `spec.getFloors()` | 楼层数 | 1 |
| `spec.getMaterials().getWall()` | 外墙材质 | stone |
| `spec.getMaterials().getFloor()` | 楼板材质 | oak_planks |
| `spec.getMaterials().getWindow()` | 窗户材质 | glass_pane |
| `spec.getMaterials().getRoof()` | 屋顶材质 | oak_planks |
| `spec.getFeatures().hasWindows()` | 是否生成窗户 | true |
| `spec.getFeatures().hasStairs()` | 是否内部楼梯 | true |
| `spec.getFeatures().hasRoof()` | 是否生成屋顶 | true |
| `spec.getExtra().get("roofType")` | 屋顶类型（"cone" 为锥形） | 平顶 |

## 生成逻辑详解

### 1. 外墙生成（圆形检测）

```java
double dist = Math.sqrt(x * x + z * z);
if (dist >= radius - 0.5 && dist <= radius + 0.5) {
    // 这是外墙
}
```

使用距离公式判断是否在外圈，形成厚度为 1 方块的圆墙。

### 2. 内部清空

```java
if (dist < radius - 1) {
    result.add(new PlannedBlock(pos, Blocks.AIR.getDefaultState()));
}
```

内部全部设为 AIR，避免打到山体或其他结构。

### 3. 窗户逻辑

```java
if (hasWindows && y % 4 == 2 && (Math.abs(x) % 4 == 0 || Math.abs(z) % 4 == 0)) {
    result.add(new PlannedBlock(pos, window));
}
```

- 在 `y % 4 == 2` 的层（每 4 层中的第 3 层）
- 且 `x` 或 `z` 是 4 的倍数时
- 放置窗户方块

### 4. 螺旋楼梯

```java
BlockPos[] spiralOffsets = {
    new BlockPos(1, 0, 0),
    new BlockPos(0, 0, 1),
    new BlockPos(-1, 0, 0),
    new BlockPos(0, 0, -1)
};
int stairIndex = 0;
// 每层旋转一次
BlockPos stairPos = origin.add(spiralOffsets[stairIndex % 4]);
stairIndex++;
```

定义了 4 个方向的偏移，每层旋转一次，形成螺旋楼梯。

### 5. 楼板生成

```java
int floorHeight = height / floors;
if (y > 0 && y % floorHeight == 0) {
    // 生成楼板
}
```

按照 `floors` 参数分楼层，每层楼板在圆形内部生成。

### 6. 屋顶生成

**平顶（默认）：**
```java
for (int x = -radius; x <= radius; x++) {
    for (int z = -radius; z <= radius; z++) {
        double dist = Math.sqrt(x * x + z * z);
        if (dist <= radius + 0.3) {
            result.add(new PlannedBlock(origin.add(x, height, z), roof));
        }
    }
}
```

**锥形屋顶：**
```java
int roofHeight = Math.min(radius, 5);
for (int roofY = 0; roofY < roofHeight; roofY++) {
    int currentRadius = radius - roofY;
    // 每层半径递减
}
```

## 材质解析

### 标准方式（使用 Registry）

```java
Identifier identifier = Identifier.of("minecraft", id);
Block block = Registries.BLOCK.get(identifier);
return block.getDefaultState();
```

### 回退方案（字符串匹配）

如果 Registry 查找失败，使用字符串匹配：

- `stone_brick` / `stonebrick` → `STONE_BRICKS`
- `cobblestone` → `COBBLESTONE`
- `brick` → `BRICKS`
- `dark_oak` → `DARK_OAK_PLANKS`
- `oak_plank` / `oak_wood` → `OAK_PLANKS`
- `glass_pane` → `GLASS_PANE`
- `glass` → `GLASS`
- 其他 → `STONE`（默认）

## 使用示例

### 在 BuildExecutionService 中使用

```java
TowerGenerator gen = new TowerGenerator();
GeneratedStructure structure = gen.generate(spec, origin, world);

BuildExecutionService.getInstance().enqueueBuild(world, structure);
```

### BuildingSpec 示例

```json
{
  "type": "TOWER",
  "style": "MEDIEVAL",
  "footprint": {
    "shape": "circle",
    "radius": 6
  },
  "height": 20,
  "floors": 3,
  "materials": {
    "wall": "minecraft:stone_bricks",
    "roof": "minecraft:dark_oak_planks",
    "floor": "minecraft:spruce_planks",
    "window": "minecraft:glass_pane"
  },
  "features": {
    "hasWindows": true,
    "hasStairs": true,
    "hasRoof": true
  },
  "extra": {
    "roofType": "cone"
  }
}
```

## 输出

TowerGenerator 输出 `List<PlannedBlock>`，供 `BuildExecutionService` 执行：

1. **分 Tick 执行**：每 Tick 最多 200 个方块
2. **自动 Undo**：完成后自动加入 Undo 栈
3. **玩家关联**：通过 UUID 关联玩家，支持 `/formacraft_undo` 撤销

## 扩展建议

### 1. 窗户比例控制

可以在 `extra` 中添加 `windowRatio`：

```java
double windowRatio = spec.getExtra() != null ? 
    (Double) spec.getExtra().getOrDefault("windowRatio", 0.25) : 0.25;
// 根据 windowRatio 调整窗户密度
```

### 2. 楼梯朝向

可以设置楼梯的正确朝向：

```java
BlockState stairState = Blocks.OAK_STAIRS.getDefaultState()
    .with(StairsBlock.FACING, Direction.NORTH);
```

### 3. 门

可以在底层添加门：

```java
if (y == 0 && spec.getFeatures().hasDoor()) {
    // 在某个方向添加门
}
```

### 4. 阳台

可以在特定楼层添加阳台：

```java
if (spec.getFeatures().hasBalcony() && y % floorHeight == floorHeight - 1) {
    // 生成阳台
}
```

## 总结

TowerGenerator 是一个完整的、功能齐全的塔楼生成器，完全支持 AI 生成的 BuildingSpec，可以生成美观的圆形塔楼，包括外墙、窗户、楼梯、楼板和屋顶。所有代码兼容 Fabric 1.21.10，使用标准的 BlockPos 和 BlockState API。

