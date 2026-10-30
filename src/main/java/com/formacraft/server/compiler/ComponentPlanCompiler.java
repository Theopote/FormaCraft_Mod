package com.formacraft.server.compiler;

import com.formacraft.common.generation.component.util.GeneratedSurfaceCapture;
import com.formacraft.common.generation.component.util.ComponentFootprintUtil;

import com.formacraft.common.compiler.postprocess.PostProcessContext;
import com.formacraft.common.compiler.postprocess.PostProcessPipeline;
import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.server.assembly.AssemblyCirculationConstraints;
import com.formacraft.server.assembly.AssemblyCompileDiagnostics;
import com.formacraft.common.llm.dto.CapabilityGap;
import com.formacraft.server.generation.component.adaptor.UnifiedGeneratorRouter;
import com.formacraft.common.llm.NonClassicalEnrichmentGuard;
import com.formacraft.common.llm.dto.Component;
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
        plan = FootprintCombinationRepair.apply(plan);
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
            patchOccupancy = ComponentEmitPass.compileComponents(plan, world, globalAnchor, allowAssemblyFacade, components, assemblyFacadeSlots,
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
            if (!ComponentEmitPass.validateBridgeWalkability(components, result, slotMap)) {
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
        if (!DeclaredInteriorOpeningCompiler.validate(components, result, slotMap)) return List.of();

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
            for (var other : ComponentEmitPass.roofOccluders(roof, components, slotMap)) {
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
            ComponentEmitPass.maybeWarnIrregularGableSeal(roof, semantic);
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
    static Slot defaultSlot(LlmPlan plan) {
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

    static Vec3i slotAnchorOrZero(Map<String, Slot> slotMap, String slotId, Vec3i zero) {
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
    private static String paramIdentity(Component c) {
        return ComponentPlanTypes.paramIdentity(c);
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



    // --- PR4 forwarders (tests / validators) ---
    static boolean isSoftGenerationFailure(Component c) {
        return ComponentEmitPass.isSoftGenerationFailure(c);
    }

    static int compilationPriority(Component c) {
        return ComponentEmitPass.compilationPriority(c);
    }

    static String geometryConstraintCode(String message) {
        return ComponentEmitPass.geometryConstraintCode(message);
    }

    static void maybeWarnIrregularGableSeal(Component roof, SemanticComponent semantic) {
        ComponentEmitPass.maybeWarnIrregularGableSeal(roof, semantic);
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


    static Vec3i resolveMassOrigin(Component base) {
        return ComponentFootprintUtil.resolveMinCornerOrigin(base);
    }


    private static Boolean getParamBoolean(Map<String, Object> params, String... keys) {
        return ComponentPlanTypes.getParamBoolean(params, keys);
    }

    private static String getParamString(Map<String, Object> params, String... keys) {
        return ComponentPlanTypes.getParamString(params, keys);
    }
}
