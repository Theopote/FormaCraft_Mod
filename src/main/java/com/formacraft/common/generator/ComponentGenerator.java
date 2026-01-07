package com.formacraft.common.generator;

import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.patch.BlockPatch;

import java.util.List;

/**
 * ComponentGenerator（构件生成器接口）
 * 
 * 所有构件的统一入口
 * 
 * 核心职责：
 * - 将 SemanticComponent 转换为 BlockPatch 列表
 * - 生成的 patch 是相对 slot.anchor 的
 * - 不关心 UI / 网络，只负责几何生成
 */
public interface ComponentGenerator {

    /**
     * 生成 block-level patch（相对 slot.anchor）
     * 
     * @param component 语义构件
     * @return BlockPatch 列表（相对 slot.anchor）
     */
    List<BlockPatch> generate(SemanticComponent component);
}

