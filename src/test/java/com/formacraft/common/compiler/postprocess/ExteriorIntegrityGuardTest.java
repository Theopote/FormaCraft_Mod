package com.formacraft.common.compiler.postprocess;

import com.formacraft.common.generation.component.util.ComponentFootprintUtil.Bounds;
import com.formacraft.common.llm.dto.LlmPlanTestFixtures;
import com.formacraft.common.patch.BlockPatch;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ExteriorIntegrityGuardTest {
    @Test void capturedCurvedWallAndOverhangAreProtectedOutsideBoundingFaces() {
        var curved = block(1, 3, 2, "minecraft:stone_bricks");
        var overhang = block(-2, 7, 2, "minecraft:stone_bricks");
        var base = context(Set.of());
        var captured = PostProcessContext.create(base.plan(), BlockPos.ORIGIN, base.buildingVolumes(), Set.of(),
                Set.of(new BlockPos(1,3,2), new BlockPos(-2,7,2)));
        var result = ExteriorIntegrityGuard.preserve(List.of(curved, overhang), List.of(), captured);
        assertEquals(2, result.restored());
        assertEquals(Set.of(curved, overhang), new HashSet<>(result.patches()));
    }
    private BlockPatch block(int x, int y, int z, String id) {
        return new BlockPatch(BlockPatch.PLACE, x, y, z, id);
    }
    private PostProcessContext context(Set<BlockPos> reserved) {
        return PostProcessContext.create(LlmPlanTestFixtures.builder().build(), BlockPos.ORIGIN,
                List.of(new PostProcessContext.BuildingVolume("main", new Bounds(0, 0, 0, 6, 8, 6), 4),
                        new PostProcessContext.BuildingVolume("main", new Bounds(6, 0, 2, 10, 5, 6), 4)), reserved);
    }
    @Test void restoresDeletedWallAndAnnexRoofButKeepsMaterialChanges() {
        var wall = block(0, 3, 2, "minecraft:stone_bricks");
        var roof = block(8, 4, 4, "minecraft:stone_bricks");
        var changed = block(0, 4, 2, "minecraft:mossy_stone_bricks");
        var result = ExteriorIntegrityGuard.preserve(List.of(wall, roof,
                block(0, 4, 2, "minecraft:stone_bricks")), List.of(changed,
                new BlockPatch(BlockPatch.REMOVE, 0, 3, 2, "minecraft:air")), context(Set.of()));
        assertEquals(2, result.restored());
        assertEquals(Set.of(wall, roof, changed), new HashSet<>(result.patches()));
    }
    @Test void neverFillsAuthoredOpeningsLShapedVoidOrReservedStairCells() {
        var opening = block(0, 3, 2, "minecraft:air");
        var voidCell = block(8, 3, 0, "minecraft:stone_bricks");
        var stairCell = block(0, 2, 2, "minecraft:stone_bricks");
        var input = List.of(block(0, 3, 2, "minecraft:stone_bricks"), opening, voidCell, stairCell);
        var result = ExteriorIntegrityGuard.preserve(input, List.of(), context(Set.of(new BlockPos(0, 2, 2))));
        assertEquals(0, result.restored());
        assertTrue(result.patches().isEmpty());
    }
    @Test void sharedWallAndInteriorAreNotExteriorButUpperExposedWallIs() {
        var shared = block(5, 3, 3, "minecraft:stone_bricks");
        var upper = block(5, 6, 3, "minecraft:stone_bricks");
        var interior = block(2, 3, 2, "minecraft:stone_bricks");
        var result = ExteriorIntegrityGuard.preserve(List.of(shared, upper, interior), List.of(), context(Set.of()));
        assertEquals(List.of(upper), result.patches());
    }
    @Test void pipelineProtectsExteriorFromInPlaceMutation() {
        var wall = block(0, 3, 2, "minecraft:glass");
        var pipeline = new PostProcessPipeline().add((patches, ctx) -> { patches.clear(); return patches; });
        assertEquals(List.of(wall), pipeline.process(new ArrayList<>(List.of(wall)), context(Set.of())));
    }
}
