package com.formacraft.common.palette.dynamic;

import com.formacraft.common.llm.dto.StyleAttributes;
import com.formacraft.common.semantic.SemanticPart;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

/**
 * DynamicPaletteResolver（动态调色板解析器）
 * 
 * 根据 AI 分析的 style_attributes 动态选择方块
 * 不再依赖硬编码的预设，可以处理任何用户描述的风格
 * 
 * 核心思想：
 * - AI 分析用户描述 → 提取风格特征（颜色、材质、装饰）
 * - 根据特征动态选择方块
 * - 支持任意风格组合，不限于预设
 */
public final class DynamicPaletteResolver {
    
    private DynamicPaletteResolver() {}

    private static final Map<String, Boolean> VALID_BLOCK_CACHE = new ConcurrentHashMap<>();
    
    /**
     * 根据 style_attributes 解析方块 ID
     * 
     * @param part 语义部位（WALL, ROOF, FLOOR, etc.）
     * @param styleAttributes 风格属性（从 LlmPlan 获取）
     * @return 方块 ID（例如 "minecraft:white_terracotta"）
     */
    public static String resolve(SemanticPart part, StyleAttributes styleAttributes) {
        if (styleAttributes == null) {
            return getDefaultBlock(part);
        }
        
        return switch (part) {
            case WALL, WALL_BASE, WALL_ACCENT -> resolveWall(styleAttributes, part);
            case ROOF, ROOF_SURFACE -> resolveRoof(styleAttributes);
            case FLOOR, COURTYARD_FLOOR -> resolveFloor(styleAttributes);
            case DECOR -> resolveAccent(styleAttributes);
            case WINDOW -> resolveWindow(styleAttributes);
            case DOORWAY -> "minecraft:air"; // 门洞保持空气
            case PILLAR -> resolvePillar(styleAttributes);
            default -> getDefaultBlock(part);
        };
    }
    
    /**
     * 解析墙体方块
     */
    private static String resolveWall(StyleAttributes attrs, SemanticPart part) {
        String color = attrs.wallColor();
        String material = attrs.wallMaterial();
        
        // 如果有明确的颜色和材质，组合使用
        if (color != null && !color.isBlank() && material != null && !material.isBlank()) {
            String blockId = resolveBlock(color, material);
            if (blockId != null) {
                return blockId;
            }
        }
        
        // 只有材质
        if (material != null && !material.isBlank()) {
            String blockId = mapMaterialToBlock(material);
            if (blockId != null) {
                return blockId;
            }
        }
        
        // 只有颜色（使用默认材质）
        if (color != null && !color.isBlank()) {
            String blockId = resolveBlock(color, "default");
            if (blockId != null) {
                return blockId;
            }
        }
        
        // 根据部位返回默认
        return "minecraft:stone_bricks";
    }
    
    /**
     * 解析屋顶方块
     */
    private static String resolveRoof(StyleAttributes attrs) {
        String color = attrs.roofColor();
        String material = attrs.roofMaterial();
        
        if (color != null && !color.isBlank() && material != null && !material.isBlank()) {
            String blockId = resolveBlock(color, material);
            if (blockId != null) {
                return blockId;
            }
        }
        
        if (material != null && !material.isBlank()) {
            String blockId = mapMaterialToBlock(material);
            if (blockId != null) {
                return blockId;
            }
        }
        
        if (color != null && !color.isBlank()) {
            String blockId = resolveBlock(color, "default");
            if (blockId != null) {
                return blockId;
            }
        }
        
        return "minecraft:spruce_planks";
    }
    
    /**
     * 解析地面方块
     */
    private static String resolveFloor(StyleAttributes attrs) {
        String material = attrs.floorMaterial();
        
        if (material != null && !material.isBlank()) {
            String blockId = mapMaterialToBlock(material);
            if (blockId != null) {
                return blockId;
            }
        }
        
        return "minecraft:stone_bricks";
    }
    
    /**
     * 解析装饰/强调方块
     */
    private static String resolveAccent(StyleAttributes attrs) {
        String accent = attrs.accentMaterial();
        
        if (accent != null && !accent.isBlank()) {
            String blockId = mapMaterialToBlock(accent);
            if (blockId != null) {
                return blockId;
            }
        }
        
        // 检查装饰元素
        List<String> decorative = attrs.decorativeElements();
        if (decorative != null && decorative.contains("wood_carvings")) {
            return "minecraft:dark_oak_fence";
        }
        
        return "minecraft:dark_oak_planks";
    }
    
    /**
     * 解析窗户方块
     */
    private static String resolveWindow(StyleAttributes attrs) {
        List<String> decorative = attrs.decorativeElements();
        
        if (decorative != null && decorative.contains("lattice_windows")) {
            return "minecraft:iron_bars"; // 格子窗
        }

        if (decorative != null) {
            for (String element : decorative) {
                if (element == null) {
                    continue;
                }
                String lower = element.toLowerCase().trim();
                if (lower.contains("stained_glass") || lower.contains("rose_window") || lower.contains("rose_windows")) {
                    // 使用墙体的强调颜色，如果没有则使用墙体主色
                    String accent = attrs.wallColor();
                    String normalized = normalizeColor(accent);
                    if (normalized != null) {
                        String id = "minecraft:" + normalized + "_stained_glass";
                        if (isValidBlockId(id)) {
                            return id;
                        }
                    }
                    return "minecraft:red_stained_glass";
                }
            }
        }
        
        return "minecraft:glass"; // 默认玻璃
    }
    
    /**
     * 解析柱子方块
     */
    private static String resolvePillar(StyleAttributes attrs) {
        String accent = attrs.accentMaterial();
        
        if (accent != null && !accent.isBlank()) {
            // 柱子通常是原木，不是木板
            String lower = accent.toLowerCase();
            if (lower.contains("dark_oak")) {
                return "minecraft:dark_oak_log";
            } else if (lower.contains("spruce")) {
                return "minecraft:spruce_log";
            } else if (lower.contains("oak")) {
                return "minecraft:oak_log";
            }
        }
        
        return "minecraft:spruce_log";
    }
    
    /**
     * 根据颜色和材质组合解析方块
     */
    private static String resolveBlock(String color, String material) {
        if (color == null || color.isBlank()) {
            return null;
        }
        if (material == null || material.isBlank() || material.equals("default")) {
            // 只有颜色，使用默认材质
            return resolveColorOnly(color);
        }
        
        String normalizedColor = normalizeColor(color);
        String normalizedMaterial = normalizeMaterial(material);
        
        if (normalizedColor == null || normalizedMaterial == null) {
            return null;
        }
        
        // 组合方块 ID
        // 例如：white + terracotta → white_terracotta
        // 例如：black + concrete → black_concrete
        String blockId = normalizedColor + "_" + normalizedMaterial;
        String fullId = "minecraft:" + blockId;
        return isValidBlockId(fullId) ? fullId : null;
    }
    
    /**
     * 只有颜色时的解析
     */
    private static String resolveColorOnly(String color) {
        String normalized = normalizeColor(color);
        if (normalized == null) return null;
        
        // 默认使用 concrete（混凝土有完整的颜色系列）
        String fullId = "minecraft:" + normalized + "_concrete";
        return isValidBlockId(fullId) ? fullId : null;
    }
    
    /**
     * 标准化颜色名称
     */
    private static String normalizeColor(String color) {
        if (color == null || color.isBlank()) {
            return null;
        }
        
        String lower = color.toLowerCase().trim();
        return switch (lower) {
            case "white" -> "white";
            case "black" -> "black";
            case "gray", "grey", "dark_gray", "dark_grey" -> "gray";
            case "light_gray", "light_grey" -> "light_gray";
            case "red" -> "red";
            case "brown" -> "brown";
            case "yellow", "gold", "golden", "golden_yellow", "golden-yellow" -> "yellow";
            case "blue" -> "blue";
            case "green" -> "green";
            case "orange" -> "orange";
            case "purple" -> "purple";
            case "pink" -> "pink";
            case "cyan" -> "cyan";
            case "lime" -> "lime";
            case "magenta" -> "magenta";
            default -> null;
        };
    }
    
    /**
     * 标准化材质名称
     */
    private static String normalizeMaterial(String material) {
        if (material == null || material.isBlank()) {
            return null;
        }

        String lower = material.toLowerCase().trim();
        return switch (lower) {
            case "stone", "stone_brick", "stone_bricks", "stonebrick", "stonebricks" -> "stone_bricks";
            case "polished_andesite" -> "polished_andesite";
            case "polished_blackstone", "blackstone" -> "polished_blackstone";
            case "deepslate", "deepslate_tiles", "deepslate_tile" -> "deepslate_tiles";
            case "deepslate_bricks", "deepslate_brick" -> "deepslate_bricks";
            case "brick", "bricks" -> "bricks";
            case "wood", "planks", "oak" -> "oak_planks";
            case "dark_oak", "dark_oak_wood" -> "dark_oak_planks";
            case "spruce", "spruce_wood" -> "spruce_planks";
            case "birch", "birch_wood" -> "birch_planks";
            case "concrete" -> "concrete";
            case "terracotta", "tile", "tiles" -> "terracotta";
            case "glazed_tile", "glazed_tiles", "glazed_terracotta", "glazed" -> "glazed_terracotta";
            case "cobblestone" -> "cobblestone";
            case "shingle" -> "oak_planks"; // 瓦片用木板模拟
            case "black_tile", "black_tiles", "black_tile_roof", "dark_tile", "dark_tiles" -> "deepslate_tiles";
            case "slate" -> "deepslate_tiles"; // 石板更接近深板岩瓦
            case "metal" -> "iron_block";
            case "dark_prismarine", "prismarine", "prismarine_bricks" -> "dark_prismarine";
            default -> null;
        };
    }
    
    /**
     * 材质到方块的映射（不涉及颜色）
     */
    private static String mapMaterialToBlock(String material) {
        if (material == null || material.isBlank()) {
            return null;
        }

        String direct = resolveDirectBlockId(material);
        if (direct != null) {
            return direct;
        }

        String normalized = normalizeMaterial(material);
        if (normalized != null) {
            String fullId = "minecraft:" + normalized;
            return isValidBlockId(fullId) ? fullId : null;
        }
        
        return null;
    }

    private static String resolveDirectBlockId(String material) {
        String trimmed = material.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        String candidate = trimmed.contains(":") ? trimmed : "minecraft:" + trimmed;
        return isValidBlockId(candidate) ? candidate : null;
    }

    private static boolean isValidBlockId(String blockId) {
        if (blockId == null || blockId.isBlank()) {
            return false;
        }
        return VALID_BLOCK_CACHE.computeIfAbsent(blockId, DynamicPaletteResolver::lookupBlockId);
    }

    private static boolean lookupBlockId(String blockId) {
        String s = blockId.trim();
        if (!s.contains(":")) {
            s = "minecraft:" + s;
        }
        Identifier id = Identifier.tryParse(s);
        return id != null && Registries.BLOCK.containsId(id);
    }
    
    /**
     * 获取默认方块
     */
    private static String getDefaultBlock(SemanticPart part) {
        return switch (part) {
            case WALL, WALL_BASE, FLOOR, COURTYARD_FLOOR -> "minecraft:stone_bricks";
            case ROOF, ROOF_SURFACE -> "minecraft:spruce_planks";
            case DECOR -> "minecraft:stone_brick_slab";
            case WINDOW -> "minecraft:glass";
            case PILLAR -> "minecraft:spruce_log";
            default -> "minecraft:stone";
        };
    }
}

