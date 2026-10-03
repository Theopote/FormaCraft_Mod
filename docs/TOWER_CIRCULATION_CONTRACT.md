# 塔楼楼层与环形楼梯

更新：2026-10-03，第十九批。适用于整栋 TowerGenerator 及其构件适配路径；不代表 PlanProgram 或其他塔形生成器已经采用同一规则。

TowerGenerator 的原楼梯在 (+1,0)、(0,+1)、(-1,0)、(0,-1) 四个轴向位置间逐层跳转，水平相邻位置只在对角接触，且全部采用默认朝向。楼板按整除层高铺设，还可能多铺顶部楼板；floors 大于 height 时 floorHeight=0 导致除零。

现在高度仍至少 8、楼层数仍至少 1，但要求 floors <= height/3，不能容纳时在输出前拒绝。楼层高度为整数 height/floors，楼板位于 y=k*floorHeight、k=0..floors-1，包含地面层，不再多生成最高处的额外楼板。

启用楼梯且有多层时，TowerStairBuilder 在主体、楼板、屋顶与装饰全部生成后追加内部方形环路。环路经过 (-1,-1)、(0,-1)、(1,-1)、(1,0)、(1,1)、(0,1)、(-1,1)、(-1,0)，相邻采样水平距离一格。四角使用 floor 材质，边中点使用朝高处的橡木下半楼梯；每圈升高四格，转角不升高，最终停在最高请求楼层的角部平台。最高楼层距塔顶至少三格。

每个踏面上方两格输出空气，统一切开已有楼板；同一水平位置下一圈高四格，不会填住这两格净空。默认完整 floor 方块构成角部平台；自定义非完整 floor 材质的碰撞效果仍需核查。楼梯不自动通往屋顶，不加栏杆，也不验证入口与楼梯之间的整层路线。

生成器在本次输出内捕获和复查占用/净空要求，然后发布给当前捕获作用域；这里的 origin 是实际生成入口坐标。StructureGeneratorAdaptor 用嵌套作用域收集原始要求，交给 StructurePatchBridge，与方块一起减去生成入口 origin，转换为构件局部坐标，再发布给构件编译器。编译合并只加一次 slot.anchor，预览只加一次 planOrigin；不能直接发布原始要求后再加 slot。

桥接保留通行要求指定位置的空气操作，转换为 remove；其余批量清空仍沿用既有跳过策略。非空气方块保留完整状态属性，避免楼梯朝向、上下半部或含水状态在桥接时丢失。桥接后复查要求，生成或转换异常登记 E_STRUCTURE_GENERATION_FAILED，由完整构件计划拒绝部分输出。独立整栋预览入口仍未传递这些元数据，不能据此宣称所有塔楼预览都已保护。

TowerCirculationTest 新增九个执行案例，覆盖不可能的楼层数、请求楼板数、相邻踏步与转角平台、朝向、最终两格空气、真实 TowerGenerator 楼板洞口与屋顶保留，以及单层/关闭楼梯行为。测试初始化真实方块注册表，但没有启动 ServerWorld；邻居更新、真实碰撞与玩家通行未验收。

调用链审查确认 PlanProgramCompiler 使用 SkeletonExecutors 中注册的 SkeletonBuildService；LINEAR_PATH 默认走 LinearPathSemanticGenerator，其他类型才回退 SkeletonGeneratorRegistry。注册的 StairAssembler 并不因此成为 PlanProgram 的楼层连接入口。第十九批已改为任一骨架生成失败即拒绝整份结果；通行要求仍未接入该路径。StructurePatchBridgeTest 新增三个回归，覆盖完整状态执行、非零生成入口偏移、实际 TowerGenerator 要求与洞口，以及缺失净空拒绝；未启动真实 ServerWorld。
