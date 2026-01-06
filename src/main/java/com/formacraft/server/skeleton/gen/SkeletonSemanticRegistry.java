package com.formacraft.server.skeleton.gen;

import com.formacraft.common.skeleton.SkeletonType;

import java.util.EnumMap;
import java.util.Map;

/**
 * Skeleton 语义生成器注册表
 * 
 * 映射 SkeletonType 到 ISkeletonSemanticGenerator
 */
public final class SkeletonSemanticRegistry {

    private static final Map<SkeletonType, ISkeletonSemanticGenerator> MAP = new EnumMap<>(SkeletonType.class);

    private SkeletonSemanticRegistry() {}

    /**
     * 注册默认生成器
     */
    public static void registerDefaults() {
        MAP.put(SkeletonType.LINEAR_PATH, new LinearPathSemanticGenerator());
        // TODO: PATH_POLYLINE / RADIAL_RING / GRID ...
    }

    /**
     * 注册生成器
     */
    public static void register(SkeletonType type, ISkeletonSemanticGenerator generator) {
        if (type != null && generator != null) {
            MAP.put(type, generator);
        }
    }

    /**
     * 获取生成器
     */
    public static ISkeletonSemanticGenerator get(SkeletonType type) {
        return MAP.get(type);
    }
}

