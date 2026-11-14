package com.formacraft.server.generation.typology.interpreter;

import com.formacraft.common.build.GeneratedStructure;
import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.typology.TypologyParamResolver;
import com.formacraft.common.typology.TypologyPatchBridge;
import com.formacraft.server.generation.typology.builder.TrussTowerBuilder;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.Map;

/** Native typology interpreter for {@code truss_tower}. */
public final class TrussTowerInterpreter implements com.formacraft.server.generation.typology.TypologyInterpreter {

    @Override
    public String typologyId() {
        return TrussTowerBuilder.TYPOLOGY_ID;
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
        if (semantic.source().relativePosition() != null) {
            var rp = semantic.source().relativePosition();
            origin = origin.add(rp.x(), rp.y(), rp.z());
        }
        Map<String, Object> params = TypologyParamResolver.merge(typologyId(), semantic.source());
        if (semantic.source().dimensions() != null) {
            params.putIfAbsent("width", semantic.source().dimensions().width());
            params.putIfAbsent("height", semantic.source().dimensions().height());
            params.putIfAbsent("baseWidth", Math.max(
                    semantic.source().dimensions().width(),
                    semantic.source().dimensions().depth()));
        }
        GeneratedStructure structure = TrussTowerBuilder.generate(params, origin, world, null);
        return TypologyPatchBridge.toBlockPatches(structure, origin);
    }
}
