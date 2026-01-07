# K1：Path → 建筑群布局实现总结

## ✅ 已完全实现

### 1. 核心数据模型

#### PathClusterLayout（路径建筑群布局）
- **位置**：`src/main/java/com/formacraft/common/cluster/PathClusterLayout.java`
- **字段**：
  - `slots` - 建筑槽位列表
  - `BuildingSlot` - 建筑槽位（anchor, facing, width, depth, heightHint）
  - `Facing` - 建筑朝向路径的方向（LEFT_OF_PATH, RIGHT_OF_PATH, ALONG_PATH）

#### PathClusterLayoutGenerator（布局生成器）
- **位置**：`src/main/java/com/formacraft/server/skeleton/gen/path/PathClusterLayoutGenerator.java`
- **核心算法**：
  1. **沿路径采样**：累积距离，每 spacing 格放置一个锚点
  2. **计算左右偏移**：使用法向量计算左右两侧位置
  3. **地形自适应**：将锚点调整到地表高度

#### PathClusterLayoutToBuildingSpecs（转换器）
- **位置**：`src/main/java/com/formacraft/server/skeleton/gen/path/PathClusterLayoutToBuildingSpecs.java`
- **功能**：将 PathClusterLayout 转换为多个 BuildingSpec
- **关键设计**：
  - ❌ AI 不决定"建筑站哪"（站位由算法决定）
  - ✅ AI 决定"建筑长什么样"（风格、细节由 AI 决定）

### 2. PromptAssembler 集成

#### Cluster Layout Block（建筑群布局提示）
- **位置**：`PromptAssembler.clusterLayoutBlock()`
- **功能**：告诉 AI 建筑站位已经确定，AI 只需要决定建筑样式
- **输出**：
  ```
  CLUSTER LAYOUT:
  - type: PATH_ALIGNED
  - building_count: 12
  - spacing: 8 (algorithm-determined)
  - placement_rule: buildings aligned along path, placed on both sides
  - terrain_strategy: ADAPTIVE
  - IMPORTANT: Building positions (anchors) are already determined by algorithm
  - Your role: decide building style, details, and variations
  - Do NOT change building positions or anchors
  ```

#### PromptContext 扩展
- **新增字段**：`clusterLayout` - 路径建筑群布局

### 3. 生成器执行顺序

```
PathTool
  ↓
PathSkeleton
  ↓
PathClusterLayout   ←（K1 新增）
  ↓
List<BuildingSpec>
  ↓
Per-Building Generator
  ↓
Patch Merge
  ↓
Preview / Apply
```

## 🎯 核心算法

### 1. 沿路径采样（Path Sampling）

```java
List<BlockPos> samplePathAnchors(List<BlockPos> nodes, int spacing) {
    List<BlockPos> anchors = new ArrayList<>();
    anchors.add(nodes.get(0)); // 第一个节点总是锚点
    
    int acc = 0;
    for (int i = 1; i < nodes.size(); i++) {
        acc += Math.abs(curr.getX() - prev.getX()) + 
               Math.abs(curr.getY() - prev.getY()) + 
               Math.abs(curr.getZ() - prev.getZ());
        
        if (acc >= spacing) {
            anchors.add(curr);
            acc = 0;
        }
    }
    
    return anchors;
}
```

### 2. 计算左右偏移（生成街道两侧）

```java
BlockPos offsetFromPath(BlockPos p, BlockPos prev, int offset, boolean left) {
    int dx = p.getX() - prev.getX();
    int dz = p.getZ() - prev.getZ();
    
    // 垂直方向（左法向 / 右法向）
    int nx = left ? -dz : dz;
    int nz = left ? dx : -dx;
    
    // 单位化（简化版：只取符号）
    if (nx != 0) nx = Integer.signum(nx);
    if (nz != 0) nz = Integer.signum(nz);
    
    return p.add(nx * offset, 0, nz * offset);
}
```

### 3. 地形自适应

```java
BlockPos adaptToTerrain(GenerationContext ctx, BlockPos p) {
    int groundY = ctx.getSurfaceY(p.getX(), p.getZ());
    return new BlockPos(p.getX(), groundY, p.getZ());
}
```

## 📋 使用示例

### 场景：沿路径生成建筑群

```
1. 玩家用 PathTool 画一条路径
2. 输入："沿着这条路生成一排中世纪风格的房屋"
3. 系统自动完成：
   - PathTool.toSkeleton() → PathSkeleton
   - PathClusterLayoutGenerator.generate() → PathClusterLayout
     - 沿路径采样锚点（每 8 格一个）
     - 计算左右偏移（距离路径 5 格）
     - 地形自适应（每个锚点调整到地表）
   - PathClusterLayoutToBuildingSpecs.toBuildingSpecs() → List<BuildingSpec>
   - PromptAssembler.clusterLayoutBlock() → 告诉 AI 站位已确定
   - LLM 输出：建筑样式和细节（不改变站位）
   - Per-Building Generator → 生成每个建筑
   - Patch Merge → 合并所有建筑的 Patch
4. Preview → Apply
```

## 🔗 关键设计决策

### 1. 算法决定站位，AI 决定样式

- ✅ **站位由算法决定**：PathClusterLayoutGenerator 负责计算建筑位置
- ✅ **样式由 AI 决定**：LLM 负责决定建筑风格、细节、变化
- ✅ **明确分工**：避免 AI 破坏算法确定的站位

### 2. 地形自适应

- ✅ **每个建筑独立处理地形**：使用 `adaptToTerrain()` 调整每个锚点
- ✅ **不整体平整**：保持地形自然起伏
- ✅ **顺应地形**：建筑随地形高度调整

### 3. 左右对称布局

- ✅ **街道两侧**：自动在路径左右两侧布置建筑
- ✅ **朝向路径**：建筑朝向路径方向
- ✅ **间距控制**：可配置建筑间距和偏移距离

## 🎯 系统能力

### ✅ 现在可以做什么

1. **路径驱动建筑群生成**
   - 玩家画路径 → 自动生成沿路径的建筑群
   - 支持左右对称布局
   - 支持自定义间距和偏移

2. **地形自适应建筑群**
   - 每个建筑独立处理地形
   - 不整体平整地形
   - 顺应地形自然起伏

3. **AI 样式控制**
   - AI 决定建筑风格和细节
   - AI 不改变算法确定的站位
   - 明确的分工边界

4. **完整闭环**
   - PathTool → PathSkeleton → PathClusterLayout → BuildingSpec → Generator → Patch → Preview → Apply

## 📝 总结

✅ **K1：Path → 建筑群布局已完全实现**

- PathClusterLayout 数据模型 ✅
- PathClusterLayoutGenerator 布局算法 ✅
- PathClusterLayoutToBuildingSpecs 转换器 ✅
- PromptAssembler 集成 ✅
- 地形自适应 ✅

**系统现在可以从"单体建筑生成"跃迁到"城市级生成"！**

**这是 SimCity / CityEngine 级别的逻辑！**

