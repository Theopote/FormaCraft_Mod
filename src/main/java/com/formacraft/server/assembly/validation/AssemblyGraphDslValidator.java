package com.formacraft.server.assembly.validation;

import com.formacraft.server.assembly.AssemblyComponentTypes;
import com.formacraft.server.assembly.AssemblyPortResolver;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Graph-level DSL validation: component types and connection port references.
 */
public final class AssemblyGraphDslValidator {

    private AssemblyGraphDslValidator() {}

    public static List<AssemblyValidationIssue> validate(Object assemblyRoot) {
        List<AssemblyValidationIssue> out = new ArrayList<>();
        if (!(assemblyRoot instanceof Map<?, ?> root)) {
            return out;
        }

        Object graphObj = root.get("graph");
        Map<?, ?> graph = (graphObj instanceof Map<?, ?> gm) ? gm : null;
        Object compsObj = (graph != null) ? graph.get("components") : root.get("components");
        Object connsObj = (graph != null) ? graph.get("connections") : root.get("connections");
        if (!(compsObj instanceof List<?> comps)) {
            return out;
        }

        String compPath = graph != null ? "$.graph.components" : "$.components";
        String connPath = graph != null ? "$.graph.connections" : "$.connections";

        Map<String, Map<String, Object>> byId = new HashMap<>();
        for (int i = 0; i < comps.size(); i++) {
            Object it = comps.get(i);
            if (!(it instanceof Map<?, ?> cm)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> comp = (Map<String, Object>) cm;
            String id = str(comp.get("id"), "").trim();
            if (!id.isEmpty()) {
                byId.put(id, comp);
            }
            String type = normalizeType(comp);
            if (!type.isBlank() && !AssemblyComponentTypes.isKnown(type)) {
                out.add(AssemblyValidationSupport.warn(
                        compPath + "[" + i + "].type",
                        "W_UNKNOWN_COMPONENT_TYPE",
                        "未收录的 component type: " + type + "（可能无法 emit）"
                ));
            }
        }

        if (!(connsObj instanceof List<?> conns) || conns.isEmpty()) {
            return out;
        }

        for (int i = 0; i < conns.size(); i++) {
            Object it = conns.get(i);
            if (!(it instanceof Map<?, ?> conn)) {
                continue;
            }
            validateEndpoint(out, connPath + "[" + i + "].from", conn.get("from"), byId, "from");
            validateEndpoint(out, connPath + "[" + i + "].to", conn.get("to"), byId, "to");
        }
        return out;
    }

    private static void validateEndpoint(
            List<AssemblyValidationIssue> out,
            String path,
            Object endpoint,
            Map<String, Map<String, Object>> byId,
            String role
    ) {
        ParsedEndpoint parsed = parseEndpoint(endpoint);
        if (parsed == null) {
            return;
        }
        if (parsed.componentId() != null && !parsed.componentId().isBlank() && !byId.containsKey(parsed.componentId())) {
            out.add(AssemblyValidationSupport.err(
                    path,
                    "E_CONN_UNKNOWN_COMPONENT",
                    role + " 引用了不存在的组件 id: " + parsed.componentId()
            ));
            return;
        }
        if (parsed.componentId() == null || parsed.portName() == null || parsed.portName().isBlank()) {
            return;
        }
        Map<String, Object> comp = byId.get(parsed.componentId());
        if (comp == null) {
            return;
        }
        Set<String> ports = AssemblyPortResolver.portIds(comp);
        if (!ports.contains(parsed.portName())) {
            out.add(AssemblyValidationSupport.err(
                    path,
                    "E_CONN_UNKNOWN_PORT",
                    role + " 端口不存在: " + parsed.componentId() + "." + parsed.portName()
                            + "（可用: " + String.join(", ", ports) + "）"
            ));
        }
    }

    private record ParsedEndpoint(String componentId, String portName) {}

    private static ParsedEndpoint parseEndpoint(Object endpoint) {
        if (endpoint == null) {
            return null;
        }
        if (endpoint instanceof String s) {
            String t = s.trim();
            if (t.isEmpty()) {
                return null;
            }
            int dot = t.indexOf('.');
            if (dot <= 0 || dot >= t.length() - 1) {
                return new ParsedEndpoint(t, null);
            }
            return new ParsedEndpoint(t.substring(0, dot).trim(), t.substring(dot + 1).trim());
        }
        if (endpoint instanceof Map<?, ?> m) {
            Object cid = m.get("component");
            if (cid == null) {
                cid = m.get("id");
            }
            Object port = m.get("port");
            if (cid == null) {
                return null;
            }
            return new ParsedEndpoint(str(cid, "").trim(), port != null ? str(port, "").trim() : null);
        }
        return null;
    }

    private static String normalizeType(Map<String, Object> comp) {
        String type = str(comp.get("type"), str(comp.get("op"), "")).trim().toUpperCase(Locale.ROOT);
        return type;
    }

    private static String str(Object v, String def) {
        if (v == null) {
            return def;
        }
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? def : s;
    }
}
