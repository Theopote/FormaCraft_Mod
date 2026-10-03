package com.formacraft.server.skeleton.gen;

import com.formacraft.common.geometry.tool.symmetry.*;
import com.formacraft.common.semantic.*;
import com.formacraft.server.skeleton.gen.geometry.GeometryModifierPipeline;
import net.minecraft.util.math.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SkeletonSymmetryTest {
    private final SemanticPlacementOp base = SemanticPlacementOp.of(new BlockPos(12,64,-8),Direction.EAST,
        SemanticPart.STAIR_STEP,SemanticRole.TRIM,null,Set.of("stair"));
    @Test void bothPlanesPreserveMetadataAndReflectFacing() {
        for (var axis : SymmetryPlane.Axis.values()) {
            var symmetry = new SymmetryProcessor(new SymmetryPlane(axis,3));
            var ops = GeometryModifierPipeline.applyModifiersAndConstraints(List.of(base),null,null,symmetry,2);
            assertEquals(base,ops.getFirst()); var mirrored=ops.getLast();
            assertEquals(symmetry.mirror(base.pos()),mirrored.pos());
            assertEquals(base.part(),mirrored.part()); assertEquals(base.role(),mirrored.role());
            assertEquals(base.tags(),mirrored.tags()); assertEquals(base.geometry(),mirrored.geometry());
            assertEquals(axis==SymmetryPlane.Axis.X ? Direction.WEST : Direction.EAST,mirrored.facing());
            assertEquals(axis==SymmetryPlane.Axis.Z ? Direction.SOUTH : Direction.NORTH,symmetry.mirrorFacing(Direction.NORTH));
            assertEquals(Direction.UP,symmetry.mirrorFacing(Direction.UP));
        }
    }
    @Test void explicitDestinationWinsAndOnPlaneDoesNotDuplicate() {
        var symmetry=new SymmetryProcessor(new SymmetryPlane(SymmetryPlane.Axis.X,0));
        var destination=SemanticPlacementOp.of(symmetry.mirror(base.pos()),SemanticPart.PATH_BASE);
        assertEquals(List.of(base,destination),GeometryModifierPipeline.applyModifiersAndConstraints(List.of(base,destination),null,null,symmetry,2));
        var onPlane=SemanticPlacementOp.of(new BlockPos(0,64,0),SemanticPart.PATH_BASE);
        assertEquals(List.of(onPlane),GeometryModifierPipeline.applyModifiersAndConstraints(List.of(onPlane),null,null,symmetry,1));
    }
    @Test void mirrorBudgetRejectsAndRetryIsIndependent() {
        var symmetry=new SymmetryProcessor(new SymmetryPlane(SymmetryPlane.Axis.X,0));
        assertThrows(IllegalArgumentException.class,()->GeometryModifierPipeline.applyModifiersAndConstraints(List.of(base),null,null,symmetry,1));
        assertEquals(2,GeometryModifierPipeline.applyModifiersAndConstraints(List.of(base),null,null,symmetry,2).size());
    }
    @Test void coordinateOverflowIsRejectedInsteadOfWrapping() {
        assertThrows(ArithmeticException.class,()->new SymmetryPlane(SymmetryPlane.Axis.X,Integer.MAX_VALUE).mirror(base.pos()));
    }
    @Test void mirrorCopiesMustPassTheSameConstraints() {
        var constraints=new com.formacraft.common.geometry.tool.GeometryConstraintPipeline();
        constraints.add(pos->pos.getX()>=0);
        var symmetry=new SymmetryProcessor(new SymmetryPlane(SymmetryPlane.Axis.X,0));
        assertEquals(List.of(base),GeometryModifierPipeline.applyModifiersAndConstraints(List.of(base),null,constraints,symmetry,1));
    }
}
