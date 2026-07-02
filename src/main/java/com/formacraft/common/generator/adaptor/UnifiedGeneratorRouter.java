package com.formacraft.common.generator.adaptor;

import com.formacraft.FormacraftMod;
import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.generator.ComponentGenerator;
import com.formacraft.common.generator.ComponentGeneratorRegistry;
import com.formacraft.common.json.JsonUtil;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.skeleton.ExecutableSkeletonPlan;
import com.formacraft.common.skeleton.SkeletonExecutors;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 构件层统一路由门面（Phase 2）。
 * <p>
 * 按优先级选择生成路径：
 * <ol>
 *   <li>组件内嵌骨架（{@code params.skeleton} / {@code skeleton:} feature）→ {@link SkeletonExecutors}</li>
 *   <li>已注册的 {@link ComponentGenerator}（{@link ComponentGeneratorRegistry}）</li>
 *   <li>玩家组件扩展（{@code group_request:} / {@code component_request:}）</li>
 *   <li>整栋生成器回退（{@link StructureGeneratorAdaptor}）— 仅显式请求或未注册整栋类型</li>
 * </ol>
 * <p>
 * 安全策略：已注册组件生成器返回空结果时，<b>不会</b>自动回退到整栋生成器，
 * 避免完整建筑覆盖同 plan 内其他组件。回退需通过 {@code landmark:}、{@code structure_generator:}
 * 或 {@code params.landmark/template/assembly/blueprint} 显式启用。
 */
public final class UnifiedGeneratorRouter {

    private static final double ARCHETYPE_STRONG_THRESHOLD = 0.85;

    private static final Set<String> WHOLE_BUILDING_TYPES = Set.of(
            "HOUSE", "CASTLE", "COMPOUND", "LANDMARK", "BUILDING"
    );

    private UnifiedGeneratorRouter() {}

    public static List<BlockPatch> generate(SemanticComponent semantic, ServerWorld world) {
        if (semantic == null || semantic.source() == null) {
            return new ArrayList<>();
        }

        Component c = semantic.source();
        String componentType = c.componentType();

        // 1) 组件级骨架（优先于几何生成器）
        List<BlockPatch> skeletonPatches = trySkeletonPath(semantic, world);
        if (!skeletonPatches.isEmpty()) {
            FormacraftMod.LOGGER.debug("UnifiedGeneratorRouter: skeleton path for {}", componentType);
            return skeletonPatches;
        }

        boolean hasGroupRequest = hasFeaturePrefix(c, "group_request:");
        boolean hasComponentRequest = hasFeaturePrefix(c, "component_request:");

        // 2) ComponentGenerator
        List<BlockPatch> base = tryComponentGenerator(semantic, componentType);

        // 3) 玩家组件扩展
        base = applyExpanders(semantic, world, base, hasGroupRequest, hasComponentRequest);

        if (!base.isEmpty()) {
            return base;
        }

        // 4) StructureGenerator 回退（受控）
        if (world != null && shouldUseStructureFallback(semantic)) {
            List<BlockPatch> structurePatches = StructureGeneratorAdaptor.tryGenerate(semantic, world);
            if (!structurePatches.isEmpty()) {
                FormacraftMod.LOGGER.debug("UnifiedGeneratorRouter: structure fallback for {}", componentType);
                return structurePatches;
            }
        }

        FormacraftMod.LOGGER.debug("UnifiedGeneratorRouter: no patches for component type {}, skipping", componentType);
        return new ArrayList<>();
    }

    private static List<BlockPatch> tryComponentGenerator(SemanticComponent semantic, String componentType) {
        ComponentGenerator generator = ComponentGeneratorRegistry.getGenerator(componentType);
        if (generator == null) {
            return new ArrayList<>();
        }
        try {
            List<BlockPatch> patches = generator.generate(semantic);
            if (patches != null && !patches.isEmpty()) {
                FormacraftMod.LOGGER.debug("UnifiedGeneratorRouter: component generator for {}", componentType);
                return new ArrayList<>(patches);
            }
        } catch (Exception e) {
            FormacraftMod.LOGGER.warn("UnifiedGeneratorRouter: component generator failed for {}, skipping",
                    componentType, e);
        }
        return new ArrayList<>();
    }

    private static List<BlockPatch> applyExpanders(
            SemanticComponent semantic,
            ServerWorld world,
            List<BlockPatch> base,
            boolean hasGroupRequest,
            boolean hasComponentRequest
    ) {
        if (hasGroupRequest) {
            try {
                List<BlockPatch> expanded = com.formacraft.common.component.group.PlayerComponentGroupExpander
                        .tryExpand(semantic, world);
                if (expanded != null) {
                    if (base.isEmpty()) {
                        return expanded;
                    }
                    return expanded;
                }
            } catch (Throwable t) {
                FormacraftMod.LOGGER.warn("UnifiedGeneratorRouter: PlayerComponentGroupExpander failed, skipping", t);
            }
        }

        if (hasComponentRequest) {
            try {
                List<BlockPatch> expanded = com.formacraft.common.component.PlayerComponentExpander
                        .tryExpand(semantic, world);
                if (expanded != null) {
                    if (base.isEmpty()) {
                        return expanded;
                    }
                    if (!expanded.isEmpty()) {
                        List<BlockPatch> merged = new ArrayList<>(base.size() + expanded.size());
                        merged.addAll(base);
                        merged.addAll(expanded);
                        return merged;
                    }
                }
            } catch (Throwable t) {
                FormacraftMod.LOGGER.warn("UnifiedGeneratorRouter: PlayerComponentExpander failed, skipping", t);
            }
        }

        return base;
    }

    private static List<BlockPatch> trySkeletonPath(SemanticComponent semantic, ServerWorld world) {
        if (world == null || !SkeletonExecutors.isRegistered()) {
            return List.of();
        }

        ExecutableSkeletonPlan plan = parseSkeletonFromComponent(semantic.source());
        if (plan == null) {
            return List.of();
        }

        SlotAnchor anchor = resolveSlotAnchor(semantic);
        if (anchor == null) {
            return List.of();
        }

        String paletteId = semantic.styleProfile();
        if (paletteId == null || paletteId.isBlank()) {
            paletteId = "DEFAULT";
        }

        try {
            return SkeletonExecutors.get().build(world, anchor.worldPos, plan, paletteId);
        } catch (Exception e) {
            FormacraftMod.LOGGER.warn("UnifiedGeneratorRouter: skeleton build failed for {}", semantic.componentType(), e);
            return List.of();
        }
    }

    private static ExecutableSkeletonPlan parseSkeletonFromComponent(Component c) {
        if (c == null) {
            return null;
        }

        Map<String, Object> params = c.params();
        if (params != null) {
            Object sk = params.get("skeleton");
            if (sk instanceof Map<?, ?> map) {
                @SuppressWarnings("unchecked")
                ExecutableSkeletonPlan plan = ExecutableSkeletonPlan.fromMap((Map<String, Object>) map);
                if (plan != null) {
                    return plan;
                }
            }
        }

        String json = extractFeaturePayload(c, "skeleton:");
        if (json != null && !json.isBlank()) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> map = JsonUtil.fromJson(json, Map.class);
                return ExecutableSkeletonPlan.fromMap(map);
            } catch (Exception e) {
                FormacraftMod.LOGGER.warn("UnifiedGeneratorRouter: invalid skeleton feature json: {}", json, e);
            }
        }
        return null;
    }

    private static boolean shouldUseStructureFallback(SemanticComponent semantic) {
        Component c = semantic.source();
        if (c == null) {
            return false;
        }

        if (hasFeaturePrefix(c, "structure_generator:") || hasFeaturePrefix(c, "landmark:")) {
            return true;
        }

        Map<String, Object> params = c.params();
        if (params != null) {
            if (params.containsKey("landmark")) return true;
            if (params.containsKey("template")) return true;
            if (params.containsKey("blueprint")) return true;
            if (params.containsKey("assembly")) return true;
            if (Boolean.TRUE.equals(params.get("useStructureGenerator"))) return true;
        }

        if (semantic.genome() != null && semantic.genome().archetype != null
                && semantic.genome().archetype.confidence >= ARCHETYPE_STRONG_THRESHOLD) {
            return true;
        }

        String type = normalizeType(c.componentType());
        return !ComponentGeneratorRegistry.hasGenerator(type) && WHOLE_BUILDING_TYPES.contains(type);
    }

    private static SlotAnchor resolveSlotAnchor(SemanticComponent semantic) {
        if (semantic.slot() == null || semantic.slot().anchor() == null) {
            return null;
        }
        var a = semantic.slot().anchor();
        return new SlotAnchor(new BlockPos(a.x(), a.y(), a.z()));
    }

    private static String extractFeaturePayload(Component component, String prefix) {
        if (component == null || prefix == null) {
            return null;
        }
        List<String> features = component.features();
        if (features == null || features.isEmpty()) {
            return null;
        }
        String p = prefix.toLowerCase(Locale.ROOT);
        for (String feature : features) {
            if (feature == null) continue;
            String lower = feature.toLowerCase(Locale.ROOT);
            if (lower.startsWith(p)) {
                return feature.substring(prefix.length()).trim();
            }
        }
        return null;
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

    private static String normalizeType(String componentType) {
        if (componentType == null) return "";
        return componentType.trim().toUpperCase(Locale.ROOT);
    }

    private record SlotAnchor(BlockPos worldPos) {}
}
