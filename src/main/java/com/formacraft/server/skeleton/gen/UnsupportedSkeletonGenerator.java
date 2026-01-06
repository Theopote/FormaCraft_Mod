package com.formacraft.server.skeleton.gen;

import com.formacraft.common.patch.BlockPatch;

import java.util.Collections;
import java.util.List;

/**
 * 不支持的骨架类型生成器（fallback）
 * 
 * v1：不抛异常，避免服务器崩；只是产生空操作，并由上层提示用户/日志记录
 */
public class UnsupportedSkeletonGenerator implements ISkeletonGenerator {
    @Override
    public List<BlockPatch> generate(GenerationContext ctx, ExecutableSkeletonPlan plan) {
        // 返回空列表，上层可以记录日志或提示用户
        return Collections.emptyList();
    }
}

