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
        plan = com.formacraft.common.palette.dynamic.MaterialFallbackPolicy.normalize(plan);
        List<Component> authoredComponents = plan.components() == null ? List.of() : plan.components();
        var authoredRoofIssue = RoofDesignValidator.check(plan, authoredComponents);
        if (authoredRoofIssue.isPresent()) {
            AssemblyCompileDiagnostics.set(authoredRoofIssue.get());
            return List.of();
        }
        var authoredFacadeIssue = FacadeDesignValidator.check(plan, authoredComponents);
        if (authoredFacadeIssue.isPresent()) {
            AssemblyCompileDiagnostics.set(authoredFacadeIssue.get());
            return List.of();
        }
        var authoredMassIssue = MassDesignValidator.check(plan, authoredComponents);
        if (authoredMassIssue.isPresent()) {
            AssemblyCompileDiagnostics.set(authoredMassIssue.get());
            return List.of();
        }
        var authoredPlateIssue = HostedPlateDesignValidator.check(plan, authoredComponents);
        if (authoredPlateIssue.isPresent()) {
            AssemblyCompileDiagnostics.set(authoredPlateIssue.get());
            return List.of();
        }
        var authoredConflict = ExplicitDesignConflictValidator.check(plan, authoredComponents);
        if (authoredConflict.isPresent()) {
            AssemblyCompileDiagnostics.set(authoredConflict.get());
            return List.of();
        }
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
        if (AssemblyCompileDiagnostics.hasGap()) {
            return List.of();
        }
        List<Component> components = prepared.components();
        Set<String> assemblyFacadeSlots = prepared.assemblyFacadeSlots();
        var invalidAuthority = com.formacraft.common.generation.routing.GenerationAuthorityValidator.check(plan);
        if (invalidAuthority.isPresent()) {
            AssemblyCompileDiagnostics.set(invalidAuthority.get());
            return List.of();
        }
        var roofDesignConflict = RoofDesignValidator.check(plan, components);
        if (roofDesignConflict.isPresent()) {
            AssemblyCompileDiagnostics.set(roofDesignConflict.get());
            return List.of();
        }
        var facadeDesignConflict = FacadeDesignValidator.check(plan, components);
        if (facadeDesignConflict.isPresent()) {
            AssemblyCompileDiagnostics.set(facadeDesignConflict.get());
            return List.of();
        }
        var massDesignConflict = MassDesignValidator.check(plan, components);
        if (massDesignConflict.isPresent()) {
            AssemblyCompileDiagnostics.set(massDesignConflict.get());
            return List.of();
        }
        var plateDesignConflict = HostedPlateDesignValidator.check(plan, components);
        if (plateDesignConflict.isPresent()) {
            AssemblyCompileDiagnostics.set(plateDesignConflict.get());
            return List.of();
        }
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
        var openingExpectations = new java.util.LinkedHashMap<BlockPos, OpeningMaterialAudit.Expectation>();
        Set<BlockPos> protectedMaterials = new HashSet<>();
        Set<BlockPos> decorationRestrictions = new HashSet<>();
        var circulation = new ArrayList<AssemblyCirculationConstraints.Flight>();
        var flatRoofs = new ArrayList<FlatRoofCoverageValidator.Roof>();
        boolean typologyExclusivePlan = hasTypologyStructureComponent(components);
        UnifiedGeneratorRouter.setTypologyExclusivePlan(typologyExclusivePlan);
        UnifiedGeneratorRouter.setPlanGenerationAuthority(
                com.formacraft.common.generation.routing.PlanMetaAccess.planGenerationAuthority(plan));
        UnifiedGeneratorRouter.setPlanMeta(plan.planMeta());
        TypologyPatchBridge.setPlanWorldAnchor(globalAnchor);
        com.formacraft.common.generation.component.util.BuildingGeometryContext.begin();
        PatchOccupancyIndex patchOccupancy;
        try {
            for (Component mass : components) {
                if (com.formacraft.common.generation.component.util.MassComponentKinds.isHostMass(mass)) {
                    com.formacraft.common.generation.component.util.BuildingGeometryContext.putMass(mass);
                }
            }
            var hostGeomGap = HostedPlateDesignValidator.checkHostGeometry(components);
            if (hostGeomGap.isPresent()) {
                AssemblyCompileDiagnostics.set(hostGeomGap.get());
                return List.of();
            }
            patchOccupancy = compileComponents(plan, world, globalAnchor, allowAssemblyFacade, components, assemblyFacadeSlots,
                    slotMap, result, buildingVolumes, circulation, generatedSurfaces, flatRoofs, protectedMaterials, decorationRestrictions, openingExpectations);
        } finally {
            com.formacraft.common.generation.component.util.BuildingGeometryContext.end();
            UnifiedGeneratorRouter.clearTypologyExclusivePlan();
            UnifiedGeneratorRouter.clearPlanGenerationAuthority();
            UnifiedGeneratorRouter.clearPlanMeta();
            TypologyPatchBridge.clearPlanWorldAnchor();
        }
        if (AssemblyCompileDiagnostics.hasGap()) return List.of();
        try {
            com.formacraft.common.generation.component.util.CirculationAnalysisContext.begin(result);
            if (!validateBridgeWalkability(components, result, slotMap)) {
                return List.of();
            }
            var circulationGap = BuildingCirculationValidator.validate(plan, components, result, slotMap);
            if (circulationGap.isPresent()) {
                AssemblyCompileDiagnostics.set(circulationGap.get());
                return List.of();
            }
        } finally {
            com.formacraft.common.generation.component.util.CirculationAnalysisContext.end();
        }

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

            var postOutcome = pipeline.processWithOutcome(result, context);
            if (postOutcome.hardFailure()) {
                AssemblyCompileDiagnostics.set(new CapabilityGap(
                        "E_POSTPROCESS_FAILED",
                        "关键后处理失败：processor=" + postOutcome.failedProcessor()
                                + (postOutcome.errorMessage() == null ? "" : "; " + postOutcome.errorMessage()),
                        "components[]",
                        List.of("检查窗套/线脚/地形适应配置", "修复后处理异常后重试")
                ));
                return List.of();
            }
            result = postOutcome.patches();
            if (patchOccupancy != null) {
                for (var step : postOutcome.processorDeltas()) {
                    if (step == null || step.delta() == null || step.delta().isEmpty()) continue;
                    var conflict = patchOccupancy.validatePostProcess(
                            step.processorId(), step.policy(), step.delta(), step.scope());
                    if (conflict.isPresent()) {
                        var c = conflict.get();
                        AssemblyCompileDiagnostics.set(new CapabilityGap(
                                "E_POSTPROCESS_PATCH_CONFLICT",
                                "后处理占用冲突：processor=" + step.processorId()
                                        + "; policy=" + step.policy()
                                        + "; reason=" + c.reason()
                                        + "; pos=" + c.position()
                                        + "; previous=" + c.previousWriter() + "/" + c.previousBlock()
                                        + "; writer=" + c.writer() + "/" + c.newBlock(),
                                "components[]",
                                List.of("检查后处理器 WritePolicy / WriteScope",
                                        "避免 PLACE_ONLY 覆盖/清洞已有格",
                                        "避免越权修改 volume/seed 外方块",
                                        "修复冲突后重试")
                        ));
                        return List.of();
                    }
                    patchOccupancy.commitPostProcess(step.processorId(), step.policy(), step.delta());
                }
            }

            FormacraftMod.LOGGER.info("ComponentPlanCompiler: post-processed to {} patches", result.size());
        }

        if (!openingExpectations.isEmpty()) {
            var openingAudit = OpeningMaterialAudit.inspect(openingExpectations, result);
            FormacraftMod.LOGGER.info("[OpeningMaterialAudit] stage=postprocess result={}", openingAudit);
            var openingGap = OpeningMaterialAudit.toCapabilityGap(openingAudit);
            if (openingGap.isPresent()) {
                AssemblyCompileDiagnostics.set(openingGap.get());
                return List.of();
            }
        }

        if (patchOccupancy != null) {
            var postPatchConflict = PatchConflictValidator.inspectAuthorized(result, patchOccupancy);
            if (postPatchConflict.isPresent()) {
                PatchConflictValidator.toCapabilityGap(postPatchConflict.get()).ifPresent(AssemblyCompileDiagnostics::set);
                return List.of();
            }
        }

        for (var roof : components) {
            if (!"ROOF".equals(normalizeType(roof.componentType()))) continue;
            var slot = slotMap.get(roof.slotId());
            if (slot == null) slot = defaultSlot(plan);
            var anchor = slot.anchor();
            var offset = anchor == null ? BlockPos.ORIGIN : new BlockPos(anchor.x(), anchor.y(), anchor.z());
            var semantic = new SemanticComponent("ROOF", slot, roof, plan.styleProfile());
            // Adjacent taller towers can share roof materials and overlap this
            // roof's section. Audit its own committed cells, not every roof.
            final var auditOccupancy = patchOccupancy;
            String roofIdentity = getParamString(roof.params(), "component_id");
            var roofPatches = result.stream().filter(p -> {
                var record = auditOccupancy == null ? null : auditOccupancy.placeAt(new BlockPos(p.dx(), p.dy(), p.dz()));
                return record == null || roofIdentity == null
                        || roofIdentity.equals(getParamString(record.owner() == null ? null : record.owner().params(), "component_id"))
                        || record.writerType().startsWith("POSTPROCESS");
            }).toList();
            var occludedColumns = new HashSet<BlockPos>();
            String roofHost = getParamString(roof.params(), "host_id");
            for (var other : roofOccluders(roof, components, slotMap)) {
                var b = other.bounds();
                int baseY = roof.relativePosition().y();
                if (b == null || b.maxY() <= baseY || b.minY() > baseY + roof.dimensions().height()) continue;
                for (int x=b.minX();x<b.maxX();x++) for (int z=b.minZ();z<b.maxZ();z++) {
                    if (other.footprint().contains(x-b.minX(),z-b.minZ()))
                        occludedColumns.add(new BlockPos(x+offset.getX(),0,z+offset.getZ()));
                }
            }
            for (var p : result) {
                if (!GeneratedSurfaceCapture.occupied(p) || p.dy() < roof.relativePosition().y()+offset.getY()) continue;
                PatchOccupancyIndex.PlaceRecord record = null;
                if (auditOccupancy != null) {
                    record = auditOccupancy.placeAt(new BlockPos(p.dx(), p.dy(), p.dz()));
                }
                if (record == null || record.owner() == null) continue;
                var owner = record.owner();
                if (!(com.formacraft.common.generation.component.util.MassComponentKinds.isCirculationMass(owner)
                        || "ROOF".equals(record.writerType()))) continue;
                if (java.util.Objects.equals(roofHost, getParamString(owner.params(), "component_id"))
                        || java.util.Objects.equals(roofHost, getParamString(owner.params(), "host_id"))) continue;
                occludedColumns.add(new BlockPos(p.dx(),0,p.dz()));
            }
            var shapeAudit = RoofShapeAudit.inspect(semantic, roofPatches, offset, occludedColumns);
            var continuityGap = RoofSurfaceContinuityAudit.inspect(semantic, roofPatches, offset, occludedColumns);
            if (continuityGap.isPresent()) {
                AssemblyCompileDiagnostics.set(continuityGap.get());
                return List.of();
            }
            shapeAudit.ifPresent(audit ->
                    FormacraftMod.LOGGER.info("[RoofShapeAudit] stage=postprocess result={}", audit));
            if (shapeAudit.isPresent()) {
                if ("mismatch".equals(shapeAudit.get().status())
                        && !Set.of("missing_slope_column", "no_ridge_rise", "non_monotonic_slope")
                        .contains(shapeAudit.get().reason())) {
                    FormacraftMod.LOGGER.warn("[RoofShapeAudit] visual mismatch (non-blocking): {}", shapeAudit.get());
                }
                var shapeGap = RoofShapeAudit.toCapabilityGap(shapeAudit.get());
                if (shapeGap.isPresent()) {
                    AssemblyCompileDiagnostics.set(shapeGap.get());
                    return List.of();
                }
            }
            var seamAudit = RoofSeamAudit.inspect(semantic, components, slotMap, defaultSlot(plan), result);
            seamAudit.ifPresent(audit ->
                    FormacraftMod.LOGGER.info("[RoofSeamAudit] stage=postprocess result={}", audit));
            if (seamAudit.isPresent()) {
                var seamGap = RoofSeamAudit.toCapabilityGap(seamAudit.get());
                if (seamGap.isPresent()) {
                    AssemblyCompileDiagnostics.set(seamGap.get());
                    return List.of();
                }
            }
            maybeWarnIrregularGableSeal(roof, semantic);
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
        for (var body : components) {
            if (!"MASS_MAIN".equals(normalizeType(body.componentType()))) continue;
            var slot = slotMap.get(body.slotId());
            if (slot == null) slot = defaultSlot(plan);
            var anchor = slot.anchor();
            var offset = anchor == null ? BlockPos.ORIGIN : new BlockPos(anchor.x(), anchor.y(), anchor.z());
            FloorAccessAudit.inspect(body, offset, result, circulation).ifPresent(audit ->
                    FormacraftMod.LOGGER.info("[FloorAccessAudit] stage=postprocess result={}", audit));
        }
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

    private static PatchOccupancyIndex compileComponents(
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
            Set<BlockPos> decorationRestrictions,
            Map<BlockPos, OpeningMaterialAudit.Expectation> openingExpectations
    ) {
        // Shells and floor slabs must be emitted before stair clearance carves.
        var ordered = new ArrayList<>(components);
        ordered.sort(java.util.Comparator.comparingInt(ComponentPlanCompiler::compilationPriority));
        var surfaceLedger = new SurfaceOwnershipLedger();
        var patchOccupancy = new PatchOccupancyIndex();
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
                    return patchOccupancy;
                }
                Vec3i roofSlotAnchor = slot.anchor();
                FlatRoofCoverageValidator.resolve(semantic, roofSlotAnchor == null ? BlockPos.ORIGIN
                        : new BlockPos(roofSlotAnchor.x(), roofSlotAnchor.y(), roofSlotAnchor.z())).ifPresent(flatRoofs::add);
                var surfaceCells = new ArrayList<GeneratedSurfaceCapture.Cell>();
                try (var surfaceCapture = GeneratedSurfaceCapture.captureTo(surfaceCells::add)) {
                    var routed = GenerationHub.generateComponentResult(semantic, world);
                    if (routed != null && !routed.commitAllowed() && routed.isHardFail()
                            && !isSoftGenerationFailure(c)) {
                        String detail = routed.diagnostics().isEmpty()
                                ? routed.status().name()
                                : routed.diagnostics().getFirst().code() + ": " + routed.diagnostics().getFirst().message();
                        String diagCode = routed.diagnostics().isEmpty()
                                ? ""
                                : routed.diagnostics().getFirst().code();
                        boolean envelopeConflict = "component_request_envelope_conflict".equals(diagCode);
                        boolean authorityConflict = "generation_authority_conflict".equals(diagCode)
                                || "skeleton_empty".equals(diagCode)
                                || "assembly_empty".equals(diagCode);
                        boolean capturedLibraryMiss = "captured_library_miss".equals(diagCode);
                        boolean roofTypeUnsupported = "generator_exception".equals(diagCode)
                                && detail != null && detail.contains("roof_type_unsupported");
                        String gapCode = envelopeConflict
                                ? "E_COMPONENT_REQUEST_CONFLICT"
                                : authorityConflict
                                ? "E_GENERATION_AUTHORITY_CONFLICT"
                                : roofTypeUnsupported
                                ? "E_ROOF_TYPE_UNSUPPORTED"
                                : "E_COMPONENT_LIBRARY_FAILED";
                        String gapMessage = envelopeConflict
                                ? "显式 component_request 与包络保护冲突：type=" + c.componentType()
                                    + " id=" + paramIdentity(c)
                                    + " detail=" + detail
                                : authorityConflict
                                ? "生成权威冲突：type=" + c.componentType()
                                    + " id=" + paramIdentity(c)
                                    + " detail=" + detail
                                : capturedLibraryMiss
                                ? "捕获构件未产生几何：type=" + c.componentType()
                                    + " id=" + paramIdentity(c)
                                    + " detail=" + detail
                                : "捕获/库构件硬失败：type=" + c.componentType()
                                    + " id=" + paramIdentity(c)
                                    + " status=" + routed.status()
                                    + " detail=" + detail;
                        AssemblyCompileDiagnostics.set(new CapabilityGap(
                                gapCode,
                                gapMessage,
                                "components[]",
                                envelopeConflict
                                        ? List.of("移除 component_request 或设置 allow_envelope_attach=true",
                                                "禁止静默跳过显式库请求")
                                        : authorityConflict
                                        ? List.of("Align generation_authority with component hints and payload",
                                                "Do not silently fall back to another executor")
                                        : List.of("修复构件 ID、适配规则或清洞约束", "禁止回退为无关默认几何")));
                        return patchOccupancy;
                    }
                    if (routed != null && routed.degraded()) {
                        for (var d : routed.diagnostics()) {
                            AssemblyCompileDiagnostics.addSoftWarning(
                                    d.code().isBlank() ? "COMPONENT_PARTIAL_SUCCESS" : d.code(),
                                    d.message().isBlank()
                                            ? "构件部分成功：安装件降级，仅保留宿主几何"
                                            : d.message(),
                                    c.componentType(),
                                    paramIdentity(c)
                            );
                        }
                    }
                    patches = routed != null && routed.commitAllowed() ? routed.patches() : List.of();
                }
                var missingSurfaces = GeneratedSurfaceCapture.missing(surfaceCells, patches);
                if (!missingSurfaces.isEmpty()) {
                    var first = missingSurfaces.getFirst();
                    AssemblyCompileDiagnostics.set(new CapabilityGap("E_SURFACE_GENERATION_INCOMPLETE",
                            "生成器未提供声明的建筑表面：" + normalizedType + " " + first.role()
                                    + " at component " + first.position().toShortString() + "; missing=" + missingSurfaces.size(),
                            "components[]", List.of("Fix surface material or generator emission; keep authored openings explicitly marked.")));
                    return patchOccupancy;
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
                    var shiftedFlights = componentFlights.stream()
                            .map(flight -> AssemblyCirculationConstraints.shift(flight, flightOffset)).toList();
                    // Read-only against prior components' result; do not mutate ledgers yet.
                    var connectorAccess = ExteriorCirculationValidator.connectorAccess(c, components, slotMap, flightOffset, patchOccupancy);
                    boolean exteriorStair = c.params() != null && Boolean.TRUE.equals(c.params().get("exterior"));
                    var exteriorCollision = ExteriorCirculationValidator.findCollision(
                            result, shiftedFlights, buildingVolumes, connectorAccess, exteriorStair);
                    if (exteriorCollision.isPresent()) {
                        var collision = exteriorCollision.get();
                        AssemblyCompileDiagnostics.set(new CapabilityGap("E_CIRCULATION_EXTERIOR_CONFLICT",
                                "楼梯占用或净空覆盖已有外墙：" + collision.position().toShortString()
                                        + " (" + collision.role() + ")", "components[]",
                                List.of("Move the stair flight and its clearance inside the host building; use an existing doorway for exterior access.")));
                        return patchOccupancy;
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
                    int attachmentPatchCount = shiftedPatches.size();
                    if ("ENTRANCE".equals(normalizedType)) {
                        shiftedPatches.removeIf(p -> surfaceLedger.isProtectedFromEntrance(c, new BlockPos(p.dx(), p.dy(), p.dz())));
                    }
                    if ("FACADE_WINDOWS".equals(normalizedType)) {
                        int before = shiftedPatches.size();
                        shiftedPatches.removeIf(p -> surfaceLedger.isProtectedFromWindows(c, new BlockPos(p.dx(), p.dy(), p.dz())));
                        if (before != shiftedPatches.size()) {
                            AssemblyCompileDiagnostics.addSoftWarning("WINDOWS_CLIPPED_TO_WALL",
                                    "窗户已避让宿主屋顶或入口，跳过 " + (before - shiftedPatches.size()) + " 条操作",
                                    c.componentType(), paramIdentity(c));
                        }
                    }
                    if ("ROOF".equals(normalizedType)) {
                        trimCollidingRoofOverhang(c, components, slotMap, flightOffset, patchOccupancy, shiftedPatches);
                    }
                    if ("MASS_SECONDARY".equals(normalizedType) && c.params() != null
                            && "plate".equalsIgnoreCase(String.valueOf(c.params().get("extrude_mode")))) {
                        // Connector decks meet existing walls; retain those walls rather
                        // than aborting the whole compound or granting blanket overwrite.
                        // Bridges may also touch target_host_id shells (portal carve / flush join).
                        String host = getParamString(c.params(), "host_id");
                        String target = getParamString(c.params(), "target_host_id", "targetHostId");
                        boolean bridge = com.formacraft.common.generation.component.util.HostedPlateOccupancy
                                .isBridgePlate(c.params());
                        if (bridge) {
                            var hostMass = HostedPlateDesignValidator.findBridgeEndpoint(host, components);
                            var targetMass = HostedPlateDesignValidator.findBridgeEndpoint(target, components);
                            var portalClearance = new HashSet<BlockPos>();
                            // Preserve a central walking lane across the deck, including where
                            // later hosted railings or enclosures cross the connector.
                            var deckBounds = com.formacraft.common.generation.component.util.ComponentFootprintUtil.bounds(c);
                            if (deckBounds != null) {
                                boolean alongZ = deckBounds.depth() >= deckBounds.width();
                                int spanMin = alongZ ? deckBounds.minX() : deckBounds.minZ();
                                int spanMax = alongZ ? deckBounds.maxX() : deckBounds.maxZ();
                                int mid = (spanMin + spanMax) / 2;
                                int laneMin = Math.max(spanMin+1, mid-1);
                                int laneMax = Math.min(spanMax-2, mid);
                                for (int a=laneMin; a<=laneMax; a++)
                                    for (int b=alongZ ? deckBounds.minZ() : deckBounds.minX();
                                         b<(alongZ ? deckBounds.maxZ() : deckBounds.maxX()); b++)
                                        for (int y=c.relativePosition().y()+1; y<=c.relativePosition().y()+2; y++) {
                                            var pos = new BlockPos(alongZ ? a : b, y, alongZ ? b : a);
                                            surfaceLedger.reserveClearance(pos.add(flightOffset));
                                        }
                            }
                            // Portal coords are component-relative; shift into the same space as plate patches.
                            java.util.function.Consumer<java.util.List<BlockPatch>> addPortals = portals -> {
                                for (BlockPatch p : portals) {
                                    if (p == null) continue;
                                    shiftedPatches.add(new BlockPatch(
                                            p.action(),
                                            flightOffset.getX() + p.dx(),
                                            flightOffset.getY() + p.dy(),
                                            flightOffset.getZ() + p.dz(),
                                            p.targetBlock()));
                                    surfaceCells.add(new GeneratedSurfaceCapture.Cell(new BlockPos(p.dx(),p.dy(),p.dz()),
                                            GeneratedSurfaceCapture.Role.DOOR_OPENING));
                                    portalClearance.add(flightOffset.add(p.dx(),p.dy(),p.dz()));
                                }
                            };
                            hostMass.ifPresent(m -> addPortals.accept(
                                    com.formacraft.common.generation.component.util.HostedPlateOccupancy
                                            .bridgePortalRemovals(c, inSlotFrame(m, c.slotId(), slotMap))));
                            targetMass.ifPresent(m -> addPortals.accept(
                                    com.formacraft.common.generation.component.util.HostedPlateOccupancy
                                            .bridgePortalRemovals(c, inSlotFrame(m, c.slotId(), slotMap))));
                            if (!portalClearance.isEmpty()) circulation.add(new AssemblyCirculationConstraints.Flight(Set.of(), portalClearance));
                        }
                        shiftedPatches.removeIf(p -> {
                            if (BlockPatch.REMOVE.equals(p.action()) && bridge) {
                                return false;
                            }
                            var prev = patchOccupancy.placeAt(new BlockPos(p.dx(), p.dy(), p.dz()));
                            if (prev == null || prev.owner() == null) {
                                return false;
                            }
                            String ownerId = getParamString(prev.owner().params(), "component_id");
                            String ownerHost = getParamString(prev.owner().params(), "host_id");
                            if (host != null && (host.equals(ownerId) || host.equals(ownerHost))) {
                                return false;
                            }
                            return !bridge || target == null || (!target.equals(ownerId) && !target.equals(ownerHost));
                        });
                    }
                    if ("DECOR_DETAIL".equals(normalizedType)) {
                        int before = shiftedPatches.size();
                        shiftedPatches.removeIf(p -> surfaceLedger.isProtectedFromDecoration(c, new BlockPos(p.dx(), p.dy(), p.dz())));
                        if (before != shiftedPatches.size()) AssemblyCompileDiagnostics.addSoftWarning("DECOR_CLIPPED",
                                "装饰已避让门窗及屋顶，跳过 " + (before - shiftedPatches.size()) + " 条操作",
                                c.componentType(), paramIdentity(c));
                    }
                    if (plan.layout() != null && "COMPOUND".equalsIgnoreCase(String.valueOf(plan.layout().skeletonType()))) {
                        int shared = patchOccupancy.trimCompoundMassJoin(c, shiftedPatches);
                        if (shared > 0) AssemblyCompileDiagnostics.addSoftWarning("MASS_JOIN_CLIPPED",
                                "相邻体块交界处保留已有外壳，跳过冲突操作 " + shared, c.componentType(), paramIdentity(c));
                    }
                    int incompatible = patchOccupancy.trimIncompatibleAttachment(c, shiftedPatches, connectorAccess);
                    if (incompatible > 0) AssemblyCompileDiagnostics.addSoftWarning("ATTACHMENT_JOIN_CLIPPED",
                            "附属构件已保留已有结构，跳过冲突操作 " + incompatible,
                            c.componentType(), paramIdentity(c));
                    // Adapted overhang/window cells must not claim surfaces they no longer emit.
                    if (attachmentPatchCount != shiftedPatches.size()) {
                        var emittedPositions = new HashSet<BlockPos>();
                        for (var p : shiftedPatches) emittedPositions.add(new BlockPos(p.dx(), p.dy(), p.dz()));
                        surfaceCells.removeIf(cell -> !emittedPositions.contains(cell.shift(flightOffset).position()));
                    }
                    var surfaceConflict = surfaceLedger.check(c, shiftedPatches);
                    if (surfaceConflict.isPresent()) {
                        var conflict = surfaceConflict.get();
                        AssemblyCompileDiagnostics.set(new CapabilityGap("E_SURFACE_OVERWRITE_FORBIDDEN",
                                "构件覆盖外壳冲突：writer=" + conflict.writer() + ", owner=" + conflict.surface().owner()
                                        + ", surface=" + conflict.surface().role() + ", plan=" + conflict.position().toShortString()
                                        + "; " + conflict.reason(), "components[]",
                                List.of("Keep decoration non-destructive; bind wall openings to their host building.")));
                        return patchOccupancy;
                    }
                    // Validate all materials before any ledger / result mutation (transactional commit).
                    var explicitTargets = com.formacraft.common.palette.dynamic.ExplicitMaterialPolicy.targets(c, styleAttributes);
                    for (var patch : shiftedPatches) {
                        if (com.formacraft.common.patch.BlockPatchTargetResolver.resolve(patch) == null) {
                            AssemblyCompileDiagnostics.set(new CapabilityGap("E_MATERIAL_INVALID",
                                    "构件生成了无效方块材料：" + c.componentType() + " " + patch.targetBlock(),
                                    "components[]", List.of("Use a registered block id and valid state properties.")));
                            return patchOccupancy;
                        }
                    }
                    var occupancyConflict = patchOccupancy.validate(c, shiftedPatches, connectorAccess);
                    if (occupancyConflict.isPresent()) {
                        var oc = occupancyConflict.get();
                        var previous = patchOccupancy.placeAt(oc.position());
                        AssemblyCompileDiagnostics.set(new CapabilityGap("E_PATCH_CONFLICT",
                                "未授权 Patch 覆盖：pos=" + oc.position().toShortString()
                                        + " prev=" + oc.previousWriter() + "/" + oc.previousBlock()
                                        + " writer=" + oc.writer() + "/" + oc.newBlock()
                                        + " id=" + paramIdentity(c) + " host=" + getParamString(c.params(), "host_id")
                                        + " previous_id=" + (previous == null || previous.owner() == null ? "?" : paramIdentity(previous.owner()))
                                        + " (" + oc.reason() + ")",
                                "components[]",
                                List.of("为开口类构件授权覆盖", "或设置 allow_patch_overwrite=true",
                                        "禁止无关构件互相覆盖同坐标")));
                        return patchOccupancy;
                    }
                    patchOccupancy.commit(c, shiftedPatches, connectorAccess);

                    // Commit boundary: patches + ledgers + protected sets together.
                    result.addAll(shiftedPatches);
                    if (com.formacraft.server.generation.component.impl.StraightStairComponentGenerator.accepts(c)) {
                        // An authorized connector opening supersedes the window cells
                        // it replaces with a landing/support or carved passage.
                        for (var patch : shiftedPatches) {
                            var pos = new BlockPos(patch.dx(),patch.dy(),patch.dz());
                            var expected = openingExpectations.get(pos);
                            if (!connectorAccess.contains(pos) || expected == null
                                    || expected.role() != GeneratedSurfaceCapture.Role.WINDOW) continue;
                            if (BlockPatch.REMOVE.equals(patch.action())) {
                                openingExpectations.put(pos,new OpeningMaterialAudit.Expectation(paramIdentity(c),
                                        GeneratedSurfaceCapture.Role.DOOR_OPENING,null));
                                generatedSurfaces.remove(pos);
                            } else openingExpectations.remove(pos);
                        }
                    }
                    OpeningMaterialAudit.capture(openingExpectations, c, surfaceCells, flightOffset);
                    for (var cell : surfaceCells) if (cell.requiresBlock())
                        generatedSurfaces.add(cell.shift(flightOffset).position());
                    circulation.addAll(shiftedFlights);
                    if (isMassType(normalizedType) && !(c.params() != null
                            && "plate".equalsIgnoreCase(String.valueOf(c.params().get("extrude_mode"))))) {
                        for (var part : com.formacraft.common.generation.component.util.ResolvedMassPart.resolve(c)) {
                        var bounds = part.bounds();
                            Vec3i offset = slotAnchor == null ? new Vec3i(0, 0, 0) : slotAnchor;
                            var shifted = new ComponentFootprintUtil.Bounds(bounds.minX() + offset.x(), bounds.minY() + offset.y(),
                                bounds.minZ() + offset.z(), bounds.maxX() + offset.x(), bounds.maxY() + offset.y(), bounds.maxZ() + offset.z());
                            int floorHeight = com.formacraft.common.generation.component.util.ComponentFloorCorniceDecorator
                                .resolveFloorHeight(plan, c, shifted.height());
                            buildingVolumes.add(new PostProcessContext.BuildingVolume(slotKey, shifted, floorHeight));
                        }
                    }
                    for (var patch : shiftedPatches) {
                        var position = new BlockPos(patch.dx(), patch.dy(), patch.dz());
                        if (com.formacraft.common.style.ExplicitDesignPolicy.noComplexDecor(plan, c.params())) {
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
                if (isSoftGenerationFailure(c)) {
                    FormacraftMod.LOGGER.warn(
                            "ComponentPlanCompiler: optional decor component failed (SOFT degrade): type={} id={} slot={}: {}",
                            c.componentType(), paramIdentity(c), c.slotId(), e.getMessage(), e);
                    AssemblyCompileDiagnostics.addSoftWarning(
                            "DECOR_COMPONENT_SKIPPED",
                            "可选装饰缺失：type=" + c.componentType()
                                    + " id=" + paramIdentity(c)
                                    + " slot=" + c.slotId()
                                    + "；主体结构完整，部分装饰未生成"
                                    + (e.getMessage() == null ? "" : "（" + e.getMessage() + "）"),
                            c.componentType(),
                            paramIdentity(c)
                    );
                } else {
                    FormacraftMod.LOGGER.error("ComponentPlanCompiler: error generating component {}: {}",
                            c.componentType(), e.getMessage(), e);
                    String gapCode = geometryConstraintCode(e.getMessage());
                    AssemblyCompileDiagnostics.set(new CapabilityGap(
                            gapCode,
                            "关键构件生成失败：type=" + c.componentType()
                                    + " id=" + paramIdentity(c)
                                    + " slot=" + c.slotId()
                                    + " error=" + e.getClass().getSimpleName()
                                    + (e.getMessage() == null ? "" : ": " + e.getMessage()),
                            "components[]",
                            List.of("修复该构件参数或生成器异常", "装饰类失败可降级，主体/屋顶/门窗不可")
                    ));
                    return patchOccupancy;
                }
            }
        }
        return patchOccupancy;
    }

    static boolean isSoftGenerationFailure(Component c) {
        if (c == null) return false;
        String type = normalizeType(c.componentType());
        return "DECOR_DETAIL".equals(type) || type.startsWith("DECOR");
    }

    /**
     * After all patches commit, require bridge decks to BFS-reach both mass interiors
     * through portal clearances. Distinguishes path blocked, BFS search limit, and region scan limit.
     */
    private static boolean validateBridgeWalkability(
            List<Component> components,
            List<BlockPatch> result,
            Map<String, Slot> slotMap
    ) {
        if (components == null || result == null || result.isEmpty()) {
            return true;
        }
        var occ = com.formacraft.common.generation.component.util.CirculationAnalysisContext.occupancy();
        if (occ == null) {
            occ = com.formacraft.common.generation.component.util.BridgeWalkability.occupancyFromPatches(result);
        }
        Vec3i zero = new Vec3i(0, 0, 0);
        for (Component plate : components) {
            if (plate == null || plate.params() == null) {
                continue;
            }
            if (!com.formacraft.common.generation.component.util.HostedPlateOccupancy.isBridgePlate(plate.params())) {
                continue;
            }
            String hostId = getParamString(plate.params(), "host_id", "hostId");
            String targetId = getParamString(plate.params(), "target_host_id", "targetHostId");
            var host = HostedPlateDesignValidator.findBridgeEndpoint(hostId, components);
            var target = HostedPlateDesignValidator.findBridgeEndpoint(targetId, components);
            if (host.isEmpty() || target.isEmpty()) {
                continue;
            }
            Vec3i plateOff = slotAnchorOrZero(slotMap, plate.slotId(), zero);
            Vec3i hostOff = slotAnchorOrZero(slotMap, host.get().slotId(), zero);
            Vec3i targetOff = slotAnchorOrZero(slotMap, target.get().slotId(), zero);
            var outcome = com.formacraft.common.generation.component.util.BridgeWalkability.evaluateBridgeDetailed(
                    occ, plate, plateOff, host.get(), hostOff, target.get(), targetOff);
            var reach = outcome.reach();
            String precisionNote = ", precision=" + outcome.precision();
            if (reach == com.formacraft.common.generation.component.util.BridgeWalkability.BridgeReach.CONNECTED) {
                continue;
            }
            if (reach == com.formacraft.common.generation.component.util.BridgeWalkability.BridgeReach.REGION_SCAN_LIMIT) {
                String massId = outcome.limitedMassId() == null ? "unknown" : outcome.limitedMassId();
                AssemblyCompileDiagnostics.set(new CapabilityGap(
                        "E_CIRCULATION_REGION_SCAN_LIMIT",
                        "Storey region scan exceeds budget (component_id=" + massId
                                + ", storeyY=" + outcome.storeyY()
                                + ", area=" + outcome.areaCells()
                                + ", max=" + com.formacraft.common.generation.component.util.StoreyWalkRegions.MAX_CELLS
                                + precisionNote + ")",
                        "components[]",
                        List.of("Reduce mass footprint or split the building.",
                                "This is not a proof that the bridge path is blocked — scan stopped by budget.")));
                return false;
            }
            if (reach == com.formacraft.common.generation.component.util.BridgeWalkability.BridgeReach.SEARCH_LIMIT) {
                AssemblyCompileDiagnostics.set(new CapabilityGap(
                        "E_WALKABILITY_SEARCH_LIMIT",
                        "Bridge walkability search exhausted its budget before proving a path (host_id="
                                + hostId + ", target_host_id=" + targetId + precisionNote + ")",
                        "components[]",
                        List.of("Shorten the bridge span or simplify circulation.",
                                "This is not a proof that no path exists — only that search stopped early.",
                                "precision=BLOCKSTATE is registry-refined profiles, not VoxelShape collision.")));
                return false;
            }
            AssemblyCompileDiagnostics.set(new CapabilityGap(
                    "E_BRIDGE_PATH_BLOCKED",
                    "Bridge cannot walk from deck into both interiors (host_id=" + hostId
                            + ", target_host_id=" + targetId + precisionNote + ")",
                    "components[]",
                    List.of("Carve portals through wall_thickness at both contact faces.",
                            "Ensure bridge Y has solid floors and 2-high clearance into both masses.",
                            "precision=BLOCKSTATE is registry-refined profiles, not VoxelShape collision.")));
            var deckLocal = com.formacraft.common.generation.component.util.BridgeWalkability.deckFloorCell(plate);
            Vec3i deckWorld = null;
            if (deckLocal != null) {
                deckWorld = new Vec3i(deckLocal.x() + plateOff.x(), deckLocal.y() + plateOff.y(), deckLocal.z() + plateOff.z());
            }
            FormacraftMod.LOGGER.warn("Bridge blocked: deck={} floor={} head1={} head2={} host={} target={}", deckWorld,
                    occ.profileAt(deckWorld), occ.profileAt(new Vec3i(deckWorld.x(), deckWorld.y()+1, deckWorld.z())),
                    occ.profileAt(new Vec3i(deckWorld.x(), deckWorld.y()+2, deckWorld.z())),
                    com.formacraft.common.generation.component.util.BridgeWalkability.storeyGoalsPreferringInterior(occ, host.get(), deckWorld.y()-hostOff.y(), hostOff),
                    com.formacraft.common.generation.component.util.BridgeWalkability.storeyGoalsPreferringInterior(occ, target.get(), deckWorld.y()-targetOff.y(), targetOff));
            return false;
        }
        return true;
    }

    static Component inSlotFrame(Component component, String slotId, Map<String, Slot> slotMap) {
        Vec3i zero = new Vec3i(0, 0, 0);
        Vec3i source = slotAnchorOrZero(slotMap, component.slotId(), zero);
        Vec3i destination = slotAnchorOrZero(slotMap, slotId, zero);
        Vec3i position = component.relativePosition();
        if (position == null) return component;
        return new Component(component.componentType(), slotId,
                new Vec3i(position.x() + source.x() - destination.x(),
                        position.y() + source.y() - destination.y(), position.z() + source.z() - destination.z()),
                component.dimensions(), component.features(), component.params());
    }

    private static Vec3i slotAnchorOrZero(Map<String, Slot> slotMap, String slotId, Vec3i zero) {
        if (slotMap == null || slotId == null) {
            return zero;
        }
        Slot slot = slotMap.get(slotId);
        if (slot == null || slot.anchor() == null) {
            return zero;
        }
        return slot.anchor();
    }

    /** Prefer dedicated geometry codes when generators prefix IllegalArgumentException messages. */
    static String geometryConstraintCode(String message) {
        if (message == null) return "E_COMPONENT_GENERATION_FAILED";
        if (message.startsWith("E_ENTRANCE_DOOR_WIDTH")) return "E_ENTRANCE_DOOR_WIDTH";
        if (message.startsWith("E_WINDOW_COUNT_UNFIT")) return "E_WINDOW_COUNT_UNFIT";
        if (message.startsWith("E_FACADE_SCOPE_INVALID")) return "E_FACADE_SCOPE_INVALID";
        if (message.startsWith("E_ROOF_GEOMETRY_INVALID")) return "E_ROOF_GEOMETRY_INVALID";
        if (message.startsWith("E_ROOF_GEOMETRY_CONFLICT")) return "E_ROOF_GEOMETRY_CONFLICT";
        if (message.startsWith("E_COURTYARD_RATIO_INVALID")) return "E_COURTYARD_RATIO_INVALID";
        if (message.startsWith("E_WALL_THICKNESS_INVALID")) return "E_WALL_THICKNESS_INVALID";
        if (message.startsWith("E_COURTYARD_GEOMETRY_CONFLICT")) return "E_COURTYARD_GEOMETRY_CONFLICT";
        if (message.startsWith("E_WALL_THICKNESS_UNFIT")) return "E_WALL_THICKNESS_UNFIT";
        if (message.startsWith("E_PLAN_PATTERN_UNFIT")) return "E_PLAN_PATTERN_UNFIT";
        if (message.startsWith("E_PLAN_PATTERN_UNSUPPORTED")) return "E_PLAN_PATTERN_UNSUPPORTED";
        if (message.startsWith("E_FOOTPRINT_SHAPE_UNSUPPORTED")) return "E_FOOTPRINT_SHAPE_UNSUPPORTED";
        if (message.startsWith("E_FOOTPRINT_COMBINATION_UNSUPPORTED")) return "E_FOOTPRINT_COMBINATION_UNSUPPORTED";
        if (message.startsWith("E_PLATE_ROLE_UNSUPPORTED")) return "E_PLATE_ROLE_UNSUPPORTED";
        if (message.startsWith("E_HOST_GEOMETRY_MISSING")) return "E_HOST_GEOMETRY_MISSING";
        if (message.startsWith("E_HOST_ID_UNRESOLVED")) return "E_HOST_ID_UNRESOLVED";
        if (message.startsWith("E_HOSTED_PLATE_LEVEL_CONFLICT")) return "E_HOSTED_PLATE_LEVEL_CONFLICT";
        if (message.startsWith("E_DUPLICATE_COMPONENT_ID")) return "E_DUPLICATE_COMPONENT_ID";
        if (message.startsWith("E_TARGET_HOST_ID_REQUIRED")) return "E_TARGET_HOST_ID_REQUIRED";
        if (message.startsWith("E_TARGET_HOST_ID_UNRESOLVED")) return "E_TARGET_HOST_ID_UNRESOLVED";
        if (message.startsWith("E_BRIDGE_ENDPOINT_NO_CONTACT")) return "E_BRIDGE_ENDPOINT_NO_CONTACT";
        if (message.startsWith("E_CONNECTION_LEVEL_MISMATCH")) return "E_CONNECTION_LEVEL_MISMATCH";
        if (message.startsWith("E_BRIDGE_OPENING_BLOCKED")) return "E_BRIDGE_OPENING_BLOCKED";
        if (message.startsWith("E_BRIDGE_ENDPOINT_LEVEL_MISMATCH")) return "E_BRIDGE_ENDPOINT_LEVEL_MISMATCH";
        if (message.startsWith("E_BRIDGE_LEVEL_CONFLICT")) return "E_BRIDGE_LEVEL_CONFLICT";
        if (message.startsWith("E_BRIDGE_PATH_BLOCKED")) return "E_BRIDGE_PATH_BLOCKED";
        if (message.startsWith("E_WALKABILITY_SEARCH_LIMIT")) return "E_WALKABILITY_SEARCH_LIMIT";
        if (message.startsWith("E_BUILDING_CIRCULATION_INCOMPLETE")) return "E_BUILDING_CIRCULATION_INCOMPLETE";
        if (message.startsWith("E_BUILDING_CIRCULATION_DATA_MISSING")) return "E_BUILDING_CIRCULATION_DATA_MISSING";
        if (message.startsWith("E_CIRCULATION_SCOPE_INVALID")) return "E_CIRCULATION_SCOPE_INVALID";
        if (message.startsWith("E_CIRCULATION_HOST_CYCLE")) return "E_CIRCULATION_HOST_CYCLE";
        if (message.startsWith("E_CIRCULATION_HOST_LINKS_INVALID")) return "E_CIRCULATION_HOST_LINKS_INVALID";
        if (message.startsWith("E_CIRCULATION_HOST_LINK_BLOCKED")) return "E_CIRCULATION_HOST_LINK_BLOCKED";
        if (message.startsWith("E_CIRCULATION_REGION_SCAN_LIMIT")) return "E_CIRCULATION_REGION_SCAN_LIMIT";
        return "E_COMPONENT_GENERATION_FAILED";
    }

    /** Soft-warn when gable seal cannot run on a non-rectangular footprint. */
    static void maybeWarnIrregularGableSeal(Component roof, SemanticComponent semantic) {
        if (roof == null || roof.dimensions() == null) return;
        Map<String, Object> params = roof.params();
        String type = getParamString(params, "roof_type", "roofType");
        if (type == null) return;
        String lower = type.trim().toLowerCase(java.util.Locale.ROOT);
        if (!Set.of("gable", "gabled", "double_gable", "xuanshan").contains(lower)) return;
        if ("false".equalsIgnoreCase(getParamString(params, "gable_walls"))) return;
        int w = Math.max(1, roof.dimensions().width());
        int d = Math.max(1, roof.dimensions().depth());
        var mask = com.formacraft.common.generation.component.util.ComponentFootprintMask.from(semantic, params, w, d);
        if (mask.isFullyRectangular()) return;
        AssemblyCompileDiagnostics.addSoftWarning(
                "ROOF_GABLE_SEAL_SKIPPED",
                "不规则占地跳过矩形山墙封口：id=" + paramIdentity(roof)
                        + " footprint=" + w + "x" + d
                        + " roof_type=" + type
                        + " reason=irregular_footprint_gable_unsealed",
                "ROOF",
                paramIdentity(roof)
        );
    }

    private static String paramIdentity(Component c) {
        if (c == null || c.params() == null) return "?";
        Object id = c.params().get("component_id");
        return id == null ? "?" : String.valueOf(id);
    }

    private static int compilationPriority(Component c) {
        if (c == null) return 6;
        if (com.formacraft.server.generation.component.impl.StraightStairComponentGenerator.accepts(c)
                || com.formacraft.server.generation.component.impl.AssemblyPatchGenerator.hasCirculation(c)) return 7;
        String type = normalizeType(c.componentType());
        if ("MASS_SECONDARY".equals(type) && c.params() != null
                && "plate".equalsIgnoreCase(String.valueOf(c.params().get("extrude_mode")))) return 1;
        if (isMassType(type) || "TOWER".equals(type) || "MASS_ANCILLARY".equals(type)) return 0;
        if (isStructureFloorType(type)) return 1;
        switch (type) {
            case "ROOF", "ROOF_STRUCTURE" -> {
                return 2;
            }
            case "FACADE" -> {
                return 3;
            }
            case "ENTRANCE", "DOOR" -> {
                return 4;
            }
            case "FACADE_WINDOWS", "FACADE_OPENING", "WINDOW" -> {
                return 5;
            }
        }
        return 6;
    }

    private static boolean isStructureFloorType(String type) {
        return "FLOOR".equals(type) || "SLAB".equals(type) || "FLOOR_SLAB".equals(type)
                || "STRUCTURE_FLOOR".equals(type) || "INTERIOR_FLOOR".equals(type);
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
            if (isMassType(normalizeType(normalizedComponent.componentType()))
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
            if (!com.formacraft.common.generation.component.util.MassComponentKinds.isHostMass(slab)) continue;
            if (!"plate".equalsIgnoreCase(getParamString(slab.params(), "extrude_mode"))) continue;
            String host = getParamString(slab.params(), "host_id");
            for (Component body : components) {
                if (!"MASS_MAIN".equals(normalizeType(body.componentType()))
                        || host == null || !host.equals(getParamString(body.params(), "component_id"))) continue;
                int fh = ComponentParamParsers.intParam(body.params(), "floor_height", "floorHeight");
                Vec3i origin = resolveMassOrigin(body);
                if (fh <= 0 || origin == null || slab.relativePosition() == null || body.dimensions() == null) break;
                Vec3i pos = slab.relativePosition();
                int plateY = pos.y();
                var plateRole = com.formacraft.common.generation.component.util.HostedPlateOccupancy
                        .resolveRole(slab.params());
                var connectionLevel = com.formacraft.common.generation.component.util.HostedPlateOccupancy
                        .connectionLevel(slab.params());
                // Storey snap is interior-only. Bridge connection_level never silently overwrites explicit Y.
                if (plateRole == com.formacraft.common.generation.component.util.HostedPlateOccupancy.PlateRole.INTERIOR
                        && isAutoInferred(slab)) {
                    int count = ComponentParamParsers.intParam(body.params(), "floor_count", "floorCount");
                    int level = Math.max(1, Math.min(Math.max(1, count - 1),
                            Math.round((pos.y() - origin.y()) / (float) fh)));
                    plateY = origin.y() + level * fh;
                } else if (plateRole == com.formacraft.common.generation.component.util.HostedPlateOccupancy.PlateRole.BRIDGE
                        && connectionLevel.isPresent()) {
                    int expectedY = com.formacraft.common.generation.component.util.HostedPlateOccupancy.connectionY(slab, body, fh);
                    if (isAutoInferred(slab)) {
                        plateY = expectedY;
                    } else if (pos.y() != expectedY) {
                        AssemblyCompileDiagnostics.set(new CapabilityGap(
                                "E_BRIDGE_LEVEL_CONFLICT",
                                "Explicit bridge Y=" + pos.y()
                                        + " conflicts with connection_level=" + connectionLevel.get()
                                        + " expected Y=" + expectedY,
                                "components[].relative_position.y",
                                List.of("Align relative_position.y with hostOrigin.y + connection_level * floor_height.",
                                        "Or omit connection_level to keep the authored bridge Y.")));
                        return new PreparedComponents(List.of(), Set.of());
                    }
                } else if (!isAutoInferred(slab)) {
                    var envelope = com.formacraft.common.generation.component.util.HostedPlateOccupancy
                            .levelEnvelope(plateRole, origin.y(), HostedPlateDesignValidator.hostedEnvelopeHeight(body, components));
                    if (envelope != null && !envelope.contains(plateY)) {
                        AssemblyCompileDiagnostics.set(new CapabilityGap(
                                "E_HOSTED_PLATE_LEVEL_CONFLICT",
                                "Explicit plate Y=" + plateY + " is outside " + plateRole + " envelope ["
                                        + envelope.minInclusive() + "," + envelope.maxInclusive() + "]",
                                "components[].relative_position.y",
                                List.of("Keep interior plates within the host mass height.",
                                        "Terrace may sit on the roof (host height + 1).",
                                        "Bridge Y is not limited to a single host envelope.")));
                        return new PreparedComponents(List.of(), Set.of());
                    }
                }
                var slabParams = new HashMap<String, Object>(slab.params());
                if (plateRole == com.formacraft.common.generation.component.util.HostedPlateOccupancy.PlateRole.BRIDGE) {
                    slabParams.put("allow_carve", true);
                }
                String floorBlock = getParamString(body.params(), "floor_block");
                String slabMaterial = getParamString(slab.params(), "material");
                // A generic material family must not override the host's explicit floor block.
                if (floorBlock != null && (slabMaterial == null
                        || Set.of("wood", "minecraft:wood", "planks", "wooden").contains(slabMaterial.toLowerCase(java.util.Locale.ROOT))))
                    slabParams.put("material", floorBlock);
                // Only interior plates clear exterior walls via host AABB inset.
                int x = pos.x(), z = pos.z(), width = slab.dimensions().width(), depth = slab.dimensions().depth();
                boolean skipInset = Boolean.TRUE.equals(slabParams.get("exposed_floor_edges"))
                        || !com.formacraft.common.generation.component.util.HostedPlateOccupancy
                        .appliesHostWallInset(plateRole);
                if (!skipInset) {
                    int thickness = Math.max(1, ComponentParamParsers.intParam(body.params(), 1, "wall_thickness", "wallThickness"));
                    int endX = Math.min(x + width, origin.x() + body.dimensions().width() - thickness);
                    int endZ = Math.min(z + depth, origin.z() + body.dimensions().depth() - thickness);
                    x = Math.max(x, origin.x() + thickness);
                    z = Math.max(z, origin.z() + thickness);
                    width = Math.max(0, endX - x);
                    depth = Math.max(0, endZ - z);
                }
                components.set(i, new Component(slab.componentType(), slab.slotId(),
                        new Vec3i(x, plateY, z),
                        new Dimensions(width, depth, 1), slab.features(), slabParams));
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
            if (AssemblyCompileDiagnostics.hasGap()) {
                return new PreparedComponents(List.of(), Set.of());
            }
            boolean connectorBody = c.params() != null && Boolean.TRUE.equals(c.params().get("compiler_connector_body"));
            boolean windowsDisabled = com.formacraft.common.style.ExplicitDesignPolicy.windowsDisabled(c) || connectorBody;
            boolean entranceDisabled = com.formacraft.common.style.ExplicitDesignPolicy.entranceDisabled(c)
                    || ("MASS_SECONDARY".equals(type) && c.params() != null
                        && c.params().containsKey("tier_index")) || connectorBody;
            OpeningDelegation windowDelegation = collectOpeningDelegation(c, components, inferred, "FACADE_WINDOWS");
            OpeningDelegation doorDelegation = collectOpeningDelegation(c, components, inferred, "ENTRANCE");
            if (AssemblyCompileDiagnostics.hasGap()) {
                return new PreparedComponents(List.of(), Set.of());
            }
            boolean useAssemblyFacade = allowAssemblyFacade
                    && shouldUseAssemblyFacade(plan, c)
                    && !hasFacade
                    && !hasEntrance && !windowsDisabled && !entranceDisabled;

            boolean hasRoofEarly = hasSatelliteForMass(c, components, inferred, "ROOF");
            boolean hasCrown = slotsWithCrown.contains(slotKey);
            boolean wouldInferCrown = !hasCrown
                    && !NonClassicalEnrichmentGuard.blocksCrownInference(plan)
                    && ComponentCrownDecorator.shouldApply(plan, c.params());
            var designGap = CompilerSatellitePolicy.missingSatellitesGap(
                    plan, c, hasFacade, hasEntrance, hasRoofEarly, useAssemblyFacade, hasCrown, wouldInferCrown);
            if (designGap.isPresent()) {
                AssemblyCompileDiagnostics.set(designGap.get());
                return new PreparedComponents(List.of(), Set.of());
            }
            boolean allowCompilerInfer = plan.allowCompilerSatellites();

            if (useAssemblyFacade) {
                assemblyFacadeSlots.add(getParamString(c.params(), "component_id"));
                c = markAssemblyFacade(c);
            } else if (allowCompilerInfer) {
                if (!hasFacade && !windowsDisabled) {
                    Component facade = tagInferredSatellite(makeFacadeComponent(c, slotId));
                    facade = StyleIntentResolver.apply(plan, facade);
                    facade = OpeningGrammarResolver.apply(plan, facade);
                    inferred.add(facade);
                    hasFacade = true;
                    windowDelegation = OpeningDelegation.full();
                }
                if (!hasEntrance && !entranceDisabled) {
                    Component entrance = makeEntranceComponent(plan, c, slotId, facing);
                    if (entrance != null) {
                        entrance = tagInferredSatellite(entrance);
                        entrance = StyleIntentResolver.apply(plan, entrance);
                        inferred.add(entrance);
                        hasEntrance = true;
                        doorDelegation = OpeningDelegation.full();
                    }
                }
            }
            if (windowsDisabled) windowDelegation = OpeningDelegation.full();
            if (entranceDisabled) doorDelegation = OpeningDelegation.full();
            // World faces → local faces using slot facing before writing MASS params
            GlobalConstraints.Facing hostFacing = facing != null ? facing : GlobalConstraints.Facing.SOUTH;
            windowDelegation = windowDelegation.mapFaces(world ->
                    com.formacraft.common.generation.component.util.FacadeDirection.worldToLocal(world, hostFacing));
            doorDelegation = doorDelegation.mapFaces(world ->
                    com.formacraft.common.generation.component.util.FacadeDirection.worldToLocal(world, hostFacing));
            if (windowDelegation.isActive() || doorDelegation.isActive()) {
                c = suppressMassOpenings(c, windowDelegation, doorDelegation);
            }
            boolean hasRoof = hasSatelliteForMass(c, components, inferred, "ROOF");
            if (AssemblyCompileDiagnostics.hasGap()) {
                return new PreparedComponents(List.of(), Set.of());
            }
            if (allowCompilerInfer
                    && !hasRoof && !com.formacraft.common.style.ExplicitDesignPolicy.roofDisabled(c)) {
                Component roof = tagInferredSatellite(makeRoofComponent(plan, c, slotId));
                if (roof != null) {
                    roof = StyleIntentResolver.apply(plan, roof);
                    roof = RoofGrammarResolver.apply(plan, roof);
                    inferred.add(roof);
                    hasRoof = true;
                }
            }
            if (hasRoof) {
                c = suppressMassRoof(c);
            }
            if (allowCompilerInfer) {
                inferNestedFlatRoofs(plan, c, components, inferred, slotId);
            }
            if (allowCompilerInfer && wouldInferCrown) {
                Component roofRef = findRoofForSlot(components, inferred, slotKey);
                if (roofRef == null) {
                    roofRef = makeRoofComponent(plan, c, slotId);
                }
                Component crown = tagInferredSatellite(makeCrownComponent(plan, c, roofRef, slotId));
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
            if (isMassType(normalizeType(c.componentType()))
                    && !com.formacraft.common.generation.component.util.MassComponentKinds.isPlate(c)) {
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
                    if (candidate == null || !(isMassType(normalizeType(candidate.componentType()))
                            || "TOWER".equals(normalizeType(candidate.componentType()))
                            || "MASS_ANCILLARY".equals(normalizeType(candidate.componentType())))
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
            if (AssemblyCompileDiagnostics.hasGap()) {
                return;
            }
            if (aligned != null && mass.params() != null && mass.params().get("component_id") != null) {
                var params = new HashMap<String, Object>();
                if (aligned.params() != null) params.putAll(aligned.params());
                params.putIfAbsent("host_id", mass.params().get("component_id"));
                params.putIfAbsent("building_id", mass.params().getOrDefault("building_id", mass.params().get("component_id")));
                if (Set.of("ENTRANCE", "FACADE_WINDOWS", "ROOF", "ROOF_STRUCTURE").contains(type) && hasComponentRequest(aligned.features())) {
                    params.put("resolved_host_attachment", true);
                }
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
            int bboxOverhang = Math.max(extraW / 2, extraD / 2);
            Integer explicitOverhang = ComponentParamParsers.intOrNull(params,
                    ComponentParamParsers.keysFor("overhang"));
            boolean overhangLocked = com.formacraft.common.generation.design.DesignParameterRegistry
                    .isDesignLocked(plan, params, "overhang");
            boolean overhangExplicit = explicitOverhang != null || overhangLocked
                    || hasRawOverhangKey(params);
            if (overhangExplicit) {
                int existing = explicitOverhang != null ? explicitOverhang
                        : ComponentParamParsers.intParam(params, 0, ComponentParamParsers.keysFor("overhang"));
                // Some model roofs include eaves around the unretreated host.
                // Accept that exact representation without counting style setbacks as eaves.
                if (existing >= 0 && llmDims.width() == mass.dimensions().width() + 2 * existing
                        && llmDims.depth() == mass.dimensions().depth() + 2 * existing) {
                    bboxOverhang = existing;
                }
                if (bboxOverhang > 0 && existing != bboxOverhang) {
                    AssemblyCompileDiagnostics.set(new CapabilityGap(
                            "E_ROOF_OVERHANG_CONFLICT",
                            "explicit overhang=" + existing + " conflicts with roof bbox overhang=" + bboxOverhang,
                            "components[]",
                            List.of("Align roof dimensions with overhang, or omit overhang to derive from bbox.")));
                    return null;
                }
                // Keep explicit value; do not raise via Math.max(existing, bbox).
            } else if (bboxOverhang > 0) {
                params.put("overhang", bboxOverhang);
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
        // Inherit host wall thickness only when facade did not author its own.
        if (ComponentParamParsers.intOrNull(llmFacade.params(), "wall_thickness", "wallThickness") == null) {
            params.put("wall_thickness",
                    ComponentParamParsers.intParam(mass.params(), 1, "wall_thickness", "wallThickness"));
        }

        Dimensions massDims = mass.dimensions();
        Dimensions llmDims = llmFacade.dimensions();
        Vec3i massOrigin = template.relativePosition();
        if (massDims == null || massOrigin == null) {
            return llmFacade;
        }

        List<String> features = mergeFeatureLists(template.features(), llmFacade.features());
        int openingWidth = ComponentParamParsers.intParam(llmFacade.params(),"window_width","windowWidth");
        int openingHeight = ComponentParamParsers.intParam(llmFacade.params(),"window_height","windowHeight");
        boolean authoredSingleRow = ComponentParamParsers.intParam(llmFacade.params(),"rows") == 1;
        if (llmDims != null && llmFacade.relativePosition() != null && openingWidth > 0 && openingHeight > 0
                && (llmDims.width() == 1 || llmDims.depth() == 1)
                && (authoredSingleRow || (llmDims.width() == 1 ? llmDims.depth() : llmDims.width()) <= openingWidth+2)
                && llmDims.height() <= openingHeight+2
                && llmFacade.relativePosition().y()+openingHeight <= massOrigin.y()+massDims.height()
                && isFacadeStripOnHost(llmFacade,massOrigin,massDims)) {
            params.put("localized_window",(llmDims.width() == 1 ? llmDims.depth() : llmDims.width()) <= openingWidth+2);
            params.put("floor_count",1);
            params.put("floor_height",llmDims.height());
            var pos = llmFacade.relativePosition();
            params.put("localized_face",llmDims.width()==1 && llmDims.depth()>1
                    ? (pos.x() <= massOrigin.x() ? "EAST" : "WEST")
                    : (pos.z() <= massOrigin.z() ? "SOUTH" : "NORTH"));
            return new Component("FACADE_WINDOWS",llmFacade.slotId(),pos,llmDims,features,params);
        }
        // A thin authored strip identifies a wall and often one floor. Do not
        // expand every strip into the same full-building facade.
        if (llmDims != null && llmFacade.relativePosition() != null
                && (llmDims.width() == 1 || llmDims.depth() == 1)
                && isFacadeStripOnHost(llmFacade, massOrigin, massDims)) {
            var position = llmFacade.relativePosition();
            if (!params.containsKey("faces")) {
                String face = llmDims.width() == 1 && llmDims.depth() > 1
                        ? (Math.abs(position.x()-massOrigin.x()) <= Math.abs(position.x()-(massOrigin.x()+massDims.width()-1)) ? "EAST" : "WEST")
                        : (Math.abs(position.z()-massOrigin.z()) <= Math.abs(position.z()-(massOrigin.z()+massDims.depth()-1)) ? "SOUTH" : "NORTH");
                params.put("faces", List.of(face));
            }
            int fh = ComponentParamParsers.intParam(mass.params(), "floor_height", "floorHeight");
            int fc = ComponentParamParsers.intParam(mass.params(), "floor_count", "floorCount");
            if ("clerestory".equalsIgnoreCase(String.valueOf(params.get("height_level"))) && fh > 0) {
                params.putIfAbsent("sill_offset", Math.max(1, Math.floorMod(position.y()-massOrigin.y(), fh)));
            }
            if (fh > 0 && fc > 0 && llmDims.height() < massDims.height()) {
                int first = Math.max(0, Math.min(fc-1, (position.y()-massOrigin.y())/fh));
                int last = Math.max(first, Math.min(fc-1, (position.y()-massOrigin.y()+llmDims.height()-1)/fh));
                params.putIfAbsent("min_floor", first);
                params.putIfAbsent("max_floor", last);
            }
            var scopedFeatures = new ArrayList<>(features);
            scopedFeatures.add("wrap");
            features = scopedFeatures;
        }
        boolean wrap = hasWrapFeature(features);
        GlobalConstraints.Facing effectiveFacing = facing != null ? facing : GlobalConstraints.Facing.SOUTH;
        if ("front".equalsIgnoreCase(String.valueOf(params.get("wall")))) {
            // Explicit front-wall openings below the roof override a broad gable-wall opt-out.
            params.remove("excluded_window_axis");
        } else if (mass.params() != null && Boolean.FALSE.equals(mass.params().get("gable_windows"))) {
            params.put("excluded_window_axis", massDims.depth() >= massDims.width() ? "z" : "x");
        }

        // Window rows use the resolved host envelope, not stale model dimensions.
        int height = Math.max(2, massDims.height());
        int hostFloorHeight = ComponentParamParsers.intParam(mass.params(), "floor_height", "floorHeight");
        int hostFloorCount = ComponentParamParsers.intParam(mass.params(), "floor_count", "floorCount");
        if (hostFloorHeight > 0) params.put("floor_height", hostFloorHeight);
        if (hostFloorCount > 0) params.put("floor_count", hostFloorCount);
        // A height-only slit hint may exceed the clear wall band of a short
        // storey. Fit that hint; full explicit rectangles and locked dimensions
        // remain authoritative, as do locally authored tall openings above.
        if (openingWidth <= 0 && openingHeight >= hostFloorHeight && hostFloorHeight > 1 && hostFloorCount > 1
                && com.formacraft.common.generation.design.DesignProvenance.canMutateParam(plan,params,"window_height")) {
            params.put("window_height",hostFloorHeight-1);
            AssemblyCompileDiagnostics.addSoftWarning("WINDOW_HEIGHT_FITTED",
                    "窗高提示已适配楼层净空："+openingHeight+" -> "+(hostFloorHeight-1),
                    "FACADE_WINDOWS",paramIdentity(llmFacade));
        }

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

    private static boolean isFacadeStripOnHost(Component facade, Vec3i origin, Dimensions dims) {
        var p = facade.relativePosition();
        if (facade.dimensions().width() == 1 && facade.dimensions().depth() > 1)
            return Math.min(Math.abs(p.x()-origin.x()), Math.abs(p.x()-(origin.x()+dims.width()-1))) <= 1;
        return Math.min(Math.abs(p.z()-origin.z()), Math.abs(p.z()-(origin.z()+dims.depth()-1))) <= 1;
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
        if (llmEntrance.params() != null && llmEntrance.params().containsKey("stair_connection")) {
            return alignStairEntrance(llmEntrance, mass);
        }
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
                entranceDimensions(template.dimensions(), params, facing),
                mergeFeatureLists(template.features(), llmEntrance.features()),
                params
        );
    }

    /** Preserve a flight's actual entrance, including upper-floor Y and wall side. */
    private static Component alignStairEntrance(Component door, Component mass) {
        var params = new HashMap<String,Object>(door.params());
        var origin = resolveMassOrigin(mass);
        var rp = door.relativePosition();
        var dims = door.dimensions();
        if (origin == null || rp == null || dims == null) return door;
        String face = String.valueOf(params.getOrDefault("facing", "SOUTH"));
        boolean side = Set.of("EAST", "WEST").contains(face);
        int width = ComponentParamParsers.intParam(params, 3, "door_width");
        int height = ComponentParamParsers.intParam(params, 3, "door_height");
        int thickness = Math.max(1, ComponentParamParsers.intParam(mass.params(),1,"wall_thickness"));
        int center = side ? rp.z()+dims.depth()/2 : rp.x()+dims.width()/2;
        int start = center-(width+2)/2;
        int x = side ? ("EAST".equals(face) ? origin.x() : origin.x()+mass.dimensions().width()-thickness) : start;
        int z = side ? start : ("SOUTH".equals(face) ? origin.z() : origin.z()+mass.dimensions().depth()-thickness);
        params.put("anchor_mode","min_corner");
        params.put("canopy_depth",0);
        return new Component("ENTRANCE",door.slotId(),new Vec3i(x,rp.y(),z),
                new Dimensions(side ? thickness : width+2,side ? width+2 : thickness,height+1),
                door.features(),params);
    }

    private static Dimensions entranceDimensions(Dimensions dims, Map<String, Object> params, GlobalConstraints.Facing facing) {
        int width = ComponentParamParsers.intParam(params, 0, "door_width", "doorWidth");
        if (width <= 0 || dims == null) return dims;
        return facing == GlobalConstraints.Facing.EAST || facing == GlobalConstraints.Facing.WEST
                ? new Dimensions(dims.width(), Math.max(dims.depth(), width+1), dims.height())
                : new Dimensions(Math.max(dims.width(), width+1), dims.depth(), dims.height());
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
                "wall_thickness", "wallThickness",
                "corner_radius", "cornerRadius"
        )) {
            if (from.containsKey(key)) {
                to.put(key, from.get(key));
            }
        }
    }

    private static boolean hasRawOverhangKey(Map<String, Object> params) {
        if (params == null) {
            return false;
        }
        for (String key : ComponentParamParsers.keysFor("overhang")) {
            if (key != null && params.containsKey(key) && params.get(key) != null) {
                return true;
            }
        }
        return false;
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

    private static Component tagInferredSatellite(Component component) {
        if (component == null) {
            return null;
        }
        Map<String, Object> params = new HashMap<>();
        if (component.params() != null) {
            params.putAll(component.params());
        }
        com.formacraft.common.generation.design.DesignProvenance.markInferredParams(params);
        return new Component(
                component.componentType(),
                component.slotId(),
                component.relativePosition(),
                component.dimensions(),
                component.features(),
                params);
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
        Object v = component.params().get("_compiler_inferred");
        if (v == null) v = component.params().get("auto_inferred");
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
        Map<String, Object> params = new HashMap<>();
        if (base.params() != null) {
            params.putAll(base.params());
        }
        int roofHeight = com.formacraft.common.generation.component.util.RoofPitchGeometry.resolveRise(
                width, depth, 0, params);
        String roofType = getParamString(params, "roof_type", "roofType");
        if (roofType == null || roofType.isBlank()) {
            roofType = resolveDefaultRoofType(plan, base);
            if (roofType != null) {
                com.formacraft.common.generation.design.DesignProvenance.putIfAllowed(
                        plan, params, "roof_type", roofType);
                roofType = getParamString(params, "roof_type", "roofType");
                if (roofType == null || roofType.isBlank()) {
                    roofType = "gable";
                    params.put("roof_type", roofType);
                }
            }
        }
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
            roofParams.putAll(roof.params());
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
        return findBoundSatellite(mass, primary, inferred, wantedType) != null;
    }

    /**
     * Bind satellite to mass: explicit host_id wins; unbound satellites bind only when the nearest
     * MASS is unique (strictly closer than the next). Ambiguous unbound satellites set diagnostics.
     */
    private static Component findBoundSatellite(Component mass, List<Component> primary, List<Component> inferred, String wantedType) {
        var satellites = new ArrayList<Component>(primary);
        satellites.addAll(inferred);
        Object identity = mass.params() == null ? null : mass.params().get("component_id");
        for (var satellite : satellites) {
            if (satellite == null || !("ROOF".equals(wantedType) ? isRoofType(normalizeType(satellite.componentType()))
                    : wantedType.equals(normalizeType(satellite.componentType()))) || !slotKey(satellite).equals(slotKey(mass))) continue;
            if (satellite.params() != null && satellite.params().get("host_part_id") != null) continue;
            Object host = satellite.params() == null ? null : satellite.params().get("host_id");
            if (host != null) {
                if (host.equals(identity)) return satellite;
                continue;
            }
            HostPick pick = resolveUnboundHost(mass, primary, satellite);
            if (pick == HostPick.AMBIGUOUS) {
                AssemblyCompileDiagnostics.set(new CapabilityGap(
                        "E_HOST_BINDING_AMBIGUOUS",
                        "无 host_id 的卫星构件存在多个等距 MASS 候选：satellite="
                                + normalizeType(satellite.componentType())
                                + " slot=" + slotKey(satellite)
                                + "；请为卫星设置明确 host_id",
                        "components[].params.host_id",
                        List.of("为 ROOF/FACADE_WINDOWS/ENTRANCE 设置 host_id", "避免共享 slot 内多 MASS 无宿主绑定")
                ));
                return null;
            }
            if (pick == HostPick.MATCH) return satellite;
        }
        return null;
    }

    enum HostPick { NONE, MATCH, AMBIGUOUS }

    static HostPick resolveUnboundHost(Component mass, List<Component> primary, Component satellite) {
        Object identity = mass.params() == null ? null : mass.params().get("component_id");
        record Cand(Component mass, double dist2) {}
        var cands = new ArrayList<Cand>();
        for (var candidate : primary) {
            if (candidate == null || !isMassType(normalizeType(candidate.componentType()))
                    || !slotKey(candidate).equals(slotKey(mass)) || candidate.dimensions() == null
                    || candidate.relativePosition() == null) continue;
            if (satellite.relativePosition() == null) continue;
            // Use declared relativePosition (not ResolvedComponentGeometry) for stable host binding.
            Vec3i origin = candidate.relativePosition();
            double x = satellite.relativePosition().x(), z = satellite.relativePosition().z();
            if (ComponentFootprintUtil.isCornerAnchor(satellite.params()) && satellite.dimensions() != null) {
                x += (satellite.dimensions().width() - 1) / 2.0;
                z += (satellite.dimensions().depth() - 1) / 2.0;
            }
            double dx = x - origin.x() - (candidate.dimensions().width() - 1) / 2.0;
            double dz = z - origin.z() - (candidate.dimensions().depth() - 1) / 2.0;
            cands.add(new Cand(candidate, dx * dx + dz * dz));
        }
        if (cands.isEmpty()) return HostPick.NONE;
        cands.sort(java.util.Comparator.comparingDouble(Cand::dist2));
        Cand best = cands.get(0);
        if (cands.size() >= 2) {
            Cand second = cands.get(1);
            double bestDist = Math.sqrt(best.dist2());
            double secondDist = Math.sqrt(second.dist2());
            if (!(secondDist - bestDist > 1.0)) {
                return HostPick.AMBIGUOUS;
            }
        }
        boolean match = identity != null && best.mass().params() != null
                ? identity.equals(best.mass().params().get("component_id"))
                : java.util.Objects.equals(mass.relativePosition(), best.mass().relativePosition());
        return match ? HostPick.MATCH : HostPick.NONE;
    }

    /**
     * Collect and merge opening delegation from bound satellites of {@code wantedType}.
     * Empty faces on a satellite means {@link OpeningDelegation#full()}, not "no faces".
     */
    static OpeningDelegation collectOpeningDelegation(Component mass, List<Component> primary,
                                                       List<Component> inferred, String wantedType) {
        OpeningDelegation merged = OpeningDelegation.none();
        Integer floorHint = null;
        if (mass != null && mass.params() != null) {
            Object fc = mass.params().get("floor_count");
            if (fc == null) fc = mass.params().get("floorCount");
            if (fc instanceof Number n && n.intValue() > 0) floorHint = n.intValue();
            else if (fc != null) {
                try {
                    int v = Integer.parseInt(fc.toString().trim());
                    if (v > 0) floorHint = v;
                } catch (NumberFormatException ignored) { }
            }
        }
        Component bound = null;
        if (mass != null) {
            bound = findBoundSatellite(mass, primary, inferred, wantedType);
        }
        if (bound == null) return merged;
        merged = merged.merge(OpeningDelegation.fromSatelliteParams(bound.params(), floorHint));
        var all = new ArrayList<Component>(primary);
        all.addAll(inferred);
        Object identity = mass.params() == null ? null : mass.params().get("component_id");
        for (var s : all) {
            if (s == null || s == bound) continue;
            if (!wantedType.equals(normalizeType(s.componentType()))) continue;
            if (!slotKey(s).equals(slotKey(mass))) continue;
            Object host = s.params() == null ? null : s.params().get("host_id");
            if (host != null && host.equals(identity)) {
                merged = merged.merge(OpeningDelegation.fromSatelliteParams(s.params(), floorHint));
            }
        }
        return merged;
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

    private static Component suppressMassOpenings(Component base,
                                                   OpeningDelegation windows,
                                                   OpeningDelegation doors) {
        if (base == null) return null;
        OpeningDelegation w = windows == null ? OpeningDelegation.none() : windows;
        OpeningDelegation d = doors == null ? OpeningDelegation.none() : doors;
        if (!w.isActive() && !d.isActive()) return base;
        Map<String, Object> params = new HashMap<>();
        if (base.params() != null) {
            params.putAll(base.params());
        }
        applyOpeningDelegationParams(params, w, "suppress_windows", "suppress_window_faces", "suppress_window_regions");
        applyOpeningDelegationParams(params, d, "suppress_doors", "suppress_door_faces", "suppress_door_regions");
        return new Component(
                base.componentType(),
                base.slotId(),
                base.relativePosition(),
                base.dimensions(),
                base.features(),
                params
        );
    }

    private static void applyOpeningDelegationParams(Map<String, Object> params, OpeningDelegation delegation,
                                                     String fullKey, String facesKey, String regionsKey) {
        if (delegation == null || !delegation.isActive()) return;
        if (delegation.fullMass()) {
            params.put(fullKey, true);
            params.remove(facesKey);
            params.remove(regionsKey);
            return;
        }
        params.remove(fullKey);
        Set<String> faces = delegation.faceNames();
        if (!faces.isEmpty()) params.put(facesKey, new ArrayList<>(faces));
        else params.remove(facesKey);
        List<Map<String, Object>> regions = delegation.regionsAsMaps();
        if (!regions.isEmpty()) params.put(regionsKey, regions);
        else params.remove(regionsKey);
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
        if ("MASS_ANCILLARY".equals(normalizeType(component.componentType()))) {
            var params = new HashMap<String, Object>();
            if (component.params() != null) params.putAll(component.params());
            params.put("compiler_connector_body", true);
            params.putIfAbsent("wall_thickness", 1);
            params.put("plan_type", "none");
            // Unspecified-width covered links need one walkable cell between
            // their walls. Do not let a two-cell model default seal the link.
            Dimensions linkDims = component.dimensions();
            int linkWall = ComponentParamParsers.intParam(params, 1, "wall_thickness", "wallThickness");
            if (linkDims != null && linkWall > 0) {
                linkDims = new Dimensions(Math.max(linkDims.width(), 2*linkWall+1),
                        Math.max(linkDims.depth(), 2*linkWall+1), linkDims.height());
            }
            component = new Component("MASS_SECONDARY", component.slotId(), component.relativePosition(),
                    linkDims, component.features(), params);
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

    private record RoofOccluder(ComponentFootprintUtil.Bounds bounds,
            com.formacraft.common.generation.component.util.ResolvedFootprint footprint) {}

    private static List<RoofOccluder> roofOccluders(Component roof, List<Component> components, Map<String, Slot> slotMap) {
        String host = getParamString(roof.params(), "host_id");
        if (host == null) return List.of();
        return components.stream().filter(c ->
                com.formacraft.common.generation.component.util.MassComponentKinds.isCirculationMass(c)
                        && !host.equals(getParamString(c.params(), "component_id"))
                        && (!host.equals(getParamString(c.params(), "host_id"))
                            || "TOWER".equalsIgnoreCase(c.componentType())))
                .map(c -> inSlotFrame(c, roof.slotId(), slotMap))
                .map(c -> new RoofOccluder(ComponentFootprintUtil.bounds(c),
                        com.formacraft.common.generation.component.util.ResolvedBuildingGeometry.resolve(c).footprint())).toList();
    }

    private static void trimCollidingRoofOverhang(Component roof, List<Component> components, Map<String, Slot> slotMap, BlockPos offset,
                                                  PatchOccupancyIndex occupancy, List<BlockPatch> patches) {
        String host = getParamString(roof.params(), "host_id");
        if (host == null) return;
        Component body = components.stream().filter(c -> host.equals(getParamString(c.params(), "component_id")))
                .findFirst().orElse(null);
        if (body == null || !java.util.Objects.equals(body.slotId(), roof.slotId())) return;
        int before = patches.size();
        var foreignBodies = roofOccluders(roof, components, slotMap);
        patches.removeIf(p -> {
            for (var other : foreignBodies) {
                var b = other.bounds();
                int x=p.dx()-offset.getX(), y=p.dy()-offset.getY(), z=p.dz()-offset.getZ();
                if (b != null && x>=b.minX() && x<b.maxX() && y>=b.minY() && y<b.maxY()
                        && z>=b.minZ() && z<b.maxZ() && other.footprint().contains(x-b.minX(),z-b.minZ())) return true;
            }
            var existing = occupancy.placeAt(new BlockPos(p.dx(), p.dy(), p.dz()));
            return existing != null && existing.owner() != null
                    && !host.equals(getParamString(existing.owner().params(), "component_id"))
                    && !host.equals(getParamString(existing.owner().params(), "host_id"));
        });
        if (before != patches.size()) AssemblyCompileDiagnostics.addSoftWarning("ROOF_OVERHANG_TRIMMED",
                "屋檐已避让相邻建筑，跳过 " + (before - patches.size()) + " 条操作", roof.componentType(), paramIdentity(roof));
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
