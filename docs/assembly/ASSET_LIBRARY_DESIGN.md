# 预制件库（Asset Library）设计方案

本文档分析预制件库（Asset Library）的设计需求，评估其在 Formacraft 中的参考价值和实施建议。

## 一、需求分析

### 当前 Formacraft 的能力

✅ **已有功能**：
1. **基础构件生成**：`facade.openings` 可以生成简单的窗户/门
2. **表面图案**：`facade.surfacePattern` 支持网格、肋条等图案
3. **材质语义化**：`PaletteResolver` 实现材质语义映射（`$primary_wood` → 具体方块）
4. **参数化控制**：通过 `macro` 参数控制建筑整体特征

⚠️ **缺失功能**：
1. **复杂构件库**：没有专门的预制件库存储复杂构件（斗拱、飞檐、雕花等）
2. **构件组合**：无法将多个方块组合成复杂的装饰性构件
3. **上下文感知放置**：没有"组件分发器"根据上下文自动选择和放置构件
4. **可变形模板**：窗户等构件无法根据洞口大小自动调整

---

## 二、建议的核心价值评估

### ✅ 高价值建议

#### 1. 预制件库四大维度分类

**建议内容**：
- 连接件（Connectors）：斗拱、托架、飞扶壁、梁柱节点
- 填充件（Fillers）：窗框、花格窗、雕花墙面、百叶窗
- 收尾件（Terminators）：脊兽、鸱吻、尖塔顶、避雷针
- 功能件（Fixtures）：吊灯、喷泉、讲台、炼药台组合

**参考价值**：⭐⭐⭐⭐⭐

**理由**：
- 分类清晰，覆盖建筑细节的关键领域
- 与 Formacraft 的 `facade.*` 系统可以很好地集成
- 可以显著提升建筑的细节质量和真实感

**实施建议**：
- 可以与现有的 `facade.openings` 和 `facade.surfacePattern` 系统集成
- 建议扩展 `facade` 对象，添加 `decorativeElements` 字段：
  ```json
  {
    "facade": {
      "openings": [...],
      "surfacePattern": {...},
      "decorativeElements": [
        {
          "type": "CONNECTOR",
          "assetId": "chinese_dougong_small",
          "placement": "COLUMN_TOP",
          "density": 0.8
        },
        {
          "type": "FILLER",
          "assetId": "chinese_lattice_window",
          "placement": "WINDOW_FRAME",
          "variants": "Cross"
        },
        {
          "type": "TERMINATOR",
          "assetId": "chinese_roof_finial",
          "placement": "ROOF_RIDGE_END"
        }
      ]
    }
  }
  ```

---

#### 2. 语义化变量替换系统

**建议内容**：
- 使用 `$primary_wood_stairs` 而非 `oak_stairs`
- 根据建筑风格自动替换材质

**参考价值**：⭐⭐⭐⭐⭐

**理由**：
- ✅ **Formacraft 已部分实现**：`PaletteResolver` 系统已经实现了语义材质映射
- 可以扩展到预制件库，让预制件支持材质变量
- 与现有的 `paletteId` 系统完美契合

**实施建议**：
- 扩展现有的 `PaletteResolver`，支持预制件中的材质变量替换
- 预制件 JSON 中使用语义标识符：
  ```json
  {
    "asset_id": "chinese_dougong_small",
    "blocks": [
      {"pos": [1,0,1], "type": "$primary_log", "data": {"axis": "y"}},
      {"pos": [0,1,0], "type": "$accent_stairs", "data": {"facing": "east"}}
    ]
  }
  ```
- 在放置预制件时，通过 `PaletteResolver` 将 `$primary_log` 替换为调色板中的实际方块

---

#### 3. 动态/参数化预制件

**建议内容**：
- 窗户等构件可以根据洞口大小自动调整
- 使用生成器函数而非静态模板

**参考价值**：⭐⭐⭐⭐

**理由**：
- 解决了静态模板无法适应不同尺寸的问题
- 与 Formacraft 的参数化建模思想一致

**实施建议**：
- 在 Java 端实现预制件生成器接口：
  ```java
  public interface AssetGenerator {
      List<PlannedBlock> generate(AssetContext context);
  }
  
  public class ChineseLatticeWindowGenerator implements AssetGenerator {
      @Override
      public List<PlannedBlock> generate(AssetContext ctx) {
          int width = ctx.getWidth();
          int height = ctx.getHeight();
          // 根据尺寸生成格子窗
          // ...
      }
  }
  ```
- 预制件定义中标记 `is_flexible: true`，系统调用相应的生成器

---

#### 4. 组件分发器（Asset Dispatcher）

**建议内容**：
- 根据上下文（风格、位置、尺寸）自动选择和放置构件

**参考价值**：⭐⭐⭐⭐

**理由**：
- 可以让 AI 生成更智能、更符合上下文的建筑细节
- 与现有的 `macro.style` 系统可以集成

**实施建议**：
- 在 `AssemblyMacroApplier` 中添加 `applyDecorativeElements()` 方法
- 根据 `macro.style.styleId` 和组件类型自动选择匹配的预制件
- 使用标签匹配系统（如建议中的 `tags: ["Chinese", "High_Detail"]`）

---

### ⚠️ 需要考虑的建议

#### 1. 社区仓库概念

**建议内容**：
- `/fc export_asset <name> <tags>` 命令
- 玩家手动搭建构件并导出为预制件 JSON

**参考价值**：⭐⭐⭐

**理由**：
- 长期价值高，但实施复杂度较高
- 需要解决 JSON 序列化、边界检测、材质变量识别等问题

**实施建议**：
- **Phase 1**：先建立核心预制件库（开发团队维护）
- **Phase 2**：再考虑社区贡献和导出功能
- 如果实施，需要：
  - 选区工具（选择要导出的方块区域）
  - JSON 序列化工具
  - 材质变量识别（自动将 `oak_stairs` 识别为 `$primary_wood_stairs`）

---

#### 2. 三大基础风格库

**建议内容**：
- 东亚古建包、哥特/古典包、科幻/工业包

**参考价值**：⭐⭐⭐⭐

**理由**：
- 提供了明确的优先级和实施路径
- 与 Formacraft 现有的风格系统（`StyleProfile`, `PaletteCatalog`）可以集成

**实施建议**：
- 可以与现有的 `style_profiles` 和 `palettes` 系统集成
- 在 `StyleProfile` 中定义关联的预制件库 ID
- 预制件库文件结构：
  ```
  assets/formacraft/asset_library/
    chinese_traditional/
      connectors/
        dougong_small.json
        dougong_large.json
      fillers/
        lattice_window_cross.json
        lattice_window_diamond.json
      terminators/
        roof_finial.json
        eave_detail.json
    gothic_classical/
      connectors/
        flying_buttress.json
      fillers/
        lancet_window.json
      terminators/
        spire_top.json
    industrial_cyber/
      connectors/
        support_beam.json
      fillers/
        vent_grille.json
      terminators/
        antenna.json
  ```

---

## 三、与现有系统的集成方案

### 1. 与 `facade.*` 系统集成

**当前状态**：
- `facade.openings`：生成简单的窗户/门
- `facade.surfacePattern`：生成表面图案
- `facade.facadeGrid`：生成网格系统

**集成方案**：
- 扩展 `facade` 对象，添加 `decorativeElements` 字段
- 在 `MetaAssemblyEngine` 的立面处理逻辑中，在处理 `openings` 和 `surfacePattern` 之后，处理 `decorativeElements`
- 每个 `decorativeElement` 指定：
  - `type`：CONNECTOR / FILLER / TERMINATOR / FIXTURE
  - `assetId`：预制件 ID
  - `placement`：放置规则（如 `WINDOW_FRAME`, `COLUMN_TOP`, `ROOF_RIDGE_END`）
  - `parameters`：可选的参数（尺寸、变体等）

---

### 2. 与 `PaletteResolver` 系统集成

**当前状态**：
- `PaletteResolver` 支持语义材质映射（如 `ROOF_TILE` → 具体方块）

**集成方案**：
- 扩展 `PaletteResolver`，支持预制件中的变量替换
- 预制件 JSON 中使用语义标识符（`$primary_wood`, `$accent_stairs` 等）
- 在放置预制件时，遍历所有方块定义，将变量替换为调色板中的实际方块

---

### 3. 与 `macro.style` 系统集成

**当前状态**：
- `macro.style` 控制整体风格特征（density, symmetry, verticality 等）

**集成方案**：
- 在 `AssemblyMacroApplier.applyStyleMacro()` 中，根据 `styleId` 自动添加匹配的 `decorativeElements`
- 例如，如果 `styleId: "Chinese_Traditional"`，自动添加：
  - `CONNECTOR` 类型的斗拱（根据 density 控制密度）
  - `FILLER` 类型的格子窗（替换简单的窗户）
  - `TERMINATOR` 类型的屋顶装饰

---

## 四、实施优先级建议

### Phase 1：核心基础设施（P0）

1. **预制件存储格式定义**
   - 定义 JSON Schema
   - 支持静态模板和动态生成器标记

2. **预制件加载器**
   - 从 `assets/formacraft/asset_library/` 加载预制件
   - 支持材质变量替换

3. **基础放置逻辑**
   - 实现简单的预制件放置功能
   - 支持锚点系统（BOTTOM_CENTER, TOP_CENTER 等）

---

### Phase 2：核心风格库（P1）

1. **中式建筑构件库**
   - 斗拱（基础型）
   - 格子窗（2-3 种变体）
   - 屋顶装饰（脊兽、鸱吻）

2. **集成到现有系统**
   - 在 `macro.style` 中自动添加中式构件
   - 扩展 `facade.openings`，支持格子窗替换

---

### Phase 3：扩展功能（P2）

1. **其他风格库**
   - 哥特/古典包
   - 科幻/工业包

2. **动态预制件**
   - 实现参数化窗户生成器
   - 支持尺寸自适应

---

### Phase 4：高级功能（P3，可选）

1. **社区仓库**
   - 导出命令
   - 材质变量自动识别

2. **构件组合引擎**
   - 自动在屋顶边缘放置装饰
   - 自动在柱顶放置连接件

---

## 五、技术实现细节

### 1. 预制件 JSON Schema

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
    "$accent_stairs": "ACCENT",
    "$accent_slab": "ACCENT"
  },
  "blocks": [
    {
      "pos": [1, 0, 0],
      "type": "$primary_log",
      "state": {"axis": "y"}
    },
    {
      "pos": [0, 1, 0],
      "type": "$accent_stairs",
      "state": {"facing": "east", "half": "top"}
    }
  ]
}
```

---

### 2. 预制件加载和放置

```java
public class AssetLibrary {
    private static final Map<String, AssetDefinition> ASSETS = new HashMap<>();
    
    public static void loadAssets() {
        // 从 assets/formacraft/asset_library/ 加载所有预制件
        // ...
    }
    
    public static List<PlannedBlock> placeAsset(
        String assetId,
        BlockPos anchor,
        Direction facing,
        String paletteId,
        ServerWorld world
    ) {
        AssetDefinition asset = ASSETS.get(assetId);
        if (asset == null) return List.of();
        
        List<PlannedBlock> blocks = new ArrayList<>();
        for (AssetBlock blockDef : asset.blocks) {
            // 替换材质变量
            String blockType = PaletteResolver.resolveVariable(
                blockDef.type, paletteId, world, anchor, 0L
            );
            BlockState state = parseBlockState(blockType, blockDef.state);
            
            // 计算实际位置（考虑 anchor 和 facing）
            BlockPos pos = computePosition(blockDef.pos, anchor, asset.anchor, facing);
            blocks.add(new PlannedBlock(pos, state));
        }
        return blocks;
    }
}
```

---

### 3. 与 facade 系统集成

```java
// 在 MetaAssemblyEngine 中处理 decorativeElements
if (facade.get("decorativeElements") instanceof List<?> elements) {
    for (Object elemObj : elements) {
        if (elemObj instanceof Map<?, ?> elem) {
            String assetId = str(elem.get("assetId"), null);
            String placement = str(elem.get("placement"), null);
            String type = str(elem.get("type"), null);
            
            if (assetId != null && placement != null) {
                BlockPos anchor = computeAnchor(placement, component, ctx);
                List<PlannedBlock> assetBlocks = AssetLibrary.placeAsset(
                    assetId, anchor, ctx.facing, ctx.paletteId, ctx.world
                );
                blocks.addAll(assetBlocks);
            }
        }
    }
}
```

---

## 六、总结

### 建议的参考价值

**总体评分**：⭐⭐⭐⭐⭐（非常有参考价值）

### 核心价值点

1. ✅ **分类清晰**：四大维度（连接件、填充件、收尾件、功能件）覆盖全面
2. ✅ **语义化设计**：材质变量替换系统与现有 `PaletteResolver` 完美契合
3. ✅ **参数化思想**：动态预制件与 Formacraft 的参数化建模理念一致
4. ✅ **实施路径明确**：三大基础库提供了清晰的优先级

### 与 Formacraft 的契合度

- ✅ **高度契合**：可以很好地集成到现有的 `facade.*`、`PaletteResolver`、`macro.style` 系统
- ✅ **互补性强**：填补了当前系统在复杂构件细节方面的空白
- ✅ **扩展性好**：可以在现有架构上扩展，不需要大规模重构

### 实施建议

1. **短期**（1-2 周）：
   - 定义预制件 JSON Schema
   - 实现基础加载和放置功能
   - 实现 3-5 个核心中式建筑构件作为概念验证

2. **中期**（1-2 个月）：
   - 建立完整的中式建筑构件库
   - 集成到 `macro.style` 系统
   - 扩展到其他风格库

3. **长期**（3-6 个月）：
   - 实现动态预制件生成器
   - 考虑社区仓库功能
   - 构件组合引擎

这些建议对于提升 Formacraft 的建筑细节质量和真实感具有**很高的参考价值**，建议优先实施核心基础设施和核心风格库。

