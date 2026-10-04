# ASSEMBLY 楼梯：载荷与实际接续

更新：2026-10-04。根据 11:33 环形塔楼与 11:39 折返大厅请求中已读取的参数复现；夹具保留失败相关构件与参数，没有玩家坐标和 API 密钥。

## 根因与修复

- 只有文字参数的 `params.assembly` 曾遮蔽外层有效预设。Python 与 Java 现在一致：优先可执行内层；内层没有几何时读取外层可执行载荷，移除无关 `assembly` 元数据。两层都没有几何仍返回能力缺口。
- `spiral_watchtower` 生成扭转塔壳，不生成内部楼梯。楼梯请求不再自动套用此预设；旧日志的 `spiral_staircase + presetParams` 可转成楼板和明确梯段。此兼容转换不调用塔壳预设。
- 折返楼梯曾以高度 1 的 `SHELL_BOX` 表示踏步，违反高度至少 2 的规则。现有描述转换为两段反向 `STAIR_SYSTEM`、横向连接平台与顶部平台。Python 预校验也检查壳高度和直梯端点，避免明显非法参数到游戏端才报错。
- 单层圆形楼板曾被 CYLINDER 执行器最小高度 3 放大；执行高度现在与校验约定一致，允许 1。
- 楼板和其他构件先生成，含直梯操作的 ASSEMBLY 最后生成并开洞；实际踏步、支撑和净空继续进入最终方块校验。
- 平台位于最后一级踏步旁，保持相同踏步方块 Y，不覆盖最后一级楼梯方块。

## 支持协议

折返楼梯示例，参数在 `params.assembly` 中：

```json
{"stair_type":"switchback","width":3,"flight_rise":3,"flight_run":3,
 "landing_width":7,"landing_depth":2,
 "material":{"steps":"stone_bricks","landing":"oak_planks"}}
```

宽度 1–7；单段升高 1–32；行程不小于升高、最大 64；休息平台深度 2–8，宽度必须 `2*width+1`。首梯终点旁的休息平台跨越两条梯段，中间至少有一列完整方块。总升高为单段升高的两倍。主大厅需容纳梯段与平台；超出大厅的旧锚点会移动到室内，不能容纳时返回能力缺口。上层楼板按室内范围补齐，净空由最终梯段开洞。

圆形塔楼内部环绕楼梯示例：

```json
{"kind":"ring_staircase","radius":6,"height":15,"floorLevels":[5,10],
 "floorMaterial":"smooth_stone","direction":"clockwise","roof_access":false}
```

采用圆形墙内的**方形螺旋网格路径**，各段面相邻、转角为平平台，避免对角跳跃；不是连续曲面或任意半径的数学螺旋。仅支持一个方块宽的塔内梯段，半径 4–32，高度 8–128，最多 16 个严格递增楼层；楼层间隔至少 3，最高占用层距屋顶至少 3。终点停在最高占用楼层，屋顶访问需要另给明确梯段。主塔壳尺寸与明确半径、总高度同步，圆形楼板在梯段前生成。

已有明确 `STAIR_SYSTEM` 的 ops 保持权威，不由文字参数重写。不符合支持契约的描述返回 `E_CIRCULATION_DESCRIPTION_INVALID`，不能用默认房屋或塔壳替代。

## 验证边界

Python 回归覆盖旧参数转换、幂等、载荷优先级、非法描述、壳高度预校验和提示词。两份生成操作夹具同时由 Java `LoggedCirculationGeometryTest` 执行，检查楼板厚度、洞口、两段踏步、跨梯段平台及最终净空。

`MetaAssemblyEngine.executeGeometry` 只用于无需地形和调色板查询的几何验证，允许 CYLINDER、STAIR_SYSTEM、CLEAR_BOX 和原点栈操作；拒绝地形操作。正常游戏入口仍使用 ServerWorld。

仍需游戏验收：重启客户端和后端，用原提示词重新生成，步行通过平台到上层，检查玩家碰撞、南侧入口、预览确认与撤销。自动化通过不能替代游戏内玩家移动验收。
