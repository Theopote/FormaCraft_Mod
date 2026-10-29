# FBD：受限过程式几何实验

FBD 保留 `outputFormat=llmplan`。显式 `ASSEMBLY` 构件通过
`params.fbd_program` 提供程序，Java 将其展开为 Assembly 指令，随后沿用
BlockPatch、现有区域约束、预览票据、确认和撤销路径。

## 启用与输入

默认不向普通请求推荐 FBD。后端设置 `LLMPLAN_FBD=on` 后，LlmPlan 上下文会加入
语言摘要和共享原语签名。当前玩家消息明确要求使用 FBD 时，无需环境开关，后端使用专用
程序契约和可执行示例，保留玩家位置、朝向和选区，避免旧预设提示覆盖程序要求。
模型漏掉 `params.fbd_program` 时最多修复两轮，仍失败则返回能力缺口，不替换为普通建筑。
纯 FBD 计划不进入 MASS/ROOF 专用的语义补全与契约改写；这不代表其建筑功能已通过验证。
Java 始终独立校验显式传入的程序，不信任后端检查结果。
示例计划位于
[`repeated_pavilions.llmplan.json`](../src/main/resources/assets/formacraft/fbd/examples/repeated_pavilions.llmplan.json)。

同一构件的程序不能与 `assembly/ops/graph/components/macro/preset/presetId/skeleton`
载荷、构件库请求或另一生成权威并存。`_server_fbd_compilation` 是服务端保留字段。
不同槽中的普通语义构件仍可使用原有流程。同一槽可以包含多个独立 FBD 构件，
但不能混入普通主体、屋顶或旧 Assembly 权威；独立程序间的实际重叠仍由 Patch 冲突检查处理。

同一程序按指令顺序执行，同一坐标以后写状态为准（包括空气）。预算计量仍覆盖所有候选写入，
随后只将每个坐标的最终状态交给构件间 Patch 合并，避免把程序内部屋顶覆盖墙顶等操作误报为越权。
这不会给一个程序覆盖另一个程序或其他构件的权限。

## 首版语言边界

- 语句：`let / if / repeat / with_frame / call / emit`；所有语句 ID 全局唯一，最长 64 字符。
- 函数调用图必须无环；函数仅接收显式参数，不捕获调用者局部变量。
- `let` 在当前块内不可重复绑定；子块可遮蔽外层绑定。`i` 保留为循环索引，不能被参数或 let 重新定义。
- `int` 是有溢出检查的有符号 32 位数；seed 是非负有符号 64 位数。
  `scalar` 是有限 double；int/int 向零截断，`floor` 是唯一 scalar→int 转换。
  三角函数以度为单位，使用 StrictMath。除零、溢出、非有限结果均失败。
- `with_frame` 只平移，继承构件朝向；不支持程序内旋转。随机数由 seed、稳定语句 ID、调用/循环实例路径和标签经 SHA-256 派生。
- **不支持递归、世界查询、宿主调用、文件或网络访问。** 不执行 Python、JavaScript 或 Java 源代码。

三个原语的参数、枚举与范围由
[`primitives_v0.json`](../src/main/resources/assets/formacraft/fbd/primitives_v0.json)
统一定义。结构 Schema 由 `python python_backend/tools/export_fbd_schema.py` 生成；
Java 类型、调用图和运行预算校验仍是权威。

材质值仅可为 `wall/window/floor/roof/slab/accent`，通过类型化角色进入既有调色板，
不将角色名称当作方块 ID。HIP 与 GABLE 都调用当前通用坡顶算法，不代表独立屋顶求解器。
SHELL_BOX 有既有楼板、窗带、顶板行为，并沿用中心化闭区间坐标：偶数 w/d 的实际跨度可能为 w+1/d+1。
预算估算按实际循环边界计算，不能仅由构件 dimensions 推断程序包围盒。这些原语不保证入口、室内动线或建筑功能完整。
需要这些能力的请求应继续使用普通语义构件。

## 预算、失败与诊断

默认限制为 64 KiB 输入、2000 个 JSON 值、24 层输入/执行深度、200000 求值步、
5000 次累计循环、20000 条展开指令、2000000 次实际几何采样、200000 次候选写入。
标识符长度也受限，避免循环展开放大来源信息的内存开销。

每个原语先检查包含空气写入和采样的保守工作量上界；各 FBD 构件的上界、求值步和指令数
还会在计划级累计检查。原语循环内另有实际采样和候选写入计量、中断检查。
保守上界可能提前拒绝实际输出较小的程序，不会截断输出并冒充成功。

编译或执行失败不返回可应用的部分建筑。错误沿现有 `CapabilityGap` 返回，使用
`E_FBD_*` 错误码；`details` 包含 `code/stage/node/path/message`，预算错误还可包含
`layer/limit/actual`。Schema 见
[`diagnostic.schema.json`](../src/main/resources/assets/formacraft/fbd/diagnostic.schema.json)。
后端不会给 FBD 自动注入普通 Assembly preset，也不会在 schema 修复失败后用简化建筑替换程序。

当前版本重编译未应用的预览，并在确认后持久化源计划以支持 `REPLACE_FBD_PROGRAM` 整栋替换。
已接入预览前 Java 编译诊断的有界修订，见 [修订闭环](FBD_REPAIR_LOOP.md)。
预览前可对合格的屋顶悬空做一次确定性 `ROOF_COVER.y ±1` 修订并重发预览，不调用模型。
世界地形与其余质量诊断仍不自动回送 LLM，也未接入视觉批判或自动多轮渲染。
同一程序重放还依赖相同引擎与资产版本、调色板及外部上下文；seed 本身不是完整重放存档。
确认落地后会把 sanitize 过的 FBD 源计划写入建筑 Memory，支持后续程序替换编辑；
节点与实例来源映射仍位于展开指令中，尚未写入方块存档 NBT。

## 验证

```bash
./gradlew test --tests 'com.formacraft.common.dsl.*' --tests 'com.formacraft.server.dsl.*'
cd python_backend
python -m pytest tests/test_fbd_program.py
```

引用示例中的三个无查询程序在真实原语执行器中测试；原第四个地形查询示例作为拒绝用例保留。
测试还覆盖十二个原反例、四个朝向、材质角色、确定性、预算中断、来源诊断和计划失败传播。
这些自动化验证不替代游戏内预览、确认、撤销及专服验收。

## 地形与支撑验收

ADAPTIVE 模式按程序实际承托列补浅地基，不把屋檐和独立亭子之间的空白填成整片平台；
显式 FLATTEN 仍沿用整片平整。支撑检测与修复共用最终写入状态和六邻接连通性：
连接到落地墙体的屋檐不会逐列补柱，计划中已清除的地形和水、草不能冒充支撑。
这只是连通性检查，不是承重、跨度或材料强度求解器。

修复、选区裁剪后重新检查，实际终检问题决定是否需要强制应用。初检发现保留为历史信息，
已解决的错误不再阻止正常确认；残留悬空仍会报告。支撑及清理操作留在同一个建造计划中，
与建筑一起记录撤销、重做。回归覆盖真实计划四朝向、平地/坡地、截断支撑和地形恢复；
游戏内尚需重新运行客户端验收。
