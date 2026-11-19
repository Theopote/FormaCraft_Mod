package com.formacraft.server.compiler;

import com.formacraft.common.llm.dto.CapabilityGap;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.typology.UserConstraintContract;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Re-check sealed stacked envelopes against <em>per-family</em> server targets.
 * <p>
 * Modes are mutually exclusive:
 * <ul>
 *   <li>{@code shaft} — only MASS tiers must equal the target</li>
 *   <li>{@code building} — shaft + roofs/finials must equal the target (shaft alone may be shorter)</li>
 * </ul>
 * Authority is the server {@code plan_meta} record (family, mass_ids, target, mode), merged with
 * sealed component stamps. Classification: HARD_CONSTRAINT geometry integrity.
 */
final class StackedEnvelopeIntegrityValidator {
    private StackedEnvelopeIntegrityValidator() {}

    static Optional<CapabilityGap> check(LlmPlan plan, List<Component> components) {
        if (plan == null || components == null || components.isEmpty()) return Optional.empty();

        Map<String, FamilyTarget> targets = mergeTargets(plan, components);
        if (targets.isEmpty()) return Optional.empty();

        Map<String, List<Component>> shaftByFamily = new HashMap<>();
        Map<String, List<Component>> crownByFamily = new HashMap<>();
        Map<String, Set<String>> presentMassIds = new HashMap<>();
        for (Component c : components) {
            if (c == null || c.relativePosition() == null || c.dimensions() == null) continue;
            String mid = BuildingHostResolver.massId(c);
            String family = familyOf(c, mid);
            if (family == null || !targets.containsKey(family)) continue;
            if (isShaftMass(c, mid)) {
                shaftByFamily.computeIfAbsent(family, k -> new ArrayList<>()).add(c);
                if (mid != null) {
                    presentMassIds.computeIfAbsent(family, k -> new LinkedHashSet<>()).add(mid);
                }
            } else if (isCrownLike(c)) {
                crownByFamily.computeIfAbsent(family, k -> new ArrayList<>()).add(c);
            }
        }

        for (Map.Entry<String, FamilyTarget> e : targets.entrySet()) {
            String family = e.getKey();
            FamilyTarget ft = e.getValue();
            int target = ft.targetHeight();
            List<Component> shaft = shaftByFamily.getOrDefault(family, List.of());
            Set<String> present = presentMassIds.getOrDefault(family, Set.of());

            // Expected members from server meta — never skip when tiers were dropped to one.
            if (ft.expectedMassIds() != null && !ft.expectedMassIds().isEmpty()) {
                for (String expected : ft.expectedMassIds()) {
                    if (!present.contains(expected)) {
                        return Optional.of(new CapabilityGap(
                                "E_STACKED_HEIGHT_UNSUPPORTED",
                                "堆叠塔楼「" + family + "」缺少预期塔层 " + expected
                                        + "（期望 " + ft.expectedMassIds().size()
                                        + " 段，现有 " + present.size() + "）",
                                "StackedEnvelopeIntegrityValidator",
                                List.of("Restore missing storeys", "Clarify stacked height")));
                    }
                }
                if (present.size() != ft.expectedMassIds().size()) {
                    Set<String> extra = new HashSet<>(present);
                    extra.removeAll(ft.expectedMassIds());
                    if (!extra.isEmpty()) {
                        return Optional.of(new CapabilityGap(
                                "E_STACKED_HEIGHT_UNSUPPORTED",
                                "堆叠塔楼「" + family + "」出现未登记塔层 " + extra,
                                "StackedEnvelopeIntegrityValidator",
                                List.of("Remove unexpected storeys")));
                    }
                }
            }

            if (shaft.isEmpty()) {
                return Optional.of(new CapabilityGap(
                        "E_STACKED_HEIGHT_UNSUPPORTED",
                        "堆叠塔楼「" + family + "」已记录高度目标 " + target + " 但塔身构件缺失",
                        "StackedEnvelopeIntegrityValidator",
                        List.of("Restore shaft masses")));
            }

            int shaftEnv = envelopeOf(shaft);
            List<Component> building = new ArrayList<>(shaft);
            building.addAll(crownByFamily.getOrDefault(family, List.of()));
            int buildingEnv = envelopeOf(building);

            if ("building".equals(ft.mode())) {
                // Building mode: only whole envelope is authoritative — shaft may be shorter.
                if (buildingEnv != target) {
                    return Optional.of(new CapabilityGap(
                            "E_STACKED_HEIGHT_UNSUPPORTED",
                            "堆叠塔楼「" + family + "」含屋顶整体包络 " + buildingEnv
                                    + " 与要求 " + target + " 不一致（主体=" + shaftEnv + "）",
                            "StackedEnvelopeIntegrityValidator",
                            List.of("Clarify shaft vs whole-building height")));
                }
            } else {
                // Shaft mode: only MASS envelope; roofs above do not fail this check.
                if (shaftEnv != target) {
                    return Optional.of(new CapabilityGap(
                            "E_STACKED_HEIGHT_UNSUPPORTED",
                            "堆叠塔楼「" + family + "」主体高度包络 " + shaftEnv
                                    + " 与该建筑要求 " + target + " 不一致",
                            "StackedEnvelopeIntegrityValidator",
                            List.of("Clarify this building's height", "Reduce floor_count/floor_height")));
                }
            }
        }
        return Optional.empty();
    }

    /** Merge plan_meta (authoritative) with sealed component stamps. */
    private static Map<String, FamilyTarget> mergeTargets(LlmPlan plan, List<Component> components) {
        Map<String, FamilyTarget> out = new LinkedHashMap<>();
        out.putAll(targetsFromPlanMeta(plan));
        Map<String, FamilyTarget> fromComps = targetsFromComponents(components);
        for (Map.Entry<String, FamilyTarget> e : fromComps.entrySet()) {
            out.merge(e.getKey(), e.getValue(), FamilyTarget::mergePreferMeta);
        }
        return out;
    }

    private static Map<String, FamilyTarget> targetsFromComponents(List<Component> components) {
        Map<String, FamilyTarget> out = new LinkedHashMap<>();
        for (Component c : components) {
            if (c == null || c.params() == null) continue;
            // Only server-sealed height stamps are trusted component targets.
            if (!UserConstraintContract.isServerVerified(c.params(), "height")
                    && !UserConstraintContract.isServerVerified(c.params(), "towerHeight")) {
                continue;
            }
            Object raw = c.params().get(RequestIntentInjector.SHAFT_ENVELOPE_TARGET_KEY);
            if (!(raw instanceof Number n) || n.intValue() <= 0) continue;
            String family = String.valueOf(c.params().getOrDefault(
                    RequestIntentInjector.SHAFT_ENVELOPE_FAMILY_KEY, ""));
            if (family.isBlank()) {
                String mid = BuildingHostResolver.massId(c);
                family = RequestIntentInjector.stackedFamilyKey(mid);
            }
            if (family == null || family.isBlank()) continue;
            String mode = String.valueOf(c.params().getOrDefault(
                    RequestIntentInjector.ENVELOPE_MODE_KEY, "shaft"));
            if (!"building".equals(mode)) mode = "shaft";
            String mid = BuildingHostResolver.massId(c);
            List<String> ids = mid == null ? List.of() : List.of(mid);
            out.merge(family, new FamilyTarget(family, n.intValue(), mode, ids, false),
                    FamilyTarget::mergePreferMeta);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, FamilyTarget> targetsFromPlanMeta(LlmPlan plan) {
        Map<String, FamilyTarget> out = new LinkedHashMap<>();
        if (plan.planMeta() == null) return out;
        Object raw = plan.planMeta().get(RequestIntentInjector.STACKED_ENVELOPE_TARGETS_META);
        if (!(raw instanceof List<?> list)) return out;
        for (Object row : list) {
            if (!(row instanceof Map<?, ?> m)) continue;
            Object fam = m.get("family");
            Object th = m.get("target_height");
            if (fam == null || !(th instanceof Number n) || n.intValue() <= 0) continue;
            String mode = m.get("mode") == null ? "shaft" : String.valueOf(m.get("mode"));
            if (!"building".equals(mode)) mode = "shaft";
            List<String> massIds = new ArrayList<>();
            Object idsRaw = m.get("mass_ids");
            if (idsRaw instanceof List<?> idList) {
                for (Object id : idList) {
                    if (id != null && !String.valueOf(id).isBlank()) {
                        massIds.add(String.valueOf(id));
                    }
                }
            }
            String family = String.valueOf(fam);
            out.put(family, new FamilyTarget(family, n.intValue(), mode, massIds, true));
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

    /**
     * @param fromMeta true when this row came from server plan_meta (authoritative).
     */
    private record FamilyTarget(
            String family,
            int targetHeight,
            String mode,
            List<String> expectedMassIds,
            boolean fromMeta
    ) {
        static FamilyTarget mergePreferMeta(FamilyTarget a, FamilyTarget b) {
            FamilyTarget primary = a.fromMeta ? a : (b.fromMeta ? b : a);
            FamilyTarget secondary = primary == a ? b : a;
            String mode = primary.fromMeta ? primary.mode : secondary.mode;
            if (primary.fromMeta && "shaft".equals(primary.mode) && "building".equals(secondary.mode)
                    && !secondary.fromMeta) {
                // Untrusted component mode must not override meta shaft.
                mode = primary.mode;
            }
            if (!primary.fromMeta && secondary.fromMeta) {
                mode = secondary.mode;
            }
            // Prefer meta mass_ids; otherwise union.
            List<String> ids;
            if (primary.fromMeta && primary.expectedMassIds != null && !primary.expectedMassIds.isEmpty()) {
                ids = primary.expectedMassIds;
            } else if (secondary.fromMeta && secondary.expectedMassIds != null
                    && !secondary.expectedMassIds.isEmpty()) {
                ids = secondary.expectedMassIds;
            } else {
                LinkedHashSet<String> union = new LinkedHashSet<>();
                if (a.expectedMassIds != null) union.addAll(a.expectedMassIds);
                if (b.expectedMassIds != null) union.addAll(b.expectedMassIds);
                ids = List.copyOf(union);
            }
            int height = primary.fromMeta ? primary.targetHeight : secondary.targetHeight;
            if (!primary.fromMeta && secondary.fromMeta) height = secondary.targetHeight;
            return new FamilyTarget(primary.family, height, mode, ids,
                    a.fromMeta || b.fromMeta);
        }
    }
}
