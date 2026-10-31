package com.formacraft.server.network;

import com.formacraft.common.generation.routing.GenerationAuthority;
import com.formacraft.common.llm.dto.*;
import com.formacraft.common.llm.dto.LlmPlanTestFixtures;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.server.assembly.AssemblyCompileDiagnostics;
import com.formacraft.server.compiler.ComponentPlanCompiler;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class OffThreadPlanCompileGuardTest {

    @BeforeAll
    static void boot() {
        MinecraftRegistryTestBootstrap.initialize();
    }

    private static LlmPlan simpleMassPlan() {
        return LlmPlanTestFixtures.builder()
                .mode(LlmPlan.Mode.build)
                .anchor(new Vec3i(0, 64, 0))
                .styleProfile("DEFAULT")
                .layout(new Layout(null, false, List.of(
                        new Slot("main", new Vec3i(0, 0, 0), GlobalConstraints.Facing.SOUTH, "RESIDENTIAL", null, null))))
                .components(List.of(new Component(
                        "MASS_MAIN", "main", new Vec3i(0, 0, 0),
                        new Dimensions(6, 4, 6), List.of(), Map.of())))
                .build();
    }

    @Test
    void simpleMassPlanAllowsNullWorldCompile() {
        assertTrue(OffThreadPlanCompileGuard.allowsNullWorldCompile(simpleMassPlan()));
        assertFalse(OffThreadPlanCompileGuard.requiresLiveWorld(simpleMassPlan()));
    }

    @Test
    void assemblyAuthorityRequiresLiveWorld() {
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .mode(LlmPlan.Mode.build)
                .anchor(new Vec3i(0, 64, 0))
                .planMeta(Map.of(GenerationAuthority.META_KEY, "ASSEMBLY"))
                .components(List.of(new Component(
                        "ASSEMBLY", "main", new Vec3i(0, 0, 0),
                        new Dimensions(4, 4, 4), List.of(),
                        Map.of("assembly", Map.of("preset", "box")))))
                .build();
        assertTrue(OffThreadPlanCompileGuard.requiresLiveWorld(plan));
        assertFalse(OffThreadPlanCompileGuard.allowsNullWorldCompile(plan));
    }

    @Test
    void libraryFeatureRequiresLiveWorld() {
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .mode(LlmPlan.Mode.build)
                .anchor(new Vec3i(0, 64, 0))
                .components(List.of(new Component(
                        "DECOR_DETAIL", "main", new Vec3i(0, 0, 0),
                        new Dimensions(2, 2, 1),
                        List.of("component_request:{\"category\":\"DOOR\"}"),
                        Map.of())))
                .build();
        assertTrue(OffThreadPlanCompileGuard.requiresLiveWorld(plan));
    }

    @Test
    void typologyFeatureRequiresLiveWorld() {
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .mode(LlmPlan.Mode.build)
                .anchor(new Vec3i(0, 64, 0))
                .components(List.of(new Component(
                        "STRUCTURE", "main", new Vec3i(0, 0, 0),
                        new Dimensions(8, 8, 8),
                        List.of("typology:courtyard_compound"),
                        Map.of())))
                .build();
        assertTrue(OffThreadPlanCompileGuard.requiresLiveWorld(plan));
    }

    @Test
    void nullWorldCompileOfSafePlanIsDeterministicAndNonEmpty() {
        LlmPlan plan = simpleMassPlan();
        assertTrue(OffThreadPlanCompileGuard.allowsNullWorldCompile(plan));
        BlockPos origin = new BlockPos(10, 64, 20);
        var a = ComponentPlanCompiler.compileWithCirculation(plan, origin, null, null, false);
        var gapA = AssemblyCompileDiagnostics.get();
        var b = ComponentPlanCompiler.compileWithCirculation(plan, origin, null, null, false);
        var gapB = AssemblyCompileDiagnostics.get();
        assertNull(gapA);
        assertNull(gapB);
        assertFalse(a.patches().isEmpty());
        assertEquals(signature(a.patches()), signature(b.patches()),
                "null-world compile of a safe plan must be deterministic");
        // Guard contract: world-dependent plans must not use this path; for safe MASS plans
        // null-world output is the accepted offline baseline (no live ServerWorld in unit tests).
        assertTrue(canOffThreadForBuild(plan));
    }

    @Test
    void canOffThreadRejectsPatchAndWorldBoundPlans() {
        LlmPlan mass = simpleMassPlan();
        assertTrue(LlmPlanPreviewBuilder.canOffThreadComponentCompile(mass, buildReq()));

        LlmPlan patch = LlmPlanTestFixtures.builder()
                .mode(LlmPlan.Mode.patch)
                .components(mass.components())
                .build();
        assertFalse(LlmPlanPreviewBuilder.canOffThreadComponentCompile(patch, buildReq()));

        LlmPlan assembly = LlmPlanTestFixtures.builder()
                .mode(LlmPlan.Mode.build)
                .planMeta(Map.of(GenerationAuthority.META_KEY, "ASSEMBLY"))
                .components(List.of(new Component(
                        "ASSEMBLY", "main", new Vec3i(0, 0, 0),
                        new Dimensions(4, 4, 4), List.of(), Map.of("graph", Map.of()))))
                .build();
        assertFalse(LlmPlanPreviewBuilder.canOffThreadComponentCompile(assembly, buildReq()));
    }

    private static boolean canOffThreadForBuild(LlmPlan plan) {
        return LlmPlanPreviewBuilder.canOffThreadComponentCompile(plan, buildReq());
    }

    private static com.formacraft.common.model.request.FormaRequest buildReq() {
        var req = new com.formacraft.common.model.request.FormaRequest();
        req.setPromptMode("BUILD");
        return req;
    }

    private static Set<String> signature(List<BlockPatch> patches) {
        return patches.stream()
                .filter(Objects::nonNull)
                .map(p -> p.action() + "@" + p.dx() + "," + p.dy() + "," + p.dz() + ":" + p.targetBlock())
                .collect(Collectors.toSet());
    }
}
