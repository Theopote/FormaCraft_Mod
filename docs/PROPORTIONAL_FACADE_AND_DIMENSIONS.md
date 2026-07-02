# 比例化立面与尺寸规范化系统

## 概述

本文档描述了 Formacraft 模组中新增的**比例化立面计算系统**和**尺寸规范化系统**，实现了以下两个核心目标：

1. **尺寸规范化**：一个方块 = 1米，确保建筑尺寸符合现实比例
2. **智能立面计算**：基于建筑体量和比例自动计算立面进退，而非硬编码

## 核心设计原则

### 1. 尺寸规范化

- **一个方块 = 1米**：这是系统的基础单位约定
- **每层最小高度 = 3米（3格）**：确保建筑符合现实比例
- **用户优先**：如果用户指定了具体高度要求，优先使用用户的要求
- **自动验证**：系统会自动验证尺寸的合理性

### 2. 智能立面计算

- **基于比例，非硬编码**：立面进退根据建筑体量、整体尺寸自动计算
- **比例协调**：考虑建筑的长宽高比例，确保视觉协调
- **用户可定制**：支持用户通过特征描述指定进退比例
- **算法驱动**：使用数学算法计算合理的进退值

## 实现细节

### ProportionalFacadeCalculator（比例化立面计算器）

**位置**：`src/main/java/com/formacraft/common/generation/component/util/ProportionalFacadeCalculator.java`

#### 核心方法

##### 1. `calculateSteppedFacade()`

计算阶梯式立面的每层配置。

**参数**：
- `totalWidth`: 总宽度（米/格）
- `totalDepth`: 总深度（米/格）
- `totalHeight`: 总高度（米/格）
- `floorHeight`: 每层高度（米/格），如果为0则自动计算
- `userSetbackRatio`: 用户指定的进退比例（0-1），如果为0则自动计算

**返回值**：`LayerConfig[]` - 每层的配置数组

**算法流程**：
1. 计算合理的每层高度（至少3米）
2. 计算楼层数
3. 计算进退比例（基于建筑比例）
4. 为每一格计算尺寸和偏移

##### 2. `calculateFloorHeight()`

计算合理的每层高度。

**规则**：
- 用户指定 > 0：使用用户指定值，但至少3米
- 自动计算：
  - 小建筑（≤6米）：每层3米
  - 中等建筑（6-12米）：每层3-4米
  - 大建筑（12-24米）：每层4-5米
  - 超大建筑（>24米）：每层5米

##### 3. `calculateSetbackRatio()`

计算进退比例。

**算法**：
1. 如果用户指定了比例，直接使用
2. 否则，基于建筑比例自动计算：
   - 高瘦建筑（高度/宽度 > 1.5）：10% 每层
   - 矮胖建筑（高度/宽度 < 0.8）：4% 每层
   - 正常建筑：6% 每层
3. 根据楼层数调整（楼层越多，每层进退稍小）
4. 限制在合理范围内（3%-12%）

##### 4. `extractFloorHeightFromFeatures()`

从用户输入的特征中提取每层高度要求。

**支持的格式**：
- "floor_height: 3"
- "层高: 4米"
- "每层3m"

##### 5. `extractSetbackRatioFromFeatures()`

从用户输入的特征中提取进退比例要求。

**支持的格式**：
- "setback: 5%"
- "进退: 0.06"

##### 6. `validateDimensions()`

验证尺寸合理性。

**规则**：
- 最小尺寸：至少3x3x3米
- 最大尺寸：不超过200米
- 高度/宽度比例：不超过10:1

## 使用示例

### 示例1：自动计算立面

```java
// 用户输入：12x15x12米的中式建筑，要求有进退关系
int width = 12;
int depth = 15;
int height = 12;

// 系统自动计算：
// - 每层高度：3-4米（根据总高度12米）
// - 楼层数：3-4层
// - 进退比例：6%（正常建筑）
// - 每层缩小：约0.7-0.8米
```

### 示例2：用户指定每层高度

```java
// 用户输入：features = ["floor_height: 4", "stepped_facade"]
// 系统使用：
// - 每层高度：4米
// - 楼层数：12 / 4 = 3层
// - 进退比例：自动计算（6%）
```

### 示例3：用户指定进退比例

```java
// 用户输入：features = ["setback: 8%", "stepped_facade"]
// 系统使用：
// - 每层高度：自动计算（3-4米）
// - 进退比例：8%
// - 每层缩小：总宽度 * 8%
```

## 集成到 MassMainGenerator

`MassMainGenerator` 已经集成了新的系统：

1. **尺寸规范化**：
   ```java
   // 确保高度至少3米
   height = Math.max(3, height);
   
   // 从用户输入提取每层高度
   int userFloorHeight = ProportionalFacadeCalculator
       .extractFloorHeightFromFeatures(c.features());
   ```

2. **智能立面计算**：
   ```java
   if (hasSteppedFacade && height >= 3) {
       double userSetbackRatio = ProportionalFacadeCalculator
           .extractSetbackRatioFromFeatures(c.features());
       
       LayerConfig[] layerConfigs = ProportionalFacadeCalculator
           .calculateSteppedFacade(width, depth, height, 
                                   userFloorHeight, userSetbackRatio);
   }
   ```

## 优势

### 1. 符合现实比例

- 一个方块 = 1米，确保建筑尺寸符合现实
- 每层至少3米，符合实际建筑规范

### 2. 智能计算

- 基于建筑体量自动计算，而非硬编码
- 考虑建筑比例，确保视觉协调
- 支持用户自定义，灵活可配置

### 3. 可扩展性

- 算法可以不断优化
- 支持更多建筑类型和风格
- 易于添加新的计算规则

## 未来改进方向

1. **更智能的比例计算**：
   - 考虑建筑风格（中式、欧式等）
   - 考虑建筑功能（住宅、商业等）
   - 使用机器学习优化比例

2. **更多用户控制**：
   - 支持指定具体楼层数
   - 支持指定每层不同的进退
   - 支持指定立面曲线（非线性进退）

3. **性能优化**：
   - 缓存计算结果
   - 优化算法复杂度

## 总结

新的比例化立面与尺寸规范化系统实现了：

✅ **尺寸规范化**：一个方块 = 1米，每层至少3米  
✅ **智能计算**：基于建筑体量和比例自动计算立面进退  
✅ **用户优先**：支持用户自定义要求  
✅ **比例协调**：确保建筑视觉协调  

这使得 Formacraft 能够生成更加符合现实、比例协调的建筑。
