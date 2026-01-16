# FloorPlate / Courtyard Boolean 几何规则 v1

## 🎯 核心思想

**FloorPlate = 主体实心体量（Solid）**  
**CourtyardVoid = 从 FloorPlate 中减去的负体量（Void）**  
**墙体永远沿"实—空"的交界线生成**

## 📋 设计原则

1. **在 2D 做 Boolean**：在 XZ 平面执行 Boolean 运算
2. **在 3D 只做 Extrusion**：不执行 3D Boolean，只做拉伸
3. **从边界派生墙体**：外墙来自外边界，庭院墙来自洞边界
4. **数学简单、稳定**：v1 使用简化算法，适合 Minecraft 规模

## 🔧 实现组件

### 1. PolygonBoolean（多边形 Boolean 运算）

**位置**：`src/main/java/com/formacraft/common/geometry/boolean_/PolygonBoolean.java`

**核心方法**：
```java
PolygonBooleanResult subtract(Polygon2D base, List<Polygon2D> holes)
```

**v1 简化实现**：
- 提取洞的边界（用于生成庭院墙）
- 返回 base footprint（v1 保守策略）
- 未来：实现完整的多边形裁剪算法（Weiler–Atherton / Greiner–Hormann）

### 2. PolygonBooleanResult（Boolean 运算结果）

**位置**：`src/main/java/com/formacraft/common/geometry/boolean_/PolygonBooleanResult.java`

**字段**：
- `outerBoundaries`：外边界（一个或多个 polygon）
- `holeBoundaries`：洞的边界（每个洞的边界 polyline）

### 3. FloorCourtyardBooleanProcessor（Boolean 处理器）

**位置**：`src/main/java/com/formacraft/common/geometry/boolean_/FloorCourtyardBooleanProcessor.java`

**核心方法**：
```java
List<WallSegment> processBooleanAndGenerateWalls(
    FloorPlate floorPlate,
    List<CourtyardVoid> courtyards,
    double wallHeight,
    double wallThickness
)
```

**流程**：
1. 执行 2D Boolean 运算（FloorPlate.footprint - CourtyardVoid.footprint(s)）
2. 从外边界生成外墙（EXTERNAL，normal 向外）
3. 从洞边界生成庭院墙（COURTYARD，normal 向内）

## 🔄 完整流程

```
FloorPlate (Solid)
  ↓
- CourtyardVoid #1 (Void)
- CourtyardVoid #2 (Void)
  ↓
PolygonBoolean.subtract()
  ↓
PolygonBooleanResult
  ├─ outerBoundaries → 外墙 (EXTERNAL, normal 向外)
  └─ holeBoundaries → 庭院墙 (COURTYARD, normal 向内)
  ↓
WallSegment 列表
```

## 🧩 墙体生成规则

### 外墙（External Wall）

**来源**：EffectiveFootprint 的外边界

**生成**：
```java
WallSegment {
    type = EXTERNAL;
    baseline = boundaryPolyline;
    normal = outward;  // 指向外侧
}
```

**特点**：
- 生成 WALL_SURFACE (exterior)
- 生成 EDGE_OUTER (top)
- 允许窗、门、阳台

### 庭院墙（Courtyard Wall）

**来源**：CourtyardBoundaryEdges

**生成**：
```java
WallSegment {
    type = COURTYARD;
    baseline = holeBoundary;
    normal = inward;  // 指向 courtyard（反向）
}
```

**特点**：
- 生成 WALL_SURFACE (inward-facing)
- 允许窗、装饰
- 禁止阳台、外挑构件（v1）

### 内墙（Internal Wall）

**来源**：shared_wall（不从 Boolean 派生）

**生成**：
```java
WallSegment {
    type = INTERNAL;
    baseline = sharedEdge;
    normal = bidirectional;  // 或 undefined
}
```

**特点**：
- 生成 WALL_SURFACE (interior only)
- 不生成 EDGE_OUTER（v1）

## 📊 关键映射

| 几何事实 | 建筑语义 | WallType | Normal 方向 |
|---------|---------|----------|------------|
| Solid 外边界 | 外立面 | EXTERNAL | 向外 |
| Solid 内孔边界 | 内院立面 | COURTYARD | 向内 |
| Void 不生成 floor | 中庭无屋顶 | - | - |

## ⚠️ v1 限制

1. **多边形裁剪简化**：v1 不执行完整的多边形差集运算，使用保守策略
2. **openToSky 处理**：v1 只支持 `openToSky = true`（不生成屋顶）
3. **多个洞**：v1 支持多个洞，但每个洞独立处理
4. **复杂几何**：v1 适合简单多边形，复杂曲线需后续完善

## 🔮 未来扩展

1. **完整多边形裁剪**：实现 Weiler–Atherton 或 Greiner–Hormann 算法
2. **openToSky = false**：支持回廊、天井、半封闭院落
3. **多层 Boolean**：支持嵌套洞、复杂组合
4. **性能优化**：针对大型多边形的优化算法

## ✅ 系统能力

通过 Boolean 几何规则，系统现在支持：

- ✅ **回字形 / 中庭 / 组合体量**
- ✅ **中式院落 / 修道院 / 四合院**
- ✅ **非矩形复杂平面**
- ✅ **AI + 用户协作平面**

## 📚 相关文档

- `docs/STRUCTURAL_SKELETON_GEOMETRY_V1.md` - StructuralSkeleton 几何字段
- `docs/WALL_EXTRUSION_V1.md` - WallExtrusion 算法
- `docs/PLAN_SKELETON_TO_STRUCTURAL_CONVERTER.md` - 转换器文档
