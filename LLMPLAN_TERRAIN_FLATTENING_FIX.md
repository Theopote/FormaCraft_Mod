# LlmPlan 系统地形平整修复

## 🔍 问题

用户反馈生成的建筑地面不是平的，也没有对地形进行平整。

## ✅ 解决方案

在 `FormaCraftNetworking.java` 的 LlmPlan 处理流程中添加了地坪平整逻辑。

### 实现位置

**文件**：`src/main/java/com/formacraft/common/network/FormaCraftNetworking.java`

**位置**：Line 1272-1336

### 实现逻辑

1. **在生成方块后计算边界**：
   - 从已生成的 `PlannedBlock` 中计算建筑的边界（minX, minZ, maxX, maxZ）
   - 计算建筑的宽度和深度

2. **获取地形策略**：
   - 从 `llmPlan.globalConstraints().terrainStrategy()` 获取地形策略
   - 默认使用 `ADAPTIVE` 策略

3. **执行地形平整**：
   - 如果策略是 `ADAPTIVE` 或 `FLATTEN`，且地形起伏较大（range > 1），进行地坪平整
   - 使用 `TerrainFit.analyze()` 分析地形
   - 使用 `TerrainFit.averageFootprintHeight()` 计算目标高度
   - 根据策略选择平整方式：
     - `FLATTEN`：使用 `TerrainFit.balancedPad()` 进行较大范围平整
     - `ADAPTIVE`：使用 `TerrainFit.adaptivePad()` 轻微平整

4. **合并平整方块**：
   - 将地形平整的 `PlannedBlock` 添加到结果的最前面
   - 确保地坪平整的方块先执行，然后再放置建筑方块

### 关键代码

```java
// 获取地形策略
com.formacraft.common.llm.dto.GlobalConstraints.TerrainStrategy terrainStrategy = 
        (llmPlan.globalConstraints() != null && llmPlan.globalConstraints().terrainStrategy() != null)
        ? llmPlan.globalConstraints().terrainStrategy()
        : com.formacraft.common.llm.dto.GlobalConstraints.TerrainStrategy.ADAPTIVE;

// 如果策略是 ADAPTIVE 或 FLATTEN，进行地坪平整
if (!plannedBlocks.isEmpty()
        && (terrainStrategy == ADAPTIVE || terrainStrategy == FLATTEN)) {
    // 计算边界...
    // 分析地形...
    // 生成平整地坪的方块...
    // 合并到结果中...
}
```

## 📋 与 HouseGenerator 的一致性

- ✅ 使用相同的 `TerrainFit` 工具类
- ✅ 支持相同的地形策略（ADAPTIVE, FLATTEN）
- ✅ 使用相同的平整方法（`adaptivePad`, `balancedPad`）
- ✅ 确保地坪平整在建筑生成之前执行（通过将平整方块放在最前面）

## ✅ 效果

- ✅ 建筑生成前会自动平整地坪
- ✅ 确保建筑的地面是平的
- ✅ 支持不同的地形策略（ADAPTIVE, FLATTEN）
- ✅ 根据地形策略选择合适的平整方式

## 🎯 注意事项

1. **执行顺序**：地坪平整的方块被添加到结果的最前面，确保先执行平整，再放置建筑
2. **边界计算**：从实际生成的方块中计算边界，确保准确
3. **默认策略**：如果没有明确指定地形策略，默认使用 `ADAPTIVE`

## 📝 后续改进建议

如果需要更精确的地坪平整，可以考虑：
1. 在生成方块之前就进行地形平整（需要提前估算建筑尺寸）
2. 根据建筑的实际布局（PlanSkeleton 或 components）计算更精确的边界
3. 确保每个楼层的底板都在同一水平面上（类似 HouseGenerator 的实现）
