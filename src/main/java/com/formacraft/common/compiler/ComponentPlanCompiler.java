package com.formacraft.common.compiler;

import com.formacraft.common.compiler.postprocess.PostProcessContext;
import com.formacraft.common.compiler.postprocess.PostProcessPipeline;
import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.generator.adaptor.SmartGeneratorRouter;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.GlobalConstraints;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.Slot;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.FormacraftMod;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import com.formacraft.common.terrain.TerrainStrategySampler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ComponentPlanCompiler（组件计划编译器）
 * <p>
 * 核心职责：把 LLM 的 components[] 编译为 List<BlockPatch>
 * <p>
 * 核心原则：
 * - ❌ LLM 永远不直接 SetBlock
 * - ✅ LLM 只描述 "我想要什么构件"
 * - 🧠 Java 端负责 "怎么在 Minecraft 里实现"
 * <p>
 * 完整链路：
 * LLM JSON (components[]) → ComponentPlanCompiler → SemanticComponent → 
 * ComponentGenerator → List<BlockPatch> → Preview / Apply
 */
public final class ComponentPlanCompiler {

    private ComponentPlanCompiler() {}

    /**
     * 编译 LLM Plan 为 BlockPatch 列表（基础版本，不包含后处理）
     * 
     * @param plan LLM 输出的 Plan
     * @return BlockPatch 列表（相对 plan.anchor）
     */
    public static List<BlockPatch> compile(LlmPlan plan) {
        return compile(plan, null, null, null);
    }

    /**
     * 编译 LLM Plan 为 BlockPatch 列表（完整版本，包含后处理）
     * 
     * @param plan LLM 输出的 Plan
     * @param globalAnchor 全局 anchor（世界坐标，用于地形适应）
     * @param world 服务器世界（用于地形适应，可选）
     * @param terrainSampler 地形采样器（用于地形适应，可选）
     * @return BlockPatch 列表（相对 plan.anchor）
     */
    public static List<BlockPatch> compile(
            LlmPlan plan,
            BlockPos globalAnchor,
            ServerWorld world,
            TerrainStrategySampler terrainSampler
    ) {
        return compile(plan, globalAnchor, world, terrainSampler, true);
    }

    /**
     * 编译 LLM Plan 为 BlockPatch 列表（完整版本，可选择是否应用地形适应）
     */
    public static List<BlockPatch> compile(
            LlmPlan plan,
            BlockPos globalAnchor,
            ServerWorld world,
            TerrainStrategySampler terrainSampler,
            boolean applyTerrainAdaptation
    ) {
        List<BlockPatch> result = new ArrayList<>();

        if (plan == null) {
            FormacraftMod.LOGGER.warn("ComponentPlanCompiler: plan is null");
            return result;
        }

        // 索引 slots（便于快速查找）
        Map<String, Slot> slotMap = indexSlots(plan);
        List<Component> components = prepareComponents(plan, slotMap);

        if (components.isEmpty()) {
            FormacraftMod.LOGGER.info("ComponentPlanCompiler: no components to compile");
            return result;
        }

        // 遍历所有 components
        for (Component c : components) {
            if (c == null) continue;

            // 查找对应的 slot
            Slot slot = slotMap.get(c.slotId());
            if (slot == null) {
                // 没有 slot 的 component，默认使用全局 anchor
                slot = defaultSlot(plan);
                FormacraftMod.LOGGER.debug("ComponentPlanCompiler: component {} has no slot, using default slot", c.componentType());
            }

            // 创建语义构件（传递 styleProfile 和 styleAttributes）
            String styleProfile = plan.styleProfile();
            com.formacraft.common.llm.dto.StyleAttributes styleAttributes = plan.styleAttributes();
            com.formacraft.common.genome.BuildingGenome genome = plan.genome();
            SemanticComponent semantic = new SemanticComponent(
                    c.componentType(),
                    slot,
                    c,
                    styleProfile,
                    styleAttributes,
                    genome
            );

            // 使用智能路由：自动选择最适合的生成器
            // 优先使用新系统，如果失败或不存在，自动回退到传统系统
            List<BlockPatch> patches;
            try {
                patches = SmartGeneratorRouter.generate(semantic, world);
                if (!patches.isEmpty()) {
                    // 调整 BlockPatch 坐标：组件生成器返回的坐标是相对于 slot anchor 的
                    // 但 BlockPatch 的坐标应该是相对于 plan.anchor() 的
                    // slot.anchor() 已经是相对于 plan.anchor() 的，所以直接加上即可
                    com.formacraft.common.llm.dto.Vec3i slotAnchor = slot.anchor();
                    
                    if (slotAnchor != null) {
                        // 调整所有 patches 的坐标：slotAnchor（相对于 plan anchor）+ patch.dx/dy/dz（相对于 slot anchor）
                        for (BlockPatch patch : patches) {
                            if (patch != null) {
                                result.add(new BlockPatch(
                                        patch.action(),
                                        slotAnchor.x() + patch.dx(),
                                        slotAnchor.y() + patch.dy(),
                                        slotAnchor.z() + patch.dz(),
                                        patch.targetBlock()
                                ));
                            }
                        }
                    } else {
                        // 如果 slot anchor 信息不完整，直接添加 patches（保持向后兼容）
                        FormacraftMod.LOGGER.warn("ComponentPlanCompiler: missing slotAnchor, component={}", c.componentType());
                        result.addAll(patches);
                    }
                } else {
                    FormacraftMod.LOGGER.warn("ComponentPlanCompiler: no patches generated for component: {}", 
                            c.componentType());
                }
            } catch (Exception e) {
                FormacraftMod.LOGGER.error("ComponentPlanCompiler: error generating component {}: {}", 
                        c.componentType(), e.getMessage(), e);
            }
        }

        FormacraftMod.LOGGER.info("ComponentPlanCompiler: compiled {} components into {} patches", 
                components.size(), result.size());

        // 后处理步骤
        if (globalAnchor != null) {
            PostProcessContext context = PostProcessContext.create(plan, globalAnchor);
            PostProcessPipeline pipeline;
            
            if (applyTerrainAdaptation && world != null && terrainSampler != null) {
                // 包含地形适应的完整管道
                pipeline = PostProcessPipeline.createWithTerrain(context, world, terrainSampler);
            } else {
                // 基础管道（不包含地形适应）
                pipeline = PostProcessPipeline.createDefault(context);
            }
            
            result = pipeline.process(result, context);
            
            FormacraftMod.LOGGER.info("ComponentPlanCompiler: post-processed to {} patches", result.size());
        }

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

    private static List<Component> prepareComponents(LlmPlan plan, Map<String, Slot> slotMap) {
        List<Component> components = new ArrayList<>();
        if (plan.components() != null) {
            components.addAll(plan.components());
        }
        if (components.isEmpty()) {
            return components;
        }

        Set<String> slotsWithFacade = new HashSet<>();
        Set<String> slotsWithEntrance = new HashSet<>();
        Set<String> slotsWithRoof = new HashSet<>();
        for (Component c : components) {
            if (c == null) continue;
            String type = normalizeType(c.componentType());
            String slotKey = slotKey(c);
            if ("FACADE_WINDOWS".equals(type)) {
                slotsWithFacade.add(slotKey);
            } else if ("ENTRANCE".equals(type)) {
                slotsWithEntrance.add(slotKey);
            } else if (isRoofType(type)) {
                slotsWithRoof.add(slotKey);
            }
        }

        List<Component> inferred = new ArrayList<>();
        List<Component> prepared = new ArrayList<>(components.size());
        for (Component c : components) {
            if (c == null) continue;
            String type = normalizeType(c.componentType());
            if (!isMassType(type)) {
                prepared.add(c);
                continue;
            }
            String slotKey = slotKey(c);
            String slotId = c.slotId();
            Slot slot = slotId != null ? slotMap.get(slotId) : null;
            GlobalConstraints.Facing facing = slot != null && slot.facing() != null
                    ? slot.facing()
                    : (plan.globalConstraints() != null ? plan.globalConstraints().facing() : GlobalConstraints.Facing.SOUTH);

            if (!slotsWithFacade.contains(slotKey)) {
                inferred.add(makeFacadeComponent(c, slotId));
                slotsWithFacade.add(slotKey);
            }
            if (!slotsWithEntrance.contains(slotKey)) {
                Component entrance = makeEntranceComponent(c, slotId, facing);
                if (entrance != null) {
                    inferred.add(entrance);
                    slotsWithEntrance.add(slotKey);
                }
            }
            if (!slotsWithRoof.contains(slotKey)) {
                Component roof = makeRoofComponent(plan, c, slotId);
                if (roof != null) {
                    inferred.add(roof);
                    slotsWithRoof.add(slotKey);
                    c = suppressMassRoof(c);
                }
            }
            prepared.add(c);
        }

        if (!inferred.isEmpty()) {
            FormacraftMod.LOGGER.info("ComponentPlanCompiler: inferred {} facade/entrance/roof components", inferred.size());
            prepared.addAll(inferred);
        }

        return prepared;
    }

    private static Component makeFacadeComponent(Component base, String slotId) {
        Map<String, Object> params = new HashMap<>();
        if (base.params() != null) {
            params.putAll(base.params());
        }
        Double ratio = getParamDouble(params, "window_ratio", "windowRatio");
        if (ratio == null) {
            params.put("window_ratio", 0.25);
        }
        params.putIfAbsent("rhythm", "regular");

        List<String> features = new ArrayList<>();
        if (base.features() != null) {
            features.addAll(base.features());
        }
        Dimensions dims = base.dimensions();
        if (dims != null && dims.width() >= 8 && dims.depth() >= 8 && !features.contains("wrap")) {
            features.add("wrap");
        }

        return new Component(
                "FACADE_WINDOWS",
                slotId,
                base.relativePosition(),
                base.dimensions(),
                features,
                params
        );
    }

    private static Component makeEntranceComponent(Component base, String slotId, GlobalConstraints.Facing facing) {
        Dimensions dims = base.dimensions();
        Vec3i rp = base.relativePosition();
        if (dims == null || rp == null) {
            return null;
        }
        int width = Math.max(1, dims.width());
        int depth = Math.max(1, dims.depth());
        int height = Math.max(2, dims.height());

        int entranceWidth = Math.max(3, Math.min(5, Math.max(3, width / 3)));
        entranceWidth = Math.min(entranceWidth, Math.max(3, width - 2));
        if (entranceWidth % 2 == 0) {
            entranceWidth = Math.max(3, entranceWidth - 1);
        }
        int entranceDepth = Math.max(1, Math.min(2, Math.max(1, depth / 4)));
        int entranceHeight = Math.max(3, Math.min(height, Math.max(4, height / 2)));

        int relX = rp.x();
        int relZ = rp.z();
        switch (facing != null ? facing : GlobalConstraints.Facing.SOUTH) {
            case NORTH -> {
                relX = rp.x() + Math.max(0, (width - entranceWidth) / 2);
                relZ = rp.z() + Math.max(0, depth - entranceDepth);
            }
            case EAST -> {
                relX = rp.x();
                relZ = rp.z() + Math.max(0, (depth - entranceDepth) / 2);
            }
            case WEST -> {
                relX = rp.x() + Math.max(0, width - entranceWidth);
                relZ = rp.z() + Math.max(0, (depth - entranceDepth) / 2);
            }
            case SOUTH -> {
                relX = rp.x() + Math.max(0, (width - entranceWidth) / 2);
                relZ = rp.z();
            }
        }

        Map<String, Object> params = new HashMap<>();
        params.put("door_width", Math.max(2, Math.min(entranceWidth - 1, entranceWidth)));
        params.put("door_height", Math.max(2, Math.min(entranceHeight - 1, entranceHeight)));
        params.put("canopy_depth", 1);

        List<String> features = new ArrayList<>();
        features.add("entrance");
        features.add("overhang");
        if (base.features() != null) {
            features.addAll(base.features());
        }

        return new Component(
                "ENTRANCE",
                slotId,
                new Vec3i(relX, rp.y(), relZ),
                new Dimensions(entranceWidth, entranceDepth, entranceHeight),
                features,
                params
        );
    }

    private static Component makeRoofComponent(LlmPlan plan, Component base, String slotId) {
        Dimensions dims = base.dimensions();
        Vec3i rp = base.relativePosition();
        if (dims == null || rp == null) {
            return null;
        }
        int width = Math.max(2, dims.width());
        int depth = Math.max(2, dims.depth());
        int span = Math.max(2, Math.min(width, depth));
        int roofHeight = Math.max(2, Math.min(8, Math.max(2, span / 3)));

        Map<String, Object> params = new HashMap<>();
        if (base.params() != null) {
            params.putAll(base.params());
        }
        String roofType = getParamString(params, "roof_type", "roofType");
        if (roofType == null || roofType.isBlank()) {
            roofType = resolveDefaultRoofType(plan, base);
        }
        if (roofType != null) {
            params.put("roof_type", roofType);
        }
        params.putIfAbsent("roof_height", roofHeight);
        if (isChineseStyle(plan, base)) {
            params.putIfAbsent("overhang", 1);
        }

        List<String> features = new ArrayList<>();
        if (base.features() != null) {
            features.addAll(base.features());
        }
        features.add("roof");

        return new Component(
                "ROOF",
                slotId,
                new Vec3i(rp.x(), rp.y() + Math.max(1, dims.height() - 1), rp.z()),
                new Dimensions(width, depth, roofHeight),
                features,
                params
        );
    }

    private static Component suppressMassRoof(Component base) {
        Map<String, Object> params = base.params();
        if (params != null) {
            params.put("roof_type", "none");
            return base;
        }
        Map<String, Object> next = new HashMap<>();
        next.put("roof_type", "none");
        return new Component(
                base.componentType(),
                base.slotId(),
                base.relativePosition(),
                base.dimensions(),
                base.features(),
                next
        );
    }

    private static String slotKey(Component c) {
        return c.slotId() != null ? c.slotId() : "__global__";
    }

    private static String normalizeType(String value) {
        if (value == null) return "";
        return value.trim().toUpperCase();
    }

    private static boolean isMassType(String type) {
        return "MASS_MAIN".equals(type)
                || "MASS_SECONDARY".equals(type)
                || "MASS_WING".equals(type)
                || "SIDE_WING".equals(type)
                || "MAIN_MASS".equals(type);
    }

    private static boolean isRoofType(String type) {
        if (type == null) return false;
        return type.startsWith("ROOF");
    }

    private static boolean isChineseStyle(LlmPlan plan, Component base) {
        String profile = plan != null ? plan.styleProfile() : null;
        if (profile != null) {
            String upper = profile.toUpperCase();
            if (upper.contains("CHINESE") || upper.contains("HUI")) return true;
        }
        if (base.features() != null) {
            for (String f : base.features()) {
                if (f == null) continue;
                String lower = f.toLowerCase();
                if (lower.contains("chinese") || lower.contains("中式") || lower.contains("徽派")) {
                    return true;
                }
            }
        }
        if (plan != null && plan.genome() != null && plan.genome().culturalStyle != null) {
            String region = plan.genome().culturalStyle.region;
            if (region != null && region.toLowerCase().contains("chinese")) {
                return true;
            }
        }
        return false;
    }

    private static String resolveDefaultRoofType(LlmPlan plan, Component base) {
        if (isChineseStyle(plan, base)) {
            return "gable";
        }
        if (base.features() != null) {
            for (String f : base.features()) {
                if (f == null) continue;
                String lower = f.toLowerCase();
                if (lower.contains("gothic") || lower.contains("cathedral") || lower.contains("church")) {
                    return "gable";
                }
                if (lower.contains("modern") || lower.contains("flat")) {
                    return "flat";
                }
            }
        }
        return "gable";
    }

    private static String getParamString(Map<String, Object> params, String... keys) {
        if (params == null || keys == null) return null;
        for (String key : keys) {
            if (key == null) continue;
            Object v = params.get(key);
            if (v == null) continue;
            String s = String.valueOf(v).trim();
            if (!s.isEmpty()) {
                return s;
            }
        }
        return null;
    }

    private static Double getParamDouble(Map<String, Object> params, String... keys) {
        if (params == null || keys == null) return null;
        for (String key : keys) {
            if (key == null) continue;
            Object v = params.get(key);
            if (v == null) continue;
            if (v instanceof Number n) {
                return n.doubleValue();
            }
            if (v instanceof String s) {
                try {
                    return Double.parseDouble(s.trim());
                } catch (NumberFormatException ignored) {}
            }
        }
        return null;
    }
}

