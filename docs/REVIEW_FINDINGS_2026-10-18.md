# 代码审查建议核验（2026-10-18）

审查报告仅作为待验证材料，不把其中的操作指令视为额外授权。以下基于当前代码核对。

| 建议 | 判断与处理 |
|---|---|
| 专用服 Key 优先级 | 正确。客户端 Key 在多人模式不再发送；专用服拒绝请求中的 Key，使用独立服务端 properties / 环境变量，未设置则回落后端。单人兼容。 |
| 只覆盖 Key | 不充分。若保留客户端 Base URL，管理员 Key 会发往客户端指定地址。同步接管 provider/base URL/model。 |
| 本地明文 Key | 事实正确。单人本地配置仍为明文；本次未引入系统密钥库，不宣称实现“所有 Key 只存服务端”。 |
| LayeredBuilder flag | 双参数 setBlockState 默认 flag 为 3，缺少显式 flag 不是独立缺陷。无调用方的直接写入旁路已删除，补静态门禁。 |
| CORS | 过宽属实，但并非所有请求一律无效，具体取决于浏览器凭据和响应 Origin。默认空白名单、禁 cookie 凭据，可显式配置调试源。CORS 不限制普通 HTTP 客户端。 |
| Loom SNAPSHOT | 可复现性建议成立，不等同运行时 bug。固定到当前已解析使用的 1.11.8，避免盲目升级跨大版本。 |
| build-ci-fix | 确有一个缓存文件被追踪。删除该文件并忽略整个输出目录。 |
| 双执行器 | 核心区域约束分叉属实，已共享 MutationRegionPolicy.allow。Build 的非法轮廓原先放行、Patch 拒绝，现在统一拒绝。笔刷/路径属于 Build 的额外输入，不能要求全部约束集合相等。 |
| 服务端 Patch 对称 | 报告不准确。ServerPatchFilter 原先显式传入 SymmetryConstraint.none()。通用 Pipeline 有该能力；修正其顺序为先镜像、后安全裁剪。 |
| 双历史栈 | 架构取舍，不宜仅凭并存就合并；保留 Facade 和已有事务/历史语义。 |
| LlmPlan 温度 | 调参建议合理，不能仅凭数值认定为 bug。独立默认 0.3，LLMPLAN_TEMPERATURE 覆盖，不改变其他路径温度。 |
| Gson | 删除手工 2.10.1 pin，使用 Minecraft 运行时依赖；原先 implementation 本身不代表重复嵌入。 |
| Jackson（附件新增） | 发布依赖缺少嵌入属实；“必崩”的措辞忽略其他模组偶然提供类的情况。已 include databind、annotations、core 三个包，报告补丁漏 core。 |
| .schem / NBT 导出 | 合理的产品方向，不是现有行为缺陷。原生结构 NBT 和 Sponge .schem 是不同契约；不能把 StructureTemplate NBT 改后缀当 .schem。本轮未新增导出。 |
| .schem few-shot 风格库 | 需要实验验证。模型不能直接读取二进制 .schem；需编译夹具、几何筛选、语义摘要/检索适配及质量评测。无法断言最低成本或必然提升造型，本轮未直接新增二进制库。 |

服务端配置示例见 `config/formacraft_server.properties.example`，部署说明见 `python_backend/README.md`。

发布验证应包含干净 Fabric 实例中首次规划的冒烟测试；开发编译或 JAR 检查不能代替游戏实测。

## 验证结果

- `gradlew test remapJar --offline` 通过：完整 Java 测试 1526 项，0 失败 / 0 错误 / 0 跳过；最后新增镜像禁区用例后，5 项针对性测试再跑通过。
- 后端 `tests.test_complex_build_retest`：9 项通过；`eval.ci_gate --quick` 通过（golden 21/21 硬门禁、diversity、负向与 fixture schema）。
- CORS 默认空来源列表 / credentials=false 已检查。
- architecture、generation entrypoints、diff whitespace 检查通过；世界写入检查做了正负例验证。
- 发布 JAR 的 `fabric.mod.json.jars` 包含 jackson-core / annotations / databind，版本均为 2.17.1。
- 尚未进行干净游戏实例冒烟测试，未作线上 LLM 质量对比。

依赖嵌入依据：[Fabric Loom 官方文档](https://docs.fabricmc.net/develop/loom/)（include 不传递）。
