package com.formacraft.common.component.validate;

import com.formacraft.common.component.ComponentDefinition;
import com.formacraft.common.component.socket.ComponentSocket;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComponentValidatorSocketPlacementTest {

    @Test
    void warnsWhenSocketHasNoPlacementOrigin() {
        ComponentDefinition def = new ComponentDefinition();
        def.id = "host_with_socket";
        def.category = com.formacraft.common.component.ComponentCategory.WALL;
        def.size = new ComponentDefinition.Size();
        def.size.w = 3;
        def.size.h = 3;
        def.size.d = 1;

        ComponentSocket socket = new ComponentSocket();
        socket.id = "main_door";
        def.sockets = List.of(socket);

        ValidationResult result = ComponentValidator.validate(def);
        assertTrue(result.warnings().stream().anyMatch(w -> w.path.contains("socketPlacements")));
        assertFalse(result.hasErrors());
    }
}
