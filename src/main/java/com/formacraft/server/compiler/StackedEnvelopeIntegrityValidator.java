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
import java.util.Objects;
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
 * Authority is the <em>sealed</em> server {@code plan_meta} record (family, mass_ids, target, mode),
 * merged with component stamps whose envelope fields are covered by the server seal.
 * Classification: HARD_CONSTRAINT geometry integrity.
 */
final class StackedEnvelopeIntegrityValidator {
    private StackedEnvelopeIntegrityValidator() {}

    static Optional<CapabilityGap> check(LlmPlan plan, List<Component> components) {
        if (plan == null) return Optional.empty();
        List<Component> comps = components == null ? List.of() : components;

        // Trusted meta targets still require members even when the component list was wiped.
        Map<String, FamilyTarget> metaOnly = targetsFromPlanMeta(plan);
        if (comps.isEmpty() && !metaOnly.isEmpty()) {
            String family = metaOnly.keySet().iterator().next();
            return Optional.of(new CapabilityGap(
                    "E_STACKED_HEIGHT_UNSUPPORTED",
                    "堆叠塔楼「" + family + "」已记录高度目标但全部塔层构件缺失",
                    "StackedEnvelopeIntegrityValidator",
                    List.of("Restore shaft masses")));
        }
        if (comps.isEmpty()) return Optional.empty();

        Map<String, FamilyTarget> targets = mergeTargets(plan, comps);
        Optional<CapabilityGap> sealGap = diagnoseBrokenEnvelopeAuthority(plan, comps, targets);
        if (sealGap.isPresent()) return sealGap;
        if (targets.isEmpty()) return Optional.empty();

        Map<String, List<Component>> shaftByFamily = new HashMap<>();
        Map<String, List<Component>> crownByFamily = new HashMap<>();
        Map<String, List<String>> presentMassIds = new HashMap<>();
        for (Component c : comps) {
            if (c == null || c.relativePosition() == null || c.dimensions() == null) continue;
            String mid = BuildingHostResolver.massId(c);
            String family = familyOf(c, mid);
            if (family == null || !targets.containsKey(family)) continue;
            if (isShaftMass(c, mid)) {
                shaftByFamily.computeIfAbsent(family, k -> new ArrayList<>()).add(c);
                if (mid != null) {
                    presentMassIds.computeIfAbsent(family, k -> new ArrayList<>()).add(mid);
                }
            } else if (RequestIntentInjector.countsTowardBuildingEnvelope(c)) {
                crownByFamily.computeIfAbsent(family, k -> new ArrayList<>()).add(c);
            }
        }

        for (Map.Entry<String, FamilyTarget> e : targets.entrySet()) {
            String family = e.getKey();
            FamilyTarget ft = e.getValue();
            if (ft.conflict() || ft.targetHeight() <= 0) {
                return Optional.of(new CapabilityGap(
                        "E_HEIGHT_HOST_CONFLICT",
                        "堆叠塔楼「" + family + "」存在互相冲突的高度目标记录",
                        "StackedEnvelopeIntegrityValidator",
                        List.of("Clarify a single height for this tower")));
            }
            int target = ft.targetHeight();
            List<Component> shaft = shaftByFamily.getOrDefault(family, List.of());
            List<String> presentList = presentMassIds.getOrDefault(family, List.of());
            Set<String> present = new LinkedHashSet<>(presentList);

            if (presentList.size() != present.size()) {
                return Optional.of(new CapabilityGap(
                        "E_STACKED_HEIGHT_UNSUPPORTED",
                        "堆叠塔楼「" + family + "」存在重复塔层 ID（" + presentList.size()
                                + " 段构件 / " + present.size() + " 个唯一 ID）",
                        "StackedEnvelopeIntegrityValidator",
                        List.of("Remove duplicate storey components")));
            }

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
                if (buildingEnv != target) {
                    return Optional.of(new CapabilityGap(
                            "E_STACKED_HEIGHT_UNSUPPORTED",
                            "堆叠塔楼「" + family + "」含屋顶整体包络 " + buildingEnv
                                    + " 与要求 " + target + " 不一致（主体=" + shaftEnv + "）",
                            "StackedEnvelopeIntegrityValidator",
                            List.of("Clarify shaft vs whole-building height")));
                }
            } else {
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

    /** Merge sealed plan_meta (authoritative) with sealed component stamps. */
    private static Map<String, FamilyTarget> mergeTargets(LlmPlan plan, List<Component> components) {
        Map<String, FamilyTarget> out = new LinkedHashMap<>(targetsFromPlanMeta(plan));
        Map<String, FamilyTarget> fromComps = targetsFromComponents(components);
        for (Map.Entry<String, FamilyTarget> e : fromComps.entrySet()) {
            FamilyTarget prior = out.get(e.getKey());
            if (e.getValue().conflict()) {
                out.put(e.getKey(), e.getValue());
                continue;
            }
            if (prior != null && prior.conflict()) continue;
            if (prior != null && prior.targetHeight() > 0
                    && e.getValue().targetHeight() > 0
                    && prior.targetHeight() != e.getValue().targetHeight()) {
                out.put(e.getKey(), new FamilyTarget(
                        e.getKey(), -1, prior.mode(), prior.expectedMassIds(), true, true));
                continue;
            }
            // Both sealed sides disagree on mode (shaft vs building) — hard conflict.
            if (prior != null && prior.fromMeta() && !e.getValue().fromMeta()
                    && prior.targetHeight() > 0 && e.getValue().targetHeight() > 0
                    && prior.targetHeight() == e.getValue().targetHeight()
                    && !Objects.equals(prior.mode(), e.getValue().mode())) {
                out.put(e.getKey(), new FamilyTarget(
                        e.getKey(), -1, prior.mode(), prior.expectedMassIds(), true, true));
                continue;
            }
            out.merge(e.getKey(), e.getValue(), FamilyTarget::mergePreferMeta);
        }
        return out;
    }

    private static Map<String, FamilyTarget> targetsFromComponents(List<Component> components) {
        Map<String, FamilyTarget> out = new LinkedHashMap<>();
        Map<String, Integer> seenHeight = new HashMap<>();
        for (Component c : components) {
            if (c == null || c.params() == null) continue;
            // Envelope fields must themselves be covered by the server seal — height alone is not enough.
            if (!UserConstraintContract.isServerVerified(c.params(),
                    RequestIntentInjector.SHAFT_ENVELOPE_TARGET_KEY)
                    || !UserConstraintContract.isServerVerified(c.params(),
                    RequestIntentInjector.ENVELOPE_MODE_KEY)) {
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
            if (!UserConstraintContract.isServerVerified(c.params(),
                    RequestIntentInjector.SHAFT_ENVELOPE_FAMILY_KEY)
                    && c.params().containsKey(RequestIntentInjector.SHAFT_ENVELOPE_FAMILY_KEY)) {
                // Family stamped but not sealed → ignore untrusted family override; derive from id.
                String mid = BuildingHostResolver.massId(c);
                family = RequestIntentInjector.stackedFamilyKey(mid);
                if (family == null || family.isBlank()) continue;
            }
            String mode = String.valueOf(c.params().getOrDefault(
                    RequestIntentInjector.ENVELOPE_MODE_KEY, "shaft"));
            mode = UserConstraintContract.normalizeEnvelopeMode(mode);
            Integer priorH = seenHeight.putIfAbsent(family, n.intValue());
            if (priorH != null && priorH != n.intValue()) {
                out.put(family, new FamilyTarget(family, -1, mode, List.of(), false, true));
                continue;
            }
            String mid = BuildingHostResolver.massId(c);
            List<String> ids = mid == null ? List.of() : List.of(mid);
            out.merge(family, new FamilyTarget(family, n.intValue(), mode, ids, false, false),
                    FamilyTarget::mergePreferMeta);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, FamilyTarget> targetsFromPlanMeta(LlmPlan plan) {
        Map<String, FamilyTarget> out = new LinkedHashMap<>();
        if (plan.planMeta() == null) return out;
        Object raw = plan.planMeta().get(RequestIntentInjector.STACKED_ENVELOPE_TARGETS_META);
        Object seal = plan.planMeta().get(RequestIntentInjector.STACKED_ENVELOPE_TARGETS_SEAL_META);
        if (!(raw instanceof List<?> list)) return out;
        // Forged / unsigned meta rows are ignored — trusted=true alone is never enough.
        if (!UserConstraintContract.verifyStackedEnvelopeTargets(list, seal)) {
            return out;
        }
        for (Object row : list) {
            if (!(row instanceof Map<?, ?> m)) continue;
            Object fam = m.get("family");
            Object th = m.get("target_height");
            if (fam == null || !(th instanceof Number n) || n.intValue() <= 0) continue;
            String mode = m.get("mode") == null ? "shaft" : String.valueOf(m.get("mode"));
            mode = UserConstraintContract.normalizeEnvelopeMode(mode);
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
            FamilyTarget prior = out.get(family);
            if (prior != null && prior.targetHeight() != n.intValue()) {
                out.put(family, new FamilyTarget(family, -1, mode, massIds, true, true));
                continue;
            }
            out.put(family, new FamilyTarget(family, n.intValue(), mode, massIds, true, false));
        }
        return out;
    }

    private static String familyOf(Component c, String mid) {
        if (c.params() != null) {
            Object f = c.params().get(RequestIntentInjector.SHAFT_ENVELOPE_FAMILY_KEY);
            if (f != null && !String.valueOf(f).isBlank()
                    && UserConstraintContract.isServerVerified(c.params(),
                    RequestIntentInjector.SHAFT_ENVELOPE_FAMILY_KEY)) {
                return String.valueOf(f);
            }
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

    /**
     * Envelope keys without any trusted meta or component seal are a hard failure —
     * never silently skip height integrity when authority was expected.
     */
    private static Optional<CapabilityGap> diagnoseBrokenEnvelopeAuthority(
            LlmPlan plan, List<Component> components, Map<String, FamilyTarget> targets) {
        boolean hasEnvelopeKeys = false;
        for (Component c : components) {
            if (c == null || c.params() == null) continue;
            if (c.params().containsKey(RequestIntentInjector.SHAFT_ENVELOPE_TARGET_KEY)
                    || c.params().containsKey(RequestIntentInjector.ENVELOPE_MODE_KEY)) {
                hasEnvelopeKeys = true;
                break;
            }
        }
        boolean metaSealPresent = false;
        boolean metaSealOk = false;
        if (plan.planMeta() != null) {
            Object raw = plan.planMeta().get(RequestIntentInjector.STACKED_ENVELOPE_TARGETS_META);
            Object seal = plan.planMeta().get(RequestIntentInjector.STACKED_ENVELOPE_TARGETS_SEAL_META);
            if (raw instanceof List<?> list && !list.isEmpty() && seal != null) {
                metaSealPresent = true;
                metaSealOk = UserConstraintContract.verifyStackedEnvelopeTargets(list, seal);
            }
        }
        // Damaged server meta cannot be replaced by component height alone — mass_ids live only in meta.
        if (metaSealPresent && !metaSealOk) {
            return Optional.of(new CapabilityGap(
                    "E_STACKED_ENVELOPE_SEAL_INVALID",
                    "堆叠高度包络 plan_meta 签章被破坏，无法恢复可信塔层名单",
                    "StackedEnvelopeIntegrityValidator",
                    List.of("Re-run RequestIntentInjector with FormaRequest text")));
        }
        if (!targets.isEmpty()) return Optional.empty();
        if (hasEnvelopeKeys) {
            // Component-side envelope fields present but none sealed and no trusted meta.
            return Optional.of(new CapabilityGap(
                    "E_STACKED_ENVELOPE_SEAL_INVALID",
                    "堆叠高度包络字段存在但缺少服务器签章，已拒绝静默跳过完整性检查",
                    "StackedEnvelopeIntegrityValidator",
                    List.of("Re-run RequestIntentInjector with FormaRequest text")));
        }
        // Unsigned meta rows alone are ignored as external forgeries (no enforcement, no gap).
        return Optional.empty();
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

    private record FamilyTarget(
            String family,
            int targetHeight,
            String mode,
            List<String> expectedMassIds,
            boolean fromMeta,
            boolean conflict
    ) {
        static FamilyTarget mergePreferMeta(FamilyTarget a, FamilyTarget b) {
            if (a.conflict || b.conflict) {
                return new FamilyTarget(a.family, -1, a.mode, a.expectedMassIds, a.fromMeta || b.fromMeta, true);
            }
            if (a.targetHeight != b.targetHeight && a.targetHeight > 0 && b.targetHeight > 0
                    && a.fromMeta == b.fromMeta) {
                return new FamilyTarget(a.family, -1, a.mode, a.expectedMassIds, a.fromMeta, true);
            }
            // Two sealed records (meta + component) with same height but different modes.
            if (a.fromMeta != b.fromMeta && a.targetHeight > 0 && b.targetHeight > 0
                    && a.targetHeight == b.targetHeight
                    && a.mode != null && b.mode != null && !a.mode.equals(b.mode)) {
                return new FamilyTarget(a.family, -1, a.mode, a.expectedMassIds, a.fromMeta || b.fromMeta, true);
            }
            FamilyTarget primary = a.fromMeta ? a : (b.fromMeta ? b : a);
            FamilyTarget secondary = primary == a ? b : a;
            String mode = primary.fromMeta ? primary.mode : secondary.mode;
            if (!primary.fromMeta && secondary.fromMeta) {
                mode = secondary.mode;
            }
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
                    a.fromMeta || b.fromMeta, false);
        }
    }
}
