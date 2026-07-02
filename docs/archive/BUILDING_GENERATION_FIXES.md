# 建筑生成问题修复方案

## 问题分析

### 问题 1：地坪不平整

**现象**：单个建筑的室内每层平面不一定是平的，有时地面高低不平。

**根本原因**：
1. 建筑生成时，`origin` 的 Y 坐标可能已经不平（在地形斜坡上）
2. 地板生成使用 `y0 = f * floorHeight`，这是相对于 `origin` 的相对高度
3. 如果 `origin` 的 Y 坐标不平，所有地板的 Y 坐标都会不平

**解决方案**：
1. 在建筑生成前，确定建筑的基准高度（baseY）
2. 确保 `origin` 的 Y 坐标是平的（通过地形适应）
3. 或者在生成地板时，使用固定的绝对高度，而不是相对于 `origin`

### 问题 2：只有窗户没有墙面

**现象**：有时生成的建筑只有窗户，没有墙面。

**根本原因**：
1. 窗户生成逻辑中，如果满足条件就 `continue`，跳过墙体生成
2. 但如果窗户的判断条件过于宽松，或者墙体生成的逻辑有问题，可能导致墙体缺失

**解决方案**：
1. 确保墙体生成逻辑在所有非门窗位置都生成
2. 检查窗户生成的条件，确保不会过度替换墙体
3. 确保在窗户的上下左右都有墙体支撑

---

## 修复实施

### 修复 1：地坪平整

#### 1.1 在 `HouseGenerator` 中添加地坪平整逻辑

在生成建筑之前，先确保建筑的地坪是平的：

```java
// 在 HouseGenerator.generate() 中，在生成墙体之前：
// 1. 确定建筑的基准高度
int baseY = origin.getY();
// 2. 如果地形策略是 ADAPTIVE 或 FLATTEN，平整地坪
if (terrainStrategy == TerrainStrategy.ADAPTIVE || terrainStrategy == TerrainStrategy.FLATTEN) {
    // 计算建筑底部的地形高度范围
    int minY = Integer.MAX_VALUE;
    int maxY = Integer.MIN_VALUE;
    for (int x = 0; x < width; x++) {
        for (int z = 0; z < depth; z++) {
            int terrainY = world.getTopY(Heightmap.Type.WORLD_SURFACE, 
                    origin.getX() + x, origin.getZ() + z);
            minY = Math.min(minY, terrainY);
            maxY = Math.max(maxY, terrainY);
        }
    }
    // 使用中间值或最小值作为基准高度
    baseY = (minY + maxY) / 2;
    // 3. 平整地坪（使用 TerrainFit.balancedPad 或 adaptivePad）
    blocks.addAll(TerrainFit.balancedPad(world, origin, width, depth, baseY, 
            foundation, 4, 8, true, true));
    // 4. 更新 origin 的 Y 坐标
    origin = new BlockPos(origin.getX(), baseY, origin.getZ());
}
```

#### 1.2 确保地板生成使用固定高度

```java
// 在生成地板时，使用绝对高度而不是相对高度
for (int f = 0; f < floors; f++) {
    int floorY = baseY + f * floorHeight;  // 使用绝对高度
    // ... 生成地板
}
```

### 修复 2：墙体生成

#### 2.1 确保墙体在所有非门窗位置生成

检查 `HouseGenerator` 中的墙体生成逻辑，确保：
1. 窗户不会覆盖墙体，而是在窗带位置用窗户替换墙体
2. 在窗户的上下左右都有墙体支撑
3. 门窗位置的判断逻辑正确

#### 2.2 检查窗户生成条件

确保 `HouseGeneratorUtils.isShouldPlaceWindow()` 的逻辑正确，不会过度生成窗户。
