# K2：工具约束接入 Path→Layout + 站点可视化预览实现总结

## ✅ 已完全实现

### 核心目标

**把工具约束（禁区/轮廓/选区/语义标注）接进 Path→Layout，并且提供一个站点可视化预览（HUD），让你马上能看见"沿路径生成的建筑群站点"是否正确。**

### 核心组件

#### 1. Geom2D（2D 几何工具）
- **位置**：`src/main/java/com/formacraft/common/geom/Geom2D.java`
- **功能**：
  - `pointInPolygon()` - 射线法判断点是否在多边形内
  - `aabbOfPolygon()` - 计算多边形的 AABB
  - `sampleFootprintAllAllowed()` - Footprint 采样（用网格点采样判断是否全部落在 allowed 区域内）
- **数据结构**：
  - `Vec2` - 2D 向量（record）
  - `AABB2` - 2D AABB

#### 2. ToolSnapshot（工具快照）
- **位置**：`src/main/java/com/formacraft/client/tools/ToolSnapshot.java`
- **字段**：
  - `hasSelection` / `selMin` / `selMax` - 选区（AABB）
  - `hasOutline` / `outlinePolygon` - 轮廓（2D Polygon）
  - `forbiddenPolygons` - 禁区/保护区（一组 polygon）
  - `semanticRegions` - 语义标注区域（polygon + label）
- **方法**：`fromTools()` - 从工具实例创建快照（强类型版本）
- **适配器**：
  - SelectionTool → AABB
  - OutlineTool → Polygon（支持圆形和多边形）
  - ProtectedZoneTool → Polygons
  - SemanticLabelTool → SemanticRegions

#### 3. ToolLayoutConstraints（工具布局约束）
- **位置**：`src/main/java/com/formacraft/client/tools/ToolLayoutConstraints.java`
- **功能**：把 Snapshot 变成 LayoutConstraints（真正裁切/禁区过滤）
- **约束检查**：
  - 选区 AABB 约束
  - 轮廓多边形约束
  - 禁区多边形约束
  - Footprint 采样（占地范围也被约束）
- **方法**：`resolveSemanticTag()` - 给站点打 tag（落在哪个语义区就用那个 label）

#### 4. LayoutSitePreviewState（站点预览状态）
- **位置**：`src/main/java/com/formacraft/client/preview/LayoutSitePreviewState.java`
- **功能**：存储当前预览的站点列表
- **方法**：
  - `setSites(List<LayoutSite>)` - 设置站点列表
  - `clear()` - 清除站点列表
  - `isEnabled()` - 是否启用预览
  - `getSites()` - 获取站点列表（只读）

#### 5. LayoutSiteHudOverlay（站点 HUD 预览）
- **位置**：`src/main/java/com/formacraft/client/preview/LayoutSiteHudOverlay.java`
- **功能**：把 sites 画在屏幕左上角小地图式列表
- **显示内容**：
  - 站点总数
  - 每个站点的索引、坐标、朝向箭头、标签
  - 最多显示 10 个站点（超出显示 "..."）
- **注册**：在 `ClientInitializer` 中调用 `LayoutSiteHudOverlay.register()`

### 使用示例

```java
import com.formacraft.client.tools.*;
import com.formacraft.common.layout.*;
import com.formacraft.client.tool.*;
import com.formacraft.client.preview.LayoutSitePreviewState;

// 1. 创建工具快照
ToolSnapshot snap = ToolSnapshot.fromTools(
        SelectionTool.INSTANCE,
        OutlineTool.INSTANCE,
        ProtectedZoneTool.INSTANCE,
        SemanticLabelTool.INSTANCE
);

// 2. 创建约束
ToolLayoutConstraints constraints = new ToolLayoutConstraints(snap);

// 3. 生成站点（使用 K1 的 PathClusterLayoutPlanner）
PathClusterLayoutPlanner planner = new PathClusterLayoutPlanner(ctx.terrainStrategy);
PathClusterLayoutPlanner.Options opt = new PathClusterLayoutPlanner.Options();
opt.spacing = 12;
opt.footprintW = 9;
opt.footprintD = 11;
opt.clearance = 2;
opt.lateralOffset = 5; // 沿路右侧布置一排楼（左侧用 -5）

List<BlockPos> pathPoints = ctx.pathTool != null ? ctx.pathTool.getNodes() : List.of();

List<LayoutSite> sites = planner.plan(
        ctx.world,
        pathPoints,
        origin,
        opt,
        constraints  // 使用工具约束
);

// 4. 自动 tag：如果落在语义区，tag = label
List<LayoutSite> tagged = new ArrayList<>(sites.size());
for (LayoutSite s : sites) {
    String tag = constraints.resolveSemanticTag(s.anchor, s.tag);
    tagged.add(s.withTag(tag));
}
sites = tagged;

// 5. 预览（HUD）
LayoutSitePreviewState.setSites(sites);
```

### 完整链路

```
PathTool（用户画的路径）
  ↓
ToolSnapshot.fromTools()（工具快照）
  ↓
ToolLayoutConstraints（工具约束）
  ↓
PathClusterLayoutPlanner.plan()（生成站点，应用约束）
  ↓
List<LayoutSite>（站点序列，已应用约束和语义 tag）
  ↓
LayoutSitePreviewState.setSites()（设置预览）
  ↓
LayoutSiteHudOverlay（HUD 显示）
```

### ✅ K2 到这里你已经得到什么？

✅ **禁区/轮廓/选区 会真实裁切 Path→sites**
✅ **footprint 也参与裁切（不是只看 anchor 点）**
✅ **语义标注 会自动给 site 打 tag（K3.1 会用这个 tag 选不同 ComponentPreset）**
✅ **你能 立刻看到站点列表预览（下一步我会给"世界线框预览"版本）**

### 可视化效果

HUD 预览会显示在屏幕左上角：
```
Cluster Sites: 8
#0  (100,64,200)  →  tag=cluster_site
#1  (112,64,200)  →  tag=office
#2  (124,64,200)  →  tag=gate
...
```

## 📝 总结

✅ **K2：工具约束接入 Path→Layout + 站点可视化预览已完全实现**

- Geom2D（2D 几何工具）✅
- ToolSnapshot（工具快照）✅
- ToolLayoutConstraints（工具布局约束）✅
- LayoutSitePreviewState（站点预览状态）✅
- LayoutSiteHudOverlay（站点 HUD 预览）✅

**系统现在可以真实地应用工具约束，并立即看到站点预览！**

**这是 FormaCraft 从"能生成站点"到"可靠约束站点"的关键一步！**

