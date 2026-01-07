# K2：Path × Symmetry × Street 实现总结

## ✅ 已完全实现

### 1. 核心数据模型

#### StreetProfile（街道剖面）
- **位置**：`src/main/java/com/formacraft/common/cluster/StreetProfile.java`
- **字段**：
  - `laneCount` - 建筑排数（每侧）
  - `laneSpacing` - 相邻建筑排之间距离
  - `roadWidth` - 中央道路宽度
  - `symmetric` - 是否左右对称
- **预设**：
  - `simple()` - 单排，普通村路
  - `boulevard()` - 双排，商业街/主街
  - `avenue()` - 三排，城市大道
  - `wallCorridor()` - 单排，城墙走廊
  - `processionalAxis()` - 单侧，中轴线

#### StreetSide（街道侧）
- **位置**：`src/main/java/com/formacraft/common/cluster/StreetSide.java`
- **枚举**：LEFT, RIGHT

#### PathStreetLayoutBuilder（街道布局构建器）
- **位置**：`src/main/java/com/formacraft/server/skeleton/gen/path/PathStreetLayoutBuilder.java`
- **核心算法**：
  1. 沿路径采样锚点
  2. **多排偏移公式**：`offset = roadWidth / 2 + laneSpacing * (lane + 1)`
  3. 左右两侧 + 多排布局
  4. 地形自适应

### 2. PromptAssembler 集成

#### Street Layout Block（街道布局提示）
- **位置**：`PromptAssembler.clusterLayoutBlock()`（K2 扩展）
- **功能**：告诉 AI 街道布局信息（多排、对称等）
- **输出**：
  ```
  STREET LAYOUT:
  - type: MULTI_LANE_PATH
  - lanes_per_side: 2
  - road_width: 6
  - lane_spacing: 6
  - symmetry: true
  - style_distribution:
    * inner_lane: commercial (closer to road)
    * outer_lane: residential (farther from road)
  ```

#### ToolPromptBuilder 扩展
- **新增方法**：`resolveStreetProfile()` - 从用户输入解析街道剖面
- **集成 SymmetryTool**：如果对称工具启用，强制对称

### 3. 三种模式支持

| 模式 | StreetProfile | 结果 |
|------|--------------|------|
| STREET（默认） | `boulevard()` | 商业街/主街 |
| WALL_CORRIDOR | `wallCorridor()` | 城墙走廊 |
| PROCESSIONAL_AXIS | `processionalAxis()` | 中轴线（单侧） |

### 4. 视觉效果对照

| StreetProfile | laneCount | 结果 |
|--------------|-----------|------|
| `simple()` | 1 | 普通村路 |
| `boulevard()` | 2 | 商业街/主街 |
| `avenue()` | 3 | 城市大道 |
| `wallCorridor()` | 1 | 城墙走廊（窄通道） |
| `processionalAxis()` | 1 | 中轴线（单侧） |

## 🎯 核心算法

### 多排偏移公式

```java
int offset = profile.roadWidth() / 2
           + profile.laneSpacing() * (lane + 1);
```

**解释**：
- `roadWidth / 2`：从路径中心到道路边缘
- `laneSpacing * (lane + 1)`：每排建筑的额外偏移
- `lane = 0`：第一排（最靠近道路）
- `lane = 1`：第二排
- `lane = 2`：第三排

### 布局生成流程

```java
for (StreetSide side : StreetSide.values()) {
    // 如果不对称且是右侧，跳过
    if (!profile.symmetric() && side == StreetSide.RIGHT) {
        continue;
    }
    
    for (int lane = 0; lane < profile.laneCount(); lane++) {
        int offset = profile.roadWidth() / 2
                   + profile.laneSpacing() * (lane + 1);
        
        BlockPos anchor = offsetFromPath(p, prev, offset, side == StreetSide.LEFT);
        anchor = adaptToTerrain(ctx, anchor);
        
        slots.add(new BuildingSlot(...));
    }
}
```

## 📋 使用示例

### 场景 1：商业街

```
1. 玩家用 PathTool 画一条路径
2. 输入："沿这条路生成一条中世纪商业街，两边各两排房屋"
3. 系统自动完成：
   - resolveStreetProfile() → boulevard() (laneCount=2)
   - PathStreetLayoutBuilder.build() → 多排布局
   - 每侧 2 排建筑，道路宽度 6 格
   - 对称布局
   - 地形自适应
4. Preview → Apply
```

### 场景 2：城墙走廊

```
1. 玩家用 PathTool 画一条路径
2. 输入："沿这条路生成一段城墙走廊"
3. 系统自动完成：
   - resolveStreetProfile() → wallCorridor() (laneCount=1, roadWidth=2)
   - PathStreetLayoutBuilder.build() → 单排布局
   - 窄通道，建筑紧贴路径
   - 不对称（可选）
4. Preview → Apply
```

### 场景 3：中轴线

```
1. 玩家用 PathTool 画一条路径
2. 输入："沿这条路生成一条中轴线，单侧布置建筑"
3. 系统自动完成：
   - resolveStreetProfile() → processionalAxis() (symmetric=false)
   - PathStreetLayoutBuilder.build() → 单侧布局
   - 只在左侧布置建筑
4. Preview → Apply
```

## 🔗 关键设计决策

### 1. 不推翻 K1，只扩展

- ✅ **K1 保留**：`PathClusterLayoutGenerator` 仍然可用（单排）
- ✅ **K2 扩展**：`PathStreetLayoutBuilder` 支持多排
- ✅ **向后兼容**：现有代码不受影响

### 2. 算法决定站位，AI 决定样式

- ✅ **站位由算法决定**：多排偏移公式计算位置
- ✅ **样式由 AI 决定**：LLM 决定建筑风格和细节
- ✅ **样式分布建议**：AI 可以遵循 inner_lane=commercial, outer_lane=residential

### 3. SymmetryTool 集成

- ✅ **布局级对称**：不再只是"镜像建筑"，而是"对称布局"
- ✅ **强制对称**：如果 SymmetryTool 启用，自动设置 `symmetric=true`
- ✅ **灵活组合**：可以左侧住宅、右侧商业，但几何对称

## 🎯 系统能力

### ✅ 现在可以做什么

1. **多排街道生成**
   - 单排、双排、三排布局
   - 可配置道路宽度和建筑间距
   - 支持对称和非对称布局

2. **三种模式**
   - STREET：普通街道（默认）
   - WALL_CORRIDOR：城墙走廊
   - PROCESSIONAL_AXIS：中轴线

3. **SymmetryTool 集成**
   - 布局级对称
   - 自动强制对称（如果工具启用）

4. **完整闭环**
   - PathTool → PathSkeleton → StreetProfile → PathClusterLayout → BuildingSpec → Generator → Patch → Preview → Apply

## 📝 总结

✅ **K2：Path × Symmetry × Street 已完全实现**

- StreetProfile 数据模型 ✅
- PathStreetLayoutBuilder 多排布局算法 ✅
- PromptAssembler 集成 ✅
- SymmetryTool 集成 ✅
- 三种模式支持 ✅

**系统现在可以生成真正的街区结构！**

**这是专业城市生成系统的核心能力！**

