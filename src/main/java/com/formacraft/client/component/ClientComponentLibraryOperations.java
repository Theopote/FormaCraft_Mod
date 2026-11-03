package com.formacraft.client.component;

import com.formacraft.common.component.*;
import com.formacraft.common.network.ComponentLibraryNetworking;
import com.formacraft.client.tool.ComponentTool;
import com.formacraft.client.ui.toast.HudToast;
import net.fabricmc.fabric.api.client.networking.v1.*;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;
import java.nio.file.*;
import java.util.concurrent.CompletableFuture;

public final class ClientComponentLibraryOperations {
    public static final ComponentLibraryClientState STATE = new ComponentLibraryClientState(System::nanoTime);
    private static boolean loadingAsset;
    private ClientComponentLibraryOperations() {}
    public static void register() {
        ClientComponentThumbnails.register();
        ClientPlayConnectionEvents.DISCONNECT.register((handler,client) -> { STATE.clear(); loadingAsset = false; });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (STATE.expire()) { sendCancel(); loadFailed(); HudToast.show(STATE.message(),true); }
        });
        ClientPlayNetworking.registerGlobalReceiver(ComponentLibraryNetworking.Response.ID,(response,context) -> context.client().execute(() -> {
            ClientComponentThumbnails.accept(response);
            var before = STATE.phase(); var next = STATE.accept(response);
            if (next != null) send(next);
            if (before == STATE.phase()) return;
            switch (STATE.phase()) {
                case DELETED -> {
                    String id = STATE.assetId(); ComponentThumbnailCache.clearCache(id);
                    ComponentTool.INSTANCE.onLibraryAssetDeleted(id);
                    ClientComponentSourceState.INSTANCE.clear();
                    HudToast.show("已删除构件");
                }
                case READY -> { if (loadingAsset) loadDownload(); else saveDownload(); }
                case SAVED -> {
                    var result = STATE.saved(); var tool = ComponentTool.INSTANCE.getState();
                    if (result != null) {
                        tool.librarySelectedId = result.assetId();
                    }
                    if (result != null) {
                        tool.librarySelectedName = result.name();
                    }
                    tool.libraryPage = 0;
                    if (result != null) {
                        tool.libraryRecipes = ComponentRecipeLibrary.isRecipe(result.assetId());
                    }
                    tool.librarySearch = "";
                    if (result != null) {
                        HudToast.show("配方操作完成："+result.name());
                    }
                }
                case ERROR -> { sendCancel(); loadFailed(); HudToast.show(STATE.message(),true); }
                default -> { }
            }
        }));
    }
    public static void recipe(ComponentCatalog.Entry entry, ComponentLibraryNetworking.Action action, ComponentRecipeLibrary.Spec spec) {
        if (STATE.busy()) return;
        if (entry == null || entry.packageRef == null || !ClientPlayNetworking.canSend(ComponentLibraryNetworking.Request.ID)) { HudToast.show("请选择已入库构件",true); return; }
        ClientComponentThumbnails.pause(); loadingAsset = false;
        try { send(STATE.beginMutation(entry.id,entry.packageRef,action,spec == null ? "" : com.formacraft.common.json.JsonUtil.toJson(spec))); }
        catch (RuntimeException invalid) { STATE.fail("无法开始配方操作"); HudToast.show(STATE.message(),true); }
    }
    public static void start(ComponentCatalog.Entry entry, boolean delete) {
        if (STATE.busy()) return;
        if (entry == null || entry.packageRef == null) { HudToast.show("请选择已入库的构件",true); return; }
        if (!ClientPlayNetworking.canSend(ComponentLibraryNetworking.Request.ID)) { HudToast.show("服务器未提供构件管理功能",true); return; }
        int index = 0; String ref = entry.packageRef;
        var page = ClientComponentSourceState.INSTANCE.page();
        if (!delete && page != null && page.error() == null && entry.id.equals(page.assetId())) {
            index = page.index(); ref = page.contentRef() + "|manifest:" + page.manifestSha256();
        }
        loadingAsset = false;
        ClientComponentThumbnails.pause();
        try { send(STATE.begin(entry.id,entry.packageRef,index,ref,delete)); }
        catch (RuntimeException invalid) { STATE.fail("无法开始构件操作"); HudToast.show(STATE.message(),true); }
    }
    public static boolean load(ComponentCatalog.Entry entry) {
        if (STATE.busy() || entry == null || entry.packageRef == null || !ClientPlayNetworking.canSend(ComponentLibraryNetworking.Request.ID)) return false;
        loadingAsset = true;
        ClientComponentThumbnails.pause();
        send(STATE.begin(entry.id,entry.packageRef,0,entry.packageRef,false));
        return true;
    }
    private static void loadFailed() {
        if (loadingAsset) { ClientCommunityPlacement.clear(); loadingAsset = false; ComponentTool.INSTANCE.onComponentDefinitionFromServer(""); }
    }
    public static void cancel() { if (STATE.phase() == ComponentLibraryClientState.Phase.DELETING || STATE.phase() == ComponentLibraryClientState.Phase.SAVING) return; sendCancel(); loadFailed(); STATE.clear(); }
    private static void sendCancel() {
        var request = STATE.cancellation();
        try { if (request != null && ClientPlayNetworking.canSend(ComponentLibraryNetworking.Request.ID)) ClientPlayNetworking.send(request); }
        catch (RuntimeException disconnected) { /* Server expiry releases snapshots. */ }
    }
    private static void send(ComponentLibraryNetworking.Request request) {
        try { ClientPlayNetworking.send(request); }
        catch (RuntimeException disconnected) { STATE.fail("构件操作连接中断"); loadFailed(); HudToast.show(STATE.message(),true); }
    }
    private static void loadDownload() {
        try {
            ClientCommunityPlacement.reference(STATE.assetId());
            var verified = ComponentPackageIO.inspectBytes(STATE.bytes());
            if (!STATE.sourceRef().equals(verified.contentRef())) throw new java.io.IOException("构件内容引用不一致");
            var definition = verified.runtimeComponent(STATE.assetId());
            ComponentTool.INSTANCE.onComponentDefinitionFromServer(com.formacraft.common.json.JsonUtil.toJson(definition));
            ComponentThumbnailCache.acceptDefinition(definition);
            loadingAsset = false; STATE.clear();
        } catch (Exception invalid) { STATE.fail("构件下载校验失败"); loadFailed(); HudToast.show(STATE.message(),true); }
    }
    private static void saveDownload() {
        byte[] bytes = STATE.bytes(); String sourceRef = STATE.sourceRef();
        var client = net.minecraft.client.MinecraftClient.getInstance();
        final String selected;
        try (var stack = MemoryStack.stackPush()) {
            var filters = stack.mallocPointer(1); filters.put(stack.UTF8("*.fccomponent.json")).flip();
            selected = TinyFileDialogs.tinyfd_saveFileDialog("保存构件来源包",STATE.assetId()+".fccomponent.json",filters,"Formacraft 构件包");
        } catch (RuntimeException | LinkageError unavailable) { STATE.fail("无法打开保存文件选择器"); HudToast.show(STATE.message(),true); return; }
        STATE.clear(); if (selected == null) return;
        CompletableFuture.runAsync(() -> {
            try {
                var verified = ComponentPackageIO.inspectBytes(bytes);
                if (!sourceRef.equals(verified.contentRef()) && !sourceRef.equals(verified.contentRef() + "|manifest:" + verified.manifestSha256()))
                    throw new java.io.IOException("导出来源不一致");
                Path target = Path.of(selected.endsWith(".fccomponent.json") ? selected : selected+".fccomponent.json");
                Files.write(target,bytes,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);
                client.execute(() -> HudToast.show("构件包已导出"));
            } catch (Exception failed) { client.execute(() -> HudToast.show("导出失败：请使用可写目录中的新文件名",true)); }
        });
    }
}
