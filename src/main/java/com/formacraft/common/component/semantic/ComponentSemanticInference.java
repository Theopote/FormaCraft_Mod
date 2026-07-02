package com.formacraft.common.component.semantic;

import com.formacraft.common.component.ComponentCategory;
import com.formacraft.common.component.ComponentDefinition;
import com.formacraft.common.component.archetype.GeometryArchetype;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 从构件几何与标签推断文化风格等语义字段。
 */
public final class ComponentSemanticInference {
    private ComponentSemanticInference() {}

    public static void ensureSemanticFields(ComponentDefinition def) {
        if (def == null) {
            return;
        }
        if (isBlank(def.culturalStyle)) {
            def.culturalStyle = inferCulturalStyle(def);
        }
        if (isBlank(def.archetypeRef) && !isBlank(def.id)) {
            def.archetypeRef = def.id;
        }
        if (isBlank(def.geometryArchetype)) {
            def.geometryArchetype = inferGeometryArchetype(def);
        }
    }

    public static String inferCulturalStyle(ComponentDefinition def) {
        if (def == null) {
            return null;
        }

        if (def.tags != null) {
            for (String tag : def.tags) {
                String style = fromTag(tag);
                if (style != null) {
                    return style;
                }
            }
        }

        if (def.blocks == null || def.blocks.isEmpty()) {
            return null;
        }

        Map<String, Integer> materials = new HashMap<>();
        for (ComponentDefinition.BlockEntry block : def.blocks) {
            if (block == null || block.block == null) {
                continue;
            }
            String material = extractMaterial(block.block);
            materials.merge(material, 1, Integer::sum);
        }

        int total = def.blocks.size();
        if (materials.getOrDefault("concrete", 0) > total * 0.3
                || materials.getOrDefault("glass", 0) > total * 0.2) {
            return "MODERN";
        }
        if (materials.getOrDefault("stone", 0) > total * 0.4) {
            return "MEDIEVAL";
        }
        if (materials.getOrDefault("spruce", 0) > total * 0.3 && materials.getOrDefault("red", 0) > 0) {
            return "CHINESE";
        }

        return null;
    }

    public static String inferGeometryArchetype(ComponentDefinition def) {
        if (def == null) {
            return null;
        }

        if (def.tags != null) {
            for (String tag : def.tags) {
                String geometry = geometryFromTag(tag);
                if (geometry != null) {
                    return geometry;
                }
            }
        }

        ComponentCategory category = def.category != null ? def.category : ComponentCategory.GENERIC;
        return switch (category) {
            case DOOR, WINDOW -> GeometryArchetype.FRAME.name();
            case COLUMN -> GeometryArchetype.COLUMN.name();
            case BRACKET, ORNAMENT, ROOF_DETAIL, ARCH -> GeometryArchetype.ORNAMENT.name();
            case STAIRS -> GeometryArchetype.LINEAR.name();
            default -> GeometryArchetype.VOLUME.name();
        };
    }

    private static String geometryFromTag(String tag) {
        if (tag == null) {
            return null;
        }
        String lower = tag.toLowerCase(Locale.ROOT);
        if (lower.contains("arch") || lower.contains("拱")) {
            return GeometryArchetype.ARCH.name();
        }
        if (lower.contains("column") || lower.contains("柱")) {
            return GeometryArchetype.COLUMN.name();
        }
        if (lower.contains("railing") || lower.contains("栏杆") || lower.contains("栏杆")) {
            return GeometryArchetype.LINEAR.name();
        }
        if (lower.contains("dougong") || lower.contains("斗拱") || lower.contains("ornament") || lower.contains("装饰")) {
            return GeometryArchetype.ORNAMENT.name();
        }
        if (lower.contains("frame") || lower.contains("窗套") || lower.contains("门框")) {
            return GeometryArchetype.FRAME.name();
        }
        if (lower.contains("panel") || lower.contains("板")) {
            return GeometryArchetype.FLAT_PANEL.name();
        }
        return null;
    }

    public static void applyCulturalStyleAffinity(Map<String, Double> affinity, String culturalStyle) {
        if (affinity == null || isBlank(culturalStyle)) {
            return;
        }
        switch (culturalStyle.toUpperCase(Locale.ROOT)) {
            case "CHINESE" -> {
                affinity.put("Chinese_Traditional", 1.0);
                affinity.put("Medieval_Castle", 0.3);
            }
            case "JAPANESE" -> {
                affinity.put("Japanese_Traditional", 1.0);
                affinity.put("Chinese_Traditional", 0.4);
            }
            case "GOTHIC" -> {
                affinity.put("Gothic", 1.0);
                affinity.put("Medieval_Castle", 0.9);
            }
            case "MEDIEVAL" -> {
                affinity.put("Medieval_Castle", 0.9);
                affinity.put("Gothic", 0.7);
            }
            case "MODERN" -> {
                affinity.put("Modern", 1.0);
                affinity.put("Industrial", 0.6);
            }
            case "EUROPEAN" -> affinity.put("European_Classical", 1.0);
            case "ISLAMIC" -> affinity.put("Islamic", 1.0);
            case "INDUSTRIAL" -> affinity.put("Industrial", 1.0);
            default -> affinity.put(culturalStyle, 0.8);
        }
    }

    private static String fromTag(String tag) {
        if (tag == null) {
            return null;
        }
        String lower = tag.toLowerCase(Locale.ROOT);
        if (lower.contains("chinese") || lower.contains("中式") || lower.contains("dougong") || lower.contains("斗拱")) {
            return "CHINESE";
        }
        if (lower.contains("japanese") || lower.contains("日式")) {
            return "JAPANESE";
        }
        if (lower.contains("gothic") || lower.contains("哥特")) {
            return "GOTHIC";
        }
        if (lower.contains("medieval") || lower.contains("中世纪")) {
            return "MEDIEVAL";
        }
        if (lower.contains("modern") || lower.contains("现代")) {
            return "MODERN";
        }
        if (lower.contains("european") || lower.contains("欧式")) {
            return "EUROPEAN";
        }
        if (lower.contains("islamic")) {
            return "ISLAMIC";
        }
        if (lower.contains("industrial")) {
            return "INDUSTRIAL";
        }
        return null;
    }

    private static String extractMaterial(String blockState) {
        String lower = blockState.toLowerCase(Locale.ROOT);
        if (lower.contains("spruce")) return "spruce";
        if (lower.contains("concrete")) return "concrete";
        if (lower.contains("glass")) return "glass";
        if (lower.contains("stone")) return "stone";
        if (lower.contains("red")) return "red";
        return "other";
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
