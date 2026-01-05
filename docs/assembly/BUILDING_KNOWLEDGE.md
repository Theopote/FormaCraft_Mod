# Building Knowledge Base（建筑知识库）— v1

目标：为特定建筑（地标、知名建筑）提供详细的建筑特征信息，增强 AI 对建筑特征的理解和生成能力。

## 存放位置

- `src/main/resources/assets/formacraft/building_knowledge/*.json`

## Schema（v1）

每个文件一个 JSON 对象，存储一个特定建筑的详细信息：

```json
{
  "id": "birds_nest_stadium",
  "name": "鸟巢体育馆",
  "nameEn": "Bird's Nest Stadium / Beijing National Stadium",
  "aliases": ["鸟巢", "鸟巢体育馆", "国家体育场", "北京鸟巢", "birds nest", "birds nest stadium", "national stadium"],
  "category": "LANDMARK",
  "architect": "Herzog & de Meuron",
  "style": "Deconstructivism",
  "styleId": "Deconstructivism_Zaha",
  
  "features": {
    "shape": "elliptical",
    "form": "organic_curved",
    "structuralType": "steel_frame_mesh",
    "materials": ["steel", "concrete", "glass"],
    "scale": "large",
    "distinctiveElements": [
      "mesh facade structure",
      "irregular steel frame",
      "elliptical footprint",
      "exposed structural elements",
      "organic curved form"
    ]
  },
  
  "dimensions": {
    "typicalWidth": 60,
    "typicalDepth": 80,
    "typicalHeight": 30,
    "footprintShape": "ellipse",
    "aspectRatio": "3:4"
  },
  
  "assemblyHints": {
    "type": "CUSTOM",
    "macro": {
      "style": {
        "styleId": "Deconstructivism_Zaha",
        "structureExposure": 0.95,
        "density": 0.8,
        "curvature": 0.7
      },
      "twist": {
        "twistTurns": 0.0
      },
      "roofCurvature": 0.6
    },
    "components": [
      {
        "type": "SHELL_BOX",
        "facade": {
          "surfacePattern": {
            "pattern": "MESH",
            "density": 0.8
          }
        }
      }
    ]
  },
  
  "description": "Beijing National Stadium, known as the Bird's Nest, features an irregular steel frame structure forming a mesh-like facade. The elliptical form and exposed structural elements are characteristic of deconstructivist architecture.",
  "descriptionZh": "北京国家体育场，又称鸟巢，采用不规则的钢框架结构，形成网状立面。椭圆形形态和外露的结构元素是解构主义建筑的典型特征。",
  
  "keywords": ["鸟巢", "体育馆", "体育场", "钢结构", "网状结构", "椭圆形", "解构主义", "扎哈风格"],
  "exampleRefs": []
}
```

### 字段说明

- **`id`**: string（必填，唯一标识符，与 archetype 的 id 对应）
- **`name`**: string（必填，中文名称）
- **`nameEn`**: string（可选，英文名称）
- **`aliases`**: string[]（必填，检索关键词，用于匹配用户输入）
- **`category`**: string（可选，LANDMARK / INFRASTRUCTURE / FORTIFICATION）
- **`architect`**: string（可选，建筑师/设计者）
- **`style`**: string（可选，建筑风格）
- **`styleId`**: string（可选，对应的 styleProfileId）

- **`features`**: object（必填，建筑特征）
  - `shape`: 形状（rectangular / circular / elliptical / organic / etc.）
  - `form`: 形式（curved / linear / radial / etc.）
  - `structuralType`: 结构类型（steel_frame / concrete / masonry / hybrid / etc.）
  - `materials`: 材料列表
  - `scale`: 尺度（small / medium / large / huge）
  - `distinctiveElements`: 标志性元素列表

- **`dimensions`**: object（可选，典型尺寸）
  - `typicalWidth`, `typicalDepth`, `typicalHeight`: 典型尺寸
  - `footprintShape`: 基座形状
  - `aspectRatio`: 宽高比

- **`assemblyHints`**: object（可选，生成建议）
  - `type`: BuildingType
  - `macro`: macro 参数建议
  - `components`: 组件结构建议

- **`description`**: string（可选，英文描述）
- **`descriptionZh`**: string（可选，中文描述）
- **`keywords`**: string[]（可选，检索关键词）
- **`exampleRefs`**: string[]（可选，引用 assembly_examples）

## 检索集成

建筑知识库通过增强的 RAG 检索器集成到 AI 提示词中：

1. **检索阶段**：当用户输入包含建筑名称时，RAG 系统会从 `building_knowledge` 目录检索匹配的建筑特征信息
2. **提示词注入**：检索到的建筑特征信息会被注入到系统提示词中，指导 LLM 生成准确的 BuildingSpec
3. **Assembly 生成**：`assemblyHints` 字段提供生成建议，帮助 LLM 创建合适的 `extra.assembly` 结构

## 与 Archetype 的关系

- **Archetype**（`archetypes_v1.json`）：存储建筑类型的元数据（id, aliases, defaults, constraints），用于路由到专用生成器
- **Building Knowledge**（`building_knowledge/*.json`）：存储建筑的详细特征信息（形状、材料、结构、生成建议），用于增强 AI 理解

两者可以配合使用：Archetype 用于路由，Building Knowledge 用于生成。

## 使用场景

- 用户输入："生成鸟巢体育馆"
  1. Archetype 检测器识别 `birds_nest_stadium`
  2. RAG 检索器从 `building_knowledge/birds_nest_stadium.json` 检索特征信息
  3. 系统提示词中包含建筑特征描述和 `assemblyHints`
  4. LLM 根据特征信息生成准确的 BuildingSpec

