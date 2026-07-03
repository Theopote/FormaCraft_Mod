package com.formacraft.server.generation.structure;

import com.formacraft.common.logging.FcaLog;
import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.common.model.build.BuildingStyle;
import com.formacraft.common.style.StyleGenome;
import com.formacraft.common.style.profile.StyleProfile;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;

/**
 * 房屋材质和样式解析器
 *
 * 负责解析和提供建筑材质，包括：
 * - 默认材质（基于 BuildingStyle）
 * - 材质 ID 解析和回退
 * - 窗户样式解析
 * - 材质工具方法
 *
 * 从 HouseGenerator 中拆分出来，以提高代码可维护性。
 */
public class HouseMaterialResolver {

    private static final FcaLog LOG = FcaLog.of("HouseMaterialResolver");

    private HouseMaterialResolver() {} // Utility class

    /**
     * 材质集合记录
     */
    public record MaterialSet(
            BlockState wall,
            BlockState floor,
            BlockState window,
            BlockState roof,
            BlockState trim,
            BlockState foundation,
            BlockState pillar,
            BlockState roofStairs,
            BlockState roofSlab,
            BlockState door,
            BlockState windowBlock  // 经过样式解析后的窗户材质
    ) {}

    /**
     * 解析所有材质
     *
     * @param world 世界对象
     * @param spec 建筑规格
     * @param style 建筑风格
     * @param genome 风格基因
     * @param profile 风格配置文件
     * @return 材质集合
     */
    public static MaterialSet resolveMaterials(ServerWorld world, BuildingSpec spec,
                                               BuildingStyle style, StyleGenome genome, StyleProfile profile) {
        // 获取材质 ID（优先级：spec.materials > profile.palette > genome.palette）
        String wallId = spec.getMaterials() != null ? spec.getMaterials().getWall() : null;
        String floorId = spec.getMaterials() != null ? spec.getMaterials().getFloor() : null;
        String windowId = spec.getMaterials() != null ? spec.getMaterials().getWindow() : null;
        String roofId = spec.getMaterials() != null ? spec.getMaterials().getRoof() : null;

        String pWall = (profile != null && profile.palette() != null) ? profile.palette().wall : null;
        String pFloor = (profile != null && profile.palette() != null) ? profile.palette().floor : null;
        String pWindow = (profile != null && profile.palette() != null) ? profile.palette().window : null;
        String pRoof = (profile != null && profile.palette() != null) ? profile.palette().roof : null;

        // 解析基础材质
        BlockState wall = getStateOrDefault(world, wallId,
                getStateOrDefault(world,
                        genome != null && genome.palette != null ? genome.palette.wall : pWall,
                        defaultWall(style)));
        BlockState floor = getStateOrDefault(world, floorId,
                getStateOrDefault(world,
                        genome != null && genome.palette != null ? genome.palette.floor : pFloor,
                        defaultFloor(style)));
        BlockState window = getStateOrDefault(world, windowId,
                getStateOrDefault(world,
                        genome != null && genome.palette != null ? genome.palette.window : pWindow,
                        defaultWindow(style)));
        BlockState roof = getStateOrDefault(world, roofId,
                getStateOrDefault(world,
                        genome != null && genome.palette != null ? genome.palette.roof : pRoof,
                        defaultRoof(style)));

        // 装饰/细节材质
        BlockState trim = getStateOrDefault(world,
                genome != null && genome.palette != null ? genome.palette.trim : (profile != null && profile.palette() != null ? profile.palette().trim : null),
                defaultTrim(style, wall));
        BlockState foundation = getStateOrDefault(world,
                genome != null && genome.palette != null ? genome.palette.foundation : (profile != null && profile.palette() != null ? profile.palette().foundation : null),
                defaultFoundation(style, wall));
        BlockState pillar = getStateOrDefault(world,
                genome != null && genome.palette != null ? genome.palette.pillar : (profile != null && profile.palette() != null ? profile.palette().pillar : null),
                defaultPillar(style));
        BlockState roofStairs = defaultRoofStairs(style, roof);
        BlockState roofSlab = defaultRoofSlab(style, roof);
        BlockState door = defaultDoor(style);

        // 根据样式解析窗户材质
        BlockState windowBlock = resolveWindowByStyleOption(world, style, spec, genome, profile, window, pillar, trim);

        return new MaterialSet(wall, floor, window, roof, trim, foundation, pillar, roofStairs, roofSlab, door, windowBlock);
    }

    // ========== 默认材质方法 ==========

    public static BlockState defaultWall(BuildingStyle style) {
        if (style == null) style = BuildingStyle.DEFAULT;
        return switch (style) {
            case MODERN -> Blocks.WHITE_CONCRETE.getDefaultState();
            case ASIAN ->
                // 明清官式默认红墙
                    Blocks.RED_TERRACOTTA.getDefaultState();
            case FUTURISTIC -> Blocks.QUARTZ_BLOCK.getDefaultState();
            case RUSTIC -> Blocks.SPRUCE_PLANKS.getDefaultState();
            case MEDIEVAL -> Blocks.STONE_BRICKS.getDefaultState();
            default -> Blocks.OAK_PLANKS.getDefaultState();
        };
    }

    public static BlockState defaultFloor(BuildingStyle style) {
        if (style == null) style = BuildingStyle.DEFAULT;
        return switch (style) {
            case MODERN, FUTURISTIC -> Blocks.SMOOTH_QUARTZ.getDefaultState();
            case ASIAN, MEDIEVAL, DEFAULT -> Blocks.OAK_PLANKS.getDefaultState();
            case RUSTIC -> Blocks.SPRUCE_PLANKS.getDefaultState();
            default -> Blocks.OAK_PLANKS.getDefaultState();
        };
    }

    public static BlockState defaultWindow(BuildingStyle style) {
        if (style == null) style = BuildingStyle.DEFAULT;
        return switch (style) {
            case MODERN, FUTURISTIC -> Blocks.GLASS.getDefaultState();
            default -> Blocks.GLASS_PANE.getDefaultState();
        };
    }

    public static BlockState defaultRoof(BuildingStyle style) {
        if (style == null) style = BuildingStyle.DEFAULT;
        return switch (style) {
            case MODERN -> Blocks.BLACK_CONCRETE.getDefaultState();
            case FUTURISTIC -> Blocks.QUARTZ_BLOCK.getDefaultState();
            case ASIAN ->
                // 官式灰瓦（近似）：深板岩瓦
                    Blocks.DEEPSLATE_TILES.getDefaultState();
            case MEDIEVAL, DEFAULT -> Blocks.DARK_OAK_PLANKS.getDefaultState();
            case RUSTIC -> Blocks.SPRUCE_PLANKS.getDefaultState();
            default -> Blocks.DARK_OAK_PLANKS.getDefaultState();
        };
    }

    public static BlockState defaultTrim(BuildingStyle style, BlockState wall) {
        // 如果墙材本身是 stone bricks，trim 用石砖更一致
        if (wall != null && wall.getBlock() == Blocks.STONE_BRICKS) {
            return Blocks.CHISELED_STONE_BRICKS.getDefaultState();
        }
        if (style == null) style = BuildingStyle.DEFAULT;
        return switch (style) {
            case MODERN -> Blocks.BLACK_CONCRETE.getDefaultState();
            case FUTURISTIC -> Blocks.LIGHT_BLUE_STAINED_GLASS.getDefaultState();
            case ASIAN -> Blocks.RED_TERRACOTTA.getDefaultState();
            default -> Blocks.SPRUCE_LOG.getDefaultState();
        };
    }

    public static BlockState defaultFoundation(BuildingStyle style, BlockState wall) {
        if (wall != null && (wall.getBlock() == Blocks.WHITE_CONCRETE || wall.getBlock() == Blocks.QUARTZ_BLOCK)) {
            return Blocks.SMOOTH_STONE.getDefaultState();
        }
        return switch (style) {
            case MODERN, FUTURISTIC -> Blocks.SMOOTH_STONE.getDefaultState();
            case ASIAN -> Blocks.POLISHED_BLACKSTONE.getDefaultState();
            case RUSTIC, MEDIEVAL, DEFAULT -> Blocks.COBBLESTONE.getDefaultState();
        };
    }

    public static BlockState defaultPillar(BuildingStyle style) {
        return switch (style) {
            case MODERN, FUTURISTIC -> Blocks.QUARTZ_PILLAR.getDefaultState();
            case ASIAN -> Blocks.DARK_OAK_LOG.getDefaultState();
            case RUSTIC -> Blocks.SPRUCE_LOG.getDefaultState();
            case MEDIEVAL, DEFAULT -> Blocks.OAK_LOG.getDefaultState();
        };
    }

    public static BlockState defaultRoofStairs(BuildingStyle style, BlockState roof) {
        Block b = roof != null ? roof.getBlock() : null;
        if (b == Blocks.DARK_OAK_PLANKS) return Blocks.DARK_OAK_STAIRS.getDefaultState();
        if (b == Blocks.SPRUCE_PLANKS) return Blocks.SPRUCE_STAIRS.getDefaultState();
        if (b == Blocks.OAK_PLANKS) return Blocks.OAK_STAIRS.getDefaultState();
        if (b == Blocks.BRICKS) return Blocks.BRICK_STAIRS.getDefaultState();
        if (b == Blocks.STONE_BRICKS) return Blocks.STONE_BRICK_STAIRS.getDefaultState();
        if (b == Blocks.BLACK_CONCRETE) return Blocks.POLISHED_BLACKSTONE_STAIRS.getDefaultState();
        return switch (style) {
            case MEDIEVAL, ASIAN, DEFAULT -> Blocks.DARK_OAK_STAIRS.getDefaultState();
            case RUSTIC -> Blocks.SPRUCE_STAIRS.getDefaultState();
            case MODERN, FUTURISTIC -> Blocks.POLISHED_BLACKSTONE_STAIRS.getDefaultState();
        };
    }

    public static BlockState defaultRoofSlab(BuildingStyle style, BlockState roof) {
        Block b = roof != null ? roof.getBlock() : null;
        if (b == Blocks.DARK_OAK_PLANKS) return Blocks.DARK_OAK_SLAB.getDefaultState();
        if (b == Blocks.SPRUCE_PLANKS) return Blocks.SPRUCE_SLAB.getDefaultState();
        if (b == Blocks.OAK_PLANKS) return Blocks.OAK_SLAB.getDefaultState();
        if (b == Blocks.BLACK_CONCRETE) return Blocks.POLISHED_BLACKSTONE_SLAB.getDefaultState();
        if (b == Blocks.STONE_BRICKS) return Blocks.STONE_BRICK_SLAB.getDefaultState();
        return switch (style) {
            case MODERN, FUTURISTIC -> Blocks.SMOOTH_STONE_SLAB.getDefaultState();
            case ASIAN, MEDIEVAL, DEFAULT -> Blocks.DARK_OAK_SLAB.getDefaultState();
            case RUSTIC -> Blocks.SPRUCE_SLAB.getDefaultState();
        };
    }

    public static BlockState defaultDoor(BuildingStyle style) {
        return switch (style) {
            case MODERN, FUTURISTIC -> Blocks.IRON_DOOR.getDefaultState();
            case ASIAN -> Blocks.DARK_OAK_DOOR.getDefaultState();
            case RUSTIC -> Blocks.SPRUCE_DOOR.getDefaultState();
            case MEDIEVAL, DEFAULT -> Blocks.OAK_DOOR.getDefaultState();
        };
    }

    // ========== 样式解析方法 ==========

    /**
     * 根据样式选项解析窗户材质
     */
    public static BlockState resolveWindowByStyleOption(ServerWorld world,
                                                        BuildingStyle style,
                                                        BuildingSpec spec,
                                                        StyleGenome genome,
                                                        StyleProfile profile,
                                                        BlockState fallback,
                                                        BlockState pillar,
                                                        BlockState trim) {
        String windowStyle = resolveEffectiveWindowStyle(spec, genome, profile, style);
        String ws = (windowStyle == null) ? "" : windowStyle.trim().toLowerCase();

        switch (ws) {
            case "shoji", "paper" -> {
                return Blocks.WHITE_STAINED_GLASS_PANE.getDefaultState();
            }
            case "fence" -> {
                // lattice window: pick wood fence matching pillars if possible
                String pid = (profile != null && profile.palette() != null) ? profile.palette().pillar : null;
                if ((pid == null || pid.isBlank()) && pillar != null) {
                    pid = Registries.BLOCK.getId(pillar.getBlock()).toString();
                }
                String fenceId;
                if (pid != null && pid.contains("dark_oak")) fenceId = "minecraft:dark_oak_fence";
                else if (pid != null && pid.contains("spruce")) fenceId = "minecraft:spruce_fence";
                else fenceId = (style == BuildingStyle.ASIAN) ? "minecraft:oak_fence" : "minecraft:oak_fence";
                return getStateOrDefault(world, fenceId, Blocks.OAK_FENCE.getDefaultState());
            }
            case "bars", "iron_bars" -> {
                // medieval/castle: iron bar windows
                return Blocks.IRON_BARS.getDefaultState();
            }
            case "slit" -> {
                // Use bars for thin openings; density is handled by windowRatio clamps.
                return Blocks.IRON_BARS.getDefaultState();
            }
            case "stained" -> {
                // stained glass pane: derive color from trim first (if it's stained glass)
                String tid = (profile != null && profile.palette() != null) ? profile.palette().trim : null;
                if ((tid == null || tid.isBlank()) && trim != null) {
                    tid = Registries.BLOCK.getId(trim.getBlock()).toString();
                }
                String paneId = deriveStainedPaneId(tid);
                return getStateOrDefault(world, paneId, Blocks.LIGHT_BLUE_STAINED_GLASS_PANE.getDefaultState());
            }
            case "curtain_wall", "curtain" -> {
                // Modern curtain wall: prefer panes for thin facade, fallback to glass pane.
                try {
                    if (fallback != null && fallback.getBlock() == Blocks.TINTED_GLASS) {
                        return Blocks.TINTED_GLASS.getDefaultState();
                    }
                } catch (Throwable ex) { LOG.debug("best-effort step failed", ex); }
                return Blocks.GLASS_PANE.getDefaultState();
            }
            default -> {
                // pane: if fallback is full glass, use glass pane for better proportions
                try {
                    if (fallback != null && fallback.getBlock() == Blocks.GLASS) {
                        return Blocks.GLASS_PANE.getDefaultState();
                    }
                } catch (Throwable ex) { LOG.debug("best-effort step failed", ex); }
                return fallback != null ? fallback : Blocks.GLASS_PANE.getDefaultState();
            }
        }
    }

    /**
     * 解析有效的窗户样式
     * 优先级：StyleOptions（显式） > genome.params.windowStyle > styleProfile.details.windowStyle > heuristic default
     */
    public static String resolveEffectiveWindowStyle(BuildingSpec spec,
                                                     StyleGenome genome,
                                                     StyleProfile profile,
                                                     BuildingStyle style) {
        String windowStyle = (spec != null && spec.getStyleOptions() != null) ? spec.getStyleOptions().getWindowStyle() : null;
        if (windowStyle == null || windowStyle.isBlank()) {
            windowStyle = (genome != null && genome.params != null) ? genome.params.windowStyle : null;
        }
        if (windowStyle == null || windowStyle.isBlank()) {
            try {
                if (profile != null && profile.details() != null) {
                    windowStyle = profile.details().windowStyle;
                }
            } catch (Throwable ex) { LOG.debug("best-effort step failed", ex); }
        }
        if (windowStyle == null || windowStyle.isBlank()) {
            windowStyle = switch (style) {
                case ASIAN -> "fence";
                case MEDIEVAL -> "bars";
                case MODERN, FUTURISTIC -> "pane";
                case RUSTIC -> "pane";
                default -> "pane";
            };
        }
        return windowStyle;
    }

    // ========== 工具方法 ==========

    /**
     * 将字符串 blockId 转为 BlockState（比如 "minecraft:stone_bricks"）
     */
    public static BlockState getState(ServerWorld world, String id) {
        if (id == null || id.isEmpty()) {
            return Blocks.OAK_PLANKS.getDefaultState();
        }

        try {
            // 解析 Identifier
            Identifier identifier;
            if (id.contains(":")) {
                identifier = Identifier.of(id);
            } else {
                identifier = Identifier.of("minecraft", id);
            }

            // 从注册表获取 Block（使用静态 Registries）
            Block block = Registries.BLOCK.get(identifier);
            return block.getDefaultState();

        } catch (Exception e) {
            // 如果解析失败，使用回退方案
            return resolveBlockFallback(id);
        }
    }

    /**
     * 获取 BlockState，如果失败则返回默认值
     */
    public static BlockState getStateOrDefault(ServerWorld world, String id, BlockState defaultState) {
        if (id == null || id.isBlank()) return defaultState;
        try {
            return getState(world, id);
        } catch (Throwable ex) {
            LOG.debug("resolve block state failed id={}", id, ex);
            return defaultState;
        }
    }

    /**
     * 回退方案：通过字符串匹配解析常用方块
     */
    private static BlockState resolveBlockFallback(String material) {
        if (material == null) return Blocks.OAK_PLANKS.getDefaultState();

        String lower = material.toLowerCase();

        // 石头类
        if (lower.contains("stone_brick") || lower.contains("stonebrick")) {
            return Blocks.STONE_BRICKS.getDefaultState();
        }
        if (lower.contains("cobblestone")) {
            return Blocks.COBBLESTONE.getDefaultState();
        }
        if (lower.contains("stone") && !lower.contains("brick")) {
            return Blocks.STONE.getDefaultState();
        }

        // 砖类
        if (lower.contains("brick") && !lower.contains("stone")) {
            return Blocks.BRICKS.getDefaultState();
        }

        // 木头类
        if (lower.contains("dark_oak")) {
            return Blocks.DARK_OAK_PLANKS.getDefaultState();
        }
        if (lower.contains("oak_plank") || lower.contains("oak_wood")) {
            return Blocks.OAK_PLANKS.getDefaultState();
        }
        if (lower.contains("spruce")) {
            return Blocks.SPRUCE_PLANKS.getDefaultState();
        }
        if (lower.contains("birch")) {
            return Blocks.BIRCH_PLANKS.getDefaultState();
        }
        if (lower.contains("jungle")) {
            return Blocks.JUNGLE_PLANKS.getDefaultState();
        }
        if (lower.contains("acacia")) {
            return Blocks.ACACIA_PLANKS.getDefaultState();
        }
        if (lower.contains("wood") || lower.contains("plank") || lower.contains("oak")) {
            return Blocks.OAK_PLANKS.getDefaultState();
        }

        // 玻璃类
        if (lower.contains("glass_pane")) {
            return Blocks.GLASS_PANE.getDefaultState();
        }
        if (lower.contains("glass")) {
            return Blocks.GLASS.getDefaultState();
        }

        // 默认返回橡木木板
        return Blocks.OAK_PLANKS.getDefaultState();
    }

    /**
     * 从 ID 推导染色玻璃板 ID
     */
    private static String deriveStainedPaneId(String id) {
        if (id == null || id.isBlank()) return "minecraft:light_blue_stained_glass_pane";
        String s = id.trim();
        if (s.endsWith("_stained_glass_pane")) return s;
        if (s.endsWith("_stained_glass")) return s + "_pane";
        // best-effort: unknown stained id, use safe fallback
        return "minecraft:light_blue_stained_glass_pane";
    }
}

