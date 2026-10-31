package com.formacraft.server.compiler;

import com.formacraft.FormacraftMod;
import com.formacraft.common.alignment.AlignmentContractEnforcer;
import com.formacraft.common.alignment.BayGridRhythmPlanner;
import com.formacraft.common.generation.component.util.ComponentCrownDecorator;
import com.formacraft.common.generation.component.util.ComponentFacadeRhythmPlanner;
import com.formacraft.common.generation.component.util.ComponentFoundationEnforcer;
import com.formacraft.common.generation.component.util.ComponentFootprintUtil;
import com.formacraft.common.generation.component.util.ComponentParamParsers;
import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.llm.NonClassicalEnrichmentGuard;
import com.formacraft.common.llm.dto.CapabilityGap;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.GlobalConstraints;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.Slot;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.proportion.CrownGrammarResolver;
import com.formacraft.common.proportion.OpeningGrammarResolver;
import com.formacraft.common.proportion.RoofGrammarResolver;
import com.formacraft.common.style.StyleIntentResolver;
import com.formacraft.common.typology.TypologyComponentRouter;
import com.formacraft.server.assembly.AssemblyCompileDiagnostics;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Plan preparation: normalize components, infer satellites (roof/facade/entrance/crown),
 * opening delegation, and host binding.
 * <p>
 * Extracted from {@link ComponentPlanCompiler} (PR2). Assembly-facade patch emission remains
 * on the compiler until PR3.
 */
final class ComponentSatelliteInference {
    private ComponentSatelliteInference() {}

    record PreparedComponents(List<Component> components, Set<String> assemblyFacadeSlots) {}

    static PreparedComponents prepareComponents(LlmPlan plan, Map<String, Slot> slotMap, boolean allowAssemblyFacade) {
        List<Component> normalized = new ArrayList<>();
        if (plan.components() != null) {
            normalized.addAll(plan.components());
        }
        if (normalized.isEmpty()) {
            return new PreparedComponents(normalized, Set.of());
        }

        normalized = com.formacraft.server.dsl.FbdPlanLowering.lower(normalized);
        if (AssemblyCompileDiagnostics.hasGap()) return new PreparedComponents(List.of(), Set.of());

        List<Component> components = new ArrayList<>();
        Set<String> massSlots = new HashSet<>();
        for (Component c : normalized) {
            Component normalizedComponent = normalizeComponent(NativeBuildingComponentPolicy.apply(c, plan));
            if (normalizedComponent == null) {
                continue;
            }
            if (normalizedComponent.slotId() == null && ComponentPlanTypes.getParamString(normalizedComponent.params(), "slot_id") != null) {
                normalizedComponent = new Component(normalizedComponent.componentType(), ComponentPlanTypes.getParamString(normalizedComponent.params(), "slot_id"),
                        normalizedComponent.relativePosition(), normalizedComponent.dimensions(), normalizedComponent.features(), normalizedComponent.params());
            }
            if (normalizedComponent.params() != null && normalizedComponent.params().containsKey("compiler_suppressed_roof")) {
                var params = new HashMap<String, Object>(normalizedComponent.params());
                params.remove("compiler_suppressed_roof");
                normalizedComponent = new Component(normalizedComponent.componentType(), normalizedComponent.slotId(),
                        normalizedComponent.relativePosition(), normalizedComponent.dimensions(), normalizedComponent.features(), params);
            }
            normalizedComponent = reconcileDelegatedRoof(plan, normalizedComponent, normalized);
            if (ComponentPlanTypes.isMassType(ComponentPlanTypes.normalizeType(normalizedComponent.componentType()))
                    && ComponentParamParsers.intOrNull(normalizedComponent.params(), "wall_thickness", "wallThickness") == null) {
                var params = new HashMap<String, Object>(normalizedComponent.params());
                params.put("wall_thickness", 1);
                normalizedComponent = new Component(normalizedComponent.componentType(), normalizedComponent.slotId(),
                        normalizedComponent.relativePosition(), normalizedComponent.dimensions(), normalizedComponent.features(), params);
            }
            normalizedComponent = StyleIntentResolver.apply(plan, normalizedComponent);
            normalizedComponent = OpeningGrammarResolver.apply(plan, normalizedComponent);
            normalizedComponent = com.formacraft.common.generation.component.util.ResolvedComponentGeometry.normalizeBody(normalizedComponent);
            String type = ComponentPlanTypes.normalizeType(normalizedComponent.componentType());
            if (com.formacraft.common.style.ExplicitDesignPolicy.noComplexDecor(plan, normalizedComponent.params())) {
                var params = new HashMap<String, Object>();
                if (normalizedComponent.params() != null) params.putAll(normalizedComponent.params());
                params.put("no_complex_decor", true);
                normalizedComponent = new Component(normalizedComponent.componentType(), normalizedComponent.slotId(),
                        normalizedComponent.relativePosition(), normalizedComponent.dimensions(), normalizedComponent.features(), params);
            }
            if (ComponentPlanTypes.isMassType(type) && ComponentPlanTypes.getParamString(normalizedComponent.params(), "component_id") == null) {
                var params = new HashMap<String, Object>();
                if (normalizedComponent.params() != null) params.putAll(normalizedComponent.params());
                params.put("component_id", "mass:" + normalizedComponent.slotId() + ":" + normalizedComponent.relativePosition());
                normalizedComponent = new Component(normalizedComponent.componentType(), normalizedComponent.slotId(),
                        normalizedComponent.relativePosition(), normalizedComponent.dimensions(), normalizedComponent.features(), params);
            }
            String slotKey = ComponentPlanTypes.slotKey(normalizedComponent);
            if (ComponentPlanTypes.isMassType(type)) {
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
            if (c != null && ComponentPlanTypes.isMassType(ComponentPlanTypes.normalizeType(c.componentType()))) {
                massSlots.add(ComponentPlanTypes.slotKey(c));
            }
        }

        if (!components.isEmpty() && !massSlots.isEmpty()) {
            List<Component> filtered = new ArrayList<>(components.size());
            for (Component c : components) {
                String type = ComponentPlanTypes.normalizeType(c.componentType());
                if (massSlots.contains(ComponentPlanTypes.slotKey(c)) && ComponentPlanTypes.AUTO_INFERRED_TYPES.contains(type) && isAutoInferred(c)) {
                    continue;
                }
                filtered.add(c);
            }
            components = filtered;
        }

        if (components.isEmpty()) {
            return new PreparedComponents(components, Set.of());
        }

        NativeLayoutRepair.apply(components, plan);
        List<Component> beforeAlignment = List.copyOf(components);
        components = AlignmentContractEnforcer.apply(plan, components);
        CourtyardAnchorCompiler.apply(components, slotMap);
        InteriorPortalCompiler.apply(components, slotMap);
        for (int i=0;i<components.size();i++) {
            Component roof = components.get(i);
            if (!ComponentPlanTypes.isRoofType(ComponentPlanTypes.normalizeType(roof.componentType())) || roof.dimensions()==null) continue;
            String host = ComponentPlanTypes.getParamString(roof.params(),"host_id");
            Integer eaves = ComponentParamParsers.intOrNull(roof.params(),"overhang");
            if (host==null || eaves==null || eaves<0) continue;
            for (Component original : beforeAlignment) {
                if (!host.equals(ComponentPlanTypes.getParamString(original.params(),"component_id")) || original.dimensions()==null) continue;
                for (Component aligned : components) {
                    if (!host.equals(ComponentPlanTypes.getParamString(aligned.params(),"component_id")) || aligned.dimensions()==null) continue;
                    if (!aligned.dimensions().equals(original.dimensions())
                            && roof.dimensions().width()==original.dimensions().width()+2*eaves
                            && roof.dimensions().depth()==original.dimensions().depth()+2*eaves) {
                        components.set(i,new Component(roof.componentType(),roof.slotId(),roof.relativePosition(),
                                new Dimensions(aligned.dimensions().width()+2*eaves,aligned.dimensions().depth()+2*eaves,roof.dimensions().height()),
                                roof.features(),roof.params()));
                    }
                    break;
                }
                break;
            }
        }
        for (int i = 0; i < components.size(); i++) {
            Component slab = components.get(i);
            if (!com.formacraft.common.generation.component.util.MassComponentKinds.isHostMass(slab)) continue;
            if (!"plate".equalsIgnoreCase(ComponentPlanTypes.getParamString(slab.params(), "extrude_mode"))) continue;
            String host = ComponentPlanTypes.getParamString(slab.params(), "host_id");
            for (Component body : components) {
                if (!"MASS_MAIN".equals(ComponentPlanTypes.normalizeType(body.componentType()))
                        || host == null || !host.equals(ComponentPlanTypes.getParamString(body.params(), "component_id"))) continue;
                int fh = ComponentParamParsers.intParam(body.params(), "floor_height", "floorHeight");
                Vec3i origin = ComponentPlanCompiler.resolveMassOrigin(body);
                if (fh <= 0 || origin == null || slab.relativePosition() == null || body.dimensions() == null) break;
                Vec3i pos = slab.relativePosition();
                int plateY = pos.y();
                var plateRole = com.formacraft.common.generation.component.util.HostedPlateOccupancy
                        .resolveRole(slab.params());
                var connectionLevel = com.formacraft.common.generation.component.util.HostedPlateOccupancy
                        .connectionLevel(slab.params());
                // Storey snap is interior-only. Bridge connection_level never silently overwrites explicit Y.
                // Authored LLM plates often land one cell above a storey or on the roof plane
                // (legacy height packed into dimensions); snap them onto a real storey floor.
                if (plateRole == com.formacraft.common.generation.component.util.HostedPlateOccupancy.PlateRole.INTERIOR
                        && (isAutoInferred(slab) || shouldSnapInteriorPlate(pos.y(), origin.y(), fh,
                        body.dimensions().height(),
                        ComponentParamParsers.intParam(body.params(), "floor_count", "floorCount")))) {
                    int count = ComponentParamParsers.intParam(body.params(), "floor_count", "floorCount");
                    int maxLevel = Math.max(1, count > 0 ? count - 1 : 1);
                    int level = Math.max(1, Math.min(maxLevel,
                            Math.round((pos.y() - origin.y()) / (float) fh)));
                    // Roof-plane Y (>= host height) is not an interior storey.
                    if (pos.y() - origin.y() >= body.dimensions().height()) {
                        level = maxLevel;
                    }
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
                        int clamped = Math.max(envelope.minInclusive(),
                                Math.min(envelope.maxInclusive(), plateY));
                        FormacraftMod.LOGGER.info(
                                "HostedPlate soft-snap: Y={} → {} ({} envelope [{},{}])",
                                plateY, clamped, plateRole,
                                envelope.minInclusive(), envelope.maxInclusive());
                        plateY = clamped;
                    }
                }
                var slabParams = new HashMap<String, Object>(slab.params());
                if (plateRole == com.formacraft.common.generation.component.util.HostedPlateOccupancy.PlateRole.BRIDGE) {
                    slabParams.put("allow_carve", true);
                }
                String floorBlock = ComponentPlanTypes.getParamString(body.params(), "floor_block");
                String slabMaterial = ComponentPlanTypes.getParamString(slab.params(), "material");
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
            String type = ComponentPlanTypes.normalizeType(c.componentType());
            String slotKey = ComponentPlanTypes.slotKey(c);
            if (ComponentPlanTypes.isCrownType(type)) {
                slotsWithCrown.add(slotKey);
            }
        }

        List<Component> inferred = new ArrayList<>();
        List<Component> prepared = new ArrayList<>(components.size());
        for (Component c : components) {
            if (c == null) continue;
            String type = ComponentPlanTypes.normalizeType(c.componentType());
            if ("ASSEMBLY".equals(type)) {
                prepared.add(c);
                continue;
            }
            String slotKey = ComponentPlanTypes.slotKey(c);
            if (assemblyPrimarySlots.contains(slotKey)) {
                continue;
            }
            if (!ComponentPlanTypes.isMassType(type)) {
                prepared.add(c);
                continue;
            }
            // Floor plates are structural slabs, not buildings with facades, doors and roofs.
            if (c.params() != null && "plate".equalsIgnoreCase(String.valueOf(c.params().get("extrude_mode")))) {
                // Explicit pavilion roofs still belong to this deck's authored columns.
                // Do not infer a roof for arbitrary floor slabs.
                for (int i = 0; i < components.size(); i++) {
                    Component roof = components.get(i);
                    if (ComponentPlanTypes.isRoofType(ComponentPlanTypes.normalizeType(roof.componentType()))
                            && java.util.Objects.equals(roof.slotId(), c.slotId())
                            && ComponentPlanTypes.paramIdentity(c).equals(ComponentPlanTypes.getParamString(roof.params(), "host_id"))) {
                        Component aligned = ComponentSatelliteAligner.alignRoofToMass(roof, c, plan);
                        if (aligned == null) return new PreparedComponents(List.of(), Set.of());
                        components.set(i, aligned);
                        int alreadyPrepared = prepared.indexOf(roof);
                        if (alreadyPrepared >= 0) prepared.set(alreadyPrepared, aligned);
                    }
                }
                prepared.add(c);
                continue;
            }
            String slotId = c.slotId();
            Slot slot = slotId != null ? slotMap.get(slotId) : null;
            GlobalConstraints.Facing facing = ComponentSatelliteAligner.resolveSlotFacing(plan, slotMap, slotId);
            boolean hasFacade = hasSatelliteForMass(c, components, inferred, "FACADE_WINDOWS");
            boolean hasEntrance = hasSatelliteForMass(c, components, inferred, "ENTRANCE");
            if (AssemblyCompileDiagnostics.hasGap()) {
                return new PreparedComponents(List.of(), Set.of());
            }
            boolean connectorBody = c.params() != null && Boolean.TRUE.equals(c.params().get("compiler_connector_body"));
            boolean openFrame = com.formacraft.common.generation.component.util.OpenFramePolicy.isOpen(c.params());
            boolean windowsDisabled = com.formacraft.common.style.ExplicitDesignPolicy.windowsDisabled(c) || connectorBody || openFrame;
            boolean entranceDisabled = com.formacraft.common.style.ExplicitDesignPolicy.entranceDisabled(c)
                    || openFrame || ("MASS_SECONDARY".equals(type) && c.params() != null
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
            Component crownHost = c;
            boolean wouldInferCrown = !hasCrown
                    && !(c.params() != null && c.params().containsKey("tier_index")
                        && components.stream().anyMatch(upper -> upper != crownHost && upper.params() != null
                            && java.util.Objects.equals(upper.params().get("host_id"), crownHost.params().get("component_id"))
                            && upper.params().containsKey("tier_index")))
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
                assemblyFacadeSlots.add(ComponentPlanTypes.getParamString(c.params(), "component_id"));
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

        ComponentSatelliteAligner.realignSatellitesToMass(prepared, plan, slotMap, assemblyPrimarySlots);
        // Elevated hosts only (see ExteriorDoorAttachmentCompiler): ground-level openings stay authored.
        ExteriorDoorAttachmentCompiler.apply(prepared);
        DoorFloorDatumCompiler.apply(prepared);
        // Infer interior stairs when satellites are allowed, or when circulation is REQUIRED
        // (fidelity cases keep allow_compiler_satellites=false but still need vertical links).
        if (plan.allowCompilerSatellites()
                || com.formacraft.common.generation.design.DesignRequirementAccess.circulation(plan)
                == com.formacraft.common.generation.design.DesignRequirement.REQUIRED) {
            InteriorStairInferenceCompiler.apply(prepared);
        }
        StairFloorPlaneCompiler.apply(prepared);
        ExteriorStairEndpointRepair.apply(prepared);
        BareStairFitCompiler.apply(prepared);
        StairChainCompiler.apply(prepared, plan);
        StairOpeningGuardCompiler.apply(prepared);
        DeckRailingAccessCompiler.apply(prepared);
        ComponentFoundationEnforcer.apply(prepared);
        DeclaredInteriorOpeningCompiler.apply(prepared, slotMap);
        prepared = ExplicitDesignConflictRepair.apply(prepared);

        return new PreparedComponents(prepared, assemblyFacadeSlots);
    }

    /**
     * When a plan already has {@code STRUCTURE + typology:*}, drop redundant compositional
     * building parts (MASS/TOWER/ROOF/…) that the typology builder already owns.
     */
    static List<Component> applyTypologyExclusiveFilter(List<Component> components) {
        if (components == null || components.isEmpty()) {
            return components;
        }

        boolean hasTypologyStructure = false;
        for (Component c : components) {
            if (c == null) {
                continue;
            }
            if ("STRUCTURE".equals(ComponentPlanTypes.normalizeType(c.componentType()))
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
            String type = ComponentPlanTypes.normalizeType(c.componentType());
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

    /** Reflection-compatible forwarders for tests; logic lives in {@link ComponentSatelliteAligner}. */

    static boolean isResolvedPartRoof(Component roof, List<Component> components, LlmPlan plan) {
        if (!ComponentPlanTypes.isRoofType(ComponentPlanTypes.normalizeType(roof.componentType())) || roof.params() == null
                || !Boolean.TRUE.equals(roof.params().get("resolved_mass_part_roof"))) return false;
        Object partId = roof.params().get("host_part_id");
        for (Component mass : components) {
            if (mass == null || !"MASS_MAIN".equals(ComponentPlanTypes.normalizeType(mass.componentType()))
                    || !ComponentPlanTypes.slotKey(mass).equals(ComponentPlanTypes.slotKey(roof))) continue;
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

    static Component makeFacadeComponent(Component base, String slotId) {
        Vec3i origin = ComponentPlanCompiler.resolveMassOrigin(base);
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
        String facadeProfile = ComponentPlanTypes.getParamString(params, "facade_profile", "facadeProfile", "facade");
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

    /**
     * Authored interior plates that sit on the roof plane or one cell off a storey
     * band are legacy packing artifacts — snap them rather than emit a thick floor.
     */
    private static boolean shouldSnapInteriorPlate(int plateY, int originY, int floorHeight,
                                                   int hostHeight, int floorCount) {
        if (floorHeight <= 0 || hostHeight <= 0) return false;
        int local = plateY - originY;
        if (local >= hostHeight) return true;
        if (local <= 0) return false;
        int nearest = Math.round(local / (float) floorHeight) * floorHeight;
        int maxLevel = Math.max(1, floorCount > 0 ? floorCount - 1 : 1);
        nearest = Math.max(floorHeight, Math.min(maxLevel * floorHeight, nearest));
        return Math.abs(local - nearest) <= 1;
    }

    static Component makeEntranceComponent(LlmPlan plan, Component base, String slotId, GlobalConstraints.Facing facing) {
        GlobalConstraints.Facing courtyardFacing = courtyardEntranceFacing(plan, base);
        if (courtyardFacing != null) facing = courtyardFacing;
        Dimensions dims = base.dimensions();
        Vec3i rp = ComponentPlanCompiler.resolveMassOrigin(base);
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
        if (courtyardFacing != null) {
            if (facing != null) {
                params.put("facing", facing.name());
            }
            params.put("entrance_orientation_source", "enclosed_courtyard");
        }
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

    /** Use the inward side only for a uniquely identified courtyard enclosed on at least three sides. */
    private static GlobalConstraints.Facing courtyardEntranceFacing(LlmPlan plan, Component base) {
        if (plan.components() == null || base.params() != null &&
                (base.params().containsKey("facing") || base.params().containsKey("entrance_facing"))) return null;
        var slots = ComponentPlanCompiler.indexSlots(plan);
        GlobalConstraints.Facing result = null;
        for (Component court : plan.components()) {
            if (!Set.of("COURTYARD", "COURTYARD_SPACE").contains(ComponentPlanTypes.normalizeType(court.componentType()))
                    || court.dimensions() == null || court.relativePosition() == null) continue;
            Component local = ComponentPlanCompiler.inSlotFrame(court, base.slotId(), slots);
            Vec3i co = ComponentPlanCompiler.resolveMassOrigin(local), bo = ComponentPlanCompiler.resolveMassOrigin(base);
            if (co == null || bo == null || base.dimensions() == null) continue;
            Set<GlobalConstraints.Facing> sides = new HashSet<>();
            for (Component body : plan.components()) {
                if (!Set.of("MASS_MAIN","MASS_SECONDARY","MASS_ANCILLARY","TOWER","MASS_WING","SIDE_WING")
                        .contains(ComponentPlanTypes.normalizeType(body.componentType())) || body.dimensions() == null
                        || body.relativePosition() == null || "plate".equals(ComponentPlanTypes.getParamString(body.params(),"extrude_mode"))) continue;
                var side = courtyardSide(ComponentPlanCompiler.inSlotFrame(body, base.slotId(), slots), local);
                if (side != null) sides.add(side);
            }
            if (sides.size() < 3) continue;
            var side = courtyardSide(base, local);
            if (side == null) continue;
            if (result != null) return null; // Multiple courts require an authored choice.
            result = side;
        }
        return result;
    }

    private static GlobalConstraints.Facing courtyardSide(Component body, Component court) {
        Vec3i b = ComponentPlanCompiler.resolveMassOrigin(body), c = ComponentPlanCompiler.resolveMassOrigin(court);
        if (b == null || c == null) return null;
        Dimensions bd = body.dimensions(), cd = court.dimensions();
        int gapLimit = 4;
        boolean overlapX = b.x() < c.x()+cd.width() && b.x()+bd.width() > c.x();
        boolean overlapZ = b.z() < c.z()+cd.depth() && b.z()+bd.depth() > c.z();
        if (overlapX && c.z() >= b.z()+bd.depth() && c.z()-b.z()-bd.depth() <= gapLimit) return GlobalConstraints.Facing.NORTH;
        if (overlapX && b.z() >= c.z()+cd.depth() && b.z()-c.z()-cd.depth() <= gapLimit) return GlobalConstraints.Facing.SOUTH;
        if (overlapZ && c.x() >= b.x()+bd.width() && c.x()-b.x()-bd.width() <= gapLimit) return GlobalConstraints.Facing.WEST;
        if (overlapZ && b.x() >= c.x()+cd.width() && b.x()-c.x()-cd.width() <= gapLimit) return GlobalConstraints.Facing.EAST;
        return null;
    }

    static Component makeRoofComponent(LlmPlan plan, Component base, String slotId) {
        if (com.formacraft.common.style.ExplicitDesignPolicy.roofDisabled(base)) return null;
        Dimensions dims = base.dimensions();
        Vec3i rp = ComponentPlanCompiler.resolveMassOrigin(base);
        if (dims == null || rp == null) {
            return null;
        }
        int width = Math.max(2, dims.width());
        int depth = Math.max(2, dims.depth());
        // Only a fully rectangular primary body can safely rebase its footprint mask.
        // Other shapes need clipping in the original mask coordinates rather than a smaller mask.
        if ("MASS_MAIN".equals(ComponentPlanTypes.normalizeType(base.componentType()))) {
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
        if (!com.formacraft.common.generation.component.util.RoofPitchGeometry.hasExplicitRiseIntent(params))
            roofHeight = com.formacraft.common.generation.component.util.StyleRoofGeometry.defaultRise(
                    plan.styleProfile(), width, depth, roofHeight);
        String roofType = ComponentPlanTypes.getParamString(params, "roof_type", "roofType");
        // An unroofed setback tier supports the next tier and its garden deck;
        // a default pitched roof would cut through that upper occupied body.
        if ((roofType == null || roofType.isBlank()) && params.containsKey("tier_index")
                && (Boolean.TRUE.equals(params.get("terraced")) || plan.components().stream().anyMatch(body ->
                    body.params() != null && Boolean.TRUE.equals(body.params().get("terraced"))
                        && java.util.Objects.equals(body.params().get("component_id"), params.get("host_id"))))) {
            roofType = "flat";
            params.put("roof_type", roofType);
        }
        if (roofType == null || roofType.isBlank()) {
            roofType = resolveDefaultRoofType(plan, base);
            if (roofType != null) {
                com.formacraft.common.generation.design.DesignProvenance.putIfAllowed(
                        plan, params, "roof_type", roofType);
                roofType = ComponentPlanTypes.getParamString(params, "roof_type", "roofType");
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

        // Open-frame pavilions need the roof fully above post tops; enclosed masses seal at height-1.
        boolean openFrame = com.formacraft.common.generation.component.util.OpenFramePolicy.isOpen(base.params());
        int roofY = rp.y() + (openFrame ? Math.max(1, dims.height()) : Math.max(1, dims.height() - 1));
        return new Component(
                "ROOF",
                slotId,
                new Vec3i(rp.x(), roofY, rp.z()),
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
        if (!"MASS_MAIN".equals(ComponentPlanTypes.normalizeType(mass.componentType()))) return;
        var parts = com.formacraft.common.generation.component.util.ResolvedMassPart.resolve(mass);
        if (parts.size() < 2) return;
        Component roof = null;
        Object rootId = mass.params() == null ? null : mass.params().get("component_id");
        for (Component candidate : java.util.stream.Stream.concat(components.stream(), inferred.stream()).toList()) {
            if (!ComponentPlanTypes.isRoofType(ComponentPlanTypes.normalizeType(candidate.componentType())) || !ComponentPlanTypes.slotKey(candidate).equals(ComponentPlanTypes.slotKey(mass))) continue;
            Object host = candidate.params() == null ? null : candidate.params().get("host_id");
            if (rootId != null && rootId.equals(host)) { roof = candidate; break; }
            if (host == null && roof == null) roof = candidate;
        }
        if (roof == null || !"flat".equalsIgnoreCase(ComponentPlanTypes.getParamString(roof.params(), "roof_type", "roofType"))) return;
        for (int i = 1; i < parts.size(); i++) {
            var part = parts.get(i);
            if (parts.getFirst().bounds().contains(part.bounds())
                    && part.bounds().maxY() <= parts.getFirst().bounds().maxY()) continue;
            if (part.dimensions().width() < 2 || part.dimensions().depth() < 2) continue;
            var frame = resolvePartRoofFrame(plan,mass,part);
            if (frame == null) continue;
            String requestedRoof = ComponentPlanTypes.getParamString(part.sourceParams(), "roof_type", "roofType");
            if (requestedRoof != null && !"flat".equalsIgnoreCase(requestedRoof)) continue;
            var params = new HashMap<String, Object>();
            if (mass.params() != null) params.putAll(mass.params());
            params.putAll(part.sourceParams());
            params.remove("masses"); params.remove("offset"); params.remove("dimensions");
            String shape = ComponentPlanTypes.getParamString(params, "shape", "footprint_shape", "footprintShape");
            String pattern = ComponentPlanTypes.getParamString(params, "plan_type", "planType");
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
            ComponentSatelliteAligner.copyFootprintParams(params, roofParams);
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

    static Component applyRoofGrammar(LlmPlan plan, Component roof) {
        if (roof == null) {
            return null;
        }
        return RoofGrammarResolver.apply(plan, roof);
    }

    static Component makeCrownComponent(LlmPlan plan, Component base, Component roof, String slotId) {
        Dimensions dims = base.dimensions();
        Vec3i rp = ComponentPlanCompiler.resolveMassOrigin(base);
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


    static Component findRoofForSlot(List<Component> primary, List<Component> inferred, String slotKey) {
        Component found = findRoofInList(primary, slotKey);
        if (found != null) {
            return found;
        }
        return findRoofInList(inferred, slotKey);
    }

    private static void bindDerived(Map<String, Object> params, Component mass, String role) {
        if (mass.params() != null && Boolean.TRUE.equals(mass.params().get("no_complex_decor"))) params.put("no_complex_decor", true);
        String identity = ComponentPlanTypes.getParamString(mass.params(), "component_id");
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
    static Component findBoundSatellite(Component mass, List<Component> primary, List<Component> inferred, String wantedType) {
        var satellites = new ArrayList<Component>(primary);
        satellites.addAll(inferred);
        Object identity = mass.params() == null ? null : mass.params().get("component_id");
        for (var satellite : satellites) {
            if (satellite == null || !("ROOF".equals(wantedType) ? ComponentPlanTypes.isRoofType(ComponentPlanTypes.normalizeType(satellite.componentType()))
                    : wantedType.equals(ComponentPlanTypes.normalizeType(satellite.componentType()))) || !ComponentPlanTypes.slotKey(satellite).equals(ComponentPlanTypes.slotKey(mass))) continue;
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
                                + ComponentPlanTypes.normalizeType(satellite.componentType())
                                + " slot=" + ComponentPlanTypes.slotKey(satellite)
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
            if (candidate == null || !ComponentPlanTypes.isMassType(ComponentPlanTypes.normalizeType(candidate.componentType()))
                    || !ComponentPlanTypes.slotKey(candidate).equals(ComponentPlanTypes.slotKey(mass)) || candidate.dimensions() == null
                    || candidate.relativePosition() == null) continue;
            // Floor plates are not host shells for entrance/window/roof binding.
            if (candidate.params() != null
                    && "plate".equalsIgnoreCase(String.valueOf(candidate.params().get("extrude_mode")))) {
                continue;
            }
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
            if (!wantedType.equals(ComponentPlanTypes.normalizeType(s.componentType()))) continue;
            if (!ComponentPlanTypes.slotKey(s).equals(ComponentPlanTypes.slotKey(mass))) continue;
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
            if (!slotKey.equals(ComponentPlanTypes.slotKey(c))) {
                continue;
            }
            if (ComponentPlanTypes.isRoofType(ComponentPlanTypes.normalizeType(c.componentType()))) {
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
        // An authored host suppression flag disables its default openings, not
        // explicit facade components. Partial delegation must not erase it and
        // thereby generate new default windows elsewhere on the host.
        if (Boolean.TRUE.equals(ComponentPlanTypes.getParamBoolean(params, fullKey))) return;
        params.remove(fullKey);
        Set<String> faces = delegation.faceNames();
        if (!faces.isEmpty()) params.put(facesKey, new ArrayList<>(faces));
        else params.remove(facesKey);
        List<Map<String, Object>> regions = delegation.regionsAsMaps();
        if (!regions.isEmpty()) params.put(regionsKey, regions);
        else params.remove(regionsKey);
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
            String type = ComponentPlanTypes.getParamString(roof.params(), "roof_type", "roofType");
            if (type == null || "none".equalsIgnoreCase(type)) continue;
            var params = new HashMap<String, Object>(body.params());
            params.put("roof_type", type);
            return new Component(body.componentType(), body.slotId(), body.relativePosition(), body.dimensions(), body.features(), params);
        }
        return body;
    }

    static Component normalizeComponent(Component component) {
        if (component == null) {
            return null;
        }
        if (ComponentPlanTypes.isRoofType(ComponentPlanTypes.normalizeType(component.componentType())) && component.params()!=null
                && component.params().get("overhang") instanceof Boolean enabled) {
            var p=new HashMap<String,Object>(component.params());
            if(enabled) p.remove("overhang"); else p.put("overhang",0);
            component=new Component(component.componentType(),component.slotId(),component.relativePosition(),component.dimensions(),component.features(),p);
        }
        if ("FACADE_WINDOWS".equalsIgnoreCase(component.componentType()) && component.params()!=null
                && Set.of("wood_lattice", "wooden_lattice").contains(
                    String.valueOf(component.params().get("window_style")).toLowerCase(Locale.ROOT))) {
            var p=new HashMap<String,Object>(component.params());
            String wood=String.valueOf(p.getOrDefault("frame_material",p.getOrDefault("material","oak")));
            p.put("window_block",wood.contains("dark_oak")?"minecraft:dark_oak_fence":wood.contains("spruce")?"minecraft:spruce_fence":"minecraft:oak_fence");
            p.put("material",p.get("window_block"));
            p.put("window_material",p.get("window_block"));
            var f=component.features()==null?List.<String>of():component.features().stream()
                    .filter(x->x!=null && !x.startsWith("component_request:")).toList();
            component=new Component(component.componentType(),component.slotId(),component.relativePosition(),component.dimensions(),f,p);
        }
        if (component.dimensions()!=null && component.dimensions().height()==1
                && ComponentPlanTypes.isMassType(ComponentPlanTypes.normalizeType(component.componentType())) && component.params()!=null
                && ("none".equalsIgnoreCase(String.valueOf(component.params().get("wall_type")))
                    || Boolean.FALSE.equals(component.params().get("hollow")))) {
            var floorParams=new HashMap<String,Object>(component.params());
            floorParams.put("extrude_mode","plate");
            component=new Component(component.componentType(),component.slotId(),component.relativePosition(),
                    component.dimensions(),component.features(),floorParams);
        }
        if ("MASS_ANCILLARY".equals(ComponentPlanTypes.normalizeType(component.componentType()))) {
            var params = new HashMap<String, Object>();
            if (component.params() != null) params.putAll(component.params());
            boolean solidSupport = Boolean.FALSE.equals(params.get("hollow"))
                    || com.formacraft.common.generation.component.util.OpenFramePolicy.isOpen(params);
            if (!solidSupport) params.put("compiler_connector_body", true);
            params.putIfAbsent("wall_thickness", 1);
            params.put("plan_type", "none");
            // Unspecified-width covered links need one walkable cell between
            // their walls. Do not let a two-cell model default seal the link.
            Dimensions linkDims = component.dimensions();
            int linkWall = ComponentParamParsers.intParam(params, 1, "wall_thickness", "wallThickness");
            if (!solidSupport && linkDims != null && linkWall > 0) {
                linkDims = new Dimensions(Math.max(linkDims.width(), 2*linkWall+1),
                        Math.max(linkDims.depth(), 2*linkWall+1), linkDims.height());
            }
            component = new Component("MASS_SECONDARY", component.slotId(), component.relativePosition(),
                    linkDims, component.features(), params);
        }
        // allowUnknown：component_request/group_request，或显式地标/模块路由提示，
        // 都应保留原始 type（交由 UnifiedGeneratorRouter 的扩展/整栋回退处理）。
        boolean allowUnknown = ComponentPlanTypes.hasComponentRequest(component.features())
                || com.formacraft.server.generation.component.impl.StraightStairComponentGenerator.accepts(component)
                || ComponentPlanTypes.hasStructureRoutingHint(component)
                || "MODULE".equals(ComponentPlanTypes.normalizeType(component.componentType()))
                || "STRUCTURE".equals(ComponentPlanTypes.normalizeType(component.componentType()))
                || "ASSEMBLY".equals(ComponentPlanTypes.normalizeType(component.componentType()));
        String type = ComponentPlanTypes.normalizeComponentType(component.componentType(), allowUnknown);
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



    /**
     * 是否携带地标/模块/整栋路由提示（Phase 7）。用于放行 MODULE 等未注册 type，
     * 交由 {@code UnifiedGeneratorRouter} 的整栋回退（{@code StructureGeneratorAdaptor}）处理。
     */





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
        return normalizeRoofTypeToken(ComponentPlanTypes.getParamString(base.params(), "preferred_roof_type", "preferredRoofType"));
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
        Boolean override = ComponentPlanTypes.getParamBoolean(params, "assembly_facade", "assemblyFacade", "useAssemblyFacade");
        if (override != null) {
            return override;
        }

        // P0：默认关闭 Assembly 立面；仅 params / style_attributes 显式请求时开启。
        if (Boolean.TRUE.equals(ComponentPlanTypes.getParamBoolean(params, "assembly_macro", "assemblyMacro", "use_assembly_macro"))) {
            Dimensions d = c.dimensions();
            return Math.min(d.width(), d.depth()) >= 6 && d.height() >= 6;
        }
        String facadeProfile = ComponentPlanTypes.getParamString(params, "facade_profile", "facadeProfile");
        if (needsAssemblyFacadeProfile(facadeProfile)) {
            String detail = ComponentPlanTypes.getParamString(params, "detail_level", "detailLevel", "quality", "quality_level");
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

}
