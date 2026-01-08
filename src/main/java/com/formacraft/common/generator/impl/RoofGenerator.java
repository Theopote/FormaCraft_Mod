package com.formacraft.common.generator.impl;

import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.generator.ComponentGenerator;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.palette.component.Palette;
import com.formacraft.common.palette.component.PaletteLibrary;
import com.formacraft.common.palette.dynamic.DynamicPaletteResolver;
import com.formacraft.common.semantic.SemanticPart;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * RoofGenerator（屋顶生成器）
 * 
 * 生成屋顶结构（斜屋顶、平屋顶等）
 */
public class RoofGenerator implements ComponentGenerator {

    @Override
    public List<BlockPatch> generate(SemanticComponent semantic) {
        List<BlockPatch> out = new ArrayList<>();

        Component c = semantic.source();
        if (c == null || c.dimensions() == null || c.relativePosition() == null) {
            return out;
        }

        Dimensions d = c.dimensions();
        Vec3i rp = c.relativePosition();

        int width = Math.max(1, d.width());
        int depth = Math.max(1, d.depth());
        int height = Math.max(1, d.height());
        Map<String, Object> params = c.params();

        String styleProfile = getStyleProfile(semantic);
        Palette palette = PaletteLibrary.forStyle(styleProfile);

        RoofType roofType = resolveRoofType(c, semantic, params);
        int roofHeight = getParamInt(params, 0, "roof_height", "roofHeight", "roofHeightBlocks");
        if (roofHeight <= 0) {
            int span = Math.max(2, Math.min(width, depth));
            roofHeight = Math.max(2, Math.min(8, Math.max(2, span / 3)));
        }
        height = Math.max(1, roofHeight);

        int overhang = getParamInt(params, 0, "overhang", "overhang_blocks", "eave_overhang");
        int baseX = rp.x();
        int baseZ = rp.z();
        if (overhang > 0) {
            baseX = rp.x() - overhang;
            baseZ = rp.z() - overhang;
            width = Math.max(1, width + overhang * 2);
            depth = Math.max(1, depth + overhang * 2);
        }

        switch (roofType) {
            case GABLE -> generateGableRoof(out, semantic, baseX, rp.y(), baseZ, width, depth, height, palette);
            case HIP -> generateHipRoof(out, semantic, baseX, rp.y(), baseZ, width, depth, height, palette);
            case PYRAMID -> generatePyramidRoof(out, semantic, baseX, rp.y(), baseZ, width, depth, height, palette);
            case CONE -> generateConeRoof(out, semantic, baseX, rp.y(), baseZ, width, depth, height, palette, false);
            case DOME -> generateConeRoof(out, semantic, baseX, rp.y(), baseZ, width, depth, height, palette, true);
            default -> generateFlatRoof(out, semantic, baseX, rp.y(), baseZ, width, depth, height, palette);
        }

        return out;
    }

    private void generateGableRoof(List<BlockPatch> out, SemanticComponent semantic, int baseX, int baseY, int baseZ,
                                   int width, int depth, int height, Palette palette) {
        if (width < 2 || depth < 2 || height < 2) {
            generateFlatRoof(out, semantic, baseX, baseY, baseZ, width, depth, height, palette);
            return;
        }
        boolean alongDepth = depth >= width;
        int center = alongDepth ? depth / 2 : width / 2;
        int maxSpan = Math.max(1, alongDepth ? depth / 2 : width / 2);
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < depth; z++) {
                int axis = alongDepth ? z : x;
                int dist = Math.abs(axis - center);
                int rise = (int) Math.round((1.0 - (dist / (double) maxSpan)) * (height - 1));
                if (rise < 0) continue;
                SemanticPart part = (dist == 0) ? SemanticPart.ROOF_SURFACE : SemanticPart.ROOF;
                String block = getBlockForPart(semantic, palette, part);
                out.add(new BlockPatch(
                        BlockPatch.PLACE,
                        baseX + x,
                        baseY + rise,
                        baseZ + z,
                        block
                ));
            }
        }
    }

    private void generateHipRoof(List<BlockPatch> out, SemanticComponent semantic, int baseX, int baseY, int baseZ,
                                 int width, int depth, int height, Palette palette) {
        if (width < 2 || depth < 2 || height < 2) {
            generateFlatRoof(out, semantic, baseX, baseY, baseZ, width, depth, height, palette);
            return;
        }
        int centerX = width / 2;
        int centerZ = depth / 2;
        int maxSpan = Math.max(1, Math.min(width, depth) / 2);
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < depth; z++) {
                int dist = Math.max(Math.abs(x - centerX), Math.abs(z - centerZ));
                int rise = (int) Math.round((1.0 - (dist / (double) maxSpan)) * (height - 1));
                if (rise < 0) continue;
                SemanticPart part = (dist == 0) ? SemanticPart.ROOF_SURFACE : SemanticPart.ROOF;
                String block = getBlockForPart(semantic, palette, part);
                out.add(new BlockPatch(
                        BlockPatch.PLACE,
                        baseX + x,
                        baseY + rise,
                        baseZ + z,
                        block
                ));
            }
        }
    }

    private void generatePyramidRoof(List<BlockPatch> out, SemanticComponent semantic, int baseX, int baseY, int baseZ,
                                     int width, int depth, int height, Palette palette) {
        generateHipRoof(out, semantic, baseX, baseY, baseZ, width, depth, height, palette);
    }

    private void generateConeRoof(List<BlockPatch> out, SemanticComponent semantic, int baseX, int baseY, int baseZ,
                                  int width, int depth, int height,
                                  Palette palette, boolean dome) {
        if (width < 2 || depth < 2 || height < 2) {
            generateFlatRoof(out, semantic, baseX, baseY, baseZ, width, depth, height, palette);
            return;
        }
        double cx = (width - 1) / 2.0;
        double cz = (depth - 1) / 2.0;
        double radius = Math.max(1.0, Math.min(width, depth) / 2.0);
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < depth; z++) {
                double dx = x - cx;
                double dz = z - cz;
                double dist = Math.sqrt(dx * dx + dz * dz);
                if (dist > radius + 0.25) {
                    continue;
                }
                double t = Math.max(0.0, 1.0 - (dist / radius));
                double curve = dome ? Math.sqrt(t) : t;
                int rise = (int) Math.round(curve * (height - 1));
                SemanticPart part = (dist <= 0.5) ? SemanticPart.ROOF_SURFACE : SemanticPart.ROOF;
                String block = getBlockForPart(semantic, palette, part);
                out.add(new BlockPatch(
                        BlockPatch.PLACE,
                        baseX + x,
                        baseY + rise,
                        baseZ + z,
                        block
                ));
            }
        }
    }

    /**
     * 生成平屋顶
     */
    private void generateFlatRoof(List<BlockPatch> out, SemanticComponent semantic, int baseX, int baseY, int baseZ,
                                  int width, int depth, int height, Palette palette) {
        // 平屋顶：只在最上层放置方块
        int topY = height - 1;
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < depth; z++) {
                SemanticPart part = SemanticPart.ROOF_SURFACE;
                String block = getBlockForPart(semantic, palette, part);
                
                out.add(new BlockPatch(
                        BlockPatch.PLACE,
                        baseX + x,
                        baseY + topY,
                        baseZ + z,
                        block
                ));
            }
        }
    }

    private String getStyleProfile(SemanticComponent semantic) {
        if (semantic != null && semantic.styleProfile() != null && !semantic.styleProfile().isBlank()) {
            String profile = semantic.styleProfile().trim();
            String upper = profile.toUpperCase();
            if (upper.contains("GOTHIC")) {
                return "MEDIEVAL_CLASSIC";
            }
            if (upper.contains("CHINESE") || upper.contains("HUI")) {
                return "HUI_STYLE_VILLA";
            }
            return profile;
        }
        Component c = semantic != null ? semantic.source() : null;
        if (c != null && c.features() != null) {
            for (String feature : c.features()) {
                if (feature == null) continue;
                String lower = feature.toLowerCase();
                if (lower.contains("gothic") || lower.contains("pointed") || lower.contains("ribbed")) {
                    return "MEDIEVAL_CLASSIC";
                }
                if (lower.contains("chinese") || lower.contains("中式")) {
                    return "HUI_STYLE_VILLA";
                }
            }
        }
        return "MEDIEVAL_CLASSIC";
    }

    private RoofType resolveRoofType(Component c, SemanticComponent semantic, Map<String, Object> params) {
        String type = getParamString(params, "roof_type", "roofType");
        if (type == null && c.features() != null) {
            for (String feature : c.features()) {
                if (feature == null) continue;
                String lower = feature.toLowerCase();
                if (lower.contains("gable")) type = "gable";
                else if (lower.contains("hip")) type = "hip";
                else if (lower.contains("pyramid")) type = "pyramid";
                else if (lower.contains("cone")) type = "cone";
                else if (lower.contains("dome") || lower.contains("curved")) type = "dome";
                else if (lower.contains("sloped") || lower.contains("pitched")) type = "gable";
            }
        }
        if (type == null && semantic != null && semantic.genome() != null && semantic.genome().form != null) {
            String curvature = semantic.genome().form.curvature;
            if ("curved".equalsIgnoreCase(curvature)) {
                type = "dome";
            }
            String progression = semantic.genome().form.progression;
            if ("tapering".equalsIgnoreCase(progression)) {
                type = "pyramid";
            }
        }
        if (type == null) {
            String profile = getStyleProfile(semantic);
            if (profile != null) {
                String upper = profile.toUpperCase();
                if (upper.contains("CHINESE") || upper.contains("HUI")) {
                    return RoofType.GABLE;
                }
                if (upper.contains("GOTHIC") || upper.contains("MEDIEVAL")) {
                    return RoofType.GABLE;
                }
                if (upper.contains("MODERN")) {
                    return RoofType.FLAT;
                }
            }
            return RoofType.GABLE;
        }
        return switch (type.trim().toLowerCase()) {
            case "gable", "gabled" -> RoofType.GABLE;
            case "hip", "hipped" -> RoofType.HIP;
            case "pyramid" -> RoofType.PYRAMID;
            case "cone", "conical" -> RoofType.CONE;
            case "dome", "domed", "curved" -> RoofType.DOME;
            default -> RoofType.FLAT;
        };
    }

    private static String getParamString(Map<String, Object> params, String... keys) {
        if (params == null || keys == null) return null;
        for (String key : keys) {
            if (key == null) continue;
            Object v = params.get(key);
            if (v == null) continue;
            String s = String.valueOf(v).trim();
            if (!s.isEmpty()) return s;
        }
        return null;
    }

    private static int getParamInt(Map<String, Object> params, int fallback, String... keys) {
        if (params == null || keys == null) return fallback;
        for (String key : keys) {
            if (key == null) continue;
            Object v = params.get(key);
            if (v == null) continue;
            if (v instanceof Number n) {
                return n.intValue();
            }
            if (v instanceof String s) {
                try {
                    return Integer.parseInt(s.trim());
                } catch (NumberFormatException ignored) {}
            }
        }
        return fallback;
    }

    private static String getBlockForPart(SemanticComponent semantic, Palette palette, SemanticPart part) {
        if (semantic != null && semantic.styleAttributes() != null) {
            String block = DynamicPaletteResolver.resolve(part, semantic.styleAttributes());
            if (block != null && !block.isEmpty()) {
                return block;
            }
        }
        if (palette != null) {
            String block = palette.pick(part);
            if (block != null && !block.isEmpty()) {
                return block;
            }
        }
        return switch (part) {
            case ROOF, ROOF_SURFACE -> "minecraft:spruce_planks";
            default -> "minecraft:stone_bricks";
        };
    }

    private enum RoofType {
        FLAT,
        GABLE,
        HIP,
        PYRAMID,
        CONE,
        DOME
    }
}

