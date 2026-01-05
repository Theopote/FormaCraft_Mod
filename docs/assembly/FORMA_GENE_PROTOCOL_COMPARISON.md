# Forma-Gene Protocol v1.0 与 Formacraft 实现对比

本文档对比 Forma-Gene Protocol v1.0 的设计思想与 Formacraft 的实际实现，说明哪些功能已实现、实现方式如何，以及是否存在差异。

## 核心思想对比

### ✅ 1. 参数化建模（Parametric Modeling）

**Forma-Gene Protocol 思想**：
> LLM 不是在画图，而是在调节参数滑块。

**Formacraft 实现**：
- ✅ **已完全实现**：通过 `extra.assembly.macro` 参数系统
- **实现方式**：`macro.{shapeType, heightScale, roofType, roofCurvature, overhang, openness, symmetry, style, verticalProfile, twist, ...}`
- **文档位置**：`docs/assembly/ASSEMBLY_EXAMPLES.md` 明确说明"参数化建模"思想
- **状态**：✅ 完全符合 Forma-Gene Protocol 的核心思想

---

### ✅ 2. 语义材质表（Palette Map）

**Forma-Gene Protocol 设计**：
```json
"palette_map": {
  "primary_structure": "minecraft:stone_bricks",
  "secondary_fill": "minecraft:white_wool",
  "roof_main": "minecraft:dark_prismarine",
  ...
}
```

**Formacraft 实现**：
- ✅ **已完全实现**：通过 `paletteId` + `PaletteResolver` 系统
- **实现方式**：
  - `BuildingSpec.extra.paletteId` 指定调色板 ID
  - `PaletteResolver` 将语义名称（如 `ROOF_TILE`, `WALL_STONE`）映射到具体方块 ID
  - 支持多个预定义调色板（如 `PALETTE_GOTHIC_CATHEDRAL_A`, `PALETTE_INDUSTRIAL_STEEL_A`）
- **文档位置**：`docs/assembly/FORMA_GENE_INTEGRATION.md` 第 13-16 行
- **状态**：✅ 完全符合 Forma-Gene Protocol 思想，解耦"功能"与"方块ID"

---

### ✅ 3. 体积（Volumes）

**Forma-Gene Protocol 设计**：
```json
"volumes": [
  { ...Volume_Object_1... },
  { ...Volume_Object_2... }
]
```

**Formacraft 实现**：
- ✅ **已完全实现**：通过 `graph.components[]` 数组
- **实现方式**：
  - 每个 `component` 代表一个三维体积（如 `SHELL_BOX`, `EXTRUDE_POLYGON`, `CYLINDER` 等）
  - 组件可以组合、连接，形成复杂建筑
- **文档位置**：`docs/assembly/ASSEMBLY_EXAMPLES.md` 第 14 行
- **状态**：✅ 完全符合 Forma-Gene Protocol 思想

---

### ✅ 4. 拓扑底座（Footprint）

**Forma-Gene Protocol 设计**：
```json
"footprint": {
  "type": "REGULAR_POLYGON",
  "params": {
    "sides": 8,
    "radius": 12,
    "elongation": 1.0
  },
  "boolean_ops": [
    { "op": "SUBTRACT", "shape": "RECTANGLE", "size": [6, 6] }
  ]
}
```

**Formacraft 实现**：
- ✅ **已实现**：通过 `SHELL_BOX` 和 `EXTRUDE_POLYGON` 操作
- **实现方式**：
  - `SHELL_BOX.{w,d}` = 矩形 footprint
  - `EXTRUDE_POLYGON.points[]` = 多边形 footprint
  - `macro.shapeType: "CIRCLE"` = 圆形 footprint（自动转换为 `EXTRUDE_POLYGON`）
  - `macro.subtractHoles[]` = Boolean 减法运算（自动生成 `CLEAR_BOX` 组件）
- **文档位置**：`docs/assembly/ASSEMBLY_EXAMPLES.md` 第 10-13 行，`FORMA_GENE_INTEGRATION.md` 第 175-214 行
- **状态**：✅ 功能完全实现，但结构略有不同（通过组件而非独立 footprint 对象）

---

### ✅ 5. 垂直操作（Vertical Extrusion）

**Forma-Gene Protocol 设计**：
```json
"vertical_extrusion": {
  "segments": [
    {"height": 5, "scale_top": 1.0},
    {"height": 4, "scale_top": 0.6},  // 收缩
    {"height": 10, "scale_top": 1.2}  // 扩张
  ]
}
```

**Formacraft 实现**：
- ✅ **已完全实现**：通过 `macro.verticalProfile` 参数
- **实现方式**：
  - `macro.verticalProfile: [{height: 5, scaleTop: 1.0}, {height: 4, scaleTop: 0.6}]`
  - `AssemblyMacroApplier.applyVerticalProfile()` 将单个 `SHELL_BOX` 分解为多个分段
  - 每个分段有不同的高度和 w/d 缩放
- **文档位置**：`docs/assembly/FORMA_GENE_INTEGRATION.md` 第 35-67 行，`ASSEMBLY_EXAMPLES.md` 第 84-87 行
- **状态**：✅ 完全符合 Forma-Gene Protocol 思想，已实现（P1 优先级）

---

### ✅ 6. 扭转操作（Twist）

**Forma-Gene Protocol 设计**：
```json
"vertical_extrusion": {
  "segments": [{
    "function": "TWIST",
    "twist_angle": 45
  }]
}
```

**Formacraft 实现**：
- ✅ **已完全实现**：通过 `SHELL_BOX.twistTurns` 和 `SHELL_BOX.twistPhase` 参数
- **实现方式**：
  - `twistTurns`: 扭转圈数（-2.0~2.0，如 0.25=90°、0.5=180°、1.0=360°）
  - `twistPhase`: 初始相位（0.0~1.0，控制起始旋转角度）
  - 每一层（y坐标）根据高度比例计算旋转角度，将局部坐标绕中心点旋转
- **文档位置**：`docs/assembly/FORMA_GENE_INTEGRATION.md` 第 71-93 行，`ASSEMBLY_EXAMPLES.md` 第 88-91 行
- **状态**：✅ 完全符合 Forma-Gene Protocol 思想，已实现（P2 优先级）

---

### ✅ 7. 通用屋顶求解器（Roof Solver）

**Forma-Gene Protocol 设计**：
```json
"roof_operator": {
  "type": "HIP",
  "profile_params": {
    "height": 6,
    "overhang_length": 3,
    "curvature_power": 1.5,  // 0.0=直线, >0=凹曲线, <0=凸曲线
    "corner_lift_factor": 0.8  // 飞檐翘角
  }
}
```

**Formacraft 实现**：
- ✅ **已完全实现**：通过 `ROOF_COVER` 操作 + `macro.roofType/roofCurvature/roofCurvaturePower/roofCornerLift`
- **实现方式**：
  - `ROOF_COVER` 操作支持多种屋顶类型：`FLAT`, `GABLE`, `HIP`, `CONE`, `PYRAMID`
  - `macro.roofCurvature`: 0.0~1.0 或 `LOW/MEDIUM/HIGH`（驱动 `ROOF_COVER.rise`）
  - `macro.roofCurvaturePower`: 0.1~3.0（曲率幂次，1.0=线性，>1.0=凹曲线，<1.0=凸曲线）✅ **已实现**
  - `macro.roofCornerLift`: 0.0~2.0（飞檐翘角系数，用于中式/日式/泰式屋顶）✅ **已实现**
  - `macro.overhang`: `NONE/SMALL/MEDIUM/LARGE`（出檐长度）
- **文档位置**：`docs/assembly/FORMA_GENE_INTEGRATION.md` 第 97-135 行，`ASSEMBLY_EXAMPLES.md` 第 81-83 行
- **状态**：✅ 完全符合 Forma-Gene Protocol 思想，所有参数已实现

---

### ✅ 8. 表面韵律与构件（Facade Rules）

**Forma-Gene Protocol 设计**：
```json
"facade_rules": {
  "base_material": "primary_structure",
  "noise_overlay": {
    "material": "secondary_fill",
    "probability": 0.2,
    "method": "PERLIN"
  },
  "grid_system": {
    "h_spacing": 4,
    "v_spacing": 5,
    "element": "PILLAR_OUTER"
  },
  "attachments": [
    {"type": "WINDOW", "placement_rule": "CENTER_OF_GRID", "probability": 0.8},
    {"type": "DOOR", "placement_rule": "BOTTOM_CENTER_FRONT"}
  ]
}
```

**Formacraft 实现**：
- ✅ **已完全实现**：通过 `facade.{openings, facadeGrid, surfaceBands, surfacePattern}` 系统
- **实现方式**：
  - `facade.openings[]`: 定义窗户/门的规则化放置（支持 `rows`, `cols`, `winW`, `winH`, `placement` 等）
  - `facade.facadeGrid`: 定义网格系统（柱子、横梁等）
  - `facade.surfaceBands`: 定义表面带（水平/垂直装饰带）
  - `facade.surfacePattern`: 定义表面图案
    - `pattern: "NOISE"`: 纹理噪点 ✅ **已实现**
      - `noiseMaterial`: 噪点材质
      - `noiseProbability`: 噪点概率（0.0~1.0）
      - `noiseMethod`: `PERLIN`/`RANDOM`/`HASH` ✅ **已实现**
- **文档位置**：`docs/assembly/FORMA_GENE_INTEGRATION.md` 第 139-171 行，`ASSEMBLY_EXAMPLES.md` 第 92-96 行
- **状态**：✅ 完全符合 Forma-Gene Protocol 思想，所有功能已实现

---

### ✅ 9. Boolean 运算（挖洞）

**Forma-Gene Protocol 设计**：
```json
"footprint": {
  "boolean_ops": [
    {"op": "SUBTRACT", "shape": "RECTANGLE", "size": [6, 6]}
  ]
}
```

**Formacraft 实现**：
- ✅ **已完全实现**：通过 `macro.subtractHoles[]` 和 `CLEAR_BOX` 操作
- **实现方式**：
  - `macro.subtractHoles: [{type: "RECTANGLE", w: 6, d: 6, at: {x: 0, z: 0}}]`
  - `AssemblyMacroApplier.applySubtractHoles()` 自动为每个 hole 创建 `CLEAR_BOX` 组件
  - `CLEAR_BOX` 操作在指定区域清除方块（实现减法运算）
- **文档位置**：`docs/assembly/FORMA_GENE_INTEGRATION.md` 第 175-214 行
- **状态**：✅ 完全符合 Forma-Gene Protocol 思想，已实现（P1 优先级）

---

## 实现方式对比

### 结构差异

| Forma-Gene Protocol | Formacraft 实现 | 说明 |
|---------------------|-----------------|------|
| 独立的 `footprint` 对象 | `SHELL_BOX`/`EXTRUDE_POLYGON` 组件 | Formacraft 将 footprint 作为组件的一部分，而非独立对象 |
| 独立的 `vertical_extrusion` 对象 | `macro.verticalProfile` | Formacraft 通过 macro 参数控制，而非独立操作对象 |
| 独立的 `roof_operator` 对象 | `ROOF_COVER` 操作 + `macro.roofType` | Formacraft 通过操作 + macro 组合实现 |
| 独立的 `facade_rules` 对象 | `facade.*` 属性 | Formacraft 将 facade 规则作为组件的属性 |

### 设计哲学差异

**Forma-Gene Protocol**：
- 采用**独立对象**的设计（footprint, vertical_extrusion, roof_operator, facade_rules）
- 每个对象有明确的职责边界

**Formacraft**：
- 采用**组件 + macro 参数**的设计
- 通过 `graph.components[]` 定义体积，通过 `macro.*` 控制参数化行为
- 更灵活，可以组合多个组件和操作

### 优势对比

**Forma-Gene Protocol 的优势**：
- 结构清晰，职责分明
- 易于理解和文档化

**Formacraft 实现的优势**：
- ✅ **更灵活**：可以组合多个组件和操作
- ✅ **更简洁**：不需要独立的 footprint/vertical_extrusion 对象
- ✅ **向后兼容**：可以在现有结构上扩展，不破坏兼容性
- ✅ **已实现**：所有核心功能都已实现并经过测试

---

## 实战案例对比

### 案例 A：福建土楼（The Tulou）

**Forma-Gene Protocol 描述**：
```json
{
  "footprint": {
    "type": "CIRCLE",
    "params": {"radius": 20},
    "boolean_ops": [{"op": "SUBTRACT", "radius": 12}]
  },
  "vertical_extrusion": {
    "segments": [{"height": 15, "scale_top": 1.0}]
  },
  "roof_operator": {
    "type": "GABLE",
    "overhang": 2,
    "curvature": 0.1
  }
}
```

**Formacraft 实现**：
```json
{
  "graph": {
    "components": [
      {
        "id": "Main",
        "type": "SHELL_BOX",
        "w": 20, "d": 20, "h": 15,
        "facade": {"openings": [{"y_min": 10, "winH": 1}]}
      },
      {
        "id": "Courtyard",
        "type": "CLEAR_BOX",
        "w": 12, "d": 12, "h": 15
      }
    ]
  },
  "macro": {
    "shapeType": "CIRCLE",
    "subtractHoles": [{"type": "CIRCLE", "radius": 12}],
    "roofType": "GABLE",
    "roofCurvature": 0.1,
    "overhang": "SMALL"
  }
}
```

**状态**：✅ Formacraft 可以完全实现土楼的所有特征

---

### 案例 B：哥特式大教堂尖塔（Gothic Spire）

**Forma-Gene Protocol 描述**：
```json
{
  "footprint": {
    "type": "REGULAR_POLYGON",
    "params": {"sides": 4}
  },
  "vertical_extrusion": {
    "segments": [
      {"height": 10, "scale": 1.0},
      {"height": 20, "scale": 0.0}
    ]
  },
  "facade_rules": {
    "grid_system": {"element": "BUTTRESS", "spacing": 3},
    "attachments": [{"type": "STAINED_GLASS_PANE"}]
  }
}
```

**Formacraft 实现**：
```json
{
  "graph": {
    "components": [
      {
        "id": "Spire",
        "type": "SHELL_BOX",
        "w": 8, "d": 8, "h": 30,
        "facade": {
          "facadeGrid": {"element": "BUTTRESS", "hSpacing": 3},
          "openings": [{"type": "STAINED_GLASS_PANE"}]
        }
      }
    ]
  },
  "macro": {
    "verticalProfile": [
      {"height": 10, "scaleTop": 1.0},
      {"height": 20, "scaleTop": 0.01}
    ],
    "style": {
      "styleId": "Gothic_Cathedral",
      "verticality": 0.95
    }
  }
}
```

**状态**：✅ Formacraft 可以完全实现哥特式尖塔的所有特征

---

## 总结

### ✅ 已实现的功能（100%）

Formacraft **已经完全实现了 Forma-Gene Protocol v1.0 的所有核心思想**：

1. ✅ **参数化建模**：通过 `macro` 参数系统
2. ✅ **语义材质表**：通过 `paletteId` + `PaletteResolver`
3. ✅ **体积集合**：通过 `graph.components[]`
4. ✅ **拓扑底座**：通过 `SHELL_BOX`/`EXTRUDE_POLYGON`
5. ✅ **垂直操作**：通过 `macro.verticalProfile`
6. ✅ **扭转操作**：通过 `SHELL_BOX.twistTurns/twistPhase`
7. ✅ **屋顶求解器**：通过 `ROOF_COVER` + `macro.roofCurvature/roofCurvaturePower/roofCornerLift`
8. ✅ **表面韵律**：通过 `facade.{openings, facadeGrid, surfaceBands, surfacePattern}`
9. ✅ **Boolean 运算**：通过 `macro.subtractHoles` + `CLEAR_BOX`
10. ✅ **纹理噪点**：通过 `facade.surfacePattern.pattern: "NOISE"`

### 实现方式差异

- **Forma-Gene Protocol**：采用独立对象设计（footprint, vertical_extrusion, roof_operator, facade_rules）
- **Formacraft**：采用组件 + macro 参数设计（`graph.components[]` + `macro.*`）

### 优势

Formacraft 的实现方式：
- ✅ **更灵活**：可以组合多个组件和操作
- ✅ **更简洁**：不需要独立的中间对象
- ✅ **向后兼容**：可以在现有结构上扩展
- ✅ **已实现并测试**：所有功能都已实现并经过实际使用验证

### 结论

**Formacraft 已经完全实现了 Forma-Gene Protocol v1.0 的核心思想**，只是实现方式采用了更灵活的"组件 + macro 参数"设计，而非独立的中间对象。这种设计在实际使用中更加灵活和强大，同时保持了与 Forma-Gene Protocol 相同的参数化建模哲学。

