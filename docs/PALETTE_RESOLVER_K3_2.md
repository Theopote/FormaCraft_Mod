# K3.2：Semantic → PaletteResolver（权重随机）实现总结

## ✅ 已完全实现

### 核心目标

**把 Generator 从 `stone_bricks` 升级成"中世纪石墙会自然老化、有苔藓、有裂纹、有变化"**

这是 **质感跃迁** 的关键一步。

### 设计原则

- ❌ **错误做法**：
  - Generator 里直接写具体方块
  - LLM 直接输出 block id

- ✅ **正确做法**（已实现）：
  - Generator 只画几何
  - Palette 决定"用什么块 + 概率"
  - StyleProfile → Palette → Semantic Part

### 核心组件

#### 1. PaletteBlock（带权重的方块）
- **位置**：`src/main/java/com/formacraft/common/palette/component/PaletteBlock.java`
- **字段**：
  - `blockId` - 方块 ID（String）
  - `weight` - 权重（int）

#### 2. Palette（权重随机选择器）
- **位置**：`src/main/java/com/formacraft/common/palette/component/Palette.java`
- **功能**：
  - 根据 `SemanticPart` 权重随机选择方块
  - 支持 `add(SemanticPart, blockId, weight)` 添加映射
  - 支持 `pick(SemanticPart)` 权重随机选择
  - 支持自定义 `Random` 实例（用于可复现的随机）

#### 3. PaletteLibrary（风格预设库）
- **位置**：`src/main/java/com/formacraft/common/palette/component/PaletteLibrary.java`
- **功能**：
  - 提供不同风格的 Palette 预设
  - `forStyle(String styleProfile)` 根据风格 ID 获取 Palette
- **已实现风格**：
  - **MEDIEVAL_STONE**（中世纪石墙）
    - 墙体：70% 正常石砖 + 15% 裂纹 + 10% 苔藓 + 5% 圆石
    - 墙体基础：60% 正常 + 20% 裂纹 + 15% 苔藓 + 5% 圆石
    - 墙体装饰：50% 雕纹石砖 + 30% 正常 + 20% 裂纹
    - 结构柱：90% 云杉原木 + 10% 去皮云杉原木
    - 地面：40% 粗土 + 30% 沙砾 + 30% 圆石
    - 屋顶：60% 云杉木板 + 30% 深色橡木木板 + 10% 橡木木板
    - 道路表面：50% 沙砾 + 40% 圆石 + 10% 粗土
    - 道路边缘：70% 石头 + 30% 圆石
  - **CYBERPUNK**（赛博朋克）
    - 墙体：50% 黑色混凝土 + 30% 灰色混凝土 + 15% 青色混凝土 + 5% 蓝色混凝土
    - 结构柱：80% 铁块 + 20% 灰色混凝土
    - 装饰：50% 红石灯 + 50% 萤石
  - **ELVEN**（精灵风格）
    - 墙体：60% 白桦原木 + 30% 去皮白桦原木 + 10% 橡木原木
    - 地面：40% 苔藓块 + 30% 草方块 + 30% 白桦木板
    - 装饰：40% 藤蔓 + 60% 苔藓块

#### 4. SemanticPart 扩展
- **位置**：`src/main/java/com/formacraft/common/semantic/SemanticPart.java`
- **新增枚举值**：
  - `WALL_BASE` - 墙体基础（地基）
  - `WALL_ACCENT` - 墙体装饰（强调）
  - `ROAD_SURFACE` - 道路表面
  - `ROAD_EDGE` - 道路边缘

### Generator 升级

#### TowerGenerator v2
- **位置**：`src/main/java/com/formacraft/common/generation/component/impl/TowerComponentGenerator.java`
- **升级内容**：
  - ✅ 使用 `PaletteLibrary.forStyle()` 获取风格
  - ✅ 根据位置确定 `SemanticPart`（基础/装饰/边缘）
  - ✅ 使用 `palette.pick(part)` 权重随机选择方块
- **效果**：
  - 同一个 JSON，每次生成略有不同
  - 建筑不再"贴图感"
  - 风格可以被系统性扩展

#### WallGenerator v2
- **位置**：`src/main/java/com/formacraft/common/generation/component/impl/WallComponentGenerator.java`
- **升级内容**：
  - ✅ 使用 `Palette` 权重随机
  - ✅ 根据位置确定 `SemanticPart`（基础/装饰/边缘）
- **效果**：
  - 墙体自然老化、有苔藓、有裂纹、有变化

#### RoadGenerator v2
- **位置**：`src/main/java/com/formacraft/common/generation/component/impl/RoadGenerator.java`
- **升级内容**：
  - ✅ 使用 `Palette` 权重随机
  - ✅ 区分 `ROAD_SURFACE` 和 `ROAD_EDGE`
- **效果**：
  - 道路表面和边缘有不同的材质变化

### 使用示例

```java
// 1. 获取风格 Palette
String styleProfile = "MEDIEVAL_CLASSIC";
Palette palette = PaletteLibrary.forStyle(styleProfile);

// 2. 根据位置确定 SemanticPart
SemanticPart part = determinePart(y, height, radius, x, z);

// 3. 权重随机选择方块
String block = palette.pick(part);

// 4. 生成 BlockPatch
out.add(new BlockPatch(BlockPatch.PLACE, x, y, z, block));
```

### 核心提升

✅ **同一个 JSON，每次生成略有不同**
✅ **建筑不再"贴图感"**
✅ **风格可以被系统性扩展**
✅ **LLM 不需要知道任何 block id**

### 结果

**同一个 TowerGenerator**
- → 中世纪像城堡
- → 赛博朋克像数据塔
- → 精灵风像树干

**你已经真正进入了"建筑生成引擎"的范畴！**

## 📝 总结

✅ **K3.2：Semantic → PaletteResolver（权重随机）已完全实现**

- PaletteBlock（带权重的方块）✅
- Palette（权重随机选择器）✅
- PaletteLibrary（风格预设库，3 种风格）✅
- SemanticPart 扩展（4 个新枚举值）✅
- Generator 升级（TowerGenerator, WallGenerator, RoadGenerator）✅

**系统现在可以从"能生成"推进到"看起来像人建的"！**

**这是 FormaCraft 质感跃迁的关键一步！**

