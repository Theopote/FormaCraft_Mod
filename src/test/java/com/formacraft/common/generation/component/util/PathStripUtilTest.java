package com.formacraft.common.generation.component.util;

import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.generation.component.impl.PathComponentGenerator;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PathStripUtilTest {
    @BeforeAll
    static void bootstrap() {
        MinecraftRegistryTestBootstrap.initialize();
    }

    @Test
    void fromToStripIsConnectedAndSmallerThanAabbPad() {
        Map<String, Object> params = new HashMap<>();
        params.put("from", Map.of("x", 0, "y", 0, "z", 0));
        params.put("to", Map.of("x", 10, "y", 0, "z", 0));
        params.put("width", 3);
        params.put("path_block", "minecraft:gravel");
        Component path = new Component(
                "PATH", null, new Vec3i(0, 0, 0), new Dimensions(20, 20, 1), List.of(), params);
        var patches = new PathComponentGenerator().generate(new SemanticComponent("PATH", null, path, "DEFAULT"));
        assertFalse(patches.isEmpty());
        // Full AABB would be 20*20=400; strip along X with width 3 ≈ 11*3 cells.
        assertTrue(patches.size() < 100, "strip should not fill the AABB pad, size=" + patches.size());

        Set<String> xz = new HashSet<>();
        for (BlockPatch p : patches) {
            xz.add(p.dx() + "," + p.dz());
        }
        for (int x = 0; x <= 10; x++) {
            boolean hit = false;
            for (int oz = -1; oz <= 1; oz++) {
                if (xz.contains(x + "," + oz)) {
                    hit = true;
                    break;
                }
            }
            assertTrue(hit, "centerline gap at x=" + x);
        }
    }

    @Test
    void aabbPadWithoutEndpointsStillFillsFootprint() {
        Component path = new Component(
                "PATH", null, new Vec3i(0, 0, 0), new Dimensions(4, 5, 1), List.of(),
                Map.of("path_block", "minecraft:gravel"));
        var patches = new PathComponentGenerator().generate(new SemanticComponent("PATH", null, path, "DEFAULT"));
        assertEquals(4 * 5, patches.size());
    }

    @Test
    void waypointsAreIncludedInPolyline() {
        Map<String, Object> params = new HashMap<>();
        params.put("from", Map.of("x", 0, "y", 0, "z", 0));
        params.put("to", Map.of("x", 6, "y", 0, "z", 0));
        params.put("waypoints", List.of(Map.of("x", 3, "y", 0, "z", 4)));
        params.put("width", 1);
        var ep = PathStripUtil.resolveEndpoints(new Component(
                "PATH", null, new Vec3i(0, 0, 0), new Dimensions(3, 3, 1), List.of(), params));
        assertNotNull(ep);
        assertEquals(3, ep.points().size());
        List<int[]> cells = PathStripUtil.rasterPolyline(ep.points());
        assertTrue(cells.stream().anyMatch(c -> c[0] == 3 && c[2] == 4));
    }
}
