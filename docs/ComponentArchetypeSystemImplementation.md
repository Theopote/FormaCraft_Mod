# Component Archetype System（构件原型系统）实现总结

## 📋 实现内容

根据建议，已成功实现 Component Archetype System（构件原型系统），这是 Formacraft 的"建筑基因 DNA 层"。

## 🎯 核心思想

**一句话总结**：
> 构件不是"模型"，而是"可变的建筑器官（Architectural Organs）"

**三层抽象**：
1. **Component Prototype（构件原型）** - 由玩家定义，稳定、长期可复用
2. **Component Variant（构件变体）** - 由 AI/系统生成，决定尺寸、材质、重复等
3. **Component Instance（构件实例）** - 最终落到世界里的东西（Patch / Memory / Undo 栈）

**角色分工**：
- 👤 **玩家**：定义构件原型、语义、使用规则
- 🤖 **AI**：选择构件、生成变体、组合结构、调整比例
- ⚙ **系统**：验证合法性、防止乱用、转换为 Patch、支持 Undo / Memory

## ✅ 已实现的组件

### 1. 核心枚举

**AttachmentType**（附着类型）：
- `SURFACE` - 表面附着（门、窗、阳台）
- `EDGE` - 边缘附着（栏杆、女儿墙）
- `POINT` - 点附着（柱、斗拱）
- `VOLUME` - 体积附着（房间模块、体块）

**ContextType**（上下文类型）：
- `WALL`, `ROOF`, `FLOOR`, `EDGE`, `CORNER`, `FREE`

**SurfaceSide**（表面侧）：
- `INTERIOR`, `EXTERIOR`, `BOTH`

**RepeatAxis**（重复轴）：
- `X`, `Y`, `Z`

**GeometryArchetype**（几何原型）：
- `FLAT_PANEL`, `ARCH`, `COLUMN`, `FRAME`, `ORNAMENT`, `LINEAR`, `VOLUME`

### 2. AttachmentSpec（附着规格）

**核心功能**：
- 定义构件如何附着到建筑结构上
- 支持上下文类型、表面侧、承重要求

**预设方法**：
- `forDoor()` - 门的附着规则
- `forWindow()` - 窗的附着规则
- `forColumn()` - 柱的附着规则
- `forRailing()` - 栏杆的附着规则
- `forBalcony()` - 阳台的附着规则

**示例映射**：
| 构件类型 | AttachmentSpec |
|---------|----------------|
| 门/窗 | SURFACE + WALL + INTERIOR/EXTERIOR |
| 阳台 | SURFACE + EXTERIOR + requireSupport |
| 柱/斗拱 | POINT + FREE |
| 栏杆 | EDGE |
| 飞檐/烟囱 | SURFACE + ROOF |

### 3. VariationSpec（变形规格）

**核心功能**：
- 定义"AI 可以改哪里"的白名单
- 支持轴向缩放、镜像、旋转、材质替换、重复拼接

**关键组件**：
- `AxisScaleRule` - 轴向缩放规则（locked / min / max）
- `RepeatRule` - 重复规则（enabled / axis / minSegments / maxSegments）

**预设方法**：
- `forDoor()` - 门的变形规则（宽度/高度可变，厚度锁定）
- `forWindow()` - 窗的变形规则
- `forRailing()` - 栏杆的变形规则（X 轴可拉伸，允许重复）
- `forColumn()` - 柱的变形规则（高度可变，允许旋转）

**示例：栏杆构件**：
```java
"variation": {
  "scaleX": { "locked": false, "min": 1.0, "max": 10.0 },
  "scaleY": { "locked": true },
  "scaleZ": { "locked": true },
  "allowMirror": true,
  "repeatRule": {
    "enabled": true,
    "axis": "X",
    "minSegments": 1,
    "maxSegments": 20
  }
}
```

### 4. SocketSpec（对接规格）

**核心功能**：
- 定义构件的"接口"，用于构件拼装
- AI 可以根据 socket 自动推断"这里该用什么构件"

**预设方法**：
- `forDoor()` - 门的 socket（wall.opening）
- `forWindow()` - 窗的 socket（wall.opening）
- `forRailing()` - 栏杆的 socket（edge.linear）

### 5. GeometryHint（几何提示）

**核心功能**：
- 专门给 AI / Generator 的形态提示
- 不是强约束，而是"生成风格提示"

**预设方法**：
- `forDoor()` - 门的几何提示（FRAME, "vertical door opening", symmetryPreferred）
- `forWindow()` - 窗的几何提示
- `forColumn()` - 柱的几何提示
- `forRailing()` - 栏杆的几何提示

### 6. ValidationSpec（验证规格）

**核心功能**：
- 防止 AI 犯蠢的约束规则
- 禁止内部放置、禁止悬空、禁止重叠、不可共存的构件

### 7. ComponentArchetype（总定义）

**核心功能**：
- 一个"可被生成系统调用的、受规则约束的建筑器官模板"
- 回答 5 个问题：
  1. 它是什么？（语义）
  2. 它能贴在哪？（上下文 / 附着）
  3. 它能怎么变？（变体规则）
  4. 它和谁对接？（Socket）
  5. 它不能做什么？（约束）

**关键字段**：
- `id` - 唯一标识符（door.basic, window.gothic）
- `displayName` - 显示名称
- `category` - 分类
- `semanticTags` - 语义标签
- `attachment` - 附着规则
- `variation` - 变形规则
- `socket` - 对接规则
- `geometryHint` - 几何提示
- `validation` - 验证规则

**预设方法**：
- `createBasicDoor()` - 创建基础门原型
- `createBasicWindow()` - 创建基础窗原型
- `createRailing()` - 创建栏杆原型

### 8. ComponentArchetypeStorage（存储系统）

**核心功能**：
- 管理 Archetype 的加载、保存和注册
- 支持文件存储和内存注册表

**存储结构**：
```
.minecraft/formacraft/archetypes/
    doors/
    windows/
    columns/
    roofs/
    decorations/
```

**关键方法**：
- `loadArchetype(String id)` - 从文件加载
- `saveArchetype(ComponentArchetype)` - 保存到文件
- `register(ComponentArchetype)` - 注册到内存
- `get(String id)` - 获取已注册的 Archetype
- `getByCategory(String category)` - 按分类获取
- `searchByTag(String tag)` - 按语义标签搜索

### 9. DefaultArchetypes（默认原型）

**核心功能**：
- 初始化系统默认的构件原型
- 在系统启动时调用

**当前注册的原型**：
- 基础门（door.basic）
- 基础窗（window.basic）
- 基础栏杆（railing.basic）

## 📊 JSON 示例

### 门构件原型

```json
{
  "schema": "formacraft.archetype.v1",
  "id": "door.basic",
  "displayName": "基础门",
  "category": "OPENING",
  "semanticTags": ["door", "entry", "opening"],

  "attachment": {
    "type": "SURFACE",
    "allowedContexts": ["WALL"],
    "allowedSides": ["BOTH"],
    "requireSupport": true
  },

  "variation": {
    "scaleX": { "locked": false, "min": 0.8, "max": 1.5 },
    "scaleY": { "locked": false, "min": 1.8, "max": 3.0 },
    "scaleZ": { "locked": true },
    "allowMirror": true,
    "allowRotation": false,
    "allowMaterialSwap": true,
    "repeatRule": { "enabled": false }
  },

  "socket": {
    "socketType": "wall.opening",
    "compatibleWith": ["wall.opening"],
    "autoAlign": true
  },

  "geometryHint": {
    "archetype": "FRAME",
    "visualIdentity": "vertical door opening",
    "symmetryPreferred": true
  },

  "validation": {
    "forbidInteriorPlacement": false,
    "forbidFloating": true,
    "forbidOverlap": true,
    "forbiddenWith": []
  }
}
```

## 🔄 系统集成

### 与现有系统的关系

| 系统 | 关系 |
|------|------|
| ComponentDefinition | Archetype 是 ComponentDefinition 的"元定义" |
| ComponentVariant | Variant 基于 Archetype 的 VariationSpec 生成 |
| ComponentPlanCompiler | 使用 Archetype 验证和约束编译过程 |
| PromptAssembler | 使用 Archetype 的 GeometryHint 生成 AI 提示 |
| AI 系统 | 使用 Archetype 选择构件、生成变体 |

### 数据流

```
[ 玩家定义 ComponentArchetype ]
        ↓
[ 保存到文件 / 注册到内存 ]
        ↓
[ AI 选择 Archetype ]
        ↓
[ 根据 VariationSpec 生成 Variant ]
        ↓
[ 根据 AttachmentSpec 确定放置位置 ]
        ↓
[ ComponentPlanCompiler 编译为 Patch ]
        ↓
[ 应用 ValidationSpec 验证 ]
        ↓
[ 生成到世界 ]
```

## ✅ 完成度

| 组件 | 状态 | 说明 |
|------|------|------|
| 核心枚举 | ✅ 完成 | AttachmentType, ContextType, SurfaceSide, RepeatAxis, GeometryArchetype |
| AttachmentSpec | ✅ 完成 | 附着规则，包含预设方法 |
| VariationSpec | ✅ 完成 | 变形规则，包含 AxisScaleRule 和 RepeatRule |
| SocketSpec | ✅ 完成 | 对接规则 |
| GeometryHint | ✅ 完成 | 几何提示 |
| ValidationSpec | ✅ 完成 | 验证规则 |
| ComponentArchetype | ✅ 完成 | 总定义，包含预设方法 |
| ComponentArchetypeStorage | ✅ 完成 | 存储和注册系统 |
| DefaultArchetypes | ✅ 完成 | 默认原型初始化 |

## 🎉 总结

已成功实现 Component Archetype System（构件原型系统）：

- ✅ **核心思想** - 构件是"可变的建筑器官"，不是静态模型
- ✅ **三层抽象** - Prototype → Variant → Instance
- ✅ **完整数据结构** - 附着、变形、对接、几何、验证规则
- ✅ **存储系统** - 文件存储和内存注册表
- ✅ **预设原型** - 门、窗、栏杆等基础原型

**这一层完成后，系统立刻获得的能力**：
- ✅ AI 不再直接操纵方块
- ✅ 构件"像器官一样"被复用
- ✅ 同一门 → 中式 / 欧式 / 现代都能成立
- ✅ 构件可被自动裁剪 / 拉伸 / 重复
- ✅ 后续 Socket / Variant / Patch / Memory 全部自然衔接

**这是 Formacraft 的"建筑基因 DNA 层"，之后 AI、工具、生成器、Patch、Memory 全都会围绕它转。**

---

**实现时间**: 2026-01-14  
**版本**: v1.0  
**状态**: ✅ 核心功能完成，可用于 AI 系统集成
