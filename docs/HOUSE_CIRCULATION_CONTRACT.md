# 两层住宅：墙体、楼板与直楼梯

更新：2026-10-04。对应游戏日志 `2026-10-04-2.log.gz` 的 01:31 住宅请求。

## 已定位的问题

- `base_plinth` 默认装饰曾用下半砖替换整圈墙体，墙体上半格为空。默认基座带现在使用完整方块；显式请求的半砖规则仍可使用。
- 主体生成器曾把所有外墙单元当作装饰，用 DECOR 材质覆盖明确的石砖墙。装饰现在限于顶部和角部，使用墙体强调材质。
- `MASS_SECONDARY + extrude_mode=plate` 曾按高体块生成；现在生成单层楼板，编译器也将其几何高度规范为 1。
- 日志中 `STRUCTURE + stair:straight_single_run` 输出 0 方块后仍成功。现在具有独立执行路径，无法执行的楼梯返回 `E_STAIR_COMPONENT_INVALID`，不交付部分建筑。

## 可执行协议

直楼梯可以使用 `STRUCTURE` 和 `stair:straight_single_run`，例如：

```json
{
  "component_type": "STRUCTURE",
  "relative_position": {"x": 1, "y": 1, "z": 1},
  "dimensions": {"width": 3, "depth": 9, "height": 6},
  "features": ["stair:straight_single_run"],
  "params": {
    "from": {"x": 1, "y": 0, "z": 0},
    "to": {"x": 1, "y": 5, "z": 6},
    "width": 3,
    "stairs": "minecraft:oak_stairs",
    "floor": "minecraft:oak_planks",
    "clearHeight": 2,
    "landing_length": 3
  }
}
```

端点相对构件的 `relative_position`，表示踏步方块坐标。宽度以路径中心为基准，偶数宽度也严格保留指定格数。终点 Y 必须与二楼楼板 Y 一致；平台从终点沿行进方向延伸，共 `landing_length` 行。宽度 1–15，净空 2–16，平台长度 1–16，水平行程至少等于升高，最多 4096。复用 ASSEMBLY 的直楼梯几何执行器和 100000 操作预算。

楼板使用 `MASS_SECONDARY`，`params.extrude_mode=plate`、`material=oak_planks`、`anchor_mode=min_corner`，高度为 1。编译器先生成其他构件，再生成直楼梯；楼梯会实际开洞、清除踏步及平台上方净空。保护信息继续进入后处理和预览最终校验。

旧日志仅在同时明确 `stair:straight_single_run` 与 `ascends_from_north_low_end_to_south_high_end` 时可按包围盒转换，升高取 `height-1`、末端保留三行平台。其他文字楼梯不得猜测几何，必须提供端点或可执行 ASSEMBLY 操作。曲线、转折、螺旋楼梯应使用专用生成器或多段 ASSEMBLY，不能作为这个直楼梯协议发送。

## 验证与游戏验收

`HousePromptRegressionTest` 重放脱敏的真实返回计划，检查侧墙连续、基座无半砖缺口、石砖材质、三格宽橡木踏步、三行平台与二楼楼板齐平、楼板洞口和净空；另检查乱序楼板、偶数宽度及无效楼梯拒绝。夹具在 `src/test/resources/regressions/two-storey-house.json`，没有玩家世界坐标或 API 密钥。

仍需游戏验收：重启后端和客户端，在空地用原提示词重新生成，从南门步行到二楼，检查整个墙周边、踏步头顶和洞口。自动化验证不包含玩家碰撞物理。已有错误建筑不会因更新自动修复，应撤销后重新生成。
