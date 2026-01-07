# K3.3：Tool → PatchFilterPipeline（AI 改动裁剪系统）实现总结

## ✅ 已完全实现

### 核心目标

**不论 LLM 多"放飞"，最终落地方块一定满足：**
- ✅ 不进禁区
- ✅ 不出轮廓
- ✅ 满足对称 / 镜像约束
- ✅ 只在允许区域内修改（增量模式）

**这一步是 Formacraft 从"AI 玩具" → "可靠工具" 的分水岭。**

### 总体架构

```
LLM JSON
  ↓
ComponentPlanCompiler
  ↓
List<BlockPatch>   ←（AI 的"意图"）
  ↓
PatchFilterPipeline
  ├─ ForbiddenZoneFilter
  ├─ OutlineClipFilter
  ├─ SelectionOnlyFilter
  ├─ SymmetryFilter
  ↓
SafePatchList      ←（可执行）
```

**👉 PatchFilter 是最后一道闸门**
**👉 Generator / LLM 都不可信，只有 Filter 后的 Patch 才能执行**

### 核心组件

#### 1. PatchFilter（接口）
- **位置**：`src/main/java/com/formacraft/common/patch/filter/PatchFilter.java`
- **方法**：`filter(List<BlockPatch>, BlockPos, PatchFilterContext)`
- **职责**：过滤 BlockPatch 列表，确保满足工具约束

#### 2. PatchFilterContext（工具状态快照）
- **位置**：`src/main/java/com/formacraft/common/patch/filter/PatchFilterContext.java`
- **字段**：
  - `selection` - SelectionTool
  - `outline` - OutlineTool
  - `symmetry` - SymmetryTool
  - `forbidden` - ProtectedZoneTool
- **便捷方法**：
  - `hasSelection()` - 是否有选区
  - `hasOutline()` - 是否有轮廓
  - `hasForbiddenZone()` - 是否有禁区
  - `hasSymmetry()` - 是否启用对称

#### 3. PatchFilterPipeline（主入口）
- **位置**：`src/main/java/com/formacraft/common/patch/filter/PatchFilterPipeline.java`
- **功能**：
  - 组合多个 Filter
  - 按顺序应用 Filter
  - `createDefault()` - 创建默认 Pipeline（推荐顺序）

#### 4. PatchFilterContextBuilder（构建器）
- **位置**：`src/main/java/com/formacraft/common/patch/filter/PatchFilterContextBuilder.java`
- **功能**：
  - `fromTools()` - 从工具实例创建
  - `fromDefaultTools()` - 从默认工具实例创建（推荐）

### Filter 实现

#### 1. ForbiddenZoneFilter（禁区裁剪）
- **位置**：`src/main/java/com/formacraft/common/patch/filter/impl/ForbiddenZoneFilter.java`
- **功能**：移除所有位于禁区内的 BlockPatch
- **效果**：🟥 AI 想在禁区放什么都直接消失

#### 2. OutlineClipFilter（轮廓裁切）
- **位置**：`src/main/java/com/formacraft/common/patch/filter/impl/OutlineClipFilter.java`
- **功能**：只保留位于轮廓内的 BlockPatch
- **支持**：
  - 圆形轮廓（CIRCLE）
  - 多边形轮廓（POLYGON / RECTANGLE / FREE_DRAW）
  - 高度范围检查
- **效果**：🟪 AI 建筑被"剪"进轮廓，再复杂的 Generator 都服从

#### 3. SelectionOnlyFilter（只修改选区）
- **位置**：`src/main/java/com/formacraft/common/patch/filter/impl/SelectionOnlyFilter.java`
- **功能**：只保留位于选区内的 BlockPatch
- **效果**：🟩 "只修改选区内建筑"，而且是硬约束

#### 4. SymmetryFilter（对称 / 镜像）
- **位置**：`src/main/java/com/formacraft/common/patch/filter/impl/SymmetryFilter.java`
- **功能**：为每个 BlockPatch 生成镜像版本
- **支持**：
  - `MIRROR_X` - 关于 X 轴镜像
  - `MIRROR_Z` - 关于 Z 轴镜像
  - `BOTH` - 双向镜像
  - `CUSTOM_AXIS` - 自定义轴线镜像
- **效果**：🪞 AI 只画一半，系统自动补另一半

### 使用示例

```java
import com.formacraft.common.patch.filter.*;
import com.formacraft.common.patch.BlockPatch;
import net.minecraft.util.math.BlockPos;

// 1. 创建工具状态快照
PatchFilterContext context = PatchFilterContextBuilder.fromDefaultTools();

// 2. 创建 Pipeline（使用默认顺序）
PatchFilterPipeline pipeline = PatchFilterPipeline.createDefault();

// 3. 应用 Filter
List<BlockPatch> rawPatches = ComponentPlanCompiler.compile(llmPlan);
BlockPos origin = new BlockPos(0, 0, 0);

List<BlockPatch> safePatches = pipeline.apply(rawPatches, origin, context);

// 4. 使用安全的 Patch 列表
// BuildConfirmPanel.INSTANCE.showPatchPreview(origin, safePatches);
// FormaCraftNetworking.sendPatchApply(origin, safePatches);
```

### 完整链路

```
LLM JSON (components[])
  ↓
LlmPlanParser.parseAndValidate(json)
  ↓
LlmPlan（强类型 DTO）
  ↓
ComponentPlanCompiler.compile(plan)
  ↓
List<BlockPatch>（AI 的"意图"）
  ↓
PatchFilterPipeline.apply(patches, origin, context)
  ↓
List<BlockPatch>（安全的、可执行的）
  ↓
Preview / Apply
```

### 系统"人格"

✅ **AI = 创意**
✅ **Generator = 工程**
✅ **Tool = 人类意图**
✅ **PatchFilter = 法律**

**任何非法改动 = 物理不存在**

### 核心原则

- ❌ **Generator / LLM 都不可信**
- ✅ **只有 Filter 后的 Patch 才能执行**
- ✅ **Filter 是最后一道闸门**
- ✅ **硬约束，不可绕过**

## 📝 总结

✅ **K3.3：Tool → PatchFilterPipeline（AI 改动裁剪系统）已完全实现**

- PatchFilter（接口）✅
- PatchFilterContext（工具状态快照）✅
- PatchFilterPipeline（主入口）✅
- PatchFilterContextBuilder（构建器）✅
- ForbiddenZoneFilter（禁区裁剪）✅
- OutlineClipFilter（轮廓裁切）✅
- SelectionOnlyFilter（只修改选区）✅
- SymmetryFilter（对称 / 镜像）✅

**系统现在可以从"AI 玩具"推进到"可靠工具"！**

**这是 Formacraft 从"能生成"到"可靠工具"的分水岭！**

