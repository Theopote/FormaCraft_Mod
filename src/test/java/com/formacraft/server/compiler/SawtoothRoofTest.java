package com.formacraft.server.compiler;

import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.generation.component.impl.RoofGenerator;
import com.formacraft.common.llm.dto.*;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SawtoothRoofTest {
    @BeforeAll static void bootstrap() { com.formacraft.test.MinecraftRegistryTestBootstrap.initialize(); }
    @Test void factoryRoofHasRepeatedSlopesAndGlazedVerticalFaces() {
        var c = new Component("ROOF", "factory", new Vec3i(0,10,0), new Dimensions(15,20,4), List.of(),
                Map.of("roof_type","sawtooth", "roof_block","minecraft:deepslate_tiles"));
        var patches = new RoofGenerator().generate(new SemanticComponent("ROOF", null, c));
        var roof = new HashMap<Integer,Integer>();
        for (var p : patches) if (p.dx() == 7 && "minecraft:deepslate_tiles".equals(p.targetBlock())) roof.put(p.dz(), p.dy());
        assertTrue(roof.get(4) > roof.get(0));
        assertTrue(roof.get(5) < roof.get(4));
        assertEquals(roof.get(0), roof.get(10));
        assertTrue(patches.stream().anyMatch(p -> "minecraft:glass".equals(p.targetBlock()) && p.dy() > 10));
    }
}
