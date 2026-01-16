# PlanSkeleton Schema v1 - 2D 平面骨架架构

## 🎯 核心定义

**PlanSkeleton 不是平面几何图形，而是：一个带有拓扑、轴线、边界语义的 2D 骨架**

它的职责只有三件事：
1. 把 zones + adjacency 转成可落地的平面关系
2. 提供 "边 / 面 / 内部 / 外部" 等语义
3. 为后续 Skeleton / SocketProvider 提供输入

## 📋 完整 Schema

```json
{
  "schema": "formacraft.plan_skeleton.v1",
  "outline": {
    "source": "generated",
    "shape": "polygon"
  },
  "zones": [
    {
      "id": "core",
      "boundary": "external",
      "access": "public",
      "connected_to": ["wing_a", "wing_b", "service"]
    },
    {
      "id": "wing_a",
      "boundary": "external",
      "access": "semi_private",
      "connected_to": ["core"]
    }
  ],
  "edges": [
    {
      "id": "edge_1",
      "type": "external_wall",
      "zones": ["core"],
      "exterior": true
    },
    {
      "id": "edge_2",
      "type": "shared_wall",
      "zones": ["core", "wing_a"]
    }
  ],
  "courtyards": [
    {
      "id": "court_1",
      "adjacent_zones": ["core", "wing_b"]
    }
  ],
  "axes": [
    {
      "id": "main_axis",
      "role": "primary",
      "zones": ["wing_a", "core", "wing_b"]
    }
  ]
}
```

## 🔍 字段详解

### 1️⃣ outline - 平面"从哪来"

**这个字段现在看似没用，但极其重要。**

它解决的是未来这件事：
- 这是 AI 生成的？
- 用户画的？
- 从已有建筑扫描的？

**推荐枚举（v1）：**

```json
{
  "source": "generated | user_drawn | imported",
  "shape": "polygon | rectilinear | freeform"
}
```

**source 枚举：**
- `generated` - AI 完全生成
- `user_drawn` - 用户轮廓工具
- `imported` - 未来：从世界扫描

**shape 枚举：**
- `polygon` - 多边形
- `rectilinear` - 直线型（矩形组合）
- `freeform` - 自由曲线

**👉 后续 "用户平面绘制工具"会直接写这里。**

### 2️⃣ zones - 与 PlanProgram 对应，但角色不同

**区别：**

| 层级           | zones 的含义           |
| ------------ | ------------------- |
| PlanProgram  | "我要哪些空间？"           |
| PlanSkeleton | "这些空间在平面中处于什么位置关系？" |

**新增字段说明：**

| 字段             | 作用           |
| -------------- | ------------ |
| `boundary`     | 决定是否生成外墙     |
| `access`       | 为门、动线、窗提供语义  |
| `connected_to` | 变成**真实的连接边** |

**boundary 枚举：**
- `external` - 外部边界（有外墙）
- `internal` - 内部区域（被包裹）
- `mixed` - 混合（部分外部）

**access 枚举：**
- `public` - 公共访问
- `semi_private` - 半私密
- `private` - 私密

**👉 这一步开始出现"墙"的概念，但还没有几何。**

### 3️⃣ edges - SocketProvider 的黄金输入

**这一步非常关键，SocketProvider v1 几乎可以直接吃这个结构。**

**Edge 的职责：**
- 定义哪里是：外墙、内墙、共墙
- 为后续生成 WALL_SURFACE、EDGE_OUTER、WALL_OPENING 提供语义来源

**type 枚举：**
- `external_wall` - 外墙
- `shared_wall` - 共墙（两个 zone 共享）
- `courtyard_wall` - 庭院墙（内向立面）
- `boundary_edge` - 边界边（非墙，如栏杆）

**示例：**
```json
{
  "id": "edge_1",
  "type": "external_wall",
  "zones": ["core"],
  "exterior": true
}
```

### 4️⃣ courtyards - 非矩形平面的核心来源

**为什么单独列出来？**
- 庭院 = 体量减法
- 它会生成：内向立面、特殊采光、不同 Socket 密度

**👉 这是 回字形 / 组合平面 / 中式院落 的根源。**

**示例：**
```json
{
  "id": "court_1",
  "adjacent_zones": ["core", "wing_b"]
}
```

### 5️⃣ axes - 平面"像建筑师画的"的关键

**轴线在系统中的作用：**
- 影响对称性
- 影响主入口
- 影响门窗密度
- 但**不强制几何对称**

**role 枚举：**
- `primary` - 主轴线
- `secondary` - 次轴线
- `symmetry` - 对称轴

**👉 这是非常"建筑学"的一层抽象。**

## 🔄 在整个流水线中的位置

```
LLM
  ↓
PlanProgram.json        ← AI 擅长（功能关系）
  ↓
PlanSkeleton.json       ← 系统 + 半自动（几何语义）
  ↓
Skeleton (3D)           ← 3D 骨架
  ↓
SocketProvider          ← Socket 生成
  ↓
Component / Assembly    ← 构件装配
```

**PlanSkeleton 是人类 & AI 都可以理解和修改的一层。**

## 🧠 PlanSkeleton → Skeleton（3D）的最小映射逻辑

### 规则 1：每个 edge → 一段墙 Skeleton

| edge.type      | 生成           |
| -------------- | ------------ |
| external_wall  | 外墙 skeleton  |
| shared_wall    | 内墙 skeleton  |
| courtyard_wall | 内向墙 skeleton |

### 规则 2：zone.boundary 决定是否封闭

- `external` → 必有外墙
- `internal` → 可被包裹

### 规则 3：axis 提供"拉伸与对齐偏好"

- 主轴上的 zone：更规则、更对称
- 非轴线 zone：可偏移、可错动

**👉 这一步就是你之前说的**"消解体量但不乱"**。**

## 🛠️ 用户平面工具如何接入

**你之前提到："在 minecraft 中绘制平面，其实就是选中一些方块"**

对应到这里，非常自然：

1. **用户画轮廓** → `outline.source = user_drawn`
2. **系统生成 edges**（沿 outline）
3. **AI 只负责**：zones、adjacency、circulation

**几何归用户，组织归 AI**

这是 Formacraft 非常独特的一点。

## ✅ 你现在已经拥有的能力

到这一步，你的系统已经具备：

- ❌ 不是"AI 画平面"
- ✅ 是 AI 设计平面逻辑
- ✅ 用户可以覆盖任何几何
- ✅ Socket / Component 自动装配不需要改一行核心逻辑

**这已经是建筑设计软件级别的抽象，而不是模组常见水平。**

## 📊 与现有系统的关系

### 与 PlanProgram 的关系

PlanProgram 描述"功能关系"，PlanSkeleton 描述"几何语义"。

转换流程：
```
PlanProgram.zones + adjacency
  ↓
PlanSkeleton.zones + edges
  ↓
Skeleton (3D)
```

### 与 SocketProvider 的关系

PlanSkeleton.edges 可以直接映射到 SocketProvider 的输入：

- `external_wall` → 生成 `WALL_SURFACE` sockets
- `shared_wall` → 生成 `WALL_OPENING` sockets
- `courtyard_wall` → 生成内向 `WALL_SURFACE` sockets

### 与用户工具的关系

- **SelectionTool** → 可作为 `outline` 的来源
- **OutlineTool** → 可直接作为 `outline` 输入
- **PathTool** → 可作为 `axes` 的输入

## ✅ 验证规则

### 必填字段
- `zones`（至少一个）
- 每个 zone 的 `id`

### 可选但建议有
- `schema`（版本控制）
- `outline`（来源标识）
- `edges`（墙定义）
- `axes`（轴线定义）

### 自动校验
- Zone ID 唯一性
- Edge 引用的 zone ID 必须存在
- Courtyard 引用的 zone ID 必须存在
- Axis 引用的 zone ID 必须存在
- Zone `connected_to` 引用的 zone ID 必须存在

## 🎯 下一步

1. **PlanProgram → PlanSkeleton 转换器**：将功能关系转换为几何语义
2. **PlanSkeleton → Skeleton（3D）转换器**：将 2D 骨架转换为 3D 骨架
3. **用户工具集成**：OutlineTool 可直接生成 PlanSkeleton
4. **SocketProvider 扩展**：支持从 PlanSkeleton.edges 生成 sockets
