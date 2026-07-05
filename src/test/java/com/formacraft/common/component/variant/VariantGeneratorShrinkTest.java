package com.formacraft.common.component.variant;

import com.formacraft.common.component.ComponentDefinition;
import com.formacraft.common.component.query.ComponentQuery;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class VariantGeneratorShrinkTest {

    @Test
    void trimsWhenOpeningSmallerThanBase() {
        ComponentDefinition base = new ComponentDefinition();
        base.id = "window_wide";
        base.size = new ComponentDefinition.Size();
        base.size.w = 4;
        base.size.h = 3;
        base.size.d = 1;
        ComponentDefinition.BlockEntry b = new ComponentDefinition.BlockEntry();
        b.dx = 0;
        b.dy = 0;
        b.dz = 0;
        b.block = "minecraft:glass_pane";
        base.blocks = List.of(b);

        ComponentQuery query = new ComponentQuery();
        query.geometry = new ComponentQuery.Geometry();
        query.geometry.openingWidth = 2;
        query.geometry.openingHeight = 2;

        ComponentVariant variant = VariantGenerator.generate(base, query, new Random(1));
        assertNotNull(variant);
        assertEquals(2, variant.trimmedWidth);
        assertEquals(2, variant.trimmedHeight);
    }
}
