package com.formacraft.server.compiler;

import com.formacraft.common.llm.dto.*;
import com.formacraft.server.assembly.AssemblyCompileDiagnostics;
import com.formacraft.test.PatchTestSnapshot;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ComponentPlanCompilerDesignIntentTest {
    @BeforeAll static void bootstrap() { com.formacraft.test.MinecraftRegistryTestBootstrap.initialize(); }
    private Component body(String id, int x, Map<String, Object> extra) {
        var params = new HashMap<String, Object>(Map.of("component_id", id, "anchor_mode", "min_corner",
                "plan_type", "rectangle", "roof_type", "flat", "window_ratio", 0.3, "floor_height", 5));
        params.putAll(extra);
        return new Component("MASS_MAIN", "s", new Vec3i(x, 0, 0), new Dimensions(15, 13, 11), List.of(), params);
    }
    private LlmPlan plan(List<Component> components, String style, Map<String, Object> hints) {
        return LlmPlanTestFixtures.builder().mode(LlmPlan.Mode.build).styleProfile(style).anchor(new Vec3i(0, 0, 0))
                .layout(new Layout(null, false, List.of())).components(components).proportionHints(hints).build();
    }
    @Test void bothBodiesSharingSlotGetWindowsAndEntranceRegardlessOfOrder() {
        var a = body("a", 0, Map.of());
        var b = body("b", 30, Map.of());
        var first = ComponentPlanCompiler.compile(plan(List.of(b, a), "MODERN", Map.of()));
        assertFalse(first.isEmpty(), String.valueOf(AssemblyCompileDiagnostics.get()));
        var blocks = PatchTestSnapshot.blocks(first);
        for (int x : new int[]{0, 30}) {
            assertTrue(blocks.entrySet().stream().anyMatch(e -> e.getKey().x() >= x && e.getKey().x() < x + 15
                    && e.getValue().contains("glass")), "windows on building " + x);
            assertFalse(blocks.containsKey(new Vec3i(x + 7, 1, 0)), "entrance opening on building " + x);
        }
        assertEquals(blocks, PatchTestSnapshot.blocks(ComponentPlanCompiler.compile(plan(List.of(a, b), "MODERN", Map.of()))));
    }
    @Test void explicitFacadeOnSecondBodyDoesNotSuppressFirstBodyWindows() {
        var facade = new Component("FACADE_WINDOWS", "s", new Vec3i(999, 0, 999), new Dimensions(15, 13, 11),
                List.of("wrap"), Map.of("component_id", "b_windows", "host_id", "b", "window_style", "stained"));
        var output = PatchTestSnapshot.blocks(ComponentPlanCompiler.compile(plan(List.of(body("a", 0, Map.of()),
                facade, body("b", 30, Map.of())), "MODERN", Map.of())));
        assertFalse(output.isEmpty(), String.valueOf(AssemblyCompileDiagnostics.get()));
        assertTrue(output.entrySet().stream().anyMatch(e -> e.getKey().x() < 15 && e.getValue().contains("glass")));
        assertTrue(output.entrySet().stream().anyMatch(e -> e.getKey().x() >= 30 && e.getKey().x() < 45 && e.getValue().contains("stained_glass")));
        assertTrue(output.keySet().stream().allMatch(p -> p.x() < 60 && p.z() < 30));
    }
    @Test void sharedRealSlotAppliesOffsetOnceForBothFacadesAndEntrances() {
        var slot = new Slot("s", new Vec3i(-20, 4, 50), GlobalConstraints.Facing.NORTH, null, null, null);
        var plan = LlmPlanTestFixtures.builder().mode(LlmPlan.Mode.build).styleProfile("MODERN")
                .anchor(new Vec3i(0, 0, 0)).layout(new Layout(null, false, List.of(slot)))
                .components(List.of(body("a", 0, Map.of()), body("b", 30, Map.of()))).build();
        var output = PatchTestSnapshot.blocks(ComponentPlanCompiler.compile(plan));
        assertFalse(output.isEmpty(), String.valueOf(AssemblyCompileDiagnostics.get()));
        for (int x : new int[]{-20, 10}) {
            assertTrue(output.entrySet().stream().anyMatch(e -> e.getKey().x() >= x && e.getKey().x() < x + 15
                    && e.getKey().z() >= 50 && e.getKey().z() <= 62 && e.getValue().contains("glass")));
            assertFalse(output.containsKey(new Vec3i(x + 7, 5, 62)));
        }
    }
    @Test void scopedDecorationOptOutProtectsOneBuildingAndAllowsTheOther() {
        var facade = new Component("FACADE_WINDOWS", "s", new Vec3i(30, 0, 0), new Dimensions(15, 13, 11),
                List.of("wrap"), Map.of("component_id", "b_windows", "host_id", "b", "window_order", "full"));
        var plan = plan(List.of(body("a", 0, Map.of("no_complex_decor", true)),
                body("b", 30, Map.of()), facade), "MODERN", Map.of());
        var before = PatchTestSnapshot.blocks(ComponentPlanCompiler.compile(plan));
        var after = PatchTestSnapshot.blocks(ComponentPlanCompiler.compile(plan, BlockPos.ORIGIN, null, null, false));
        assertFalse(before.isEmpty());
        var aBefore = before.keySet().stream().filter(p -> p.x() < 20).collect(java.util.stream.Collectors.toSet());
        var aAfter = after.keySet().stream().filter(p -> p.x() < 20).collect(java.util.stream.Collectors.toSet());
        assertEquals(aBefore, aAfter);
        assertTrue(after.keySet().stream().anyMatch(p -> p.x() >= 25 && !before.containsKey(p)));
    }
    @Test void gothicDefaultsCannotRecreateDisabledRoofWindowsOrEntrance() {
        var plan = plan(List.of(body("a", 0, Map.of("roof_type", "none", "window_style", "none", "entrance_type", "none"))),
                "GOTHIC", Map.of("typology", "baroque_townhouse", "roof_specialty", "mansard_dormer", "crown_assembly", false));
        var output = PatchTestSnapshot.blocks(ComponentPlanCompiler.compile(plan));
        assertFalse(output.isEmpty(), String.valueOf(AssemblyCompileDiagnostics.get()));
        assertTrue(output.values().stream().noneMatch(v -> v.contains("glass")));
        assertTrue(output.keySet().stream().allMatch(p -> p.y() < 11));
        assertTrue(output.containsKey(new Vec3i(7, 1, 0)));
    }
    @Test void hostedRoofContradictingOptOutProducesDiagnostic() {
        var roof = new Component("ROOF", "s", new Vec3i(0, 10, 0), new Dimensions(15, 13, 4), List.of(),
                Map.of("host_id", "a", "roof_type", "gable"));
        assertTrue(ComponentPlanCompiler.compile(plan(List.of(body("a", 0, Map.of("roof_type", "none")), roof), "MODERN", Map.of())).isEmpty());
        assertEquals("E_EXPLICIT_DESIGN_CONFLICT", AssemblyCompileDiagnostics.get().code());
    }
    @Test void noComplexDecorBlocksCrownAndOptionalPostProcessingFromContract() {
        var contract = Map.of("requirements", List.of(Map.of("property", "no_complex_decor", "value", true, "scope", "plan")));
        var plan = plan(List.of(body("a", 0, Map.of("window_ratio", 0.0))), "GOTHIC",
                Map.of("building_contract", contract, "crown_assembly", true, "roof_specialty", "mansard_dormer"));
        var before = PatchTestSnapshot.blocks(ComponentPlanCompiler.compile(plan));
        assertFalse(before.isEmpty());
        var after = PatchTestSnapshot.blocks(ComponentPlanCompiler.compile(plan, BlockPos.ORIGIN, null, null, false));
        assertEquals(before.keySet(), after.keySet());
        assertTrue(after.keySet().stream().allMatch(p -> p.y() < 14));
    }
}
