# LINEAR_PATH 道路布局契约

更新：2026-10-04，第二十一批。

LinearPathSemanticGenerator 与旧 LinearPathGenerator 现在共用 LinearPathLayout，避免语义默认路由与旧回退路由产生不同宽度和高度。生成前应用 params，宽度和长度沿用至少 1 的规则，朝向必须水平。

横向偏移从 -width/2 开始，恰好生成 width 列。奇数宽度以原点居中；偶数宽度多占负 right 方向的一列，保持原有起始边界并去掉旧实现多铺的末列。语义路径在宽度至少 3 时给最外侧两列追加高一格的 PATH_EDGE，旧路径只生成道路基底。

高度沿用旧路径的优先级：conformTerrain=true 或 FOLLOW_TERRAIN 时每行中心采样一次地表；否则 STEP_UP 每四行升一格；SLOPE 从原点按请求 height 总升降量插值并取整，单行不升降；FLAT 使用原点高度。conformTerrain 默认 true，因此要启用 STEP_UP/SLOPE，必须明确关闭贴地。横向整行使用同一个中心采样高度，不逐列采样。

在返回输出前检查全部行及操作预算。语义预算包含两列边饰，旧路径预算只计基底；超预算拒绝整条道路，不截断为部分结果。底界包含、顶界排除；顶界是世界 bottomY+height，不能把世界高度尺寸直接当绝对 Y。语义边饰也必须落在世界内，越界拒绝而非钳制高度。第二十二批已在主骨架流水线增加修饰后的预算与高度检查，详见 [骨架几何契约](SKELETON_GEOMETRY_CONTRACT.md)；单个修饰器内部的分配及多骨架累计预算仍待处理。

这只是道路几何高度策略。STEP_UP/SLOPE 仍输出道路基底方块，没有自动生成真实楼梯踏面、支撑或头部净空；FOLLOW_TERRAIN 可有相邻高度跳变，尚未构成可步行性保证。不可把此次高度一致性修复视为 PlanProgram 通行要求接线。

LinearPathLayoutTest 使用可控地表与世界上下界，执行两条实际生成器，覆盖四方向和奇偶宽度、台阶高度、正负坡度、单行、贴地优先级、预算、负底界、顶部边饰、非法朝向和偶数边饰位置。没有启动 ServerWorld；真实地表、碰撞与玩家通行仍需验收。
