package com.formacraft.server.dsl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.server.edit.BuildingMemoryKeys;
import com.formacraft.server.memory.ProjectMemory;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FbdSourcePlanTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @BeforeAll
    static void bootstrap() {
        MinecraftRegistryTestBootstrap.initialize();
    }

    @Test
    void sanitizeStripsServerFieldsAndRoundTrips() throws Exception {
        var tree = (ObjectNode) JSON.readTree(Files.readString(
                Path.of("src/main/resources/assets/formacraft/fbd/examples/repeated_pavilions.llmplan.json")));
        ((ObjectNode) tree.at("/components/0/params")).put(FbdPlanLowering.COMPILED, "injected");
        ((ObjectNode) tree.at("/components/0/params")).put(FbdPlanLowering.SOURCE, "injected");
        LlmPlan plan = JSON.treeToValue(tree, LlmPlan.class);

        String json = FbdSourcePlan.sanitizeToJson(plan);
        assertNotNull(json);
        assertFalse(json.contains(FbdPlanLowering.COMPILED));
        assertFalse(json.contains(FbdPlanLowering.SOURCE));

        LlmPlan parsed = FbdSourcePlan.parse(json);
        assertNotNull(parsed);
        assertTrue(FbdSourcePlan.hasProgram(parsed));

        ProjectMemory memory = new ProjectMemory("fbd-test", null);
        BuildingMemoryKeys.storeFbdSourcePlan(memory, json);
        assertTrue(BuildingMemoryKeys.hasFbdSourcePlan(memory));
        assertEquals(json, BuildingMemoryKeys.fbdSourcePlan(memory));
        assertNotNull(memory.getMetadata().get(BuildingMemoryKeys.FBD_SOURCE_FINGERPRINT));
    }

    @Test
    void rejectsPlansWithoutFbdProgram() throws Exception {
        assertNull(FbdSourcePlan.sanitizeToJson(null));
        LlmPlan empty = JSON.readValue("{\"mode\":\"build\",\"components\":[]}", LlmPlan.class);
        assertNull(FbdSourcePlan.sanitizeToJson(empty));
    }
}
