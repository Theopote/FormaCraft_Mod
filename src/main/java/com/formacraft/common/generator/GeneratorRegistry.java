package com.formacraft.common.generator;

import com.formacraft.common.generator.impl.GateGenerator;
import com.formacraft.common.generator.impl.KeepGenerator;
import com.formacraft.common.generator.impl.RoadGenerator;
import com.formacraft.common.generator.impl.TowerGenerator;
import com.formacraft.common.generator.impl.WallGenerator;

import java.util.HashMap;
import java.util.Map;

/**
 * GeneratorRegistry（生成器注册表）
 * 
 * component_type → Generator 映射
 * 
 * 插件式扩展的关键：后续可以不断添加新的 Generator
 */
public final class GeneratorRegistry {

    private static final Map<String, ComponentGenerator> REGISTRY = new HashMap<>();

    static {
        // 核心构件
        register("TOWER", new TowerGenerator());
        register("KEEP", new KeepGenerator());
        register("WALL", new WallGenerator());
        register("GATE", new GateGenerator());
        register("ROAD", new RoadGenerator());

        // 后续可以不断添加
        // register("BRIDGE", new BridgeGenerator());
        // register("COURTYARD", new CourtyardGenerator());
        // register("ROOF", new RoofGenerator());
    }

    private GeneratorRegistry() {}

    /**
     * 注册生成器
     */
    public static void register(String type, ComponentGenerator generator) {
        if (type != null && generator != null) {
            REGISTRY.put(type.toUpperCase(), generator);
        }
    }

    /**
     * 获取生成器
     */
    public static ComponentGenerator getGenerator(String type) {
        if (type == null) return null;
        return REGISTRY.get(type.toUpperCase());
    }

    /**
     * 检查是否有生成器
     */
    public static boolean hasGenerator(String type) {
        if (type == null) return false;
        return REGISTRY.containsKey(type.toUpperCase());
    }
}

