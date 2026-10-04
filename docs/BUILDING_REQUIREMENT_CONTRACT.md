# 建筑需求契约与构件归属（第一批）

日期：2026-10-04。实现入口：`python_backend/app/services/building_contract.py`。

## 目的与当前边界

用户明确要求优先于风格研究和比例丰富化。第一批为现有 components 路线保存可检查的要求，并让楼梯、屋顶等附属构件明确引用主体。尚未实现统一 ResolvedBuilding、最终方块需求验收或任意自然语言解析。

支持保守提取「宽15格」「深13格」「两层住宅」「每层高度5格」「平屋顶」及「不要复杂装饰」等中文表达。尺寸、层数和层高只有单一值时才应用于全部 MASS_MAIN；同一属性出现多个值时记录 `unsupported_scope`，等待后续逐栋需求作用域解析。墙体材质、朝向、建筑数量、半径、连接端口、复杂排除表达仍未纳入自动验收。不要把这里的规则当作完整语言理解器。

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

显式 host 优先于距离推断。旧方案无 host 时保留几何推断，并记录 `host_source=legacy_geometry_inference`。连廊不猜测单一主体，双主体与连接端口留待下一批实现。不同真实 layout slot 之间不能直接按相同局部坐标对齐，需要坐标转换能力。Python 楼梯归属与 Java 屋顶、立面等对齐已读取 host；这不代表所有生成器已经统一。

重复身份、未知主体及跨真实坐标系主体引用分别记录 `E_COMPONENT_ID_DUPLICATE`、`E_HOST_UNKNOWN`、`E_HOST_COORDINATE_FRAME`。

## 验证阶段

每条要求保存 `source=user_explicit`、原文片段、属性、值、作用域、优先级与状态。`validation_stage=plan` 明确表示只检查方案参数：

| 状态 | 含义 |
|---|---|
| planned | components 的相关参数与明确要求一致；尚未证明最终方块满足要求 |
| mismatch | 主体参数或屋顶参数不符合要求 |
| unsupported_scope | 表达存在多个不同数值，当前无法分配到具体主体 |
| unverified | 方案没有可供本规则验证的 MASS_MAIN，例如其他生成入口 |

规范化前保存契约，防止丰富化覆盖要求；最终 assembly 修复后再次验证。已识别的 mismatch 或无效身份关系以 `E_BUILDING_CONTRACT` 能力缺口返回，保留原参数与具体诊断，不静默改写建筑。已有其他能力缺口优先保留。patch 编辑不应用此建造契约。

短风格答复可继承聊天记录中最近一条 Player 建筑要求；新的明确建造请求独立解析，不继承旧建筑尺寸。AI 历史内容不作为用户要求来源。

## 回归与后续

`tests/test_building_contract.py` 覆盖原文提取、需求不被改写、方案验证边界、身份幂等、卫星在主体之前、显式远端 host、无效引用、跨坐标系、连廊及历史隔离。Java `ComponentPlanCompilerRealignTest.explicitHostWinsOverNearestMass` 验证屋顶位置遵从显式主体，并保留身份。

下一批应把逐栋作用域、材料与方向接入契约，再统一解析主体尺寸、楼层标高和局部到世界坐标。待已解析几何稳定后实现外墙、屋顶与开口的最终方块验收，才能把 `planned` 升级为实际需求达成报告。

### 本批验证记录

- Java：508 项测试通过，`gradlew build --offline` 成功，已有 remap 警告仍出现。
- 后端相关测试：39 项通过，包含新增 14 项契约测试。
- 后端全套：在新增前 12 项契约测试时运行 351 项，10 项失败；HEAD 独立副本运行 339 项，同样的 10 项失败，未新增失败。随后补充的 2 项历史隔离与方案传输测试已在上述 39 项相关测试中通过。
- 原有失败：两个 CI gate、patch/四合院/方塔/天坛场景门禁、文化卡迁移的两个测试、哥特模板研究路由、参考图研究测试。完整输出见本地 `build/backend-contract-tests.log` 与 `build/backend-contract-baseline-tests.log`；这仍不是全项目测试通过。
- 架构边界：20 项已知债务，0 项新增；`git diff --check` 通过。
