# K3.1：Program → ComponentPreset 实现总结

## ✅ 已完全实现

### 1. 核心数据模型

#### SemanticComponentType（语义组件类型）
- **位置**：`src/main/java/com/formacraft/common/semantic/SemanticComponentType.java`
- **枚举值**：
  - MASS_MAIN / MASS_SECONDARY（主体/次体块）
  - ENTRANCE / FACADE_WINDOWS / SIGNAGE / BALCONY（入口/立面）
  - FENCE_OR_WALL / GATEWAY（围合/边界）
  - PAVING / PLAZA_CORE / GREENERY / BENCHES（场地/公共空间）
  - STREET_LIGHTS / STREET_FURNITURE（道路设施）
  - CHIMNEY / YARD_STORAGE（工业/结构）
  - TOWER_NODE / BATTLEMENTS / WALKWAY_RAMPART（防御）

#### ComponentPreset（组件装配清单）
- **位置**：`src/main/java/com/formacraft/common/semantic/ComponentPreset.java`
- **字段**：
  - `id` - 预设 ID
  - `descriptionForPrompt` - 描述（用于 Prompt）
  - `items` - 组件项列表
- **Item 字段**：
  - `type` - 组件类型
  - `weight` - 装配权重（0~1）
  - `density` - 密度（0~1）
  - `minSize` / `maxSize` - 尺寸范围
  - `noteForPrompt` - 额外提示

#### ProgramPresetLibrary（程序预设库）
- **位置**：`src/main/java/com/formacraft/common/cluster/zoning/ProgramPresetLibrary.java`
- **预设**：
  - COMMERCIAL - 商业街（门头、招牌、橱窗、路灯、摊位）
  - RESIDENTIAL - 住宅（院墙、门廊、阳台、小花园）
  - PLAZA - 广场（铺装、核心、绿化、长椅）
  - INDUSTRIAL - 工业（大门、烟囱、堆场、工业灯）
  - DEFENSIVE - 防御（塔楼、垛口、巡逻道、门楼）
  - MIXED_USE - 混合用途
  - PARK - 公园
  - CIVIC - 市政
  - LANDMARK - 地标

#### ProgramPresetResolver（程序预设解析器）
- **位置**：`src/main/java/com/formacraft/common/cluster/zoning/ProgramPresetResolver.java`
- **功能**：支持风格偏置的预设解析
- **风格支持**：
  - cyber/cyberpunk - 赛博朋克（招牌/灯更多）
  - medieval/castle - 中世纪（围墙更常见）
  - modern/contemporary - 现代（更简洁）
  - classical/traditional - 古典（更强调入口和装饰）

#### ZonedSlot（带功能分区的槽位）
- **位置**：`src/main/java/com/formacraft/common/cluster/ZonedSlot.java`
- **功能**：扩展 BuildingSlot，包含 preset 信息
- **字段**：所有 BuildingSlot 字段 + `presetId` + `presetText`

### 2. PromptAssembler 集成

#### Component Preset Block（组件预设提示）
- **位置**：`PromptAssembler.componentPresetBlock()`（K3.1 新增）
- **功能**：告诉 AI 每个 Slot 的组件装配清单
- **输出格式**：
  ```json
  "slots": [
    {
      "anchor": [x, y, z],
      "t": 0.123,
      "side": "LEFT",
      "lane": 0,
      "facing": "LEFT_OF_PATH",
      "program": "COMMERCIAL",
      "component_preset_id": "PRESET_COMMERCIAL_STREET",
      "component_preset": "preset_id=PRESET_COMMERCIAL_STREET\n..."
    }
  ]
  ```

### 3. PromptContext 扩展

#### 新增字段
- `zonedSlots` - ZonedSlot 列表（K3.1 新增）
- `styleProfileId` - StyleProfile ID（K3.1 新增，用于风格偏置）

## 🎯 核心算法

### 预设解析流程

```java
// 1. 获取默认预设
ComponentPreset base = ProgramPresetLibrary.getDefault(program);

// 2. 根据风格偏置
if (styleId.contains("cyber")) {
    base = tweak(base, SIGNAGE, +0.15f, 0.0f);
    base = tweakDensity(base, STREET_LIGHTS, +0.10f);
}

// 3. 创建 ZonedSlot
ZonedSlot zoned = ZonedSlot.from(buildingSlot, base);
```

### 预设到 Prompt 的转换

```java
// 1. 为每个 BuildingSlot 解析 preset
for (BuildingSlot slot : slots) {
    ComponentPreset preset = ProgramPresetResolver.resolve(styleId, slot.program);
    ZonedSlot zoned = ZonedSlot.from(slot, preset);
    zonedSlots.add(zoned);
}

// 2. 输出到 Prompt
componentPresetBlock(ctx); // 生成 JSON 格式的 preset 信息
```

## 📋 使用示例

### 场景 1：商业街（立刻看到差异化）

```
1. PathTool → PathSkeleton → StreetProfile → ZoningProfile
2. PathStreetLayoutBuilder.build() → BuildingSlot (program=COMMERCIAL)
3. ProgramPresetResolver.resolve(null, COMMERCIAL) → ComponentPreset
   - MASS_MAIN (weight=1.00)
   - ENTRANCE (weight=0.90)
   - FACADE_WINDOWS (weight=0.95)
   - SIGNAGE (weight=0.85)
   - STREET_LIGHTS (density=0.25)
   - STREET_FURNITURE (density=0.20)
4. ZonedSlot.from(slot, preset) → ZonedSlot
5. PromptAssembler.componentPresetBlock() → 输出到 Prompt
6. Generator / LLM 根据 preset 生成具体组件
```

### 场景 2：赛博朋克风格商业街

```
1. styleId = "cyberpunk"
2. ProgramPresetResolver.resolve("cyberpunk", COMMERCIAL)
   - 基础：COMMERCIAL preset
   - 偏置：SIGNAGE weight +0.15 → 0.95
   - 偏置：STREET_LIGHTS density +0.10 → 0.35
3. 结果：更多招牌和灯光
```

### 场景 3：广场节点

```
1. program = PLAZA
2. ProgramPresetLibrary.getDefault(PLAZA)
   - PAVING (weight=1.00, size=10~30)
   - PLAZA_CORE (weight=0.65, size=3~9)
   - GREENERY (weight=0.75, density=0.20)
   - BENCHES (weight=0.70, density=0.25)
   - STREET_LIGHTS (weight=0.60, density=0.20)
3. Generator 根据 preset 生成：
   - 铺装面（必须）
   - 中心喷泉/雕塑（可选）
   - 边缘树池（密度 20%）
   - 长椅（密度 25%）
```

## 🔗 关键设计决策

### 1. 算法决定组件类型，AI 决定组件细节

- ✅ **组件类型由 preset 决定**：MASS_MAIN, SIGNAGE, STREET_LIGHTS 等
- ✅ **组件细节由 AI 决定**：具体的招牌设计、窗户样式、灯光造型等
- ✅ **明确分工**：避免 AI 自由发散，确保差异化效果

### 2. 权重和密度系统

- ✅ **权重（weight）**：0~1，越大越必做（MASS_MAIN=1.00 必须，BALCONY=0.35 可选）
- ✅ **密度（density）**：0~1，用于路灯/长椅等需要分布放置的组件
- ✅ **尺寸范围**：minSize~maxSize，给 Generator 参考

### 3. 风格偏置系统

- ✅ **不破坏默认**：风格偏置只微调权重/密度，不改变组件类型
- ✅ **可扩展**：后续可以接入 StyleProfile 数据驱动
- ✅ **向后兼容**：styleId=null 时使用默认预设

### 4. 立刻看到差异化效果

- ✅ **不接 LLM 也能跑**：Generator 可以直接根据 preset 生成占位组件
- ✅ **MASS_MAIN**：画一个盒子体块
- ✅ **SIGNAGE**：在入口上方挂一条彩色块
- ✅ **STREET_LIGHTS**：沿 path 每 12 格插一个灯
- ✅ **FENCE_OR_WALL**：前沿画一条矮墙
- ✅ **PAVING**：plaza 区域铺装

## 🎯 系统能力

### ✅ 现在可以做什么

1. **立刻看到差异化效果**
   - COMMERCIAL：门头 + 招牌 + 橱窗 + 路灯 + 摊位
   - RESIDENTIAL：院墙 + 门廊 + 阳台 + 小花园
   - PLAZA：铺装 + 核心 + 绿化 + 长椅
   - INDUSTRIAL：大门 + 烟囱 + 堆场 + 工业灯
   - DEFENSIVE：塔楼 + 垛口 + 巡逻道 + 门楼

2. **风格偏置**
   - 赛博朋克：更多招牌和灯光
   - 中世纪：更多围墙
   - 现代：更简洁，减少装饰
   - 古典：更强调入口和装饰

3. **完整闭环**
   - PathTool → PathSkeleton → StreetProfile → ZoningProfile → Program → ComponentPreset → ZonedSlot → Prompt → Generator → Patch → Preview → Apply

## 📝 总结

✅ **K3.1：Program → ComponentPreset 已完全实现**

- SemanticComponentType 枚举 ✅
- ComponentPreset 类 ✅
- ProgramPresetLibrary 预设库 ✅
- ProgramPresetResolver 解析器 ✅
- ZonedSlot 扩展 ✅
- PromptAssembler 集成 ✅

**系统现在可以"立刻看到差异化效果"！**

**这是专业城市生成系统的核心能力！**

