package com.formacraft.common.patch.filter;

import com.formacraft.common.patch.BlockPatch;

import java.util.ArrayList;
import java.util.List;

/**
 * Patch 过滤结果：
 * - accepted：允许执行
 * - rejected：被拒绝/裁剪
 * - warnings：人类可读的原因（用于 UI/Debug）
 */
public class PatchFilterResult {
    public final List<BlockPatch> accepted = new ArrayList<>();
    public final List<BlockPatch> rejected = new ArrayList<>();
    public final List<String> warnings = new ArrayList<>();

    public boolean isEmpty() {
        return accepted.isEmpty();
    }
}


