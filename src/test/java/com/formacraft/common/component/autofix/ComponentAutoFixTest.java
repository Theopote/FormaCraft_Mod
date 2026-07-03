package com.formacraft.common.component.autofix;

import com.formacraft.common.component.ComponentDefinition;
import com.formacraft.common.component.validate.ComponentValidator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComponentAutoFixTest {

    @Test
    void normalizeBlocksWithNegativeCoordinates() {
        ComponentDefinition def = new ComponentDefinition();
        def.id = "test";
        def.size = new ComponentDefinition.Size();
        def.size.w = 14;
        def.size.h = 1;
        def.size.d = 7;

        List<ComponentDefinition.BlockEntry> blocks = new ArrayList<>();
        blocks.add(block(-13, 0, -3));
        blocks.add(block(0, 0, 3));
        def.blocks = blocks;

        ComponentAutoFix.apply(def);

        assertEquals(0, def.blocks.get(0).dx);
        assertEquals(0, def.blocks.get(0).dz);
        assertEquals(14, def.size.w);
        assertEquals(7, def.size.d);
        assertTrue(ComponentValidator.validate(def).warnings().isEmpty());
    }

    private static ComponentDefinition.BlockEntry block(int x, int y, int z) {
        ComponentDefinition.BlockEntry entry = new ComponentDefinition.BlockEntry();
        entry.dx = x;
        entry.dy = y;
        entry.dz = z;
        entry.block = "minecraft:stone";
        return entry;
    }
}
