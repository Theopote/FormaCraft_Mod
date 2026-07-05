package com.formacraft.common.generation.component.util;

import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.generation.component.impl.RoofGenerator;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.patch.BlockPatch;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComponentFootprintMaskTest {

    @Test
    void cutCornersExcludesCornerCells() {
        Map<String, Object> params = Map.of("plan_type", "cut_corners", "corner_cut", 2);
        ComponentFootprintMask mask = ComponentFootprintMask.from(null, params, 10, 10);

        assertFalse(mask.contains(0, 0));
        assertFalse(mask.contains(9, 0));
        assertFalse(mask.contains(0, 9));
        assertFalse(mask.contains(9, 9));
        assertTrue(mask.contains(5, 5));
    }

    @Test
    void roofGeneratorSkipsCutCornerCells() {
        Map<String, Object> params = new HashMap<>();
        params.put("plan_type", "cut_corners");
        params.put("corner_cut", 2);
        params.put("roof_type", "flat");

        Component roof = new Component(
                "ROOF",
                "villa_1",
                new Vec3i(0, 5, 0),
                new Dimensions(10, 3, 10),
                List.of(),
                params
        );
        SemanticComponent semantic = new SemanticComponent("ROOF", null, roof, "HUI_STYLE_VILLA");

        List<BlockPatch> patches = new RoofGenerator().generate(semantic);
        assertFalse(patches.isEmpty());

        boolean cornerRoof = patches.stream().anyMatch(p ->
                p.dx() == 0 && p.dz() == 0 && p.targetBlock() != null && !p.targetBlock().contains("air"));
        boolean interiorRoof = patches.stream().anyMatch(p ->
                p.dx() == 5 && p.dz() == 5 && p.targetBlock() != null && !p.targetBlock().contains("air"));

        assertFalse(cornerRoof, "cut corner should not receive roof blocks");
        assertTrue(interiorRoof, "interior footprint should still receive roof blocks");
    }
}
