package com.formacraft.common.generator.adaptor;

import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.generator.ComponentGenerator;
import com.formacraft.common.generator.GeneratorRegistry;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.FormacraftMod;
import net.minecraft.server.world.ServerWorld;

import java.util.List;

/**
 * SmartGeneratorRouter（智能生成器路由）
 * 
 * 自动选择最适合的生成器：
 * 1. 优先使用新系统（common.generator）的 ComponentGenerator
 * 2. 如果新系统没有生成器或返回空结果，直接返回空列表（不回退到传统系统）
 * 
 * 重要说明：
 * - 传统系统（server.generator）是为完整建筑设计的，不适合组件级别的生成
 * - 在 LlmPlan 流程中，每个组件应该只使用新系统的 ComponentGenerator
 * - 如果某个组件没有生成器或返回空结果，该组件将被跳过，不会影响其他组件
 * 
 * 设计原则：
 * - 组件级别只使用新系统，避免生成完整建筑覆盖其他组件
 * - 如果组件无法生成，静默跳过（返回空列表）
 */
public final class SmartGeneratorRouter {

    private SmartGeneratorRouter() {}

    /**
     * 智能生成 BlockPatch
     * 
     * @param semantic 语义组件
     * @param world 服务器世界（保留参数以保持接口兼容性，但不再用于回退）
     * @return BlockPatch 列表
     */
    public static List<BlockPatch> generate(SemanticComponent semantic, ServerWorld world) {
        if (semantic == null || semantic.source() == null) {
            return new java.util.ArrayList<>();
        }

        Component c = semantic.source();
        String componentType = c.componentType();

        // 只使用新系统（common.generator）的 ComponentGenerator
        ComponentGenerator newSystemGenerator = GeneratorRegistry.getGenerator(componentType);
        if (newSystemGenerator != null) {
            try {
                List<BlockPatch> patches = newSystemGenerator.generate(semantic);
                if (patches != null && !patches.isEmpty()) {
                    FormacraftMod.LOGGER.debug("SmartGeneratorRouter: using new system generator for {}", componentType);
                    return patches;
                } else {
                    // 生成器返回空结果，静默跳过（不记录警告，因为这是正常的）
                    FormacraftMod.LOGGER.debug("SmartGeneratorRouter: generator {} returned empty patches for component {}, skipping", 
                            componentType, componentType);
                    return new java.util.ArrayList<>();
                }
            } catch (Exception e) {
                FormacraftMod.LOGGER.warn("SmartGeneratorRouter: new system generator failed for {}, skipping component", 
                        componentType, e);
                return new java.util.ArrayList<>();
            }
        } else {
            // 没有注册生成器，静默跳过（不记录警告，因为这是正常的）
            FormacraftMod.LOGGER.debug("SmartGeneratorRouter: no generator registered for component type: {}, skipping", componentType);
            return new java.util.ArrayList<>();
        }
    }

    /**
     * 检查是否应该使用传统系统
     * 
     * 某些组件类型更适合使用传统系统：
     * - HOUSE, CASTLE 等复杂建筑
     * - 地标建筑（土楼、埃菲尔铁塔等）
     */
    public static boolean shouldUseTraditionalSystem(String componentType) {
        if (componentType == null) return false;
        
        String type = componentType.toUpperCase();
        
        // 复杂建筑类型，优先使用传统系统
        return type.equals("HOUSE") || 
               type.equals("CASTLE") || 
               type.equals("KEEP") ||
               type.equals("COMPOUND");
    }
}

