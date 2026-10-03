# 完整回归恢复清单

状态：维护中；更新：2026-10-03。

第二轮已消除 Java 测试编译错误。当前全套 JUnit 已通过；不跳过、不禁用，也不将失败改成软告警。失败需要对照协议/建筑几何判断是测试过时还是实现错误，不能直接改断言迎合输出。

第十三批最终完整检查：403 个测试执行，全部通过，0 失败、0 跳过；完整 check 通过。本批新增 22 个楼梯与状态保留案例，18 个 assembly 案例及其他资源校验通过。

## 本轮修复

- 旧 LlmPlan 构造调用迁到具名 fixture，保留非古典 guard、capability_gap、distinguishing_features 等字段的含义。
- 当前 PromptAssembler、Slot、ComponentSocket、ComponentCategory API 的测试调用；响应样例补齐 kind，使用有效 build 模式，同时验证拒绝无判别字段、未知 kind 和旧 layout 模式。
- FormacraftMod 工具 RegistryKey 改用 RegistryKeys.ITEM，避免单纯引用日志就启动活注册表。
- AlignmentContractParser 直接解析 Map，保留 snake_case/camelCase 的轴和开间信息；不再依赖 Gson 识别 Jackson 注解。
- 缺失或空 socketPlacements 也检查宿主 socket 的原点缺失；匹配坐标不告警。
- 显式自由几何请求不注入固定地标模块清单；测试只检查实际注入块，避免把系统规则中的名称引用误判为注入。
- 内置建筑类型解释器由服务端初始化，注册测试覆盖重复初始化及大小写/空格规范化。

## 下一批检查

现有 Java 回归已恢复通过，下一步审查真实方块执行、历史事务及最终建筑空间质量，具体顺序见 [测试与建筑验收](../TESTING_AND_BUILDING_ACCEPTANCE.md)。整体包围盒楼层基准、偶数开间镜像、冠部预算、跨入口路由仍是已登记技术债，不能因 check 通过而视作全部解决。

本轮未修改 Python 规划器；后端完整质量门仍需具备 requirements.lock 依赖环境。游戏内预览、确认、撤销、Memory 更新与多玩家验收仍待执行。

## 第三批完成：构件拼接

- 主体原点计算统一使用 ComponentFootprintUtil；中心锚定基础贴到主体角点后明确标为 min_corner，消除二次中心偏移。
- 显式 min_corner 平台位置保持不变，再由 FoundationEnforcer 补齐不足覆盖与 2 格余量。测试检查最终基础平面的逐格覆盖。
- 窗口材质只有在 palette 存在 WINDOW 映射时才使用；缺映射继续窗型回退，避免生成石头“窗户”。
- wrap 立面的门口预留仅作用于面对方向对应的主墙；测试覆盖四个方向，其他三面中间开间保留窗口。
- 平屋顶落在连接面，坡顶使用显式 roof_height 或 Dimensions.height；编译器不注入与显式高度竞争的重复默认参数。
- 修正切角屋顶测试的 width/depth/height 顺序、矩形锚点测试的形状前提、开间测试中未关闭门口预留的矛盾前提，没有降低几何标准。
- 新增具名的 [构件坐标契约](../COMPONENT_COORDINATE_CONTRACT.md)，覆盖 slot/plan/world 层次、半开边界、锚点和连接面。纯 patch 重放不模拟真实执行器的权限/跳过规则。

## 第四批完成：冠部、线脚与开间

- 旋转体高度统一为方块层数，六层覆盖 baseY..baseY+5；保留零半径轴心尖顶，单层只采样底部。新增单层、负坐标、零半径连续轴心及零高度案例。
- 楼层顶圈包含最高完整楼层：高度 12、层高 4 对应 3、7、11。
- 线脚测试补齐实际 patch 的 Y 和 XZ 包围盒，同时验证顶圈生成、内墙及底层不变；原来仅输入一层薄片无法表示完整楼层上下文。显式屋檐规则仍验证实际最高层。
- 绝对曲线点的旧断言从 y=1 修正为 1/6，并验证半径、其他高度按最大值缩放。
- 进深重复单元独立使用进深节奏：10 格的柱轴 3、7，与 13 格宽度的柱轴 4、8 分别验证，窗轴及内部位置不能当作柱位。
- [构件坐标契约](../COMPONENT_COORDINATE_CONTRACT.md) 补齐这些定义，并记录仍待处理的整体包围盒楼层基准、偶数跨度镜像、冠部推断尺寸/4000 patch 上限/segments 未生效问题。本轮没有改变 assembly 曲面协议。

## 第五批完成：路由迁移、八边形与统计

- LandmarkRoutingPolicy 原本识别已迁移的鸟巢/哥特类型，却在最终提示中要求旧 MODULE。本轮改为 STRUCTURE + typology 候选；明确点名保留比例参考，泛型不自动添加 reference_landmark，最终遵守研究结果。同步清理目录中的旧体育场建议及过时注释。
- 固定模块目录测试改为验证有效 ID、唯一性及迁移/研究条目排除，删除旧数量下限；指标测试验证旧鸟巢转 stadium_bowl、保留 pantheon 的模块路径，已迁移哥特 ID 不再算精确 MODULE。
- JSON defaults 数值按 Number 验证，保留 levels=13 的数值约束，不依赖 Gson 的 Integer/Double 表示。
- 八边形 NORTH/WEST 修复重复取负；八面索引按周向排列，使 index+4 真正对应对面，斜面凹龛落在切角边界。新测试检查半径 1..10 的边界、八点唯一性与对面关系。
- 执行结果样例修复漏记的 26 个越界项，验证 total=placed+skipped；额外验证未归类余量与空计划。未宣称完成真实世界执行器验证。
- 新增 [地标与建筑类型路由契约](../ROUTING_AND_TYPOLOGY_CONTRACT.md)，并更新开发者入口、文档索引和八边形坐标契约。Python、知识卡与兼容入口的路由一致性仍需继续收敛。

## 第六批完成：注册表环境与状态桥接

- 接入与生产 Loader 版本一致的官方 fabric-loader-junit；依赖注册表的测试显式初始化 Minecraft，真实运行取代普通 JVM 中失败的 Bootstrap 尝试。
- 半球与 dome 别名统一为上半球，旧测试改为核查当前定义，新增旋转 180 度的下半球镜像案例。保留实际实现，没有反转已使用的穹顶原语。
- TypologyPatchBridge 复用 BlockStateStringUtil，保留 facing、half、shape、waterlogged 等属性，不再只输出方块 ID。测试验证负偏移及倒置西向含水楼梯；缺少 targetState 明确拒绝，取消石头静默回退。
- CI 保留完整验证报告；文档记录官方测试运行器、缓存要求、验证范围和下一轮验收顺序。

## 第七批完成：实际写入与历史差异

- BuildTask 和 PatchExecutor 遵守 setBlockState 返回值；同状态跳过和写入失败分别累计，失败不能进入成功统计或 BuildTask 撤销记录。
- 世界边界与区块检查移到读取之前；BlockMutationAccess 保留生产写入行为，并支持可控拒绝/边界测试。
- Patch 严格接受 place/replace/remove；拒绝未知动作、方块、属性、非法/重复属性及不完整状态文本，保留合法显式空气和楼梯属性。
- Patch 历史按真实最终 before/after 差异过滤，没有实际变化时不推事务或清空 redo；Memory 正反向分析使用最终/原始状态，不简单翻转动作。
- 普通建造有越界、未加载、非法目标、失败或未归类结果时不注册完整建筑 Memory，实际成功改动仍保留撤销。
- 新增 [方块执行与历史契约](../WORLD_MUTATION_CONTRACT.md)，明确部分恢复失败、跨维度及原子性问题仍待修复；测试不替代真实服务器邻居更新和方块实体验收。

## 第八批完成：可重试撤销与世界约束

- 共用 PatchReplayHistory：失败/未加载/冲突保留待办，成功位置不重复恢复，整条完成后才移栈；部分 redo 的新分支保留已完成部分的可撤销记录。
- 世界身份使用原 ServerWorld 对象，跨维度及另一服务器会话拒绝写入；SERVER_STOPPED 清理历史和建造服务引用。
- 后续修改与事务预期状态不一致时拒绝覆盖；同位置重复建造恢复最早 before，已是目标状态不再次写入。
- Patch Memory 只分析当前恢复实际变化；网络及命令输出部分结果、剩余数量、失败和冲突信息。旧整数 API 未完成返回 -2，不误报成功。
- 9 项新增回归验证部分 undo/redo 重试、世界身份、冲突、空事务、分支、阻塞位置和普通重复位置恢复。普通建造 Undo 的 Memory 更新、异常补偿、方块实体和邻居更新仍待处理。

## 复现

仓库根运行 `./gradlew.bat check --offline`。缓存不完整时需允许 Wrapper/依赖下载；`--offline` 不会阻止尚未安装的 Wrapper 自身下载。

完整 JUnit 报告位于 `build/reports/tests/test/index.html`。定向测试通过不能作为完整 check 通过的证明。后续每次修复同步本文与 [架构审计](ARCHITECTURE_AUDIT.md) 中的验证结果。

## 第九批完成：历史恢复异常

逐位置处理运行时异常，成功位置仍汇总差异；写入后异常保留待确认源状态，重试补报实际差异。未确认的部分 redo 在新分支中仍可撤销。新增 4 个失败注入测试，完整 check 通过。第九批时普通建造 Memory 同步尚未实现，空间查询缺少维度、Patch 创建记忆默认主世界；维度问题已在第十批修复。建筑记忆仍缺少与普通撤销关联的事务身份，后续必须明确归属和局部恢复语义。

## 第十批完成：Memory 维度隔离

空间/最近查询显式接收维度，Patch 分析与 mutation 传递执行世界维度，新建记忆使用该维度。更新已有 UUID 前再次校验维度；提示词所有候选按当前维度过滤。未登记位置的 remove 不创建建筑记忆。新增 6 个回归测试，355 个测试全部通过，完整 check 通过；旧数据修复、普通建造事务 UUID 关联及真实游戏验收仍待处理。详见 [Memory 契约](../MEMORY_TRANSACTION_CONTRACT.md)。

## 第十一批完成：普通 Undo 记忆关联与存档隔离

registerBuilding 返回 UUID 关联到 UndoEntry，部分恢复和完成写入同一事务的绝对进度，不删除整栋记忆或覆盖 Patch 元数据。记忆保存失败回退缓存并保留待保存更新，下次同玩家撤销命令重试；服务器停止时清空运行时状态。存储改为当前存档目录，使用临时文件替换完整 JSON；旧全局目录不自动迁移。新增 13 个回归（UndoService 7、记忆更新 3、存储 3），368 个 Java 测试全部通过。真实游戏、gene 逆向恢复及持久化补偿仍未验收，见 [Memory 契约](../MEMORY_TRANSACTION_CONTRACT.md)。

## 第十二批完成：主体楼层基准与后处理几何保留

成功生成的 MASS 范围和一次 slot 偏移进入 PostProcessContext。楼层收边按当前主体底部、跨度与层高定位；基础/屋顶不再参与该路径的层高包围盒，重叠主体跳过不明归属位置。只有最后候选操作消耗替换预算，已删除的旧 patch 不影响兼容范围。修复角柱/腰线覆盖已生成倒置楼梯、台阶、窗/门，以及已删位置被旧占用记录填回；材质变化只匹配完整方块 ID。新增 13 个回归（主体规则 8、完整编译 2、几何保留 3），381 个测试全部通过、无跳过，完整 check 通过。

第十二批时实体楼板、楼梯连续性/净空、复杂主体真正外边界及无 MASS 元数据入口仍待检查。楼梯局部生成与状态问题在第十三批处理，组合通行仍待验收，详见 [坐标契约](../COMPONENT_COORDINATE_CONTRACT.md)。

## 第十三批完成：实际楼梯入口与完整状态

调用链确认 StairAssembler 仅注册、没有主流程调用。实际 assembly STAIR_SYSTEM 修复偶数宽度多铺、上行末端缺踏面、支撑/挖空与踏面错位；非法端点、对角/过陡/超长路径在输出前拒绝，ops 和组件图校验给出 E_STAIR_FLIGHT_INVALID。运行时限制预计输出预算。Schema 补充实际支持的 clear_h、supportMaterial。

活跃 Skeleton 状态解析使用完整属性序列化，STAIR_STEP 设置水平朝向并校验材质。旧 StairAssembler 保留显式非零偏移高程、传递方向，限制 steps，地形挡路整段拒绝。新增 22 个回归（assembly 15、状态落地 4、旧装配器 3），403 个测试全通过，完整 check 和 43/23/37 项资源校验通过；架构检查 20 处既有技术债、0 新增，检查器 2 个测试通过。

没有启动真实游戏世界；楼板洞口、跨层平台、组合后净空与邻居更新仍未验收。对角梯段现在明确拒绝，需要拆为轴向梯段与平台；不能将旧装配器修复视为主流程接线。详见 [楼梯契约](../STAIR_CIRCULATION_CONTRACT.md)。
