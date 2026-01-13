# Variant Rules Specification v1

## 概述

`variant_rules` 定义了一个 Component Prototype 如何生成变体（Variant）的**约束规则**。

核心原则：**非破坏性变化**——变体可以调整尺寸/材质/装饰，但**不能改变组件的"身份"与"识别性"**。

---

## 🎯 Scaling Rules（缩放规则）

### 核心概念

**缩放 ≠ `scale(x, y, z)`**，而是：

- **REPEAT**：重复中段（例如门拉高 = 中段重复）
- **TRIM**：裁剪中段（例如栏杆缩短 = 减少中段）
- **FIXED**：固定不变（例如门的宽度/深度）

### JSON 结构

```json
{
  "scaling": {
    "mode": "SEGMENTED",
    "axes": {
      "X": { "type": "FIXED" },
      "Y": { 
        "type": "REPEAT", 
        "min": 5, 
        "max": 9, 
        "segment": "MID_Y" 
      },
      "Z": { "type": "FIXED" }
    }
  }
}
```

### 字段说明

#### `mode`（缩放模式）

- `"SEGMENTED"`：分段缩放（v1 唯一支持模式）
- 未来可扩展：`"PROPORTIONAL"` / `"ADAPTIVE"`

#### `axes.<X|Y|Z>.type`（轴缩放类型）

| 类型 | 含义 | 适用场景 | 算法 |
|------|------|---------|------|
| `FIXED` | 固定不变 | 门的宽度/深度、柱子的直径 | 不处理 |
| `REPEAT` | 重复中段 | 门拉高、栏杆加长、墙体延伸 | `START + MID × N + END` |
| `TRIM` | 裁剪中段 | 栏杆缩短、窗户收窄 | `START + MID × (N-M) + END` |

#### `axes.<X|Y|Z>.min / max`（尺寸约束）

- **单位**：方块数
- **作用**：限制变体的最小/最大尺寸
- **示例**：门高度最小 5 格，最大 9 格

#### `axes.<X|Y|Z>.segment`（分段标签）

- **默认值**：`"MID_X"` / `"MID_Y"` / `"MID_Z"`（对应轴）
- **作用**：指定哪个分段用于重复/裁剪
- **与结构模板对应**：需在 structure 中标注 segment tag

---

## 📐 分段标签约定（Segment Tags）

### 标准分段

| Tag | 含义 | 出现次数 | 用途 |
|-----|------|---------|------|
| `SEG_START` | 起始段 | 1 次 | 固定头部（例如门的门楣） |
| `SEG_MID_X` | X 轴中段 | N 次 | 可重复段（例如栏杆的横梁） |
| `SEG_MID_Y` | Y 轴中段 | N 次 | 可重复段（例如门的中间竖条） |
| `SEG_MID_Z` | Z 轴中段 | N 次 | 可重复段（例如墙体的中间层） |
| `SEG_END` | 结束段 | 1 次 | 固定尾部（例如门的门槛） |

### 标注方式（v1）

**结构文件内标注**（v2，NBT 支持时）：

```
BlockEntityTag: {
  segment: "MID_Y",
  semantic: "FRAME"
}
```

**启发式推断**（v1，当前实现）：

如果结构中没有显式标注 segment tag，`StructureTemplate.getSegment()` 会基于坐标分布启发式推断：

- `SEG_START`：轴上最小坐标的切片
- `SEG_END`：轴上最大坐标的切片
- `SEG_MID_*`：中间所有切片（不包括 START 和 END）

---

## 🎨 Material Rules（材质规则）

### 核心概念

**组件内部方块不直接指定材质，而是打"语义标签"**，由 `StyleProfile` 在编译时决定具体方块。

### JSON 结构

```json
{
  "material": {
    "semantic_map": {
      "FRAME": "DOOR_FRAME",
      "PANEL": "DOOR_PANEL",
      "ACCENT": "WALL_ACCENT"
    }
  }
}
```

### 字段说明

#### `semantic_map`（语义映射表）

- **Key**：组件内部的语义标签（例如 `"FRAME"` / `"PANEL"` / `"ACCENT"`）
- **Value**：`StyleProfile` 的 palette key（例如 `"DOOR_FRAME"` / `"WALL"` / `"TRIM"`）

### 编译时解析流程

```
1. Voxel 有 semantic tag "FRAME"
2. 查询 semantic_map: "FRAME" → "DOOR_FRAME"
3. 查询 StyleProfile.palette: "DOOR_FRAME" → "minecraft:stone_bricks"
4. 替换 Voxel.blockState 为 "minecraft:stone_bricks"
```

### 标准 Palette Key（推荐）

| Palette Key | 含义 | 对应 BlockPalette 字段 |
|-------------|------|----------------------|
| `WALL` | 主墙体 | `BlockPalette.wall` |
| `ROOF` | 屋顶 | `BlockPalette.roof` |
| `FLOOR` | 地板 | `BlockPalette.floor` |
| `WINDOW` | 窗户 | `BlockPalette.window` |
| `FOUNDATION` | 地基 | `BlockPalette.foundation` |
| `TRIM` | 装饰边 | `BlockPalette.trim` |
| `PILLAR` | 柱子 | `BlockPalette.pillar` |
| `CAP` | 顶盖 | `BlockPalette.cap` |

**自定义 Key**（允许）：

- `DOOR_FRAME` / `DOOR_PANEL`（门特有）
- `BRACKET_BASE` / `BRACKET_ARM`（斗拱特有）
- 等等...

---

## 🎭 Details Rules（细节规则）

### 核心概念

**装饰等级**（ornament_level）决定组件的"豪华程度"，例如：

- `LOW`：简约（最少装饰）
- `MED`：中等
- `HIGH`：豪华（最多装饰）

### JSON 结构

```json
{
  "details": {
    "ornament_levels": ["LOW", "MED", "HIGH"]
  }
}
```

### 字段说明

#### `ornament_levels`（装饰等级列表）

- **类型**：字符串数组
- **作用**：声明该组件支持哪些装饰等级
- **v1 状态**：字段已预留，编译器尚未实现处理逻辑（v2 补齐）

### 未来实现方向（v2）

```
ornament_level="HIGH" →
  - 添加额外装饰方块（例如雕花/灯笼）
  - 选择更复杂的变体结构
  - 增加细节层（例如窗格/栏杆细节）
```

---

## 📋 完整示例：哥特门

```json
{
  "schema": "formacraft.component.prototype.v1",
  "id": "gothic_door",
  "name": "Gothic Door",
  "category": "DOOR",
  "tags": ["Gothic", "Stone", "Medieval"],

  "structure": {
    "format": "nbt",
    "file": "structure.nbt",
    "bounds": { "w": 3, "h": 5, "d": 1 },
    "anchor": { "x": 1, "y": 0, "z": 0 },
    "default_facing": "SOUTH"
  },

  "placement": {
    "attachment": "WALL_OPENING",
    "spatial_context": "ANY",
    "facing_policy": "DERIVED_FROM_HOST",
    "has_interior_exterior": true,
    "constraints": {
      "requires_attachment": true,
      "min_attachments": 1,
      "max_attachments": 1,
      "respect_protected_zones": true
    }
  },

  "variant_rules": {
    "scaling": {
      "mode": "SEGMENTED",
      "axes": {
        "X": { 
          "type": "FIXED",
          "min": 3,
          "max": 3
        },
        "Y": { 
          "type": "REPEAT", 
          "min": 5, 
          "max": 9, 
          "segment": "MID_Y" 
        },
        "Z": { 
          "type": "FIXED",
          "min": 1,
          "max": 1
        }
      }
    },
    "material": {
      "semantic_map": {
        "FRAME": "DOOR_FRAME",
        "PANEL": "DOOR_PANEL",
        "ACCENT": "WALL_ACCENT",
        "HINGE": "TRIM"
      }
    },
    "details": {
      "ornament_levels": ["LOW", "MED", "HIGH"]
    }
  }
}
```

### 变体生成示例

**变体参数**（AI 输出）：

```json
{
  "variant_params": {
    "scale": { "x": 3, "y": 7, "z": 1 },
    "mirror": "NONE",
    "material_set": "dark_stone",
    "ornament_level": "MED"
  }
}
```

**编译结果**：

- 门高度：5 → 7（中段重复 2 次）
- 材质：`FRAME → DOOR_FRAME → minecraft:polished_blackstone`（来自 `dark_stone` StyleProfile）
- 装饰等级：`MED`（v2 启用）

---

## 🔒 约束规则校验

### 编译期校验（自动）

- `scale.y < min` → 自动钳制到 `min`
- `scale.y > max` → 自动钳制到 `max`
- `type="FIXED"` → 忽略 `scale` 参数

### 运行期错误处理

- 结构文件缺失 → 返回空 `BlockPatch` 列表
- `semantic_map` 缺失 key → 保持原方块不变
- `StyleProfile` 为 `null` → 跳过材质替换

---

## 📚 相关文档

- [Component System Architecture](./COMPONENT_SYSTEM_ARCHITECTURE.md)
- [Semantic Material Mapping](./SEMANTIC_MATERIAL_MAPPING.md)
- [StructureTemplate Format Spec](./STRUCTURE_TEMPLATE_FORMAT.md)

---

**版本**：v1.0  
**最后更新**：2026-01-13
