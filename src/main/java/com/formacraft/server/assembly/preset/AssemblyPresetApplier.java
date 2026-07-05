package com.formacraft.server.assembly.preset;

import com.formacraft.FormacraftMod;
import com.formacraft.server.assembly.validation.AssemblyValidationIssue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Expands {@code assembly.preset} + {@code presetParams} into a full MetaAssembly graph before macro/validation.
 */
public final class AssemblyPresetApplier {

    private AssemblyPresetApplier() {}

    public record ApplyResult(Object applied, List<AssemblyValidationIssue> issues) {}

    @SuppressWarnings("unchecked")
    public static ApplyResult apply(Object assemblyObj) {
        List<AssemblyValidationIssue> issues = new ArrayList<>();
        if (!(assemblyObj instanceof Map<?, ?> root)) {
            return new ApplyResult(assemblyObj, issues);
        }
        Map<String, Object> m = shallowCopyMap(root);

        String presetId = resolvePresetId(m);
        if (presetId.isBlank()) {
            return new ApplyResult(m, issues);
        }

        Optional<AssemblyPresetDefinition> presetOpt = AssemblyPresetRegistry.findById(presetId);
        if (presetOpt.isEmpty()) {
            issues.add(new AssemblyValidationIssue(
                    "$.preset",
                    AssemblyValidationIssue.Severity.ERROR,
                    "E_UNKNOWN_PRESET",
                    "未知的 assembly preset: " + presetId
            ));
            return new ApplyResult(m, issues);
        }

        AssemblyPresetDefinition preset = presetOpt.get();
        Map<String, Object> merged = deepCopyMap(preset.assemblyTemplate());
        Map<String, Object> presetParams = readPresetParams(m);
        applyPresetParameters(merged, preset, presetParams);

        Map<String, Object> userOverrides = new LinkedHashMap<>(m);
        userOverrides.remove("preset");
        userOverrides.remove("presetId");
        userOverrides.remove("presetParams");
        userOverrides.remove("params");

        deepMerge(merged, userOverrides);

        FormacraftMod.LOGGER.info("AssemblyPresetApplier: expanded preset {} -> assembly graph", presetId);
        return new ApplyResult(merged, issues);
    }

    private static String resolvePresetId(Map<String, Object> m) {
        String preset = str(m.get("preset"));
        if (!preset.isBlank()) {
            return preset;
        }
        preset = str(m.get("presetId"));
        if (!preset.isBlank()) {
            return preset;
        }
        Object macro = m.get("macro");
        if (macro instanceof Map<?, ?> mm) {
            preset = str(mm.get("preset"));
            if (!preset.isBlank()) {
                return preset;
            }
            return str(mm.get("presetId"));
        }
        return "";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readPresetParams(Map<String, Object> m) {
        Object pp = m.get("presetParams");
        if (pp instanceof Map<?, ?> map) {
            return shallowCopyMap(map);
        }
        pp = m.get("params");
        if (pp instanceof Map<?, ?> map) {
            return shallowCopyMap(map);
        }
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    private static void applyPresetParameters(
            Map<String, Object> assembly,
            AssemblyPresetDefinition preset,
            Map<String, Object> params
    ) {
        if (params == null || params.isEmpty()) {
            return;
        }
        Map<String, Object> graph = graphMap(assembly);
        if (graph == null) {
            return;
        }
        Object compsObj = graph.get("components");
        if (!(compsObj instanceof List<?> comps) || comps.isEmpty()) {
            return;
        }
        Object first = comps.get(0);
        if (!(first instanceof Map<?, ?>)) {
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> shell = (Map<String, Object>) first;

        Double height = numParam(params, preset, "height");
        if (height != null) {
            shell.put("h", height.intValue());
        }
        Double footprint = numParam(params, preset, "footprint");
        if (footprint != null) {
            int fp = footprint.intValue();
            shell.put("w", fp);
            shell.put("d", fp);
        }
        Double twist = numParam(params, preset, "twistTurns");
        if (twist != null) {
            shell.put("twistTurns", twist);
        }
        Object styleId = params.get("styleId");
        if (styleId != null && !String.valueOf(styleId).isBlank()) {
            Map<String, Object> macro = macroMap(assembly);
            Map<String, Object> style = styleMap(macro);
            style.put("styleId", String.valueOf(styleId).trim());
        }
    }

    private static Double numParam(
            Map<String, Object> params,
            AssemblyPresetDefinition preset,
            String name
    ) {
        if (!params.containsKey(name)) {
            return null;
        }
        Object v = params.get(name);
        if (v instanceof Number n) {
            double val = n.doubleValue();
            AssemblyPresetDefinition.ParamSpec spec = preset.parameters().get(name);
            if (spec != null) {
                val = clamp(val, spec.min(), spec.max());
            }
            return val;
        }
        try {
            return Double.parseDouble(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> graphMap(Map<String, Object> assembly) {
        Object g = assembly.get("graph");
        if (g instanceof Map<?, ?> gm) {
            return (Map<String, Object>) gm;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> macroMap(Map<String, Object> assembly) {
        Object macro = assembly.get("macro");
        if (!(macro instanceof Map<?, ?> mm)) {
            Map<String, Object> created = new LinkedHashMap<>();
            assembly.put("macro", created);
            return created;
        }
        return (Map<String, Object>) mm;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> styleMap(Map<String, Object> macro) {
        Object style = macro.get("style");
        if (!(style instanceof Map<?, ?> sm)) {
            Map<String, Object> created = new LinkedHashMap<>();
            macro.put("style", created);
            return created;
        }
        return (Map<String, Object>) sm;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> shallowCopyMap(Map<?, ?> source) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : source.entrySet()) {
            if (e.getKey() != null) {
                out.put(String.valueOf(e.getKey()), e.getValue());
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> deepCopyMap(Map<String, Object> source) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : source.entrySet()) {
            out.put(e.getKey(), deepCopyValue(e.getValue()));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Object deepCopyValue(Object v) {
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (e.getKey() != null) {
                    out.put(String.valueOf(e.getKey()), deepCopyValue(e.getValue()));
                }
            }
            return out;
        }
        if (v instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object it : list) {
                out.add(deepCopyValue(it));
            }
            return out;
        }
        return v;
    }

    @SuppressWarnings("unchecked")
    private static void deepMerge(Map<String, Object> base, Map<String, Object> overlay) {
        for (Map.Entry<String, Object> e : overlay.entrySet()) {
            String key = e.getKey();
            Object ov = e.getValue();
            Object bv = base.get(key);
            if (bv instanceof Map<?, ?> bm && ov instanceof Map<?, ?> om) {
                @SuppressWarnings("unchecked")
                Map<String, Object> bMap = (Map<String, Object>) bm;
                deepMerge(bMap, shallowCopyMap(om));
            } else if (ov != null) {
                base.put(key, deepCopyValue(ov));
            }
        }
    }

    private static double clamp(double v, double min, double max) {
        if (min <= max) {
            if (v < min) return min;
            if (v > max) return max;
        }
        return v;
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v).trim();
    }
}
