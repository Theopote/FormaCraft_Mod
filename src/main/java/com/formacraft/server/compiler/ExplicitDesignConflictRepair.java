package com.formacraft.server.compiler;

import com.formacraft.FormacraftMod;
import com.formacraft.common.generation.component.util.MassComponentKinds;
import com.formacraft.common.generation.design.DesignProvenance;
import com.formacraft.common.llm.dto.CapabilityGap;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.style.ExplicitDesignPolicy;
import com.formacraft.common.typology.UserConstraintContract;
import com.formacraft.server.assembly.AssemblyCompileDiagnostics;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Drop satellites that contradict an explicit host opt-out (e.g. FACADE_WINDOWS on an
 * open pavilion) instead of failing the whole plan. Runs before authored validation.
 * <p>
 * Classical revolve CROWN/CUPOLA/DOME on a host that already has a pitched ROOF historically
 * overwrote roof tiles ({@code E_PATCH_CONFLICT}). Chinese 宝顶 / finial crowns that can seat
 * on a ridge are preserved. Design-locked classical crowns that cannot coexist emit a
 * capability_gap instead of a silent delete.
 */
final class ExplicitDesignConflictRepair {
    private ExplicitDesignConflictRepair() {}

    static LlmPlan apply(LlmPlan plan) {
        if (plan == null || plan.components() == null || plan.components().isEmpty()) return plan;
        Result result = apply(plan, plan.components());
        if (result.gap() != null) {
            return PlanComponentRewrite.withCapabilityGap(result.components() != plan.components()
                    ? PlanComponentRewrite.withComponents(plan, result.components())
                    : plan, result.gap());
        }
        return result.components() != plan.components()
                ? PlanComponentRewrite.withComponents(plan, result.components())
                : plan;
    }

    /** List-only path (prepareComponents); may record gap via {@link AssemblyCompileDiagnostics}. */
    static List<Component> apply(List<Component> components) {
        return apply(null, components).components();
    }

    static Result apply(LlmPlan plan, List<Component> components) {
        if (components == null || components.isEmpty()) {
            return new Result(components, null);
        }
        var bodies = new HashMap<Object, Component>();
        var roofHosts = new java.util.HashSet<String>();
        for (var c : components) {
            if (c == null || c.params() == null) continue;
            // Index all host masses (incl. MASS_SECONDARY / TOWER / STRUCTURE / GATE) so
            // window/entrance opt-outs on annexes are not invisible to conflict repair.
            if (isConflictBodyHost(c)) {
                Object id = c.params().get("component_id");
                if (id != null) bodies.put(id, c);
            }
            if (ComponentPlanTypes.isRoofType(ComponentPlanTypes.normalizeType(c.componentType()))) {
                Object host = c.params().get("host_id");
                if (host == null) host = c.params().get("hostId");
                if (host != null) roofHosts.add(String.valueOf(host));
            }
        }
        List<Component> out = new ArrayList<>(components.size());
        boolean changed = false;
        CapabilityGap lockedConflict = null;
        for (var c : components) {
            if (c == null || c.params() == null) {
                out.add(c);
                continue;
            }
            Object hostRef = c.params().get("host_id");
            if (hostRef == null) hostRef = c.params().get("hostId");
            var body = bodies.get(hostRef);
            if (body != null
                    && "FACADE_WINDOWS".equals(c.componentType())
                    && ExplicitDesignPolicy.windowsDisabled(body)
                    && !ExplicitDesignPolicy.windowsDisabled(c)) {
                if (isDesignLockedOpening(plan, c)) {
                    lockedConflict = new CapabilityGap(
                            "E_EXPLICIT_DESIGN_CONFLICT",
                            "宿主明确禁用窗户，但 FACADE_WINDOWS 带有设计锁定，无法静默删除；请取消开窗锁定或撤销宿主 window_style=none",
                            "components[].FACADE_WINDOWS",
                            List.of(
                                    "Remove the locked FACADE_WINDOWS satellite",
                                    "Or clear host window_style=none / window_ratio=0",
                                    "Or clear design_sources lock on the windows satellite"));
                    FormacraftMod.LOGGER.info(
                            "ExplicitDesignConflictRepair: locked FACADE_WINDOWS host={} conflicts with windows disabled → gap",
                            hostRef);
                    out.add(c);
                    continue;
                }
                FormacraftMod.LOGGER.info(
                        "ExplicitDesignConflictRepair: drop FACADE_WINDOWS host={} (windows disabled)",
                        hostRef);
                changed = true;
                continue;
            }
            if (body != null
                    && "ENTRANCE".equals(c.componentType())
                    && ExplicitDesignPolicy.entranceDisabled(body)
                    && !ExplicitDesignPolicy.entranceDisabled(c)
                    && !Boolean.TRUE.equals(c.params().get("compiler_internal_portal"))) {
                if (isDesignLockedOpening(plan, c)) {
                    lockedConflict = new CapabilityGap(
                            "E_EXPLICIT_DESIGN_CONFLICT",
                            "宿主明确禁用入口，但 ENTRANCE 带有设计锁定，无法静默删除；请取消入口锁定或撤销宿主 entrance_type=none",
                            "components[].ENTRANCE",
                            List.of(
                                    "Remove the locked ENTRANCE satellite",
                                    "Or clear host entrance_type=none",
                                    "Or clear design_sources lock on the entrance satellite"));
                    FormacraftMod.LOGGER.info(
                            "ExplicitDesignConflictRepair: locked ENTRANCE host={} conflicts with entrance disabled → gap",
                            hostRef);
                    out.add(c);
                    continue;
                }
                FormacraftMod.LOGGER.info(
                        "ExplicitDesignConflictRepair: drop ENTRANCE host={} (entrance disabled)",
                        hostRef);
                changed = true;
                continue;
            }
            String type = ComponentPlanTypes.normalizeType(c.componentType());
            if (("CROWN".equals(type) || "CUPOLA".equals(type) || "DOME".equals(type))) {
                Object host = c.params().get("host_id");
                if (host == null) host = c.params().get("hostId");
                if (host != null && roofHosts.contains(String.valueOf(host))) {
                    if (isChineseFinialOrBaoding(c)) {
                        // Ridge 宝顶 / finial can coexist with pitched ROOF — keep.
                        out.add(c);
                        continue;
                    }
                    if (isDesignLockedCrown(plan, c)) {
                        // User/LLM locked a classical revolve crown that conflicts with ROOF.
                        lockedConflict = new CapabilityGap(
                                "E_CROWN_ROOF_CONFLICT",
                                "宿主已有 ROOF，但设计锁定的 " + type
                                        + " 为古典旋转冠饰，无法与 pitched roof 共存；请改用脊饰/宝顶或取消冠饰锁定",
                                "components[].CROWN",
                                List.of(
                                        "Use a ridge finial / 宝顶 CROWN that seats above the roof",
                                        "Or omit the pitched ROOF when a classical cupola/dome is required",
                                        "Or clear design_sources lock on crown_template / crown_type"));
                        FormacraftMod.LOGGER.info(
                                "ExplicitDesignConflictRepair: locked {} host={} conflicts with ROOF → gap",
                                type, host);
                        out.add(c); // keep for inspection; gap blocks success
                        continue;
                    }
                    if (isClassicalRevolveCrown(c) || isAmbiguousCrown(c)) {
                        FormacraftMod.LOGGER.info(
                                "ExplicitDesignConflictRepair: drop {} host={} (classical/ambiguous crown vs ROOF)",
                                type, host);
                        changed = true;
                        continue;
                    }
                }
            }
            out.add(c);
        }
        // List-only prepareComponents path needs diagnostics; plan-level apply() stamps capabilityGap.
        if (lockedConflict != null && plan == null) {
            AssemblyCompileDiagnostics.set(lockedConflict);
        }
        return new Result(changed ? out : components, lockedConflict);
    }

    record Result(List<Component> components, CapabilityGap gap) {}

    /** Host masses that may carry explicit window/entrance/roof opt-outs for satellites. */
    private static boolean isConflictBodyHost(Component c) {
        if (c == null || c.componentType() == null) return false;
        if (MassComponentKinds.isHostMass(c)) return true;
        String t = c.componentType().trim().toUpperCase(Locale.ROOT);
        return "GATE".equals(t) || "GATE_STRUCTURE".equals(t)
                || "STRUCTURE".equals(t) || t.startsWith("TOWER");
    }

    /** Classical cupola / dome / pilaster revolve crowns — conflict with pitched ROOF tiles. */
    static boolean isClassicalRevolveCrown(Component c) {
        if (c == null) return false;
        String t = ComponentPlanTypes.normalizeType(c.componentType());
        if ("CUPOLA".equals(t) || "DOME".equals(t)) return true;
        if (!"CROWN".equals(t)) return false;
        String feat = featuresOf(c).toLowerCase(Locale.ROOT);
        String id = idOf(c).toLowerCase(Locale.ROOT);
        Object template = c.params() == null ? null : c.params().get("crown_template");
        if (template == null && c.params() != null) template = c.params().get("crownTemplate");
        String tmpl = template == null ? "" : String.valueOf(template).toLowerCase(Locale.ROOT);
        return tmpl.contains("classical") || tmpl.contains("cupola") || tmpl.contains("dome")
                || tmpl.contains("onion") || tmpl.contains("revolve")
                || feat.contains("classical") || feat.contains("cupola") || feat.contains("dome")
                || feat.contains("onion")
                || id.contains("cupola") || id.contains("classical") || id.contains("dome");
    }

    /** East-asian ridge 宝顶 / finial that seats on (not instead of) a pitched roof. */
    static boolean isChineseFinialOrBaoding(Component c) {
        if (c == null) return false;
        String blob = (featuresOf(c) + " " + idOf(c)).toLowerCase(Locale.ROOT);
        Object template = c.params() == null ? null : c.params().get("crown_template");
        if (template == null && c.params() != null) template = c.params().get("crownTemplate");
        if (template != null) blob += " " + String.valueOf(template).toLowerCase(Locale.ROOT);
        Object kind = c.params() == null ? null : c.params().get("crown_type");
        if (kind == null && c.params() != null) kind = c.params().get("crownType");
        if (kind != null) blob += " " + String.valueOf(kind).toLowerCase(Locale.ROOT);
        return blob.contains("宝顶") || blob.contains("baoding") || blob.contains("finial")
                || blob.contains("ridge_ornament") || blob.contains("ridge ornament")
                || blob.contains("脊饰") || blob.contains("脊刹") || blob.contains("pagoda_finial")
                || blob.contains("top_protrusion") || blob.contains("顶饰");
    }

    /** Bare CROWN with no oriental/classical cue — historically a cupola stand-in on gates. */
    static boolean isAmbiguousCrown(Component c) {
        if (c == null) return false;
        String t = ComponentPlanTypes.normalizeType(c.componentType());
        if (!"CROWN".equals(t)) return false;
        return !isClassicalRevolveCrown(c) && !isChineseFinialOrBaoding(c);
    }

    private static boolean isDesignLockedCrown(LlmPlan plan, Component c) {
        Map<String, Object> params = c == null ? null : c.params();
        if (params != null) {
            if (UserConstraintContract.isServerVerified(params, "crown_template")
                    || UserConstraintContract.isServerVerified(params, "crown_type")
                    || UserConstraintContract.isServerVerified(params, "crown_requirement")) {
                return true;
            }
        }
        return !DesignProvenance.canMutateParam(plan, params, "crown_template")
                || !DesignProvenance.canMutateParam(plan, params, "crown_type")
                || !DesignProvenance.canMutateParam(plan, params, "crown_requirement");
    }

    /** Locked window/entrance satellites must gap, not silently drop (same contract as crowns). */
    private static boolean isDesignLockedOpening(LlmPlan plan, Component c) {
        Map<String, Object> params = c == null ? null : c.params();
        if (params != null) {
            if (UserConstraintContract.isServerVerified(params, "window_style")
                    || UserConstraintContract.isServerVerified(params, "window_ratio")
                    || UserConstraintContract.isServerVerified(params, "entrance_type")
                    || UserConstraintContract.isServerVerified(params, "generate")) {
                return true;
            }
        }
        return !DesignProvenance.canMutateParam(plan, params, "window_style")
                || !DesignProvenance.canMutateParam(plan, params, "window_ratio")
                || !DesignProvenance.canMutateParam(plan, params, "entrance_type")
                || !DesignProvenance.canEnrichFeatures(plan, params);
    }

    private static String idOf(Component c) {
        if (c.params() == null || c.params().get("component_id") == null) return "";
        return String.valueOf(c.params().get("component_id"));
    }

    private static String featuresOf(Component c) {
        if (c.features() == null) return "";
        StringBuilder sb = new StringBuilder();
        for (String f : c.features()) {
            if (f != null) sb.append(f).append(' ');
        }
        return sb.toString();
    }
}
