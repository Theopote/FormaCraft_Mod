package com.formacraft.server.network;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bounded pool for plan-compile work that must not stall the Minecraft server thread.
 * World reads/writes still hop back via {@code MinecraftServer.execute}.
 * Queue is bounded; overflow runs on the caller (CallerRunsPolicy) to apply backpressure.
 */
public final class PlanCompileWorkers {
    private PlanCompileWorkers() {}

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final ThreadFactory FACTORY = r -> {
        Thread t = new Thread(r, "formacraft-plan-compile-" + SEQ.incrementAndGet());
        t.setDaemon(true);
        return t;
    };

    private static final int POOL = Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors() / 2));
    private static final int QUEUE = 32;

    private static final ExecutorService EXEC = new ThreadPoolExecutor(
            POOL,
            POOL,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(QUEUE),
            FACTORY,
            new ThreadPoolExecutor.CallerRunsPolicy()
    );

    public static ExecutorService executor() {
        return EXEC;
    }

    public static void submit(Runnable task) {
        EXEC.execute(task);
    }
}
