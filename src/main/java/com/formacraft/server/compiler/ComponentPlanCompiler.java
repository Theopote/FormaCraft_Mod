package com.formacraft.server.compiler;

import com.formacraft.common.generation.component.util.GeneratedSurfaceCapture;
import com.formacraft.common.generation.component.util.ComponentFootprintUtil;

import com.formacraft.common.generation.component.util.ComponentCrownDecorator;
import com.formacraft.common.generation.component.util.ComponentFoundationEnforcer;
import com.formacraft.common.generation.component.util.ComponentFacadeRhythmPlanner;
import com.formacraft.common.alignment.BayGridRhythmPlanner;
import com.formacraft.common.generation.component.util.ComponentParamParsers;
import com.formacraft.common.alignment.AlignmentContractEnforcer;
import com.formacraft.common.compiler.postprocess.PostProcessContext;
import com.formacraft.common.compiler.postprocess.PostProcessPipeline;
import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.generation.component.ComponentGeneratorRegistry;
import com.formacraft.server.generation.GenerationHub;
import com.formacraft.server.assembly.AssemblyCirculationConstraints;
import com.formacraft.server.assembly.AssemblyCompileDiagnostics;
import com.formacraft.common.llm.dto.CapabilityGap;
import com.formacraft.server.generation.component.adaptor.UnifiedGeneratorRouter;
import com.formacraft.common.llm.NonClassicalEnrichmentGuard;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.GlobalConstraints;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.Slot;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.style.StyleIntentResolver;
import com.formacraft.common.proportion.CrownGrammarResolver;
import com.formacraft.common.proportion.OpeningGrammarResolver;
import com.formacraft.common.proportion.RoofGrammarResolver;
import com.formacraft.FormacraftMod;
import com.formacraft.common.typology.TypologyComponentRouter;
import com.formacraft.common.typology.TypologyPatchBridge;
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
    public record Compilation(List<BlockPatch> patches, List<AssemblyCirculationConstraints.Flight> circulation) {
        public Compilation { patches = List.copyOf(patches); circulation = List.copyOf(circulation); }
    }

    /** Explicit metadata result for preview; coordinates remain relative to the plan origin. */
    public static Compilation compileWithCirculation(LlmPlan plan, BlockPos globalAnchor, ServerWorld world,
                                                      TerrainStrategySampler terrainSampler, boolean applyTerrainAdaptation) {
        var flights = new ArrayList<AssemblyCirculationConstraints.Flight>();
        try (var capture = AssemblyCirculationConstraints.captureTo(flights::addAll)) {
            return new Compilation(compile(plan, globalAnchor, world, terrainSampler, applyTerrainAdaptation), flights);
        }
    }

    private ComponentPlanCompiler() {}

    private record PreparedComponents(List<Component> components, Set<String> assemblyFacadeSlots) {}

    private static final Map<String, String> COMPONENT_TYPE_ALIASES = Map.of(
            "MAIN_MASS", "MASS_MAIN",
            "BUTTRESS", "WALL",
            "WINDOW", "FACADE_WINDOWS",
            "WINDOWS", "FACADE_WINDOWS",
            "RAMPART", "WALL",
            "PALISADE", "WALL",
            "BARRIER", "WALL",
            "PARAPET", "WALL"
    );

    private static final Set<String> AUTO_INFERRED_TYPES = Set.of(
            "FACADE_WINDOWS",
            "ENTRANCE",
            "ROOF",
            "ROOF_STRUCTURE",
            "CROWN",
            "CUPOLA",
            "DOME"
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

        AssemblyCompileDiagnostics.clear();

        plan = com.formacraft.common.llm.parser.LlmPlanAnchorNormalizer.normalize(plan);
        plan = com.formacraft.common.llm.DistinguishingFeaturesBridge.enrich(plan);
        plan = NonClassicalEnrichmentGuard.sanitize(plan);
        var invalidMaterial = com.formacraft.common.palette.dynamic.ExplicitMaterialPolicy.invalidAttribute(plan.styleAttributes(), plan.components());
        if (invalidMaterial.isPresent()) {
            AssemblyCompileDiagnostics.set(new CapabilityGap("E_MATERIAL_INVALID",
                    "无法解析明确指定的材料：" + invalidMaterial.get(), "style_attributes",
                    List.of("Use a registered block id or a supported material name.")));
            return List.of();
        }

        // 索引 slots（便于快速查找）
        Map<String, Slot> slotMap = indexSlots(plan);
        boolean allowAssemblyFacade = world != null && globalAnchor != null;
        PreparedComponents prepared = prepareComponents(plan, slotMap, allowAssemblyFacade);
        List<Component> components = prepared.components();
        Set<String> assemblyFacadeSlots = prepared.assemblyFacadeSlots();
        var designConflict = ExplicitDesignConflictValidator.check(plan, components);
        if (designConflict.isPresent()) {
            AssemblyCompileDiagnostics.set(designConflict.get());
            return List.of();
        }

        if (components.isEmpty()) {
            FormacraftMod.LOGGER.info("ComponentPlanCompiler: no components to compile");
            return result;
        }
        for (var component : components) {
            if (component == null) continue;
            var invalid = com.formacraft.common.palette.dynamic.ExplicitMaterialPolicy.invalidComponent(component);
            if (invalid.isPresent()) {
                AssemblyCompileDiagnostics.set(new CapabilityGap("E_MATERIAL_INVALID",
                        "无法解析明确指定的构件材料：" + invalid.get(), "components[]",
                        List.of("Use a registered block id and valid state properties.")));
                return List.of();
            }
        }

        List<PostProcessContext.BuildingVolume> buildingVolumes = new ArrayList<>();
        Set<BlockPos> generatedSurfaces = new HashSet<>();
        Set<BlockPos> protectedMaterials = new HashSet<>();
        Set<BlockPos> decorationRestrictions = new HashSet<>();
        var circulation = new ArrayList<AssemblyCirculationConstraints.Flight>();
        var flatRoofs = new ArrayList<FlatRoofCoverageValidator.Roof>();
        boolean typologyExclusivePlan = hasTypologyStructureComponent(components);
        UnifiedGeneratorRouter.setTypologyExclusivePlan(typologyExclusivePlan);
        TypologyPatchBridge.setPlanWorldAnchor(globalAnchor);
        try {
            compileComponents(plan, world, globalAnchor, allowAssemblyFacade, components, assemblyFacadeSlots,
                    slotMap, result, buildingVolumes, circulation, generatedSurfaces, flatRoofs, protectedMaterials, decorationRestrictions);
        } finally {
            UnifiedGeneratorRouter.clearTypologyExclusivePlan();
            TypologyPatchBridge.clearPlanWorldAnchor();
        }
        if (AssemblyCompileDiagnostics.hasGap()) return List.of();

        Set<BlockPos> roofClearance = new HashSet<>();
        for (var flight : circulation) roofClearance.addAll(flight.clearance());
        var roofMissing = FlatRoofCoverageValidator.check(flatRoofs, result, roofClearance);
        if (roofMissing.isPresent()) {
            var missing = roofMissing.get();
            AssemblyCompileDiagnostics.set(new CapabilityGap("E_FLAT_ROOF_COVERAGE",
                    "平屋顶核心覆盖不完整：" + missing.source() + ", plan=" + missing.position().toShortString()
                            + ", missing=" + missing.count(), "components[]",
                    List.of("Restore the roof core or remove the conflicting operation; keep courtyard and stair openings explicit.")));
            return List.of();
        }
        for (var body : components) {
            if (!"MASS_MAIN".equals(normalizeType(body.componentType()))) continue;
            Slot hostSlot = slotMap.get(body.slotId());
            if (hostSlot == null) hostSlot = defaultSlot(plan);
            Vec3i hostAnchor = hostSlot.anchor();
            var hostSemantic = new SemanticComponent(body.componentType(), hostSlot, body,
                    plan.styleProfile(), plan.styleAttributes(), plan.genome());
            var uncovered = FlatRoofCoverageValidator.checkHost(hostSemantic, flatRoofs,
                    hostAnchor == null ? BlockPos.ORIGIN : new BlockPos(hostAnchor.x(),hostAnchor.y(),hostAnchor.z()), result, roofClearance);
            if (uncovered.isPresent()) {
                var missing = uncovered.get();
                AssemblyCompileDiagnostics.set(new CapabilityGap("E_FLAT_ROOF_HOST_COVERAGE",
                        "平屋顶未覆盖宿主顶层：part=" + missing.source() + ", plan=" + missing.position().toShortString()
                                + ", uncovered=" + missing.count(), "components[]",
                        List.of("Align and size the bound roof to the host footprint; preserve explicit courtyard and L-shaped voids.")));
                return List.of();
            }
        }

        FormacraftMod.LOGGER.info("ComponentPlanCompiler: compiled {} components into {} patches",
                components.size(), result.size());

        // 后处理步骤
        if (globalAnchor != null) {
            Set<BlockPos> clearance = new HashSet<>();
            for (var flight : circulation) {
                clearance.addAll(flight.clearance());
                clearance.addAll(flight.occupied());
            }
            PostProcessContext context = new PostProcessContext(plan, globalAnchor,
                    plan.anchor() == null ? new Vec3i(0, 0, 0) : plan.anchor(),
                    buildingVolumes, clearance, generatedSurfaces, protectedMaterials, decorationRestrictions);
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

        for (var roof : components) {
            if (!"ROOF".equals(normalizeType(roof.componentType()))) continue;
            var slot = slotMap.get(roof.slotId());
            if (slot == null) slot = defaultSlot(plan);
            var anchor = slot == null ? null : slot.anchor();
            var offset = anchor == null ? BlockPos.ORIGIN : new BlockPos(anchor.x(), anchor.y(), anchor.z());
            var semantic = new SemanticComponent("ROOF", slot, roof, plan.styleProfile());
            RoofShapeAudit.inspect(semantic, result, offset).ifPresent(audit ->
                    FormacraftMod.LOGGER.info("[RoofShapeAudit] stage=postprocess result={}", audit));
            RoofSeamAudit.inspect(semantic, components, slotMap, defaultSlot(plan), result).ifPresent(audit ->
                    FormacraftMod.LOGGER.info("[RoofSeamAudit] stage=postprocess result={}", audit));
        }

        try {
            AssemblyCirculationConstraints.validatePatches(result, circulation);
        } catch (AssemblyCirculationConstraints.Conflict conflict) {
            AssemblyCompileDiagnostics.set(new CapabilityGap(
                "E_PLAN_CIRCULATION_CONFLICT", conflict.getMessage(), "components[]",
                List.of("Separate conflicting components or fix post-processing at stair treads and clearance.")));
            return List.of();
        }
        if (AssemblyCompileDiagnostics.hasGap()) return List.of();
        AssemblyCirculationConstraints.publish(circulation);
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

    private static boolean hasTypologyStructureComponent(List<Component> components) {
        if (components == null || components.isEmpty()) {
            return false;
        }
        for (Component c : components) {
            if (c == null) {
                continue;
            }
            if ("STRUCTURE".equals(normalizeType(c.componentType()))
                    && TypologyComponentRouter.hasTypologyHint(c)) {
                return true;
            }
        }
        return false;
    }

    private static void compileComponents(
            LlmPlan plan,
            ServerWorld world,
            BlockPos globalAnchor,
            boolean allowAssemblyFacade,
            List<Component> components,
            Set<String> assemblyFacadeSlots,
            Map<String, Slot> slotMap,
            List<BlockPatch> result,
            List<PostProcessContext.BuildingVolume> buildingVolumes,
            List<AssemblyCirculationConstraints.Flight> circulation,
            Set<BlockPos> generatedSurfaces,
            List<FlatRoofCoverageValidator.Roof> flatRoofs,
            Set<BlockPos> protectedMaterials,
            Set<BlockPos> decorationRestrictions
    ) {
        // Shells and floor slabs must be emitted before stair clearance carves.
        var ordered = new ArrayList<>(components);
        ordered.sort(java.util.Comparator.comparingInt(ComponentPlanCompiler::compilationPriority));
        var surfaceLedger = new SurfaceOwnershipLedger();
        for (Component c : ordered) {
            if (c == null) continue;
            String normalizedType = normalizeType(c.componentType());
            if (isRoofType(normalizedType) && com.formacraft.common.style.ExplicitDesignPolicy.roofDisabled(c)) continue;

            Slot slot = slotMap.get(c.slotId());
            if (slot == null) {
                slot = defaultSlot(plan);
                FormacraftMod.LOGGER.debug("ComponentPlanCompiler: component {} has no slot, using default slot", c.componentType());
            }
            String slotKey = slotKey(c);

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

            List<BlockPatch> patches;
            var componentFlights = new ArrayList<AssemblyCirculationConstraints.Flight>();
            try (var capture = AssemblyCirculationConstraints.captureTo(componentFlights::addAll);
                 var materials = com.formacraft.common.palette.component.PaletteSelectionScope.open(plan, c)) {
                var roofAttachment = FlatRoofCoverageValidator.attachmentMismatch(semantic, components, slotMap, defaultSlot(plan));
                if (roofAttachment.isPresent()) {
                    var attachment = roofAttachment.get();
                    AssemblyCompileDiagnostics.set(new CapabilityGap("E_FLAT_ROOF_ATTACHMENT",
                            "平屋顶接合高度不匹配：host=" + attachment.host() + ", expected plan y=" + attachment.expectedY()
                                    + ", actual plan y=" + attachment.actualY(), "components[]",
                            List.of("Align the flat roof to the resolved host part's top plane, including slot offset once.")));
                    return;
                }
                Vec3i roofSlotAnchor = slot.anchor();
                FlatRoofCoverageValidator.resolve(semantic, roofSlotAnchor == null ? BlockPos.ORIGIN
                        : new BlockPos(roofSlotAnchor.x(), roofSlotAnchor.y(), roofSlotAnchor.z())).ifPresent(flatRoofs::add);
                var surfaceCells = new ArrayList<GeneratedSurfaceCapture.Cell>();
                try (var surfaceCapture = GeneratedSurfaceCapture.captureTo(surfaceCells::add)) {
                    patches = GenerationHub.generateComponent(semantic, world);
                }
                var missingSurfaces = GeneratedSurfaceCapture.missing(surfaceCells, patches);
                if (!missingSurfaces.isEmpty()) {
                    var first = missingSurfaces.getFirst();
                    AssemblyCompileDiagnostics.set(new CapabilityGap("E_SURFACE_GENERATION_INCOMPLETE",
                            "生成器未提供声明的建筑表面：" + normalizedType + " " + first.role()
                                    + " at component " + first.position().toShortString() + "; missing=" + missingSurfaces.size(),
                            "components[]", List.of("Fix surface material or generator emission; keep authored openings explicitly marked.")));
                    return;
                }
                if (!patches.isEmpty()) {
                    if (allowAssemblyFacade && globalAnchor != null && isMassType(normalizedType)
                            && assemblyFacadeSlots.contains(getParamString(c.params(), "component_id"))) {
                        List<BlockPatch> facade = generateAssemblyFacadePatches(plan, semantic, slot, globalAnchor, world);
                        if (!facade.isEmpty()) {
                            List<BlockPatch> merged = new ArrayList<>(patches.size() + facade.size());
                            merged.addAll(patches);
                            merged.addAll(facade);
                            patches = merged;
                        }
                    }
                    logComponentPatchCount(normalizedType, c, patches.size());
                    com.formacraft.common.llm.dto.Vec3i slotAnchor = slot.anchor();
                    BlockPos flightOffset = slotAnchor == null ? BlockPos.ORIGIN : new BlockPos(slotAnchor.x(), slotAnchor.y(), slotAnchor.z());
                    for (var cell : surfaceCells) if (cell.requiresBlock())
                        generatedSurfaces.add(cell.shift(flightOffset).position());
                    var shiftedFlights = componentFlights.stream()
                            .map(flight -> AssemblyCirculationConstraints.shift(flight, flightOffset)).toList();
                    var exteriorCollision = ExteriorCirculationValidator.findCollision(result, shiftedFlights, buildingVolumes);
                    if (exteriorCollision.isPresent()) {
                        var collision = exteriorCollision.get();
                        AssemblyCompileDiagnostics.set(new CapabilityGap("E_CIRCULATION_EXTERIOR_CONFLICT",
                                "楼梯占用或净空覆盖已有外墙：" + collision.position().toShortString()
                                        + " (" + collision.role() + ")", "components[]",
                                List.of("Move the stair flight and its clearance inside the host building; use an existing doorway for exterior access.")));
                        return;
                    }
                    circulation.addAll(shiftedFlights);
                    // A floor plate has no enclosing walls, even if legacy dimensions give it height.
                    if (isMassType(normalizedType) && !(c.params() != null
                            && "plate".equalsIgnoreCase(String.valueOf(c.params().get("extrude_mode"))))) {
                        for (var part : com.formacraft.common.generation.component.util.ResolvedMassPart.resolve(c)) {
                        var bounds = part.bounds();
                        if (bounds != null) {
                            Vec3i offset = slotAnchor == null ? new Vec3i(0, 0, 0) : slotAnchor;
                            var shifted = new ComponentFootprintUtil.Bounds(bounds.minX() + offset.x(), bounds.minY() + offset.y(),
                                bounds.minZ() + offset.z(), bounds.maxX() + offset.x(), bounds.maxY() + offset.y(), bounds.maxZ() + offset.z());
                            int floorHeight = com.formacraft.common.generation.component.util.ComponentFloorCorniceDecorator
                                .resolveFloorHeight(plan, c, shifted.height());
                            buildingVolumes.add(new PostProcessContext.BuildingVolume(slotKey, shifted, floorHeight));
                        }
                        }
                    }


                    var shiftedPatches = new ArrayList<BlockPatch>();
                    if (slotAnchor != null) {
                        for (BlockPatch patch : patches) {
                            if (patch != null) {
                                shiftedPatches.add(new BlockPatch(
                                        patch.action(),
                                        slotAnchor.x() + patch.dx(),
                                        slotAnchor.y() + patch.dy(),
                                        slotAnchor.z() + patch.dz(),
                                        patch.targetBlock()
                                ));
                            }
                        }
                    } else {
                        FormacraftMod.LOGGER.warn("ComponentPlanCompiler: missing slotAnchor, component={}", c.componentType());
                        shiftedPatches.addAll(patches);
                    }
                    var surfaceConflict = surfaceLedger.check(c, shiftedPatches);
                    if (surfaceConflict.isPresent()) {
                        var conflict = surfaceConflict.get();
                        AssemblyCompileDiagnostics.set(new CapabilityGap("E_SURFACE_OVERWRITE_FORBIDDEN",
                                "构件覆盖外壳冲突：writer=" + conflict.writer() + ", owner=" + conflict.surface().owner()
                                        + ", surface=" + conflict.surface().role() + ", plan=" + conflict.position().toShortString()
                                        + "; " + conflict.reason(), "components[]",
                                List.of("Keep decoration non-destructive; bind wall openings to their host building.")));
                        return;
                    }
                    result.addAll(shiftedPatches);
                    var explicitTargets = com.formacraft.common.palette.dynamic.ExplicitMaterialPolicy.targets(c, styleAttributes);
                    for (var patch : shiftedPatches) {
                        if (com.formacraft.common.patch.BlockPatchTargetResolver.resolve(patch) == null) {
                            AssemblyCompileDiagnostics.set(new CapabilityGap("E_MATERIAL_INVALID",
                                    "构件生成了无效方块材料：" + c.componentType() + " " + patch.targetBlock(),
                                    "components[]", List.of("Use a registered block id and valid state properties.")));
                            return;
                        }
                        var position = new BlockPos(patch.dx(), patch.dy(), patch.dz());
                        if (com.formacraft.common.style.ExplicitDesignPolicy.noComplexDecor(plan, c.params())) {
                            // Optional post-processing decorates existing cells and adjacent trim cells.
                            for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++)
                                decorationRestrictions.add(position.add(dx, dy, dz));
                        }
                        if (!BlockPatch.REMOVE.equals(patch.action()) && explicitTargets.contains(
                                com.formacraft.common.palette.dynamic.ExplicitMaterialPolicy.canonical(patch.targetBlock())))
                            protectedMaterials.add(position);
                        else protectedMaterials.remove(position);
                    }
                    surfaceLedger.accept(c, shiftedPatches, surfaceCells, flightOffset);
                } else {
                    FormacraftMod.LOGGER.warn(
                            "ComponentPlanCompiler: no patches generated for component: {}{}",
                            c.componentType(),
                            componentPatchDiagSuffix(normalizedType, c));
                }
            } catch (Exception e) {
                FormacraftMod.LOGGER.error("ComponentPlanCompiler: error generating component {}: {}",
                        c.componentType(), e.getMessage(), e);
            }
        }
    }

    private static int compilationPriority(Component c) {
        if (c == null) return 2;
        if (com.formacraft.server.generation.component.impl.StraightStairComponentGenerator.accepts(c)
                || com.formacraft.server.generation.component.impl.AssemblyPatchGenerator.hasCirculation(c)) return 3;
        String type = normalizeType(c.componentType());
        if (isMassType(type)) return 0;
        return "ROOF".equals(type) || "ROOF_STRUCTURE".equals(type) ? 1 : 2;
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
            if (normalizedComponent.slotId() == null && getParamString(normalizedComponent.params(), "slot_id") != null) {
                normalizedComponent = new Component(normalizedComponent.componentType(), getParamString(normalizedComponent.params(), "slot_id"),
                        normalizedComponent.relativePosition(), normalizedComponent.dimensions(), normalizedComponent.features(), normalizedComponent.params());
            }
            if (normalizedComponent.params() != null && normalizedComponent.params().containsKey("compiler_suppressed_roof")) {
                var params = new HashMap<String, Object>(normalizedComponent.params());
                params.remove("compiler_suppressed_roof");
                normalizedComponent = new Component(normalizedComponent.componentType(), normalizedComponent.slotId(),
                        normalizedComponent.relativePosition(), normalizedComponent.dimensions(), normalizedComponent.features(), params);
            }
            normalizedComponent = reconcileDelegatedRoof(plan, normalizedComponent, normalized);
            if ("MASS_MAIN".equals(normalizeType(normalizedComponent.componentType()))
                    && ComponentParamParsers.intParam(normalizedComponent.params(), "floor_count", "floorCount") > 0
                    && ComponentParamParsers.intOrNull(normalizedComponent.params(), "wall_thickness", "wallThickness") == null) {
                var params = new HashMap<String, Object>(normalizedComponent.params());
                params.put("wall_thickness", 1);
                normalizedComponent = new Component(normalizedComponent.componentType(), normalizedComponent.slotId(),
                        normalizedComponent.relativePosition(), normalizedComponent.dimensions(), normalizedComponent.features(), params);
            }
            normalizedComponent = StyleIntentResolver.apply(plan, normalizedComponent);
            normalizedComponent = OpeningGrammarResolver.apply(plan, normalizedComponent);
            normalizedComponent = com.formacraft.common.generation.component.util.ResolvedComponentGeometry.normalizeBody(normalizedComponent);
            String type = normalizeType(normalizedComponent.componentType());
            if (com.formacraft.common.style.ExplicitDesignPolicy.noComplexDecor(plan, normalizedComponent.params())) {
                var params = new HashMap<String, Object>();
                if (normalizedComponent.params() != null) params.putAll(normalizedComponent.params());
                params.put("no_complex_decor", true);
                normalizedComponent = new Component(normalizedComponent.componentType(), normalizedComponent.slotId(),
                        normalizedComponent.relativePosition(), normalizedComponent.dimensions(), normalizedComponent.features(), params);
            }
            if (isMassType(type) && getParamString(normalizedComponent.params(), "component_id") == null) {
                var params = new HashMap<String, Object>();
                if (normalizedComponent.params() != null) params.putAll(normalizedComponent.params());
                params.put("component_id", "mass:" + normalizedComponent.slotId() + ":" + normalizedComponent.relativePosition());
                normalizedComponent = new Component(normalizedComponent.componentType(), normalizedComponent.slotId(),
                        normalizedComponent.relativePosition(), normalizedComponent.dimensions(), normalizedComponent.features(), params);
            }
            String slotKey = slotKey(normalizedComponent);
            if (isMassType(type)) {
                massSlots.add(slotKey);
            }
            components.add(normalizedComponent);
        }

        AssemblyPlanPromoter.PromotionResult assemblyPromotion = AssemblyPlanPromoter.promoteNestedAssembly(components);
        components = assemblyPromotion.components();
        components = applyTypologyExclusiveFilter(components);
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

        components = AlignmentContractEnforcer.apply(plan, components);
        for (int i = 0; i < components.size(); i++) {
            Component slab = components.get(i);
            if (!"plate".equalsIgnoreCase(getParamString(slab.params(), "extrude_mode"))) continue;
            String host = getParamString(slab.params(), "host_id");
            for (Component body : components) {
                if (!"MASS_MAIN".equals(normalizeType(body.componentType()))
                        || host == null || !host.equals(getParamString(body.params(), "component_id"))) continue;
                int fh = ComponentParamParsers.intParam(body.params(), "floor_height", "floorHeight");
                Vec3i origin = resolveMassOrigin(body);
                if (fh <= 0 || origin == null || slab.relativePosition() == null) break;
                int count = ComponentParamParsers.intParam(body.params(), "floor_count", "floorCount");
                int level = Math.max(1, Math.min(Math.max(1, count - 1), Math.round((slab.relativePosition().y() - origin.y()) / (float) fh)));
                Vec3i pos = slab.relativePosition();
                var slabParams = new HashMap<String, Object>(slab.params());
                String floorBlock = getParamString(body.params(), "floor_block");
                String slabMaterial = getParamString(slab.params(), "material");
                // A generic material family must not override the host's explicit floor block.
                if (floorBlock != null && (slabMaterial == null
                        || Set.of("wood", "minecraft:wood", "planks", "wooden").contains(slabMaterial.toLowerCase(java.util.Locale.ROOT))))
                    slabParams.put("material", floorBlock);
                components.set(i, new Component(slab.componentType(), slab.slotId(),
                        new Vec3i(pos.x(), origin.y() + level * fh, pos.z()),
                        new Dimensions(slab.dimensions().width(), slab.dimensions().depth(), 1), slab.features(), slabParams));
                break;
            }
        }

        Set<String> slotsWithCrown = new HashSet<>();
        Set<String> assemblyFacadeSlots = new HashSet<>();
        for (Component c : components) {
            if (c == null) continue;
            String type = normalizeType(c.componentType());
            String slotKey = slotKey(c);
            if (isCrownType(type)) {
                slotsWithCrown.add(slotKey);
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
            // Floor plates are structural slabs, not buildings with facades, doors and roofs.
            if (c.params() != null && "plate".equalsIgnoreCase(String.valueOf(c.params().get("extrude_mode")))) {
                prepared.add(c);
                continue;
            }
            String slotId = c.slotId();
            Slot slot = slotId != null ? slotMap.get(slotId) : null;
            GlobalConstraints.Facing facing = resolveSlotFacing(plan, slotMap, slotId);
            boolean hasFacade = hasSatelliteForMass(c, components, inferred, "FACADE_WINDOWS");
            boolean hasEntrance = hasSatelliteForMass(c, components, inferred, "ENTRANCE");
            boolean windowsDisabled = com.formacraft.common.style.ExplicitDesignPolicy.windowsDisabled(c);
            boolean entranceDisabled = com.formacraft.common.style.ExplicitDesignPolicy.entranceDisabled(c);
            boolean useAssemblyFacade = allowAssemblyFacade
                    && shouldUseAssemblyFacade(plan, c)
                    && !hasFacade
                    && !hasEntrance && !windowsDisabled && !entranceDisabled;

            if (useAssemblyFacade) {
                assemblyFacadeSlots.add(getParamString(c.params(), "component_id"));
                c = markAssemblyFacade(c);
            } else {
                if (!hasFacade && !windowsDisabled) {
                    Component facade = makeFacadeComponent(c, slotId);
                    facade = StyleIntentResolver.apply(plan, facade);
                    facade = OpeningGrammarResolver.apply(plan, facade);
                    inferred.add(facade);
                    hasFacade = true;
                }
                if (!hasEntrance && !entranceDisabled) {
                    Component entrance = makeEntranceComponent(plan, c, slotId, facing);
                    if (entrance != null) {
                        entrance = StyleIntentResolver.apply(plan, entrance);
                        inferred.add(entrance);
                        hasEntrance = true;
                    }
                }
            }
            if (hasFacade || hasEntrance || windowsDisabled || entranceDisabled) {
                c = suppressMassOpenings(c, hasFacade || windowsDisabled, hasEntrance || entranceDisabled);
            }
            if (!hasSatelliteForMass(c, components, inferred, "ROOF")) {
                Component roof = makeRoofComponent(plan, c, slotId);
                if (roof != null) {
                    roof = StyleIntentResolver.apply(plan, roof);
                    roof = RoofGrammarResolver.apply(plan, roof);
                    inferred.add(roof);
                    c = suppressMassRoof(c);
                }
            }
            inferNestedFlatRoofs(plan, c, components, inferred, slotId);
            if (!slotsWithCrown.contains(slotKey)
                    && !NonClassicalEnrichmentGuard.blocksCrownInference(plan)
                    && ComponentCrownDecorator.shouldApply(plan, c.params())) {
                Component roofRef = findRoofForSlot(components, inferred, slotKey);
                if (roofRef == null) {
                    roofRef = makeRoofComponent(plan, c, slotId);
                }
                Component crown = makeCrownComponent(plan, c, roofRef, slotId);
                if (crown != null) {
                    crown = StyleIntentResolver.apply(plan, crown);
                    crown = CrownGrammarResolver.apply(plan, crown);
                    inferred.add(crown);
                    slotsWithCrown.add(slotKey);
                }
            }
            prepared.add(c);
        }

        if (!inferred.isEmpty()) {
            FormacraftMod.LOGGER.info("ComponentPlanCompiler: inferred {} facade/entrance/roof/crown components", inferred.size());
            prepared.addAll(inferred);
        }

        realignSatellitesToMass(prepared, plan, slotMap, assemblyPrimarySlots);
        ComponentFoundationEnforcer.apply(prepared);

        return new PreparedComponents(prepared, assemblyFacadeSlots);
    }

    /**
     * When a plan already has {@code STRUCTURE + typology:*}, drop redundant compositional
     * building parts (MASS/TOWER/ROOF/…) that the typology builder already owns.
     */
    private static List<Component> applyTypologyExclusiveFilter(List<Component> components) {
        if (components == null || components.isEmpty()) {
            return components;
        }

        boolean hasTypologyStructure = false;
        for (Component c : components) {
            if (c == null) {
                continue;
            }
            if ("STRUCTURE".equals(normalizeType(c.componentType()))
                    && TypologyComponentRouter.hasTypologyHint(c)) {
                hasTypologyStructure = true;
                break;
            }
        }
        if (!hasTypologyStructure) {
            return components;
        }

        List<Component> kept = new ArrayList<>();
        int stripped = 0;
        for (Component c : components) {
            if (c == null) {
                continue;
            }
            String type = normalizeType(c.componentType());
            if ("STRUCTURE".equals(type) && TypologyComponentRouter.hasTypologyHint(c)) {
                kept.add(c);
            } else if (isTypologyExclusivePeripheral(type)) {
                kept.add(c);
            } else {
                stripped++;
            }
        }

        if (stripped > 0) {
            FormacraftMod.LOGGER.info(
                    "ComponentPlanCompiler: typology-exclusive plan stripped {} compositional component(s), kept {}",
                    stripped, kept.size());
        }
        return kept;
    }

    private static boolean isTypologyExclusivePeripheral(String type) {
        return "PAVING".equals(type) || "PATH".equals(type) || "COURTYARD_SPACE".equals(type);
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
            if (isMassType(type) || isResolvedPartRoof(c, components, plan)) {
                continue;
            }
            Object hostId = c.params() == null ? null : c.params().get("host_id");
            Component mass = massBySlot.get(slotKey);
            if (hostId != null) {
                mass = null;
                for (Component candidate : components) {
                    if (candidate == null || !"MASS_MAIN".equals(normalizeType(candidate.componentType()))
                            || candidate.params() == null
                            || !hostId.equals(candidate.params().get("component_id"))) continue;
                    // Explicit ownership cannot equate different real slot coordinate frames.
                    if (slotKey(candidate).equals(slotKey)
                            || (slotMap != null && !slotMap.containsKey(candidate.slotId())
                                && !slotMap.containsKey(c.slotId()))) mass = candidate;
                    break;
                }
            }
            if (mass == null) {
                continue;
            }
            // Several unassigned masses share __global__. Attach to the nearest body,
            // rather than moving every facade and roof onto the first building.
            if (hostId == null && c.relativePosition() != null) {
                double nearest = Double.POSITIVE_INFINITY;
                for (Component candidate : components) {
                    if (!"MASS_MAIN".equals(normalizeType(candidate.componentType()))
                            || !slotKey(candidate).equals(slotKey)) continue;
                    Vec3i origin = resolveMassOrigin(candidate);
                    if (origin == null || candidate.dimensions() == null) continue;
                    double cx = origin.x() + (candidate.dimensions().width() - 1) / 2.0;
                    double cz = origin.z() + (candidate.dimensions().depth() - 1) / 2.0;
                    double x = c.relativePosition().x(), z = c.relativePosition().z();
                    if (ComponentFootprintUtil.isCornerAnchor(c.params()) && c.dimensions() != null) {
                        x += (c.dimensions().width() - 1) / 2.0;
                        z += (c.dimensions().depth() - 1) / 2.0;
                    }
                    double distance = (cx-x)*(cx-x) + (cz-z)*(cz-z);
                    if (distance < nearest) { nearest = distance; mass = candidate; }
                }
            }

            GlobalConstraints.Facing facing = resolveSlotFacing(plan, slotMap, c.slotId());
            Component aligned = switch (type) {
                case "FACADE_WINDOWS" -> alignFacadeToMass(c, mass, plan, facing);
                case "ENTRANCE" -> alignEntranceToMass(c, mass, plan, facing);
                case "CROWN", "CUPOLA", "DOME" -> alignCrownToMass(c, mass, plan, components);
                case "FOUNDATION", "TERRACE", "BASE" -> alignFoundationToMass(c, mass);
                case "DECOR_DETAIL", "CHIMNEY" -> alignHostedDetail(c, mass);
                default -> isRoofType(type) ? alignRoofToMass(c, mass, plan) : c;
            };
            if (aligned != null && mass.params() != null && mass.params().get("component_id") != null) {
                var params = new HashMap<String, Object>();
                if (aligned.params() != null) params.putAll(aligned.params());
                params.putIfAbsent("host_id", mass.params().get("component_id"));
                if (com.formacraft.common.style.ExplicitDesignPolicy.noComplexDecor(plan, mass.params())) params.put("no_complex_decor", true);
                aligned = new Component(aligned.componentType(), aligned.slotId(), aligned.relativePosition(),
                        aligned.dimensions(), aligned.features(), params);
            }
            if (aligned != null && aligned != c) {
                components.set(i, aligned);
                realigned++;
            }
        }
        if (realigned > 0) {
            FormacraftMod.LOGGER.info("ComponentPlanCompiler: realigned {} satellite component(s) to MASS min_corner", realigned);
        }
    }

    private static boolean isResolvedPartRoof(Component roof, List<Component> components, LlmPlan plan) {
        if (!isRoofType(normalizeType(roof.componentType())) || roof.params() == null
                || !Boolean.TRUE.equals(roof.params().get("resolved_mass_part_roof"))) return false;
        Object partId = roof.params().get("host_part_id");
        for (Component mass : components) {
            if (mass == null || !"MASS_MAIN".equals(normalizeType(mass.componentType()))
                    || !slotKey(mass).equals(slotKey(roof))) continue;
            Object hostId = roof.params().get("host_id");
            if (hostId != null && mass.params() != null && mass.params().get("component_id") != null
                    && !hostId.equals(mass.params().get("component_id"))) continue;
            var parts = com.formacraft.common.generation.component.util.ResolvedMassPart.resolve(mass);
            for (int i = 1; i < parts.size(); i++) {
                var part = parts.get(i);
                if (!part.partId().equals(partId)) continue;
                var frame = resolvePartRoofFrame(plan, mass, part);
                if (frame != null && frame.origin().equals(roof.relativePosition())
                        && roof.dimensions() != null && frame.width() == roof.dimensions().width()
                        && frame.depth() == roof.dimensions().depth()) return true;
            }
        }
        return false;
    }

    private static GlobalConstraints.Facing resolveSlotFacing(LlmPlan plan, Map<String, Slot> slotMap, String slotId) {
        if (slotId != null && slotMap != null) {
            Slot slot = slotMap.get(slotId);
            if (slot != null) {
                // Generators default a real slot without facing to SOUTH, not the global facing.
                return slot.facing() != null ? slot.facing() : GlobalConstraints.Facing.SOUTH;
            }
        }
        if (plan != null && plan.globalConstraints() != null && plan.globalConstraints().facing() != null) {
            return plan.globalConstraints().facing();
        }
        return GlobalConstraints.Facing.SOUTH;
    }

    private static Component alignHostedDetail(Component detail, Component mass) {
        Vec3i origin = resolveMassOrigin(mass);
        Dimensions body = mass.dimensions(), dims = detail.dimensions();
        if (origin == null || body == null || dims == null || detail.relativePosition() == null) return detail;
        String identity = getParamString(detail.params(), "component_id");
        boolean chimney = "CHIMNEY".equals(normalizeType(detail.componentType()))
                || identity != null && identity.toLowerCase(Locale.ROOT).contains("chimney");
        boolean perimeter = dims.width() >= body.width() - 2 && dims.depth() >= body.depth() - 2
                && dims.height() <= 1;
        if (!chimney && !perimeter) return detail;
        var params = new HashMap<String, Object>();
        if (detail.params() != null) params.putAll(detail.params());
        params.put("anchor_mode", "min_corner");
        params.put("resolved_host_attachment", true);
        Vec3i position;
        if (chimney) {
            int x = Math.max(origin.x() + 1, Math.min(origin.x() + body.width() - dims.width() - 1, detail.relativePosition().x()));
            int z = Math.max(origin.z() + 1, Math.min(origin.z() + body.depth() - dims.depth() - 1, detail.relativePosition().z()));
            position = new Vec3i(x, origin.y() + body.height() - 1, z);
        } else {
            // A cornice surrounds the facade; it must not fill the glazing plane.
            dims = new Dimensions(Math.max(dims.width(), body.width() + 2), Math.max(dims.depth(), body.depth() + 2), 1);
            position = new Vec3i(origin.x() - Math.max(0, (dims.width() - body.width()) / 2),
                    origin.y() + body.height() - 1,
                    origin.z() - Math.max(0, (dims.depth() - body.depth()) / 2));
        }
        return new Component(chimney ? "CHIMNEY" : detail.componentType(), detail.slotId(), position,
                dims, detail.features(), params);
    }

    private static Component alignFoundationToMass(Component foundation, Component mass) {
        Vec3i massOrigin = resolveMassOrigin(mass);
        Vec3i fp = foundation.relativePosition();
        Dimensions dims = foundation.dimensions();
        if (massOrigin == null || fp == null || dims == null) return foundation;
        Map<String, Object> params = new HashMap<>();
        if (foundation.params() != null) params.putAll(foundation.params());
        boolean rebased = Boolean.TRUE.equals(params.get("coordinate_frame_rebased"));
        boolean corner = ComponentFootprintUtil.isCornerAnchor(params) && !rebased;
        params.put("anchor_mode", "min_corner");
        // Fill support up to the host floor.
        int bottom = Math.min(fp.y(), massOrigin.y() - 1);
        if (Boolean.TRUE.equals(params.get("terrain_adaptive")) && !params.containsKey("foundation_margin")) {
            return new Component(foundation.componentType(), foundation.slotId(),
                    new Vec3i(massOrigin.x(), bottom, massOrigin.z()),
                    new Dimensions(mass.dimensions().width(), mass.dimensions().depth(), massOrigin.y()-bottom),
                    foundation.features(), params);
        }
        return new Component(foundation.componentType(), foundation.slotId(),
                new Vec3i(corner ? fp.x() : massOrigin.x() - (rebased ? Math.max(0, (dims.width() - mass.dimensions().width()) / 2) : 0),
                        bottom, corner ? fp.z() : massOrigin.z() - (rebased ? Math.max(0, (dims.depth() - mass.dimensions().depth()) / 2) : 0)),
                new Dimensions(dims.width(), dims.depth(), massOrigin.y() - bottom), foundation.features(), params);
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
        params.put("anchor_mode", "min_corner");

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

        return applyRoofGrammar(plan, new Component(
                llmRoof.componentType(),
                llmRoof.slotId(),
                template.relativePosition(),
                new Dimensions(baseW, baseD, roofHeight),
                mergeFeatureLists(template.features(), llmRoof.features()),
                params
        ));
    }

    private static Component alignFacadeToMass(
            Component llmFacade,
            Component mass,
            LlmPlan plan,
            GlobalConstraints.Facing facing
    ) {
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
        params.put("anchor_mode", "min_corner");
        params.put("wall_thickness", ComponentParamParsers.intParam(mass.params(), 1, "wall_thickness", "wallThickness"));

        Dimensions massDims = mass.dimensions();
        Dimensions llmDims = llmFacade.dimensions();
        Vec3i massOrigin = template.relativePosition();
        if (massDims == null || massOrigin == null) {
            return llmFacade;
        }

        List<String> features = mergeFeatureLists(template.features(), llmFacade.features());
        boolean wrap = hasWrapFeature(features);
        GlobalConstraints.Facing effectiveFacing = facing != null ? facing : GlobalConstraints.Facing.SOUTH;
        if (mass.params() != null && Boolean.FALSE.equals(mass.params().get("gable_windows"))) {
            params.put("excluded_window_axis", massDims.depth() >= massDims.width() ? "z" : "x");
        }

        // Window rows use the resolved host envelope, not stale model dimensions.
        int height = Math.max(2, massDims.height());
        int hostFloorHeight = ComponentParamParsers.intParam(mass.params(), "floor_height", "floorHeight");
        int hostFloorCount = ComponentParamParsers.intParam(mass.params(), "floor_count", "floorCount");
        if (hostFloorHeight > 0) params.put("floor_height", hostFloorHeight);
        if (hostFloorCount > 0) params.put("floor_count", hostFloorCount);

        int width;
        int depth;
        Vec3i origin = massOrigin;
        if (wrap) {
            width = massDims.width();
            depth = massDims.depth();
            if (llmDims != null && llmDims.depth() > 0 && llmDims.depth() != depth) {
                FormacraftMod.LOGGER.debug(
                        "ComponentPlanCompiler: clamped FACADE_WINDOWS depth from {} to mass depth {} (wrap/perimeter)",
                        llmDims.depth(), depth);
            }
        } else {
            OrientedFacade oriented = orientSingleFaceFacade(massOrigin, massDims, effectiveFacing);
            width = oriented.width();
            depth = oriented.depth();
            origin = oriented.origin();
            if (llmDims != null && llmDims.depth() > 1) {
                FormacraftMod.LOGGER.debug(
                        "ComponentPlanCompiler: ignored FACADE_WINDOWS depth {} (single-face facade uses depth=1)",
                        llmDims.depth());
            }
        }

        return new Component(
                "FACADE_WINDOWS",
                llmFacade.slotId(),
                origin,
                new Dimensions(width, depth, height),
                features,
                params
        );
    }

    private static void logComponentPatchCount(String normalizedType, Component c, int patchCount) {
        FormacraftMod.LOGGER.info(
                "ComponentPlanCompiler: {} -> {} patches{}",
                normalizedType,
                patchCount,
                componentPatchDiagSuffix(normalizedType, c));
    }

    private static String componentPatchDiagSuffix(String normalizedType, Component c) {
        Dimensions d = c != null ? c.dimensions() : null;
        String dims = formatDimensions(d);
        if (!"FACADE_WINDOWS".equals(normalizedType)) {
            return dims.isEmpty() ? "" : " " + dims;
        }
        Map<String, Object> params = c != null ? c.params() : null;
        String aspect = paramString(params, "window_aspect", "windowAspect");
        String rhythm = paramString(params, "rhythm_preset", "rhythmPreset");
        if (rhythm == null) {
            rhythm = paramString(params, "rhythm");
        }
        StringBuilder sb = new StringBuilder();
        if (!dims.isEmpty()) {
            sb.append(' ').append(dims);
        }
        if (aspect != null) {
            sb.append(" aspect=").append(aspect);
        }
        if (rhythm != null) {
            sb.append(" rhythm=").append(rhythm);
        }
        return sb.toString();
    }

    private static String formatDimensions(Dimensions d) {
        if (d == null) {
            return "";
        }
        return String.format(Locale.ROOT, "dims=%dx%dx%d", d.width(), d.depth(), d.height());
    }

    private static String paramString(Map<String, Object> params, String... keys) {
        if (params == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            if (key == null) {
                continue;
            }
            Object v = params.get(key);
            if (v == null) {
                continue;
            }
            String s = String.valueOf(v).trim();
            if (!s.isEmpty()) {
                return s;
            }
        }
        return null;
    }

    private record OrientedFacade(int width, int depth, Vec3i origin) {}

    private static OrientedFacade orientSingleFaceFacade(
            Vec3i massOrigin,
            Dimensions massDims,
            GlobalConstraints.Facing facing
    ) {
        int mw = Math.max(1, massDims.width());
        int md = Math.max(1, massDims.depth());
        return switch (facing) {
            case NORTH -> new OrientedFacade(
                    mw, 1,
                    new Vec3i(massOrigin.x(), massOrigin.y(), massOrigin.z() + md - 1));
            case EAST -> new OrientedFacade(1, md, massOrigin);
            case WEST -> new OrientedFacade(
                    1, md,
                    new Vec3i(massOrigin.x() + mw - 1, massOrigin.y(), massOrigin.z()));
            case SOUTH -> new OrientedFacade(mw, 1, massOrigin);
        };
    }

    private static boolean hasWrapFeature(List<String> features) {
        if (features == null || features.isEmpty()) {
            return false;
        }
        for (String feature : features) {
            if (feature == null) {
                continue;
            }
            String lower = feature.toLowerCase(Locale.ROOT);
            if ("wrap".equals(lower) || lower.contains("all_sides") || lower.contains("perimeter")
                    || lower.contains("around")) {
                return true;
            }
        }
        return false;
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

        int floorHeight = ComponentParamParsers.intParam(mass.params(), "floor_height", "floorHeight");
        int authoredDoorHeight = ComponentParamParsers.intParam(params, 2, "door_height", "doorHeight");
        params.put("door_height", Math.max(2, Math.min(authoredDoorHeight, floorHeight > 0 ? floorHeight - 1 : 3)));
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
        bindDerived(params, base, "facade");
        params.put("anchor_mode", "min_corner");
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
        if (facadeProfile != null) {
            String fp = facadeProfile.toLowerCase(Locale.ROOT);
            if (fp.contains("pilaster") || fp.contains("colonnade") || fp.contains("classical")) {
                params.putIfAbsent("window_order", "full");
            }
        }

        List<String> features = new ArrayList<>();
        if (base.features() != null) {
            features.addAll(base.features());
        }
        Dimensions dims = base.dimensions();
        if (dims != null && Boolean.FALSE.equals(params.get("gable_windows")))
            params.put("excluded_window_axis", dims.depth() >= dims.width() ? "z" : "x");
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
        int entranceDepth = Math.max(1, ComponentParamParsers.intParam(base.params(), 1, "wall_thickness", "wallThickness"));
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
        } else {
            var entranceSemantic = new SemanticComponent("MASS_MAIN", null, base,
                    plan.styleProfile(), plan.styleAttributes(), plan.genome());
            var layers = com.formacraft.common.generation.component.util.ResolvedFacadeLayers
                    .resolve(entranceSemantic, width, depth, height);
            for (int y = 0; y + 1 < layers.length; y++) {
                var lower = layers[y];
                var upper = layers[y + 1];
                if (upper.width < lower.width || upper.depth < lower.depth) {
                    entranceHeight = Math.min(entranceHeight, Math.max(2, y));
                    break;
                }
            }
        }
        if (paramCanopy > 0) {
            entranceDepth = Math.max(entranceDepth, Math.max(1, Math.min(depth / 2, paramCanopy + 1)));
        }

        BayGridRhythmPlanner.EntranceSnap baySnap = BayGridRhythmPlanner.snapEntrance(
                baseParams, width, depth, facing);
        int facadeBoxSpan = entranceWidth;
        if (baySnap != null) {
            facadeBoxSpan = Math.max(3, Math.min(baySnap.axisSpan(), width - baySnap.axisStart()));
        }
        int doorWidth = paramDoorW > 0
                ? Math.max(2, Math.min(facadeBoxSpan - 1, paramDoorW))
                : Math.max(2, Math.min(facadeBoxSpan - 1, facadeBoxSpan - 1));
        if (doorWidth % 2 == 0 && doorWidth > 2) {
            doorWidth--;
        }

        int relX = rp.x();
        int relZ = rp.z();
        int boxWidth;
        int boxDepth;
        int facadeAxisLength = facing == GlobalConstraints.Facing.EAST || facing == GlobalConstraints.Facing.WEST ? depth : width;
        int facadeAxisStart = baySnap != null ? baySnap.axisStart() : Math.max(0, (facadeAxisLength - facadeBoxSpan) / 2);
        switch (facing != null ? facing : GlobalConstraints.Facing.SOUTH) {
            case NORTH -> {
                boxWidth = facadeBoxSpan;
                boxDepth = entranceDepth;
                relX = rp.x() + facadeAxisStart;
                relZ = rp.z() + Math.max(0, depth - boxDepth);
            }
            case EAST -> {
                boxWidth = entranceDepth;
                boxDepth = facadeBoxSpan;
                relX = rp.x();
                relZ = rp.z() + facadeAxisStart;
            }
            case WEST -> {
                boxWidth = entranceDepth;
                boxDepth = facadeBoxSpan;
                relX = rp.x() + Math.max(0, width - boxWidth);
                relZ = rp.z() + facadeAxisStart;
            }
            default -> {
                boxWidth = facadeBoxSpan;
                boxDepth = entranceDepth;
                relX = rp.x() + facadeAxisStart;
                relZ = rp.z();
            }
        }

        Map<String, Object> params = new HashMap<>();
        params.put("door_width", doorWidth);
        bindDerived(params, base, "entrance");
        params.put("door_height", Math.max(2, Math.min(entranceHeight - 1, entranceHeight)));
        params.put("canopy_depth", paramCanopy > 0 ? paramCanopy : 1);
        if (baySnap != null) {
            params.put("entrance_bay_start", baySnap.axisStart());
            params.put("entrance_bay_span", baySnap.axisSpan());
            params.put("reserve_entrance_bay", true);
        }

        List<String> features = new ArrayList<>();
        features.add("entrance");
        features.add("overhang");
        if (!com.formacraft.common.style.ExplicitDesignPolicy.noComplexDecor(plan, base.params()) && hasOrnateEntranceHints(plan, base)) {
            features.add("decorative_lintel");
            features.add("wood_carvings");
        }
        if (base.features() != null) {
            features.addAll(base.features());
        }

        return new Component(
                "ENTRANCE",
                slotId,
                new Vec3i(relX, rp.y() + 1, relZ),
                new Dimensions(boxWidth, boxDepth, entranceHeight),
                features,
                params
        );
    }

    private static Component makeRoofComponent(LlmPlan plan, Component base, String slotId) {
        if (com.formacraft.common.style.ExplicitDesignPolicy.roofDisabled(base)) return null;
        Dimensions dims = base.dimensions();
        Vec3i rp = resolveMassOrigin(base);
        if (dims == null || rp == null) {
            return null;
        }
        int width = Math.max(2, dims.width());
        int depth = Math.max(2, dims.depth());
        // Only a fully rectangular primary body can safely rebase its footprint mask.
        // Other shapes need clipping in the original mask coordinates rather than a smaller mask.
        if ("MASS_MAIN".equals(normalizeType(base.componentType()))) {
            var semantic = new SemanticComponent(base.componentType(), null, base,
                    plan.styleProfile(), plan.styleAttributes(), plan.genome());
            var mask = com.formacraft.common.generation.component.util.ComponentFootprintMask.from(semantic,
                    base.params(), dims.width(), dims.depth());
            boolean rectangle = true;
            for (int x = 0; x < dims.width() && rectangle; x++) for (int z = 0; z < dims.depth(); z++)
                if (!mask.contains(x,z)) { rectangle = false; break; }
            if (rectangle) {
                var layers = com.formacraft.common.generation.component.util.ResolvedFacadeLayers.resolve(semantic,
                        dims.width(), dims.depth(), dims.height());
                var top = layers[layers.length-1];
                width = Math.max(2, Math.min(dims.width(), top.width));
                depth = Math.max(2, Math.min(dims.depth(), top.depth));
                rp = new Vec3i(rp.x()+top.xOffset,rp.y(),rp.z()+top.zOffset);
            }
        }
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
        if (params.get("component_id") != null) params.putIfAbsent("host_id", params.get("component_id"));
        bindDerived(params, base, "roof");
        // The inferred height lives in Dimensions. Do not inject a duplicate
        // default param that overrides the height of an explicit ROOF component.
        params.put("anchor_mode", "min_corner");
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

    private record PartRoofFrame(Vec3i origin, int width, int depth) {}

    private static PartRoofFrame resolvePartRoofFrame(LlmPlan plan, Component mass,
            com.formacraft.common.generation.component.util.ResolvedMassPart part) {
        var params = new HashMap<String,Object>();
        if (mass.params() != null) params.putAll(mass.params());
        params.putAll(part.sourceParams());
        var semantic = new SemanticComponent(mass.componentType(),null,mass,
                plan.styleProfile(),plan.styleAttributes(),plan.genome());
        var dims = part.dimensions();
        var mask = com.formacraft.common.generation.component.util.ComponentFootprintMask.from(semantic,params,dims.width(),dims.depth());
        for (int x = 0; x < dims.width(); x++) for (int z = 0; z < dims.depth(); z++)
            if (!mask.contains(x,z)) return null;
        // Nested masses use the root component's feature/floor/setback policy in emitMass.
        var layers = com.formacraft.common.generation.component.util.ResolvedFacadeLayers.resolve(semantic,dims.width(),dims.depth(),dims.height());
        var top = layers[layers.length-1];
        return new PartRoofFrame(new Vec3i(part.origin().x()+top.xOffset,part.bounds().maxY()-1,part.origin().z()+top.zOffset),
                Math.min(dims.width(),top.width),Math.min(dims.depth(),top.depth));
    }

    private static void inferNestedFlatRoofs(LlmPlan plan, Component mass, List<Component> components,
                                             List<Component> inferred, String slotId) {
        if (!"MASS_MAIN".equals(normalizeType(mass.componentType()))) return;
        var parts = com.formacraft.common.generation.component.util.ResolvedMassPart.resolve(mass);
        if (parts.size() < 2) return;
        Component roof = null;
        Object rootId = mass.params() == null ? null : mass.params().get("component_id");
        for (Component candidate : java.util.stream.Stream.concat(components.stream(), inferred.stream()).toList()) {
            if (!isRoofType(normalizeType(candidate.componentType())) || !slotKey(candidate).equals(slotKey(mass))) continue;
            Object host = candidate.params() == null ? null : candidate.params().get("host_id");
            if (rootId != null && rootId.equals(host)) { roof = candidate; break; }
            if (host == null && roof == null) roof = candidate;
        }
        if (roof == null || !"flat".equalsIgnoreCase(getParamString(roof.params(), "roof_type", "roofType"))) return;
        for (int i = 1; i < parts.size(); i++) {
            var part = parts.get(i);
            if (parts.getFirst().bounds().contains(part.bounds())
                    && part.bounds().maxY() <= parts.getFirst().bounds().maxY()) continue;
            if (part.dimensions().width() < 2 || part.dimensions().depth() < 2) continue;
            var frame = resolvePartRoofFrame(plan,mass,part);
            if (frame == null) continue;
            String requestedRoof = getParamString(part.sourceParams(), "roof_type", "roofType");
            if (requestedRoof != null && !"flat".equalsIgnoreCase(requestedRoof)) continue;
            var params = new HashMap<String, Object>();
            if (mass.params() != null) params.putAll(mass.params());
            params.putAll(part.sourceParams());
            params.remove("masses"); params.remove("offset"); params.remove("dimensions");
            String shape = getParamString(params, "shape", "footprint_shape", "footprintShape");
            String pattern = getParamString(params, "plan_type", "planType");
            if (shape != null && !List.of("rectangle", "rect", "box").contains(shape.toLowerCase(Locale.ROOT))) continue;
            if (pattern != null && !List.of("rectangle", "rect", "box", "none").contains(pattern.toLowerCase(Locale.ROOT))) continue;
            params.put("anchor_mode", "min_corner");
            params.put("component_id", part.partId());
            Component body = new Component("MASS_SECONDARY", slotId, part.origin(), part.dimensions(), List.of(), params);
            Component derived = makeRoofComponent(plan, body, slotId);
            var roofParams = new HashMap<String, Object>();
            if (derived != null) {
                roofParams.putAll(derived.params());
            }
            if (roof.params() != null) roofParams.putAll(roof.params());
            roofParams.remove("masses");
            roofParams.put("anchor_mode", "min_corner");
            copyFootprintParams(params, roofParams);
            roofParams.put("roof_type", "flat");
            roofParams.put("component_id", part.partId() + "#roof");
            roofParams.put("host_part_id", part.partId());
            roofParams.put("resolved_mass_part_roof", true);
            if (derived != null) {
                inferred.add(new Component("ROOF", slotId, frame.origin(),
                        new Dimensions(frame.width(),frame.depth(),derived.dimensions().height()), List.of("roof"), roofParams));
            }
        }
    }

    private static Component applyRoofGrammar(LlmPlan plan, Component roof) {
        if (roof == null) {
            return null;
        }
        return RoofGrammarResolver.apply(plan, roof);
    }

    private static Component makeCrownComponent(LlmPlan plan, Component base, Component roof, String slotId) {
        Dimensions dims = base.dimensions();
        Vec3i rp = resolveMassOrigin(base);
        if (dims == null || rp == null) {
            return null;
        }
        int width = Math.max(2, dims.width());
        int depth = Math.max(2, dims.depth());
        int span = Math.min(width, depth);
        if (span < 7) {
            return null;
        }

        int roofHeight = Math.max(2, Math.min(8, Math.max(2, span / 3)));
        if (roof != null && roof.dimensions() != null && roof.dimensions().height() > 0) {
            roofHeight = roof.dimensions().height();
        }

        int radius = Math.max(2, Math.min(6, span / 4));
        int crownWidth = radius * 2 + 1;
        int crownHeight = Math.max(4, Math.min(10, radius * 2));

        Map<String, Object> params = new HashMap<>();
        if (base.params() != null) {
            params.putAll(base.params());
        }
        params.put("crown_radius", radius);
        params.put("crown_height", crownHeight);
        params.putIfAbsent("revolve_segments", 32);
        params.put("crown_template", ComponentCrownDecorator.resolveTemplate(plan, params, null));

        int relX = rp.x() + (width - crownWidth) / 2;
        int relZ = rp.z() + (depth - crownWidth) / 2;
        int relY = rp.y() + Math.max(1, dims.height() - 1) + roofHeight;

        List<String> features = new ArrayList<>();
        features.add("crown");
        features.add("revolve_surface");
        if (base.features() != null) {
            for (String feature : base.features()) {
                if (feature != null && !features.contains(feature)) {
                    features.add(feature);
                }
            }
        }

        return new Component(
                "CROWN",
                slotId,
                new Vec3i(relX, relY, relZ),
                new Dimensions(crownWidth, crownWidth, crownHeight),
                features,
                params
        );
    }

    private static Component alignCrownToMass(Component llmCrown, Component mass, LlmPlan plan, List<Component> all) {
        Component roof = findRoofForSlot(all, List.of(), slotKey(mass));
        if (roof == null) {
            roof = makeRoofComponent(plan, mass, mass.slotId());
        }
        Component template = makeCrownComponent(plan, mass, roof, mass.slotId());
        if (template == null) {
            return CrownGrammarResolver.apply(plan, llmCrown);
        }

        Map<String, Object> params = new HashMap<>();
        if (template.params() != null) {
            params.putAll(template.params());
        }
        if (llmCrown.params() != null) {
            params.putAll(llmCrown.params());
        }

        Dimensions templateDims = template.dimensions();
        Dimensions llmDims = llmCrown.dimensions();
        int crownHeight = templateDims != null ? templateDims.height() : 6;
        int crownWidth = templateDims != null ? templateDims.width() : 5;
        if (llmDims != null) {
            if (llmDims.height() > 0) {
                crownHeight = llmDims.height();
            }
            if (llmDims.width() > 0) {
                crownWidth = llmDims.width();
            }
        }

        Component aligned = new Component(
                "CROWN",
                llmCrown.slotId(),
                template.relativePosition(),
                new Dimensions(crownWidth, crownWidth, crownHeight),
                mergeFeatureLists(template.features(), llmCrown.features()),
                params
        );
        return CrownGrammarResolver.apply(plan, aligned);
    }

    private static Component findRoofForSlot(List<Component> primary, List<Component> inferred, String slotKey) {
        Component found = findRoofInList(primary, slotKey);
        if (found != null) {
            return found;
        }
        return findRoofInList(inferred, slotKey);
    }

    private static void bindDerived(Map<String, Object> params, Component mass, String role) {
        if (mass.params() != null && Boolean.TRUE.equals(mass.params().get("no_complex_decor"))) params.put("no_complex_decor", true);
        String identity = getParamString(mass.params(), "component_id");
        if (identity != null) {
            params.put("host_id", identity);
            params.put("component_id", identity + "#" + role);
        }
    }

    private static boolean hasSatelliteForMass(Component mass, List<Component> primary, List<Component> inferred, String wantedType) {
        var roofs = new ArrayList<Component>(primary);
        roofs.addAll(inferred);
        Object identity = mass.params() == null ? null : mass.params().get("component_id");
        for (var roof : roofs) {
            if (roof == null || !("ROOF".equals(wantedType) ? isRoofType(normalizeType(roof.componentType()))
                    : wantedType.equals(normalizeType(roof.componentType()))) || !slotKey(roof).equals(slotKey(mass))) continue;
            if (roof.params() != null && roof.params().get("host_part_id") != null) continue;
            Object host = roof.params() == null ? null : roof.params().get("host_id");
            if (host != null) {
                if (host.equals(identity)) return true;
                continue;
            }
            // Legacy unbound roofs use the same nearest-body compatibility rule as alignment.
            Component nearest = null;
            double best = Double.POSITIVE_INFINITY;
            for (var candidate : primary) {
                if (candidate == null || !isMassType(normalizeType(candidate.componentType()))
                        || !slotKey(candidate).equals(slotKey(mass)) || candidate.dimensions() == null) continue;
                Vec3i origin = resolveMassOrigin(candidate);
                if (origin == null || roof.relativePosition() == null) continue;
                double x = roof.relativePosition().x(), z = roof.relativePosition().z();
                if (ComponentFootprintUtil.isCornerAnchor(roof.params()) && roof.dimensions() != null) {
                    x += (roof.dimensions().width() - 1) / 2.0;
                    z += (roof.dimensions().depth() - 1) / 2.0;
                }
                double dx = x - origin.x() - (candidate.dimensions().width() - 1) / 2.0;
                double dz = z - origin.z() - (candidate.dimensions().depth() - 1) / 2.0;
                double distance = dx * dx + dz * dz;
                if (distance < best) { best = distance; nearest = candidate; }
            }
            if (nearest != null && (identity != null && nearest.params() != null
                    ? identity.equals(nearest.params().get("component_id"))
                    : java.util.Objects.equals(mass.relativePosition(), nearest.relativePosition()))) return true;
        }
        return false;
    }

    private static Component findRoofInList(List<Component> components, String slotKey) {
        if (components == null) {
            return null;
        }
        for (Component c : components) {
            if (c == null) {
                continue;
            }
            if (!slotKey.equals(slotKey(c))) {
                continue;
            }
            if (isRoofType(normalizeType(c.componentType()))) {
                return c;
            }
        }
        return null;
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
        params.put("compiler_suppressed_roof", true);
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

    private static Component reconcileDelegatedRoof(LlmPlan plan, Component body, List<Component> components) {
        if (!"MASS_MAIN".equals(body.componentType()) || !com.formacraft.common.style.ExplicitDesignPolicy.roofDisabled(body)
                || plan.proportionHints() == null
                || !(plan.proportionHints().get("building_contract") instanceof Map<?, ?> contract)
                || !(contract.get("requirements") instanceof List<?> requirements)) return body;
        Object id = body.params().get("component_id");
        boolean requested = false;
        for (Object raw : requirements) {
            if (!(raw instanceof Map<?, ?> req)) continue;
            Object scope = req.get("scope");
            boolean applies = "all_main_masses".equals(scope) || "plan".equals(scope)
                    || scope != null && scope.equals(body.params().get("requirement_scope"))
                    || req.get("target_components") instanceof List<?> targets && targets.contains(id);
            if (!applies) continue;
            if ("roof_type".equals(req.get("property")) && "none".equals(req.get("value"))) return body;
            if (Set.of("roof_block", "roof_type").contains(req.get("property"))
                    && req.get("value") != null && !"none".equals(req.get("value"))) requested = true;
        }
        if (!requested) return body;
        for (Component roof : components) {
            if (roof == null || roof.params() == null || !"ROOF".equals(roof.componentType())
                    || id == null || !id.equals(roof.params().get("host_id"))) continue;
            String type = getParamString(roof.params(), "roof_type", "roofType");
            if (type == null || "none".equalsIgnoreCase(type)) continue;
            var params = new HashMap<String, Object>(body.params());
            params.put("roof_type", type);
            return new Component(body.componentType(), body.slotId(), body.relativePosition(), body.dimensions(), body.features(), params);
        }
        return body;
    }

    private static Component normalizeComponent(Component component) {
        if (component == null) {
            return null;
        }
        // allowUnknown：component_request/group_request，或显式地标/模块路由提示，
        // 都应保留原始 type（交由 UnifiedGeneratorRouter 的扩展/整栋回退处理）。
        boolean allowUnknown = hasComponentRequest(component.features())
                || com.formacraft.server.generation.component.impl.StraightStairComponentGenerator.accepts(component)
                || hasStructureRoutingHint(component)
                || "MODULE".equals(normalizeType(component.componentType()))
                || "STRUCTURE".equals(normalizeType(component.componentType()))
                || "ASSEMBLY".equals(normalizeType(component.componentType()));
        String type = normalizeComponentType(component.componentType(), allowUnknown);
        if (type.isBlank()) {
            return null;
        }
        // Phase 10：合理性修复 —— "太矮"的主体/塔拔高到合理最小层高。
        boolean plate = component.params() != null && "plate".equals(component.params().get("extrude_mode"));
        Dimensions dims = plate && component.dimensions() != null
            ? new Dimensions(component.dimensions().width(), component.dimensions().depth(), 1)
            : clampMinHeight(type, component.dimensions());
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
        FormacraftMod.LOGGER.warn("ComponentPlanCompiler: skipping unsupported component type {}", type);
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
                        || lower.startsWith("typology:")
                        || lower.startsWith("structure_generator:") || lower.startsWith("skeleton:")) {
                    return true;
                }
            }
        }
        Map<String, Object> params = component.params();
        if (params != null) {
            return params.containsKey("landmark") || params.containsKey("module_id")
                    || params.containsKey("typology_id") || params.containsKey("structural_typology")
                    || params.containsKey("template") || params.containsKey("blueprint")
                    || params.containsKey("assembly") || params.containsKey("skeleton")
                    || Boolean.TRUE.equals(params.get("useStructureGenerator"));
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
        if (type.contains("CROWN") || type.contains("CUPOLA") || type.contains("DOME") || type.contains("SPIRE")) {
            return "CROWN";
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
        if (type.contains("WINDOW")) {
            return "FACADE_WINDOWS";
        }
        if (type.contains("RAMPART") || type.contains("PALISADE") || type.contains("BARRIER")
                || type.contains("PARAPET") || type.contains("SCREEN")) {
            return "WALL";
        }
        if (type.contains("WALL") || type.contains("BUTTRESS")) {
            return "WALL";
        }
        if (type.contains("TERRACE")) {
            return "TERRACE";
        }
        if (type.contains("PALACE") || type.contains("TIER")) {
            return "MASS_MAIN";
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

    private static boolean isCrownType(String type) {
        if (type == null) {
            return false;
        }
        return "CROWN".equals(type) || "CUPOLA".equals(type) || "DOME".equals(type);
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
        String fromHints = roofTypeHintFromProportionHints(plan);
        if (fromHints != null) {
            return fromHints;
        }
        return "gable";
    }

    private static String roofTypeHintFromProportionHints(LlmPlan plan) {
        if (plan == null || plan.proportionHints() == null) {
            return null;
        }
        Map<String, Object> hints = plan.proportionHints();
        Object specialty = hints.get("roof_specialty");
        if (specialty == null) {
            specialty = hints.get("roofSpecialty");
        }
        if (specialty != null) {
            String s = String.valueOf(specialty).toLowerCase(Locale.ROOT);
            if (s.contains("mansard")) {
                return "mansard";
            }
        }
        String typology = String.valueOf(hints.getOrDefault("typology", "")).toLowerCase(Locale.ROOT);
        if (typology.contains("baroque") || typology.contains("townhouse") || typology.contains("paris")) {
            return "mansard";
        }
        return null;
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
        if (needsAssemblyFacadeProfile(facadeProfile)) {
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
        return ComponentFootprintUtil.resolveMinCornerOrigin(base);
    }

    private static String resolveAssemblyStyleId(LlmPlan plan, SemanticComponent semantic) {
        String profile = plan != null ? plan.styleProfile() : null;
        StringBuilder merged = new StringBuilder(profile != null ? profile : "");
        if (plan != null && plan.distinguishingFeatures() != null) {
            for (String feature : plan.distinguishingFeatures()) {
                if (feature != null) {
                    merged.append(" ").append(feature);
                }
            }
        }
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
