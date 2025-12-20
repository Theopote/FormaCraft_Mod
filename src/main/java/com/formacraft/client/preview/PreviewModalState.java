 package com.formacraft.client.preview;

/**
 * 全局预览模态锁状态源（单点裁决）。
 *
 * - BUILD：BuildingSpec/结构预览确认
 * - PATCH：Patch 预览确认
 */
public final class PreviewModalState {
    private PreviewModalState() {}

    public enum Mode {
        NONE,
        BUILD,
        PATCH
    }

    private static Mode mode = Mode.NONE;

    public static void lockBuild() {
        mode = Mode.BUILD;
    }

    public static void lockPatch() {
        mode = Mode.PATCH;
    }

    public static void unlock() {
        mode = Mode.NONE;
    }

    public static boolean isLocked() {
        return mode != Mode.NONE;
    }

    public static boolean isBuild() {
        return mode == Mode.BUILD;
    }

    public static boolean isPatch() {
        return mode == Mode.PATCH;
    }
}

