# 建筑需求契约与构件归属

日期：2026-10-04。实现入口：`python_backend/app/services/building_contract.py`。

## 目的与当前边界

用户明确要求优先于风格研究和比例丰富化。第一批为现有 components 路线保存可检查的要求，并让楼梯、屋顶等附属构件明确引用主体。尚未实现统一 ResolvedBuilding、最终方块需求验收或任意自然语言解析。

支持保守提取「宽15格」「深13格」「两层住宅」「每层高度5格」「平屋顶」及「不要复杂装饰」等中文表达。无逐栋声明时，单一尺寸、层数和层高应用于全部 MASS_MAIN；同一作用域出现多个值时记录 `unsupported_scope`。第二批增加显式序号作用域、有限的墙体/楼板材质及入口方向适配。建筑数量、半径、连接端口、任意建筑名称和复杂排除表达仍未纳入自动验收。不要把这里的规则当作完整语言理解器。

「不要复杂装饰」当前仅检查方案未添加 CROWN、CUPOLA、DOME；不能据此判断任意 DECOR_DETAIL 或最终方块的装饰复杂度。平屋顶当前检查 roof_type 参数，不能证明生成器输出的几何形状。

当前明确要求会阻止整段自动建筑丰富化，以免修改尺寸或增加不需要的屋顶装饰；没有明确要求的原有风格丰富化继续运行。后续应改为按属性保护，而不是整体跳过。

## 传输与身份

契约放在既有 `proportion_hints.building_contract`，schema 为 `formacraft.building_contract.v1`。这样可沿用 Python 与 Java 已有 Map 字段，不改变 LlmPlan 构造器与旧 JSON。契约由后端根据用户文本重建，不能把模型声称的验收结果作为证据。

```json
{
  "component_type": "ROOF",
  "params": {
    "component_id": "roof_a",
    "host_id": "house_a",
    "building_id": "house_a",
    "roof_type": "flat"
  }
}
```

`component_id` 在同一方案重复规范化时保持稳定，旧方案缺失时自动分配；不保证重新调用模型后仍有相同身份。MASS_MAIN 的 `building_id` 等于自身身份。`host_id` 引用 MASS_MAIN 的身份；`slot_id` 仍表示局部坐标系，不能替代构件身份。

### 第二批：逐栋作用域、材料与朝向

例如「第一栋宽15格、石砖外墙；第二栋宽20格、墙体使用砖块。两栋均每层高度5格」。序号声明仅在紧邻可识别属性时生效；「从第一栋进入、到第二栋」这样的通行描述不创建建筑声明。「所有建筑」「全部建筑」「两栋均」「两栋都」「每栋均」「每栋都」可恢复共同作用域。

对应 MASS_MAIN 必须显式设置 `params.requirement_scope="building_1"` 或 `"building_2"`。绑定保存在契约 `scope_bindings`，作用域检查按身份执行，交换 components 顺序不会交换要求。缺少绑定或多主体争用同一序号时记录 `E_REQUIREMENT_SCOPE`，要求状态为 `unresolved_binding`，最终返回能力缺口；不会按位置、slot 名称或数组序号猜测。一个序号绑定一个主 MASS_MAIN，多体块建筑尚需下一阶段统一模型。

材质表达支持「石砖外墙」「墙体使用砖块」「橡木楼板」「地板采用云杉木板」等。当前映射限于石砖、橡木板（橡木作为楼板/墙体修饰时映射木板）、云杉木板、砖块、圆石、石英块。方案必须以 `params.wall_block`、`params.floor_block` 保存完整方块 ID。MASS_MAIN 生成器以这些参数优先于动态风格与调色板，作用于墙体语义部分和其内部基础地板；转折楼梯规范化新增的楼板继承主体 floor_block、slot 与 host。独立立面装饰、其他生成器和任意 assembly 楼板仍可能使用自己的材料，本规则尚未验收全部最终方块。

入口表达支持「正门位于南侧」「入口朝北」等。契约的 value 是 Minecraft 世界方向；现有主体/入口生成器的旧 NORTH 实际开在 +Z（世界南），SOUTH 开在 -Z（世界北），EAST 开在 -X（世界西），WEST 开在 +X（世界东）。因此要求另存 `direction_convention=minecraft_world` 与 `runtime_field_value`，模型必须把后者写入 `layout.slots[].facing` 或 `global_constraints.facing`。这是一层明确的旧协议适配，未改写已有方案的四向定义。

检查读取真实 `slot_id` 对应的 slot.facing；真实 slot 未填方向时按生成器默认 SOUTH 解释，只有不存在真实 slot 时才读取 global_constraints，最后默认 SOUTH。入口自动创建和对齐采用同样的回退规则，避免主体门洞与入口使用不同方向。`params.facing` 不作为入口方向证据。方案级方向检查不证明最终门洞位于立面中央，也不替代连通性检查。

显式 host 优先于距离推断。旧方案无 host 时保留几何推断，并记录 `host_source=legacy_geometry_inference`。连廊不猜测单一主体，双主体与连接端口留待下一批实现。不同真实 layout slot 之间不能直接按相同局部坐标对齐，需要坐标转换能力。Python 楼梯归属与 Java 屋顶、立面等对齐已读取 host；这不代表所有生成器已经统一。

重复身份、未知主体及跨真实坐标系主体引用分别记录 `E_COMPONENT_ID_DUPLICATE`、`E_HOST_UNKNOWN`、`E_HOST_COORDINATE_FRAME`。

## 验证阶段

每条要求保存 `source=user_explicit`、原文片段、属性、值、作用域、优先级与状态。`validation_stage=plan` 明确表示只检查方案参数：

| 状态 | 含义 |
|---|---|
| planned | components 的相关参数与明确要求一致；尚未证明最终方块满足要求 |
| mismatch | 主体参数或屋顶参数不符合要求 |
| unsupported_scope | 表达存在多个不同数值，当前无法分配到具体主体 |
| unresolved_binding | 已识别逐栋声明，但对应主体绑定缺失或存在歧义 |
| unverified | 方案没有可供本规则验证的 MASS_MAIN，例如其他生成入口 |

规范化前保存契约，防止丰富化覆盖要求；最终 assembly 修复后再次验证。已识别的 mismatch 或无效身份关系以 `E_BUILDING_CONTRACT` 能力缺口返回，保留原参数与具体诊断，不静默改写建筑。已有其他能力缺口优先保留。patch 编辑不应用此建造契约。

短风格答复可继承聊天记录中最近一条 Player 建筑要求；新的明确建造请求独立解析，不继承旧建筑尺寸。AI 历史内容不作为用户要求来源。

## 回归与后续

`tests/test_building_contract.py` 覆盖原文提取、需求不被改写、方案验证边界、身份幂等、卫星在主体之前、显式远端 host、无效引用、跨坐标系、连廊及历史隔离。Java `ComponentPlanCompilerRealignTest.explicitHostWinsOverNearestMass` 验证屋顶位置遵从显式主体，并保留身份。

下一批应统一解析主体尺寸、楼层标高和局部到世界坐标，让多个体块能够组成同一建筑；入口方向适配也应纳入该模型。待已解析几何稳定后实现外墙、屋顶与开口的最终方块验收，才能把 `planned` 升级为实际需求达成报告。

### 本批验证记录

- Java：508 项测试通过，`gradlew build --offline` 成功，已有 remap 警告仍出现。
- 后端相关测试：39 项通过，包含新增 14 项契约测试。
- 后端全套：在新增前 12 项契约测试时运行 351 项，10 项失败；HEAD 独立副本运行 339 项，同样的 10 项失败，未新增失败。随后补充的 2 项历史隔离与方案传输测试已在上述 39 项相关测试中通过。
- 原有失败：两个 CI gate、patch/四合院/方塔/天坛场景门禁、文化卡迁移的两个测试、哥特模板研究路由、参考图研究测试。完整输出见本地 `build/backend-contract-tests.log` 与 `build/backend-contract-baseline-tests.log`；这仍不是全项目测试通过。
- 架构边界：20 项已知债务，0 项新增；`git diff --check` 通过。

### 第二批验证记录

- Java 全部 510 项测试通过，`gradlew build --offline` 成功。
- 后端相关 48 项测试通过，其中契约测试 23 项；逐栋约束额外经过真实规范化函数与 LlmPlan schema 检查。
- 后端全套 362 项，仍为相同的 10 项已知失败；输出为 `build/backend-contract-batch2-tests.log`。
- 实际生成测试覆盖墙体/地板显式材质优先于动态风格，以及四个旧方向对应的门洞平面和对侧墙体保留。
- 架构边界仍为 20 项已知债务、0 项新增；更新 jar 后需重启使用新 Python 代码的后端服务。
