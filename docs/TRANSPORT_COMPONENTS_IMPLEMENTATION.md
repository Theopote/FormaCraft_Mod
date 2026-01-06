# 交通器官（Transport Components）实现总结

## 实现状态

### ✅ 已完全实现

建议中的三个交通器官已经全部实现：

1. ✅ **GATE（门/入口）** - GateAssembler
2. ✅ **STAIR（楼梯/高差连接）** - StairAssembler
3. ✅ **WALKWAY（道路/连廊/城墙步道）** - WalkwayAssembler

## 核心组件

### 1. GATE 装配器

**语义用途**：
- 城门
- 建筑主入口
- 中庭入口
- 桥头入口

**本质**：在"围护结构"上打一个洞 + 标注入口语义

**参数约定**：
```json
{
  "type": "GATE",
  "params": {
    "width": 3,
    "height": 4,
    "position": "south | north | east | west | auto"
  }
}
```

**功能**：
- 自动从 Skeleton 获取门位置（城墙中点、环形外围等）
- 生成门洞（GATE_OPENING）
- 生成门框/门楣（GATE_LINTEL）
- 支持自定义位置（通过 offset）

### 2. STAIR 装配器

**语义用途**：
- 地形高差
- 建筑入口台阶
- 中庭上下层连接
- 山地建筑"依山就势"的关键

**这是"不破坏地形"的核心器官之一**

**参数约定**：
```json
{
  "type": "STAIR",
  "params": {
    "from": "ground",
    "to": "platform",
    "direction": "north | south | east | west",
    "steps": 5
  }
}
```

**功能**：
- 自动从 Skeleton 获取楼梯起点
- 支持指定方向
- 生成台阶（STAIR_STEP）
- 自动适应地形高度

### 3. WALKWAY 装配器

**语义用途**：
- 城墙巡逻道
- 建筑之间连接
- 桥面
- 内院回廊

**WALKWAY = 可走的"语义线"**

**参数约定**：
```json
{
  "type": "WALKWAY",
  "params": {
    "width": 2,
    "connect": "tower_to_tower | gate_to_keep | custom"
  }
}
```

**功能**：
- 自动从 Skeleton 获取路径（环形外围、直线路径等）
- 支持连接类型（tower_to_tower, gate_to_keep, custom）
- 生成步道地面（WALKWAY_FLOOR）
- 支持自定义宽度

### 4. SkeletonHelper 扩展

**新增方法**：
- `getGatePosition()` - 获取门位置（支持 south/north/east/west/auto）
- `getGateFacing()` - 获取门的朝向
- `getStairStart()` - 获取楼梯起点
- `getWalkwayPath()` - 获取步道路径

**支持的 Skeleton 类型**：
- RADIAL_RING - 环形外围门、环形步道
- LINEAR_PATH - 沿路径的步道
- COURTYARD / GRID - 矩形边缘门

### 5. SemanticPart 扩展

**新增语义部位**：
- `GATE_OPENING` - 门洞
- `GATE_LINTEL` - 门框/门楣
- `STAIR_STEP` - 台阶
- `WALKWAY_FLOOR` - 步道地面

## 完整示例

### 示例 1：城堡（带门和台阶）

```java
ExecutableSkeletonPlan skeleton = new ExecutableSkeletonPlan(SkeletonType.GRID)
    .put("width", 30)
    .put("depth", 30);

ComponentPlan components = new ComponentPlan()
    // 围墙
    .add(new ComponentSpec(ComponentType.ENCLOSING_WALL).size(0, 0, 8))
    // 南门
    .add(new ComponentSpec(ComponentType.GATE)
        .param("width", 4)
        .param("height", 5)
        .param("position", "south"))
    // 门前台阶
    .add(new ComponentSpec(ComponentType.STAIR)
        .param("direction", "north")
        .param("steps", 5))
    // 城墙巡逻道
    .add(new ComponentSpec(ComponentType.WALKWAY)
        .param("width", 2)
        .param("connect", "tower_to_tower"));
```

### 示例 2：土楼（带中庭入口）

```java
ExecutableSkeletonPlan skeleton = new ExecutableSkeletonPlan(SkeletonType.RADIAL_RING)
    .put("radius", 20);

ComponentPlan components = new ComponentPlan()
    // 外墙
    .add(new ComponentSpec(ComponentType.ENCLOSING_WALL).size(0, 0, 12))
    // 中庭
    .add(new ComponentSpec(ComponentType.COURTYARD)
        .param("shape", "circle")
        .param("radius", 8))
    // 中庭入口（从外墙到中庭）
    .add(new ComponentSpec(ComponentType.GATE)
        .param("width", 3)
        .param("height", 4)
        .param("position", "auto"))
    // 连接门到中庭的步道
    .add(new ComponentSpec(ComponentType.WALKWAY)
        .param("width", 2)
        .param("connect", "gate_to_keep"));
```

### 示例 3：山地建筑（依山就势）

```java
ExecutableSkeletonPlan skeleton = new ExecutableSkeletonPlan(SkeletonType.TERRACED)
    .put("levels", 4)
    .put("conformTerrain", true);

ComponentPlan components = new ComponentPlan()
    // 台地结构
    .add(new ComponentSpec(ComponentType.FOUNDATION))
    // 连接各层的楼梯
    .add(new ComponentSpec(ComponentType.STAIR)
        .param("direction", "north")
        .param("steps", 8))
    // 层间步道
    .add(new ComponentSpec(ComponentType.WALKWAY)
        .param("width", 2));
```

## 设计优势

### ✅ 智能位置推断

- **GATE**：自动从 Skeleton 获取门位置（城墙中点、环形外围等）
- **STAIR**：自动从 Skeleton 获取起点（通常是入口位置）
- **WALKWAY**：自动从 Skeleton 获取路径（环形外围、直线路径等）

### ✅ 依赖 Skeleton

- 所有交通器官都依赖 Skeleton 来确定位置
- 不定义建筑体量，而是"连接/穿透/行走"
- 天然适合被 AI 组合和省略

### ✅ 语义清晰

- 使用专门的语义部位（GATE_OPENING, STAIR_STEP, WALKWAY_FLOOR）
- 与通用部位区分，便于 Palette 差异化处理

## 已具备的能力

✅ **城堡能"进"** - GATE 在围墙上打洞
✅ **建筑能"走"** - WALKWAY 连接各个部分
✅ **地形高差能"爬"** - STAIR 连接不同高度
✅ **AI 能自然说出**：
   - "在南侧城墙中央开门，门前有台阶通向内院，城墙顶部有巡逻通道"
   - "中庭入口，连接外墙和内院"
   - "依山就势，用楼梯连接各层"

✅ **不需要再写任何 if-else 特判建筑类型**

## 总结

✅ **三个交通器官已完全实现**：
- GATE - 门/入口
- STAIR - 楼梯/高差连接
- WALKWAY - 道路/连廊/城墙步道

✅ **工具类已扩展**：
- SkeletonHelper 新增门位置、楼梯起点、步道路径查询

✅ **语义部位已扩展**：
- GATE_OPENING, GATE_LINTEL, STAIR_STEP, WALKWAY_FLOOR

系统现在从"形体生成"走向"可使用空间"，支持完整的交通连接！

