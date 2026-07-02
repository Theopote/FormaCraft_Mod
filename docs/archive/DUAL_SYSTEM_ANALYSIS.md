# 两套建筑生成系统检查报告

## 📋 系统概览

Formacraft 模组包含两套建筑生成系统：

1. **LlmPlan 系统（新系统）** - 基于语义组件的生成
2. **BuildingSpec 系统（传统系统）** - 基于完整建筑规格的生成

---

## 🔍 系统检查结果

### ✅ 1. LlmPlan 系统（新系统）

#### 系统状态：✅ **正常工作**

**触发条件**：
- Python 后端返回 `LlmPlan` 格式（JSON 包含 `"mode"`, `"components"` 等字段）
- Java 端检测：`spec.getExtra().get("isLlmPlan") == true`

**路由逻辑**：
```java
// FormaCraftNetworking.java:1036-1039
boolean isLlmPlan = spec.getExtra() != null && 
        Boolean.TRUE.equals(spec.getExtra().get("isLlmPlan"));

if (isLlmPlan) {
    // 处理 LlmPlan 格式
    // ...
}
```

**编译路径**：
1. **PlanProgram 模式**（如果 `llmPlan.usesPlanProgramMode() == true`）：
   - `PlanProgramCompiler.compile()` 或 `PlanProgramCompiler.compileFromPlanSkeleton()`
   - 可选：`BuildingMassSystemIntegrator.compileWithBuildingMass()`（补充）

2. **Component 模式**（如果 `llmPlan.usesComponentMode() == true`）：
   - `ComponentPlanCompiler.compile()`
   - 使用 `ComponentGenerator`（在 `common.generation.component` 包中）

**生成器**：
- `TowerGenerator` - 塔楼
- `WallGenerator` - 墙体
- `GateGenerator` - 门/门楼
- `RoadGenerator` - 道路
- `MassMainGenerator` - 主体体块
- `RoofGenerator` - 屋顶
- 等...

**输出**：`List<BlockPatch>`（相对坐标）

**使用场景**：
- ✅ LLM 输出语义组件
- ✅ 组件级别的灵活组合
- ✅ 需要快速迭代和测试
- ✅ PlanProgram 模式（平面规划系统）

---

### ✅ 2. BuildingSpec 系统（传统系统）

#### 系统状态：✅ **正常工作**

**触发条件**：
- Python 后端返回 `BuildingSpec` 格式（不是 LlmPlan）
- Java 端检测：`isLlmPlan == false` 或 `null`

**路由逻辑**：
```java
// FormaCraftNetworking.java:1330-1338
// 传统的 BuildingSpec 处理流程
StructureGenerator generator = StructureGeneratorFactory.getGenerator(spec);
GeneratedStructure generated = generator.generate(spec, origin, serverWorld);
```

**编译路径**：
1. `StructureGeneratorFactory.getGenerator(spec)` - 获取生成器
2. `GeneratorRouter.route(spec)` - 路由到合适的生成器
3. `StructureGenerator.generate()` - 生成结构

**生成器路由优先级**：
1. `routeByStyleProfileId()` - 按风格配置路由
2. `routeByAssembly()` - 按 Assembly 路由
3. `routeByBlueprint()` - 按 Blueprint 路由
4. `routeByTemplate()` - 按模板路由
5. `routeLegacyLandmark()` - 按地标路由
6. `routeByArchetype()` - 按原型路由
7. 默认：按 `BuildingSpec.type` 路由

**生成器**：
- `HouseGenerator` - 房屋生成器
- `TowerGenerator` - 塔楼生成器
- `CastleCompoundGenerator` - 城堡复合结构
- `MingQingCourtyardGenerator` - 明清庭院
- `TulouGenerator` - 土楼生成器
- `EiffelTowerGenerator` - 埃菲尔铁塔
- 等 50+ 个生成器...

**输出**：`GeneratedStructure`（绝对坐标）

**使用场景**：
- ✅ 传统 BuildingSpec 格式
- ✅ 地标建筑（如土楼、埃菲尔铁塔）
- ✅ 完整建筑生成
- ✅ CitySpec 和 CompositeSpec

---

## 🔀 Python 后端路由逻辑

**位置**：`python_backend/app/routes/build.py:50-68`

**路由优先级**：
```python
# 优先级：LlmPlan > 城市 > 复合 > 单个
if build_req.promptMode == "BUILD":
    request_text = build_req.requestText or ""
    llm_plan_indicators = [
        "LlmPlan", "component_type", "semantic components", 
        "ComponentObject", "SlotObject", "STRUCTURED JSON TEMPLATE",
        "mode", "style_profile", "components"
    ]
    # 检查是否包含 LlmPlan 格式的特征
    if any(indicator in request_text for indicator in llm_plan_indicators):
        return generate_llm_plan(build_req)  # 返回 LlmPlan

if _should_generate_city(build_req):
    return generate_city_spec(build_req)  # 返回 CitySpec
if _should_generate_composite(build_req):
    return generate_composite_spec(build_req)  # 返回 CompositeSpec
return generate_building_spec(build_req)  # 返回 BuildingSpec
```

**问题**：⚠️ **路由逻辑可能不够准确**

**原因**：
- LlmPlan 的检测基于 `requestText` 中是否包含特定关键词
- 如果 `requestText` 不包含这些关键词，即使 LLM 应该输出 LlmPlan，也会走 BuildingSpec 路径
- 这可能导致系统选择错误

**建议**：
- 应该基于 `PromptAssembler` 的输出格式来决定，而不是基于关键词匹配
- 或者让 LLM 明确指定输出格式

---

## ⚠️ 潜在问题分析

### 1. 路由逻辑不够准确 ⚠️

**问题**：
- Python 后端使用关键词匹配来决定是否生成 LlmPlan
- 如果关键词不匹配，可能错误地走 BuildingSpec 路径

**影响**：
- 可能导致系统选择错误的生成路径
- 用户期望使用新系统，但实际使用了传统系统

**建议**：
- 改进路由逻辑，基于 `PromptAssembler` 的输出格式
- 或者让用户/系统明确指定使用哪个系统

---

### 2. 两套系统可能产生不同的结果 ⚠️

**问题**：
- 同样的用户输入，可能走不同的系统路径
- 两套系统使用不同的生成器，可能产生不同的建筑

**影响**：
- 用户体验不一致
- 难以预测系统行为

**建议**：
- 明确两套系统的使用场景
- 在文档中说明何时使用哪个系统

---

### 3. 系统选择逻辑分散 ⚠️

**问题**：
- 系统选择逻辑分散在多个地方：
  - Python 后端：`build.py` 中的路由逻辑
  - Java 端：`FormaCraftNetworking.java` 中的 `isLlmPlan` 检查

**影响**：
- 难以维护
- 容易出错

**建议**：
- 统一系统选择逻辑
- 在 Java 端统一处理，Python 后端只负责生成

---

## ✅ 系统功能完整性检查

### LlmPlan 系统功能

- ✅ **PlanProgram 模式**：支持平面规划系统
- ✅ **Component 模式**：支持语义组件生成
- ✅ **地形适应**：支持地形策略
- ✅ **后处理**：支持细节装饰、材质变化
- ✅ **预览系统**：支持预览和确认
- ✅ **BuildingMass 集成**：可选使用 BuildingMass 系统

### BuildingSpec 系统功能

- ✅ **完整建筑生成**：支持生成完整建筑结构
- ✅ **地标建筑**：支持专用生成器（土楼、埃菲尔铁塔等）
- ✅ **多级路由**：支持按风格、Assembly、Blueprint、模板、地标、原型路由
- ✅ **地形适应**：支持地形策略（在生成器中实现）
- ✅ **预览系统**：支持预览和确认
- ✅ **质量检查**：支持质量检查和自动修复

---

## 🎯 使用场景分析

### 何时使用 LlmPlan 系统

✅ **推荐使用**：
- LLM 输出语义组件（`components[]`）
- 需要组件级别的灵活组合
- 需要 PlanProgram 模式（平面规划）
- 需要快速迭代和测试
- 需要语义驱动的生成

### 何时使用 BuildingSpec 系统

✅ **推荐使用**：
- LLM 输出 BuildingSpec 格式
- 需要生成完整建筑
- 需要地标建筑（如土楼、埃菲尔铁塔）
- 需要复杂的建筑结构
- 需要 CitySpec 或 CompositeSpec

---

## 📊 系统对比

| 特性 | LlmPlan 系统 | BuildingSpec 系统 |
|------|-------------|------------------|
| **系统类型** | 组件生成器（新系统） | 结构生成器（传统系统） |
| **输入** | `LlmPlan`（包含 `components[]` 或 `planProgram`） | `BuildingSpec` |
| **输出** | `List<BlockPatch>`（相对坐标） | `GeneratedStructure`（绝对坐标） |
| **粒度** | 组件级别（TOWER, WALL, GATE） | 建筑级别（完整建筑） |
| **生成器数量** | ~20 个组件生成器 | ~50+ 个结构生成器 |
| **使用场景** | 语义组件生成、PlanProgram | 完整建筑、地标建筑 |
| **地形适应** | 支持（后处理） | 支持（生成器中） |
| **预览系统** | 支持 | 支持 |
| **质量检查** | 部分支持 | 完整支持 |

---

## ✅ 总结

### 系统状态

- ✅ **LlmPlan 系统**：正常工作，功能完整
- ✅ **BuildingSpec 系统**：正常工作，功能完整
- ⚠️ **路由逻辑**：需要改进，当前基于关键词匹配可能不够准确

### 系统关系

- ✅ **无冲突**：两套系统完全独立，不会产生冲突
- ✅ **无重复**：两套系统服务于不同的场景和架构层次
- ⚠️ **选择逻辑**：需要改进，当前逻辑可能不够准确

### 建议

1. **改进路由逻辑**：
   - 基于 `PromptAssembler` 的输出格式来决定系统选择
   - 或者让用户/系统明确指定使用哪个系统

2. **统一系统选择**：
   - 在 Java 端统一处理系统选择逻辑
   - Python 后端只负责生成，不负责选择

3. **文档完善**：
   - 明确两套系统的使用场景
   - 在文档中说明何时使用哪个系统

4. **测试覆盖**：
   - 确保两套系统都能正常工作
   - 测试不同场景下的系统选择

---

## 🎉 结论

**两套系统都能正常工作，没有冲突，但路由逻辑需要改进。**

- ✅ 系统功能完整
- ✅ 系统职责明确
- ✅ 系统独立运行
- ⚠️ 路由逻辑需要改进
