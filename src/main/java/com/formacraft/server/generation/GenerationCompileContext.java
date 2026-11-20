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
 * Independent-building identity must match {@code applyTypologyExclusiveFilter}
 * (building_id / plan-space footprints — never component_id alone).
 */
public final class GenerationCompileContext {
    private static final ThreadLocal<GenerationCompileContext> CURRENT = new ThreadLocal<>();

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
     * Strong evidence only: distinct {@code building_id}, or clearly separated plan-space footprints.
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
            String bid = param(c, "building_id", "buildingId");
            if (bid != null
                    && (exclusiveBuildingIds == null || exclusiveBuildingIds.isEmpty()
                    || !exclusiveBuildingIds.contains(bid))) {
                return true;
            }
            if (isSpatiallySeparatedFromExclusiveRoots(c, exclusiveRoots, slotMap)) {
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

    private static boolean isSpatiallySeparatedFromExclusiveRoots(
            Component candidate, List<Component> exclusiveRoots, Map<String, Slot> slotMap) {
        if (candidate == null || exclusiveRoots == null || exclusiveRoots.isEmpty()) {
            return false;
        }
        int[] cand = ComponentFootprintUtil.planSpaceFootprintXz(candidate, slotMap);
        String candSlot = authoredSlotId(candidate);
        boolean sawComparable = false;
        for (Component root : exclusiveRoots) {
            if (root == null) continue;
            int[] rootBox = ComponentFootprintUtil.planSpaceFootprintXz(root, slotMap);
            String rootSlot = authoredSlotId(root);
            boolean distinctSlots = candSlot != null && rootSlot != null && !candSlot.equals(rootSlot);

            if (cand != null && rootBox != null) {
                sawComparable = true;
                if (ComponentFootprintUtil.xzFootprintsOverlap(cand, rootBox)) {
                    return false;
                }
                continue;
            }

            // Incomplete geometry: distinct slot frames cannot be proven co-located.
            if (distinctSlots) {
                sawComparable = true;
                continue;
            }
            return false;
        }
        return sawComparable;
    }

    private static String authoredSlotId(Component c) {
        if (c == null || c.slotId() == null || c.slotId().isBlank()) {
            return null;
        }
        return c.slotId().trim();
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
