# BuildingSpec 2.0 扩展总结

## 完成的工作

### 1. 创建 StyleOptions 类

**Java 端：** `com.formacraft.common.model.build.StyleOptions`

包含以下字段：
- `doorStyle` - 门样式（single/double/arched/none）
- `roofType` - 屋顶类型（flat/gable/cone/pyramid/hipped）
- `bridgeType` - 桥型（flat/arched/suspension/beam/rope）
- `windowRatio` - 窗户比例（0.0~1.0）
- `windowStyle` - 窗户样式（pane/fence/stained）
- `wallPattern` - 墙体图案（uniform/striped/gradient/random）

**Python 端：** `app/models/building_spec.py` 中的 `StyleOptions` 类

### 2. 更新 BuildingSpec

**Java 端：**
- 添加 `styleOptions` 字段（默认创建新实例）
- 提供 getter/setter 方法
- 保留 `extra` 字段用于向后兼容

**Python 端：**
- 添加 `styleOptions` 字段（默认创建新实例）
- 保留 `extra` 字段用于向后兼容

### 3. 更新 AI Prompt

**更新内容：**
- 在 `_build_system_prompt()` 中添加 styleOptions 字段说明
- 在 `_default_schema()` 中添加 styleOptions 的 JSON Schema
- 在 `_generate_fallback_spec()` 中根据建筑类型设置 styleOptions

### 4. 更新生成器

**HouseGenerator：**
- 使用 `styleOptions.getDoorStyle()` 控制门样式
- 使用 `styleOptions.getRoofType()` 控制屋顶类型
- 使用 `styleOptions.getWindowRatio()` 控制窗户密度
- 向后兼容 `extra` 字段

**TowerGenerator：**
- 使用 `styleOptions.getRoofType()` 控制屋顶类型（cone/flat）
- 使用 `styleOptions.getWindowRatio()` 控制窗户密度
- 向后兼容 `extra` 字段

**BridgeGenerator：**
- 使用 `styleOptions.getBridgeType()` 控制桥型
- 向后兼容 `extra` 字段

## StyleOptions 字段详解

### doorStyle（门样式）

| 值 | 说明 | 适用建筑 |
|----|------|---------|
| `single` | 单门 | House, Tower |
| `double` | 双门 | House |
| `arched` | 拱形门 | House, Tower |
| `none` | 无门 | Tower（瞭望塔） |

### roofType（屋顶类型）

| 值 | 说明 | 适用建筑 |
|----|------|---------|
| `flat` | 平顶 | House, Tower |
| `gable` | 双坡屋顶 | House |
| `cone` | 圆锥形屋顶 | Tower |
| `pyramid` | 金字塔式屋顶 | Tower（未来） |
| `hipped` | 四坡屋顶 | House（未来） |

### bridgeType（桥型）

| 值 | 说明 |
|----|------|
| `flat` | 平桥 |
| `arched` | 拱桥 |
| `suspension` | 悬索桥 |
| `beam` | 梁桥（未来） |
| `rope` | 藤桥（未来） |

### windowRatio（窗户比例）

- 范围：0.0 ~ 1.0
- 控制墙体上开窗的概率
- 0.0 = 无窗
- 0.3 = 低密度（每隔 4 格）
- 0.5 = 中等密度（每隔 3 格）
- 1.0 = 高密度（每隔 2 格）

### windowStyle（窗户样式）

| 值 | 说明 | 适用场景 |
|----|------|---------|
| `pane` | 玻璃窗格 | 现代建筑 |
| `fence` | 栅栏窗 | 古代建筑 |
| `stained` | 彩色玻璃 | 教堂等 |

### wallPattern（墙体图案）

| 值 | 说明 | 未来用途 |
|----|------|---------|
| `uniform` | 统一材质 | 默认 |
| `striped` | 条纹图案 | Mossy Bricks 分布 |
| `gradient` | 渐变图案 | 材质渐变 |
| `random` | 随机图案 | 破损效果 |

## JSON 示例

### 完整 BuildingSpec 2.0 JSON

```json
{
  "type": "HOUSE",
  "style": "MEDIEVAL",
  "footprint": {
    "shape": "rectangle",
    "width": 16,
    "depth": 10
  },
  "height": 10,
  "floors": 2,
  "materials": {
    "wall": "minecraft:stone_bricks",
    "roof": "minecraft:dark_oak_planks",
    "floor": "minecraft:oak_planks",
    "window": "minecraft:glass_pane"
  },
  "features": {
    "hasWindows": true,
    "hasStairs": true,
    "hasDoor": true,
    "hasRoof": true
  },
  "styleOptions": {
    "doorStyle": "double",
    "roofType": "gable",
    "windowRatio": 0.35,
    "windowStyle": "pane",
    "bridgeType": "flat",
    "wallPattern": "uniform"
  },
  "notes": "AI 生成的说明"
}
```

### 塔楼示例

```json
{
  "type": "TOWER",
  "style": "MEDIEVAL",
  "footprint": {
    "shape": "circle",
    "radius": 6
  },
  "height": 20,
  "floors": 3,
  "materials": {
    "wall": "minecraft:stone_bricks",
    "roof": "minecraft:dark_oak_planks"
  },
  "features": {
    "hasWindows": true,
    "hasStairs": true,
    "hasRoof": true
  },
  "styleOptions": {
    "doorStyle": "none",
    "roofType": "cone",
    "windowRatio": 0.3
  }
}
```

### 桥梁示例

```json
{
  "type": "BRIDGE",
  "style": "MEDIEVAL",
  "footprint": {
    "shape": "line",
    "width": 5,
    "depth": 30
  },
  "materials": {
    "floor": "minecraft:spruce_planks",
    "wall": "minecraft:stone_bricks"
  },
  "features": {
    "hasRoof": false
  },
  "styleOptions": {
    "bridgeType": "arched"
  }
}
```

## 向后兼容

所有生成器都支持向后兼容：

1. **优先使用 styleOptions**
   ```java
   String roofType = spec.getStyleOptions() != null ? 
       spec.getStyleOptions().getRoofType() : "flat";
   ```

2. **回退到 extra 字段**
   ```java
   if (roofType == null || roofType.isEmpty()) {
       if (spec.getExtra() != null && spec.getExtra().containsKey("roofType")) {
           roofType = String.valueOf(spec.getExtra().get("roofType"));
       }
   }
   ```

3. **默认值**
   ```java
   if (roofType == null || roofType.isEmpty()) {
       roofType = "flat"; // 默认值
   }
   ```

## 优势

### 1. AI 输出更稳定
- 不再在 `extra` 里乱塞字段
- 所有风格参数都有明确的 Schema
- AI 知道应该输出哪些字段

### 2. Java 端代码更简单
- 不再需要从 `extra` Map 中提取值
- 类型安全（有明确的 getter 方法）
- 代码更易读、更易维护

### 3. 不同建筑共享参数
- House 和 Tower 都使用 `roofType`
- 所有建筑都使用 `windowRatio`
- 逻辑更一致

### 4. 未来扩展更容易
- 添加新字段只需修改 StyleOptions
- UI 编辑界面可以直接绑定 styleOptions
- 城市生成 CitySpec 也更好维护

## 使用示例

### 在生成器中使用

```java
// HouseGenerator
String doorStyle = spec.getStyleOptions().getDoorStyle();
String roofType = spec.getStyleOptions().getRoofType();
double windowRatio = spec.getStyleOptions().getWindowRatio();

// TowerGenerator
String roofType = spec.getStyleOptions().getRoofType();
double windowRatio = spec.getStyleOptions().getWindowRatio();

// BridgeGenerator
String bridgeType = spec.getStyleOptions().getBridgeType();
```

### 在 AI Prompt 中

AI 会自动生成包含 styleOptions 的 JSON：

```json
{
  "styleOptions": {
    "doorStyle": "double",
    "roofType": "gable",
    "windowRatio": 0.35
  }
}
```

## 总结

BuildingSpec 2.0 通过引入 `StyleOptions` 类，统一管理所有风格/结构扩展参数，使得：

1. ✅ AI 输出更稳定
2. ✅ Java 端代码更简单、更安全
3. ✅ 不同建筑共享参数时逻辑更一致
4. ✅ 后期扩展更容易
5. ✅ 向后兼容旧格式

FormaCraft 现在拥有了一个标准化的、可扩展的建筑规格系统！

