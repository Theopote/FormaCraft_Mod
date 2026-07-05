package com.formacraft.common.generation.component.impl;

import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.generation.component.ComponentGenerator;
import com.formacraft.common.generation.component.util.ComponentCrownDecorator;
import com.formacraft.common.generation.component.util.ComponentCrownRevolveSolver;
import com.formacraft.common.generation.component.util.CrownTemplateLibrary;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.palette.component.Palette;
import com.formacraft.common.palette.component.PaletteLibrary;
import com.formacraft.common.semantic.SemanticPart;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * CROWN 组件：在屋顶之上装配 REVOLVE_SURFACE 冠部（穹顶 / 葱头 / 圆顶）。
 */
public class CrownGenerator implements ComponentGenerator {

    @Override
    public List<BlockPatch> generate(SemanticComponent semantic) {
        List<BlockPatch> out = new ArrayList<>();
        Component c = semantic.source();
        if (c == null || c.dimensions() == null || c.relativePosition() == null) {
            return out;
        }

        Dimensions d = c.dimensions();
        Vec3i rp = c.relativePosition();
        Map<String, Object> params = c.params() != null ? c.params() : Map.of();

        ComponentCrownDecorator.CrownDimensions crownDims = ComponentCrownDecorator.resolveDimensions(d, params);
        int width = crownDims.width();
        int depth = crownDims.depth();
        int height = crownDims.height();

        int centerX = rp.x() + width / 2;
        int centerZ = rp.z() + depth / 2;
        int baseY = rp.y();

        String templateId = ComponentCrownDecorator.resolveTemplate(null, params, semantic);
        List<double[]> profile = CrownTemplateLibrary.profile(templateId);
        int segments = ComponentCrownDecorator.resolveSegments(params);

        String styleProfile = semantic.styleProfile() != null ? semantic.styleProfile() : "MEDIEVAL_CLASSIC";
        Palette palette = PaletteLibrary.forStyle(styleProfile);
        String block = palette.pick(SemanticPart.ROOF_SURFACE);
        if (block == null || block.isBlank()) {
            block = palette.pick(SemanticPart.DECOR);
        }
        if (block == null || block.isBlank()) {
            block = palette.pick(SemanticPart.WALL_ACCENT);
        }
        if (block == null || block.isBlank()) {
            block = "minecraft:quartz_block";
        }

        ComponentCrownRevolveSolver.emitRevolveSolid(
                out,
                centerX,
                baseY,
                centerZ,
                crownDims.radiusScale(),
                height,
                profile,
                block,
                segments
        );
        return out;
    }
}
