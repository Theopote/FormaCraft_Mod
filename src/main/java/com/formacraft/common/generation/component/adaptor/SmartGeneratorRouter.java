package com.formacraft.common.generation.component.adaptor;

import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.patch.BlockPatch;
import net.minecraft.server.world.ServerWorld;

import java.util.List;

/**
 * @deprecated 使用 {@link UnifiedGeneratorRouter}。此类保留为兼容别名。
 */
@Deprecated
public final class SmartGeneratorRouter {

    private SmartGeneratorRouter() {}

    /**
     * @see UnifiedGeneratorRouter#generate(SemanticComponent, ServerWorld)
     */
    public static List<BlockPatch> generate(SemanticComponent semantic, ServerWorld world) {
        return UnifiedGeneratorRouter.generate(semantic, world);
    }

    /**
     * @deprecated 使用 {@link UnifiedGeneratorRouter} 内的回退策略。
     */
    @Deprecated
    public static boolean shouldUseTraditionalSystem(String componentType) {
        if (componentType == null) return false;
        String type = componentType.toUpperCase();
        return type.equals("HOUSE")
                || type.equals("CASTLE")
                || type.equals("KEEP")
                || type.equals("COMPOUND");
    }
}
