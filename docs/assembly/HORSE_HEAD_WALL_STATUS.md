# 马头墙生成器状态说明

## 当前状态

### ✅ 部分实现：集成在 HouseGenerator 中的动态逻辑

在 `HouseGenerator.java` 中有一个**私有的动态马头墙生成方法**：

- **方法名**：`addHuizhouHorseHeadWalls()`
- **位置**：`src/main/java/com/formacraft/common/generation/structure/HouseGenerator.java` (第2795行)
- **调用条件**：当检测到徽派风格（通过 `isHuizhouStyle()` 检查）时自动调用
- **触发位置**：在屋顶生成过程中（第550行）

**特点**：
- ✅ **动态生成**：根据建筑尺寸（width, depth, height）动态计算马头墙形状
- ✅ **参数化**：支持不同的步高和步数（基于建筑宽度自动计算）
- ✅ **独立工具类**：已从 HouseGenerator 中拆分，可被其他生成器复用
- ✅ **灵活配置**：支持自定义 stepWidth 和 stepHeight 参数
- ⚠️ **风格限制**：只在检测到徽派/江南风格时触发（通过 paletteId 或 styleProfileId 判断）

**实现逻辑**：
- 阶梯步进算法：每 stepWidth 个方块距离创建一个"马头"阶梯
- 在山墙两端生成阶梯状加高的山墙
- 使用 wall 材质填充，trim/cap 做顶部收边
- 支持自定义阶梯宽度和高度增量

### ✅ 已实现：独立的动态生成工具类

已从 `HouseGenerator` 中拆分出独立的工具类：

- ✅ 有独立的 `HorseHeadWallGenerator` 类（工具类，静态方法）
- ✅ 可被其他生成器复用
- ⚠️ 不是完整的 `StructureGenerator`（如果需要独立生成结构，可以进一步扩展）
- ⚠️ 不支持通过 `facade_rules.side_wall_type: "HORSE_HEAD_WALL"` 配置调用（未来可扩展）

### ✅ 已实现：静态 Asset Library 构件

- **文件**：`connectors/horse_head_wall_3step.json`
- **ID**：`chinese_horse_head_wall_3step`
- **类别**：CONNECTOR
- **尺寸**：9×8×1（三叠式，固定尺寸）
- **用途**：可以通过 `decorativeElements` 手动指定使用

---

## 对比总结

| 特性 | 独立工具类 | 静态 Asset 构件 |
|------|-----------|----------------|
| **实现状态** | ✅ 已实现 | ✅ 已实现 |
| **动态参数化** | ✅ 是（基于建筑尺寸，可配置参数） | ❌ 否（固定尺寸） |
| **独立性** | ✅ 是（独立工具类，可被其他生成器复用） | ✅ 是（可作为构件使用） |
| **触发方式** | 自动（徽派风格检测）或手动调用 | 手动（JSON 指定） |
| **灵活性** | 高（支持自定义参数） | 中（固定尺寸） |
| **可扩展性** | 高（易于扩展为完整生成器） | 低（需要创建新构件） |

---

## 使用方式

### 方式 1：通过 HouseGenerator 自动生成（当前可用）

当使用 `HouseGenerator` 生成房屋，且满足以下条件时，会自动生成马头墙：

1. 使用徽派风格的调色板（如 `PALETTE_HUIZHOU_WHITE_BLACK_A`）
2. 或 styleProfileId 包含 "huizhou"
3. 屋顶类型为 "gable"（双坡屋顶）

**示例**：
```json
{
  "type": "HOUSE",
  "style": "ASIAN",
  "extra": {
    "paletteId": "PALETTE_HUIZHOU_WHITE_BLACK_A",
    "styleOptions": {
      "roofType": "gable"
    }
  }
}
```

### 方式 2：通过 Asset Library 手动指定（当前可用）

在 `extra.assembly` 或 `facade.decorativeElements` 中手动指定：

```json
{
  "facade": {
    "decorativeElements": [
      {
        "type": "CONNECTOR",
        "assetId": "chinese_horse_head_wall_3step",
        "placement": "GABLE_WALL",
        "face": "EAST,WEST"
      }
    ]
  }
}
```

---

## 未来扩展建议

如果要实现独立的动态生成器（方案 B），需要：

1. **创建 `HorseHeadWallGenerator` 类**
   - 实现 `StructureGenerator` 接口
   - 支持参数化配置（步数、步高、装饰级别）
   - 支持不同的宽度和高度

2. **集成到路由系统**
   - 在 `GeneratorRouter` 中添加路由逻辑
   - 或通过 `facade_rules.side_wall_type: "HORSE_HEAD_WALL"` 配置触发

3. **添加 MetaAssembly 操作支持**（方案 C，长期目标）
   - 在 `MetaAssemblyEngine` 中添加 `HORSE_HEAD_WALL` 操作
   - 在 `facade.sideWallType` 中指定

---

## 结论

**当前状态**：
- ✅ **有动态生成逻辑**（但集成在 HouseGenerator 中，不是独立生成器）
- ✅ **有静态构件**（可通过 Asset Library 使用）
- ❌ **没有独立的动态生成器类**

**建议**：
- 对于大多数用例，当前的集成式动态逻辑已经足够
- 如果需要更灵活的控制或独立使用，可以考虑实现独立的生成器类

