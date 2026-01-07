package com.formacraft.common.compiler.postprocess;

import com.formacraft.common.patch.BlockPatch;

import java.util.List;

/**
 * PostProcessor（后处理器接口）
 * 
 * 后处理器用于在生成器生成基础结构后，对 BlockPatch 列表进行后处理，
 * 例如：细节装饰增强、材质变化、地形适应等。
 * 
 * 后处理器应该：
 * - 不改变 BlockPatch 的数量（只修改或替换，不删除）
 * - 保持相对坐标不变
 * - 可以添加新的 BlockPatch（用于细节增强）
 */
public interface PostProcessor {

    /**
     * 处理 BlockPatch 列表
     * 
     * @param patches 输入的 BlockPatch 列表（相对坐标）
     * @param context 后处理上下文
     * @return 处理后的 BlockPatch 列表（相对坐标）
     */
    List<BlockPatch> process(List<BlockPatch> patches, PostProcessContext context);
}

