# 整栋预览结果与通行要求

更新：2026-10-04，第二十批。

BuildRequestProcessor 的 BuildingSpec、更新 BuildingSpec、CompositeSpec 和 CitySpec 四个网络预览入口现在通过 StructureGenerationResult.capture 包住同步生成调用。结果同时保存 GeneratedStructure 与不可变通行要求列表，再显式交给 BuildPreviewDelivery 和 BuildPreviewPipeline。已有只传 GeneratedStructure 的交付接口仍以空要求兼容旧调用。

这里的方块与要求都是生成器实际输出的世界坐标。复合结构的子结构入口已包含 origin 与 sub.offset；不能在交付时再加 origin、slot 或子偏移。它与 StructurePatchBridge 转为构件局部坐标的路径不同，详见 [塔楼契约](TOWER_CIRCULATION_CONTRACT.md)。

捕获作用域只收集本次同步生成期间明确发布的要求；嵌套调用隔离自己的结果，成功或异常结束都恢复外层作用域。生成异常直接传播，不返回先前收集的元数据，也不影响下一次重试。没有发布要求的生成器仍返回空列表，不由全部空气或全部方块推断通行路线。

交付层直接传递世界要求，在自动修复与约束裁剪之后复查最终状态。后续子建筑、道路、修复支撑或地形变换如果删掉踏面、填住净空或移走要求位置，最终结果不能通过通行检查。当前不自动平移要求适应 DRAPE；若要支持该变换，必须让方块和要求采用同一变换并重新验证。城市内部生成失败后的整体处理策略尚未统一。

StructureGenerationResultTest 覆盖真实 TowerGenerator 的非零入口、真实 CompositeStructureGenerator 的子偏移、无世界自动修复去重、选区裁掉顶部净空、后续合并挖掉踏面、异常/嵌套捕获恢复和单层无要求。没有启动 ServerWorld、真实城市地形变换或玩家网络交付。

PlanProgram 的 SkeletonBuildService 仍先走 SkeletonSemanticRegistry，未注册语义生成器时回退 SkeletonGeneratorRegistry。当前路径没有发布通行要求；注册的 StairAssembler 并未成为该调用链的一部分。因此本批没有添加仅返回空通行列表的 PlanProgram 接口，也没有宣称保护这条路径。后续需先明确可执行楼层连接与踏面/净空的产出协议，再接入计划与预览。命令直接排队建造的 BuildExecutionService 也未经过本批预览交付接口。

同批审查发现 LinearPathSemanticGenerator 使用 -width/2..width/2 的闭区间，偶数宽度会多铺一列；它只按 conformTerrain 选高度，没有应用旧 LinearPathGenerator 支持的 heightPolicy。后续应先核对 LINEAR_PATH 的有效参数契约与两条路由的一致性，再确定哪些道路需要楼梯与净空，避免把普通平路全部当作楼层连接。
