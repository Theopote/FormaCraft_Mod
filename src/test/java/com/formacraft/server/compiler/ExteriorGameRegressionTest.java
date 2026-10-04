package com.formacraft.server.compiler;

import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.parser.LlmPlanParser;
import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.generation.component.impl.MassMainGenerator;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ExteriorGameRegressionTest {
    @Test void legacyEntranceDirectionAdapterMatchesGeneratedDoorPlanes() {
        MinecraftRegistryTestBootstrap.initialize();
        for (var facing : com.formacraft.common.llm.dto.GlobalConstraints.Facing.values()) {
            var component = new Component("MASS_MAIN", "house",
                    new com.formacraft.common.llm.dto.Vec3i(0, 0, 0),
                    new com.formacraft.common.llm.dto.Dimensions(9, 9, 8), List.of("hollow", "door"),
                    Map.of("anchor_mode", "min_corner", "suppress_windows", true));
            var slot = new com.formacraft.common.llm.dto.Slot("house",
                    new com.formacraft.common.llm.dto.Vec3i(0, 0, 0), facing, null, null, null);
            var patches = new MassMainGenerator().generate(new SemanticComponent("MASS_MAIN", slot, component));
            int x = switch (facing) { case EAST -> 0; case WEST -> 8; default -> 4; };
            int z = switch (facing) { case NORTH -> 8; case SOUTH -> 0; default -> 4; };
            assertFalse(patches.stream().anyMatch(p -> p.dx() == x && p.dy() == 1 && p.dz() == z),
                    "Legacy " + facing + " must open on its documented Minecraft world plane");
            assertTrue(patches.stream().anyMatch(p -> p.dx() == 8-x && p.dy() == 1 && p.dz() == 8-z),
                    "The opposite wall must remain closed");
        }
    }
    @Test void explicitBuildingMaterialsOverrideDynamicStylePalette() {
        MinecraftRegistryTestBootstrap.initialize();
        var attrs = new com.formacraft.common.llm.dto.StyleAttributes(
                null, "brick", null, null, null, "cobblestone", List.of(), Map.of());
        var component = new Component("MASS_MAIN", null,
                new com.formacraft.common.llm.dto.Vec3i(0, 0, 0),
                new com.formacraft.common.llm.dto.Dimensions(9, 9, 8), List.of("hollow"),
                Map.of("anchor_mode", "min_corner", "wall_block", "minecraft:quartz_block",
                        "floor_block", "minecraft:spruce_planks", "hollow", true,
                        "suppress_windows", true, "suppress_doors", true));
        var patches = new MassMainGenerator().generate(
                new SemanticComponent("MASS_MAIN", null, component, "DEFAULT", attrs, null));
        assertTrue(patches.stream().anyMatch(p -> p.dx() == 0 && p.dy() == 2 && p.dz() == 0
                && "minecraft:quartz_block".equals(p.targetBlock())));
        assertTrue(patches.stream().anyMatch(p -> p.dx() == 4 && p.dy() == 0 && p.dz() == 4
                && "minecraft:spruce_planks".equals(p.targetBlock())));
        assertFalse(patches.stream().anyMatch(p -> "minecraft:bricks".equals(p.targetBlock())));
    }
    @Test void decoratedRetestUsesFinalStairPatches() throws Exception {
        MinecraftRegistryTestBootstrap.initialize();
        try (var input = getClass().getResourceAsStream("/regressions/exterior-retest/4.json")) {
            var plan = LlmPlanParser.parse(new String(Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8));
            assertFalse(ComponentPlanCompiler.compile(plan, net.minecraft.util.math.BlockPos.ORIGIN, null, null, false).isEmpty(),
                String.valueOf(com.formacraft.server.assembly.AssemblyCompileDiagnostics.get()));
        }
    }
    private List<Component> components(int index) throws Exception {
        try (var input = getClass().getResourceAsStream("/regressions/exterior-game/" + index + ".json")) {
            return LlmPlanParser.parse(new String(Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8)).components();
        }
    }

    @Test void loggedCirculationAssembliesPreserveCompleteBuildingComponents() throws Exception {
        for (int index : List.of(2, 3)) {
            var input = components(index);
            var result = AssemblyPlanPromoter.promoteNestedAssembly(input);
            assertTrue(result.assemblyPrimarySlots().isEmpty(), "Stairs do not own the shell");
            assertEquals(input, result.components());
            assertTrue(result.components().stream().anyMatch(c -> c.componentType().equals("MASS_MAIN")));
            assertTrue(result.components().stream().anyMatch(c -> c.componentType().equals("ROOF")));
        }
    }

    @Test void loggedTwinBuildingsHaveWallGeometryBeforeDetailExpansion() throws Exception {
        MinecraftRegistryTestBootstrap.initialize();
        for (var c : components(5)) {
            if (!c.componentType().equals("MASS_MAIN")) continue;
            var semantic = new SemanticComponent("MASS_MAIN", null, c, "DEFAULT", null, null);
            var patches = new MassMainGenerator().generate(semantic);
            assertTrue(patches.size() > 400, "A building must not collapse to a column query");
        }
    }
}
