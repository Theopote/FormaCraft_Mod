package com.formacraft.server.build;

import net.minecraft.server.network.ServerPlayerEntity;
import com.formacraft.FormacraftMod;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 撤销服务
 * 按玩家维护撤销栈
 */
public class UndoService {
    private static final int MAX_UNDO_PER_PLAYER = 10;

    private final Map<UUID, Deque<UndoEntry>> undoStacks = new HashMap<>();

    /**
     * 添加一个撤销条目到玩家的撤销栈
     */
    public void pushUndo(ServerPlayerEntity player, UndoEntry entry) {
        UUID id = player.getUuid();
        Deque<UndoEntry> stack = undoStacks.computeIfAbsent(id, k -> new ArrayDeque<>());

        stack.push(entry);

        // 限制每个玩家的撤销栈大小
        while (stack.size() > MAX_UNDO_PER_PLAYER) {
            stack.removeLast();
        }
    }

    /**
     * 撤销玩家最后一次建造操作
     * @param player 玩家
     * @return 是否成功撤销
     */
    public boolean undoLast(ServerPlayerEntity player) {
        UUID id = player.getUuid();
        Deque<UndoEntry> stack = undoStacks.get(id);
        if (stack == null || stack.isEmpty()) {
            return false;
        }

        UndoEntry entry = stack.pop();

        // 逆序回放 fromState
        var world = entry.getWorld();
        var changes = entry.getChanges();

        for (int i = changes.size() - 1; i >= 0; i--) {
            BlockChange change = changes.get(i);
            world.setBlockState(change.getPos(), change.getFromState(), 3);
        }

        FormacraftMod.LOGGER.info("Undid build: {} ({} blocks)", entry.getDescription(), changes.size());
        return true;
    }

    /**
     * 获取玩家当前的撤销栈大小
     */
    public int getUndoStackSize(ServerPlayerEntity player) {
        UUID id = player.getUuid();
        Deque<UndoEntry> stack = undoStacks.get(id);
        return stack != null ? stack.size() : 0;
    }
}

