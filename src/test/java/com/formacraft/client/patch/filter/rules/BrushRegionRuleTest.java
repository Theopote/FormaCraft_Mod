package com.formacraft.client.patch.filter.rules;

import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.patch.filter.PatchRuleContext;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class BrushRegionRuleTest {
    @Test void footprintKeepsHolesAndAllowsVerticalBuildings() {
        var rule = new BrushRegionRule(List.of(new BlockPos(-2, 64, 3), new BlockPos(0, 70, 3)));
        var context = new PatchRuleContext(new BlockPos(-2, 64, 3));
        assertTrue(rule.allow(new BlockPatch(BlockPatch.PLACE, 0, 100, 0, "minecraft:stone"), context));
        assertFalse(rule.allow(new BlockPatch(BlockPatch.PLACE, 1, 0, 0, "minecraft:stone"), context));
        assertTrue(rule.allow(new BlockPatch(BlockPatch.PLACE, 2, -10, 0, "minecraft:stone"), context));
    }

    @Test void emptyBrushDoesNotConstrain() {
        var rule = new BrushRegionRule(List.of());
        assertTrue(rule.allow(new BlockPatch(BlockPatch.REMOVE, 0, 0, 0, null), new PatchRuleContext(BlockPos.ORIGIN)));
    }
}
