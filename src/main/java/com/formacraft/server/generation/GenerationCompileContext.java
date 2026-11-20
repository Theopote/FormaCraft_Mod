package com.formacraft.server.generation;

import com.formacraft.common.generation.routing.GenerationAuthority;
import com.formacraft.common.generation.routing.PlanMetaAccess;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.typology.TypologyComponentRouter;

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
 */
public final class GenerationCompileContext {
    private static final ThreadLocal<GenerationCompileContext> CURRENT = new ThreadLocal<>();

    private final GenerationAuthority authority;
    private final Map<String, Object> planMeta;
    private final boolean typologyExclusive;
    /** True when the plan is a sole typology shell (no independent compositional building). */
    private final boolean soleTypologyShell;
    private final Set<String> typologyExclusiveHostIds;

    public GenerationCompileContext(
            GenerationAuthority authority,
            Map<String, Object> planMeta,
            boolean typologyExclusive
    ) {
        this(authority, planMeta, typologyExclusive, typologyExclusive, Set.of());
    }

    public GenerationCompileContext(
            GenerationAuthority authority,
            Map<String, Object> planMeta,
            boolean typologyExclusive,
            boolean soleTypologyShell,
            Set<String> typologyExclusiveHostIds
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
    }

    public static GenerationCompileContext fromPlan(LlmPlan plan, boolean typologyExclusive) {
        return fromPlan(plan, typologyExclusive, typologyExclusive, Set.of());
    }

    public static GenerationCompileContext fromPlan(
            LlmPlan plan,
            boolean typologyExclusive,
            boolean soleTypologyShell,
            Set<String> typologyExclusiveHostIds
    ) {
        return new GenerationCompileContext(
                PlanMetaAccess.planGenerationAuthority(plan),
                plan == null ? null : plan.planMeta(),
                typologyExclusive,
                soleTypologyShell,
                typologyExclusiveHostIds
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
     * Independent compositional host present beside typology STRUCTURE (MASS without typology /
     * host_id, with its own identity). Used to keep residential circulation hard-failing in mixes.
     */
    public static boolean hasIndependentCompositionalBuilding(List<Component> components) {
        if (components == null || components.isEmpty()) {
            return false;
        }
        Set<String> exclusiveIds = exclusiveTypologyHostIds(components);
        Set<String> exclusiveBuildingIds = new HashSet<>();
        for (Component c : components) {
            if (c == null) continue;
            String type = c.componentType() == null ? "" : c.componentType().trim().toUpperCase(Locale.ROOT);
            if (!"STRUCTURE".equals(type) || !TypologyComponentRouter.hasTypologyHint(c)) continue;
            if (param(c, "host_id", "hostId") != null) continue;
            String bid = param(c, "building_id", "buildingId");
            if (bid != null) exclusiveBuildingIds.add(bid);
        }
        for (Component c : components) {
            if (c == null) continue;
            String type = c.componentType() == null ? "" : c.componentType().trim().toUpperCase(Locale.ROOT);
            if (!(type.startsWith("MASS") || "TOWER".equals(type) || type.startsWith("TOWER"))) {
                continue;
            }
            if (param(c, "host_id", "hostId") != null) {
                continue;
            }
            if (TypologyComponentRouter.hasTypologyHint(c)) {
                continue;
            }
            String bid = param(c, "building_id", "buildingId");
            if (bid != null && (exclusiveBuildingIds.isEmpty() || !exclusiveBuildingIds.contains(bid))) {
                return true;
            }
            String id = param(c, "component_id", "componentId");
            if (id != null && !exclusiveIds.contains(id) && bid == null && exclusiveBuildingIds.isEmpty()) {
                // Distinct unbound mass with identity — compositional companion when no shared building_id.
                return true;
            }
        }
        return false;
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
     * Block archetype→structure fallback for components owned by exclusive typology hosts,
     * or for every component when the plan is a sole typology shell.
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
        String host = param(c, "host_id", "hostId");
        return host != null && typologyExclusiveHostIds.contains(host);
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
