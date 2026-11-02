package com.formacraft.server.network;

import com.formacraft.common.component.*;
import com.formacraft.common.network.ComponentLibraryNetworking;
import com.formacraft.common.network.FormaCraftNetworking;
import com.formacraft.common.json.JsonUtil;
import net.fabricmc.fabric.api.networking.v1.*;
import net.fabricmc.fabric.api.event.lifecycle.v1.*;
import net.minecraft.util.WorldSavePath;

final class ComponentLibraryHandler {
    private static final ComponentExportSessions EXPORTS = new ComponentExportSessions();
    private static boolean registered;
    static synchronized void register() {
        if (registered) return; registered = true;
        ServerTickEvents.END_SERVER_TICK.register(server -> EXPORTS.expire());
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> EXPORTS.clear());
        ServerPlayConnectionEvents.DISCONNECT.register((handler,server) -> EXPORTS.disconnect(handler.getPlayer().getUuid()));
        ServerPlayNetworking.registerGlobalReceiver(ComponentLibraryNetworking.Request.ID, (request,context) -> context.server().execute(() -> {
            var player = context.player();
            if (!ServerPlayNetworking.canSend(player, ComponentLibraryNetworking.Response.ID)) return;
            var world = context.server().getSavePath(WorldSavePath.ROOT);
            try {
                switch (request.action()) {
                    case SAVE_RECIPE, REPLAY_RECIPE, RESTORE_RECIPE -> {
                        ComponentRecipeLibrary.Saved result;
                        if (request.action() == ComponentLibraryNetworking.Action.SAVE_RECIPE) {
                            var spec = JsonUtil.fromJson(request.options(),ComponentRecipeLibrary.Spec.class);
                            result = ComponentRecipeLibrary.save(world,player.getUuid(),request.assetId(),request.primaryRef(),spec);
                        } else {
                            ComponentLibraryOperations.export(world,player.getUuid(),request.assetId(),request.primaryRef(),0,request.primaryRef());
                            if (request.action() == ComponentLibraryNetworking.Action.REPLAY_RECIPE) {
                                ComponentRecipeLibrary.replay(player.getUuid(),request.assetId());
                                result = new ComponentRecipeLibrary.Saved(request.assetId(),ComponentRecipeLibrary.result(player.getUuid(),request.assetId()).component().name,ComponentPackageInstallations.ImportStatus.REUSED);
                            } else result = ComponentRecipeLibrary.restore(ComponentStorage.SaveContext.forPlayer(world,player.getUuid(),context.server().isDedicated(),player.hasPermissionLevel(2)),request.assetId());
                        }
                        ServerPlayNetworking.send(player,new ComponentLibraryNetworking.Response(request.token(),ComponentLibraryNetworking.Reply.SAVED,0,0,null,JsonUtil.toJson(result)));
                        ComponentCatalogSync.send(player,JsonUtil.toJson(ComponentStorage.loadCatalogWithSockets(world,player.getUuid())));
                    }
                    case DELETE -> {
                        ComponentLibraryOperations.delete(ComponentStorage.SaveContext.forPlayer(world, player.getUuid(), context.server().isDedicated(), player.hasPermissionLevel(2)), request.assetId(), request.primaryRef());
                        ServerPlayNetworking.send(player,new ComponentLibraryNetworking.Response(request.token(),ComponentLibraryNetworking.Reply.DELETED,0,0,null,""));
                        ComponentCatalogSync.send(player,JsonUtil.toJson(ComponentStorage.loadCatalogWithSockets(world,player.getUuid())));
                    }
                    case EXPORT, NEXT -> {
                        if (request.action() == ComponentLibraryNetworking.Action.EXPORT) {
                            var source = ComponentLibraryOperations.export(world,player.getUuid(),request.assetId(),request.primaryRef(),request.sourceIndex(),request.sourceRef());
                            EXPORTS.begin(player.getUuid(),request.token(),source);
                        }
                        var chunk = EXPORTS.next(player.getUuid(),request.token(),request.offset());
                        ServerPlayNetworking.send(player,new ComponentLibraryNetworking.Response(request.token(),ComponentLibraryNetworking.Reply.CHUNK,chunk.length(),chunk.offset(),chunk.data(),""));
                    }
                    case CANCEL -> EXPORTS.cancel(player.getUuid(),request.token());
                }
            } catch (Exception invalid) {
                EXPORTS.cancel(player.getUuid(),request.token());
                com.formacraft.FormacraftMod.LOGGER.warn("Component library operation failed player={} action={}",player.getUuid(),request.action(),invalid);
                String message = invalid instanceof java.nio.file.FileSystemException ? "构件文件操作失败，请查看服务端日志" : invalid.getMessage();
                if (message == null || message.length() > 512) message = "构件操作失败，请刷新或查看服务端日志";
                ServerPlayNetworking.send(player,new ComponentLibraryNetworking.Response(request.token(),ComponentLibraryNetworking.Reply.ERROR,0,0,null,message));
            }
        }));
    }
}
