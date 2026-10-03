# 完整构件计划的楼梯约束与预览状态

更新：2026-10-03，第十六批。本文适用于 ComponentPlanCompiler 主构件路径，补充 [楼梯契约](STAIR_CIRCULATION_CONTRACT.md) 的单次 assembly 校验。

## 约束如何进入完整计划

MetaAssemblyEngine 在本次 assembly 最终检查成功后发布梯段占用/净空要求。ComponentPlanCompiler 在每个构件生成期间开启局部捕获作用域；只接收该次同步生成的要求，结束或异常时恢复前一作用域。当前使用线程局部回调适配既有“生成器返回 List<BlockPatch>”接口，没有把生成器注册或静态全局表当作约束存储。嵌套捕获恢复有回归覆盖。

捕获位置已经包含 assembly 入口旋转和组件相对位置；编译器合并时与 patch 一样只加一次 slot.anchor，变为相对 plan.anchor 的坐标。此时不加 globalAnchor。传到 PostProcessContext 的 protectedClearance 是这些计划局部净空位置的不可变集合；预览时再加一次世界 planOrigin。

## 后处理与最终检查

回归复现了通用檐口在楼梯最高方块上方添装饰，填住明确净空的问题。DetailEnhancementPostProcessor 现在跳过落在 protectedClearance 内的新增装饰；它不删用户构件、不重新排序，也不改动原始挖空操作。其他后处理器如果改变通行位置，最终校验仍会拒绝结果。

全部构件合并与后处理完成后，AssemblyCirculationConstraints.validatePatches 按同一位置的最后操作检查梯段要求。后来构件挖掉踏面/请求支撑，或填回请求净空时，编译器设置 E_PLAN_CIRCULATION_CONFLICT，并返回空结果。没有 globalAnchor、未运行后处理的编译也执行该检查。只有成功输出 patch 的构件贡献约束。

ASSEMBLY 失败不能因其他构件还有输出而被忽略。编译器在已有 capability gap 时不返回部分建筑；主预览入口无论计划是否为空都先检查编译诊断，并报告能力缺口。这个策略只针对已登记的 ASSEMBLY/通行失败，其他构件现有异常处理尚未全部统一。

## 主预览与执行共用目标解析

LlmPlanPreviewBuilder 曾去掉方块属性再使用默认状态，还按 targetBlock 而非 action 判断 remove。现在委托 PlanPatchConverter，并与 PatchExecutor 共用 BlockPatchTargetResolver。place/replace 严格解析完整状态；remove 忽略 targetBlock、生成空气；未知操作、未知方块和非法/重复属性明确算作非法输入，不静默变石头或默认方向。

转换保持操作次序和重复位置，不在此折叠历史。非法预览操作计数并跳过，沿用现有不完整输出告警策略；通行相关位置的非法 patch 会先被最终约束拒绝。严格解析器从已有执行器抽出，执行器的高度、区块、同状态与写入成功统计规则保持不变。

## 证据与边界

ComponentPlanCompilerCirculationTest 使用具名测试生成器发布要求，执行真实编译合并与默认后处理，验证跨构件填堵/挖空、分开 slot、一次偏移、暂时覆盖恢复、混合计划失败及捕获生命周期；单独的后处理追加冲突案例验证最终检查。PlanPatchConverterTest 验证完整楼梯状态经主转换函数与真实 PatchExecutor 一致、remove 与非法输入语义。没有启动 ServerWorld 或真实玩家网络预览。

当前要求仍只比较空气/非空气，不建立楼层可达图或验证碰撞形状。没有 STAIR_SYSTEM 元数据的其他楼梯生成器、PlanProgram/BuildingMass 自身的通行要求仍未接入。第十七批已补充预览末端复查，详见下节；生成器接口的范围与通行要求仍需逐步变为显式结果。

## 预览末端复查（第十七批）

ComponentPlanCompiler.compileWithCirculation 返回不可变 Compilation，包含成功的 patch 与计划局部梯段要求；失败不会交付部分要求。主预览两处构件编译调用都使用该结果，将要求只加一次 planOrigin 后交给 BuildPreviewPipeline。旧 compile 与六参数 prepare 接口保留，未提供要求的其他入口行为不变。

调用链核实地坪平整与地基准备都排在建筑操作之前，因此重复位置由后面的建筑状态覆盖。回归验证该顺序在真实 BuildAutoRepair 去重后仍保留踏面与空气；没有为了假设中的覆盖问题改变地形生成顺序。

BuildPreviewPipeline 在自动修复、约束裁剪完成后，创建可交付结构之前复查最终 PlannedBlock。踏面/请求支撑变空气或消失、净空被填住或其操作被裁掉时，报告 FATAL / E_PREVIEW_CIRCULATION_CONFLICT，返回 delivered=false 和空结构。主预览不会把失败结果存为可确认预览。完全裁空仍使用既有 STRUCT_EMPTY_AFTER_CLIP。

自动修复的新增支撑仍遵循现有算法，发生冲突时拒绝结果，当前不自动搬移支撑或重建梯段。裁剪丢失要求即失败，即便真实世界该位置可能已为空气也不推断满足；不会通过放宽选区、轮廓或保护区规则恢复楼梯。

BuildPreviewCirculationTest 验证地形准备顺序、真实无世界修复的去重、模拟后续支撑填堵、真实选区裁剪删除踏面/空气、世界偏移一次和无元数据兼容。编译回归补充成功/失败 Compilation 与元数据不泄漏。没有真实 ServerWorld 地形采样、自动补支撑或玩家网络交付验收；覆盖问题的最终拒绝规则和接线由代码与可控回归验证。
