# 生成器系统使用情况分析

> **2026-07-02 更新**：`server/generator` 已迁至 `common/generation/structure`；整栋路径经 `GenerationHub.routeStructure()` 接入（BuildRequestProcessor、命令、CityBuilder 等）。

## 🔍 问题

（历史）`server\generator` 下的生成器是否没有被使用？—— **现已迁移并接入统一入口。**

## 📊 分析结果

### 两个生成器系统的使用场景

#### 1. 新系统（`common.generation.component`）- LlmPlan 路径 ✅ **当前在使用**

**触发条件**：
- Python 后端返回 `LlmPlan` 格式（JSON 包含 `"mode"`, `"components"` 等字段）
- `FormaCraftNetworking.java` 检测到 `spec.getExtra().get("isLlmPlan") == true`

**调用链**：
```
Python 后端 → 返回 LlmPlan JSON
  ↓
OrchestratorClient → 检测到 LlmPlan，存储在 BuildingSpec.extra 中
  ↓
FormaCraftNetworking.handleBuildRequest() → 检测到 isLlmPlan == true
  ↓
ComponentPlanCompiler.compile() → 使用 common.generation.component 系统
  ↓
ComponentGeneratorRegistry.getGenerator() → 获取 ComponentGenerator
  ↓
生成 List<BlockPatch>
```

**当前状态**：✅ **正在使用**（用户当前走的就是这个路径）

#### 2. 整栋系统（`common.generation.structure`）- BuildingSpec 路径 ✅ **已接入 GenerationHub**

**触发条件**：
- Python 后端返回 `BuildingSpec` 格式（不是 LlmPlan）
- `FormaCraftNetworking.java` 检测到 `isLlmPlan == false` 或 `null`
- 地标 / 城市 / 复合结构等经 `GenerationHub.routeStructure()`

**调用链**：
```
BuildingSpec
  ↓
GenerationHub.routeStructure()
  ↓
StructureGeneratorFactory → GeneratorRouter → StructureGeneratorRegistry
  ↓
GeneratedStructure
```

**当前状态**：✅ **活跃**（Phase 4 主路径已切换至 GenerationHub）

## 🔍 代码证据

### FormaCraftNetworking.java 中的判断逻辑

```java
// 第 906-994 行：LlmPlan 路径（新系统）
boolean isLlmPlan = spec.getExtra() != null && 
        Boolean.TRUE.equals(spec.getExtra().get("isLlmPlan"));

if (isLlmPlan) {
    // 使用 ComponentPlanCompiler（新系统）
    List<BlockPatch> patches = ComponentPlanCompiler.compile(...);
    // ...
    return;
}

// 第 1000+ 行：BuildingSpec 路径（传统系统）
// 只有当 isLlmPlan == false 时才会走到这里
StructureGenerator generator = StructureGeneratorFactory.getGenerator(spec);
GeneratedStructure structure = generator.generate(spec, origin, serverWorld);
```

### Python 后端的路由逻辑

```python
# python_backend/app/routes/build.py
# 第 50-68 行

# 检查应该生成什么类型的结构（优先级：LlmPlan > 城市 > 复合 > 单个）
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

## ⚠️ 问题诊断

### 当前情况

从用户的日志来看：
```
[18:06:39] [ForkJoinPool.commonPool-worker-1/INFO] (formacraft) Received LlmPlan from orchestrator, mode: build
```

**结论**：用户当前走的是 **LlmPlan 路径**，使用的是 **构件层（`common.generation.component`）**。

**整栋层（`common.generation.structure`）** 在以下情况使用（经 `GenerationHub.routeStructure()`）：
1. Python 后端返回 `BuildingSpec` 格式（不是 LlmPlan）
2. `CompositeSpec` / `CitySpec`、Blueprint、地标 archetype 等整栋路径

### 历史备注：为何曾认为整栋层「未充分使用」

1. **PromptAssembler 倾向输出 LlmPlan 格式**
2. **LlmPlan 路径在 networking 层优先级较高**

上述情况仍成立，但 Phase 4 已将 BuildRequestProcessor、命令、CityBuilder 等切换至 `GenerationHub`，整栋层现为活跃子系统。

## 📋 传统系统（common.generation.structure）的使用场景

传统系统在以下情况下**会被使用**：

### 1. 返回 BuildingSpec 格式
- 当 Python 后端返回标准的 `BuildingSpec` 时
- 例如：`generate_building_spec()` 返回的格式

### 2. 返回 CompositeSpec 格式
- 当生成复合结构时
- `CompositeStructureGenerator` 内部会调用传统系统的生成器

### 3. 返回 CitySpec 格式
- 当生成城市时
- `CityBuilder` 内部会调用传统系统的生成器

### 4. Blueprint 路径
- 当 `BuildingSpec.extra.blueprint` 存在时
- `BlueprintStructureGenerator` 会使用传统系统的生成器

### 5. 地标建筑
- 当检测到特定 archetype（如土楼、埃菲尔铁塔）时
- `GeneratorRouter` 会路由到专用的 `StructureGenerator`（如 `TulouGenerator`、`EiffelTowerGenerator`）

## 🎯 结论

### 当前状态

1. **新系统（`common.generation.component`）**：✅ **正在使用**
   - 用户当前走的是 LlmPlan 路径
   - 使用 `ComponentPlanCompiler` + `ComponentGenerator`

2. **传统系统（`common.generation.structure`）**：⚠️ **可能未被使用**
   - 只有当 Python 后端返回 BuildingSpec/CompositeSpec/CitySpec 时才会使用
   - 如果 PromptAssembler 总是生成 LlmPlan 格式的 prompt，传统系统就不会被触发

### 建议

1. **如果希望使用传统系统**：
   - 修改 Python 后端，让它不总是返回 LlmPlan
   - 或者修改 PromptAssembler，让它不总是生成 LlmPlan 格式的 prompt

2. **如果希望两个系统共存**：
   - 保持现状即可
   - 传统系统会在特定场景下被使用（地标建筑、复合结构、城市等）

3. **如果希望整合两个系统**：
   - 可以考虑创建适配器，让传统系统的生成器可以被新系统调用
   - 但这需要较大的重构工作

## 📝 总结

**回答用户的问题**：
- `common.generation.structure` 文件夹下的生成器**不是完全没有被使用**
- 但它们**在当前场景下（LlmPlan 路径）确实没有被使用**
- 传统系统会在以下场景被使用：
  - BuildingSpec 格式
  - CompositeSpec 格式
  - CitySpec 格式
  - Blueprint 路径
  - 地标建筑（archetype 路由）

**建议**：
- 如果希望使用传统系统的完整功能，可以考虑让 Python 后端在某些情况下返回 BuildingSpec 而不是 LlmPlan
- 或者创建适配器，让传统系统的生成器可以被新系统调用

