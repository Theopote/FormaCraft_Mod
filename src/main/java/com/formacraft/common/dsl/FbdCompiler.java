package com.formacraft.common.dsl;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Pure, bounded JSON-AST compilation. No world access or host-language evaluation. */
public final class FbdCompiler {
    public record Command(String node, String instance, String path, Map<String, Object> op) {
        public Command { op = Collections.unmodifiableMap(new LinkedHashMap<>(op)); }
    }
    public record Compilation(List<Command> commands, long cost, int steps) {
        public Compilation { commands = List.copyOf(commands); }
    }
    private FbdCompiler() {}

    public static Compilation compile(Object raw) { return compile(raw, FbdLimits.DEFAULT); }

    public static Compilation compile(Object raw, FbdLimits limits) {
        inspect(raw, limits);
        Map<String, Object> program = object(raw, "");
        var checker = new FbdTypeChecker(program, limits);
        checker.check();
        return new FbdEvaluator(program, checker.functions, checker.functionPaths, limits).run();
    }

    /** Inspect before serialization/recursive descent, including inactive branches. */
    private static void inspect(Object raw, FbdLimits limits) {
        record Entry(Object value, int depth) {}
        var pending = new ArrayDeque<Entry>();
        pending.push(new Entry(raw, 0));
        int nodes = 0;
        long bytes = 0;
        while (!pending.isEmpty()) {
            Entry e = pending.pop();
            if (++nodes > limits.nodes() || e.depth() > limits.depth())
                throw error("budget_exceeded", "parse", null, "", "Input node/depth budget exceeded");
            if (e.value() instanceof Map<?, ?> map) {
                bytes += 2L + Math.max(0, map.size() - 1);
                if (map.size() > limits.nodes() - nodes - pending.size())
                    throw error("budget_exceeded", "parse", null, "", "Input node budget exceeded");
                for (var item : map.entrySet()) {
                    if (!(item.getKey() instanceof String key) || key.length() > limits.bytes())
                        throw error("schema_violation", "parse", null, "", "Expected bounded string keys");
                    bytes += key.getBytes(StandardCharsets.UTF_8).length + 3L;
                    pending.push(new Entry(item.getValue(), e.depth() + 1));
                }
            } else if (e.value() instanceof List<?> list) {
                bytes += 2L + Math.max(0, list.size() - 1);
                if (list.size() > limits.nodes() - nodes - pending.size())
                    throw error("budget_exceeded", "parse", null, "", "Input node budget exceeded");
                for (Object value : list) pending.push(new Entry(value, e.depth() + 1));
            } else if (e.value() instanceof String s) {
                if (s.length() > limits.bytes()) throw error("budget_exceeded", "parse", null, "", "String too large");
                bytes += s.getBytes(StandardCharsets.UTF_8).length + 2L;
            } else if (!(e.value() instanceof Number) && !(e.value() instanceof Boolean)) {
                throw error("schema_violation", "parse", null, "", "Only JSON values are allowed (null is unsupported)");
            } else {
                bytes += e.value().toString().length();
            }
            if (bytes > limits.bytes()) throw FbdException.budget("parse", null, "", "input_bytes", limits.bytes(), bytes);
        }
        try {
            // The lower-bound pass bounds this allocation even for heavily escaped strings.
            int exactBytes = new ObjectMapper().writeValueAsBytes(raw).length;
            if (exactBytes > limits.bytes())
                throw FbdException.budget("parse", null, "", "input_bytes", limits.bytes(), exactBytes);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw error("schema_violation", "parse", null, "", "Program is not JSON data");
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> object(Object value, String path) {
        if (!(value instanceof Map<?, ?>)) throw error("schema_violation", "parse", null, path, "Expected object");
        return (Map<String, Object>) value;
    }

    static List<?> list(Object value, String path) {
        if (!(value instanceof List<?> result)) throw error("schema_violation", "parse", null, path, "Expected array");
        return result;
    }

    static String string(Object value, String path) {
        if (!(value instanceof String s)) throw error("schema_violation", "parse", null, path, "Expected string");
        return s;
    }

    static void keys(Map<String, Object> map, Set<String> allowed, Set<String> required, String path) {
        for (String k : map.keySet()) if (!allowed.contains(k))
            throw error("unknown_field", "parse", null, path + "/" + k, "Unknown field: " + k);
        for (String k : required) if (!map.containsKey(k))
            throw error("missing_field", "parse", null, path + "/" + k, "Missing field: " + k);
    }

    static FbdException error(String code, String stage, String node, String path, String message) {
        return new FbdException(code, stage, node, path, message);
    }
}
