# 预制件库（Asset Library）集成总结

## 完成状态

✅ **Phase 1：核心基础设施** - 已完成
✅ **Phase 2：核心风格库和集成** - 已完成

## 实施内容

### 1. 核心基础设施

#### ✅ 预制件数据模型
- **文件**：`src/main/java/com/formacraft/server/asset/AssetDefinition.java`
- **功能**：定义预制件的数据结构（assetId, tags, category, size, anchor, blocks 等）

#### ✅ 预制件加载器
- **文件**：`src/main/java/com/formacraft/server/asset/AssetLibrary.java`
- **功能**：
  - 从 `assets/formacraft/asset_library/` 递归加载所有 JSON 文件
  - 支持开发环境和运行时的资源加载
  - 在 `ServerInitializer.onInitializeServer()` 中自动初始化

#### ✅ 基础放置逻辑
- **文件**：`src/main/java/com/formacraft/server/asset/AssetLibrary.java` 的 `placeAsset()` 方法
- **功能**：
  - 锚点系统（BOTTOM_CENTER, TOP_CENTER, CENTER 等）
  - 朝向转换（NORTH, SOUTH, EAST, WEST）
  - 材质变量替换（`$primary_log` → 调色板中的实际方块）
  - 方块状态属性应用（facing, axis, half, type 等）

### 2. 核心风格库

#### ✅ 中式建筑构件库
- **目录**：`src/main/resources/assets/formacraft/asset_library/chinese_traditional/`
- **连接件（Connectors）**：
  - `connectors/dougong_small.json` - 小型斗拱（3×2×2）
  - `connectors/dougong_large.json` - 大型斗拱（5×3×3）
  - `connectors/flying_eave.json` - 飞檐（5×2×3，可变形）
- **填充件（Fillers）**：
  - `fillers/lattice_window_cross.json` - 十字格子窗（3×3×1）
  - `fillers/lattice_window_diamond.json` - 菱形格子窗（3×3×1）
  - `fillers/lattice_window_square.json` - 方形格子窗（4×4×1）
  - `fillers/door_luxury.json` - 抱鼓石门（4×4×1）
- **收尾件（Terminators）**：
  - `terminators/roof_finial.json` - 屋顶装饰（2×2×2）
  - `terminators/eave_detail.json` - 瓦当与滴水（1×1×1，可变形）

### 3. 集成到 facade 系统

#### ✅ 编译器集成
- **文件**：`src/main/java/com/formacraft/server/assembly/MetaAssemblyCompiler.java`
- **位置**：`emitFacadeOpsForBox()` 方法
- **功能**：解析 `facade.decorativeElements`，生成 `PLACE_ASSET` 操作

#### ✅ 执行引擎集成
- **文件**：`src/main/java/com/formacraft/server/assembly/MetaAssemblyEngine.java`
- **位置**：`applyOp()` 方法的 `PLACE_ASSET` case
- **功能**：
  - 计算锚点位置
  - 调用 `AssetLibrary.placeAsset()` 放置预制件
  - 处理朝向转换

#### ✅ 自动添加逻辑
- **文件**：`src/main/java/com/formacraft/server/assembly/macro/AssemblyMacroApplier.java`
- **位置**：`applyStyleMacro()` 方法
- **功能**：当检测到中式风格时，自动添加：
  - 斗拱（CONNECTOR）- 当 density >= 0.3 时
  - 格子窗（FILLER）- 当 openness >= 0.2 时
  - 屋顶装饰（TERMINATOR）- 当 structureExposure >= 0.5 时

## 使用方法

### 手动指定预制件

在 `extra.assembly` 的组件中，可以在 `facade` 对象中添加 `decorativeElements`：

```json
{
  "graph": {
    "components": [
      {
        "id": "Main",
        "type": "SHELL_BOX",
        "w": 20,
        "d": 15,
        "h": 18,
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
    ]
  }
}
```

### 自动添加（通过 macro.style）

当使用 `macro.style` 参数，且 `styleId` 包含 "CHINESE" 或 "ASIAN"，或 `intent` 包含 "中式"、"传统" 时，系统会自动添加中式构件：

```json
{
  "macro": {
    "style": {
      "styleId": "Chinese_Traditional",
      "density": 0.6,
      "openness": 0.4,
      "structureExposure": 0.7
    }
  }
}
```

系统会自动在 `facade.decorativeElements` 中添加匹配的构件。

## 技术细节

### 预制件 JSON Schema

```json
{
  "asset_id": "chinese_dougong_small",
  "version": "1.0",
  "tags": ["Chinese", "Structure", "Connector"],
  "category": "CONNECTOR",
  "size": [3, 2, 2],
  "anchor": "BOTTOM_CENTER",
  "is_flexible": false,
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

### 材质变量替换流程

1. 预制件 JSON 中使用变量：`"type": "$primary_log"`
2. `material_variables` 映射：`"$primary_log": "PRIMARY_STRUCTURE"`
3. `placeAsset()` 调用 `PaletteResolver.pick()` 获取实际方块
4. 使用调色板语义名称查找对应的方块 ID

### 朝向和锚点

- **朝向转换**：预制件的朝向会根据 `entranceFacing` 自动旋转
- **锚点系统**：支持 BOTTOM_CENTER, TOP_CENTER, CENTER, BOTTOM_LEFT, TOP_LEFT
- **放置规则**：当前实现使用简单的中心放置，未来可以扩展（WINDOW_FRAME, COLUMN_TOP, ROOF_RIDGE_END 等）

## 已知限制

1. **放置规则简化**：当前 `computeAssetAnchor()` 使用简单的中心放置，复杂的放置规则（如 WINDOW_FRAME, COLUMN_TOP）需要进一步实现
2. **动态预制件未实现**：`is_flexible: true` 的预制件需要生成器类支持
3. **方块状态属性应用不完整**：部分复杂的方块状态属性（如楼梯的复杂组合）可能无法完全应用

## 下一步建议

1. **完善放置规则**：实现更智能的放置规则（如自动在柱子顶部放置斗拱）
2. **添加更多中式构件**：飞檐、更多格子窗变体、抱鼓石门等
3. **动态预制件支持**：实现参数化窗户生成器
4. **其他风格库**：哥特/古典包、科幻/工业包

## 相关文件

- `src/main/java/com/formacraft/server/asset/AssetDefinition.java` - 数据模型
- `src/main/java/com/formacraft/server/asset/AssetLibrary.java` - 加载器和放置逻辑
- `src/main/java/com/formacraft/server/assembly/MetaAssemblyCompiler.java` - 编译器集成
- `src/main/java/com/formacraft/server/assembly/MetaAssemblyEngine.java` - 执行引擎集成
- `src/main/java/com/formacraft/server/assembly/macro/AssemblyMacroApplier.java` - 自动添加逻辑
- `docs/assembly/ASSET_LIBRARY_DESIGN.md` - 设计文档
- `docs/assembly/ASSET_LIBRARY_IMPLEMENTATION.md` - 实施进度文档

