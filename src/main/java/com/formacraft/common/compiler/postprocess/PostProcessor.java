package com.formacraft.common.compiler.postprocess;

import com.formacraft.common.patch.BlockPatch;

import java.util.List;

/**
 * PostProcessor（后处理器接口）
 *
 * 后处理器用于在生成器生成基础结构后，对 BlockPatch 列表进行后处理，
 * 例如：细节装饰增强、材质变化、地形适应等。
 * <p>
 * 约定：不得修改 {@code context} 或入参 list；通过返回值产出新的 Patch 列表。
 */
public interface PostProcessor {

    enum FailurePolicy {
        /** 保留上一阶段结果，打 WARN，继续后续处理器 */
        SOFT,
        /** 恢复上一阶段后中止管道，由编译器记 CapabilityGap */
        HARD
    }

    /**
     * @param patches 输入的 BlockPatch 列表（相对坐标）
     * @param context 后处理上下文
     * @return 处理后的 BlockPatch 列表（相对坐标）
     */
    List<BlockPatch> process(List<BlockPatch> patches, PostProcessContext context);

    default FailurePolicy failurePolicy() {
        return FailurePolicy.SOFT;
    }

    /** Occupancy write authority for this processor's delta. Default: decorate-only. */
    default PostProcessWritePolicy writePolicy() {
        return PostProcessWritePolicy.PLACE_ONLY;
    }
}
