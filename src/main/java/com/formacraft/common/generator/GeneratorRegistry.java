package com.formacraft.common.generator;

import com.formacraft.common.generator.impl.*;

import java.util.HashMap;
import java.util.Map;

/**
 * GeneratorRegistry（生成器注册表）
 * <p>
 * component_type → Generator 映射
 * <p>
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
        
        // 语义组件（K3.1 新增）
        register("MASS_MAIN", new MassMainGenerator());
        register("MASS_SECONDARY", new MassMainGenerator()); // 复用 MassMainGenerator
        register("ENTRANCE", new EntranceGenerator());
        register("SIGNAGE", new SignageGenerator());
        register("FACADE_WINDOWS", new FacadeWindowsGenerator()); // 使用专用生成器
        register("PAVING", new RoadGenerator()); // 临时复用，后续可单独实现
        register("FENCE_OR_WALL", new WallGenerator()); // 复用 WallGenerator
        
        // 新增生成器
        register("ROOF", new RoofGenerator());
        register("COURTYARD_SPACE", new CourtyardSpaceGenerator());
        register("GATE_STRUCTURE", new GateStructureGenerator());
        register("PATH", new PathGenerator());
        
        // 完整建筑生成所需的生成器
        register("BALCONY", new BalconyGenerator());
        register("CHIMNEY", new ChimneyGenerator());
        register("FOUNDATION", new FoundationGenerator());
        register("DECOR_DETAIL", new DecorDetailGenerator());
        
        // 侧翼生成器（复用 MassMainGenerator）
        register("SIDE_WING", new MassMainGenerator());

        // 后续可以不断添加
        // register("BRIDGE", new BridgeGenerator());
        // register("COURTYARD", new CourtyardGenerator());
        // register("ROOF", new RoofGenerator());
        // register("BALCONY", new BalconyGenerator());
        // register("PLAZA_CORE", new PlazaCoreGenerator());
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

