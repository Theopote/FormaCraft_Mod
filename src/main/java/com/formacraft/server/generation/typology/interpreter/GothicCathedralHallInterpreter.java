package com.formacraft.server.generation.typology.interpreter;

import com.formacraft.common.build.GeneratedStructure;
import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.typology.TypologyParamResolver;
import com.formacraft.common.typology.TypologyPatchBridge;
import com.formacraft.server.generation.typology.builder.GothicCathedralHallBuilder;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.Map;

/** Native typology interpreter for {@code gothic_cathedral_hall}. */
public final class GothicCathedralHallInterpreter implements com.formacraft.common.typology.TypologyInterpreter {

    @Override
    public String typologyId() {
        return GothicCathedralHallBuilder.TYPOLOGY_ID;
    }

    @Override
    public List<BlockPatch> interpret(SemanticComponent semantic, ServerWorld world) {
        if (semantic == null || world == null || semantic.source() == null) {
            return List.of();
        }
        BlockPos origin = TypologyPatchBridge.slotOrigin(semantic);
        if (origin == null) {
            return List.of();
        }
        Map<String, Object> params = TypologyParamResolver.merge(typologyId(), semantic.source());
        GeneratedStructure structure = GothicCathedralHallBuilder.generate(params, origin, world, null);
        return TypologyPatchBridge.toBlockPatches(structure, origin);
    }
}
