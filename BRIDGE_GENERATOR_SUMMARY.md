# BridgeGenerator 完整实现总结

## 功能特性

BridgeGenerator 是 FormaCraft AI 建筑体系里第三个重要的生成器，支持三种桥型，具备以下功能：

### ✅ 核心功能

| 特性 | 实现状况 |
|------|---------|
| 平桥 (flat bridge) | ✔ |
| 拱桥 (arched bridge) | ✔ |
| 悬索桥 (suspension bridge) | ✔ |
| 桥面生成 | ✔ |
| 护栏生成 | ✔ |
| 自动桥墩生成 | ✔ |
| 主塔生成（悬索桥） | ✔ |
| 主缆线生成（悬索桥） | ✔ |
| 垂直吊索生成（悬索桥） | ✔ |
| 地形自适应 | ✔ |

## 三种桥型详解

### 1. 平桥 (Flat Bridge)

**配置：**
```json
{
  "extra": {
    "bridgeType": "flat",
    "pillarInterval": 6
  }
}
```

**特点：**
- 桥面固定高度
- 自动生成桥墩（`pillarInterval` 控制间隔）
- 适合现代/简约风格
- 适合短距离桥梁

### 2. 拱桥 (Arched Bridge)

**配置：**
```json
{
  "extra": {
    "bridgeType": "arched",
    "pillarInterval": 6
  }
}
```

**特点：**
- 使用抛物线生成优雅的上拱曲线
- 公式：`dy = -((z-mid)^2 / mid^2) * height + height`
- 非常适合中世纪风格
- 适合古典风格
- 推荐使用石砖材质

**视觉效果：**
```
     /\
    /  \
   /    \
  /      \
 /        \
/__________\
```

### 3. 悬索桥 (Suspension Bridge)

**配置：**
```json
{
  "extra": {
    "bridgeType": "suspension",
    "pillarInterval": 6
  }
}
```

**特点：**
- 包含两座主塔
- 悬链线主缆（cosh 函数近似）
- 主缆 → 桥面的垂直吊索
- 自动生成的桥墩
- 视觉效果非常好
- 适合长距离桥梁

**结构组成：**
- **主塔**：左右各一座，高度 8 格
- **主缆**：使用悬链线曲线（cosh 函数）
- **吊索**：从主缆垂直连接到桥面（使用铁栏杆）

## 参数映射

| BuildingSpec 字段 | 用途 | 默认值 |
|------------------|------|--------|
| `spec.getFootprint().getWidth()` | 桥面宽度 | 5 |
| `spec.getFootprint().getDepth()` | 桥长（Z 方向） | 20 |
| `spec.getMaterials().getFloor()` | 桥面材质 | stone_bricks |
| `spec.getMaterials().getWall()` | 支撑/主塔材质 | stone_bricks |
| `spec.getExtra().get("bridgeType")` | 桥型（flat/arched/suspension） | flat |
| `spec.getExtra().get("pillarInterval")` | 桥墩间隔 | 6 |

## 生成逻辑详解

### 1. 空间清空

```java
for (int z = 0; z <= length; z++) {
    for (int x = -width; x <= width; x++) {
        for (int y = -2; y <= 10; y++) {
            blocks.add(new PlannedBlock(origin.add(x, y, z), Blocks.AIR.getDefaultState()));
        }
    }
}
```

清空桥上与两侧空间，避免建造时嵌进地形。

### 2. 桥面 Y 偏移计算

不同桥型使用不同的数学曲线：

#### 平桥
```java
for (int i = 0; i <= length; i++) {
    arr[i] = 0;  // 不偏移
}
```

#### 拱桥（抛物线）
```java
double mid = length / 2.0;
double height = Math.max(2, length / 10.0);
for (int i = 0; i <= length; i++) {
    double dy = -((i - mid) * (i - mid)) / (mid * mid) * height + height;
    arr[i] = (int) Math.round(dy);  // 上拱
}
```

#### 悬索桥（悬链线）
```java
double mid = length / 2.0;
double a = 3.0;
double b = Math.max(1.0, length / 5.0);
for (int i = 0; i <= length; i++) {
    double dy = a * (Math.cosh((i - mid) / b) - 1);
    arr[i] = -(int) Math.round(dy); // 下垂
}
```

### 3. 桥面生成

```java
for (int z = 0; z <= length; z++) {
    int yOffset = yOffsets[z];
    for (int x = -width / 2; x <= width / 2; x++) {
        BlockPos pos = origin.add(x, yOffset, z);
        blocks.add(new PlannedBlock(pos, floor));
    }
}
```

根据 Y 偏移数组生成桥面。

### 4. 护栏生成

```java
for (int z = 0; z <= length; z++) {
    int yOffset = yOffsets[z];
    BlockPos left = origin.add(-width / 2 - 1, yOffset, z);
    BlockPos right = origin.add(width / 2 + 1, yOffset, z);
    blocks.add(new PlannedBlock(left, railing));
    blocks.add(new PlannedBlock(right, railing));
}
```

在桥面两侧生成护栏（使用橡木栅栏）。

### 5. 桥墩生成

```java
for (int z = 0; z <= length; z += pillarInterval) {
    int yOffset = yOffsets[z];
    BlockPos mid = origin.add(0, yOffset, z);
    
    // 向下延伸支撑直到遇到实心方块
    BlockPos p = mid.down();
    int maxDepth = 20;
    int depth = 0;
    
    while (p.getY() > world.getBottomY() && depth < maxDepth) {
        BlockState state = world.getBlockState(p);
        if (!state.isAir() && !state.isOf(Blocks.WATER)) {
            break;
        }
        blocks.add(new PlannedBlock(p, support));
        p = p.down();
        depth++;
    }
}
```

根据地形自动生成桥墩，向下延伸直到遇到实心方块。

### 6. 悬索桥特殊结构

#### 主塔生成

```java
int towerHeight = 8;
int[] towerZ = { length / 3, length * 2 / 3 };

for (int tz : towerZ) {
    // 左塔
    for (int y = 0; y < towerHeight; y++) {
        blocks.add(new PlannedBlock(origin.add(-width / 2 - 2, y, tz), material));
    }
    
    // 右塔
    for (int y = 0; y < towerHeight; y++) {
        blocks.add(new PlannedBlock(origin.add(width / 2 + 2, y, tz), material));
    }
}
```

在桥的 1/3 和 2/3 位置生成左右主塔。

#### 主缆线生成

```java
int z1 = towerZ[0];
int z2 = towerZ[1];
int mid = (z1 + z2) / 2;
double a = 3.0;
double b = Math.max(1.0, (z2 - z1) / 5.0);

for (int z = z1; z <= z2; z++) {
    double dy = a * (Math.cosh((z - mid) / b) - 1);
    int y = towerHeight - (int) Math.round(dy);
    
    // 左右主缆
    blocks.add(new PlannedBlock(origin.add(-width / 2 - 2, y, z), cableMaterial));
    blocks.add(new PlannedBlock(origin.add(width / 2 + 2, y, z), cableMaterial));
}
```

使用悬链线（cosh 函数）生成主缆线。

#### 垂直吊索生成

```java
for (int z = 0; z <= length; z++) {
    int cableY = findHighestNonAir(blocks, cablePos);
    int floorY = findHighestNonAir(blocks, origin.add(0, 0, z));
    
    if (cableY > floorY) {
        // 垂直放吊索
        for (int y = floorY + 1; y < cableY; y++) {
            blocks.add(new PlannedBlock(origin.add(-width / 2 - 2, y, z), hangerMaterial));
            blocks.add(new PlannedBlock(origin.add(width / 2 + 2, y, z), hangerMaterial));
        }
    }
}
```

从主缆垂直连接到桥面，使用铁栏杆作为吊索。

## 使用示例

### 在 BuildExecutionService 中使用

```java
BridgeGenerator generator = new BridgeGenerator();
GeneratedStructure structure = generator.generate(spec, origin, world);

BuildExecutionService.getInstance().enqueueBuild(world, structure);
```

### BuildingSpec 示例

#### 平桥示例

```json
{
  "type": "BRIDGE",
  "style": "MODERN",
  "footprint": {
    "shape": "line",
    "width": 5,
    "depth": 20
  },
  "materials": {
    "floor": "minecraft:spruce_planks",
    "wall": "minecraft:stone_bricks"
  },
  "extra": {
    "bridgeType": "flat",
    "pillarInterval": 6
  }
}
```

#### 拱桥示例

```json
{
  "type": "BRIDGE",
  "style": "MEDIEVAL",
  "footprint": {
    "shape": "line",
    "width": 5,
    "depth": 30
  },
  "materials": {
    "floor": "minecraft:stone_bricks",
    "wall": "minecraft:stone_bricks"
  },
  "extra": {
    "bridgeType": "arched",
    "pillarInterval": 8
  }
}
```

#### 悬索桥示例

```json
{
  "type": "BRIDGE",
  "style": "MODERN",
  "footprint": {
    "shape": "line",
    "width": 6,
    "depth": 40
  },
  "materials": {
    "floor": "minecraft:spruce_planks",
    "wall": "minecraft:stone_bricks"
  },
  "extra": {
    "bridgeType": "suspension",
    "pillarInterval": 10
  }
}
```

## 输出

BridgeGenerator 输出 `List<PlannedBlock>`，供 `BuildExecutionService` 执行：

1. **分 Tick 执行**：每 Tick 最多 200 个方块
2. **自动 Undo**：完成后自动加入 Undo 栈
3. **玩家关联**：通过 UUID 关联玩家，支持 `/formacraft_undo` 撤销

## 应用场景

通过该生成器，AI 能直接生成：

- **河上的拱桥** - 使用 arched 类型，石砖材质
- **山谷之间的悬索桥** - 使用 suspension 类型，长距离
- **村庄边的木桥** - 使用 flat 类型，木板材质
- **城门前的石桥** - 使用 arched 类型，石砖材质

## 技术细节

### 数学曲线

1. **抛物线（拱桥）**
   - 公式：`y = -a * (x - mid)^2 + height`
   - 特点：对称、优雅的上拱

2. **悬链线（悬索桥）**
   - 公式：`y = a * (cosh((x - mid) / b) - 1)`
   - 特点：自然下垂的曲线

### 地形自适应

- 桥墩自动向下延伸直到遇到实心方块
- 限制最大深度为 20 格，避免无限循环
- 忽略水和空气，只检测实心方块

### 性能优化

- 使用数组预计算 Y 偏移，避免重复计算
- 限制桥墩最大深度
- 悬索桥的吊索生成使用辅助方法查找最高点

## 扩展建议

### 1. 方向旋转

可以根据玩家朝向自动旋转桥的方向：

```java
Direction facing = player.getHorizontalFacing();
// 根据 facing 旋转坐标
```

### 2. 更多桥型

可以添加：
- **斜拉桥** (cable-stayed bridge)
- **桁架桥** (truss bridge)
- **吊桥** (drawbridge)

### 3. 装饰元素

可以添加：
- 桥头堡
- 路灯
- 旗帜
- 雕像

### 4. 材质变化

可以根据风格自动选择材质：
- 中世纪：石砖
- 现代：混凝土、钢铁
- 自然：木头、原木

## 总结

BridgeGenerator 是一个完整的、功能齐全的桥梁生成器，完全支持 AI 生成的 BuildingSpec，可以生成三种不同类型的桥梁：

1. **平桥** - 简单实用
2. **拱桥** - 优雅古典
3. **悬索桥** - 现代壮观

所有代码兼容 Fabric 1.21.10，使用标准的 BlockPos 和 BlockState API。

与 TowerGenerator 和 HouseGenerator 一起，FormaCraft 现在拥有三个完整的建筑生成器，可以处理不同类型的建筑需求，包括：
- 圆形塔楼
- 矩形房屋
- 各种类型的桥梁

BridgeGenerator 是 FormaCraft 城市级 AI 建筑里极其重要的单元。

