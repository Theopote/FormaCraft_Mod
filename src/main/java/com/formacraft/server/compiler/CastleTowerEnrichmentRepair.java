package com.formacraft.server.compiler;

import com.formacraft.FormacraftMod;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.Vec3i;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * LLM castles often emit bare cylindrical TOWER stubs. Promote them to watchtower /
 * spire silhouettes (taller shaft + conical / pointed crown) when the plan is castle-like.
 */
final class CastleTowerEnrichmentRepair {
    private CastleTowerEnrichmentRepair() {}

    static LlmPlan apply(LlmPlan plan) {
        if (plan == null || plan.components() == null || plan.components().isEmpty()) return plan;
        if (!looksCastle(plan)) return plan;
        Set<String> existingCrowns = new HashSet<>();
        for (Component c : plan.components()) {
            if (c == null || c.params() == null) continue;
            String type = c.componentType() == null ? "" : c.componentType().toUpperCase(Locale.ROOT);
            if (!Set.of("CROWN", "CUPOLA", "DOME", "ROOF").contains(type)) continue;
            Object host = c.params().get("host_id");
            if (host == null) host = c.params().get("hostId");
            if (host != null) existingCrowns.add(String.valueOf(host));
        }
        List<Component> out = new ArrayList<>(plan.components());
        boolean changed = false;
        int n = out.size();
        for (int i = 0; i < n; i++) {
            Component tower = out.get(i);
            if (tower == null || tower.dimensions() == null || tower.relativePosition() == null) continue;
            if (!isTower(tower)) continue;
            Component enriched = enrichTower(tower);
            if (enriched != tower) {
                out.set(i, enriched);
                changed = true;
                tower = enriched;
            }
            String towerId = componentId(tower);
            if (towerId == null || existingCrowns.contains(towerId)) continue;
            Component crown = makeSpireCrown(tower, towerId);
            out.add(crown);
            existingCrowns.add(towerId);
            changed = true;
            FormacraftMod.LOGGER.info(
                    "CastleTowerEnrichmentRepair: add spire crown for tower={}", towerId);
        }
        return changed ? PlanComponentRewrite.withComponents(plan, out) : plan;
    }

    private static Component enrichTower(Component tower) {
        Map<String, Object> params = new HashMap<>();
        if (tower.params() != null) params.putAll(tower.params());
        boolean changed = false;
        Object shape = params.get("shape");
        if (shape == null || "rectangle".equalsIgnoreCase(String.valueOf(shape))
                || "box".equalsIgnoreCase(String.valueOf(shape))) {
            params.put("shape", "circle");
            changed = true;
        }
        if (!params.containsKey("roof_type") && !params.containsKey("roofType")) {
            params.put("roof_type", "cone");
            changed = true;
        }
        params.putIfAbsent("battlement", true);
        Dimensions dims = tower.dimensions();
        int height = dims.height();
        // Corner watchtowers need a readable vertical shaft above the curtain wall.
        if (height < 10) {
            height = Math.max(10, Math.min(dims.width(), dims.depth()) * 2);
            changed = true;
        }
        List<String> features = tower.features() == null ? new ArrayList<>() : new ArrayList<>(tower.features());
        if (features.stream().noneMatch(f -> f != null && f.toLowerCase(Locale.ROOT).contains("watch"))) {
            features.add("watchtower");
            changed = true;
        }
        if (!changed) return tower;
        FormacraftMod.LOGGER.info(
                "CastleTowerEnrichmentRepair: enrich tower id={} height {}→{}",
                componentId(tower), dims.height(), height);
        return new Component(tower.componentType(), tower.slotId(), tower.relativePosition(),
                new Dimensions(dims.width(), dims.depth(), height), features, params);
    }

    private static Component makeSpireCrown(Component tower, String towerId) {
        Dimensions dims = tower.dimensions();
        Vec3i origin = ComponentPlanCompiler.resolveMassOrigin(tower);
        if (origin == null) origin = tower.relativePosition();
        int crownW = Math.max(3, Math.min(dims.width(), dims.depth()) - 2);
        if (crownW % 2 == 0) crownW = Math.max(3, crownW - 1);
        int crownH = Math.max(4, Math.min(8, dims.height() / 2));
        int x = origin.x() + Math.max(0, (dims.width() - crownW) / 2);
        int z = origin.z() + Math.max(0, (dims.depth() - crownW) / 2);
        int y = origin.y() + dims.height() - 1;
        Map<String, Object> params = new HashMap<>();
        params.put("component_id", towerId + "#spire");
        params.put("host_id", towerId);
        params.put("anchor_mode", "min_corner");
        params.put("roof_type", "cone");
        params.put("detail_kind", "spire");
        return new Component("CROWN", tower.slotId(), new Vec3i(x, y, z),
                new Dimensions(crownW, crownW, crownH),
                List.of("spire", "watchtower crown"), params);
    }

    private static boolean isTower(Component c) {
        String t = c.componentType() == null ? "" : c.componentType().trim().toUpperCase(Locale.ROOT);
        return "TOWER".equals(t) || t.startsWith("TOWER_");
    }

    private static String componentId(Component c) {
        if (c.params() == null || c.params().get("component_id") == null) return null;
        return String.valueOf(c.params().get("component_id"));
    }

    private static boolean looksCastle(LlmPlan plan) {
        if (plan.distinguishingFeatures() != null) {
            for (String f : plan.distinguishingFeatures()) {
                if (f != null && looksCastleToken(f)) return true;
            }
        }
        if (plan.playerFidelityNoticeZh() != null && looksCastleToken(plan.playerFidelityNoticeZh())) {
            return true;
        }
        int towers = 0;
        for (Component c : plan.components()) {
            if (c == null) continue;
            if (isTower(c)) towers++;
            if (c.params() != null) {
                Object id = c.params().get("component_id");
                if (id != null && looksCastleToken(String.valueOf(id))) return true;
            }
            if (looksCastleFeatures(c.features())) return true;
        }
        return towers >= 3;
    }

    private static boolean looksCastleFeatures(List<String> features) {
        if (features == null) return false;
        for (String f : features) {
            if (f != null && looksCastleToken(f)) return true;
        }
        return false;
    }

    private static boolean looksCastleToken(String raw) {
        String lower = raw.toLowerCase(Locale.ROOT);
        return lower.contains("castle") || lower.contains("keep") || lower.contains("fortress")
                || lower.contains("堡垒") || lower.contains("城堡") || lower.contains("城墙")
                || lower.contains("curtain") || lower.contains("battlement");
    }
}
