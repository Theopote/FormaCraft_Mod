package com.formacraft.common.component;

import com.formacraft.common.component.query.ComponentBatchPlanner;
import com.formacraft.common.component.query.ComponentResolveResult;
import com.formacraft.common.llm.dto.Component;
import java.util.*;
import java.util.function.BiFunction;

/** Scoped compiler selections, keyed by the exact prepared component rather than mutable global IDs. */
public final class ComponentLibraryBatchContext implements AutoCloseable {
    private static final ThreadLocal<ComponentLibraryBatchContext> ACTIVE = new ThreadLocal<>();
    private final ComponentLibraryBatchContext previous;
    private final Map<Component, ComponentResolveResult> results = new IdentityHashMap<>();
    private final Map<String, ComponentBatchPlanner.Plan> plans = new LinkedHashMap<>();

    private ComponentLibraryBatchContext() { previous = ACTIVE.get(); }

    public static ComponentLibraryBatchContext open(List<Component> components, long seed) {
        return open(components, seed, ComponentBatchPlanner::plan);
    }

    static ComponentLibraryBatchContext open(List<Component> components, long seed,
            BiFunction<List<ComponentBatchPlanner.Need>, Long, ComponentBatchPlanner.Plan> planner) {
        var context = new ComponentLibraryBatchContext();
        var groups = new TreeMap<String, List<Component>>();
        var buildingsByPart = new HashMap<String, String>();
        for (Component component : components) if (component != null) {
            String id = value(component, "component_id");
            String building = value(component, "building_id");
            if (id != null && building != null) buildingsByPart.put(id, building);
        }
        var queries = new IdentityHashMap<Component, com.formacraft.common.component.query.ComponentQuery>();
        for (Component component : components) {
            var query = PlayerComponentExpander.batchQuery(component);
            String group = group(component, buildingsByPart);
            if (query == null || group == null) continue;
            queries.put(component, query);
            groups.computeIfAbsent(group, ignored -> new ArrayList<>()).add(component);
        }
        for (var group : groups.entrySet()) {
            if (group.getValue().size() < 2) continue;
            var byId = new TreeMap<String, Component>();
            boolean duplicate = false;
            for (Component component : group.getValue()) {
                if (byId.putIfAbsent(needId(component), component) != null) duplicate = true;
            }
            // Ambiguous identities retain independent selection; never guess which request is which.
            if (duplicate) continue;
            var needs = byId.entrySet().stream().map(e ->
                    new ComponentBatchPlanner.Need(e.getKey(), queries.get(e.getValue()), true)).toList();
            var plan = planner.apply(needs, seed ^ group.getKey().hashCode());
            context.plans.put(group.getKey(), plan);
            for (var entry : byId.entrySet()) {
                var selected = plan.selected().get(entry.getKey());
                var result = selected != null ? ComponentResolveResult.success(selected.definition())
                        : plan.unresolved().get(entry.getKey());
                if (result != null) context.results.put(entry.getValue(), result);
            }
        }
        ACTIVE.set(context);
        return context;
    }

    public static ComponentResolveResult lookup(Component component) {
        var context = ACTIVE.get();
        return context == null ? null : context.results.get(component);
    }

    public Map<String, ComponentBatchPlanner.Plan> plans() { return Collections.unmodifiableMap(plans); }

    private static String group(Component component, Map<String, String> buildingsByPart) {
        if (component == null) return null;
        String explicit = value(component, "suite_group");
        if (explicit != null) return "suite_group:" + explicit;
        String building = value(component, "building_id");
        if (building != null) return "building:" + building;
        String host = value(component, "host_id");
        if (host != null) return "building:" + buildingsByPart.getOrDefault(host, host);
        return component.slotId() == null || component.slotId().isBlank() ? null : "slot:" + component.slotId();
    }

    private static String needId(Component component) {
        String explicit = value(component, "component_id");
        return explicit != null ? explicit : component.componentType() + "@" + component.slotId() + "@"
                + component.relativePosition() + "@" + component.dimensions() + "@" + Integer.toUnsignedString(component.hashCode(), 36);
    }

    private static String value(Component component, String key) {
        Object value = component.params() == null ? null : component.params().get(key);
        return value instanceof String text && !text.isBlank() ? text.strip() : null;
    }

    @Override public void close() {
        if (ACTIVE.get() != this) throw new IllegalStateException("Component selection scopes closed out of order");
        if (previous == null) ACTIVE.remove(); else ACTIVE.set(previous);
    }
}
