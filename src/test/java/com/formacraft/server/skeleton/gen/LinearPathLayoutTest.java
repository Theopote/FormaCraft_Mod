package com.formacraft.server.skeleton.gen;

import com.formacraft.common.skeleton.*;
import com.formacraft.common.semantic.SemanticPart;
import net.minecraft.util.math.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.function.IntBinaryOperator;
import static org.junit.jupiter.api.Assertions.*;

class LinearPathLayoutTest {
    private static final BlockPos ORIGIN = new BlockPos(100, 64, -200);
    private GenerationContext context(int budget, IntBinaryOperator surface) {
        return new GenerationContext(null, ORIGIN, budget) {
            @Override public int getSurfaceY(int x, int z) { return surface.applyAsInt(x, z); }
            @Override public int getBottomY() { return -64; }
            @Override public int getTopYExclusive() { return 320; }
        };
    }
    private ExecutableSkeletonPlan plan(int width, int length, Direction direction, ExecutableSkeletonPlan.HeightPolicy policy) {
        return new ExecutableSkeletonPlan(SkeletonType.LINEAR_PATH).put("width", width).put("length", length)
                .put("facing", direction).put("conformTerrain", false).put("heightPolicy", policy);
    }
    private List<BlockPos> semantic(GenerationContext ctx, ExecutableSkeletonPlan plan) {
        return new LinearPathSemanticGenerator().generateSemantic(ctx, plan).stream()
                .filter(op -> op.part() == SemanticPart.PATH_BASE).map(op -> op.pos()).toList();
    }
    private List<BlockPos> legacy(GenerationContext ctx, ExecutableSkeletonPlan plan) {
        return new LinearPathGenerator().generate(ctx, plan).stream().map(p -> ORIGIN.add(p.dx(), p.dy(), p.dz())).toList();
    }
    @Test void widthsAndDirectionsHaveExactSharedFootprint() {
        var ctx = context(1000, (x,z) -> 80);
        for (Direction d : List.of(Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST)) {
            for (int width : List.of(1,2,3,4,5,6)) {
                var p = plan(width, 3, d, ExecutableSkeletonPlan.HeightPolicy.FLAT);
                var positions = semantic(ctx, p);
                assertEquals(width * 3, new HashSet<>(positions).size());
                assertEquals(legacy(ctx, p), positions);
            }
        }
    }
    @Test void stepUpIsAppliedByBothRoutesEveryFourRows() {
        var ctx = context(100, (x,z) -> 200);
        var p = plan(1,9,Direction.SOUTH,ExecutableSkeletonPlan.HeightPolicy.STEP_UP);
        assertEquals(List.of(64,64,64,64,65,65,65,65,66), semantic(ctx,p).stream().map(BlockPos::getY).toList());
        assertEquals(legacy(ctx,p), semantic(ctx,p));
    }
    @Test void slopeUsesRequestedSignedRiseAndSingleRowStaysAtOrigin() {
        var ctx = context(100, (x,z) -> 200);
        for (int rise : List.of(4,-4)) {
            var p = plan(1,5,Direction.WEST,ExecutableSkeletonPlan.HeightPolicy.SLOPE).put("height",rise);
            var positions = semantic(ctx,p);
            assertEquals(64 + rise, positions.getLast().getY());
            assertEquals(legacy(ctx,p),positions);
        }
        assertEquals(ORIGIN, semantic(ctx,plan(1,1,Direction.EAST,ExecutableSkeletonPlan.HeightPolicy.SLOPE)).getFirst());
    }
    @Test void terrainFlagAndTerrainPolicyHaveLegacyPrecedence() {
        var ctx = context(100, (x,z) -> 80 + z - ORIGIN.getZ());
        var p = plan(1,3,Direction.SOUTH,ExecutableSkeletonPlan.HeightPolicy.FOLLOW_TERRAIN);
        assertEquals(List.of(80,81,82),semantic(ctx,p).stream().map(BlockPos::getY).toList());
        p.put("heightPolicy",ExecutableSkeletonPlan.HeightPolicy.SLOPE).put("conformTerrain",true);
        assertEquals(legacy(ctx,p),semantic(ctx,p));
        assertEquals(82,semantic(ctx,p).getLast().getY());
    }
    @Test void semanticBudgetCountsEdgeTrimAndRejectsWholeOutput() {
        var p = plan(4,2,Direction.NORTH,ExecutableSkeletonPlan.HeightPolicy.FLAT);
        assertThrows(IllegalArgumentException.class, () -> new LinearPathSemanticGenerator().generateSemantic(context(11,(x,z)->80),p));
        assertEquals(12,new LinearPathSemanticGenerator().generateSemantic(context(12,(x,z)->80),p).size());
        assertThrows(IllegalArgumentException.class, () -> new LinearPathGenerator().generate(context(7,(x,z)->80),p));
        assertEquals(8,new LinearPathGenerator().generate(context(8,(x,z)->80),p).size());
    }
    @Test void worldTopIncludesNegativeBottomAndTrimMustFit() {
        var ctx = context(100,(x,z)->319);
        var p = plan(3,1,Direction.NORTH,ExecutableSkeletonPlan.HeightPolicy.FOLLOW_TERRAIN);
        assertEquals(3,legacy(ctx,p).size());
        assertThrows(IllegalArgumentException.class, () -> semantic(ctx,p));
        assertThrows(IllegalArgumentException.class, () -> legacy(context(100,(x,z)->320),p));
        assertThrows(IllegalArgumentException.class, () -> semantic(context(100,(x,z)->-65),p));
    }
    @Test void verticalFacingAndHugeDimensionsRejectBeforeTerrainSampling() {
        var ctx = context(100,(x,z)-> { fail("must reject before sampling"); return 0; });
        assertThrows(IllegalArgumentException.class, () -> semantic(ctx,plan(1,3,Direction.UP,ExecutableSkeletonPlan.HeightPolicy.FOLLOW_TERRAIN)));
        assertThrows(IllegalArgumentException.class, () -> legacy(ctx,plan(Integer.MAX_VALUE,Integer.MAX_VALUE,Direction.NORTH,ExecutableSkeletonPlan.HeightPolicy.FOLLOW_TERRAIN)));
    }
    @Test void evenWidthTrimStaysOnTwoOuterColumns() {
        var p = plan(4,1,Direction.SOUTH,ExecutableSkeletonPlan.HeightPolicy.FLAT);
        var ops = new LinearPathSemanticGenerator().generateSemantic(context(100,(x,z)->80),p);
        var bases = ops.stream().filter(op->op.part()==SemanticPart.PATH_BASE).map(op->op.pos()).toList();
        var trims = ops.stream().filter(op->op.part()==SemanticPart.PATH_EDGE).map(op->op.pos()).toList();
        assertEquals(List.of(bases.getFirst().up(),bases.getLast().up()),trims);
    }
}
