package com.formacraft.client.preview;

import com.formacraft.common.json.JsonUtil;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * SkeletonPreviewState:
 * Client-side cache for J-layer skeleton layout preview.
 *
 * Data source: PreviewSkeletonPayload (origin + skeletonLayout JSON).
 */
public final class SkeletonPreviewState {
    private SkeletonPreviewState() {}

    public static final class Visual {
        public final Box box;          // world coords
        public final float r, g, b, a; // color

        public Visual(Box box, float r, float g, float b, float a) {
            this.box = box;
            this.r = r;
            this.g = g;
            this.b = b;
            this.a = a;
        }
    }

    private static List<Visual> visuals = new ArrayList<>();
    private static boolean active = false;

    public static boolean isActive() {
        return active && visuals != null && !visuals.isEmpty();
    }

    public static List<Visual> getVisuals() {
        return visuals != null ? visuals : Collections.emptyList();
    }

    public static void clear() {
        if (visuals != null) visuals.clear();
        visuals = new ArrayList<>();
        active = false;
    }

    @SuppressWarnings("unchecked")
    public static void setFromJson(String json) {
        clear();
        if (json == null || json.isBlank()) return;
        try {
            Map<String, Object> root = (Map<String, Object>) JsonUtil.get().fromJson(json, Map.class);
            if (root == null) return;
            Map<String, Object> originM = (Map<String, Object>) root.get("origin");
            Map<String, Object> layout = (Map<String, Object>) root.get("skeletonLayout");
            if (originM == null || layout == null) return;

            int ox = intOr(originM.get("x"), 0);
            int oy = intOr(originM.get("y"), 0);
            int oz = intOr(originM.get("z"), 0);
            BlockPos origin = new BlockPos(ox, oy, oz);

            Object ss = layout.get("skeletons");
            if (!(ss instanceof List<?> skeletons)) return;

            List<Visual> out = new ArrayList<>();
            int budget = 1200; // avoid too many boxes

            for (Object o : skeletons) {
                if (!(o instanceof Map<?, ?> m0)) continue;
                Map<String, Object> m = (Map<String, Object>) m0;

                String zoneType = str(m.get("zoneType")).toUpperCase(Locale.ROOT);
                String shape = str(m.get("shape")).toUpperCase(Locale.ROOT);
                Map<String, Object> a0 = (Map<String, Object>) m.get("anchor");
                if (a0 == null) continue;

                int ax = intOr(a0.get("x"), 0);
                int ay = intOr(a0.get("y"), 0);
                int az = intOr(a0.get("z"), 0);
                BlockPos anchor = origin.add(ax, ay, az);

                float[] c = colorForZoneType(zoneType);
                float r = c[0], g = c[1], b = c[2], a = c[3];

                if ("RECTANGLE".equals(shape)) {
                    int w = intOr(m.get("width"), 12);
                    int d = intOr(m.get("depth"), 10);
                    Box box = new Box(anchor.getX(), anchor.getY(), anchor.getZ(),
                            anchor.getX() + w, anchor.getY() + 1, anchor.getZ() + d).expand(0.02);
                    out.add(new Visual(box, r, g, b, a));
                    budget--;
                } else if ("CIRCLE".equals(shape)) {
                    int radius = intOr(m.get("radius"), 10);
                    budget = addCircleRing(out, anchor, radius, r, g, b, a, budget);
                } else if ("POINT".equals(shape)) {
                    Box box = new Box(anchor).expand(0.30);
                    out.add(new Visual(box, r, g, b, 0.95f));
                    budget--;
                } else if ("LINEAR".equals(shape)) {
                    Object pts0 = m.get("points");
                    if (pts0 instanceof List<?> pts && pts.size() >= 2) {
                        Object pA0 = pts.get(0);
                        Object pB0 = pts.get(pts.size() - 1);
                        if (pA0 instanceof Map<?, ?> && pB0 instanceof Map<?, ?>) {
                            Map<String, Object> pA = (Map<String, Object>) pA0;
                            Map<String, Object> pB = (Map<String, Object>) pB0;
                            BlockPos aPos = origin.add(intOr(pA.get("x"), 0), intOr(pA.get("y"), 0), intOr(pA.get("z"), 0));
                            BlockPos bPos = origin.add(intOr(pB.get("x"), 0), intOr(pB.get("y"), 0), intOr(pB.get("z"), 0));
                            budget = addLine(out, aPos, bPos, r, g, b, a, budget);
                        }
                    } else {
                        // fallback: show a point marker at anchor
                        Box box = new Box(anchor).expand(0.25);
                        out.add(new Visual(box, r, g, b, a));
                        budget--;
                    }
                } else {
                    // POLYGON or unknown: draw a small marker at anchor
                    Box box = new Box(anchor).expand(0.25);
                    out.add(new Visual(box, r, g, b, a));
                    budget--;
                }

                if (budget <= 0) break;
            }

            visuals = out;
            active = !out.isEmpty();
        } catch (Exception ignored) {
            clear();
        }
    }

    private static int addCircleRing(List<Visual> out, BlockPos center, int radius,
                                     float r, float g, float b, float a, int budget) {
        int rr = Math.max(2, Math.min(64, radius));
        int samples = Math.max(16, Math.min(96, rr * 6));
        double cy = center.getY();
        for (int i = 0; i < samples && budget > 0; i++) {
            double ang = (i / (double) samples) * Math.PI * 2.0;
            int x = center.getX() + (int) Math.round(Math.cos(ang) * rr);
            int z = center.getZ() + (int) Math.round(Math.sin(ang) * rr);
            Box box = new Box(x, cy, z, x + 1, cy + 1, z + 1).expand(0.02);
            out.add(new Visual(box, r, g, b, a));
            budget--;
        }
        return budget;
    }

    private static int addLine(List<Visual> out, BlockPos aPos, BlockPos bPos,
                               float r, float g, float b, float a, int budget) {
        int x0 = aPos.getX();
        int z0 = aPos.getZ();
        int x1 = bPos.getX();
        int z1 = bPos.getZ();
        int y = aPos.getY();

        int dx = Math.abs(x1 - x0);
        int dz = Math.abs(z1 - z0);
        int sx = x0 < x1 ? 1 : -1;
        int sz = z0 < z1 ? 1 : -1;
        int err = dx - dz;

        int steps = 0;
        int maxSteps = 300; // hard cap
        while (budget > 0 && steps < maxSteps) {
            Box box = new Box(x0, y, z0, x0 + 1, y + 1, z0 + 1).expand(0.02);
            out.add(new Visual(box, r, g, b, a));
            budget--;
            steps++;

            if (x0 == x1 && z0 == z1) break;
            int e2 = 2 * err;
            if (e2 > -dz) { err -= dz; x0 += sx; }
            if (e2 < dx) { err += dx; z0 += sz; }
        }
        return budget;
    }

    private static float[] colorForZoneType(String zoneTypeUpper) {
        String t = zoneTypeUpper != null ? zoneTypeUpper.trim().toUpperCase(Locale.ROOT) : "";
        return switch (t) {
            case "CORE" -> new float[]{1.00f, 0.85f, 0.20f, 0.90f};          // gold
            case "PUBLIC" -> new float[]{0.20f, 0.95f, 0.45f, 0.85f};        // green
            case "SEMI_PUBLIC" -> new float[]{0.20f, 0.90f, 1.00f, 0.80f};   // cyan
            case "PRIVATE" -> new float[]{0.45f, 0.55f, 1.00f, 0.85f};       // blue
            case "SERVICE" -> new float[]{1.00f, 0.55f, 0.20f, 0.85f};       // orange
            case "TRANSITION" -> new float[]{0.95f, 0.25f, 1.00f, 0.90f};    // magenta
            case "CIRCULATION" -> new float[]{0.95f, 0.95f, 0.95f, 0.75f};   // white
            case "LANDSCAPE" -> new float[]{0.40f, 0.80f, 0.35f, 0.70f};     // muted green
            default -> new float[]{0.85f, 0.85f, 0.85f, 0.70f};
        };
    }

    private static int intOr(Object v, int def) {
        if (v == null) return def;
        try {
            if (v instanceof Number n) return n.intValue();
            String s = String.valueOf(v).trim();
            if (s.isEmpty()) return def;
            return Integer.parseInt(s);
        } catch (Exception ignored) {
            return def;
        }
    }

    private static String str(Object v) {
        if (v == null) return "";
        String s = String.valueOf(v).trim();
        return s != null ? s : "";
    }
}


