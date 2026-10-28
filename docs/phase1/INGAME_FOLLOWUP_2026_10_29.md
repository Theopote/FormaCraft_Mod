# 2026-10-29 游戏日志回归

本轮依据 2026-10-28 22:13–23:48 的游戏日志（现已轮转到 run/logs/2026-10-28-2.log.gz、2026-10-28-3.log.gz）。原始返回方案保存在 src/test/resources/regressions/ingame_20261028_2221/。

## 修复

- 暗黑堡垒：此前 internal_openings 只有模型参数，无执行器消费。新增 DeclaredInteriorOpeningCompiler：仅在唯一、相接、同地坪的两栋建筑之间开通对应墙洞，保留地板和至少两格净高；最终检查洞口与室内接驳处。连接失败返回 E_INTERNAL_PASSAGE。外部门禁 entrance_type=none 仍保留，显式内部连接单独处理。
- 小木屋：模型附带 schema=1.0 的非标准 plan_program，原先仅检查 zones 非空导致错误路由。现在核对受支持 schema，并沿用已有的显式 components 降级路径；不吞掉实际有效 PlanProgram 的运行时错误。
- 佛塔：绑定的上下体块只允许在受限顶部接缝拼接，方龛可嵌入明确实心曲面塔身的上半部；无绑定、越界、普通居住壳体仍受覆盖检查。三格层高的窗带不再因窗台与竖条窗规则互斥而输出为空。
- 日式庭院：一格厚的庭院铺地避让已登记的步道，保留步道方块。

## 验证范围

Live2221RegressionTest 重放四份真实失败方案，并检查两层相接墙洞、连续地板、主厅到两塔的底层方块 BFS 路径、无相接目标的拒绝，以及日式庭院原步道材料。相关历史回归和 constraintParityTest 另行运行。

没有启动或修改游戏世界。修复不自动改变已经生成的旧堡垒；需重启开发客户端并重新生成验收。本轮仅修改 Java，不要求重启 Python 后端。

## 尚不能视为已完成的封窗请求

22:18:33 请求“去掉朝天井的木格窗”被拒绝；22:18:19 模型实际返回 window_style=pane、window_block=minecraft:glass_pane，与 wooden lattice 特征矛盾。不能把现存玻璃窗直接视为已确认木窗并扩大编辑权限。本轮没有宣称该封窗请求已修复。