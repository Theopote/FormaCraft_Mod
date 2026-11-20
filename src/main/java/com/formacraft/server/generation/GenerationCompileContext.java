package com.formacraft.server.generation;

import com.formacraft.common.generation.component.util.ComponentFootprintUtil;
import com.formacraft.common.generation.routing.GenerationAuthority;
import com.formacraft.common.generation.routing.PlanMetaAccess;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.Slot;
import com.formacraft.common.typology.TypologyComponentRouter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Compile-scoped authority and metadata shared by the generation pipeline.
 * Prefer {@link #runWith}; manual installation must restore the previous
 * context in a finally block.
 * <p>
 * Typology-exclusive softening / fallback blocking is host-scoped when a mixed
 * compound retains independent buildings beside a typology STRUCTURE.
 * Per-component ownership ({@link ComponentOwnership}) is the shared contract for
 * exclusive filtering and independent-building detection — never a global boolean alone.
 */
public final class GenerationCompileContext {
    private static final ThreadLocal<GenerationCompileContext> CURRENT = new ThreadLocal<>();

    /**
     * Per-component building ownership relative to exclusive typology STRUCTURE roots.
     * Classification: GEOMETRY / HARD_CONSTRAINT — not style heuristics.
     */
    public enum ComponentOwnership {
        /** Clear exclusive-host scope (id/building_id/host chain or overlapping footprint). */
        EXCLUSIVE_HOST,
        /** Proven independent building (distinct building_id or separated plan-space footprint). */
        INDEPENDENT_BUILDING,
        /** Cannot prove exclusive or independent — fail closed toward exclusive strip. */
        AMBIGUOUS
    }

    private final GenerationAuthority authority;
    private final Map<String, Object> planMeta;
    private final boolean typologyExclusive;
    /** True when the plan is a sole typology shell (no independent compositional building). */
    private final boolean soleTypologyShell;
    private final Set<String> typologyExclusiveHostIds;
    /** component_id → host_id for multi-level ownership walks. */
    private final Map<String, String> typologyHostParentById;

    public GenerationCompileContext(
            GenerationAuthority authority,
            Map<String, Object> planMeta,
            boolean typologyExclusive
    ) {
        this(authority, planMeta, typologyExclusive, typologyExclusive, Set.of(), Map.of());
    }

    public GenerationCompileContext(
            GenerationAuthority authority,
            Map<String, Object> planMeta,
            boolean typologyExclusive,
            boolean soleTypologyShell,
            Set<String> typologyExclusiveHostIds
    ) {
        this(authority, planMeta, typologyExclusive, soleTypologyShell, typologyExclusiveHostIds, Map.of());
    }

    public GenerationCompileContext(
            GenerationAuthority authority,
            Map<String, Object> planMeta,
            boolean typologyExclusive,
            boolean soleTypologyShell,
            Set<String> typologyExclusiveHostIds,
            Map<String, String> typologyHostParentById
    ) {
        this.authority = authority;
        this.planMeta = planMeta == null || planMeta.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap(new HashMap<>(planMeta));
        this.typologyExclusive = typologyExclusive;
        this.soleTypologyShell = soleTypologyShell;
        this.typologyExclusiveHostIds = typologyExclusiveHostIds == null || typologyExclusiveHostIds.isEmpty()
                ? Set.of()
                : Set.copyOf(typologyExclusiveHostIds);
        this.typologyHostParentById = typologyHostParentById == null || typologyHostParentById.isEmpty()
                ? Map.of()
                : Map.copyOf(typologyHostParentById);
    }

    public static GenerationCompileContext fromPlan(LlmPlan plan, boolean typologyExclusive) {
        return fromPlan(plan, typologyExclusive, typologyExclusive, Set.of(), Map.of());
    }

    public static GenerationCompileContext fromPlan(
            LlmPlan plan,
            boolean typologyExclusive,
            boolean soleTypologyShell,
            Set<String> typologyExclusiveHostIds
    ) {
        return fromPlan(plan, typologyExclusive, soleTypologyShell, typologyExclusiveHostIds, Map.of());
    }

    public static GenerationCompileContext fromPlan(
            LlmPlan plan,
            boolean typologyExclusive,
            boolean soleTypologyShell,
            Set<String> typologyExclusiveHostIds,
            Map<String, String> typologyHostParentById
    ) {
        return new GenerationCompileContext(
                PlanMetaAccess.planGenerationAuthority(plan),
                plan == null ? null : plan.planMeta(),
                typologyExclusive,
                soleTypologyShell,
                typologyExclusiveHostIds,
                typologyHostParentById
        );
    }

    /** Collect unbound STRUCTURE+typology component ids for host-scoped exclusive policy. */
    public static Set<String> exclusiveTypologyHostIds(List<Component> components) {
        if (components == null || components.isEmpty()) {
            return Set.of();
        }
        Set<String> ids = new HashSet<>();
        for (Component c : components) {
            if (c == null) continue;
            String type = c.componentType() == null ? "" : c.componentType().trim().toUpperCase(Locale.ROOT);
            if (!"STRUCTURE".equals(type) || !TypologyComponentRouter.hasTypologyHint(c)) {
                continue;
            }
            if (param(c, "host_id", "hostId") != null) {
                continue;
            }
            String id = param(c, "component_id", "componentId");
            if (id != null) {
                ids.add(id);
            }
        }
        return ids;
    }

    /**
     * Independent compositional host present beside typology STRUCTURE.
     * Same contract as typology-exclusive filter: distinct {@code building_id} or plan-space
     * spatial separation (anchor_mode + slot.anchor). Never component_id alone.
     */
    public static boolean hasIndependentCompositionalBuilding(List<Component> components) {
        return hasIndependentCompositionalBuilding(components, Map.of());
    }

    public static boolean hasIndependentCompositionalBuilding(
            List<Component> components, Map<String, Slot> slotMap) {
        if (components == null || components.isEmpty()) {
            return false;
        }
        List<Component> exclusiveRoots = new ArrayList<>();
        Set<String> exclusiveBuildingIds = new HashSet<>();
        for (Component c : components) {
            if (c == null) continue;
            String type = c.componentType() == null ? "" : c.componentType().trim().toUpperCase(Locale.ROOT);
            if (!"STRUCTURE".equals(type) || !TypologyComponentRouter.hasTypologyHint(c)) continue;
            if (param(c, "host_id", "hostId") != null) continue;
            exclusiveRoots.add(c);
            String bid = param(c, "building_id", "buildingId");
            if (bid != null) exclusiveBuildingIds.add(bid);
        }
        if (exclusiveRoots.isEmpty()) {
            return false;
        }
        return hasIndependentBuildingBesideTypology(
                components, exclusiveRoots, exclusiveBuildingIds, slotMap);
    }

    /**
     * Shared mixed-compound detector used by exclusive filter and compile context.
     * Strong evidence only: a component with {@link ComponentOwnership#INDEPENDENT_BUILDING}.
     */
    public static boolean hasIndependentBuildingBesideTypology(
            List<Component> components,
            List<Component> exclusiveRoots,
            Set<String> exclusiveBuildingIds,
            Map<String, Slot> slotMap) {
        if (exclusiveRoots == null || exclusiveRoots.isEmpty()) {
            return false;
        }
        if (exclusiveRoots.size() > 1) {
            return true;
        }
        Set<String> exclusiveIds = new HashSet<>();
        for (Component root : exclusiveRoots) {
            String id = param(root, "component_id", "componentId");
            if (id != null) exclusiveIds.add(id);
        }
        Map<String, String> parentById = hostParentMap(components);
        Set<Component> exclusiveSet = new HashSet<>(exclusiveRoots);
        for (Component c : components) {
            if (c == null || exclusiveSet.contains(c) || isWholeBuildingTypologyRoot(c)) {
                continue;
            }
            if (param(c, "host_id", "hostId") != null) {
                continue;
            }
            if (!isTypologyExclusiveBuildingRootType(c)) {
                continue;
            }
            if (resolveCompositionalOwnership(
                    c, exclusiveRoots, exclusiveIds, exclusiveBuildingIds, parentById, slotMap)
                    == ComponentOwnership.INDEPENDENT_BUILDING) {
                return true;
            }
        }
        Set<String> seenBuildingIds = new HashSet<>(
                exclusiveBuildingIds == null ? Set.of() : exclusiveBuildingIds);
        for (Component c : components) {
            if (c == null) continue;
            String bid = param(c, "building_id", "buildingId");
            if (bid == null) continue;
            if (seenBuildingIds.add(bid)
                    && exclusiveBuildingIds != null && !exclusiveBuildingIds.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Per-component ownership vs exclusive typology roots.
     * Overlap / host-chain → {@link ComponentOwnership#EXCLUSIVE_HOST};
     * distinct building_id or proven footprint separation → {@link ComponentOwnership#INDEPENDENT_BUILDING};
     * otherwise {@link ComponentOwnership#AMBIGUOUS} (missing geom ≠ independent).
     */
    public static ComponentOwnership resolveCompositionalOwnership(
            Component c,
            List<Component> exclusiveRoots,
            Set<String> exclusiveIds,
            Set<String> exclusiveBuildingIds,
            Map<String, String> parentById,
            Map<String, Slot> slotMap) {
        if (c == null) {
            return ComponentOwnership.AMBIGUOUS;
        }
        if (belongsToTypologyExclusiveScope(c, exclusiveIds, exclusiveBuildingIds, parentById)) {
            return ComponentOwnership.EXCLUSIVE_HOST;
        }
        if (overlapsAnyExclusiveRoot(c, exclusiveRoots, slotMap)) {
            return ComponentOwnership.EXCLUSIVE_HOST;
        }
        // Satellite of a non-exclusive host travels with that building tree.
        String host = param(c, "host_id", "hostId");
        if (host != null && (exclusiveIds == null || !exclusiveIds.contains(host))) {
            return ComponentOwnership.INDEPENDENT_BUILDING;
        }
        String bid = param(c, "building_id", "buildingId");
        if (bid != null
                && (exclusiveBuildingIds == null || exclusiveBuildingIds.isEmpty()
                || !exclusiveBuildingIds.contains(bid))) {
            return ComponentOwnership.INDEPENDENT_BUILDING;
        }
        if (isSpatiallySeparatedFromExclusiveRoots(c, exclusiveRoots, slotMap)) {
            return ComponentOwnership.INDEPENDENT_BUILDING;
        }
        return ComponentOwnership.AMBIGUOUS;
    }

    public static boolean belongsToTypologyExclusiveScope(
            Component c,
            Set<String> exclusiveIds,
            Set<String> exclusiveBuildingIds,
            Map<String, String> parentById) {
        if (c == null) return false;
        String bid = param(c, "building_id", "buildingId");
        if (bid != null && exclusiveBuildingIds != null && exclusiveBuildingIds.contains(bid)) {
            return true;
        }
        String id = param(c, "component_id", "componentId");
        if (id != null && exclusiveIds != null && exclusiveIds.contains(id)) {
            return true;
        }
        String cursor = param(c, "host_id", "hostId");
        if (cursor == null) {
            cursor = id;
        }
        Set<String> seen = new HashSet<>();
        Map<String, String> parents = parentById == null ? Map.of() : parentById;
        while (cursor != null && seen.add(cursor)) {
            if (exclusiveIds != null && exclusiveIds.contains(cursor)) {
                return true;
            }
            cursor = parents.get(cursor);
        }
        return false;
    }

    public static Map<String, String> hostParentMap(List<Component> components) {
        Map<String, String> parentById = new HashMap<>();
        if (components == null) return parentById;
        for (Component c : components) {
            if (c == null) continue;
            String id = param(c, "component_id", "componentId");
            if (id == null) continue;
            String host = param(c, "host_id", "hostId");
            if (host != null) {
                parentById.put(id, host);
            }
        }
        return parentById;
    }

    private static boolean overlapsAnyExclusiveRoot(
            Component candidate, List<Component> exclusiveRoots, Map<String, Slot> slotMap) {
        int[] cand = ComponentFootprintUtil.planSpaceFootprintXz(candidate, slotMap);
        if (cand == null || exclusiveRoots == null) {
            return false;
        }
        for (Component root : exclusiveRoots) {
            if (root == null) continue;
            int[] rootBox = ComponentFootprintUtil.planSpaceFootprintXz(root, slotMap);
            if (rootBox != null && ComponentFootprintUtil.xzFootprintsOverlap(cand, rootBox)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isWholeBuildingTypologyRoot(Component c) {
        if (c == null) return false;
        String type = c.componentType() == null ? "" : c.componentType().trim().toUpperCase(Locale.ROOT);
        return "STRUCTURE".equals(type)
                && TypologyComponentRouter.hasTypologyHint(c)
                && param(c, "host_id", "hostId") == null;
    }

    private static boolean isTypologyExclusiveBuildingRootType(Component c) {
        String type = c.componentType() == null ? "" : c.componentType().trim().toUpperCase(Locale.ROOT);
        return type.startsWith("MASS")
                || "STRUCTURE".equals(type)
                || "MODULE".equals(type)
                || "TOWER".equals(type)
                || type.startsWith("TOWER");
    }

    /**
     * Proven plan-space separation only. Missing geometry (even with distinct slot ids) is NOT
     * independent — unknown location ≠ proven different location.
     */
    private static boolean isSpatiallySeparatedFromExclusiveRoots(
            Component candidate, List<Component> exclusiveRoots, Map<String, Slot> slotMap) {
        if (candidate == null || exclusiveRoots == null || exclusiveRoots.isEmpty()) {
            return false;
        }
        int[] cand = ComponentFootprintUtil.planSpaceFootprintXz(candidate, slotMap);
        if (cand == null) {
            return false;
        }
        boolean sawRootGeom = false;
        for (Component root : exclusiveRoots) {
            if (root == null) continue;
            int[] rootBox = ComponentFootprintUtil.planSpaceFootprintXz(root, slotMap);
            if (rootBox == null) {
                continue;
            }
            sawRootGeom = true;
            if (ComponentFootprintUtil.xzFootprintsOverlap(cand, rootBox)) {
                return false;
            }
        }
        return sawRootGeom;
    }

    private static String param(Component c, String... keys) {
        if (c == null || c.params() == null) return null;
        for (String key : keys) {
            Object v = c.params().get(key);
            if (v == null) continue;
            String s = String.valueOf(v).trim();
            if (!s.isEmpty() && !"null".equalsIgnoreCase(s)) return s;
        }
        return null;
    }

    public GenerationAuthority authority() {
        return authority;
    }

    public Map<String, Object> planMeta() {
        return planMeta;
    }

    public boolean typologyExclusive() {
        return typologyExclusive;
    }

    public boolean soleTypologyShell() {
        return soleTypologyShell;
    }

    /**
     * Soften circulation conflicts only for sole typology shells — never because a stadium
     * companion is present beside an ordinary house.
     */
    public boolean softensCirculationConflicts() {
        return typologyExclusive && soleTypologyShell;
    }

    /**
     * Block archetype→structure fallback for components owned by exclusive typology hosts
     * (including multi-level host_id chains), or for every component when the plan is a sole
     * typology shell.
     */
    public boolean blocksArchetypeFallbackFor(Component c) {
        if (!typologyExclusive) {
            return false;
        }
        if (soleTypologyShell || typologyExclusiveHostIds.isEmpty()) {
            return true;
        }
        if (c == null) {
            return false;
        }
        String id = param(c, "component_id", "componentId");
        if (id != null && typologyExclusiveHostIds.contains(id)) {
            return true;
        }
        String cursor = param(c, "host_id", "hostId");
        Set<String> seen = new HashSet<>();
        while (cursor != null && seen.add(cursor)) {
            if (typologyExclusiveHostIds.contains(cursor)) {
                return true;
            }
            cursor = typologyHostParentById.get(cursor);
        }
        return false;
    }

    public static GenerationCompileContext current() {
        return CURRENT.get();
    }

    public static void install(GenerationCompileContext ctx) {
        if (ctx == null) CURRENT.remove();
        else CURRENT.set(ctx);
    }

    public static <T> T runWith(GenerationCompileContext ctx, Supplier<T> fn) {
        GenerationCompileContext prev = CURRENT.get();
        try {
            install(ctx);
            return fn.get();
        } finally {
            if (prev == null) CURRENT.remove();
            else CURRENT.set(prev);
        }
    }

    public static void runWith(GenerationCompileContext ctx, Runnable fn) {
        runWith(ctx, () -> {
            fn.run();
            return null;
        });
    }
}
