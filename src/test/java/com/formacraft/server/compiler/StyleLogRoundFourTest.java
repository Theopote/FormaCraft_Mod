package com.formacraft.server.compiler;

import com.formacraft.common.llm.parser.LlmPlanParser;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.test.PatchTestSnapshot;
import com.formacraft.server.assembly.AssemblyCompileDiagnostics;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class StyleLogRoundFourTest {
    @BeforeAll static void bootstrap() { com.formacraft.test.MinecraftRegistryTestBootstrap.initialize(); }
    @ParameterizedTest @ValueSource(ints={1,2,3,4,5,6})
    void actualPlansHaveRoomsAttachedDetailsAndRectangularDoors(int id) throws Exception {
        try (var in = getClass().getResourceAsStream("/style_log_cases_round4/case_" + id + ".json")) {
            var plan = LlmPlanParser.parseAndValidate(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            var patches = ComponentPlanCompiler.compile(plan, BlockPos.ORIGIN, null, null, false);
            var blocks = PatchTestSnapshot.blocks(patches);
            assertFalse(blocks.isEmpty(), () -> String.valueOf(AssemblyCompileDiagnostics.get()));
            if (id == 1) {
                var connected = new HashSet<Vec3i>();
                var queue = new ArrayDeque<Vec3i>(); queue.add(new Vec3i(0,0,0));
                while (!queue.isEmpty()) {
                    var p = queue.remove();
                    if (!blocks.containsKey(p) || !connected.add(p)) continue;
                    for (int[] delta : new int[][]{{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}})
                        queue.add(new Vec3i(p.x()+delta[0],p.y()+delta[1],p.z()+delta[2]));
                }
                var detached = new HashSet<>(blocks.keySet()); detached.removeAll(connected);
                assertTrue(detached.isEmpty(), () -> "detached detail blocks: " + detached);
                assertNull(blocks.get(new Vec3i(0,8,0)), "duplicated slot height must not create roof-level floor plate");
            }
            if (id == 2) for (int x : new int[]{-1,0}) for (int y : new int[]{1,2,3})
                assertNull(blocks.get(new Vec3i(x,y,-6)), "rectangular modern doorway: forbidden arch tags are not features");
            if (id == 4) {
                for (int x : new int[]{-15,15}) {
                    for (int y : new int[]{2,5}) assertTrue(blocks.entrySet().stream().anyMatch(e ->
                            Math.abs(e.getKey().x()-x)<=7 && e.getKey().y()==y && e.getValue().contains("glass")),
                            "both storeys retain raised windows above their floor: " + x + ", y=" + y + " actual=" + blocks.entrySet().stream().filter(e -> e.getValue().contains("glass")).map(e -> e.getKey().toString()).toList());
                    assertNotNull(blocks.get(new Vec3i(x,0,0)), "floor at slot center, offset added once");
                    assertNull(blocks.get(new Vec3i(x,1,0)), "first-floor interior remains empty");
                    assertTrue(blocks.entrySet().stream().anyMatch(e -> e.getKey().x()==x-7 && e.getKey().y()==6), "roof attached at body top");
                }
            }
            if (id == 5) for (int x : new int[]{-11,11}) {
                assertNull(blocks.get(new Vec3i(x,1,0)), "low void ratio must not fill occupied rooms");
                assertTrue(patches.stream().anyMatch(p -> p.dx()==x && p.dy()==1 && p.dz()==0 && BlockPatch.REMOVE.equals(p.action())), "clear existing terrain explicitly");
            }
        }
    }
}
