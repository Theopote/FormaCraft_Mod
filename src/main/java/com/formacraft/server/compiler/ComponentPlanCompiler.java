package com.formacraft.server.compiler;

import com.formacraft.common.generation.component.util.ComponentFacadeRhythmPlanner;
import com.formacraft.common.generation.component.util.ComponentParamParsers;
import com.formacraft.common.compiler.postprocess.PostProcessContext;
import com.formacraft.common.compiler.postprocess.PostProcessPipeline;
import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.generation.component.ComponentGeneratorRegistry;
import com.formacraft.server.generation.GenerationHub;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.GlobalConstraints;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.Slot;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.style.StyleIntentResolver;
import com.formacraft.common.proportion.OpeningGrammarResolver;
import com.formacraft.FormacraftMod;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import com.formacraft.common.terrain.TerrainStrategySampler;
import com.formacraft.server.assembly.AssemblySpec;
import com.formacraft.server.assembly.MetaAssemblyCompiler;
import com.formacraft.server.assembly.MetaAssemblyEngine;
import com.formacraft.server.assembly.macro.AssemblyMacroApplier;
import com.formacraft.server.assembly.macro.AssemblyMacroApplyResult;
import com.formacraft.server.assembly.validation.AssemblySpecNormalizer;
import com.formacraft.server.assembly.validation.AssemblySpecNormalizeResult;
import com.formacraft.server.assembly.validation.AssemblySpecValidator;
import com.formacraft.server.assembly.validation.AssemblyValidationIssue;
import com.formacraft.common.build.PlannedBlock;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
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

    private record PreparedComponents(List<Component> components, Set<String> assemblyFacadeSlots) {}

    private static final Map<String, String> COMPONENT_TYPE_ALIASES = Map.of(
            "MAIN_MASS", "MASS_MAIN",
            "BUTTRESS", "WALL"
    );

    private static final Set<String> AUTO_INFERRED_TYPES = Set.of(
            "FACADE_WINDOWS",
            "ENTRANCE",
            "ROOF",
            "ROOF_STRUCTURE"
    );

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

        com.formacraft.server.assembly.AssemblyCompileDiagnostics.clear();

        plan = com.formacraft.common.llm.parser.LlmPlanAnchorNormalizer.normalize(plan);

        // 索引 slots（便于快速查找）
        Map<String, Slot> slotMap = indexSlots(plan);
        boolean allowAssemblyFacade = world != null && globalAnchor != null;
        PreparedComponents prepared = prepareComponents(plan, slotMap, allowAssemblyFacade);
        List<Component> components = prepared.components();
        Set<String> assemblyFacadeSlots = prepared.assemblyFacadeSlots();

        if (components.isEmpty()) {
            FormacraftMod.LOGGER.info("ComponentPlanCompiler: no components to compile");
            return result;
        }

        // 遍历所有 components
        for (Component c : components) {
            if (c == null) continue;
            String normalizedType = normalizeType(c.componentType());

            // 查找对应的 slot
            Slot slot = slotMap.get(c.slotId());
            if (slot == null) {
                // 没有 slot 的 component，默认使用全局 anchor
                slot = defaultSlot(plan);
                FormacraftMod.LOGGER.debug("ComponentPlanCompiler: component {} has no slot, using default slot", c.componentType());
            }
            String slotKey = slotKey(c);

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

            // 统一路由：ComponentGenerator → 扩展器 → 受控 StructureGenerator 回退
            List<BlockPatch> patches;
            try {
                patches = GenerationHub.generateComponent(semantic, world);
                if (!patches.isEmpty()) {
                    if (allowAssemblyFacade && globalAnchor != null && isMassType(normalizedType)
                            && assemblyFacadeSlots.contains(slotKey)) {
                        List<BlockPatch> facade = generateAssemblyFacadePatches(plan, semantic, slot, globalAnchor, world);
                        if (!facade.isEmpty()) {
                            List<BlockPatch> merged = new ArrayList<>(patches.size() + facade.size());
                            merged.addAll(patches);
                            merged.addAll(facade);
                            patches = merged;
                        }
                    }
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
     * 创建默认 slot（用于没有显式 slot 的组件）。
     * <p>
     * <b>锚点必须是相对原点 (0,0,0)</b>，而不是 {@code plan.anchor()}。因为编译产出的
     * BlockPatch 契约是“相对 plan.anchor 的偏移”，下游（如 {@code LlmPlanPreviewBuilder}）
     * 会再统一叠加世界锚点 {@code planOrigin}。若这里放绝对锚点，会导致 anchor 被叠加两次，
     * 使建筑整体偏移一个 anchor 的量（远离锚点 + 悬空），进而触发巨量地形填充。
     */
    private static Slot defaultSlot(LlmPlan plan) {
        GlobalConstraints.Facing facing = (plan.globalConstraints() != null && plan.globalConstraints().facing() != null)
                ? plan.globalConstraints().facing()
                : null;

        return new Slot(
                "__global__",
                new Vec3i(0, 0, 0),
                facing,
                "default",
                null,
                null
        );
    }

    private static PreparedComponents prepareComponents(LlmPlan plan, Map<String, Slot> slotMap, boolean allowAssemblyFacade) {
        List<Component> normalized = new ArrayList<>();
        if (plan.components() != null) {
            normalized.addAll(plan.components());
        }
        if (normalized.isEmpty()) {
            return new PreparedComponents(normalized, Set.of());
        }

        List<Component> components = new ArrayList<>();
        Set<String> massSlots = new HashSet<>();
        for (Component c : normalized) {
            Component normalizedComponent = normalizeComponent(c);
            if (normalizedComponent == null) {
                continue;
            }
            normalizedComponent = StyleIntentResolver.apply(plan, normalizedComponent);
            normalizedComponent = OpeningGrammarResolver.apply(plan, normalizedComponent);
            String type = normalizeType(normalizedComponent.componentType());
            String slotKey = slotKey(normalizedComponent);
            if (isMassType(type)) {
                massSlots.add(slotKey);
            }
            components.add(normalizedComponent);
        }

        AssemblyPlanPromoter.PromotionResult assemblyPromotion = AssemblyPlanPromoter.promoteNestedAssembly(components);
        components = assemblyPromotion.components();
        Set<String> assemblyPrimarySlots = assemblyPromotion.assemblyPrimarySlots();
        massSlots.clear();
        for (Component c : components) {
            if (c != null && isMassType(normalizeType(c.componentType()))) {
                massSlots.add(slotKey(c));
            }
        }

        if (!components.isEmpty() && !massSlots.isEmpty()) {
            List<Component> filtered = new ArrayList<>(components.size());
            for (Component c : components) {
                String type = normalizeType(c.componentType());
                if (massSlots.contains(slotKey(c)) && AUTO_INFERRED_TYPES.contains(type) && isAutoInferred(c)) {
                    continue;
                }
                filtered.add(c);
            }
            components = filtered;
        }

        if (components.isEmpty()) {
            return new PreparedComponents(components, Set.of());
        }

        Set<String> slotsWithFacade = new HashSet<>();
        Set<String> slotsWithEntrance = new HashSet<>();
        Set<String> slotsWithRoof = new HashSet<>();
        Set<String> assemblyFacadeSlots = new HashSet<>();
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
            if ("ASSEMBLY".equals(type)) {
                prepared.add(c);
                continue;
            }
            String slotKey = slotKey(c);
            if (assemblyPrimarySlots.contains(slotKey)) {
                continue;
            }
            if (!isMassType(type)) {
                prepared.add(c);
                continue;
            }
            String slotId = c.slotId();
            Slot slot = slotId != null ? slotMap.get(slotId) : null;
            GlobalConstraints.Facing facing = slot != null && slot.facing() != null
                    ? slot.facing()
                    : (plan.globalConstraints() != null ? plan.globalConstraints().facing() : GlobalConstraints.Facing.SOUTH);
            boolean hasFacade = slotsWithFacade.contains(slotKey);
            boolean hasEntrance = slotsWithEntrance.contains(slotKey);
            boolean useAssemblyFacade = allowAssemblyFacade
                    && shouldUseAssemblyFacade(plan, c)
                    && !hasFacade
                    && !hasEntrance;

            if (useAssemblyFacade) {
                assemblyFacadeSlots.add(slotKey);
                c = markAssemblyFacade(c);
            } else {
                if (!hasFacade) {
                    Component facade = makeFacadeComponent(c, slotId);
                    facade = StyleIntentResolver.apply(plan, facade);
                    facade = OpeningGrammarResolver.apply(plan, facade);
                    inferred.add(facade);
                    slotsWithFacade.add(slotKey);
                    hasFacade = true;
                }
                if (!hasEntrance) {
                    Component entrance = makeEntranceComponent(plan, c, slotId, facing);
                    if (entrance != null) {
                        entrance = StyleIntentResolver.apply(plan, entrance);
                        inferred.add(entrance);
                        slotsWithEntrance.add(slotKey);
                        hasEntrance = true;
                    }
                }
            }
            if (hasFacade || hasEntrance) {
                c = suppressMassOpenings(c, hasFacade, hasEntrance);
            }
            if (!slotsWithRoof.contains(slotKey)) {
                Component roof = makeRoofComponent(plan, c, slotId);
                if (roof != null) {
                    roof = StyleIntentResolver.apply(plan, roof);
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

        realignSatellitesToMass(prepared, plan, slotMap, assemblyPrimarySlots);

        return new PreparedComponents(prepared, assemblyFacadeSlots);
    }

    /**
     * LLM 常把 ROOF/FACADE/ENTRANCE 的 relative_position 写成与 MASS 相同的中心坐标；
     * 这些附属组件生成器按 min_corner 解释坐标。在此统一贴回 {@link #resolveMassOrigin(Component)}。
     * <p>assembly-primary slot 无 MASS 锚点，跳过以免误对齐。</p>
     */
    private static void realignSatellitesToMass(
            List<Component> components,
            LlmPlan plan,
            Map<String, Slot> slotMap,
            Set<String> assemblyPrimarySlots
    ) {
        if (components == null || components.isEmpty()) {
            return;
        }

        Map<String, Component> massBySlot = new HashMap<>();
        for (Component c : components) {
            if (c == null) {
                continue;
            }
            if (assemblyPrimarySlots != null && assemblyPrimarySlots.contains(slotKey(c))) {
                continue;
            }
            if (isMassType(normalizeType(c.componentType()))) {
                massBySlot.putIfAbsent(slotKey(c), c);
            }
        }
        if (massBySlot.isEmpty()) {
            return;
        }

        int realigned = 0;
        for (int i = 0; i < components.size(); i++) {
            Component c = components.get(i);
            if (c == null) {
                continue;
            }
            String slotKey = slotKey(c);
            if (assemblyPrimarySlots != null && assemblyPrimarySlots.contains(slotKey)) {
                continue;
            }
            String type = normalizeType(c.componentType());
            if (isMassType(type)) {
                continue;
            }
            Component mass = massBySlot.get(slotKey);
            if (mass == null) {
                continue;
            }

            GlobalConstraints.Facing facing = resolveSlotFacing(plan, slotMap, c.slotId());
            Component aligned = switch (type) {
                case "FACADE_WINDOWS" -> alignFacadeToMass(c, mass, plan);
                case "ENTRANCE" -> alignEntranceToMass(c, mass, plan, facing);
                default -> isRoofType(type) ? alignRoofToMass(c, mass, plan) : c;
            };
            if (aligned != null && aligned != c) {
                components.set(i, aligned);
                realigned++;
            }
        }
        if (realigned > 0) {
            FormacraftMod.LOGGER.info("ComponentPlanCompiler: realigned {} satellite component(s) to MASS min_corner", realigned);
        }
    }

    private static GlobalConstraints.Facing resolveSlotFacing(LlmPlan plan, Map<String, Slot> slotMap, String slotId) {
        if (slotId != null && slotMap != null) {
            Slot slot = slotMap.get(slotId);
            if (slot != null && slot.facing() != null) {
                return slot.facing();
            }
        }
        if (plan != null && plan.globalConstraints() != null && plan.globalConstraints().facing() != null) {
            return plan.globalConstraints().facing();
        }
        return GlobalConstraints.Facing.SOUTH;
    }

    private static Component alignRoofToMass(Component llmRoof, Component mass, LlmPlan plan) {
        Component template = makeRoofComponent(plan, mass, mass.slotId());
        if (template == null) {
            return llmRoof;
        }

        Map<String, Object> params = new HashMap<>();
        if (template.params() != null) {
            params.putAll(template.params());
        }
        if (llmRoof.params() != null) {
            params.putAll(llmRoof.params());
        }
        copyFootprintParams(mass.params(), params);

        Dimensions templateDims = template.dimensions();
        Dimensions llmDims = llmRoof.dimensions();
        int roofHeight = templateDims != null ? templateDims.height() : 3;
        if (llmDims != null && llmDims.height() > 0) {
            roofHeight = llmDims.height();
        }

        int baseW = templateDims != null ? templateDims.width() : 1;
        int baseD = templateDims != null ? templateDims.depth() : 1;
        if (llmDims != null) {
            int extraW = Math.max(0, llmDims.width() - baseW);
            int extraD = Math.max(0, llmDims.depth() - baseD);
            int overhang = Math.max(extraW / 2, extraD / 2);
            if (overhang > 0) {
                int existing = ComponentParamParsers.intParam(params, 0, "overhang", "overhang_blocks", "eave_overhang");
                params.put("overhang", Math.max(existing, overhang));
            }
        }

        return new Component(
                llmRoof.componentType(),
                llmRoof.slotId(),
                template.relativePosition(),
                new Dimensions(baseW, baseD, roofHeight),
                mergeFeatureLists(template.features(), llmRoof.features()),
                params
        );
    }

    private static Component alignFacadeToMass(Component llmFacade, Component mass, LlmPlan plan) {
        Component template = makeFacadeComponent(mass, mass.slotId());
        template = StyleIntentResolver.apply(plan, template);
        template = OpeningGrammarResolver.apply(plan, template);

        Map<String, Object> params = new HashMap<>();
        if (template.params() != null) {
            params.putAll(template.params());
        }
        if (llmFacade.params() != null) {
            params.putAll(llmFacade.params());
        }

        Dimensions massDims = mass.dimensions();
        Dimensions llmDims = llmFacade.dimensions();
        if (massDims == null || template.relativePosition() == null) {
            return llmFacade;
        }

        int width = massDims.width();
        int depth = 1;
        int height = massDims.height();
        if (llmDims != null) {
            if (llmDims.depth() > 0) {
                depth = llmDims.depth();
            }
            if (llmDims.width() > 0) {
                width = Math.max(width, llmDims.width());
            }
            if (llmDims.height() > 0) {
                height = Math.max(2, llmDims.height());
            }
        }

        return new Component(
                "FACADE_WINDOWS",
                llmFacade.slotId(),
                template.relativePosition(),
                new Dimensions(width, depth, height),
                mergeFeatureLists(template.features(), llmFacade.features()),
                params
        );
    }

    private static Component alignEntranceToMass(Component llmEntrance, Component mass, LlmPlan plan,
                                                 GlobalConstraints.Facing facing) {
        Component template = makeEntranceComponent(plan, mass, mass.slotId(), facing);
        if (template == null) {
            return llmEntrance;
        }

        Map<String, Object> params = new HashMap<>();
        if (template.params() != null) {
            params.putAll(template.params());
        }
        if (llmEntrance.params() != null) {
            params.putAll(llmEntrance.params());
        }

        return new Component(
                "ENTRANCE",
                llmEntrance.slotId(),
                template.relativePosition(),
                template.dimensions(),
                mergeFeatureLists(template.features(), llmEntrance.features()),
                params
        );
    }

    private static void copyFootprintParams(Map<String, Object> from, Map<String, Object> to) {
        if (from == null || to == null) {
            return;
        }
        for (String key : List.of(
                "plan_type", "planType",
                "shape", "footprint_shape", "footprintShape",
                "corner_cut", "cornerCut", "cut_corner", "cutCorner", "cut_size",
                "l_corner", "lCorner", "l_cut", "lCut",
                "arm_width", "cross_arm", "cross_arm_width", "armWidth",
                "courtyard_ratio", "courtyardRatio", "court_ratio", "void_ratio", "voidRatio",
                "corner_radius", "cornerRadius"
        )) {
            if (from.containsKey(key)) {
                to.put(key, from.get(key));
            }
        }
    }

    private static List<String> mergeFeatureLists(List<String> primary, List<String> secondary) {
        List<String> merged = new ArrayList<>();
        if (primary != null) {
            merged.addAll(primary);
        }
        if (secondary != null) {
            for (String feature : secondary) {
                if (feature != null && !merged.contains(feature)) {
                    merged.add(feature);
                }
            }
        }
        return merged;
    }

    private static Component makeFacadeComponent(Component base, String slotId) {
        Vec3i origin = resolveMassOrigin(base);
        if (origin == null) {
            origin = base.relativePosition();
        }
        Map<String, Object> params = new HashMap<>();
        if (base.params() != null) {
            params.putAll(base.params());
        }
        Double ratio = ComponentParamParsers.doubleOrNull(params, "window_ratio", "windowRatio");
        if (ratio == null) {
            params.put("window_ratio", 0.25);
        }
        params.putIfAbsent("rhythm", "regular");
        String facadeProfile = getParamString(params, "facade_profile", "facadeProfile", "facade");
        if (facadeProfile != null) {
            String fp = facadeProfile.toLowerCase(Locale.ROOT);
            if (fp.contains("pilaster") || fp.contains("colonnade") || fp.contains("classical")) {
                params.putIfAbsent("rhythm_preset", ComponentFacadeRhythmPlanner.PRESET_CLASSICAL_PILASTER_BAY);
                params.putIfAbsent("rhythm", "vertical_bay");
            }
        }
        params.putIfAbsent("reserve_entrance_bay", true);

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
                origin,
                base.dimensions(),
                features,
                params
        );
    }

    private static boolean isAutoInferred(Component component) {
        if (component == null || component.params() == null) {
            return false;
        }
        Object v = component.params().get("auto_inferred");
        if (v == null) {
            v = component.params().get("autoInferred");
        }
        if (v instanceof Boolean b) {
            return b;
        }
        if (v != null) {
            return "true".equalsIgnoreCase(v.toString().trim());
        }
        return false;
    }

    private static Component makeEntranceComponent(LlmPlan plan, Component base, String slotId, GlobalConstraints.Facing facing) {
        Dimensions dims = base.dimensions();
        Vec3i rp = resolveMassOrigin(base);
        if (dims == null || rp == null) {
            return null;
        }
        int width = Math.max(1, dims.width());
        int depth = Math.max(1, dims.depth());
        int height = Math.max(2, dims.height());

        int entranceWidth = Math.max(3, Math.min(5, Math.max(3, width / 3)));
        entranceWidth = Math.min(entranceWidth, Math.max(3, width - 2));
        if (entranceWidth % 2 == 0) {
            entranceWidth = 3;
        }
        int entranceDepth = Math.max(1, Math.min(2, Math.max(1, depth / 4)));
        int entranceHeight = Math.max(3, Math.min(height, Math.max(4, height / 2)));

        Map<String, Object> baseParams = base.params();
        int paramDoorW = ComponentParamParsers.intParam(baseParams, 0, "door_width", "doorWidth");
        int paramDoorH = ComponentParamParsers.intParam(baseParams, 0, "door_height", "doorHeight");
        int paramCanopy = ComponentParamParsers.intParam(baseParams, 0, "canopy_depth", "canopyDepth");
        if (paramDoorW > 0) {
            entranceWidth = Math.max(2, Math.min(width - 1, paramDoorW + 1));
        }
        if (paramDoorH > 0) {
            entranceHeight = Math.max(2, Math.min(height, paramDoorH + 1));
        }
        if (paramCanopy > 0) {
            entranceDepth = Math.max(1, Math.min(depth / 2, paramCanopy + 1));
        }

        int relX = rp.x();
        int relZ = rp.z();
        switch (facing != null ? facing : GlobalConstraints.Facing.SOUTH) {
            case NORTH -> {
                relX = rp.x() + Math.max(0, (width - entranceWidth) / 2);
                relZ = rp.z() + Math.max(0, depth - entranceDepth);
            }
            case EAST -> relZ = rp.z() + Math.max(0, (depth - entranceDepth) / 2);
            case WEST -> {
                relX = rp.x() + Math.max(0, width - entranceWidth);
                relZ = rp.z() + Math.max(0, (depth - entranceDepth) / 2);
            }
            case SOUTH -> relX = rp.x() + Math.max(0, (width - entranceWidth) / 2);
        }

        Map<String, Object> params = new HashMap<>();
        params.put("door_width", Math.max(2, Math.min(entranceWidth - 1, entranceWidth)));
        params.put("door_height", Math.max(2, Math.min(entranceHeight - 1, entranceHeight)));
        params.put("canopy_depth", paramCanopy > 0 ? paramCanopy : 1);

        List<String> features = new ArrayList<>();
        features.add("entrance");
        features.add("overhang");
        if (hasOrnateEntranceHints(plan, base)) {
            features.add("decorative_lintel");
            features.add("wood_carvings");
        }
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
        Vec3i rp = resolveMassOrigin(base);
        if (dims == null || rp == null) {
            return null;
        }
        int width = Math.max(2, dims.width());
        int depth = Math.max(2, dims.depth());
        int span = Math.min(width, depth);
        int roofHeight = Math.max(2, Math.min(8, Math.max(2, span / 3)));

        Map<String, Object> params = new HashMap<>();
        if (base.params() != null) {
            params.putAll(base.params());
        }
        String roofType = getParamString(params, "roof_type", "roofType");
        if (roofType == null || roofType.isBlank()) {
            roofType = resolveDefaultRoofType(plan, base);
        }
        params.put("roof_type", roofType);
        params.putIfAbsent("roof_height", roofHeight);
        applyInferredOverhang(params, roofType, base);

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
        if (base == null) {
            return null;
        }
        Map<String, Object> params = new HashMap<>();
        if (base.params() != null) {
            params.putAll(base.params());
        }
        params.put("roof_type", "none");
        return new Component(
                base.componentType(),
                base.slotId(),
                base.relativePosition(),
                base.dimensions(),
                base.features(),
                params
        );
    }

    private static Component suppressMassOpenings(Component base, boolean suppressWindows, boolean suppressDoors) {
        if (base == null || (!suppressWindows && !suppressDoors)) {
            return base;
        }
        Map<String, Object> params = new HashMap<>();
        if (base.params() != null) {
            params.putAll(base.params());
        }
        if (suppressWindows) {
            params.put("suppress_windows", true);
        }
        if (suppressDoors) {
            params.put("suppress_doors", true);
        }
        return new Component(
                base.componentType(),
                base.slotId(),
                base.relativePosition(),
                base.dimensions(),
                base.features(),
                params
        );
    }

    private static String slotKey(Component c) {
        return c.slotId() != null ? c.slotId() : "__global__";
    }

    private static String normalizeType(String value) {
        if (value == null) return "";
        return value.trim().toUpperCase();
    }

    private static Component normalizeComponent(Component component) {
        if (component == null) {
            return null;
        }
        // allowUnknown：component_request/group_request，或显式地标/模块路由提示，
        // 都应保留原始 type（交由 UnifiedGeneratorRouter 的扩展/整栋回退处理）。
        boolean allowUnknown = hasComponentRequest(component.features())
                || hasStructureRoutingHint(component)
                || "MODULE".equals(normalizeType(component.componentType()))
                || "ASSEMBLY".equals(normalizeType(component.componentType()));
        String type = normalizeComponentType(component.componentType(), allowUnknown);
        if (type.isBlank()) {
            return null;
        }
        // Phase 10：合理性修复 —— "太矮"的主体/塔拔高到合理最小层高。
        Dimensions dims = clampMinHeight(type, component.dimensions());
        boolean typeChanged = !type.equals(component.componentType());
        boolean dimsChanged = dims != component.dimensions();
        if (!typeChanged && !dimsChanged) {
            return component;
        }
        return new Component(
                type,
                component.slotId(),
                component.relativePosition(),
                dims,
                component.features(),
                component.params()
        );
    }

    /**
     * Phase 10：把明显过矮的主体/塔类构件拔高到合理最小高度（其它类型不动）。
     * 仅调整 height，不改 width/depth；越界方块仍会被 BuildConstraintClipper 裁剪。
     */
    private static Dimensions clampMinHeight(String type, Dimensions dims) {
        if (dims == null) return null;
        int minH = minHeightForType(type);
        if (minH <= 0) return dims;
        int h = dims.height();
        if (h > 0 && h < minH) {
            FormacraftMod.LOGGER.debug("ComponentPlanCompiler: raising too-short {} height {} -> {}", type, h, minH);
            return new Dimensions(dims.width(), dims.depth(), minH);
        }
        return dims;
    }

    private static int minHeightForType(String type) {
        if (type == null) return 0;
        return switch (type) {
            case "MASS_MAIN", "MASS_SECONDARY", "MASS_WING", "HOUSE", "BUILDING" -> 4;
            case "TOWER" -> 6;
            default -> 0;
        };
    }

    private static String normalizeComponentType(String value, boolean allowUnknown) {
        String type = normalizeType(value);
        if (type.isBlank()) {
            return "";
        }
        String alias = COMPONENT_TYPE_ALIASES.get(type);
        if (alias != null) {
            type = alias;
        }
        if (allowUnknown) {
            return type;
        }
        if (ComponentGeneratorRegistry.hasGenerator(type)) {
            return type;
        }
        String fallback = inferFallbackType(type);
        if (ComponentGeneratorRegistry.hasGenerator(fallback)) {
            FormacraftMod.LOGGER.debug("ComponentPlanCompiler: fallback component type {} -> {}", type, fallback);
            return fallback;
        }
        FormacraftMod.LOGGER.debug("ComponentPlanCompiler: skipping unsupported component type {}", type);
        return "";
    }

    private static boolean hasComponentRequest(List<String> features) {
        if (features == null || features.isEmpty()) {
            return false;
        }
        for (String feature : features) {
            if (feature == null) {
                continue;
            }
            String lower = feature.toLowerCase(Locale.ROOT);
            if (lower.startsWith("component_request:") || lower.startsWith("group_request:")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 是否携带地标/模块/整栋路由提示（Phase 7）。用于放行 MODULE 等未注册 type，
     * 交由 {@code UnifiedGeneratorRouter} 的整栋回退（{@code StructureGeneratorAdaptor}）处理。
     */
    private static boolean hasStructureRoutingHint(Component component) {
        if (component == null) {
            return false;
        }
        List<String> features = component.features();
        if (features != null) {
            for (String feature : features) {
                if (feature == null) continue;
                String lower = feature.toLowerCase(Locale.ROOT);
                if (lower.startsWith("landmark:") || lower.startsWith("module:")
                        || lower.startsWith("structure_generator:") || lower.startsWith("skeleton:")) {
                    return true;
                }
            }
        }
        Map<String, Object> params = component.params();
        if (params != null) {
            if (params.containsKey("landmark") || params.containsKey("module_id")
                    || params.containsKey("template") || params.containsKey("blueprint")
                    || params.containsKey("assembly") || params.containsKey("skeleton")
                    || Boolean.TRUE.equals(params.get("useStructureGenerator"))) {
                return true;
            }
        }
        return false;
    }

    private static String inferFallbackType(String type) {
        if (type == null || type.isBlank()) {
            return null;
        }
        if (type.contains("PLAZA") || type.contains("PAVING") || type.contains("FLOOR")
                || type.contains("GROUND") || type.contains("PATH")) {
            return "PAVING";
        }
        if (type.contains("BENCH") || type.contains("LIGHT") || type.contains("GREEN")
                || type.contains("TREE") || type.contains("DECOR") || type.contains("ORNAMENT")
                || type.contains("STATUE") || type.contains("GARGOYLE")) {
            return "DECOR_DETAIL";
        }
        if (type.contains("ROOF")) {
            return "ROOF";
        }
        if (type.contains("BALCONY")) {
            return "BALCONY";
        }
        if (type.contains("CHIMNEY")) {
            return "CHIMNEY";
        }
        if (type.contains("FOUNDATION") || type.contains("BASE")) {
            return "FOUNDATION";
        }
        if (type.contains("GATE")) {
            return "GATE";
        }
        if (type.contains("WALL") || type.contains("BUTTRESS")) {
            return "WALL";
        }
        if (type.contains("TOWER") || type.contains("SPIRE")) {
            return "TOWER";
        }
        return null;
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

    private static String resolveDefaultRoofType(LlmPlan plan, Component base) {
        String fromParams = roofTypeHintFromParams(base);
        if (fromParams != null) {
            return fromParams;
        }
        String fromFeatures = roofTypeHintFromFeatures(base);
        if (fromFeatures != null) {
            return fromFeatures;
        }
        String fromStyle = roofTypeHintFromStyleAttributes(plan);
        if (fromStyle != null) {
            return fromStyle;
        }
        return "gable";
    }

    private static String roofTypeHintFromParams(Component base) {
        if (base == null || base.params() == null) {
            return null;
        }
        return normalizeRoofTypeToken(getParamString(base.params(), "preferred_roof_type", "preferredRoofType"));
    }

    private static String roofTypeHintFromFeatures(Component base) {
        if (base == null || base.features() == null) {
            return null;
        }
        for (String feature : base.features()) {
            if (feature == null) {
                continue;
            }
            String lower = feature.toLowerCase(Locale.ROOT);
            if (lower.contains("xuanshan") || lower.contains("悬山")) {
                return "xuanshan";
            }
            if (lower.contains("xieshan") || lower.contains("歇山")) {
                return "xieshan";
            }
            if (lower.contains("double_gable") || lower.contains("shuangpo") || lower.contains("双坡")) {
                return "double_gable";
            }
            if (lower.contains("hip") || lower.contains("hipped")) {
                return "hip";
            }
            if (lower.contains("flat") && (lower.contains("roof") || lower.contains("顶"))) {
                return "flat";
            }
            if (lower.contains("pyramid") || lower.contains("cone")) {
                return "pyramid";
            }
            if (lower.contains("dome") || lower.contains("curved_roof")) {
                return "dome";
            }
            if (lower.contains("gable") || lower.contains("gothic") || lower.contains("cathedral")) {
                return "gable";
            }
        }
        return null;
    }

    private static String roofTypeHintFromStyleAttributes(LlmPlan plan) {
        if (plan == null || plan.styleAttributes() == null) {
            return null;
        }
        com.formacraft.common.llm.dto.StyleAttributes attrs = plan.styleAttributes();
        String roofMat = attrs.roofMaterial();
        if (roofMat != null) {
            String lower = roofMat.toLowerCase(Locale.ROOT);
            if (lower.contains("flat") || (lower.contains("concrete") && !lower.contains("tile"))) {
                return "flat";
            }
        }
        List<String> decorative = attrs.decorativeElements();
        if (decorative != null) {
            for (String element : decorative) {
                if (element == null) {
                    continue;
                }
                String lower = element.toLowerCase(Locale.ROOT);
                if (lower.contains("curved_eaves") || lower.contains("flying_eaves") || lower.contains("飞檐")) {
                    return "xuanshan";
                }
            }
        }
        return null;
    }

    private static String normalizeRoofTypeToken(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static void applyInferredOverhang(Map<String, Object> params, String roofType, Component base) {
        if (ComponentParamParsers.intParam(params, -1, "overhang", "overhang_blocks", "eave_overhang") >= 0) {
            return;
        }
        if ("xuanshan".equalsIgnoreCase(roofType)) {
            params.putIfAbsent("overhang", 2);
        } else if ("xieshan".equalsIgnoreCase(roofType)) {
            params.putIfAbsent("overhang", 1);
        } else if (hasRoofOverhangFeature(base)) {
            params.putIfAbsent("overhang", 1);
        }
    }

    private static boolean hasRoofOverhangFeature(Component base) {
        if (base == null || base.features() == null) {
            return false;
        }
        for (String feature : base.features()) {
            if (feature == null) {
                continue;
            }
            String lower = feature.toLowerCase(Locale.ROOT);
            if (lower.contains("overhang") || lower.contains("eave") || lower.contains("flying_eaves")
                    || lower.contains("飞檐") || lower.contains("出檐")) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasOrnateEntranceHints(LlmPlan plan, Component base) {
        if (base != null && base.features() != null) {
            for (String feature : base.features()) {
                if (feature == null) {
                    continue;
                }
                String lower = feature.toLowerCase(Locale.ROOT);
                if (lower.contains("wood_carving") || lower.contains("carved") || lower.contains("lintel")
                        || lower.contains("decorative_lintel")) {
                    return true;
                }
            }
        }
        if (plan != null && plan.styleAttributes() != null && plan.styleAttributes().decorativeElements() != null) {
            for (String element : plan.styleAttributes().decorativeElements()) {
                if (element == null) {
                    continue;
                }
                String lower = element.toLowerCase(Locale.ROOT);
                if (lower.contains("carving") || lower.contains("lintel") || lower.contains("dougong")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Component markAssemblyFacade(Component base) {
        if (base == null) return null;
        Map<String, Object> params = new HashMap<>();
        if (base.params() != null) {
            params.putAll(base.params());
        }
        params.put("assembly_facade", true);
        return new Component(
                base.componentType(),
                base.slotId(),
                base.relativePosition(),
                base.dimensions(),
                base.features(),
                params
        );
    }

    private static boolean shouldUseAssemblyFacade(LlmPlan plan, Component c) {
        if (plan == null || c == null || c.dimensions() == null || c.relativePosition() == null) {
            return false;
        }
        Map<String, Object> params = c.params();
        Boolean override = getParamBoolean(params, "assembly_facade", "assemblyFacade", "useAssemblyFacade");
        if (override != null) {
            return override;
        }

        // P0：默认关闭 Assembly 立面；仅 params / style_attributes 显式请求时开启。
        if (Boolean.TRUE.equals(getParamBoolean(params, "assembly_macro", "assemblyMacro", "use_assembly_macro"))) {
            Dimensions d = c.dimensions();
            return Math.min(d.width(), d.depth()) >= 6 && d.height() >= 6;
        }
        String facadeProfile = getParamString(params, "facade_profile", "facadeProfile");
        if (facadeProfile != null && needsAssemblyFacadeProfile(facadeProfile)) {
            String detail = getParamString(params, "detail_level", "detailLevel", "quality", "quality_level");
            if (detail == null || !detail.trim().toLowerCase(Locale.ROOT).contains("low")) {
                Dimensions d = c.dimensions();
                return Math.min(d.width(), d.depth()) >= 6 && d.height() >= 6;
            }
        }
        return false;
    }

    private static boolean needsAssemblyFacadeProfile(String facadeProfile) {
        if (facadeProfile == null || facadeProfile.isBlank()) {
            return false;
        }
        String v = facadeProfile.trim().toLowerCase(Locale.ROOT);
        return v.contains("mullion") || v.contains("module_grid") || v.contains("curtain");
    }

    private static List<BlockPatch> generateAssemblyFacadePatches(
            LlmPlan plan,
            SemanticComponent semantic,
            Slot slot,
            BlockPos globalAnchor,
            ServerWorld world
    ) {
        Component c = semantic != null ? semantic.source() : null;
        if (c == null || c.dimensions() == null || c.relativePosition() == null) {
            return List.of();
        }
        if (slot == null || slot.anchor() == null || world == null || globalAnchor == null) {
            return List.of();
        }

        Dimensions dims = c.dimensions();
        Vec3i rp = resolveMassOrigin(c);
        Vec3i slotAnchor = slot.anchor();

        int width = Math.max(3, dims.width());
        int depth = Math.max(3, dims.depth());
        int height = Math.max(3, dims.height());

        int shellW = (width % 2 == 0) ? Math.max(3, width - 1) : width;
        int shellD = (depth % 2 == 0) ? Math.max(3, depth - 1) : depth;

        int centerOffsetX = shellW / 2;
        int centerOffsetZ = shellD / 2;

        BlockPos slotWorld = globalAnchor.add(slotAnchor.x(), slotAnchor.y(), slotAnchor.z());
        BlockPos componentBase = slotWorld.add(rp.x(), rp.y(), rp.z());
        BlockPos origin = componentBase.add(centerOffsetX, 0, centerOffsetZ);

        Direction entranceDir = resolveEntranceFacing(plan, slot);
        String entranceFace = directionToFaceToken(entranceDir);

        Map<String, Object> assembly = new HashMap<>();
        String paletteId = resolveAssemblyPaletteId(plan, semantic);
        if (paletteId != null && !paletteId.isBlank()) {
            assembly.put("paletteId", paletteId);
        }
        if (!entranceFace.isBlank()) {
            assembly.put("entranceFacing", entranceFace);
        }

        Map<String, Object> macro = new HashMap<>();
        Map<String, Object> style = buildAssemblyMacroStyle(plan, semantic, width, depth, height, entranceFace);
        if (!style.isEmpty()) {
            macro.put("style", style);
        }
        Double windowRatio = ComponentParamParsers.doubleOrNull(c.params(), "window_ratio", "windowRatio");
        if (windowRatio != null) {
            macro.put("openness", clamp01(windowRatio));
        }
        if (!macro.isEmpty()) {
            assembly.put("macro", macro);
        }

        Map<String, Object> primary = new HashMap<>();
        primary.put("id", "MainVolume");
        primary.put("type", "SHELL_BOX");
        primary.put("w", shellW);
        primary.put("d", shellD);
        primary.put("h", height);

        Map<String, Object> door = buildDoorOpening(plan, semantic, width, depth, height, entranceFace);
        Map<String, Object> facade = new HashMap<>();
        List<Map<String, Object>> openings = new ArrayList<>();
        openings.add(door);
        facade.put("openings", openings);
        primary.put("facade", facade);

        List<Object> comps = new ArrayList<>();
        comps.add(primary);
        assembly.put("components", comps);

        AssemblySpecNormalizeResult norm = AssemblySpecNormalizer.normalize(assembly);
        AssemblyMacroApplyResult macroApplied = AssemblyMacroApplier.apply(norm.normalized());
        Object applied = macroApplied.applied();

        List<AssemblyValidationIssue> issues = AssemblySpecValidator.validate(applied);
        long errCount = issues.stream()
                .filter(i -> i.severity() == AssemblyValidationIssue.Severity.ERROR)
                .count();
        if (errCount > 0) {
            FormacraftMod.LOGGER.warn("ComponentPlanCompiler: assembly facade validation failed ({} errors)", errCount);
            return List.of();
        }

        AssemblySpec spec = MetaAssemblyCompiler.compile(applied, null);
        if (spec == null || spec.ops == null || spec.ops.isEmpty()) {
            return List.of();
        }

        List<Map<String, Object>> ops = filterAssemblyFacadeOps(spec.ops);
        if (ops.isEmpty()) {
            return List.of();
        }

        MetaAssemblyEngine engine = new MetaAssemblyEngine();
        AssemblySpec facadeSpec = AssemblySpec.of(spec.paletteId, spec.entranceFacing, ops);
        List<PlannedBlock> blocks = engine.execute(
                facadeSpec,
                new MetaAssemblyEngine.Context(world, origin, entranceDir, spec.paletteId)
        );
        if (blocks.isEmpty()) {
            return List.of();
        }

        List<BlockPatch> out = new ArrayList<>(blocks.size());
        for (PlannedBlock pb : blocks) {
            if (pb == null || pb.getPos() == null || pb.getTargetState() == null) continue;
            BlockPos pos = pb.getPos();
            int dx = pos.getX() - slotWorld.getX();
            int dy = pos.getY() - slotWorld.getY();
            int dz = pos.getZ() - slotWorld.getZ();
            String blockId = Registries.BLOCK.getId(pb.getTargetState().getBlock()).toString();
            String action = pb.getTargetState().isAir() ? BlockPatch.REMOVE : BlockPatch.PLACE;
            if (action.equals(BlockPatch.REMOVE)) {
                blockId = "minecraft:air";
            }
            out.add(new BlockPatch(action, dx, dy, dz, blockId));
        }
        return out;
    }

    private static Map<String, Object> buildAssemblyMacroStyle(
            LlmPlan plan,
            SemanticComponent semantic,
            int width,
            int depth,
            int height,
            String entranceFace
    ) {
        Map<String, Object> style = new HashMap<>();
        String styleId = resolveAssemblyStyleId(plan, semantic);
        if (styleId != null) {
            style.put("styleId", styleId);
        }
        if (entranceFace != null && !entranceFace.isBlank()) {
            style.put("entranceFace", entranceFace);
        }
        boolean chinese = styleId != null && styleId.toUpperCase(Locale.ROOT).contains("CHINESE")
                || styleId != null && styleId.toUpperCase(Locale.ROOT).contains("HUIZHOU")
                || styleId != null && styleId.toUpperCase(Locale.ROOT).contains("JIANGNAN");
        boolean gothic = styleId != null && styleId.toUpperCase(Locale.ROOT).contains("GOTHIC");

        double footprint = Math.max(1.0, Math.max(width, depth));
        double verticality = clamp01((height / footprint) * 0.6 + 0.2);
        if (gothic) {
            verticality = Math.max(verticality, 0.7);
        }
        style.put("verticality", verticality);

        double density = 0.55;
        Double windowRatio = ComponentParamParsers.doubleOrNull(semantic.source().params(), "window_ratio", "windowRatio");
        if (windowRatio != null) {
            density = clamp01(0.3 + windowRatio * 0.8);
            style.put("transparency", clamp01(windowRatio));
        }
        if (chinese) {
            density = Math.max(density, 0.55);
            style.putIfAbsent("intent", "中式 传统");
        }
        style.put("density", density);

        double symmetry = 0.45;
        if (plan != null && plan.globalConstraints() != null && plan.globalConstraints().symmetry() != null) {
            if (plan.globalConstraints().symmetry() != GlobalConstraints.Symmetry.NONE) {
                symmetry = 0.75;
            }
        }
        style.put("symmetry", symmetry);

        double structureExposure = 0.45 + Math.min(0.25, verticality * 0.2);
        if (chinese) {
            structureExposure = Math.max(structureExposure, 0.65);
        }
        style.put("structureExposure", clamp01(structureExposure));

        return style;
    }

    private static Vec3i resolveMassOrigin(Component base) {
        if (base == null) {
            return null;
        }
        Vec3i rp = base.relativePosition();
        Dimensions dims = base.dimensions();
        if (rp == null || dims == null) {
            return rp;
        }
        if (isCornerAnchor(base)) {
            return rp;
        }
        int offsetX = -(dims.width() / 2);
        int offsetZ = -(dims.depth() / 2);
        return new Vec3i(rp.x() + offsetX, rp.y(), rp.z() + offsetZ);
    }

    private static boolean isCornerAnchor(Component base) {
        Map<String, Object> params = base.params();
        String anchorMode = getParamString(params, "anchor_mode", "anchorMode");
        return anchorMode != null && anchorMode.toLowerCase(Locale.ROOT).contains("corner");
    }

    private static String resolveAssemblyStyleId(LlmPlan plan, SemanticComponent semantic) {
        String profile = plan != null ? plan.styleProfile() : null;
        StringBuilder merged = new StringBuilder(profile != null ? profile : "");
        if (semantic != null && semantic.source() != null && semantic.source().features() != null) {
            for (String f : semantic.source().features()) {
                if (f == null) continue;
                merged.append(" ").append(f);
            }
        }
        String hint = merged.toString().toUpperCase(Locale.ROOT);

        if (hint.contains("HUI") || hint.contains("HUIZHOU") || hint.contains("徽派")) {
            return "HUIZHOU_TRADITIONAL";
        }
        if (hint.contains("JIANGNAN") || hint.contains("WATERTOWN") || hint.contains("江南")) {
            return "JIANGNAN_WATERTOWN";
        }
        if (hint.contains("CHINESE") || hint.contains("ASIAN") || hint.contains("中式") || hint.contains("传统")) {
            return "CHINESE_TRADITIONAL";
        }
        if (hint.contains("GOTHIC") || hint.contains("CATHEDRAL")) {
            return "GOTHIC";
        }
        if (hint.contains("INDUSTRIAL")) {
            return "INDUSTRIAL";
        }
        if (hint.contains("MODERN")) {
            return "MODERN";
        }

        if (plan != null && plan.styleAttributes() != null) {
            com.formacraft.common.llm.dto.StyleAttributes attrs = plan.styleAttributes();
            if (attrs.decorativeElements() != null) {
                for (String deco : attrs.decorativeElements()) {
                    if (deco == null) continue;
                    String d = deco.toLowerCase(Locale.ROOT);
                    if (d.contains("lattice") || d.contains("dougong") || d.contains("飞檐") || d.contains("斗拱")) {
                        return "CHINESE_TRADITIONAL";
                    }
                    if (d.contains("rose_window") || d.contains("pointed") || d.contains("gothic")) {
                        return "GOTHIC";
                    }
                }
            }
            String roofMat = attrs.roofMaterial();
            String wallMat = attrs.wallMaterial();
            if (roofMat != null && wallMat != null) {
                String rm = roofMat.toLowerCase(Locale.ROOT);
                String wm = wallMat.toLowerCase(Locale.ROOT);
                if (rm.contains("tile") && (wm.contains("plaster") || wm.contains("lime") || wm.contains("white"))) {
                    return "CHINESE_TRADITIONAL";
                }
            }
        }

        if (plan != null && plan.genome() != null && plan.genome().culturalStyle != null) {
            String region = plan.genome().culturalStyle.region;
            if (region != null) {
                String r = region.toUpperCase(Locale.ROOT);
                if (r.contains("CHINESE")) return "CHINESE_TRADITIONAL";
                if (r.contains("EUROPEAN") && plan.genome().culturalStyle.era != null
                        && plan.genome().culturalStyle.era.toUpperCase(Locale.ROOT).contains("MEDIEVAL")) {
                    return "GOTHIC";
                }
            }
        }
        return null;
    }

    private static String resolveAssemblyPaletteId(LlmPlan plan, SemanticComponent semantic) {
        if (plan != null && plan.styleAttributes() != null) {
            com.formacraft.common.llm.dto.StyleAttributes attrs = plan.styleAttributes();
            String wall = attrs.wallColor();
            String roof = attrs.roofColor();
            if (wall != null && roof != null) {
                String wl = wall.toLowerCase(Locale.ROOT);
                String rl = roof.toLowerCase(Locale.ROOT);
                if (wl.contains("white") && (rl.contains("black") || rl.contains("dark") || rl.contains("gray") || rl.contains("grey"))) {
                    return "PALETTE_HUIZHOU_WHITE_BLACK_A";
                }
            }
            String wallMat = attrs.wallMaterial();
            String roofMat = attrs.roofMaterial();
            if (wallMat != null && roofMat != null) {
                String wm = wallMat.toLowerCase(Locale.ROOT);
                String rm = roofMat.toLowerCase(Locale.ROOT);
                if ((wm.contains("plaster") || wm.contains("lime") || wm.contains("white"))
                        && (rm.contains("tile") || rm.contains("slate") || rm.contains("gray") || rm.contains("grey"))) {
                    return "PALETTE_HUIZHOU_WHITE_BLACK_A";
                }
            }
        }
        String styleId = resolveAssemblyStyleId(plan, semantic);
        if (styleId != null) {
            String s = styleId.toUpperCase(Locale.ROOT);
            if (s.contains("HUIZHOU")) return "PALETTE_HUIZHOU_WHITE_BLACK_A";
            if (s.contains("JIANGNAN")) return "PALETTE_JIANGNAN_WATERTOWN_A";
            if (s.contains("CHINESE")) return "PALETTE_CHINESE_IMPERIAL_A";
            if (s.contains("GOTHIC")) return "PALETTE_GOTHIC_CATHEDRAL_A";
            if (s.contains("INDUSTRIAL")) return "PALETTE_INDUSTRIAL_STEEL_A";
            if (s.contains("MODERN")) return "PALETTE_MODERN_GLASS_B";
        }
        return null;
    }

    private static Direction resolveEntranceFacing(LlmPlan plan, Slot slot) {
        GlobalConstraints.Facing facing = null;
        if (slot != null && slot.facing() != null) {
            facing = slot.facing();
        } else if (plan != null && plan.globalConstraints() != null) {
            facing = plan.globalConstraints().facing();
        }
        if (facing == null) return Direction.SOUTH;
        return switch (facing) {
            case NORTH -> Direction.NORTH;
            case EAST -> Direction.EAST;
            case WEST -> Direction.WEST;
            case SOUTH -> Direction.SOUTH;
        };
    }

    private static String directionToFaceToken(Direction direction) {
        if (direction == null) {
            return "SOUTH";
        }
        return switch (direction) {
            case NORTH -> "NORTH";
            case EAST -> "EAST";
            case WEST -> "WEST";
            default -> "SOUTH";
        };
    }

    private static List<Map<String, Object>> filterAssemblyFacadeOps(List<Map<String, Object>> ops) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (ops == null) return out;
        for (Map<String, Object> op : ops) {
            if (op == null) continue;
            Object opName = op.get("op");
            if (opName == null) continue;
            String name = String.valueOf(opName).trim().toUpperCase(Locale.ROOT);
            if (name.isEmpty()) continue;
            if (name.equals("SHELL_BOX")
                    || name.equals("EXTRUDE_POLYGON")
                    || name.equals("CLEAR_BOX")
                    || name.equals("ANCHOR_FOOTPRINT")
                    || name.equals("ANCHORAGE")
                    || name.equals("ROOF_COVER")
                    || name.equals("BSP_FLOOR_PLAN")) {
                continue;
            }
            out.add(op);
        }
        return out;
    }

    private static Map<String, Object> buildDoorOpening(
            LlmPlan plan,
            SemanticComponent semantic,
            int width,
            int depth,
            int height,
            String entranceFace
    ) {
        Component c = semantic != null ? semantic.source() : null;
        Map<String, Object> params = c != null ? c.params() : null;
        int doorW = ComponentParamParsers.intParam(params, "door_width", "doorWidth");
        int doorH = ComponentParamParsers.intParam(params, "door_height", "doorHeight");
        if (doorW <= 0) {
            doorW = Math.max(2, Math.min(5, width / 4));
        }
        if (doorH <= 0) {
            doorH = Math.max(3, Math.min(6, height / 3));
        }

        Map<String, Object> door = new HashMap<>();
        door.put("face", (entranceFace == null || entranceFace.isBlank()) ? "SOUTH" : entranceFace);
        door.put("kind", "DOOR");
        door.put("doorW", doorW);
        door.put("doorH", doorH);
        door.put("sillY", 1);
        return door;
    }

    private static double clamp01(double v) {
        if (v < 0.0) return 0.0;
        return Math.min(v, 1.0);
    }

    private static Boolean getParamBoolean(Map<String, Object> params, String... keys) {
        if (params == null || keys == null) return null;
        for (String key : keys) {
            if (key == null) continue;
            Object v = params.get(key);
            switch (v) {
                case Boolean b -> {
                    return b;
                }
                case String s -> {
                    String t = s.trim().toLowerCase(Locale.ROOT);
                    if (t.equals("true") || t.equals("1") || t.equals("yes")) return true;
                    if (t.equals("false") || t.equals("0") || t.equals("no")) return false;
                }
                case Number n -> {
                    return n.doubleValue() != 0.0;
                }
                case null, default -> {
                }
            }
        }
        return null;
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
}
