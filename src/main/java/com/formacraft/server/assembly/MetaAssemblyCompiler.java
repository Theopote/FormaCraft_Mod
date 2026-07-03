package com.formacraft.server.assembly;

import com.formacraft.common.logging.FcaLog;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * MetaAssemblyCompiler (v1):
 * Compiles a higher-level "topology-ish" graph/components form into executable ops.
 * <p>
 * Input: BuildingSpec.extra.assembly
 * - If ops are present, compiler is unnecessary.
 * - If graph/components are present, compiler expands them into ops + PUSH_ORIGIN blocks.
 */
public final class MetaAssemblyCompiler {
    private static final FcaLog LOG = FcaLog.of("MetaAssemblyCompiler");

    private MetaAssemblyCompiler() {}

    /**
     * Compile an assembly map into an AssemblySpec with an ops list.
     * Returns null if cannot compile.
     *
     * @param assemblyObj The assembly object to compile
     * @param spec        Optional BuildingSpec to get dimensions from (can be null)
     */
    @SuppressWarnings("unchecked")
    public static AssemblySpec compile(Object assemblyObj, com.formacraft.common.model.build.BuildingSpec spec) {
        if (!(assemblyObj instanceof Map<?, ?> mm)) return null;
        Map<String, Object> m;
        try {
            m = (Map<String, Object>) mm;
        } catch (Exception e) {
            return null;
        }
        if (m.isEmpty()) return null;

        AssemblySpec existing = AssemblySpec.fromExtra(m);
        if (existing != null && existing.ops != null && !existing.ops.isEmpty()) return existing;

        String paletteId = AssemblyCompilerUtils.str(m.get("paletteId"), null);
        String entranceFacing = AssemblyCompilerUtils.str(m.get("entranceFacing"), null);

        List<Map<String, Object>> ops = new ArrayList<>();

        Object graphObj = m.get("graph");
        Map<String, Object> graph = null;
        if (graphObj instanceof Map<?, ?> gm) {
            try {
                graph = (Map<String, Object>) gm;
            } catch (Exception e) {
                LOG.debug("assembly graph is not a string map", e);
            }
        }

        Object compsObj = (graph != null) ? graph.get("components") : m.get("components");
        List<?> comps = null;
        if (compsObj instanceof List<?> cl) {
            comps = cl;
        }

        if ((comps == null || comps.isEmpty()) && m.get("macro") instanceof Map<?, ?>) {
            comps = AssemblyComponentEmitter.generateBasicComponentFromMacro(m, graph, spec);
        }

        if (comps == null || comps.isEmpty()) return null;

        Map<String, Map<String, Object>> byId = new HashMap<>();
        Map<String, int[]> originById = new HashMap<>();
        Map<String, Map<String, int[]>> portsById = new HashMap<>();

        for (Object c : comps) {
            if (!(c instanceof Map<?, ?> cm)) continue;
            Map<String, Object> comp;
            try {
                comp = (Map<String, Object>) cm;
            } catch (Exception e) {
                continue;
            }
            String id = AssemblyCompilerUtils.str(comp.get("id"), null);
            int[] o = AssemblyComponentEmitter.componentOrigin(comp);
            if (id != null && !id.isBlank()) {
                String cid = id.trim();
                byId.put(cid, comp);
                originById.put(cid, o);
                portsById.put(cid, AssemblyComponentEmitter.buildPorts(comp));
            }
            AssemblyComponentEmitter.emitComponent(ops, comp);
        }

        Object connsObj = (graph != null) ? graph.get("connections") : m.get("connections");
        if (connsObj instanceof List<?> conns) {
            for (Object cc : conns) {
                if (!(cc instanceof Map<?, ?> cm)) continue;
                Map<String, Object> conn;
                try {
                    conn = (Map<String, Object>) cm;
                } catch (Exception e) {
                    continue;
                }
                AssemblyConnectionCompiler.emitConnection(ops, conn, byId, originById, portsById);
            }
        }

        if (ops.isEmpty()) return null;
        return AssemblySpec.of(paletteId, entranceFacing, ops);
    }
}
