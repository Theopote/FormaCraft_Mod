# 架构审计 — 当前状态

状态：维护中；复核：2026-10-31。

历史分批记录已移至 [archive/ARCHITECTURE_AUDIT_batches_through_2026-10-25.md](archive/ARCHITECTURE_AUDIT_batches_through_2026-10-25.md)。以本页与门禁脚本为准，不以历史「债务 0」表述为准。

## 当前门禁

| 检查 | 命令 | 期望 |
|---|---|---|
| 包边界 + common 服务端类型冻结 | `python scripts/check_architecture.py` | exit 0；新增引用必须先消债再改基线 |
| StructureGenerator 冻结 | `python scripts/check_structure_generator_freeze.py` | register 调用数 = baseline |
| Panel 行数（软） | `python scripts/check_panel_line_budget.py` | 仅打印超标 |
| 生成入口 | `python scripts/check_generation_entrypoints.py` | exit 0 |

`config/architecture-debt.json` 现登记 common 内既有的 `ServerWorld` / `ServerPlayerEntity` / `MinecraftServer` 引用；**禁止新增**。包引用 `com.formacraft.server` 在 common 中应保持为 0。

## 未决项（优先）

- ThreadLocal 全面改为显式参数传参；世界只读快照后，计划编译可完全离开主线程
- `ai_planner.py` 继续按阶段拆分；删除已停用的 `_generate_*` 地标模板函数体
- Panel 超 400 行拆分（Settings / Chat / ComponentCapture）
- 双 JSON 库收敛（新代码优先 `common.json.JsonUtil` / Gson）；服务端硬编码中文文案迁语言文件（建造门控文案已走 lang）
- Memory 落盘与世界修改原子性；Undo 双轨统一（见 WORLD_MUTATION_CONTRACT）

## 已落地（2026-10-31 审查整改）

- `ExplicitDesignPolicy.isExplicitlyDisabled` 下沉 common，架构门禁恢复
- 后端默认 `127.0.0.1` + `FORMACRAFT_ORCHESTRATOR_SECRET` / `X-FormaCraft-Secret`
- `/models` 走 `url_safety`；日志打码 `searchApiKey`；健康检查缓存
- 聊天建造权限 / 限流 / 并发；`GenerationCompileContext` + `PlanCompileWorkers`
- 提示词所有权迁后端；Python 地标 BuildingSpec 模板调度已停用
- 能力矩阵见 [GENERATION_CAPABILITY_MATRIX.md](GENERATION_CAPABILITY_MATRIX.md)；边界见 [PYTHON_JAVA_PLANNING_BOUNDARY.md](../PYTHON_JAVA_PLANNING_BOUNDARY.md)

## 原则

AI 规划、确定性编译、BlockPatch 唯一写入。完成报告不能代替运行事实。
