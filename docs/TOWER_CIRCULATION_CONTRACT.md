# 塔楼楼层与环形楼梯

更新：2026-10-03，第十八批。适用于整栋 TowerGenerator；不代表 PlanProgram 或其他塔形生成器已经采用同一规则。

TowerGenerator 的原楼梯在 (+1,0)、(0,+1)、(-1,0)、(0,-1) 四个轴向位置间逐层跳转，水平相邻位置只在对角接触，且全部采用默认朝向。楼板按整除层高铺设，还可能多铺顶部楼板；floors 大于 height 时 floorHeight=0 导致除零。

现在高度仍至少 8、楼层数仍至少 1，但要求 floors <= height/3，不能容纳时在输出前拒绝。楼层高度为整数 height/floors，楼板位于 y=k*floorHeight、k=0..floors-1，包含地面层，不再多生成最高处的额外楼板。

启用楼梯且有多层时，TowerStairBuilder 在主体、楼板、屋顶与装饰全部生成后追加内部方形环路。环路经过 (-1,-1)、(0,-1)、(1,-1)、(1,0)、(1,1)、(0,1)、(-1,1)、(-1,0)，相邻采样水平距离一格。四角使用 floor 材质，边中点使用朝高处的橡木下半楼梯；每圈升高四格，转角不升高，最终停在最高请求楼层的角部平台。最高楼层距塔顶至少三格。

每个踏面上方两格输出空气，统一切开已有楼板；同一水平位置下一圈高四格，不会填住这两格净空。默认完整 floor 方块构成角部平台；自定义非完整 floor 材质的碰撞效果仍需核查。楼梯不自动通往屋顶，不加栏杆，也不验证入口与楼梯之间的整层路线。

生成器在本次输出内捕获和复查占用/净空要求；这里的 origin 是实际生成入口坐标。尚未把要求发布给构件编译捕获，因为整栋转构件的 StructureGeneratorAdaptor 还需要对要求执行与 patch 相同的坐标转换。不能直接发布后再加 slot，否则可能重复世界偏移。预览修复/裁剪后的要求保护仍只覆盖已经接线的 ASSEMBLY 路径。

TowerCirculationTest 新增九个执行案例，覆盖不可能的楼层数、请求楼板数、相邻踏步与转角平台、朝向、最终两格空气、真实 TowerGenerator 楼板洞口与屋顶保留，以及单层/关闭楼梯行为。测试初始化真实方块注册表，但没有启动 ServerWorld；邻居更新、真实碰撞与玩家通行未验收。

同批调用链审查确认 PlanProgramCompiler 使用 SkeletonExecutors 中注册的 SkeletonBuildService；LINEAR_PATH 默认走 LinearPathSemanticGenerator，其他类型才回退 SkeletonGeneratorRegistry。注册的 StairAssembler 并不因此成为 PlanProgram 的楼层连接入口。PlanProgram 逐 skeleton 捕获异常后继续合并，可能交付部分结果，尚未修复；后续应先明确失败与通行要求协议，再接入最终预览检查。
