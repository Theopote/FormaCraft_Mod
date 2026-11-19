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
        // Enclosed roof H=3 seats at hostTop-1 → net rise 2.
        // Whole-building 50 → shaft 48, building 50 (not shaft 47 from pre-align flush Y).
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
        assertEquals(48, shaftEnvelope(pagodaTiers(out)));
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
    void conflictingPagodaHeightsReportGap() {
        List<Component> tiers = stackedTight(new int[]{8, 8, 7, 6, 5});
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .allowCompilerSatellites(false)
                .components(tiers)
                .build();
        LlmPlan out = RequestIntentInjector.ensure(plan, "宝塔高度30格，宝塔高度40格。");
        assertTrue(out.hasCapabilityGap(), "conflicting tower heights must fail");
        assertEquals("E_HEIGHT_HOST_CONFLICT", out.capabilityGap().code());
    }

    @Test
    void nearTopWindowDoesNotStickAboveScaledHost() {
        List<Component> comps = new ArrayList<>(stackedTight(new int[]{20, 20}));
        Map<String, Object> win = new HashMap<>();
        win.put("component_id", "near_top_windows");
        win.put("host_id", "pagoda_tier_1");
        // Host top at 20; window starts at 19 with height 2 → would overflow if top-stuck.
        comps.add(new Component("FACADE_WINDOWS", "pagoda", new Vec3i(0, 19, 0),
                new Dimensions(4, 1, 2), List.of(), win));
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .allowCompilerSatellites(false)
                .components(comps)
                .build();
        LlmPlan out = RequestIntentInjector.ensure(plan, "建造一座高度16格的宝塔。");
        assertFalse(out.hasCapabilityGap(), () -> String.valueOf(out.capabilityGap()));
        Component host = null;
        Component window = null;
        for (Component c : out.components()) {
            if (c == null || c.params() == null) continue;
            String id = String.valueOf(c.params().get("component_id"));
            if ("pagoda_tier_1".equals(id)) host = c;
            if ("near_top_windows".equals(id)) window = c;
        }
        assertNotNull(host);
        assertNotNull(window);
        int hostY = host.relativePosition().y();
        int hostH = host.dimensions().height();
        int winY = window.relativePosition().y();
        int winH = window.dimensions().height();
        assertTrue(winY + winH <= hostY + hostH,
                "near-top window must stay inside host after shrink ("
                        + winY + "+" + winH + " vs " + hostY + "+" + hostH + ")");
    }

    @Test
    void pavilionWholeBuildingDoesNotFlipPagodaToBuildingMode() {
        List<Component> comps = new ArrayList<>();
        comps.add(mass("garden_pavilion", "pavilion", 0, 0, 7, 8));
        comps.addAll(stackedTight(new int[]{8, 8, 7, 6, 5}));
        Map<String, Object> roof = new HashMap<>();
        roof.put("component_id", "pagoda_roof");
        roof.put("host_id", "pagoda_tier_5");
        comps.add(new Component("ROOF", "pagoda", new Vec3i(0, 34, 0),
                new Dimensions(6, 6, 3), List.of(), roof));
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .allowCompilerSatellites(false)
                .components(comps)
                .build();
        // 「整座」 scopes the pavilion only; pagoda「主体」must stay shaft@50.
        LlmPlan out = RequestIntentInjector.ensure(plan,
                "整座凉亭高度8格，旁边宝塔主体高度50格。");
        assertFalse(out.hasCapabilityGap(), () -> String.valueOf(out.capabilityGap()));
        assertEquals(8, heightOf(out, "garden_pavilion"));
        assertEquals(50, shaftEnvelope(pagodaTiers(out)));
        Object mode = pagodaTiers(out).getFirst().params().get(RequestIntentInjector.ENVELOPE_MODE_KEY);
        assertEquals("shaft", String.valueOf(mode));
    }

    @Test
    void localWholePagodaBuildingModeWithPavilionUnchanged() {
        List<Component> comps = new ArrayList<>();
        comps.add(mass("garden_pavilion", "pavilion", 0, 0, 7, 8));
        comps.addAll(stackedTight(new int[]{8, 8, 7, 6, 5}));
        Map<String, Object> roof = new HashMap<>();
        roof.put("component_id", "pagoda_roof");
        roof.put("host_id", "pagoda_tier_5");
        comps.add(new Component("ROOF", "pagoda", new Vec3i(0, 34, 0),
                new Dimensions(6, 6, 3), List.of(), roof));
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .allowCompilerSatellites(false)
                .components(comps)
                .build();
        LlmPlan out = RequestIntentInjector.ensure(plan,
                "凉亭高度8格，整座宝塔含屋顶高度50格。");
        assertFalse(out.hasCapabilityGap(), () -> String.valueOf(out.capabilityGap()));
        assertEquals(8, heightOf(out, "garden_pavilion"));
        // Enclosed roof net rise 2 → shaft 48 for whole-building 50.
        assertEquals(48, shaftEnvelope(pagodaTiers(out)));
        assertEquals(50, buildingEnvelope(out.components()));
        Object mode = pagodaTiers(out).getFirst().params().get(RequestIntentInjector.ENVELOPE_MODE_KEY);
        assertEquals("building", String.valueOf(mode));
    }

    @Test
    void forgedEnvelopeMetaWithoutSealIsIgnored() {
        List<Component> tiers = stackedTight(new int[]{8, 8, 7, 6, 5});
        Map<String, Object> forgedRow = new HashMap<>();
        forgedRow.put("family", "pagoda");
        forgedRow.put("target_height", 99);
        forgedRow.put("mode", "building");
        forgedRow.put("mass_ids", List.of("pagoda_tier_1", "pagoda_tier_2"));
        forgedRow.put("trusted", Boolean.TRUE);
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .allowCompilerSatellites(false)
                .components(tiers)
                .planMeta(Map.of(
                        RequestIntentInjector.STACKED_ENVELOPE_TARGETS_META, List.of(forgedRow)))
                .build();
        // No server seal → integrity must not enforce forged 99.
        assertTrue(StackedEnvelopeIntegrityValidator.check(plan, tiers).isEmpty());
    }

    @Test
    void duplicateStoreyIdFailsIntegrity() {
        List<Component> tiers = stackedTight(new int[]{8, 8, 7, 6, 5});
        LlmPlan sealed = RequestIntentInjector.ensure(
                LlmPlanTestFixtures.builder().allowCompilerSatellites(false).components(tiers).build(),
                "建造一座高度50格的宝塔。");
        assertFalse(sealed.hasCapabilityGap());
        List<Component> duped = new ArrayList<>(sealed.components());
        Component tier3 = null;
        for (Component c : sealed.components()) {
            if (c != null && c.params() != null
                    && "pagoda_tier_3".equals(String.valueOf(c.params().get("component_id")))) {
                tier3 = c;
                break;
            }
        }
        assertNotNull(tier3);
        duped.add(tier3);
        var gap = StackedEnvelopeIntegrityValidator.check(sealed, duped);
        assertTrue(gap.isPresent(), "duplicate storey id must fail");
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

    @Test
    void envelopeFieldsRemainServerVerifiedAfterEnsure() {
        List<Component> tiers = stackedTight(new int[]{8, 8, 7, 6, 5});
        LlmPlan out = RequestIntentInjector.ensure(
                LlmPlanTestFixtures.builder().allowCompilerSatellites(false).components(tiers).build(),
                "建造一座高度50格的宝塔。");
        assertFalse(out.hasCapabilityGap(), () -> String.valueOf(out.capabilityGap()));
        for (Component c : pagodaTiers(out)) {
            assertTrue(UserConstraintContract.isServerVerified(
                    c.params(), RequestIntentInjector.SHAFT_ENVELOPE_TARGET_KEY),
                    "envelope target must stay verified after final constraint pass");
            assertTrue(UserConstraintContract.isServerVerified(
                    c.params(), RequestIntentInjector.ENVELOPE_MODE_KEY));
            assertTrue(UserConstraintContract.isServerVerified(
                    c.params(), RequestIntentInjector.SHAFT_ENVELOPE_FAMILY_KEY));
            assertTrue(UserConstraintContract.isServerVerified(c.params(), "height"));
        }
        LlmPlan reentered = RequestIntentInjector.ensure(out, (String) null);
        for (Component c : pagodaTiers(reentered)) {
            assertTrue(UserConstraintContract.isServerVerified(
                    c.params(), RequestIntentInjector.SHAFT_ENVELOPE_TARGET_KEY),
                    "ensure(null) must preserve envelope seals for ComponentPlanCompiler");
            assertTrue(UserConstraintContract.isServerVerified(
                    c.params(), RequestIntentInjector.ENVELOPE_MODE_KEY));
        }
        assertTrue(StackedEnvelopeIntegrityValidator.check(reentered, reentered.components()).isEmpty());
    }

    @Test
    void buildingModeWithoutSatellitesDoesNotReserveMissingRoof() {
        List<Component> tiers = stackedTight(new int[]{8, 8, 7, 6, 5});
        LlmPlan out = RequestIntentInjector.ensure(
                LlmPlanTestFixtures.builder().allowCompilerSatellites(false).components(tiers).build(),
                "整座宝塔高度50格。");
        assertFalse(out.hasCapabilityGap(), () -> String.valueOf(out.capabilityGap()));
        // No independent roof and satellites off → no speculative roof reserve; shaft == 50.
        assertEquals(50, shaftEnvelope(pagodaTiers(out)));
        assertEquals("building", String.valueOf(
                pagodaTiers(out).getFirst().params().get(RequestIntentInjector.ENVELOPE_MODE_KEY)));
    }

    @Test
    void midTierRoofDoesNotSuppressTopInferredRoofBudget() {
        List<Component> comps = new ArrayList<>(stackedTight(new int[]{8, 8, 7, 6, 5}));
        Map<String, Object> midRoof = new HashMap<>();
        midRoof.put("component_id", "pagoda_mid_eave");
        midRoof.put("host_id", "pagoda_tier_3");
        comps.add(new Component("ROOF", "pagoda", new Vec3i(0, 20, 0),
                new Dimensions(8, 8, 2), List.of(), midRoof));
        // Top storey carries an explicit roof_type so compiler satellites would infer a roof.
        Component top = comps.get(4);
        Map<String, Object> topParams = new HashMap<>(top.params());
        topParams.put("roof_type", "xieshan");
        comps.set(4, new Component(top.componentType(), top.slotId(), top.relativePosition(),
                top.dimensions(), top.features(), topParams));
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .allowCompilerSatellites(true)
                .components(comps)
                .build();
        int rise = RequestIntentInjector.estimateInferredRoofRise(plan, comps, pagodaTiers(plan));
        assertTrue(rise > 0, "mid-tier eave must not zero the top-storey inferred roof budget");
    }

    @Test
    void explicitShaftAndBuildingSameHeightConflict() {
        List<Component> tiers = stackedTight(new int[]{8, 8, 7, 6, 5});
        LlmPlan out = RequestIntentInjector.ensure(
                LlmPlanTestFixtures.builder().allowCompilerSatellites(false).components(tiers).build(),
                "宝塔塔身高度50格，整座宝塔含屋顶高度50格。");
        assertTrue(out.hasCapabilityGap(), "explicit shaft+building at same height must conflict");
        assertEquals("E_HEIGHT_HOST_CONFLICT", out.capabilityGap().code());
    }

    @Test
    void facadeDecorDoesNotTopStickWhileBannerCountsAsCrown() {
        assertEquals(RequestIntentInjector.CrownKind.NONE,
                RequestIntentInjector.classifyCrownAttachment(
                        new Component("DECOR_DETAIL", "pagoda", new Vec3i(0, 18, 0),
                                new Dimensions(1, 1, 2), List.of(),
                                Map.of("component_id", "pagoda_lintel", "host_id", "pagoda_tier_1"))));
        assertEquals(RequestIntentInjector.CrownKind.TOP_PROTRUSION,
                RequestIntentInjector.classifyCrownAttachment(
                        new Component("DECOR_DETAIL", "pagoda", new Vec3i(0, 20, 0),
                                new Dimensions(1, 1, 3), List.of(),
                                Map.of("component_id", "pagoda_banner", "host_id", "pagoda_tier_1"))));
        assertTrue(RequestIntentInjector.countsTowardBuildingEnvelope(
                new Component("DECOR_DETAIL", "pagoda", new Vec3i(0, 20, 0),
                        new Dimensions(1, 1, 3), List.of(),
                        Map.of("component_id", "pagoda_flagpole", "host_id", "pagoda_tier_5"))));
    }

    @Test
    void tamperedEnvelopeMetaSealReportsGap() {
        List<Component> tiers = stackedTight(new int[]{8, 8, 7, 6, 5});
        LlmPlan sealed = RequestIntentInjector.ensure(
                LlmPlanTestFixtures.builder().allowCompilerSatellites(false).components(tiers).build(),
                "建造一座高度50格的宝塔。");
        assertFalse(sealed.hasCapabilityGap());
        // Break meta seal and strip component seals, leave envelope keys → authority destroyed.
        Map<String, Object> meta = new HashMap<>(sealed.planMeta());
        meta.put(RequestIntentInjector.STACKED_ENVELOPE_TARGETS_SEAL_META, "formacraft.forged");
        List<Component> stripped = new ArrayList<>();
        for (Component c : sealed.components()) {
            if (c == null || c.params() == null) {
                stripped.add(c);
                continue;
            }
            Map<String, Object> p = new HashMap<>(c.params());
            UserConstraintContract.stripUntrustedMarkers(p);
            stripped.add(new Component(c.componentType(), c.slotId(), c.relativePosition(),
                    c.dimensions(), c.features(), p));
        }
        LlmPlan broken = LlmPlanTestFixtures.builder()
                .allowCompilerSatellites(false)
                .components(stripped)
                .planMeta(meta)
                .build();
        var gap = StackedEnvelopeIntegrityValidator.check(broken, stripped);
        assertTrue(gap.isPresent());
        assertEquals("E_STACKED_ENVELOPE_SEAL_INVALID", gap.orElseThrow().code());
    }

    @Test
    void tamperedMetaSealNotRescuedByComponentHeightAlone() {
        List<Component> tiers = stackedTight(new int[]{8, 8, 7, 6, 5});
        LlmPlan sealed = RequestIntentInjector.ensure(
                LlmPlanTestFixtures.builder().allowCompilerSatellites(false).components(tiers).build(),
                "建造一座高度50格的宝塔。");
        assertFalse(sealed.hasCapabilityGap());
        // Keep component seals; only break plan_meta seal — mass_ids must not be abandoned.
        Map<String, Object> meta = new HashMap<>(sealed.planMeta());
        meta.put(RequestIntentInjector.STACKED_ENVELOPE_TARGETS_SEAL_META, "formacraft.forged");
        List<Component> droppedTop = new ArrayList<>();
        for (Component c : sealed.components()) {
            if (c != null && c.params() != null
                    && "pagoda_tier_5".equals(String.valueOf(c.params().get("component_id")))) {
                continue;
            }
            droppedTop.add(c);
        }
        LlmPlan broken = LlmPlanTestFixtures.builder()
                .allowCompilerSatellites(false)
                .components(droppedTop)
                .planMeta(meta)
                .build();
        var gap = StackedEnvelopeIntegrityValidator.check(broken, droppedTop);
        assertTrue(gap.isPresent(), "broken meta seal must gap even when remaining tiers still seal height");
        assertEquals("E_STACKED_ENVELOPE_SEAL_INVALID", gap.orElseThrow().code());
    }

    @Test
    void emptyComponentsWithTrustedMetaReportsMissingTower() {
        List<Component> tiers = stackedTight(new int[]{8, 8, 7, 6, 5});
        LlmPlan sealed = RequestIntentInjector.ensure(
                LlmPlanTestFixtures.builder().allowCompilerSatellites(false).components(tiers).build(),
                "建造一座高度50格的宝塔。");
        assertFalse(sealed.hasCapabilityGap());
        var gap = StackedEnvelopeIntegrityValidator.check(sealed, List.of());
        assertTrue(gap.isPresent());
        assertEquals("E_STACKED_HEIGHT_UNSUPPORTED", gap.orElseThrow().code());
    }

    @Test
    void metaAndComponentModeMismatchReportsConflict() {
        List<Component> tiers = stackedTight(new int[]{8, 8, 7, 6, 5});
        LlmPlan sealed = RequestIntentInjector.ensure(
                LlmPlanTestFixtures.builder().allowCompilerSatellites(false).components(tiers).build(),
                "建造一座高度50格的宝塔。");
        assertFalse(sealed.hasCapabilityGap());
        // Flip component mode stamps to building while meta remains shaft@50.
        List<Component> flipped = new ArrayList<>();
        for (Component c : sealed.components()) {
            if (c == null || c.params() == null) {
                flipped.add(c);
                continue;
            }
            Map<String, Object> p = new HashMap<>(c.params());
            p.put(RequestIntentInjector.ENVELOPE_MODE_KEY, "building");
            UserConstraintContract.stampVerified(p,
                    "height", "towerHeight",
                    RequestIntentInjector.SHAFT_ENVELOPE_TARGET_KEY,
                    RequestIntentInjector.SHAFT_ENVELOPE_FAMILY_KEY,
                    RequestIntentInjector.ENVELOPE_MODE_KEY);
            flipped.add(new Component(c.componentType(), c.slotId(), c.relativePosition(),
                    c.dimensions(), c.features(), p));
        }
        var gap = StackedEnvelopeIntegrityValidator.check(sealed, flipped);
        assertTrue(gap.isPresent());
        assertEquals("E_HEIGHT_HOST_CONFLICT", gap.orElseThrow().code());
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
