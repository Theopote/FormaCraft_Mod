package com.formacraft.client.component;

import com.formacraft.common.component.ComponentPackageIO;
import com.formacraft.common.network.ComponentImportNetworking;
import com.formacraft.client.ui.toast.HudToast;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;
import java.nio.file.*;
import java.util.concurrent.CompletableFuture;

public final class ClientComponentImport {
    public static final ComponentImportClientState STATE = new ComponentImportClientState(System::nanoTime);
    private ClientComponentImport() {}
    public static void register() {
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> STATE.clear());
        ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
        ClientPlayNetworking.registerGlobalReceiver(ComponentImportNetworking.Response.ID, (response, context) -> context.client().execute(() -> {
            var before = STATE.phase(); STATE.accept(response);
            if (before != ComponentImportClientState.Phase.DONE && STATE.phase() == ComponentImportClientState.Phase.DONE) {
                var result = STATE.installed(); var tool = com.formacraft.client.tool.ComponentTool.INSTANCE.getState();
                tool.librarySelectedId = result.assetId(); tool.librarySelectedName = result.name(); tool.libraryPage = 0; tool.librarySearch = "";
                ClientComponentSourceState.INSTANCE.clear();
                String action = switch (result.status()) { case NEW_ASSET -> "已导入构件："; case NEW_CONFIGURATION -> "已导入新配置："; case SOURCE_ADDED -> "已复用构件并补充来源："; case REUSED -> "已复用已有构件："; };
                HudToast.show(action + result.name());
            }
        }));
    }
    public static void chooseFile() {
        if (STATE.busy()) return;
        if (!ClientPlayNetworking.canSend(ComponentImportNetworking.Request.ID)) { STATE.fail("服务器未提供构件包导入功能"); return; }
        cancel();
        var client = MinecraftClient.getInstance(); var connection = client.getNetworkHandler();
        final String selected;
        try (var stack = MemoryStack.stackPush()) {
            var filters = stack.mallocPointer(1); filters.put(stack.UTF8("*.fccomponent.json")).flip();
            selected = TinyFileDialogs.tinyfd_openFileDialog("选择 Formacraft 构件包", null, filters, "Formacraft 构件包", false);
        } catch (RuntimeException | LinkageError unavailable) { STATE.fail("无法打开文件选择器"); return; }
        if (selected == null) return;
        var token = STATE.begin();
        CompletableFuture.supplyAsync(() -> {
            try {
                Path file = Path.of(selected);
                if (!Files.isRegularFile(file)) throw new java.io.IOException("Not a regular file");
                try (var input = Files.newInputStream(file)) { return input.readNBytes(ComponentPackageIO.MAX_PACKAGE_BYTES + 1); }
            }
            catch (Exception invalid) { throw new java.util.concurrent.CompletionException(invalid); }
        }).whenComplete((bytes, failure) -> client.execute(() -> {
            if (!STATE.matches(token) || connection == null || client.getNetworkHandler() != connection) return;
            if (failure != null) { STATE.fail("无法读取构件包文件"); return; }
            if (STATE.fileReady(token, bytes)) {
                try { ClientPlayNetworking.send(new ComponentImportNetworking.Request(token, ComponentImportNetworking.Operation.START, STATE.length(), 0, null)); }
                catch (RuntimeException invalid) { STATE.fail("无法开始上传构件包"); }
            }
        }));
    }
    private static void tick() {
        if (STATE.expire()) { sendCancel(); return; }
        for (int i = 0; i < 2; i++) {
            var request = STATE.nextUploadRequest(); if (request == null) break;
            try { ClientPlayNetworking.send(request); }
            catch (RuntimeException invalid) { sendCancel(); STATE.fail("构件包上传中断"); break; }
        }
    }
    public static void confirm() {
        var request = STATE.confirm(); if (request == null) return;
        try { ClientPlayNetworking.send(request); }
        catch (RuntimeException invalid) { STATE.fail("无法提交构件包导入"); }
    }
    private static void sendCancel() {
        try {
            if (STATE.token() != null && ClientPlayNetworking.canSend(ComponentImportNetworking.Request.ID))
                ClientPlayNetworking.send(new ComponentImportNetworking.Request(STATE.token(), ComponentImportNetworking.Operation.CANCEL, 0, 0, null));
        } catch (RuntimeException disconnected) { /* Server expiry also releases abandoned uploads. */ }
    }
    public static void cancel() { sendCancel(); STATE.clear(); }
}
