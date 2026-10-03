# 完整回归恢复清单

状态：维护中；更新：2026-10-03。

第二轮已消除 Java 测试编译错误。当前能够执行全套 JUnit，仍有实际断言与运行环境失败；不跳过、不禁用，也不将失败改成软告警。失败需要对照协议/建筑几何判断是测试过时还是实现错误，不能直接改断言迎合输出。

第三批最终完整检查：320 个测试执行，303 通过，17 失败；18 个 assembly 案例通过。第二批为 314 项、22 失败，第三批消除其中 5 项失败并增加 6 个几何回归案例。完整 check 仍失败。

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
| 冠部曲线 | ComponentCrownRevolveSolverTest、RevolveProfileParserTest：高度用方块数还是末层偏移、零半径尖顶是否应生成方块、绝对高度归一化；现有测试含把 1/6 误写为 1 的可疑预期，先核查契约 |
| 楼层线脚 | ComponentFloorCorniceDecoratorTest、DetailRulePostProcessorTest、FloorCornicePostProcessorTest：楼层边界含不含顶层、局部 patch 高度与完整建筑高度的区别 |
| 立面开间 | HouseGeneratorUtilsRhythmTest：沿进深方向的柱轴测试仍失败；参数化窗户的 FacadeWindowsRhythmTest 已恢复通过 |
| 地标与建筑类型 | LandmarkModuleRegistryTest、LandmarkRoutingMetricsTest、TypologyRoutingMetricsTest：固定地标收紧和 typology 迁移后，旧模块数量/强制路由预期是否仍合法 |
| 类型/朝向/统计 | StructuralTypologyRegistryTest 的 Number 表示、ChineseTypologyDetailUtilTest 的面方向、BuildTaskTest 的累积跳过计数 |
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

## 复现

仓库根运行 `./gradlew.bat check --offline`。缓存不完整时需允许 Wrapper/依赖下载；`--offline` 不会阻止尚未安装的 Wrapper 自身下载。

完整 JUnit 报告位于 `build/reports/tests/test/index.html`。定向测试通过不能作为完整 check 通过的证明。后续每次修复同步本文与 [架构审计](ARCHITECTURE_AUDIT.md) 中的验证结果。
