package com.formacraft.server.generation.typology;

import com.formacraft.FormacraftMod;
import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.typology.TypologyComponentRouter;
import com.formacraft.common.typology.TypologyGenerationException;
import net.minecraft.server.world.ServerWorld;

import java.util.List;

/** Executes the typology selected by shared component routing metadata. */
public final class TypologyGenerationRouter {
    private TypologyGenerationRouter() {}

    public static List<BlockPatch> tryGenerate(SemanticComponent semantic, ServerWorld world) {
        if (semantic == null || world == null || semantic.source() == null) {
            return List.of();
        }

        String typologyId = TypologyComponentRouter.extractTypologyId(semantic.source());
        if (typologyId == null || typologyId.isBlank()) {
            return List.of();
        }

        TypologyInterpreter interpreter = TypologyInterpreterRegistry.get(typologyId);
        if (interpreter == null) {
            FormacraftMod.LOGGER.debug("TypologyComponentRouter: no interpreter registered for {}", typologyId);
            return List.of();
        }

        try {
            List<BlockPatch> patches = interpreter.interpret(semantic, world);
            if (patches != null && !patches.isEmpty()) {
                com.formacraft.common.network.metrics.TypologyRoutingMetrics.recordTypologyComponentHit(typologyId);
                FormacraftMod.LOGGER.debug(
                        "TypologyComponentRouter: typology={} produced {} patches",
                        typologyId, patches.size()
                );
                return patches;
            }
        } catch (TypologyGenerationException e) {
            // Explicit typology failure — must not be degraded to empty success / structure fallback.
            FormacraftMod.LOGGER.warn(
                    "TypologyComponentRouter: typology={} generation failed code={} diags={}",
                    typologyId, e.code(), e.diagnostics());
            throw e;
        } catch (Exception e) {
            FormacraftMod.LOGGER.warn("TypologyComponentRouter: interpreter failed for {}", typologyId, e);
        }
        return List.of();
    }

}
