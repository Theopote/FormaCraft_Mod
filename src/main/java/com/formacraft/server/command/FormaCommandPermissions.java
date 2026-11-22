package com.formacraft.server.command;

import com.formacraft.server.config.ServerLlmConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;

/** Shared Brigadier gates for FormaCraft player commands. */
final class FormaCommandPermissions {
    private FormaCommandPermissions() {}

    /**
     * Integrated/singleplayer: default allow (level 0).
     * Dedicated: {@link ServerLlmConfig#requireBuildPermissionLevel(boolean)} (default OP 2).
     */
    static boolean mayBuild(ServerCommandSource source) {
        if (source == null) return false;
        MinecraftServer server = source.getServer();
        int required = ServerLlmConfig.requireBuildPermissionLevel(server != null && server.isDedicated());
        return required <= 0 || source.hasPermissionLevel(required);
    }
}
