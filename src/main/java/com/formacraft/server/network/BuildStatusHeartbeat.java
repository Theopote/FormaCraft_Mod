package com.formacraft.server.network;

import com.formacraft.common.network.FormaCraftNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Periodic S2C status updates while long-running orchestrator requests are in flight.
 */
public final class BuildStatusHeartbeat {
    private BuildStatusHeartbeat() {}

    /** 首次状态更新的延迟：尽早出现"已等待 N 秒"，避免让玩家以为卡死。 */
    private static final long STATUS_INITIAL_DELAY_SEC = 5;
    /** 后续心跳间隔。 */
    private static final long STATUS_HEARTBEAT_SEC = 10;

    public static void start(MinecraftServer server,
                             ServerPlayerEntity player,
                             AtomicBoolean alive,
                             long startMs,
                             AtomicReference<String> phaseRef) {
        if (server == null || player == null || alive == null) return;

        Runnable tick = new Runnable() {
            @Override
            public void run() {
                if (!alive.get()) return;
                long waitedSec = Math.max(0, (System.currentTimeMillis() - startMs) / 1000);
                String phase = (phaseRef == null || phaseRef.get() == null || phaseRef.get().isBlank())
                        ? "仍在生成中"
                        : phaseRef.get().trim();

                server.execute(() -> {
                    if (!alive.get()) return;
                    try {
                        ServerPlayNetworking.send(player, new FormaCraftNetworking.ResponseBuildStatusPayload(
                                phase + "（已等待 " + waitedSec + " 秒）…"
                        ));
                    } catch (Throwable t) {
                        alive.set(false);
                    }
                });

                CompletableFuture.delayedExecutor(STATUS_HEARTBEAT_SEC, TimeUnit.SECONDS).execute(this);
            }
        };

        CompletableFuture.delayedExecutor(STATUS_INITIAL_DELAY_SEC, TimeUnit.SECONDS).execute(tick);
    }
}
