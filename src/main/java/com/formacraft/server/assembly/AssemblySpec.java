package com.formacraft.server.assembly;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * AssemblySpec (v1):
 * A lightweight IR for the "Parametric Meta-Assembly Engine".
 *
 * Source: BuildingSpec.extra.assembly
 *
 * Minimal contract:
 * {
 *   "paletteId": "PALETTE_...",
 *   "entranceFacing": "SOUTH",
 *   "ops": [ { "op": "CLEAR_BOX", ... }, { "op": "SHELL_BOX", ... }, ... ]
 * }
 */
public final class AssemblySpec {
    public final String paletteId;
    public final String entranceFacing;
    public final List<Map<String, Object>> ops;

    private AssemblySpec(String paletteId, String entranceFacing, List<Map<String, Object>> ops) {
        this.paletteId = paletteId;
        this.entranceFacing = entranceFacing;
        this.ops = ops != null ? ops : Collections.emptyList();
    }

    public static AssemblySpec of(String paletteId, String entranceFacing, List<Map<String, Object>> ops) {
        return new AssemblySpec(paletteId, entranceFacing, ops);
    }

    @SuppressWarnings("unchecked")
    public static AssemblySpec fromExtra(Object assemblyObj) {
        if (!(assemblyObj instanceof Map<?, ?> mm)) return null;
        Map<String, Object> m;
        try { m = (Map<String, Object>) mm; } catch (Exception e) { return null; }
        if (m == null || m.isEmpty()) return null;

        String paletteId = str(m.get("paletteId"), null);
        String entranceFacing = str(m.get("entranceFacing"), null);

        List<Map<String, Object>> ops = new ArrayList<>();
        Object opsObj = m.get("ops");
        if (opsObj instanceof List<?> list) {
            for (Object it : list) {
                if (it instanceof Map<?, ?> opMap) {
                    try { ops.add((Map<String, Object>) opMap); } catch (Exception ignored) {}
                }
            }
        }

        return new AssemblySpec(paletteId, entranceFacing, ops);
    }

    private static String str(Object v, String def) {
        if (v == null) return def;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? def : s;
    }
}


