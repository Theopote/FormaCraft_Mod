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

旧装配器 `StairAssembler` 只有注册、没有主流程调用，生产调用者为 0，已于 2026-11-15 **已删除**（连同整个 `server/skeleton/gen/assembler/` 子系统，见 `docs/refactoring/ARCHITECTURE_AUDIT.md` LEGACY-035）。

### 已删除 StairAssembler 的逐条行为比对（2026-11-15）

删除前把 `StairAssembler` 最后一次修改（`860fccb7`）的最终版本与现行实现逐条对照，结论如下。**三条历史修复均未被移植**，因为现行路径在设计上就不走那套语义：

| 历史行为 | StairAssembler 当时的做法 | 现行实现 | 结论 |
| --- | --- | --- | --- |
| **非零 offset 高程保留** | `explicit = offsetX/Y/Z 任一非零`；显式时 `start = ctx.origin.add(offsetX, offsetY, offsetZ)`，**保留 origin 相对 Y**；零 offset 时才落到 `ctx.getSurfaceY()` 地表高度 | `StraightStairComponentGenerator` 一律用 `origin = new BlockPos(rp.x(), rp.y(), rp.z())`（构件的 `relativePosition`），再由 `AssemblyCirculationOps.applyStairSystem` 以 `origin + 局部 x/y/z` 落块 | **已覆盖（换机制）**：现行路径**总是**保留构件声明的相对 Y，没有"零 offset 就吸附地表"的分叉。原行为里"零 offset 吸附地表"这一支在现行体系中**不再存在**——现行楼梯必须由构件给出显式 from/to 端点，没有隐式地表入口 |
| **direction 传递** | 从 `params.direction` 解析 `Direction`，**拒绝竖直方向**（`!dir.getAxis().isHorizontal()` 即返回空），并把该方向作为 `SemanticPlacementOp.of(pos, dir, STAIR_STEP)` 的 facing 下传 | 方向**不再作为独立参数**，而是由 from/to 端点差**推导**：`dx != 0 ? (dx>0?EAST:WEST) : (dz>=0?SOUTH:NORTH)`，随后写入楼梯方块的 `HORIZONTAL_FACING` | **已覆盖（换机制）**：竖直方向仍然被拒绝，但拒绝点从"解析参数时"移到了 `flightError`（`dx != 0 && dz != 0` 判对角、`abs(dy) > run` 判过陡）。**注意陷阱**：现行链路**不再支持 `direction` 参数**——`AssemblyComponentEmitter` 的 `STAIR_SYSTEM` 分支不拷贝它，`AssemblyComponentSchemaRegistry` 的参数白名单里也没有它。若 LLM 仍输出 `direction`，它会被**静默忽略**（不报错、也不影响方向），实际方向只由 from/to 决定 |
| **steps 预算** | `steps <= 0 \|\| steps > ctx.maxOps \|\| steps > 4096` 即拒绝 | `steps` 参数在现行体系中**不存在**（它是 `AssemblyStructuralOps` 的曲线采样参数，与楼梯无关）。现行楼梯的等价预算是：`flightError` 限制水平 run ≤ 4096；`applyStairSystem` 限制 `abs(dy) <= run`；预计操作数 `> 100_000` 拒绝 | **已覆盖（换机制）**：从"按步数计数"改为"按端点几何 + 预计操作数"三重限额。语义等价（防止超预算生成）但触发条件不同 |

**注意 `steps` 在现行体系里是另一个含义**：它仍作为参数存在，但只用于 `AssemblyStructuralOps` 的曲线采样（`samples`/`steps` 二选一），与楼梯无关；`AssemblySpecValidator` 对它的约束是 `requireIntMin(..., "samples", 2)`。看到 `steps` 不要以为在控制楼梯级数。

**仍然缺失、且确实是能力缺口的两项**（不属于上述三条修复，但由 `StairAssembler` 曾经提供）：

1. **地形相交拒绝**。`StairAssembler` 在零 offset 分支会逐级检查 `ctx.getSurfaceY(pos.x, pos.z) > pos.y`，一旦踏面低于地表就整段拒绝。现行 `AssemblyCirculationOps.applyStairSystem` **从不读取世界高度**——它虽然接收 `MetaAssemblyEngine.Context`（该 record 确实带 `ServerWorld world` 字段），但方法体内没有任何一处调用 `ctx.world().getTopY(...)` 之类的地形查询；并且 `StraightStairComponentGenerator` 调用它时传的 `ctx` 是 **`null`**。因此不会发现"楼梯埋进山坡"。
   - `GenerationContext.getSurfaceY` 仍然存在且可用，但只服务 `LinearPathLayout` 与 `BasePathGenerator`（道路/路径），与楼梯无关。
   - 换到 assembly 体系后，地形贴合由 `BareStairFitCompiler` 在**构件层面**处理（把已声明端点的梯段平移到宿主 MASS_MAIN 的可行区间内），而不是在生成时按地形拒绝。
2. **隐式入口解析**。`StairAssembler` 通过 `SkeletonHelper.getStairStart(ctx, skeleton)` 取"默认取骨架入口位置"（`getGatePosition(ctx, skeleton, "auto")`）。该 helper 连同整个 `assembler/` 包已删除，且现行体系**没有**"楼梯自动接到骨架入口"的概念——`StraightStairComponentGenerator` 在缺少 from/to 时直接抛错（"Straight stairs require explicit from/to endpoints"），只有带 `stair:straight_single_run` + `ascends_from_` 特征的历史兼容分支才会合成端点。

**结论**：`StairAssembler` 的三条历史修复在现行体系中**均有等价覆盖**，无需移植；真正未被承载的是**地形相交拒绝**与**隐式入口解析**这两项能力。前者已由 `BareStairFitCompiler` 在构件层部分补位（平移而非拒绝），后者是现行设计的有意选择（楼梯必须显式声明端点）。两者都不应通过复活 `StairAssembler` 来解决。

### 两项能力空白的处置方向（2026-11-15 设计结论，不实现）

用户 P1 结论明确要求：**不恢复 `StairAssembler`**，把这两项当作"生成能力设计问题"而不是"最近删除造成的回归"。处置方向记录如下，供后续实现时对齐，**本轮不写实现代码**。

**① 楼梯与真实地形的相交检查**

- **不在楼梯生成阶段做**。现行 `AssemblyCirculationOps.applyStairSystem` 是纯几何的：它只消费 from/to 端点，算宽度偏移、支撑、挖空与操作数预算，全程不读世界高度——`StraightStairComponentGenerator` 传进来的 `ctx` 甚至是 `null`。把地形查询塞回生成阶段，会让楼梯算子重新依赖 `ServerWorld`，破坏它"可在无世界上下文中做纯几何计算"的可测性与复用性。
- **放在最终预览校验层**。这与仓库既有的分层一致：`AssemblyCirculationConstraints` 已经在 assembly 收尾时按**真实世界坐标**复查占用/净空冲突，`BareStairFitCompiler` 已在构件层做端点平移。地形相交属于同一类"生成完成后、写入世界前"的空间合法性检查，应落在同一条链路上，而不是在梯段算子内部。
- **判据是"踏面低于地表"**，与旧 `StairAssembler` 的 `ctx.getSurfaceY(pos.x, pos.z) > pos.y` 语义一致；但**失败动作应是拒绝或修复，而不是静默平移**——平移是 `BareStairFitCompiler` 的既有职责（把梯段塞进宿主 MASS_MAIN 的可行区间），与"地形不允许这条梯"是两种不同语义，混用会让契约含糊。
- **依赖缺口**：地形查询需要世界上下文，而当前预览校验链是否持有 `ServerWorld` 需要在实现时确认；若不持有，需要先决定"校验在客户端预览还是服务端写入前"落点，再决定数据来源。

**② 从骨架入口自动推导楼梯起点**

- **保持显式端点契约为默认**。现行 `StraightStairComponentGenerator` 在缺少 from/to 时直接抛错（"Straight stairs require explicit from/to endpoints"），只有 `stair:straight_single_run` + `ascends_from_` 特征的历史兼容分支才合成端点。这是**有意的设计选择**：端点由规划期决定，生成期只负责落实。
- **"自动推导"属于规划期，不属于装配期**。若要支持"楼梯自动接到骨架入口"，正确的落点是**从骨架门位（gate）推导规划期的楼梯起点约束**（即在 LlmPlan / PlanProgram 阶段补一条 `from` 约束），而不是在生成期回头去查骨架。放错阶段会导致"生成器知道骨架、规划器不知道"的双向依赖。
- **不得复活 `SkeletonHelper.getStairStart`**。该 helper 随整个 `assembler/` 包删除（LEGACY-035），复活它等于重建一套没人知道何时调用的骨架装配器，与本轮迁移目标相反。
- **实施前置条件**：需要先明确"门位 → 楼梯起点"的选取规则（选哪个 gate、朝哪个方向起步、层间如何选层），这是一个**独立的规划契约**，不随本次清理一并落地。

现行的楼梯生成路径是 **assembly 元操作**而非骨架装配器：`AssemblyComponentEmitter` 把 `STRUCTURE`/`STAIRS_SYSTEM`/`STAIRCASE` 归一为 `STAIR_SYSTEM`，`MetaAssemblyEngine` 调用 `AssemblyCirculationOps.applyStairSystem` 落块，`AssemblyCirculationConstraints` 捕获真实世界坐标并做通行校验。层间楼梯目前**没有**接入 Skeleton 主流程：不解析 from/to 楼层端点，不自动连接平台或打开楼板。

## 验证与下一步

AssemblyCirculationOpsTest 验证精确宽度、两端踏面、升降/缓坡、旋转、支撑与挖空、非法路线及两种规划入口校验。SemanticBlockStateResolverTest 经真实 PatchExecutor 与可控方块访问验证完整属性落地。它们没有启动真实游戏世界。

第十四批已修复 assembly SHELL_BOX 最后清空内部删除楼板的问题，并验证先主体、后楼梯的洞口与上层踏面组合，见 [箱体楼板契约](SHELL_FLOOR_CONTRACT.md)。其他生成器、自动楼层入口/平台连接、构件依赖排序以及头顶碰撞、邻居更新、存档与玩家实际通行仍待检查。楼梯不能仅靠单个部件的局部测试认定可用。

## 同一 assembly 的最终组合检查（第十五批）

MetaAssemblyEngine 为每个 STAIR_SYSTEM 捕获实际输出的世界坐标：非空气位置为踏面/请求支撑的占用要求，空气位置为请求的净空要求。所有 ops 完成后，AssemblyCirculationConstraints 按最后操作复查这些位置。占用变空气，或净空被非空气填回时，抛出包含梯段编号、世界位置和原因的 Conflict，生成阶段拒绝结果，不向世界写入。只检查最终结果，暂时覆盖后恢复不算冲突。

这能识别上下梯段过近、后一梯段挖掉前一梯段、晚生成楼板封住净空等问题；不自动排序、移动梯段或补平台。carve=false 不登记净空要求，support=false 不登记额外支撑要求。重复兼容梯段允许。ASSEMBLY 构件入口将 Conflict 转为 E_ASSEMBLY_CIRCULATION_CONFLICT 的 capability gap；整栋 MetaAssemblyGenerator 入口继续传播失败。

校验只比较空气/非空气，不验证非空气是否是合适的踏面、方向是否匹配、碰撞形状是否可走，也不建立完整楼层可达图。第十五批只检查单次 assembly；第十六批扩展至 ComponentPlanCompiler 的跨构件合并与后处理，第十七批接入预览修复/裁剪后的最终检查，见 [完整计划契约](PLAN_CIRCULATION_CONTRACT.md)。其他路由、真实地形行为和后续世界改动仍需核查。它不会读取未生成位置的真实世界状态，因此不能替代全局通行验收。

ASSEMBLY 构件的 PlannedBlock → BlockPatch 转换现在使用 BlockStateStringUtil.fromState，保留楼梯 facing/half/shape/waterlogged、半砖类型和原木轴向，不再只保存方块 ID。坐标及操作顺序保持既有契约，空气仍输出 remove。AssemblyPatchStateTest 经真实 PatchExecutor 和可控访问验证属性与偏移落地；AssemblyCirculationConstraintsTest 验证冲突、合法平台组合、最终状态、方向与禁用选项，箱体组合回归也执行最终检查。没有启动真实 ServerWorld。
