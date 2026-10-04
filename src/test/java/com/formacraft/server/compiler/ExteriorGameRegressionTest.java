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
