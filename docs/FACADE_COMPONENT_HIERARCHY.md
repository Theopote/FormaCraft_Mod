# 立面构件分级系统（Facade Component Hierarchy）v1

## 🎯 核心设计理念

**这一步直接决定"像不像建筑师做的立面"，而且选的是完全正确的切入点：**
不是先谈风格，不是先谈材质，而是先把立面构件的"层级语法"立起来。

### 核心定义

**立面构件分级系统 = 决定"哪些构件是结构性的、哪些是装饰性的、哪些是可省略的"的规则层。**

注意三点：
- ❌ 不是"多放点装饰"
- ❌ 不是"贴模型"
- ✅ 是控制复杂度与秩序的系统

## 📋 为什么一定要"分级"

如果没有分级，AI 或规则系统会：
- 每个窗都想加窗套
- 每个立面都想加线脚
- 柱距乱来，节奏崩坏

**现实建筑中之所以"稳"，是因为：**
- 不是所有构件是同一等级的

## 🏗 三层等级（v1 足够）

### L0：结构级（Structural）
- **语义定义**：决定立面"骨架节奏"的构件
- **特点**：
  - 数量少
  - 间距大
  - 强烈影响整体比例
- **核心代表**：柱距（Bay / Column Spacing）

### L1：强调级（Articulation）
- **语义定义**：帮助人"读懂立面层次"的构件
- **特点**：
  - 不承重（语义上）
  - 但非常重要
  - 可以少，但不能乱
- **核心代表**：窗套、腰线、分层线

### L2：装饰级（Decoration）
- **语义定义**：即使没有，建筑也"成立"的构件
- **特点**：
  - 可选、可删、可变
- **示例**：窗楣小装饰、局部浮雕、次要线脚

## 🔧 核心组件

### 1. FacadeComponentLevel（构件分级枚举）

**位置**：`src/main/java/com/formacraft/common/mass/facade/FacadeComponentLevel.java`

```java
public enum FacadeComponentLevel {
    STRUCTURAL,     // L0
    ARTICULATION,   // L1
    DECORATION      // L2
}
```

### 2. FacadeBay（立面柱距）

**位置**：`src/main/java/com/formacraft/common/mass/facade/FacadeBay.java`

**核心定义**：
- 柱距 = 立面被"划分"的节奏单位
- 不是"柱子模型"，而是立面的结构骨架

**Bay 决定**：
- 窗的对齐位置
- 窗是否成组
- 线脚的断点

**数据模型**：
```java
public record FacadeBay(
    String id,
    Box bounds,
    int width,
    int baseY,
    int topY,
    BayRole role  // PRIMARY / SECONDARY
)
```

### 3. WindowSurroundRule（窗套规则）

**位置**：`src/main/java/com/formacraft/common/mass/facade/WindowSurroundRule.java`

**规则地位**：
- 依附于 WINDOW Socket
- 不改变窗洞尺寸
- 只是"强调边界"

**派生规则（v1）**：
```
if (windowSocket.layer.role >= STANDARD
    && bay.role == PRIMARY
    && detailLevel >= MEDIUM) {
    allow window surround;
}
```

**注意**：不是每个窗都有窗套

### 4. HorizontalBandRule（水平线脚规则）

**位置**：`src/main/java/com/formacraft/common/mass/facade/HorizontalBandRule.java`

**语义**：
- 线脚 = 在"楼层边界"上画一条强调线
- 跟 FloorLayer 走，不跟 Window 走

**规则**：
```
if (layer.role == STANDARD && detailLevel >= MEDIUM) {
    create FLOOR_DIVIDER band;
}
```

### 5. FacadeDetailLevel（细节级别）

**位置**：`src/main/java/com/formacraft/common/mass/facade/FacadeDetailLevel.java`

**AI 控制**：
- AI 不能随便加窗套、线脚
- AI 只能选择 detailLevel 和是否启用某类构件

**级别**：
- `LOW`：只有 STRUCTURAL 构件
- `MEDIUM`：STRUCTURAL + 部分 ARTICULATION
- `HIGH`：STRUCTURAL + ARTICULATION + DECORATION

### 6. FacadeBayGenerator（柱距生成器）

**位置**：`src/main/java/com/formacraft/common/mass/facade/FacadeBayGenerator.java`

**算法**：
1. 收集所有 Window Socket 的 X/Z 位置
2. 根据 Rhythm Profile 的 spacing 和 alignment 计算 Bay 边界
3. 识别主次 Bay（基于宽度和窗的密度）

### 7. FacadeHierarchyProcessor（立面层次处理器）

**位置**：`src/main/java/com/formacraft/common/mass/facade/FacadeHierarchyProcessor.java`

**核心职责**：协调立面构件分级系统的完整流程

## 🔄 执行顺序（不可打乱）

```
1️⃣ 计算 FacadeBay（柱距）
2️⃣ 在 Bay 内放 Window Socket
3️⃣ 给部分 Window 加 WindowSurround
4️⃣ 在 FloorLayer 边界加 HorizontalBand
5️⃣ 最后（可选）加 Decoration
```

**为什么顺序这么重要？**

因为：
- Bay 是骨架
- Window 是内容
- Surround / Band 是强调
- Decoration 是气氛

👉 这是建筑学的真实顺序

## 🎯 AI 在这一层的正确角色

**AI 不能：**
- 随便加窗套
- 随便加线脚

**AI 只能：**
- 选择：
  - `detailLevel`（低 / 中 / 高）
  - 是否启用某一类强调构件

例如：
```json
{
  "facadeDetailLevel": "MEDIUM",
  "enableWindowSurround": true,
  "enableHorizontalBand": true
}
```

**系统再决定：**
- 加在哪
- 加多少
- 哪些窗能加

## ✅ 关键优势

1. **不引入模型**
2. **不生成连续几何**
3. **所有规则基于 block / layer / socket**
4. **AI 非常好控制**
5. **玩家极易理解**（"骨架 / 强调 / 装饰"）

## 🔗 系统集成

### BuildingMassPipeline 集成

**位置**：`src/main/java/com/formacraft/common/mass/integration/BuildingMassPipeline.java`

**Step 8**：应用立面层次处理（构件分级系统）

```java
// Step 8: 应用立面层次处理
Map<Direction, FacadeHierarchyResult> facadeHierarchies = new HashMap<>();
FacadeDetailLevel detailLevel = FacadeDetailLevel.MEDIUM; // v1 简化

for (Direction facing : facings) {
    // 收集这个朝向的所有 Socket
    // 处理立面层次
    FacadeHierarchyResult hierarchyResult = 
            FacadeHierarchyProcessor.processHierarchy(
                    facingSockets,
                    facing,
                    layers,
                    facadeProfile,
                    detailLevel
            );
    facadeHierarchies.put(facing, hierarchyResult);
}
```

### BuildingMassPipelineResult 扩展

现在 `BuildingMassPipelineResult` 包含：
- `facadeHierarchies` - 每个朝向的立面层次处理结果

## 🐛 Debug Overlay（未来）

强烈建议显示：
- Bay 边界（竖线）
- Window 是否属于 PRIMARY Bay
- Band 所在高度

这样可以立刻看出：
- 立面是否"散"
- 是否缺乏骨架
- 是否装饰过度

---

**设计时间**: 2026-01-14  
**状态**: ✅ 已实现并集成到 BuildingMassPipeline
