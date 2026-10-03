package com.formacraft.server.compiler;

import com.formacraft.common.llm.dto.*;
import com.formacraft.common.patch.*;
import com.formacraft.common.generation.component.ComponentGeneratorRegistry;
import com.formacraft.server.assembly.*;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ComponentPlanCompilerCirculationTest {
    private static final String SOURCE = "TEST_CIRCULATION_SOURCE", OVERRIDE = "TEST_CIRCULATION_OVERRIDE";
    private static final BlockPos TREAD = new BlockPos(2, 3, 4), CLEAR = new BlockPos(2, 4, 4);
    @BeforeAll static void initialize() {
        MinecraftRegistryTestBootstrap.initialize();
        ComponentGeneratorRegistry.register(SOURCE, semantic -> {
            AssemblyCirculationConstraints.publish(List.of(new AssemblyCirculationConstraints.Flight(Set.of(TREAD), Set.of(CLEAR))));
            return List.of(new BlockPatch("place", 2, 3, 4, "minecraft:stone_brick_stairs[facing=east]"), new BlockPatch("remove", 2, 4, 4, null));
        });
        ComponentGeneratorRegistry.register(OVERRIDE, semantic -> {
            String mode = semantic.source().params().get("test_mode").toString();
            if (mode.equals("gap")) {
                AssemblyCompileDiagnostics.set(new CapabilityGap("TEST_ASSEMBLY_FAILURE", "test failure", "components[]", List.of()));
                return List.of();
            }
            return switch (mode) {
                case "remove" -> List.of(new BlockPatch("remove", 2, 3, 4, null));
                case "restore" -> List.of(new BlockPatch("place", 2, 4, 4, "minecraft:stone"), new BlockPatch("remove", 2, 4, 4, null));
                default -> List.of(new BlockPatch("place", 2, 4, 4, "minecraft:stone"));
            };
        });
    }
    private LlmPlan plan(String mode, boolean separate) {
        return LlmPlanTestFixtures.builder().mode(LlmPlan.Mode.build).anchor(new Vec3i(100, 64, 200)).styleProfile("DEFAULT")
            .layout(new Layout(null, false, List.of(
                new Slot("a", new Vec3i(10, 2, -5), GlobalConstraints.Facing.SOUTH, "RESIDENTIAL", null, null),
                new Slot("b", new Vec3i(30, 2, -5), GlobalConstraints.Facing.SOUTH, "RESIDENTIAL", null, null))))
            .components(List.of(new Component(SOURCE, "a", new Vec3i(0, 0, 0), new Dimensions(1, 1, 1), List.of(), Map.of()),
                new Component(OVERRIDE, separate ? "b" : "a", new Vec3i(0, 0, 0), new Dimensions(1, 1, 1), List.of(), Map.of("test_mode", mode))))
            .build();
    }
    @Test void laterComponentBlockingClearanceRejectsWholePlan() {
        assertTrue(ComponentPlanCompiler.compile(plan("block", false), new BlockPos(100, 64, 200), null, null, false).isEmpty());
        assertEquals("E_PLAN_CIRCULATION_CONFLICT", AssemblyCompileDiagnostics.get().code());
        assertTrue(AssemblyCompileDiagnostics.get().message().contains("12, 6, -1"));
    }
    @Test void laterComponentRemovingTreadIsRejectedEvenWithoutPostProcessing() {
        assertTrue(ComponentPlanCompiler.compile(plan("remove", false)).isEmpty());
        assertEquals("E_PLAN_CIRCULATION_CONFLICT", AssemblyCompileDiagnostics.get().code());
    }
    @Test void differentSlotDoesNotConflictAndAnchorIsAddedOnlyOnce() {
        var result = ComponentPlanCompiler.compile(plan("block", true), new BlockPos(100, 64, 200), null, null, false);
        assertFalse(result.isEmpty()); assertNull(AssemblyCompileDiagnostics.get());
        assertTrue(result.stream().anyMatch(p -> p.dx() == 12 && p.dy() == 5 && p.dz() == -1));
        assertTrue(result.stream().noneMatch(p -> p.dx() >= 100));
    }
    @Test void temporaryBlockFollowedByRemoveUsesFinalState() {
        assertFalse(ComponentPlanCompiler.compile(plan("restore", false), BlockPos.ORIGIN, null, null, false).isEmpty());
        assertNull(AssemblyCompileDiagnostics.get());
    }
    @Test void mixedPlanGapCannotReturnPartialBuildingAndNextCompileClearsIt() {
        assertTrue(ComponentPlanCompiler.compile(plan("gap", true)).isEmpty());
        assertEquals("TEST_ASSEMBLY_FAILURE", AssemblyCompileDiagnostics.get().code());
        assertFalse(ComponentPlanCompiler.compile(plan("restore", false)).isEmpty());
        assertNull(AssemblyCompileDiagnostics.get());
    }
    @Test void captureIsScopedAndNestedCaptureRestoresOuterOnException() {
        var flight = new AssemblyCirculationConstraints.Flight(Set.of(TREAD), Set.of(CLEAR));
        var outer = new ArrayList<AssemblyCirculationConstraints.Flight>(); var inner = new ArrayList<AssemblyCirculationConstraints.Flight>();
        try (var scope = AssemblyCirculationConstraints.captureTo(outer::addAll)) {
            assertThrows(IllegalStateException.class, () -> {
                try (var nested = AssemblyCirculationConstraints.captureTo(inner::addAll)) {
                    AssemblyCirculationConstraints.publish(List.of(flight)); throw new IllegalStateException("test");
                }
            });
            AssemblyCirculationConstraints.publish(List.of(flight));
        }
        AssemblyCirculationConstraints.publish(List.of(flight));
        assertEquals(1, inner.size()); assertEquals(1, outer.size());
    }
    @Test void appendedPostProcessingCannotSealReservedClearance() {
        var flight = new AssemblyCirculationConstraints.Flight(Set.of(TREAD), Set.of(CLEAR));
        var patches = List.of(new BlockPatch("place", 2, 3, 4, "minecraft:stone_brick_stairs"), new BlockPatch("remove", 2, 4, 4, null));
        var pipeline = new com.formacraft.common.compiler.postprocess.PostProcessPipeline().add((input, context) -> {
            var changed = new ArrayList<>(input);
            changed.add(new BlockPatch("replace", 2, 4, 4, "minecraft:stone"));
            return changed;
        });
        var finalPatches = pipeline.process(patches, com.formacraft.common.compiler.postprocess.PostProcessContext.create(plan("restore", false), BlockPos.ORIGIN));
        assertThrows(AssemblyCirculationConstraints.Conflict.class,
            () -> AssemblyCirculationConstraints.validatePatches(finalPatches, List.of(flight)));
    }
}
