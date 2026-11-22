package com.formacraft.server.assembly;

import com.formacraft.test.MinecraftRegistryTestBootstrap;
import net.minecraft.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class AssemblyVoxelBridgeOpsTest {
    @BeforeAll
    static void initialize() {
        MinecraftRegistryTestBootstrap.initialize();
    }


    @ParameterizedTest
    @CsvSource({
            "1, 0, 0",
            "2, 0, 1",
            "3, -1, 1",
            "4, -1, 2",
            "5, -2, 2",
            "8, -3, 4",
            "9, -4, 4"
    })
    void placePrismFootprintMatchesExactThickness(int thickness, int expectedMin, int expectedMax) {
        Set<Long> xz = new HashSet<>();
        AssemblyVoxelBridgeOps.placePrism(
                (x, y, z, state) -> xz.add((((long) x) << 32) ^ (z & 0xffffffffL)),
                0, 0, 0, thickness, 1, Blocks.STONE.getDefaultState());
        assertEquals((long) thickness * thickness, xz.size());
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (long key : xz) {
            int x = (int) (key >> 32);
            int z = (int) key;
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x);
            minZ = Math.min(minZ, z);
            maxZ = Math.max(maxZ, z);
        }
        assertEquals(expectedMin, minX);
        assertEquals(expectedMax, maxX);
        assertEquals(expectedMin, minZ);
        assertEquals(expectedMax, maxZ);
    }

    @Test
    void placePrismEvenThicknessIsNotOneCellTooWide() {
        Set<Long> xz = new HashSet<>();
        AssemblyVoxelBridgeOps.placePrism(
                (x, y, z, state) -> xz.add((((long) x) << 32) ^ (z & 0xffffffffL)),
                10, 0, 20, 2, 1, Blocks.STONE.getDefaultState());
        // Old bug: thickness=2 drew 3×3 centered at (10,20). Correct: 2×2 at x∈[10,11], z∈[20,21].
        assertEquals(4, xz.size());
        assertTrue(xz.contains(pack(10, 20)));
        assertTrue(xz.contains(pack(11, 20)));
        assertTrue(xz.contains(pack(10, 21)));
        assertTrue(xz.contains(pack(11, 21)));
        assertFalse(xz.contains(pack(9, 20)));
        assertFalse(xz.contains(pack(10, 19)));
    }

    private static long pack(int x, int z) {
        return (((long) x) << 32) ^ (z & 0xffffffffL);
    }
}
