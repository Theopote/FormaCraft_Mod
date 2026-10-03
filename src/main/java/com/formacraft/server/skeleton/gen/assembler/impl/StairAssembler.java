package com.formacraft.server.skeleton.gen.assembler.impl;

import com.formacraft.common.component.ComponentSpec;
import com.formacraft.common.skeleton.SkeletonParamParsers;
import com.formacraft.common.component.ComponentType;
import com.formacraft.common.semantic.SemanticPart;
import com.formacraft.common.semantic.SemanticPlacementOp;
import com.formacraft.common.skeleton.ExecutableSkeletonPlan;
import com.formacraft.server.skeleton.gen.GenerationContext;
import com.formacraft.server.skeleton.gen.assembler.ComponentAssembler;
import com.formacraft.server.skeleton.gen.assembler.util.SkeletonHelper;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.List;

/** Legacy straight-flight API. Registered but not invoked by the current SkeletonBuildPipeline.
 * Explicit component offsets preserve the origin-relative Y; zero offsets use the helper/terrain entry.
 * Endpoint names from/to are not resolved by this API. */
public class StairAssembler implements ComponentAssembler {

    @Override
    public List<SemanticPlacementOp> assemble(
            GenerationContext ctx,
            ExecutableSkeletonPlan skeleton,
            ComponentSpec component
    ) {
        if (ctx == null || component == null || component.type != ComponentType.STAIR) return List.of();
        int steps = SkeletonParamParsers.componentInt(component, "steps", 5);
        if (steps <= 0 || steps > ctx.maxOps || steps > 4096) {
            com.formacraft.FormacraftMod.LOGGER.warn("StairAssembler rejected invalid/over-budget steps: {}", steps);
            return List.of();
        }
        Direction dir = parseDirection(getStringParam(component, "direction", "north"));
        if (dir == null || !dir.getAxis().isHorizontal()) {
            com.formacraft.FormacraftMod.LOGGER.warn("StairAssembler requires horizontal direction");
            return List.of();
        }
        boolean explicit = component.offsetX != 0 || component.offsetY != 0 || component.offsetZ != 0;
        BlockPos start = explicit ? ctx.origin.add(component.offsetX, component.offsetY, component.offsetZ)
            : SkeletonHelper.getStairStart(ctx, skeleton);
        if (start == null) start = ctx.origin;
        if (!explicit) start = new BlockPos(start.getX(), ctx.getSurfaceY(start.getX(), start.getZ()), start.getZ());
        List<SemanticPlacementOp> ops = new ArrayList<>(steps);
        for (int i = 0; i < steps; i++) {
            BlockPos pos = start.offset(dir, i).up(i);
            if (!explicit && ctx.getSurfaceY(pos.getX(), pos.getZ()) > pos.getY()) {
                com.formacraft.FormacraftMod.LOGGER.warn("StairAssembler rejected terrain-intersecting flight at {}", pos);
                return List.of();
            }
            ops.add(SemanticPlacementOp.of(pos, dir, SemanticPart.STAIR_STEP));
        }
        return ops;
    }


    private static String getStringParam(ComponentSpec component, String key, String defaultValue) {
        Object v = component.params == null ? null : component.params.get(key);
        if (v instanceof String s) return s;
        if (v != null) return String.valueOf(v);
        return defaultValue;
    }

    private static Direction parseDirection(String dirStr) {
        if (dirStr == null || dirStr.isBlank()) return Direction.NORTH;
        try {
            return Direction.valueOf(dirStr.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}

