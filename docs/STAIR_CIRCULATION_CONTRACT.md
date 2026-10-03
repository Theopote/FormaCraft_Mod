# 楼梯生成与通行契约

更新：2026-10-03，第十三批。本文描述已经接线的入口，并区分生成回归与真实通行验收。

第十五批补充：同一 assembly 的楼梯组合最终检查与 ASSEMBLY 构件完整状态桥接。

## 实际入口

assembly 的 STAIR_SYSTEM 经 AssemblyComponentEmitter / MetaAssemblyEngine 调用 AssemblyCirculationOps。ops 与组件图校验都拒绝不合法端点和坡度，组件别名为 STAIRS_SYSTEM、STAIRCASE。from/to 必须包含整数 x/y/z，表示局部踏面方块坐标；位置与楼梯朝向一起按 assembly 入口方向旋转，再加世界原点。

仅支持沿 X 或 Z 的直梯，水平长度不超过 4096，绝对高度差不能超过水平长度。对角、纯竖直和过陡路径明确拒绝，不再覆盖同一列生成假楼梯；转弯必须拆为多个梯段与显式平台。重合端点生成单列平台。含支撑与挖空的预计操作数超过 100000 时，在输出之前拒绝。

width 默认 2，运行时限制在 1..15，实际铺设恰好 width 格。横向偏移为 -floor(width/2) 到该值加 width-1，偶数宽度偏向负侧；X 向梯横向沿 Z，Z 向梯横向沿 X。沿水平路径逐格采样高度，相邻高度差最多一格。升降位置在当前列生成朝向高处的下半直楼梯，等高位置铺 floor；起点和终点均保留踏面。终点可能是楼梯，不保证自动生成平台。

support 默认开启，在每列踏面下方一格放支撑；它不延伸至地基。carve 默认开启，clearHeight（别名 clear_h）默认 3，限制 0..16，在每个踏面上方生成指定数量的空气操作。stairs 材质必须是楼梯，floor 必须非空气。其他构件之后仍可能覆盖踏面或净空；自定义半砖、透明地板的物理效果也需游戏内核查。这里的挖空范围不能作为整栋楼已可达的证明。

## Skeleton 状态与旧装配器

活跃 SkeletonBuildPipeline 的 SemanticBlockStateResolver 使用完整状态序列化，保留 half、shape、waterlogged、axis 等属性。SemanticPaletteResolver 将 STAIR_STEP 的水平 facing 应用到楼梯材质，缺少规则时使用石砖楼梯；非楼梯材质或竖直朝向明确拒绝。

StairAssembler 目前只有注册，没有主流程调用。该旧 API 已修复显式非零 offset 的高程保留、direction 传递和 steps 预算；没有偏移时以起点地表为基准，遇到高于踏步的地形整段拒绝，避免跳级。它仍不解析 from/to 楼层端点，不自动连接平台或打开楼板。不能将这项修复解释为 Skeleton 主流程新增了层间楼梯装配。

## 验证与下一步

AssemblyCirculationOpsTest 验证精确宽度、两端踏面、升降/缓坡、旋转、支撑与挖空、非法路线及两种规划入口校验。SemanticBlockStateResolverTest 经真实 PatchExecutor 与可控方块访问验证完整属性落地；StairAssemblerTest 验证旧 API 的偏移、地形和预算。它们没有启动真实游戏世界。

第十四批已修复 assembly SHELL_BOX 最后清空内部删除楼板的问题，并验证先主体、后楼梯的洞口与上层踏面组合，见 [箱体楼板契约](SHELL_FLOOR_CONTRACT.md)。其他生成器、自动楼层入口/平台连接、构件依赖排序以及头顶碰撞、邻居更新、存档与玩家实际通行仍待检查。楼梯不能仅靠单个部件的局部测试认定可用。

## 同一 assembly 的最终组合检查（第十五批）

MetaAssemblyEngine 为每个 STAIR_SYSTEM 捕获实际输出的世界坐标：非空气位置为踏面/请求支撑的占用要求，空气位置为请求的净空要求。所有 ops 完成后，AssemblyCirculationConstraints 按最后操作复查这些位置。占用变空气，或净空被非空气填回时，抛出包含梯段编号、世界位置和原因的 Conflict，生成阶段拒绝结果，不向世界写入。只检查最终结果，暂时覆盖后恢复不算冲突。

这能识别上下梯段过近、后一梯段挖掉前一梯段、晚生成楼板封住净空等问题；不自动排序、移动梯段或补平台。carve=false 不登记净空要求，support=false 不登记额外支撑要求。重复兼容梯段允许。ASSEMBLY 构件入口将 Conflict 转为 E_ASSEMBLY_CIRCULATION_CONFLICT 的 capability gap；整栋 MetaAssemblyGenerator 入口继续传播失败。

校验只比较空气/非空气，不验证非空气是否是合适的踏面、方向是否匹配、碰撞形状是否可走，也不建立完整楼层可达图。第十五批只检查单次 assembly；第十六批已将捕获要求扩展至 ComponentPlanCompiler 的跨构件合并与后处理，见 [完整计划契约](PLAN_CIRCULATION_CONTRACT.md)。预览随后追加的地形/地基、其他路由和后续世界改动仍需核查。它不会读取未生成位置的真实世界状态，因此不能替代全局通行验收。

ASSEMBLY 构件的 PlannedBlock → BlockPatch 转换现在使用 BlockStateStringUtil.fromState，保留楼梯 facing/half/shape/waterlogged、半砖类型和原木轴向，不再只保存方块 ID。坐标及操作顺序保持既有契约，空气仍输出 remove。AssemblyPatchStateTest 经真实 PatchExecutor 和可控访问验证属性与偏移落地；AssemblyCirculationConstraintsTest 验证冲突、合法平台组合、最终状态、方向与禁用选项，箱体组合回归也执行最终检查。没有启动真实 ServerWorld。
