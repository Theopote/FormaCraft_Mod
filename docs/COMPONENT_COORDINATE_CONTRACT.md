# 构件坐标与连接契约

状态：维护中；核查更新：2026-10-03（第四批）。适用于 LlmPlan.components 经 ComponentPlanCompiler 编译的参数化构件；不替代 typology、assembly 或 PlanProgram 的内部坐标协议。

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

平屋顶是连接面上的一层板，放在 ROOF.relativePosition.y；坡頂高度预算不应把它抬成悬空板。冠部的旋转体层数见下文；复杂屋顶连接仍需分别核查。

## 冠部曲线与高度

`ComponentCrownRevolveSolver.heightBlocks` 表示层数。六层冠部从 baseY 到 baseY+5，归一化曲线 y=0、y=1 分别落在首层、末层；单层只采样曲线底部。半径为零仍生成轴心一格，避免穹顶缺尖或曲线中段断层。非正高度不生成方块。

显式曲线点内部顺序为 `[radius, y]`；绝对坐标按各轴最大值分别归一化。例如最高 y=6 时，y=1 对应 1/6，半径 3、最大半径 4 对应 3/4，不能把第二个点直接当成顶端。

当前 CrownDimensions 优先使用 crown_radius/crown_height，缺失时从主体跨度推断；它并非直接使用传入 Dimensions.height。旋转体为逐格实心圆盘，segments 参数目前不影响该 component 路径。总 patch 上限 4000 可能截断大冠部；这些行为尚未统一到 assembly 的曲面协议，不应以本轮层数修复宣称所有冠部契约已经一致。

## 楼层线脚与重复开间

完整楼层的顶圈为 `floorHeight-1 + n*floorHeight`，只要小于建筑高度就保留。例如高度 12、层高 4 的顶圈为 3、7、11；最高完整楼层的顶圈不再被排除。局部内墙和底层不匹配外墙顶圈预设。

第十二批起，ComponentPlanCompiler 为成功产出非空 patch 的 MASS 主体记录 BuildingVolume，使用预处理后的 min_corner、Dimensions 和 slot.anchor（只加一次）。后处理范围为相对计划的半开区间 [min,max)，匹配外圈时使用 max-1；plan/world anchor 不再次加入该范围。范围不是从基础、飞檐或穹顶的整体包围盒推断。

DetailRulePostProcessor 按每个主体自己的水平边界、底部 Y 和层高应用 FLOOR_BOUNDARY、BASE_TOP、ROOF_EAVE 及局部绝对 Y 规则。层高优先为 plan.proportionHints.floor_height/floorHeight，其次当前主体 params，最后尺寸启发式（高度 >=8 用 4，否则 3）；不会借用计划中另一个主体的层高。ROOF_EAVE 以主体顶层为参考，屋顶尖不参与。位于所有主体之外的位置保持原样；多个主体范围同时包含的位置归属不明，跳过收边，避免依赖组件顺序猜测。

后处理只装饰每个位置最后一条有效候选操作；被随后覆盖/删除的旧 patch 不消耗 2500 次替换预算。无主体元数据的兼容入口仍根据最终非空气操作的包围盒推断，已删除的离群点不影响该包围盒。三参数 PostProcessContext 构造器与 create(plan,anchor) 保留此兼容行为；只输入某层切片时仍无法恢复完整建筑基准。

BuildingVolume 只描述轴对齐主体范围，不携带每条 patch 的生成者身份或真实多边形外边界。同范围内屋顶/基础与主体重叠、切角/曲面边界、STRUCTURE/assembly 缺少 MASS 元数据和后续地形适应仍需继续检查。当前修复的是线脚/收边定位；实体楼板、屋顶逐格覆盖和可通行路径需要独立验收。

P-W-W-W-P 重复单元宽度为 5，当前使用整数中心 `axisMax/2`。宽度 13 的中央单元占 4..8，柱轴为 4、8；进深 10 的中央单元占 3..7，柱轴为 3、7。宽度墙和进深墙必须分别使用各自节奏计划。偶数跨度的整数中心有半格偏置，当前实现不保证绕几何中线严格镜像，后续需明确偶数跨度的对称策略。

## 验证边界

相关回归包含基础逐格覆盖、奇数尺寸与非零 slot 偏移、四向门口预留、窗户材质、平屋顶连接面和显式坡顶高度。`PatchTestSnapshot` 按顺序重放 patch，保留覆盖/删除的结果，但不模拟 Minecraft 执行权限、碰撞、地形或跳过策略。

完整回归状态见 [回归清单](refactoring/REGRESSION_BASELINE.md)。仍需要游戏内确认、地形场景及撤销验收，不能以纯 patch 测试宣称完成最终建筑质量验证。

## 八边形建筑类型细节

ChineseTypologyDetailUtil 使用 Minecraft 坐标方向：SOUTH=+Z、NORTH=-Z、EAST=+X、WEST=-X，与前述立面“面对墙面”的识别含义不同。轴向查询已经带符号，调用方不能再重复取负。

八面索引为 SOUTH、东南、EAST、东北、NORTH、西北、WEST、西南；index+4 为对面，支持负索引及八步循环。斜面凹龛取切角边界附近的整数点，必须位于八边形轮廓边缘。第五批修复此前四个轴面加四个角点的非循环顺序、重复取负及斜面落在轮廓外的问题。该变化会调整密檐塔各层凹龛位置，仍需游戏内检查凹龛与斗拱/檐层的组合效果。

## 半球原语与状态桥接

ShapeKind 中 dome、hemisphere、half_sphere 使用同一上半球定义：在以包围盒中点为原点的 canonical 空间保留 localY >= -0.25 的椭球体素。默认不是下半球；需要下半球时可绕 X 旋转 180 度。半球底面位于包围盒中部，调用方应考虑这一点，不能直接把它当成从 relativePosition.y 起铺的完整穹顶。

TypologyPatchBridge 将世界位置减去 worldBuildOrigin 后输出局部 patch，方块状态通过 BlockStateStringUtil.fromState 序列化，保留完整属性且稳定排序。不能只存方块 ID，否则倒置楼梯、朝向和含水状态会丢失。缺少 targetState 明确拒绝，不静默变成石头或空气。注册表/序列化错误继续传播，由上层生成失败路径处理。

## 楼梯连续性待办（第十二批审查）

Skeleton 的 StairAssembler 虽然计算 start=origin+offset，但随后用地表高度覆盖 stepY；显式 offsetY 因此不能保证楼层起点。每一级采用 max(baseSurface+i,currentSurface+i)，陡坡可能造成多格跳级；SemanticPlacementOp.of(pos,part) 默认 NORTH，组件的 direction 尚未传入输出朝向。该路径的 steps 也未检查 ctx.maxOps，from/to 参数只是注释约定，没有实际端点解析。下一批应先明确入口台阶与室内层间楼梯的起点、终点和坡度契约，再验证连续踏步、头顶净空、平台与楼板开口。当前未实现此项修复，不能仅因有 STAIR_STEP 输出就认定层间可达。

## 后处理的几何保留（第十二批）

DetailEnhancementPostProcessor 的角柱和腰线补丁仅替换普通墙体候选，保留楼梯、台阶、玻璃、门、栅栏门和铁栏杆。它的占用图按最后操作更新，remove/空气清除位置；基础墙先生成、再开窗或切空的旧记录不会被这些替换装饰填回。通用装饰仍使用整体范围，新增顶檐和每个构件的精确归属需后续核查。

MaterialVariationPostProcessor 只对完整 minecraft:stone_bricks、minecraft:cobblestone、minecraft:deepslate_bricks、minecraft:deepslate_tiles ID 做既有随机变化，不通过子串匹配楼梯/台阶/墙块。这样可以保留形状及 facing、half、waterlogged 等属性；当前没有给形状方块生成对应的苔藓/破损变体。
