# Phase 1 游戏内验收发现 · 2026-10-25

基线：`ff7039cd`。玩家实测 + 截图；本文件归档问题与根因分诊。  
日志：`run/logs/latest.log`（本机会话约 16:19–16:45）。**未宣称已在本机复现全部截图场景之外的结论。**

## 严重度总表

| ID | 现象 | 严重度 | 归类 | 根因倾向 |
|----|------|--------|------|----------|
| A2 | 增量修改整栋重建；原点漂到屋顶角等 | **P0** | 主干工作流 | Chat 固定 `PromptMode.BUILD`；PATCH 入口走错/未走 LlmPlan patch |
| A1 | 门窗朝向错、玻璃板单侧未连接、门下缺地板 | **P1** | 生成质量 | 朝向/开口契约与后处理 |
| A1 备选 | 入口方向错、无窗 | **P1** | 生成质量 | 同 A1 |
| A3 / 备选 | 非亭廊、实墙条窗、屋面断缺 | **P1** | FBD/屋顶 | 可能未真正进 FBD；屋顶几何不完整 |
| A4 大雁塔 | 形不像 + 生成错误提示 | **P1** | 地标质量 | 资料/生成器与路由；需日志 |
| A4 天坛 | 中段悬空缺块 | **P0/P1** | 地标放置 | 非法/跳过写入或分层失败 |
| A4 四合院 | 进深/面宽比例失调 | **P2** | 比例 | typology 默认尺寸 |
| C 玻璃走廊 | `E_BUILDING_CONTRACT` 能力缺口 | **P2** | 合同拒识 | 细长玻璃走廊不被当「建筑」 |
| C 白染色玻璃 | 100% 放置但全是石质地基 | **P0** | 材质/意图 | 材质角色漂移 + 误加地基 |
| C 铁栏杆 | `E_HOST_UNKNOWN` | **P1** | Assembly 宿主 | host_id 未解析 |
| C 橡木栅栏 | 变成石墙 + 自动补支撑 | **P0** | 材质/后处理 | 同材质漂移；悬空修复误伤 |
| 湖畔木屋 | 栏杆穿模；Undo 未完成 1087 | **P0/P1** | 几何 + Undo | 构件重叠；Build 路径部分撤销 |
| 退台石屋 | 墙体内收、窗错、屋顶不配 | **P1** | 质量 | 退台与屋顶尺寸不同步 |

## 硬门槛对照（Phase 1）

| 门槛 | 实测信号 |
|------|----------|
| 预览不改世界 | 未报告相反现象（待矩阵勾选） |
| Confirm≈预览 | 天坛/亭廊大量空洞 → 疑似预览与落地或非法跳过不一致 |
| Undo 完整 | 湖畔案「撤销未完成」→ **未过硬门槛** |
| 增量不重建 | A2 **未过** |

## A2 代码根因（已核对）

1. `ChatPanel` 写死 `promptMode = PromptMode.BUILD`，注释称不再显示模式按钮。  
2. 用户说「只改局部」仍发 `promptMode=BUILD` + 全量 `generate_llm_plan`。  
3. `BuildRequestProcessor` 仅在非 BUILD 时走 `editBuilding`；且该路径依赖 `PlayerSpecRepository` 的 **BuildingSpec**，而 LlmPlan 主干确认后通常**不**写入该仓库 → 即便切到 PATCH 也会「No current building spec」或落到错误路径。  
4. 原点来自 `BuildContextResolver` / 玩家位置；站在屋顶上时整栋以脚下为底心 → 与截图一致。

修复方向（本轮实施）：意图检测 → `PATCH`；`outputFormat=llmplan` 的 PATCH 走 `requestAiPlan`；拒绝 PATCH 请求落地为整栋 `mode=build`；原点优先最近记忆建筑。

## 日志核对（`run/logs/latest.log`）

日志路径已确认。关键证据如下（时间均为同日会话）。

### A4 · 大雁塔（15:09:54）

| 项 | 日志事实 |
|----|----------|
| 路由 | `typology=dense_eaves_pagoda`（typology_builder），**不是**强原型 `GiantWildGoosePagodaGenerator` |
| 保真提示 | `组合式生成「大雁塔」（保真度：风格化，非精确复刻…）` |
| 材质 | `deepslate_tiles` / `dark_prismarine`（偏「密檐塔」泛型，不像唐砖塔） |
| 质量 | 初始 `FLOATING` **517**；终态仍 **260** 悬空未修；补支撑 1045 |
| Confirm | 需 `/forma_confirm force` |
| 落地 | 计划 5043 / 放置 5006 / 跳过地形 37（99.3%） |

截图「腰部断开、悬空」与 **FLOATING 未修干净仍强制应用**一致；不是 Apply 阶段大量 `skippedIllegal`。

### A4 · 天坛（15:12:23）

| 项 | 日志事实 |
|----|----------|
| 路由 | `typology=radial_terrace_hall`，`reference_landmark=temple_of_heaven` |
| 材质回退 | `Material fallback: marble_lacquered_wood_glazed_tile -> minecraft:bricks` |
| 质量 | 初始 `FLOATING` **1339**；终态仍 **726**；`DUPLICATE_POS` **1464** |
| Confirm | 同样 `force` |
| 落地 | 6716→6700（99.8%），跳过地形仅 16 |

视觉「柱圈悬浮、中段空」主因是 **生成几何本身悬空柱过多 + force 放行**，不是放置器大量拒写。

### A3 · FBD（14:59 / 15:05）

- `FBD validation complete: repairAttempts=0, valid=true` → **程序路径已进**，不是没走 FBD。  
- 仍大量「修复悬空 / 补全支撑」→ 亭廊体量像实心盒 + 屋顶不连续，属 **FBD 原语/坡顶展开质量**，不是路由失败。

### C / 湖畔 Undo（15:25）

```
History undo conflict … expected=oak_fence[…south=true…] actual=…
History redo conflict … expected=tall_seagrass … actual=water
```

栅栏连接态冲突（未对栅栏做 soft-match，符合契约）+ 水域方块与快照不一致 → 对应「撤销未完成」。

### 能力缺口（与截图一致）

- 玻璃走廊：`E_BUILDING_CONTRACT`（req_length/width vs `glass_corridor`）  
- 铁栏杆：`E_HOST_UNKNOWN`（`railing_post_*` → host `iron_railing_wall`）

## 重测会话 · 16:19–16:45（对照截图）

| 时间 | 用例 | 日志结论 | 截图对照 | 归类 |
|------|------|----------|----------|------|
| 16:22 | 9×7 小木屋 | `E_GENERATION_AUTHORITY_CONFLICT`：`generation_authority=COMPONENTS` 但路由 hint=`ASSEMBLY`（`cabin_assembly`） | 能力缺口红字 | **P0 路由权威** |
| 16:24 | 增量开窗 | 客户端已判 PATCH；编排返回 `mode=patch` + `FACADE_WINDOWS`；**无**可用 `patch.blocks[]` → `patch_expected_rebuild_rejected`（安全拒绝，未整栋重建） | 红字要求 patch.blocks | **P0 PATCH 载荷契约**：提示允许 components[]，落地只认 blocks[] |
| 16:27 | FBD 3 亭 | `FBD validation valid=true`；终检修悬空 21、重复 217；落地 966→883 | 实心盒、无四角柱、坡顶残 | **P1 FBD 语义→几何**（路由已进，展开不像亭） |
| 16:30 | FBD repeat 4 | 同上 valid=true；修 36 / 支撑 92 / 重复 276；放置率 75.6% | 四座石盒+条窗，非柱廊 | 同 A3 |
| 16:32 | 大雁塔 | `dense_eaves_pagoda`；初始 FLOATING **516** → 终检 **全部修完**（fatal=0 error=0，+1781 支撑） | 方砖塔像了，**每层无券窗** | 悬空门控/预算 **已改善**；缺窗属立面属性未拟合 |
| 16:33 | 天坛 | `radial_terrace_hall`；初始 FLOATING **1031** → 终检修完（+1102 支撑） | 出檐过小、整体不像 | 悬空 **已改善**；比例/出檐属属性拟合不足（非写死复刻） |
| 16:37 | 橡木栅栏 10 格 | 计划 `WALL` + `wall_block=oak_fence`，却生成 **156** 块；截图无栅栏、有石台 | 「木栅栏没有生成」 | **P0 材质/构件角色**：栅栏当墙体展开 + 可能加地基 |

### 本轮相对上午的变化

- 大雁塔/天坛终检不再残留数百悬空、也不再依赖 force 叙事（悬空修复预算生效）。  
- 视觉问题从「悬空崩坏」转为「属性拟合不够」（窗洞、出檐、亭廊开敞）。  
- A2 安全闸有效（拒绝重建）；下一步应让 `mode=patch` + 局部 `components[]` 也能编译成 `patch.blocks`，或强制编排只出 blocks。

## 下一轮修复优先级（按 16:xx 日志）

1. **P0 已改（待重测）**：`generation_authority` — ASSEMBLY 形组件在 plan_meta=COMPONENTS 时推断为 ASSEMBLY，避免 cabin_assembly 冲突。  
2. **P0 已改（待重测）**：`mode=patch` + 局部增量 `components[]`（如 `FACADE_WINDOWS`）→ 编译为 BlockPatch → `PatchPreviewService` Ticket；MASS/ASSEMBLY 仍走重建拒绝闸。  
3. **P0 已改（待重测）**：`WALL`+`oak_fence`/railing → 线性栅栏；纯栅栏计划跳过地形石台 pad。  
4. **P1 已改（待重测）**：FBD 亭廊——`SHELL_BOX wallMode=COLONNADE`（或 openness≥0.65）出角柱/开间柱；提示与示例已改，勿用 SURFACE_PATTERN 假柱。  
5. **P0 续（待重测）**：增量开窗剥离 `component_request`，避免 `E_COMPONENT_REQUEST_CONFLICT`。  
6. **P1**：地标/描述拟合——层间券窗、出檐深度等从属性推导，不写死单体。
