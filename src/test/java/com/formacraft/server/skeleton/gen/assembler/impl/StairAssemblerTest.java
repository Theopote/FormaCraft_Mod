package com.formacraft.server.skeleton.gen.assembler.impl;

import com.formacraft.common.component.*;
import com.formacraft.server.skeleton.gen.GenerationContext;
import net.minecraft.util.math.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StairAssemblerTest {
    private GenerationContext context(int budget, boolean hill) {
        return new GenerationContext(null, new BlockPos(100, 64, 200), budget) {
            @Override public int getSurfaceY(int x, int z) { return hill && x >= 102 ? 80 : 64; }
        };
    }
    @Test void explicitOffsetHeightAndFacingArePreservedWithoutTerrainResnapping() {
        var component = new ComponentSpec(ComponentType.STAIR).offset(1, 8, 2).param("direction", " east ").param("steps", 4);
        var result = new StairAssembler().assemble(context(20, true), null, component);
        assertEquals(4, result.size());
        for (int i = 0; i < 4; i++) {
            assertEquals(new BlockPos(101 + i, 72 + i, 202), result.get(i).pos());
            assertEquals(Direction.EAST, result.get(i).facing());
        }
    }
    @Test void defaultTerrainFlightRejectsHillRatherThanGeneratingJumpingSteps() {
        var component = new ComponentSpec(ComponentType.STAIR).param("direction", "east");
        assertTrue(new StairAssembler().assemble(context(20, true), null, component).isEmpty());
        var flat = new StairAssembler().assemble(context(20, false), null, component);
        assertEquals(5, flat.size());
        for (int i = 0; i < 5; i++) assertEquals(new BlockPos(100 + i, 64 + i, 200), flat.get(i).pos());
    }
    @Test void invalidDirectionOrInsufficientBudgetProducesNoPartialFlight() {
        var assembler = new StairAssembler();
        assertTrue(assembler.assemble(context(20, false), null, new ComponentSpec(ComponentType.STAIR).param("direction", "up")).isEmpty());
        assertTrue(assembler.assemble(context(20, false), null, new ComponentSpec(ComponentType.STAIR).param("direction", "invalid")).isEmpty());
        assertTrue(assembler.assemble(context(3, false), null, new ComponentSpec(ComponentType.STAIR).param("steps", 5)).isEmpty());
        assertTrue(assembler.assemble(context(20, false), null, new ComponentSpec(ComponentType.STAIR).param("steps", 0)).isEmpty());
    }
}
