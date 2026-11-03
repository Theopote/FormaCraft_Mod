package com.formacraft.server.command;

import com.formacraft.server.network.CommunityTaskService;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import java.io.IOException;
import static net.minecraft.server.command.CommandManager.literal;

/** Only level-4 administrators can change the server's community connection. */
final class CommunityServiceCommands {
    private CommunityServiceCommands() {}
    static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(literal("forma_community").requires(source -> source.hasPermissionLevel(4))
                .executes(ctx -> status(ctx.getSource()))
                .then(literal("status").executes(ctx -> status(ctx.getSource())))
                .then(literal("reload").executes(ctx -> reload(ctx.getSource())))
                .then(literal("disable").executes(ctx -> {
                    CommunityTaskService.disable(ctx.getSource().getServer());
                    ctx.getSource().sendFeedback(() -> Text.literal("社区服务已临时停用，现有会话已取消；下次重载或重启将重新读取配置"),true); return 1;
                })));
    }
    private static int status(ServerCommandSource source) {
        String authority=CommunityTaskService.authority(source.getServer());
        source.sendFeedback(() -> Text.literal(authority==null ? "社区服务：已停用" : "社区服务：已启用，身份 "+authority+"；连接可用性尚未探测"),false);
        return 1;
    }
    private static int reload(ServerCommandSource source) {
        try {
            CommunityTaskService.reload(source.getServer());
            source.sendFeedback(() -> Text.literal("社区配置已重载，旧会话已取消；玩家需重新连接"),true);
            return status(source);
        } catch(IOException invalid) {
            source.sendError(Text.literal("社区配置无效或不可读取，服务已停用；请检查存档内 formacraft/community/service.json")); return 0;
        }
    }
}
