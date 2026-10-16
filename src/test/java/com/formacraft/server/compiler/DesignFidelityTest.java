package com.formacraft.server.compiler;

import com.formacraft.common.generation.routing.GenerationAuthority;
import com.formacraft.common.generation.routing.PlanMetaAccess;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.LlmPlanTestFixtures;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.llm.parser.LlmPlanParser;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.proportion.OpeningGrammarResolver;
import com.formacraft.server.assembly.AssemblyCompileDiagnostics;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import com.formacraft.test.PatchTestSnapshot;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Design fidelity suite: fixtures under {@code /plans/fidelity/} must survive parse → compile
 * without silent design mutation (satellites, roof pitch, provenance locks).
 */
class DesignFidelityTest {

    @BeforeAll
    static void bootstrap() {
        MinecraftRegistryTestBootstrap.initialize();
    }

    @AfterEach
    void clearDiagnostics() {
        AssemblyCompileDiagnostics.clear();
    }

    @Test
    void massOnlyWithoutSatellitesFailsWhenCompilerInferDisabled() {
        Component mass = new Component(
                "MASS_MAIN",
                "body",
                new Vec3i(0, 0, 0),
                new Dimensions(12, 10, 8),
                List.of(),
                Map.of());
        Map<String, Object> planMeta = new HashMap<>();
        planMeta.put("allow_compiler_satellites", false);
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .components(List.of(mass))
                .planMeta(planMeta)
                .build();

        var patches = ComponentPlanCompiler.compile(plan, null, null, null, false);
        assertTrue(patches.isEmpty());
        assertTrue(AssemblyCompileDiagnostics.hasGap());
        assertEquals("E_DESIGN_INCOMPLETE", AssemblyCompileDiagnostics.get().code());
    }

    @Test
    void massOnlyFixtureReportsDesignIncomplete() throws Exception {
        LlmPlan plan = loadFidelityPlan("mass_only_no_infer.json");
        assertNotEquals(Boolean.TRUE, plan.planMeta() == null ? null : plan.planMeta().get("allow_compiler_satellites"));

        var patches = ComponentPlanCompiler.compile(plan, BlockPos.ORIGIN, null, null, false);
        assertTrue(patches.isEmpty());
        assertTrue(AssemblyCompileDiagnostics.hasGap());
        assertEquals("E_DESIGN_INCOMPLETE", AssemblyCompileDiagnostics.get().code());
    }

    @Test
    void authorityContractComponentsFixturePreservesLockedRatio() throws Exception {
        LlmPlan plan = loadFidelityPlan("authority_contract_components.json");
        assertEquals(GenerationAuthority.COMPONENTS, PlanMetaAccess.planGenerationAuthority(plan));
        assertFalse(PlanMetaAccess.allowTypologyEmptyFallback(null, plan.planMeta()));
        assertFalse(PlanMetaAccess.allowStructureEmptyFallback(null, plan.planMeta()));
        assertEquals("AI_PLANNED", plan.designSources().get("window_ratio"));

        Component facade = plan.components().stream()
                .filter(c -> c != null && "FACADE_WINDOWS".equalsIgnoreCase(c.componentType()))
                .findFirst()
                .orElseThrow();
        Component afterOpening = OpeningGrammarResolver.apply(plan, facade);
        assertEquals(0.42, ((Number) afterOpening.params().get("window_ratio")).doubleValue(), 1e-9);

        List<BlockPatch> patches = ComponentPlanCompiler.compile(plan, BlockPos.ORIGIN, null, null, false);
        assertFalse(AssemblyCompileDiagnostics.hasGap(),
                () -> String.valueOf(AssemblyCompileDiagnostics.get()));
        assertFalse(patches.isEmpty());
    }

    @Test
    void lockedWindowRatioSurvivesOpeningGrammarAndCompile() throws Exception {
        LlmPlan plan = loadFidelityPlan("locked_window_ratio.json");
        Component facade = plan.components().stream()
                .filter(c -> c != null && "FACADE_WINDOWS".equalsIgnoreCase(c.componentType()))
                .findFirst()
                .orElseThrow();
        assertEquals(0.42, ((Number) facade.params().get("window_ratio")).doubleValue(), 1e-9);

        Component afterOpening = OpeningGrammarResolver.apply(plan, facade);
        assertEquals(0.42, ((Number) afterOpening.params().get("window_ratio")).doubleValue(), 1e-9,
                "component-level AI_PLANNED window_ratio must not be clamped");

        List<BlockPatch> patches = ComponentPlanCompiler.compile(plan, BlockPos.ORIGIN, null, null, false);
        assertFalse(AssemblyCompileDiagnostics.hasGap(),
                () -> String.valueOf(AssemblyCompileDiagnostics.get()));
        assertFalse(patches.isEmpty());
        assertTrue(patches.size() > 20,
                "0.42 window_ratio plan should produce substantial facade opening geometry");
    }

    @Test
    void unknownRoofTypeFailsCompileWithUnsupportedError() throws Exception {
        LlmPlan plan = loadFidelityPlan("unknown_roof_type.json");
        List<BlockPatch> patches = ComponentPlanCompiler.compile(plan, BlockPos.ORIGIN, null, null, false);
        assertTrue(patches.isEmpty());
        assertTrue(AssemblyCompileDiagnostics.hasGap());
        assertEquals("E_ROOF_TYPE_UNSUPPORTED", AssemblyCompileDiagnostics.get().code());
    }

    @Test
    void conflictingRoofHeightAndPitchFailsCompile() throws Exception {
        LlmPlan plan = loadFidelityPlan("conflicting_roof_height_pitch.json");
        List<BlockPatch> patches = ComponentPlanCompiler.compile(plan, BlockPos.ORIGIN, null, null, false);
        assertTrue(patches.isEmpty());
        assertTrue(AssemblyCompileDiagnostics.hasGap());
        assertEquals("E_ROOF_GEOMETRY_CONFLICT", AssemblyCompileDiagnostics.get().code());
    }

    @Test
    void gothicSteepRoofDocumentsPitchFidelityBaseline() throws Exception {
        LlmPlan plan = loadFidelityPlan("gothic_steep_roof.json");
        assertEquals("AI_PLANNED", plan.designSources().get("roof_pitch_degrees"),
                "baseline: AI_PLANNED pitch must survive to compile geometry");
    }

    @Test
    void crownRequiredWithExplicitCrownCompiles() throws Exception {
        LlmPlan plan = loadFidelityPlan("crown_required_present.json");
        assertEquals("REQUIRED",
                ((Map<?, ?>) plan.planMeta().get("design_requirements")).get("crown"));

        List<BlockPatch> patches = ComponentPlanCompiler.compile(plan, BlockPos.ORIGIN, null, null, false);
        assertFalse(AssemblyCompileDiagnostics.hasGap(),
                () -> String.valueOf(AssemblyCompileDiagnostics.get()));
        assertFalse(patches.isEmpty(), "REQUIRED crown plan with explicit CROWN must compile");
    }

    @Test
    void invalidRoofHeightAndPitchFailsCompile() throws Exception {
        LlmPlan plan = loadFidelityPlan("invalid_roof_height_pitch.json");
        List<BlockPatch> patches = ComponentPlanCompiler.compile(plan, BlockPos.ORIGIN, null, null, false);
        assertTrue(patches.isEmpty());
        assertTrue(AssemblyCompileDiagnostics.hasGap());
        assertEquals("E_ROOF_GEOMETRY_INVALID", AssemblyCompileDiagnostics.get().code());
    }

    @Test
    void windowAspectStyleConflictFailsCompile() throws Exception {
        LlmPlan plan = loadFidelityPlan("window_aspect_style_conflict.json");
        List<BlockPatch> patches = ComponentPlanCompiler.compile(plan, BlockPos.ORIGIN, null, null, false);
        assertTrue(patches.isEmpty());
        assertTrue(AssemblyCompileDiagnostics.hasGap());
        assertEquals("E_WINDOW_ASPECT_CONFLICT", AssemblyCompileDiagnostics.get().code());
    }

    @Test
    void floorLayoutOverflowFailsCompile() throws Exception {
        LlmPlan plan = loadFidelityPlan("floor_layout_overflow.json");
        List<BlockPatch> patches = ComponentPlanCompiler.compile(plan, BlockPos.ORIGIN, null, null, false);
        assertTrue(patches.isEmpty());
        assertTrue(AssemblyCompileDiagnostics.hasGap());
        assertEquals("E_FLOOR_LAYOUT_OVERFLOW", AssemblyCompileDiagnostics.get().code());
    }

    @Test
    void roofTypeNoneProducesNoRoofRise() throws Exception {
        LlmPlan plan = loadFidelityPlan("roof_type_none.json");
        List<BlockPatch> patches = ComponentPlanCompiler.compile(plan, BlockPos.ORIGIN, null, null, false);
        assertFalse(AssemblyCompileDiagnostics.hasGap(),
                () -> String.valueOf(AssemblyCompileDiagnostics.get()));
        assertFalse(patches.isEmpty());
        int massHeight = plan.components().stream()
                .filter(c -> c != null && "MASS_MAIN".equalsIgnoreCase(c.componentType()))
                .findFirst()
                .orElseThrow()
                .dimensions()
                .height();
        int maxY = patches.stream().mapToInt(BlockPatch::dy).max().orElse(-1);
        assertTrue(maxY < massHeight,
                "roof_type=none must not emit blocks above mass envelope, maxY=" + maxY);
    }

    @Test
    void courtyardRatio075PreservesLargeHole() throws Exception {
        LlmPlan plan = loadFidelityPlan("courtyard_ratio_075.json");
        List<BlockPatch> patches = ComponentPlanCompiler.compile(plan, BlockPos.ORIGIN, null, null, false);
        assertFalse(AssemblyCompileDiagnostics.hasGap(),
                () -> String.valueOf(AssemblyCompileDiagnostics.get()));
        assertFalse(patches.isEmpty());
        var blocks = PatchTestSnapshot.blocks(patches);
        // Mid-height interior of a 20x20 courtyard at ratio 0.75 should be open (no solid wall).
        assertFalse(blocks.containsKey(new com.formacraft.common.llm.dto.Vec3i(10, 2, 10)),
                "courtyard_ratio=0.75 must leave a large central void");
    }

    @Test
    void courtyardRatioUnfitFailsCompile() throws Exception {
        LlmPlan plan = loadFidelityPlan("courtyard_ratio_unfit.json");
        List<BlockPatch> patches = ComponentPlanCompiler.compile(plan, BlockPos.ORIGIN, null, null, false);
        assertTrue(patches.isEmpty());
        assertTrue(AssemblyCompileDiagnostics.hasGap());
        assertEquals("E_COURTYARD_GEOMETRY_CONFLICT", AssemblyCompileDiagnostics.get().code());
    }

    @Test
    void wallThickness6SurvivesToFacadeCarve() throws Exception {
        LlmPlan plan = loadFidelityPlan("wall_thickness_6.json");
        List<BlockPatch> patches = ComponentPlanCompiler.compile(plan, BlockPos.ORIGIN, null, null, false);
        assertFalse(AssemblyCompileDiagnostics.hasGap(),
                () -> String.valueOf(AssemblyCompileDiagnostics.get()));
        assertFalse(patches.isEmpty());
        long inwardAir = patches.stream()
                .filter(p -> BlockPatch.REMOVE.equalsIgnoreCase(p.action())
                        || (p.targetBlock() != null && p.targetBlock().toLowerCase().contains("air")))
                .count();
        assertTrue(inwardAir >= 5,
                "wall_thickness=6 facade should carve multiple inward air layers, got " + inwardAir);
    }

    @Test
    void overhangBboxConflictFailsCompile() throws Exception {
        LlmPlan plan = loadFidelityPlan("overhang_bbox_conflict.json");
        List<BlockPatch> patches = ComponentPlanCompiler.compile(plan, BlockPos.ORIGIN, null, null, false);
        assertTrue(patches.isEmpty());
        assertTrue(AssemblyCompileDiagnostics.hasGap());
        assertEquals("E_ROOF_OVERHANG_CONFLICT", AssemblyCompileDiagnostics.get().code());
    }

    @Test
    void setbackRatioExplicitCompilesWithoutMutation() throws Exception {
        LlmPlan plan = loadFidelityPlan("setback_ratio_explicit.json");
        Component mass = plan.components().stream()
                .filter(c -> c != null && "MASS_MAIN".equalsIgnoreCase(c.componentType()))
                .findFirst()
                .orElseThrow();
        assertEquals(0.12, ((Number) mass.params().get("setback_ratio")).doubleValue(), 1e-9);
        List<BlockPatch> patches = ComponentPlanCompiler.compile(plan, BlockPos.ORIGIN, null, null, false);
        assertFalse(AssemblyCompileDiagnostics.hasGap(),
                () -> String.valueOf(AssemblyCompileDiagnostics.get()));
        assertFalse(patches.isEmpty());
    }

    @Test
    void explicitSquareAspectSurvivesLargeFeatureCompile() throws Exception {
        LlmPlan plan = loadFidelityPlan("explicit_square_window_large.json");
        List<BlockPatch> patches = ComponentPlanCompiler.compile(plan, BlockPos.ORIGIN, null, null, false);
        assertFalse(AssemblyCompileDiagnostics.hasGap(),
                () -> String.valueOf(AssemblyCompileDiagnostics.get()));
        assertFalse(patches.isEmpty());
        var blocks = PatchTestSnapshot.blocks(patches);
        long glass = blocks.values().stream()
                .filter(id -> id != null && id.toLowerCase().contains("glass"))
                .count();
        assertTrue(glass > 0, "explicit square facade must still produce glazing");
    }

    @Test
    void unsupportedPlanTypeFailsCompile() throws Exception {
        LlmPlan plan = loadFidelityPlan("plan_type_unsupported.json");
        List<BlockPatch> patches = ComponentPlanCompiler.compile(plan, BlockPos.ORIGIN, null, null, false);
        assertTrue(patches.isEmpty());
        assertTrue(AssemblyCompileDiagnostics.hasGap());
        assertEquals("E_PLAN_PATTERN_UNSUPPORTED", AssemblyCompileDiagnostics.get().code());
    }

    @Test
    void unsupportedFootprintShapeFailsCompile() throws Exception {
        LlmPlan plan = loadFidelityPlan("footprint_shape_unsupported.json");
        List<BlockPatch> patches = ComponentPlanCompiler.compile(plan, BlockPos.ORIGIN, null, null, false);
        assertTrue(patches.isEmpty());
        assertTrue(AssemblyCompileDiagnostics.hasGap());
        assertEquals("E_FOOTPRINT_SHAPE_UNSUPPORTED", AssemblyCompileDiagnostics.get().code());
    }

    @Test
    void circleCrossCombinationFailsCompile() throws Exception {
        LlmPlan plan = loadFidelityPlan("circle_cross_combo_unsupported.json");
        List<BlockPatch> patches = ComponentPlanCompiler.compile(plan, BlockPos.ORIGIN, null, null, false);
        assertTrue(patches.isEmpty());
        assertTrue(AssemblyCompileDiagnostics.hasGap());
        assertEquals("E_FOOTPRINT_COMBINATION_UNSUPPORTED", AssemblyCompileDiagnostics.get().code());
    }

    @Test
    void gothicStyleDoesNotForceCrossPlanType() throws Exception {
        LlmPlan plan = loadFidelityPlan("gothic_style_no_forced_cross.json");
        Component mass = plan.components().stream()
                .filter(c -> c != null && "MASS_MAIN".equalsIgnoreCase(c.componentType()))
                .findFirst()
                .orElseThrow();
        assertFalse(mass.params() != null && mass.params().containsKey("plan_type"),
                "style must not invent plan_type before compile");

        List<BlockPatch> patches = ComponentPlanCompiler.compile(plan, BlockPos.ORIGIN, null, null, false);
        assertFalse(AssemblyCompileDiagnostics.hasGap(),
                () -> String.valueOf(AssemblyCompileDiagnostics.get()));
        assertFalse(patches.isEmpty());

        // After StyleIntent + resolve, footprint must stay rectangular (NONE), not cruciform.
        var geom = com.formacraft.common.generation.component.util.ResolvedBuildingGeometry.resolve(mass);
        assertNotNull(geom);
        assertEquals(com.formacraft.common.generation.component.util.ResolvedFootprint.PlanPattern.NONE,
                geom.footprint().pattern());
        assertFalse(mass.params() != null && "cross".equals(String.valueOf(mass.params().get("plan_type"))),
                "compiled mass must not receive style-forced plan_type=cross");
    }

    @Test
    void negativeCourtyardRatioFailsCompileAsInvalid() throws Exception {
        LlmPlan plan = loadFidelityPlan("courtyard_ratio_negative.json");
        List<BlockPatch> patches = ComponentPlanCompiler.compile(plan, BlockPos.ORIGIN, null, null, false);
        assertTrue(patches.isEmpty());
        assertTrue(AssemblyCompileDiagnostics.hasGap());
        assertEquals("E_COURTYARD_RATIO_INVALID", AssemblyCompileDiagnostics.get().code());
    }

    @Test
    void negativeWallThicknessFailsCompileAsInvalid() throws Exception {
        LlmPlan plan = loadFidelityPlan("wall_thickness_negative.json");
        List<BlockPatch> patches = ComponentPlanCompiler.compile(plan, BlockPos.ORIGIN, null, null, false);
        assertTrue(patches.isEmpty());
        assertTrue(AssemblyCompileDiagnostics.hasGap());
        assertEquals("E_WALL_THICKNESS_INVALID", AssemblyCompileDiagnostics.get().code());
    }

    @Test
    void thickWallCourtyardRoofLeavesHoleOpenInMassAndRoof() throws Exception {
        LlmPlan plan = loadFidelityPlan("courtyard_thick_wall_roof.json");
        List<BlockPatch> patches = ComponentPlanCompiler.compile(plan, BlockPos.ORIGIN, null, null, false);
        assertFalse(AssemblyCompileDiagnostics.hasGap(),
                () -> String.valueOf(AssemblyCompileDiagnostics.get()));
        assertFalse(patches.isEmpty());

        var fp = com.formacraft.common.generation.component.util.ResolvedFootprint.resolve(
                plan.components().getFirst(), 48, 48);
        int cx = (fp.holeMinX() + fp.holeMaxX()) / 2;
        int cz = (fp.holeMinZ() + fp.holeMaxZ()) / 2;
        assertTrue(fp.isCourtyardHole(cx, cz));

        var blocks = PatchTestSnapshot.blocks(patches);
        // Mass shell mid-height and roof layers must not fill the courtyard column.
        // y=0 may hold COURTYARD_FLOOR; above that (and roof attachment) must stay open.
        for (int y = 1; y <= 10; y++) {
            assertFalse(blocks.containsKey(new Vec3i(cx, y, cz)),
                    "courtyard column must stay open at (" + cx + "," + y + "," + cz + ")"
                            + " block=" + blocks.get(new Vec3i(cx, y, cz)));
        }
    }

    @Test
    void fourStoreyFacadeWindowBandsAlignWithFloorLayout() throws Exception {
        LlmPlan plan = loadFidelityPlan("four_storey_mass_facade.json");
        Component mass = plan.components().stream()
                .filter(c -> c != null && "MASS_MAIN".equalsIgnoreCase(c.componentType()))
                .findFirst()
                .orElseThrow();
        var layout = com.formacraft.common.generation.component.util.ResolvedFloorLayout.from(
                mass, mass.dimensions().height());
        assertEquals(List.of(0, 4, 8, 12), layout.floorYs());

        List<BlockPatch> patches = ComponentPlanCompiler.compile(plan, BlockPos.ORIGIN, null, null, false);
        assertFalse(AssemblyCompileDiagnostics.hasGap(),
                () -> String.valueOf(AssemblyCompileDiagnostics.get()));
        var blocks = PatchTestSnapshot.blocks(patches);
        var glassYs = blocks.entrySet().stream()
                .filter(e -> e.getValue() != null && e.getValue().toLowerCase().contains("glass"))
                .map(e -> e.getKey().y())
                .collect(java.util.stream.Collectors.toSet());
        assertFalse(glassYs.isEmpty(), "expected facade glass");
        // sill_offset=1 → glass starts at floorY+1 within each band of height 4.
        for (int floorY : layout.floorYs()) {
            int sill = floorY + 1;
            assertTrue(glassYs.contains(sill) || glassYs.contains(sill + 1),
                    "expected glass in floor band starting at " + floorY + ", glassYs=" + glassYs);
        }
    }

    @Test
    void dualMassSharedFloorHeightProducesAlignedFloorYs() throws Exception {
        LlmPlan plan = loadFidelityPlan("dual_mass_shared_floor_height.json");
        var masses = plan.components().stream()
                .filter(c -> c != null && "MASS_MAIN".equalsIgnoreCase(c.componentType()))
                .toList();
        assertEquals(2, masses.size());
        var a = com.formacraft.common.generation.component.util.ResolvedFloorLayout.from(
                masses.get(0), masses.get(0).dimensions().height());
        var b = com.formacraft.common.generation.component.util.ResolvedFloorLayout.from(
                masses.get(1), masses.get(1).dimensions().height());
        assertEquals(a.floorYs(), b.floorYs());
        assertEquals(List.of(0, 4, 8), a.floorYs());

        List<BlockPatch> patches = ComponentPlanCompiler.compile(plan, BlockPos.ORIGIN, null, null, false);
        assertFalse(AssemblyCompileDiagnostics.hasGap(),
                () -> String.valueOf(AssemblyCompileDiagnostics.get()));
        assertFalse(patches.isEmpty());
    }

    @Test
    void courtyardHostedInteriorPlateSkipsCourtyardHole() throws Exception {
        LlmPlan plan = loadFidelityPlan("courtyard_hosted_interior_plate.json");
        List<BlockPatch> patches = ComponentPlanCompiler.compile(plan, BlockPos.ORIGIN, null, null, false);
        assertFalse(AssemblyCompileDiagnostics.hasGap(),
                () -> String.valueOf(AssemblyCompileDiagnostics.get()));
        var fp = com.formacraft.common.generation.component.util.ResolvedFootprint.resolve(
                plan.components().getFirst(), 24, 24);
        int cx = (fp.holeMinX() + fp.holeMaxX()) / 2;
        int cz = (fp.holeMinZ() + fp.holeMaxZ()) / 2;
        var blocks = PatchTestSnapshot.blocks(patches);
        assertFalse(isSprucePlate(blocks.get(new Vec3i(cx, 4, cz))),
                "courtyard hole must not receive hosted interior plate");
        assertTrue(isSprucePlate(blocks.get(new Vec3i(2, 4, 2))),
                "interior shell ring must receive hosted plate");
    }

    @Test
    void lShapeHostedInteriorPlateSkipsCutCorner() throws Exception {
        LlmPlan plan = loadFidelityPlan("l_shape_hosted_interior_plate.json");
        List<BlockPatch> patches = ComponentPlanCompiler.compile(plan, BlockPos.ORIGIN, null, null, false);
        assertFalse(AssemblyCompileDiagnostics.hasGap(),
                () -> String.valueOf(AssemblyCompileDiagnostics.get()));
        var fp = com.formacraft.common.generation.component.util.ResolvedFootprint.resolve(
                plan.components().getFirst(), 18, 18);
        assertFalse(fp.contains(15, 2), "expected NE cut void");
        assertTrue(fp.contains(2, 2));
        var blocks = PatchTestSnapshot.blocks(patches);
        assertFalse(isSprucePlate(blocks.get(new Vec3i(15, 4, 2))),
                "L-cut void must not receive hosted interior plate");
        assertTrue(isSprucePlate(blocks.get(new Vec3i(2, 4, 2))),
                "L arm must receive hosted plate");
    }

    @Test
    void crossHostedInteriorPlateSkipsCornerVoid() throws Exception {
        LlmPlan plan = loadFidelityPlan("cross_hosted_interior_plate.json");
        List<BlockPatch> patches = ComponentPlanCompiler.compile(plan, BlockPos.ORIGIN, null, null, false);
        assertFalse(AssemblyCompileDiagnostics.hasGap(),
                () -> String.valueOf(AssemblyCompileDiagnostics.get()));
        var fp = com.formacraft.common.generation.component.util.ResolvedFootprint.resolve(
                plan.components().getFirst(), 18, 18);
        assertFalse(fp.contains(1, 1), "expected cross corner void");
        assertTrue(fp.contains(9, 1));
        var blocks = PatchTestSnapshot.blocks(patches);
        assertFalse(isSprucePlate(blocks.get(new Vec3i(1, 4, 1))),
                "cross corner void must not receive hosted interior plate");
        assertTrue(isSprucePlate(blocks.get(new Vec3i(9, 4, 1))),
                "cross arm must receive hosted plate");
    }

    @Test
    void steppedHostedInteriorPlateRespectsUpperLayer() throws Exception {
        LlmPlan plan = loadFidelityPlan("stepped_hosted_interior_plate.json");
        Component mass = plan.components().getFirst();
        var geom = com.formacraft.common.generation.component.util.ResolvedBuildingGeometry.resolve(mass);
        assertNotNull(geom);
        var layer = geom.layers().get(8);
        int outsideX = 0;
        int outsideZ = 0;
        boolean found = false;
        for (int x = 0; x < 20 && !found; x++) {
            for (int z = 0; z < 16; z++) {
                if (geom.footprint().contains(x, z)
                        && !com.formacraft.common.generation.component.util.ResolvedFacadeLayers
                        .contains(layer, x, z)) {
                    outsideX = x;
                    outsideZ = z;
                    found = true;
                    break;
                }
            }
        }
        assertTrue(found, "expected a ground-footprint cell outside upper setback layer");

        List<BlockPatch> patches = ComponentPlanCompiler.compile(plan, BlockPos.ORIGIN, null, null, false);
        assertFalse(AssemblyCompileDiagnostics.hasGap(),
                () -> String.valueOf(AssemblyCompileDiagnostics.get()));
        var blocks = PatchTestSnapshot.blocks(patches);
        assertFalse(isSprucePlate(blocks.get(new Vec3i(outsideX, 8, outsideZ))),
                "setback shoulder must not receive upper hosted plate at ("
                        + outsideX + ",8," + outsideZ + ")");
        int insideX = layer.xOffset + layer.width / 2;
        int insideZ = layer.zOffset + layer.depth / 2;
        assertTrue(isSprucePlate(blocks.get(new Vec3i(insideX, 8, insideZ))),
                "upper layer core must receive hosted plate");
    }

    @Test
    void terraceHostedPlateMayOverhangHostFootprint() throws Exception {
        LlmPlan plan = loadFidelityPlan("terrace_hosted_plate_overhang.json");
        List<BlockPatch> patches = ComponentPlanCompiler.compile(plan, BlockPos.ORIGIN, null, null, false);
        assertFalse(AssemblyCompileDiagnostics.hasGap(),
                () -> String.valueOf(AssemblyCompileDiagnostics.get()));
        var blocks = PatchTestSnapshot.blocks(patches);
        assertTrue(isSprucePlate(blocks.get(new Vec3i(-2, 4, 6))),
                "plate_role=terrace must place overhang outside host footprint");
        assertTrue(isSprucePlate(blocks.get(new Vec3i(6, 4, 6))),
                "terrace plate must still cover host interior");
    }

    @Test
    void mezzanineHostedPlateKeepsExplicitY() throws Exception {
        LlmPlan plan = loadFidelityPlan("mezzanine_hosted_plate_explicit_y.json");
        List<BlockPatch> patches = ComponentPlanCompiler.compile(plan, BlockPos.ORIGIN, null, null, false);
        assertFalse(AssemblyCompileDiagnostics.hasGap(),
                () -> String.valueOf(AssemblyCompileDiagnostics.get()));
        var blocks = PatchTestSnapshot.blocks(patches);
        // floor_height=5 would snap inferred plates to Y=5 or 10; authored mezzanine is Y=7.
        assertTrue(isSprucePlate(blocks.get(new Vec3i(2, 7, 2))),
                "explicit mezzanine Y must not be storey-snapped");
        assertFalse(isSprucePlate(blocks.get(new Vec3i(2, 5, 2))),
                "must not snap mezzanine down to nearest storey");
        assertFalse(isSprucePlate(blocks.get(new Vec3i(2, 10, 2))),
                "must not snap mezzanine up to nearest storey");
    }

    @Test
    void hostedPlateOutsideHostEnvelopeFails() throws Exception {
        LlmPlan plan = loadFidelityPlan("hosted_plate_level_outside_host.json");
        List<BlockPatch> patches = ComponentPlanCompiler.compile(plan, BlockPos.ORIGIN, null, null, false);
        assertTrue(patches.isEmpty());
        assertTrue(AssemblyCompileDiagnostics.hasGap());
        assertEquals("E_HOSTED_PLATE_LEVEL_CONFLICT", AssemblyCompileDiagnostics.get().code());
    }

    @Test
    void hostedPlateUnresolvedHostFails() throws Exception {
        LlmPlan plan = loadFidelityPlan("hosted_plate_host_unresolved.json");
        List<BlockPatch> patches = ComponentPlanCompiler.compile(plan, BlockPos.ORIGIN, null, null, false);
        assertTrue(patches.isEmpty());
        assertTrue(AssemblyCompileDiagnostics.hasGap());
        assertEquals("E_HOST_ID_UNRESOLVED", AssemblyCompileDiagnostics.get().code());
    }

    @Test
    void unsupportedPlateRoleFailsCompile() throws Exception {
        LlmPlan plan = loadFidelityPlan("plate_role_unsupported.json");
        List<BlockPatch> patches = ComponentPlanCompiler.compile(plan, BlockPos.ORIGIN, null, null, false);
        assertTrue(patches.isEmpty());
        assertTrue(AssemblyCompileDiagnostics.hasGap());
        assertEquals("E_PLATE_ROLE_UNSUPPORTED", AssemblyCompileDiagnostics.get().code());
    }

    @Test
    void bridgeHostedPlateMayOverhangHostFootprint() throws Exception {
        LlmPlan plan = loadFidelityPlan("bridge_hosted_plate_overhang.json");
        List<BlockPatch> patches = ComponentPlanCompiler.compile(plan, BlockPos.ORIGIN, null, null, false);
        assertFalse(AssemblyCompileDiagnostics.hasGap(),
                () -> String.valueOf(AssemblyCompileDiagnostics.get()));
        var blocks = PatchTestSnapshot.blocks(patches);
        // Plate spans body↔pier (x=-3..11); overhang is west of body (x<0).
        boolean overhang = blocks.entrySet().stream().anyMatch(e ->
                e.getKey().y() == 4 && e.getKey().x() < 0 && isSprucePlate(e.getValue()));
        assertTrue(overhang, "plate_role=bridge must place overhang outside host footprint");
        assertTrue(isSprucePlate(blocks.get(new Vec3i(6, 4, 6))),
                "bridge plate must still cover host interior");
    }

    @Test
    void terraceRoofDeckAboveHostCompiles() throws Exception {
        LlmPlan plan = loadFidelityPlan("terrace_roof_deck_above_host.json");
        List<BlockPatch> patches = ComponentPlanCompiler.compile(plan, BlockPos.ORIGIN, null, null, false);
        assertFalse(AssemblyCompileDiagnostics.hasGap(),
                () -> String.valueOf(AssemblyCompileDiagnostics.get()));
        var blocks = PatchTestSnapshot.blocks(patches);
        assertTrue(isSprucePlate(blocks.get(new Vec3i(5, 9, 5))),
                "terrace may sit one block above host roof (height+1)");
    }

    @Test
    void terraceOverhangWithoutExposedEdgesKeepsAuthoredSize() throws Exception {
        LlmPlan plan = loadFidelityPlan("terrace_overhang_without_exposed_edges.json");
        List<BlockPatch> patches = ComponentPlanCompiler.compile(plan, BlockPos.ORIGIN, null, null, false);
        assertFalse(AssemblyCompileDiagnostics.hasGap(),
                () -> String.valueOf(AssemblyCompileDiagnostics.get()));
        var blocks = PatchTestSnapshot.blocks(patches);
        // wall_thickness=2 would inset interior plates; terrace must keep overhang at x=-2.
        assertTrue(isSprucePlate(blocks.get(new Vec3i(-2, 4, 6))),
                "terrace without exposed_floor_edges must not be wall-inset");
        assertTrue(isSprucePlate(blocks.get(new Vec3i(6, 4, 6))));
    }

    @Test
    void dualMassBridgeSpansBothEndpoints() throws Exception {
        LlmPlan plan = loadFidelityPlan("bridge_dual_mass_span.json");
        List<BlockPatch> patches = ComponentPlanCompiler.compile(plan, BlockPos.ORIGIN, null, null, false);
        assertFalse(AssemblyCompileDiagnostics.hasGap(),
                () -> String.valueOf(AssemblyCompileDiagnostics.get()));
        var blocks = PatchTestSnapshot.blocks(patches);
        // Plate (8,4,2) w=4 → x=8..11. Hall 0..9; gap 10–11; wing face at x=12.
        assertTrue(isSprucePlate(blocks.get(new Vec3i(9, 4, 3))), "hall-side bridge endpoint");
        assertTrue(isSprucePlate(blocks.get(new Vec3i(10, 4, 3))), "gap span");
        assertTrue(isSprucePlate(blocks.get(new Vec3i(11, 4, 3))),
                "wing-adjacent endpoint (flush to wing west face at x=12)");
        // Portal carve clears clear-height above the deck (Y+1 / Y+2), not the deck itself.
        assertTrue(isAirOrMissing(blocks, 9, 5, 3), "hall portal clear height");
        assertTrue(isAirOrMissing(blocks, 12, 5, 3), "wing portal clear height");
    }

    @Test
    void bridgeMissingTargetHostFails() throws Exception {
        LlmPlan plan = loadFidelityPlan("bridge_missing_target_host.json");
        List<BlockPatch> patches = ComponentPlanCompiler.compile(plan, BlockPos.ORIGIN, null, null, false);
        assertTrue(patches.isEmpty());
        assertTrue(AssemblyCompileDiagnostics.hasGap());
        assertEquals("E_TARGET_HOST_ID_REQUIRED", AssemblyCompileDiagnostics.get().code());
    }

    @Test
    void bridgeEndpointNoContactFails() throws Exception {
        LlmPlan plan = loadFidelityPlan("bridge_endpoint_no_contact.json");
        List<BlockPatch> patches = ComponentPlanCompiler.compile(plan, BlockPos.ORIGIN, null, null, false);
        assertTrue(patches.isEmpty());
        assertTrue(AssemblyCompileDiagnostics.hasGap());
        assertEquals("E_BRIDGE_ENDPOINT_NO_CONTACT", AssemblyCompileDiagnostics.get().code());
    }

    @Test
    void inferredBridgeKeepsAuthoredY() throws Exception {
        LlmPlan plan = loadFidelityPlan("inferred_bridge_keeps_y.json");
        List<BlockPatch> patches = ComponentPlanCompiler.compile(plan, BlockPos.ORIGIN, null, null, false);
        assertFalse(AssemblyCompileDiagnostics.hasGap(),
                () -> String.valueOf(AssemblyCompileDiagnostics.get()));
        var blocks = PatchTestSnapshot.blocks(patches);
        // floor_height=5 would snap interior inferred plates to 5/10; bridge must keep Y=7.
        assertTrue(isSprucePlate(blocks.get(new Vec3i(9, 7, 3))),
                "inferred bridge must not storey-snap away from authored Y=7");
        assertFalse(isSprucePlate(blocks.get(new Vec3i(9, 5, 3))));
        assertFalse(isSprucePlate(blocks.get(new Vec3i(9, 10, 3))));
    }

    @Test
    void duplicateComponentIdFailsCompile() throws Exception {
        LlmPlan plan = loadFidelityPlan("duplicate_component_id.json");
        List<BlockPatch> patches = ComponentPlanCompiler.compile(plan, BlockPos.ORIGIN, null, null, false);
        assertTrue(patches.isEmpty());
        assertTrue(AssemblyCompileDiagnostics.hasGap());
        assertEquals("E_DUPLICATE_COMPONENT_ID", AssemblyCompileDiagnostics.get().code());
    }

    @Test
    void gothicSteepRoofFixtureCompilesWithPitchAboveTechnicalCap() throws Exception {
        LlmPlan plan = loadFidelityPlan("gothic_steep_roof.json");

        assertEquals("AI_PLANNED", plan.designSources().get("roof_pitch_degrees"));
        assertEquals("AI_PLANNED", plan.designSources().get("roof_type"));
        assertNotEquals(Boolean.TRUE, plan.planMeta().get("allow_compiler_satellites"));

        Component roof = plan.components().stream()
                .filter(c -> c != null && "ROOF".equalsIgnoreCase(c.componentType()))
                .findFirst()
                .orElseThrow();
        assertEquals(58, ((Number) roof.params().get("roof_pitch_degrees")).intValue());

        List<BlockPatch> patches = ComponentPlanCompiler.compile(plan, BlockPos.ORIGIN, null, null, false);
        assertFalse(AssemblyCompileDiagnostics.hasGap(),
                () -> String.valueOf(AssemblyCompileDiagnostics.get()));
        assertFalse(patches.isEmpty(), "gothic steep roof plan must produce geometry");

        // alignRoofToMass places the roof at mass.height-1; pitch 58° on span 20 → rise ≈ 16 (> cap 8).
        Component mass = plan.components().stream()
                .filter(c -> c != null && "MASS_MAIN".equalsIgnoreCase(c.componentType()))
                .findFirst()
                .orElseThrow();
        int roofBaseY = mass.dimensions().height() - 1;
        int maxRoofY = patches.stream()
                .filter(p -> p.dy() >= roofBaseY)
                .mapToInt(BlockPatch::dy)
                .max()
                .orElse(roofBaseY);
        int rise = maxRoofY - roofBaseY + 1;
        assertTrue(rise > 8, "expected steep gable rise > 8 after full compile, got " + rise);

        var blocks = PatchTestSnapshot.blocks(patches);
        assertFalse(blocks.isEmpty());
        assertTrue(blocks.keySet().stream().anyMatch(p -> p.y() >= roofBaseY + 9),
                "compiled blocks must reach above technical roof cap of 8");
    }

    private static boolean isSprucePlate(String blockId) {
        return blockId != null && blockId.toLowerCase().contains("spruce");
    }

    private static boolean isAirOrMissing(java.util.Map<Vec3i, String> blocks, int x, int y, int z) {
        String id = blocks.get(new Vec3i(x, y, z));
        return id == null || id.equalsIgnoreCase("minecraft:air") || id.toLowerCase().contains("air");
    }

    private static LlmPlan loadFidelityPlan(String fileName) throws Exception {
        String path = "/plans/fidelity/" + fileName;
        try (var in = DesignFidelityTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "missing fixture " + path);
            String json = new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8);
            return LlmPlanParser.parseAndValidate(json);
        }
    }
}
