# 中式建筑构件库目录

本文档列出所有可用的中式建筑预制件及其用途。

## 连接件（Connectors）

### 1. chinese_dougong_small - 小型斗拱
- **文件**：`connectors/dougong_small.json`
- **大小**：3×2×2
- **用途**：柱头与屋檐的连接件（基础型）
- **材质变量**：
  - `$primary_log` → PRIMARY_STRUCTURE
  - `$accent_stairs` → ACCENT
  - `$accent_slab` → ACCENT
- **自动添加条件**：density >= 0.3

### 2. chinese_dougong_large - 大型斗拱
- **文件**：`connectors/dougong_large.json`
- **大小**：5×3×3
- **用途**：大型建筑的柱头连接件（更复杂）
- **材质变量**：同小型斗拱
- **特点**：四向出挑，结构更复杂

### 3. chinese_flying_eave - 飞檐
- **文件**：`connectors/flying_eave.json`
- **大小**：5×2×3
- **用途**：屋顶边缘的飞檐结构
- **可变形**：是（`is_flexible: true`）
- **材质变量**：
  - `$roof_material` → ROOF_TILE
  - `$roof_slab` → ROOF_SLOPE
  - `$accent` → ACCENT
  - `$support` → PRIMARY_STRUCTURE
- **自动添加条件**：structureExposure >= 0.6

---

## 填充件（Fillers）

### 4. chinese_lattice_window_cross - 十字格子窗
- **文件**：`fillers/lattice_window_cross.json`
- **大小**：3×3×1
- **用途**：中式风格的十字格子窗
- **可变形**：是
- **材质变量**：
  - `$primary_wood` → PRIMARY_STRUCTURE
  - `$window_glass` → WINDOW
  - `$frame_material` → FACADE_TRIM
- **自动添加条件**：density >= 0.2（随机选择变体）

### 5. chinese_lattice_window_diamond - 菱形格子窗
- **文件**：`fillers/lattice_window_diamond.json`
- **大小**：3×3×1
- **用途**：菱形图案的格子窗
- **可变形**：是
- **材质变量**：同十字格子窗
- **自动添加条件**：density >= 0.2（随机选择变体）

### 6. chinese_lattice_window_square - 方形格子窗
- **文件**：`fillers/lattice_window_square.json`
- **大小**：4×4×1
- **用途**：方形网格的格子窗
- **可变形**：是
- **材质变量**：同十字格子窗
- **自动添加条件**：density >= 0.2（随机选择变体）

### 7. chinese_door_luxury - 抱鼓石门
- **文件**：`fillers/door_luxury.json`
- **大小**：4×4×1
- **用途**：高级住宅的入口门（带装饰）
- **可变形**：否
- **材质变量**：
  - `$primary_door` → DOOR
  - `$frame_material` → FACADE_TRIM
  - `$decorative` → DECOR_DETAIL
  - `$base` → FOUNDATION
- **自动添加条件**：structureExposure >= 0.6
- **特点**：双门结构，带装饰性元素

---

## 收尾件（Terminators）

### 8. chinese_roof_finial - 屋顶装饰
- **文件**：`terminators/roof_finial.json`
- **大小**：2×2×2
- **用途**：屋脊末端的装饰（脊兽、鸱吻）
- **可变形**：否
- **材质变量**：
  - `$roof_material` → ROOF_TILE
  - `$roof_accent` → ROOF_EDGE
  - `$decoration` → DECOR_DETAIL
- **自动添加条件**：structureExposure >= 0.5

### 9. chinese_eave_detail - 瓦当与滴水
- **文件**：`terminators/eave_detail.json`
- **大小**：1×1×1
- **用途**：屋檐边缘的瓦当和滴水细节
- **可变形**：是（`is_flexible: true`）
- **材质变量**：
  - `$roof_accent` → ROOF_EDGE
  - `$roof_tile` → ROOF_TILE
- **自动添加条件**：structureExposure >= 0.4
- **特点**：沿屋顶边缘放置，形成连续的装饰带

---

## 自动添加逻辑

当检测到中式风格（`styleId` 包含 "CHINESE" 或 "ASIAN"，或 `intent` 包含 "中式"、"传统"）时，系统会根据以下条件自动添加构件：

1. **斗拱**（CONNECTOR）
   - 条件：`density >= 0.3`
   - 使用：`chinese_dougong_small`
   - 放置：COLUMN_TOP

2. **格子窗**（FILLER）
   - 条件：`density >= 0.2`
   - 使用：从三个变体中随机选择（基于建筑尺寸）
   - 放置：WINDOW_FRAME

3. **抱鼓石门**（FILLER）
   - 条件：`structureExposure >= 0.6`
   - 使用：`chinese_door_luxury`
   - 放置：ENTRANCE（仅 NORTH 面）

4. **屋顶装饰**（TERMINATOR）
   - 条件：`structureExposure >= 0.5`
   - 使用：`chinese_roof_finial`
   - 放置：ROOF_RIDGE_END

5. **飞檐**（CONNECTOR）
   - 条件：`structureExposure >= 0.6`
   - 使用：`chinese_flying_eave`
   - 放置：ROOF_EDGE

6. **瓦当与滴水**（TERMINATOR）
   - 条件：`structureExposure >= 0.4`
   - 使用：`chinese_eave_detail`
   - 放置：ROOF_EDGE

---

## 使用示例

### 手动指定

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
              "assetId": "chinese_dougong_large",
              "placement": "COLUMN_TOP",
              "face": "ALL"
            },
            {
              "type": "FILLER",
              "assetId": "chinese_lattice_window_diamond",
              "placement": "WINDOW_FRAME",
              "face": "NORTH,SOUTH"
            },
            {
              "type": "FILLER",
              "assetId": "chinese_door_luxury",
              "placement": "ENTRANCE",
              "face": "NORTH"
            }
          ]
        }
      }
    ]
  }
}
```

### 通过 macro.style 自动添加

```json
{
  "macro": {
    "style": {
      "styleId": "Chinese_Traditional",
      "density": 0.7,
      "structureExposure": 0.8
    }
  }
}
```

系统会自动添加匹配的构件。

---

## 总计

**12 个中式建筑构件**：
- 4 个连接件（2 个斗拱 + 1 个飞檐 + 1 个马头墙）
- 5 个填充件（3 个格子窗变体 + 1 个抱鼓石门 + 1 个漏窗）
- 2 个收尾件（1 个屋顶装饰 + 1 个瓦当与滴水）
- 1 个功能件（1 个美人靠）

