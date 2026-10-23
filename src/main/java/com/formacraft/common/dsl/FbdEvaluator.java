package com.formacraft.common.dsl;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import static com.formacraft.common.dsl.FbdCompiler.*;

/** Evaluation is local to one request; failure discards all accumulated commands. */
final class FbdEvaluator {
    private final Map<String, Object> program;
    private final Map<String, Map<String, Object>> functions;
    private final Map<String, String> functionPaths;
    private final FbdLimits limits;
    private final List<Command> commands = new ArrayList<>();
    private int steps;
    private int repeats;
    private long cost;

    FbdEvaluator(Map<String, Object> program, Map<String, Map<String, Object>> functions,
                 Map<String, String> functionPaths, FbdLimits limits) {
        this.program = program; this.functions = functions; this.functionPaths = functionPaths; this.limits = limits;
    }

    Compilation run() {
        statements(list(program.get("main"), "/main"), new HashMap<>(), "main", "/main", 0);
        if (commands.stream().noneMatch(c -> !Set.of("PUSH_ORIGIN", "POP_ORIGIN").contains(c.op().get("op"))))
            throw error("empty_program", "evaluate", null, "/main", "Program emits no geometry");
        return new Compilation(commands, cost, steps);
    }

    private void tick(String id, String path) {
        if (++steps > limits.steps() || Thread.currentThread().isInterrupted())
            throw FbdException.budget("evaluate", id, path, "evaluation_steps", limits.steps(), steps);
    }

    private void emit(String id, String instance, Map<String, Object> op, String path) {
        if (commands.size() >= limits.commands())
            throw FbdException.budget("evaluate", id, path, "expanded_commands", limits.commands(), commands.size() + 1L);
        commands.add(new Command(id, instance, path, op));
    }

    private void statements(List<?> body, Map<String, Object> env, String instance, String path, int depth) {
        if (depth > limits.depth()) throw error("budget_exceeded", "evaluate", null, path, "Execution depth exceeded");
        for (int index = 0; index < body.size(); index++) {
            String p = path + "/" + index;
            var s = object(body.get(index), p);
            String id = (String) s.get("id");
            String at = instance + "/" + id;
            tick(id, p);
            switch ((String) s.get("stmt")) {
                case "let" -> env.put((String) s.get("name"), expr(s.get("value"), env, at, id, p + "/value"));
                case "if" -> {
                    boolean condition = (Boolean) expr(s.get("cond"), env, at, id, p + "/cond");
                    String branch = condition ? "then" : "else";
                    statements(list(s.getOrDefault(branch, List.of()), p), new HashMap<>(env), at, p + "/" + branch, depth + 1);
                }
                case "repeat" -> {
                    int count = (Integer) expr(s.get("times"), env, at, id, p + "/times");
                    if (count < 0) throw error("repeat_negative", "evaluate", id, p, "Negative repeat count");
                    if (count > limits.repeats() - repeats)
                        throw FbdException.budget("evaluate", id, p, "repeat_iterations", limits.repeats(), (long) repeats + count);
                    repeats += count;
                    for (int i = 0; i < count; i++) {
                        var inner = new HashMap<>(env); inner.put("i", i);
                        statements(list(s.get("body"), p), inner, at + "[" + i + "]", p + "/body", depth + 1);
                    }
                }
                case "with_frame" -> {
                    var origin = list(s.get("origin"), p);
                    var push = new LinkedHashMap<String, Object>(); push.put("op", "PUSH_ORIGIN");
                    for (int i = 0; i < 3; i++) {
                        int coordinate = (Integer) expr(origin.get(i), env, at, id, p + "/origin/" + i);
                        if (Math.abs((long) coordinate) > 1_000_000) throw error("param_out_of_range", "evaluate", id, p, "Frame offset exceeds 1000000 blocks");
                        push.put(List.of("dx", "dy", "dz").get(i), coordinate);
                    }
                    emit(id, at, push, p);
                    statements(list(s.get("body"), p), new HashMap<>(env), at, p + "/body", depth + 1);
                    emit(id, at, Map.of("op", "POP_ORIGIN"), p);
                }
                case "call" -> {
                    String fn = (String) s.get("fn");
                    var args = new HashMap<String, Object>();
                    for (var entry : object(s.get("args"), p).entrySet())
                        args.put(entry.getKey(), expr(entry.getValue(), env, at, id, p + "/args/" + entry.getKey()));
                    // Widen int arguments only when a scalar parameter requires it.
                    for (Object raw : list(functions.get(fn).get("params"), p)) {
                        var param = object(raw, p); String name = (String) param.get("name");
                        if ("scalar".equals(param.get("type")) && args.get(name) instanceof Integer n) args.put(name, n.doubleValue());
                    }
                    statements(list(functions.get(fn).get("body"), p), args, at + ":" + fn, functionPaths.get(fn) + "/body", depth + 1);
                }
                case "emit" -> {
                    String name = (String) s.get("op");
                    var op = new LinkedHashMap<String, Object>(); op.put("op", name);
                    for (var entry : object(s.get("params"), p).entrySet()) {
                        Object value = expr(entry.getValue(), env, at, id, p + "/params/" + entry.getKey());
                        FbdPrimitives.value(name, entry.getKey(), value, id, p + "/params/" + entry.getKey());
                        if ("role".equals(FbdPrimitives.signature(name, id, p).get(entry.getKey()).get("type")))
                            value = new FbdPrimitives.Role((String) value);
                        op.put(entry.getKey(), value);
                    }
                    long amount = FbdPrimitives.cost(name, op);
                    if (amount > Math.min(limits.samples(), limits.writes()) - cost)
                        throw FbdException.budget("evaluate", id, p, "geometry_bound", Math.min(limits.samples(), limits.writes()),
                                amount > Long.MAX_VALUE - cost ? Long.MAX_VALUE : cost + amount);
                    cost += amount;
                    emit(id, at, op, p);
                }
                default -> throw new AssertionError("Unchecked statement");
            }
        }
    }

    private Object expr(Object raw, Map<String, Object> env, String instance, String id, String path) {
        tick(id, path);
        if (raw instanceof Long n) return n.intValue();
        if (!(raw instanceof Map<?, ?>)) {
            if (raw instanceof String s && s.startsWith("$")) return env.get(s.substring(1));
            return raw;
        }
        var e = object(raw, path);
        if (e.containsKey("rand")) return random(instance, (String) e.get("rand"));
        String op = (String) e.get("expr");
        var args = list(e.get("args"), path);
        Object a = expr(args.getFirst(), env, instance, id, path + "/args/0");
        if (op.equals("&&") && !((Boolean) a)) return false;
        if (op.equals("||") && ((Boolean) a)) return true;
        var values = new ArrayList<Object>(); values.add(a);
        for (int i = 1; i < args.size(); i++) values.add(expr(args.get(i), env, instance, id, path + "/args/" + i));
        if (op.equals("!")) return !((Boolean) a);
        if (op.equals("&&") || op.equals("||")) return values.get(1);
        if (op.equals("==") || op.equals("!=")) {
            boolean same = a.equals(values.get(1)); return op.equals("==") == same;
        }
        double x = ((Number) a).doubleValue();
        double y = values.size() > 1 ? ((Number) values.get(1)).doubleValue() : 0;
        if (Set.of(">", ">=", "<", "<=").contains(op)) return switch (op) {
            case ">" -> x > y; case ">=" -> x >= y; case "<" -> x < y; default -> x <= y;
        };
        try {
            if (values.stream().allMatch(v -> v instanceof Integer) && !Set.of("floor", "sin", "cos", "lerp").contains(op)) {
                int ix = ((Number) a).intValue(), iy = (int) y;
                return switch (op) {
                    case "+" -> Math.addExact(ix, iy);
                    case "-" -> Math.subtractExact(ix, iy);
                    case "*" -> Math.multiplyExact(ix, iy);
                    case "/" -> {
                        if (iy == 0 || ix == Integer.MIN_VALUE && iy == -1) throw new ArithmeticException("Invalid integer division");
                        yield ix / iy;
                    }
                    case "abs" -> { if (ix == Integer.MIN_VALUE) throw new ArithmeticException("abs overflow"); yield Math.abs(ix); }
                    case "min" -> values.stream().mapToInt(v -> (Integer) v).min().orElseThrow();
                    case "max" -> values.stream().mapToInt(v -> (Integer) v).max().orElseThrow();
                    default -> throw new AssertionError("Unchecked integer operator");
                };
            }
            double value = switch (op) {
                case "+" -> x + y; case "-" -> x - y; case "*" -> x * y;
                case "/" -> { if (y == 0) throw new ArithmeticException("Division by zero"); yield x / y; }
                case "abs" -> StrictMath.abs(x); case "floor" -> StrictMath.floor(x);
                case "sin" -> StrictMath.sin(StrictMath.toRadians(x));
                case "cos" -> StrictMath.cos(StrictMath.toRadians(x));
                case "min" -> values.stream().mapToDouble(v -> ((Number) v).doubleValue()).min().orElseThrow();
                case "max" -> values.stream().mapToDouble(v -> ((Number) v).doubleValue()).max().orElseThrow();
                case "lerp" -> x + (y - x) * ((Number) values.get(2)).doubleValue();
                default -> throw new AssertionError("Unchecked scalar operator");
            };
            if (!Double.isFinite(value)) throw new ArithmeticException("Non-finite result");
            if (op.equals("floor")) {
                if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) throw new ArithmeticException("floor overflow");
                return (int) value;
            }
            return value;
        } catch (ArithmeticException e1) {
            throw error("arithmetic_error", "evaluate", id, path, e1.getMessage());
        }
    }

    private double random(String instance, String label) {
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            hash.update(ByteBuffer.allocate(8).putLong(((Number) program.get("seed")).longValue()).array());
            for (String part : List.of(instance, label)) {
                byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
                hash.update(ByteBuffer.allocate(4).putInt(bytes.length).array()); hash.update(bytes);
            }
            return (ByteBuffer.wrap(hash.digest()).getLong() >>> 11) * 0x1.0p-53;
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
