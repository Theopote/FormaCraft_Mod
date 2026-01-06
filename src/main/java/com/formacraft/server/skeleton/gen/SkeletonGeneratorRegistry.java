package com.formacraft.server.skeleton.gen;

import com.formacraft.common.skeleton.SkeletonType;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * SkeletonType -> Generator 映射表
 * 
 * 特性：
 * - 类型安全（按 enum 注册）
 * - 可替换（不同风格/版本/算法换 generator）
 * - 可缺省（没注册时有 fallback）
 */
public final class SkeletonGeneratorRegistry {

    private final Map<SkeletonType, ISkeletonGenerator> map = new EnumMap<>(SkeletonType.class);
    private final ISkeletonGenerator fallback;

    public SkeletonGeneratorRegistry(ISkeletonGenerator fallback) {
        this.fallback = Objects.requireNonNull(fallback);
    }

    public SkeletonGeneratorRegistry register(SkeletonType type, ISkeletonGenerator generator) {
        map.put(Objects.requireNonNull(type), Objects.requireNonNull(generator));
        return this;
    }

    public ISkeletonGenerator get(SkeletonType type) {
        return map.getOrDefault(type, fallback);
    }

    /**
     * 创建默认的 v1 映射表
     */
    public static SkeletonGeneratorRegistry createDefault() {
        SkeletonGeneratorRegistry reg = new SkeletonGeneratorRegistry(new UnsupportedSkeletonGenerator());
        
        // 注册所有骨架类型的生成器
        reg.register(SkeletonType.LINEAR_PATH, new LinearPathGenerator());
        reg.register(SkeletonType.PATH_POLYLINE, new com.formacraft.server.skeleton.gen.path.PathSkeletonGenerator());
        reg.register(SkeletonType.CONTOUR_FOLLOW, new ContourFollowGenerator());
        reg.register(SkeletonType.RADIAL_RING, new RadialRingGenerator());
        reg.register(SkeletonType.RADIAL_SPOKE, new RadialSpokeGenerator());
        reg.register(SkeletonType.VERTICAL_STACK, new VerticalStackGenerator());
        reg.register(SkeletonType.VERTICAL_TAPER, new VerticalTaperGenerator());
        reg.register(SkeletonType.GRID, new GridGenerator());
        reg.register(SkeletonType.COURTYARD, new CourtyardGenerator());
        reg.register(SkeletonType.PERIMETER_LOOP, new PerimeterLoopGenerator());
        reg.register(SkeletonType.ENCLOSURE, new EnclosureGenerator());
        reg.register(SkeletonType.SPAN_SUSPENSION, new SpanSuspensionGenerator());
        reg.register(SkeletonType.TERRACED, new TerracedGenerator());
        reg.register(SkeletonType.HIERARCHICAL_TREE, new HierarchicalTreeGenerator());
        reg.register(SkeletonType.COMPOUND, new CompoundGenerator(reg)); // 递归用 registry
        
        return reg;
    }
}

