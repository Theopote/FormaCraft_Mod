package com.formacraft.server.generator;

import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.common.model.build.BuildingType;
import com.formacraft.FormacraftMod;

/**
 * 建筑生成器工厂
 * 根据 BuildingSpec 的类型选择合适的生成器
 */
public class StructureGeneratorFactory {
    public static StructureGenerator getGenerator(BuildingSpec spec) {
        if (spec == null || spec.getType() == null) {
            FormacraftMod.LOGGER.warn("BuildingSpec or type is null, using default TowerGenerator");
            return new TowerGenerator();
        }

        BuildingType type = spec.getType();
        return switch (type) {
            case TOWER -> new TowerGenerator();
            case HOUSE -> new HouseGenerator();
            case BRIDGE -> new BridgeGenerator();
            case WALL -> new WallGenerator();
            case CASTLE -> new HouseGenerator(); // 暂时使用房屋生成器
            case CUSTOM -> {
                FormacraftMod.LOGGER.warn("CUSTOM building type not yet implemented, using TowerGenerator");
                yield new TowerGenerator();
            }
        };
    }
}

