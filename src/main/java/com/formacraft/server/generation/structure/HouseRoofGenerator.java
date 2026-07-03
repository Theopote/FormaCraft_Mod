package com.formacraft.server.generation.structure;

import com.formacraft.common.logging.FcaLog;
import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.common.model.build.BuildingStyle;
import com.formacraft.common.style.profile.StyleProfile;
import com.formacraft.common.build.PlannedBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.List;

/**
 * 房屋屋顶生成器
 * 
 * 负责生成各种类型的屋顶结构，包括：
 * - 四坡屋顶（hipped）
 * - 歇山顶（xie_shan）
 * - 双坡屋顶（gable）
 * - 尖塔屋顶（spires）
 * - 平顶（flat）
 * - 飞檐装饰
 * 
 * 从 HouseGenerator 中拆分出来，以提高代码可维护性。
 */
public class HouseRoofGenerator {

    private static final FcaLog LOG = FcaLog.of("HouseRoofGenerator");
    
    private HouseRoofGenerator() {} // Utility class
    
    /**
     * 生成屋顶
     * 
     * @param blocks 方块列表
     * @param origin 建筑原点
     * @param width 建筑宽度
     * @param depth 建筑深度
     * @param height 建筑高度
     * @param roofType 屋顶类型（hipped, xie_shan, gable, spires, flat等）
     * @param roof 屋顶主材质
     * @param roofStairs 屋顶楼梯材质
     * @param roofSlab 屋顶半砖材质
     * @param trim 装饰材质
     * @param style 建筑风格
     * @param profile 风格配置（可选）
     * @param doorSide 门朝向
     * @param spec 建筑规格（用于检查样式配置，可选）
     * @param paletteId 调色板ID（可选）
     */
    public static void generateRoof(
            List<PlannedBlock> blocks,
            BlockPos origin,
            int width,
            int depth,
            int height,
            String roofType,
            BlockState roof,
            BlockState roofStairs,
            BlockState roofSlab,
            BlockState trim,
            BuildingStyle style,
            StyleProfile profile,
            Direction doorSide,
            BuildingSpec spec,
            String paletteId) {
        
        if (blocks == null || origin == null || roofType == null) {
            return;
        }
        
        // 标准化屋顶类型
        String actualRoofType = normalizeRoofType(roofType, style, profile);
        
        // 根据屋顶类型生成
        if ("xie_shan".equalsIgnoreCase(actualRoofType) 
            || "hipped".equalsIgnoreCase(actualRoofType) 
            || "pyramid".equalsIgnoreCase(actualRoofType)) {
            
            boolean emphasizeEaves = (profile != null && profile.details() != null && profile.details().emphasizeEaves);
            boolean overhang = (style == BuildingStyle.ASIAN) || emphasizeEaves;
            
            if ("xie_shan".equalsIgnoreCase(actualRoofType)) {
                addXieShanRoof(blocks, origin, width, depth, height, roof, roofStairs, roofSlab, trim, overhang, emphasizeEaves);
            } else {
                addHippedRoof(blocks, origin, width, depth, height, roof, roofStairs, roofSlab, trim, overhang, emphasizeEaves);
            }
            
        } else if ("spires".equalsIgnoreCase(actualRoofType) || "spire".equalsIgnoreCase(actualRoofType)) {
            addSpireRoof(blocks, origin, width, depth, height, roof, roofStairs, roofSlab, trim);
            
        } else if ("gable".equalsIgnoreCase(actualRoofType)) {
            addGableRoof(blocks, origin, width, depth, height, roof, roofStairs, roofSlab, trim, 
                        doorSide, spec, paletteId, style, profile);
            
        } else {
            // 平顶（现代/未来风格）
            addFlatRoof(blocks, origin, width, depth, height, roof, trim, style);
        }
        
        // 檐口装饰（y=height-1 一圈 slab）
        addEavesBase(blocks, origin, width, depth, height, roofSlab);
        
        // 檐口轮廓
        String eavesProfile = null;
        try {
            if (profile != null && profile.details() != null) {
                eavesProfile = profile.details().eavesProfile;
            }
        } catch (Throwable ex) { LOG.debug("best-effort step failed", ex); }
        if (eavesProfile != null && !eavesProfile.isBlank()) {
            applyEavesProfile(blocks, origin, width, depth, height, style, eavesProfile, trim, roof, roofSlab);
        }
        
        // 额外一圈线脚（更有"屋檐层次"）：仅在"偏层次屋顶"的风格下启用
        if (height >= 4 && profile != null && profile.rules() != null && profile.rules().layeredRoof) {
            addLayeredRoofTrim(blocks, origin, width, depth, height, trim);
        }
        
        // 中式：简化斗拱/雀替（檐下 1 格）+ 彩画点缀
        if (style == BuildingStyle.ASIAN || (profile != null && profile.details() != null && profile.details().emphasizeEaves)) {
            addDougongAndPainting(blocks, origin, width, depth, height, trim);
        }
    }
    
    /**
     * 标准化屋顶类型
     */
    private static String normalizeRoofType(String roofType, BuildingStyle style, StyleProfile profile) {
        if (roofType == null || roofType.isBlank()) {
            return (style == BuildingStyle.ASIAN) ? "hipped" : "gable";
        }
        
        String rt = roofType.trim().toLowerCase(java.util.Locale.ROOT);
        if ("hip".equals(rt)) rt = "hipped";
        if (rt.equals("xie_shan") || rt.equals("xieshan") || rt.equals("xie-shan") || rt.equals("xie shan") || rt.contains("xie")) {
            rt = "xie_shan";
        }
        
        return rt;
    }
    
    /**
     * 生成双坡屋顶（gable）
     */
    private static void addGableRoof(List<PlannedBlock> blocks, BlockPos origin, int width, int depth, int height,
                                     BlockState roof, BlockState roofStairs, BlockState roofSlab, BlockState trim,
                                     Direction doorSide, BuildingSpec spec, String paletteId, BuildingStyle style, StyleProfile profile) {
        int roofHeight = Math.min(width / 2 + 1, 7);

        for (int i = 0; i < roofHeight; i++) {
            int rightX = width - 1 - i;
            if (i > rightX) break;

            for (int z = -1; z <= depth; z++) {
                // 左坡
                blocks.add(new PlannedBlock(origin.add(i, height + i, z), withFacingIfPossible(roofStairs, Direction.EAST)));
                // 右坡
                blocks.add(new PlannedBlock(origin.add(rightX, height + i, z), withFacingIfPossible(roofStairs, Direction.WEST)));
            }
        }

        // 屋脊：用 slab
        int ridgeY = height + roofHeight - 1;
        int midLeft = (width - 1) / 2;
        int midRight = width / 2;
        for (int z = -1; z <= depth; z++) {
            blocks.add(new PlannedBlock(origin.add(midLeft, ridgeY + 1, z), roofSlab));
            blocks.add(new PlannedBlock(origin.add(midRight, ridgeY + 1, z), roofSlab));
        }

        // Japanese Traditional: 唐破风（kara-hafu）MVP（只在门位于 NORTH/SOUTH 时添加到正面山墙）
        if (isJapaneseTraditionalStyle(spec, paletteId) && (doorSide == Direction.NORTH || doorSide == Direction.SOUTH)) {
            addKaraHafuGable(blocks, origin, width, depth, height, roofHeight, doorSide, roofSlab, trim);
        }

                // Huizhou (徽派) phenotype: 马头墙（阶梯状山墙）
                // Note: HorseHeadWallGenerator generation is handled by HouseGenerator after roof generation
                // because it needs wall/trim materials which are not available here
    }
    
    /**
     * 生成平顶
     */
    private static void addFlatRoof(List<PlannedBlock> blocks, BlockPos origin, int width, int depth, int height,
                                   BlockState roof, BlockState trim, BuildingStyle style) {
        // 平顶（现代/未来风格）：屋面 + 女儿墙边框
        for (int x = -1; x <= width; x++) {
            for (int z = -1; z <= depth; z++) {
                boolean edge = (x == -1 || x == width || z == -1 || z == depth);
                if (edge) {
                    blocks.add(new PlannedBlock(origin.add(x, height, z), trim));
                    blocks.add(new PlannedBlock(origin.add(x, height + 1, z), trim));
                } else {
                    blocks.add(new PlannedBlock(origin.add(x, height, z), roof));
                }
            }
        }

        // 现代风格：简单天窗（中间一条玻璃）
        if (style == BuildingStyle.MODERN || style == BuildingStyle.FUTURISTIC) {
            int midZ = depth / 2;
            for (int x = 2; x < width - 2; x++) {
                blocks.add(new PlannedBlock(origin.add(x, height, midZ), Blocks.GLASS.getDefaultState()));
            }
        }
    }
    
    /**
     * 添加檐口基础（y=height-1 一圈 slab）
     */
    private static void addEavesBase(List<PlannedBlock> blocks, BlockPos origin, int width, int depth, int height, BlockState roofSlab) {
        for (int x = -1; x <= width; x++) {
            blocks.add(new PlannedBlock(origin.add(x, height - 1, -1), roofSlab));
            blocks.add(new PlannedBlock(origin.add(x, height - 1, depth), roofSlab));
        }
        for (int z = -1; z <= depth; z++) {
            blocks.add(new PlannedBlock(origin.add(-1, height - 1, z), roofSlab));
            blocks.add(new PlannedBlock(origin.add(width, height - 1, z), roofSlab));
        }
    }
    
    /**
     * 添加层次屋顶线脚
     */
    private static void addLayeredRoofTrim(List<PlannedBlock> blocks, BlockPos origin, int width, int depth, int height, BlockState trim) {
        int y = height - 2;
        for (int x = -1; x <= width; x++) {
            blocks.add(new PlannedBlock(origin.add(x, y, -1), trim));
            blocks.add(new PlannedBlock(origin.add(x, y, depth), trim));
        }
        for (int z = -1; z <= depth; z++) {
            blocks.add(new PlannedBlock(origin.add(-1, y, z), trim));
            blocks.add(new PlannedBlock(origin.add(width, y, z), trim));
        }
    }
    
    // ========== 从 HouseGenerator 迁移的方法 ==========
    // 这些方法需要从 HouseGenerator 中复制过来
    
    private static BlockState withFacingIfPossible(BlockState state, Direction facing) {
        if (state == null) return null;
        try {
            if (state.contains(net.minecraft.state.property.Properties.HORIZONTAL_FACING)) {
                return state.with(net.minecraft.state.property.Properties.HORIZONTAL_FACING, facing);
            }
        } catch (Throwable ex) { LOG.debug("best-effort step failed", ex); }
        return state;
    }
    
    private static boolean isJapaneseTraditionalStyle(BuildingSpec spec, String paletteId) {
        try {
            if (spec != null && spec.getExtra() != null) {
                Object spid = spec.getExtra().get("styleProfileId");
                if (spid != null) {
                    String s = String.valueOf(spid).trim();
                    if (s.equals("Japanese_Traditional")) return true;
                }
            }
        } catch (Throwable ex) { LOG.debug("best-effort step failed", ex); }
        if (paletteId == null) return false;
        String p = paletteId.trim().toUpperCase(java.util.Locale.ROOT);
        return p.contains("JAPAN") || p.contains("JAPANESE");
    }
    
    /**
     * 官式四坡屋顶（近似庑殿/歇山的屋面表达）
     * - overhang: 出檐（扩大一圈）
     * - flyingEaves: 飞檐角（四角上翘）+ 更细檐口层次
     */
    private static void addHippedRoof(List<PlannedBlock> blocks, BlockPos o, int w, int d, int baseH,
                                      BlockState roofMain, BlockState roofStairs, BlockState roofSlab, BlockState trim,
                                      boolean overhang, boolean flyingEaves) {
        int ox = overhang ? -1 : 0;
        int oz = overhang ? -1 : 0;
        int ow = w + (overhang ? 2 : 0);
        int od = d + (overhang ? 2 : 0);

        // 更细的檐口层次：屋顶起坡之前先做两道"檐口线脚"（更像官式）
        if (overhang) {
            int y0 = baseH - 1;
            // 外圈：roofSlab（滴水线）
            for (int x = ox - 1; x <= ox + ow; x++) {
                blocks.add(new PlannedBlock(o.add(x, y0, oz - 1), roofSlab));
                blocks.add(new PlannedBlock(o.add(x, y0, oz + od), roofSlab));
            }
            for (int z = oz - 1; z <= oz + od; z++) {
                blocks.add(new PlannedBlock(o.add(ox - 1, y0, z), roofSlab));
                blocks.add(new PlannedBlock(o.add(ox + ow, y0, z), roofSlab));
            }
            // 内圈：trim（额枋/檐口线）
            for (int x = ox; x <= ox + ow - 1; x++) {
                blocks.add(new PlannedBlock(o.add(x, y0, oz), trim));
                blocks.add(new PlannedBlock(o.add(x, y0, oz + od - 1), trim));
            }
            for (int z = oz; z <= oz + od - 1; z++) {
                blocks.add(new PlannedBlock(o.add(ox, y0, z), trim));
                blocks.add(new PlannedBlock(o.add(ox + ow - 1, y0, z), trim));
            }
        }

        int layers = Math.min(Math.min(ow, od) / 2 + 1, 7);
        for (int i = 0; i < layers; i++) {
            int x0 = ox + i;
            int x1 = ox + ow - 1 - i;
            int z0 = oz + i;
            int z1 = oz + od - 1 - i;
            if (x0 > x1 || z0 > z1) break;

            int y = baseH + i;

            // 屋面边缘用 stairs，内部用 roofMain（近似瓦面层次）
            for (int x = x0; x <= x1; x++) {
                blocks.add(new PlannedBlock(o.add(x, y, z0), withFacingIfPossible(roofStairs, Direction.SOUTH)));
                blocks.add(new PlannedBlock(o.add(x, y, z1), withFacingIfPossible(roofStairs, Direction.NORTH)));
            }
            for (int z = z0; z <= z1; z++) {
                blocks.add(new PlannedBlock(o.add(x0, y, z), withFacingIfPossible(roofStairs, Direction.EAST)));
                blocks.add(new PlannedBlock(o.add(x1, y, z), withFacingIfPossible(roofStairs, Direction.WEST)));
            }

            for (int x = x0 + 1; x <= x1 - 1; x++) {
                for (int z = z0 + 1; z <= z1 - 1; z++) {
                    blocks.add(new PlannedBlock(o.add(x, y, z), roofMain));
                }
            }

            // 檐口收边（每层下沿一圈 trim）
            if (i == 0 && overhang) {
                for (int x = x0; x <= x1; x++) {
                    blocks.add(new PlannedBlock(o.add(x, baseH - 1, z0), trim));
                    blocks.add(new PlannedBlock(o.add(x, baseH - 1, z1), trim));
                }
                for (int z = z0; z <= z1; z++) {
                    blocks.add(new PlannedBlock(o.add(x0, baseH - 1, z), trim));
                    blocks.add(new PlannedBlock(o.add(x1, baseH - 1, z), trim));
                }
            }
        }

        // 顶部封顶
        int topY = baseH + layers;
        int cx = ox + ow / 2;
        int cz = oz + od / 2;
        blocks.add(new PlannedBlock(o.add(cx, topY, cz), roofSlab));

        // 飞檐角：四角外挑 + 上翘（用 slab 逐级抬升，稳定且不会出现"空洞"）
        if (flyingEaves && overhang) {
            addFlyingEavesCorners(blocks, o, baseH, ox, oz, ow, od, roofSlab);
        }
    }
    
    /**
     * 歇山顶（hip-and-gable, MVP）
     * 在四坡屋顶基础上"收短屋脊 + 两端斜收"，形成明显区别于纯四坡的轮廓。
     */
    private static void addXieShanRoof(List<PlannedBlock> blocks, BlockPos o, int w, int d, int baseH,
                                       BlockState roofMain, BlockState roofStairs, BlockState roofSlab, BlockState trim,
                                       boolean overhang, boolean flyingEaves) {
        int ox = overhang ? -1 : 0;
        int oz = overhang ? -1 : 0;
        int ow = w + (overhang ? 2 : 0);
        int od = d + (overhang ? 2 : 0);

        // reuse the same eaves/cornice layering as hipped roof for consistency
        if (overhang) {
            int y0 = baseH - 1;
            for (int x = ox - 1; x <= ox + ow; x++) {
                blocks.add(new PlannedBlock(o.add(x, y0, oz - 1), roofSlab));
                blocks.add(new PlannedBlock(o.add(x, y0, oz + od), roofSlab));
            }
            for (int z = oz - 1; z <= oz + od; z++) {
                blocks.add(new PlannedBlock(o.add(ox - 1, y0, z), roofSlab));
                blocks.add(new PlannedBlock(o.add(ox + ow, y0, z), roofSlab));
            }
            for (int x = ox; x <= ox + ow - 1; x++) {
                blocks.add(new PlannedBlock(o.add(x, y0, oz), trim));
                blocks.add(new PlannedBlock(o.add(x, y0, oz + od - 1), trim));
            }
            for (int z = oz; z <= oz + od - 1; z++) {
                blocks.add(new PlannedBlock(o.add(ox, y0, z), trim));
                blocks.add(new PlannedBlock(o.add(ox + ow - 1, y0, z), trim));
            }
        }

        // Roof height: similar to gable, but capped for stability
        int layers = Math.min(Math.min(ow, od) / 2 + 1, 8);
        int capDepth = Math.max(2, Math.min(4, layers)); // how much to "hip" the gable ends

        // Build per-Z slices: full height in the middle, reduced height near both ends -> creates hip ends
        for (int z = oz; z <= oz + od - 1; z++) {
            int distToEnd = Math.min(z - oz, (oz + od - 1) - z);
            int reduce = Math.max(0, (capDepth - 1) - distToEnd);
            int hSlice = layers - reduce;
            if (hSlice <= 0) continue;

            for (int i = 0; i < hSlice; i++) {
                int x0 = ox + i;
                int x1 = ox + ow - 1 - i;
                if (x0 > x1) break;
                int y = baseH + i;

                // side slopes (along X) using stairs; these define the main gable silhouette
                blocks.add(new PlannedBlock(o.add(x0, y, z), withFacingIfPossible(roofStairs, Direction.EAST)));
                blocks.add(new PlannedBlock(o.add(x1, y, z), withFacingIfPossible(roofStairs, Direction.WEST)));

                // fill between slopes for this slice
                for (int x = x0 + 1; x <= x1 - 1; x++) {
                    blocks.add(new PlannedBlock(o.add(x, y, z), roofMain));
                }
            }

            // ridge line: shorter at ends due to reduced hSlice -> distinctive xie-shan roof ridge
            int ridgeY = baseH + hSlice;
            int midLeft = ox + (ow - 1) / 2;
            int midRight = ox + (ow / 2);
            blocks.add(new PlannedBlock(o.add(midLeft, ridgeY, z), roofSlab));
            blocks.add(new PlannedBlock(o.add(midRight, ridgeY, z), roofSlab));
        }

        // flying eaves corners: reuse existing horn for imperial silhouette emphasis
        if (flyingEaves && overhang) {
            addFlyingEavesCorners(blocks, o, baseH, ox, oz, ow, od, roofSlab);
        }
    }
    
    /**
     * Spire roof (MVP):
     * - Build a steep gable as base.
     * - Add 4 corner spires for a gothic silhouette.
     */
    private static void addSpireRoof(List<PlannedBlock> blocks, BlockPos origin, int width, int depth, int height,
                                     BlockState roofMain, BlockState roofStairs, BlockState roofSlab, BlockState trim) {
        int roofHeight = Math.min(Math.min(width, depth) / 2 + 2, 9);
        // Steep gable (along X)
        for (int i = 0; i < roofHeight; i++) {
            int rightX = width - 1 - i;
            if (i > rightX) break;
            for (int z = -1; z <= depth; z++) {
                blocks.add(new PlannedBlock(origin.add(i, height + i, z), withFacingIfPossible(roofStairs, Direction.EAST)));
                blocks.add(new PlannedBlock(origin.add(rightX, height + i, z), withFacingIfPossible(roofStairs, Direction.WEST)));
            }
        }
        // Ridge spike line (slab + trim)
        int ridgeY = height + roofHeight - 1;
        int midLeft = (width - 1) / 2;
        int midRight = width / 2;
        for (int z = -1; z <= depth; z++) {
            blocks.add(new PlannedBlock(origin.add(midLeft, ridgeY + 1, z), roofSlab));
            blocks.add(new PlannedBlock(origin.add(midRight, ridgeY + 1, z), roofSlab));
            if ((z & 1) == 0) {
                blocks.add(new PlannedBlock(origin.add(midLeft, ridgeY + 2, z), trim));
            }
        }

        // Corner spires (small vertical tapers)
        int spireBaseY = height + 1;
        int spireH = Math.max(4, Math.min(10, roofHeight + 1));
        addCornerSpire(blocks, origin.add(0, spireBaseY, 0), spireH, roofMain, trim);
        addCornerSpire(blocks, origin.add(width - 1, spireBaseY, 0), spireH, roofMain, trim);
        addCornerSpire(blocks, origin.add(0, spireBaseY, depth - 1), spireH, roofMain, trim);
        addCornerSpire(blocks, origin.add(width - 1, spireBaseY, depth - 1), spireH, roofMain, trim);
    }
    
    private static void addCornerSpire(List<PlannedBlock> blocks, BlockPos base, int h, BlockState body, BlockState tip) {
        for (int i = 0; i < h; i++) {
            int r = Math.max(0, 1 - (i / 3)); // taper quickly (2x2 then 1x1)
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    BlockPos p = base.add(dx, i, dz);
                    blocks.add(new PlannedBlock(p, (i == h - 1) ? tip : body));
                }
            }
        }
    }
    
    /**
     * 唐破风（kara-hafu）MVP：在正面山墙做一个"拱形/曲线"屋檐轮廓。
     */
    private static void addKaraHafuGable(List<PlannedBlock> blocks, BlockPos origin,
                                         int width, int depth, int height, int roofHeight,
                                         Direction doorSide, BlockState roofSlab, BlockState trim) {
        if (blocks == null || origin == null) return;
        if (width < 9 || depth < 7) return;
        if (doorSide == null) doorSide = Direction.SOUTH;
        if (roofSlab == null) roofSlab = Blocks.STONE_BRICK_SLAB.getDefaultState();
        if (trim == null) trim = Blocks.STONE_BRICKS.getDefaultState();

        // Front gable plane: z=-1 for NORTH, z=depth for SOUTH (matches roof overhang loops)
        int zFront = (doorSide == Direction.NORTH) ? -1 : depth;
        int yBase = height + Math.max(1, roofHeight / 2);
        int yPeak = height + roofHeight + 2;

        int mid = width / 2;
        int span = Math.min(6, Math.max(4, width / 3));
        int x0 = Math.max(1, mid - span);
        int x1 = Math.min(width - 2, mid + span);

        // build a stepped "arc": higher in the center, lower toward sides
        for (int x = x0; x <= x1; x++) {
            int dx = Math.abs(x - mid);
            int y = yPeak - (dx / 2); // pseudo curve
            y = Math.max(yBase, Math.min(yPeak, y));
            blocks.add(new PlannedBlock(origin.add(x, y, zFront), roofSlab));
            // add a small vertical trim drop to emphasize the curve silhouette
            if ((dx & 1) == 0) {
                blocks.add(new PlannedBlock(origin.add(x, y - 1, zFront), trim));
            }
        }

        // center pendant (like a small ridge ornament)
        blocks.add(new PlannedBlock(origin.add(mid, yPeak - 1, zFront), trim));
    }
    
    private static void addFlyingEavesCorners(List<PlannedBlock> blocks, BlockPos o, int baseH,
                                              int ox, int oz, int ow, int od, BlockState roofSlab) {
        // 四角坐标（以 overhang 扩展后的外轮廓为基准）
        int x1 = ox + ow - 1;
        int z1 = oz + od - 1;

        // 每个角向外延伸 2 格，并逐级抬升 2 格
        // 角1：(-,-)
        addCornerHorn(blocks, o, ox, oz, -1, -1, baseH, roofSlab);
        // 角2：(+, -)
        addCornerHorn(blocks, o, x1, oz, +1, -1, baseH, roofSlab);
        // 角3：(-, +)
        addCornerHorn(blocks, o, ox, z1, -1, +1, baseH, roofSlab);
        // 角4：(+, +)
        addCornerHorn(blocks, o, x1, z1, +1, +1, baseH, roofSlab);
    }

    private static void addCornerHorn(List<PlannedBlock> blocks, BlockPos o,
                                      int cx, int cz, int sx, int sz, int baseH, BlockState slab) {
        // level 0：角点本身稍微抬起一点（让角更"翘"）
        blocks.add(new PlannedBlock(o.add(cx, baseH + 1, cz), slab));

        // level 1：向外 1 格（对角），抬升 1
        blocks.add(new PlannedBlock(o.add(cx + sx, baseH + 2, cz), slab));
        blocks.add(new PlannedBlock(o.add(cx, baseH + 2, cz + sz), slab));
        blocks.add(new PlannedBlock(o.add(cx + sx, baseH + 2, cz + sz), slab));

        // level 2：向外 2 格（对角），再抬升 1
        blocks.add(new PlannedBlock(o.add(cx + sx * 2, baseH + 3, cz + sz), slab));
        blocks.add(new PlannedBlock(o.add(cx + sx, baseH + 3, cz + sz * 2), slab));
        blocks.add(new PlannedBlock(o.add(cx + sx * 2, baseH + 3, cz + sz * 2), slab));
    }
    
    /**
     * 应用檐口轮廓配置
     */
    private static void applyEavesProfile(List<PlannedBlock> blocks,
                                          BlockPos origin,
                                          int width,
                                          int depth,
                                          int height,
                                          BuildingStyle style,
                                          String eavesProfile,
                                          BlockState trim,
                                          BlockState roof,
                                          BlockState roofSlab) {
        if (blocks == null || origin == null || eavesProfile == null) return;
        String ep = eavesProfile.trim().toLowerCase(java.util.Locale.ROOT);
        if (trim == null) trim = Blocks.STONE_BRICKS.getDefaultState();
        if (roof == null) roof = trim;
        if (roofSlab == null) roofSlab = trim;

        // battlement: simple crenels on the very top ring for defensive silhouettes
        if (ep.contains("battlement")) {
            int y = height + 1;
            for (int x = -1; x <= width; x++) {
                if ((x & 1) == 0) {
                    blocks.add(new PlannedBlock(origin.add(x, y, -1), trim));
                    blocks.add(new PlannedBlock(origin.add(x, y, depth), trim));
                }
            }
            for (int z = -1; z <= depth; z++) {
                if ((z & 1) == 0) {
                    blocks.add(new PlannedBlock(origin.add(-1, y, z), trim));
                    blocks.add(new PlannedBlock(origin.add(width, y, z), trim));
                }
            }
            return;
        }

        // neon strip: use trim material as a "light band" (cyberpunk often uses stained-glass trim)
        if (ep.contains("neon")) {
            for (int x = -1; x <= width; x++) {
                blocks.add(new PlannedBlock(origin.add(x, height, -1), trim));
                blocks.add(new PlannedBlock(origin.add(x, height, depth), trim));
            }
            for (int z = -1; z <= depth; z++) {
                blocks.add(new PlannedBlock(origin.add(-1, height, z), trim));
                blocks.add(new PlannedBlock(origin.add(width, height, z), trim));
            }
            return;
        }

        // parapet: emphasize flat-roof edge ring by extending it one more layer
        if (ep.contains("parapet")) {
            int y = height + 2;
            for (int x = -1; x <= width; x++) {
                blocks.add(new PlannedBlock(origin.add(x, y, -1), trim));
                blocks.add(new PlannedBlock(origin.add(x, y, depth), trim));
            }
            for (int z = -1; z <= depth; z++) {
                blocks.add(new PlannedBlock(origin.add(-1, y, z), trim));
                blocks.add(new PlannedBlock(origin.add(width, y, z), trim));
            }
            return;
        }

        // organic vines: a soft edge band with leaves (best-effort, non-invasive)
        if (ep.contains("vine") || ep.contains("organic")) {
            int y = height - 1;
            BlockState leaf = Blocks.OAK_LEAVES.getDefaultState();
            for (int x = -1; x <= width; x++) {
                if ((x & 1) == 0) {
                    blocks.add(new PlannedBlock(origin.add(x, y, -2), leaf));
                    blocks.add(new PlannedBlock(origin.add(x, y, depth + 1), leaf));
                }
            }
            for (int z = -1; z <= depth; z++) {
                if ((z & 1) == 0) {
                    blocks.add(new PlannedBlock(origin.add(-2, y, z), leaf));
                    blocks.add(new PlannedBlock(origin.add(width + 1, y, z), leaf));
                }
            }
            return;
        }

        // flying eaves: add an extra slab ring one block further out for stronger silhouette
        if (ep.contains("flying")) {
            int y = height - 1;
            for (int x = -2; x <= width + 1; x++) {
                blocks.add(new PlannedBlock(origin.add(x, y, -2), roofSlab));
                blocks.add(new PlannedBlock(origin.add(x, y, depth + 1), roofSlab));
            }
            for (int z = -2; z <= depth + 1; z++) {
                blocks.add(new PlannedBlock(origin.add(-2, y, z), roofSlab));
                blocks.add(new PlannedBlock(origin.add(width + 1, y, z), roofSlab));
            }
        }
    }
    
    /**
     * 中式：简化斗拱/雀替（檐下 1 格）+ 彩画点缀
     */
    private static void addDougongAndPainting(List<PlannedBlock> blocks, BlockPos origin, int width, int depth, int height, BlockState trim) {
        // 檐下"斗拱"简化：每隔 2 格在外墙下沿挂一块（stairs/slab/栅栏都可），这里用 trapdoor/柱材太花，先用 trim
        int y = height - 2;
        for (int x = 0; x < width; x += 2) {
            blocks.add(new PlannedBlock(origin.add(x, y, -1), trim));
            blocks.add(new PlannedBlock(origin.add(x, y, depth), trim));
        }
        for (int z = 0; z < depth; z += 2) {
            blocks.add(new PlannedBlock(origin.add(-1, y, z), trim));
            blocks.add(new PlannedBlock(origin.add(width, y, z), trim));
        }

        // 彩画点缀：檐下用少量绿/蓝，避免过花
        for (int x = 1; x < width - 1; x += 3) {
            blocks.add(new PlannedBlock(origin.add(x, height - 2, -1), Blocks.GREEN_TERRACOTTA.getDefaultState()));
        }
        for (int z = 1; z < depth - 1; z += 3) {
            blocks.add(new PlannedBlock(origin.add(width, height - 2, z), Blocks.BLUE_TERRACOTTA.getDefaultState()));
        }
    }
}

