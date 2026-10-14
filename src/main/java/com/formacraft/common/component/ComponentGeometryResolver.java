package com.formacraft.common.component;

import com.formacraft.FormacraftMod;
import com.formacraft.common.component.transform.ComponentTransform;
import com.formacraft.common.component.transform.Mirror;
import com.formacraft.common.component.variant.VariantCompileResult;
import com.formacraft.common.patch.BlockPatch;
import net.minecraft.util.math.Direction;

import java.util.List;

/** Single PLACE emitter for library/captured {@link ComponentDefinition}s. */
public final class ComponentGeometryResolver {
    private ComponentGeometryResolver() {}

    public static ResolvedComponentGeometry emit(
            ComponentDefinition def,
            int baseX, int baseY, int baseZ,
            Direction targetFacing,
            Mirror mirror,
            boolean semanticSkin,
            String semanticStyleId,
            long seedBase
    ) {
        if (def == null) {
            return ResolvedComponentGeometry.failure(
                    VariantCompileResult.Status.INVALID_INPUT, "", "definition_null");
        }
        String id = def.id != null ? def.id : "";
        if (def.blocks == null || def.blocks.isEmpty()) {
            ResolvedComponentGeometry missing = ResolvedComponentGeometry.failure(
                    VariantCompileResult.Status.TEMPLATE_MISSING, id, "blocks_empty");
            FormacraftMod.LOGGER.error(
                    "[ComponentGeometryResolver] emit failed id={} status={} message={}",
                    id, missing.status(), missing.message());
            return missing;
        }
        List<BlockPatch> patches = ComponentDefinitionCompiler.compile(
                def, baseX, baseY, baseZ, targetFacing, mirror, semanticSkin, semanticStyleId, seedBase);
        return ResolvedComponentGeometry.success(def, patches);
    }

    /** Expander path: facing already derived; reuse the same transform for PLACE (and carve). */
    public static ResolvedComponentGeometry emit(
            ComponentDefinition def,
            Direction fromFacing,
            ComponentTransform transform,
            int baseX, int baseY, int baseZ,
            boolean semanticSkin,
            String semanticStyleId,
            long seedBase
    ) {
        if (def == null) {
            return ResolvedComponentGeometry.failure(
                    VariantCompileResult.Status.INVALID_INPUT, "", "definition_null");
        }
        String id = def.id != null ? def.id : "";
        if (def.blocks == null || def.blocks.isEmpty()) {
            ResolvedComponentGeometry missing = ResolvedComponentGeometry.failure(
                    VariantCompileResult.Status.TEMPLATE_MISSING, id, "blocks_empty");
            FormacraftMod.LOGGER.error(
                    "[ComponentGeometryResolver] emit failed id={} status={} message={}",
                    id, missing.status(), missing.message());
            return missing;
        }
        List<BlockPatch> patches = ComponentDefinitionCompiler.compile(
                def, fromFacing, transform, baseX, baseY, baseZ, semanticSkin, semanticStyleId, seedBase);
        return ResolvedComponentGeometry.success(def, patches);
    }
}
