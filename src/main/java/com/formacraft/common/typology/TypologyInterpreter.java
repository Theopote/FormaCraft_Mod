package com.formacraft.common.typology;

import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.patch.BlockPatch;
import net.minecraft.server.world.ServerWorld;

import java.util.List;

/**
 * Parametric interpreter for a structural typology id.
 * Phase 1: legacy implementations delegate to existing {@code StructureGenerator} keys.
 */
public interface TypologyInterpreter {

    /** Stable typology id, e.g. {@code dense_eaves_pagoda}. */
    String typologyId();

    /**
     * Interpret semantic component into block patches.
     *
     * @return non-empty patches on success; empty when this interpreter cannot handle the input
     */
    List<BlockPatch> interpret(SemanticComponent semantic, ServerWorld world);
}
