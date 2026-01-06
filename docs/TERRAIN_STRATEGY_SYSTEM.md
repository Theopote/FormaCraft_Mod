# 地形策略系统实现总结

## ✅ 已实现

### 1. 核心组件

#### TerrainStrategy（地形策略枚举）
- `PRESERVE` - 保护地形（不削山、不填谷）
- `ADAPTIVE` - 自适应（默认推荐）
- `TERRACE` - 梯田/台地
- `FLATTEN` - 强制平整

#### TerrainPolicy（地形策略参数）
- `strategy` - 策略类型
- `scope` - 作用域（NONE/SELECTION/OUTLINE/PATH/ALL）
- `maxCutDepth` - 最大削深
- `maxFillHeight` - 最大填高
- `allowBridges` - 是否允许桥梁
- `allowStairs` - 是否允许台阶/坡道
- `allowFoundations` - 是否允许地基
- `preserveOverallShape` - 是否保护整体地形形状
- `avoidLargeScaleFlatten` - 是否避免大规模平整

#### TerrainPolicyResolver（策略解析器）
- 从工具状态自动解析策略
- 从用户输入中识别策略关键词
- 优先级：PATH > OUTLINE > SELECTION > NONE

### 2. PromptAssembler 集成

#### 系统提示更新
- 添加了"地形是建筑语义的一部分"的说明
- 强调"不要默认平整地形"

#### 地形策略块
- 自动生成地形策略约束
- 包含策略语义、作用域、限制等
- 根据策略类型生成不同的提示语义

#### 路径约束块
- 当作用域为 PATH 时，自动添加路径约束
- 强调"沿路径生成地形自适应结构"
- 支持台阶、桥梁等连接方式

## 🎯 设计理念

### 核心原则

1. **默认 ADAPTIVE，而不是 FLATTEN**
   - 建筑不应该默认平整地形
   - 地形本身是建筑语义的一部分
   - "是否改地形"必须是策略，而不是固定行为

2. **分层决策系统**
   ```
   User Intent
     ↓
   StyleProfile（风格）
     ↓
   Cluster Layout（建筑群级）
     ↓
   Terrain Strategy（地形策略）
     ↓
   Building Base Adaptation（单体底座）
     ↓
   Patch（最终方块修改）
   ```

3. **建筑群级正确处理**
   - 不同的单体建筑根据所在的地形进行平整或者依着地形调整建筑底座
   - 不应该整体把这片区域平整
   - 用道路/台阶/桥连接

### 策略语义

#### PRESERVE（保护地形）
- 不削山、不填谷
- 建筑抬高/架空/台阶连接
- 适合：日式、精灵风、山地寺庙、景观建筑

#### ADAPTIVE（自适应 - 默认）
- 单体建筑各自处理底座
- 建筑群不做整体平整
- 建筑之间用：台阶、缓坡、小桥
- 适合：村落、山地城镇、多建筑办公群、长城/道路/聚落

#### TERRACE（梯田/台地）
- 把地形离散为几个高度平台
- 每个平台放一组建筑
- 平台之间用台阶/坡道
- 适合：山城、中国古城、中世纪山地要塞

#### FLATTEN（强制平整）
- 大范围填平
- 高成本但规则
- 只在用户明确说："完全平整地形"、"工业园区"、"现代城市核心区"

## 📋 使用示例

### 自动解析（从工具状态）

```java
// PromptAssembler 自动调用
TerrainPolicy basePolicy = TerrainPolicyResolver.resolve(ctx);
ctx.terrainPolicy = TerrainPolicyResolver.resolveFromUserText(raw, basePolicy);
```

### 优先级规则

1. **如果有 PathTool 激活（且有路径）**
   → `scope=PATH`, `strategy=ADAPTIVE`

2. **else 如果有 OutlineTool**
   → `scope=OUTLINE`, `strategy=ADAPTIVE`

3. **else 如果有 SelectionTool**
   → `scope=SELECTION`, `strategy=ADAPTIVE`

4. **否则（只有 anchor）**
   → `scope=NONE`, `strategy=ADAPTIVE`（但限制更严）

### 用户输入识别

- "完全平整地形" → `FLATTEN`
- "保护地形" → `PRESERVE`
- "梯田式" → `TERRACE`
- 其他 → `ADAPTIVE`（默认）

## 🔗 与现有系统的关系

### PathTool 集成

PathTool 是地形自适应系统的灵魂：
- 驱动道路生成
- 驱动长城生成
- 驱动建筑沿线布局
- 驱动台阶/缓坡/桥

### Prompt 输出

地形策略会自动添加到 Prompt 中：

```
TERRAIN STRATEGY:
- strategy: ADAPTIVE (adapt buildings individually to terrain...)
- scope: PATH
- limits:
  * max_cut_depth: 3
  * max_fill_height: 3
  * allow_bridges: true
  * allow_stairs: true
  * allow_foundations: true
- IMPORTANT: avoid large-scale flattening; adapt buildings individually
- preserve overall terrain shape (avoid massive earthwork)

PATH CONSTRAINTS:
- follow the provided path geometry
- adapt structure height smoothly to terrain
- add steps/ramps where slope increases
- use small bridges over gaps/water
- keep within path corridor

CLUSTER RULES:
- buildings may sit at different elevations
- connect buildings using terrain-following paths
- use stairs, ramps, or small bridges where needed
```

## 🎯 总结

✅ **地形策略系统已完全集成到 PromptAssembler**

- 默认策略：`ADAPTIVE`（而不是 `FLATTEN`）
- 自动从工具状态解析策略
- 支持用户输入识别
- 完整的 Prompt 语义生成
- PathTool 完美集成

**系统现在可以生成"有意境的建筑群"，而不是"平整地形上的建筑"！**

