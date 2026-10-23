package com.formacraft.common.dsl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FbdCompilerTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    static Map<String, Object> parse(String text) throws Exception {
        return JSON.readValue(text, new TypeReference<>() {});
    }
    static Map<String, Object> program(Object... body) {
        return Map.of("dialect", "fbd/0", "seed", 42, "main", List.of(body));
    }
    static Map<String, Object> shell(String id, Object height) {
        return Map.of("stmt", "emit", "id", id, "op", "SHELL_BOX", "params", Map.of("w", 7, "d", 7, "h", height));
    }
    static Map<String, Object> expr(String op, Object... args) { return Map.of("expr", op, "args", List.of(args)); }

    private static void rejects(String code, Object p) {
        assertEquals(code, assertThrows(FbdException.class, () -> FbdCompiler.compile(p)).code);
    }

    @Test void suppliedExamplesAndExpectedRejections() throws Exception {
        var expected = Map.ofEntries(
                Map.entry("05", "call_cycle"), Map.entry("06", "repeat_not_int"),
                Map.entry("07", "schema_violation"), Map.entry("08", "type_mismatch"),
                Map.entry("09", "unknown_variable"), Map.entry("10", "missing_param"),
                Map.entry("11", "repeat_negative"), Map.entry("12", "unknown_param"),
                Map.entry("13", "param_out_of_range"), Map.entry("14", "param_out_of_range"),
                Map.entry("15", "duplicate_function"), Map.entry("16", "param_redefinition"));
        try (var paths = Files.list(Path.of("src/test/resources/fbd"))) {
            for (Path file : paths.sorted().toList()) {
                var p = parse(Files.readString(file));
                String name = file.getFileName().toString();
                if (name.equals("invalid_world_query.json")) rejects("unsupported_query", p);
                else if (name.startsWith("invalid_")) rejects(expected.get(name.substring(8, 10)), p);
                else assertFalse(FbdCompiler.compile(p).commands().isEmpty(), name);
            }
        }
    }

    @Test void functionsAndFramesExpandWithoutUnbalancedOrigins() throws Exception {
        var p = parse(Files.readString(Path.of("src/test/resources/fbd/01_two_wing_library.json")));
        var compiled = FbdCompiler.compile(p);
        assertEquals(13, compiled.commands().size()); // 7 primitives, 3 scope pairs
        int frames = 0;
        for (var command : compiled.commands()) {
            if (command.op().get("op").equals("PUSH_ORIGIN")) frames++;
            if (command.op().get("op").equals("POP_ORIGIN")) frames--;
            assertTrue(frames >= 0);
        }
        assertEquals(0, frames);
        assertEquals(compiled, FbdCompiler.compile(p));
        assertNotEquals(compiled.commands().get(1).instance(), compiled.commands().get(6).instance());
    }

    @Test void signedIntegerArithmeticAndScalarQuantizationAreExplicit() {
        var result = FbdCompiler.compile(program(shell("a", expr("+", 8, expr("/", -5, 2))),
                shell("b", expr("floor", 6.9))));
        assertEquals(6, result.commands().getFirst().op().get("h"));
        assertEquals(6, result.commands().get(1).op().get("h"));
        rejects("arithmetic_error", program(shell("a", expr("/", 6, 0))));
        rejects("arithmetic_error", program(shell("a", expr("+", Integer.MAX_VALUE, 1))));
        rejects("arithmetic_error", program(shell("a", expr("floor", 1e100))));
        rejects("type_mismatch", program(shell("a", 6.0)));
    }

    @Test void allBranchesAndUncalledFunctionsAreChecked() {
        rejects("unknown_variable", program(Map.of("stmt", "if", "id", "if1", "cond", false,
                "then", List.of(shell("a", "$missing")), "else", List.of(shell("b", 6)))));
        rejects("unknown_field", Map.of("dialect", "fbd/0", "seed", 1, "main", List.of(shell("a", 6)), "hostCall", "exec"));
        rejects("duplicate_node_id", program(shell("a", 6), shell("a", 6)));
        rejects("invalid_identifier", program(Map.of("stmt", "let", "id", "a", "name", "i", "value", false)));
    }

    @Test void conditionalShortCircuitAvoidsUnexecutedArithmeticErrors() {
        var condition = expr("&&", false, expr(">", expr("/", 1, 0), 0));
        var compiled = FbdCompiler.compile(program(Map.of("stmt", "if", "id", "a", "cond", condition,
                "then", List.of(shell("b", 7)), "else", List.of(shell("c", 8)))));
        assertEquals(8, compiled.commands().getFirst().op().get("h"));
    }

    @Test void primitiveContractRejectsUnknownEnumsRolesAndCorrectsReferenceRanges() {
        rejects("invalid_enum", program(Map.of("stmt", "emit", "id", "a", "op", "SURFACE_PATTERN",
                "params", Map.of("face", "BANANA", "pattern", "GRID", "x0", 0, "x1", 0, "y0", 0, "y1", 0, "z0", 0, "z1", 0))));
        var p = Map.of("stmt", "emit", "id", "a", "op", "SHELL_BOX",
                "params", Map.of("w", 7, "d", 7, "h", 6, "twistTurns", -1.0, "wall", "wall"));
        assertInstanceOf(FbdPrimitives.Role.class, FbdCompiler.compile(program(p)).commands().getFirst().op().get("wall"));
        rejects("param_out_of_range", program(Map.of("stmt", "emit", "id", "a", "op", "SHELL_BOX",
                "params", Map.of("w", 7, "d", 7, "h", 6, "twistTurns", 3.0))));
        rejects("unknown_role", program(Map.of("stmt", "let", "id", "a", "name", "mat", "value", "minecraft:stone")));
    }

    @Test void dynamicRangesAreCheckedAndFailureReturnsNoPartialCompilation() {
        rejects("param_out_of_range", program(shell("good", 6), shell("bad", expr("+", 254, 2))));
        var e = assertThrows(FbdException.class, () -> FbdCompiler.compile(program(shell("good", 6), shell("bad", expr("/", 1, 0)))));
        assertEquals("bad", e.node);
        assertTrue(e.path.endsWith("/params/h"));
        assertEquals("evaluate", e.diagnostic().get("stage"));
    }

    @Test void repeatAndWorkBudgetsCoverNestedExpansionAndInactiveInput() {
        rejects("budget_exceeded", program(Map.of("stmt", "repeat", "id", "a", "times", 5001, "body", List.of(shell("b", 6)))));
        rejects("budget_exceeded", program(Map.of("stmt", "repeat", "id", "a", "times", 100,
                "body", List.of(Map.of("stmt", "repeat", "id", "b", "times", 100, "body", List.of())))));
        rejects("budget_exceeded", program(Map.of("stmt", "emit", "id", "a", "op", "SURFACE_PATTERN", "params",
                Map.of("face", "NORTH", "pattern", "GRID", "x0", -1000000, "x1", 1000000,
                        "y0", -1000000, "y1", 1000000, "z0", 0, "z1", 0))));
        rejects("budget_exceeded", Map.of("dialect", "fbd/0", "seed", 1, "main", List.of(), "padding", "a".repeat(65537)));
    }

    @Test void independentRandomStreamsSurviveInsertionAndVaryAcrossLoopInstances() {
        var roof = Map.of("stmt", "emit", "id", "roof1", "op", "ROOF_COVER", "params",
                Map.of("type", "HIP", "w", 7, "d", 7, "y", 6, "cornerLift", Map.of("rand", "lift")));
        var first = FbdCompiler.compile(program(roof));
        var inserted = FbdCompiler.compile(program(shell("unrelated", 6), roof));
        assertEquals(first.commands().getFirst().op(), inserted.commands().get(1).op());
        assertEquals(first.commands().getFirst().instance(), inserted.commands().get(1).instance());
        var loop = FbdCompiler.compile(program(Map.of("stmt", "repeat", "id", "loop", "times", 2, "body", List.of(roof))));
        assertNotEquals(loop.commands().getFirst().op().get("cornerLift"), loop.commands().get(1).op().get("cornerLift"));
    }
}
