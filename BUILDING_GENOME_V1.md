# BuildingGenome v1（FormaCraft 建筑 DNA 标准）

本文件定义 **BuildingGenome v1**：用于描述“建筑应该长什么样、遵循什么空间逻辑”，而不是描述具体方块/patch。

目标：
- **工程可落地**（JSON / Java / Python / AI 可共用）
- **通用覆盖**（地标/线性结构/组团/自由建筑）
- **AI 高自由但可控**（选择题，不写作文）
- **未来可视化/可编辑/可混合**

## 1. 总体结构（v1）

```json
{
  "genomeVersion": "1.0",
  "archetype": { "id": "generic", "confidence": 0.0 },
  "topology": {},
  "structure": {},
  "form": {},
  "symmetry": {},
  "modules": [],
  "materials": {},
  "culturalStyle": {},
  "constraints": {},
  "aiHints": {}
}
```

## 2. Archetype（原型识别）
- `id`: 例如 `tulou` / `eiffel_tower` / `temple_of_heaven` / `great_wall`
- `confidence`: 0~1

推荐策略（执行层路由）：
- `< 0.6`：Genome 驱动（非强原型）
- `>= 0.8`：LandmarkGenerator（高还原模板/参数化）
- 中间区间：混合（原型约束 + genome 细节）

## 3. Topology（空间拓扑）
建议字段：
- `layout`: `rectangular|circular|linear|radial|freeform`
- `composition`: `single|cluster|chain|grid`
- `axis`: `centered|axial|none`
- `levels`: `horizontal|vertical|mixed`

## 4. Structure（结构逻辑）
建议字段：
- `type`: `solid|frame|hybrid|suspended`
- `massiveness`: 0~1（厚重感）
- `voidRatio`: 0~1（空洞比例）
- `supports`: `central|distributed`

## 5. Form（形态语法）
建议字段：
- `repetition`: `none|horizontal|vertical|radial`
- `progression`: `uniform|tapering|stepping|upward`
- `curvature`: `straight|curved|mixed`
- `rhythm`: `regular|segmented|irregular`

## 6. Symmetry（对称）
建议字段：
- `type`: `none|bilateral|radial|grid`
- `order`: int（radial 阶数）
- `mirror`: bool

## 7. Modules（模块组合）
v1 采用字符串列表（后续 v1.1 可升级为带参数的对象）：

```json
"modules": ["ring_rooms", "central_courtyard", "stairs", "roof", "windows"]
```

## 8. Materials（材料语义）
v1 只描述语义，不指定方块：
- `primary`: `stone|wood|earth|metal|glass|mixed`
- `secondary`
- `accent`
- `textureBias`: `rough|smooth|polished|aged`

映射在执行层完成（或由 StyleGenome 提供 palette）。

## 9. CulturalStyle（文化风格）
建议字段：
- `region`: `chinese|european|japanese|islamic|modern|industrial|...`
- `era`: `traditional|medieval|19th_century|modern|...`
- `keywords`: string[]

## 10. Constraints（硬约束）
与选区/保护区/地形等系统对接：
- `maxHeight`
- `respectTerrain`
- `insideSelectionOnly`
- `noModifyZones`

## 11. AIHints（仅给 AI 看）
用于提示优先级/参考/避免：
- `reference`
- `priority`
- `avoid`

## 12. 与现有系统的兼容策略（重要）

当前 FormaCraft 的**可执行输入**仍是 `BuildingSpec / CompositeSpec / CitySpec`。

**BuildingGenome v1 建议挂载方式：**
- `BuildingSpec.extra.genome = <BuildingGenome JSON>`
- 或 `CompositeSpec.extra.genome = <BuildingGenome JSON>`（后续扩展）

原则：
- **不改变现有行为**：没有 genome 时照旧生成。
- **逐步接入**：先用于路由与默认参数，再用于模块组合与风格混合。

## 13. GeneratorRouter（路由层约定）

一句话：**BuildingGenome 是不可变中间语言（IR）**，路由层只负责把 genome 映射到合适的 generator 家族。

当前实现（v1 骨架）：
- Java：`com.formacraft.server.generator.router.GeneratorRouter`
- 入口：`StructureGeneratorFactory.getGenerator(spec)` 统一委托给 Router
- 兼容：若 `extra.genome` 不存在或解析失败，则回退到旧的 `type` 路由（保持行为不变）

建议挂载：
- `BuildingSpec.extra.genome`：完整 BuildingGenome JSON object（Map/JSON string 皆可）


