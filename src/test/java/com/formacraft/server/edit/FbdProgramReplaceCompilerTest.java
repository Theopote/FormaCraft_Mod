package com.formacraft.server.edit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.server.assembly.AssemblyCompileDiagnostics;
import com.formacraft.server.dsl.FbdSourcePlan;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FbdProgramReplaceCompilerTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ROOT = "/components/0/params/fbd_program";

    @BeforeAll
    static void bootstrap() {
        MinecraftRegistryTestBootstrap.initialize();
    }

    @AfterEach
    void clear() {
        AssemblyCompileDiagnostics.clear();
    }

    private LlmPlan pavilion(int roofY) throws Exception {
        var tree = (ObjectNode) JSON.readTree(Files.readString(
                Path.of("src/main/resources/assets/formacraft/fbd/examples/repeated_pavilions.llmplan.json")));
        var program = (ObjectNode) tree.at(ROOT);
        var body = program.at("/functions/0/body").deepCopy();
        program.remove("functions");
        program.set("main", body);
        ((ObjectNode) program.at("/main/1/params")).put("y", roofY);
        ((ObjectNode) program.at("/main/1/params")).put("type", "FLAT");
        return JSON.treeToValue(tree, LlmPlan.class);
    }

    @Test
    void refusesWithoutStoredPlan() throws Exception {
        var neu = pavilion(8);
        var result = FbdProgramReplaceCompiler.compile(
                new BlockPos(0, 64, 0), null, neu, Map.of());
        assertFalse(result.ok());
        assertTrue(result.refusals().getFirst().contains("stored_fbd"));
    }

    @Test
    void diffsRoofHeightChangeIntoPatches() throws Exception {
        LlmPlan oldPlan = pavilion(8);
        LlmPlan newPlan = pavilion(9);
        String stored = FbdSourcePlan.sanitizeToJson(oldPlan);
        assertNotNull(stored);

        BlockPos origin = new BlockPos(10, 64, 20);
        var result = FbdProgramReplaceCompiler.compile(origin, stored, newPlan, cell -> "minecraft:air");
        assertTrue(result.ok(), String.valueOf(result.refusals()));
        assertFalse(result.patches().isEmpty());
        assertFalse(result.allowedCells().isEmpty());
        assertNotNull(result.sanitizedNewPlanJson());
        assertEquals(result.allowedCells(), result.targetWorldStates().keySet());
    }
}
