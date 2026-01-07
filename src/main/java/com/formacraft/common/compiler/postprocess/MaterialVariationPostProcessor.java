package com.formacraft.common.compiler.postprocess;

import com.formacraft.common.patch.BlockPatch;
import com.formacraft.FormacraftMod;

import java.util.ArrayList;
import java.util.List;

/**
 * MaterialVariationPostProcessor（材质变化后处理器）
 * 
 * 根据风格配置和随机性，对 BlockPatch 的材质进行变化，
 * 使建筑看起来更加自然和真实。
 * 
 * 功能：
 * - 根据风格配置调整材质
 * - 添加材质变化（例如：石头有裂纹、有苔藓）
 * - 保持语义一致性（相同 SemanticPart 使用相似的材质）
 */
public class MaterialVariationPostProcessor implements PostProcessor {

    @Override
    public List<BlockPatch> process(List<BlockPatch> patches, PostProcessContext context) {
        if (patches == null || patches.isEmpty()) {
            return patches;
        }

        List<BlockPatch> result = new ArrayList<>(patches.size());

        // 对每个 patch 进行材质变化
        for (BlockPatch patch : patches) {
            if (patch == null) continue;

            // 当前实现：保持原样
            // 未来可以：
            // 1. 根据位置和上下文选择不同的材质变体
            // 2. 添加随机性（例如：10% 的方块使用变体材质）
            // 3. 根据风格配置调整材质

            result.add(patch);
        }

        FormacraftMod.LOGGER.debug("MaterialVariationPostProcessor: processed {} patches", result.size());
        return result;
    }
}

