package com.formacraft.client.preview;

import java.util.ArrayList;
import java.util.List;

/**
 * 预览状态管理
 * 客户端缓存预览线框数据
 */
public class OutlinePreviewState {
    public static List<OutlineBlock> blocks = new ArrayList<>();
    public static boolean active = false;

    public static void clear() {
        blocks.clear();
        active = false;
    }

    public static void setBlocks(List<OutlineBlock> newBlocks) {
        blocks = newBlocks != null ? new ArrayList<>(newBlocks) : new ArrayList<>();
        active = !blocks.isEmpty();
    }
}

