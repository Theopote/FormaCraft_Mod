package com.formacraft.server.network;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bounded pool for blocking orchestrator proxy HTTP ({@code /models}, {@code /summarize}).
 * Never uses {@link java.util.concurrent.ForkJoinPool#commonPool()}.
 */
public final class OrchestratorProxyWorkers {
    private OrchestratorProxyWorkers() {}

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final ThreadFactory FACTORY = r -> {
        Thread t = new Thread(r, "formacraft-orchestrator-proxy-" + SEQ.incrementAndGet());
        t.setDaemon(true);
        return t;
    };

    private static final int POOL = Math.max(1, Math.min(2, Runtime.getRuntime().availableProcessors() / 2));
    private static final int QUEUE = 16;

    private static final ExecutorService EXEC = new ThreadPoolExecutor(
            POOL,
            POOL,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(QUEUE),
            FACTORY,
            new ThreadPoolExecutor.AbortPolicy()
    );

    public static ExecutorService executor() {
        return EXEC;
    }

    public static boolean trySubmit(Runnable task) {
        try {
            EXEC.execute(task);
            return true;
        } catch (RejectedExecutionException rejected) {
            return false;
        }
    }
}
