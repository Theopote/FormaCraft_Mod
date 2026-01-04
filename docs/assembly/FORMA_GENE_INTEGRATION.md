# Forma-Gene Protocol 思想融入计划

本文档记录如何将 Forma-Gene Protocol v1.0 中的核心思想融入当前 `extra.assembly` 实现，**不改变现有 JSON 结构**，通过扩展 `macro` 参数和操作功能来实现。

## 核心原则

1. **保持现有结构**：继续使用 `graph.components` + `macro` 的设计
2. **通过 macro 扩展参数化能力**：将 Forma-Gene 的"参数滑块"思想通过 `macro.*` 实现
3. **增强现有操作的表达能力**：扩展现有操作的参数，而不是创建新的抽象层

## 已实现的部分 ✅

### 1. 语义材质表
- ✅ **当前实现**：`paletteId` + `PaletteResolver`
- ✅ **Forma-Gene 思想**：解耦"功能"与"方块ID"
- **状态**：已完整实现，无需改进

### 2. 参数化建模（Macro 参数）
- ✅ **当前实现**：`macro.{shapeType, heightScale, roofType, roofCurvature, overhang, openness, symmetry, style}`
- ✅ **Forma-Gene 思想**：通过"参数滑块"简化 LLM 输出
- **状态**：已实现核心功能，可扩展

### 3. 屋顶控制
- ✅ **当前实现**：`ROOF_COVER` + `macro.roofType/roofCurvature/overhang`
- ⚠️ **Forma-Gene 思想**：`curvature_power`（曲率控制）、`corner_lift_factor`（飞檐翘角）
- **可扩展**：添加 `macro.roofCurvaturePower` 和 `ROOF_COVER.cornerLift` 参数

### 4. 立面规则
- ✅ **当前实现**：`facade.{openings, facadeGrid, surfaceBands}`
- ✅ **Forma-Gene 思想**：规则化表面处理
- **状态**：已实现，可扩展纹理噪点

## 可融入的改进建议 🚀

### 1. 分段垂直操作（Vertical Extrusion Segments）

**Forma-Gene 思想**：
```json
"vertical_extrusion": {
  "segments": [
    {"height": 5, "scale_top": 1.0},
    {"height": 4, "scale_top": 0.6},  // 收缩（塔楼腰部）
    {"height": 10, "scale_top": 1.2}  // 扩张（悬挑）
  ]
}
```

**融入方案（通过 macro 扩展）**：
```json
{
  "macro": {
    "verticalProfile": [
      {"height": 5, "scaleTop": 1.0},
      {"height": 4, "scaleTop": 0.6},
      {"height": 10, "scaleTop": 1.2}
    ]
  }
}
```

**实现方式**：
- ✅ **已实现**：`AssemblyMacroApplier.applyVerticalProfile()` 读取 `macro.verticalProfile`
- 将单个 `SHELL_BOX` 分解为多个 `SHELL_BOX`，通过 `w/d` 缩放实现分段收缩/扩张
- 每个分段保持原始组件的材质、facade 等属性
- 分段高度总和可以与原始高度不匹配（会发出警告但继续执行）

**优先级**：✅ P1（已完成，可实现塔楼、金字塔、阶梯式建筑等复杂形状）

---

### 2. 扭转操作（Twist）

**Forma-Gene 思想**：
```json
"vertical_extrusion": {
  "segments": [{
    "function": "TWIST",
    "twist_angle": 45
  }]
}
```

**融入方案**：
- ✅ **已部分实现**：`SPLINE_SWEEP` 支持 `twistTurns` 和 `twistPhase`
- ✅ **已扩展**：`SHELL_BOX` 现在支持 `twistTurns` 和 `twistPhase` 参数

**实现方式**：
- ✅ **已实现**：`SHELL_BOX` 操作读取 `twistTurns` 和 `twistPhase`
- 每一层（y坐标）根据高度比例计算旋转角度
- 将局部坐标 (x, z) 绕中心点旋转，实现螺旋效果
- 支持负值扭转（反向旋转）

**优先级**：✅ P2（已完成，特殊场景，如 DNA 塔、螺旋楼梯、扭转式建筑）

---

### 3. 屋顶曲率精细控制

**Forma-Gene 思想**：
```json
"roof_operator": {
  "profile_params": {
    "curvature_power": 1.5,  // 0.0=直线, >0=凹曲线, <0=凸曲线
    "corner_lift_factor": 0.8  // 飞檐翘角
  }
}
```

**融入方案（扩展 ROOF_COVER）**：
```json
{
  "type": "ROOF_COVER",
  "roofType": "HIP",
  "curvaturePower": 1.5,  // 新增：曲率幂次（影响 rise 的计算方式）
  "cornerLift": 0.8,  // 新增：角落抬升系数（中式/日式飞檐）
  "rise": 6
}
```

**或通过 macro**：
```json
{
  "macro": {
    "roofType": "HIP",
    "roofCurvaturePower": 1.5,
    "roofCornerLift": 0.8
  }
}
```

**实现方式**：
- `ROOF_COVER` 读取 `curvaturePower`，调整屋顶曲线的计算方式
- `cornerLift` 在角落处额外提升 Y 坐标

**优先级**：P1（高价值，可实现中式/日式/泰式屋顶的曲线特征）

---

### 4. 表面纹理噪点（Noise Overlay）

**Forma-Gene 思想**：
```json
"facade_rules": {
  "noise_overlay": {
    "material": "secondary_fill",
    "probability": 0.2,
    "method": "PERLIN"
  }
}
```

**融入方案（扩展 SURFACE_PATTERN 或新建操作）**：
```json
{
  "facade": {
    "surfacePattern": {
      "pattern": "NOISE",
      "noiseMaterial": "minecraft:white_wool",
      "noiseProbability": 0.2,
      "noiseMethod": "PERLIN"
    }
  }
}
```

**实现方式**：
- ✅ **已实现**：`SURFACE_PATTERN` 现在支持 `pattern: "NOISE"`
- 支持三种噪点方法：`PERLIN`（伪 Perlin 噪点）、`RANDOM`（纯随机）、`HASH`（确定性哈希噪点，默认）
- 通过 `noiseProbability` 控制噪点密度（0.0~1.0）

**优先级**：✅ P2（已完成，增强真实感，模拟风化、磨损、随机纹理）

---

### 5. Boolean 运算（挖洞）✅ 已实现

**Forma-Gene 思想**：
```json
"footprint": {
  "boolean_ops": [
    {"op": "SUBTRACT", "shape": "RECTANGLE", "size": [6, 6]}
  ]
}
```

**融入方案（通过 CLEAR_BOX 或扩展组件）**：
```json
{
  "graph": {
    "components": [
      {"id": "Main", "type": "SHELL_BOX", "w": 20, "d": 20, "h": 10},
      {"id": "Courtyard", "type": "CLEAR_BOX", "w": 6, "d": 6, "h": 10}
    ]
  }
}
```

**或通过 macro**（✅ **已实现**）：
```json
{
  "macro": {
    "primaryComponent": "Main",
    "subtractHoles": [
      {"type": "RECTANGLE", "w": 6, "d": 6, "at": {"x": 0, "z": 0}}
    ]
  }
}
```

**实现方式**：
- ✅ **已实现**：`macro.subtractHoles` 自动生成 `CLEAR_BOX` 组件
- `AssemblyMacroApplier.applySubtractHoles()` 读取 `macro.subtractHoles` 数组，为每个 hole 创建 `CLEAR_BOX` 组件

**优先级**：✅ P1（已完成，可实现四合院、土楼等中空结构）

---

### 6. 拓扑底座抽象（Footprint）

**Forma-Gene 思想**：
独立的 `footprint` 对象，支持 `type`, `params`, `boolean_ops`

**融入方案（概念化，不改变结构）**：
- 当前 `EXTRUDE_POLYGON` 和 `SHELL_BOX` 已经可以表达 footprint
- 在文档中明确：`SHELL_BOX.{w,d}` = footprint，`EXTRUDE_POLYGON.points[]` = footprint
- 不需要创建新的抽象，只需要在文档中统一概念

**优先级**：✅ P0（已完成，仅文档改进）

---

## 实施优先级

### ✅ P0（已完成 - 文档改进）
- [x] 在 `ASSEMBLY_EXAMPLES.md` 中明确 footprint 概念映射
- [x] 添加参数化建模思想说明

### ✅ P1（高价值功能 - 全部完成）
- [x] Boolean 运算辅助（`macro.subtractHoles`）✅ **已实现**
- [x] 分段垂直操作（`macro.verticalProfile`）✅ **已实现**
- [x] 屋顶曲率精细控制（`ROOF_COVER.curvaturePower/cornerLift`）✅ **已实现**

### ✅ P2（增强功能 - 全部完成）
- [x] 扭转操作扩展（`SHELL_BOX` 支持 `twistTurns/twistPhase`）✅ **已实现**
- [x] 表面纹理噪点（`SURFACE_PATTERN` 支持 `NOISE` 模式）✅ **已实现**

## 总结

当前实现已经很好地支持了 Forma-Gene Protocol 的核心思想：
- ✅ **参数化建模**：通过 `macro` 实现
- ✅ **语义材质**：通过 `paletteId` 实现
- ✅ **规则化立面**：通过 `facade.*` 实现
- ✅ **屋顶控制**：通过 `ROOF_COVER` + `macro` 实现
- ✅ **Boolean 运算**：通过 `macro.subtractHoles` 实现 ✅ **新实现**

可以融入的改进主要集中在：
1. **增强垂直操作的表达能力**（✅ 分段已完成、✅ 扭转已完成）
2. **增强屋顶的精细控制**（✅ 曲率幂次、飞檐翘角已完成）
3. **增强表面处理的真实性**（纹理噪点待实现）

所有这些改进都可以在**不改变现有 JSON 结构**的前提下，通过扩展 `macro` 参数和操作功能来实现。
