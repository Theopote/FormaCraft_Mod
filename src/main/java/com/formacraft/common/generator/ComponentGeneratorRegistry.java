package com.formacraft.common.generator;

import com.formacraft.common.generator.impl.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 构件层生成器注册表（Phase 6）。
 * <p>
 * {@code component_type} → {@link ComponentGenerator} 映射。
 * 与整栋层的 {@code StructureGeneratorRegistry} 对称命名，避免混淆。
 */
public final class ComponentGeneratorRegistry {

    private static final Map<String, ComponentGenerator> REGISTRY = new HashMap<>();

    static {
        // 核心构件
        register("TOWER", new TowerComponentGenerator());
        register("KEEP", new KeepGenerator());
        register("WALL", new WallComponentGenerator());
        register("WALL_SEGMENT", new WallComponentGenerator());
        register("GATE", new GateGenerator());
        register("ROAD", new RoadGenerator());

        // 语义组件
        register("MASS_MAIN", new MassMainGenerator());
        register("MASS_SECONDARY", new MassMainGenerator());
        register("ENTRANCE", new EntranceGenerator());
        register("SIGNAGE", new SignageGenerator());
        register("FACADE_WINDOWS", new FacadeWindowsGenerator());
        register("PAVING", new RoadGenerator());
        register("FENCE_OR_WALL", new WallComponentGenerator());

        register("ROOF", new RoofGenerator());
        register("COURTYARD_SPACE", new CourtyardSpaceGenerator());
        register("COURTYARD", new CourtyardSpaceGenerator());
        register("GATE_STRUCTURE", new GateStructureGenerator());
        register("PATH", new PathComponentGenerator());

        register("BALCONY", new BalconyGenerator());
        register("TERRACE", new TerraceGenerator());
        register("CHIMNEY", new ChimneyGenerator());
        register("FOUNDATION", new FoundationGenerator());
        register("DECOR_DETAIL", new DecorDetailGenerator());

        register("TOWER_BASE", new TowerComponentGenerator());
        register("TOWER_MID", new TowerComponentGenerator());
        register("TOWER_TOP", new TowerComponentGenerator());

        register("SIDE_WING", new MassMainGenerator());
        register("MASS_WING", new MassMainGenerator());

        register("ENTRANCE_CANOPY", new EntranceGenerator());
        register("ROOF_STRUCTURE", new RoofGenerator());

        register("TERRACE_PLAZA", new TerraceGenerator());
        register("PLAZA", new TerraceGenerator());

        register("CONNECTOR", new PathComponentGenerator());
        register("BRIDGE", new PathComponentGenerator());
        register("BRIDGE_CONNECTOR", new PathComponentGenerator());
    }

    private ComponentGeneratorRegistry() {}

    public static void register(String type, ComponentGenerator generator) {
        if (type != null && generator != null) {
            REGISTRY.put(type.toUpperCase(), generator);
        }
    }

    public static ComponentGenerator getGenerator(String type) {
        if (type == null) return null;
        return REGISTRY.get(type.toUpperCase());
    }

    public static boolean hasGenerator(String type) {
        if (type == null) return false;
        return REGISTRY.containsKey(type.toUpperCase());
    }
}
