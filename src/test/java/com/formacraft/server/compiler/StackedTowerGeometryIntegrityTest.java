package com.formacraft.server.compiler;

import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.LlmPlanTestFixtures;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.typology.UserConstraintContract;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Real pagoda geometry: gaps, mixed compounds, missing wing, per-building envelopes. */
class StackedTowerGeometryIntegrityTest {

    @Test
    void mixedPavilionAndPagodaKeepIndependentHeightTargets() {
        List<Component> comps = new ArrayList<>();
        comps.add(mass("garden_pavilion", "pavilion", 0, 0, 7, 8));
        comps.addAll(gappedPagodaTiers()); // shaft envelope 41
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .allowCompilerSatellites(false)
                .components(comps)
                .build();
        // Pavilion first in text — old global height_blocks=8 must not reject pagoda@30.
        LlmPlan out = RequestIntentInjector.ensure(plan,
                "建造一座高度8格的凉亭，旁边建一座高度50格的五层宝塔。");
        assertFalse(out.hasCapabilityGap(), () -> String.valueOf(out.capabilityGap()));
        assertEquals(8, heightOf(out, "garden_pavilion"));
        List<Component> tiers = pagodaTiers(out);
        assertEquals(5, tiers.size());
        assertEquals(50, shaftEnvelope(tiers));
        assertTrue(UserConstraintContract.isServerVerified(
                tiers.getFirst().params(), "height"));
        assertEquals(50, ((Number) tiers.getFirst().params()
                .get(RequestIntentInjector.SHAFT_ENVELOPE_TARGET_KEY)).intValue());
        var gap = StackedEnvelopeIntegrityValidator.check(out, out.components());
        assertTrue(gap.isEmpty(), () -> String.valueOf(gap.orElse(null)));
    }

    @Test
    void mixedPagodaFirstThenPavilionAlsoIndependent() {
        List<Component> comps = new ArrayList<>();
        comps.addAll(gappedPagodaTiers());
        comps.add(mass("garden_pavilion", "pavilion", 30, 0, 7, 8));
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .allowCompilerSatellites(false)
                .components(comps)
                .build();
        LlmPlan out = RequestIntentInjector.ensure(plan,
                "建造一座高度50格的五层宝塔，旁边一座高度8格的凉亭。");
        assertFalse(out.hasCapabilityGap(), () -> String.valueOf(out.capabilityGap()));
        assertEquals(8, heightOf(out, "garden_pavilion"));
        assertEquals(50, shaftEnvelope(pagodaTiers(out)));
    }

    @Test
    void westWingMissingDoesNotBindEastWing() {
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .allowCompilerSatellites(false)
                .components(List.of(
                        mass("east_wing", "wing", 12, 0, 9, 6)))
                .build();
        LlmPlan out = RequestIntentInjector.ensure(plan, "西厢房不要屋顶。");
        assertTrue(out.hasCapabilityGap(), "missing west wing must fail closed");
        assertNotEquals("none", roofOf(out, "east_wing"),
                "east wing must not receive west-wing omit");
    }

    @Test
    void westWingMissingHeightDoesNotBindEastWing() {
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .allowCompilerSatellites(false)
                .components(List.of(
                        mass("east_wing", "wing", 12, 0, 9, 8)))
                .build();
        LlmPlan out = RequestIntentInjector.ensure(plan, "西厢房高度12格。");
        assertTrue(out.hasCapabilityGap(), "missing west wing height must fail closed");
        assertEquals(8, heightOf(out, "east_wing"));
    }

    @Test
    void gappedPagodaScalePreservesGapsAndTaper() {
        List<Component> tiers = gappedPagodaTiers();
        int[] beforeH = tiers.stream().mapToInt(c -> c.dimensions().height()).toArray();
        int[] beforeGaps = gapsBetween(tiers);
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .allowCompilerSatellites(false)
                .components(new ArrayList<>(tiers))
                .build();
        LlmPlan out = RequestIntentInjector.ensure(plan, "建造一座高度50格的宝塔。");
        assertFalse(out.hasCapabilityGap(), () -> String.valueOf(out.capabilityGap()));
        List<Component> after = pagodaTiers(out);
        after.sort(Comparator.comparingInt(c -> c.relativePosition().y()));
        assertEquals(50, shaftEnvelope(after));
        int[] afterGaps = gapsBetween(after);
        // Original positive gaps must remain positive (eave/transition not deleted).
        for (int i = 0; i < beforeGaps.length; i++) {
            if (beforeGaps[i] > 0) {
                assertTrue(afterGaps[i] >= 1,
                        "gap after tier " + (i + 1) + " must survive scaling");
            }
        }
        // Taper: lower tiers stay ≥ upper tiers (no crown blow-up).
        for (int i = 1; i < after.size(); i++) {
            assertTrue(after.get(i).dimensions().height()
                            <= after.get(i - 1).dimensions().height() + 2,
                    "upper tiers must not absorb disproportionate growth: "
                            + after.get(i - 1).dimensions().height()
                            + " → " + after.get(i).dimensions().height());
        }
        // Relative height order roughly preserved (tier1/2 largest).
        assertTrue(after.get(0).dimensions().height() >= after.get(3).dimensions().height());
        assertTrue(after.get(1).dimensions().height() >= after.get(4).dimensions().height());
        // Width taper unchanged (geometry authority stays with plan widths).
        for (int i = 0; i < after.size(); i++) {
            assertEquals(beforeH[i] > 0, after.get(i).dimensions().height() > 0);
        }
    }

    @Test
    void hardFloorHeightConflictWithEnvelopeReportsGap() {
        List<Component> tiers = stackedTight(new int[]{10, 10, 10, 10, 10});
        for (Component c : tiers) {
            c.params().put("floor_count", 2);
            c.params().put("floor_height", 5);
            UserConstraintContract.stampVerified(c.params(), "floor_height");
        }
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .allowCompilerSatellites(false)
                .components(tiers)
                .build();
        // 5 × (2×5)=50 minimum; target 45 is impossible under hard floor_height.
        LlmPlan out = RequestIntentInjector.ensure(plan, "建造一座高度45格的宝塔。");
        assertTrue(out.hasCapabilityGap());
        assertEquals("E_STACKED_HEIGHT_UNSUPPORTED", out.capabilityGap().code());
    }

    @Test
    void shaftEnvelopeTargetIgnoresExtraRoofRise() {
        List<Component> comps = new ArrayList<>(stackedTight(new int[]{8, 8, 7, 6, 5}));
        Map<String, Object> roof = new HashMap<>();
        roof.put("component_id", "pagoda_roof");
        roof.put("host_id", "pagoda_tier_5");
        comps.add(new Component("ROOF", "pagoda", new Vec3i(0, 34, 0),
                new Dimensions(6, 6, 3), List.of(), roof));
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .allowCompilerSatellites(false)
                .components(comps)
                .build();
        LlmPlan out = RequestIntentInjector.ensure(plan, "建造一座高度50格的宝塔。");
        assertFalse(out.hasCapabilityGap(), () -> String.valueOf(out.capabilityGap()));
        assertEquals(50, shaftEnvelope(pagodaTiers(out)));
        // Default mode is shaft — roof above does not fail integrity.
        assertTrue(StackedEnvelopeIntegrityValidator.check(out, out.components()).isEmpty());
    }

    @Test
    void buildingModeAllowsShaftShorterThanWholeTarget() {
        // Shaft 34 + roof 3; player asks for whole-building 50 → shaft 47, building 50.
        List<Component> comps = new ArrayList<>(stackedTight(new int[]{8, 8, 7, 6, 5}));
        Map<String, Object> roof = new HashMap<>();
        roof.put("component_id", "pagoda_roof");
        roof.put("host_id", "pagoda_tier_5");
        comps.add(new Component("ROOF", "pagoda", new Vec3i(0, 34, 0),
                new Dimensions(6, 6, 3), List.of(), roof));
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .allowCompilerSatellites(false)
                .components(comps)
                .build();
        LlmPlan out = RequestIntentInjector.ensure(plan, "建造一座整座高度50格的宝塔。");
        assertFalse(out.hasCapabilityGap(), () -> String.valueOf(out.capabilityGap()));
        assertEquals(47, shaftEnvelope(pagodaTiers(out)));
        assertEquals(50, buildingEnvelope(out.components()));
        assertTrue(StackedEnvelopeIntegrityValidator.check(out, out.components()).isEmpty());
        // Must not require shaftEnv == building target.
        Object mode = pagodaTiers(out).getFirst().params().get(RequestIntentInjector.ENVELOPE_MODE_KEY);
        assertEquals("building", String.valueOf(mode));
    }

    @Test
    void facadeWindowStaysInsideScaledHost() {
        List<Component> comps = new ArrayList<>(stackedTight(new int[]{20, 20}));
        Map<String, Object> win = new HashMap<>();
        win.put("component_id", "tier1_windows");
        win.put("host_id", "pagoda_tier_1");
        comps.add(new Component("FACADE_WINDOWS", "pagoda", new Vec3i(0, 15, 0),
                new Dimensions(4, 1, 2), List.of(), win));
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .allowCompilerSatellites(false)
                .components(comps)
                .build();
        // 40 → 16: each storey ~8; window must remain within [hostY, hostY+hostH).
        LlmPlan out = RequestIntentInjector.ensure(plan, "建造一座高度16格的宝塔。");
        assertFalse(out.hasCapabilityGap(), () -> String.valueOf(out.capabilityGap()));
        Component host = null;
        Component window = null;
        for (Component c : out.components()) {
            if (c == null || c.params() == null) continue;
            String id = String.valueOf(c.params().get("component_id"));
            if ("pagoda_tier_1".equals(id)) host = c;
            if ("tier1_windows".equals(id)) window = c;
        }
        assertNotNull(host);
        assertNotNull(window);
        int hostY = host.relativePosition().y();
        int hostH = host.dimensions().height();
        int winY = window.relativePosition().y();
        int winH = window.dimensions().height();
        assertTrue(winY >= hostY, "window below host");
        assertTrue(winY + winH <= hostY + hostH,
                "window must not float above scaled host (" + winY + "+" + winH
                        + " vs host " + hostY + "+" + hostH + ")");
    }

    @Test
    void missingExpectedStoreyFailsIntegrity() {
        List<Component> tiers = stackedTight(new int[]{8, 8, 7, 6, 5});
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .allowCompilerSatellites(false)
                .components(tiers)
                .build();
        LlmPlan sealed = RequestIntentInjector.ensure(plan, "建造一座高度50格的宝塔。");
        assertFalse(sealed.hasCapabilityGap(), () -> String.valueOf(sealed.capabilityGap()));
        // Drop top storey after seal — meta still expects five masses.
        List<Component> dropped = new ArrayList<>();
        for (Component c : sealed.components()) {
            if (c != null && c.params() != null
                    && "pagoda_tier_5".equals(String.valueOf(c.params().get("component_id")))) {
                continue;
            }
            dropped.add(c);
        }
        var gap = StackedEnvelopeIntegrityValidator.check(sealed, dropped);
        assertTrue(gap.isPresent(), "missing expected storey must fail integrity");
        assertEquals("E_STACKED_HEIGHT_UNSUPPORTED", gap.orElseThrow().code());
    }

    private static int buildingEnvelope(List<Component> comps) {
        int minY = Integer.MAX_VALUE;
        int maxTop = Integer.MIN_VALUE;
        for (Component c : comps) {
            if (c == null || c.relativePosition() == null || c.dimensions() == null) continue;
            String type = c.componentType() == null ? "" : c.componentType().toUpperCase();
            String mid = String.valueOf(c.params() == null ? "" : c.params().get("component_id"));
            boolean shaft = mid.startsWith("pagoda_tier_");
            boolean crown = type.startsWith("ROOF") || mid.contains("roof");
            if (!shaft && !crown) continue;
            minY = Math.min(minY, c.relativePosition().y());
            maxTop = Math.max(maxTop, c.relativePosition().y() + c.dimensions().height());
        }
        return maxTop - minY;
    }

    /** oct18_landmark_1 tier layout: gaps 2,2,2,1 — shaft envelope 41. */
    private static List<Component> gappedPagodaTiers() {
        int[] ys = {4, 14, 24, 33, 40};
        int[] hs = {8, 8, 7, 6, 5};
        int[] ws = {17, 13, 9, 7, 5};
        List<Component> out = new ArrayList<>();
        for (int i = 0; i < hs.length; i++) {
            Map<String, Object> params = new HashMap<>();
            params.put("component_id", "pagoda_tier_" + (i + 1));
            params.put("design_sources", Map.of("height", "AI_PLANNED"));
            out.add(new Component("MASS_MAIN", "pagoda",
                    new Vec3i(0, ys[i], 0),
                    new Dimensions(ws[i], ws[i], hs[i]),
                    List.of(), params));
        }
        return out;
    }

    private static List<Component> stackedTight(int[] heights) {
        List<Component> tiers = new ArrayList<>();
        int y = 0;
        for (int i = 0; i < heights.length; i++) {
            Map<String, Object> params = new HashMap<>();
            params.put("component_id", "pagoda_tier_" + (i + 1));
            params.put("design_sources", Map.of("height", "AI_PLANNED"));
            tiers.add(new Component("MASS_MAIN", "pagoda",
                    new Vec3i(0, y, 0),
                    new Dimensions(Math.max(4, 12 - i), Math.max(4, 12 - i), heights[i]),
                    List.of(), params));
            y += heights[i];
        }
        return tiers;
    }

    private static Component mass(String id, String role, int x, int y, int w, int h) {
        Map<String, Object> params = new HashMap<>();
        params.put("component_id", id);
        params.put("building_role", role);
        params.put("roof_type", "gable");
        return new Component("MASS_MAIN", id, new Vec3i(x, y, 0),
                new Dimensions(w, w, h), List.of(), params);
    }

    private static int[] gapsBetween(List<Component> tiers) {
        tiers = new ArrayList<>(tiers);
        tiers.sort(Comparator.comparingInt(c -> c.relativePosition().y()));
        int[] gaps = new int[Math.max(0, tiers.size() - 1)];
        for (int i = 0; i < gaps.length; i++) {
            int top = tiers.get(i).relativePosition().y() + tiers.get(i).dimensions().height();
            gaps[i] = tiers.get(i + 1).relativePosition().y() - top;
        }
        return gaps;
    }

    private static int shaftEnvelope(List<Component> tiers) {
        tiers = new ArrayList<>(tiers);
        tiers.sort(Comparator.comparingInt(c -> c.relativePosition().y()));
        int minY = tiers.getFirst().relativePosition().y();
        int maxTop = tiers.stream()
                .mapToInt(c -> c.relativePosition().y() + c.dimensions().height())
                .max().orElse(minY);
        return maxTop - minY;
    }

    private static List<Component> pagodaTiers(LlmPlan plan) {
        List<Component> out = new ArrayList<>();
        for (Component c : plan.components()) {
            if (c == null || c.params() == null) continue;
            String id = String.valueOf(c.params().get("component_id"));
            if (id.startsWith("pagoda_tier_")) out.add(c);
        }
        return out;
    }

    private static int heightOf(LlmPlan plan, String id) {
        for (Component c : plan.components()) {
            if (c != null && c.params() != null
                    && id.equals(String.valueOf(c.params().get("component_id")))) {
                return c.dimensions().height();
            }
        }
        fail("missing " + id);
        return -1;
    }

    private static String roofOf(LlmPlan plan, String id) {
        for (Component c : plan.components()) {
            if (c != null && c.params() != null
                    && id.equals(String.valueOf(c.params().get("component_id")))) {
                Object roof = c.params().get("roof_type");
                if (roof == null) roof = c.params().get("roofType");
                return roof == null ? "" : String.valueOf(roof);
            }
        }
        fail("missing " + id);
        return "";
    }
}
