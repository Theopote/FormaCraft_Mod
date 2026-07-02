package com.formacraft.common.generation.structure;

import com.formacraft.common.logging.FcaLog;
import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.common.style.profile.DetailPreferences;
import com.formacraft.server.build.PlannedBlock;
import com.formacraft.server.material.PaletteResolver;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.List;

/**
 * 房屋装饰生成器
 * 
 * 负责生成各种装饰元素，包括：
 * - 立面装饰（门户、装饰轮廓、柱廊、山花等）
 * - 照明装饰（门照明、周边照明、门旗等）
 * - 风格化装饰（哥特式、古典式、中式等）
 * 
 * 从 HouseGenerator 中拆分出来，以提高代码可维护性。
 */
public class HouseDecorator {

    private static final FcaLog LOG = FcaLog.of("HouseDecorator");
    
    private HouseDecorator() {} // Utility class
    
    /**
     * 生成装饰元素
     * 
     * @param blocks 方块列表
     * @param origin 建筑原点
     * @param world 世界对象
     * @param spec 建筑规格
     * @param width 建筑宽度
     * @param depth 建筑深度
     * @param height 建筑高度
     * @param wall 墙体材质
     * @param trim 装饰材质
     * @param foundation 基础材质
     * @param pillar 柱子材质
     * @param roof 屋顶材质
     * @param roofStairs 屋顶楼梯材质
     * @param roofSlab 屋顶半砖材质
     * @param windowBlock 窗户材质
     * @param paletteId 调色板ID
     * @param details 细节偏好
     * @param layoutInfo 布局信息（可选）
     */
    public static void decorate(
            List<PlannedBlock> blocks,
            BlockPos origin,
            ServerWorld world,
            BuildingSpec spec,
            int width,
            int depth,
            int height,
            BlockState wall,
            BlockState trim,
            BlockState foundation,
            BlockState pillar,
            BlockState roof,
            BlockState roofStairs,
            BlockState roofSlab,
            BlockState windowBlock,
            String paletteId,
            DetailPreferences details,
            HouseLayoutGenerator.LayoutInfo layoutInfo) {
        
        if (blocks == null || origin == null || details == null) return;
        if (width < 9 || depth < 9 || height < 6) return; // too small
        
        Direction doorSide = resolveDoorSide(spec);
        String layoutSymmetry = (layoutInfo != null) ? layoutInfo.symmetry() : "NONE";
        
        // --- Entry / portal feature (cross-style) ---
        if (details.portalStyle != null && !details.portalStyle.isBlank()) {
            addPortalFeature(blocks, origin, width, depth, height, doorSide, foundation, trim, roofSlab, roofStairs, details.portalStyle);
        }
        
        // --- Ornaments / props (cross-style) ---
        if (details.ornamentProfile != null && !details.ornamentProfile.isBlank()) {
            addOrnamentProfile(blocks, origin, world, width, depth, height, doorSide, foundation, trim, roofSlab,
                    paletteId, details.ornamentProfile, details, layoutSymmetry);
        }
        
        // --- Classical stylobate / podium ring ---
        if (details.stylobate) {
            addStylobate(blocks, origin, width, depth, doorSide, foundation, roofSlab, roofStairs);
        }
        
        // --- Greco-Roman: colonnade + pediment ---
        if (details.peristyle) {
            addPeristyleColonnade(blocks, origin, width, depth, height, doorSide, foundation, pillar, roofSlab,
                    details.entablature ? trim : null,
                    Math.max(2, Math.min(4, details.colonnadeSpacing)),
                    details.classicalColumnOrder);
            if (details.pediment) {
                addPediment(blocks, origin, width, depth, height, doorSide, trim, roofSlab);
            }
        } else if (details.colonnade || details.pediment) {
            // only build a front portico to avoid heavy intrusion
            addFrontColonnade(blocks, origin, width, depth, height, doorSide, foundation, pillar, roofSlab,
                    details.entablature ? trim : null,
                    Math.max(2, Math.min(4, details.colonnadeSpacing)),
                    details.classicalColumnOrder);
            if (details.pediment) {
                addPediment(blocks, origin, width, depth, height, doorSide, trim, roofSlab);
            }
        }
        
        // --- Gothic: rose window + buttresses ---
        if (details.roseWindow) {
            addRoseWindow(blocks, origin, width, depth, height, doorSide, windowBlock, trim);
        }
        if (details.buttresses) {
            addFlyingButtresses(blocks, origin, width, depth, height, doorSide, foundation, trim, roofStairs);
        }
        if (details.pointedArches || details.arches) {
            addPointedDoorPortal(blocks, origin, width, depth, height, doorSide, trim, roofStairs);
        }
    }
    
    /**
     * 生成照明装饰
     * 
     * @param blocks 方块列表
     * @param origin 建筑原点
     * @param width 建筑宽度
     * @param depth 建筑深度
     * @param foundation 基础材质
     * @param doorSide 门朝向
     * @param lightingMode 照明模式（none | door | perimeter）
     * @param lightingType 照明类型（torch | lantern）
     * @param lightingSpacing 照明间距（仅用于 perimeter）
     * @param bannerColor 旗帜颜色
     * @param paletteId 调色板ID
     * @param world 世界对象
     */
    public static void generateLighting(
            List<PlannedBlock> blocks,
            BlockPos origin,
            int width,
            int depth,
            BlockState foundation,
            Direction doorSide,
            String lightingMode,
            String lightingType,
            int lightingSpacing,
            String bannerColor,
            String paletteId,
            ServerWorld world) {
        
        if (blocks == null || origin == null) return;
        if ("none".equalsIgnoreCase(lightingMode)) return;
        
        if ("door".equalsIgnoreCase(lightingMode)) {
            addDoorLighting(blocks, origin, width, depth, foundation, doorSide, lightingType);
        } else if ("perimeter".equalsIgnoreCase(lightingMode)) {
            addPerimeterLighting(blocks, origin, width, depth, doorSide, lightingType, lightingSpacing);
        }
        
        // 门旗（可选）
        if (bannerColor != null || paletteId != null) {
            addDoorBanners(blocks, origin, width, depth, doorSide, bannerColor, paletteId, world);
        }
    }
    
    // ========== 辅助方法 ==========
    
    private static Direction resolveDoorSide(BuildingSpec spec) {
        if (spec == null || spec.getExtra() == null) return Direction.NORTH;
        
        // Layout IR: extra.layout.entranceFacing overrides legacy doorSide/facing
        try {
            Object layoutObj = spec.getExtra().get("layout");
            if (layoutObj instanceof java.util.Map<?, ?> layout) {
                Object ef = layout.get("entranceFacing");
                if (ef != null) {
                    String s = String.valueOf(ef).trim().toUpperCase(java.util.Locale.ROOT);
                    return switch (s) {
                        case "NORTH" -> Direction.NORTH;
                        case "SOUTH" -> Direction.SOUTH;
                        case "EAST" -> Direction.EAST;
                        case "WEST" -> Direction.WEST;
                        default -> Direction.NORTH;
                    };
                }
            }
        } catch (Throwable ex) { LOG.debug("best-effort step failed", ex); }
        
        // Legacy: extra.doorSide
        try {
            Object ds = spec.getExtra().get("doorSide");
            if (ds != null) {
                String s = String.valueOf(ds).trim().toUpperCase(java.util.Locale.ROOT);
                return switch (s) {
                    case "NORTH" -> Direction.NORTH;
                    case "SOUTH" -> Direction.SOUTH;
                    case "EAST" -> Direction.EAST;
                    case "WEST" -> Direction.WEST;
                    default -> Direction.NORTH;
                };
            }
        } catch (Throwable ex) { LOG.debug("best-effort step failed", ex); }
        
        return Direction.NORTH;
    }
    
    private static String resolveLayoutSymmetry(BuildingSpec spec) {
        if (spec == null || spec.getExtra() == null) return "NONE";
        
        try {
            Object layoutObj = spec.getExtra().get("layout");
            if (layoutObj instanceof java.util.Map<?, ?> layout) {
                Object sym = layout.get("symmetry");
                if (sym != null) {
                    return String.valueOf(sym).trim().toUpperCase(java.util.Locale.ROOT);
                }
            }
        } catch (Throwable ex) { LOG.debug("best-effort step failed", ex); }
        
        return "NONE";
    }
    
    private static BlockState withFacingIfPossible(BlockState state, Direction facing) {
        if (state == null) return null;
        try {
            if (state.contains(Properties.HORIZONTAL_FACING)) {
                return state.with(Properties.HORIZONTAL_FACING, facing);
            }
        } catch (Throwable ex) { LOG.debug("best-effort step failed", ex); }
        return state;
    }
    
    // ========== 装饰方法实现 ==========
    
    private static void addPortalFeature(List<PlannedBlock> blocks, BlockPos origin, int width, int depth, int height,
                                        Direction doorSide, BlockState foundation, BlockState trim, BlockState roofSlab,
                                        BlockState roofStairs, String portalStyle) {
        if (blocks == null || origin == null || portalStyle == null) return;
        if (width < 7 || depth < 7 || height < 5) return;
        if (doorSide == null) doorSide = Direction.NORTH;
        final BlockState t = (trim != null) ? trim : (foundation != null ? foundation : Blocks.STONE_BRICKS.getDefaultState());
        final BlockState slab = (roofSlab != null) ? roofSlab : t;
        final BlockState stairs = (roofStairs != null) ? roofStairs : t;

        String ps = portalStyle.trim().toLowerCase(java.util.Locale.ROOT);

        boolean onNS = (doorSide == Direction.NORTH || doorSide == Direction.SOUTH);
        int center = onNS ? (width / 2) : (depth / 2);
        int a0 = center - 1;
        int a1 = center;
        int a2 = center + 1;

        // Outside plane adjacent to wall (within the "cleared" buffer [-1..+1])
        int ox = (doorSide == Direction.WEST) ? -1 : (doorSide == Direction.EAST ? width : 0);
        int oz = (doorSide == Direction.NORTH) ? -1 : (doorSide == Direction.SOUTH ? depth : 0);

        // Helper to place a post + beam in outside plane
        java.util.function.BiConsumer<Integer, Integer> post = (axis, yMax) -> {
            if (onNS) {
                BlockPos b = origin.add(axis, 0, oz);
                for (int y = 0; y <= yMax; y++) blocks.add(new PlannedBlock(b.up(y), t));
            } else {
                BlockPos b = origin.add(ox, 0, axis);
                for (int y = 0; y <= yMax; y++) blocks.add(new PlannedBlock(b.up(y), t));
            }
        };

        if (ps.contains("gothic")) {
            // Reuse existing pointed portal (inside wall) for strong silhouette
            addPointedDoorPortal(blocks, origin, width, depth, height, doorSide, t, stairs);
            return;
        }

        if (ps.contains("torii")) {
            // Two posts + top beam (best-effort)
            post.accept(a0, 3);
            post.accept(a2, 3);
            for (int y = 4; y <= 4; y++) {
                if (onNS) {
                    for (int x = a0; x <= a2; x++) blocks.add(new PlannedBlock(origin.add(x, y, oz), slab));
                } else {
                    for (int z = a0; z <= a2; z++) blocks.add(new PlannedBlock(origin.add(ox, y, z), slab));
                }
            }
            return;
        }

        if (ps.contains("paifang")) {
            // Slightly taller: posts + double beam
            post.accept(a0, 4);
            post.accept(a2, 4);
            if (onNS) {
                for (int x = a0; x <= a2; x++) blocks.add(new PlannedBlock(origin.add(x, 4, oz), t));
                for (int x = a0; x <= a2; x++) blocks.add(new PlannedBlock(origin.add(x, 5, oz), slab));
            } else {
                for (int z = a0; z <= a2; z++) blocks.add(new PlannedBlock(origin.add(ox, 4, z), t));
                for (int z = a0; z <= a2; z++) blocks.add(new PlannedBlock(origin.add(ox, 5, z), slab));
            }
            return;
        }

        if (ps.contains("neon")) {
            // Simple glowing frame: use trim (often stained glass in Cyberpunk) around door axis outside
            post.accept(a0, 3);
            post.accept(a2, 3);
            if (onNS) {
                blocks.add(new PlannedBlock(origin.add(a0, 3, oz), t));
                blocks.add(new PlannedBlock(origin.add(a1, 3, oz), t));
                blocks.add(new PlannedBlock(origin.add(a2, 3, oz), t));
            } else {
                blocks.add(new PlannedBlock(origin.add(ox, 3, a0), t));
                blocks.add(new PlannedBlock(origin.add(ox, 3, a1), t));
                blocks.add(new PlannedBlock(origin.add(ox, 3, a2), t));
            }
            return;
        }

        if (ps.contains("modern")) {
            // Minimal frame + small canopy
            post.accept(a0, 2);
            post.accept(a2, 2);
            if (onNS) {
                blocks.add(new PlannedBlock(origin.add(a0, 3, oz), slab));
                blocks.add(new PlannedBlock(origin.add(a1, 3, oz), slab));
                blocks.add(new PlannedBlock(origin.add(a2, 3, oz), slab));
            } else {
                blocks.add(new PlannedBlock(origin.add(ox, 3, a0), slab));
                blocks.add(new PlannedBlock(origin.add(ox, 3, a1), slab));
                blocks.add(new PlannedBlock(origin.add(ox, 3, a2), slab));
            }
            return;
        }

        if (ps.contains("steampunk")) {
            // Riveted frame vibe: iron trapdoors around outside door axis (best-effort)
            BlockState td = Blocks.IRON_TRAPDOOR.getDefaultState();
            post.accept(a0, 2);
            post.accept(a2, 2);
            if (onNS) {
                blocks.add(new PlannedBlock(origin.add(a0, 1, oz), td));
                blocks.add(new PlannedBlock(origin.add(a2, 1, oz), td));
                blocks.add(new PlannedBlock(origin.add(a0, 2, oz), td));
                blocks.add(new PlannedBlock(origin.add(a2, 2, oz), td));
            } else {
                blocks.add(new PlannedBlock(origin.add(ox, 1, a0), td));
                blocks.add(new PlannedBlock(origin.add(ox, 1, a2), td));
                blocks.add(new PlannedBlock(origin.add(ox, 2, a0), td));
                blocks.add(new PlannedBlock(origin.add(ox, 2, a2), td));
            }
            return;
        }

        if (ps.contains("organic")) {
            // Soft arch hint: posts + curved top with stairs
            post.accept(a0, 2);
            post.accept(a2, 2);
            Direction inward = (doorSide == Direction.NORTH) ? Direction.SOUTH
                    : (doorSide == Direction.SOUTH) ? Direction.NORTH
                    : (doorSide == Direction.WEST) ? Direction.EAST
                    : Direction.WEST;
            BlockState s = withFacingIfPossible(stairs, inward);
            if (onNS) {
                blocks.add(new PlannedBlock(origin.add(a0, 3, oz), s));
                blocks.add(new PlannedBlock(origin.add(a2, 3, oz), s));
                blocks.add(new PlannedBlock(origin.add(a1, 4, oz), t));
            } else {
                blocks.add(new PlannedBlock(origin.add(ox, 3, a0), s));
                blocks.add(new PlannedBlock(origin.add(ox, 3, a2), s));
                blocks.add(new PlannedBlock(origin.add(ox, 4, a1), t));
            }
            return;
        }

        // Default: stone arch-ish frame
        // posts + small arch top
        post.accept(a0, 2);
        post.accept(a2, 2);
        Direction inward = (doorSide == Direction.NORTH) ? Direction.SOUTH
                : (doorSide == Direction.SOUTH) ? Direction.NORTH
                : (doorSide == Direction.WEST) ? Direction.EAST
                : Direction.WEST;
        BlockState s = withFacingIfPossible(stairs, inward);
        if (onNS) {
            blocks.add(new PlannedBlock(origin.add(a0, 3, oz), s));
            blocks.add(new PlannedBlock(origin.add(a2, 3, oz), s));
            blocks.add(new PlannedBlock(origin.add(a1, 3, oz), slab));
        } else {
            blocks.add(new PlannedBlock(origin.add(ox, 3, a0), s));
            blocks.add(new PlannedBlock(origin.add(ox, 3, a2), s));
            blocks.add(new PlannedBlock(origin.add(ox, 3, a1), slab));
        }
    }
    
    private static void addOrnamentProfile(List<PlannedBlock> blocks,
                                          BlockPos origin,
                                          ServerWorld world,
                                          int width,
                                          int depth,
                                          int height,
                                          Direction doorSide,
                                          BlockState foundation,
                                          BlockState trim,
                                          BlockState roofSlab,
                                          String paletteId,
                                          String ornamentProfile,
                                          DetailPreferences details,
                                          String layoutSymmetry) {
        if (blocks == null || origin == null || ornamentProfile == null) return;
        if (width < 7 || depth < 7 || height < 5) return;
        if (doorSide == null) doorSide = Direction.NORTH;
        BlockState t = (trim != null) ? trim : (foundation != null ? foundation : Blocks.STONE_BRICKS.getDefaultState());
        BlockState slab = (roofSlab != null) ? roofSlab : t;

        String op = ornamentProfile.trim().toLowerCase(java.util.Locale.ROOT);
        String sym = (layoutSymmetry == null) ? "NONE" : layoutSymmetry.trim().toUpperCase(java.util.Locale.ROOT);
        boolean onNS = (doorSide == Direction.NORTH || doorSide == Direction.SOUTH);
        int center = onNS ? (width / 2) : (depth / 2);
        int a0 = center - 1;
        int a2 = center + 1;
        int ox = (doorSide == Direction.WEST) ? -1 : (doorSide == Direction.EAST ? width : 0);
        int oz = (doorSide == Direction.NORTH) ? -1 : (doorSide == Direction.SOUTH ? depth : 0);

        // --- Chinese plaque: a sign-like lintel above the door axis (outside plane)
        if (op.contains("chinese") || op.contains("plaque")) {
            BlockState sign = Blocks.DARK_OAK_WALL_SIGN.getDefaultState();
            if (paletteId != null && !paletteId.isBlank() && world != null) {
                // Prefer palette semantic signage; keep wall sign as fallback.
                sign = PaletteResolver.pick(world, paletteId, "ROAD_SIGNAGE", origin, 0xA005E001L, sign);
                sign = PaletteResolver.pick(world, paletteId, "DECOR_DETAIL", origin, 0xA005E002L, sign);
            }
            sign = withFacingIfPossible(sign, doorSide);
            int y = 3;
            if (onNS) {
                blocks.add(new PlannedBlock(origin.add(center, y, oz), sign));
                blocks.add(new PlannedBlock(origin.add(a0, y, oz), slab));
                blocks.add(new PlannedBlock(origin.add(a2, y, oz), slab));
            } else {
                blocks.add(new PlannedBlock(origin.add(ox, y, center), sign));
                blocks.add(new PlannedBlock(origin.add(ox, y, a0), slab));
                blocks.add(new PlannedBlock(origin.add(ox, y, a2), slab));
            }
            return;
        }

        // --- Castle banners: two wall banners flanking the door (outside plane)
        if (op.contains("castle") || op.contains("banner")) {
            BlockState wb = resolveWallBannerState(details != null ? details.bannerColor : null);
            if ((details == null || details.bannerColor == null || details.bannerColor.isBlank())
                    && paletteId != null && !paletteId.isBlank() && world != null) {
                // Prefer palette banner when no explicit banner color was chosen.
                wb = PaletteResolver.pick(world, paletteId, "BANNER", origin, 0xA005E003L, wb);
            }
            wb = withFacingIfPossible(wb, doorSide);
            int y = 2;
            if (onNS) {
                blocks.add(new PlannedBlock(origin.add(a0, y, oz), wb));
                blocks.add(new PlannedBlock(origin.add(a2, y, oz), wb));
            } else {
                blocks.add(new PlannedBlock(origin.add(ox, y, a0), wb));
                blocks.add(new PlannedBlock(origin.add(ox, y, a2), wb));
            }
            return;
        }

        // --- Steampunk pipes: vertical pipes on one corner + tiny chimney hint on roof edge
        if (op.contains("steam") || op.contains("pipe")) {
            BlockState pipe = Blocks.COPPER_BLOCK.getDefaultState();
            BlockState rib = Blocks.IRON_BARS.getDefaultState();
            BlockState chimney = Blocks.CAMPFIRE.getDefaultState();
            if (paletteId != null && !paletteId.isBlank() && world != null) {
                pipe = PaletteResolver.pick(world, paletteId, "DECOR_DETAIL", origin, 0xA005E100L, pipe);
                pipe = PaletteResolver.pick(world, paletteId, "FRAME", origin, 0xA005E101L, pipe);
                rib = PaletteResolver.pick(world, paletteId, "DECOR_DETAIL", origin, 0xA005E102L, rib);
                chimney = PaletteResolver.pick(world, paletteId, "DECOR_DETAIL", origin, 0xA005E103L, chimney);
            }
            BlockPos base = origin.add(-1, 0, -1);
            for (int y = 1; y <= Math.min(height + 1, 8); y++) {
                blocks.add(new PlannedBlock(base.up(y), pipe));
                if ((y & 1) == 0) blocks.add(new PlannedBlock(base.up(y).east(), rib));
            }
            // chimney on roof corner
            BlockPos top = origin.add(1, height + 1, 1);
            blocks.add(new PlannedBlock(top, chimney));
            return;
        }

        // --- Cyber signage: neon-ish plate using trim (often stained glass) above/side of door
        if (op.contains("cyber") || op.contains("sign")) {
            int y = 4;
            if (onNS) {
                blocks.add(new PlannedBlock(origin.add(center, y, oz), t));
                blocks.add(new PlannedBlock(origin.add(a2 + 1, y, oz), t));
                // symmetry X/BOTH: mirror to the left side as well
                if (sym.equals("X") || sym.equals("BOTH")) {
                    blocks.add(new PlannedBlock(origin.add(a0 - 1, y, oz), t));
                    blocks.add(new PlannedBlock(origin.add(a0, y, oz), t));
                }
            } else {
                blocks.add(new PlannedBlock(origin.add(ox, y, center), t));
                blocks.add(new PlannedBlock(origin.add(ox, y, a2 + 1), t));
                if (sym.equals("Z") || sym.equals("BOTH")) {
                    blocks.add(new PlannedBlock(origin.add(ox, y, a0 - 1), t));
                    blocks.add(new PlannedBlock(origin.add(ox, y, a0), t));
                }
            }
            return;
        }

        // --- Shrine lanterns (Japanese-ish): two small lanterns flanking the door axis
        if (op.contains("shrine_lantern") || op.contains("shrine-lantern") || op.contains("shrine lantern")) {
            BlockState post = t;
            BlockState lantern = Blocks.LANTERN.getDefaultState();
            if (paletteId != null && !paletteId.isBlank() && world != null) {
                post = PaletteResolver.pick(world, paletteId, "STRUCTURAL_BEAM", origin, 0xA005E0A1L, post);
                post = PaletteResolver.pick(world, paletteId, "FRAME", origin, 0xA005E0A2L, post);
                lantern = PaletteResolver.pick(world, paletteId, "LIGHTING", origin, 0xA005E0A3L, lantern);
                lantern = PaletteResolver.pick(world, paletteId, "ROAD_LIGHT", origin, 0xA005E0A4L, lantern);
            }
            int yPost = 1;
            int yLantern = 3;
            if (onNS) {
                blocks.add(new PlannedBlock(origin.add(a0, yPost, oz), post));
                blocks.add(new PlannedBlock(origin.add(a2, yPost, oz), post));
                blocks.add(new PlannedBlock(origin.add(a0, yLantern, oz), lantern));
                blocks.add(new PlannedBlock(origin.add(a2, yLantern, oz), lantern));
            } else {
                blocks.add(new PlannedBlock(origin.add(ox, yPost, a0), post));
                blocks.add(new PlannedBlock(origin.add(ox, yPost, a2), post));
                blocks.add(new PlannedBlock(origin.add(ox, yLantern, a0), lantern));
                blocks.add(new PlannedBlock(origin.add(ox, yLantern, a2), lantern));
            }
            return;
        }

        // --- Organic lanterns: leaves + lantern near door
        if (op.contains("organic") || op.contains("lantern") || op.contains("vine")) {
            BlockState leaf = Blocks.OAK_LEAVES.getDefaultState();
            BlockState lantern = Blocks.LANTERN.getDefaultState();
            if (paletteId != null && !paletteId.isBlank() && world != null) {
                leaf = PaletteResolver.pick(world, paletteId, "DECOR_DETAIL", origin, 0xA005E004L, leaf);
                lantern = PaletteResolver.pick(world, paletteId, "LIGHTING", origin, 0xA005E005L, lantern);
                lantern = PaletteResolver.pick(world, paletteId, "ROAD_LIGHT", origin, 0xA005E006L, lantern);
            }
            int y = 3;
            if (onNS) {
                blocks.add(new PlannedBlock(origin.add(a0, y, oz), leaf));
                blocks.add(new PlannedBlock(origin.add(a2, y, oz), leaf));
                blocks.add(new PlannedBlock(origin.add(center, y + 1, oz), lantern));
            } else {
                blocks.add(new PlannedBlock(origin.add(ox, y, a0), leaf));
                blocks.add(new PlannedBlock(origin.add(ox, y, a2), leaf));
                blocks.add(new PlannedBlock(origin.add(ox, y + 1, center), lantern));
            }
        }
    }
    
    private static void addStylobate(List<PlannedBlock> blocks,
                                     BlockPos origin,
                                     int width,
                                     int depth,
                                     Direction doorSide,
                                     BlockState foundation,
                                     BlockState capSlab,
                                     BlockState capStairs) {
        if (blocks == null || origin == null || foundation == null) return;
        if (capSlab == null) capSlab = foundation;
        if (capStairs == null) capStairs = capSlab;

        // Outer ring expands 1 block around footprint: x [-1..width], z [-1..depth]
        for (int x = -1; x <= width; x++) {
            for (int z = -1; z <= depth; z++) {
                boolean inside = (x >= 0 && x <= width - 1 && z >= 0 && z <= depth - 1);
                if (inside) continue;
                blocks.add(new PlannedBlock(origin.add(x, 0, z), foundation));
                blocks.add(new PlannedBlock(origin.add(x, 1, z), capSlab));
            }
        }

        // Front steps: 5-wide (clamped) aligned to door axis; placed just outside the ring.
        int cx = width / 2;
        int cz = depth / 2;
        if (doorSide == null) doorSide = Direction.NORTH;

        if (doorSide == Direction.NORTH || doorSide == Direction.SOUTH) {
            int x0 = Math.max(1, cx - 2);
            int x1 = Math.min(width - 2, cx + 2);
            int zOutside = (doorSide == Direction.NORTH) ? -2 : depth + 1;
            Direction stairFacing = (doorSide == Direction.NORTH) ? Direction.SOUTH : Direction.NORTH;
            for (int x = x0; x <= x1; x++) {
                blocks.add(new PlannedBlock(origin.add(x, 0, zOutside), withFacingIfPossible(capStairs, stairFacing)));
            }
        } else {
            int z0 = Math.max(1, cz - 2);
            int z1 = Math.min(depth - 2, cz + 2);
            int xOutside = (doorSide == Direction.WEST) ? -2 : width + 1;
            Direction stairFacing = (doorSide == Direction.WEST) ? Direction.EAST : Direction.WEST;
            for (int z = z0; z <= z1; z++) {
                blocks.add(new PlannedBlock(origin.add(xOutside, 0, z), withFacingIfPossible(capStairs, stairFacing)));
            }
        }
    }
    
    private static void addFrontColonnade(List<PlannedBlock> blocks,
                                          BlockPos origin,
                                          int width,
                                          int depth,
                                          int height,
                                          Direction doorSide,
                                          BlockState foundation,
                                          BlockState pillar,
                                          BlockState roofSlab,
                                          BlockState entablatureBlock,
                                          int spacing,
                                          String columnOrder) {
        if (width < 11 && depth < 11) return;
        int colH = Math.max(4, Math.min(height - 2, 8));
        int sp = Math.max(2, Math.min(4, spacing));

        // place 1 block outside the door side
        int zOutside = (doorSide == Direction.NORTH) ? -2 : (doorSide == Direction.SOUTH ? depth + 1 : -2);
        int xOutside = (doorSide == Direction.WEST) ? -2 : (doorSide == Direction.EAST ? width + 1 : -2);

        if (doorSide == Direction.NORTH || doorSide == Direction.SOUTH) {
            for (int x = 1; x <= width - 2; x += sp) {
                BlockPos base = origin.add(x, 0, zOutside);
                blocks.add(new PlannedBlock(base, foundation));
                for (int y = 1; y <= colH; y++) blocks.add(new PlannedBlock(base.up(y), pillar));
                // simple column base/capital to read more "classical"
                blocks.add(new PlannedBlock(base.up(colH + 1), pickCapitalBlock(roofSlab, entablatureBlock, columnOrder)));
            }
            if (entablatureBlock != null) {
                int y = colH + 1;
                for (int x = 1; x <= width - 2; x++) {
                    blocks.add(new PlannedBlock(origin.add(x, y, zOutside), entablatureBlock));
                }
            }
        } else {
            for (int z = 1; z <= depth - 2; z += sp) {
                BlockPos base = origin.add(xOutside, 0, z);
                blocks.add(new PlannedBlock(base, foundation));
                for (int y = 1; y <= colH; y++) blocks.add(new PlannedBlock(base.up(y), pillar));
                blocks.add(new PlannedBlock(base.up(colH + 1), pickCapitalBlock(roofSlab, entablatureBlock, columnOrder)));
            }
            if (entablatureBlock != null) {
                int y = colH + 1;
                for (int z = 1; z <= depth - 2; z++) {
                    blocks.add(new PlannedBlock(origin.add(xOutside, y, z), entablatureBlock));
                }
            }
        }
    }
    
    private static void addPeristyleColonnade(List<PlannedBlock> blocks,
                                              BlockPos origin,
                                              int width,
                                              int depth,
                                              int height,
                                              Direction doorSide,
                                              BlockState foundation,
                                              BlockState pillar,
                                              BlockState roofSlab,
                                              BlockState entablatureBlock,
                                              int spacing,
                                              String columnOrder) {
        if (blocks == null || origin == null) return;
        // Size gate: avoid clogging small houses
        if (width < 9 || depth < 9) return;

        int colH = Math.max(3, Math.min(6, height - 2));
        int sp = Math.max(2, spacing);

        int zNorth = -2;
        int zSouth = depth + 1;
        int xWest = -2;
        int xEast = width + 1;

        int cx = width / 2;
        int cz = depth / 2;

        java.util.function.BiPredicate<Integer, Integer> shouldSkipForDoor =
                (x, z) -> {
                    // Create a 3-wide opening centered on the door axis on the door side only
                    if (doorSide == Direction.NORTH && z == zNorth) return Math.abs(x - cx) <= 1;
                    if (doorSide == Direction.SOUTH && z == zSouth) return Math.abs(x - cx) <= 1;
                    if (doorSide == Direction.WEST && x == xWest) return Math.abs(z - cz) <= 1;
                    if (doorSide == Direction.EAST && x == xEast) return Math.abs(z - cz) <= 1;
                    return false;
                };

        // North/South sides
        for (int x = 1; x <= width - 2; x += sp) {
            if (!shouldSkipForDoor.test(x, zNorth)) placeColumn(blocks, origin.add(x, 0, zNorth), colH, foundation, pillar, roofSlab, entablatureBlock, columnOrder);
            if (!shouldSkipForDoor.test(x, zSouth)) placeColumn(blocks, origin.add(x, 0, zSouth), colH, foundation, pillar, roofSlab, entablatureBlock, columnOrder);
        }
        // West/East sides
        for (int z = 1; z <= depth - 2; z += sp) {
            if (!shouldSkipForDoor.test(xWest, z)) placeColumn(blocks, origin.add(xWest, 0, z), colH, foundation, pillar, roofSlab, entablatureBlock, columnOrder);
            if (!shouldSkipForDoor.test(xEast, z)) placeColumn(blocks, origin.add(xEast, 0, z), colH, foundation, pillar, roofSlab, entablatureBlock, columnOrder);
        }

        // Optional entablature ring (continuous beam) at capital level
        if (entablatureBlock != null) {
            int y = colH + 1;
            for (int x = 0; x <= width - 1; x++) {
                if (!shouldSkipForDoor.test(x, zNorth)) blocks.add(new PlannedBlock(origin.add(x, y, zNorth), entablatureBlock));
                if (!shouldSkipForDoor.test(x, zSouth)) blocks.add(new PlannedBlock(origin.add(x, y, zSouth), entablatureBlock));
            }
            for (int z = 0; z <= depth - 1; z++) {
                if (!shouldSkipForDoor.test(xWest, z)) blocks.add(new PlannedBlock(origin.add(xWest, y, z), entablatureBlock));
                if (!shouldSkipForDoor.test(xEast, z)) blocks.add(new PlannedBlock(origin.add(xEast, y, z), entablatureBlock));
            }
        }
    }
    
    private static void addPediment(List<PlannedBlock> blocks,
                                    BlockPos origin,
                                    int width,
                                    int depth,
                                    int height,
                                    Direction doorSide,
                                    BlockState trim,
                                    BlockState roofSlab) {
        // Pediment only looks right on a gable end; for MVP, attach to door-facing side if it's N/S, else north.
        boolean onNS = (doorSide == Direction.NORTH || doorSide == Direction.SOUTH);
        int z = onNS ? (doorSide == Direction.NORTH ? -1 : depth) : -1;
        int yBase = Math.max(3, height - 1);
        int mid = width / 2;
        int pedH = Math.max(3, Math.min(7, width / 2));

        // Outline triangle
        for (int i = 0; i < pedH; i++) {
            int x0 = Math.max(0, mid - i);
            int x1 = Math.min(width - 1, mid + i);
            int y = yBase + i;
            blocks.add(new PlannedBlock(origin.add(x0, y, z), trim));
            blocks.add(new PlannedBlock(origin.add(x1, y, z), trim));
            if (i == pedH - 1) {
                for (int x = x0; x <= x1; x++) blocks.add(new PlannedBlock(origin.add(x, y + 1, z), roofSlab));
            }
        }
        // Base line
        for (int x = 0; x < width; x++) blocks.add(new PlannedBlock(origin.add(x, yBase, z), trim));
    }
    
    private static void addRoseWindow(List<PlannedBlock> blocks,
                                      BlockPos origin,
                                      int width,
                                      int depth,
                                      int height,
                                      Direction doorSide,
                                      BlockState windowBlock,
                                      BlockState trim) {
        // place on door side if N/S else on north wall
        boolean onNS = (doorSide == Direction.NORTH || doorSide == Direction.SOUTH);
        Direction face = onNS ? doorSide : Direction.NORTH;
        int zWall = (face == Direction.NORTH) ? 0 : (face == Direction.SOUTH ? depth - 1 : 0);

        int cx = width / 2;
        int cy = Math.max(4, Math.min(height - 2, height / 2));
        int r = Math.max(2, Math.min(4, Math.min(width, height) / 4));

        // carve + fill a simple circle on the wall plane (1-block thick)
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                int d2 = dx * dx + dy * dy;
                if (d2 > r * r) continue;
                boolean edge = d2 >= (r - 1) * (r - 1);
                int x = cx + dx;
                int y = cy + dy;
                if (x <= 0 || x >= width - 1 || y <= 2 || y >= height - 1) continue;
                BlockPos p = origin.add(x, y, zWall);
                blocks.add(new PlannedBlock(p, edge ? trim : windowBlock));
            }
        }
    }
    
    private static void addFlyingButtresses(List<PlannedBlock> blocks,
                                           BlockPos origin,
                                           int width,
                                           int depth,
                                           int height,
                                           Direction doorSide,
                                           BlockState foundation,
                                           BlockState trim,
                                           BlockState roofStairs) {
        if (width < 13 || depth < 13 || height < 10) return;
        // Put buttresses on the two long sides (avoid door side so entrance remains clean)
        boolean alongX = width >= depth;
        int step = 4;
        int yTop = Math.max(6, height - 2);
        if (alongX) {
            // buttress on north/south walls (z = -1 and z = depth)
            for (int x = 2; x <= width - 3; x += step) {
                if (doorSide == Direction.NORTH) continue;
                placeButtress(blocks, origin.add(x, 0, -2), yTop, foundation, trim, roofStairs, Direction.SOUTH);
                if (doorSide == Direction.SOUTH) continue;
                placeButtress(blocks, origin.add(x, 0, depth + 1), yTop, foundation, trim, roofStairs, Direction.NORTH);
            }
        } else {
            // buttress on west/east walls (x=-1 and x=width)
            for (int z = 2; z <= depth - 3; z += step) {
                if (doorSide == Direction.WEST) continue;
                placeButtress(blocks, origin.add(-2, 0, z), yTop, foundation, trim, roofStairs, Direction.EAST);
                if (doorSide == Direction.EAST) continue;
                placeButtress(blocks, origin.add(width + 1, 0, z), yTop, foundation, trim, roofStairs, Direction.WEST);
            }
        }
    }
    
    private static void placeButtress(List<PlannedBlock> blocks,
                                      BlockPos base,
                                      int yTop,
                                      BlockState foundation,
                                      BlockState trim,
                                      BlockState roofStairs,
                                      Direction towardWall) {
        // vertical pier
        blocks.add(new PlannedBlock(base, foundation));
        for (int y = 1; y <= yTop - 3; y++) blocks.add(new PlannedBlock(base.up(y), trim));
        // diagonal-ish arm using stairs (2 steps)
        BlockPos a0 = base.up(yTop - 3).offset(towardWall, 1);
        BlockPos a1 = base.up(yTop - 2).offset(towardWall, 2);
        blocks.add(new PlannedBlock(a0, withFacingIfPossible(roofStairs, towardWall)));
        blocks.add(new PlannedBlock(a1, withFacingIfPossible(roofStairs, towardWall)));
    }
    
    private static void addPointedDoorPortal(List<PlannedBlock> blocks,
                                             BlockPos origin,
                                             int width,
                                             int depth,
                                             int height,
                                             Direction doorSide,
                                             BlockState trim,
                                             BlockState roofStairs) {
        if (trim == null || roofStairs == null) return;
        if (width < 9 || depth < 9 || height < 6) return;
        boolean onNS = (doorSide == Direction.NORTH || doorSide == Direction.SOUTH);
        int center = onNS ? (width / 2) : (depth / 2);
        int x = onNS ? center : (doorSide == Direction.EAST ? width - 1 : 0);
        int z = onNS ? (doorSide == Direction.SOUTH ? depth - 1 : 0) : center;

        // Frame around the door on the wall plane:
        // - two vertical trims, then a pointed top using stairs
        int y0 = 1;
        int yTop = Math.min(height - 2, 5);
        for (int y = y0; y <= yTop; y++) {
            if (onNS) {
                blocks.add(new PlannedBlock(origin.add(x - 1, y, z), trim));
                blocks.add(new PlannedBlock(origin.add(x + 1, y, z), trim));
            } else {
                blocks.add(new PlannedBlock(origin.add(x, y, z - 1), trim));
                blocks.add(new PlannedBlock(origin.add(x, y, z + 1), trim));
            }
        }
        // pointed apex at yTop+1
        int apexY = Math.min(height - 1, yTop + 1);
        if (onNS) {
            Direction fL = (doorSide == Direction.NORTH) ? Direction.EAST : Direction.EAST;
            Direction fR = (doorSide == Direction.NORTH) ? Direction.WEST : Direction.WEST;
            blocks.add(new PlannedBlock(origin.add(x - 1, apexY, z), withFacingIfPossible(roofStairs, fL)));
            blocks.add(new PlannedBlock(origin.add(x + 1, apexY, z), withFacingIfPossible(roofStairs, fR)));
            blocks.add(new PlannedBlock(origin.add(x, apexY + 1 <= height ? (apexY + 1) : apexY, z), trim));
        } else {
            Direction fL = (doorSide == Direction.EAST) ? Direction.SOUTH : Direction.SOUTH;
            Direction fR = (doorSide == Direction.EAST) ? Direction.NORTH : Direction.NORTH;
            blocks.add(new PlannedBlock(origin.add(x, apexY, z - 1), withFacingIfPossible(roofStairs, fR)));
            blocks.add(new PlannedBlock(origin.add(x, apexY, z + 1), withFacingIfPossible(roofStairs, fL)));
            blocks.add(new PlannedBlock(origin.add(x, apexY + 1 <= height ? (apexY + 1) : apexY, z), trim));
        }
    }
    
    // 公开方法，供 HouseGenerator 调用
    public static void addPointedWindowFrame(List<PlannedBlock> blocks,
                                             BlockPos origin,
                                             int x,
                                             int yFrame,
                                             int z,
                                             int width,
                                             int depth,
                                             BlockState trim,
                                             BlockState roofStairs) {
        if (blocks == null || origin == null || trim == null || roofStairs == null) return;
        // Determine inward-facing direction (so stairs slope toward the interior)
        Direction inward;
        if (z == 0) inward = Direction.SOUTH;
        else if (z == depth - 1) inward = Direction.NORTH;
        else if (x == 0) inward = Direction.EAST;
        else if (x == width - 1) inward = Direction.WEST;
        else return;

        // Two side "arch shoulders" + a small lintel line for stronger silhouette
        if (z == 0 || z == depth - 1) {
            blocks.add(new PlannedBlock(origin.add(x - 1, yFrame, z), withFacingIfPossible(roofStairs, inward)));
            blocks.add(new PlannedBlock(origin.add(x + 1, yFrame, z), withFacingIfPossible(roofStairs, inward)));
            blocks.add(new PlannedBlock(origin.add(x, yFrame, z), trim));
        } else {
            blocks.add(new PlannedBlock(origin.add(x, yFrame, z - 1), withFacingIfPossible(roofStairs, inward)));
            blocks.add(new PlannedBlock(origin.add(x, yFrame, z + 1), withFacingIfPossible(roofStairs, inward)));
            blocks.add(new PlannedBlock(origin.add(x, yFrame, z), trim));
        }
        // Apex
        blocks.add(new PlannedBlock(origin.add(x, yFrame + 1, z), trim));
    }
    
    // 公开方法，供 HouseGenerator 调用
    public static void addMullionBehindWindow(List<PlannedBlock> blocks,
                                               BlockPos origin,
                                               int x,
                                               int y,
                                               int z,
                                               int width,
                                               int depth,
                                               Direction doorSide) {
        if (blocks == null || origin == null) return;
        if (isNearDoor(doorSide, x, z, width, depth)) return;
        boolean onNorth = (z == 0);
        boolean onSouth = (z == depth - 1);
        boolean onWest = (x == 0);
        boolean onEast = (x == width - 1);
        if (!(onNorth || onSouth || onWest || onEast)) return;

        // inside-adjacent cell
        int ix = x;
        int iz = z;
        if (onNorth) iz = z + 1;
        else if (onSouth) iz = z - 1;
        else if (onWest) ix = x + 1;
        else if (onEast) ix = x - 1;

        if (ix <= 0 || ix >= width - 1 || iz <= 0 || iz >= depth - 1) return;
        blocks.add(new PlannedBlock(origin.add(ix, y, iz), Blocks.IRON_BARS.getDefaultState()));
    }
    
    private static void addDoorLighting(List<PlannedBlock> blocks, BlockPos origin, int width, int depth,
                                       BlockState foundation, Direction doorSide, String lightingType) {
        // Two lights flanking the door, placed without overwriting wall blocks.
        boolean onNorthSouth = (doorSide == Direction.NORTH || doorSide == Direction.SOUTH);
        int center = onNorthSouth ? (width / 2) : (depth / 2);
        int y = 2;

        // positions in the "air" adjacent to the wall: inside by default for wall torches, outside posts for lanterns
        int off = 2;
        int a0 = center - off;
        int a1 = center + off;
        if (onNorthSouth) {
            a0 = Math.max(2, Math.min(width - 3, a0));
            a1 = Math.max(2, Math.min(width - 3, a1));
        } else {
            a0 = Math.max(2, Math.min(depth - 3, a0));
            a1 = Math.max(2, Math.min(depth - 3, a1));
        }

        if ("lantern".equals(lightingType)) {
            // Outside posts: foundation base + fence + lantern
            placeLanternPost(blocks, origin, foundation, doorSide, a0, y, width, depth);
            if (a1 != a0) placeLanternPost(blocks, origin, foundation, doorSide, a1, y, width, depth);
        } else {
            // Wall torch: place at inside air cell and face toward the wall block.
            BlockState wallTorch = Blocks.WALL_TORCH.getDefaultState();
            wallTorch = withFacingIfPossible(wallTorch, doorSide);
            BlockPos p0 = doorTorchPos(origin, doorSide, a0, y, width, depth);
            blocks.add(new PlannedBlock(p0, wallTorch));
            if (a1 != a0) {
                BlockPos p1 = doorTorchPos(origin, doorSide, a1, y, width, depth);
                blocks.add(new PlannedBlock(p1, wallTorch));
            }
        }
    }
    
    private static void addPerimeterLighting(List<PlannedBlock> blocks, BlockPos origin, int width, int depth,
                                            Direction doorSide, String lightingType, int spacing) {
        // MVP: perimeter lighting only for torches (lantern perimeter is more intrusive and terrain-sensitive).
        if (!"torch".equals(lightingType)) return;
        BlockState wallTorch = Blocks.WALL_TORCH.getDefaultState();
        int y = 2;

        // north wall (inside z=1, facing NORTH to attach to z=0)
        wallTorch = withFacingIfPossible(wallTorch, Direction.NORTH);
        for (int x = 2; x <= width - 3; x += spacing) {
            if (doorSide == Direction.NORTH && isNearDoor(Direction.NORTH, x, 0, width, depth)) continue;
            blocks.add(new PlannedBlock(origin.add(x, y, 1), wallTorch));
        }
        // south wall (inside z=depth-2, facing SOUTH)
        wallTorch = withFacingIfPossible(Blocks.WALL_TORCH.getDefaultState(), Direction.SOUTH);
        for (int x = 2; x <= width - 3; x += spacing) {
            if (doorSide == Direction.SOUTH && isNearDoor(Direction.SOUTH, x, depth - 1, width, depth)) continue;
            blocks.add(new PlannedBlock(origin.add(x, y, depth - 2), wallTorch));
        }
        // west wall (inside x=1, facing WEST)
        wallTorch = withFacingIfPossible(Blocks.WALL_TORCH.getDefaultState(), Direction.WEST);
        for (int z = 2; z <= depth - 3; z += spacing) {
            if (doorSide == Direction.WEST && isNearDoor(Direction.WEST, 0, z, width, depth)) continue;
            blocks.add(new PlannedBlock(origin.add(1, y, z), wallTorch));
        }
        // east wall (inside x=width-2, facing EAST)
        wallTorch = withFacingIfPossible(Blocks.WALL_TORCH.getDefaultState(), Direction.EAST);
        for (int z = 2; z <= depth - 3; z += spacing) {
            if (doorSide == Direction.EAST && isNearDoor(Direction.EAST, width - 1, z, width, depth)) continue;
            blocks.add(new PlannedBlock(origin.add(width - 2, y, z), wallTorch));
        }
    }
    
    private static void addDoorBanners(List<PlannedBlock> blocks, BlockPos origin, int width, int depth,
                                      Direction doorSide, String bannerColor, String paletteId, ServerWorld world) {
        // Place 1-2 wall banners on the inside face near the door side, attached to the wall.
        boolean onNorthSouth = (doorSide == Direction.NORTH || doorSide == Direction.SOUTH);
        int center = onNorthSouth ? (width / 2) : (depth / 2);
        int y = 3;
        int off = 3;
        int a0 = center - off;
        int a1 = center + off;
        if (onNorthSouth) {
            a0 = Math.max(2, Math.min(width - 3, a0));
            a1 = Math.max(2, Math.min(width - 3, a1));
        } else {
            a0 = Math.max(2, Math.min(depth - 3, a0));
            a1 = Math.max(2, Math.min(depth - 3, a1));
        }

        // Priority: explicit bannerColor > paletteId(BANNER) > red_wall_banner.
        BlockState banner;
        if (bannerColor != null && !bannerColor.isBlank()) {
            String c = bannerColor.trim().toLowerCase();
            String id = "minecraft:red_wall_banner";
            if (c.matches("^[a-z_]{3,20}$")) id = "minecraft:" + c + "_wall_banner";
            banner = PaletteResolver.stateFromId(world, id);
            if (banner == null) banner = PaletteResolver.stateFromId(world, "minecraft:red_wall_banner");
        } else if (paletteId != null && !paletteId.isBlank()) {
            // deterministic pick based on position
            BlockPos p0 = doorTorchPos(origin, doorSide, a0, y, width, depth);
            long salt = (p0.getX() * 31L) ^ (p0.getZ() * 17L) ^ (p0.getY() * 13L);
            banner = PaletteResolver.pick(world, paletteId, "BANNER", p0, salt,
                    PaletteResolver.stateFromId(world, "minecraft:red_wall_banner"));
        } else {
            banner = PaletteResolver.stateFromId(world, "minecraft:red_wall_banner");
        }
        if (banner == null) banner = Blocks.RED_WOOL.getDefaultState();
        banner = withFacingIfPossible(banner, doorSide);

        BlockPos p0 = doorTorchPos(origin, doorSide, a0, y, width, depth);
        blocks.add(new PlannedBlock(p0, banner));
        if (a1 != a0) {
            BlockPos p1 = doorTorchPos(origin, doorSide, a1, y, width, depth);
            blocks.add(new PlannedBlock(p1, banner));
        }
    }
    
    private static BlockPos doorTorchPos(BlockPos origin, Direction doorSide, int axis, int y, int width, int depth) {
        return switch (doorSide) {
            case NORTH -> origin.add(axis, y, 1);
            case SOUTH -> origin.add(axis, y, depth - 2);
            case WEST -> origin.add(1, y, axis);
            case EAST -> origin.add(width - 2, y, axis);
            default -> origin.add(axis, y, 1);
        };
    }

    private static void placeLanternPost(List<PlannedBlock> blocks, BlockPos origin, BlockState foundation,
                                        Direction doorSide, int axis, int lanternY, int width, int depth) {
        // outside coordinate (just outside wall), with a small post and lantern
        BlockPos base = switch (doorSide) {
            case NORTH -> origin.add(axis, 0, -1);
            case SOUTH -> origin.add(axis, 0, depth);
            case WEST -> origin.add(-1, 0, axis);
            case EAST -> origin.add(width, 0, axis);
            default -> origin.add(axis, 0, -1);
        };
        blocks.add(new PlannedBlock(base, foundation));
        blocks.add(new PlannedBlock(base.up(), Blocks.OAK_FENCE.getDefaultState()));
        blocks.add(new PlannedBlock(base.up(2), Blocks.LANTERN.getDefaultState()));
    }
    
    private static boolean isNearDoor(Direction doorSide, int x, int z, int width, int depth) {
        int cx = width / 2;
        int cz = depth / 2;
        if (doorSide == null) doorSide = Direction.NORTH;
        return switch (doorSide) {
            case NORTH -> (z == 0) && (x == cx || x == cx - 1);
            case SOUTH -> (z == depth - 1) && (x == cx || x == cx - 1);
            case WEST -> (x == 0) && (z == cz || z == cz - 1);
            case EAST -> (x == width - 1) && (z == cz || z == cz - 1);
            default -> (z == 0) && (x == cx || x == cx - 1);
        };
    }
    
    private static BlockState resolveWallBannerState(String color) {
        String c = (color == null) ? "" : color.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (c) {
            case "black" -> Blocks.BLACK_WALL_BANNER.getDefaultState();
            case "white" -> Blocks.WHITE_WALL_BANNER.getDefaultState();
            case "blue" -> Blocks.BLUE_WALL_BANNER.getDefaultState();
            case "green" -> Blocks.GREEN_WALL_BANNER.getDefaultState();
            case "yellow" -> Blocks.YELLOW_WALL_BANNER.getDefaultState();
            case "purple" -> Blocks.PURPLE_WALL_BANNER.getDefaultState();
            case "cyan" -> Blocks.CYAN_WALL_BANNER.getDefaultState();
            default -> Blocks.RED_WALL_BANNER.getDefaultState();
        };
    }
    
    private static BlockState pickCapitalBlock(BlockState roofSlab, BlockState entablatureBlock, String columnOrder) {
        // Best-effort: keep it simple and consistent with available materials.
        String o = (columnOrder == null) ? "" : columnOrder.trim().toLowerCase();
        if (o.contains("corinth")) {
            // "leafy" suggestion using entablature/trim when present
            return entablatureBlock != null ? entablatureBlock : roofSlab;
        }
        if (o.contains("ionic")) {
            return entablatureBlock != null ? entablatureBlock : roofSlab;
        }
        return roofSlab; // doric/simple
    }
    
    private static void placeColumn(List<PlannedBlock> blocks,
                                    BlockPos base,
                                    int colH,
                                    BlockState foundation,
                                    BlockState pillar,
                                    BlockState roofSlab,
                                    BlockState entablatureBlock,
                                    String columnOrder) {
        blocks.add(new PlannedBlock(base, foundation));
        for (int y = 1; y <= colH; y++) blocks.add(new PlannedBlock(base.up(y), pillar));
        blocks.add(new PlannedBlock(base.up(colH + 1), pickCapitalBlock(roofSlab, entablatureBlock, columnOrder)));
    }
}

