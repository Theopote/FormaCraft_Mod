package com.formacraft.common.generation.component.util;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Compile-scoped protected spatial voids (Phase 02.3.4 Tasks C–F).
 * <p>
 * Kinds are spatial uses (COURTYARD / ATRIUM / …), not building presets.
 * Cells are plan-space pack3 keys. Classification: HARD_CONSTRAINT execution of USER/AI
 * spatial intent — never keyword building routers.
 */
public final class ProtectedVoidIndex {
    private ProtectedVoidIndex() {}

    public enum Kind {
        COURTYARD,
        ATRIUM,
        STAIRWELL,
        PILOTIS,
        PASSAGE,
        DOUBLE_HEIGHT,
        GENERIC_PROTECTED_VOID
    }

    public enum Policy {
        /** Keep empty; deny generic PLACE/CARVE. */
        PROTECT,
        /** Explicit world-clear REMOVE then protect. */
        CLEAR_AND_PROTECT,
        /** Floor slabs skip; walls may remain. */
        SKIP_SLABS,
        /** Bound specialty writers (stair/column/rail) may PLACE. */
        RESERVE
    }

    public record Space(
            String id,
            String hostId,
            Kind kind,
            Policy policy,
            Set<Long> planCells,
            Set<String> allowRoles,
            Set<String> allowComponentIds,
            String provenance
    ) {
        public Space {
            id = id == null ? "" : id.trim();
            hostId = hostId == null ? "" : hostId.trim();
            kind = kind == null ? Kind.GENERIC_PROTECTED_VOID : kind;
            policy = policy == null ? Policy.PROTECT : policy;
            planCells = planCells == null ? Set.of() : Set.copyOf(planCells);
            allowRoles = allowRoles == null ? Set.of() : Set.copyOf(allowRoles);
            allowComponentIds = allowComponentIds == null ? Set.of() : Set.copyOf(allowComponentIds);
            provenance = provenance == null ? "" : provenance;
        }

        public boolean allowsWriter(String componentId, String role) {
            if (policy == Policy.RESERVE) {
                if (componentId != null && allowComponentIds.contains(componentId.trim())) return true;
                if (role != null && allowRoles.contains(role.trim().toUpperCase(Locale.ROOT))) return true;
                return false;
            }
            // PROTECT / CLEAR / SKIP_SLABS: only explicit allow lists may write.
            if (componentId != null && !allowComponentIds.isEmpty()
                    && allowComponentIds.contains(componentId.trim())) {
                return true;
            }
            if (role != null && !allowRoles.isEmpty()
                    && allowRoles.contains(role.trim().toUpperCase(Locale.ROOT))) {
                return true;
            }
            return false;
        }
    }

    private static final ThreadLocal<Map<String, Space>> CACHE = new ThreadLocal<>();
    private static final ThreadLocal<Set<Long>> CELL_INDEX = new ThreadLocal<>();

    public static Scope open() {
        var scope = new Scope(CACHE.get(), CELL_INDEX.get());
        CACHE.set(new LinkedHashMap<>());
        CELL_INDEX.set(new HashSet<>());
        return scope;
    }

    public static final class Scope implements AutoCloseable {
        private final Map<String, Space> prevSpaces;
        private final Set<Long> prevCells;
        private boolean closed;

        private Scope(Map<String, Space> prevSpaces, Set<Long> prevCells) {
            this.prevSpaces = prevSpaces;
            this.prevCells = prevCells;
        }

        @Override
        public void close() {
            if (closed) return;
            if (prevSpaces == null) CACHE.remove();
            else CACHE.set(prevSpaces);
            if (prevCells == null) CELL_INDEX.remove();
            else CELL_INDEX.set(prevCells);
            closed = true;
        }
    }

    public static boolean isActive() {
        return CACHE.get() != null;
    }

    public static void put(Space space) {
        if (space == null || space.id().isEmpty() || !isActive()) return;
        CACHE.get().put(space.id(), space);
        Set<Long> cells = CELL_INDEX.get();
        if (cells != null) cells.addAll(space.planCells());
    }

    public static Space get(String id) {
        var c = CACHE.get();
        return c == null || id == null ? null : c.get(id.trim());
    }

    public static Map<String, Space> snapshot() {
        var c = CACHE.get();
        return c == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(c));
    }

    public static boolean isProtected(int planX, int planY, int planZ) {
        Set<Long> cells = CELL_INDEX.get();
        if (cells == null || cells.isEmpty()) return false;
        if (!MassBooleanComposition.inPackRange3(planX, planY, planZ)) return false;
        return cells.contains(MassBooleanComposition.pack3(planX, planY, planZ));
    }

    public static boolean isProtectedKey(long planKey) {
        Set<Long> cells = CELL_INDEX.get();
        return cells != null && cells.contains(planKey);
    }

    /** True when any covering space denies the writer. */
    public static boolean deniesWriter(long planKey, String componentId, String role) {
        var c = CACHE.get();
        if (c == null || c.isEmpty()) return false;
        boolean covered = false;
        for (Space s : c.values()) {
            if (!s.planCells().contains(planKey)) continue;
            covered = true;
            if (s.allowsWriter(componentId, role)) return false;
        }
        return covered;
    }

    public static Set<Long> allPlanKeys() {
        Set<Long> cells = CELL_INDEX.get();
        return cells == null ? Set.of() : Set.copyOf(cells);
    }

    public static List<Map<String, Object>> toMetaList() {
        var c = CACHE.get();
        if (c == null || c.isEmpty()) return List.of();
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (Space s : c.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.id());
            m.put("host_id", s.hostId());
            m.put("kind", s.kind().name());
            m.put("policy", s.policy().name());
            m.put("cells", new java.util.ArrayList<>(s.planCells()));
            m.put("allow_roles", new java.util.ArrayList<>(s.allowRoles()));
            m.put("allow_component_ids", new java.util.ArrayList<>(s.allowComponentIds()));
            m.put("provenance", s.provenance());
            out.add(m);
        }
        return out;
    }

    public static void loadFromMeta(Object raw) {
        if (!(raw instanceof java.util.Collection<?> col) || !isActive()) return;
        for (Object item : col) {
            if (!(item instanceof Map<?, ?> m)) continue;
            String id = str(m.get("id"));
            if (id.isEmpty()) continue;
            Set<Long> cells = new LinkedHashSet<>();
            Object cellsRaw = m.get("cells");
            if (cellsRaw instanceof java.util.Collection<?> cc) {
                for (Object o : cc) {
                    if (o instanceof Number n) cells.add(n.longValue());
                    else if (o != null) {
                        try {
                            cells.add(Long.parseLong(String.valueOf(o)));
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
            }
            put(new Space(
                    id,
                    str(m.get("host_id")),
                    parseKind(str(m.get("kind"))),
                    parsePolicy(str(m.get("policy"))),
                    cells,
                    stringSet(m.get("allow_roles")),
                    stringSet(m.get("allow_component_ids")),
                    str(m.get("provenance"))
            ));
        }
    }

    public static Kind parseKind(String raw) {
        if (raw == null || raw.isBlank()) return Kind.GENERIC_PROTECTED_VOID;
        String s = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        try {
            return Kind.valueOf(s);
        } catch (IllegalArgumentException ex) {
            if (s.contains("COURTYARD") || s.contains("YARD")) return Kind.COURTYARD;
            if (s.contains("ATRIUM")) return Kind.ATRIUM;
            if (s.contains("STAIR")) return Kind.STAIRWELL;
            if (s.contains("PILOT") || s.contains("STILT")) return Kind.PILOTIS;
            if (s.contains("PASSAGE") || s.contains("CORRIDOR")) return Kind.PASSAGE;
            if (s.contains("DOUBLE") || s.contains("VOID_HEIGHT")) return Kind.DOUBLE_HEIGHT;
            return Kind.GENERIC_PROTECTED_VOID;
        }
    }

    public static Policy parsePolicy(String raw) {
        if (raw == null || raw.isBlank()) return Policy.PROTECT;
        String s = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        try {
            return Policy.valueOf(s);
        } catch (IllegalArgumentException ex) {
            if (s.contains("CLEAR")) return Policy.CLEAR_AND_PROTECT;
            if (s.contains("SLAB") || s.contains("SKIP")) return Policy.SKIP_SLABS;
            if (s.contains("RESERVE")) return Policy.RESERVE;
            return Policy.PROTECT;
        }
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private static Set<String> stringSet(Object raw) {
        Set<String> out = new LinkedHashSet<>();
        if (!(raw instanceof java.util.Collection<?> col)) return out;
        for (Object o : col) {
            if (o != null) {
                String s = String.valueOf(o).trim();
                if (!s.isEmpty()) out.add(s);
            }
        }
        return out;
    }

    public static boolean sameSpace(Space a, Space b) {
        return Objects.equals(a, b);
    }
}
