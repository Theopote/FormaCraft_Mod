package com.formacraft.server.compiler;

import com.formacraft.common.generation.design.DesignDecisionPriority;
import com.formacraft.common.generation.design.DesignParamSource;
import com.formacraft.common.generation.design.DesignProvenance;
import com.formacraft.common.generation.routing.GenerationAuthority;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.GlobalConstraints;
import com.formacraft.common.llm.dto.Layout;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.LlmPlanTestFixtures;
import com.formacraft.common.llm.dto.Slot;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.style.AestheticPassHints;
import com.formacraft.common.style.ExplicitDesignPolicy;
import com.formacraft.server.assembly.AssemblyCompileDiagnostics;
import com.formacraft.server.generation.GenerationCompileContext;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import com.formacraft.test.PatchTestSnapshot;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 01.1-F: explicit-design priority + cross-building isolation regressions.
 */
class ExplicitDesignPriorityRegressionTest {

    @BeforeAll
    static void bootstrap() {
        MinecraftRegistryTestBootstrap.initialize();
    }

    @AfterEach
    void clear() {
        AssemblyCompileDiagnostics.clear();
    }

    @Test
    void explicitDisableRoofWindowsLightsAndOpenPavilion() {
        Component pavilion = mass("pav", 0, Map.of(
                "roof_type", "none",
                "window_style", "none",
                "entrance_type", "none",
                "wall_type", "none",
                "open", true,
                "lighting_style", "none"));
        LlmPlan pavilionPlan = LlmPlanTestFixtures.builder().allowCompilerSatellites(true)
                .mode(LlmPlan.Mode.build).styleProfile("DEFAULT").anchor(new Vec3i(0, 0, 0))
                .layout(new Layout(null, false, List.of(
                        new Slot("site", new Vec3i(0, 0, 0), GlobalConstraints.Facing.SOUTH, null, null, null))))
                .components(List.of(pavilion))
                .designSources(Map.of(
                        "roof_type", "USER_EXPLICIT",
                        "window_style", "USER_EXPLICIT",
                        "entrance_type", "USER_EXPLICIT",
                        "wall_type", "USER_EXPLICIT",
                        "lighting_style", "USER_EXPLICIT"))
                .build();
        assertTrue(ExplicitDesignPolicy.roofDisabled(pavilion));
        assertTrue(ExplicitDesignPolicy.windowsDisabled(pavilion));
        assertTrue(ExplicitDesignPolicy.entranceDisabled(pavilion));
        assertEquals(AestheticPassHints.LightingStyle.NONE,
                AestheticPassHints.resolve(pavilionPlan, pavilion.params()).lightingStyle());

        var prepared = ComponentSatelliteInference.prepareComponents(pavilionPlan, Map.of(), false);
        assertTrue(prepared.components().stream().noneMatch(c ->
                c.componentType() != null && (
                        c.componentType().toUpperCase().startsWith("ROOF")
                                || "FACADE_WINDOWS".equalsIgnoreCase(c.componentType())
                                || "ENTRANCE".equalsIgnoreCase(c.componentType()))),
                "open pavilion opt-outs must not infer roof/window/entrance satellites");

        Map<Vec3i, String> blocks = PatchTestSnapshot.blocks(
                ComponentPlanCompiler.compile(pavilionPlan, BlockPos.ORIGIN, null, null, false));
        assertFalse(blocks.isEmpty(), () -> String.valueOf(AssemblyCompileDiagnostics.get()));
        assertTrue(blocks.values().stream().noneMatch(v -> v != null && v.contains("glass")),
                "window_style=none must leave no glass");
        assertTrue(blocks.values().stream().noneMatch(v -> v != null && (
                v.contains("lantern") || v.contains("torch"))),
                "lighting_style=none must leave no lights");
        int maxY = blocks.keySet().stream().mapToInt(Vec3i::y).max().orElse(-1);
        assertTrue(maxY < 8, "roof_type=none must stay inside mass height, maxY=" + maxY);
    }

    @Test
    void explicitMaterialIsPreservedThroughCompile() {
        // Provenance is plan-level design_sources (String map) — nested params.design_sources
        // string values are not material ids and must not be fed to the material validator.
        Component hall = mass("hall", 0, Map.of(
                "wall_block", "minecraft:prismarine_bricks",
                "floor_block", "minecraft:dark_prismarine",
                "roof_type", "flat",
                "window_style", "none",
                "entrance_type", "none"));
        LlmPlan locked = LlmPlanTestFixtures.builder().allowCompilerSatellites(true)
                .mode(LlmPlan.Mode.build).styleProfile("DEFAULT").anchor(new Vec3i(0, 0, 0))
                .layout(new Layout(null, false, List.of(
                        new Slot("site", new Vec3i(0, 0, 0), GlobalConstraints.Facing.SOUTH, null, null, null))))
                .components(List.of(hall))
                .designSources(Map.of("wall_block", "USER_EXPLICIT", "floor_block", "USER_EXPLICIT"))
                .build();
        assertFalse(DesignProvenance.canMutateParam(locked, hall.params(), "wall_block"));
        Map<Vec3i, String> blocks = PatchTestSnapshot.blocks(
                ComponentPlanCompiler.compile(locked, BlockPos.ORIGIN, null, null, false));
        assertFalse(blocks.isEmpty(), () -> String.valueOf(AssemblyCompileDiagnostics.get()));
        assertTrue(blocks.values().stream().anyMatch(v -> v != null && v.contains("prismarine_bricks")),
                "USER_EXPLICIT wall_block must survive compile");
        assertTrue(blocks.values().stream().anyMatch(v -> v != null && v.contains("dark_prismarine")),
                "USER_EXPLICIT floor_block must survive compile");
    }

    @Test
    void aiPlannedDimensionsAreNotOverwrittenByStyleDefaults() {
        Map<String, Object> params = new HashMap<>(Map.of(
                "component_id", "wide",
                "hollow", true,
                "anchor_mode", "min_corner",
                "plan_type", "rectangle",
                "roof_type", "flat",
                "window_style", "none",
                "entrance_type", "none",
                "floor_height", 7,
                "wall_thickness", 2));
        params.put("design_sources", Map.of(
                "floor_height", "AI_PLANNED",
                "wall_thickness", "AI_PLANNED"));
        Component wide = new Component("MASS_MAIN", "site", new Vec3i(0, 0, 0),
                new Dimensions(21, 15, 14), List.of(), params);
        assertFalse(DesignProvenance.canMutateParam(plan(List.of(wide)), wide.params(), "floor_height"));
        assertFalse(DesignProvenance.canMutateParam(plan(List.of(wide)), wide.params(), "wall_thickness"));
        assertFalse(DesignProvenance.putIfAllowed(plan(List.of(wide)), new HashMap<>(wide.params()),
                "floor_height", 4),
                "STYLE_DEFAULT / technical fill must not overwrite AI_PLANNED floor_height");

        Map<Vec3i, String> blocks = PatchTestSnapshot.blocks(compile(List.of(wide)));
        assertFalse(blocks.isEmpty(), () -> String.valueOf(AssemblyCompileDiagnostics.get()));
        int maxY = blocks.keySet().stream().mapToInt(Vec3i::y).max().orElse(-1);
        assertTrue(maxY >= 13, "AI-planned height 14 must not collapse to a style default envelope, maxY=" + maxY);
    }

    @Test
    void oneBuildingNoComplexDecorInTwoBuildingSite() {
        Component quiet = mass("quiet", 0, Map.of(
                "no_complex_decor", true,
                "lighting_style", "none",
                "window_style", "none",
                "entrance_type", "none"));
        Component lively = mass("lively", 30, Map.of(
                "lighting_style", "warm_lantern",
                "window_style", "none",
                "entrance_type", "none"));
        LlmPlan site = plan(List.of(quiet, lively));
        assertTrue(ExplicitDesignPolicy.noComplexDecor(site, quiet.params()));
        assertFalse(ExplicitDesignPolicy.noComplexDecor(site, lively.params()),
                "plan-level décor must not leak from sibling mass params");
        assertEquals(AestheticPassHints.LightingStyle.NONE,
                AestheticPassHints.resolve(site, quiet.params()).lightingStyle());
        assertEquals(AestheticPassHints.LightingStyle.WARM_LANTERN,
                AestheticPassHints.resolve(site, lively.params()).lightingStyle());

        Map<Vec3i, String> blocks = PatchTestSnapshot.blocks(compile(List.of(quiet, lively)));
        assertFalse(blocks.isEmpty(), () -> String.valueOf(AssemblyCompileDiagnostics.get()));
        boolean lightsOnLively = blocks.entrySet().stream().anyMatch(e ->
                e.getKey().x() >= 30 && e.getValue() != null
                        && (e.getValue().contains("lantern") || e.getValue().contains("torch")));
        boolean lightsOnQuiet = blocks.entrySet().stream().anyMatch(e ->
                e.getKey().x() < 20 && e.getValue() != null
                        && (e.getValue().contains("lantern") || e.getValue().contains("torch")));
        assertTrue(lightsOnLively, "lively building should receive warm_lantern placement");
        assertFalse(lightsOnQuiet, "quiet building no_complex_decor/lighting none must stay dark");
    }

    @Test
    void userExplicitVersusHardConstraintSurfacesDiagnostic() {
        // dense_eaves_pagoda cannot execute roof_type=none — HARD_CONSTRAINT capability table.
        assertTrue(DesignDecisionPriority.requiresCapabilityGap(
                DesignParamSource.USER_EXPLICIT, true));
        Component shell = new Component("STRUCTURE", "pagoda", new Vec3i(0, 0, 0),
                new Dimensions(9, 9, 28), List.of("typology:dense_eaves_pagoda"),
                Map.of("component_id", "pagoda_shell",
                        "typology_id", "dense_eaves_pagoda",
                        "roof_type", "none"));
        LlmPlan locked = LlmPlanTestFixtures.builder().allowCompilerSatellites(true)
                .mode(LlmPlan.Mode.build)
                .components(List.of(shell))
                .designSources(Map.of("roof_type", "USER_EXPLICIT"))
                .build();
        var gap = DesignValidatorSuite.checkAuthored(locked);
        assertTrue(gap.isPresent(), "user roof_type=none vs unsupported typology must gap");
        assertEquals("E_TYPOLOGY_ROOF_UNSUPPORTED", gap.get().code());
    }

    @Test
    void compileContextRestoredAfterFailure() {
        var before = GenerationCompileContext.current();
        var outer = new GenerationCompileContext(GenerationAuthority.COMPONENTS, Map.of("k", 1), false);
        var inner = new GenerationCompileContext(GenerationAuthority.TYPOLOGY, Map.of("k", 2), true);
        GenerationCompileContext.runWith(outer, () -> {
            assertThrows(IllegalStateException.class, () -> GenerationCompileContext.runWith(inner, () -> {
                assertSame(inner, GenerationCompileContext.current());
                throw new IllegalStateException("forced");
            }));
            assertSame(outer, GenerationCompileContext.current(),
                    "outer compile context must restore after inner failure");
        });
        assertSame(before, GenerationCompileContext.current());
    }

    @Test
    void crossBuildingParamsDoNotContaminate() {
        Component left = mass("left", 0, Map.of(
                "roof_type", "none",
                "window_style", "none",
                "wall_block", "minecraft:red_concrete"));
        Component right = mass("right", 40, Map.of(
                "roof_type", "gable",
                "window_style", "none",
                "entrance_type", "none",
                "wall_block", "minecraft:blue_concrete"));
        Map<Vec3i, String> blocks = PatchTestSnapshot.blocks(compile(List.of(left, right)));
        assertFalse(blocks.isEmpty(), () -> String.valueOf(AssemblyCompileDiagnostics.get()));
        assertTrue(blocks.entrySet().stream().anyMatch(e ->
                e.getKey().x() < 20 && e.getValue() != null && e.getValue().contains("red_concrete")));
        assertTrue(blocks.entrySet().stream().anyMatch(e ->
                e.getKey().x() >= 40 && e.getValue() != null && e.getValue().contains("blue_concrete")));
        assertTrue(blocks.entrySet().stream().noneMatch(e ->
                e.getKey().x() < 20 && e.getValue() != null && e.getValue().contains("blue_concrete")),
                "right-building material must not contaminate left footprint");
        int leftMaxY = blocks.keySet().stream().filter(p -> p.x() < 20).mapToInt(Vec3i::y).max().orElse(-1);
        assertTrue(leftMaxY < 8, "left roof_type=none must not inherit right gable height");
    }

    private static Component mass(String id, int x, Map<String, Object> extra) {
        var params = new HashMap<String, Object>(Map.of(
                "component_id", id,
                "building_id", id,
                "hollow", true,
                "anchor_mode", "min_corner",
                "plan_type", "rectangle",
                "wall_block", "minecraft:stone_bricks",
                "floor_block", "minecraft:oak_planks"));
        params.putAll(extra);
        return new Component("MASS_MAIN", "site", new Vec3i(x, 0, 0),
                new Dimensions(11, 11, 8), List.of(), params);
    }

    private static LlmPlan plan(List<Component> components) {
        return LlmPlanTestFixtures.builder().allowCompilerSatellites(true)
                .mode(LlmPlan.Mode.build).styleProfile("DEFAULT").anchor(new Vec3i(0, 0, 0))
                .layout(new Layout(null, false, List.of(
                        new Slot("site", new Vec3i(0, 0, 0), GlobalConstraints.Facing.SOUTH, null, null, null))))
                .components(components).build();
    }

    private static List<com.formacraft.common.patch.BlockPatch> compile(List<Component> components) {
        return ComponentPlanCompiler.compile(plan(components), BlockPos.ORIGIN, null, null, false);
    }
}
