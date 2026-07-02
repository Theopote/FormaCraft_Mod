package com.formacraft.server.patch;

import com.formacraft.common.component.ComponentDefinition;
import com.formacraft.common.component.ComponentDefinitionCompiler;
import com.formacraft.common.component.transform.BlockStateStringUtil;
import com.formacraft.common.component.transform.Mirror;
import com.formacraft.common.logging.FcaLog;
import com.formacraft.common.patch.BlockPatch;
import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务端根据构件定义或选区扫描生成 BlockPatch（相对 origin）。
 */
public final class ComponentPatchPreviewBuilder {
    private ComponentPatchPreviewBuilder() {}

    private static final FcaLog LOG = FcaLog.of("ComponentPatchPreviewBuilder");

    public static List<BlockPatch> fromComponentDefinition(
            ComponentDefinition def,
            Direction targetFacing,
            Mirror mirror,
            boolean semanticSkin,
            String semanticStyleId,
            long seedBase
    ) {
        if (def == null) return List.of();
        Direction facing = targetFacing != null && targetFacing.getAxis().isHorizontal() ? targetFacing : Direction.SOUTH;
        Mirror m = mirror != null ? mirror : Mirror.NONE;
        return ComponentDefinitionCompiler.compile(def, 0, 0, 0, facing, m, semanticSkin, semanticStyleId, seedBase);
    }

    public static List<BlockPatch> fromWorldSelection(ServerWorld world, BlockPos origin, BlockPos min, BlockPos max) {
        if (world == null || origin == null || min == null || max == null) return List.of();

        int x0 = Math.min(min.getX(), max.getX());
        int y0 = Math.min(min.getY(), max.getY());
        int z0 = Math.min(min.getZ(), max.getZ());
        int x1 = Math.max(min.getX(), max.getX());
        int y1 = Math.max(min.getY(), max.getY());
        int z1 = Math.max(min.getZ(), max.getZ());

        List<BlockPatch> patches = new ArrayList<>();
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    BlockPos p = new BlockPos(x, y, z);
                    BlockState bs = world.getBlockState(p);
                    if (bs == null || bs.isAir()) continue;
                    patches.add(new BlockPatch(
                            BlockPatch.PLACE,
                            x - origin.getX(),
                            y - origin.getY(),
                            z - origin.getZ(),
                            BlockStateStringUtil.fromState(bs)
                    ));
                }
            }
        }
        return patches;
    }

    public static Mirror parseMirror(String raw) {
        if (raw == null || raw.isBlank()) return Mirror.NONE;
        try {
            return Mirror.valueOf(raw.trim().toUpperCase());
        } catch (Throwable t) {
            LOG.debug("parse mirror failed value={}", raw, t);
            return Mirror.NONE;
        }
    }

    public static Direction parseFacing(String raw) {
        if (raw == null || raw.isBlank()) return Direction.SOUTH;
        try {
            Direction d = Direction.valueOf(raw.trim().toUpperCase());
            return d.getAxis().isHorizontal() ? d : Direction.SOUTH;
        } catch (Throwable t) {
            LOG.debug("parse facing failed value={}", raw, t);
            return Direction.SOUTH;
        }
    }
}
