# 完整回归恢复清单

状态：维护中；更新：2026-10-03。

第二轮已消除 Java 测试编译错误。当前能够执行全套 JUnit，仍有实际断言与运行环境失败；不跳过、不禁用，也不将失败改成软告警。失败需要对照协议/建筑几何判断是测试过时还是实现错误，不能直接改断言迎合输出。

第五批完整检查：327 个测试执行，324 通过，3 失败；18 个 assembly 案例通过。第四批为 322 项、11 失败，第五批消除其中 8 项失败并新增 5 个路由、八边形及统计案例。完整 check 仍失败。

## 本轮修复

- 旧 LlmPlan 构造调用迁到具名 fixture，保留非古典 guard、capability_gap、distinguishing_features 等字段的含义。
- 当前 PromptAssembler、Slot、ComponentSocket、ComponentCategory API 的测试调用；响应样例补齐 kind，使用有效 build 模式，同时验证拒绝无判别字段、未知 kind 和旧 layout 模式。
- FormacraftMod 工具 RegistryKey 改用 RegistryKeys.ITEM，避免单纯引用日志就启动活注册表。
- AlignmentContractParser 直接解析 Map，保留 snake_case/camelCase 的轴和开间信息；不再依赖 Gson 识别 Jackson 注解。
- 缺失或空 socketPlacements 也检查宿主 socket 的原点缺失；匹配坐标不告警。
- 显式自由几何请求不注入固定地标模块清单；测试只检查实际注入块，避免把系统规则中的名称引用误判为注入。
- 内置建筑类型解释器由服务端初始化，注册测试覆盖重复初始化及大小写/空格规范化。

## 下一批问题

| 领域 | 失败测试与核查方向 |
|---|---|
| 原语 | ShapeLibraryTest：半球朝向的协议定义 |
| Minecraft 测试运行环境 | PaletteLibraryTest、TypologyPatchBridgeTest：普通 JUnit 未启动注册表；尝试直接 Bootstrap 后又出现 remap jar 内部访问权限错误，需在 Fabric 测试运行环境中验证，不能简单启动 Bootstrap 或吞异常 |

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

## 复现

仓库根运行 `./gradlew.bat check --offline`。缓存不完整时需允许 Wrapper/依赖下载；`--offline` 不会阻止尚未安装的 Wrapper 自身下载。

完整 JUnit 报告位于 `build/reports/tests/test/index.html`。定向测试通过不能作为完整 check 通过的证明。后续每次修复同步本文与 [架构审计](ARCHITECTURE_AUDIT.md) 中的验证结果。
