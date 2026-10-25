# Phase 1 游戏内验收发现 · 2026-10-25

基线：`ff7039cd`。玩家实测 + 截图；本文件归档问题与根因分诊。  
**未宣称已在本机复现全部截图场景。** A4 专用服务器日志本轮未在开发机定位到 `latest.log`。

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

## 日志

请从游戏实例目录提供 `latest.log` 中含 `大雁塔` / `giant_wild_goose` / `temple_of_heaven` / `Capability` / `skipped` 的片段，以便核对 A4。
