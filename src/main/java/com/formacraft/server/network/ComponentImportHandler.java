package com.formacraft.server.network;

import com.formacraft.common.component.*;
import com.formacraft.common.json.JsonUtil;
import com.formacraft.common.network.ComponentImportNetworking;
import com.formacraft.common.network.FormaCraftNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.util.WorldSavePath;

final class ComponentImportHandler {
    private static final ComponentImportSessions SESSIONS = new ComponentImportSessions();
    private static boolean registered;
    private ComponentImportHandler() {}
    static synchronized void register() {
        if (registered) return;
        registered = true;
        ServerTickEvents.END_SERVER_TICK.register(server -> SESSIONS.expire());
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> SESSIONS.clear());
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> SESSIONS.disconnect(handler.getPlayer().getUuid()));
        ServerPlayNetworking.registerGlobalReceiver(ComponentImportNetworking.Request.ID, (request, context) -> context.server().execute(() -> {
            var player = context.player();
            if (player == null || !ServerPlayNetworking.canSend(player, ComponentImportNetworking.Response.ID)) return;
            var world = context.server().getSavePath(WorldSavePath.ROOT);
            var save = ComponentStorage.SaveContext.forPlayer(world, player.getUuid(), context.server().isDedicated(), player.hasPermissionLevel(2));
            try {
                switch (request.operation()) {
                    case START -> SESSIONS.begin(player.getUuid(), request.token(), request.length(), save);
                    case CAPTURE_START -> SESSIONS.beginCapture(player.getUuid(), request.token(), request.length(), save);
                    case CHUNK -> SESSIONS.append(player.getUuid(), request.token(), request.offset(), request.data());
                    case CANCEL -> SESSIONS.cancel(player.getUuid(), request.token());
                    case PREVIEW -> {
                        var preview = SESSIONS.preview(player.getUuid(), request.token(), ComponentPackageIO.currentEnvironment());
                        ServerPlayNetworking.send(player, new ComponentImportNetworking.Response(request.token(), ComponentImportNetworking.Reply.PREVIEW, JsonUtil.toJson(preview)));
                    }
                    case CONFIRM -> {
                        var installed = SESSIONS.confirm(player.getUuid(), request.token(), save);
                        ServerPlayNetworking.send(player, new ComponentImportNetworking.Response(request.token(), ComponentImportNetworking.Reply.INSTALLED, JsonUtil.toJson(installed)));
                        ServerPlayNetworking.send(player, new FormaCraftNetworking.ComponentCatalogPayload(JsonUtil.toJson(ComponentStorage.loadCatalogWithSockets(world, player.getUuid()))));
                    }
                }
            } catch (Exception invalid) {
                SESSIONS.cancel(player.getUuid(), request.token());
                String message = invalid.getMessage();
                if (invalid instanceof java.nio.file.FileSystemException || invalid instanceof SecurityException) {
                    com.formacraft.FormacraftMod.LOGGER.warn("Component import storage failure player={}", player.getUuid(), invalid);
                    message = "目标库写入失败，请查看服务端日志";
                }
                if (message == null) message = "构件包导入失败";
                if (message.length() > 512) message = message.substring(0, 512);
                ServerPlayNetworking.send(player, new ComponentImportNetworking.Response(request.token(), ComponentImportNetworking.Reply.ERROR, JsonUtil.toJson(message)));
            }
        }));
    }
}
