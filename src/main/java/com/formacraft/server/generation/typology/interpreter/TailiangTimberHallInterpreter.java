package com.formacraft.server.generation.typology.interpreter;

import com.formacraft.common.build.GeneratedStructure;
import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.typology.TypologyParamResolver;
import com.formacraft.common.typology.TypologyPatchBridge;
import com.formacraft.server.generation.typology.builder.TailiangTimberHallBuilder;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.Map;

/** Native typology interpreter for {@code tailiang_timber_hall} — no landmark id required. */
public final class TailiangTimberHallInterpreter implements com.formacraft.common.typology.TypologyInterpreter {

    @Override
    public String typologyId() {
        return TailiangTimberHallBuilder.TYPOLOGY_ID;
    }

    @Override
    public List<BlockPatch> interpret(SemanticComponent semantic, ServerWorld world) {
        if (semantic == null || world == null || semantic.source() == null) {
            return List.of();
        }
        BlockPos origin = TypologyPatchBridge.worldBuildOrigin(semantic);
        if (origin == null) {
            return List.of();
        }
        Map<String, Object> params = TypologyParamResolver.merge(typologyId(), semantic.source());
        GeneratedStructure structure = TailiangTimberHallBuilder.generate(params, origin, world, null);
        return TypologyPatchBridge.toBlockPatches(structure, origin);
    }
}
