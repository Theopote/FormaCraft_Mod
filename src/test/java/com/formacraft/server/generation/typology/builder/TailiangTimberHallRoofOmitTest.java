package com.formacraft.server.generation.typology.builder;

import com.formacraft.common.build.PlannedBlock;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Acceptance: hall roof_type=none must skip roof surfaces, not only stamp JSON. */
class TailiangTimberHallRoofOmitTest {

    @BeforeAll
    static void bootstrap() {
        com.formacraft.test.MinecraftRegistryTestBootstrap.initialize();
    }

    @Test
    void omitRoofRemovesHallRoofTilesButKeepsTimberFrame() {
        Map<String, Object> withRoof = baseParams();
        withRoof.put("roofType", "xie_shan");
        Map<String, Object> omit = baseParams();
        omit.put("roof_type", "none");

        var origin = new BlockPos(0, 64, 0);
        var roofed = TailiangTimberHallBuilder.generate(withRoof, origin, null, null);
        var bare = TailiangTimberHallBuilder.generate(omit, origin, null, null);

        assertFalse(roofed.getBlocks().isEmpty());
        assertFalse(bare.getBlocks().isEmpty());
        long roofTiles = countRoofish(roofed.getBlocks(), origin);
        long bareTiles = countRoofish(bare.getBlocks(), origin);
        assertTrue(roofTiles > 20, "expected chinese hall roof tiles, got " + roofTiles);
        assertEquals(0, bareTiles, "omit roof must not emit deepslate roof tiles/stairs/slabs");
        assertTrue(bare.getBlocks().size() > 100, "timber frame / walls must remain");
    }

    private static Map<String, Object> baseParams() {
        Map<String, Object> p = new HashMap<>();
        p.put("typology_id", TailiangTimberHallBuilder.TYPOLOGY_ID);
        p.put("width", 21);
        p.put("depth", 13);
        p.put("baysX", 7);
        p.put("baysZ", 4);
        p.put("facing", "SOUTH");
        p.put("anchorMode", "center");
        p.put("includeSubEaves", false);
        p.put("includeBrackets", false);
        return p;
    }

    private static long countRoofish(java.util.List<PlannedBlock> blocks, BlockPos origin) {
        return blocks.stream()
                .filter(b -> b != null && b.getTargetState() != null && !b.getTargetState().isAir())
                .filter(b -> b.getPos().getY() >= origin.getY() + 8)
                .filter(b -> {
                    var s = b.getTargetState();
                    return s.isOf(Blocks.DEEPSLATE_TILES)
                            || s.isOf(Blocks.DEEPSLATE_BRICK_STAIRS)
                            || s.isOf(Blocks.DEEPSLATE_TILE_SLAB);
                })
                .count();
    }
}
