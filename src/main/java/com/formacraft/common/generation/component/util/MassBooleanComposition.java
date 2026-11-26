package com.formacraft.common.generation.component.util;

import com.formacraft.common.geometry.shape.CsgOp;
import com.formacraft.common.geometry.shape.ShapeLibrary;
import com.formacraft.common.geometry.shape.ShapeSpec;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.Vec3i;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Authored mass boolean composition (Phase 02.2 / 02.2.1).
 * <p>
 * Nested {@code params.masses[]} may carry {@code operation}/{@code op}:
 * {@code add|union} (default when absent), {@code subtract|carve}, {@code intersect}.
 * Illegal ops → {@code E_MASS_CSG_OP_UNSUPPORTED} (never silent UNION).
 * Classification: GEOMETRY operators (not building-class enums).
 * <p>
 * Parent body = first UNION / envelope; annex = additional UNION; subtract/intersect
 * carve the accumulator. {@code replace_envelope} / centered tiers remain
 * {@link ResolvedMassPart} concerns, not CSG ops.
 */
public final class MassBooleanComposition {

    /** HARD_CONSTRAINT — refuse pathological composites. */
    public static final int MAX_CSG_VOXELS = 250_000;

    /**
     * One CSG operand. Offsets are envelope-local (host min-corner space after fold).
     */
    public record CsgPart(CsgOp op, int offsetX, int offsetY, int offsetZ, int width, int depth, int height) {}

    /**
     * Consumable boolean solid (Phase 02.2.1 Task C). Bounds are true voxel min/max
     * (exclusive max on each axis, half-open). Empty solid → zero-size bounds at origin.
     */
    public record CsgResult(
            List<ShapeLibrary.Voxel> voxels,
            int minX,
            int minY,
            int minZ,
            int maxX,
            int maxY,
            int maxZ,
            String sourceOwnerId
    ) {
        /** Legacy probe width (max exclusive X). */
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
            return voxels == null || voxels.isEmpty();
        }

        public boolean contains(int x, int y, int z) {
            if (isEmpty()) return false;
            for (ShapeLibrary.Voxel v : voxels) {
                if (v.x() == x && v.y() == y && v.z() == z) return true;
            }
            return false;
        }

        /** Face-adjacent to a solid voxel but not itself solid (6-neighborhood). */
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

        /** Solid XZ cells at storey Y (envelope-local). */
        public Set<Long> footprintAt(int y) {
            Set<Long> out = new HashSet<>();
            if (isEmpty()) return out;
            for (ShapeLibrary.Voxel v : voxels) {
                if (v.y() == y) {
                    out.add((((long) v.x()) << 32) ^ (v.z() & 0xffffffffL));
                }
            }
            return out;
        }

        /**
         * Cells inside the AABB bounds that are not solid (atrium / carve voids).
         * Empty when the solid fills its AABB or result is empty.
         */
        public List<ShapeLibrary.Voxel> voids() {
            if (isEmpty()) return List.of();
            Set<ShapeLibrary.Voxel> solid = new HashSet<>(voxels);
            List<ShapeLibrary.Voxel> holes = new ArrayList<>();
            for (int y = minY; y < maxY; y++) {
                for (int x = minX; x < maxX; x++) {
                    for (int z = minZ; z < maxZ; z++) {
                        ShapeLibrary.Voxel v = new ShapeLibrary.Voxel(x, y, z);
                        if (!solid.contains(v)) holes.add(v);
                    }
                }
            }
            return holes;
        }

        public ComponentFootprintUtil.Bounds bounds() {
            return new ComponentFootprintUtil.Bounds(minX, minY, minZ, maxX, maxY, maxZ);
        }
    }

    private MassBooleanComposition() {}

    /**
     * True when nested masses declare any non-ADD/UNION operation, or the host was marked
     * for peer boolean fold ({@code mass_boolean_composition}) including UNION interlock.
     */
    public static boolean needsCsg(Component component) {
        if (component == null || component.params() == null) return false;
        if (Boolean.TRUE.equals(component.params().get("mass_boolean_composition"))
                || Boolean.TRUE.equals(component.params().get("massBooleanComposition"))) {
            Object masses = component.params().get("masses");
            return masses instanceof List<?> list && list.size() >= 2;
        }
        Object masses = component.params().get("masses");
        if (!(masses instanceof List<?> list) || list.isEmpty()) return false;
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> raw)) continue;
            Object rawOp = raw.get("operation");
            if (rawOp == null) rawOp = raw.get("op");
            if (rawOp == null) continue;
            CsgOp op = parseOpStrict(rawOp);
            if (op != CsgOp.UNION) return true;
        }
        return false;
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
     */
    public static CsgOp peerMassOperation(Component component) {
        if (component == null || component.params() == null) return null;
        Object raw = component.params().get("mass_operation");
        if (raw == null) raw = component.params().get("massOperation");
        if (raw == null) raw = component.params().get("operation");
        CsgOp op = raw == null ? null : parseOpLenient(raw);
        if (op == null) {
            Object composition = component.params().get("mass_composition");
            if (composition == null) composition = component.params().get("massComposition");
            if (composition != null) {
                String s = String.valueOf(composition).trim().toLowerCase(Locale.ROOT);
                if (s.contains("subtract") || s.contains("carve")) return CsgOp.SUBTRACT;
                if (s.contains("intersect")) return CsgOp.INTERSECT;
                if (s.contains("union") || s.contains("interlock")) return CsgOp.UNION;
            }
        }
        if (op == null
                && (truthy(component.params().get("intentional_interlock"))
                || truthy(component.params().get("intentionalInterlock")))) {
            return CsgOp.UNION;
        }
        if (op == CsgOp.UNION
                && !truthy(component.params().get("intentional_interlock"))
                && !truthy(component.params().get("intentionalInterlock"))) {
            Object explicit = component.params().get("mass_operation");
            if (explicit == null) explicit = component.params().get("massOperation");
            if (explicit == null) return null;
        }
        return op;
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
            CsgOp opA = peerMassOperation(a);
            CsgOp opB = peerMassOperation(b);
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
            budgetProbe += (long) Math.max(1, p.width()) * Math.max(1, p.depth()) * Math.max(1, p.height());
            if (budgetProbe > MAX_CSG_VOXELS) {
                throw new IllegalArgumentException(
                        "E_MASS_CSG_BUDGET: composite mass exceeds " + MAX_CSG_VOXELS + " voxel probe");
            }
        }

        Set<ShapeLibrary.Voxel> acc = new HashSet<>();
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
                    // Subtract before any seed is a no-op; after seed removes voxels (may empty).
                    placed.forEach(acc::remove);
                    // Do not clear seeded — empty after subtract is a legal finished state.
                }
                case INTERSECT -> {
                    if (!seeded) {
                        // First INTERSECT seeds the accumulator (ShapeLibrary semantics).
                        acc.clear();
                        acc.addAll(placed);
                        seeded = true;
                    } else {
                        // Empty acc after seed is a finished empty — never re-add placed (P0-01).
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
        return fromVoxels(new ArrayList<>(acc), sourceOwnerId);
    }

    public static CsgResult fromVoxels(List<ShapeLibrary.Voxel> voxels, String sourceOwnerId) {
        if (voxels == null || voxels.isEmpty()) {
            return new CsgResult(List.of(), 0, 0, 0, 0, 0, 0, sourceOwnerId);
        }
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (ShapeLibrary.Voxel v : voxels) {
            minX = Math.min(minX, v.x());
            minY = Math.min(minY, v.y());
            minZ = Math.min(minZ, v.z());
            maxX = Math.max(maxX, v.x() + 1);
            maxY = Math.max(maxY, v.y() + 1);
            maxZ = Math.max(maxZ, v.z() + 1);
        }
        return new CsgResult(List.copyOf(voxels), minX, minY, minZ, maxX, maxY, maxZ, sourceOwnerId);
    }

    public static CsgResult composeFromComponent(Component component) {
        String owner = componentId(component);
        List<ResolvedMassPart> resolved = ResolvedMassPart.resolve(component);
        List<CsgPart> parts = partsFromMassesParam(component);
        if (parts.isEmpty()) {
            parts = partsFromResolved(resolved);
        }
        return compose(parts, owner.isBlank() ? null : owner);
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
            // Non-box shapes are not silently coerced.
            Object shape = m.get("shape");
            if (shape == null) shape = m.get("kind");
            if (shape == null) shape = m.get("footprint");
            if (shape != null) {
                String s = String.valueOf(shape).trim().toLowerCase(Locale.ROOT);
                if (!s.isEmpty() && !"box".equals(s) && !"rect".equals(s) && !"rectangle".equals(s)
                        && !"cube".equals(s) && !"aabb".equals(s)) {
                    throw new IllegalArgumentException(
                            "E_MASS_CSG_SHAPE_UNSUPPORTED: nested mass shape=" + s
                                    + " (only box/rect supported in Phase 02.2.1)");
                }
            }
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
}
