package com.formacraft.server.compiler;

import com.formacraft.common.llm.dto.*;
import com.formacraft.common.llm.parser.LlmPlanParser;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.server.assembly.AssemblyCompileDiagnostics;
import com.formacraft.server.network.PlanPatchConverter;
import com.formacraft.test.PatchTestSnapshot;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

class Phase00DiagTest {
    @BeforeAll static void bootstrap() { com.formacraft.test.MinecraftRegistryTestBootstrap.initialize(); }

    @Test void dumpFinalGeometryStairs() {
        var mass = new Component("MASS_MAIN", "site", new Vec3i(0, 0, 0),
                new Dimensions(9, 9, 6), List.of(),
                Map.of("component_id", "bare", "building_id", "bare",
                        "hollow", true, "roof_type", "none",
                        "window_style", "none", "entrance_type", "none",
                        "wall_block", "minecraft:stone_bricks",
                        "floor_block", "minecraft:oak_planks",
                        "anchor_mode", "min_corner", "plan_type", "rectangle"));
        var plan = LlmPlanTestFixtures.builder().allowCompilerSatellites(true)
                .mode(LlmPlan.Mode.build).styleProfile("DEFAULT").anchor(new Vec3i(0, 0, 0))
                .layout(new Layout(null, false, List.of(
                        new Slot("site", new Vec3i(0, 0, 0), GlobalConstraints.Facing.SOUTH, null, null, null))))
                .components(List.of(mass)).build();
        var blocks = PatchTestSnapshot.blocks(ComponentPlanCompiler.compile(plan, BlockPos.ORIGIN, null, null, false));
        blocks.entrySet().stream().filter(e -> e.getValue() != null && e.getValue().contains("stairs"))
                .forEach(e -> System.out.println("STAIR " + e.getKey() + " = " + e.getValue()));
        System.out.println("maxY=" + blocks.keySet().stream().mapToInt(Vec3i::y).max().orElse(-1));
        System.out.println("gap=" + AssemblyCompileDiagnostics.get());
    }

    @Test void dumpDesignIntentDiff() {
        var paramsA = new HashMap<String, Object>(Map.of("component_id", "a", "anchor_mode", "min_corner",
                "plan_type", "rectangle", "roof_type", "flat", "window_ratio", 0.3, "floor_height", 5,
                "no_complex_decor", true));
        var paramsB = new HashMap<String, Object>(Map.of("component_id", "b", "anchor_mode", "min_corner",
                "plan_type", "rectangle", "roof_type", "flat", "window_ratio", 0.3, "floor_height", 5));
        var a = new Component("MASS_MAIN", "s", new Vec3i(0, 0, 0), new Dimensions(15, 13, 11), List.of(), paramsA);
        var b = new Component("MASS_MAIN", "s", new Vec3i(30, 0, 0), new Dimensions(15, 13, 11), List.of(), paramsB);
        var plan = LlmPlanTestFixtures.builder().allowCompilerSatellites(true).mode(LlmPlan.Mode.build)
                .styleProfile("MODERN").anchor(new Vec3i(0, 0, 0))
                .layout(new Layout(null, false, List.of())).components(List.of(a, b)).build();
        var before = PatchTestSnapshot.blocks(ComponentPlanCompiler.compile(plan));
        var after = PatchTestSnapshot.blocks(ComponentPlanCompiler.compile(plan, BlockPos.ORIGIN, null, null, false));
        var aBefore = before.keySet().stream().filter(p -> p.x() < 20).collect(Collectors.toSet());
        var aAfter = after.keySet().stream().filter(p -> p.x() < 20).collect(Collectors.toSet());
        var onlyBefore = new HashSet<>(aBefore); onlyBefore.removeAll(aAfter);
        var onlyAfter = new HashSet<>(aAfter); onlyAfter.removeAll(aBefore);
        System.out.println("onlyBefore(" + onlyBefore.size() + ")=" + onlyBefore.stream().limit(20).toList());
        onlyBefore.stream().limit(20).forEach(p -> System.out.println("  B " + p + " " + before.get(p)));
        System.out.println("onlyAfter(" + onlyAfter.size() + ")=" + onlyAfter.stream().limit(20).toList());
        onlyAfter.stream().limit(20).forEach(p -> System.out.println("  A " + p + " " + after.get(p)));
    }

    @Test void dumpFoundationFacadeZs() {
        Map<String, Object> massParams = Map.of(
                "anchor_mode", "min_corner", "window_ratio", 0.2, "facade_profile", "vertical_pilasters");
        Component mass = new Component("MASS_MAIN", "s0", new Vec3i(0, 0, 0),
                new Dimensions(16, 20, 9), List.of("pilasters"), massParams);
        Component foundation = new Component("FOUNDATION", "s0", new Vec3i(-2, -1, -2),
                new Dimensions(20, 20, 1), List.of(), Map.of("anchor_mode", "min_corner"));
        Component facade = new Component("FACADE_WINDOWS", "s0", new Vec3i(0, 3, 0),
                new Dimensions(16, 16, 5), List.of(),
                Map.of("window_ratio", 0.2, "window_aspect", "vertical_strip"));
        LlmPlan plan = LlmPlanTestFixtures.builder().allowCompilerSatellites(true)
                .mode(LlmPlan.Mode.build).styleProfile("DEFAULT").anchor(new Vec3i(0, 64, 0))
                .globalConstraints(new GlobalConstraints(GlobalConstraints.Facing.EAST, null, null))
                .layout(new Layout(null, false, List.of())).components(List.of(foundation, mass, facade)).build();
        List<BlockPatch> patches = ComponentPlanCompiler.compile(plan, null, null, null, false);
        patches.stream().filter(p -> p != null && p.targetBlock() != null
                        && (p.targetBlock().contains("glass") || p.targetBlock().contains("bars")))
                .collect(Collectors.groupingBy(BlockPatch::dz, Collectors.counting()))
                .entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(e -> System.out.println("glass@z=" + e.getKey() + " count=" + e.getValue()));
        patches.stream().filter(p -> p != null && p.dz() >= 17 && p.dz() <= 19)
                .limit(40)
                .forEach(p -> System.out.println("z" + p.dz() + " " + p.action() + " " + p.dx() + "," + p.dy() + " " + p.targetBlock()));
    }

    @Test void dumpStyleLog2Windows() throws Exception {
        try (var in = getClass().getResourceAsStream("/style_log_cases_round2/case_2.json")) {
            var plan = LlmPlanParser.parseAndValidate(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            var blocks = PatchTestSnapshot.blocks(ComponentPlanCompiler.compile(plan, BlockPos.ORIGIN, null, null, false));
            blocks.entrySet().stream()
                    .filter(e -> e.getValue() != null && e.getValue().contains("glass"))
                    .filter(e -> e.getKey().y() == 3 || e.getKey().y() == 6)
                    .forEach(e -> System.out.println("WIN " + e.getKey() + " " + e.getValue()));
        }
    }

    @Test void dumpStackedTowerCritical() {
        // Minimal: just compile sealed tower from test helpers is heavy; print converter failure via re-run path.
        System.out.println("see StackedTowerFullCompileEnvelopeTest failure at conversion.hasCriticalFailure");
    }

    @Test void dumpOct20Plan2() throws Exception {
        var path = Path.of("python_backend/tests/fixtures/complex_build_retest/oct20_access_2_repaired.normalized.json");
        var plan = com.formacraft.common.llm.dto.TransitionalSatellitePlans.ensureAllowIfAbsent(
                LlmPlanParser.parse(Files.readString(path)));
        var patches = ComponentPlanCompiler.compile(plan);
        System.out.println("empty=" + patches.isEmpty() + " gap=" + AssemblyCompileDiagnostics.get());
    }

    @Test void dumpStackedCritical() {
        // Re-run the sealed tower compile path from StackedTowerFullCompileEnvelopeTest is large;
        // print PlanPatchConverter failure if any via a tiny empty check placeholder.
        System.out.println("run StackedTower with message augmentation separately");
    }
}
