# 几何质量反馈：结构化诊断首版

## 已接入

预览流水线沿用“初检 → 确定性修复 → 约束裁剪 → 最终复检”。
`BuildQualityIssue` 现在除 severity、code、message 外，提供：

- `phase`：initial / final；独立调用检查器时为 unspecified。
- `affectedCount`：该问题计数，不是坐标样本数；CHUNK_UNLOADED 按区块计数，其他已定位规则按方块或底层柱计数。
- `positions`：最多 8 个世界坐标 `{x,y,z}`；不包含方块实体 NBT、请求文本、API key。

悬空、世界高度越界、选区/禁区越界、重复写入和未加载区块提供坐标样本。
缺少坐标或状态的非法方块提供数量；尺寸差异等聚合规则暂不提供坐标，affectedCount 为 0。
受影响数量为 0 或空坐标都表示未提供证据，不代表该问题不存在。
当前坐标定位没有穿透 Patch 合并和地形处理到原始组件/FBD 节点，不能当成 AST 修改路径。

所有实体统计和尺寸检查以同一坐标最后写入为准，忽略最终 AIR 的几何包围盒。
原始写入的重复次数单独保留。非法方块先诊断，避免后续检查空指针。
全部清空后没有实体方块的建筑结果以 STRUCT_NO_SOLID 拒绝预览。

最终报告保留初检证据，历史项使用 INITIAL_ 代码前缀、initial 阶段和 INFO 严重度。
`activeDiagnostics()` 只返回非 INFO 的当前问题，历史错误不会重新触发强制确认。
最终残余 ERROR 仍要求原有显式 force，FATAL 始终拒绝。

日志示例：

```text
Quality diagnostic: phase=final code=FLOATING severity=ERROR affected=20 positions=[Position[x=0, y=80, z=0], ...]
```

## 修订边界

本阶段补齐几何反馈证据，不调用模型修改几何。已有 Java 编译诊断修订继续运行。
现有确定性支撑修复、净空处理、保护区与预算门禁继续适用。
入口堵塞没有通用可靠检测器；连通性支撑检查也不是承重求解器，不宣称完成这些验证。

下一阶段先建立方块 → 组件 → FBD 节点的可信来源映射，并为可改参数定义边界，
再把少量可证明安全的几何问题接入模型修订；位置样本不能直接授权改变尺寸、循环或地形。

## 自动化验证

GeometryDiagnosticsTest 覆盖最终写入统计、清空几何、非法状态、选区越界证据、
悬空证据上限与确定顺序、JSON 往返、修复后清除活动诊断。
GeometryHistoryTest 覆盖预览最终报告保留历史坐标、阶段，以及残余错误继续要求 force。
StructureSupportAnalysisTest 保留既有真实程序、方向、地形与修复回归。
游戏中需要重启客户端加载 Java 修改，再观察日志；本次自动化不替代游戏验收。
