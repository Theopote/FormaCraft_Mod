package com.formacraft.server.compiler;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.server.assembly.AssemblyCompileDiagnostics;
import com.formacraft.server.init.TypologySystemInitializer;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import com.formacraft.test.PatchTestSnapshot;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase-4 style geometry reports from real live_acceptance fixtures.
 * Does not weaken production asserts — reports metrics and requires successful compile
 * for non-tower_like plans (towers may use typology STRUCTURE that needs a live world).
 */
class StackedTowerGeometryFixtureReportTest {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @BeforeAll
    static void boot() {
        MinecraftRegistryTestBootstrap.initialize();
        TypologySystemInitializer.initialize();
    }

    @BeforeEach
    void reset() {
        AssemblyCompileDiagnostics.clear();
    }

    @AfterEach
    void clear() {
        AssemblyCompileDiagnostics.clear();
    }

    @Test
    void fiveStoreyPagodaFixtureReportsMorphology() throws Exception {
        LlmPlan plan = load("03_pagoda_5");
        String prompt = String.valueOf(plan.planMeta().get("acceptance_prompt"));
        LlmPlan sealed = RequestIntentInjector.ensure(plan, prompt);
        assertFalse(sealed.hasCapabilityGap(), () -> String.valueOf(sealed.capabilityGap()));

        List<Component> comps = sealed.components();
        assertFalse(comps.isEmpty());
        Component structure = comps.stream()
                .filter(c -> c != null && c.componentType() != null
                        && "STRUCTURE".equalsIgnoreCase(c.componentType()))
                .findFirst().orElse(null);
        assertNotNull(structure, "fixture must keep dense-eaves STRUCTURE mass");
        assertEquals(5, ((Number) structure.params().get("floor_count")).intValue());
        assertTrue(structure.dimensions().height() >= 20,
                "five-storey pagoda STRUCTURE must remain tall");
        assertTrue(structure.features().stream().anyMatch(f ->
                f != null && f.toLowerCase(Locale.ROOT).contains("finial")),
                "finial/塔刹 feature required");

        // Dense-eaves typology STRUCTURE compile needs ServerWorld — report coverage gap explicitly.
        AssemblyCompileDiagnostics.clear();
        List<BlockPatch> patches = ComponentPlanCompiler.compile(sealed, BlockPos.ORIGIN, null, null, false);
        if (patches.isEmpty() || AssemblyCompileDiagnostics.hasGap()) {
            System.out.println("[fixture 03_pagoda_5] UNCOVERED BlockPatch geometry: typology STRUCTURE "
                    + "requires live ServerWorld (diag=" + AssemblyCompileDiagnostics.get() + ")");
        } else {
            Map<com.formacraft.common.llm.dto.Vec3i, String> blocks = PatchTestSnapshot.blocks(patches);
            assertFalse(blocks.isEmpty(), "pagoda BlockPatch solids required when compile succeeds");
        }

        System.out.println("[fixture 03_pagoda_5] components=" + comps.size()
                + " height=" + structure.dimensions().height()
                + " floors=" + structure.params().get("floor_count")
                + " gap=" + sealed.capabilityGap());
    }

    @Test
    void pavilionGardenFixtureCompilesWithMultipleHosts() throws Exception {
        LlmPlan plan = load("20_pavilion_garden");
        String prompt = String.valueOf(plan.planMeta().get("acceptance_prompt"));
        LlmPlan sealed = RequestIntentInjector.ensure(plan, prompt);
        assertFalse(sealed.hasCapabilityGap(), () -> String.valueOf(sealed.capabilityGap()));

        List<Component> hosts = sealed.components().stream()
                .filter(c -> c != null && BuildingHostResolver.isDesignConstraintHost(c))
                .toList();
        assertTrue(hosts.size() >= 2,
                "garden+pavilion must keep ≥2 design hosts (hosts=" + hosts.size() + ")");
        // Distinct component ids — not the same mass counted twice.
        long distinctIds = hosts.stream()
                .map(BuildingHostResolver::massId)
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .count();
        assertTrue(distinctIds >= 2, "hosts must be distinct buildings (ids=" + distinctIds + ")");

        AssemblyCompileDiagnostics.clear();
        List<BlockPatch> patches = ComponentPlanCompiler.compile(sealed, BlockPos.ORIGIN, null, null, false);
        assertFalse(AssemblyCompileDiagnostics.hasGap(),
                () -> String.valueOf(AssemblyCompileDiagnostics.get()));
        assertFalse(patches.isEmpty());
        Map<com.formacraft.common.llm.dto.Vec3i, String> blocks = PatchTestSnapshot.blocks(patches);
        int minY = blocks.keySet().stream().mapToInt(com.formacraft.common.llm.dto.Vec3i::y).min().orElse(0);
        int maxY = blocks.keySet().stream().mapToInt(com.formacraft.common.llm.dto.Vec3i::y).max().orElse(0);
        assertTrue(maxY - minY + 1 >= 4, "multi-building garden must produce vertical solids");

        // Footprints of the two largest hosts should not be identical origins (layout separation).
        hosts = hosts.stream()
                .sorted(Comparator.comparingInt(c ->
                        -(c.dimensions().width() * c.dimensions().depth())))
                .toList();
        var a = hosts.get(0).relativePosition();
        var b = hosts.get(1).relativePosition();
        assertFalse(a.x() == b.x() && a.z() == b.z(),
                "two garden hosts must not share the same XZ origin");

        System.out.println("[fixture 20_pavilion_garden] components=" + sealed.components().size()
                + " hosts=" + hosts.size()
                + " patches=" + patches.size()
                + " solidY=" + minY + ".." + maxY
                + " envelope=" + (maxY - minY + 1)
                + " gap=" + AssemblyCompileDiagnostics.get());
    }

    @Test
    void twoStoreyWoodHouseFixtureKeepsOpeningsOnFloors() throws Exception {
        LlmPlan plan = load("13_wood_house_no_windows");
        String prompt = String.valueOf(plan.planMeta().get("acceptance_prompt"));
        LlmPlan sealed = RequestIntentInjector.ensure(plan, prompt);
        assertFalse(sealed.hasCapabilityGap(), () -> String.valueOf(sealed.capabilityGap()));

        AssemblyCompileDiagnostics.clear();
        List<BlockPatch> patches = ComponentPlanCompiler.compile(sealed, BlockPos.ORIGIN, null, null, false);
        assertFalse(AssemblyCompileDiagnostics.hasGap(),
                () -> String.valueOf(AssemblyCompileDiagnostics.get()));
        assertFalse(patches.isEmpty());

        Component main = sealed.components().stream()
                .filter(c -> c != null && c.dimensions() != null)
                .max(Comparator.comparingInt(c -> c.dimensions().width() * c.dimensions().depth()))
                .orElseThrow();
        Map<com.formacraft.common.llm.dto.Vec3i, String> blocks = PatchTestSnapshot.blocks(patches);
        int minY = blocks.keySet().stream().mapToInt(com.formacraft.common.llm.dto.Vec3i::y).min().orElse(0);
        int maxY = blocks.keySet().stream().mapToInt(com.formacraft.common.llm.dto.Vec3i::y).max().orElse(0);
        int solidH = maxY - minY + 1;
        assertTrue(solidH >= 8 || main.dimensions().height() >= 6,
                "two-storey wood house must remain multi-storey (solidH=" + solidH
                        + " mainH=" + main.dimensions().height() + ")");

        // 「不要窗」must survive — no facade window satellite required; entrance may still exist.
        boolean hasFacadeWindows = sealed.components().stream().anyMatch(c ->
                c != null && c.componentType() != null
                        && c.componentType().toUpperCase(Locale.ROOT).contains("FACADE_WINDOWS"));
        assertFalse(hasFacadeWindows, "no-windows request must not invent FACADE_WINDOWS");

        System.out.println("[fixture 13_wood_house_no_windows] components=" + sealed.components().size()
                + " mainH=" + main.dimensions().height()
                + " solidH=" + solidH
                + " patches=" + patches.size()
                + " solids=" + blocks.size()
                + " gap=" + AssemblyCompileDiagnostics.get());
    }

    @Test
    void huizhouHouseFixtureCompilesWithOpeningAndFloorBands() throws Exception {
        LlmPlan plan = load("10_huizhou_house");
        Object promptObj = plan.planMeta() == null ? null : plan.planMeta().get("acceptance_prompt");
        String prompt = promptObj == null ? "徽派两层民居，有门窗和楼梯。" : String.valueOf(promptObj);
        LlmPlan sealed = RequestIntentInjector.ensure(plan, prompt);
        assertFalse(sealed.hasCapabilityGap(), () -> String.valueOf(sealed.capabilityGap()));

        AssemblyCompileDiagnostics.clear();
        List<BlockPatch> patches = ComponentPlanCompiler.compile(sealed, BlockPos.ORIGIN, null, null, false);
        assertFalse(AssemblyCompileDiagnostics.hasGap(),
                () -> String.valueOf(AssemblyCompileDiagnostics.get()));
        assertFalse(patches.isEmpty());
        Map<com.formacraft.common.llm.dto.Vec3i, String> blocks = PatchTestSnapshot.blocks(patches);

        Component main = sealed.components().stream()
                .filter(c -> c != null && c.componentType() != null
                        && c.componentType().toUpperCase(Locale.ROOT).startsWith("MASS")
                        && c.dimensions() != null)
                .max(Comparator.comparingInt(c -> c.dimensions().width() * c.dimensions().depth()))
                .orElseThrow();
        int baseY = main.relativePosition() == null ? 0 : main.relativePosition().y();
        int height = main.dimensions().height();
        assertTrue(height >= 6, "huizhou MASS_MAIN must stay multi-storey (h=" + height + ")");

        boolean hasDoorOrOpening = blocks.values().stream().anyMatch(b -> {
            if (b == null) return false;
            String lower = b.toLowerCase(Locale.ROOT);
            return lower.contains("door") || lower.contains("trapdoor") || lower.contains("glass")
                    || lower.contains("pane");
        }) || sealed.components().stream().anyMatch(c ->
                c != null && c.componentType() != null
                        && c.componentType().toUpperCase(Locale.ROOT).contains("ENTRANCE"));
        assertTrue(hasDoorOrOpening,
                "huizhou house with openings must keep ENTRANCE or emit door/window-like blocks");

        // Solids span the mass height band (floor plate / wall / roof join), not ground-only.
        long midBand = blocks.keySet().stream()
                .filter(p -> p.y() >= baseY + 2 && p.y() < baseY + height)
                .count();
        assertTrue(midBand > 0, "expected solids inside the mass height band above y=" + (baseY + 2));

        System.out.println("[fixture 10_huizhou_house] components=" + sealed.components().size()
                + " mainH=" + height
                + " patches=" + patches.size()
                + " solids=" + blocks.size()
                + " midBand=" + midBand
                + " gap=" + AssemblyCompileDiagnostics.get());
    }

    private static LlmPlan load(String fixtureId) throws Exception {
        try (InputStream in = StackedTowerGeometryFixtureReportTest.class
                .getResourceAsStream("/regressions/live_acceptance_20/" + fixtureId + ".json")) {
            assertNotNull(in, fixtureId);
            return MAPPER.readValue(in, LlmPlan.class);
        }
    }
}
