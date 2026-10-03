# 测试与建筑验收

状态：维护中；更新：2026-10-03（第六批）。

## 可复现的 Java 检查

仓库根执行 `./gradlew.bat check`（Unix 使用 `./gradlew check`）。依赖缓存完整时可加 `--offline`；首次需下载与 loader_version 相同版本的 fabric-loader-junit。该依赖仅用于测试，不改变生产 Loader 版本。

JUnit 通过官方 Fabric Loader JUnit 运行，而非直接用普通 JVM 启动 Minecraft 类。依赖方块注册表的测试使用 MinecraftRegistryTestBootstrap，在 BeforeAll 中调用 SharedConstants.createGameVersion 和 Bootstrap.initialize。初始化失败直接报错，不吞异常或跳过测试。历史普通 JUnit 下的 IllegalAccessError 不能靠重复 Bootstrap 解决。

依据：[Fabric 自动化测试文档](https://docs.fabricmc.net/develop/automatic-testing)。文档当前映射名称可能与 1.21.10 Yarn 不同，应以本仓库能编译运行的 API 为准。

`check` 覆盖 JUnit、assembly 示例校验、文化卡校验、生成器目录冻结及 18 个 assembly 评估案例。报告在 build/reports/tests/test/index.html；CI 保留 build/reports 和 build/test-results，无论构建成功还是失败。Java 分层检查另行执行 python scripts/test_check_architecture.py 和 python scripts/check_architecture.py。

本轮完整 Java 检查通过，资源校验仍有宏展开、styleId 桶等 warning，不等于已经消除所有资源语义漂移。Python 后端质量门及真实世界验收独立执行，不能用 Java check 代替。

## 下一步检查顺序

1. 世界修改与历史事务：状态字符串解析能否保留 facing、half、axis、waterlogged；预览与执行是否一致；setBlockState 失败是否被计作成功；越界和跳过是否准确；撤销/重做与 Memory 更新是否原子一致。优先审查 BuildTask、PatchExecutor、PatchHistoryManager 和服务端历史事务入口。
2. 最终建筑几何：为小屋、多层塔、四合院、带穹顶建筑保存描述、计划、编译诊断和最终方块快照。检查屋顶覆盖、入口可达、层间通行、窗柱冲突、凹龛位置及边界预算，而非仅判断 JSON 合法。
3. 规划与失败语义：比较 Python Research、Java typology、assembly 和兼容 BuildingSpec 的路由；检查同步 AI 规划是否阻塞健康检查、超时后的结果是否仍进入预览；再按阶段拆 ai_planner。

这些是待验收目标，目前不宣称已经具备全部自动检查能力。游戏内测试应先使用专门测试世界；本轮没有启动客户端、修改玩家存档或验收多人场景。

## 第七批执行检查

BuildTask/PatchExecutor 的成功计数、拒绝写入、边界读取与状态解析已建立回归；历史与 Memory 分析改用最终差异。详细行为和仍未解决的部分撤销、跨维度、原子性问题见 [方块执行契约](WORLD_MUTATION_CONTRACT.md)。可控世界测试不模拟真实邻居更新或方块实体。

## 第八批历史检查

Patch 与普通建造恢复共用可重试流程，测试覆盖部分恢复、原世界身份、冲突、重复位置及部分重做的新分支。失败或阻塞位置保留原栈，完成后才移动；Memory 只更新当前 Patch 实际恢复差异。世界停止清理运行时历史，真实游戏邻居更新、方块实体及普通建造 Undo 的 Memory 补偿仍需继续验收。

## 第九批历史异常检查

新增四个异常注入测试：写入前抛异常、写入后抛异常、写入后读取失败及异常 redo 后的新分支。恢复逐位置报告成功/失败，未确认结果在后续读取中补报真实差异。349 个 Java 测试全部通过，完整 check 通过；仍需真实游戏验收。普通建造 Memory 同步应先解决维度过滤与事务归属，不能根据撤销完成就删除整栋记忆。
