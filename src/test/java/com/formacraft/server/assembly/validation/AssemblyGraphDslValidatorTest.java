package com.formacraft.server.assembly.validation;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssemblyGraphDslValidatorTest {

    @Test
    void rejectsUnknownPortOnConnection() {
        Map<String, Object> tower = new HashMap<>();
        tower.put("id", "Tower");
        tower.put("type", "SHELL_BOX");
        tower.put("w", 10);
        tower.put("d", 10);
        tower.put("h", 20);

        Map<String, Object> bridge = new HashMap<>();
        bridge.put("id", "Bridge");
        bridge.put("type", "SHELL_BOX");
        bridge.put("w", 6);
        bridge.put("d", 6);
        bridge.put("h", 4);

        Map<String, Object> conn = Map.of(
                "from", "Tower.nonexistent_port",
                "to", "Bridge.bottom_center"
        );

        Map<String, Object> assembly = Map.of(
                "graph", Map.of(
                        "components", List.of(tower, bridge),
                        "connections", List.of(conn)
                )
        );

        List<AssemblyValidationIssue> issues = AssemblyGraphDslValidator.validate(assembly);
        assertTrue(issues.stream().anyMatch(i ->
                "E_CONN_UNKNOWN_PORT".equals(i.code()) && i.path().contains(".from")));
    }

    @Test
    void acceptsValidPortReference() {
        Map<String, Object> a = Map.of("id", "A", "type", "SHELL_BOX", "w", 8, "d", 8, "h", 12);
        Map<String, Object> b = Map.of("id", "B", "type", "SHELL_BOX", "w", 6, "d", 6, "h", 8);
        Map<String, Object> assembly = Map.of(
                "graph", Map.of(
                        "components", List.of(a, b),
                        "connections", List.of(Map.of(
                                "from", "A.top_center",
                                "to", "B.bottom_center"
                        ))
                )
        );
        List<AssemblyValidationIssue> issues = AssemblyGraphDslValidator.validate(assembly);
        assertFalse(issues.stream().anyMatch(i -> i.severity() == AssemblyValidationIssue.Severity.ERROR));
    }
}
