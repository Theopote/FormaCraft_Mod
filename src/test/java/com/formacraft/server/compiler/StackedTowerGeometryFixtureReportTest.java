package com.formacraft.server.compiler;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.formacraft.common.generation.component.util.OpenFramePolicy;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.Vec3i;
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
import java.util.Objects;

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

        Component pavilion = sealed.components().stream()
                .filter(c -> c != null && c.params() != null
                        && ("pavilion_1".equals(String.valueOf(c.params().get("component_id")))
                        || OpenFramePolicy.isOpen(c.params())
                        || (c.features() != null && c.features().stream().anyMatch(f ->
                        f != null && f.toLowerCase(Locale.ROOT).contains("pavilion")))))
                .findFirst().orElse(null);
        Component hall = sealed.components().stream()
                .filter(c -> c != null && c.params() != null
                        && "hall_main".equals(String.valueOf(c.params().get("component_id"))))
                .findFirst().orElse(null);
        assertNotNull(pavilion, "open pavilion host required");
        assertNotNull(hall, "main hall host required");
        assertTrue(OpenFramePolicy.isOpen(pavilion.params())
                        || (pavilion.features() != null && pavilion.features().stream().anyMatch(f ->
                        f != null && f.toLowerCase(Locale.ROOT).contains("pavilion"))),
                "pavilion must remain open-frame / pavilion-marked");

        AssemblyCompileDiagnostics.clear();
        List<BlockPatch> patches = ComponentPlanCompiler.compile(sealed, BlockPos.ORIGIN, null, null, false);
        assertFalse(AssemblyCompileDiagnostics.hasGap(),
                () -> String.valueOf(AssemblyCompileDiagnostics.get()));
        assertFalse(patches.isEmpty());
        Map<Vec3i, String> blocks = PatchTestSnapshot.blocks(patches);
        int minY = blocks.keySet().stream().mapToInt(Vec3i::y).min().orElse(0);
        int maxY = blocks.keySet().stream().mapToInt(Vec3i::y).max().orElse(0);
        assertTrue(maxY - minY + 1 >= 4, "multi-building garden must produce vertical solids");

        // Footprints must stay separated — not the same XZ origin and not nested containment.
        assertFalse(sameXzOrigin(hall, pavilion),
                "hall and pavilion must not share the same XZ origin");
        assertFalse(xzFullyContains(hall, pavilion) || xzFullyContains(pavilion, hall),
                "hall/pavilion footprints must not nest as one mass");

        int pavilionTop = pavilion.relativePosition().y() + pavilion.dimensions().height();
        Footprint pavilionFp = footprintOf(pavilion);
        long roofAbove = blocks.keySet().stream()
                .filter(p -> p.y() >= pavilionTop)
                .filter(p -> inside(pavilionFp, p.x(), p.z()))
                .count();
        assertTrue(roofAbove > 0,
                "open pavilion must have roof/support solids at or above column tops (y>=" + pavilionTop + ")");

        System.out.println("[fixture 20_pavilion_garden] components=" + sealed.components().size()
                + " hosts=" + hosts.size()
                + " patches=" + patches.size()
                + " solidY=" + minY + ".." + maxY
                + " envelope=" + (maxY - minY + 1)
                + " pavilionRoofAbove=" + roofAbove
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

        // 「不要窗」must survive — no facade window satellite and no glazing voxels.
        boolean hasFacadeWindows = sealed.components().stream().anyMatch(c ->
                c != null && c.componentType() != null
                        && c.componentType().toUpperCase(Locale.ROOT).contains("FACADE_WINDOWS"));
        assertFalse(hasFacadeWindows, "no-windows request must not invent FACADE_WINDOWS");
        long glassCells = blocks.values().stream().filter(b -> {
            if (b == null) return false;
            String lower = b.toLowerCase(Locale.ROOT);
            return lower.contains("glass") || lower.contains("pane");
        }).count();
        assertEquals(0, glassCells, "no-windows wood house must not emit glass/pane voxels");

        System.out.println("[fixture 13_wood_house_no_windows] components=" + sealed.components().size()
                + " mainH=" + main.dimensions().height()
                + " solidH=" + solidH
                + " patches=" + patches.size()
                + " solids=" + blocks.size()
                + " glass=" + glassCells
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

    @Test
    void archedEntranceHouseFixtureKeepsDoorOnGroundBand() throws Exception {
        LlmPlan plan = load("14_arched_entrance_house");
        Object promptObj = plan.planMeta() == null ? null : plan.planMeta().get("acceptance_prompt");
        String prompt = promptObj == null ? "建造一座带拱形大门的石砖房子" : String.valueOf(promptObj);
        LlmPlan sealed = RequestIntentInjector.ensure(plan, prompt);
        assertFalse(sealed.hasCapabilityGap(), () -> String.valueOf(sealed.capabilityGap()));

        Component entrance = sealed.components().stream()
                .filter(c -> c != null && c.componentType() != null
                        && c.componentType().toUpperCase(Locale.ROOT).contains("ENTRANCE"))
                .findFirst().orElse(null);
        assertNotNull(entrance, "arched entrance component must survive ensure");
        Component main = sealed.components().stream()
                .filter(c -> c != null && c.componentType() != null
                        && c.componentType().toUpperCase(Locale.ROOT).startsWith("MASS")
                        && c.dimensions() != null)
                .max(Comparator.comparingInt(c -> c.dimensions().width() * c.dimensions().depth()))
                .orElseThrow();
        int baseY = main.relativePosition() == null ? 0 : main.relativePosition().y();
        int doorY = entrance.relativePosition() == null ? baseY : entrance.relativePosition().y();
        assertTrue(doorY >= baseY && doorY <= baseY + 2,
                "entrance must sit on the ground band (doorY=" + doorY + ", baseY=" + baseY + ")");

        AssemblyCompileDiagnostics.clear();
        List<BlockPatch> patches = ComponentPlanCompiler.compile(sealed, BlockPos.ORIGIN, null, null, false);
        assertFalse(AssemblyCompileDiagnostics.hasGap(),
                () -> String.valueOf(AssemblyCompileDiagnostics.get()));
        assertFalse(patches.isEmpty());
        Map<Vec3i, String> blocks = PatchTestSnapshot.blocks(patches);
        boolean hasDoorBlock = blocks.values().stream().anyMatch(b ->
                b != null && b.toLowerCase(Locale.ROOT).contains("door"));
        assertTrue(hasDoorBlock || entrance.dimensions().height() >= 2,
                "arched entrance must emit door blocks or keep a usable opening height");

        System.out.println("[fixture 14_arched_entrance_house] components=" + sealed.components().size()
                + " doorY=" + doorY
                + " patches=" + patches.size()
                + " solids=" + blocks.size()
                + " gap=" + AssemblyCompileDiagnostics.get());
    }

    @Test
    void xieshanShrineFixtureKeepsRoofAboveHall() throws Exception {
        LlmPlan plan = load("11_xieshan_stone_shrine");
        Object promptObj = plan.planMeta() == null ? null : plan.planMeta().get("acceptance_prompt");
        String prompt = promptObj == null ? "建造一座歇山石砖神殿" : String.valueOf(promptObj);
        LlmPlan sealed = RequestIntentInjector.ensure(plan, prompt);
        assertFalse(sealed.hasCapabilityGap(), () -> String.valueOf(sealed.capabilityGap()));

        Component hall = sealed.components().stream()
                .filter(c -> c != null && c.params() != null
                        && "temple_hall".equals(String.valueOf(c.params().get("component_id"))))
                .findFirst().orElse(null);
        Component roof = sealed.components().stream()
                .filter(c -> c != null && c.componentType() != null
                        && c.componentType().toUpperCase(Locale.ROOT).startsWith("ROOF"))
                .findFirst().orElse(null);
        assertNotNull(hall, "temple hall mass required");
        assertNotNull(roof, "xieshan roof component required");
        assertEquals("temple_hall", String.valueOf(roof.params().get("host_id")));

        AssemblyCompileDiagnostics.clear();
        List<BlockPatch> patches = ComponentPlanCompiler.compile(sealed, BlockPos.ORIGIN, null, null, false);
        assertFalse(AssemblyCompileDiagnostics.hasGap(),
                () -> String.valueOf(AssemblyCompileDiagnostics.get()));
        assertFalse(patches.isEmpty());
        Map<Vec3i, String> blocks = PatchTestSnapshot.blocks(patches);

        int hallTop = hall.relativePosition().y() + hall.dimensions().height();
        Footprint hallFp = footprintOf(hall);
        long roofAbove = blocks.keySet().stream()
                .filter(p -> p.y() >= hallTop - 1)
                .filter(p -> inside(hallFp, p.x(), p.z())
                        || (p.x() >= hallFp.minX - 2 && p.x() <= hallFp.maxX + 2
                        && p.z() >= hallFp.minZ - 2 && p.z() <= hallFp.maxZ + 2))
                .count();
        assertTrue(roofAbove > 0, "xieshan roof must place solids at/above hall top y=" + hallTop);

        long stoneLike = blocks.values().stream().filter(b -> {
            if (b == null) return false;
            String lower = b.toLowerCase(Locale.ROOT);
            return lower.contains("stone") || lower.contains("brick") || lower.contains("andesite")
                    || lower.contains("cobble");
        }).count();
        assertTrue(stoneLike > 0, "stone shrine walls/foundation should emit stone-family blocks");

        System.out.println("[fixture 11_xieshan_stone_shrine] components=" + sealed.components().size()
                + " hallH=" + hall.dimensions().height()
                + " roofAbove=" + roofAbove
                + " stoneLike=" + stoneLike
                + " patches=" + patches.size()
                + " gap=" + AssemblyCompileDiagnostics.get());
    }

    @Test
    void modernFlatHouseFixtureKeepsThinRoofAndGroundDoor() throws Exception {
        LlmPlan plan = load("19_modern_flat_house");
        Object promptObj = plan.planMeta() == null ? null : plan.planMeta().get("acceptance_prompt");
        String prompt = promptObj == null ? "建造一座简约现代平屋顶住宅" : String.valueOf(promptObj);
        LlmPlan sealed = RequestIntentInjector.ensure(plan, prompt);
        assertFalse(sealed.hasCapabilityGap(), () -> String.valueOf(sealed.capabilityGap()));

        Component roof = sealed.components().stream()
                .filter(c -> c != null && c.componentType() != null
                        && c.componentType().toUpperCase(Locale.ROOT).startsWith("ROOF"))
                .findFirst().orElse(null);
        Component entrance = sealed.components().stream()
                .filter(c -> c != null && c.componentType() != null
                        && c.componentType().toUpperCase(Locale.ROOT).contains("ENTRANCE"))
                .findFirst().orElse(null);
        Component main = sealed.components().stream()
                .filter(c -> c != null && c.params() != null
                        && "main".equals(String.valueOf(c.params().get("component_id"))))
                .findFirst().orElseThrow();
        assertNotNull(roof, "flat roof component required");
        assertNotNull(entrance, "entrance required");
        assertTrue(roof.dimensions().height() <= 2,
                "modern flat roof must stay thin (h=" + roof.dimensions().height() + ")");

        int baseY = main.relativePosition().y();
        int doorY = entrance.relativePosition().y();
        assertTrue(doorY >= baseY && doorY <= baseY + 2,
                "flat-house door must stay on ground band");

        AssemblyCompileDiagnostics.clear();
        List<BlockPatch> patches = ComponentPlanCompiler.compile(sealed, BlockPos.ORIGIN, null, null, false);
        assertFalse(AssemblyCompileDiagnostics.hasGap(),
                () -> String.valueOf(AssemblyCompileDiagnostics.get()));
        assertFalse(patches.isEmpty());
        Map<Vec3i, String> blocks = PatchTestSnapshot.blocks(patches);
        int hallTop = baseY + main.dimensions().height();
        long roofBand = blocks.keySet().stream()
                .filter(p -> p.y() >= hallTop - 1 && p.y() <= hallTop + roof.dimensions().height())
                .count();
        assertTrue(roofBand > 0, "flat roof solids expected near hall top y=" + hallTop);

        System.out.println("[fixture 19_modern_flat_house] components=" + sealed.components().size()
                + " roofH=" + roof.dimensions().height()
                + " doorY=" + doorY
                + " roofBand=" + roofBand
                + " patches=" + patches.size()
                + " gap=" + AssemblyCompileDiagnostics.get());
    }

    @Test
    void templeCompoundFixtureKeepsCourtyardAndHallSolids() throws Exception {
        LlmPlan plan = load("07_temple_compound");
        Object promptObj = plan.planMeta() == null ? null : plan.planMeta().get("acceptance_prompt");
        String prompt = promptObj == null ? "建造一座带庭院的寺庙建筑群" : String.valueOf(promptObj);
        LlmPlan sealed = RequestIntentInjector.ensure(plan, prompt);
        assertFalse(sealed.hasCapabilityGap(), () -> String.valueOf(sealed.capabilityGap()));

        Component yard = sealed.components().stream()
                .filter(c -> c != null && c.componentType() != null
                        && c.componentType().toUpperCase(Locale.ROOT).contains("COURTYARD"))
                .findFirst().orElse(null);
        Component hall = sealed.components().stream()
                .filter(c -> c != null && c.params() != null
                        && "temple_hall".equals(String.valueOf(c.params().get("component_id"))))
                .findFirst().orElse(null);
        assertNotNull(yard, "temple courtyard required");
        assertNotNull(hall, "temple hall required");

        AssemblyCompileDiagnostics.clear();
        List<BlockPatch> patches = ComponentPlanCompiler.compile(sealed, BlockPos.ORIGIN, null, null, false);
        assertFalse(AssemblyCompileDiagnostics.hasGap(),
                () -> String.valueOf(AssemblyCompileDiagnostics.get()));
        assertFalse(patches.isEmpty());
        Map<Vec3i, String> blocks = PatchTestSnapshot.blocks(patches);

        Footprint yardFp = footprintOf(yard);
        Footprint hallFp = footprintOf(hall);
        long yardSolids = blocks.keySet().stream()
                .filter(p -> inside(yardFp, p.x(), p.z()))
                .count();
        long hallSolids = blocks.keySet().stream()
                .filter(p -> inside(hallFp, p.x(), p.z()))
                .count();
        assertTrue(yardSolids > 0, "courtyard footprint must retain paving/edge solids");
        assertTrue(hallSolids > 0, "temple hall footprint must retain wall/floor solids");
        assertTrue(hall.dimensions().height() >= 5, "temple hall must stay multi-storey");

        System.out.println("[fixture 07_temple_compound] components=" + sealed.components().size()
                + " yardSolids=" + yardSolids
                + " hallSolids=" + hallSolids
                + " hallH=" + hall.dimensions().height()
                + " patches=" + patches.size()
                + " gap=" + AssemblyCompileDiagnostics.get());
    }

    private static LlmPlan load(String fixtureId) throws Exception {
        try (InputStream in = StackedTowerGeometryFixtureReportTest.class
                .getResourceAsStream("/regressions/live_acceptance_20/" + fixtureId + ".json")) {
            assertNotNull(in, fixtureId);
            return MAPPER.readValue(in, LlmPlan.class);
        }
    }

    private record Footprint(int minX, int maxX, int minZ, int maxZ) {}

    private static Footprint footprintOf(Component c) {
        Vec3i p = Objects.requireNonNull(c.relativePosition());
        int w = Math.max(1, c.dimensions().width());
        int d = Math.max(1, c.dimensions().depth());
        return new Footprint(p.x(), p.x() + w - 1, p.z(), p.z() + d - 1);
    }

    private static boolean inside(Footprint fp, int x, int z) {
        return x >= fp.minX && x <= fp.maxX && z >= fp.minZ && z <= fp.maxZ;
    }

    private static boolean sameXzOrigin(Component a, Component b) {
        Vec3i pa = a.relativePosition();
        Vec3i pb = b.relativePosition();
        return pa != null && pb != null && pa.x() == pb.x() && pa.z() == pb.z();
    }

    private static boolean xzFullyContains(Component outer, Component inner) {
        Footprint o = footprintOf(outer);
        Footprint i = footprintOf(inner);
        return o.minX <= i.minX && o.maxX >= i.maxX && o.minZ <= i.minZ && o.maxZ >= i.maxZ;
    }
}
