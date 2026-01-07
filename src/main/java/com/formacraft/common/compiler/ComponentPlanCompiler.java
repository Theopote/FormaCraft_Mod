package com.formacraft.common.compiler;

import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.generator.ComponentGenerator;
import com.formacraft.common.generator.GeneratorRegistry;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.GlobalConstraints;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.Slot;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.FormacraftMod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ComponentPlanCompiler（组件计划编译器）
 * 
 * 核心职责：把 LLM 的 components[] 编译为 List<BlockPatch>
 * 
 * 核心原则：
 * - ❌ LLM 永远不直接 SetBlock
 * - ✅ LLM 只描述 "我想要什么构件"
 * - 🧠 Java 端负责 "怎么在 Minecraft 里实现"
 * 
 * 完整链路：
 * LLM JSON (components[]) → ComponentPlanCompiler → SemanticComponent → 
 * ComponentGenerator → List<BlockPatch> → Preview / Apply
 */
public final class ComponentPlanCompiler {

    private ComponentPlanCompiler() {}

    /**
     * 编译 LLM Plan 为 BlockPatch 列表
     * 
     * @param plan LLM 输出的 Plan
     * @return BlockPatch 列表（相对 plan.anchor）
     */
    public static List<BlockPatch> compile(LlmPlan plan) {
        List<BlockPatch> result = new ArrayList<>();

        if (plan == null) {
            FormacraftMod.LOGGER.warn("ComponentPlanCompiler: plan is null");
            return result;
        }

        if (plan.components() == null || plan.components().isEmpty()) {
            FormacraftMod.LOGGER.info("ComponentPlanCompiler: no components to compile");
            return result;
        }

        // 索引 slots（便于快速查找）
        Map<String, Slot> slotMap = indexSlots(plan);

        // 遍历所有 components
        for (Component c : plan.components()) {
            if (c == null) continue;

            // 查找对应的 slot
            Slot slot = slotMap.get(c.slotId());
            if (slot == null) {
                // 没有 slot 的 component，默认使用全局 anchor
                slot = defaultSlot(plan);
                FormacraftMod.LOGGER.debug("ComponentPlanCompiler: component {} has no slot, using default slot", c.componentType());
            }

            // 创建语义构件
            SemanticComponent semantic = new SemanticComponent(
                    c.componentType(),
                    slot,
                    c
            );

            // 获取生成器
            ComponentGenerator generator = GeneratorRegistry.getGenerator(c.componentType());

            if (generator == null) {
                // ⚠️ 非致命错误：未知构件类型
                FormacraftMod.LOGGER.warn("[FormaCraft] No generator for component: {}", c.componentType());
                continue;
            }

            // 生成 BlockPatch
            try {
                List<BlockPatch> patches = generator.generate(semantic);
                if (patches != null) {
                    result.addAll(patches);
                }
            } catch (Exception e) {
                FormacraftMod.LOGGER.error("ComponentPlanCompiler: error generating component {}: {}", 
                        c.componentType(), e.getMessage(), e);
            }
        }

        FormacraftMod.LOGGER.info("ComponentPlanCompiler: compiled {} components into {} patches", 
                plan.components().size(), result.size());

        return result;
    }

    /**
     * 索引 slots（便于快速查找）
     */
    private static Map<String, Slot> indexSlots(LlmPlan plan) {
        Map<String, Slot> map = new HashMap<>();
        if (plan.layout() == null || plan.layout().slots() == null) {
            return map;
        }

        for (Slot s : plan.layout().slots()) {
            if (s != null && s.slotId() != null) {
                map.put(s.slotId(), s);
            }
        }
        return map;
    }

    /**
     * 创建默认 slot（使用全局 anchor）
     */
    private static Slot defaultSlot(LlmPlan plan) {
        GlobalConstraints.Facing facing = (plan.globalConstraints() != null && plan.globalConstraints().facing() != null)
                ? plan.globalConstraints().facing()
                : null;

        return new Slot(
                "__global__",
                plan.anchor(),
                facing,
                "default",
                null,
                null
        );
    }
}

