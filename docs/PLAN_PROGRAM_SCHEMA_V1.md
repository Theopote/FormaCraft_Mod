# PlanProgram Schema v1 - 平面程序架构

## 🎯 核心思想

**PlanProgram = 功能拓扑 + 体量拆解策略 + 约束，而不是形状**

这个 schema 的目标：
- ✅ LLM 易生成（低歧义、低数学、低坐标）
- ✅ 不直接包含几何细节
- ✅ 支持复杂平面（非矩形 / 非单体）
- ✅ 可被系统逐步"几何化"
- ✅ 可被用户工具覆盖 / 修正

## 📋 完整 Schema

```json
{
  "schema": "formacraft.plan_program.v1",
  "intent": {
    "building_type": "residential",
    "style_hint": "medieval",
    "scale": "medium"
  },
  "zones": [
    {
      "id": "core",
      "role": "main_hall",
      "importance": "primary",
      "area_ratio": 0.4
    },
    {
      "id": "wing_a",
      "role": "living",
      "importance": "secondary",
      "area_ratio": 0.2
    },
    {
      "id": "wing_b",
      "role": "living",
      "importance": "secondary",
      "area_ratio": 0.2
    },
    {
      "id": "service",
      "role": "service",
      "importance": "support",
      "area_ratio": 0.2
    }
  ],
  "adjacency": [
    ["core", "wing_a"],
    ["core", "wing_b"],
    ["core", "service"]
  ],
  "massing": {
    "strategy": "decompose",
    "rules": {
      "max_zone_area": 120,
      "preferred_operations": [
        "add_wings",
        "subtract_courtyard"
      ]
    }
  },
  "circulation": {
    "primary_axis": "core",
    "connection_style": "direct"
  },
  "constraints": {
    "terrain": {
      "respect_slope": true,
      "avoid": ["water", "cliff"]
    },
    "geometry": {
      "avoid_shapes": ["perfect_circle"],
      "prefer_symmetry": "axial"
    }
  }
}
```

## 🔍 字段详解

### 1️⃣ intent - AI 的"建筑动机层"

**作用：**
- 给 LLM 一个高层语境
- 给系统一个默认行为集合

**为什么不写更多？**
- 风格细节 → 交给 Skeleton / Component
- 这里只影响平面逻辑倾向

**示例影响：**
- `residential` → 更可能分翼
- `temple` → 更可能轴线对称
- `market` → 更可能多入口 / 多块

```json
{
  "building_type": "residential | temple | market | ...",
  "style_hint": "medieval | modern | chinese | ...",
  "scale": "small | medium | large"
}
```

### 2️⃣ zones - 平面生成的核心（最重要）

**这是整个 schema 最重要的部分。**

**zones 解决了什么？**
- 把"一个建筑"拆成多个功能块
- 平面复杂度 = zone 数量 + 关系
- 十字形 / L 形 / 锯齿形 → 都是 zone 组合的"几何结果"

**关键字段说明：**

| 字段           | 含义                                | 类型   |
| ------------ | --------------------------------- | ---- |
| `id`         | 稳定引用（给 adjacency / circulation 用） | 必填  |
| `role`       | 语义角色（AI 非常擅长）                     | 可选  |
| `importance` | 决定体量中心性                           | 可选  |
| `area_ratio` | 不给绝对值，避免 LLM 数学崩溃                 | 可选  |

**importance 枚举：**
- `primary` - 主要功能块（核心、主体）
- `secondary` - 次要功能块（翼楼、附属）
- `support` - 支撑功能块（服务、后勤）

### 3️⃣ adjacency - 平面不是形状，是关系

**这是你想要「复杂平面」的真正来源：**
- 十字形 = 一个 core + 四个 adjacency
- 回字形 = adjacency + courtyard（在 massing 里）
- 锯齿形 = adjacency + offset（在 massing 里）

**⚠️ 注意：**
这里是无方向、无距离、无角度的
→ **纯拓扑关系（LLM 的强项）**

```json
"adjacency": [
  ["core", "wing_a"],
  ["core", "wing_b"]
]
```

### 4️⃣ massing - 体量消解机制（关键点）

**这正是"面积大的时候来消解体量"的显式策略。**

**为什么一定要显式写出来？**
- 否则 AI 会默认：一个大盒子
- 而不是：多个相关盒子

**strategy 枚举：**
- `decompose` - 拆解（默认）
- `monolithic` - 单体（不拆解）
- `cluster` - 集群（独立体量）

**推荐的 operation 枚举（v1）：**
- `add_wings` - 添加翼楼
- `subtract_courtyard` - 减去庭院
- `offset_volumes` - 偏移体量
- `split_along_axis` - 沿轴线拆分
- `wrap_around_courtyard` - 环绕庭院

**这些都不是几何，而是建筑语言。**

### 5️⃣ circulation - 平面"像不像人设计的"关键因素

**为什么 MVP 也要有 circulation？**
- 平面如果没有"动线逻辑"，再复杂也会显得"随机"
- 即使 v1 只支持 `direct` / `ring` / `branch`，也已经足够让平面有秩序感

**connection_style 枚举：**
- `direct` - 直接连接（最简单）
- `ring` - 环形（环绕庭院）
- `branch` - 分支（树状）

### 6️⃣ constraints - 平面不是自由创作，是受限推导

**这是"根据地形、建筑风格……"的入口。**

**在这里：**
- AI 不画
- AI 决策
- 系统执行

**这也是未来支持：**
- 用户平面轮廓
- 法线限制
- 红线范围
的入口。

**geometry.symmetry 枚举：**
- `none` - 无对称
- `axial` - 轴线对称
- `radial` - 径向对称
- `bilateral` - 双轴对称

## 🧠 LLM 使用这个 Schema 的方式

**你以后给 LLM 的 prompt 可以是：**

> "请输出一个符合 formacraft.plan_program.v1 的 JSON，
> 不要包含任何坐标或尺寸，只描述空间组织与体量策略。"

这会极大降低胡编几何的概率。

## 🔄 工作流程

```
LLM 输出 PlanProgram
  ↓
PlanProgramParser.parseAndValidate()
  ↓
PlanProgram → PlanSkeleton（2D 拓扑骨架）
  ↓
Extrude / Offset / Decompose
  ↓
Skeleton（3D）
  ↓
Socket × Component
```

## 📊 与现有系统的关系

### 与 LlmPlan 的关系

PlanProgram 可以作为 LlmPlan 的一个可选字段：

```json
{
  "mode": "build",
  "anchor": { "x": 0, "y": 0, "z": 0 },
  "plan_program": { ... },  // 新增
  "layout": { ... },
  "components": [ ... ]
}
```

或者作为独立的中间层：

```
用户输入
  ↓
LLM 生成 PlanProgram
  ↓
系统生成 PlanSkeleton
  ↓
LLM 继续生成 LlmPlan（包含 Skeleton 信息）
```

### 与 Skeleton 的关系

PlanProgram → PlanSkeleton（2D）→ Skeleton（3D）

### 与用户工具的关系

- **SelectionTool** → 可作为 `constraints.geometry` 的输入
- **OutlineTool** → 可作为 `constraints.geometry` 的 hard boundary
- **PathTool** → 可作为 `circulation` 的输入

## ✅ 验证规则

### 必填字段
- `zones`（至少一个）
- 每个 zone 的 `id`

### 可选但建议有
- `schema`（版本控制）
- `intent`（帮助系统理解）
- `adjacency`（关系定义）
- `massing`（拆解策略）

### 自动校验
- Zone ID 唯一性
- Adjacency 引用的 zone ID 必须存在
- Circulation `primary_axis` 必须是有效的 zone ID
- `area_ratio` 总和应该接近 1.0（允许误差）

## 🎯 下一步

1. **集成到 PromptAssembler**：添加 PlanProgram 提示
2. **PlanSkeleton 生成器**：将 PlanProgram 转换为 2D 骨架
3. **用户工具支持**：允许用户绘制/选择平面轮廓
4. **向后兼容**：PlanProgram 可以为空，系统回退到现有逻辑
