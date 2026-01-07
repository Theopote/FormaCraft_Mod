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
 * 2. 如果新系统没有生成器或功能不够，自动回退到传统系统（server.generator）
 * 3. 用户无需关心后台有两套系统
 * 
 * 设计原则：
 * - 用户透明：用户不需要知道有两套系统
 * - 自动选择：系统自动选择最适合的生成器
 * - 向后兼容：保持现有功能不变
 */
public final class SmartGeneratorRouter {

    private SmartGeneratorRouter() {}

    /**
     * 智能生成 BlockPatch
     * 
     * @param semantic 语义组件
     * @param world 服务器世界（可选，用于传统系统）
     * @return BlockPatch 列表
     */
    public static List<BlockPatch> generate(SemanticComponent semantic, ServerWorld world) {
        if (semantic == null || semantic.source() == null) {
            return new java.util.ArrayList<>();
        }

        Component c = semantic.source();
        String componentType = c.componentType();

        // 1. 优先使用新系统（common.generator）
        ComponentGenerator newSystemGenerator = GeneratorRegistry.getGenerator(componentType);
        if (newSystemGenerator != null) {
            try {
                List<BlockPatch> patches = newSystemGenerator.generate(semantic);
                if (patches != null && !patches.isEmpty()) {
                    FormacraftMod.LOGGER.debug("SmartGeneratorRouter: using new system generator for {}", componentType);
                    return patches;
                }
            } catch (Exception e) {
                FormacraftMod.LOGGER.warn("SmartGeneratorRouter: new system generator failed for {}, trying fallback", 
                        componentType, e);
            }
        }

        // 2. 回退到传统系统（server.generator）
        if (world != null) {
            StructureGeneratorAdaptor adaptor = StructureGeneratorAdaptor.createFor(componentType);
            if (adaptor != null) {
                try {
                    List<BlockPatch> patches = adaptor.generate(semantic, world);
                    if (patches != null && !patches.isEmpty()) {
                        FormacraftMod.LOGGER.debug("SmartGeneratorRouter: using traditional system generator for {}", componentType);
                        return patches;
                    }
                } catch (Exception e) {
                    FormacraftMod.LOGGER.warn("SmartGeneratorRouter: traditional system generator failed for {}", 
                            componentType, e);
                }
            }
        }

        // 3. 如果都失败了，返回空列表
        FormacraftMod.LOGGER.warn("SmartGeneratorRouter: no generator available for {}", componentType);
        return new java.util.ArrayList<>();
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

