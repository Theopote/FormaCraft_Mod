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
 * FacadeWindowsGenerator（立面窗户生成器）
 * 
 * 生成建筑立面的窗户/橱窗/窗带
 * 支持不同风格的窗户（格子窗、大窗、落地窗等）
 */
public class FacadeWindowsGenerator implements ComponentGenerator {

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

        // 检查 features 以确定窗户类型
        boolean isLattice = hasFeature(c, "lattice", "lattice_pattern");
        boolean isLarge = hasFeature(c, "large", "big", "wide");
        boolean wrapFacade = hasFeature(c, "wrap", "all_sides", "around", "perimeter");
        Double windowRatio = getParamDouble(params, "window_ratio", "windowRatio");
        String rhythm = getParamString(params, "rhythm");
        if (rhythm == null && semantic != null && semantic.genome() != null && semantic.genome().form != null) {
            rhythm = semantic.genome().form.rhythm;
        }
        String windowStyle = getParamString(params, "window_style", "windowStyle");
        int floorHeight = getParamInt(params, 0, "floor_height", "floorHeight");
        int floorCount = getParamInt(params, 0, "floor_count", "floorCount");
        if (floorHeight <= 0 && floorCount > 0) {
            floorHeight = Math.max(3, height / Math.max(1, floorCount));
        }
        if (floorHeight <= 0) {
            floorHeight = 3;
        }
        int windowSpacing = resolveWindowSpacing(windowRatio);
        com.formacraft.common.llm.dto.GlobalConstraints.Facing facing =
                (semantic.slot() != null && semantic.slot().facing() != null)
                        ? semantic.slot().facing()
                        : com.formacraft.common.llm.dto.GlobalConstraints.Facing.SOUTH;
        // boolean isFloorToCeiling = hasFeature(c, "floor_to_ceiling", "full_height"); // 保留用于未来扩展

        // 生成窗户（通常只在立面，depth 通常为 1）
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                for (int z = 0; z < depth; z++) {
                    boolean isFacade = isFacadePosition(x, z, width, depth, facing, wrapFacade);
                    if (!isFacade) {
                        continue;
                    }
                    if (!isWindowBand(y, height, floorHeight)) {
                        continue;
                    }
                    int axis = (facing == com.formacraft.common.llm.dto.GlobalConstraints.Facing.EAST
                            || facing == com.formacraft.common.llm.dto.GlobalConstraints.Facing.WEST)
                            ? z : x;
                    if (!shouldPlaceWindow(axis, y, windowSpacing, windowRatio, rhythm)) {
                        continue;
                    }

                    SemanticPart part = determineWindowPart(y, height, isLattice, isLarge);
                    String block = getBlockForWindow(semantic, palette, part, isLattice, windowStyle);

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

    /**
     * 确定窗户的语义部位
     */
    private SemanticPart determineWindowPart(int y, int height, boolean isLattice, boolean isLarge) {
        // 顶部和底部使用窗框
        if (y == 0 || y >= height - 1) {
            return SemanticPart.WINDOW; // 窗框
        }
        
        // 中间部分使用窗户
        if (isLattice) {
            return SemanticPart.WINDOW; // 格子窗
        } else if (isLarge) {
            return SemanticPart.WINDOW; // 大窗
        } else {
            return SemanticPart.WINDOW; // 普通窗
        }
    }

    /**
     * 检查是否有特定特征
     */
    private boolean hasFeature(Component c, String... keywords) {
        if (c.features() == null) return false;
        for (String feature : c.features()) {
            if (feature == null) continue;
            String lower = feature.toLowerCase();
            for (String keyword : keywords) {
                if (lower.contains(keyword.toLowerCase())) {
                    return true;
                }
            }
        }
        return false;
    }

    private String getStyleProfile(SemanticComponent semantic) {
        // 1. 优先使用 SemanticComponent 中的 styleProfile
        if (semantic != null && semantic.styleProfile() != null && !semantic.styleProfile().isBlank()) {
            String profile = semantic.styleProfile().trim();
            // 映射 LLM 返回的风格名称到系统支持的风格
            String upper = profile.toUpperCase();
            if (upper.contains("GOTHIC")) {
                return "MEDIEVAL_CLASSIC"; // 哥特式使用中世纪风格
            }
            if (upper.contains("CHINESE") || upper.contains("HUI")) {
                return "HUI_STYLE_VILLA";
            }
            return profile;
        }

        // 2. 尝试从 Component 的 features 推断风格
        Component c = semantic != null ? semantic.source() : null;
        if (c != null && c.features() != null) {
            for (String feature : c.features()) {
                if (feature == null) continue;
                String lower = feature.toLowerCase();
                if (lower.contains("gothic") || lower.contains("stained_glass") || lower.contains("pointed")) {
                    return "MEDIEVAL_CLASSIC";
                }
                if (lower.contains("chinese") || lower.contains("中式") || lower.contains("lattice")) {
                    return "HUI_STYLE_VILLA";
                }
            }
        }

        // 3. 默认
        return "MEDIEVAL_CLASSIC";
    }
    
    /**
     * 获取窗户方块（优先使用动态解析和 features，回退到传统 Palette）
     */
    private String getBlockForWindow(SemanticComponent semantic, Palette palette, SemanticPart part, boolean isLattice,
                                     String windowStyle) {
        // 1. 优先使用动态解析（如果 LlmPlan 有 style_attributes）
        if (semantic.styleAttributes() != null) {
            String block = DynamicPaletteResolver.resolve(part, semantic.styleAttributes());
            if (block != null && !block.isEmpty()) {
                return block;
            }
        }
        
        // 2. 尝试从 features 中提取材质信息
        Component c = semantic.source();
        if (c != null && c.features() != null) {
            String block = extractBlockFromFeatures(c.features());
            if (block != null && !block.isEmpty()) {
                return block;
            }
        }
        
        // 3. 回退到传统 Palette
        if (palette != null) {
            String block = palette.pick(part);
            if (block != null && !block.isEmpty()) {
                return block;
            }
        }
        
        // 4. 默认方块
        if (windowStyle != null && !windowStyle.isBlank()) {
            String v = windowStyle.trim().toLowerCase();
            if (v.contains("lattice") || v.contains("fence") || v.contains("bars")) {
                return "minecraft:iron_bars";
            }
            if (v.contains("stained")) {
                return "minecraft:blue_stained_glass";
            }
            if (v.contains("pane")) {
                return "minecraft:glass_pane";
            }
        }
        return isLattice ? "minecraft:iron_bars" : "minecraft:glass";
    }
    
    /**
     * 从 features 中提取窗户材质
     */
    private String extractBlockFromFeatures(List<String> features) {
        if (features == null || features.isEmpty()) {
            return null;
        }
        
        for (String feature : features) {
            if (feature == null || feature.isBlank()) {
                continue;
            }
            
            String lower = feature.toLowerCase().trim();
            
            if (lower.contains("glass_curtain") || lower.contains("curtain_wall") || 
                lower.contains("glass_pane") || (lower.contains("glass") && lower.contains("curtain"))) {
                return "minecraft:glass_pane";
            }
            if (lower.contains("glass") && !lower.contains("curtain")) {
                return "minecraft:glass";
            }
            if (lower.contains("lattice") || lower.contains("iron_bars")) {
                return "minecraft:iron_bars";
            }
        }
        
        return null;
    }

    private boolean isFacadePosition(int x, int z, int width, int depth,
                                     com.formacraft.common.llm.dto.GlobalConstraints.Facing facing,
                                     boolean wrapFacade) {
        if (wrapFacade) {
            return x == 0 || z == 0 || x == width - 1 || z == depth - 1;
        }
        return switch (facing) {
            case NORTH -> z == depth - 1;
            case EAST -> x == 0;
            case WEST -> x == width - 1;
            case SOUTH -> z == 0;
        };
    }

    private boolean isWindowBand(int y, int height, int floorHeight) {
        if (y <= 0 || y >= height - 1) {
            return false;
        }
        int band = y % Math.max(1, floorHeight);
        return band >= 1 && band <= Math.max(1, floorHeight - 2);
    }

    private boolean shouldPlaceWindow(int axis, int y, int spacing, Double ratio, String rhythm) {
        int density = ratio != null ? (int) Math.round(Math.max(0.0, Math.min(1.0, ratio)) * 100.0) : 60;
        int hash = stableHash(axis, y);
        if ((hash % 100) >= density) {
            return false;
        }
        int step = Math.max(2, spacing);
        if (rhythm == null || rhythm.isBlank() || "regular".equalsIgnoreCase(rhythm)) {
            return axis % step != 0;
        }
        if ("segmented".equalsIgnoreCase(rhythm)) {
            int block = axis % (step + 2);
            return block == 1 || block == 2;
        }
        if ("irregular".equalsIgnoreCase(rhythm)) {
            return (hash % 5) != 0;
        }
        return axis % step != 0;
    }

    private int resolveWindowSpacing(Double windowRatio) {
        if (windowRatio == null) {
            return 3;
        }
        if (windowRatio >= 0.6) return 2;
        if (windowRatio >= 0.35) return 3;
        if (windowRatio >= 0.2) return 4;
        return 5;
    }

    private static int stableHash(int a, int b) {
        int h = a * 734287 + b * 912271;
        h ^= (h >>> 11);
        h *= 1103515245;
        return h;
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

    private static Double getParamDouble(Map<String, Object> params, String... keys) {
        if (params == null || keys == null) return null;
        for (String key : keys) {
            if (key == null) continue;
            Object v = params.get(key);
            if (v == null) continue;
            if (v instanceof Number n) {
                return n.doubleValue();
            }
            if (v instanceof String s) {
                try {
                    return Double.parseDouble(s.trim());
                } catch (NumberFormatException ignored) {}
            }
        }
        return null;
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
}

