package com.formacraft.server.generator;

import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.server.generator.router.GeneratorRouter;

/**
 * 建筑生成器工厂
 * 根据 BuildingSpec 的类型选择合适的生成器
 */
public class StructureGeneratorFactory {
    public static StructureGenerator getGenerator(BuildingSpec spec) {
        // 统一入口：BuildingGenome -> Router -> ConcreteGenerator
        // IMPORTANT：若 spec.extra 不含 genome，则 Router 会回退到旧的 type 路由，保持现有行为不变。
        return GeneratorRouter.route(spec);
    }
}

