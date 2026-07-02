# PathGenerator（自动生成道路）完成总结

## 完成的工作

### 1. 定义 PathSpec（JSON Schema）

**Python 端：** `app/models/composite_spec.py` 和 `app/models/path_spec.py`
- `PathSpec` - 路径规格（from_pos, to_pos, width, material, style）
- 集成到 `CompositeSpec` 中

**Java 端：** `com.formacraft.common.model.path.PathSpec`
- `Point` - 三维坐标点
- `PathSpec` - 路径规格

### 2. 更新 CompositeSpec

**Python 端：**
- 添加 `paths: Optional[List[PathSpec]] = []` 字段

**Java 端：**
- 添加 `List<PathSpec> paths` 字段
- 提供 getter/setter 方法

### 3. 实现 PathGenerator

**路径：** `com.formacraft.server.generator.path.PathGenerator`

**功能：**
- ✅ 直线路径（3D Bresenham 算法）
- ✅ 平滑曲线路径（二次贝塞尔曲线）
- ✅ 阶梯化路径（自动处理高度差）
- ✅ 自动地形贴合（findGround）
- ✅ 道路宽度控制
- ✅ 材质支持
- ✅ 边界装饰（可选）

### 4. 集成到 CompositeStructureGenerator

**更新内容：**
- 添加 `PathGenerator` 实例
- 在生成建筑后生成道路
- 确保道路覆盖在建筑之后（避免冲突）

### 5. 更新 AI Planner

**Python 端：** `app/services/ai_planner.py`
- 更新 system prompt 包含路径生成说明
- 在回退方案中生成示例路径

## PathSpec JSON 格式

```json
{
  "paths": [
    {
      "from_pos": { "x": 0, "y": 0, "z": 0 },
      "to_pos": { "x": 20, "y": 0, "z": -30 },
      "width": 3,
      "material": "minecraft:gravel",
      "style": "default"
    }
  ]
}
```

## 路径样式

### default（默认）
- 直线路径
- 使用 Bresenham 算法
- 自动贴合地形

### curved（曲线）
- 二次贝塞尔曲线
- 更自然的路径
- 随机控制点偏移

### stepped（阶梯）
- 自动处理高度差
- 在高度差处生成中间台阶
- 适合山地地形

### decorated（装饰）
- 道路边缘放置装饰方块
- 使用 Cobblestone 作为边界

## PathGenerator 核心算法

### 1. 直线离散化（rasterizeLine）

使用 3D Bresenham 算法生成直线路径点：

```java
private List<BlockPos> rasterizeLine(BlockPos start, BlockPos end) {
    // 计算 dx, dy, dz
    // 使用 Bresenham 算法生成路径点
    // 返回所有路径点
}
```

### 2. 平滑曲线（generateCurvedPath）

使用二次贝塞尔曲线：

```java
// 控制点（中点 + 随机偏移）
int cx = midX + offsetX;
int cy = midY;
int cz = midZ + offsetZ;

// 二次贝塞尔曲线公式：B(t) = (1-t)²P₀ + 2(1-t)tP₁ + t²P₂
```

### 3. 阶梯化路径（generateSteppedPath）

自动检测高度差并添加中间台阶：

```java
int heightDiff = ground.getY() - prevGround.getY();
if (Math.abs(heightDiff) > 1) {
    // 添加中间台阶
}
```

### 4. 地形贴合（findGround）

向下搜索找到地面：

```java
private BlockPos findGround(ServerWorld world, BlockPos pos) {
    // 从 pos.getY() 向下搜索
    // 找到第一个非空气方块
    // 返回地面位置
}
```

## 道路生成流程

```
PathSpec (from, to, width, material, style)
    ↓
根据 style 选择生成方式
    ↓
生成路径点列表
    ↓
对每个路径点：
    - 计算道路宽度范围
    - 找到地面高度
    - 放置道路方块
    - 清空头顶空间
    - 可选：添加边界装饰
    ↓
返回 GeneratedStructure
```

## 与 CompositeStructureGenerator 集成

```java
// 1. 先生成建筑
for (SubStructure s : spec.getStructures()) {
    GeneratedStructure subStructure = gen.generate(...);
    merged.addAll(subStructure.getBlocks());
}

// 2. 再生成道路（确保道路在建筑之后）
if (spec.getPaths() != null) {
    for (PathSpec path : spec.getPaths()) {
        GeneratedStructure pathStructure = pathGenerator.generate(path, origin, world);
        merged.addAll(pathStructure.getBlocks());
    }
}
```

## 使用示例

### AI 生成的 CompositeSpec

```json
{
  "structures": [
    {
      "type": "HOUSE",
      "spec": {...},
      "offset": {"x": 0, "y": 0, "z": 0}
    },
    {
      "type": "TOWER",
      "spec": {...},
      "offset": {"x": 20, "y": 0, "z": 0}
    }
  ],
  "paths": [
    {
      "from_pos": {"x": 0, "y": 0, "z": 0},
      "to_pos": {"x": 20, "y": 0, "z": 0},
      "width": 3,
      "material": "minecraft:gravel",
      "style": "default"
    }
  ]
}
```

### 效果

玩家会看到：
- ✅ 平滑的通路连接建筑
- ✅ 自动贴合地形
- ✅ 宽度可控（默认 3 格）
- ✅ 材质可控（默认 gravel）
- ✅ 自动清空空气，预留行走空间

## 未来扩展

### 1. 路径平滑（样条曲线）
- 使用三次贝塞尔曲线
- 更自然的曲线路径
- 类似 Minecraft 原版村庄道路

### 2. 道路装饰
- 路灯（Torch / Lantern）
- 栅栏（Fence）
- 路边草丛（Grass）
- 石砖边框（Stone Bricks）

### 3. 自动阶梯化道路
- 处理高度差 → 自动生成楼梯
- 支持斜坡（Slab）
- 自动填充支撑结构

### 4. 悬桥 + Path 联动
- AI 自动决定桥 + 路的连接方式
- 在桥上生成道路
- 桥头自动连接路径

### 5. 河岸步道
- 沿河流生成步道
- 自动避开水域
- 在需要的地方生成桥梁

### 6. 自动地形填平
- 检测道路经过的地形
- 自动填平凹陷
- 自动削平凸起

## 总结

PathGenerator 是 FormaCraft 城市级生成的关键组件。现在系统可以：

1. ✅ 自动生成连接建筑的道路
2. ✅ 支持多种路径样式（直线、曲线、阶梯）
3. ✅ 自动贴合地形
4. ✅ 支持道路宽度和材质配置
5. ✅ 与 CompositeStructureGenerator 完美集成

FormaCraft 现在具备了生成完整村庄/城镇/要塞布局的能力，包括建筑和连接它们的道路网络！

