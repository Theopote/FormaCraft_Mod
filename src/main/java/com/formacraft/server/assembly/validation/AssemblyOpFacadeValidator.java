package com.formacraft.server.assembly.validation;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Validates facade pattern / opening assembly ops. */
final class AssemblyOpFacadeValidator {
    private AssemblyOpFacadeValidator() {}

    static void validate(String op, List<AssemblyValidationIssue> out, String p, Map<?, ?> m) {

            if (op.equals("SURFACE_PATTERN")) {
                AssemblyValidationSupport.requireFace(out, p, m);
                String pattern = AssemblyValidationSupport.str(m.get("pattern"), AssemblyValidationSupport.str(m.get("type"), "GRID")).trim().toUpperCase(Locale.ROOT);
                if (!(pattern.equals("GRID") || pattern.equals("STRIPES_V") || pattern.equals("STRIPES_H") || pattern.equals("RIBS_V") || pattern.equals("RIBS_H")
                        || pattern.equals("STRIPES_VERTICAL") || pattern.equals("STRIPES_HORIZONTAL") || pattern.equals("RIBS_VERTICAL") || pattern.equals("RIBS_HORIZONTAL")
                        || pattern.equals("NOISE"))) {
                    out.add(AssemblyValidationSupport.err(p + ".pattern", "E_PATTERN_VALUE", "SURFACE_PATTERN.pattern 取值非法: " + pattern));
                }
                if (!pattern.equals("NOISE")) {
                    AssemblyValidationSupport.requireIntMin(out, p, m, "step", 1);
                    AssemblyValidationSupport.requireIntMin(out, p, m, "thickness", 1);
                }
                // Forma-Gene integration: NOISE pattern parameters
                if (pattern.equals("NOISE")) {
                    if (m.get("noiseProbability") != null || m.get("noise_probability") != null) {
                        Object np = m.get("noiseProbability") != null ? m.get("noiseProbability") : m.get("noise_probability");
                        if (np instanceof Number) {
                            double v = ((Number) np).doubleValue();
                            if (v < 0.0 || v > 1.0) {
                                out.add(AssemblyValidationSupport.warn(p + ".noiseProbability", "W_NOISE_PROB_RANGE", "noiseProbability 建议在 0.0~1.0 范围内"));
                            }
                        }
                    }
                    if (m.get("noiseMethod") != null || m.get("noise_method") != null) {
                        Object nm = m.get("noiseMethod") != null ? m.get("noiseMethod") : m.get("noise_method");
                        String method = String.valueOf(nm).trim().toUpperCase(Locale.ROOT);
                        if (!(method.equals("PERLIN") || method.equals("RANDOM") || method.equals("HASH"))) {
                            out.add(AssemblyValidationSupport.warn(p + ".noiseMethod", "W_NOISE_METHOD_VALUE", "noiseMethod 建议为 PERLIN/RANDOM/HASH: " + method));
                        }
                    }
                }
            }
            if (op.equals("FACADE_GRID")) {
                AssemblyValidationSupport.requireFace(out, p, m);
                AssemblyValidationSupport.requireIntMin(out, p, m, "bayW", 1);
                AssemblyValidationSupport.requireIntMin(out, p, m, "bayH", 1);
            }
            if (op.equals("SURFACE_BANDS")) {
                AssemblyValidationSupport.requireFace(out, p, m);
            }
            if (op.equals("OPENINGS")) {
                AssemblyValidationSupport.requireFace(out, p, m);
                String kind = AssemblyValidationSupport.str(m.get("kind"), AssemblyValidationSupport.str(m.get("type"), "")).trim().toUpperCase(Locale.ROOT);
                if (kind.isBlank()) {
                    out.add(AssemblyValidationSupport.err(p + ".kind", "E_OPENINGS_KIND_MISSING", "OPENINGS.kind 缺失"));
                } else {
                    boolean ok = kind.equals("WINDOW_GRID") || kind.equals("DOOR") || kind.equals("ARCH_WINDOW") || kind.equals("ROSE_WINDOW");
                    if (!ok) out.add(AssemblyValidationSupport.err(p + ".kind", "E_OPENINGS_KIND_VALUE", "OPENINGS.kind 取值非法: " + kind));
                }
                // Range sanity (soft): allow missing fields (engine has defaults), but validate if present.
                AssemblyValidationSupport.requireIntMin(out, p, m, "rows", 1);
                AssemblyValidationSupport.requireIntMin(out, p, m, "cols", 1);
                AssemblyValidationSupport.requireIntMin(out, p, m, "winW", 1);
                AssemblyValidationSupport.requireIntMin(out, p, m, "winH", 1);
                if (m.get("doorW") != null) AssemblyValidationSupport.requireIntMin(out, p, m, "doorW", 1);
                if (m.get("doorH") != null) AssemblyValidationSupport.requireIntMin(out, p, m, "doorH", 2);
                if (m.get("r") != null || m.get("radius") != null) {
                    // rose window uses r>=2
                    Integer rv = AssemblyValidationSupport.intOrNull(m.get("r"));
                    if (rv == null) rv = AssemblyValidationSupport.intOrNull(m.get("radius"));
                    if (rv != null && rv < 2) out.add(AssemblyValidationSupport.err(p + ".r", "E_ROSE_R_RANGE", "ROSE_WINDOW.r 必须 >= 2"));
                }
                if (m.get("petals") != null || m.get("spokes") != null) {
                    Integer pv = AssemblyValidationSupport.intOrNull(m.get("petals"));
                    if (pv == null) pv = AssemblyValidationSupport.intOrNull(m.get("spokes"));
                    if (pv != null && pv < 3) out.add(AssemblyValidationSupport.err(p + ".petals", "E_ROSE_PETALS_RANGE", "ROSE_WINDOW.petals 必须 >= 3"));
                }
                if (m.get("archType") != null || m.get("arch") != null) {
                    String at = AssemblyValidationSupport.str(m.get("archType"), AssemblyValidationSupport.str(m.get("arch"), "")).trim().toUpperCase(Locale.ROOT);
                    if (!at.isEmpty() && !(at.equals("ROUND") || at.equals("POINTED"))) {
                        out.add(AssemblyValidationSupport.err(p + ".archType", "E_ARCH_TYPE_VALUE", "ARCH_WINDOW.archType 取值非法: " + at + "（允许 ROUND/POINTED）"));
                    }
                }
            }
    }
}
