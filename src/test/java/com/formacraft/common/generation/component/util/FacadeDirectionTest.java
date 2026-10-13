package com.formacraft.common.generation.component.util;

import com.formacraft.common.llm.dto.GlobalConstraints;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FacadeDirectionTest {
    @Test
    void southHostWorldEqualsLocal() {
        assertEquals("NORTH", FacadeDirection.worldToLocal("NORTH", GlobalConstraints.Facing.SOUTH));
        assertEquals("EAST", FacadeDirection.worldToLocal("EAST", GlobalConstraints.Facing.SOUTH));
        assertEquals("SOUTH", FacadeDirection.worldToLocal("SOUTH", GlobalConstraints.Facing.SOUTH));
        assertEquals("WEST", FacadeDirection.worldToLocal("WEST", GlobalConstraints.Facing.SOUTH));
    }

    @Test
    void eastHostRotatesWorldNorthToLocalWest() {
        // Front (local SOUTH) = world EAST; world NORTH is 90° CCW from front → local EAST? 
        // worldIdx N=0, host E=1; localIdx = 2 + (0-1) = 1 → EAST
        assertEquals("EAST", FacadeDirection.worldToLocal("NORTH", GlobalConstraints.Facing.EAST));
        assertEquals("SOUTH", FacadeDirection.worldToLocal("EAST", GlobalConstraints.Facing.EAST));
        assertEquals("WEST", FacadeDirection.worldToLocal("SOUTH", GlobalConstraints.Facing.EAST));
        assertEquals("NORTH", FacadeDirection.worldToLocal("WEST", GlobalConstraints.Facing.EAST));
    }

    @Test
    void northHostInverts() {
        assertEquals("SOUTH", FacadeDirection.worldToLocal("NORTH", GlobalConstraints.Facing.NORTH));
        assertEquals("WEST", FacadeDirection.worldToLocal("EAST", GlobalConstraints.Facing.NORTH));
        assertEquals("NORTH", FacadeDirection.worldToLocal("SOUTH", GlobalConstraints.Facing.NORTH));
        assertEquals("EAST", FacadeDirection.worldToLocal("WEST", GlobalConstraints.Facing.NORTH));
    }

    @Test
    void westHostQuarterTurn() {
        assertEquals("WEST", FacadeDirection.worldToLocal("NORTH", GlobalConstraints.Facing.WEST));
        assertEquals("NORTH", FacadeDirection.worldToLocal("EAST", GlobalConstraints.Facing.WEST));
        assertEquals("EAST", FacadeDirection.worldToLocal("SOUTH", GlobalConstraints.Facing.WEST));
        assertEquals("SOUTH", FacadeDirection.worldToLocal("WEST", GlobalConstraints.Facing.WEST));
    }

    @Test
    void roundTripLocalWorld() {
        for (var host : GlobalConstraints.Facing.values()) {
            for (String world : new String[]{"NORTH", "EAST", "SOUTH", "WEST"}) {
                String local = FacadeDirection.worldToLocal(world, host);
                assertEquals(world, FacadeDirection.localToWorld(local, host));
            }
        }
    }
}
