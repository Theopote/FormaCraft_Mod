package com.formacraft.common.component;

import com.formacraft.common.component.query.*;
import com.formacraft.common.llm.dto.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ComponentLibraryBatchContextTest {
    @Test void explicitGroupCanSpanBuildingsAndSlotsProvideFallback() {
        var a = new Component("DECOR_DETAIL", "one", new Vec3i(0, 0, 0), new Dimensions(5, 3, 1),
                List.of("component_request:" + QUERY), Map.of("component_id", "a", "building_id", "one", "suite_group", "shared"));
        var b = new Component("DECOR_DETAIL", "two", new Vec3i(0, 0, 0), new Dimensions(5, 3, 1),
                List.of("component_request:" + QUERY), Map.of("component_id", "b", "building_id", "two", "suite_group", "shared"));
        var c = new Component("DECOR_DETAIL", "fallback", new Vec3i(0, 0, 0), new Dimensions(5, 3, 1),
                List.of("component_request:" + QUERY), Map.of("component_id", "c"));
        var d = new Component("DECOR_DETAIL", "fallback", new Vec3i(1, 0, 0), new Dimensions(5, 3, 1),
                List.of("component_request:" + QUERY), Map.of("component_id", "d"));
        try (var scope = ComponentLibraryBatchContext.open(List.of(a, b, c, d), 1, this::selections)) {
            assertEquals(Set.of("suite_group:shared", "slot:fallback"), scope.plans().keySet());
            assertNotNull(ComponentLibraryBatchContext.lookup(a));
            assertNotNull(ComponentLibraryBatchContext.lookup(d));
        }
    }
    private static final String QUERY = "{\"component_query\":{\"semantic\":{\"role\":\"window\"}}}";
    private Component request(String id, String building, String json) {
        return new Component("DECOR_DETAIL", "slot", new Vec3i(0, 0, 0), new Dimensions(5, 3, 1),
                List.of("component_request:" + json), Map.of("component_id", id, "building_id", building));
    }
    private ComponentBatchPlanner.Plan selections(List<ComponentBatchPlanner.Need> needs, long seed) {
        var selected = new LinkedHashMap<String, ComponentRetriever.FittedCandidate>();
        for (var need : needs) {
            var def = new ComponentDefinition(); def.id = "chosen_" + need.id();
            var block = new ComponentDefinition.BlockEntry(); block.block = "minecraft:stone";
            def.blocks = List.of(block);
            selected.put(need.id(), new ComponentRetriever.FittedCandidate(def.id, def,
                    new ComponentFitScore(0, 0, .8), Set.of("a")));
        }
        return new ComponentBatchPlanner.Plan("a", true, selected, Map.of(), List.of());
    }

    @Test void preparedSelectionIsConsumedWithoutReloadingAndDoesNotLeakAfterScope() {
        var a = request("a", "home", QUERY); var b = request("b", "home", QUERY);
        var calls = new AtomicInteger();
        try (var scope = ComponentLibraryBatchContext.open(List.of(a, b), 1, (needs, seed) -> {
            calls.incrementAndGet(); assertEquals("window", needs.getFirst().query().semantic.role);
            return selections(needs, seed);
        })) {
            assertEquals(1, calls.get()); assertEquals(1, scope.plans().size());
            assertEquals("chosen_a", PlayerComponentExpander.resolveSingleRequest(a, null, Map.of()).sourceId());
            assertTrue(PlayerComponentExpander.resolveSingleRequest(a, null, Map.of()).ok());
            // A value-equal copy must not accidentally reuse a selection for another prepared component.
            var copy = request("a", "home", QUERY);
            assertEquals(a, copy); assertNull(ComponentLibraryBatchContext.lookup(copy));
        }
        assertNull(ComponentLibraryBatchContext.lookup(a));
        assertEquals(ComponentResolveResult.Status.NO_CANDIDATE,
                PlayerComponentExpander.resolveSingleRequest(a, null, Map.of()).status());
    }

    @Test void nativeRequestsExplicitAssetsMountsAndOptOutStayOutsideBatch() {
        var explicit = request("explicit", "home", "{\"id\":\"asset\",\"component_query\":{}}");
        var mount = request("mount", "home", "{\"host\":{},\"mount\":{}}");
        var optOut = request("out", "home", "{\"component_query\":{\"selection\":{\"coordinateSuite\":false}}}");
        var invalid = request("invalid", "home", "{");
        var disabled = new Component("DECOR_DETAIL", "slot", new Vec3i(0, 0, 0), new Dimensions(5, 3, 1),
                List.of("component_request:" + QUERY), Map.of("component_id", "disabled", "building_id", "home", "disabled", true));
        var nativePart = new Component("MASS_MAIN", "slot", new Vec3i(0, 0, 0), new Dimensions(5, 5, 5), List.of(), Map.of());
        try (var scope = ComponentLibraryBatchContext.open(List.of(explicit, mount, optOut, invalid, nativePart, disabled), 1,
                (needs, seed) -> { fail("must not query the library"); return null; })) {
            assertTrue(scope.plans().isEmpty());
            assertNull(ComponentLibraryBatchContext.lookup(explicit));
        }
    }

    @Test void partitionsBuildingsAndResolvesHostBuildingIdentity() {
        var mass = new Component("MASS_MAIN", "slot", new Vec3i(0, 0, 0), new Dimensions(10, 8, 10),
                List.of(), Map.of("component_id", "mass", "building_id", "home"));
        var a = request("a", "home", QUERY);
        var b = new Component("DECOR_DETAIL", "other_slot", new Vec3i(0, 0, 0), new Dimensions(5, 3, 1),
                List.of("component_request:" + QUERY), Map.of("component_id", "b", "host_id", "mass"));
        var separate = request("c", "other_building", QUERY);
        try (var scope = ComponentLibraryBatchContext.open(List.of(mass, a, b, separate), 1, this::selections)) {
            assertEquals(Set.of("building:home"), scope.plans().keySet());
            assertNotNull(ComponentLibraryBatchContext.lookup(b));
            assertNull(ComponentLibraryBatchContext.lookup(separate));
        }
    }

    @Test void nestedCompilationAndExceptionsRestorePreviousSelections() {
        var a = request("a", "home", QUERY); var b = request("b", "home", QUERY);
        try (var outer = ComponentLibraryBatchContext.open(List.of(a, b), 1, this::selections)) {
            try (var inner = ComponentLibraryBatchContext.open(List.of(), 2, this::selections)) {
                assertNull(ComponentLibraryBatchContext.lookup(a));
            }
            assertNotNull(ComponentLibraryBatchContext.lookup(a));
            assertThrows(IllegalStateException.class, () -> {
                try (var inner = ComponentLibraryBatchContext.open(List.of(), 2, this::selections)) {
                    throw new IllegalStateException("simulated emit failure");
                }
            });
            assertNotNull(ComponentLibraryBatchContext.lookup(a));
        }
        assertNull(ComponentLibraryBatchContext.lookup(a));
    }

    @Test void unresolvedStatusIsRetainedAndAmbiguousIdentitiesDoNotBatch() {
        var a = request("a", "home", QUERY); var b = request("b", "home", QUERY);
        try (var scope = ComponentLibraryBatchContext.open(List.of(a, b), 1, (needs, seed) ->
                new ComponentBatchPlanner.Plan("", false, Map.of(), Map.of(
                        "a", ComponentResolveResult.adaptFailed("asset", "cannot_fit"),
                        "b", ComponentResolveResult.loadFailed("missing", "missing")), List.of()))) {
            assertEquals(ComponentResolveResult.Status.ADAPT_FAILED, PlayerComponentExpander.resolveSingleRequest(a, null, Map.of()).status());
            assertEquals(ComponentResolveResult.Status.LOAD_FAILED, PlayerComponentExpander.resolveSingleRequest(b, null, Map.of()).status());
        }
        try (var scope = ComponentLibraryBatchContext.open(List.of(a, request("a", "home", QUERY)), 1,
                (needs, seed) -> { fail("ambiguous IDs must not be coordinated"); return null; })) {
            assertTrue(scope.plans().isEmpty());
        }
    }
}
