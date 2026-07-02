# CompositeStructure（复合结构生成器）完成总结

## 完成的工作

### 1. 定义 CompositeSpec（JSON Schema）

**Python 端：** `app/models/composite_spec.py`
- `Vec3i` - 三维坐标
- `SubStructure` - 子结构定义（type, spec, offset）
- `CompositeSpec` - 复合结构规格（structures 列表）

**Java 端：** `com.formacraft.common.model.composite.CompositeSpec`
- `Offset` - 三维坐标偏移
- `SubStructure` - 子结构定义
- `CompositeSpec` - 复合结构规格

### 2. 创建 WallGenerator

**路径：** `com.formacraft.server.generator.WallGenerator`

**功能：**
- 生成矩形直线城墙
- 支持高度、长度、厚度配置
- 沿 Z 轴延伸

### 3. 创建 CompositeStructureGenerator

**路径：** `com.formacraft.server.generator.composite.CompositeStructureGenerator`

**功能：**
- 注册所有生成器（House, Tower, Bridge, Wall）
- 根据 CompositeSpec 生成复合结构
- 计算每个子结构的绝对坐标
- 合并所有子结构为一个 GeneratedStructure

### 4. 更新 AI Planner

**Python 端：** `app/services/ai_planner.py`
- 添加 `_should_generate_composite()` 判断函数
- 添加 `generate_composite_spec()` 生成复合结构
- 添加 `_generate_fallback_composite_spec()` 回退方案
- 更新 system prompt 支持 CompositeSpec

### 5. 更新路由

**Python 端：** `app/routes/build.py`
- 支持返回 `Union[BuildingSpec, CompositeSpec]`
- 自动检测是否应该生成复合结构

### 6. 更新 OrchestratorClient

**Java 端：** `com.formacraft.server.orchestrator.OrchestratorClient`
- 添加 `requestCompositeSpec()` 方法
- 支持解析 CompositeSpec JSON

### 7. 更新 BuildExecutionService

**Java 端：** `com.formacraft.server.build.BuildExecutionService`
- 添加 `queueCompositeBuild()` 方法
- 支持提交复合结构建造任务

### 8. 更新网络通信

**Java 端：** `com.formacraft.common.network.FormaCraftNetworking`
- 自动检测复合结构关键词
- 如果是复合结构，直接开始建造（不发送预览）

## CompositeSpec JSON 格式

```json
{
  "structures": [
    {
      "type": "TOWER",
      "spec": {
        "type": "TOWER",
        "style": "MEDIEVAL",
        "footprint": {
          "shape": "circle",
          "radius": 5
        },
        "height": 15,
        "materials": {...},
        "features": {...}
      },
      "offset": {
        "x": -20,
        "y": 0,
        "z": -20
      }
    },
    {
      "type": "HOUSE",
      "spec": {...},
      "offset": {
        "x": 0,
        "y": 0,
        "z": 0
      }
    }
  ]
}
```

## 复合结构生成流程

```
玩家自然语言描述（包含复合关键词）
    ↓
Python AI Planner 检测关键词
    ↓
生成 CompositeSpec JSON
    ↓
服务器接收 CompositeSpec
    ↓
CompositeStructureGenerator
    ↓
多个 BuildingSpec
    ↓
多个生成器（Tower / House / Bridge / Wall）
    ↓
合并为一个 GeneratedStructure
    ↓
BuildExecutionService 执行
    ↓
分 Tick 放置所有方块
```

## 检测复合结构的关键词

Python 端会自动检测以下关键词：

- 中文：城墙、要塞、复合、组合、城堡、村庄、城市、多个、几座、几栋、围起来
- 英文：village, fort, compound, city, town, settlement, multiple, several, many, surround, enclose

## 使用示例

### 自然语言请求

```
"在这片区域生成一个中世纪小要塞：前面有一座桥，左右各一个瞭望塔，中心一栋大厅，城墙把它们围起来。"
```

### AI 返回的 CompositeSpec

```json
{
  "structures": [
    {
      "type": "BRIDGE",
      "spec": {...},
      "offset": {"x": 0, "y": 0, "z": -25}
    },
    {
      "type": "TOWER",
      "spec": {...},
      "offset": {"x": -20, "y": 0, "z": 0}
    },
    {
      "type": "TOWER",
      "spec": {...},
      "offset": {"x": 20, "y": 0, "z": 0}
    },
    {
      "type": "HOUSE",
      "spec": {...},
      "offset": {"x": 0, "y": 0, "z": 0}
    },
    {
      "type": "WALL",
      "spec": {...},
      "offset": {"x": -30, "y": 0, "z": -30}
    }
  ]
}
```

### Java 端使用

```java
CompositeSpec compositeSpec = ...; // 从后端接收
BlockPos origin = player.getBlockPos();

BuildExecutionService.getInstance()
    .queueCompositeBuild(world, origin, compositeSpec, player.getUuid());
```

## WallGenerator 说明

### 参数

- `height` - 城墙高度（默认 3）
- `length` - 城墙长度（从 footprint.depth 获取，默认 10）
- `thickness` - 城墙厚度（固定为 2 格）

### 生成逻辑

```java
for (int z = 0; z < length; z++) {
    for (int t = 0; t < thickness; t++) {
        for (int y = 0; y < height; y++) {
            BlockPos pos = origin.add(t, y, z);
            blocks.add(new PlannedBlock(pos, wallBlock));
        }
    }
}
```

城墙沿 Z 轴延伸，厚度为 2 格。

## CompositeStructureGenerator 说明

### 注册的生成器

- `HOUSE` → `HouseGenerator`
- `TOWER` → `TowerGenerator`
- `BRIDGE` → `BridgeGenerator`
- `WALL` → `WallGenerator`

### 坐标计算

```java
BlockPos subOrigin = origin.add(offset.x, offset.y, offset.z);
GeneratedStructure subStructure = gen.generate(spec, subOrigin, world);
```

每个子结构的绝对坐标 = 复合结构原点 + 相对偏移

### 合并逻辑

```java
List<PlannedBlock> merged = new ArrayList<>();
for (SubStructure sub : spec.getStructures()) {
    GeneratedStructure subStructure = gen.generate(...);
    merged.addAll(subStructure.getBlocks());
}
```

将所有子结构的 PlannedBlock 合并到一个列表中。

## 应用场景

通过 CompositeStructureGenerator，AI 能一次性生成：

1. **要塞** - 中心大厅 + 4 个角塔 + 城墙
2. **村庄** - 多个房屋 + 塔楼 + 道路
3. **城墙围起来的城门** - 城墙 + 门楼 + 塔楼
4. **河上的桥 + 两岸建筑** - 桥梁 + 房屋
5. **组合城市块** - 多个建筑组合

## 优势

1. **一次性生成** - 不需要多次请求
2. **统一执行** - 所有结构在一个任务中完成
3. **统一 Undo** - 整个复合结构可以一次性撤销
4. **AI 理解上下文** - AI 可以理解建筑之间的关系
5. **可扩展** - 未来可以添加更多生成器（Path, Gate, etc.）

## 未来扩展

### 1. 路径生成器（PathGenerator）

```java
registry.put("PATH", new PathGenerator());
```

### 2. 城门生成器（GateGenerator）

```java
registry.put("GATE", new GateGenerator());
```

### 3. 更智能的布局

- 自动计算最优布局
- 考虑地形高度
- 避免重叠

### 4. 城墙自动连接

- 自动生成闭合的城墙
- 在转角处自动添加角塔

## 总结

CompositeStructureGenerator 是 FormaCraft 迈向 AI 自动生成城市/要塞/村庄的关键一步。现在系统可以：

1. ✅ 检测复合结构关键词
2. ✅ 生成 CompositeSpec
3. ✅ 调用多个生成器
4. ✅ 合并为单个 GeneratedStructure
5. ✅ 统一执行和 Undo

FormaCraft 现在具备了生成复杂复合建筑的能力！

