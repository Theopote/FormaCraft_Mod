# 临水码头（Waterfront Pier）实现进度

本文档记录临水码头自动关联逻辑的实现进度。

## 实现状态

### ✅ Phase 1：核心基础设施（已完成）

#### 1. WaterDetector 类
**位置**：`src/main/java/com/formacraft/server/waterfront/WaterDetector.java`

**功能**：
- ✅ 检测建筑附近的水体（`detectNearbyWater`）
  - 距离阈值检测（默认8格）
  - 高差判定（陆地Y - 水体Y）
  - 水岸线检测（找到水陆交界处）
  
- ✅ 寻找最佳接驳点（`findBestPierAnchor`）
  - 射线探测（从建筑出入口沿朝向搜索）
  - 计算A点（建筑出入口）、B点（水岸线）、C点（停泊位）
  - 返回高差和距离信息

**数据结构**：
- `WaterDetectionResult`：水体检测结果
- `WaterEdge`：水岸线信息
- `PierAnchor`：码头锚点信息

#### 2. WaterfrontPierGenerator 类
**位置**：`src/main/java/com/formacraft/server/waterfront/WaterfrontPierGenerator.java`

**功能**：
- ✅ 三种码头类型自动选择
  - **平铺式驳岸**（高差 ≤ 1）：与地面齐平
  - **阶梯式码头**（高差 2-4）：自动插值计算台阶数量
  - **挑空木栈桥**（高差 > 4）：使用柱子支撑
  
- ✅ 几何路径生成
  - 从建筑出入口到水岸线的路径计算
  - 根据高差自动选择码头形态
  
- ✅ 视觉细节（部分实现）
  - 拴船桩（在码头转角处放置）
  - 驳岸处理（码头两侧岸线替换为石质方块）
  - 照明（码头入口悬挂灯笼）

**材质支持**：
- 支持调色板材质变量替换（通过 `PaletteResolver`）
- 默认材质：
  - `pierStep`：石砖楼梯
  - `pierEdge`：安山岩半砖
  - `pierDeck`：石砖
  - `mooringPost`：云杉栅栏
  - `revetment`：石砖

---

### ⏳ Phase 2：调色板材质变量（待实现）

**需求**：
- 在调色板中添加码头专用语义变量：
  - `PIER_STEP` → `stone_brick_stairs`
  - `PIER_EDGE` → `andesite_slab`
  - `PIER_DECK` → `stone_bricks`
  - `MOORING_POST` → `spruce_fence`
  - `REVETMENT` → `stone_bricks`

**状态**：✅ 已通过 `PaletteResolver.pick()` 支持，但调色板文件中未定义

---

### ⏳ Phase 3：集成到生成流程（待实现）

**需求**：
1. 在 `HouseGenerator` 中添加自动检测逻辑
2. 或在后处理系统中调用
3. 支持通过配置启用/禁用

**集成点建议**：
- 在 `HouseGenerator.generate()` 方法末尾，建筑生成完成后
- 检测是否满足条件（距离阈值、高差等）
- 如果满足，调用 `WaterfrontPierGenerator.generate()`

**触发条件**：
- 建筑附近有水体（距离 < 8格）
- 建筑有出入口（通过 `resolveDoorSide` 获取）
- 可选的配置开关（`extra.waterfront.enabled`）

---

## 使用示例（代码层面）

```java
// 1. 检测附近水体
WaterDetector.WaterDetectionResult waterResult = WaterDetector.detectNearbyWater(
    world, buildingOrigin, width, depth, 8, 8
);

if (waterResult.hasWater()) {
    // 2. 获取建筑出入口位置和朝向
    Direction doorSide = resolveDoorSide(spec);
    BlockPos buildingExit = calculateDoorPosition(origin, width, depth, doorSide);
    
    // 3. 寻找最佳接驳点
    WaterDetector.PierAnchor anchor = WaterDetector.findBestPierAnchor(
        world, buildingExit, doorSide, 8
    );
    
    if (anchor != null) {
        // 4. 生成码头
        List<PlannedBlock> pierBlocks = WaterfrontPierGenerator.generate(
            world, anchor, paletteId, 3  // 宽度3格
        );
        blocks.addAll(pierBlocks);
    }
}
```

---

## 下一步工作

### 优先级 1：集成到 HouseGenerator
- [ ] 在 `HouseGenerator.generate()` 中添加自动检测逻辑
- [ ] 计算建筑出入口位置
- [ ] 调用水体检测和码头生成
- [ ] 添加配置开关支持

### 优先级 2：调色板材质变量
- [ ] 在调色板文件中添加码头专用变量（可选，当前已有默认材质）

### 优先级 3：增强功能
- [ ] 改进拴船桩生成（更真实的样式）
- [ ] 改进驳岸处理（更智能的岸线替换）
- [ ] 改进照明（支持不同风格的灯笼）
- [ ] 添加苔藓石砖效果（水下部分）

---

## 已知限制

1. **标签系统**：当前实现不依赖标签系统，而是自动检测附近水体
2. **出入口位置**：需要从 `HouseGenerator` 中提取实际的门的坐标，当前仅使用朝向
3. **路径生成**：当前使用简单的直线路径，可以改进为更智能的路径规划
4. **性能**：水体检测可能在大范围内性能较差，可以优化搜索算法

---

## 总结

**已完成**：
- ✅ 核心基础设施（WaterDetector + WaterfrontPierGenerator）
- ✅ 三种码头类型生成
- ✅ 基础视觉细节

**待完成**：
- ⏳ 集成到 HouseGenerator
- ⏳ 调色板材质变量（可选）
- ⏳ 功能增强和优化

**编译状态**：✅ 通过

