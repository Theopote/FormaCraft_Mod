package com.formacraft.server.network;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bounded pool for plan-compile work that must not stall the Minecraft server thread.
 * World reads/writes still hop back via {@code MinecraftServer.execute}.
 */
public final class PlanCompileWorkers {
    private PlanCompileWorkers() {}

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final ThreadFactory FACTORY = r -> {
        Thread t = new Thread(r, "formacraft-plan-compile-" + SEQ.incrementAndGet());
        t.setDaemon(true);
        return t;
    };

    private static final ExecutorService EXEC = Executors.newFixedThreadPool(
            Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors() / 2)),
            FACTORY
    );

    public static ExecutorService executor() {
        return EXEC;
    }

    public static void submit(Runnable task) {
        EXEC.execute(task);
    }
}
