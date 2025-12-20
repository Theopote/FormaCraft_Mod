package com.formacraft.common.patch;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.util.List;

/**
 * Patch 执行器：在服务端世界应用增量修改。
 *
 * 约定：
 * - place/replace：setBlockState(target)
 * - remove：setBlockState(AIR)
 */
public final class PatchExecutor {
    private PatchExecutor() {}

    public static void apply(ServerWorld world, BlockPos origin, List<BlockPatch> patches) {
        if (world == null || origin == null || patches == null || patches.isEmpty()) return;

        for (BlockPatch p : patches) {
            if (p == null) continue;
            BlockPos pos = origin.add(p.dx(), p.dy(), p.dz());

            String action = p.action() == null ? "" : p.action().toLowerCase();
            if (BlockPatch.REMOVE.equals(action)) {
                world.setBlockState(pos, Blocks.AIR.getDefaultState(), 3);
                continue;
            }

            // place/replace：目标方块
            BlockState target = parseBlockState(p.targetBlock());
            world.setBlockState(pos, target, 3);
        }
    }

    private static BlockState parseBlockState(String id) {
        if (id == null || id.isBlank()) return Blocks.AIR.getDefaultState();
        try {
            Identifier ident = Identifier.tryParse(id.trim());
            if (ident == null) return Blocks.AIR.getDefaultState();
            Block b = Registries.BLOCK.get(ident);
            if (b == null) return Blocks.AIR.getDefaultState();
            return b.getDefaultState();
        } catch (Throwable ignored) {
            return Blocks.AIR.getDefaultState();
        }
    }
}

