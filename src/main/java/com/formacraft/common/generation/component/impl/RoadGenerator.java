package com.formacraft.common.generation.component.impl;

import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.generation.component.ComponentGenerator;
import com.formacraft.common.generation.component.util.PathStripUtil;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.semantic.SemanticPart;

import java.util.ArrayList;
import java.util.List;

/**
 * RoadGenerator（道路生成器 v2）
 *
 * Palette 权重平面道路；有 params.from/to 时与 PATH 共用折线条带（GEOMETRY）。
 */
public class RoadGenerator implements ComponentGenerator {

    @Override
    public List<BlockPatch> generate(SemanticComponent semantic) {
        Component c = semantic == null ? null : semantic.source();
        if (c == null) return List.of();

        PathStripUtil.Endpoints endpoints = PathStripUtil.resolveEndpoints(c);
        if (endpoints != null) {
            return PathStripUtil.emitStrip(
                    semantic, endpoints, "minecraft:gravel",
                    "road_block", "floor_block", "material", "path_block");
        }

        List<BlockPatch> out = new ArrayList<>();
        if (c.dimensions() == null || c.relativePosition() == null) {
            return out;
        }

        Dimensions d = c.dimensions();
        Vec3i rp = com.formacraft.common.generation.component.util.ComponentFootprintUtil.resolveLegacyCornerOrigin(c);

        int width = Math.max(1, d.width());
        int depth = Math.max(1, d.depth());
        int height = Math.max(1, d.height());

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                for (int z = 0; z < depth; z++) {
                    boolean isEdge = (x == 0 || x == width - 1 || z == 0 || z == depth - 1);
                    SemanticPart part = (isEdge && width >= 3 && depth >= 3)
                            ? SemanticPart.ROAD_EDGE
                            : SemanticPart.ROAD_SURFACE;
                    String block = com.formacraft.common.generation.component.util.ComponentMaterialResolver.resolve(
                            semantic, part, "minecraft:gravel", "road_block", "floor_block", "material");
                    out.add(new BlockPatch(
                            BlockPatch.PLACE,
                            rp.x() + x,
                            rp.y() + y,
                            rp.z() + z,
                            block
                    ));
                }
            }
        }

        return out;
    }
}
