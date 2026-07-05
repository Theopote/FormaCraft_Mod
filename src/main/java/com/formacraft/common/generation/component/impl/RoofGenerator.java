package com.formacraft.common.generation.component.impl;

import com.formacraft.common.generation.component.util.ComponentFootprintMask;
import com.formacraft.common.generation.component.util.ComponentParamParsers;
import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.generation.component.ComponentGenerator;
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
 * <p>
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
        Map<String, Object> params = c.params();

        int coreWidth = Math.max(1, d.width());
        int coreDepth = Math.max(1, d.depth());
        ComponentFootprintMask footprint = ComponentFootprintMask.from(semantic, params, coreWidth, coreDepth);

        int width = coreWidth;
        int depth = coreDepth;
        int height;

        String styleProfile = getStyleProfile(semantic);
        Palette palette = PaletteLibrary.forStyle(styleProfile);

        RoofType roofType = resolveRoofType(c, semantic, params);
        int roofHeight = ComponentParamParsers.intParam(params, "roof_height", "roofHeight", "roofHeightBlocks");
        if (roofHeight <= 0) {
            int span = Math.max(2, Math.min(width, depth));
            roofHeight = Math.max(2, Math.min(8, Math.max(2, span / 3)));
        }
        height = roofHeight;

        int overhang = ComponentParamParsers.intParam(params, "overhang", "overhang_blocks", "eave_overhang");
        if (roofType == RoofType.XUANSHAN && overhang <= 0) {
            overhang = 2;
        } else if (roofType == RoofType.XIESHAN && overhang <= 0) {
            overhang = 1;
        }
        if (hasRoofFeature(c, semantic, "flying_eaves", "flying eaves", "飞檐")) {
            int minOverhang = (roofType == RoofType.XIESHAN || roofType == RoofType.XUANSHAN) ? 3 : 2;
            overhang = Math.max(overhang, minOverhang);
        }
        int baseX = rp.x();
        int baseZ = rp.z();
        int appliedOverhang = overhang;
        if (overhang > 0) {
            baseX = rp.x() - overhang;
            baseZ = rp.z() - overhang;
            width = Math.max(1, coreWidth + overhang * 2);
            depth = Math.max(1, coreDepth + overhang * 2);
        }

        boolean doubleEave = isDoubleEave(params, c.features());
        if (doubleEave && (roofType == RoofType.XIESHAN || roofType == RoofType.XUANSHAN || roofType == RoofType.GABLE
                || roofType == RoofType.DOUBLE_GABLE || roofType == RoofType.HIP)) {
            generateDoubleEaveRoof(out, semantic, roofType, baseX, rp.y(), baseZ, width, depth, height, palette,
                    footprint, appliedOverhang);
        } else {
            switch (roofType) {
                case GABLE, DOUBLE_GABLE, XUANSHAN -> generateGableRoof(out, semantic, baseX, rp.y(), baseZ, width, depth, height, palette,
                        footprint, appliedOverhang);
                case HIP -> generateHipRoof(out, semantic, baseX, rp.y(), baseZ, width, depth, height, palette,
                        footprint, appliedOverhang);
                case XIESHAN -> generateXieshanRoof(out, semantic, baseX, rp.y(), baseZ, width, depth, height, palette,
                        footprint, appliedOverhang);
                case PYRAMID -> generatePyramidRoof(out, semantic, baseX, rp.y(), baseZ, width, depth, height, palette,
                        footprint, appliedOverhang);
                case CONE -> generateConeRoof(out, semantic, baseX, rp.y(), baseZ, width, depth, height, palette, false,
                        footprint, appliedOverhang);
                case DOME -> generateConeRoof(out, semantic, baseX, rp.y(), baseZ, width, depth, height, palette, true,
                        footprint, appliedOverhang);
                default -> generateFlatRoof(out, semantic, baseX, rp.y(), baseZ, width, depth, height, palette,
                        footprint, appliedOverhang);
            }
        }

        boolean emphasizeEaves = hasRoofFeature(c, semantic, "dougong", "flying_eaves", "flying eaves", "飞檐", "斗拱");
        if (emphasizeEaves && roofType != RoofType.FLAT) {
            int eaveOutset = hasRoofFeature(c, semantic, "dougong", "斗拱") ? 2 : 1;
            int eaveY = rp.y() > 0 ? rp.y() - 1 : rp.y();
            addEaveLayer(out, semantic, baseX, eaveY, baseZ, width, depth, palette, eaveOutset, footprint, appliedOverhang);
        }

        return out;
    }

    private static boolean allowsRoofCell(ComponentFootprintMask footprint, int overhang, int localX, int localZ) {
        return footprint == null || footprint.shouldPlaceRoof(localX, localZ, overhang);
    }

    private void generateGableRoof(List<BlockPatch> out, SemanticComponent semantic, int baseX, int baseY, int baseZ,
                                   int width, int depth, int height, Palette palette,
                                   ComponentFootprintMask footprint, int overhang) {
        if (width < 2 || depth < 2 || height < 2) {
            generateFlatRoof(out, semantic, baseX, baseY, baseZ, width, depth, height, palette, footprint, overhang);
            return;
        }
        boolean alongDepth = depth >= width;
        int center = alongDepth ? depth / 2 : width / 2;
        int maxSpan = Math.max(1, alongDepth ? depth / 2 : width / 2);
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < depth; z++) {
                if (!allowsRoofCell(footprint, overhang, x - overhang, z - overhang)) {
                    continue;
                }
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
                                 int width, int depth, int height, Palette palette,
                                 ComponentFootprintMask footprint, int overhang) {
        if (width < 2 || depth < 2 || height < 2) {
            generateFlatRoof(out, semantic, baseX, baseY, baseZ, width, depth, height, palette, footprint, overhang);
            return;
        }
        int centerX = width / 2;
        int centerZ = depth / 2;
        int maxSpan = Math.max(1, Math.min(width, depth) / 2);
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < depth; z++) {
                if (!allowsRoofCell(footprint, overhang, x - overhang, z - overhang)) {
                    continue;
                }
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

    private void generateXieshanRoof(List<BlockPatch> out, SemanticComponent semantic, int baseX, int baseY, int baseZ,
                                     int width, int depth, int height, Palette palette,
                                     ComponentFootprintMask footprint, int overhang) {
        if (width < 2 || depth < 2 || height < 2) {
            generateFlatRoof(out, semantic, baseX, baseY, baseZ, width, depth, height, palette, footprint, overhang);
            return;
        }
        boolean ridgeAlongDepth = depth >= width;
        int spanAcross = Math.max(1, (ridgeAlongDepth ? width : depth) / 2);
        int centerAcross = ridgeAlongDepth ? width / 2 : depth / 2;
        int ridgeInset = Math.max(1, (ridgeAlongDepth ? depth : width) / 4);
        int maxRise = height - 1;

        for (int x = 0; x < width; x++) {
            for (int z = 0; z < depth; z++) {
                if (!allowsRoofCell(footprint, overhang, x - overhang, z - overhang)) {
                    continue;
                }
                int distAcross = ridgeAlongDepth ? Math.abs(x - centerAcross) : Math.abs(z - centerAcross);
                int riseAcross = (int) Math.round((1.0 - (distAcross / (double) spanAcross)) * maxRise);
                if (riseAcross < 0) continue;

                int distAlong = ridgeAlongDepth ? Math.min(z, depth - 1 - z) : Math.min(x, width - 1 - x);
                int riseAlong;
                if (distAlong >= ridgeInset) {
                    riseAlong = maxRise;
                } else {
                    riseAlong = (int) Math.round((distAlong / (double) ridgeInset) * maxRise);
                }

                int rise = Math.min(riseAcross, riseAlong);
                if (rise < 0) continue;
                SemanticPart part = (rise >= maxRise && distAcross == 0 && distAlong >= ridgeInset)
                        ? SemanticPart.ROOF_SURFACE
                        : SemanticPart.ROOF;
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

    private void generateDoubleEaveRoof(List<BlockPatch> out, SemanticComponent semantic, RoofType roofType, int baseX, int baseY,
                                        int baseZ, int width, int depth, int height, Palette palette,
                                        ComponentFootprintMask footprint, int overhang) {
        int upperHeight = Math.max(2, height / 2);
        int upperInset = Math.max(2, Math.min(width, depth) / 4);
        int upperWidth = Math.max(2, width - (upperInset * 2));
        int upperDepth = Math.max(2, depth - (upperInset * 2));
        int upperBaseX = baseX + upperInset;
        int upperBaseZ = baseZ + upperInset;
        int upperBaseY = baseY + (height - upperHeight);

        switch (roofType) {
            case XIESHAN -> {
                generateXieshanRoof(out, semantic, baseX, baseY, baseZ, width, depth, height, palette, footprint, overhang);
                generateXieshanRoof(out, semantic, upperBaseX, upperBaseY, upperBaseZ, upperWidth, upperDepth, upperHeight, palette, footprint, overhang);
            }
            case XUANSHAN, GABLE, DOUBLE_GABLE -> {
                generateGableRoof(out, semantic, baseX, baseY, baseZ, width, depth, height, palette, footprint, overhang);
                generateGableRoof(out, semantic, upperBaseX, upperBaseY, upperBaseZ, upperWidth, upperDepth, upperHeight, palette, footprint, overhang);
            }
            case HIP -> {
                generateHipRoof(out, semantic, baseX, baseY, baseZ, width, depth, height, palette, footprint, overhang);
                generateHipRoof(out, semantic, upperBaseX, upperBaseY, upperBaseZ, upperWidth, upperDepth, upperHeight, palette, footprint, overhang);
            }
            default -> {
                generateFlatRoof(out, semantic, baseX, baseY, baseZ, width, depth, height, palette, footprint, overhang);
                generateFlatRoof(out, semantic, upperBaseX, upperBaseY, upperBaseZ, upperWidth, upperDepth, upperHeight, palette, footprint, overhang);
            }
        }
    }

    private void generatePyramidRoof(List<BlockPatch> out, SemanticComponent semantic, int baseX, int baseY, int baseZ,
                                     int width, int depth, int height, Palette palette,
                                     ComponentFootprintMask footprint, int overhang) {
        generateHipRoof(out, semantic, baseX, baseY, baseZ, width, depth, height, palette, footprint, overhang);
    }

    private void generateConeRoof(List<BlockPatch> out, SemanticComponent semantic, int baseX, int baseY, int baseZ,
                                  int width, int depth, int height,
                                  Palette palette, boolean dome,
                                  ComponentFootprintMask footprint, int overhang) {
        if (width < 2 || depth < 2 || height < 2) {
            generateFlatRoof(out, semantic, baseX, baseY, baseZ, width, depth, height, palette, footprint, overhang);
            return;
        }
        double cx = (width - 1) / 2.0;
        double cz = (depth - 1) / 2.0;
        double radius = Math.max(1.0, Math.min(width, depth) / 2.0);
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < depth; z++) {
                if (!allowsRoofCell(footprint, overhang, x - overhang, z - overhang)) {
                    continue;
                }
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
                                  int width, int depth, int height, Palette palette,
                                  ComponentFootprintMask footprint, int overhang) {
        int topY = height - 1;
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < depth; z++) {
                if (!allowsRoofCell(footprint, overhang, x - overhang, z - overhang)) {
                    continue;
                }
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

    private void addEaveLayer(List<BlockPatch> out, SemanticComponent semantic, int baseX, int baseY, int baseZ,
                              int width, int depth, Palette palette, int outset,
                              ComponentFootprintMask footprint, int overhang) {
        if (width < 1 || depth < 1 || outset <= 0) {
            return;
        }
        int startX = baseX - outset;
        int startZ = baseZ - outset;
        int endX = baseX + width - 1 + outset;
        int endZ = baseZ + depth - 1 + outset;
        int eaveReach = overhang + outset;
        String block = getBlockForPart(semantic, palette, SemanticPart.ROOF);
        for (int x = startX; x <= endX; x++) {
            int localX = x - baseX - overhang;
            int localZNorth = startZ - baseZ - overhang;
            int localZSouth = endZ - baseZ - overhang;
            if (allowsRoofCell(footprint, eaveReach, localX, localZNorth)) {
                out.add(new BlockPatch(BlockPatch.PLACE, x, baseY, startZ, block));
            }
            if (allowsRoofCell(footprint, eaveReach, localX, localZSouth)) {
                out.add(new BlockPatch(BlockPatch.PLACE, x, baseY, endZ, block));
            }
        }
        for (int z = startZ; z <= endZ; z++) {
            int localZ = z - baseZ - overhang;
            int localXWest = startX - baseX - overhang;
            int localXEast = endX - baseX - overhang;
            if (allowsRoofCell(footprint, eaveReach, localXWest, localZ)) {
                out.add(new BlockPatch(BlockPatch.PLACE, startX, baseY, z, block));
            }
            if (allowsRoofCell(footprint, eaveReach, localXEast, localZ)) {
                out.add(new BlockPatch(BlockPatch.PLACE, endX, baseY, z, block));
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
            if (upper.contains("CHINESE_ROYAL") || upper.contains("CHINESE_IMPERIAL") || upper.contains("CHINESE_PALACE")
                    || (upper.contains("CHINESE") && (upper.contains("ROYAL") || upper.contains("IMPERIAL") || upper.contains("PALACE")))) {
                return "CHINESE_ROYAL";
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
                if ((lower.contains("chinese") || lower.contains("imperial") || lower.contains("royal") || lower.contains("palace"))
                        && (lower.contains("imperial") || lower.contains("royal") || lower.contains("palace"))) {
                    return "CHINESE_ROYAL";
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
                if (lower.contains("yingshan") || lower.contains("ying_shan") || lower.contains("ying-shan")
                        || lower.contains("hard_gable") || lower.contains("hard gable") || lower.contains("硬山")) {
                    type = "yingshan";
                } else if (lower.contains("xuanshan") || lower.contains("xuan_shan") || lower.contains("悬山")) {
                    type = "xuanshan";
                }
                else if (lower.contains("xieshan") || lower.contains("xie_shan") || lower.contains("hip_and_gable") || lower.contains("歇山")) type = "xieshan";
                else if (lower.contains("double_gable") || lower.contains("double gable") || lower.contains("shuangpo") || lower.contains("双坡")) type = "double_gable";
                else if (lower.contains("gable")) type = "gable";
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
            String upper = profile.toUpperCase();
            if (upper.contains("CHINESE_ROYAL") || upper.contains("CHINESE_IMPERIAL") || upper.contains("CHINESE_PALACE")
                    || (upper.contains("CHINESE") && (upper.contains("ROYAL") || upper.contains("IMPERIAL") || upper.contains("PALACE")))) {
                return RoofType.XIESHAN;
            }
            if (upper.contains("CHINESE") || upper.contains("HUI")) {
                return RoofType.XUANSHAN;
            }
            if (upper.contains("GOTHIC") || upper.contains("MEDIEVAL")) {
                return RoofType.GABLE;
            }
            if (upper.contains("MODERN")) {
                return RoofType.FLAT;
            }
            return RoofType.GABLE;
        }
        return switch (type.trim().toLowerCase()) {
            case "gable", "gabled" -> RoofType.GABLE;
            case "yingshan", "ying_shan", "ying-shan", "hard_gable", "hard gable", "硬山" -> RoofType.GABLE;
            case "double_gable", "doublegable", "shuangpo", "双坡" -> RoofType.DOUBLE_GABLE;
            case "hip", "hipped" -> RoofType.HIP;
            case "xieshan", "xie_shan", "xie-shan", "hip_and_gable", "hip-gable", "歇山" -> RoofType.XIESHAN;
            case "xuanshan", "xuan_shan", "xuan-shan", "overhang_gable", "悬山" -> RoofType.XUANSHAN;
            case "pyramid" -> RoofType.PYRAMID;
            case "cone", "conical" -> RoofType.CONE;
            case "dome", "domed", "curved" -> RoofType.DOME;
            default -> RoofType.FLAT;
        };
    }

    private static boolean isDoubleEave(Map<String, Object> params, List<String> features) {
        if (getParamBoolean(params, "double_eave", "doubleEave", "double_eaved", "double_eave_roof")) {
            return true;
        }
        if (features == null) {
            return false;
        }
        for (String feature : features) {
            if (feature == null) {
                continue;
            }
            String lower = feature.toLowerCase().trim();
            if (lower.contains("double_eave") || lower.contains("double-eave")
                    || (lower.contains("double") && lower.contains("eave"))) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasRoofFeature(Component component, SemanticComponent semantic, String... tokens) {
        if (component != null && component.features() != null) {
            for (String feature : component.features()) {
                if (feature == null) continue;
                String lower = feature.toLowerCase();
                for (String token : tokens) {
                    if (token != null && lower.contains(token.toLowerCase())) {
                        return true;
                    }
                }
            }
        }
        if (semantic != null && semantic.styleAttributes() != null
                && semantic.styleAttributes().decorativeElements() != null) {
            for (String element : semantic.styleAttributes().decorativeElements()) {
                if (element == null) continue;
                String lower = element.toLowerCase();
                for (String token : tokens) {
                    if (token != null && lower.contains(token.toLowerCase())) {
                        return true;
                    }
                }
            }
        }
        return false;
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

    private static boolean getParamBoolean(Map<String, Object> params, String... keys) {
        if (params == null || keys == null) return false;
        for (String key : keys) {
            if (key == null) continue;
            Object v = params.get(key);
            if (v instanceof Boolean b) {
                return b;
            }
            if (v != null) {
                String s = String.valueOf(v).trim();
                if ("true".equalsIgnoreCase(s)) {
                    return true;
                }
            }
        }
        return false;
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
        DOUBLE_GABLE,
        HIP,
        XIESHAN,
        XUANSHAN,
        PYRAMID,
        CONE,
        DOME
    }
}
