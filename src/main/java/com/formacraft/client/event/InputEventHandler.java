package com.formacraft.client.event;

import com.formacraft.client.ui.TextInputScreen;
import com.formacraft.client.ui.FormaCraftChatScreen;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;

public class InputEventHandler {
    static {
        // Example: open screen on next tick if a condition is met (stub only)
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // no-op stub
        });
    }

    public static void openInputScreen() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null) {
            client.execute(() -> client.setScreen(new TextInputScreen()));
        }
    }

    public static void openChatScreen() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null) {
            client.execute(() -> client.setScreen(new FormaCraftChatScreen()));
        }
    }
}
