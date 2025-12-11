# HouseGenerator 完整实现总结

## 功能特性

HouseGenerator 是 FormaCraft AI 建筑体系里第二个重要的生成器，具备以下功能：

### ✅ 核心功能

| 特性 | 实现状况 |
|------|---------|
| 外墙（矩形） | ✔ |
| 门（可选） | ✔ |
| 窗户分布（带窗户比例） | ✔ |
| 内部清空 | ✔ |
| 楼板（按 floors 生成） | ✔ |
| 屋顶（gable 双坡屋顶构造） | ✔ |
| 材质来自 BuildingSpec | ✔ |
| 未来扩展（阳台、立柱、烟囱等） | 预留接口 |

## 参数映射

| BuildingSpec 字段 | 用途 | 默认值 |
|------------------|------|--------|
| `spec.getFootprint().getWidth()` | 矩形 X 方向宽度 | 8 |
| `spec.getFootprint().getDepth()` | 矩形 Z 方向深度 | 6 |
| `spec.getHeight()` | 总高度 | 4 |
| `spec.getFloors()` | 楼层数 | 1 |
| `spec.getMaterials().getWall()` | 外墙材质 | oak_planks |
| `spec.getMaterials().getFloor()` | 楼板材质 | oak_planks |
| `spec.getMaterials().getWindow()` | 窗户材质 | glass_pane |
| `spec.getMaterials().getRoof()` | 屋顶材质 | oak_planks |
| `spec.getFeatures().hasWindows()` | 是否生成窗户 | true |
| `spec.getFeatures().hasDoor()` | 是否生成门 | true |
| `spec.getFeatures().hasRoof()` | 是否生成屋顶 | true |
| `spec.getExtra().get("roofType")` | 屋顶类型（"flat" 为平顶） | gable（双坡） |

## 生成逻辑详解

### 1. 内部清空

```java
for (int x = -1; x <= width + 1; x++) {
    for (int z = -1; z <= depth + 1; z++) {
        for (int y = 0; y <= height + 6; y++) {
            blocks.add(new PlannedBlock(origin.add(x, y, z), Blocks.AIR.getDefaultState()));
        }
    }
}
```

房屋生成之前清空一个比房屋大一圈的区域，避免建造时嵌进山体。

### 2. 外墙生成

```java
boolean isEdgeX = (x == 0 || x == width - 1);
boolean isEdgeZ = (z == 0 || z == depth - 1);

if (isEdgeX || isEdgeZ) {
    // 这是外墙
}
```

墙整体是矩形，使用简单边界逻辑判断外墙位置。

### 3. 门生成

```java
boolean isDoor = hasDoor &&
        (z == 0) &&
        (x == width / 2 || x == width / 2 - 1) &&
        (y == 0 || y == 1);
```

默认设置在前方（z == 0）中心，两格宽、两格高。

### 4. 窗户生成

```java
if (hasWindows && y >= 1 && y <= 2) {
    // 每隔 3 格开一个窗（避免在门的位置开窗）
    if ((x % 3 == 0 || z % 3 == 0) && 
        !(z == 0 && (x == width / 2 || x == width / 2 - 1))) {
        blocks.add(new PlannedBlock(pos, window));
    }
}
```

- 在 `y ∈ [1,2]` 的层开窗
- `x` 或 `z` 每隔 3 格开窗
- 避免在门的位置开窗

### 5. 楼板生成

```java
int floorHeight = height / floors;
for (int f = 0; f < floors; f++) {
    int y = f * floorHeight;
    for (int x = 1; x < width - 1; x++) {
        for (int z = 1; z < depth - 1; z++) {
            blocks.add(new PlannedBlock(origin.add(x, y, z), floor));
        }
    }
}
```

每个 floor 的 `y = floorHeight * f`，生成完整内区地板。

### 6. 屋顶生成

#### 双坡屋顶（Gable Roof，默认）

```java
int roofBaseY = height;
int roofHeight = Math.min(width / 2 + 2, 8);

// 正面与背面方向（沿 X 形成双坡）
for (int i = 0; i < roofHeight; i++) {
    int leftX = i;
    int rightX = width - 1 - i;
    
    if (leftX > rightX) break;
    
    for (int z = 0; z < depth; z++) {
        blocks.add(new PlannedBlock(origin.add(leftX, roofBaseY + i, z), roof));
        blocks.add(new PlannedBlock(origin.add(rightX, roofBaseY + i, z), roof));
    }
}

// 封顶（最上层 ridge）
int ridgeY = roofBaseY + roofHeight;
int midX = width / 2;
for (int z = 0; z < depth; z++) {
    blocks.add(new PlannedBlock(origin.add(midX, ridgeY, z), roof));
}
```

使用等腰三角形逐层缩进，形成对称斜顶：

```
   ^
  / \
 /   \
/_____\
```

#### 平顶

```java
if ("flat".equalsIgnoreCase(String.valueOf(spec.getExtra().get("roofType")))) {
    // 平顶
    for (int x = 0; x < width; x++) {
        for (int z = 0; z < depth; z++) {
            blocks.add(new PlannedBlock(origin.add(x, height, z), roof));
        }
    }
}
```

## 材质解析

与 TowerGenerator 使用相同的解析逻辑：

1. **标准方式（使用 Registry）**
   ```java
   Identifier identifier = Identifier.of("minecraft", id);
   Block block = Registries.BLOCK.get(identifier);
   return block.getDefaultState();
   ```

2. **回退方案（字符串匹配）**
   - 支持常用方块的字符串匹配
   - 包括石头、砖、木头、玻璃等

## 使用示例

### 在 BuildExecutionService 中使用

```java
HouseGenerator generator = new HouseGenerator();
GeneratedStructure structure = generator.generate(spec, origin, world);

BuildExecutionService.getInstance().enqueueBuild(world, structure);
```

### BuildingSpec 示例

```json
{
  "type": "HOUSE",
  "style": "MEDIEVAL",
  "footprint": {
    "shape": "rectangle",
    "width": 10,
    "depth": 8
  },
  "height": 6,
  "floors": 2,
  "materials": {
    "wall": "minecraft:stone_bricks",
    "roof": "minecraft:dark_oak_planks",
    "floor": "minecraft:spruce_planks",
    "window": "minecraft:glass_pane"
  },
  "features": {
    "hasWindows": true,
    "hasDoor": true,
    "hasRoof": true
  },
  "extra": {
    "roofType": "gable"
  }
}
```

### 平顶房屋示例

```json
{
  "type": "HOUSE",
  "footprint": {
    "width": 8,
    "depth": 6
  },
  "height": 4,
  "materials": {
    "wall": "minecraft:oak_planks",
    "roof": "minecraft:oak_planks"
  },
  "features": {
    "hasRoof": true
  },
  "extra": {
    "roofType": "flat"
  }
}
```

## 输出

HouseGenerator 输出 `List<PlannedBlock>`，供 `BuildExecutionService` 执行：

1. **分 Tick 执行**：每 Tick 最多 200 个方块
2. **自动 Undo**：完成后自动加入 Undo 栈
3. **玩家关联**：通过 UUID 关联玩家，支持 `/formacraft_undo` 撤销

## 扩展建议

### 1. 楼梯

可以在楼层之间添加楼梯：

```java
if (hasStairs && f > 0) {
    // 在某个位置添加楼梯
    BlockPos stairPos = origin.add(width / 2, f * floorHeight, depth - 1);
    blocks.add(new PlannedBlock(stairPos, Blocks.OAK_STAIRS.getDefaultState()));
}
```

### 2. 内墙

可以添加内墙分隔房间：

```java
if (spec.getExtra() != null && spec.getExtra().containsKey("hasInteriorWalls")) {
    // 添加内墙
}
```

### 3. 阳台

可以在特定楼层添加阳台：

```java
if (spec.getFeatures().hasBalcony() && y == floorHeight - 1) {
    // 生成阳台
}
```

### 4. 烟囱

可以在屋顶添加烟囱：

```java
if (spec.getExtra() != null && spec.getExtra().containsKey("hasChimney")) {
    // 在屋顶中心添加烟囱
}
```

### 5. 柱子

可以在角落添加装饰性柱子：

```java
if (spec.getExtra() != null && spec.getExtra().containsKey("hasColumns")) {
    // 在四个角落添加柱子
}
```

## 与 TowerGenerator 的对比

| 特性 | TowerGenerator | HouseGenerator |
|------|---------------|----------------|
| 形状 | 圆形 | 矩形 |
| 外墙 | 圆形外墙 | 矩形外墙 |
| 楼梯 | 螺旋楼梯 | 未实现（可扩展） |
| 屋顶 | 平顶/锥形 | 双坡/平顶 |
| 窗户 | 圆形分布 | 矩形分布 |
| 门 | 无 | 有 |

## 总结

HouseGenerator 是一个完整的、功能齐全的房屋生成器，完全支持 AI 生成的 BuildingSpec，可以生成美观的矩形房屋，包括外墙、门、窗户、楼板和屋顶。所有代码兼容 Fabric 1.21.10，使用标准的 BlockPos 和 BlockState API。

与 TowerGenerator 一起，FormaCraft 现在拥有两个完整的建筑生成器，可以处理不同类型的建筑需求。

