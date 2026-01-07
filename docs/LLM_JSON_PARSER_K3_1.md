# K3.1：LLM JSON Parser 实现总结

## ✅ 已完全实现

### 1. DTO 结构（强类型）

#### LlmPlan（顶层结构）
- **位置**：`src/main/java/com/formacraft/common/llm/dto/LlmPlan.java`
- **字段**：
  - `mode` - 模式（build/patch）
  - `styleProfile` - 风格配置
  - `anchor` - 锚点
  - `globalConstraints` - 全局约束
  - `layout` - 布局信息
  - `components` - 组件列表
  - `targetSlotId` - patch 专用（可选）
  - `allowedArea` - patch 专用（可选）
  - `patch` - block-level patch（可选）

#### Vec3i（坐标）
- **位置**：`src/main/java/com/formacraft/common/llm/dto/Vec3i.java`
- **字段**：`x`, `y`, `z`

#### GlobalConstraints（全局约束）
- **位置**：`src/main/java/com/formacraft/common/llm/dto/GlobalConstraints.java`
- **字段**：
  - `facing` - 朝向（NORTH/SOUTH/EAST/WEST）
  - `symmetry` - 对称（NONE/MIRROR_X/MIRROR_Z/RADIAL）
  - `terrainStrategy` - 地形策略（FOLLOW/PAD_PER_BUILDING/TERRACE/FLATTEN_ALL）

#### Layout（布局）
- **位置**：`src/main/java/com/formacraft/common/llm/dto/Layout.java`
- **字段**：
  - `skeletonType` - 骨架类型
  - `pathBased` - 是否基于路径
  - `slots` - 槽位列表

#### Slot（槽位）
- **位置**：`src/main/java/com/formacraft/common/llm/dto/Slot.java`
- **字段**：
  - `slotId` - 槽位 ID
  - `anchor` - 锚点
  - `facing` - 朝向
  - `program` - 建筑功能
  - `componentPresetId` - 组件预设 ID
  - `componentPreset` - 组件预设描述

#### Component（组件）
- **位置**：`src/main/java/com/formacraft/common/llm/dto/Component.java`
- **字段**：
  - `componentType` - 组件类型
  - `slotId` - 所属槽位 ID
  - `relativePosition` - 相对位置
  - `dimensions` - 尺寸
  - `features` - 特性列表

#### Dimensions（尺寸）
- **位置**：`src/main/java/com/formacraft/common/llm/dto/Dimensions.java`
- **字段**：`width`, `depth`, `height`

#### PatchBlockSection（Block-level Patch）
- **位置**：`src/main/java/com/formacraft/common/llm/dto/PatchBlockSection.java`
- **字段**：
  - `origin` - 原点
  - `blocks` - 方块列表

#### PatchBlock（单个方块 Patch）
- **位置**：`src/main/java/com/formacraft/common/llm/dto/PatchBlock.java`
- **字段**：
  - `action` - 操作（place/remove/replace）
  - `dx`, `dy`, `dz` - 相对坐标
  - `targetBlock` - 目标方块 ID

### 2. Parser（解析 + 校验）

#### LlmPlanParser（核心解析器）
- **位置**：`src/main/java/com/formacraft/common/llm/parser/LlmPlanParser.java`
- **功能**：
  - `parseAndValidate()` - 解析 + 校验（推荐）
  - `parse()` - 只解析不校验（不推荐）
- **校验规则**：
  - 必填字段：`mode`, `anchor`
  - Slot 校验：`slot_id` 唯一，`anchor` 不为空
  - Component 校验：`component_type`, `relative_position`, `dimensions` 必填，尺寸 > 0
  - Patch 模式：必须有 `components[]` 或 `patch.blocks[]`

#### PlanParseException（解析异常）
- **位置**：`src/main/java/com/formacraft/common/llm/parser/PlanParseException.java`
- **用途**：在 ChatPanel 中显示错误信息，方便调试 prompt

### 3. Bridge（桥接器）

#### BlockPatchBridge（BlockPatch 转换器）
- **位置**：`src/main/java/com/formacraft/common/llm/bridge/BlockPatchBridge.java`
- **功能**：
  - `toOrigin()` - 将 PatchBlockSection.origin 转换为 BlockPos
  - `toBlockPatches()` - 将 PatchBlockSection 转换为 BlockPatch 列表
  - `normalizeAction()` - 标准化 action（place/remove/replace）
  - `safeTarget()` - 安全的 targetBlock（提供默认值）

### 4. 使用示例

```java
import com.formacraft.common.llm.parser.LlmPlanParser;
import com.formacraft.common.llm.parser.PlanParseException;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.bridge.BlockPatchBridge;
import com.formacraft.common.network.FormaCraftNetworking;
import net.minecraft.util.math.BlockPos;

public void onLlmResponse(String json) {
    try {
        LlmPlan plan = LlmPlanParser.parseAndValidate(json);

        if (plan.mode() == LlmPlan.Mode.patch && plan.patch() != null) {
            BlockPos origin = BlockPatchBridge.toOrigin(plan.patch());
            var patches = BlockPatchBridge.toBlockPatches(plan.patch());
            // 直接走你现在的 patch preview
            // BuildConfirmPanel.INSTANCE.showPatchPreview(origin, patches);
            FormaCraftNetworking.sendPatchApply(origin, patches);
            return;
        }

        // build 模式：把 plan 交给你的 Skeleton/Generator 管线
        // generatorPipeline.generate(plan);

    } catch (PlanParseException e) {
        // 在 ChatPanel 输出错误给用户（方便调 prompt）
        // chatLog.append("LLM JSON parse error: " + e.getMessage());
    }
}
```

## 📋 Gradle 依赖

需要在 `build.gradle` 中添加 Jackson 依赖：

```gradle
dependencies {
    implementation "com.fasterxml.jackson.core:jackson-databind:2.17.1"
    implementation "com.fasterxml.jackson.core:jackson-annotations:2.17.1"
}
```

## 🔗 关键设计决策

### 1. 强类型 DTO

- ✅ **使用 record**：不可变、简洁
- ✅ **Jackson 注解**：`@JsonProperty` 映射 JSON 字段
- ✅ **忽略未知字段**：`@JsonIgnoreProperties(ignoreUnknown = true)`

### 2. 校验策略

- ✅ **必填字段校验**：`mode`, `anchor`
- ✅ **可选字段允许为空**：`styleProfile`, `globalConstraints`（后端给默认）
- ✅ **Slot 唯一性校验**：`slot_id` 必须唯一
- ✅ **Component 尺寸校验**：`width`, `depth`, `height` 必须 > 0
- ✅ **Patch 模式约束**：必须有 `components[]` 或 `patch.blocks[]`

### 3. 桥接器设计

- ✅ **标准化 action**：`place`/`remove`/`replace` → `BlockPatch.PLACE`/`REMOVE`/`REPLACE`
- ✅ **默认值处理**：`targetBlock` 为空时使用 `minecraft:stone`
- ✅ **兼容现有系统**：直接转换为 `BlockPatch`，无缝对接

## 📝 总结

✅ **K3.1：LLM JSON Parser 已完全实现**

- DTO 结构（强类型）✅
- Parser（解析 + 校验）✅
- Bridge（BlockPatch 转换器）✅
- 异常处理（PlanParseException）✅

**系统现在可以完整解析 LLM 输出的 JSON，并转换为现有的 BlockPatch 系统！**

**这是专业 AI 建筑生成系统的核心能力！**

