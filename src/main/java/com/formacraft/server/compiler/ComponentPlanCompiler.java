package com.formacraft.server.compiler;

import com.formacraft.common.generation.component.util.GeneratedSurfaceCapture;
import com.formacraft.common.generation.component.util.ComponentFootprintUtil;

import com.formacraft.common.generation.component.util.ComponentParamParsers;
import com.formacraft.common.compiler.postprocess.PostProcessContext;
import com.formacraft.common.compiler.postprocess.PostProcessPipeline;
import com.formacraft.common.compiler.semantic.SemanticComponent;
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
        var authoredIssue = DesignValidatorSuite.checkAuthored(plan);
        if (authoredIssue.isPresent()) {
            AssemblyCompileDiagnostics.set(authoredIssue.get());
            return List.of();
        }

        // 索引 slots（便于快速查找）
        Map<String, Slot> slotMap = indexSlots(plan);
        boolean allowAssemblyFacade = world != null && globalAnchor != null;
        ComponentSatelliteInference.PreparedComponents prepared = ComponentSatelliteInference.prepareComponents(plan, slotMap, allowAssemblyFacade);
        if (AssemblyCompileDiagnostics.hasGap()) {
            return List.of();
        }
        List<Component> components = prepared.components();
        Set<String> assemblyFacadeSlots = prepared.assemblyFacadeSlots();
        var preparedIssue = DesignValidatorSuite.checkPrepared(plan, components);
        if (preparedIssue.isPresent()) {
            AssemblyCompileDiagnostics.set(preparedIssue.get());
            return List.of();
        }

        if (components.isEmpty()) {
            FormacraftMod.LOGGER.info("ComponentPlanCompiler: no components to compile");
            return result;
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

        if (!StairChainCompiler.validateConnectivity(components, result, slotMap)) return List.of();

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
                String writerType = record.writerType() == null ? "" : record.writerType();
                // Chimneys (and hosted roof penetrations) own the column after overwrite;
                // mark them occluded so gable audit does not false-fail on missing cells.
                boolean roofPenetration = "CHIMNEY".equals(writerType) || "DECOR_DETAIL".equals(writerType);
                if (!(com.formacraft.common.generation.component.util.MassComponentKinds.isCirculationMass(owner)
                        || writerType.startsWith("ROOF") || roofPenetration)) continue;
                if (!roofPenetration && (java.util.Objects.equals(roofHost, getParamString(owner.params(), "component_id"))
                        || java.util.Objects.equals(roofHost, getParamString(owner.params(), "host_id")))) continue;
                if (roofPenetration) {
                    // Authored chimneys often bind host_id to the ROOF component_id, not the mass.
                    String penetrationHost = getParamString(owner.params(), "host_id");
                    if (penetrationHost != null
                            && !java.util.Objects.equals(roofHost, penetrationHost)
                            && !java.util.Objects.equals(roofIdentity, penetrationHost)) continue;
                }
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
    static Map<String, Slot> indexSlots(LlmPlan plan) {
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
                        boolean nativeFailure = "required_component_empty".equals(diagCode)
                                || routed.pipelineStep() == com.formacraft.server.generation.component.adaptor.ComponentPipelineStepId.COMPONENT_REGISTRY;
                        boolean roofTypeUnsupported = "generator_exception".equals(diagCode)
                                && detail != null && detail.contains("roof_type_unsupported");
                        String gapCode = envelopeConflict
                                ? "E_COMPONENT_REQUEST_CONFLICT"
                                : authorityConflict
                                ? "E_GENERATION_AUTHORITY_CONFLICT"
                                : roofTypeUnsupported
                                ? "E_ROOF_TYPE_UNSUPPORTED"
                                : nativeFailure ? "E_COMPONENT_GENERATION_FAILED" : "E_COMPONENT_LIBRARY_FAILED";
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
                                : (nativeFailure ? "原生建筑组件生成失败：type=" : "捕获/库构件硬失败：type=") + c.componentType()
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
                                        : nativeFailure ? List.of("检查原生门窗尺寸、材质与宿主墙面", "不需要添加构件库资产")
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
                                shiftedPatches.add(patch.translated(slotAnchor.x(), slotAnchor.y(), slotAnchor.z()));
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
                    if ((plan.layout() != null && "COMPOUND".equalsIgnoreCase(String.valueOf(plan.layout().skeletonType())))
                            || com.formacraft.common.generation.component.util.OpenFramePolicy.isOpen(c.params())) {
                        int shared = patchOccupancy.trimCompoundMassJoin(c, shiftedPatches);
                        shared += patchOccupancy.trimWallTowerJoins(c, shiftedPatches);
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

                    // Phase 1 building-edit: stamp roof provenance for part-index registration.
                    if (isRoofType(normalizedType)) {
                        var roofSource = new com.formacraft.common.build.GeometrySource(
                                normalizedType,
                                c.slotId(),
                                null,
                                paramIdentity(c),
                                null,
                                "ROOF_COMPONENT");
                        for (int i = 0; i < shiftedPatches.size(); i++) {
                            BlockPatch p = shiftedPatches.get(i);
                            if (p == null) continue;
                            if (p.source() == null
                                    || !com.formacraft.server.edit.BuildingPartIndex
                                    .isRoofProvenance(p.source(), normalizedType)) {
                                shiftedPatches.set(i, p.withSource(roofSource));
                            }
                        }
                    }

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
            if (deckWorld != null) {
                FormacraftMod.LOGGER.warn("Bridge blocked: deck={} floor={} head1={} head2={} host={} target={}", deckWorld,
                        occ.profileAt(deckWorld), occ.profileAt(new Vec3i(deckWorld.x(), deckWorld.y()+1, deckWorld.z())),
                        occ.profileAt(new Vec3i(deckWorld.x(), deckWorld.y()+2, deckWorld.z())),
                        com.formacraft.common.generation.component.util.BridgeWalkability.storeyGoalsPreferringInterior(occ, host.get(), deckWorld.y()-hostOff.y(), hostOff),
                        com.formacraft.common.generation.component.util.BridgeWalkability.storeyGoalsPreferringInterior(occ, target.get(), deckWorld.y()-targetOff.y(), targetOff));
            }
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
        return ComponentPlanTypes.paramIdentity(c);
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
        return ComponentPlanTypes.isStructureFloorType(type);
    }

    static void realignSatellitesToMass(
            List<Component> components,
            LlmPlan plan,
            Map<String, Slot> slotMap,
            Set<String> assemblyPrimarySlots
    ) {
        ComponentSatelliteAligner.realignSatellitesToMass(components, plan, slotMap, assemblyPrimarySlots);
    }

    static Component alignFacadeToMass(
            Component llmFacade,
            Component mass,
            LlmPlan plan,
            GlobalConstraints.Facing facing
    ) {
        return ComponentSatelliteAligner.alignFacadeToMass(llmFacade, mass, plan, facing);
    }

    static Component alignEntranceToMass(
            Component llmEntrance,
            Component mass,
            LlmPlan plan,
            GlobalConstraints.Facing facing
    ) {
        return ComponentSatelliteAligner.alignEntranceToMass(llmEntrance, mass, plan, facing);
    }

    static Component alignStairEntrance(Component door, Component mass, LlmPlan plan) {
        return ComponentSatelliteAligner.alignStairEntrance(door, mass, plan);
    }


    // --- PR2 forwarders (tests / Aligner compatibility) ---
    static ComponentSatelliteInference.PreparedComponents prepareComponents(
            LlmPlan plan, Map<String, Slot> slotMap, boolean allowAssemblyFacade) {
        return ComponentSatelliteInference.prepareComponents(plan, slotMap, allowAssemblyFacade);
    }

    static List<Component> applyTypologyExclusiveFilter(List<Component> components) {
        return ComponentSatelliteInference.applyTypologyExclusiveFilter(components);
    }

    static boolean isResolvedPartRoof(Component roof, List<Component> components, LlmPlan plan) {
        return ComponentSatelliteInference.isResolvedPartRoof(roof, components, plan);
    }

    static Component normalizeComponent(Component component) {
        return ComponentSatelliteInference.normalizeComponent(component);
    }

    static Component makeFacadeComponent(Component base, String slotId) {
        return ComponentSatelliteInference.makeFacadeComponent(base, slotId);
    }

    static Component makeEntranceComponent(LlmPlan plan, Component base, String slotId, GlobalConstraints.Facing facing) {
        return ComponentSatelliteInference.makeEntranceComponent(plan, base, slotId, facing);
    }

    static Component makeRoofComponent(LlmPlan plan, Component base, String slotId) {
        return ComponentSatelliteInference.makeRoofComponent(plan, base, slotId);
    }

    static Component makeCrownComponent(LlmPlan plan, Component base, Component roof, String slotId) {
        return ComponentSatelliteInference.makeCrownComponent(plan, base, roof, slotId);
    }

    static Component applyRoofGrammar(LlmPlan plan, Component roof) {
        return ComponentSatelliteInference.applyRoofGrammar(plan, roof);
    }

    static Component findRoofForSlot(List<Component> primary, List<Component> inferred, String slotKey) {
        return ComponentSatelliteInference.findRoofForSlot(primary, inferred, slotKey);
    }

    static ComponentSatelliteInference.HostPick resolveUnboundHost(Component mass, List<Component> primary, Component satellite) {
        return ComponentSatelliteInference.resolveUnboundHost(mass, primary, satellite);
    }

    static OpeningDelegation collectOpeningDelegation(Component mass, List<Component> primary,
                                                       List<Component> inferred, String wantedType) {
        return ComponentSatelliteInference.collectOpeningDelegation(mass, primary, inferred, wantedType);
    }

    static Component findBoundSatellite(Component mass, List<Component> primary, List<Component> inferred, String wantedType) {
        return ComponentSatelliteInference.findBoundSatellite(mass, primary, inferred, wantedType);
    }

    static String normalizeComponentType(String value, boolean allowUnknown) {
        return ComponentPlanTypes.normalizeComponentType(value, allowUnknown);
    }

    static String inferFallbackType(String type) {
        return ComponentPlanTypes.inferFallbackType(type);
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
        return ComponentPlanTypes.paramString(params, keys);
    }



    private static String slotKey(Component c) {
        return ComponentPlanTypes.slotKey(c);
    }

    private static String normalizeType(String value) {
        return ComponentPlanTypes.normalizeType(value);
    }

    private static boolean isMassType(String type) {
        return ComponentPlanTypes.isMassType(type);
    }

    private static boolean isRoofType(String type) {
        return ComponentPlanTypes.isRoofType(type);
    }

    private static boolean isCrownType(String type) {
        return ComponentPlanTypes.isCrownType(type);
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

    static Vec3i resolveMassOrigin(Component base) {
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
        return ComponentPlanTypes.getParamBoolean(params, keys);
    }

    private static String getParamString(Map<String, Object> params, String... keys) {
        return ComponentPlanTypes.getParamString(params, keys);
    }
}
