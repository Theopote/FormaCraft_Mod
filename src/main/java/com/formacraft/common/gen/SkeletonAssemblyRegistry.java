package com.formacraft.common.gen;

import com.formacraft.common.gen.impl.LinearPathRoadGenerator;
import com.formacraft.common.gen.impl.PolylineRoadGenerator;
import com.formacraft.common.gen.impl.RadialRingCourtyardGenerator;
import com.formacraft.common.skeleton.SkeletonType;

import java.util.EnumMap;
import java.util.function.Supplier;

/**
 * SkeletonAssemblyRegistry（SkeletonType → GeneratorFactory 映射表）
 * 
 * 核心职责：
 * - 注册 SkeletonType 到 Generator 的映射
 * - 根据 SkeletonType 创建对应的 Generator
 * - 提供默认注册表
 */
public class SkeletonAssemblyRegistry {

    private final EnumMap<SkeletonType, Supplier<SkeletonGenerator>> map = new EnumMap<>(SkeletonType.class);

    /**
     * 注册 Generator 工厂
     */
    public SkeletonAssemblyRegistry register(SkeletonType type, Supplier<SkeletonGenerator> factory) {
        if (type != null && factory != null) {
            map.put(type, factory);
        }
        return this;
    }

    /**
     * 创建 Generator
     */
    public SkeletonGenerator create(SkeletonType type) {
        Supplier<SkeletonGenerator> f = map.get(type);
        if (f == null) {
            throw new IllegalStateException("No generator for skeleton type: " + type);
        }
        return f.get();
    }

    /**
     * 检查是否有 Generator
     */
    public boolean hasGenerator(SkeletonType type) {
        return map.containsKey(type);
    }

    /**
     * 创建默认注册表（推荐）
     */
    public static SkeletonAssemblyRegistry defaultRegistry() {
        SkeletonAssemblyRegistry r = new SkeletonAssemblyRegistry();

        // Path / roads / walls
        r.register(SkeletonType.LINEAR_PATH, LinearPathRoadGenerator::new);
        r.register(SkeletonType.PATH_POLYLINE, PolylineRoadGenerator::new);

        // Rings / courtyards
        r.register(SkeletonType.RADIAL_RING, RadialRingCourtyardGenerator::new);

        // TODO: 你后续逐个补齐
        // r.register(SkeletonType.SPAN_SUSPENSION, SuspensionBridgeGenerator::new);
        // r.register(SkeletonType.VERTICAL_STACK, VerticalStackGenerator::new);
        // r.register(SkeletonType.GRID, GridCityBlockGenerator::new);

        return r;
    }
}

