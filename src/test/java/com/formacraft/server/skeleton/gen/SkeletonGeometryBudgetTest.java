package com.formacraft.server.skeleton.gen;

import com.formacraft.common.semantic.*;
import com.formacraft.common.style.SemanticStyleProfile;
import com.formacraft.server.skeleton.gen.geometry.GeometryModifierPipeline;
import net.minecraft.util.math.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SkeletonGeometryBudgetTest {
    private final SemanticPlacementOp base = SemanticPlacementOp.of(new BlockPos(10, 64, -20), SemanticPart.PATH_BASE);
    private GenerationContext context(int budget) {
        return new GenerationContext(null, BlockPos.ORIGIN, budget) {
            @Override public int getBottomY() { return -64; }
            @Override public int getTopYExclusive() { return 320; }
        };
    }
    private SemanticPlacementOp at(BlockPos pos) { return SemanticPlacementOp.of(pos, SemanticPart.PATH_BASE); }
    @Test void expansionOverBudgetRejectsBeforeNextModifier() {
        int[] calls = {0};
        var style = new SemanticStyleProfile("test").bindGeometry(SemanticPart.PATH_BASE, op -> {
            calls[0]++; return List.of(op,at(op.pos().up()),at(op.pos().down()));
        });
        assertThrows(IllegalArgumentException.class, () -> SkeletonBuildPipeline.prepareSemanticOps(context(2),List.of(base,base),style));
        assertEquals(1,calls[0]);
        assertEquals(3,SkeletonBuildPipeline.prepareSemanticOps(context(3),List.of(base),style).size());
    }
    @Test void baseOperationsAndDuplicateExpansionStillConsumeBudget() {
        assertThrows(IllegalArgumentException.class, () -> SkeletonBuildPipeline.prepareSemanticOps(context(1),List.of(base,base),null));
        var style = new SemanticStyleProfile("test").bindGeometry(SemanticPart.PATH_BASE,op->List.of(op,op,op));
        assertThrows(IllegalArgumentException.class, () -> SkeletonBuildPipeline.prepareSemanticOps(context(2),List.of(base),style));
    }
    @Test void overlappingOperationsUseLastCompleteSemanticState() {
        var last = SemanticPlacementOp.of(base.pos(),Direction.WEST,SemanticPart.STAIR_STEP,SemanticRole.TRIM,null,Set.of("last"));
        var other = at(base.pos().east());
        assertEquals(List.of(last,other),GeometryModifierPipeline.applyModifiers(List.of(base,other,last),null));
    }
    @Test void overlappingModifierOutputAlsoUsesLastOperation() {
        var last = SemanticPlacementOp.of(base.pos(),Direction.EAST,SemanticPart.PATH_EDGE);
        var style = new SemanticStyleProfile("test").bindGeometry(SemanticPart.PATH_BASE,op->List.of(op,last));
        assertEquals(List.of(last),GeometryModifierPipeline.applyModifiers(List.of(base),style,2));
    }
    @Test void expansionCannotCrossWorldTopOrBottom() {
        for (int y : List.of(-65,320)) {
            var style = new SemanticStyleProfile("test").bindGeometry(SemanticPart.PATH_BASE,op->List.of(at(new BlockPos(10,y,-20))));
            assertThrows(IllegalArgumentException.class, () -> SkeletonBuildPipeline.prepareSemanticOps(context(10),List.of(base),style));
        }
        assertEquals(2,SkeletonBuildPipeline.prepareSemanticOps(context(2),List.of(at(new BlockPos(0,-64,0)),at(new BlockPos(0,319,0))),null).size());
    }
    @Test void failedExpansionDoesNotAffectNextAttempt() {
        var style = new SemanticStyleProfile("test").bindGeometry(SemanticPart.PATH_BASE,op->List.of(op,op));
        assertThrows(IllegalArgumentException.class, () -> SkeletonBuildPipeline.prepareSemanticOps(context(1),List.of(base),style));
        assertEquals(List.of(base),SkeletonBuildPipeline.prepareSemanticOps(context(1),List.of(base),null));
    }
}
