package com.formacraft.common.generator.adaptor;

import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.generator.ComponentGenerator;
import com.formacraft.common.generator.GeneratorRegistry;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.FormacraftMod;
import net.minecraft.server.world.ServerWorld;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * SmartGeneratorRouter（智能生成器路由）
 * <p>
 * 自动选择最适合的生成器：
 * 1. 优先使用新系统（common.generator）的 ComponentGenerator
 * 2. 如果新系统没有生成器或返回空结果，直接返回空列表（不回退到传统系统）
 * <p>
 * 重要说明：
 * - 传统系统（server.generator）是为完整建筑设计的，不适合组件级别的生成
 * - 在 LlmPlan 流程中，每个组件应该只使用新系统的 ComponentGenerator
 * - 如果某个组件没有生成器或返回空结果，该组件将被跳过，不会影响其他组件
 * <p>
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
            return new ArrayList<>();
        }

        Component c = semantic.source();
        String componentType = c.componentType();
        boolean hasGroupRequest = hasFeaturePrefix(c, "group_request:");
        boolean hasComponentRequest = hasFeaturePrefix(c, "component_request:");

        // 只使用新系统（common.generator）的 ComponentGenerator
        ComponentGenerator newSystemGenerator = GeneratorRegistry.getGenerator(componentType);
        List<BlockPatch> base = new ArrayList<>();
        if (newSystemGenerator != null) {
            try {
                List<BlockPatch> patches = newSystemGenerator.generate(semantic);
                if (patches != null && !patches.isEmpty()) {
                    FormacraftMod.LOGGER.debug("SmartGeneratorRouter: using new system generator for {}", componentType);
                    base.addAll(patches);
                }
            } catch (Exception e) {
                FormacraftMod.LOGGER.warn("SmartGeneratorRouter: new system generator failed for {}, skipping component", 
                        componentType, e);
            }
        }

        if (hasGroupRequest) {
            try {
                List<BlockPatch> expanded = com.formacraft.common.component.group.PlayerComponentGroupExpander.tryExpand(semantic, world);
                if (expanded != null) {
                    if (base.isEmpty()) {
                        return expanded;
                    }
                    // group_request 默认视为替换；若要叠加在主结构上，请改用 component_request
                    return expanded;
                }
            } catch (Throwable t) {
                FormacraftMod.LOGGER.warn("SmartGeneratorRouter: PlayerComponentGroupExpander failed, skipping", t);
            }
        }

        if (hasComponentRequest) {
            try {
                List<BlockPatch> expanded = com.formacraft.common.component.PlayerComponentExpander.tryExpand(semantic, world);
                if (expanded != null) {
                    if (base.isEmpty()) {
                        return expanded;
                    }
                    if (!expanded.isEmpty()) {
                        base.addAll(expanded);
                    }
                }
            } catch (Throwable t) {
                FormacraftMod.LOGGER.warn("SmartGeneratorRouter: PlayerComponentExpander failed, skipping", t);
            }
        }

        if (!base.isEmpty()) {
            return base;
        }

        FormacraftMod.LOGGER.debug("SmartGeneratorRouter: no generator patches for component type: {}, skipping", componentType);
        return new ArrayList<>();
    }

    /**
     * 检查是否应该使用传统系统
     * <p>
     * 某些组件类型更适合使用传统系统：
     * - HOUSE, CASTLE 等复杂建筑
     * - 地标建筑（土楼、埃菲尔铁塔等）
     */
    /**
     * @deprecated Legacy policy 已弃用。当前流程不再回退到传统系统。
     * 此方法仅保留用于未来扩展/调试目的，不在当前流程中启用。
     * 
     * @param componentType 组件类型
     * @return 始终返回 false（不再使用传统系统）
     */
    @Deprecated
    public static boolean shouldUseTraditionalSystem(String componentType) {
        if (componentType == null) return false;
        
        String type = componentType.toUpperCase();
        
        // 复杂建筑类型，优先使用传统系统
        return type.equals("HOUSE") || 
               type.equals("CASTLE") || 
               type.equals("KEEP") ||
               type.equals("COMPOUND");
    }

    private static boolean hasFeaturePrefix(Component component, String prefix) {
        if (component == null || prefix == null) return false;
        List<String> features = component.features();
        if (features == null || features.isEmpty()) return false;
        String p = prefix.toLowerCase(Locale.ROOT);
        for (String feature : features) {
            if (feature == null) continue;
            if (feature.toLowerCase(Locale.ROOT).startsWith(p)) {
                return true;
            }
        }
        return false;
    }
}

