# Landmark / Archetype 子系统（v1）

目标：为“强原型、强还原”的建筑对象提供稳定生成链路：**识别 → 选择原型 → 参数化 → 专用生成器 → 校验/修正（评分） → Patch**。

## 1. 核心概念
- **Style（风格）**：材质/窗型/屋顶语言/细节倾向（中世纪、现代、中式…）
- **Archetype（原型）**：几何拓扑 + 关键比例 + 标志性构件（土楼环形围合、天坛三层台基与圆殿、埃菲尔塔收分…）

## 2. 两段式识别（v1）

### Stage 1：本地规则先筛（快且稳定）
- 基于 `aliases`（中文/英文/别名）命中
- 输出：`{id, confidence, reason_tags}`

实现：
- `python_backend/app/services/archetype_registry.py`
- `python_backend/app/services/archetype_detector.py`
- 统一数据源：`src/main/resources/assets/formacraft/archetypes/archetypes_v1.json`
  - Python 默认直接读取该文件（可用环境变量 `FORMACRAFT_ARCHETYPES_JSON` 覆盖路径）
  - Java 从 classpath 读取同路径资源

### Stage 2：AI 多选确认（v1 预留）
v1 先不强制启用（避免增加额外 LLM 调用），后续接入时必须采用“候选列表多选一”，防止幻觉创造新 archetype。

## 3. Strong / Soft 两种模式
- **LANDMARK_STRONG（强还原）**
  - 触发：`confidence >= 0.85` 或 玩家明确要求“尽量还原/真实比例/像”
  - 行为：必须走专用生成器/确定性模板
- **INSPIRED（灵感）**
  - 行为：继承原型关键语义，但不强制专用生成器（可走通用 topology 生成）

## 4. 与 BuildingGenome 的关系
BuildingGenome 作为 IR：Archetype 检测结果会挂到：

- `BuildingSpec.extra.genome.archetype = {id, confidence}`
- 以及 `BuildingSpec.extra.archetypeMode / archetypeReasonTags`（用于调试与 UI）

## 5. 当前落地状态（v1）
- 已实现强原型：`tulou`
  - 识别：本地 alias 命中（“土楼/永定/福建土楼/tulou”）
  - 参数化：直径/层数/门朝向/内院/百叶窗等（详见 `docs/landmarks/tulou.md`）
  - 专用生成器：`TulouGenerator`（Java）

其它原型（已进入 Registry/候选集，后续补专用生成器）：
- `eiffel_tower`
- `temple_of_heaven`
- `golden_gate_bridge`
- `great_wall`
- `giant_wild_goose_pagoda`


