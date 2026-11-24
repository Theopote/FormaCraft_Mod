package com.formacraft.server.compiler;

import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.LlmPlanTestFixtures;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.patch.ConfirmedPlanIdentity;
import com.formacraft.common.typology.UserConstraintContract;
import com.formacraft.server.assembly.AssemblyCompileDiagnostics;
import com.formacraft.server.build.BuildAutoRepair;
import com.formacraft.server.build.BuildConstraintClipper;
import com.formacraft.server.build.BuildPreviewPipeline;
import com.formacraft.server.network.PlanPatchConverter;
import com.formacraft.test.PatchTestSnapshot;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Full compile path for stacked tower height envelopes — not Component.dimensions alone.
 * LlmPlan → RequestIntentInjector → ComponentPlanCompiler → BlockPatch
 * → PlanPatchConverter → BuildAutoRepair → clip → collapse (preview pipeline cells).
 */
class StackedTowerFullCompileEnvelopeTest {

    @BeforeAll
    static void bootstrap() {
        com.formacraft.test.MinecraftRegistryTestBootstrap.initialize();
    }

    /**
     * Shaft target 40. Height口径: sealed component shaft envelope must be exactly 40;
     * BlockPatch solid envelope inside the shaft footprint (excluding dy below shaft base)
     * must also be exactly 40. Error sources if this drifts: foundation pads, cornice, typology inject.
     */
    @Test
    void shaftHeightConstraintSurvivesFullCompileToBlockPatch() {
        List<Component> tiers = stackedTight(new int[]{8, 8, 7, 6, 5});
        for (int i = 0; i < tiers.size(); i++) {
            Component c = tiers.get(i);
            Map<String, Object> p = new HashMap<>(c.params());
            p.put("roof_type", "none");
            p.put("window_style", "none");
            p.put("entrance_type", "none");
            tiers.set(i, new Component(c.componentType(), c.slotId(), c.relativePosition(),
                    c.dimensions(), c.features(), p));
        }
        Map<String, Object> contract = Map.of("requirements", List.of(
                Map.of("property", "roof_type", "value", "none", "scope", "plan"),
                Map.of("property", "window_style", "value", "none", "scope", "plan"),
                Map.of("property", "entrance_type", "value", "none", "scope", "plan")));
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .allowCompilerSatellites(false)
                .mode(LlmPlan.Mode.build)
                .anchor(new Vec3i(0, 64, 0))
                .components(tiers)
                .proportionHints(Map.of("building_contract", contract))
                .build();
        LlmPlan sealed = RequestIntentInjector.ensure(plan, "建造一座高度40格的宝塔。");
        assertFalse(sealed.hasCapabilityGap(), () -> String.valueOf(sealed.capabilityGap()));
        assertEquals(40, shaftEnvelope(pagodaTiers(sealed)), "component shaft envelope must be exact");

        AssemblyCompileDiagnostics.clear();
        var patches = ComponentPlanCompiler.compile(sealed, BlockPos.ORIGIN, null, null, false);
        assertFalse(patches.isEmpty(), () -> String.valueOf(AssemblyCompileDiagnostics.get()));
        Map<Vec3i, String> blocks = PatchTestSnapshot.blocks(patches);
        assertFalse(blocks.isEmpty());

        Footprint fp = shaftFootprint(pagodaTiers(sealed));
        int solidEnvelope = footprintSolidHeight(blocks, fp);
        assertEquals(40, solidEnvelope,
                "BlockPatch solid height inside shaft footprint must equal target 40 "
                        + "(excludes foundation below shaft base; no roof satellites)");

        // Deterministic re-compile (same entry) — not preview/apply parity.
        AssemblyCompileDiagnostics.clear();
        var again = ComponentPlanCompiler.compile(sealed, BlockPos.ORIGIN, null, null, false);
        assertEquals(blocks, PatchTestSnapshot.blocks(again),
                "identical compile inputs must be deterministic");
    }

    @Test
    void buildingModeWithExplicitRoofTypeAndSatellitesMatchesTargetEnvelope() {
        // Explicit ROOF (not inferred): avoids estimate-vs-inference drift so a legal request
        // must compile to a non-empty BlockPatch with exact building height 50.
        List<Component> comps = new ArrayList<>(stackedTight(new int[]{8, 8, 7, 6, 5}));
        for (int i = 0; i < comps.size(); i++) {
            Component c = comps.get(i);
            Map<String, Object> p = new HashMap<>(c.params());
            p.put("window_style", "none");
            p.put("entrance_type", "none");
            // Mid-tiers omit mass roofs; only the top hosts the explicit ROOF satellite.
            if (i < comps.size() - 1) p.put("roof_type", "none");
            else p.put("roof_type", "xieshan");
            comps.set(i, new Component(c.componentType(), c.slotId(), c.relativePosition(),
                    c.dimensions(), c.features(), p));
        }
        Map<String, Object> roofParams = new HashMap<>();
        roofParams.put("component_id", "pagoda_roof");
        roofParams.put("host_id", "pagoda_tier_5");
        roofParams.put("roof_type", "xieshan");
        comps.add(new Component("ROOF", "pagoda", new Vec3i(0, 34, 0),
                new Dimensions(6, 6, 3), List.of("roof"), roofParams));
        Map<String, Object> contract = Map.of("requirements", List.of(
                Map.of("property", "window_style", "value", "none", "scope", "plan"),
                Map.of("property", "entrance_type", "value", "none", "scope", "plan")));
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .mode(LlmPlan.Mode.build)
                .anchor(new Vec3i(0, 64, 0))
                .styleProfile("CHINESE_CLASSICAL")
                .components(comps)
                .proportionHints(Map.of("building_contract", contract))
                .planMeta(Map.of(
                        "design_requirements", Map.of(
                                "facade", "OMITTED",
                                "entrance", "OMITTED",
                                "roof", "REQUIRED")))
                // Must follow planMeta — allowCompilerSatellites merges into meta.
                .allowCompilerSatellites(true)
                .build();
        LlmPlan sealed = RequestIntentInjector.ensure(plan, "整座宝塔含屋顶高度50格。");
        assertFalse(sealed.hasCapabilityGap(), () -> String.valueOf(sealed.capabilityGap()));
        assertEquals("building", String.valueOf(
                pagodaTiers(sealed).getFirst().params().get(RequestIntentInjector.ENVELOPE_MODE_KEY)));
        assertEquals(50, buildingEnvelope(sealed.components()),
                "sealed component building envelope must equal player target");
        assertTrue(hasRoofComponent(sealed.components()), "explicit ROOF must survive ensure");
        int shaftBefore = shaftEnvelope(pagodaTiers(sealed));
        assertTrue(shaftBefore < 50, "shaft must leave room for roof (shaft=" + shaftBefore + ")");

        AssemblyCompileDiagnostics.clear();
        var patches = ComponentPlanCompiler.compile(sealed, BlockPos.ORIGIN, null, null, false);
        assertFalse(patches.isEmpty(),
                () -> "legal building-mode request must yield BlockPatch, got "
                        + AssemblyCompileDiagnostics.get());
        Map<Vec3i, String> blocks = PatchTestSnapshot.blocks(patches);

        Footprint fp = buildingFootprint(sealed.components());
        int solidEnvelope = footprintSolidHeight(blocks, fp);
        assertEquals(50, solidEnvelope,
                "BlockPatch building footprint solid height must equal target 50 "
                        + "(excludes foundation below shaft base)");
        assertTrue(hasRoofLikeBlocksAbove(blocks, fp, shaftTopY(pagodaTiers(sealed))),
                "top roof surface must exist above the shaft top");
    }

    @Test
    void impossibleBuildingHeightReportsStackedGap() {
        List<Component> tiers = stackedTight(new int[]{10, 10, 10, 10, 10});
        // Hard floor bands: 5 × (2×5)=50 minimum shaft; whole-building 40 is infeasible.
        for (Component c : tiers) {
            c.params().put("floor_count", 2);
            c.params().put("floor_height", 5);
            UserConstraintContract.stampVerified(c.params(), "floor_height");
        }
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .allowCompilerSatellites(true)
                .components(tiers)
                .build();
        LlmPlan out = RequestIntentInjector.ensure(plan, "整座宝塔含屋顶高度40格。");
        assertTrue(out.hasCapabilityGap(), "infeasible building envelope must gap");
        assertEquals("E_STACKED_HEIGHT_UNSUPPORTED", out.capabilityGap().code());
    }

    /**
     * Mid-tier eave (H=4) + explicit top ROOF (H=3). Old shaftTop+net budget would reserve
     * mid net 3 and break exact whole-building 50; host-relative seating must not.
     */
    @Test
    void midTierEaveWithExplicitTopRoofMatchesBuildingEnvelope50() {
        List<Component> comps = new ArrayList<>(stackedTight(new int[]{8, 8, 7, 6, 5}));
        for (int i = 0; i < comps.size(); i++) {
            Component c = comps.get(i);
            Map<String, Object> p = new HashMap<>(c.params());
            p.put("window_style", "none");
            p.put("entrance_type", "none");
            if (i < comps.size() - 1) p.put("roof_type", "none");
            else p.put("roof_type", "xieshan");
            comps.set(i, new Component(c.componentType(), c.slotId(), c.relativePosition(),
                    c.dimensions(), c.features(), p));
        }
        Map<String, Object> midRoof = new HashMap<>();
        midRoof.put("component_id", "pagoda_mid_eave");
        midRoof.put("host_id", "pagoda_tier_3");
        midRoof.put("roof_type", "flat");
        comps.add(new Component("ROOF", "pagoda", new Vec3i(0, 20, 0),
                new Dimensions(8, 8, 4), List.of("roof"), midRoof));
        Map<String, Object> topRoof = new HashMap<>();
        topRoof.put("component_id", "pagoda_roof");
        topRoof.put("host_id", "pagoda_tier_5");
        topRoof.put("roof_type", "xieshan");
        comps.add(new Component("ROOF", "pagoda", new Vec3i(0, 34, 0),
                new Dimensions(6, 6, 3), List.of("roof"), topRoof));
        Map<String, Object> contract = Map.of("requirements", List.of(
                Map.of("property", "window_style", "value", "none", "scope", "plan"),
                Map.of("property", "entrance_type", "value", "none", "scope", "plan")));
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .mode(LlmPlan.Mode.build)
                .anchor(new Vec3i(0, 64, 0))
                .styleProfile("CHINESE_CLASSICAL")
                .components(comps)
                .proportionHints(Map.of("building_contract", contract))
                .planMeta(Map.of(
                        "design_requirements", Map.of(
                                "facade", "OMITTED",
                                "entrance", "OMITTED",
                                "roof", "REQUIRED")))
                .allowCompilerSatellites(true)
                .build();

        LlmPlan sealed = RequestIntentInjector.ensure(plan, "整座宝塔含屋顶高度50格。");
        assertFalse(sealed.hasCapabilityGap(), () -> String.valueOf(sealed.capabilityGap()));
        // Top enclosed H=3 → net 2; mid eave must not add another 3.
        assertEquals(48, shaftEnvelope(pagodaTiers(sealed)));
        assertEquals(50, buildingEnvelope(sealed.components()));
        assertTrue(shaftEnvelope(pagodaTiers(sealed)) > 50 - 3,
                "mid-tier eave must not consume top crown budget");

        AssemblyCompileDiagnostics.clear();
        var patches = ComponentPlanCompiler.compile(sealed, BlockPos.ORIGIN, null, null, false);
        assertFalse(patches.isEmpty(),
                () -> "mid-eave + top roof must compile, got " + AssemblyCompileDiagnostics.get());
        Map<Vec3i, String> blocks = PatchTestSnapshot.blocks(patches);
        Footprint fp = buildingFootprint(sealed.components());
        assertEquals(50, footprintSolidHeight(blocks, fp),
                "BlockPatch building height must be exact 50 with mid eaves present");
        assertTrue(hasRoofLikeBlocksAbove(blocks, fp, shaftTopY(pagodaTiers(sealed))),
                "top roof must place blocks above the shaft");
    }

    /**
     * Diagram case: mid-tier eave present + top roof inferred (no authored top ROOF).
     * Mid eaves must not steal top crown budget; sealed + BlockPatch height stay exact 50.
     */
    @Test
    void midTierEaveWithInferredTopRoofMatchesBuildingEnvelope50() {
        List<Component> comps = new ArrayList<>(stackedTight(new int[]{8, 8, 7, 6, 5}));
        for (int i = 0; i < comps.size(); i++) {
            Component c = comps.get(i);
            Map<String, Object> p = new HashMap<>(c.params());
            p.put("window_style", "none");
            p.put("entrance_type", "none");
            if (i < comps.size() - 1) p.put("roof_type", "none");
            else p.put("roof_type", "xieshan");
            comps.set(i, new Component(c.componentType(), c.slotId(), c.relativePosition(),
                    c.dimensions(), c.features(), p));
        }
        Map<String, Object> midRoof = new HashMap<>();
        midRoof.put("component_id", "pagoda_mid_eave");
        midRoof.put("host_id", "pagoda_tier_3");
        midRoof.put("roof_type", "flat");
        comps.add(new Component("ROOF", "pagoda", new Vec3i(0, 20, 0),
                new Dimensions(8, 8, 4), List.of("roof"), midRoof));
        Map<String, Object> contract = Map.of("requirements", List.of(
                Map.of("property", "window_style", "value", "none", "scope", "plan"),
                Map.of("property", "entrance_type", "value", "none", "scope", "plan")));
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .mode(LlmPlan.Mode.build)
                .anchor(new Vec3i(0, 64, 0))
                .styleProfile("CHINESE_CLASSICAL")
                .components(comps)
                .proportionHints(Map.of("building_contract", contract))
                .planMeta(Map.of(
                        "design_requirements", Map.of(
                                "facade", "OMITTED",
                                "entrance", "OMITTED",
                                "roof", "REQUIRED")))
                .allowCompilerSatellites(true)
                .build();

        LlmPlan sealed = RequestIntentInjector.ensure(plan, "整座宝塔含屋顶高度50格。");
        assertFalse(sealed.hasCapabilityGap(), () -> String.valueOf(sealed.capabilityGap()));
        int inferred = RequestIntentInjector.estimateInferredRoofRise(
                sealed, sealed.components(), pagodaTiers(sealed));
        assertTrue(inferred > 0, "mid eave must not suppress top inferred roof");
        assertEquals(50 - inferred, shaftEnvelope(pagodaTiers(sealed)));
        boolean authoredTopRoof = sealed.components().stream().anyMatch(c ->
                c != null && c.params() != null
                        && "pagoda_roof".equals(String.valueOf(c.params().get("component_id"))));
        assertFalse(authoredTopRoof, "top roof must remain inferred (no authored top ROOF id)");
        assertTrue(StackedEnvelopeIntegrityValidator.check(sealed, sealed.components()).isEmpty());

        AssemblyCompileDiagnostics.clear();
        var patches = ComponentPlanCompiler.compile(sealed, BlockPos.ORIGIN, null, null, false);
        assertFalse(patches.isEmpty(),
                () -> "mid-eave + inferred top must compile, got " + AssemblyCompileDiagnostics.get());
        assertFalse(AssemblyCompileDiagnostics.hasGap(),
                () -> String.valueOf(AssemblyCompileDiagnostics.get()));
        Map<Vec3i, String> blocks = PatchTestSnapshot.blocks(patches);
        Footprint fp = buildingFootprint(sealed.components());
        assertEquals(50, footprintSolidHeight(blocks, fp),
                "BlockPatch building height must be exact 50 with mid eaves + inferred top");
        assertTrue(hasRoofLikeBlocksAbove(blocks, fp, shaftTopY(pagodaTiers(sealed))),
                "inferred top roof must place blocks above the shaft");
    }

    /**
     * Shaft-only + roof_type → compiler infers top roof. Component envelope budgets exact 50;
     * compile must succeed with roof voxels above the shaft.
     */
    @Test
    void inferredTopRoofBudgetsAndCompilesWithRoofAboveShaft() {
        List<Component> comps = new ArrayList<>(stackedTight(new int[]{8, 8, 7, 6, 5}));
        for (int i = 0; i < comps.size(); i++) {
            Component c = comps.get(i);
            Map<String, Object> p = new HashMap<>(c.params());
            p.put("window_style", "none");
            p.put("entrance_type", "none");
            if (i < comps.size() - 1) p.put("roof_type", "none");
            else p.put("roof_type", "xieshan");
            comps.set(i, new Component(c.componentType(), c.slotId(), c.relativePosition(),
                    c.dimensions(), c.features(), p));
        }
        Map<String, Object> contract = Map.of("requirements", List.of(
                Map.of("property", "window_style", "value", "none", "scope", "plan"),
                Map.of("property", "entrance_type", "value", "none", "scope", "plan")));
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .mode(LlmPlan.Mode.build)
                .anchor(new Vec3i(0, 64, 0))
                .styleProfile("CHINESE_CLASSICAL")
                .components(comps)
                .proportionHints(Map.of("building_contract", contract))
                .planMeta(Map.of(
                        "design_requirements", Map.of(
                                "facade", "OMITTED",
                                "entrance", "OMITTED",
                                "roof", "REQUIRED")))
                .allowCompilerSatellites(true)
                .build();
        LlmPlan sealed = RequestIntentInjector.ensure(plan, "整座宝塔含屋顶高度50格。");
        assertFalse(sealed.hasCapabilityGap(), () -> String.valueOf(sealed.capabilityGap()));
        int inferred = RequestIntentInjector.estimateInferredRoofRise(
                sealed, sealed.components(), pagodaTiers(sealed));
        assertTrue(inferred > 0);
        assertEquals(50 - inferred, shaftEnvelope(pagodaTiers(sealed)));
        assertTrue(StackedEnvelopeIntegrityValidator.check(sealed, sealed.components()).isEmpty());

        AssemblyCompileDiagnostics.clear();
        var patches = ComponentPlanCompiler.compile(sealed, BlockPos.ORIGIN, null, null, false);
        assertFalse(patches.isEmpty(), () -> String.valueOf(AssemblyCompileDiagnostics.get()));
        assertFalse(AssemblyCompileDiagnostics.hasGap(),
                () -> String.valueOf(AssemblyCompileDiagnostics.get()));
        Map<Vec3i, String> blocks = PatchTestSnapshot.blocks(patches);
        Footprint fp = shaftFootprint(pagodaTiers(sealed));
        assertTrue(hasRoofLikeBlocksAbove(blocks, fp, shaftTopY(pagodaTiers(sealed))),
                "inferred roof must place blocks above the shaft");
        int solid = footprintSolidHeight(blocks, fp);
        assertEquals(50, solid,
                "inferred xieshan BlockPatch height must match sealed building budget (solid="
                        + solid + ", inferred=" + inferred + ")");
    }

    /**
     * Preview-pipeline cell+block identity — not {@code BuildPreviewPipeline.prepare()} and not a
     * live Minecraft world apply / confirm token. Coverage: compile → PlanPatchConverter →
     * BuildAutoRepair → clip → collapseToFinalCells vs ConfirmedPlanIdentity final patches as
     * BlockPos→blockId maps (coords alone are insufficient).
     */
    @Test
    void previewPipelineCellAndBlockIdentityMatchesConfirmedPlan() {
        List<Component> tiers = stackedTight(new int[]{8, 8, 7, 6, 5});
        for (int i = 0; i < tiers.size(); i++) {
            Component c = tiers.get(i);
            Map<String, Object> p = new HashMap<>(c.params());
            p.put("roof_type", "none");
            p.put("window_style", "none");
            p.put("entrance_type", "none");
            tiers.set(i, new Component(c.componentType(), c.slotId(), c.relativePosition(),
                    c.dimensions(), c.features(), p));
        }
        Map<String, Object> contract = Map.of("requirements", List.of(
                Map.of("property", "roof_type", "value", "none", "scope", "plan"),
                Map.of("property", "window_style", "value", "none", "scope", "plan"),
                Map.of("property", "entrance_type", "value", "none", "scope", "plan")));
        LlmPlan sealed = RequestIntentInjector.ensure(
                LlmPlanTestFixtures.builder()
                        .allowCompilerSatellites(false)
                        .mode(LlmPlan.Mode.build)
                        .anchor(new Vec3i(0, 64, 0))
                        .components(tiers)
                        .proportionHints(Map.of("building_contract", contract))
                        .build(),
                "建造一座高度40格的宝塔。");
        assertFalse(sealed.hasCapabilityGap(), () -> String.valueOf(sealed.capabilityGap()));

        AssemblyCompileDiagnostics.clear();
        BlockPos origin = BlockPos.ORIGIN;
        List<BlockPatch> compiled = ComponentPlanCompiler.compile(sealed, origin, null, null, false);
        assertFalse(compiled.isEmpty(), () -> String.valueOf(AssemblyCompileDiagnostics.get()));
        List<BlockPatch> compileFinal = ConfirmedPlanIdentity.toFinalPatches(compiled);

        var conversion = PlanPatchConverter.convert(compileFinal, origin);
        assertFalse(conversion.hasCriticalFailure(),
                () -> "PlanPatchConverter critical failure: " + conversion);
        // world=null → BuildAutoRepair only dedupes; still exercises converter→clip→collapse.
        var repaired = BuildAutoRepair.apply(null, Optional.empty(), conversion.blocks());
        var clipped = BuildConstraintClipper.clipPlannedBlocks(repaired.blocks(), null);
        var collapsed = BuildPreviewPipeline.collapseToFinalCellsWithStats(clipped).blocks();
        assertFalse(collapsed.isEmpty(), "preview pipeline must leave non-empty final cells");

        Map<String, String> compileCells = new java.util.LinkedHashMap<>();
        for (BlockPatch p : compileFinal) {
            if (p == null) continue;
            String action = p.action() == null ? "" : p.action().toLowerCase(Locale.ROOT);
            String target = p.targetBlock() == null ? "" : p.targetBlock().toLowerCase(Locale.ROOT);
            if (BlockPatch.REMOVE.equals(action) || target.contains("air")) continue;
            compileCells.put(p.dx() + "," + p.dy() + "," + p.dz(), target);
        }
        Map<String, String> previewCells = new java.util.LinkedHashMap<>();
        for (var pb : collapsed) {
            if (pb == null || pb.getTargetState() == null || pb.getTargetState().isAir()) continue;
            BlockPos rel = pb.getPos().subtract(origin);
            String id = net.minecraft.registry.Registries.BLOCK.getId(pb.getTargetState().getBlock())
                    .toString().toLowerCase(Locale.ROOT);
            previewCells.put(rel.getX() + "," + rel.getY() + "," + rel.getZ(), id);
        }
        assertEquals(compileCells.keySet(), previewCells.keySet(),
                "preview pipeline solid cell coords must match compile final");
        assertEquals(compileCells, previewCells,
                "preview pipeline BlockPos→blockId must match compile final (not coords alone)");

        // Confirmed-plan identity: final-set patch hash / planId are stable for the same cells.
        String patchHash = ConfirmedPlanIdentity.patchHash(compileFinal);
        assertEquals(patchHash, ConfirmedPlanIdentity.patchHash(
                        ConfirmedPlanIdentity.toFinalPatches(compiled)),
                "toFinalPatches must be idempotent for plan identity");
        String planId = ConfirmedPlanIdentity.planId("stacked-tower-preview-40", compileFinal);
        assertEquals(planId, ConfirmedPlanIdentity.planId("stacked-tower-preview-40", compileFinal));
        assertNotEquals(planId, ConfirmedPlanIdentity.planId("other-context", compileFinal),
                "planId must bind contextHash");

        // PreviewStorage digest of collapsed cells is order-stable (confirm enqueue fingerprint).
        var structureA = new com.formacraft.common.build.GeneratedStructure(
                java.util.UUID.randomUUID(), origin, "tower", collapsed);
        var reversed = new java.util.ArrayList<>(collapsed);
        java.util.Collections.reverse(reversed);
        var structureB = new com.formacraft.common.build.GeneratedStructure(
                java.util.UUID.randomUUID(), origin, "tower", reversed);
        assertEquals(com.formacraft.server.preview.PreviewStorage.digestStructure(structureA),
                com.formacraft.server.preview.PreviewStorage.digestStructure(structureB),
                "confirm digest must ignore PlannedBlock list order");
    }

    private static boolean hasRoofComponent(List<Component> comps) {
        for (Component c : comps) {
            if (c == null || c.componentType() == null) continue;
            if (c.componentType().toUpperCase(Locale.ROOT).startsWith("ROOF")) return true;
        }
        return false;
    }

    private static boolean topStoreyHasRoofType(List<Component> comps) {
        Component top = null;
        int maxY = Integer.MIN_VALUE;
        for (Component c : comps) {
            if (c == null || c.params() == null || c.relativePosition() == null) continue;
            String id = String.valueOf(c.params().get("component_id"));
            if (!id.startsWith("pagoda_tier_")) continue;
            if (c.relativePosition().y() >= maxY) {
                maxY = c.relativePosition().y();
                top = c;
            }
        }
        if (top == null || top.params() == null) return false;
        Object rt = top.params().get("roof_type");
        if (rt == null) rt = top.params().get("roofType");
        if (rt == null) return false;
        String roof = String.valueOf(rt).trim().toLowerCase(Locale.ROOT);
        return !roof.isEmpty() && !"none".equals(roof) && !"无".equals(roof);
    }

    private static boolean hasRoofLikeBlocksAbove(Map<Vec3i, String> blocks, Footprint fp, int shaftTop) {
        for (var e : blocks.entrySet()) {
            Vec3i p = e.getKey();
            if (p.y() < shaftTop) continue;
            if (p.x() < fp.minX || p.x() > fp.maxX || p.z() < fp.minZ || p.z() > fp.maxZ) continue;
            String id = e.getValue() == null ? "" : e.getValue().toLowerCase(Locale.ROOT);
            if (id.contains("stair") || id.contains("slab") || id.contains("roof")
                    || id.contains("dark_oak") || id.contains("spruce") || id.contains("terracotta")) {
                return true;
            }
        }
        return false;
    }

    private static int shaftTopY(List<Component> tiers) {
        int max = Integer.MIN_VALUE;
        for (Component c : tiers) {
            max = Math.max(max, c.relativePosition().y() + c.dimensions().height());
        }
        return max;
    }

    private static int shaftEnvelope(List<Component> tiers) {
        tiers = new ArrayList<>(tiers);
        tiers.sort(java.util.Comparator.comparingInt(c -> c.relativePosition().y()));
        int minY = tiers.getFirst().relativePosition().y();
        int maxTop = tiers.stream()
                .mapToInt(c -> c.relativePosition().y() + c.dimensions().height())
                .max().orElse(minY);
        return maxTop - minY;
    }

    private static int buildingEnvelope(List<Component> comps) {
        int minY = Integer.MAX_VALUE;
        int maxTop = Integer.MIN_VALUE;
        for (Component c : comps) {
            if (c == null || c.relativePosition() == null || c.dimensions() == null) continue;
            String mid = String.valueOf(c.params() == null ? "" : c.params().get("component_id"));
            boolean shaft = mid.startsWith("pagoda_tier_");
            boolean crown = RequestIntentInjector.countsTowardBuildingEnvelope(c);
            if (!shaft && !crown) continue;
            minY = Math.min(minY, c.relativePosition().y());
            maxTop = Math.max(maxTop, c.relativePosition().y() + c.dimensions().height());
        }
        return maxTop - minY;
    }

    private static List<Component> pagodaTiers(LlmPlan plan) {
        List<Component> out = new ArrayList<>();
        for (Component c : plan.components()) {
            if (c == null || c.params() == null) continue;
            String id = String.valueOf(c.params().get("component_id"));
            if (id.startsWith("pagoda_tier_")) out.add(c);
        }
        return out;
    }

    private record Footprint(int minX, int maxX, int minZ, int maxZ, int baseY) {}

    private static Footprint shaftFootprint(List<Component> tiers) {
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        int baseY = Integer.MAX_VALUE;
        for (Component c : tiers) {
            int x = c.relativePosition().x();
            int z = c.relativePosition().z();
            int w = c.dimensions().width();
            int d = c.dimensions().depth();
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x + w - 1);
            minZ = Math.min(minZ, z);
            maxZ = Math.max(maxZ, z + d - 1);
            baseY = Math.min(baseY, c.relativePosition().y());
        }
        return new Footprint(minX, maxX, minZ, maxZ, baseY);
    }

    private static Footprint buildingFootprint(List<Component> comps) {
        List<Component> members = new ArrayList<>();
        for (Component c : comps) {
            if (c == null || c.relativePosition() == null || c.dimensions() == null) continue;
            String mid = String.valueOf(c.params() == null ? "" : c.params().get("component_id"));
            if (mid.startsWith("pagoda_tier_") || RequestIntentInjector.countsTowardBuildingEnvelope(c)) {
                members.add(c);
            }
        }
        return shaftFootprint(members);
    }

    /** Solid place-block height inside footprint, ignoring cells below shaft/building baseY. */
    private static int footprintSolidHeight(Map<Vec3i, String> blocks, Footprint fp) {
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (Vec3i p : blocks.keySet()) {
            if (p.y() < fp.baseY) continue; // foundation / terrain below host base
            if (p.x() < fp.minX || p.x() > fp.maxX || p.z() < fp.minZ || p.z() > fp.maxZ) continue;
            String id = blocks.get(p);
            if (id == null || id.contains("air")) continue;
            minY = Math.min(minY, p.y());
            maxY = Math.max(maxY, p.y());
        }
        assertTrue(minY != Integer.MAX_VALUE, "no solid blocks inside building footprint");
        return maxY - minY + 1;
    }

    private static List<Component> stackedTight(int[] heights) {
        List<Component> tiers = new ArrayList<>();
        int y = 0;
        for (int i = 0; i < heights.length; i++) {
            Map<String, Object> params = new HashMap<>();
            params.put("component_id", "pagoda_tier_" + (i + 1));
            params.put("design_sources", Map.of("height", "AI_PLANNED"));
            params.put("plan_type", "rectangle");
            params.put("anchor_mode", "min_corner");
            params.put("roof_type", i == heights.length - 1 ? "xieshan" : "flat");
            tiers.add(new Component("MASS_MAIN", "pagoda",
                    new Vec3i(0, y, 0),
                    new Dimensions(Math.max(4, 12 - i), Math.max(4, 12 - i), heights[i]),
                    List.of(), params));
            y += heights[i];
        }
        return tiers;
    }
}
