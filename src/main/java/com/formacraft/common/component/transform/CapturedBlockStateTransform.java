package com.formacraft.common.component.transform;

import com.formacraft.common.patch.BlockPatchTargetResolver;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.math.Direction;

/** Native rotation/mirroring retains stair shape, log axis and connection properties. */
public final class CapturedBlockStateTransform {
    private CapturedBlockStateTransform() {}
    public static String apply(String captured, Direction fromFacing, ComponentTransform transform) {
        var state = BlockPatchTargetResolver.parse(captured);
        if (state == null) return BlockStateStringUtil.withTransformedFacing(captured, fromFacing, transform);
        if (transform == null) transform = ComponentTransform.IDENTITY;
        if (transform.mirror() == Mirror.X) state = state.mirror(net.minecraft.util.BlockMirror.FRONT_BACK);
        else if (transform.mirror() == Mirror.Z) state = state.mirror(net.minecraft.util.BlockMirror.LEFT_RIGHT);
        int steps = Math.floorMod(index(transform.facing()) - index(fromFacing), 4);
        for (int i = 0; i < steps; i++) state = state.rotate(BlockRotation.CLOCKWISE_90);
        return BlockStateStringUtil.fromState(state);
    }
    private static int index(Direction direction) {
        if (direction == null) return 0;
        return switch (direction) { case SOUTH -> 0; case WEST -> 1; case NORTH -> 2; case EAST -> 3; default -> 0; };
    }
}
