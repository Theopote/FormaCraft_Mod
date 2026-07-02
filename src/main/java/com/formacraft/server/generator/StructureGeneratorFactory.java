package com.formacraft.server.generator;

import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.server.generator.router.GeneratorRouter;

/**
 * 建筑生成器工厂。
 * <p>
 * 统一入口：{@link com.formacraft.server.generation.GenerationHub#routeStructure(BuildingSpec)}
 * 内部委托 {@link GeneratorRouter}（数据驱动路由，见 {@code structure_routes_v1.json}）。
 */
public class StructureGeneratorFactory {
    public static StructureGenerator getGenerator(BuildingSpec spec) {
        // 统一入口：BuildingGenome -> Router -> ConcreteGenerator
        // IMPORTANT：若 spec.extra 不含 genome，则 Router 会回退到旧的 type 路由，保持现有行为不变。
        return GeneratorRouter.route(spec);
    }
}

