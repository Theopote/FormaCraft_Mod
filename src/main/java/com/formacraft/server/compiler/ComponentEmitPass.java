package com.formacraft.server.compiler;

import com.formacraft.FormacraftMod;
import com.formacraft.common.compiler.postprocess.PostProcessContext;
import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.generation.component.util.ComponentFootprintUtil;
import com.formacraft.common.generation.component.util.GeneratedSurfaceCapture;
import com.formacraft.common.llm.dto.CapabilityGap;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.Slot;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.server.assembly.AssemblyCirculationConstraints;
import com.formacraft.server.assembly.AssemblyCompileDiagnostics;
import com.formacraft.server.generation.GenerationHub;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Component emission pass: ordered generation, occupancy commit, bridge walkability,
 * and roof overhang trim.
 * <p>
 * Extracted from {@link ComponentPlanCompiler} (PR4). Plan orchestration / postprocess
 * remain on the compiler.
 */
final class ComponentEmitPass {
    private ComponentEmitPass() {}

    static PatchOccupancyIndex compileComponents(
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
            String normalizedType = ComponentPlanTypes.normalizeType(c.componentType());
            if (ComponentPlanTypes.isRoofType(normalizedType) && com.formacraft.common.style.ExplicitDesignPolicy.roofDisabled(c)) continue;

            Slot slot = slotMap.get(c.slotId());
            if (slot == null) {
                slot = ComponentPlanCompiler.defaultSlot(plan);
                FormacraftMod.LOGGER.debug("ComponentPlanCompiler: component {} has no slot, using default slot", c.componentType());
            }
            String slotKey = ComponentPlanTypes.slotKey(c);

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
                var roofAttachment = FlatRoofCoverageValidator.attachmentMismatch(semantic, components, slotMap, ComponentPlanCompiler.defaultSlot(plan));
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
                                    + " id=" + ComponentPlanTypes.paramIdentity(c)
                                    + " detail=" + detail
                                : authorityConflict
                                ? "生成权威冲突：type=" + c.componentType()
                                    + " id=" + ComponentPlanTypes.paramIdentity(c)
                                    + " detail=" + detail
                                : capturedLibraryMiss
                                ? "捕获构件未产生几何：type=" + c.componentType()
                                    + " id=" + ComponentPlanTypes.paramIdentity(c)
                                    + " detail=" + detail
                                : (nativeFailure ? "原生建筑组件生成失败：type=" : "捕获/库构件硬失败：type=") + c.componentType()
                                    + " id=" + ComponentPlanTypes.paramIdentity(c)
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
                                    ComponentPlanTypes.paramIdentity(c)
                            );
                        }
                    }
                    patches = routed != null && routed.commitAllowed() ? routed.patches() : List.of();
                }
                var missingSurfaces = GeneratedSurfaceCapture.missing(surfaceCells, patches);
                if (!missingSurfaces.isEmpty()) {
                    var first = missingSurfaces.getFirst();
                    String surfaceMsg = "生成器未提供声明的建筑表面：" + normalizedType + " " + first.role()
                            + " at component " + first.position().toShortString()
                            + "; missing=" + missingSurfaces.size();
                    // Opening / decor incompleteness must not abort the whole compound
                    // (e.g. FACADE_WINDOWS declaring WINDOW cells that the emitter skipped).
                    if (isSoftSurfaceIncomplete(normalizedType)) {
                        FormacraftMod.LOGGER.warn(
                                "ComponentPlanCompiler: soft surface incomplete type={} id={} missing={}",
                                normalizedType, ComponentPlanTypes.paramIdentity(c), missingSurfaces.size());
                        AssemblyCompileDiagnostics.addSoftWarning(
                                "E_SURFACE_GENERATION_INCOMPLETE",
                                surfaceMsg,
                                c.componentType(),
                                ComponentPlanTypes.paramIdentity(c));
                    } else {
                        AssemblyCompileDiagnostics.set(new CapabilityGap("E_SURFACE_GENERATION_INCOMPLETE",
                                surfaceMsg,
                                "components[]", List.of("Fix surface material or generator emission; keep authored openings explicitly marked.")));
                        return patchOccupancy;
                    }
                }
                if (!patches.isEmpty()) {
                    if (allowAssemblyFacade && globalAnchor != null && ComponentPlanTypes.isMassType(normalizedType)
                            && assemblyFacadeSlots.contains(ComponentPlanTypes.getParamString(c.params(), "component_id"))) {
                        List<BlockPatch> facade = AssemblyFacadeCompiler.generateAssemblyFacadePatches(plan, semantic, slot, globalAnchor, world);
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
                                    c.componentType(), ComponentPlanTypes.paramIdentity(c));
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
                        String host = ComponentPlanTypes.getParamString(c.params(), "host_id");
                        String target = ComponentPlanTypes.getParamString(c.params(), "target_host_id", "targetHostId");
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
                                            .bridgePortalRemovals(c, ComponentPlanCompiler.inSlotFrame(m, c.slotId(), slotMap))));
                            targetMass.ifPresent(m -> addPortals.accept(
                                    com.formacraft.common.generation.component.util.HostedPlateOccupancy
                                            .bridgePortalRemovals(c, ComponentPlanCompiler.inSlotFrame(m, c.slotId(), slotMap))));
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
                            String ownerId = ComponentPlanTypes.getParamString(prev.owner().params(), "component_id");
                            String ownerHost = ComponentPlanTypes.getParamString(prev.owner().params(), "host_id");
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
                                c.componentType(), ComponentPlanTypes.paramIdentity(c));
                    }
                    if ((plan.layout() != null && "COMPOUND".equalsIgnoreCase(String.valueOf(plan.layout().skeletonType())))
                            || com.formacraft.common.generation.component.util.OpenFramePolicy.isOpen(c.params())) {
                        int shared = patchOccupancy.trimCompoundMassJoin(c, shiftedPatches);
                        shared += patchOccupancy.trimWallTowerJoins(c, shiftedPatches);
                        if (shared > 0) AssemblyCompileDiagnostics.addSoftWarning("MASS_JOIN_CLIPPED",
                                "相邻体块交界处保留已有外壳，跳过冲突操作 " + shared, c.componentType(), ComponentPlanTypes.paramIdentity(c));
                    }
                    int incompatible = patchOccupancy.trimIncompatibleAttachment(c, shiftedPatches, connectorAccess);
                    if (incompatible > 0) AssemblyCompileDiagnostics.addSoftWarning("ATTACHMENT_JOIN_CLIPPED",
                            "附属构件已保留已有结构，跳过冲突操作 " + incompatible,
                            c.componentType(), ComponentPlanTypes.paramIdentity(c));
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
                    // Avoid gravity/falling blocks (gravel, sand, concrete powder) collapsing after place.
                    for (int gi = 0; gi < shiftedPatches.size(); gi++) {
                        shiftedPatches.set(gi, com.formacraft.common.palette.dynamic.GravityBlockPolicy
                                .stabilize(shiftedPatches.get(gi)));
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
                                        + " id=" + ComponentPlanTypes.paramIdentity(c) + " host=" + ComponentPlanTypes.getParamString(c.params(), "host_id")
                                        + " previous_id=" + (previous == null || previous.owner() == null ? "?" : ComponentPlanTypes.paramIdentity(previous.owner()))
                                        + " (" + oc.reason() + ")",
                                "components[]",
                                List.of("为开口类构件授权覆盖", "或设置 allow_patch_overwrite=true",
                                        "禁止无关构件互相覆盖同坐标")));
                        return patchOccupancy;
                    }
                    patchOccupancy.commit(c, shiftedPatches, connectorAccess);

                    // Phase 1 building-edit: stamp roof provenance for part-index registration.
                    if (ComponentPlanTypes.isRoofType(normalizedType)) {
                        var roofSource = new com.formacraft.common.build.GeometrySource(
                                normalizedType,
                                c.slotId(),
                                null,
                                ComponentPlanTypes.paramIdentity(c),
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
                                openingExpectations.put(pos,new OpeningMaterialAudit.Expectation(ComponentPlanTypes.paramIdentity(c),
                                        GeneratedSurfaceCapture.Role.DOOR_OPENING,null));
                                generatedSurfaces.remove(pos);
                            } else openingExpectations.remove(pos);
                        }
                    }
                    OpeningMaterialAudit.capture(openingExpectations, c, surfaceCells, flightOffset);
                    for (var cell : surfaceCells) if (cell.requiresBlock())
                        generatedSurfaces.add(cell.shift(flightOffset).position());
                    circulation.addAll(shiftedFlights);
                    if (ComponentPlanTypes.isMassType(normalizedType) && !(c.params() != null
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
                            c.componentType(), ComponentPlanTypes.paramIdentity(c), c.slotId(), e.getMessage(), e);
                    AssemblyCompileDiagnostics.addSoftWarning(
                            "DECOR_COMPONENT_SKIPPED",
                            "可选装饰缺失：type=" + c.componentType()
                                    + " id=" + ComponentPlanTypes.paramIdentity(c)
                                    + " slot=" + c.slotId()
                                    + "；主体结构完整，部分装饰未生成"
                                    + (e.getMessage() == null ? "" : "（" + e.getMessage() + "）"),
                            c.componentType(),
                            ComponentPlanTypes.paramIdentity(c)
                    );
                } else {
                    FormacraftMod.LOGGER.error("ComponentPlanCompiler: error generating component {}: {}",
                            c.componentType(), e.getMessage(), e);
                    String gapCode = geometryConstraintCode(e.getMessage());
                    AssemblyCompileDiagnostics.set(new CapabilityGap(
                            gapCode,
                            "关键构件生成失败：type=" + c.componentType()
                                    + " id=" + ComponentPlanTypes.paramIdentity(c)
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
        String type = ComponentPlanTypes.normalizeType(c.componentType());
        return "DECOR_DETAIL".equals(type) || type.startsWith("DECOR");
    }

    /** Openings may partially miss declared surfaces without aborting the whole plan. */
    static boolean isSoftSurfaceIncomplete(String normalizedType) {
        if (normalizedType == null) return false;
        return "FACADE_WINDOWS".equals(normalizedType)
                || "FACADE".equals(normalizedType)
                || "FACADE_OPENING".equals(normalizedType)
                || "WINDOW".equals(normalizedType)
                || "DOOR".equals(normalizedType)
                || "GATE".equals(normalizedType)
                || "DECOR_DETAIL".equals(normalizedType)
                || normalizedType.startsWith("DECOR");
    }

    /**
     * After all patches commit, require bridge decks to BFS-reach both mass interiors
     * through portal clearances. Distinguishes path blocked, BFS search limit, and region scan limit.
     */
    static boolean validateBridgeWalkability(
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
            String hostId = ComponentPlanTypes.getParamString(plate.params(), "host_id", "hostId");
            String targetId = ComponentPlanTypes.getParamString(plate.params(), "target_host_id", "targetHostId");
            var host = HostedPlateDesignValidator.findBridgeEndpoint(hostId, components);
            var target = HostedPlateDesignValidator.findBridgeEndpoint(targetId, components);
            if (host.isEmpty() || target.isEmpty()) {
                continue;
            }
            Vec3i plateOff = ComponentPlanCompiler.slotAnchorOrZero(slotMap, plate.slotId(), zero);
            Vec3i hostOff = ComponentPlanCompiler.slotAnchorOrZero(slotMap, host.get().slotId(), zero);
            Vec3i targetOff = ComponentPlanCompiler.slotAnchorOrZero(slotMap, target.get().slotId(), zero);
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


    static int compilationPriority(Component c) {
        if (c == null) return 6;
        if (com.formacraft.server.generation.component.impl.StraightStairComponentGenerator.accepts(c)
                || com.formacraft.server.generation.component.impl.AssemblyPatchGenerator.hasCirculation(c)) return 7;
        String type = ComponentPlanTypes.normalizeType(c.componentType());
        if ("MASS_SECONDARY".equals(type) && c.params() != null
                && "plate".equalsIgnoreCase(String.valueOf(c.params().get("extrude_mode")))) return 1;
        if (ComponentPlanTypes.isMassType(type) || "TOWER".equals(type) || "MASS_ANCILLARY".equals(type)) return 0;
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
        String aspect = ComponentPlanTypes.paramString(params, "window_aspect", "windowAspect");
        String rhythm = ComponentPlanTypes.paramString(params, "rhythm_preset", "rhythmPreset");
        if (rhythm == null) {
            rhythm = ComponentPlanTypes.paramString(params, "rhythm");
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


    record RoofOccluder(ComponentFootprintUtil.Bounds bounds,
            com.formacraft.common.generation.component.util.ResolvedFootprint footprint) {}

    static List<RoofOccluder> roofOccluders(Component roof, List<Component> components, Map<String, Slot> slotMap) {
        String host = ComponentPlanTypes.getParamString(roof.params(), "host_id");
        if (host == null) return List.of();
        return components.stream().filter(c ->
                com.formacraft.common.generation.component.util.MassComponentKinds.isCirculationMass(c)
                        && !host.equals(ComponentPlanTypes.getParamString(c.params(), "component_id"))
                        && (!host.equals(ComponentPlanTypes.getParamString(c.params(), "host_id"))
                            || "TOWER".equalsIgnoreCase(c.componentType())))
                .map(c -> ComponentPlanCompiler.inSlotFrame(c, roof.slotId(), slotMap))
                .map(c -> new RoofOccluder(ComponentFootprintUtil.bounds(c),
                        com.formacraft.common.generation.component.util.ResolvedBuildingGeometry.resolve(c).footprint())).toList();
    }

    private static void trimCollidingRoofOverhang(Component roof, List<Component> components, Map<String, Slot> slotMap, BlockPos offset,
                                                  PatchOccupancyIndex occupancy, List<BlockPatch> patches) {
        String host = ComponentPlanTypes.getParamString(roof.params(), "host_id");
        if (host == null) return;
        Component body = components.stream().filter(c -> host.equals(ComponentPlanTypes.getParamString(c.params(), "component_id")))
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
                    && !host.equals(ComponentPlanTypes.getParamString(existing.owner().params(), "component_id"))
                    && !host.equals(ComponentPlanTypes.getParamString(existing.owner().params(), "host_id"));
        });
        if (before != patches.size()) AssemblyCompileDiagnostics.addSoftWarning("ROOF_OVERHANG_TRIMMED",
                "屋檐已避让相邻建筑，跳过 " + (before - patches.size()) + " 条操作", roof.componentType(), ComponentPlanTypes.paramIdentity(roof));
    }

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
        String type = ComponentPlanTypes.getParamString(params, "roof_type", "roofType");
        if (type == null) return;
        String lower = type.trim().toLowerCase(java.util.Locale.ROOT);
        if (!Set.of("gable", "gabled", "double_gable", "xuanshan").contains(lower)) return;
        if ("false".equalsIgnoreCase(ComponentPlanTypes.getParamString(params, "gable_walls"))) return;
        int w = Math.max(1, roof.dimensions().width());
        int d = Math.max(1, roof.dimensions().depth());
        var mask = com.formacraft.common.generation.component.util.ComponentFootprintMask.from(semantic, params, w, d);
        if (mask.isFullyRectangular()) return;
        AssemblyCompileDiagnostics.addSoftWarning(
                "ROOF_GABLE_SEAL_SKIPPED",
                "不规则占地跳过矩形山墙封口：id=" + ComponentPlanTypes.paramIdentity(roof)
                        + " footprint=" + w + "x" + d
                        + " roof_type=" + type
                        + " reason=irregular_footprint_gable_unsealed",
                "ROOF",
                ComponentPlanTypes.paramIdentity(roof)
        );
    }


}
