package com.formacraft.common.component.variant;

import com.formacraft.common.component.ComponentDefinition;
import com.formacraft.common.component.query.ComponentQuery;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertTrue;

class VariantGeneratorRepeatTest {

    @Test
    void repeatsRailingWhenTargetWidthExceedsBase() {
        ComponentDefinition base = new ComponentDefinition();
        base.id = "railing.repeat.test";
        base.category = com.formacraft.common.component.ComponentCategory.RAILING;
        base.size = new ComponentDefinition.Size();
        base.size.w = 3;
        base.size.h = 2;
        base.size.d = 1;
        ComponentDefinition.BlockEntry b = new ComponentDefinition.BlockEntry();
        b.dx = 0;
        b.dy = 0;
        b.dz = 0;
        b.block = "minecraft:oak_fence";
        base.blocks = List.of(b);

        ComponentQuery query = new ComponentQuery();
        query.semantic = new ComponentQuery.Semantic();
        query.semantic.role = "railing";
        query.geometry = new ComponentQuery.Geometry();
        query.geometry.openingWidth = 9;

        ComponentVariant variant = VariantGenerator.generate(base, query, new Random(2));
        assertTrue(variant.repeatCount >= 3);
    }
}
