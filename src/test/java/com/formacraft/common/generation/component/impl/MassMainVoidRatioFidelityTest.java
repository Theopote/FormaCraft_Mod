package com.formacraft.common.generation.component.impl;

import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.GlobalConstraints;
import com.formacraft.common.llm.dto.Slot;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MassMain fidelity: explicit void_ratio must not be overridden by size-heuristic interior fill.
 */
class MassMainVoidRatioFidelityTest {

    @BeforeAll
    static void bootstrap() {
        MinecraftRegistryTestBootstrap.initialize();
    }

    @Test
    void lowExplicitVoidRatioKeepsSolidInterior() {
        Map<String, Object> params = new HashMap<>();
        params.put("void_ratio", 0.0);
        params.put("roof_type", "none");
        params.put("window_style", "none");
        params.put("entrance_type", "none");
        Component c = new Component(
                "MASS_MAIN",
                "body",
                new Vec3i(0, 0, 0),
                new Dimensions(8, 8, 6),
                List.of(),
                params);
        Slot slot = new Slot("body", new Vec3i(0, 0, 0), GlobalConstraints.Facing.SOUTH, "HOUSE", null, null);
        List<BlockPatch> patches = new MassMainGenerator().generate(
                new SemanticComponent("MASS_MAIN", slot, c, "DEFAULT", null, null));
        assertFalse(patches.isEmpty());

        // Solid fill: interior cells should be occupied (not hollow).
        long interiorSolid = patches.stream()
                .filter(p -> p.dx() > 1 && p.dx() < 6 && p.dz() > 1 && p.dz() < 6
                        && p.dy() > 0 && p.dy() < 5)
                .count();
        assertTrue(interiorSolid > 10,
                "void_ratio=0 must keep solid mass; interior solid patches=" + interiorSolid);
    }
}
