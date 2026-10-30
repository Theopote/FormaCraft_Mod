package com.formacraft.common.component.query;

import com.formacraft.common.component.ComponentDefinition;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ComponentBatchPlannerTest {
    @Test void unavailableFitsStayFailuresEvenWithMaximumCoordinationBonus() {
        var need = need("too_narrow", true); need.query().geometry.openingWidth = 3;
        var pool = pool(need.query(), new String[]{"wide"}, new String[]{"a"}, new double[]{.95});
        assertTrue(pool.candidates().isEmpty());
        var plan = ComponentBatchPlanner.plan(List.of(need), 1, n -> pool);
        assertFalse(plan.complete()); assertTrue(plan.selected().isEmpty());
        assertEquals(ComponentResolveResult.Status.ADAPT_FAILED, plan.unresolved().get(need.id()).status());
    }
    private ComponentRetriever.CandidatePool pool(ComponentQuery q, String[] ids, String[] suites, double[] scores) {
        var ranked = new ArrayList<ComponentScore>();
        var definitions = new HashMap<String, ComponentDefinition>();
        for (int i = 0; i < ids.length; i++) {
            var def = new ComponentDefinition(); def.id = ids[i]; def.sourceOrigin = "CAPTURED";
            def.size = new ComponentDefinition.Size(); def.size.w = 5; def.size.h = def.size.d = 1;
            def.tags = List.of("suite:" + suites[i]);
            var b = new ComponentDefinition.BlockEntry(); b.block = "minecraft:stone"; def.blocks = List.of(b);
            definitions.put(def.id, def);
            var score = new ComponentScore(def.id); score.totalScore = scores[i]; ranked.add(score);
        }
        return ComponentRetriever.fitCandidates(q, new Random(1), ranked, definitions::get);
    }
    private ComponentBatchPlanner.Need need(String id, boolean required) {
        var q = new ComponentQuery(); q.geometry.openingWidth = 5;
        return new ComponentBatchPlanner.Need(id, q, required);
    }

    @Test void sharedSuiteCanBeatIndependentlySelectedPartsWithoutRepeatedFits() {
        var window = need("window", true); var rail = need("rail", true);
        var windows = pool(window.query(), new String[]{"w_c", "w_a"}, new String[]{"c", "a"}, new double[]{.82, .80});
        var rails = pool(rail.query(), new String[]{"r_b", "r_a"}, new String[]{"b", "a"}, new double[]{.82, .80});
        assertEquals("w_c", windows.report().result().sourceId());
        assertEquals("r_b", rails.report().result().sourceId());
        var calls = new AtomicInteger();
        var plan = ComponentBatchPlanner.plan(List.of(window, rail), .05, n -> {
            calls.incrementAndGet(); return n.id().equals("window") ? windows : rails;
        });
        assertEquals(2, calls.get()); assertTrue(plan.complete()); assertEquals("a", plan.suite());
        assertEquals("w_a", plan.selected().get("window").sourceId());
        assertEquals("r_a", plan.selected().get("rail").sourceId());
        assertNull(window.query().selection.preferredSuite);
        assertThrows(UnsupportedOperationException.class, () -> plan.selected().clear());
        var reversed = ComponentBatchPlanner.plan(List.of(rail, window), .05,
                n -> n.id().equals("window") ? windows : rails);
        assertEquals(plan.suite(), reversed.suite()); assertEquals(plan.selected(), reversed.selected());
        assertEquals(plan.alternatives(), reversed.alternatives());
    }

    @Test void requiredFailuresBlockCompletionAndOptionalFailuresRemainVisible() {
        var required = need("required", true); var optional = need("optional", false);
        var missing = ComponentRetriever.fitCandidates(optional.query(), new Random(1), List.of(), id -> null);
        var plan = ComponentBatchPlanner.plan(List.of(required, optional), .05, n -> missing);
        assertFalse(plan.complete()); assertEquals(2, plan.unresolved().size());
        var optionalOnly = ComponentBatchPlanner.plan(List.of(optional), .05, n -> missing);
        assertTrue(optionalOnly.complete());
        assertEquals(ComponentResolveResult.Status.NO_CANDIDATE, optionalOnly.unresolved().get("optional").status());
    }

    @Test void explicitSuitePreferenceIsNotOverwrittenOrRewardedTwice() {
        var window = need("window", true); window.query().selection.preferredSuite = "b";
        var pool = pool(window.query(), new String[]{"a", "b"}, new String[]{"a", "b"}, new double[]{.8, .8});
        var plan = ComponentBatchPlanner.plan(List.of(window), 1, n -> pool);
        assertEquals("b", plan.selected().get("window").sourceId());
        assertEquals("b", window.query().selection.preferredSuite);
        assertEquals("", plan.suite());
        assertTrue(plan.alternatives().stream().allMatch(a -> Math.abs(a.score() - .85) < 1e-12));
    }

    @Test void duplicateNeedsAndInvalidPolicyFailBeforeResolving() {
        var need = need("same", true);
        assertThrows(IllegalArgumentException.class, () -> ComponentBatchPlanner.plan(List.of(need, need), .05,
                n -> { fail("must not resolve invalid batch"); return null; }));
        assertThrows(IllegalArgumentException.class, () -> ComponentBatchPlanner.plan(List.of(need), Double.NaN,
                n -> { fail("must not resolve invalid policy"); return null; }));
    }
}
