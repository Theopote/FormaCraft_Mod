package com.formacraft.common.component.socket;

import com.formacraft.common.component.ComponentDefinition;
import org.junit.jupiter.api.Test;
import net.minecraft.util.math.BlockPos;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SocketPlacementResolverTest {

    @Test
    void resolvesExplicitSocketPlacement() {
        ComponentDefinition def = new ComponentDefinition();
        def.anchor = new ComponentDefinition.Anchor();
        def.anchor.facing = "SOUTH";

        ComponentDefinition.SocketPlacement sp = new ComponentDefinition.SocketPlacement();
        sp.id = "main_door";
        sp.dx = 2;
        sp.dy = 0;
        sp.dz = 1;
        def.socketPlacements = List.of(sp);

        BlockPos origin = SocketPlacementResolver.resolveLocalOrigin(def, "main_door", null);
        assertEquals(new BlockPos(2, 0, 1), origin);
    }

    @Test
    void infersOpeningOnFrontFaceWhenNoPlacementRecorded() {
        ComponentDefinition def = new ComponentDefinition();
        def.anchor = new ComponentDefinition.Anchor();
        def.anchor.facing = "SOUTH";

        ComponentDefinition.BlockEntry b1 = new ComponentDefinition.BlockEntry();
        b1.dx = 0; b1.dy = 0; b1.dz = 0;
        ComponentDefinition.BlockEntry b2 = new ComponentDefinition.BlockEntry();
        b2.dx = 2; b2.dy = 2; b2.dz = 0;
        def.blocks = List.of(b1, b2);

        ComponentSocket socket = ComponentSocket.builder("opening")
                .size(SizeConstraint.rect(2, 3, 2, 3))
                .build();

        BlockPos origin = SocketPlacementResolver.resolveLocalOrigin(def, "opening", socket);
        assertEquals(0, origin.getY());
        assertEquals(0, origin.getZ());
    }

    @Test
    void infersOpeningOnEastFace() {
        ComponentDefinition def = new ComponentDefinition();
        def.anchor = new ComponentDefinition.Anchor();
        def.anchor.facing = "EAST";

        ComponentDefinition.BlockEntry b1 = new ComponentDefinition.BlockEntry();
        b1.dx = 0; b1.dy = 0; b1.dz = 0;
        ComponentDefinition.BlockEntry b2 = new ComponentDefinition.BlockEntry();
        b2.dx = 2; b2.dy = 2; b2.dz = 4;
        def.blocks = List.of(b1, b2);

        ComponentSocket socket = ComponentSocket.builder("opening")
                .size(SizeConstraint.rect(2, 3, 2, 3))
                .build();

        BlockPos origin = SocketPlacementResolver.resolveLocalOrigin(def, "opening", socket);
        assertEquals(2, origin.getX());
        assertEquals(0, origin.getY());
        assertEquals(1, origin.getZ());
    }

    @Test
    void infersOpeningOnWestFace() {
        ComponentDefinition def = new ComponentDefinition();
        def.anchor = new ComponentDefinition.Anchor();
        def.anchor.facing = "WEST";

        ComponentDefinition.BlockEntry b1 = new ComponentDefinition.BlockEntry();
        b1.dx = 0; b1.dy = 0; b1.dz = 0;
        ComponentDefinition.BlockEntry b2 = new ComponentDefinition.BlockEntry();
        b2.dx = 2; b2.dy = 2; b2.dz = 4;
        def.blocks = List.of(b1, b2);

        ComponentSocket socket = ComponentSocket.builder("opening")
                .size(SizeConstraint.rect(2, 3, 2, 3))
                .build();

        BlockPos origin = SocketPlacementResolver.resolveLocalOrigin(def, "opening", socket);
        assertEquals(0, origin.getX());
        assertEquals(0, origin.getY());
        assertEquals(1, origin.getZ());
    }
}
