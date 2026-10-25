package com.formacraft.common.dsl;

import java.util.*;
import static com.formacraft.common.dsl.FbdCompiler.*;

final class FbdTypeChecker {
    final Map<String, Map<String, Object>> functions = new LinkedHashMap<>();
    final Map<String, String> functionPaths = new HashMap<>();
    private final Map<String, Object> program;
    private final FbdLimits limits;
    private final Set<String> ids = new HashSet<>();
    private final Map<String, Set<String>> edges = new LinkedHashMap<>();
    private static final Set<String> NUMERIC = Set.of("int", "scalar");
    private static final Set<String> TYPES = Set.of("int", "scalar", "bool", "role");

    FbdTypeChecker(Map<String, Object> program, FbdLimits limits) { this.program = program; this.limits = limits; }

    void check() {
        keys(program, Set.of("dialect", "seed", "functions", "main"), Set.of("dialect", "seed", "main"), "");
        if (!"fbd/0".equals(program.get("dialect"))) fail("unsupported_dialect", null, "/dialect", "Expected fbd/0");
        Object seed = program.get("seed");
        if (!(seed instanceof Integer || seed instanceof Long) || ((Number) seed).longValue() < 0)
            fail("type_mismatch", null, "/seed", "seed must be a nonnegative signed 64-bit integer");
        int index = 0;
        for (Object raw : list(program.getOrDefault("functions", List.of()), "/functions")) {
            String p = "/functions/" + index++;
            var f = object(raw, p);
            keys(f, Set.of("name", "params", "body", "doc"), Set.of("name", "params", "body"), p);
            String name = identifier(f.get("name"), p + "/name");
            if (functions.putIfAbsent(name, f) != null) fail("duplicate_function", null, p, name);
            functionPaths.put(name, p);
            if (f.containsKey("doc")) string(f.get("doc"), p + "/doc");
            parameters(f, p);
            edges.put(name, new LinkedHashSet<>());
        }
        for (var entry : functions.entrySet()) {
            var env = parameters(entry.getValue(), functionPaths.get(entry.getKey()));
            statements(list(entry.getValue().get("body"), ""), env, entry.getKey(), functionPaths.get(entry.getKey()) + "/body");
        }
        edges.put("<main>", new LinkedHashSet<>());
        statements(list(program.get("main"), "/main"), new HashMap<>(), "<main>", "/main");
        var colors = new HashMap<String, Integer>();
        for (String name : edges.keySet()) cycle(name, colors, 0);
    }

    private Map<String, String> parameters(Map<String, Object> f, String path) {
        var env = new HashMap<String, String>();
        for (Object raw : list(f.get("params"), path + "/params")) {
            var p = object(raw, path + "/params");
            keys(p, Set.of("name", "type"), Set.of("name", "type"), path);
            String name = identifier(p.get("name"), path);
            String type = string(p.get("type"), path);
            if (!TYPES.contains(type)) fail("type_mismatch", null, path, "Unknown parameter type: " + type);
            if (env.putIfAbsent(name, type) != null) fail("param_redefinition", null, path, name);
        }
        return env;
    }

    private void cycle(String name, Map<String, Integer> colors, int depth) {
        if (colors.getOrDefault(name, 0) == 1)
            throw error("call_cycle", "callgraph", null, "/functions/" + name, "Recursive function call: " + name);
        if (colors.getOrDefault(name, 0) == 2) return;
        if (depth > limits.depth()) fail("budget_exceeded", null, "/functions", "Function call depth exceeded");
        colors.put(name, 1);
        for (String next : edges.get(name)) cycle(next, colors, depth + 1);
        colors.put(name, 2);
    }

    private void statements(List<?> body, Map<String, String> env, String fn, String path) {
        var local = new HashSet<String>();
        for (int i = 0; i < body.size(); i++) {
            String p = path + "/" + i;
            var s = object(body.get(i), p);
            String kind = string(s.get("stmt"), p + "/stmt");
            String id = string(s.get("id"), p + "/id");
            if (id.length() > 64 || !id.matches("[a-zA-Z][a-zA-Z0-9_]*")) fail("schema_violation", id, p, "Invalid/oversized node id");
            if (!ids.add(id)) fail("duplicate_node_id", id, p, "Node id must be globally unique");
            switch (kind) {
                case "let" -> {
                    keys(s, Set.of("stmt", "id", "name", "value", "doc"), Set.of("name", "value"), p);
                    String name = identifier(s.get("name"), p + "/name");
                    if (!local.add(name)) fail("let_redefinition", id, p, name);
                    if (s.containsKey("doc")) string(s.get("doc"), p + "/doc");
                    env.put(name, expression(s.get("value"), env, id, p + "/value"));
                }
                case "if" -> {
                    keys(s, Set.of("stmt", "id", "cond", "then", "else"), Set.of("cond", "then"), p);
                    require("bool", expression(s.get("cond"), env, id, p + "/cond"), id, p);
                    statements(list(s.get("then"), p), new HashMap<>(env), fn, p + "/then");
                    statements(list(s.getOrDefault("else", List.of()), p), new HashMap<>(env), fn, p + "/else");
                }
                case "repeat" -> {
                    keys(s, Set.of("stmt", "id", "times", "body"), Set.of("times", "body"), p);
                    if (!expression(s.get("times"), env, id, p + "/times").equals("int")) fail("repeat_not_int", id, p, "repeat.times must be int");
                    if (s.get("times") instanceof Number n && n.longValue() < 0) fail("repeat_negative", id, p, "Negative repeat");
                    var inner = new HashMap<>(env); inner.put("i", "int");
                    statements(list(s.get("body"), p), inner, fn, p + "/body");
                }
                case "with_frame" -> {
                    keys(s, Set.of("stmt", "id", "origin", "body"), Set.of("origin", "body"), p);
                    var origin = list(s.get("origin"), p + "/origin");
                    if (origin.size() != 3) fail("schema_violation", id, p, "origin must have three coordinates");
                    for (Object v : origin) require("int", expression(v, env, id, p), id, p);
                    statements(list(s.get("body"), p), new HashMap<>(env), fn, p + "/body");
                }
                case "call" -> {
                    keys(s, Set.of("stmt", "id", "fn", "args"), Set.of("fn", "args"), p);
                    String target = string(s.get("fn"), p + "/fn");
                    if (!functions.containsKey(target)) fail("unknown_function", id, p, target);
                    var expected = parameters(functions.get(target), p);
                    var args = object(s.get("args"), p + "/args");
                    for (String name : args.keySet()) {
                        String actual = expression(args.get(name), env, id, p + "/args/" + name);
                        if (!expected.containsKey(name)) fail("unknown_param", id, p, name);
                        require(expected.get(name), actual, id, p + "/args/" + name);
                    }
                    for (String name : expected.keySet()) if (!args.containsKey(name)) fail("missing_param", id, p, name);
                    edges.get(fn).add(target);
                }
                case "emit" -> {
                    keys(s, Set.of("stmt", "id", "op", "params"), Set.of("op", "params"), p);
                    String op = string(s.get("op"), p + "/op");
                    var sig = FbdPrimitives.signature(op, id, p);
                    var params = object(s.get("params"), p + "/params");
                    for (String key : params.keySet()) {
                        if (!sig.containsKey(key)) fail("unknown_param", id, p + "/params/" + key, key);
                        Object v = params.get(key);
                        require((String) sig.get(key).get("type"), expression(v, env, id, p + "/params/" + key), id, p + "/params/" + key);
                        if (!(v instanceof Map<?, ?>) && !(v instanceof String str && str.startsWith("$")))
                            FbdPrimitives.value(op, key, v, id, p + "/params/" + key);
                    }
                    for (var field : sig.entrySet()) if (Boolean.TRUE.equals(field.getValue().get("required")) && !params.containsKey(field.getKey()))
                        fail("missing_param", id, p, field.getKey());
                }
                default -> fail("unknown_statement", id, p, kind);
            }
        }
    }

    private String expression(Object raw, Map<String, String> env, String id, String p) {
        if (raw instanceof Boolean) return "bool";
        if (raw instanceof Integer || raw instanceof Long) {
            long value = ((Number) raw).longValue();
            if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) fail("numeric_overflow", id, p, "int is signed 32-bit");
            return "int";
        }
        if (raw instanceof Number n) {
            if (!Double.isFinite(n.doubleValue())) fail("non_finite", id, p, "scalar must be finite");
            return "scalar";
        }
        if (raw instanceof String s) {
            if (s.startsWith("$")) {
                if (!env.containsKey(s.substring(1))) fail("unknown_variable", id, p, s);
                return env.get(s.substring(1));
            }
            if (s.matches("[A-Z][A-Z0-9_]*")) return "enum";
            if (!FbdPrimitives.ROLES.contains(s)) fail("unknown_role", id, p, s);
            return "role";
        }
        var e = object(raw, p);
        if (e.containsKey("rand")) {
            keys(e, Set.of("rand"), Set.of("rand"), p);
            identifier(e.get("rand"), p);
            return "scalar";
        }
        if (e.containsKey("query")) fail("unsupported_query", id, p, "World queries are not enabled in fbd/0");
        keys(e, Set.of("expr", "args"), Set.of("expr", "args"), p);
        String op = string(e.get("expr"), p);
        var args = list(e.get("args"), p);
        int min = switch (op) { case "abs", "floor", "sin", "cos", "!" -> 1; case "lerp" -> 3; default -> 2; };
        int max = Set.of("min", "max").contains(op) ? 8 : min;
        if (args.size() < min || args.size() > max) fail("schema_violation", id, p, "Wrong operator arity: " + op);
        var types = new ArrayList<String>();
        for (Object a : args) types.add(expression(a, env, id, p + "/args"));
        if (Set.of("&&", "||", "!").contains(op)) {
            for (String t : types) require("bool", t, id, p);
            return "bool";
        }
        if (Set.of("==", "!=").contains(op)) {
            if (new HashSet<>(types).size() != 1) fail("type_mismatch", id, p, "Equality requires matching types");
            return "bool";
        }
        if (!Set.of("+", "-", "*", "/", "min", "max", "abs", "floor", "lerp", "sin", "cos", ">", ">=", "<", "<=").contains(op))
            fail("unknown_operator", id, p, op);
        for (String t : types) if (!NUMERIC.contains(t)) fail("type_mismatch", id, p, "Numeric operator requires numbers");
        if (Set.of(">", ">=", "<", "<=").contains(op)) return "bool";
        if (op.equals("floor")) return "int";
        if (Set.of("lerp", "sin", "cos").contains(op)) return "scalar";
        return types.contains("scalar") ? "scalar" : "int";
    }

    private static String identifier(Object raw, String p) {
        String value = string(raw, p);
        if (value.length() > 64 || !value.matches("[a-z][a-zA-Z0-9_]*") || value.equals("i"))
            fail("invalid_identifier", null, p, "Invalid/reserved name: " + value);
        return value;
    }

    private static void require(String wanted, String actual, String id, String p) {
        if (!wanted.equals(actual) && !(wanted.equals("scalar") && actual.equals("int")))
            fail("type_mismatch", id, p, "Expected " + wanted + ", got " + actual);
    }

    private static void fail(String code, String id, String path, String message) {
        throw error(code, "typecheck", id, path, message);
    }
}
