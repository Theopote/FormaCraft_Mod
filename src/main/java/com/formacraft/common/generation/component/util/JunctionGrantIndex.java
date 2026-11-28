package com.formacraft.common.generation.component.util;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Compile-scoped pair → authorized cells + operations (Phase 02.3.4 Task A3).
 * <p>
 * Empty cell set for a registered pair means <strong>deny</strong> (not allow-all).
 * Classification: HARD_CONSTRAINT provenance for junction/interlock writes.
 */
public final class JunctionGrantIndex {
    private JunctionGrantIndex() {}

    public enum Op { OMIT, CARVE, PLACE, PROTECT, OVERWRITE }

    public record Grant(
            String aId,
            String bId,
            Set<Long> planCells,
            Set<Op> ops,
            String provenance
    ) {
        public Grant {
            aId = aId == null ? "" : aId.trim();
            bId = bId == null ? "" : bId.trim();
            planCells = planCells == null ? Set.of() : Set.copyOf(planCells);
            ops = ops == null ? Set.of() : Set.copyOf(ops);
            provenance = provenance == null ? "" : provenance;
        }

        public boolean allows(Op op, long planCellKey) {
            if (op != null && !ops.isEmpty() && !ops.contains(op)) return false;
            return planCells.contains(planCellKey);
        }
    }

    private static final ThreadLocal<Map<String, Grant>> CACHE = new ThreadLocal<>();

    public static Scope open() {
        var scope = new Scope(CACHE.get());
        CACHE.set(new HashMap<>());
        return scope;
    }

    public static final class Scope implements AutoCloseable {
        private final Map<String, Grant> previous;
        private boolean closed;

        private Scope(Map<String, Grant> previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (closed) return;
            if (previous == null) CACHE.remove();
            else CACHE.set(previous);
            closed = true;
        }
    }

    public static boolean isActive() {
        return CACHE.get() != null;
    }

    public static void clear() {
        var c = CACHE.get();
        if (c != null) c.clear();
    }

    private static String key(String a, String b) {
        String x = a == null ? "" : a.trim();
        String y = b == null ? "" : b.trim();
        if (x.compareTo(y) <= 0) return x + "\0" + y;
        return y + "\0" + x;
    }

    public static void put(Grant grant) {
        if (grant == null || grant.aId().isEmpty() || grant.bId().isEmpty()) return;
        var cache = CACHE.get();
        if (cache == null) return;
        String k = key(grant.aId(), grant.bId());
        Grant prev = cache.get(k);
        if (prev == null) {
            cache.put(k, grant);
            return;
        }
        // Same pair: merge cells/ops (never drop provenance of either).
        Set<Long> cells = new LinkedHashSet<>(prev.planCells());
        cells.addAll(grant.planCells());
        Set<Op> ops = new LinkedHashSet<>(prev.ops());
        ops.addAll(grant.ops());
        cache.put(k, new Grant(prev.aId(), prev.bId(), cells, ops,
                prev.provenance().isEmpty() ? grant.provenance()
                        : prev.provenance() + ";" + grant.provenance()));
    }

    public static void put(
            String aId, String bId, Set<Long> planCells, Set<Op> ops, String provenance
    ) {
        put(new Grant(aId, bId, planCells, ops, provenance));
    }

    public static Grant get(String aId, String bId) {
        var cache = CACHE.get();
        if (cache == null) return null;
        return cache.get(key(aId, bId));
    }

    /**
     * Cell authority for writer↔owner pair.
     * @return true if a grant exists and contains the cell; false if grant missing/empty/miss
     */
    public static boolean allowsCell(String writerId, String ownerId, long planCellKey, Op op) {
        Grant g = get(writerId, ownerId);
        if (g == null) return false;
        if (g.planCells().isEmpty()) return false; // registered empty = deny
        return g.allows(op == null ? Op.OVERWRITE : op, planCellKey);
    }

    /** True when a pair grant was registered (even if empty). */
    public static boolean hasPair(String aId, String bId) {
        return get(aId, bId) != null;
    }

    public static Map<String, Grant> snapshot() {
        var cache = CACHE.get();
        return cache == null ? Map.of() : Collections.unmodifiableMap(new HashMap<>(cache));
    }

    /** Persist grants for emit rehydration after the repair-scope ThreadLocal closes. */
    public static List<Map<String, Object>> toMetaList() {
        var cache = CACHE.get();
        if (cache == null || cache.isEmpty()) return List.of();
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (Grant g : cache.values()) {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("a", g.aId());
            m.put("b", g.bId());
            m.put("cells", new java.util.ArrayList<>(g.planCells()));
            List<String> ops = new java.util.ArrayList<>();
            for (Op op : g.ops()) ops.add(op.name());
            m.put("ops", ops);
            m.put("provenance", g.provenance());
            out.add(m);
        }
        return out;
    }

    /** Reload grants stamped on {@code plan_meta.compiler_junction_grants}. */
    public static void loadFromMeta(Object raw) {
        if (!(raw instanceof java.util.Collection<?> col) || !isActive()) return;
        for (Object item : col) {
            if (!(item instanceof Map<?, ?> m)) continue;
            String a = String.valueOf(m.get("a") == null ? "" : m.get("a")).trim();
            String b = String.valueOf(m.get("b") == null ? "" : m.get("b")).trim();
            if (a.isEmpty() || b.isEmpty()) continue;
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
            Set<Op> ops = new LinkedHashSet<>();
            Object opsRaw = m.get("ops");
            if (opsRaw instanceof java.util.Collection<?> oc) {
                for (Object o : oc) {
                    if (o != null) ops.add(parseOp(String.valueOf(o)));
                }
            }
            if (ops.isEmpty()) ops.add(Op.OVERWRITE);
            String prov = m.get("provenance") == null ? "" : String.valueOf(m.get("provenance"));
            put(a, b, cells, ops, prov);
        }
    }

    public static Op parseOp(String raw) {
        if (raw == null || raw.isBlank()) return Op.OVERWRITE;
        try {
            return Op.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return Op.OVERWRITE;
        }
    }

    public static boolean samePair(String a, String b, String c, String d) {
        return Objects.equals(key(a, b), key(c, d));
    }
}
