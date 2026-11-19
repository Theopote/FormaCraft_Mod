package com.formacraft.server.compiler;

import com.formacraft.common.llm.dto.CapabilityGap;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.typology.UserConstraintContract;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * After FloorLayoutOverflowRepair and other grows, re-check that each sealed stacked shaft
 * still matches <em>its own</em> player envelope target — never a single global height_blocks
 * applied to every tower in a mixed compound.
 * <p>
 * Distinguishes <b>shaft</b> envelope (MASS tiers) from <b>building</b> envelope (shaft + roofs /
 * finials). Shaft integrity is hard; building envelope is reported when roofs are present but
 * does not fail solely because a roof rises above the shaft target.
 * Classification: HARD_CONSTRAINT geometry integrity.
 */
final class StackedEnvelopeIntegrityValidator {
    private StackedEnvelopeIntegrityValidator() {}

    static Optional<CapabilityGap> check(LlmPlan plan, List<Component> components) {
        if (plan == null || components == null || components.isEmpty()) return Optional.empty();

        Map<String, Integer> targetsByFamily = targetsFromComponents(components);
        if (targetsByFamily.isEmpty()) {
            targetsByFamily = targetsFromPlanMeta(plan);
        }
        if (targetsByFamily.isEmpty()) return Optional.empty();

        Map<String, List<Component>> shaftByFamily = new HashMap<>();
        Map<String, List<Component>> crownByFamily = new HashMap<>();
        for (Component c : components) {
            if (c == null || c.relativePosition() == null || c.dimensions() == null) continue;
            String mid = BuildingHostResolver.massId(c);
            String family = familyOf(c, mid);
            if (family == null || !targetsByFamily.containsKey(family)) continue;
            if (isShaftMass(c, mid)) {
                shaftByFamily.computeIfAbsent(family, k -> new ArrayList<>()).add(c);
            } else if (isCrownLike(c)) {
                crownByFamily.computeIfAbsent(family, k -> new ArrayList<>()).add(c);
            }
        }

        for (Map.Entry<String, Integer> e : targetsByFamily.entrySet()) {
            String family = e.getKey();
            int target = e.getValue();
            List<Component> shaft = shaftByFamily.getOrDefault(family, List.of());
            if (shaft.size() < 2) continue;
            int shaftEnv = envelopeOf(shaft);
            if (shaftEnv != target) {
                return Optional.of(new CapabilityGap(
                        "E_STACKED_HEIGHT_UNSUPPORTED",
                        "堆叠塔楼「" + family + "」主体高度包络 " + shaftEnv
                                + " 与该建筑要求 " + target + " 不一致",
                        "StackedEnvelopeIntegrityValidator",
                        List.of("Clarify this building's height", "Reduce floor_count/floor_height")));
            }
            // Building envelope (shaft + roofs/finials) — informational hard check only when
            // a component explicitly opts into building-mode target equality.
            List<Component> building = new ArrayList<>(shaft);
            building.addAll(crownByFamily.getOrDefault(family, List.of()));
            int buildingEnv = envelopeOf(building);
            for (Component c : shaft) {
                if (c.params() != null && "building".equals(String.valueOf(
                        c.params().getOrDefault("_fc_envelope_mode", "shaft")))) {
                    if (buildingEnv != target) {
                        return Optional.of(new CapabilityGap(
                                "E_STACKED_HEIGHT_UNSUPPORTED",
                                "堆叠塔楼「" + family + "」含屋顶整体包络 " + buildingEnv
                                        + " 与要求 " + target + " 不一致（主体=" + shaftEnv + "）",
                                "StackedEnvelopeIntegrityValidator",
                                List.of("Clarify shaft vs whole-building height")));
                    }
                    break;
                }
            }
        }
        return Optional.empty();
    }

    private static Map<String, Integer> targetsFromComponents(List<Component> components) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Component c : components) {
            if (c == null || c.params() == null) continue;
            Object raw = c.params().get(RequestIntentInjector.SHAFT_ENVELOPE_TARGET_KEY);
            if (!(raw instanceof Number n) || n.intValue() <= 0) continue;
            String family = String.valueOf(c.params().getOrDefault(
                    RequestIntentInjector.SHAFT_ENVELOPE_FAMILY_KEY, ""));
            if (family.isBlank()) {
                String mid = BuildingHostResolver.massId(c);
                family = RequestIntentInjector.stackedFamilyKey(mid);
            }
            if (family == null || family.isBlank()) continue;
            out.putIfAbsent(family, n.intValue());
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Integer> targetsFromPlanMeta(LlmPlan plan) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (plan.planMeta() == null) return out;
        Object raw = plan.planMeta().get(RequestIntentInjector.STACKED_ENVELOPE_TARGETS_META);
        if (!(raw instanceof List<?> list)) return out;
        for (Object row : list) {
            if (!(row instanceof Map<?, ?> m)) continue;
            Object fam = m.get("family");
            Object th = m.get("target_height");
            if (fam == null || !(th instanceof Number n) || n.intValue() <= 0) continue;
            out.putIfAbsent(String.valueOf(fam), n.intValue());
        }
        return out;
    }

    private static String familyOf(Component c, String mid) {
        if (c.params() != null) {
            Object f = c.params().get(RequestIntentInjector.SHAFT_ENVELOPE_FAMILY_KEY);
            if (f != null && !String.valueOf(f).isBlank()) return String.valueOf(f);
        }
        String family = RequestIntentInjector.stackedFamilyKey(mid);
        if (family != null) return family;
        String host = hostId(c);
        return host == null ? null : RequestIntentInjector.stackedFamilyKey(host);
    }

    private static boolean isShaftMass(Component c, String mid) {
        if (mid != null && RequestIntentInjector.stackedFamilyKey(mid) != null) {
            String type = c.componentType() == null ? "" : c.componentType().toUpperCase();
            return type.startsWith("MASS") || "TOWER".equals(type) || "KEEP".equals(type);
        }
        return false;
    }

    private static boolean isCrownLike(Component c) {
        String type = c.componentType() == null ? "" : c.componentType().toUpperCase();
        if (type.startsWith("ROOF") || type.contains("CROWN") || "DECOR_DETAIL".equals(type)
                || "FINIAL".equals(type)) {
            return true;
        }
        String mid = BuildingHostResolver.massId(c);
        if (mid == null) return false;
        String lower = mid.toLowerCase();
        return lower.contains("roof") || lower.contains("finial") || lower.contains("crown")
                || lower.contains("spire");
    }

    private static String hostId(Component c) {
        if (c == null || c.params() == null) return null;
        Object host = c.params().get("host_id");
        if (host == null) host = c.params().get("hostId");
        if (host == null || String.valueOf(host).isBlank()
                || "null".equalsIgnoreCase(String.valueOf(host))) {
            return null;
        }
        return String.valueOf(host).trim();
    }

    private static int envelopeOf(List<Component> members) {
        int minY = Integer.MAX_VALUE;
        int maxTop = Integer.MIN_VALUE;
        for (Component c : members) {
            int y = c.relativePosition().y();
            int h = Math.max(1, c.dimensions().height());
            minY = Math.min(minY, y);
            maxTop = Math.max(maxTop, y + h);
        }
        return maxTop - minY;
    }
}
