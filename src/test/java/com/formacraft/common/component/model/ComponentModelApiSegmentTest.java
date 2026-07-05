package com.formacraft.common.component.model;

import com.formacraft.common.component.ComponentCategory;
import com.formacraft.common.component.ComponentDefinition;
import com.formacraft.common.component.query.ComponentQuery;
import com.formacraft.common.component.variant.ComponentVariant;
import com.formacraft.common.component.variant.ComponentVariantSpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComponentModelApiSegmentTest {

    @Test
    void stretchesRailingViaSegmentScaler() {
        ComponentDefinition base = buildRailing(3, 2, 1);

        ComponentQuery query = new ComponentQuery();
        query.semantic = new ComponentQuery.Semantic();
        query.semantic.role = "railing";
        query.geometry = new ComponentQuery.Geometry();
        query.geometry.openingWidth = 9;

        ComponentVariant variant = new ComponentVariant(base);
        variant.applyRepeat(ComponentVariantSpec.Axis.X, 3);

        ComponentDefinition out = ComponentModelApi.applyRuntimeVariant(base, query, variant);
        assertNotNull(out);
        assertNotNull(out.size);
        assertTrue(out.size.w >= 7, "expected segment-scaled width >= 7, got " + out.size.w);
    }

    private static ComponentDefinition buildRailing(int w, int h, int d) {
        ComponentDefinition base = new ComponentDefinition();
        base.id = "test.railing.segment";
        base.category = ComponentCategory.RAILING;
        base.size = new ComponentDefinition.Size();
        base.size.w = w;
        base.size.h = h;
        base.size.d = d;

        ComponentDefinition.BlockEntry start = new ComponentDefinition.BlockEntry();
        start.dx = 0;
        start.dy = 0;
        start.dz = 0;
        start.block = "minecraft:oak_fence";

        ComponentDefinition.BlockEntry mid = new ComponentDefinition.BlockEntry();
        mid.dx = 1;
        mid.dy = 0;
        mid.dz = 0;
        mid.block = "minecraft:oak_fence";

        ComponentDefinition.BlockEntry end = new ComponentDefinition.BlockEntry();
        end.dx = 2;
        end.dy = 0;
        end.dz = 0;
        end.block = "minecraft:oak_fence";

        base.blocks = List.of(start, mid, end);
        return base;
    }
}
