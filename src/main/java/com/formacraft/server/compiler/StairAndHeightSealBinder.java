package com.formacraft.server.compiler;

import com.formacraft.FormacraftMod;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.typology.UserConstraintContract;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Package-private stair-tread and height-seal binding helpers carved from RequestIntentInjector.
 */
final class StairAndHeightSealBinder {
    private StairAndHeightSealBinder() {}

    record StairSealTarget(int componentIndex, Integer opIndex, String blockId) {}

    record StairTreadBindResult(
            List<StairSealTarget> targets,
            List<Map<String, Object>> diagnostics,
            boolean capabilityGap
    ) {
        StairTreadBindResult {
            targets = targets == null ? List.of() : List.copyOf(targets);
            diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        }
    }

    record StairTargetRef(int componentIndex, Integer opIndex) {}

    static StairTreadBindResult resolveStairTreadBindings(
            LlmPlan plan,
            List<com.formacraft.common.typology.UserConstraintContract.StairTreadBinding> bindings,
            boolean authorizeFromText
    ) {
        if (!authorizeFromText || bindings == null || bindings.isEmpty()) {
            return new StairTreadBindResult(List.of(), List.of(), false);
        }
        LinkedHashSet<String> materials = new LinkedHashSet<>();
        for (var b : bindings) {
            if (b != null && b.blockId() != null && !b.blockId().isBlank()) {
                materials.add(b.blockId().trim());
            }
        }
        List<Map<String, Object>> diags = new ArrayList<>();
        if (materials.size() != 1) {
            Map<String, Object> detail = new HashMap<>();
            detail.put("code", "E_STAIR_TREAD_MATERIAL_CONFLICT");
            detail.put("materials", List.copyOf(materials));
            detail.put("message", "conflicting explicit stair tread materials in the request");
            diags.add(detail);
            return new StairTreadBindResult(List.of(), diags, true);
        }
        String blockId = materials.getFirst();
        List<StairTargetRef> targets = collectStairTargets(plan);
        if (targets.isEmpty()) {
            Map<String, Object> detail = new HashMap<>();
            detail.put("code", "E_STAIR_TREAD_TARGET_MISSING");
            detail.put("blockId", blockId);
            detail.put("message", "explicit stair tread material has no stair component or STAIR_SYSTEM op");
            diags.add(detail);
            return new StairTreadBindResult(List.of(), diags, true);
        }
        if (targets.size() != 1) {
            Map<String, Object> detail = new HashMap<>();
            detail.put("code", "E_STAIR_TREAD_TARGET_AMBIGUOUS");
            detail.put("blockId", blockId);
            detail.put("targetCount", targets.size());
            detail.put("message", "explicit stair tread material cannot uniquely bind a stair target");
            diags.add(detail);
            return new StairTreadBindResult(List.of(), diags, true);
        }
        StairTargetRef only = targets.getFirst();
        return new StairTreadBindResult(
                List.of(new StairSealTarget(only.componentIndex(), only.opIndex(), blockId)),
                List.of(),
                false);
    }

    static List<StairTargetRef> collectStairTargets(LlmPlan plan) {
        List<StairTargetRef> out = new ArrayList<>();
        if (plan == null || plan.components() == null) return out;
        List<Component> comps = plan.components();
        for (int i = 0; i < comps.size(); i++) {
            Component c = comps.get(i);
            if (c == null) continue;
            if (com.formacraft.common.palette.dynamic.StairComponentIdentity.isStairComponent(c)) {
                out.add(new StairTargetRef(i, null));
                continue;
            }
            if (c.componentType() == null
                    || !"ASSEMBLY".equalsIgnoreCase(c.componentType().trim())
                    || c.params() == null) {
                continue;
            }
            Object assembly = c.params().get("assembly");
            if (!(assembly instanceof Map<?, ?> asm)) continue;
            Object opsObj = asm.get("ops");
            if (!(opsObj instanceof List<?> ops)) continue;
            for (int oi = 0; oi < ops.size(); oi++) {
                Object op = ops.get(oi);
                if (!(op instanceof Map<?, ?> om)) continue;
                String kind = Objects.toString(om.get("op"), Objects.toString(om.get("type"), ""))
                        .trim().toUpperCase(Locale.ROOT);
                if ("STAIR_SYSTEM".equals(kind) || "STAIRS_SYSTEM".equals(kind) || "STAIRCASE".equals(kind)) {
                    out.add(new StairTargetRef(i, oi));
                }
            }
        }
        return out;
    }

    static LlmPlan applyStairTreadSeals(
            LlmPlan plan, StairTreadBindResult stairResult, boolean authorizeFromText) {
        if (!authorizeFromText || plan == null || stairResult == null || stairResult.targets().isEmpty()) {
            return plan;
        }
        List<Component> comps = plan.components();
        if (comps == null || comps.isEmpty()) return plan;
        List<Component> out = new ArrayList<>(comps);
        boolean changed = false;
        for (StairSealTarget target : stairResult.targets()) {
            if (target == null || target.blockId() == null || target.blockId().isBlank()) continue;
            int ci = target.componentIndex();
            if (ci < 0 || ci >= out.size()) continue;
            Component c = out.get(ci);
            if (c == null) continue;
            Map<String, Object> params = c.params() == null
                    ? new HashMap<>()
                    : new HashMap<>(c.params());
            if (target.opIndex() == null) {
                com.formacraft.common.typology.UserConstraintContract
                        .applyVerifiedStairTread(params, target.blockId());
                out.set(ci, new Component(
                        c.componentType(), c.slotId(), c.relativePosition(),
                        c.dimensions(), c.features(), params));
                changed = true;
                continue;
            }
            Object assemblyObj = params.get("assembly");
            if (!(assemblyObj instanceof Map<?, ?> asmRaw)) continue;
            Map<String, Object> assembly = deepCopyMap(asmRaw);
            Object opsObj = assembly.get("ops");
            if (!(opsObj instanceof List<?> opsRaw)) continue;
            List<Object> ops = new ArrayList<>(opsRaw.size());
            for (Object op : opsRaw) {
                if (op instanceof Map<?, ?> om) ops.add(deepCopyMap(om));
                else ops.add(op);
            }
            int oi = target.opIndex();
            if (oi < 0 || oi >= ops.size() || !(ops.get(oi) instanceof Map<?, ?>)) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> op = (Map<String, Object>) ops.get(oi);
            com.formacraft.common.typology.UserConstraintContract
                    .applyVerifiedStairTread(op, target.blockId());
            ops.set(oi, op);
            assembly.put("ops", ops);
            params.put("assembly", assembly);
            out.set(ci, new Component(
                    c.componentType(), c.slotId(), c.relativePosition(),
                    c.dimensions(), c.features(), params));
            changed = true;
        }
        return changed ? PlanComponentRewrite.withComponents(plan, out) : plan;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> deepCopyMap(Map<?, ?> raw) {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : raw.entrySet()) {
            if (e.getKey() == null) continue;
            Object v = e.getValue();
            if (v instanceof Map<?, ?> nested) {
                copy.put(String.valueOf(e.getKey()), deepCopyMap(nested));
            } else if (v instanceof List<?> list) {
                List<Object> copied = new ArrayList<>(list.size());
                for (Object item : list) {
                    if (item instanceof Map<?, ?> nm) copied.add(deepCopyMap(nm));
                    else copied.add(item);
                }
                copy.put(String.valueOf(e.getKey()), copied);
            } else {
                copy.put(String.valueOf(e.getKey()), v);
            }
        }
        return copy;
    }
    record HeightBindResult(
            Map<String, Integer> byMassId,
            List<StackedHeightEnvelopeAllocator.StackedHeightEnvelope> envelopes,
            List<Map<String, Object>> diagnostics,
            boolean capabilityGap
    ) {
        HeightBindResult(Map<String, Integer> byMassId,
                                 List<Map<String, Object>> diagnostics,
                                 boolean capabilityGap) {
            this(byMassId, List.of(), diagnostics, capabilityGap);
        }
    }

    /**
     * Map mass ids to player height values by unique role/ordinal host.
     * Stacked storeys receive an envelope binding, not per-tier height copies.
     * Classification: HARD_CONSTRAINT — no area/order guess for unmatched pavilion cues.
     */
    static HeightBindResult resolveHeightBindings(
            List<Component> hosts,
            String primary,
            boolean multi,
            List<com.formacraft.common.typology.UserConstraintContract.HeightBinding> bindings
    ) {
        Map<String, Integer> out = new HashMap<>();
        List<StackedHeightEnvelopeAllocator.StackedHeightEnvelope> envelopes = new ArrayList<>();
        Map<String, Integer> envelopeHeightByFamily = new HashMap<>();
        Map<String, String> envelopeModeByFamily = new HashMap<>();
        Map<String, Boolean> envelopeModeExplicitByFamily = new HashMap<>();
        List<Map<String, Object>> diagnostics = new ArrayList<>();
        boolean gap = false;
        if (bindings == null || bindings.isEmpty()) {
            return new HeightBindResult(out, envelopes, diagnostics, false);
        }
        if (hosts == null || hosts.isEmpty()) {
            for (var b : bindings) {
                if (b == null || b.blocks() <= 0) continue;
                gap = true;
                Map<String, Object> detail = new HashMap<>();
                detail.put("code", "E_DESIGN_HOST_MISSING");
                detail.put("blocks", b.blocks());
                detail.put("roleHint", b.roleHint() == null ? "" : b.roleHint());
                detail.put("message", "explicit height cue with no design-constraint host");
                diagnostics.add(detail);
            }
            return new HeightBindResult(out, envelopes, diagnostics, gap);
        }
        for (var b : bindings) {
            if (b == null || b.blocks() <= 0) continue;
            String hint = b.roleHint();
            String mode = b.envelopeMode() == null ? "shaft" : b.envelopeMode();
            boolean modeExplicit = b.modeExplicit();
            String target = null;
            if (hint == null || hint.isBlank()) {
                // Unscoped: one building entity (incl. stacked storeys) only.
                if (!multi || hosts.size() == 1) {
                    List<String> stackedIds = DesignOmitBindingResolver.stackedFamilyMassIds(hosts);
                    if (stackedIds.size() > 1) {
                        if (!StackedHeightEnvelopeAllocator.registerStackedEnvelope(
                                envelopes, envelopeHeightByFamily, envelopeModeByFamily,
                                envelopeModeExplicitByFamily,
                                diagnostics, stackedIds, b.blocks(), mode, modeExplicit)) {
                            gap = true;
                        }
                        continue;
                    }
                    target = primary;
                    if (target == null || !BuildingHostResolver.isKnownDesignHostId(hosts, target)) {
                        target = BuildingHostResolver.massId(hosts.getFirst());
                    }
                } else {
                    gap = true;
                    diagnostics.add(Map.of(
                            "code", "E_HEIGHT_HOST_UNSCOPED",
                            "blocks", b.blocks(),
                            "message", "multi-mass height cue without role/ordinal scope"));
                    continue;
                }
            } else if (hint.startsWith("building_")) {
                target = DesignOmitBindingResolver.resolveOrdinalOmitHost(hosts, primary, hint);
            } else {
                // namedKeys first (wing≠pavilion); clause text keeps 西厢房 side-local.
                List<String> namedHits = DesignOmitBindingResolver.resolveNamedOrRoleHostIds(
                        hosts, hint, b.namedKeys(), b.sourceText());
                if (namedHits.isEmpty()) {
                    gap = true;
                    diagnostics.add(Map.of(
                            "code", "E_HEIGHT_HOST_TIE",
                            "blocks", b.blocks(),
                            "roleHint", hint == null ? "" : hint,
                            "message", "height host role score is not unique or missing"));
                    continue;
                }
                if (DesignOmitBindingResolver.isStackedFamilyIds(namedHits)) {
                    for (String id : namedHits) {
                        if (!BuildingHostResolver.isKnownDesignHostId(hosts, id)) {
                            gap = true;
                            Map<String, Object> detail = new HashMap<>();
                            detail.put("code", "E_HEIGHT_HOST_MISSING");
                            detail.put("blocks", b.blocks());
                            detail.put("roleHint", hint == null ? "" : hint);
                            detail.put("target", id);
                            detail.put("message", "height target is not a design-constraint host");
                            diagnostics.add(detail);
                        }
                    }
                    if (!gap) {
                        if (!StackedHeightEnvelopeAllocator.registerStackedEnvelope(
                                envelopes, envelopeHeightByFamily, envelopeModeByFamily,
                                envelopeModeExplicitByFamily,
                                diagnostics, List.copyOf(namedHits), b.blocks(), mode, modeExplicit)) {
                            gap = true;
                        }
                    }
                    continue;
                }
                for (String id : namedHits) {
                    if (!BuildingHostResolver.isKnownDesignHostId(hosts, id)) {
                        gap = true;
                        Map<String, Object> detail = new HashMap<>();
                        detail.put("code", "E_HEIGHT_HOST_MISSING");
                        detail.put("blocks", b.blocks());
                        detail.put("roleHint", hint == null ? "" : hint);
                        detail.put("target", id);
                        detail.put("message", "height target is not a design-constraint host");
                        diagnostics.add(detail);
                        continue;
                    }
                    Integer prior = out.get(id);
                    if (prior != null && prior != b.blocks()) {
                        gap = true;
                        diagnostics.add(Map.of(
                                "code", "E_HEIGHT_HOST_CONFLICT",
                                "target", id,
                                "blocks", b.blocks(),
                                "priorBlocks", prior,
                                "message", "conflicting height cues for the same host"));
                        continue;
                    }
                    out.put(id, b.blocks());
                }
                continue;
            }
            if (target == null || !BuildingHostResolver.isKnownDesignHostId(hosts, target)) {
                gap = true;
                Map<String, Object> detail = getStringObjectMap(b, target, hint);
                diagnostics.add(detail);
                continue;
            }
            Integer prior = out.get(target);
            if (prior != null && prior != b.blocks()) {
                gap = true;
                diagnostics.add(Map.of(
                        "code", "E_HEIGHT_HOST_CONFLICT",
                        "target", target,
                        "blocks", b.blocks(),
                        "priorBlocks", prior,
                        "message", "conflicting height cues for the same host"));
                continue;
            }
            out.put(target, b.blocks());
        }
        return new HeightBindResult(out, List.copyOf(envelopes), diagnostics, gap);
    }

    static @NotNull Map<String, Object> getStringObjectMap(UserConstraintContract.HeightBinding b, String target, String hint) {
        Map<String, Object> detail = new HashMap<>();
        detail.put("code", target == null ? "E_HEIGHT_HOST_TIE" : "E_HEIGHT_HOST_MISSING");
        detail.put("blocks", b.blocks());
        detail.put("roleHint", hint == null ? "" : hint);
        if (target != null) detail.put("target", target);
        detail.put("message", target == null
                ? "height host role score is not unique or missing"
                : "height target is not a design-constraint host");
        return detail;
    }

    /**
     * Identity-only role score. Never includes {@code componentType} (MASS_MAIN contains "main").
     * Classification: HARD_CONSTRAINT host matching — no DESIGN_OVERRIDE from type enums.
     */
    static int heightRoleScore(Component host, String roleHint) {
        if (roleHint == null || roleHint.isBlank() || host == null) return 0;
        StringBuilder sb = new StringBuilder();
        if (BuildingHostResolver.massId(host) != null) sb.append(BuildingHostResolver.massId(host)).append(' ');
        if (host.slotId() != null) sb.append(host.slotId()).append(' ');
        if (host.params() != null) {
            for (String k : List.of(
                    "component_id", "building_id", "requirement_scope",
                    "building_role", "buildingRole", "role", "part")) {
                Object v = host.params().get(k);
                if (v != null) sb.append(v).append(' ');
            }
        }
        if (host.features() != null) {
            for (String f : host.features()) {
                if (f != null) sb.append(f).append(' ');
            }
        }
        String lower = sb.toString().toLowerCase(Locale.ROOT);
        // Pavilion must not score wing hosts; wing has its own identity.
        boolean pavilionLike = lower.contains("pavilion") || lower.contains("亭")
                || lower.contains("廊");
        boolean wingLike = lower.contains("wing") || lower.contains("厢");
        boolean towerLike = lower.contains("tower") || lower.contains("塔")
                || lower.contains("pagoda");
        return switch (roleHint) {
            case "pavilion" -> pavilionLike ? 3 : 0;
            case "wing" -> wingLike ? 3 : 0;
            case "hall" -> {
                if (lower.contains("main_hall") || lower.contains("townhall")
                        || lower.contains("hall") || lower.contains("殿")
                        || lower.contains("正房") || lower.contains("主殿")
                        || lower.contains("正殿") || lower.contains("大殿")
                        || lower.contains("主楼") || lower.contains("主厅")) {
                    yield 3;
                }
                // Weak identity only (never componentType): house_main / *_main without pavilion/tower cues.
                if (!pavilionLike && !wingLike && !towerLike
                        && (lower.contains("_main") || lower.contains("house"))) {
                    yield 2;
                }
                yield 0;
            }
            case "tower" -> towerLike ? 3 : 0;
            default -> 0;
        };
    }
}
