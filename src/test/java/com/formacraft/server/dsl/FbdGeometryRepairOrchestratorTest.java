package com.formacraft.server.dsl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.server.assembly.AssemblyCompileDiagnostics;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class FbdGeometryRepairOrchestratorTest {
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

    private ObjectNode floatingRoofTree() throws Exception {
        var tree = (ObjectNode) JSON.readTree(Files.readString(
                Path.of("src/main/resources/assets/formacraft/fbd/examples/repeated_pavilions.llmplan.json")));
        var program = (ObjectNode) tree.at(ROOT);
        var body = program.at("/functions/0/body").deepCopy();
        program.remove("functions");
        program.set("main", body);
        ((ObjectNode) program.at("/main/1/params")).put("y", 9);
        ((ObjectNode) program.at("/main/1/params")).put("type", "FLAT");
        return tree;
    }

    private LlmPlan plan(ObjectNode tree) throws Exception {
        return JSON.treeToValue(tree, LlmPlan.class);
    }

    @Test
    void acceptsLowerRoofWhenItClearsFloating() throws Exception {
        var tree = floatingRoofTree();
        var original = plan(tree);
        var before = JSON.valueToTree(original);
        var baseline = FbdGeometryQualityVerifier.verify(
                original, new BlockPos(0, 64, 0), p -> p.getY() < 64 ? Blocks.STONE.getDefaultState() : Blocks.AIR.getDefaultState());
        assertNotNull(baseline);
        assertTrue(baseline.stats().floatingColumns >= 12);

        var outcome = FbdGeometryRepairOrchestrator.tryRepair(
                original,
                baseline,
                candidate -> FbdGeometryQualityVerifier.verify(
                        candidate, new BlockPos(0, 64, 0),
                        p -> p.getY() < 64 ? Blocks.STONE.getDefaultState() : Blocks.AIR.getDefaultState()));

        assertTrue(outcome.accepted(), outcome.reason());
        assertEquals(8, outcome.y());
        assertEquals(before, JSON.valueToTree(original));
        ((ObjectNode) tree.at(ROOT + "/main/1/params")).put("y", 8);
        assertEquals(JSON.valueToTree(plan(tree)), JSON.valueToTree(outcome.plan()));
    }

    @Test
    void keepsOriginalWhenNeitherCandidateImproves() throws Exception {
        var tree = floatingRoofTree();
        ((ObjectNode) tree.at(ROOT + "/main/1/params")).put("y", 8);
        var original = plan(tree);
        var baseline = FbdGeometryQualityVerifier.verify(
                original, new BlockPos(0, 64, 0), p -> p.getY() < 64 ? Blocks.STONE.getDefaultState() : Blocks.AIR.getDefaultState());
        assertNotNull(baseline);
        assertEquals(0, baseline.stats().floatingColumns);

        var outcome = FbdGeometryRepairOrchestrator.tryRepair(
                original,
                baseline,
                candidate -> FbdGeometryQualityVerifier.verify(
                        candidate, new BlockPos(0, 64, 0),
                        p -> p.getY() < 64 ? Blocks.STONE.getDefaultState() : Blocks.AIR.getDefaultState()));

        assertFalse(outcome.accepted());
        assertTrue(outcome.reason().equals("NO_ACTIVE_ISSUE")
                || outcome.reason().equals("NO_ACCEPTED_CANDIDATE")
                || outcome.reason().equals("SKIPPED")
                || outcome.reason().equals("UNSUPPORTED_ISSUE")
                || outcome.reason().equals("UNMEASURED_QUALITY")
                || outcome.reason().equals("MISSING_SOURCE")
                || outcome.reason().equals("BLOCKED_QUALITY")
                || outcome.reason().equals("NOT_FINAL")
                || outcome.reason().equals("SYSTEM_OR_UNKNOWN_SOURCE")
                || outcome.reason().equals("MULTI_INSTANCE")
                || outcome.reason().equals("STALE_SOURCE")
                || outcome.reason().equals("UNSUPPORTED_PRIMITIVE")
                || outcome.reason().equals("NON_LITERAL_PARAMETER")
                || outcome.reason().equals("PARAMETER_BOUNDARY")
                || outcome.reason().equals("INVALID_PLAN")
                || outcome.reason().equals("ELIGIBLE_CANDIDATE_ONLY"));
        // Healthy roof: assess sees no FLOATING → NO_ACTIVE_ISSUE.
        assertEquals("NO_ACTIVE_ISSUE", outcome.reason());
    }
}
