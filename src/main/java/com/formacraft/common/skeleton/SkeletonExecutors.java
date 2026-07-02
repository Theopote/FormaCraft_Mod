package com.formacraft.common.skeleton;

/**
 * 全局骨架执行器持有者。
 * <p>
 * 在 {@link com.formacraft.common.init.SkeletonSystemInitializer#initialize()} 中注册服务端实现。
 */
public final class SkeletonExecutors {

    private static volatile SkeletonExecutor executor;

    private SkeletonExecutors() {}

    public static void register(SkeletonExecutor impl) {
        executor = impl;
    }

    public static SkeletonExecutor get() {
        SkeletonExecutor ex = executor;
        if (ex == null) {
            throw new IllegalStateException(
                    "SkeletonExecutor not registered. Ensure SkeletonSystemInitializer.initialize() runs at mod startup.");
        }
        return ex;
    }

    public static boolean isRegistered() {
        return executor != null;
    }
}
