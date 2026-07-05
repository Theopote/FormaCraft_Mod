package com.formacraft.common.component.variant;

import com.formacraft.common.component.ComponentDefinition;
import com.formacraft.common.component.model.ComponentPrototype;
import com.formacraft.common.component.model.PersistedComponentVariant;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SegmentScalerTest {

    @Test
    void repeatsMidSegmentAlongX() {
        ComponentDefinition def = new ComponentDefinition();
        def.size = new ComponentDefinition.Size();
        def.size.w = 3;
        def.size.h = 1;
        def.size.d = 1;

        ComponentDefinition.BlockEntry a = new ComponentDefinition.BlockEntry();
        a.dx = 0;
        a.block = "minecraft:stone";
        ComponentDefinition.BlockEntry b = new ComponentDefinition.BlockEntry();
        b.dx = 1;
        b.block = "minecraft:stone";
        ComponentDefinition.BlockEntry c = new ComponentDefinition.BlockEntry();
        c.dx = 2;
        c.block = "minecraft:stone";
        def.blocks = List.of(a, b, c);

        StructureTemplate tpl = StructureLoader.fromComponentDefinition(def);

        ComponentPrototype.VariantRules.Scaling scaling = new ComponentPrototype.VariantRules.Scaling();
        scaling.axes = new java.util.HashMap<>();
        ComponentPrototype.VariantRules.Scaling.AxisRule xRule = new ComponentPrototype.VariantRules.Scaling.AxisRule();
        xRule.type = "REPEAT";
        xRule.segment = "SEG_MID_X";
        xRule.min = 1;
        xRule.max = 20;
        scaling.axes.put("X", xRule);

        PersistedComponentVariant.Params.Scale target = new PersistedComponentVariant.Params.Scale();
        target.x = 9;
        target.y = 1;
        target.z = 1;

        VoxelGrid grid = SegmentScaler.applyScaling(tpl, scaling, target);
        int[] range = grid.getAxisRange(Voxel.Axis.X);
        assertTrue(range[1] - range[0] + 1 >= 7);
    }
}
