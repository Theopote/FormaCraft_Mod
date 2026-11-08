package com.formacraft.server.compiler;

import com.formacraft.FormacraftMod;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.Vec3i;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Circular Roman temples: keep the cella as an enclosed tall body (never pavilion-clamped),
 * grow it toward the peristyle radius, and keep column shafts thinner/shorter relative to the cella.
 */
final class RomanTempleProportionRepair {
    private RomanTempleProportionRepair() {}

    private static final Pattern RADIUS = Pattern.compile("半径\\s*(?:为|=|:)?\\s*(\\d{1,2})|radius\\s*(?:of|=|:)?\\s*(\\d{1,2})",
            Pattern.CASE_INSENSITIVE);

    static LlmPlan apply(LlmPlan plan) {
        if (plan == null || plan.components() == null || plan.components().isEmpty()) {
            return plan;
        }
        String corpus = intentCorpus(plan);
        if (!looksRomanCircularTemple(corpus, plan)) {
            return plan;
        }
        Component cella = findCella(plan);
        if (cella == null || cella.dimensions() == null || cella.relativePosition() == null) {
            return plan;
        }
        int radius = parseRadius(corpus);
        if (radius <= 0) {
            radius = estimateRadiusFromColumns(plan, cella);
        }
        List<Component> columns = findColumns(plan);
        int maxColH = 0;
        for (Component col : columns) {
            if (col.dimensions() != null) {
                maxColH = Math.max(maxColH, col.dimensions().height());
            }
        }

        List<Component> out = new ArrayList<>(plan.components().size());
        boolean changed = false;
        Dimensions cellaDims = cella.dimensions();
        int targetSpan = cellaDims.width();
        if (radius > 0) {
            // Cella diameter ≈ 1.2× radius (peristyle sits outside), clamp into foundation.
            targetSpan = Math.max(cellaDims.width(), Math.min(radius * 2 - 4, Math.max(14, (int) Math.round(radius * 1.35))));
        }
        int targetHeight = Math.max(cellaDims.height(), Math.max(10, maxColH > 0 ? maxColH : cellaDims.height()));
        // Prefer cella at least as tall as columns; if columns dwarf it, raise cella.
        if (maxColH > cellaDims.height()) {
            targetHeight = Math.max(targetHeight, maxColH);
        }

        Vec3i cellaPos = cella.relativePosition();
        int dx = 0;
        int dz = 0;
        if (targetSpan != cellaDims.width() || targetSpan != cellaDims.depth()) {
            // Keep center when authoring used center anchors / symmetric offsets.
            int oldSpan = Math.max(cellaDims.width(), cellaDims.depth());
            dx = (oldSpan - targetSpan) / 2;
            dz = dx;
        }

        for (Component c : plan.components()) {
            if (c == null) {
                out.add(null);
                continue;
            }
            if (isSameId(c, cella)) {
                Map<String, Object> params = c.params() == null ? new HashMap<>() : new HashMap<>(c.params());
                // Never leave open-frame / pavilion clamps on a cella.
                params.put("open", false);
                params.remove("open_sides");
                params.remove("hollow");
                if ("none".equalsIgnoreCase(String.valueOf(params.get("wall_type")))) {
                    params.remove("wall_type");
                }
                if ("none".equalsIgnoreCase(String.valueOf(params.get("wall_style")))) {
                    params.remove("wall_style");
                }
                params.put("suppress_windows", false);
                params.putIfAbsent("shape", "circle");
                params.put("diameter", targetSpan);
                params.put("height", targetHeight);
                params.put("roman_temple_proportion_repaired", true);
                Dimensions nextDims = new Dimensions(targetSpan, targetSpan, targetHeight);
                Vec3i nextPos = new Vec3i(cellaPos.x() + dx, cellaPos.y(), cellaPos.z() + dz);
                out.add(new Component(c.componentType(), c.slotId(), nextPos, nextDims, c.features(), params));
                changed = true;
                continue;
            }
            if (isColumn(c) && c.dimensions() != null && c.relativePosition() != null) {
                Dimensions d = c.dimensions();
                int colH = d.height();
                // Columns should not exceed the cella body; leave a short capital above the wall line only.
                int targetColH = Math.min(colH, Math.max(8, targetHeight));
                int cross = Math.min(d.width(), d.depth());
                // 1×1 shafts look oversized vs a short cella; keep 1×1 but shorten when needed.
                if (targetColH != colH || cross > 1 && targetSpan >= 14) {
                    Map<String, Object> params = c.params() == null ? new HashMap<>() : new HashMap<>(c.params());
                    params.put("roman_column_proportion_repaired", true);
                    Dimensions next = new Dimensions(Math.min(cross, 1), Math.min(cross, 1), targetColH);
                    if (cross <= 1) {
                        next = new Dimensions(d.width(), d.depth(), targetColH);
                    }
                    out.add(new Component(c.componentType(), c.slotId(), c.relativePosition(), next, c.features(), params));
                    changed = true;
                    continue;
                }
            }
            // Hosted dome / entablature sitting on the old cella top — nudge Y with new height.
            if (hostedBy(c, cella) && c.relativePosition() != null && cella.dimensions() != null) {
                int oldTop = cellaPos.y() + cella.dimensions().height();
                int newTop = cellaPos.y() + targetHeight;
                if (c.relativePosition().y() >= oldTop - 1 && newTop != oldTop) {
                    Vec3i p = c.relativePosition();
                    out.add(new Component(
                            c.componentType(), c.slotId(),
                            new Vec3i(p.x() + dx, p.y() + (newTop - oldTop), p.z() + dz),
                            c.dimensions(), c.features(), c.params()));
                    changed = true;
                    continue;
                }
            }
            out.add(c);
        }
        if (!changed) {
            return plan;
        }
        FormacraftMod.LOGGER.info(
                "RomanTempleProportionRepair: cella {}x{}x{} → {}x{}x{} (radius≈{}, columns={})",
                cellaDims.width(), cellaDims.depth(), cellaDims.height(),
                targetSpan, targetSpan, targetHeight, radius, columns.size());
        return PlanComponentRewrite.withComponents(plan, out);
    }

    private static boolean looksRomanCircularTemple(String corpus, LlmPlan plan) {
        if (corpus.contains("罗马") || corpus.contains("roman") || corpus.contains("classical_grecoroman")
                || (plan.styleProfile() != null && plan.styleProfile().toLowerCase(Locale.ROOT).contains("grecoroman"))) {
            return corpus.contains("神殿") || corpus.contains("temple") || corpus.contains("cella")
                    || corpus.contains("圆形") || corpus.contains("circular") || corpus.contains("peristyle")
                    || findCella(plan) != null;
        }
        return findCella(plan) != null && !findColumns(plan).isEmpty();
    }

    private static Component findCella(LlmPlan plan) {
        Component best = null;
        for (Component c : plan.components()) {
            if (c == null || c.params() == null) continue;
            String id = String.valueOf(c.params().getOrDefault("component_id", "")).toLowerCase(Locale.ROOT);
            String type = c.componentType() == null ? "" : c.componentType().toUpperCase(Locale.ROOT);
            if (!(type.startsWith("MASS") || "STRUCTURE".equals(type))) continue;
            if (id.contains("cella") || id.contains("naos") || id.contains("神殿主体")) {
                return c;
            }
            if (best == null && (id.contains("temple") || id.contains("main")) && !isColumn(c)) {
                best = c;
            }
        }
        return best;
    }

    private static List<Component> findColumns(LlmPlan plan) {
        List<Component> cols = new ArrayList<>();
        for (Component c : plan.components()) {
            if (isColumn(c)) cols.add(c);
        }
        return cols;
    }

    private static boolean isColumn(Component c) {
        if (c == null) return false;
        String id = "";
        if (c.params() != null && c.params().get("component_id") != null) {
            id = String.valueOf(c.params().get("component_id")).toLowerCase(Locale.ROOT);
        }
        if (id.contains("column") || id.contains("peristyle") || id.contains("pillar") || id.contains("柱")) {
            return true;
        }
        if (c.features() != null) {
            for (String f : c.features()) {
                if (f == null) continue;
                String lower = f.toLowerCase(Locale.ROOT);
                if (lower.contains("peristyle") || lower.contains("quartz_column") || lower.contains("corinthian")) {
                    return true;
                }
            }
        }
        String type = c.componentType() == null ? "" : c.componentType().toUpperCase(Locale.ROOT);
        return "MASS_ANCILLARY".equals(type) && c.dimensions() != null
                && c.dimensions().width() <= 2 && c.dimensions().depth() <= 2
                && c.dimensions().height() >= 6;
    }

    private static boolean isSameId(Component a, Component b) {
        if (a == b) return true;
        if (a == null || b == null || a.params() == null || b.params() == null) return false;
        Object ai = a.params().get("component_id");
        Object bi = b.params().get("component_id");
        return ai != null && ai.equals(bi);
    }

    private static boolean hostedBy(Component c, Component host) {
        if (c == null || host == null || c.params() == null || host.params() == null) return false;
        Object hostId = host.params().get("component_id");
        if (hostId == null) return false;
        Object bound = c.params().get("host_id");
        if (bound == null) bound = c.params().get("hostId");
        return hostId.equals(bound);
    }

    private static int parseRadius(String corpus) {
        Matcher m = RADIUS.matcher(corpus);
        if (m.find()) {
            for (int g = 1; g <= m.groupCount(); g++) {
                if (m.group(g) != null) {
                    try {
                        return Integer.parseInt(m.group(g));
                    } catch (NumberFormatException ignored) {
                        // continue
                    }
                }
            }
        }
        return 0;
    }

    private static int estimateRadiusFromColumns(LlmPlan plan, Component cella) {
        int maxR = 0;
        Vec3i origin = cella.relativePosition();
        if (origin == null) return 0;
        int cx = origin.x() + cella.dimensions().width() / 2;
        int cz = origin.z() + cella.dimensions().depth() / 2;
        for (Component col : findColumns(plan)) {
            if (col.relativePosition() == null) continue;
            int dx = col.relativePosition().x() - cx;
            int dz = col.relativePosition().z() - cz;
            maxR = Math.max(maxR, (int) Math.round(Math.hypot(dx, dz)));
        }
        return maxR;
    }

    private static String intentCorpus(LlmPlan plan) {
        StringBuilder sb = new StringBuilder();
        if (plan.planMeta() != null) {
            Object ri = plan.planMeta().get("request_intent_text");
            if (ri != null) sb.append(ri).append(' ');
            Object intent = plan.planMeta().get("request_intent");
            if (intent instanceof Map<?, ?> m && m.get("original_text") != null) {
                sb.append(m.get("original_text")).append(' ');
            }
        }
        if (plan.styleProfile() != null) sb.append(plan.styleProfile()).append(' ');
        if (plan.playerFidelityNoticeZh() != null) sb.append(plan.playerFidelityNoticeZh()).append(' ');
        if (plan.distinguishingFeatures() != null) {
            for (String f : plan.distinguishingFeatures()) {
                if (f != null) sb.append(f).append(' ');
            }
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }
}
