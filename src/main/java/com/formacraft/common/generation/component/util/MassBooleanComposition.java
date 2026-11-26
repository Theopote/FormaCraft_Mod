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
 * Authored mass boolean composition (Phase 02.2).
 * <p>
 * Nested {@code params.masses[]} may carry {@code operation}/{@code op}:
 * {@code add|union} (default), {@code subtract|carve}, {@code intersect}.
 * Absent ops keep additive shell emit — never silent CSG.
 * Classification: GEOMETRY operators (not building-class enums).
 */
public final class MassBooleanComposition {

    /** HARD_CONSTRAINT — refuse pathological composites. */
    public static final int MAX_CSG_VOXELS = 250_000;

    public record CsgPart(CsgOp op, int offsetX, int offsetY, int offsetZ, int width, int depth, int height) {}

    public record CsgResult(List<ShapeLibrary.Voxel> voxels, int envelopeWidth, int envelopeDepth, int envelopeHeight) {}

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
            CsgOp op = parseOpLenient(raw.get("operation"));
            if (op == null) op = parseOpLenient(raw.get("op"));
            if (op != null && op != CsgOp.UNION) return true;
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
        CsgOp op = parseOpLenient(component.params().get("mass_operation"));
        if (op == null) op = parseOpLenient(component.params().get("massOperation"));
        if (op == null) op = parseOpLenient(component.params().get("operation"));
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
            // Bare union without interlock cue is ordinary additive — not peer CSG fold.
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
            CsgOp op = parseOpLenient(src == null ? null : src.get("operation"));
            if (op == null) op = parseOpLenient(src == null ? null : src.get("op"));
            if (op == null) op = CsgOp.UNION;
            Dimensions d = part.dimensions();
            Vec3i o = part.offset();
            parts.add(new CsgPart(op, o.x(), o.y(), o.z(), d.width(), d.depth(), d.height()));
        }
        return parts;
    }

    /**
     * Offset-aware CSG over axis-aligned boxes (envelope-local voxels).
     * @throws IllegalArgumentException with {@code E_MASS_CSG_*} codes on invalid / oversized input
     */
    public static CsgResult compose(List<CsgPart> parts) {
        if (parts == null || parts.isEmpty()) {
            throw new IllegalArgumentException("E_MASS_CSG_EMPTY: no mass parts for boolean composition");
        }
        int maxX = 0;
        int maxY = 0;
        int maxZ = 0;
        for (CsgPart p : parts) {
            maxX = Math.max(maxX, p.offsetX() + Math.max(1, p.width()));
            maxY = Math.max(maxY, p.offsetY() + Math.max(1, p.height()));
            maxZ = Math.max(maxZ, p.offsetZ() + Math.max(1, p.depth()));
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
                case SUBTRACT -> placed.forEach(acc::remove);
                case INTERSECT -> {
                    if (!seeded || acc.isEmpty()) {
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
        return new CsgResult(new ArrayList<>(acc), maxX, maxZ, maxY);
    }

    public static CsgResult composeFromComponent(Component component) {
        List<ResolvedMassPart> resolved = ResolvedMassPart.resolve(component);
        // When CSG is requested, prefer raw masses[] (include parent body as first UNION if present).
        List<CsgPart> parts = partsFromMassesParam(component);
        if (parts.isEmpty()) {
            parts = partsFromResolved(resolved);
        }
        return compose(parts);
    }

    @SuppressWarnings("unchecked")
    private static List<CsgPart> partsFromMassesParam(Component component) {
        if (component == null || component.params() == null) return List.of();
        Object masses = component.params().get("masses");
        if (!(masses instanceof List<?> list) || list.isEmpty()) return List.of();
        Dimensions envelope = component.dimensions();
        List<CsgPart> parts = new ArrayList<>();
        // Envelope body as first UNION when masses[] describe children only without a y=0 full cover.
        boolean hasBase = false;
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> raw)) continue;
            Map<String, Object> m = (Map<String, Object>) raw;
            CsgOp op = parseOpLenient(m.get("operation"));
            if (op == null) op = parseOpLenient(m.get("op"));
            if (op == null) op = CsgOp.UNION;
            Dimensions dims = readDims(m, envelope);
            Vec3i offset = readOffset(m);
            if (offset.y() == 0 && op == CsgOp.UNION) hasBase = true;
            parts.add(new CsgPart(op, offset.x(), offset.y(), offset.z(),
                    dims.width(), dims.depth(), dims.height()));
        }
        if (!parts.isEmpty() && !hasBase && envelope != null
                && parts.stream().anyMatch(p -> p.op() != CsgOp.UNION)) {
            // Keep envelope as additive host when only carve/intersect children authored.
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
