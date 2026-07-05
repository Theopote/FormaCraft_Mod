package com.formacraft.common.component.variant;

import com.formacraft.common.component.ComponentDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ComponentVariantApplierRepeatTest {

    @Test
    void repeatsAlongXAxis() {
        ComponentDefinition base = new ComponentDefinition();
        base.id = "railing_test";
        ComponentDefinition.BlockEntry b = new ComponentDefinition.BlockEntry();
        b.dx = 0;
        b.dy = 0;
        b.dz = 0;
        b.block = "minecraft:oak_fence";
        base.blocks = List.of(b);
        base.size = new ComponentDefinition.Size();
        base.size.w = 1;
        base.size.h = 1;
        base.size.d = 1;

        ComponentVariant variant = new ComponentVariant(base);
        variant.applyRepeat(ComponentVariantSpec.Axis.X, 3);

        ComponentDefinition out = ComponentVariantApplier.apply(base, variant);
        assertEquals(3, out.blocks.size());
        assertEquals(0, out.blocks.get(0).dx);
        assertEquals(1, out.blocks.get(1).dx);
        assertEquals(2, out.blocks.get(2).dx);
    }
}
