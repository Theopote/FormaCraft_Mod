package com.formacraft.server.compiler;

import com.formacraft.common.generation.design.DesignRequirement;
import com.formacraft.common.generation.design.DesignRequirementAccess;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.LlmPlanTestFixtures;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.typology.UserConstraintContract;
import com.formacraft.server.assembly.AssemblyCompileDiagnostics;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Strict-mode (allow_compiler_satellites=false) integrity: provenance-gated omissions,
 * and multi-mass roof authorship surviving prepare → BlockPatch.
 */
class StrictModeDesignScopeIntegrityTest {

    @BeforeAll
    static void bootstrap() {
        MinecraftRegistryTestBootstrap.initialize();
    }

    @AfterEach
    void clearDiagnostics() {
        AssemblyCompileDiagnostics.clear();
    }

    @Test
    void aiPlannedRoofNoneStillRequiresRoofUnderPlanRequired() {
        Component mass = mass("house", Map.of(
                "component_id", "house",
                "roof_type", "none",
                "design_sources", Map.of("roof_type", "AI_PLANNED")));
        LlmPlan plan = strictPlan(List.of(mass), Map.of(
                "roof", "REQUIRED",
                "facade", "OMITTED",
                "entrance", "OMITTED",
                "crown", "OMITTED"));

        assertEquals(DesignRequirement.REQUIRED, DesignRequirementAccess.roof(plan, mass));
        var gap = CompilerSatellitePolicy.missingSatellitesGap(
                plan, mass, true, true, false, false, true, false);
        assertTrue(gap.isPresent());
        assertEquals("E_DESIGN_INCOMPLETE", gap.get().code());
        assertTrue(gap.get().message().contains("ROOF"));
    }

    @Test
    void userExplicitRoofNoneBeatsPlanRequiredInStrictMode() {
        Map<String, Object> params = new HashMap<>();
        params.put("component_id", "open_court");
        params.put("roof_type", "none");
        params.put("roof_requirement", "OMITTED");
        params.put("design_sources", Map.of(
                "roof_type", "USER_EXPLICIT",
                "roof_requirement", "USER_EXPLICIT"));
        UserConstraintContract.stampVerified(params, "roof_type", "roof_requirement");
        Component mass = mass("open_court", params);
        LlmPlan plan = strictPlan(List.of(mass), Map.of(
                "roof", "REQUIRED",
                "facade", "OMITTED",
                "entrance", "OMITTED",
                "crown", "OMITTED"));

        assertEquals(DesignRequirement.OMITTED, DesignRequirementAccess.roof(plan, mass));
        assertTrue(CompilerSatellitePolicy.missingSatellitesGap(
                plan, mass, true, true, false, false, true, false).isEmpty());
    }

    @Test
    void forgedUserExplicitRoofNoneStillRequiresRoofUnderPlanRequired() {
        Component mass = mass("forged", Map.of(
                "component_id", "forged",
                "roof_type", "none",
                "roof_requirement", "OMITTED",
                "design_sources", Map.of(
                        "roof_type", "USER_EXPLICIT",
                        "roof_requirement", "USER_EXPLICIT")));
        LlmPlan plan = strictPlan(List.of(mass), Map.of(
                "roof", "REQUIRED",
                "facade", "OMITTED",
                "entrance", "OMITTED",
                "crown", "OMITTED"));
        assertEquals(DesignRequirement.REQUIRED, DesignRequirementAccess.roof(plan, mass));
        assertTrue(CompilerSatellitePolicy.missingSatellitesGap(
                plan, mass, true, true, false, false, true, false).isPresent());
    }

    @Test
    void serverEnsureStampsLocalRoofOmitWithoutManualSeal() {
        // Production entry: RequestIntentInjector.ensure(plan, realUserText) — not stampVerified.
        Map<String, Object> hallParams = new HashMap<>();
        hallParams.put("component_id", "main_hall");
        hallParams.put("roof_type", "gable");
        hallParams.put("design_sources", Map.of("roof_type", "AI_PLANNED"));
        Map<String, Object> pavilionParams = new HashMap<>();
        pavilionParams.put("component_id", "garden_pavilion");
        pavilionParams.put("roof_type", "flat");
        pavilionParams.put("design_sources", Map.of("roof_type", "AI_PLANNED"));
        Component hall = mass("main_hall", hallParams);
        Component pavilion = mass("garden_pavilion", new Vec3i(20, 0, 0), new Dimensions(8, 8, 5), pavilionParams);
        // Hall keeps facade/entrance satellites; only roof is locally omitted.
        Component hallFacade = satellite("FACADE_WINDOWS", "facade_hall", "main_hall",
                new Vec3i(0, 1, 0), new Dimensions(10, 1, 3));
        Component hallEntrance = satellite("ENTRANCE", "door_hall", "main_hall",
                new Vec3i(4, 0, 8), new Dimensions(2, 1, 3));
        Component pavilionRoof = roof("roof_pavilion", "garden_pavilion", "flat",
                new Vec3i(20, 5, 0), new Dimensions(10, 10, 2));
        Component pavilionFacade = satellite("FACADE_WINDOWS", "facade_pavilion", "garden_pavilion",
                new Vec3i(20, 1, 0), new Dimensions(8, 1, 3));
        Component pavilionEntrance = satellite("ENTRANCE", "door_pavilion", "garden_pavilion",
                new Vec3i(23, 0, 8), new Dimensions(2, 1, 3));

        LlmPlan raw = strictPlan(
                List.of(hall, pavilion, hallFacade, hallEntrance,
                        pavilionRoof, pavilionFacade, pavilionEntrance),
                Map.of(
                        "roof", "REQUIRED",
                        "facade", "REQUIRED",
                        "entrance", "REQUIRED",
                        "crown", "OMITTED"));

        String userText = "第一栋不要屋顶，第二栋保留平顶和小窗";
        LlmPlan ensured = RequestIntentInjector.ensure(raw, userText);
        Component sealedHall = ensured.components().stream()
                .filter(c -> c != null && c.params() != null
                        && "main_hall".equals(String.valueOf(c.params().get("component_id"))))
                .findFirst().orElseThrow();
        assertTrue(UserConstraintContract.isServerVerified(sealedHall.params(), "roof_type"));
        assertTrue(UserConstraintContract.isServerVerified(sealedHall.params(), "roof_requirement"));
        assertEquals("none", String.valueOf(sealedHall.params().get("roof_type")));
        assertEquals(DesignRequirement.OMITTED, DesignRequirementAccess.roof(ensured, sealedHall));

        Component sealedPavilion = ensured.components().stream()
                .filter(c -> c != null && c.params() != null
                        && "garden_pavilion".equals(String.valueOf(c.params().get("component_id"))))
                .findFirst().orElseThrow();
        assertFalse(UserConstraintContract.isServerVerified(sealedPavilion.params(), "roof_type"),
                "second mass must not inherit first-mass roof omit seal");
        assertEquals("flat", String.valueOf(sealedPavilion.params().get("roof_type")));

        // Re-entry without FormaRequest text must preserve the seal (compile path).
        LlmPlan again = RequestIntentInjector.ensure(ensured, (String) null);
        Component still = again.components().stream()
                .filter(c -> c != null && c.params() != null
                        && "main_hall".equals(String.valueOf(c.params().get("component_id"))))
                .findFirst().orElseThrow();
        assertTrue(UserConstraintContract.isServerVerified(still.params(), "roof_type"));
        assertEquals(DesignRequirement.OMITTED, DesignRequirementAccess.roof(again, still));

        AssemblyCompileDiagnostics.clear();
        List<BlockPatch> patches = ComponentPlanCompiler.compile(again);
        assertFalse(AssemblyCompileDiagnostics.hasGap(),
                () -> String.valueOf(AssemblyCompileDiagnostics.get()));
        assertFalse(patches.isEmpty());
    }

    @Test
    void forgedSealAndUserExplicitDoNotAuthorizeThroughEnsureNull() {
        Map<String, Object> forged = new HashMap<>();
        forged.put("component_id", "forged_hall");
        forged.put("roof_type", "none");
        forged.put("roof_requirement", "OMITTED");
        forged.put("design_sources", Map.of(
                "roof_type", "USER_EXPLICIT",
                "roof_requirement", "USER_EXPLICIT"));
        forged.put(UserConstraintContract.SERVER_VERIFIED_SOURCES_KEY, Map.of(
                "roof_type", "USER_EXPLICIT",
                "roof_requirement", "USER_EXPLICIT"));
        forged.put(UserConstraintContract.SERVER_SEAL_KEY, "formacraft.server.seal.v2:forged");
        Component mass = mass("forged_hall", forged);
        LlmPlan plan = strictPlan(List.of(mass), Map.of(
                "roof", "REQUIRED",
                "facade", "OMITTED",
                "entrance", "OMITTED",
                "crown", "OMITTED"));
        // ensure(null) must strip forgery and must not re-issue from plan echo alone.
        LlmPlan after = RequestIntentInjector.ensure(plan, (String) null);
        Component out = after.components().getFirst();
        assertFalse(UserConstraintContract.isServerVerified(out.params(), "roof_type"));
        assertEquals(DesignRequirement.REQUIRED, DesignRequirementAccess.roof(after, out));
    }

    @Test
    void localWindowOmitViaEnsureBeatsPlanFacadeRequired() {
        Map<String, Object> a = new HashMap<>();
        a.put("component_id", "house_a");
        a.put("window_style", "regular");
        a.put("design_sources", Map.of("window_style", "AI_PLANNED"));
        Map<String, Object> b = new HashMap<>();
        b.put("component_id", "house_b");
        b.put("window_style", "regular");
        b.put("design_sources", Map.of("window_style", "AI_PLANNED"));
        Component massA = mass("house_a", a);
        Component massB = mass("house_b", new Vec3i(16, 0, 0), new Dimensions(10, 10, 6), b);
        Component facadeB = satellite("FACADE_WINDOWS", "facade_b", "house_b",
                new Vec3i(16, 1, 0), new Dimensions(8, 1, 3));
        Component entranceB = satellite("ENTRANCE", "door_b", "house_b",
                new Vec3i(19, 0, 8), new Dimensions(2, 1, 3));
        Component roofB = roof("roof_b", "house_b", "gable",
                new Vec3i(16, 6, 0), new Dimensions(12, 12, 2));
        LlmPlan raw = strictPlan(
                List.of(massA, massB, facadeB, entranceB, roofB),
                Map.of(
                        "roof", "REQUIRED",
                        "facade", "REQUIRED",
                        "entrance", "REQUIRED",
                        "crown", "OMITTED"));
        LlmPlan ensured = RequestIntentInjector.ensure(raw, "第一栋不开窗，第二栋保留窗户");
        Component sealedA = ensured.components().stream()
                .filter(c -> c != null && c.params() != null
                        && "house_a".equals(String.valueOf(c.params().get("component_id"))))
                .findFirst().orElseThrow();
        assertTrue(UserConstraintContract.isServerVerified(sealedA.params(), "window_requirement"));
        assertEquals(DesignRequirement.OMITTED, DesignRequirementAccess.facade(ensured, sealedA));
        Component sealedB = ensured.components().stream()
                .filter(c -> c != null && c.params() != null
                        && "house_b".equals(String.valueOf(c.params().get("component_id"))))
                .findFirst().orElseThrow();
        assertEquals(DesignRequirement.REQUIRED, DesignRequirementAccess.facade(ensured, sealedB));
    }

    @Test
    void massMainHallOmitDoesNotFollowComponentListOrder() {
        // Both hosts are MASS_MAIN; "main" in componentType must not score pavilion as hall.
        Map<String, Object> hallParams = new HashMap<>();
        hallParams.put("component_id", "main_hall");
        hallParams.put("roof_type", "xieshan");
        hallParams.put("design_sources", Map.of("roof_type", "AI_PLANNED"));
        Map<String, Object> pavilionParams = new HashMap<>();
        pavilionParams.put("component_id", "garden_pavilion");
        pavilionParams.put("roof_type", "flat");
        pavilionParams.put("design_sources", Map.of("roof_type", "AI_PLANNED"));
        Component hall = mass("main_hall", hallParams);
        Component pavilion = mass("garden_pavilion", new Vec3i(20, 0, 0), new Dimensions(8, 8, 5), pavilionParams);
        String userText = "主殿不要屋顶，旁边亭子保留屋顶";
        for (List<Component> order : List.of(
                List.of(hall, pavilion),
                List.of(pavilion, hall))) {
            LlmPlan raw = strictPlan(order, Map.of(
                    "roof", "REQUIRED",
                    "facade", "OMITTED",
                    "entrance", "OMITTED",
                    "crown", "OMITTED"));
            LlmPlan ensured = RequestIntentInjector.ensure(raw, userText);
            Component sealedHall = byId(ensured, "main_hall");
            Component sealedPavilion = byId(ensured, "garden_pavilion");
            assertTrue(UserConstraintContract.isServerVerified(sealedHall.params(), "roof_type"),
                    "hall omit must seal regardless of list order");
            assertEquals("none", String.valueOf(sealedHall.params().get("roof_type")));
            assertFalse(UserConstraintContract.isServerVerified(sealedPavilion.params(), "roof_type"),
                    "pavilion must not inherit hall omit when both are MASS_MAIN");
            assertEquals("flat", String.valueOf(sealedPavilion.params().get("roof_type")));
        }
    }

    @Test
    void pavilionBesideHallOmitBindsPavilionOnly() {
        Map<String, Object> hallParams = new HashMap<>();
        hallParams.put("component_id", "main_hall");
        hallParams.put("roof_type", "xieshan");
        hallParams.put("design_sources", Map.of("roof_type", "AI_PLANNED"));
        Map<String, Object> pavilionParams = new HashMap<>();
        pavilionParams.put("component_id", "garden_pavilion");
        pavilionParams.put("roof_type", "flat");
        pavilionParams.put("design_sources", Map.of("roof_type", "AI_PLANNED"));
        Component hall = mass("main_hall", hallParams);
        Component pavilion = mass("garden_pavilion", new Vec3i(20, 0, 0), new Dimensions(8, 8, 5), pavilionParams);
        LlmPlan raw = strictPlan(List.of(hall, pavilion), Map.of(
                "roof", "REQUIRED",
                "facade", "OMITTED",
                "entrance", "OMITTED",
                "crown", "OMITTED"));
        LlmPlan ensured = RequestIntentInjector.ensure(raw, "主殿旁的亭子不要屋顶");
        Component sealedHall = byId(ensured, "main_hall");
        Component sealedPavilion = byId(ensured, "garden_pavilion");
        assertFalse(UserConstraintContract.isServerVerified(sealedHall.params(), "roof_type"));
        assertEquals("xieshan", String.valueOf(sealedHall.params().get("roof_type")));
        assertTrue(UserConstraintContract.isServerVerified(sealedPavilion.params(), "roof_type"));
        assertEquals("none", String.valueOf(sealedPavilion.params().get("roof_type")));
    }

    @Test
    void twoPavilionsWithoutUniqueScopeDoNotGuessOmitHost() {
        Map<String, Object> a = new HashMap<>();
        a.put("component_id", "garden_pavilion_east");
        a.put("roof_type", "flat");
        a.put("design_sources", Map.of("roof_type", "AI_PLANNED"));
        Map<String, Object> b = new HashMap<>();
        b.put("component_id", "garden_pavilion_west");
        b.put("roof_type", "flat");
        b.put("design_sources", Map.of("roof_type", "AI_PLANNED"));
        Component east = mass("east", a);
        Component west = mass("west", new Vec3i(16, 0, 0), new Dimensions(8, 8, 5), b);
        LlmPlan raw = strictPlan(List.of(east, west), Map.of(
                "roof", "REQUIRED",
                "facade", "OMITTED",
                "entrance", "OMITTED",
                "crown", "OMITTED"));
        LlmPlan ensured = RequestIntentInjector.ensure(raw, "亭子不要屋顶");
        Component sealedEast = byId(ensured, "garden_pavilion_east");
        Component sealedWest = byId(ensured, "garden_pavilion_west");
        assertFalse(UserConstraintContract.isServerVerified(sealedEast.params(), "roof_type"),
                "ambiguous pavilion role must not seal first-in-list");
        assertFalse(UserConstraintContract.isServerVerified(sealedWest.params(), "roof_type"));
        assertEquals("flat", String.valueOf(sealedEast.params().get("roof_type")));
        assertEquals("flat", String.valueOf(sealedWest.params().get("roof_type")));
        assertTrue(ensured.hasCapabilityGap(), "hard omit without unique host must block");
        assertEquals("E_DESIGN_OMIT_HOST_SCOPE", ensured.capabilityGap().code());
    }

    @Test
    void dualRoofOmitDeclarationsDoNotFalseConflict() {
        Map<String, Object> hallParams = new HashMap<>();
        hallParams.put("component_id", "main_hall");
        hallParams.put("roof_type", "xieshan");
        hallParams.put("design_sources", Map.of("roof_type", "AI_PLANNED"));
        Map<String, Object> pavilionParams = new HashMap<>();
        pavilionParams.put("component_id", "garden_pavilion");
        pavilionParams.put("roof_type", "flat");
        pavilionParams.put("design_sources", Map.of("roof_type", "AI_PLANNED"));
        // Python-style per-declaration targets (must not be domain-aggregated into a conflict).
        Map<String, Object> proportionHints = new HashMap<>();
        proportionHints.put("building_contract", Map.of(
                "requirements", List.of(
                        Map.of(
                                "id", "req_disable_roof_type",
                                "property", "roof_type",
                                "value", "none",
                                "source_text", List.of("不要屋顶"),
                                "scope", "named_main_hall",
                                "target_components", List.of("main_hall")),
                        Map.of(
                                "id", "req_disable_roof_type_1",
                                "property", "roof_type",
                                "value", "none",
                                "source_text", List.of("不要屋顶"),
                                "scope", "named_pavilion",
                                "target_components", List.of("garden_pavilion")))));
        Component hall = mass("main_hall", hallParams);
        Component pavilion = mass("garden_pavilion", new Vec3i(20, 0, 0), new Dimensions(8, 8, 5), pavilionParams);
        LlmPlan raw = LlmPlanTestFixtures.builder()
                .allowCompilerSatellites(false)
                .styleProfile("DEFAULT")
                .components(List.of(hall, pavilion))
                .planMeta(Map.of(
                        "allow_compiler_satellites", false,
                        "design_requirements", Map.of(
                                "roof", "REQUIRED", "facade", "OMITTED",
                                "entrance", "OMITTED", "crown", "OMITTED")))
                .proportionHints(proportionHints)
                .build();
        LlmPlan ensured = RequestIntentInjector.ensure(raw, "主殿不要屋顶，旁边的亭子也不要屋顶");
        assertFalse(ensured.hasCapabilityGap(), () -> String.valueOf(ensured.capabilityGap()));
        assertTrue(UserConstraintContract.isServerVerified(byId(ensured, "main_hall").params(), "roof_type"));
        assertTrue(UserConstraintContract.isServerVerified(byId(ensured, "garden_pavilion").params(), "roof_type"));
        assertEquals("none", String.valueOf(byId(ensured, "main_hall").params().get("roof_type")));
        assertEquals("none", String.valueOf(byId(ensured, "garden_pavilion").params().get("roof_type")));
    }

    @Test
    void dualRoofOmitSurvivesComponentAndClauseOrderSwap() {
        Map<String, Object> hallParams = new HashMap<>();
        hallParams.put("component_id", "main_hall");
        hallParams.put("roof_type", "xieshan");
        hallParams.put("design_sources", Map.of("roof_type", "AI_PLANNED"));
        Map<String, Object> pavilionParams = new HashMap<>();
        pavilionParams.put("component_id", "garden_pavilion");
        pavilionParams.put("roof_type", "flat");
        pavilionParams.put("design_sources", Map.of("roof_type", "AI_PLANNED"));
        Component hall = mass("main_hall", hallParams);
        Component pavilion = mass("garden_pavilion", new Vec3i(20, 0, 0), new Dimensions(8, 8, 5), pavilionParams);
        LlmPlan pavilionFirst = strictPlan(List.of(pavilion, hall), Map.of(
                "roof", "REQUIRED", "facade", "OMITTED", "entrance", "OMITTED", "crown", "OMITTED"));
        LlmPlan ensured = RequestIntentInjector.ensure(pavilionFirst, "旁边的亭子不要屋顶，主殿也不要屋顶");
        assertFalse(ensured.hasCapabilityGap(), () -> String.valueOf(ensured.capabilityGap()));
        assertTrue(UserConstraintContract.isServerVerified(byId(ensured, "main_hall").params(), "roof_type"));
        assertTrue(UserConstraintContract.isServerVerified(byId(ensured, "garden_pavilion").params(), "roof_type"));
        assertEquals("none", String.valueOf(byId(ensured, "main_hall").params().get("roof_type")));
        assertEquals("none", String.valueOf(byId(ensured, "garden_pavilion").params().get("roof_type")));
    }

    @Test
    void pavilionOnlyHeightDoesNotRewriteHall() {
        Map<String, Object> hallParams = new HashMap<>();
        hallParams.put("component_id", "main_hall");
        hallParams.put("height", 12);
        hallParams.put("design_sources", Map.of("height", "AI_PLANNED", "roof_type", "AI_PLANNED"));
        hallParams.put("roof_type", "xieshan");
        Map<String, Object> pavilionParams = new HashMap<>();
        pavilionParams.put("component_id", "garden_pavilion");
        pavilionParams.put("height", 8);
        pavilionParams.put("design_sources", Map.of("height", "AI_PLANNED", "roof_type", "AI_PLANNED"));
        pavilionParams.put("roof_type", "flat");
        Component hall = mass("main_hall", new Vec3i(0, 0, 0), new Dimensions(14, 12, 12), hallParams);
        Component pavilion = mass("garden_pavilion", new Vec3i(20, 0, 0), new Dimensions(8, 8, 8), pavilionParams);
        LlmPlan raw = strictPlan(List.of(hall, pavilion), Map.of(
                "roof", "REQUIRED", "facade", "OMITTED", "entrance", "OMITTED", "crown", "OMITTED"));
        LlmPlan ensured = RequestIntentInjector.ensure(raw, "建一座主殿，旁边放一座高度6格的亭子");
        assertFalse(ensured.hasCapabilityGap(), () -> String.valueOf(ensured.capabilityGap()));
        Component outHall = byId(ensured, "main_hall");
        Component outPavilion = byId(ensured, "garden_pavilion");
        assertEquals(12, outHall.dimensions().height());
        assertEquals(12, ((Number) outHall.params().get("height")).intValue());
        assertFalse(UserConstraintContract.isServerVerified(outHall.params(), "height"));
        assertEquals(6, outPavilion.dimensions().height());
        assertEquals(6, ((Number) outPavilion.params().get("height")).intValue());
        assertTrue(UserConstraintContract.isServerVerified(outPavilion.params(), "height"));
    }

    @Test
    void nearestPavilionHeightBesideHallPhrase() {
        Map<String, Object> hallParams = new HashMap<>();
        hallParams.put("component_id", "main_hall");
        hallParams.put("height", 18);
        hallParams.put("design_sources", Map.of("height", "AI_PLANNED"));
        Map<String, Object> pavilionParams = new HashMap<>();
        pavilionParams.put("component_id", "garden_pavilion");
        pavilionParams.put("height", 9);
        pavilionParams.put("design_sources", Map.of("height", "AI_PLANNED"));
        LlmPlan raw = strictPlan(List.of(
                mass("main_hall", new Vec3i(0, 0, 0), new Dimensions(14, 12, 18), hallParams),
                mass("garden_pavilion", new Vec3i(20, 0, 0), new Dimensions(8, 8, 9), pavilionParams)),
                Map.of("roof", "OMITTED", "facade", "OMITTED", "entrance", "OMITTED", "crown", "OMITTED"));
        LlmPlan ensured = RequestIntentInjector.ensure(raw, "主殿旁边的亭子高度6格");
        assertEquals(18, byId(ensured, "main_hall").dimensions().height());
        assertEquals(6, byId(ensured, "garden_pavilion").dimensions().height());
        assertTrue(UserConstraintContract.isServerVerified(byId(ensured, "garden_pavilion").params(), "height"));
    }

    @Test
    void dualHeightsSurviveComponentOrderSwap() {
        Map<String, Object> hallParams = new HashMap<>();
        hallParams.put("component_id", "main_hall");
        hallParams.put("height", 10);
        hallParams.put("design_sources", Map.of("height", "AI_PLANNED"));
        Map<String, Object> pavilionParams = new HashMap<>();
        pavilionParams.put("component_id", "garden_pavilion");
        pavilionParams.put("height", 8);
        pavilionParams.put("design_sources", Map.of("height", "AI_PLANNED"));
        LlmPlan pavilionFirst = strictPlan(List.of(
                mass("garden_pavilion", new Vec3i(20, 0, 0), new Dimensions(8, 8, 8), pavilionParams),
                mass("main_hall", new Vec3i(0, 0, 0), new Dimensions(14, 12, 10), hallParams)),
                Map.of("roof", "OMITTED", "facade", "OMITTED", "entrance", "OMITTED", "crown", "OMITTED"));
        LlmPlan ensured = RequestIntentInjector.ensure(pavilionFirst, "主殿高20格，亭子高6格");
        assertFalse(ensured.hasCapabilityGap(), () -> String.valueOf(ensured.capabilityGap()));
        assertEquals(20, byId(ensured, "main_hall").dimensions().height());
        assertEquals(6, byId(ensured, "garden_pavilion").dimensions().height());
    }

    @Test
    void ambiguousPavilionHeightIsCapabilityGap() {
        Map<String, Object> a = new HashMap<>();
        a.put("component_id", "garden_pavilion_east");
        a.put("height", 8);
        a.put("design_sources", Map.of("height", "AI_PLANNED"));
        Map<String, Object> b = new HashMap<>();
        b.put("component_id", "garden_pavilion_west");
        b.put("height", 8);
        b.put("design_sources", Map.of("height", "AI_PLANNED"));
        LlmPlan raw = strictPlan(List.of(
                mass("east", new Vec3i(0, 0, 0), new Dimensions(8, 8, 8), a),
                mass("west", new Vec3i(16, 0, 0), new Dimensions(8, 8, 8), b)),
                Map.of("roof", "OMITTED", "facade", "OMITTED", "entrance", "OMITTED", "crown", "OMITTED"));
        LlmPlan ensured = RequestIntentInjector.ensure(raw, "亭子高度6格");
        assertEquals(8, byId(ensured, "garden_pavilion_east").dimensions().height());
        assertEquals(8, byId(ensured, "garden_pavilion_west").dimensions().height());
        assertFalse(UserConstraintContract.isServerVerified(byId(ensured, "garden_pavilion_east").params(), "height"));
        assertTrue(ensured.hasCapabilityGap());
        assertEquals("E_DESIGN_HEIGHT_HOST_SCOPE", ensured.capabilityGap().code());
    }

    @Test
    void allBuildingsOmitRoofSealsEveryHost() {
        Map<String, Object> hallParams = new HashMap<>();
        hallParams.put("component_id", "main_hall");
        hallParams.put("roof_type", "xieshan");
        hallParams.put("design_sources", Map.of("roof_type", "AI_PLANNED"));
        Map<String, Object> pavilionParams = new HashMap<>();
        pavilionParams.put("component_id", "garden_pavilion");
        pavilionParams.put("roof_type", "flat");
        pavilionParams.put("design_sources", Map.of("roof_type", "AI_PLANNED"));
        LlmPlan raw = strictPlan(List.of(
                mass("main_hall", hallParams),
                mass("garden_pavilion", new Vec3i(20, 0, 0), new Dimensions(8, 8, 5), pavilionParams)),
                Map.of("roof", "REQUIRED", "facade", "OMITTED", "entrance", "OMITTED", "crown", "OMITTED"));
        LlmPlan ensured = RequestIntentInjector.ensure(raw, "建造两栋建筑，所有建筑都不要屋顶");
        assertFalse(ensured.hasCapabilityGap(), () -> String.valueOf(ensured.capabilityGap()));
        assertTrue(UserConstraintContract.isServerVerified(byId(ensured, "main_hall").params(), "roof_type"));
        assertTrue(UserConstraintContract.isServerVerified(byId(ensured, "garden_pavilion").params(), "roof_type"));
        assertEquals("none", String.valueOf(byId(ensured, "main_hall").params().get("roof_type")));
        assertEquals("none", String.valueOf(byId(ensured, "garden_pavilion").params().get("roof_type")));
    }

    @Test
    void multiNamedOmitRoofSealsBothHosts() {
        Map<String, Object> hallParams = new HashMap<>();
        hallParams.put("component_id", "main_hall");
        hallParams.put("roof_type", "xieshan");
        hallParams.put("design_sources", Map.of("roof_type", "AI_PLANNED"));
        Map<String, Object> pavilionParams = new HashMap<>();
        pavilionParams.put("component_id", "garden_pavilion");
        pavilionParams.put("roof_type", "flat");
        pavilionParams.put("design_sources", Map.of("roof_type", "AI_PLANNED"));
        LlmPlan raw = strictPlan(List.of(
                mass("main_hall", hallParams),
                mass("garden_pavilion", new Vec3i(20, 0, 0), new Dimensions(8, 8, 5), pavilionParams)),
                Map.of("roof", "REQUIRED", "facade", "OMITTED", "entrance", "OMITTED", "crown", "OMITTED"));
        LlmPlan ensured = RequestIntentInjector.ensure(raw, "主殿和亭子都不要屋顶");
        assertFalse(ensured.hasCapabilityGap(), () -> String.valueOf(ensured.capabilityGap()));
        assertTrue(UserConstraintContract.isServerVerified(byId(ensured, "main_hall").params(), "roof_type"));
        assertTrue(UserConstraintContract.isServerVerified(byId(ensured, "garden_pavilion").params(), "roof_type"));
    }

    @Test
    void allBuildingsOmitIgnoresStructureStairHost() {
        Map<String, Object> hallParams = new HashMap<>();
        hallParams.put("component_id", "main_hall");
        hallParams.put("roof_type", "xieshan");
        hallParams.put("design_sources", Map.of("roof_type", "AI_PLANNED"));
        Map<String, Object> pavilionParams = new HashMap<>();
        pavilionParams.put("component_id", "garden_pavilion");
        pavilionParams.put("roof_type", "flat");
        pavilionParams.put("design_sources", Map.of("roof_type", "AI_PLANNED"));
        Map<String, Object> stairParams = new HashMap<>();
        stairParams.put("component_id", "entrance_stair");
        stairParams.put("roof_type", "flat");
        stairParams.put("design_sources", Map.of("roof_type", "AI_PLANNED"));
        Component stair = new Component(
                "STRUCTURE", "stair", new Vec3i(8, 0, 0), new Dimensions(3, 6, 4),
                List.of("stair:straight_single_run"), stairParams);
        LlmPlan raw = strictPlan(List.of(
                mass("main_hall", hallParams),
                mass("garden_pavilion", new Vec3i(20, 0, 0), new Dimensions(8, 8, 5), pavilionParams),
                stair),
                Map.of("roof", "REQUIRED", "facade", "OMITTED", "entrance", "OMITTED", "crown", "OMITTED"));
        LlmPlan ensured = RequestIntentInjector.ensure(raw, "所有建筑都不要屋顶");
        assertFalse(ensured.hasCapabilityGap(), () -> String.valueOf(ensured.capabilityGap()));
        assertTrue(UserConstraintContract.isServerVerified(byId(ensured, "main_hall").params(), "roof_type"));
        assertTrue(UserConstraintContract.isServerVerified(byId(ensured, "garden_pavilion").params(), "roof_type"));
        assertFalse(UserConstraintContract.isServerVerified(byId(ensured, "entrance_stair").params(), "roof_type"),
                "STRUCTURE stair must not receive building-level omit seals");
        assertEquals("flat", String.valueOf(byId(ensured, "entrance_stair").params().get("roof_type")));
    }

    @Test
    void missingPavilionNamedHeightDoesNotSealHall() {
        Map<String, Object> hallParams = new HashMap<>();
        hallParams.put("component_id", "main_hall");
        hallParams.put("height", 14);
        hallParams.put("roof_type", "xieshan");
        hallParams.put("design_sources", Map.of("height", "AI_PLANNED", "roof_type", "AI_PLANNED"));
        LlmPlan raw = strictPlan(List.of(
                mass("main_hall", new Vec3i(0, 0, 0), new Dimensions(14, 12, 14), hallParams)),
                Map.of("roof", "REQUIRED", "facade", "OMITTED", "entrance", "OMITTED", "crown", "OMITTED"));
        LlmPlan ensured = RequestIntentInjector.ensure(raw, "建造主殿和亭子，亭子高度6格，亭子不要屋顶");
        assertEquals(14, byId(ensured, "main_hall").dimensions().height());
        assertFalse(UserConstraintContract.isServerVerified(byId(ensured, "main_hall").params(), "height"));
        assertFalse(UserConstraintContract.isServerVerified(byId(ensured, "main_hall").params(), "roof_type"));
        assertEquals("xieshan", String.valueOf(byId(ensured, "main_hall").params().get("roof_type")));
        assertTrue(ensured.hasCapabilityGap());
    }

    @Test
    void newlineSeparatedDualOmitBindsBothHosts() {
        Map<String, Object> hallParams = new HashMap<>();
        hallParams.put("component_id", "main_hall");
        hallParams.put("roof_type", "xieshan");
        hallParams.put("design_sources", Map.of("roof_type", "AI_PLANNED"));
        Map<String, Object> pavilionParams = new HashMap<>();
        pavilionParams.put("component_id", "garden_pavilion");
        pavilionParams.put("roof_type", "flat");
        pavilionParams.put("design_sources", Map.of("roof_type", "AI_PLANNED"));
        LlmPlan raw = strictPlan(List.of(
                mass("main_hall", hallParams),
                mass("garden_pavilion", new Vec3i(20, 0, 0), new Dimensions(8, 8, 5), pavilionParams)),
                Map.of("roof", "REQUIRED", "facade", "OMITTED", "entrance", "OMITTED", "crown", "OMITTED"));
        LlmPlan ensured = RequestIntentInjector.ensure(raw, "主殿不要屋顶\n亭子也不要屋顶");
        assertFalse(ensured.hasCapabilityGap(), () -> String.valueOf(ensured.capabilityGap()));
        assertTrue(UserConstraintContract.isServerVerified(byId(ensured, "main_hall").params(), "roof_type"));
        assertTrue(UserConstraintContract.isServerVerified(byId(ensured, "garden_pavilion").params(), "roof_type"));
    }

    @Test
    void workshopAndBoilerMultiNamedOmitBindsExactIdentities() {
        Map<String, Object> workshopParams = new HashMap<>();
        workshopParams.put("component_id", "main_workshop");
        workshopParams.put("building_role", "workshop");
        workshopParams.put("roof_type", "gable");
        workshopParams.put("design_sources", Map.of("roof_type", "AI_PLANNED"));
        Map<String, Object> boilerParams = new HashMap<>();
        boilerParams.put("component_id", "boiler_house");
        boilerParams.put("building_role", "boiler");
        boilerParams.put("roof_type", "flat");
        boilerParams.put("design_sources", Map.of("roof_type", "AI_PLANNED"));
        Map<String, Object> hallParams = new HashMap<>();
        hallParams.put("component_id", "office_hall");
        hallParams.put("building_role", "hall");
        hallParams.put("roof_type", "xieshan");
        hallParams.put("design_sources", Map.of("roof_type", "AI_PLANNED"));
        LlmPlan raw = strictPlan(List.of(
                mass("workshop", new Vec3i(0, 0, 0), new Dimensions(16, 12, 8), workshopParams),
                mass("boiler", new Vec3i(20, 0, 0), new Dimensions(10, 8, 6), boilerParams),
                mass("hall", new Vec3i(40, 0, 0), new Dimensions(12, 10, 10), hallParams)),
                Map.of("roof", "REQUIRED", "facade", "OMITTED", "entrance", "OMITTED", "crown", "OMITTED"));
        LlmPlan ensured = RequestIntentInjector.ensure(raw, "主车间和锅炉房都不要屋顶");
        assertFalse(ensured.hasCapabilityGap(), () -> String.valueOf(ensured.capabilityGap()));
        assertTrue(UserConstraintContract.isServerVerified(byId(ensured, "main_workshop").params(), "roof_type"));
        assertTrue(UserConstraintContract.isServerVerified(byId(ensured, "boiler_house").params(), "roof_type"));
        assertFalse(UserConstraintContract.isServerVerified(byId(ensured, "office_hall").params(), "roof_type"));
        assertEquals("xieshan", String.valueOf(byId(ensured, "office_hall").params().get("roof_type")));
    }

    @Test
    void officeOmitDoesNotBindUnrelatedHall() {
        Map<String, Object> officeParams = new HashMap<>();
        officeParams.put("component_id", "office_tower");
        officeParams.put("building_role", "office");
        officeParams.put("roof_type", "flat");
        officeParams.put("design_sources", Map.of("roof_type", "AI_PLANNED"));
        Map<String, Object> hallParams = new HashMap<>();
        hallParams.put("component_id", "grand_hall");
        hallParams.put("building_role", "hall");
        hallParams.put("roof_type", "xieshan");
        hallParams.put("design_sources", Map.of("roof_type", "AI_PLANNED"));
        LlmPlan raw = strictPlan(List.of(
                mass("hall", hallParams),
                mass("office", new Vec3i(24, 0, 0), new Dimensions(10, 10, 12), officeParams)),
                Map.of("roof", "REQUIRED", "facade", "OMITTED", "entrance", "OMITTED", "crown", "OMITTED"));
        LlmPlan ensured = RequestIntentInjector.ensure(raw, "办公楼不要屋顶");
        assertFalse(ensured.hasCapabilityGap(), () -> String.valueOf(ensured.capabilityGap()));
        assertTrue(UserConstraintContract.isServerVerified(byId(ensured, "office_tower").params(), "roof_type"));
        assertFalse(UserConstraintContract.isServerVerified(byId(ensured, "grand_hall").params(), "roof_type"));
    }

    @Test
    void plateSecondaryDoesNotCreateMultiHostForUnscopedHeight() {
        Map<String, Object> houseParams = new HashMap<>();
        houseParams.put("component_id", "house_main");
        houseParams.put("height", 8);
        houseParams.put("design_sources", Map.of("height", "AI_PLANNED"));
        Map<String, Object> plateParams = new HashMap<>();
        plateParams.put("component_id", "deck_plate");
        plateParams.put("extrude_mode", "plate");
        plateParams.put("height", 1);
        plateParams.put("design_sources", Map.of("height", "AI_PLANNED"));
        Component plate = new Component(
                "MASS_SECONDARY", "deck", new Vec3i(0, 4, 0), new Dimensions(10, 6, 1),
                List.of(), plateParams);
        LlmPlan raw = strictPlan(List.of(
                mass("house", new Vec3i(0, 0, 0), new Dimensions(12, 10, 8), houseParams),
                plate),
                Map.of("roof", "OMITTED", "facade", "OMITTED", "entrance", "OMITTED", "crown", "OMITTED"));
        LlmPlan ensured = RequestIntentInjector.ensure(raw, "建造一座高度12格的房子");
        assertFalse(ensured.hasCapabilityGap(), () -> String.valueOf(ensured.capabilityGap()));
        assertEquals(12, byId(ensured, "house_main").dimensions().height());
        assertEquals(1, byId(ensured, "deck_plate").dimensions().height());
        assertFalse(UserConstraintContract.isServerVerified(byId(ensured, "deck_plate").params(), "height"));
    }

    @Test
    void bareStructureColumnDoesNotStealGlobalRoofOmit() {
        Map<String, Object> hallParams = new HashMap<>();
        hallParams.put("component_id", "main_hall");
        hallParams.put("roof_type", "xieshan");
        hallParams.put("design_sources", Map.of("roof_type", "AI_PLANNED"));
        Map<String, Object> columnParams = new HashMap<>();
        columnParams.put("component_id", "porch_column");
        columnParams.put("roof_type", "flat");
        columnParams.put("design_sources", Map.of("roof_type", "AI_PLANNED"));
        Component column = new Component(
                "STRUCTURE", "column", new Vec3i(2, 0, 2), new Dimensions(1, 1, 4),
                List.of(), columnParams);
        LlmPlan raw = strictPlan(List.of(
                mass("main_hall", hallParams),
                column),
                Map.of("roof", "REQUIRED", "facade", "OMITTED", "entrance", "OMITTED", "crown", "OMITTED"));
        LlmPlan ensured = RequestIntentInjector.ensure(raw, "所有建筑都不要屋顶");
        assertFalse(ensured.hasCapabilityGap(), () -> String.valueOf(ensured.capabilityGap()));
        assertTrue(UserConstraintContract.isServerVerified(byId(ensured, "main_hall").params(), "roof_type"));
        assertFalse(UserConstraintContract.isServerVerified(byId(ensured, "porch_column").params(), "roof_type"));
        assertEquals("flat", String.valueOf(byId(ensured, "porch_column").params().get("roof_type")));
    }

    @Test
    void allBuildingsOmitIgnoresBridgePlate() {
        Map<String, Object> hallParams = new HashMap<>();
        hallParams.put("component_id", "main_hall");
        hallParams.put("roof_type", "xieshan");
        hallParams.put("design_sources", Map.of("roof_type", "AI_PLANNED"));
        Map<String, Object> pavilionParams = new HashMap<>();
        pavilionParams.put("component_id", "garden_pavilion");
        pavilionParams.put("roof_type", "flat");
        pavilionParams.put("design_sources", Map.of("roof_type", "AI_PLANNED"));
        Map<String, Object> bridgeParams = new HashMap<>();
        bridgeParams.put("component_id", "bridge_deck");
        bridgeParams.put("extrude_mode", "plate");
        bridgeParams.put("plate_role", "bridge");
        bridgeParams.put("host_id", "main_hall");
        bridgeParams.put("target_host_id", "garden_pavilion");
        bridgeParams.put("roof_type", "flat");
        bridgeParams.put("design_sources", Map.of("roof_type", "AI_PLANNED"));
        Component bridge = new Component(
                "MASS_SECONDARY", "bridge", new Vec3i(12, 3, 4), new Dimensions(8, 3, 1),
                List.of(), bridgeParams);
        LlmPlan raw = strictPlan(List.of(
                mass("main_hall", hallParams),
                mass("garden_pavilion", new Vec3i(20, 0, 0), new Dimensions(8, 8, 5), pavilionParams),
                bridge),
                Map.of("roof", "REQUIRED", "facade", "OMITTED", "entrance", "OMITTED", "crown", "OMITTED"));
        LlmPlan ensured = RequestIntentInjector.ensure(raw, "所有建筑都不要屋顶");
        assertFalse(ensured.hasCapabilityGap(), () -> String.valueOf(ensured.capabilityGap()));
        assertTrue(UserConstraintContract.isServerVerified(byId(ensured, "main_hall").params(), "roof_type"));
        assertTrue(UserConstraintContract.isServerVerified(byId(ensured, "garden_pavilion").params(), "roof_type"));
        assertFalse(UserConstraintContract.isServerVerified(byId(ensured, "bridge_deck").params(), "roof_type"));
    }

    @Test
    void newlineJointQuantifierDoesNotMergeAcrossLines() {
        Map<String, Object> hallParams = new HashMap<>();
        hallParams.put("component_id", "main_hall");
        hallParams.put("roof_type", "xieshan");
        hallParams.put("design_sources", Map.of("roof_type", "AI_PLANNED"));
        Map<String, Object> pavilionParams = new HashMap<>();
        pavilionParams.put("component_id", "garden_pavilion");
        pavilionParams.put("roof_type", "flat");
        pavilionParams.put("design_sources", Map.of("roof_type", "AI_PLANNED"));
        LlmPlan raw = strictPlan(List.of(
                mass("main_hall", hallParams),
                mass("garden_pavilion", new Vec3i(20, 0, 0), new Dimensions(8, 8, 5), pavilionParams)),
                Map.of("roof", "REQUIRED", "facade", "OMITTED", "entrance", "OMITTED", "crown", "OMITTED"));
        LlmPlan ensured = RequestIntentInjector.ensure(raw, "主殿不要屋顶\n亭子都不要屋顶");
        assertFalse(ensured.hasCapabilityGap(), () -> String.valueOf(ensured.capabilityGap()));
        assertTrue(UserConstraintContract.isServerVerified(byId(ensured, "main_hall").params(), "roof_type"));
        assertTrue(UserConstraintContract.isServerVerified(byId(ensured, "garden_pavilion").params(), "roof_type"));
    }

    @Test
    void officeBesideBoilerHeightBindsOnlyBoiler() {
        Map<String, Object> officeParams = new HashMap<>();
        officeParams.put("component_id", "office_tower");
        officeParams.put("building_role", "office");
        officeParams.put("height", 12);
        officeParams.put("design_sources", Map.of("height", "AI_PLANNED"));
        Map<String, Object> boilerParams = new HashMap<>();
        boilerParams.put("component_id", "boiler_house");
        boilerParams.put("building_role", "boiler");
        boilerParams.put("height", 8);
        boilerParams.put("design_sources", Map.of("height", "AI_PLANNED"));
        LlmPlan raw = strictPlan(List.of(
                mass("office", new Vec3i(0, 0, 0), new Dimensions(10, 10, 12), officeParams),
                mass("boiler", new Vec3i(16, 0, 0), new Dimensions(8, 8, 8), boilerParams)),
                Map.of("roof", "OMITTED", "facade", "OMITTED", "entrance", "OMITTED", "crown", "OMITTED"));
        LlmPlan ensured = RequestIntentInjector.ensure(raw, "办公楼旁边的锅炉房高度6格");
        assertFalse(ensured.hasCapabilityGap(), () -> String.valueOf(ensured.capabilityGap()));
        assertEquals(6, byId(ensured, "boiler_house").dimensions().height());
        assertEquals(12, byId(ensured, "office_tower").dimensions().height());
        assertTrue(UserConstraintContract.isServerVerified(byId(ensured, "boiler_house").params(), "height"));
        assertFalse(UserConstraintContract.isServerVerified(byId(ensured, "office_tower").params(), "height"));
    }

    @Test
    void officeBesideBoilerOmitBindsOnlyBoiler() {
        Map<String, Object> officeParams = new HashMap<>();
        officeParams.put("component_id", "office_tower");
        officeParams.put("building_role", "office");
        officeParams.put("roof_type", "flat");
        officeParams.put("design_sources", Map.of("roof_type", "AI_PLANNED"));
        Map<String, Object> boilerParams = new HashMap<>();
        boilerParams.put("component_id", "boiler_house");
        boilerParams.put("building_role", "boiler");
        boilerParams.put("roof_type", "gable");
        boilerParams.put("design_sources", Map.of("roof_type", "AI_PLANNED"));
        LlmPlan raw = strictPlan(List.of(
                mass("office", new Vec3i(0, 0, 0), new Dimensions(10, 10, 12), officeParams),
                mass("boiler", new Vec3i(16, 0, 0), new Dimensions(8, 8, 8), boilerParams)),
                Map.of("roof", "REQUIRED", "facade", "OMITTED", "entrance", "OMITTED", "crown", "OMITTED"));
        LlmPlan ensured = RequestIntentInjector.ensure(raw, "办公楼旁边的锅炉房不要屋顶");
        assertFalse(ensured.hasCapabilityGap(), () -> String.valueOf(ensured.capabilityGap()));
        assertTrue(UserConstraintContract.isServerVerified(byId(ensured, "boiler_house").params(), "roof_type"));
        assertFalse(UserConstraintContract.isServerVerified(byId(ensured, "office_tower").params(), "roof_type"));
        assertEquals("flat", String.valueOf(byId(ensured, "office_tower").params().get("roof_type")));
    }

    @Test
    void missingPrimaryBuildingIdDoesNotPhantomSealHeight() {
        Map<String, Object> houseParams = new HashMap<>();
        houseParams.put("component_id", "house_main");
        houseParams.put("height", 8);
        houseParams.put("design_sources", Map.of("height", "AI_PLANNED"));
        LlmPlan raw = LlmPlanTestFixtures.builder()
                .allowCompilerSatellites(false)
                .styleProfile("DEFAULT")
                .components(List.of(mass("house", new Vec3i(0, 0, 0), new Dimensions(12, 10, 8), houseParams)))
                .planMeta(Map.of(
                        "allow_compiler_satellites", false,
                        "primary_building_id", "old_house",
                        "design_requirements", Map.of(
                                "roof", "OMITTED", "facade", "OMITTED",
                                "entrance", "OMITTED", "crown", "OMITTED")))
                .build();
        LlmPlan ensured = RequestIntentInjector.ensure(raw, "建造一座高度12格的房子");
        assertEquals(12, byId(ensured, "house_main").dimensions().height());
        assertTrue(UserConstraintContract.isServerVerified(byId(ensured, "house_main").params(), "height"));
        assertTrue(ensured.planMeta() != null && ensured.planMeta().get("scope_diagnostics") instanceof List<?> diags
                && diags.stream().anyMatch(d -> d instanceof Map<?, ?> m
                && "E_PRIMARY_HOST_MISSING".equals(String.valueOf(m.get("code")))));
    }

    @Test
    void hostSetStableWhenStructureAndOrderChange() {
        Map<String, Object> hallParams = new HashMap<>();
        hallParams.put("component_id", "main_hall");
        hallParams.put("roof_type", "xieshan");
        hallParams.put("design_sources", Map.of("roof_type", "AI_PLANNED"));
        Map<String, Object> pavilionParams = new HashMap<>();
        pavilionParams.put("component_id", "garden_pavilion");
        pavilionParams.put("roof_type", "flat");
        pavilionParams.put("design_sources", Map.of("roof_type", "AI_PLANNED"));
        Map<String, Object> columnParams = new HashMap<>();
        columnParams.put("component_id", "porch_column");
        columnParams.put("roof_type", "flat");
        Component column = new Component(
                "STRUCTURE", "column", new Vec3i(2, 0, 2), new Dimensions(1, 1, 4),
                List.of(), columnParams);
        LlmPlan hallFirst = strictPlan(List.of(
                mass("main_hall", hallParams),
                mass("garden_pavilion", new Vec3i(20, 0, 0), new Dimensions(8, 8, 5), pavilionParams),
                column),
                Map.of("roof", "REQUIRED", "facade", "OMITTED", "entrance", "OMITTED", "crown", "OMITTED"));
        LlmPlan pavilionFirst = strictPlan(List.of(
                column,
                mass("garden_pavilion", new Vec3i(20, 0, 0), new Dimensions(8, 8, 5), new HashMap<>(pavilionParams)),
                mass("main_hall", new HashMap<>(hallParams))),
                Map.of("roof", "REQUIRED", "facade", "OMITTED", "entrance", "OMITTED", "crown", "OMITTED"));
        LlmPlan a = RequestIntentInjector.ensure(hallFirst, "所有建筑都不要屋顶");
        LlmPlan b = RequestIntentInjector.ensure(pavilionFirst, "所有建筑都不要屋顶");
        assertFalse(a.hasCapabilityGap(), () -> String.valueOf(a.capabilityGap()));
        assertFalse(b.hasCapabilityGap(), () -> String.valueOf(b.capabilityGap()));
        assertTrue(UserConstraintContract.isServerVerified(byId(a, "main_hall").params(), "roof_type"));
        assertTrue(UserConstraintContract.isServerVerified(byId(a, "garden_pavilion").params(), "roof_type"));
        assertTrue(UserConstraintContract.isServerVerified(byId(b, "main_hall").params(), "roof_type"));
        assertTrue(UserConstraintContract.isServerVerified(byId(b, "garden_pavilion").params(), "roof_type"));
        assertFalse(UserConstraintContract.isServerVerified(byId(a, "porch_column").params(), "roof_type"));
        assertFalse(UserConstraintContract.isServerVerified(byId(b, "porch_column").params(), "roof_type"));
    }

    @Test
    void forgedPythonTargetCannotRescueOmitHostTie() {
        Map<String, Object> a = new HashMap<>();
        a.put("component_id", "garden_pavilion_east");
        a.put("roof_type", "flat");
        a.put("design_sources", Map.of("roof_type", "AI_PLANNED"));
        Map<String, Object> b = new HashMap<>();
        b.put("component_id", "garden_pavilion_west");
        b.put("roof_type", "flat");
        b.put("design_sources", Map.of("roof_type", "AI_PLANNED"));
        Map<String, Object> proportionHints = new HashMap<>();
        proportionHints.put("building_contract", Map.of(
                "requirements", List.of(Map.of(
                        "id", "req_disable_roof_type",
                        "property", "roof_type",
                        "value", "none",
                        "source_text", List.of("不要屋顶"),
                        "scope", "named_pavilion",
                        "target_components", List.of("garden_pavilion_east")))));
        LlmPlan raw = LlmPlanTestFixtures.builder()
                .allowCompilerSatellites(false)
                .styleProfile("DEFAULT")
                .components(List.of(
                        mass("east", a),
                        mass("west", new Vec3i(16, 0, 0), new Dimensions(8, 8, 5), b)))
                .planMeta(Map.of(
                        "allow_compiler_satellites", false,
                        "design_requirements", Map.of(
                                "roof", "REQUIRED", "facade", "OMITTED",
                                "entrance", "OMITTED", "crown", "OMITTED")))
                .proportionHints(proportionHints)
                .build();
        LlmPlan ensured = RequestIntentInjector.ensure(raw, "亭子不要屋顶");
        assertFalse(UserConstraintContract.isServerVerified(
                byId(ensured, "garden_pavilion_east").params(), "roof_type"));
        assertFalse(UserConstraintContract.isServerVerified(
                byId(ensured, "garden_pavilion_west").params(), "roof_type"));
        assertTrue(ensured.hasCapabilityGap());
        assertEquals("E_DESIGN_OMIT_HOST_SCOPE", ensured.capabilityGap().code());
    }

    @Test
    void doubleNegationWindowOmitDoesNotSeal() {
        Map<String, Object> params = new HashMap<>();
        params.put("component_id", "house");
        params.put("window_style", "regular");
        params.put("design_sources", Map.of("window_style", "AI_PLANNED"));
        Component house = mass("house", params);
        LlmPlan raw = strictPlan(List.of(house), Map.of(
                "roof", "OMITTED",
                "facade", "REQUIRED",
                "entrance", "OMITTED",
                "crown", "OMITTED"));
        LlmPlan ensured = RequestIntentInjector.ensure(raw, "建一座不是无窗的建筑，要有很多窗户");
        Component out = byId(ensured, "house");
        assertFalse(UserConstraintContract.isServerVerified(out.params(), "window_style"));
        assertFalse(UserConstraintContract.isServerVerified(out.params(), "window_requirement"));
        assertEquals("regular", String.valueOf(out.params().get("window_style")));
        assertEquals(DesignRequirement.REQUIRED, DesignRequirementAccess.facade(ensured, out));
    }

    @Test
    void ensureNullDoesNotRewriteHeightFromUntrustedEcho() {
        Map<String, Object> params = new HashMap<>();
        params.put("component_id", "tower");
        params.put("height", 12);
        params.put("towerHeight", 12);
        params.put("design_sources", Map.of("height", "AI_PLANNED"));
        Component tower = mass("tower", new Vec3i(0, 0, 0), new Dimensions(8, 8, 12), params);
        Map<String, Object> meta = new HashMap<>();
        meta.put("allow_compiler_satellites", false);
        meta.put("design_requirements", Map.of(
                "roof", "OMITTED", "facade", "OMITTED", "entrance", "OMITTED", "crown", "OMITTED"));
        meta.put("request_intent", Map.of(
                "original_text", "建一座塔，高度40格",
                "height_explicit", true,
                "explicit_height_blocks", 40));
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .allowCompilerSatellites(false)
                .styleProfile("DEFAULT")
                .components(List.of(tower))
                .planMeta(meta)
                .build();
        LlmPlan after = RequestIntentInjector.ensure(plan, (String) null);
        Component out = byId(after, "tower");
        assertEquals(12, out.dimensions().height());
        assertEquals(12, ((Number) out.params().get("height")).intValue());
        assertFalse(UserConstraintContract.isServerVerified(out.params(), "height"));
    }

    @Test
    void enclosedGardenPavilionKeepsAuthoredOpeningsThroughOpenFrameRepair() {
        // Real pavilion id + enclosed AI design: must not force open-frame / strip openings.
        Component pavilion = mass("garden_pavilion", Map.of(
                "component_id", "garden_pavilion",
                "roof_type", "xieshan",
                "wall_style", "solid",
                "window_style", "regular",
                "window_ratio", 0.25,
                "entrance_type", "door",
                "design_sources", Map.of(
                        "roof_type", "AI_PLANNED",
                        "window_style", "AI_PLANNED",
                        "entrance_type", "AI_PLANNED",
                        "wall_style", "AI_PLANNED")));
        Component roof = roof("roof_pavilion", "garden_pavilion", "xieshan",
                new Vec3i(0, 6, 0), new Dimensions(14, 12, 3));
        Component facade = satellite("FACADE_WINDOWS", "facade_pavilion", "garden_pavilion",
                new Vec3i(0, 1, 0), new Dimensions(12, 1, 4));
        Component entrance = satellite("ENTRANCE", "door_pavilion", "garden_pavilion",
                new Vec3i(5, 0, 10), new Dimensions(2, 1, 3));
        LlmPlan plan = strictPlan(
                List.of(pavilion, roof, facade, entrance),
                Map.of(
                        "roof", "REQUIRED",
                        "facade", "REQUIRED",
                        "entrance", "REQUIRED",
                        "crown", "OMITTED"));

        LlmPlan afterOpen = PavilionOpenFrameRepair.apply(plan);
        Component body = afterOpen.components().stream()
                .filter(c -> c != null && c.params() != null
                        && "garden_pavilion".equals(String.valueOf(c.params().get("component_id"))))
                .findFirst().orElseThrow();
        assertFalse(Boolean.TRUE.equals(body.params().get("open")),
                "enclosed AI pavilion must not be forced open by id alone");
        assertEquals("regular", String.valueOf(body.params().get("window_style")));
        assertEquals("door", String.valueOf(body.params().get("entrance_type")));

        LlmPlan afterConflict = ExplicitDesignConflictRepair.apply(afterOpen);
        assertTrue(afterConflict.components().stream().anyMatch(c ->
                c != null && "FACADE_WINDOWS".equalsIgnoreCase(c.componentType())));
        assertTrue(afterConflict.components().stream().anyMatch(c ->
                c != null && "ENTRANCE".equalsIgnoreCase(c.componentType())));

        AssemblyCompileDiagnostics.clear();
        List<BlockPatch> patches = ComponentPlanCompiler.compile(afterConflict);
        assertFalse(AssemblyCompileDiagnostics.hasGap(),
                () -> String.valueOf(AssemblyCompileDiagnostics.get()));
        assertFalse(patches.isEmpty());
    }

    @Test
    void prepareComponentsFailsWhenAiOmitsRoofAgainstPlanRequired() {
        Component mass = mass("hall", Map.of(
                "component_id", "hall",
                "roof_type", "none",
                "design_sources", Map.of("roof_type", "AI_PLANNED")));
        LlmPlan plan = strictPlan(List.of(mass), Map.of(
                "roof", "REQUIRED",
                "facade", "OMITTED",
                "entrance", "OMITTED",
                "crown", "OMITTED"));

        AssemblyCompileDiagnostics.clear();
        var prepared = ComponentSatelliteInference.prepareComponents(plan, Map.of(), false);
        assertTrue(prepared.components().isEmpty());
        assertTrue(AssemblyCompileDiagnostics.hasGap());
        assertEquals("E_DESIGN_INCOMPLETE", AssemblyCompileDiagnostics.get().code());
        assertTrue(AssemblyCompileDiagnostics.get().message().contains("ROOF"));
    }

    @Test
    void multiMassScopedRoofsSurviveStrictPrepareAndCompile() {
        // Avoid "pavilion" in ids so PavilionOpenFrameRepair does not force open-frame
        // (window_style=none) and ExplicitDesignConflictRepair does not strip facades.
        Component house = mass("modern_house", Map.of(
                "component_id", "modern_house",
                "roof_type", "flat",
                "design_sources", Map.of("roof_type", "AI_PLANNED")));
        Component wing = mass("garden_wing", new Vec3i(20, 0, 0), new Dimensions(8, 8, 5), Map.of(
                "component_id", "garden_wing",
                "roof_type", "xieshan",
                "design_sources", Map.of("roof_type", "AI_PLANNED")));
        Component houseRoof = roof("roof_house", "modern_house", "flat", new Vec3i(0, 6, 0), new Dimensions(14, 12, 2));
        Component wingRoof = roof("roof_wing", "garden_wing", "xieshan",
                new Vec3i(20, 5, 0), new Dimensions(10, 10, 3));
        // Explicit facade/entrance satellites so strict mode does not demand inference.
        Component houseFacade = satellite("FACADE_WINDOWS", "facade_house", "modern_house",
                new Vec3i(0, 1, 0), new Dimensions(12, 1, 4));
        Component houseEntrance = satellite("ENTRANCE", "door_house", "modern_house",
                new Vec3i(5, 0, 10), new Dimensions(2, 1, 3));
        Component wingFacade = satellite("FACADE_WINDOWS", "facade_wing", "garden_wing",
                new Vec3i(20, 1, 0), new Dimensions(8, 1, 3));
        Component wingEntrance = satellite("ENTRANCE", "door_wing", "garden_wing",
                new Vec3i(23, 0, 8), new Dimensions(2, 1, 3));

        LlmPlan plan = strictPlan(
                List.of(house, wing, houseRoof, wingRoof,
                        houseFacade, houseEntrance, wingFacade, wingEntrance),
                Map.of(
                        "roof", "REQUIRED",
                        "facade", "REQUIRED",
                        "entrance", "REQUIRED",
                        "crown", "OMITTED"));

        AssemblyCompileDiagnostics.clear();
        var prepared = ComponentSatelliteInference.prepareComponents(plan, Map.of(), false);
        assertFalse(AssemblyCompileDiagnostics.hasGap(),
                () -> String.valueOf(AssemblyCompileDiagnostics.get()));
        // MASS roofs are suppressed to none when a hosted ROOF satellite exists; authorship lives on ROOF.
        assertEquals("flat", roofTypeOf(prepared.components(), "roof_house"),
                () -> dumpRoofParams(prepared.components()));
        assertEquals("xieshan", roofTypeOf(prepared.components(), "roof_wing"),
                () -> dumpRoofParams(prepared.components()));
        assertTrue(prepared.components().stream().anyMatch(c ->
                c != null && "ROOF".equalsIgnoreCase(c.componentType())
                        && "modern_house".equals(String.valueOf(c.params().get("host_id")))
                        && "flat".equalsIgnoreCase(String.valueOf(c.params().get("roof_type")))));
        assertTrue(prepared.components().stream().anyMatch(c ->
                c != null && "ROOF".equalsIgnoreCase(c.componentType())
                        && "garden_wing".equals(String.valueOf(c.params().get("host_id")))
                        && "xieshan".equalsIgnoreCase(String.valueOf(c.params().get("roof_type")))));

        AssemblyCompileDiagnostics.clear();
        List<BlockPatch> patches = ComponentPlanCompiler.compile(plan);
        assertFalse(AssemblyCompileDiagnostics.hasGap(),
                () -> String.valueOf(AssemblyCompileDiagnostics.get()));
        assertFalse(patches.isEmpty());
        // Design property survival: both authored roof types remain on prepared hosts;
        // BlockPatch emission must not empty-out a complete multi-mass plan.
        assertTrue(patches.stream().anyMatch(p -> p.dy() >= 5),
                "expected roof-height patches for wing/house roofs");
    }

    private static Component byId(LlmPlan plan, String componentId) {
        return plan.components().stream()
                .filter(c -> c != null && c.params() != null
                        && componentId.equals(String.valueOf(c.params().get("component_id"))))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing component_id=" + componentId));
    }

    private static String roofTypeOf(List<Component> components, String id) {
        return components.stream()
                .filter(c -> c != null && c.params() != null && id.equals(String.valueOf(c.params().get("component_id"))))
                .map(c -> String.valueOf(c.params().get("roof_type")))
                .findFirst()
                .orElse("<missing>");
    }

    private static String dumpRoofParams(List<Component> components) {
        StringBuilder sb = new StringBuilder();
        for (Component c : components) {
            if (c == null || c.params() == null) {
                continue;
            }
            String type = c.componentType();
            if (type == null) {
                continue;
            }
            String upper = type.toUpperCase();
            if (!upper.startsWith("ROOF") && !upper.startsWith("MASS")) {
                continue;
            }
            sb.append(type).append('{')
                    .append("id=").append(c.params().get("component_id"))
                    .append(", host=").append(c.params().get("host_id"))
                    .append(", roof_type=").append(c.params().get("roof_type"))
                    .append("}; ");
        }
        return sb.toString();
    }

    private static LlmPlan strictPlan(List<Component> components, Map<String, String> designRequirements) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("allow_compiler_satellites", false);
        meta.put("design_requirements", designRequirements);
        return LlmPlanTestFixtures.builder()
                .allowCompilerSatellites(false)
                .styleProfile("DEFAULT")
                .components(components)
                .planMeta(meta)
                .build();
    }

    private static Component mass(String slot, Map<String, Object> params) {
        return mass(slot, new Vec3i(0, 0, 0), new Dimensions(12, 10, 6), params);
    }

    private static Component mass(String slot, Vec3i pos, Dimensions dims, Map<String, Object> params) {
        return new Component("MASS_MAIN", slot, pos, dims, List.of(), params);
    }

    private static Component roof(String id, String hostId, String roofType, Vec3i pos, Dimensions dims) {
        return new Component(
                "ROOF",
                hostId,
                pos,
                dims,
                List.of(),
                Map.of(
                        "component_id", id,
                        "host_id", hostId,
                        "roof_type", roofType,
                        "design_sources", Map.of("roof_type", "AI_PLANNED")));
    }

    private static Component satellite(String type, String id, String hostId, Vec3i pos, Dimensions dims) {
        Map<String, Object> params = new HashMap<>();
        params.put("component_id", id);
        params.put("host_id", hostId);
        if ("ENTRANCE".equals(type)) {
            params.put("entrance_type", "door");
            params.put("door_width", 1);
            params.put("door_height", 3);
        }
        if ("FACADE_WINDOWS".equals(type)) {
            params.put("window_style", "regular");
            params.put("window_ratio", 0.2);
        }
        return new Component(type, hostId, pos, dims, List.of(), params);
    }
}
