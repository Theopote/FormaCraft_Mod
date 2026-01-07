# K1：Path → 建筑群布局（Cluster Layout Planner）实现总结

## ✅ 已完全实现

### 核心目标

**PathTool 画一条路径 → 自动生成一串"站点 sites"（带位置 + 朝向 + 半径/占地）→ 供后续 Program/ComponentPreset 直接装配。**

**道路、长城、办公楼组、沿路村落都靠这一层。**

### 核心组件

#### 1. LayoutSite（布局站点）
- **位置**：`src/main/java/com/formacraft/common/layout/LayoutSite.java`
- **字段**：
  - `anchor` - 站点锚点（世界坐标）
  - `facing` - 朝向（沿路径切线）
  - `footprintW` - 占地宽（X方向/侧向宽）
  - `footprintD` - 占地深（沿 facing 方向）
  - `clearance` - 额外安全边距（用于碰撞/避让）
  - `tag` - 可选：语义标签（如 "office", "tower", "gate"）
- **方法**：`withTag(String)` - 创建带新 tag 的站点（不可变）

#### 2. PathSample（路径采样点）
- **位置**：`src/main/java/com/formacraft/common/layout/PathSample.java`
- **字段**：`x`, `z`（double 精度）
- **用途**：用于路径重采样和朝向计算

#### 3. LayoutConstraints（布局约束接口）
- **位置**：`src/main/java/com/formacraft/common/layout/LayoutConstraints.java`
- **方法**：
  - `allowAnchor(BlockPos)` - 是否允许在该位置放置
  - `allowFootprint(BlockPos, int, int, int)` - 是否允许某个 footprint 区域放置
- **常量**：`ALLOW_ALL` - 允许所有位置的约束（默认）
- **设计**：K2 会把禁区/轮廓/选区/语义标注接进来；先把接口摆好

#### 4. PathClusterLayoutPlanner（核心规划器）
- **位置**：`src/main/java/com/formacraft/common/layout/PathClusterLayoutPlanner.java`
- **功能**：从 PathTool 路径生成建筑站点序列
- **配置选项（Options）**：
  - `spacing` - 站点间距（格，默认 10）
  - `footprintW` - 默认占地宽（默认 9）
  - `footprintD` - 默认占地深（默认 11）
  - `clearance` - 安全边距（默认 2）
  - `lateralOffset` - 沿法线方向偏移（>0右侧，<0左侧，默认 0）
  - `maxSites` - 最多生成多少个站点（默认 64）
  - `snapToCardinal` - 朝向是否只用 NESW（默认 true）
  - `defaultTag` - 默认标签（默认 "cluster_site"）

### 核心功能点（v1）

✅ **PathTool 的 world points → 2D polyline**
✅ **按 spacing 重采样（每隔 N 格一个站点）**
✅ **站点朝向 = 路径切线方向（N/E/S/W）**
✅ **地形：用 TerrainStrategySampler.sampleGroundY() 把站点落到地面**
✅ **简易碰撞：站点之间按 footprint+clearance 做 AABB 近似避免重叠**
✅ **可选：左右偏移（比如沿路两侧布置办公楼）**

### 使用示例

```java
import com.formacraft.common.layout.*;
import com.formacraft.common.terrain.TerrainStrategySampler;
import com.formacraft.client.tool.PathTool;
import net.minecraft.util.math.BlockPos;

// 1. 创建规划器
TerrainStrategySampler terrainStrategy = new TerrainStrategySampler();
PathClusterLayoutPlanner planner = new PathClusterLayoutPlanner(terrainStrategy);

// 2. 配置选项
PathClusterLayoutPlanner.Options opt = new PathClusterLayoutPlanner.Options();
opt.spacing = 12;
opt.footprintW = 9;
opt.footprintD = 11;
opt.clearance = 2;
opt.lateralOffset = 5; // 沿路右侧布置一排楼（左侧用 -5）

// 3. 获取路径点
List<BlockPos> pathPoints = ctx.pathTool != null ? ctx.pathTool.getNodes() : List.of();

// 4. 生成站点
List<LayoutSite> sites = planner.plan(
        ctx.world,
        pathPoints,
        origin,
        opt,
        LayoutConstraints.ALLOW_ALL // K2 会换成真正 constraints
);

// 5. 可视化验证（临时 patch，可马上看效果）
List<BlockPatch> debug = new ArrayList<>();
for (LayoutSite s : sites) {
    for (int dy = 0; dy < 3; dy++) {
        debug.add(new BlockPatch(
                BlockPatch.PLACE,
                s.anchor.getX() - origin.getX(),
                (s.anchor.getY() - origin.getY()) + dy,
                s.anchor.getZ() - origin.getZ(),
                "minecraft:purple_wool"
        ));
    }
}

// TODO: 把 sites 交给 Program → ComponentPreset（K3.1）
```

### 完整链路

```
PathTool（用户画的路径）
  ↓
PathClusterLayoutPlanner.plan()
  ↓
List<LayoutSite>（站点序列）
  ↓
Program → ComponentPreset（K3.1）
  ↓
ComponentGenerator.generate()
  ↓
List<BlockPatch>
```

### 可视化验证

即使你还没写 Program/ComponentPreset，你也可以先做"可视化验证"：

- 对每个 site 放一个粒子/临时线框（HUD overlay）
- 或者临时生成 patch：在站点放一个彩色羊毛柱/旗帜

**举例（临时 patch，可马上看效果）**：
```java
// debug patches: mark sites with colored wool pillar
List<BlockPatch> debug = new ArrayList<>();
for (LayoutSite s : sites) {
    for (int dy = 0; dy < 3; dy++) {
        debug.add(new BlockPatch(BlockPatch.PLACE,
                s.anchor.getX() - origin.getX(),
                (s.anchor.getY() - origin.getY()) + dy,
                s.anchor.getZ() - origin.getZ(),
                "minecraft:purple_wool"));
    }
}
```

### ✅ K1 交付清单

你现在已经有：

✅ **LayoutSite**：站点定义（含 facing + footprint）
✅ **LayoutConstraints**：预留禁区/轮廓/选区裁切入口
✅ **PathClusterLayoutPlanner**：路径 → 建筑群站点序列（带地形适配 + 碰撞避免 + 侧向偏移）

## 📝 总结

✅ **K1：Path → 建筑群布局（Cluster Layout Planner）已完全实现**

- LayoutSite（布局站点）✅
- PathSample（路径采样点）✅
- LayoutConstraints（布局约束接口）✅
- PathClusterLayoutPlanner（核心规划器）✅

**系统现在可以从 PathTool 路径自动生成建筑站点序列！**

**这是 FormaCraft 从"单体建筑"到"建筑群布局"的关键一步！**
