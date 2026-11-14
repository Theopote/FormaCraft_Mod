package com.formacraft.common.component;

import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.component.group.PlayerComponentGroupExpander;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Vec3i;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ComponentExpansionInputsTest {
    @Test
    void unrelatedComponentsAndUnavailableLibraryRemainNoOps() {
        var semantic = component(List.of());
        assertNull(PlayerComponentExpander.tryExpandResult(semantic, Path.of("."), 42));
        assertNull(PlayerComponentGroupExpander.tryExpandResult(semantic, Path.of("."), 42));
        assertNull(PlayerComponentExpander.tryExpandResult(component(List.of("component_request:{")), null, 42));
        assertNull(PlayerComponentGroupExpander.tryExpandResult(component(List.of("group_request:{")), null, 42));
    }

    @Test
    void invalidRequestsReturnDiagnosticsWithoutWorldAccess() {
        var single = PlayerComponentExpander.tryExpandResult(
                component(List.of("component_request:{")), Path.of("."), 42);
        var group = PlayerComponentGroupExpander.tryExpandResult(
                component(List.of("group_request:{")), Path.of("."), 42);
        assertEquals(ComponentGenerationOutcome.Status.INVALID_INPUT, single.status());
        assertEquals(ComponentGenerationOutcome.Status.INVALID_INPUT, group.status());
    }

    private static SemanticComponent component(List<String> features) {
        return new SemanticComponent("DECOR_DETAIL", null,
                new Component("DECOR_DETAIL", null, new Vec3i(0, 0, 0), null, features, Map.of()));
    }
}
