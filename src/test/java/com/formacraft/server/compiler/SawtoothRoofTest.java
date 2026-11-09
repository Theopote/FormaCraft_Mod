package com.formacraft.server.compiler;

import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.generation.component.impl.RoofGenerator;
import com.formacraft.common.llm.dto.*;
import net.minecraft.util.math.BlockPos;
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

    @Test void requestedGlazingDirectionMirrorsThreeBalancedBays() {
        for (String facing : List.of("east", "west", "north", "south")) {
            boolean acrossX = facing.equals("east") || facing.equals("west");
            boolean mirrored = facing.equals("west") || facing.equals("north");
            int span = acrossX ? 13 : 17;
            int last = span - 1;
            var component = new Component("ROOF", "factory", new Vec3i(0, 10, 0), new Dimensions(13, 17, 5),
                    List.of(), Map.of("roof_type", "sawtooth", "roof_block", "minecraft:deepslate_tiles",
                            "sawtooth_glazing_facing", facing, "sawtooth_count", 3,
                            "sawtooth_axis", acrossX ? "z" : "x"));
            var semantic = new SemanticComponent("ROOF", null, component);
            var patches = new RoofGenerator().generate(semantic);
            assertTrue(RoofSurfaceContinuityAudit.inspect(semantic, patches, BlockPos.ORIGIN, Set.of()).isEmpty(), facing);
            var glassStarts = new TreeSet<Integer>();
            var deck = new HashMap<Integer, Integer>();
            for (var patch : patches) {
                if ((acrossX ? patch.dz() : patch.dx()) != 6) continue;
                int across = acrossX ? patch.dx() : patch.dz();
                int axis = mirrored ? last - across : across;
                if ("minecraft:glass".equals(patch.targetBlock())) glassStarts.add(axis);
                if ("minecraft:deepslate_tiles".equals(patch.targetBlock())) deck.merge(axis, patch.dy(), Math::max);
            }
            List<Integer> starts = acrossX ? List.of(0, 5, 9) : List.of(0, 6, 12);
            assertEquals(starts, List.copyOf(glassStarts), facing);
            for (int bay = 0; bay < starts.size(); bay++) {
                int start = starts.get(bay);
                int end = bay == starts.size() - 1 ? last : starts.get(bay + 1) - 1;
                assertEquals(10, deck.get(start), facing);
                assertEquals(14, deck.get(end), facing);
                for (int column = start + 1; column <= end; column++) {
                    assertTrue(deck.get(column) >= deck.get(column - 1), facing);
                }
            }
        }
    }
}
