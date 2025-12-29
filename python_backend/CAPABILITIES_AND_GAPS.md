# FormaCraft LLM 建筑泛化能力：现状、缺口与路线图（v1）

本文面向“让大语言模型在模组中发挥更好作用”的目标，总结目前**已支持的能力**与**仍待补齐的空缺**，并给出按优先级排序的下一步迭代方向。

> 术语：
> - **LLM**：大语言模型
> - **Spec**：结构化规格（`BuildingSpec` / `CompositeSpec` / `CitySpec`）
> - **Style Genes（风格基因）**：`extra.styleProfileId / extra.paletteId` + `StyleProfile.details` 的默认回退
> - **硬约束**：选区/轮廓/禁区，必须遵守（而不是“建议遵守”）

---

## 当前链路总览（已具备的“泛化底座”）

### 1) 端到端链路
- **客户端聊天 → PromptAssembler**：自动注入 `anchor/facing/mode` 等空间协议（并支持 Selection/Outline/ProtectedZones）
- **Minecraft 服务端 → Python `/build`**：携带 `facing/dimension/biome/selection/outline/protectedZones/chatHistory` 等上下文
- **Python `ai_planner.py` → BuildingSpec/CompositeSpec/CitySpec**：支持候选集约束、归一化纠错、风格基因回退、强原型模板、部分 blueprint 生成
- **Java `GeneratorRouter`**：优先 blueprint → template → archetype/landmark → type fallback
- **生成器落块**：多个生成器已接入 palette 语义（道路/桥/墙/房/塔/地标等）

### 2) 风格基因与可观测性
- **候选集相关性筛选**：StyleProfile/Palette 由请求文本打分筛选后再喂给模型，减少“瞎选 ID”
- **风格基因归一化**：`palette_id/palette/style_profile_id` 等别名统一到 `extra.paletteId/extra.styleProfileId`
- **合法性校验 + 回退**：无效 ID 会被删除并写入 `extra.debugWarnings`；`styleProfileId` 有默认 palette 时会自动补齐 `paletteId`
- **Debug Warnings 可选显示**：客户端可开关显示 `[debugWarnings]`，便于调 prompt 与回退链路

---

## 结构化 IR（中间表示）能力矩阵

| IR/路径 | 目的 | 当前支持度 | 典型适用场景 | 主要缺口 |
|---|---|---:|---|---|
| `BuildingSpec`（单体） | 单体建筑快速生成 | 高 | “建一个塔/房/桥/墙/地标” | 复杂布局表达不足（轴线/对称/分区/动线） |
| `CompositeSpec`（多体 + Path） | 组团/街区/多栋联动 | 中 | “一排商铺 + 中央广场 + 路网” | 子结构之间的语义关系弱（功能分区/主从关系） |
| `CitySpec`（城市级） | 多区块城市规划 | 中 | “生成城镇/城区/工业区” | 可控性不足（用户指定细节时易跑偏） |
| `extra.blueprint`（语义组件蓝图） | 复杂结构的可控组合 | 中（偏城堡） | 城堡复合体等 | 只覆盖少数 archetype，通用 blueprint schema 仍薄 |
| `extra.genome`（BuildingGenome） | 路由/原型识别 IR | 中 | 强原型/地标路由 | 丰富度不足（风格/拓扑/装饰密度等基因未系统化） |

---

## 用户描述能“稳定表达”的能力（当前强项）

### 结构维度（相对坐标）
- **锚点/朝向**：`anchor/facing` 已稳定注入（PromptAssembler + BuildRequest）
- **尺寸/层数/高度**：`footprint + height + floors` 体系成熟
- **基本类型**：`HOUSE/TOWER/BRIDGE/CASTLE/WALL` + 部分 landmark/template

### 风格维度（材料语义）
- **风格基因**：`styleProfileId/paletteId` 可驱动道路/桥/建筑材料语义一致
- **硬约束**：selection/outline/protectedZones 已进入模型提示（并且服务端仍会裁剪兜底）

---

## 仍然缺失/不够稳的能力（“查缺补漏”清单）

下面每条都对应“用户会自然说出来、但目前很难稳定落实”的能力。

### P0（最影响泛化体验，优先补）
- **布局语义缺口**：用户说“对称/轴线/中庭/回廊/主入口朝向/庭院围合”，目前缺少可强约束的结构字段（只能靠模型自行组织，波动大）。
- **功能分区缺口**：用户说“前店后宅/一层商铺二层住宅/仓库+装卸区+烟囱”，目前只能用 notes/自由文本，缺少可执行的分区结构。
- **材料语义缺口（建筑细部）**：用户说“窗框/梁柱/幕墙/楼板/内照明”，虽已有 palette 语义，但并非所有生成器都消费这些语义；LLM 也缺少明确的“哪些字段会生效”的反馈。

### P1（提升一致性与稳定性）
- **蓝图通用化**：目前 blueprint 更偏特定 archetype；缺一个“通用组件蓝图”（例如：`components[]` 能表达门楼/主楼/侧翼/连廊/院墙/塔楼等）。
- **约束反馈闭环**：服务端裁剪/修复后，缺少把“哪里被裁剪、为什么”反馈给 LLM 的机制（未来可用于二次修复/补全）。
- **路径/道路与建筑关系**：例如“门口自动接路”“路灯密度按街区级风格”，部分已做，但还没形成统一基因/策略层。

### P2（质量提升/可维护性）
- **规则/模板/LLM 的分工边界**：哪些请求应该走 deterministic template，哪些走 LLM，缺少明确可配置策略（当前有部分启发式，但可更系统）。
- **更强的可回归测试**：目前有离线 smoke（契约层），但缺少“生成质量指标”的轻量回归（例如：越界率、材料一致性、灯光密度等）。

---

## 推荐路线图（下一步做什么最划算）

### 线路 A：新增“布局 IR”，把自然语言变成可执行结构约束（P0）
目标：让“对称/轴线/中庭/分区”从“文案”变成“硬约束字段”。
- **建议新增字段（先放在 `extra`，逐步升级为正式 schema）**
  - `extra.layout`: `{ symmetry: "NONE|X|Z|BOTH", axis: "X|Z|NONE", courtyard: true/false, entranceFacing: "NORTH|..." }`
  - `extra.zones`: `[{name,type,aabb_relative, rules[]}]`（分区：商业/居住/仓储/交通）
  - `extra.connectivity`: `[{from,to,type:"corridor|gate|bridge"}]`
- **落地路径**：先让生成器“只消费少量字段”（例如入口朝向/对称），逐步扩展到更多生成器。

### 线路 B：语义材料“全链路生效”（P0/P1）
目标：LLM 选择了 palette 后，更多生成器能消费细部语义（`FRAME/FACADE_CURTAIN/FLOOR_SLAB/INTERNAL_LIGHT/...`）。
- 做法：逐个 generator 把硬编码 block → `PaletteResolver.pick`（遵循“explicit > style > fallback”）
- 并在 prompt 里明确“哪些 extra/materials 字段会影响哪些生成器”

### 线路 C：通用 Blueprint（P1）
目标：复杂建筑不要靠“单体 spec 里塞无穷细节”，改为组件化蓝图。
- 统一 schema：`blueprint_type=generic_compound` + `components[]`（每个组件=类型+尺寸+相对位置+语义标签）
- Java `BlueprintCompilerRegistry` 扩一个通用 compiler，输出 `BuildingSpec`/子结构计划

---

## 快速回归工具

### 离线 Smoke（契约/纠错链路）
无需 LLM/网络，一键验证 `styleProfileId/paletteId` 归一化、合法性校验、自动补齐、debugWarnings：

```bash
python python_backend/tools/spec_contract_smoke.py
```


