package com.formacraft.common.generation.component.impl;

import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.generation.component.ComponentGenerator;
import com.formacraft.common.geometry.shape.ShapeCsgOperation;
import com.formacraft.common.geometry.shape.ShapeLibrary;
import com.formacraft.common.geometry.shape.ShapeSpec;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.palette.component.PaletteLibrary;
import com.formacraft.common.semantic.SemanticPart;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * PRIMITIVE 组件生成器（ShapeLibrary M1 + M2 + M3）。
 * <p>
 * params.kind: box | cylinder | cone | frustum | prism | sphere | ellipse | sector | triangle | voronoi | mobius
 * params: extrude_mode, hollow, rotation_*_deg, CSG operations[], subtract{}
 */
public class PrimitiveShapeGenerator implements ComponentGenerator {

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

        List<ShapeLibrary.Voxel> voxels;
        List<ShapeCsgOperation> ops = ShapeSpec.parseOperations(d.width(), d.depth(), d.height(), params);
        if (!ops.isEmpty()) {
            voxels = ShapeLibrary.generateComposite(ops);
        } else {
            voxels = ShapeLibrary.generate(ShapeSpec.fromParams(d.width(), d.depth(), d.height(), params));
        }

        String block = resolveBlock(semantic, params);

        for (ShapeLibrary.Voxel v : voxels) {
            out.add(new BlockPatch(
                    BlockPatch.PLACE,
                    rp.x() + v.x(),
                    rp.y() + v.y(),
                    rp.z() + v.z(),
                    block
            ));
        }
        return out;
    }

    private static String resolveBlock(SemanticComponent semantic, Map<String, Object> params) {
        String material = paramString(params, "material", "block", "block_id", "blockId");
        if (material != null && !material.isBlank()) {
            String m = material.trim().toLowerCase(Locale.ROOT);
            if (m.contains(":")) {
                return m;
            }
            return switch (m) {
                case "glass" -> "minecraft:glass";
                case "stone" -> "minecraft:stone";
                case "cobblestone" -> "minecraft:cobblestone";
                case "deepslate" -> "minecraft:deepslate";
                case "concrete" -> "minecraft:gray_concrete";
                case "wood", "oak" -> "minecraft:oak_planks";
                case "brick" -> "minecraft:bricks";
                case "iron" -> "minecraft:iron_block";
                case "gold" -> "minecraft:gold_block";
                case "quartz" -> "minecraft:quartz_block";
                default -> "minecraft:" + m;
            };
        }

        String style = semantic.styleProfile();
        if (style == null || style.isBlank()) {
            style = "DEFAULT";
        }
        String resolved = PaletteLibrary.resolveBlock(SemanticPart.WALL_BASE, style, semantic.styleAttributes());
        return (resolved != null && !resolved.isBlank()) ? resolved : "minecraft:stone";
    }

    private static String paramString(Map<String, Object> params, String... keys) {
        if (params == null) return null;
        for (String key : keys) {
            Object v = params.get(key);
            if (v == null) continue;
            String s = String.valueOf(v).trim();
            if (!s.isEmpty()) return s;
        }
        return null;
    }
}
