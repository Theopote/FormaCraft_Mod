package com.formacraft.client.component;

import com.formacraft.common.component.ComponentCatalogTransfer;
import com.formacraft.common.network.FormaCraftNetworking;
import net.fabricmc.fabric.api.client.networking.v1.*;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

public final class ClientComponentCatalogSync {
    private static final ComponentCatalogTransfer.Receiver RECEIVER = new ComponentCatalogTransfer.Receiver(System::nanoTime);
    private ClientComponentCatalogSync() {}
    public static boolean active() { return RECEIVER.active(); }
    public static void register() {
        ClientPlayConnectionEvents.JOIN.register((handler,sender,client) -> { RECEIVER.clear(); ClientComponentCatalogState.beginSession(client.getServer() != null); });
        ClientPlayConnectionEvents.DISCONNECT.register((handler,client) -> { RECEIVER.clear(); ClientComponentCatalogState.beginSession(false); });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (RECEIVER.expire()) ClientComponentCatalogState.syncFailed();
        });
        ClientPlayNetworking.registerGlobalReceiver(FormaCraftNetworking.ComponentCatalogPayload.ID,(payload,context) -> context.client().execute(() -> {
            try {
                String json = RECEIVER.accept(payload.chunk());
                if (json != null) { ClientComponentCatalogState.setFromJson(json); ClientComponentSourceState.INSTANCE.clear(); }
            } catch (java.io.IOException invalid) {
                ClientComponentCatalogState.syncFailed();
                com.formacraft.client.ui.toast.HudToast.show("构件目录同步失败，请稍后刷新",true);
            }
        }));
    }
}
