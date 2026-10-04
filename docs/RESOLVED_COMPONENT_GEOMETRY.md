# Components 共享几何解析（第三批）

日期：2026-10-04。Java 入口：`ResolvedComponentGeometry`；后端入口：`app/services/resolved_geometry.py`。

## 已接入的链路

Components 主体规范化 → 主体/卫星对齐 → 主体生成 → 建筑边界与楼层装饰。

Java 的 `ComponentFootprintUtil` 原点及边界方法委托共享几何解析；`ComponentPlanCompiler` 在推断屋顶、入口等卫星前规范化主体；`MassMainGenerator` 在建立 MassConfig 前读取规范化尺寸。后端的楼梯原点、需求契约的主体中心与解析报告复用同一几何模块。不是新增生成路线。

## 几何约定

- 尺寸顺序为 width / depth / height。主体的 x/z 为中心或角点，y 始终为底部方块高度。
- `anchor_mode`/`anchorMode` 忽略大小写；包含 `corner` 时按最小角点处理，其他按中心处理。这与原有 Java 协议一致；`bottom_left` 不含 corner，不是额外角点别名。
- 中心到角点：`minX = x - floor(width/2)`、`minZ = z - floor(depth/2)`。偶数尺寸的真实几何中心比声明位置低半格，归属距离检查也使用该真实中心。
- 边界为半开区间 `[min, min+size)`，顶部方块为 `minY + height - 1`。
- `planOrigin = localOrigin + slot.anchor`，世界原点再叠加 plan/world anchor。编译输出保持相对 plan 原点的偏移，放置阶段才叠加世界锚点。slot.facing 不表示旋转整个局部坐标系。

`MASS_MAIN`/`MAIN_MASS` 主体最小宽深高为 3；明确层高可提高最低高度。原来主体生成器内部的尺寸调整发生在 MassConfig 创建之后，实际体块、边界和卫星可能不一致；现在调整发生在创建之前。普通卫星不应用主体最低尺寸，`extrude_mode=plate` 保持薄板。

Java 不按 floor_count 静默扩充外壳。楼层参数要求的总层高大于外壳时，解析结果的 `floorLayoutFits` 为 false；后端最终契约以 `E_FLOOR_ENVELOPE` 返回能力缺口。现有后端规范化对合法层数/层高的高度补齐继续保留。

## 楼层标高和报告

显式层高先读取 `floor_height`/`floorHeight`，再读取与 Java 相同的旧 features 层高表达。没有明确层数或层高时，不推断或声称已经存在楼板。明确 n 层、间距 h 时标高为 `bottomY + i*h`，仅列出外壳范围内的标高。

新契约方案的楼层装饰优先采用当前主体的层高，防止风格研究的全局层高覆盖逐栋要求。无 building_contract 的旧 Java 方案继续保留全局层高优先的行为，作为兼容规则。

`proportion_hints.building_contract.resolved_buildings` 记录 component_id、slot_id、local_origin、plan_origin、dimensions、floor_ys_local、roof_y_local 和 floor_layout_fits。`coordinate_stage=plan` 与 `geometry_status=parameters_only` 表示这是参数解析结果，不能证明实际墙体完整、楼板已放置或方块总范围相同。

## 边界与下一步

本批不是完整的 ResolvedBuilding。PlanProgram、typology、assembly 内部坐标尚未接入；复杂 MASS_MAIN.params.masses 的子体块、非矩形外壳的精确占用、跨 slot 旋转和多体块建筑归属仍待统一。解析楼层标高不生成楼板、洞口或楼梯；内部连通性仍由现有生成器处理。

下一批优先把多个体块组成同一建筑的归属与外轮廓纳入模型，再验证最终墙体和屋顶覆盖。内部保持基础楼层与楼梯，不投入复杂房间布局。

## 回归证据

共享样例 `src/test/resources/regressions/resolved-geometry.json` 同时用于 Java 和 Python，覆盖奇偶尺寸、角点/中心、负底部高度、slot 与世界偏移、过小主体及楼层越界。Java 测试另外检查实际主体方块边界与解析结果一致，以及契约主体层高不被研究层高覆盖。后端测试检查越界诊断、别名、薄板排除及无效可选层高数据。

本批验证：Java 513 项测试通过，`gradlew build --offline` 成功；后端相关 52 项通过。后端全套 366 项，仍为与前两批一致的 10 项已知失败，输出见 `build/backend-contract-batch3-tests.log`。架构边界检查为 20 项已知债务、0 项新增；`git diff --check` 通过。测试新 jar 时需同时重启后端。
