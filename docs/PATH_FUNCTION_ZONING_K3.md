# K3：Path × 功能分区（Function Zoning）实现总结

## ✅ 已完全实现

### 1. 核心数据模型

#### BuildingProgram（建筑功能/业态）
- **位置**：`src/main/java/com/formacraft/common/cluster/zoning/BuildingProgram.java`
- **枚举值**：
  - RESIDENTIAL（住宅）
  - COMMERCIAL（商业）
  - MIXED_USE（混合用途）
  - INDUSTRIAL（工业）
  - CIVIC（市政）
  - RELIGIOUS（宗教）
  - DEFENSIVE（防御）
  - LANDMARK（地标）
  - PARK（绿地/花园）
  - PLAZA（广场）
  - PORT（港口/码头）
  - FARM（农田/仓房）

#### ZoneRule（分区规则）
- **位置**：`src/main/java/com/formacraft/common/cluster/zoning/ZoneRule.java`
- **字段**：
  - `startT` / `endT` - 路径进度范围 [0..1]
  - `sides` - 适用的侧（LEFT/RIGHT）
  - `laneIndex` - 适用的 lane（null=所有 lane）
  - `requiredLabel` - 需要的 SemanticLabel
  - `program` - 建筑功能
  - `weight` - 权重（优先级）

#### ZoningProfile（街区分区预设）
- **位置**：`src/main/java/com/formacraft/common/cluster/zoning/ZoningProfile.java`
- **预设**：
  - `defaultTownStreet()` - 默认城镇街道（商业前段 + 住宅中段 + 广场节点 + 工业尾段）
  - `commercialStreet()` - 商业街（全程商业内排 + 住宅外排）
  - `defensiveStreet()` - 防御性街道（城墙走廊）

#### PathZoningPlanner（路径分区规划器）
- **位置**：`src/main/java/com/formacraft/common/cluster/zoning/PathZoningPlanner.java`
- **功能**：根据 ZoningProfile 为每个 BuildingSlot 计算 Program
- **算法**：匹配所有规则，选择权重最高的

### 2. BuildingSlot 扩展（K3）

#### 新增字段
- `t` - 路径进度 [0..1]
- `side` - 街道侧（LEFT/RIGHT）
- `laneIndex` - Lane 索引
- `program` - 建筑功能

#### 兼容性
- 保留 K1/K2 兼容构造函数（无 zoning 信息）
- 新增 K3 完整构造函数（包含所有字段）

### 3. 与工具联动

#### ISemanticLabelQuery（语义标签查询接口）
- **位置**：`src/main/java/com/formacraft/common/cluster/zoning/ISemanticLabelQuery.java`
- **功能**：根据路径进度查询标签集合

#### SemanticLabelQueryAdapter（适配器）
- **位置**：`src/main/java/com/formacraft/client/tool/SemanticLabelQueryAdapter.java`
- **功能**：将 SemanticLabelTool 适配为 ISemanticLabelQuery
- **实现**：根据路径进度计算对应位置，查询附近标签

### 4. PathStreetLayoutBuilder 扩展（K3）

#### 新增参数
- `zoningProfile` - 分区预设（可选）
- `labelQuery` - 标签查询接口（可选）

#### 核心逻辑
```java
// 计算路径进度 t
float t = (anchors.size() <= 1) ? 0.0f : (i / (float) (anchors.size() - 1));

// 查询标签
Set<String> labels = labelQuery.queryLabelsNearPathT(t);

// 解析建筑功能
BuildingProgram program = zoning.resolve(t, side, lane, labels);

// 创建带功能的 BuildingSlot
slots.add(new BuildingSlot(anchor, t, side, lane, facing, width, depth, height, program));
```

### 5. PromptAssembler 集成

#### Zoning Block（功能分区提示）
- **位置**：`PromptAssembler.zoningBlock()`（K3 新增）
- **功能**：告诉 AI 建筑功能分区信息
- **输出**：
  ```
  ZONING:
  - basis: path
  - profile: TOWN_STREET_DEFAULT
  - rules_summary:
    * t=0.00~0.25 both LEFT lane=0 -> COMMERCIAL
    * t=0.00~0.70 both LEFT lane=1 -> RESIDENTIAL
    * t=0.35~0.45 both all-lanes label=plaza -> PLAZA
    * t=0.75~1.00 both all-lanes -> INDUSTRIAL
  - slots (sample):
    * {t:0.12,side:LEFT,lane:0,program:COMMERCIAL}
    * {t:0.12,side:LEFT,lane:1,program:RESIDENTIAL}
  - IMPORTANT: Building functions (programs) are already determined by zoning rules
  - Your role: generate appropriate components for each program
  ```

#### ToolPromptBuilder 扩展
- **新增方法**：`resolveZoningProfile()` - 从用户输入解析分区预设

## 🎯 核心算法

### 分区规则匹配

```java
public boolean match(float t, StreetSide side, int lane, Set<String> labelsAtT) {
    // 检查路径进度
    if (t < startT || t > endT) return false;
    
    // 检查侧
    if (sides != null && !sides.isEmpty() && !sides.contains(side)) return false;
    
    // 检查 lane
    if (laneIndex != null && laneIndex != lane) return false;
    
    // 检查标签
    if (requiredLabel != null && !labelsAtT.contains(requiredLabel)) return false;
    
    return true;
}
```

### 功能解析（权重选择）

```java
public BuildingProgram resolve(float t, StreetSide side, int lane, Set<String> labelsAtT) {
    ZoneRule best = null;
    float bestWeight = -1f;

    for (ZoneRule r : rules) {
        if (!r.match(t, side, lane, labelsAtT)) continue;
        if (r.weight() > bestWeight) {
            bestWeight = r.weight();
            best = r;
        }
    }

    return (best != null) ? best.program() : RESIDENTIAL;
}
```

## 📋 使用示例

### 场景 1：城镇街道（自动分区）

```
1. 玩家用 PathTool 画一条路径
2. 输入："沿这条路生成一条城镇街道"
3. 系统自动完成：
   - resolveZoningProfile() → defaultTownStreet()
   - PathStreetLayoutBuilder.build() → 多排布局 + 功能分区
   - t=0.00~0.25: 内排商业
   - t=0.00~0.70: 外排住宅
   - t=0.35~0.45: 如果有 label=plaza → 广场
   - t=0.75~1.00: 工业/仓储
   - PromptAssembler.zoningBlock() → 告诉 AI 功能分区
4. Preview → Apply
```

### 场景 2：商业街（全程商业）

```
1. 玩家用 PathTool 画一条路径
2. 输入："沿这条路生成一条商业街"
3. 系统自动完成：
   - resolveZoningProfile() → commercialStreet()
   - 内排全程商业
   - 外排住宅（如果有）
4. Preview → Apply
```

### 场景 3：标签触发分区

```
1. 玩家用 PathTool 画一条路径
2. 用 SemanticLabelTool 标注 "plaza" 区域
3. 输入："沿这条路生成一条城镇街道"
4. 系统自动完成：
   - SemanticLabelQueryAdapter.queryLabelsNearPathT() → 查询标签
   - 在 t=0.35~0.45 且有 label=plaza 的位置 → PLAZA
   - 其他位置按规则分配功能
4. Preview → Apply
```

## 🔗 关键设计决策

### 1. 算法决定功能，AI 决定组件

- ✅ **功能由算法决定**：ZoningProfile 和 ZoneRule 决定建筑功能
- ✅ **组件由 AI 决定**：LLM 根据功能生成合适的组件（店铺门头、摊位、住宅阳台、工业烟囱等）
- ✅ **明确分工**：避免 AI 破坏算法确定的功能分区

### 2. 多维度分区规则

- ✅ **路径进度**：t=0.00~0.25 商业，t=0.75~1.00 工业
- ✅ **侧**：LEFT/RIGHT 可以不同功能
- ✅ **Lane**：内排商业，外排住宅
- ✅ **标签**：label=plaza → PLAZA

### 3. 权重系统

- ✅ **优先级**：同时命中多条规则时，选择权重最高的
- ✅ **标签优先**：标签触发的规则通常权重更高（如 2.0）

### 4. 与工具联动

- ✅ **SemanticLabelTool**：用户手工标注触发特殊分区
- ✅ **SymmetryTool**：布局级对称（功能可以不对称）
- ✅ **NoBuild/Protected**：分区生成后再裁切

## 🎯 系统能力

### ✅ 现在可以做什么

1. **自动功能分区**
   - 沿路径自动形成：商业街段 → 住宅段 → 广场节点 → 地标节点 → 工业尾段
   - 左右分工：左商业右住宅、或迎路商业/背路住宅
   - 多排分工：内排商业、外排住宅

2. **标签触发分区**
   - 用户标注 "plaza" → 自动生成广场
   - 用户标注 "gate" → 自动生成城门
   - 用户标注 "tower" → 自动生成塔楼

3. **灵活组合**
   - 几何对称 + 功能不对称
   - 多维度分区规则（进度 + 侧 + lane + 标签）

4. **完整闭环**
   - PathTool → PathSkeleton → StreetProfile → ZoningProfile → PathClusterLayout → BuildingSpec → Generator → Patch → Preview → Apply

## 📝 总结

✅ **K3：Path × 功能分区已完全实现**

- BuildingProgram 枚举 ✅
- ZoneRule 分区规则 ✅
- ZoningProfile 分区预设 ✅
- PathZoningPlanner 规划器 ✅
- BuildingSlot 扩展 ✅
- ISemanticLabelQuery 接口 ✅
- SemanticLabelQueryAdapter 适配器 ✅
- PathStreetLayoutBuilder 扩展 ✅
- PromptAssembler 集成 ✅

**系统现在可以让 AI "懂规划"，但不让它乱站位！**

**这是专业城市生成系统的核心能力！**

