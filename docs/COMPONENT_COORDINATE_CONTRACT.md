# 构件坐标与连接契约

状态：维护中；核查更新：2026-10-03（第三批）。适用于 LlmPlan.components 经 ComponentPlanCompiler 编译的参数化构件；不替代 typology、assembly 或 PlanProgram 的内部坐标协议。

## 单位与坐标层次

`Dimensions` 构造顺序为 **width, depth, height**，每项表示方块数，不是末端偏移。矩形最小角为 `(x,y,z)` 时，宽深高为 `(w,d,h)` 的覆盖边界采用半开区间 `[x,x+w)`、`[z,z+d)`、`[y,y+h)`。

`relativePosition` 是构件在所属 slot 内的位置。生成器输出 slot 局部 BlockPatch；编译器再加一次 `slot.anchor`，得到相对 `plan.anchor` 的 patch。预览/执行层才换算到世界坐标。`globalAnchor` 还用于地形等世界上下文，不能重复加进组件 patch。

例如 plan.anchor=(100,64,-100)、slot.anchor=(-20,3,50)，则 patch=(-29,3,56) 的最终世界坐标为 (71,67,-44)。编译测试只检查 patch，不把世界原点混进局部几何。

## 中心与最小角

MASS 和 FOUNDATION 默认采用 XZ 中心锚点；Y 始终是起始高度。指定 `anchor_mode=min_corner` 或 `anchorMode=min_corner` 后，位置直接视为最小角。当前兼容解析以包含 corner 的值识别角锚点；本轮没有收紧既有参数。

中心转换统一使用 `ComponentFootprintUtil.resolveMinCornerOrigin`：

`minX = centerX - floor(width/2)`，`minZ = centerZ - floor(depth/2)`，Y 不变。

例如中心 (-3,2,11)、尺寸 (9,7,6)，最小角是 (-7,2,8)，最后一格是 (1,7,14)。编译器和 MassMainGenerator 共用转换规则，不各自维护一份计算。

坐标转换后必须将 `anchor_mode` 标为 min_corner。只改坐标、不改模式会导致下游再次减半个宽深，这是第三批修复的基础错位原因。

## 基础覆盖

同 slot 的中心锚定基础会贴到主体最小角，并保留自身 Y、尺寸和材质参数；转换后标记 min_corner。显式 min_corner 的基础已是有意放置的平台，保留其 XZ 和预留边距，不再次贴到主体角点。

随后 `ComponentFoundationEnforcer` 按当前策略（默认 2 格水平余量）扩展不足覆盖，保留原有覆盖与新增覆盖的并集。它不缩小用户声明的大平台，也不改变基础 Y 或高度。没有同 slot 主体时，现有 enforcer 仍会使用全部主体的包围盒并集，此兼容策略尚未重构。

因此“基础比主体宽”不自动表示错位；应检查主体覆盖、余量、显式平台位置及是否发生二次中心转换。

## 立面与门口

编译器将附属 FACADE_WINDOWS 贴到主体边界。wrap/perimeter 立面使用主体完整 width/depth，避免在旧构件尺寸对应的内部切片上放窗。

门口预留只作用于面对方向对应的主墙，其他三面继续保留开间窗。当前立面代码以 SOUTH=z最小、NORTH=z最大、EAST=x最小、WEST=x最大识别墙面；本轮保留此既有约定，不进行全系统朝向迁移。

窗口生成器只有在 Palette.has(WINDOW) 为真时才读取该映射；否则继续使用窗型的玻璃/栏杆默认值，不能把通用 palette.pick 的石头兜底当作窗户。

矩形外墙测试必须显式指定 rectangle。中式风格可能推断 cut_corners，切掉的角点没有墙体是形状策略，不能误判为锚点丢失。切角另有 footprint 测试。

## 屋顶连接

附属 ROOF 的 XZ 对齐主体最小角，连接高度采用 `mass.minY + mass.height - 1`，即顶层方块高度。飞檐以 overhang 扩展，不能通过把主体宽深错误解释为飞檐来重复扩展。

坡顶高度优先读取显式 roof_height/roofHeight/roofHeightBlocks，其次 Dimensions.height，缺失或无效时才用跨度推断。编译器推断的高度只保存在 Dimensions，不再注入会压过显式 ROOF 高度的重复默认参数。显式参数仍保留优先级。

平屋顶是连接面上的一层板，放在 ROOF.relativePosition.y；坡頂高度预算不应把它抬成悬空板。装饰、冠部与复杂屋顶的各自高度语义仍需分别核查。

## 验证边界

相关回归包含基础逐格覆盖、奇数尺寸与非零 slot 偏移、四向门口预留、窗户材质、平屋顶连接面和显式坡顶高度。`PatchTestSnapshot` 按顺序重放 patch，保留覆盖/删除的结果，但不模拟 Minecraft 执行权限、碰撞、地形或跳过策略。

完整回归状态见 [回归清单](refactoring/REGRESSION_BASELINE.md)。仍需要游戏内确认、地形场景及撤销验收，不能以纯 patch 测试宣称完成最终建筑质量验证。
