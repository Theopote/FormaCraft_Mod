# 楼层修复设计权限复核（2026-11-21）

使用 `.cursor/skills/formacraft-design-authority-audit/SKILL.md`。

| Finding | File | Rule/branch | Owner | Classification | User impact | Remedy | Test |
|---|---|---|---|---|---|---|---|
| 局部楼层描述不能作为总层数 | PlanIntentFidelityRepair.java | parseFloorCount | 用户/LLM | FORMAT_REPAIR；不得升级为 DESIGN_OVERRIDE | “二层凸出的植物露台”曾覆盖模型的五层计划 | 保留局部描述，保留模型总层数；不按建筑名称选择模板 | PlanFloorCountIntentTest、CyberpunkStoreyRegressionTest |
| 楼板位置应执行已解析层高表 | MassMainGenerator.java | interiorFloorLevels | LLM 决定层高，Java 执行 | GEOMETRY | 首层加高时取模会与楼梯端点错层 | 使用 ResolvedFloorLayout 的楼层位置，不改变层数与层高 | MassStoreyFloorScheduleTest |

状态：本轮定向回归通过，游戏内验收待做。

- 当前静态检查：`check_design_authority_invariants.py`、`check_architecture.py`、`check_generation_entrypoints.py` 通过。
- Cursor 关闭后，离线 Gradle 定向回归成功。楼板测试 3 项、层数解析 2 项、赛博住宅回放 1 项，共 6 项通过。
- 实测计划为 12×12×20、五层、层高四格。旧回放在第 8 格高度的楼板内部占用为零。当前回放在第 4、8、12、16 格高度均通过内部覆盖断言，并在第 8 格以上生成楼梯踏步。
- 未增加预设、建筑类别枚举或风格默认值；本次复核未新增默认设计。
- 尚未执行本轮否定语句、陌生建筑、多模型、显式 preset 和游戏内验收；不宣称完整设计权限验收通过。
- 本次仅新增验收记录，未修改 `DESIGN_DECISION_INVENTORY.md` 的现有条目，保留工作区正在进行的权限迁移修改。
