# FormaCraft 文档规范

> 防止文档再次膨胀。所有贡献者在新增/修改文档前应阅读本文。

---

## 1. 文档层级

| 层级 | 位置 | 维护要求 |
|------|------|----------|
| **Tier 0 权威** | `ARCHITECTURE.md`, `docs/GENERATION_PIPELINE.md`, `docs/GENERALIZATION_STRATEGY.md`, `docs/INDEX.md` | 功能改动时必须同步更新 |
| **Tier 1 手册** | `docs/FORMACRAFT_DEVELOPER_DOCUMENTATION.md`, `CONTRIBUTING.md` | 大改时更新；细节以 Tier 0 为准 |
| **Tier 2 子系统** | `docs/` 下各子系统活文档 | 对应子系统改动时更新 |
| **Tier 3 归档** | `docs/archive/**` | **不维护**；只读参考 |

完整索引见 [docs/INDEX.md](INDEX.md)。

---

## 2. 命名规范

### 2.1 活文档（应保留在 `docs/` 根或子目录）

- 子系统名 + 版本：`ROOF_PLATE_V3.md`（只保留最新版本）
- Schema 规格：`PLAN_PROGRAM_SCHEMA_V1.md`
- 设计文档：`DYNAMIC_STYLE_RESOLUTION_DESIGN.md`
- 实现指南：`POST_PROCESS_PIPELINE_IMPLEMENTATION.md`（长期有效的实现说明）

### 2.2 禁止在 `docs/` 根目录新建

| 模式 | 去向 | 示例 |
|------|------|------|
| `*_SUMMARY.md` | `docs/archive/reports/` | `INTEGRATION_SUMMARY.md` |
| `*_FIX.md` | `docs/archive/fixes/` | `THUMBNAIL_DISPLAY_FIX.md` |
| `COMPLETE_*.md` | `docs/archive/reports/` | `COMPLETE_SYSTEM_INTEGRATION_REPORT.md` |
| `PHASE*.md` | `docs/archive/reports/` | `PHASE1_TOOLTIP_COMPLETE.md` |
| `FINAL_*.md` | `docs/archive/reports/` | `FINAL_INTEGRATION_STATUS.md` |
| `*_STATUS*.md` | `docs/archive/reports/` | `SYSTEM_STATUS_REPORT.md` |
| 被新版本取代 | `docs/archive/superseded/` | `ROOF_PLATE_V1.md` |

**原则**：完成报告、bug 修复记录、阶段进度 → 直接写入 `docs/archive/`，不在根目录留副本。

### 2.3 版本迭代

- 新版本文档保留在 `docs/`（如 `ROOF_PLATE_V3.md`）
- 旧版本移入 `docs/archive/superseded/`
- 在新版本文档顶部注明取代关系

---

## 3. 内容规范

### 3.1 必须包含

- **日期**或**版本**标记（如 `2026-07`）
- **状态**：维护中 / 参考 / 归档 / Quarantine
- 若描述代码路径，使用**真实类名**（改代码时同步改文档）

### 3.2 禁止

- 复制 Tier 0 已有内容（应链接而非重复）
- 使用已废弃类名而不标注（如 `SmartGeneratorRouter` → 应为 `UnifiedGeneratorRouter`）
- 在多个文档中维护同一段流程描述（流程真相只在 `GENERATION_PIPELINE.md`）

### 3.3 好思想抢救

若归档文档含值得保留的设计思想：

1. 摘要写入 `GENERALIZATION_STRATEGY.md` §4 或对应 Tier 2 活文档
2. 在归档原文顶部加一行：`> 设计思想已摘要至 [GENERALIZATION_STRATEGY.md](../GENERALIZATION_STRATEGY.md) §4.x`
3. 再移入 archive

---

## 4. 新增文档决策树

```
需要写文档？
  ├─ 是系统级架构/流程变更？ → 更新 Tier 0（ARCHITECTURE / GENERATION_PIPELINE）
  ├─ 是泛化/路由/策略变更？   → 更新 GENERALIZATION_STRATEGY
  ├─ 是子系统实现细节？       → 新增/更新 Tier 2，并在 INDEX.md 登记
  ├─ 是 bug 修复/完成报告？   → 直接写 docs/archive/fixes|reports/
  └─ 是临时调试笔记？         → 不写进 repo；或放 archive/fixes 并标注日期
```

---

## 5. 链接约定

- 跨层引用用相对路径
- README 文档表只链 Tier 0 + Tier 1（≤8 条）
- 详细子系统索引只在 `docs/INDEX.md`

---

## 6. 与代码变更的同步清单

改动以下代码时，**必须**检查文档：

| 代码区域 | 需更新的文档 |
|----------|--------------|
| LlmPlan schema / DTO | `GENERATION_PIPELINE.md`, `LLM_JSON_TEMPLATE_K3_1.md`, Python models |
| ComponentPlanCompiler / Router | `GENERATION_PIPELINE.md`, `GENERATOR_ROUTING_MAP.md` |
| 新增 ComponentGenerator | `GENERATION_PIPELINE.md`, `GENERALIZATION_STRATEGY.md` §2 |
| 新增 StructureGenerator / landmark | `landmarks/`, `MIGRATION_LLMPLAN_VS_BUILDINGSPEC.md` |
| Prompt / schema 字段 | `PromptSystemSections`, `GENERATION_QUALITY_PARAMS.md` |
| 包分层 / 质量门 | `ARCHITECTURE.md` §2 |

---

## 相关

- [docs/INDEX.md](INDEX.md) — 完整文档索引
- [docs/archive/README.md](archive/README.md) — 归档说明
