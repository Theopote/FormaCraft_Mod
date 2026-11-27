package com.formacraft.common.generation.component.util;

import com.formacraft.common.geometry.shape.CsgOp;
import com.formacraft.common.geometry.shape.ShapeLibrary;
import com.formacraft.common.geometry.shape.ShapeSpec;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.Vec3i;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Authored mass boolean composition (Phase 02.2 / 02.2.1 / 02.3.1).
 * <p>
 * Nested {@code params.masses[]} may carry {@code operation}/{@code op}:
 * {@code add|union} (default when absent), {@code subtract|carve}, {@code intersect}.
 * Illegal ops → {@code E_MASS_CSG_OP_UNSUPPORTED} (never silent UNION).
 * Classification: GEOMETRY operators (not building-class enums).
 * <p>
 * Parent body = first UNION / envelope; annex = additional UNION; subtract/intersect
 * carve the accumulator. {@code replace_envelope} / centered tiers remain
 * {@link ResolvedMassPart} concerns, not CSG ops.
 * <p>
 * Phase 02.3.1: {@link CsgResult} carries O(1) occupancy + Y-slices; wall shell via
 * 4-neigh erosion; carve voids tracked at SUBTRACT time (no AABB blast).
 */
public final class MassBooleanComposition {

    /** HARD_CONSTRAINT — refuse pathological composites. */
    public static final int MAX_CSG_VOXELS = 250_000;

    /** Cap on recorded carve-void cells (not AABB volume). */
    public static final int MAX_CARVE_VOIDS = 250_000;

    /**
     * One CSG operand. Offsets are envelope-local (host min-corner space after fold).
     */
    public record CsgPart(CsgOp op, int offsetX, int offsetY, int offsetZ, int width, int depth, int height) {}

    /**
     * Consumable boolean solid with indexed queries (Phase 02.3.1 Task A).
     * Bounds are true voxel min/max (exclusive max, half-open). Empty → zero-size at origin.
     */
    public static final class CsgResult {
        private final List<ShapeLibrary.Voxel> voxels;
        private final Set<Long> occupied;
        private final Map<Integer, Set<Long>> footprintByY;
        private final List<ShapeLibrary.Voxel> carveVoids;
        private final int minX;
        private final int minY;
        private final int minZ;
        private final int maxX;
        private final int maxY;
        private final int maxZ;
        private final String sourceOwnerId;

        private CsgResult(
                List<ShapeLibrary.Voxel> voxels,
                Set<Long> occupied,
                Map<Integer, Set<Long>> footprintByY,
                List<ShapeLibrary.Voxel> carveVoids,
                int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                String sourceOwnerId
        ) {
            this.voxels = voxels;
            this.occupied = occupied;
            this.footprintByY = footprintByY;
            this.carveVoids = carveVoids;
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxY = maxY;
            this.maxZ = maxZ;
            this.sourceOwnerId = sourceOwnerId;
        }

        public List<ShapeLibrary.Voxel> voxels() {
            return voxels;
        }

        public int minX() {
            return minX;
        }

        public int minY() {
            return minY;
        }

        public int minZ() {
            return minZ;
        }

        public int maxX() {
            return maxX;
        }

        public int maxY() {
            return maxY;
        }

        public int maxZ() {
            return maxZ;
        }

        public String sourceOwnerId() {
            return sourceOwnerId;
        }

        public int envelopeWidth() {
            return Math.max(0, maxX - minX);
        }

        public int envelopeDepth() {
            return Math.max(0, maxZ - minZ);
        }

        public int envelopeHeight() {
            return Math.max(0, maxY - minY);
        }

        public boolean isEmpty() {
            return voxels.isEmpty();
        }

        /** Average O(1) occupancy probe — out-of-pack coordinates are false (never throw). */
        public boolean contains(int x, int y, int z) {
            if (!inPackRange3(x, y, z)) return false;
            return occupied.contains(pack3(x, y, z));
        }

        /**
         * 6-neighborhood surface: solid with a missing face neighbor, or empty cell
         * face-adjacent to a solid. Neighbor probes never throw on pack overflow.
         */
        public boolean isBoundary(int x, int y, int z) {
            if (contains(x, y, z)) {
                return !contains(x - 1, y, z) || !contains(x + 1, y, z)
                        || !contains(x, y - 1, z) || !contains(x, y + 1, z)
                        || !contains(x, y, z - 1) || !contains(x, y, z + 1);
            }
            return contains(x - 1, y, z) || contains(x + 1, y, z)
                    || contains(x, y - 1, z) || contains(x, y + 1, z)
                    || contains(x, y, z - 1) || contains(x, y, z + 1);
        }

        /** True when solid and missing at least one 6-face neighbor (exposed surface). */
        public boolean isSolidSurface(int x, int y, int z) {
            if (!contains(x, y, z)) return false;
            return !contains(x - 1, y, z) || !contains(x + 1, y, z)
                    || !contains(x, y - 1, z) || !contains(x, y + 1, z)
                    || !contains(x, y, z - 1) || !contains(x, y, z + 1);
        }

        /** Solid XZ cells at storey Y — O(slice) via Y-index. */
        public Set<Long> footprintAt(int y) {
            Set<Long> slice = footprintByY.get(y);
            return slice == null ? Set.of() : slice;
        }

        /**
         * Cells removed by SUBTRACT that were previously solid (atrium / carve).
         * Does <strong>not</strong> enumerate empty AABB exterior between sparse parts.
         */
        public List<ShapeLibrary.Voxel> carveVoids() {
            return carveVoids;
        }

        /**
         * @deprecated Prefer {@link #carveVoids()} — AABB hole blast is forbidden for walls.
         *             Retained as an alias to carve voids for 02.2.1 call sites.
         */
        @Deprecated
        public List<ShapeLibrary.Voxel> voids() {
            return carveVoids;
        }

        public ComponentFootprintUtil.Bounds bounds() {
            return new ComponentFootprintUtil.Bounds(minX, minY, minZ, maxX, maxY, maxZ);
        }

        /**
         * Plan-horizontal 4-neigh erosion of thickness {@code t}, then shell = D \\ eroded.
         * Y is preserved (walls stay vertical). Empty when D collapses.
         */
        /**
         * Plan-horizontal shell. When erosion empties the domain, returns empty list so callers
         * can emit {@code E_MASS_WALL_THICKNESS} instead of filling the whole solid.
         */
        public List<ShapeLibrary.Voxel> shellWalls(int thickness) {
            int t = Math.max(1, thickness);
            if (isEmpty()) return List.of();
            Set<Long> domain = new HashSet<>(occupied);
            Set<Long> eroded = domain;
            for (int i = 0; i < t; i++) {
                eroded = erode4Horizontal(eroded);
                if (eroded.isEmpty()) {
                    // Thickness collapses usable interior — do not treat entire D as wall.
                    return List.of();
                }
            }
            List<ShapeLibrary.Voxel> shell = new ArrayList<>();
            for (ShapeLibrary.Voxel v : voxels) {
                long key = pack3(v.x(), v.y(), v.z());
                if (!eroded.contains(key)) {
                    shell.add(v);
                }
            }
            return shell;
        }

        /**
         * XZ cells on the slice that are solid and have a missing 4-neigh in-plane
         * (exterior or courtyard edge).
         */
        public Set<Long> horizontalBoundaryAt(int y) {
            Set<Long> slice = footprintAt(y);
            if (slice.isEmpty()) return Set.of();
            Set<Long> edge = new HashSet<>();
            for (long xz : slice) {
                int x = unpackX(xz);
                int z = unpackZ(xz);
                if (!slice.contains(pack2(x - 1, z)) || !slice.contains(pack2(x + 1, z))
                        || !slice.contains(pack2(x, z - 1)) || !slice.contains(pack2(x, z + 1))) {
                    edge.add(xz);
                }
            }
            return edge;
        }
    }

    private MassBooleanComposition() {}

    /**
     * True when nested {@code masses[]} need a unified shell (Phase 02.3.2 Task D):
     * <ul>
     *   <li>any SUBTRACT/INTERSECT → boolean compose</li>
     *   <li>explicit ADD/UNION on size≥2 → one UNION domain + shared floors (no mid partition)</li>
     *   <li>{@code mass_boolean_composition} with size≥2 → CSG</li>
     *   <li>bare tier lists without {@code operation} stay on {@link ResolvedMassPart}
     *       (stepped / replace_envelope) — not forced into CSG</li>
     * </ul>
     * Independent peer components remain separate (shared-wall cleanup is 02.3.3).
     */
    public static boolean needsCsg(Component component) {
        if (component == null || component.params() == null) return false;
        Object masses = component.params().get("masses");
        if (Boolean.TRUE.equals(component.params().get("mass_boolean_composition"))
                || Boolean.TRUE.equals(component.params().get("massBooleanComposition"))) {
            return masses instanceof List<?> list && list.size() >= 2;
        }
        if (!(masses instanceof List<?> list) || list.size() < 2) return false;
        boolean sawExplicitOp = false;
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> raw)) continue;
            Object rawOp = raw.get("operation");
            if (rawOp == null) rawOp = raw.get("op");
            if (rawOp == null) continue;
            sawExplicitOp = true;
            CsgOp op = parseOpStrict(rawOp);
            if (op != CsgOp.UNION) return true;
        }
        // Explicit ADD/UNION-only annexes → unified shell; bare stepped tiers → false.
        return sawExplicitOp;
    }

    /**
     * Stair-well / protected void reservation keys in envelope-local pack3 space.
     * Empty this phase unless params declare {@code protected_voids} / {@code stair_voids}
     * as list of {x,y,z} maps — full stair systems are out of scope (02.3.2 interface only).
     */
    public static Set<Long> protectedVoidKeys(Component component) {
        if (component == null || component.params() == null) return Set.of();
        Object raw = component.params().get("protected_voids");
        if (raw == null) raw = component.params().get("protectedVoids");
        if (raw == null) raw = component.params().get("stair_voids");
        if (raw == null) raw = component.params().get("stairVoids");
        if (!(raw instanceof List<?> list) || list.isEmpty()) return Set.of();
        Set<Long> out = new LinkedHashSet<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m)) continue;
            int x = intFrom(m.get("x"), 0);
            int y = intFrom(m.get("y"), 0);
            int z = intFrom(m.get("z"), 0);
            if (!inPackRange3(x, y, z)) continue;
            out.add(pack3(x, y, z));
        }
        return Set.copyOf(out);
    }

    private static int intFrom(Object raw, int fallback) {
        if (raw instanceof Number n) return n.intValue();
        if (raw == null) return fallback;
        try {
            return Integer.parseInt(String.valueOf(raw).trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    /**
     * Extrude a 2D footprint occupancy into a lightweight domain (non-CSG L/U/courtyard
     * share the same floor / postprocess query API without forcing boolean compose).
     */
    public static CsgResult domainFromFootprint(ResolvedFootprint footprint, int height, String sourceOwnerId) {
        if (footprint == null) {
            return fromVoxels(List.of(), sourceOwnerId);
        }
        int h = Math.max(1, height);
        List<ShapeLibrary.Voxel> voxels = new ArrayList<>();
        long budget = 0L;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < footprint.width(); x++) {
                for (int z = 0; z < footprint.depth(); z++) {
                    if (!footprint.contains(x, z)) continue;
                    budget++;
                    if (budget > MAX_CSG_VOXELS) {
                        throw new IllegalArgumentException(
                                "E_MASS_CSG_BUDGET: footprint extrude exceeds " + MAX_CSG_VOXELS + " voxels");
                    }
                    voxels.add(new ShapeLibrary.Voxel(x, y, z));
                }
            }
        }
        return fromVoxels(voxels, sourceOwnerId);
    }

    /** Peer satellite folded into a host CSG child (skip solid self-emit). */
    public static boolean isFoldedCsgSatellite(Component component) {
        if (component == null || component.params() == null) return false;
        return Boolean.TRUE.equals(component.params().get("mass_csg_folded"))
                || Boolean.TRUE.equals(component.params().get("massCsgFolded"));
    }

    /**
     * Peer-mass CSG cue: subtract / intersect / union against a host.
     * Returns null when this component is not a CSG satellite.
     * Explicit {@code mass_operation} uses strict parse (Phase 02.3.1 Task C).
     */
    public static CsgOp peerMassOperation(Component component) {
        if (component == null || component.params() == null) return null;
        Object raw = component.params().get("mass_operation");
        if (raw == null) raw = component.params().get("massOperation");
        if (raw != null) {
            return parseOpStrict(raw);
        }
        // Legacy: bare operation / composition / intentional_interlock without mass_operation.
        Object opField = component.params().get("operation");
        if (opField != null) {
            CsgOp op = parseOpLenient(opField);
            if (op != null) return op;
            throw new IllegalArgumentException(
                    "E_MASS_CSG_OP_UNSUPPORTED: " + opField
                            + " (expected add|union, subtract|carve, intersect)");
        }
        Object composition = component.params().get("mass_composition");
        if (composition == null) composition = component.params().get("massComposition");
        if (composition != null) {
            String s = String.valueOf(composition).trim().toLowerCase(Locale.ROOT);
            if (s.contains("subtract") || s.contains("carve")) return CsgOp.SUBTRACT;
            if (s.contains("intersect")) return CsgOp.INTERSECT;
            if (s.contains("union") || s.contains("interlock")) return CsgOp.UNION;
        }
        if (truthy(component.params().get("intentional_interlock"))
                || truthy(component.params().get("intentionalInterlock"))) {
            return CsgOp.UNION;
        }
        return null;
    }

    public static String peerHostId(Component component) {
        if (component == null || component.params() == null) return null;
        Object host = component.params().get("host_id");
        if (host == null) host = component.params().get("hostId");
        if (host == null) host = component.params().get("target_host_id");
        if (host == null) host = component.params().get("targetHostId");
        if (host == null) return null;
        String s = String.valueOf(host).trim();
        return s.isEmpty() || "null".equalsIgnoreCase(s) ? null : s;
    }

    /** True when two peer masses are linked by authored CSG / intentional interlock. */
    public static boolean peersAreBooleanComposition(Component a, Component b) {
        if (a == null || b == null) return false;
        String aId = componentId(a);
        String bId = componentId(b);
        if (linksTo(a, bId) || linksTo(b, aId)) {
            CsgOp opA = safePeerOp(a);
            CsgOp opB = safePeerOp(b);
            if (opA != null || opB != null) return true;
            if (truthy(a.params() == null ? null : a.params().get("intentional_interlock"))
                    || truthy(a.params() == null ? null : a.params().get("intentionalInterlock"))
                    || truthy(b.params() == null ? null : b.params().get("intentional_interlock"))
                    || truthy(b.params() == null ? null : b.params().get("intentionalInterlock"))) {
                return true;
            }
        }
        return false;
    }

    /** peerMassOperation without throwing — used by relation probes. */
    private static CsgOp safePeerOp(Component c) {
        try {
            return peerMassOperation(c);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    public static List<CsgPart> partsFromResolved(List<ResolvedMassPart> resolved) {
        if (resolved == null || resolved.isEmpty()) return List.of();
        List<CsgPart> parts = new ArrayList<>();
        for (ResolvedMassPart part : resolved) {
            Map<String, Object> src = part.sourceParams();
            Object rawOp = src == null ? null : src.get("operation");
            if (rawOp == null && src != null) rawOp = src.get("op");
            CsgOp op = rawOp == null ? CsgOp.UNION : parseOpStrict(rawOp);
            Dimensions d = part.dimensions();
            Vec3i o = part.offset();
            parts.add(new CsgPart(op, o.x(), o.y(), o.z(), d.width(), d.depth(), d.height()));
        }
        return parts;
    }

    /**
     * Offset-aware CSG over axis-aligned boxes (envelope-local voxels).
     * INTERSECT empty after seed stays empty — never reseeds from a later operand (P0-01).
     *
     * @throws IllegalArgumentException with {@code E_MASS_CSG_*} codes on invalid / oversized input
     */
    public static CsgResult compose(List<CsgPart> parts) {
        return compose(parts, null);
    }

    public static CsgResult compose(List<CsgPart> parts, String sourceOwnerId) {
        if (parts == null || parts.isEmpty()) {
            throw new IllegalArgumentException("E_MASS_CSG_EMPTY: no mass parts for boolean composition");
        }
        long budgetProbe = 0L;
        for (CsgPart p : parts) {
            try {
                long cell = Math.multiplyExact(
                        Math.multiplyExact((long) Math.max(1, p.width()), Math.max(1, p.depth())),
                        Math.max(1, p.height()));
                budgetProbe = Math.addExact(budgetProbe, cell);
            } catch (ArithmeticException ex) {
                throw new IllegalArgumentException(
                        "E_MASS_CSG_BUDGET: composite mass exceeds " + MAX_CSG_VOXELS + " voxel probe");
            }
            if (budgetProbe > MAX_CSG_VOXELS) {
                throw new IllegalArgumentException(
                        "E_MASS_CSG_BUDGET: composite mass exceeds " + MAX_CSG_VOXELS + " voxel probe");
            }
        }

        Set<ShapeLibrary.Voxel> acc = new HashSet<>();
        Set<ShapeLibrary.Voxel> carved = new LinkedHashSet<>();
        boolean seeded = false;
        for (CsgPart part : parts) {
            List<ShapeLibrary.Voxel> chunk = boxVoxels(part.width(), part.depth(), part.height());
            List<ShapeLibrary.Voxel> placed = translate(chunk, part.offsetX(), part.offsetY(), part.offsetZ());
            switch (part.op()) {
                case UNION -> {
                    acc.addAll(placed);
                    seeded = true;
                }
                case SUBTRACT -> {
                    for (ShapeLibrary.Voxel v : placed) {
                        if (acc.remove(v)) {
                            carved.add(v);
                            if (carved.size() > MAX_CARVE_VOIDS) {
                                throw new IllegalArgumentException(
                                        "E_MASS_CSG_BUDGET: carve voids exceed " + MAX_CARVE_VOIDS);
                            }
                        }
                    }
                }
                case INTERSECT -> {
                    if (!seeded) {
                        acc.clear();
                        acc.addAll(placed);
                        seeded = true;
                    } else {
                        Set<ShapeLibrary.Voxel> next = new HashSet<>();
                        for (ShapeLibrary.Voxel v : placed) {
                            if (acc.contains(v)) next.add(v);
                        }
                        acc = next;
                    }
                }
            }
        }
        if (acc.size() > MAX_CSG_VOXELS) {
            throw new IllegalArgumentException(
                    "E_MASS_CSG_BUDGET: result exceeds " + MAX_CSG_VOXELS + " voxels");
        }
        // Carve cells that are solid again (UNION after subtract) are not voids.
        carved.removeAll(acc);
        return fromVoxels(new ArrayList<>(acc), List.copyOf(carved), sourceOwnerId);
    }

    public static CsgResult fromVoxels(List<ShapeLibrary.Voxel> voxels, String sourceOwnerId) {
        return fromVoxels(voxels, List.of(), sourceOwnerId);
    }

    public static CsgResult fromVoxels(
            List<ShapeLibrary.Voxel> voxels,
            List<ShapeLibrary.Voxel> carveVoids,
            String sourceOwnerId
    ) {
        if (voxels == null || voxels.isEmpty()) {
            return new CsgResult(
                    List.of(), Set.of(), Map.of(),
                    carveVoids == null ? List.of() : List.copyOf(carveVoids),
                    0, 0, 0, 0, 0, 0, sourceOwnerId);
        }
        List<ShapeLibrary.Voxel> sorted = new ArrayList<>(voxels);
        sorted.sort(Comparator
                .comparingInt(ShapeLibrary.Voxel::y)
                .thenComparingInt(ShapeLibrary.Voxel::x)
                .thenComparingInt(ShapeLibrary.Voxel::z));
        Set<Long> occupied = new HashSet<>(sorted.size() * 2);
        Map<Integer, Set<Long>> byY = new HashMap<>();
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (ShapeLibrary.Voxel v : sorted) {
            occupied.add(pack3(v.x(), v.y(), v.z()));
            byY.computeIfAbsent(v.y(), k -> new HashSet<>()).add(pack2(v.x(), v.z()));
            minX = Math.min(minX, v.x());
            minY = Math.min(minY, v.y());
            minZ = Math.min(minZ, v.z());
            maxX = Math.max(maxX, v.x() + 1);
            maxY = Math.max(maxY, v.y() + 1);
            maxZ = Math.max(maxZ, v.z() + 1);
        }
        Map<Integer, Set<Long>> frozenY = new HashMap<>();
        for (var e : byY.entrySet()) {
            frozenY.put(e.getKey(), Set.copyOf(e.getValue()));
        }
        return new CsgResult(
                List.copyOf(sorted),
                Set.copyOf(occupied),
                Collections.unmodifiableMap(frozenY),
                carveVoids == null ? List.of() : List.copyOf(carveVoids),
                minX, minY, minZ, maxX, maxY, maxZ,
                sourceOwnerId);
    }

    public static CsgResult composeFromComponent(Component component) {
        assertHostShapeSupported(component);
        String owner = componentId(component);
        List<ResolvedMassPart> resolved = ResolvedMassPart.resolve(component);
        List<CsgPart> parts = partsFromMassesParam(component);
        if (parts.isEmpty()) {
            parts = partsFromResolved(resolved);
        }
        return compose(parts, owner.isBlank() ? null : owner);
    }

    /** Host volume_geometry / plan cues must not silently become a box under CSG. */
    public static void assertHostShapeSupported(Component component) {
        if (component == null || component.params() == null) return;
        Map<String, Object> params = component.params();
        Object vg = params.get("volume_geometry");
        if (vg == null) vg = params.get("volumeGeometry");
        if (vg != null) {
            String s = String.valueOf(vg).trim().toLowerCase(Locale.ROOT);
            if (!s.isEmpty() && !"box".equals(s) && !"rect".equals(s) && !"rectangle".equals(s)
                    && !"cube".equals(s) && !"aabb".equals(s)) {
                throw new IllegalArgumentException(
                        "E_MASS_CSG_SHAPE_UNSUPPORTED: host volume_geometry=" + s
                                + " (only box/rect supported for CSG)");
            }
        }
        assertBoxShapeToken(params.get("shape"));
        assertBoxShapeToken(params.get("kind"));
        assertBoxShapeToken(params.get("footprint"));
        Object planType = params.get("plan_type");
        if (planType == null) planType = params.get("planType");
        if (planType != null) {
            String p = String.valueOf(planType).trim().toLowerCase(Locale.ROOT);
            // Plan patterns that are not a solid box solid — CSG path is box-only this phase.
            if (!p.isEmpty() && !"rect".equals(p) && !"rectangle".equals(p) && !"box".equals(p)
                    && !"none".equals(p)) {
                throw new IllegalArgumentException(
                        "E_MASS_CSG_SHAPE_UNSUPPORTED: host plan_type=" + p
                                + " (CSG requires box envelope; use non-CSG footprint path)");
            }
        }
    }

    private static void assertBoxShapeToken(Object shape) {
        if (shape == null) return;
        String s = String.valueOf(shape).trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return;
        if (!"box".equals(s) && !"rect".equals(s) && !"rectangle".equals(s)
                && !"cube".equals(s) && !"aabb".equals(s)) {
            throw new IllegalArgumentException(
                    "E_MASS_CSG_SHAPE_UNSUPPORTED: shape=" + s
                            + " (only box/rect supported for CSG)");
        }
    }

    @SuppressWarnings("unchecked")
    private static List<CsgPart> partsFromMassesParam(Component component) {
        if (component == null || component.params() == null) return List.of();
        Object masses = component.params().get("masses");
        if (!(masses instanceof List<?> list) || list.isEmpty()) return List.of();
        Dimensions envelope = component.dimensions();
        List<CsgPart> parts = new ArrayList<>();
        boolean hasBase = false;
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> raw)) continue;
            Map<String, Object> m = (Map<String, Object>) raw;
            Object rawOp = m.get("operation");
            if (rawOp == null) rawOp = m.get("op");
            CsgOp op = rawOp == null ? CsgOp.UNION : parseOpStrict(rawOp);
            Object shape = m.get("shape");
            if (shape == null) shape = m.get("kind");
            if (shape == null) shape = m.get("footprint");
            assertBoxShapeToken(shape);
            Object vg = m.get("volume_geometry");
            if (vg == null) vg = m.get("volumeGeometry");
            if (vg != null) assertBoxShapeToken(vg);
            Dimensions dims = readDims(m, envelope);
            Vec3i offset = readOffset(m);
            if (offset.y() == 0 && op == CsgOp.UNION) hasBase = true;
            parts.add(new CsgPart(op, offset.x(), offset.y(), offset.z(),
                    dims.width(), dims.depth(), dims.height()));
        }
        if (!parts.isEmpty() && !hasBase && envelope != null
                && parts.stream().anyMatch(p -> p.op() != CsgOp.UNION)) {
            parts.add(0, new CsgPart(CsgOp.UNION, 0, 0, 0,
                    envelope.width(), envelope.depth(), envelope.height()));
        }
        return parts;
    }

    private static Dimensions readDims(Map<String, Object> m, Dimensions fallback) {
        Object raw = m.get("dimensions");
        int w = fallback == null ? 1 : fallback.width();
        int d = fallback == null ? 1 : fallback.depth();
        int h = fallback == null ? 1 : fallback.height();
        if (raw instanceof Map<?, ?> dm) {
            w = intVal(dm.get("width"), w);
            d = intVal(dm.get("depth"), d);
            h = intVal(dm.get("height"), h);
        }
        return new Dimensions(Math.max(1, w), Math.max(1, d), Math.max(1, h));
    }

    private static Vec3i readOffset(Map<String, Object> m) {
        Object raw = m.get("offset");
        if (!(raw instanceof Map<?, ?> om)) return new Vec3i(0, 0, 0);
        return new Vec3i(intVal(om.get("x"), 0), intVal(om.get("y"), 0), intVal(om.get("z"), 0));
    }

    private static int intVal(Object raw, int def) {
        if (raw instanceof Number n) return n.intValue();
        if (raw == null) return def;
        try {
            return Integer.parseInt(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static List<ShapeLibrary.Voxel> boxVoxels(int width, int depth, int height) {
        Map<String, Object> params = Map.of("kind", "box", "hollow", false);
        return ShapeLibrary.generate(ShapeSpec.fromParams(
                Math.max(1, width), Math.max(1, depth), Math.max(1, height), params));
    }

    private static List<ShapeLibrary.Voxel> translate(List<ShapeLibrary.Voxel> src, int ox, int oy, int oz) {
        if (ox == 0 && oy == 0 && oz == 0) return src;
        List<ShapeLibrary.Voxel> out = new ArrayList<>(src.size());
        for (ShapeLibrary.Voxel v : src) {
            out.add(new ShapeLibrary.Voxel(v.x() + ox, v.y() + oy, v.z() + oz));
        }
        return out;
    }

    /** Parse without throwing — unknown → null (caller supplies default). */
    public static CsgOp parseOpLenient(Object raw) {
        if (raw == null) return null;
        String s = String.valueOf(raw).trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return null;
        return switch (s) {
            case "add", "union" -> CsgOp.UNION;
            case "subtract", "sub", "difference", "minus", "carve", "cut" -> CsgOp.SUBTRACT;
            case "intersect", "intersection", "and" -> CsgOp.INTERSECT;
            default -> null;
        };
    }

    /** Parse or throw {@code E_MASS_CSG_OP_UNSUPPORTED} — never silent UNION. */
    public static CsgOp parseOpStrict(Object raw) {
        CsgOp op = parseOpLenient(raw);
        if (op == null) {
            throw new IllegalArgumentException(
                    "E_MASS_CSG_OP_UNSUPPORTED: " + raw
                            + " (expected add|union, subtract|carve, intersect)");
        }
        return op;
    }

    private static boolean linksTo(Component c, String hostId) {
        if (hostId == null || hostId.isBlank()) return false;
        String h = peerHostId(c);
        return h != null && h.equals(hostId);
    }

    private static String componentId(Component c) {
        if (c == null || c.params() == null) return "";
        Object id = c.params().get("component_id");
        if (id == null) id = c.params().get("componentId");
        return id == null ? "" : String.valueOf(id).trim();
    }

    private static boolean truthy(Object raw) {
        if (raw == null) return false;
        if (raw instanceof Boolean b) return b;
        String s = String.valueOf(raw).trim().toLowerCase(Locale.ROOT);
        return "true".equals(s) || "1".equals(s) || "yes".equals(s);
    }

    /** True when (x,y,z) fits the 21-bit signed pack (supports ±1M). */
    public static boolean inPackRange3(int x, int y, int z) {
        final int b = 1 << 20;
        long X = (long) x + b;
        long Y = (long) y + b;
        long Z = (long) z + b;
        return (X & ~0x1FFFFFL) == 0 && (Y & ~0x1FFFFFL) == 0 && (Z & ~0x1FFFFFL) == 0;
    }

    public static boolean inPackRange2(int x, int z) {
        final int b = 1 << 20;
        long X = (long) x + b;
        long Z = (long) z + b;
        return (X & ~0x1FFFFFL) == 0 && (Z & ~0x1FFFFFL) == 0;
    }

    /** Pack x,y,z with 21-bit signed bias (supports ±1M). */
    public static long pack3(int x, int y, int z) {
        if (!inPackRange3(x, y, z)) {
            throw new IllegalArgumentException(
                    "E_MASS_CSG_BUDGET: coordinate out of pack range (" + x + "," + y + "," + z + ")");
        }
        final int b = 1 << 20;
        long X = (long) x + b;
        long Y = (long) y + b;
        long Z = (long) z + b;
        return (X << 42) | (Y << 21) | Z;
    }

    public static long pack2(int x, int z) {
        if (!inPackRange2(x, z)) {
            throw new IllegalArgumentException(
                    "E_MASS_CSG_BUDGET: coordinate out of pack range (" + x + ",?," + z + ")");
        }
        final int b = 1 << 20;
        long X = (long) x + b;
        long Z = (long) z + b;
        return (X << 21) | Z;
    }

    public static int unpackX(long xz) {
        return (int) ((xz >>> 21) & 0x1FFFFFL) - (1 << 20);
    }

    public static int unpackZ(long xz) {
        return (int) (xz & 0x1FFFFFL) - (1 << 20);
    }

    public static int unpack3X(long key) {
        return (int) ((key >>> 42) & 0x1FFFFFL) - (1 << 20);
    }

    public static int unpack3Y(long key) {
        return (int) ((key >>> 21) & 0x1FFFFFL) - (1 << 20);
    }

    public static int unpack3Z(long key) {
        return (int) (key & 0x1FFFFFL) - (1 << 20);
    }

    /** One step of horizontal 4-neigh erosion (same Y). */
    private static Set<Long> erode4Horizontal(Set<Long> domain) {
        Set<Long> next = new HashSet<>();
        for (long key : domain) {
            int x = unpack3X(key);
            int y = unpack3Y(key);
            int z = unpack3Z(key);
            if (packContains(domain, x - 1, y, z)
                    && packContains(domain, x + 1, y, z)
                    && packContains(domain, x, y, z - 1)
                    && packContains(domain, x, y, z + 1)) {
                next.add(key);
            }
        }
        return next;
    }

    private static boolean packContains(Set<Long> domain, int x, int y, int z) {
        if (!inPackRange3(x, y, z)) return false;
        return domain.contains(pack3(x, y, z));
    }
}
