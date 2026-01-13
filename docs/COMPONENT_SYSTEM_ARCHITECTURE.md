# Component System 架构文档 v1

## 📌 核心理念：建筑语义中间层

Formacraft Component System 不是"几何体库"，而是**建筑语义资产系统（Architectural Semantic Asset System）**。

### 设计目标

1. **AI 可理解**：AI 只需说"哥特门"，不需要描述 327 个方块位置
2. **玩家可复用**：同一组件可在多个世界/存档间共享
3. **非破坏性缩放**：门可以"拉高"但不会"变形"
4. **智能材质适配**：同一原型在不同风格（城堡/地牢/遗迹）自动换材质

---

## 🏗️ 四层架构（严格分离）

```
┌─────────────────────────────────────────────┐
│ Component Prototype (原型)                   │
│ - 形状骨架 (Shape Skeleton)                  │
│ - 放置规格 (Placement Spec)                  │
│ - 语义标签 (Semantic Tags)                   │
│ - 变体规则 (Variant Rules)                   │
└─────────────────────────────────────────────┘
                    ↓
┌─────────────────────────────────────────────┐
│ Component Variant (变体)                     │
│ - 尺寸参数 (scale: {x, y, z})               │
│ - 镜像模式 (mirror: NONE/X/Z)                │
│ - 材质集 (material_set)                      │
│ - 装饰等级 (ornament_level)                  │
└─────────────────────────────────────────────┘
                    ↓
┌─────────────────────────────────────────────┐
│ Component Instance (实例)                    │
│ - 世界坐标 (dimension + anchor + facing)     │
│ - 关联关系 (belongs_to_building / socket_id) │
│ - 落地追踪 (patch_hash / block_count)        │
└─────────────────────────────────────────────┘
                    ↓
┌─────────────────────────────────────────────┐
│ BlockPatch (方块增量)                         │
│ - 实际写入世界的方块列表                       │
└─────────────────────────────────────────────┘
```

### 层次职责边界

| 层次 | 职责 | 存储位置 | 生命周期 |
|------|------|---------|---------|
| **Prototype** | 定义"身份"与"规则" | `<gameDir>/formacraft/components/<category>/<id>/` | 跨世界/存档 |
| **Variant** | 参数化变体（AI/玩家生成） | 可选持久化（`variants.json` 或临时）| 编译期临时/可持久化 |
| **Instance** | 世界中的具体实例 | `<worldDir>/formacraft/instances/<uuid>.json` | 与存档绑定 |
| **Patch** | 落地方块增量 | 不持久化（运行时计算） | 执行期临时 |

---

## 📂 存储布局规范

### Prototype 库（跨存档共享）

**推荐路径**（v1 标准）：

```
<gameDir>/formacraft/components/
├─ catalog.json                          # 原型索引
├─ doors/
│  ├─ gothic_door/
│  │  ├─ prototype.json                  # 原型定义（必需）
│  │  ├─ structure.nbt                   # 结构数据（v2，推荐）
│  │  ├─ component.json                  # 结构数据（v1 兼容）
│  │  ├─ thumbnail.png                   # 缩略图（自动生成）
│  │  ├─ variants.json                   # 预置变体集合（可选）
│  │  └─ variants/                       # 独立变体文件（可选）
│  │     ├─ gothic_door_tall.json
│  │     └─ gothic_door_wide.json
│  └─ wooden_door/
│     └─ ...
├─ windows/
│  └─ ...
├─ columns/
└─ brackets/
```

**兼容路径**（旧版，只读）：

```
<config>/formacraft/components/prototypes/<category>/<id>/prototype.json
```

### Instance 存储（与存档绑定）

```
<worldDir>/formacraft/instances/
├─ <uuid-1>.json
├─ <uuid-2>.json
└─ ...
```

### 可通过 JVM 参数覆盖

```bash
-Dformacraft.prototypeLibraryDir=/custom/path
```

---

## 🎯 Prototype 定义规范（JSON Schema v1）

### 完整示例：`prototype.json`

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
        "X": { "type": "FIXED" },
        "Y": { 
          "type": "REPEAT", 
          "min": 5, 
          "max": 9, 
          "segment": "MID_Y" 
        },
        "Z": { "type": "FIXED" }
      }
    },
    "material": {
      "semantic_map": {
        "FRAME": "DOOR_FRAME",
        "PANEL": "DOOR_PANEL",
        "ACCENT": "WALL_ACCENT"
      }
    },
    "details": {
      "ornament_levels": ["LOW", "MED", "HIGH"]
    }
  }
}
```

### 关键字段说明

#### `variant_rules.scaling`（分段缩放规则）

**核心原则**：缩放 ≠ `scale(x,y,z)`，而是 `repeat/trim/extend`

```json
{
  "mode": "SEGMENTED",
  "axes": {
    "Y": {
      "type": "REPEAT",     // 可重复中段
      "min": 5,              // 最小高度
      "max": 9,              // 最大高度
      "segment": "MID_Y"     // 重复哪个分段
    }
  }
}
```

**支持的缩放类型**：

- `FIXED`：固定不变（例如门的宽度）
- `REPEAT`：重复中段（例如门拉高 = 中段重复）
- `TRIM`：裁剪中段（例如栏杆缩短 = 减少中段）

**分段标签约定**：

- `SEG_START`：起始段（固定，只出现 1 次）
- `SEG_MID_X / SEG_MID_Y / SEG_MID_Z`：中段（可重复）
- `SEG_END`：结束段（固定，只出现 1 次）

#### `variant_rules.material.semantic_map`（语义材质映射）

**核心原则**：组件内部方块不直接指定材质，而是打"语义标签"

```json
{
  "semantic_map": {
    "FRAME": "DOOR_FRAME",    // 门框 → 由 StyleProfile 决定具体方块
    "PANEL": "DOOR_PANEL",    // 门板 → 同上
    "ACCENT": "WALL_ACCENT"   // 装饰 → 同上
  }
}
```

**编译时解析流程**：

```
FRAME (semantic tag) 
  → DOOR_FRAME (palette key)
  → StyleProfile.palette.pick("DOOR_FRAME")
  → minecraft:stone_bricks (实际方块)
```

**效果**：

- 同一个"哥特门"原型
- 在"中世纪城堡" → 石砖门框
- 在"暗黑地牢" → 黑石门框
- 在"精灵遗迹" → 苔石门框

---

## 🔄 Variant 编译流水线

```
Prototype + Variant + StyleProfile → BlockPatch[]
```

### 编译步骤（自动执行）

1. **加载结构模板** (`StructureLoader`)
   - 从 `structure.nbt` 或 `component.json` 加载体素
   - 解析 segment tag 和 semantic tag

2. **分段缩放** (`SegmentScaler`)
   - 按 `variant_rules.scaling` 规则重建体素网格
   - 例如：门高度 5 → 7 = START(1) + MID(5) + END(1)

3. **语义材质替换** (`SemanticMaterialApplier`)
   - 遍历体素的 semantic tag
   - 查询 `semantic_map` 获取 palette key
   - 从 `StyleProfile.palette` 获取实际方块

4. **朝向/镜像变换** (`TransformApplier`)
   - 应用坐标旋转/镜像
   - 同时修正 blockState 中的 facing 属性

5. **输出 BlockPatch** (`PatchEmitter`)
   - 转换为相对坐标增量
   - 过滤空气方块

### 调用接口

```java
// 基础调用（适用于 AI/Tool/PlayerExpander）
List<BlockPatch> patches = ComponentModelApi.compileToPatch(
    prototype,           // 原型
    variant,             // 变体（可为 null，会自动创建默认变体）
    Direction.SOUTH,     // 目标朝向
    styleProfile         // 风格配置（可为 null，跳过材质替换）
);

// 带基础坐标（自动叠加）
List<BlockPatch> patches = ComponentModelApi.compileToPatch(
    prototype, variant, 
    baseX, baseY, baseZ, // 世界坐标
    Direction.SOUTH, 
    styleProfile
);
```

---

## 🤖 AI 使用模式

### ❌ 错误模式（不要这样做）

```
AI 直接生成"门的变体 JSON"
AI 直接生成"方块坐标列表"
```

### ✅ 正确模式（推荐）

```
AI 输出变体参数 → 本地 Variant Generator 编译 → BlockPatch
```

### 示例：AI 输出

```json
{
  "component": "gothic_door",
  "variant_params": {
    "scale": { "x": 3, "y": 7, "z": 1 },
    "mirror": "NONE",
    "material_set": "主建筑材质",
    "ornament_level": "MED"
  },
  "placement": {
    "anchor": [100, 64, 90],
    "facing": "WEST"
  }
}
```

### 本地编译（自动）

```java
ComponentPrototype proto = ComponentModelApi.loadPrototype("gothic_door");
ComponentVariant variant = ComponentModelApi.makeVariant(proto, variantParams);
List<BlockPatch> patches = ComponentModelApi.compileToPatch(
    proto, variant, 
    100, 64, 90,        // 来自 AI 的 placement.anchor
    Direction.WEST,     // 来自 AI 的 placement.facing
    currentStyleProfile // 当前建筑的风格配置
);
```

**关键点**：

- AI 只决策"参数"（what）
- 本地编译器决定"实现"（how）
- AI 不需要理解"分段重复算法"或"材质映射规则"

---

## 🎨 缩略图生成

### 当前实现（v1）

**生成器**：`ComponentThumbnailGenerator`

**策略**：

- 俯视高度图 + 颜色 hash
- 不依赖客户端纹理系统
- 固定光照/视角
- 自动保存为 `thumbnail.png`

**触发时机**：

- 保存组件时自动生成（`ComponentStorage.saveComponent`）
- 尺寸：128x128px（可配置）

### 未来改进（v2）

- 离屏渲染（Offscreen World）
- 真实光照/材质
- 可选视角（俯视/透视/正交）

---

## 📊 架构审查总结

### ✅ 已完成

1. **存储布局** 符合 `.minecraft/formacraft/components/<category>/<id>/` 规范
2. **三层分离** Prototype/Variant/Instance 职责清晰
3. **非等比缩放** 基于 `repeat/trim`，不是 `scale`
4. **缩略图** 自动生成（v1 高度图模式）
5. **AI 接口** 参数化变体，本地编译器执行

### 🔧 待补齐（v2）

1. **NBT 结构支持**（当前仅支持 `component.json` v1）
2. **显式 segment tag 标注**（当前依赖启发式推断）
3. **装饰等级处理器**（`ornament_level` 字段已预留）
4. **材质变体池随机**（`wallVariants/roofVariants`）
5. **离屏缩略图渲染**（当前为简易高度图）

### 🎯 关键原则（必须遵守）

1. **组件 ≠ 几何体**：组件是"语义资产"，不是"方块集合"
2. **缩放 ≠ Scale**：用 repeat/trim 保持识别性
3. **AI ≠ Generator**：AI 决策参数，Generator 执行编译
4. **Prototype ≠ Instance**：原型跨世界共享，实例与存档绑定

---

## 📚 相关文档

- [Component Placement Spec](./COMPONENT_PLACEMENT.md)
- [Variant Rules Specification](./VARIANT_RULES_SPEC.md)
- [Semantic Material Mapping](./SEMANTIC_MATERIAL_MAPPING.md)
- [AI Integration Guide](./AI_COMPONENT_USAGE.md)

---

**版本**：v1.0  
**最后更新**：2026-01-13  
**维护者**：Formacraft Team
