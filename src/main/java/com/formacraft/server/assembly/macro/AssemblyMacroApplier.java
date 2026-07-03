package com.formacraft.server.assembly.macro;

import com.formacraft.server.assembly.validation.AssemblyValidationIssue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * P0 Macro parameter table applier.
 * <p>
 * Reads extra.assembly.macro and maps high-level "gene-like" sliders onto existing assembly knobs.
 * <p>
 * Principles:
 * - Explicit low-level parameters always win (macro only fills missing bits or adds best-effort helpers).
 * - Conservative: do not invent complex geometry; only map to already-supported ops/components.
 */
public final class AssemblyMacroApplier {
    private AssemblyMacroApplier() {}

    public static AssemblyMacroApplyResult apply(Object assemblyObj) {
        List<AssemblyValidationIssue> issues = new ArrayList<>();
        if (!(assemblyObj instanceof Map<?, ?> root)) {
            return new AssemblyMacroApplyResult(assemblyObj, issues);
        }
        Map<String, Object> m = AssemblyMacroSupport.safeMap(root);
        if (m == null) return new AssemblyMacroApplyResult(assemblyObj, issues);

        Object macroObj = m.get("macro");
        if (!(macroObj instanceof Map<?, ?> mm)) {
            return new AssemblyMacroApplyResult(assemblyObj, issues);
        }
        Map<String, Object> macro = AssemblyMacroSupport.safeMap(mm);
        if (macro == null) return new AssemblyMacroApplyResult(m, issues);

        Map<String, Object> graph = null;
        Object graphObj = m.get("graph");
        if (graphObj instanceof Map<?, ?> gm) {
            graph = AssemblyMacroSupport.safeMap(gm);
        }
        Object compsObj = (graph != null) ? graph.get("components") : m.get("components");
        Object connsObj = (graph != null) ? graph.get("connections") : m.get("connections");

        String targetId = AssemblyMacroSupport.str(macro.get("primaryComponent"), AssemblyMacroSupport.str(macro.get("primaryComponentId"), null));
        if (targetId != null) targetId = targetId.trim();

        Map<String, Object> primary = null;
        if (compsObj instanceof List<?> comps) {
            for (Object it : comps) {
                if (!(it instanceof Map<?, ?> cm)) continue;
                Map<String, Object> c = AssemblyMacroSupport.safeMap(cm);
                if (c == null) continue;
                if (primary == null) primary = c;
                if (targetId != null && targetId.equals(AssemblyMacroSupport.str(c.get("id"), "").trim())) {
                    primary = c;
                    break;
                }
            }
        }

        if (primary != null) {
            primary = AssemblyMacroPrimaryApplier.applyVerticalProfile(m, graph, compsObj, primary, macro, issues);
        }

        if (primary != null) {
            AssemblyMacroPrimaryApplier.applyShapeType(primary, macro, issues);
            AssemblyMacroPrimaryApplier.applyHeightScale(primary, macro, issues);
            AssemblyMacroPrimaryApplier.applyRoofMacro(m, graph, compsObj, primary, macro, issues);
            AssemblyMacroPrimaryApplier.applyOpenness(primary, macro, issues);
        }

        if (connsObj instanceof List<?> conns) {
            AssemblyMacroPrimaryApplier.applySymmetryToConnections(conns, macro, issues);
        }

        AssemblyMacroStyleApplier.apply(m, graph, compsObj, connsObj, primary, macro, issues);

        AssemblyMacroInjectApplier.applyBridgeTower(m, graph, compsObj, macro, issues);

        AssemblyMacroInjectApplier.applySubtractHoles(m, graph, compsObj, primary, macro, issues);

        return new AssemblyMacroApplyResult(m, issues);
    }
}
