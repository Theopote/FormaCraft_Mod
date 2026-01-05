# 预制件库（Asset Library）实施进度

本文档记录预制件库系统的实施进度和状态。

## Phase 1：核心基础设施（已完成）

### ✅ 1. 预制件存储格式定义

- **状态**：已完成
- **文件**：
  - `src/main/java/com/formacraft/server/asset/AssetDefinition.java` - 数据模型
  - `src/main/resources/assets/formacraft/asset_library/chinese_traditional/` - 示例文件

**JSON Schema**：
```json
{
  "asset_id": "chinese_dougong_small",
  "version": "1.0",
  "tags": ["Chinese", "Structure", "Connector"],
  "category": "CONNECTOR",
  "size": [3, 2, 2],
  "anchor": "BOTTOM_CENTER",
  "is_flexible": false,
  "generator_class": null,
  "material_variables": {
    "$primary_log": "PRIMARY_STRUCTURE",
    "$accent_stairs": "ACCENT"
  },
  "blocks": [
    {
      "pos": [1, 0, 0],
      "type": "$primary_log",
      "state": {"axis": "y"}
    }
  ]
}
```

### ✅ 2. 预制件加载器

- **状态**：已完成
- **文件**：`src/main/java/com/formacraft/server/asset/AssetLibrary.java`
- **功能**：
  - 从 `assets/formacraft/asset_library/` 递归加载所有 JSON 文件
  - 支持开发环境和运行时的资源加载
  - 在 `ServerInitializer.onInitializeServer()` 中调用 `AssetLibrary.loadAssets()`

**主要方法**：
- `loadAssets()` - 加载所有预制件
- `getAsset(String assetId)` - 获取预制件定义
- `listAssets()` - 列出所有已加载的预制件 ID

### ✅ 3. 基础放置逻辑

- **状态**：已实现基础功能
- **文件**：`src/main/java/com/formacraft/server/asset/AssetLibrary.java` 的 `placeAsset()` 方法
- **功能**：
  - ✅ 支持锚点系统（BOTTOM_CENTER, TOP_CENTER, CENTER, BOTTOM_LEFT, TOP_LEFT）
  - ✅ 支持朝向转换（NORTH, SOUTH, EAST, WEST）
  - ✅ 支持材质变量替换（`$primary_log` → 调色板中的实际方块）
  - ⚠️ 方块状态属性应用（TODO：需要完整实现 BlockState Properties API）

**使用示例**：
```java
List<PlannedBlock> blocks = AssetLibrary.placeAsset(
    "chinese_dougong_small",
    anchorPos,
    Direction.NORTH,
    "PALETTE_CHINESE_TRADITIONAL_A",
    world
);
```

---

## Phase 2：核心风格库（进行中）

### ✅ 1. 中式建筑构件库目录结构

- **状态**：已完成
- **目录**：`src/main/resources/assets/formacraft/asset_library/chinese_traditional/`
  - `connectors/` - 连接件（斗拱等）
  - `fillers/` - 填充件（窗户等）
  - `terminators/` - 收尾件（屋顶装饰等）

### ✅ 2. 核心中式建筑构件

已创建的预制件文件：

#### 连接件（Connectors）

1. **小型斗拱**
   - 文件：`connectors/dougong_small.json`
   - ID：`chinese_dougong_small`
   - 大小：3×2×2
   - 用途：柱头与屋檐的连接件（基础型）

2. **大型斗拱**
   - 文件：`connectors/dougong_large.json`
   - ID：`chinese_dougong_large`
   - 大小：5×3×3
   - 用途：大型建筑的柱头连接件（更复杂）

3. **飞檐**
   - 文件：`connectors/flying_eave.json`
   - ID：`chinese_flying_eave`
   - 大小：5×2×3
   - 用途：屋顶边缘的飞檐结构（可变形）
   - 特点：使用楼梯和台阶模拟飞檐的曲线

#### 填充件（Fillers）

4. **十字格子窗**
   - 文件：`fillers/lattice_window_cross.json`
   - ID：`chinese_lattice_window_cross`
   - 大小：3×3×1
   - 用途：中式风格的十字格子窗

5. **菱形格子窗**
   - 文件：`fillers/lattice_window_diamond.json`
   - ID：`chinese_lattice_window_diamond`
   - 大小：3×3×1
   - 用途：菱形图案的格子窗

6. **方形格子窗**
   - 文件：`fillers/lattice_window_square.json`
   - ID：`chinese_lattice_window_square`
   - 大小：4×4×1
   - 用途：方形网格的格子窗

7. **抱鼓石门**
   - 文件：`fillers/door_luxury.json`
   - ID：`chinese_door_luxury`
   - 大小：4×4×1
   - 用途：高级住宅的入口门（带装饰）

#### 收尾件（Terminators）

8. **屋顶装饰**
   - 文件：`terminators/roof_finial.json`
   - ID：`chinese_roof_finial`
   - 大小：2×2×2
   - 用途：屋脊末端的装饰（脊兽、鸱吻）

9. **瓦当与滴水**
   - 文件：`terminators/eave_detail.json`
   - ID：`chinese_eave_detail`
   - 大小：1×1×1
   - 用途：屋檐边缘的瓦当和滴水细节（可变形）

### ✅ 3. 集成到 facade 系统

- **状态**：已完成
- **实现**：
  - ✅ 在 `MetaAssemblyCompiler.emitFacadeOpsForBox()` 中解析 `facade.decorativeElements`，生成 `PLACE_ASSET` 操作
  - ✅ 在 `MetaAssemblyEngine.applyOp()` 中添加 `PLACE_ASSET` case，调用 `AssetLibrary.placeAsset()` 放置预制件
  - ✅ 在 `AssemblyMacroApplier.applyStyleMacro()` 中为中式风格自动添加 `decorativeElements`（斗拱、格子窗、屋顶装饰）
  
**使用方法**：
```json
{
  "facade": {
    "decorativeElements": [
      {
        "type": "CONNECTOR",
        "assetId": "chinese_dougong_small",
        "placement": "COLUMN_TOP",
        "face": "ALL"
      },
      {
        "type": "FILLER",
        "assetId": "chinese_lattice_window_cross",
        "placement": "WINDOW_FRAME",
        "face": "NORTH,SOUTH"
      }
    ]
  }
}
```

**自动添加**：当 `styleId` 包含 "CHINESE" 或 "ASIAN"，或 `intent` 包含 "中式"、"传统" 时，系统会自动添加中式构件。

---

## 下一步计划

### 短期任务

1. **完善方块状态属性应用**
   - 实现完整的 BlockState Properties API 支持
   - 支持常见属性：`facing`, `axis`, `half`, `type` 等

2. **添加更多中式建筑构件**
   - 飞檐（flying eave）
   - 更多格子窗变体（diamond, square）
   - 抱鼓石门（entrance with drum-stones）
   - 瓦当与滴水（eave tiles）

3. **集成到 facade 系统**
   - 扩展 `facade` JSON Schema
   - 实现 `decorativeElements` 处理逻辑
   - 在 `macro.style` 中自动添加构件

### 中期任务

1. **动态预制件支持**
   - 实现参数化窗户生成器
   - 支持尺寸自适应

2. **其他风格库**
   - 哥特/古典包
   - 科幻/工业包

---

## 技术细节

### 材质变量替换流程

1. 预制件 JSON 中使用变量：`"type": "$primary_log"`
2. `material_variables` 映射：`"$primary_log": "PRIMARY_STRUCTURE"`
3. `placeAsset()` 调用 `PaletteResolver.pick()` 获取实际方块
4. 使用调色板语义名称查找对应的方块 ID

### 锚点系统

- `BOTTOM_CENTER`：底部中心（默认）
- `TOP_CENTER`：顶部中心
- `CENTER`：几何中心
- `BOTTOM_LEFT`：底部左前角
- `TOP_LEFT`：顶部左前角

锚点偏移计算：将预制件的锚点对齐到指定的世界坐标位置。

### 朝向转换

支持 NORTH, SOUTH, EAST, WEST 四个方向的坐标旋转，确保预制件正确放置。

---

## 已知问题和限制

1. **方块状态属性应用不完整**
   - 当前 `applyBlockStateProperties()` 方法只返回原状态
   - 需要使用 Minecraft 的 BlockState Properties API 完整实现

2. **动态预制件尚未实现**
   - `is_flexible: true` 的预制件需要生成器类
   - 参数化窗户等动态构件需要额外的生成逻辑

3. **集成尚未完成**
   - 尚未集成到 `facade` 系统
   - 尚未在 `macro.style` 中自动添加构件

---

## 测试建议

1. **单元测试**：
   - 测试 JSON 解析
   - 测试锚点计算
   - 测试朝向转换
   - 测试材质变量替换

2. **集成测试**：
   - 测试预制件加载
   - 测试预制件放置
   - 测试与调色板系统的集成

3. **功能测试**：
   - 在游戏中手动调用 `AssetLibrary.placeAsset()` 放置预制件
   - 验证视觉效果和方块类型正确性

