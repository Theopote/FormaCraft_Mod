package com.formacraft.server.build;

import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.List;

/**
 * 撤销条目
 * 记录一次建造操作的所有方块变更，用于撤销
 */
public class UndoEntry {
    private final ServerWorld world;
    private final BlockPos origin;
    private final String description;
    private final List<BlockChange> changes;

    public UndoEntry(ServerWorld world, BlockPos origin, String description, List<BlockChange> changes) {
        this.world = world;
        this.origin = origin;
        this.description = description;
        this.changes = List.copyOf(changes);
    }

    public ServerWorld getWorld() {
        return world;
    }

    public BlockPos getOrigin() {
        return origin;
    }

    public String getDescription() {
        return description;
    }

    public List<BlockChange> getChanges() {
        return changes;
    }
}

