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
        ComponentDefinition def = validDefinition();
        ValidationResult result = ComponentValidator.validate(def);
        assertTrue(result.warnings().stream().anyMatch(w -> w.path.contains("socketPlacements")));
        assertFalse(result.hasErrors());
    }

    @Test
    void warnsWhenPlacementListIsEmpty() {
        ComponentDefinition def = validDefinition();
        def.socketPlacements = List.of();
        assertTrue(ComponentValidator.validate(def).warnings().stream()
                .anyMatch(w -> w.path.equals("socketPlacements")));
    }

    @Test
    void matchingPlacementHasNoMissingOriginWarning() {
        ComponentDefinition def = validDefinition();
        ComponentDefinition.SocketPlacement placement = new ComponentDefinition.SocketPlacement();
        placement.id = "main_door";
        def.socketPlacements = List.of(placement);
        assertFalse(ComponentValidator.validate(def).warnings().stream()
                .anyMatch(w -> w.path.equals("socketPlacements")));
    }

    private static ComponentDefinition validDefinition() {
        ComponentDefinition def = new ComponentDefinition();
        def.id = "host_with_socket";
        def.category = com.formacraft.common.component.ComponentCategory.PANEL;
        def.size = new ComponentDefinition.Size();
        def.size.w = 3;
        def.size.h = 3;
        def.size.d = 1;
        def.anchor = new ComponentDefinition.Anchor();
        ComponentDefinition.BlockEntry block = new ComponentDefinition.BlockEntry();
        block.block = "minecraft:stone";
        def.blocks = List.of(block);

        ComponentSocket socket = ComponentSocket.builder("main_door").build();
        def.sockets = List.of(socket);

        return def;
    }
}
