package com.formacraft.common.compiler.postprocess;

import com.formacraft.common.llm.dto.GlobalConstraints;
import com.formacraft.common.llm.dto.LlmPlanTestFixtures;
import com.formacraft.common.patch.BlockPatch;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TerrainHeightQueryTest {
    @Test
    void strategiesUseWorldCoordinatesAndSampleEachColumnOnce() {
        var patches = List.of(new BlockPatch("place", 2, 1, 3, "minecraft:stone"),
                new BlockPatch("remove", 2, 4, 3, null));
        var shifts = Map.of(GlobalConstraints.TerrainStrategy.PRESERVE, 0,
                GlobalConstraints.TerrainStrategy.ADAPTIVE, 3,
                GlobalConstraints.TerrainStrategy.TERRACE, 6,
                GlobalConstraints.TerrainStrategy.FLATTEN, -1);
        for (var entry : shifts.entrySet()) {
            var queries = new AtomicInteger();
            var processor = new TerrainAdaptationPostProcessor((x, z) -> {
                assertEquals(102, x);
                assertEquals(203, z);
                queries.incrementAndGet();
                return 70;
            });
            var plan = LlmPlanTestFixtures.builder().globalConstraints(new GlobalConstraints(
                    GlobalConstraints.Facing.SOUTH, GlobalConstraints.Symmetry.NONE, entry.getKey())).build();
            var result = processor.process(patches, PostProcessContext.create(plan, new BlockPos(100, 64, 200)));
            assertEquals(1 + entry.getValue(), result.get(0).dy());
            assertEquals(4 + entry.getValue(), result.get(1).dy());
            assertEquals("remove", result.get(1).action());
            assertEquals(entry.getKey() == GlobalConstraints.TerrainStrategy.PRESERVE ? 0 : 1, queries.get());
        }
        assertEquals(1, patches.getFirst().dy());
    }
}
