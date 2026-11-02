package com.formacraft.server.command;

import com.formacraft.common.component.BuildingAssetLocks;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.WorldSavePath;
import static net.minecraft.server.command.CommandManager.*;

final class BuildingAssetCommands {
    private BuildingAssetCommands() {}
    static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(literal("forma_assets")
                .then(literal("list").executes(ctx -> list(ctx.getSource(),0))
                        .then(argument("page",IntegerArgumentType.integer(1,10001)).executes(ctx -> list(ctx.getSource(),IntegerArgumentType.getInteger(ctx,"page")-1))))
                .then(literal("preview").requires(source -> source.hasPermissionLevel(2))
                        .then(argument("id",StringArgumentType.word()).executes(ctx -> preview(ctx.getSource(),StringArgumentType.getString(ctx,"id")))))
                .then(literal("restore").then(argument("id",StringArgumentType.word()).executes(ctx -> restore(ctx.getSource(),StringArgumentType.getString(ctx,"id"))))));
    }
    private static int list(ServerCommandSource source,int page) {
        try {
            var player = source.getPlayerOrThrow(); var world = (ServerWorld)player.getEntityWorld();
            var locks = BuildingAssetLocks.list(world.getServer().getSavePath(WorldSavePath.ROOT),player.getUuid(),page);
            source.sendFeedback(() -> Text.literal("建筑构件锁 第 "+(page+1)+" 页："+locks.size()+" 项"),false);
            for (var lock : locks) source.sendFeedback(() -> Text.literal(lock.id()+" | "+lock.patchCount()+" 方块 | "+lock.dependencies().size()+" 个构件版本 | "+lock.dimension()),false);
            return locks.size();
        } catch (Exception invalid) { return failure(source,invalid); }
    }
    private static int preview(ServerCommandSource source,String id) {
        try {
            var player = source.getPlayerOrThrow(); var world = (ServerWorld)player.getEntityWorld();
            var locked = BuildingAssetLocks.load(world.getServer().getSavePath(WorldSavePath.ROOT),player.getUuid(),id,world.getRegistryKey().getValue().toString());
            var ticket = com.formacraft.server.patch.PatchPreviewService.issuePreview(player,player.getBlockPos(),locked.patches(),
                    com.formacraft.server.state.PlayerProtectedZoneStorage.get(player),com.formacraft.server.state.PlayerOutlineStorage.get(player),false,null,null);
            if (ticket == null) { source.sendError(Text.literal("锁定方案未通过当前世界的预览检查")); return 0; }
            source.sendFeedback(() -> Text.literal("锁定方案已生成预览；检查后确认应用，尚未修改世界"),false); return 1;
        } catch (Exception invalid) { return failure(source,invalid); }
    }
    private static int restore(ServerCommandSource source,String id) {
        try {
            var player = source.getPlayerOrThrow(); var world = (ServerWorld)player.getEntityWorld();
            var result = BuildingAssetLocks.restore(world.getServer().getSavePath(WorldSavePath.ROOT),player.getUuid(),id,world.getRegistryKey().getValue().toString());
            source.sendFeedback(() -> Text.literal("已恢复/复用 "+result.aliases().size()+" 个构件引用；失败 "+result.failures().size()+" 项。新资产只写本人私人库，未修改世界"),false);
            com.formacraft.server.network.ComponentCatalogSync.send(player,com.formacraft.common.json.JsonUtil.toJson(
                    com.formacraft.common.component.ComponentStorage.loadCatalogWithSockets(world.getServer().getSavePath(WorldSavePath.ROOT),player.getUuid())));
            return result.complete() ? 1 : 0;
        } catch (Exception invalid) { return failure(source,invalid); }
    }
    private static int failure(ServerCommandSource source,Exception invalid) {
        com.formacraft.FormacraftMod.LOGGER.warn("Building asset lock operation failed",invalid);
        String message = invalid instanceof java.nio.file.FileSystemException || invalid instanceof SecurityException ? "锁文件不可用，请查看服务端日志" : invalid.getMessage();
        if (message == null || message.length() > 256) message = "建筑构件锁操作失败，请查看服务端日志";
        source.sendError(Text.literal(message)); return 0;
    }
}
