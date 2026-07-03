package com.formacraft.common.generation.structure;

import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.common.build.GeneratedStructure;
import com.formacraft.common.build.PlannedBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Properties;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.formacraft.common.model.build.BuildingStyle;
import com.formacraft.common.style.profile.StyleProfile;
import com.formacraft.common.style.profile.StyleProfileRegistry;
import com.formacraft.common.style.profile.DetailPreferences;

/**
 * 塔楼生成器
 * 支持 AI 生成的 BuildingSpec
 * 支持圆形塔楼（medieval / modern 都可）
 * 支持外墙、层高、窗户规则、楼板、内部楼梯、塔顶
 */
public class TowerGenerator implements StructureGenerator {
    
    @Override
    public GeneratedStructure generate(BuildingSpec spec, BlockPos origin, ServerWorld world) {
        List<PlannedBlock> result = new ArrayList<>();

        // 获取参数
        int radius = Math.max(3, spec.getFootprint() != null ? spec.getFootprint().getRadius() : 6);
        int height = Math.max(8, spec.getHeight());
        int floors = Math.max(1, spec.getFloors());

        // 获取材质
        BlockState wall = getState(world, spec.getMaterials() != null ? spec.getMaterials().getWall() : null);
        BlockState floor = getState(world, spec.getMaterials() != null ? spec.getMaterials().getFloor() : null);
        BlockState window = getState(world, spec.getMaterials() != null ? spec.getMaterials().getWindow() : null);
        BlockState roof = getState(world, spec.getMaterials() != null ? spec.getMaterials().getRoof() : null);

        // 获取特性
        boolean hasWindows = spec.getFeatures() != null && spec.getFeatures().hasWindows();
        boolean hasStairs = spec.getFeatures() != null && spec.getFeatures().hasStairs();
        
        // 获取风格选项（BuildingSpec 2.0）
        String roofType = spec.getStyleOptions() != null ?
            spec.getStyleOptions().getRoofType() : "";
        double windowRatio = spec.getStyleOptions() != null ? 
            spec.getStyleOptions().getWindowRatio() : 0.3;

        // Semantic features (Blueprint-driven, optional)
        Map<String, Object> extra = spec.getExtra();
        boolean battlements = getBool(extra, "battlements");
        boolean flag = getBool(extra, "flag");
        boolean banner = getBool(extra, "banner");
        String bannerColor = getStr(extra, "bannerColor", "red");
        String paletteId = getStr(extra, "paletteId", "");

        // StyleProfile hinting (for catalog-driven roof types like "cone"/"spires")
        BuildingStyle style = spec.getStyle() != null ? spec.getStyle() : BuildingStyle.DEFAULT;
        StyleProfile profile = StyleProfileRegistry.resolve(spec);
        DetailPreferences details = (profile != null) ? profile.details() : null;
        if ((paletteId == null || paletteId.isBlank()) && details != null && details.paletteId != null && !details.paletteId.isBlank()) {
            paletteId = details.paletteId.trim();
        }

        // windowStyle: StyleOptions > styleProfile.details.windowStyle > heuristic
        String effWindowStyle = resolveEffectiveWindowStyle(spec, details, style);
        window = resolveWindowBlock(world, effWindowStyle, window);

        // windowRatio: broad style-driven clamps (cross-style)
        String ews = effWindowStyle.trim().toLowerCase(java.util.Locale.ROOT);
        if (!ews.isBlank()) {
            if (ews.contains("curtain")) windowRatio = Math.max(windowRatio, 0.70);
            else if (ews.contains("slit") || ews.contains("bars")) windowRatio = Math.min(windowRatio, 0.14);
            else if (ews.contains("shoji")) windowRatio = Math.max(Math.min(windowRatio, 0.55), 0.32);
            else if (ews.contains("fence") || ews.contains("lattice")) windowRatio = Math.max(Math.min(windowRatio, 0.45), 0.22);
        }

        // eavesProfile -> battlements default (only when not explicitly provided)
        String eavesProfile = details != null ? details.eavesProfile : null;
        boolean battlementsExplicit = (extra != null && extra.containsKey("battlements"));
        if (!battlementsExplicit && eavesProfile != null && eavesProfile.toLowerCase(java.util.Locale.ROOT).contains("battlement")) {
            battlements = true;
        }

        // ornamentProfile -> banner/flag/signage defaults (only when not explicitly provided)
        String ornamentProfile = details != null ? details.ornamentProfile : null;
        boolean bannerExplicit = (extra != null && extra.containsKey("banner"));
        boolean flagExplicit = (extra != null && extra.containsKey("flag"));
        if (!bannerExplicit && ornamentProfile != null && ornamentProfile.contains("banner")) {
            banner = true;
            if (details.bannerColor != null && !details.bannerColor.isBlank()) {
                bannerColor = details.bannerColor;
            }
        }
        if (!flagExplicit && ornamentProfile != null && ornamentProfile.contains("cyber")) {
            // Cyber towers often read better with a top marker
            flag = true;
        }

        // 内部楼梯旋转方向偏移（螺旋楼梯）
        BlockPos[] spiralOffsets = {
                new BlockPos(1, 0, 0),
                new BlockPos(0, 0, 1),
                new BlockPos(-1, 0, 0),
                new BlockPos(0, 0, -1)
        };

        int stairIndex = 0;
        int floorHeight = height / floors;

        // 逐层生成
        for (int y = 0; y < height; y++) {
            // 生成外墙（圆形）
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    double dist = Math.sqrt(x * x + z * z);
                    BlockPos pos = origin.add(x, y, z);

                    // 外圈：墙（厚度为 1 方块）
                    if (dist >= radius - 0.5 && dist <= radius + 0.5) {
                        // 根据 windowRatio 决定是否开窗
                        boolean shouldPlaceWindow = false;
                        if (hasWindows && y % 4 == 2) {
                            if (windowRatio >= 0.5) {
                                // 高比例：每隔 2 格开窗
                                shouldPlaceWindow = (Math.abs(x) % 2 == 0 || Math.abs(z) % 2 == 0);
                            } else if (windowRatio >= 0.3) {
                                // 中等比例：每隔 4 格开窗
                                shouldPlaceWindow = (Math.abs(x) % 4 == 0 || Math.abs(z) % 4 == 0);
                            } else {
                                // 低比例：每隔 6 格开窗
                                shouldPlaceWindow = (Math.abs(x) % 6 == 0 || Math.abs(z) % 6 == 0);
                            }
                        }
                        
                        if (shouldPlaceWindow) {
                            result.add(new PlannedBlock(pos, window));
                        } else {
                            result.add(new PlannedBlock(pos, wall));
                        }
                    }

                    // 内部填充为空气（避免打到山体）
                    if (dist < radius - 1) {
                        result.add(new PlannedBlock(pos, Blocks.AIR.getDefaultState()));
                    }
                }
            }

            // 每层楼板（除了最底层）
            if (y > 0 && y % floorHeight == 0) {
                for (int fx = -radius + 1; fx < radius; fx++) {
                    for (int fz = -radius + 1; fz < radius; fz++) {
                        double dist = Math.sqrt(fx * fx + fz * fz);
                        if (dist < radius - 1) {
                            result.add(new PlannedBlock(origin.add(fx, y, fz), floor));
                        }
                    }
                }
            }

            // 螺旋楼梯（每层 4 级，形成螺旋）
            if (hasStairs && y < height - 1) {
                BlockPos stairPos = origin.add(spiralOffsets[stairIndex % 4]);
                // 使用楼梯方块，需要设置正确的朝向
                BlockState stairState = Blocks.OAK_STAIRS.getDefaultState();
                result.add(new PlannedBlock(stairPos.add(0, y, 0), stairState));
                stairIndex++;
            }
        }

        // 顶部：根据 roofType 生成屋顶
        boolean hasRoof = spec.getFeatures() != null && spec.getFeatures().hasRoof();
        
        if (hasRoof) {
            // 从 styleOptions 获取屋顶类型（向后兼容 extra）
            String actualRoofType = roofType;
            if (actualRoofType == null || actualRoofType.isEmpty()) {
                if (spec.getExtra() != null && spec.getExtra().containsKey("roofType")) {
                    actualRoofType = String.valueOf(spec.getExtra().get("roofType"));
                } else {
                    // Prefer catalog style hint, otherwise default by style
                    if (profile != null && profile.rules() != null && profile.rules().roofTypeHint != null && !profile.rules().roofTypeHint.isBlank()) {
                        actualRoofType = profile.rules().roofTypeHint;
                    } else {
                        actualRoofType = (style == BuildingStyle.MEDIEVAL) ? "cone" : "flat";
                    }
                }
            }
            if ("hip".equalsIgnoreCase(actualRoofType)) actualRoofType = "hipped";
            
            if ("cone".equalsIgnoreCase(actualRoofType)) {
                // 锥形屋顶：从顶部向下逐渐缩小
                int roofHeight = Math.min(radius, 5); // 屋顶高度不超过半径或 5 格
                for (int roofY = 0; roofY < roofHeight; roofY++) {
                    int currentRadius = radius - roofY;
                    for (int x = -currentRadius; x <= currentRadius; x++) {
                        for (int z = -currentRadius; z <= currentRadius; z++) {
                            double dist = Math.sqrt(x * x + z * z);
                            if (dist <= currentRadius + 0.3) {
                                result.add(new PlannedBlock(origin.add(x, height + roofY, z), roof));
                            }
                        }
                    }
                }
            } else if ("pyramid".equalsIgnoreCase(actualRoofType) || "hipped".equalsIgnoreCase(actualRoofType)) {
                // Stepped "pyramid" cap: square-ish taper for a distinct silhouette (cheap & stable)
                int roofH = Math.min(radius, 6);
                for (int i = 0; i < roofH; i++) {
                    int r = Math.max(1, radius - i);
                    int y = height + i;
                    for (int x = -r; x <= r; x++) {
                        for (int z = -r; z <= r; z++) {
                            if (Math.abs(x) == r || Math.abs(z) == r) {
                                result.add(new PlannedBlock(origin.add(x, y, z), roof));
                            }
                        }
                    }
                }
            } else if ("spires".equalsIgnoreCase(actualRoofType) || "spire".equalsIgnoreCase(actualRoofType)) {
                // Gothic-ish spire: taller cone
                int roofHeight = Math.min(Math.max(6, radius + 2), 12);
                for (int roofY = 0; roofY < roofHeight; roofY++) {
                    int currentRadius = Math.max(1, radius - (roofY / 2));
                    for (int x = -currentRadius; x <= currentRadius; x++) {
                        for (int z = -currentRadius; z <= currentRadius; z++) {
                            double dist = Math.sqrt(x * x + z * z);
                            if (dist <= currentRadius + 0.2) {
                                result.add(new PlannedBlock(origin.add(x, height + roofY, z), roof));
                            }
                        }
                    }
                }
            } else {
                // 平顶
                for (int x = -radius; x <= radius; x++) {
                    for (int z = -radius; z <= radius; z++) {
                        double dist = Math.sqrt(x * x + z * z);
                        if (dist <= radius + 0.3) {
                            result.add(new PlannedBlock(origin.add(x, height, z), roof));
                        }
                    }
                }
            }
        }

        // Battlements: a crenel ring above the roof line.
        if (battlements) {
            int by = height + 1;
            BlockState crenel = Blocks.STONE_BRICK_WALL.getDefaultState();
            // Use radius ring at 45-degree-ish sampling (cheap & recognizable).
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    double dist = Math.sqrt(x * x + z * z);
                    if (dist < radius - 0.4 || dist > radius + 0.7) continue;
                    // alternate to avoid dense walls
                    if (((Math.abs(x) + Math.abs(z)) & 1) == 0) {
                        result.add(new PlannedBlock(origin.add(x, by, z), crenel));
                    }
                }
            }
        }

        // Flag: a small marker near the top (safe vanilla blocks).
        if (flag) {
            BlockPos poleBase = origin.add(0, height + 2, 0);
            result.add(new PlannedBlock(poleBase, Blocks.OAK_FENCE.getDefaultState()));
            // flag cloth
            result.add(new PlannedBlock(poleBase.add(1, 0, 0), Blocks.RED_WOOL.getDefaultState()));
            // small light
            result.add(new PlannedBlock(poleBase.add(0, -1, 0), Blocks.LANTERN.getDefaultState()));
        }

        // Banner: crest on the outer face at cardinal points (optional).
        if (banner) {
            addTopBanners(result, origin, world, radius, height, bannerColor, paletteId);
        }

        // Extra ornaments on towers (cross-style, best-effort)
        if (ornamentProfile != null && !ornamentProfile.isBlank()) {
            addTowerOrnaments(result, origin, world, radius, height, ornamentProfile, paletteId);
        }

        String description = String.format("Tower (%s, height=%d, radius=%d, floors=%d)", 
                spec.getType(), height, radius, floors);

        return new GeneratedStructure(
                null, // owner 将在 BuildExecutionService 中设置
                origin,
                description,
                result
        );
    }

    private static String resolveEffectiveWindowStyle(BuildingSpec spec, DetailPreferences details, BuildingStyle style) {
        String ws = (spec != null && spec.getStyleOptions() != null) ? spec.getStyleOptions().getWindowStyle() : null;
        if (ws == null || ws.isBlank()) {
            ws = (details != null) ? details.windowStyle : null;
        }
        if (ws == null || ws.isBlank()) {
            ws = switch (style) {
                case ASIAN -> "fence";
                case MEDIEVAL -> "bars";
                default -> "pane";
            };
        }
        return ws;
    }

    private static BlockState resolveWindowBlock(ServerWorld world, String windowStyle, BlockState fallback) {
        String ws = (windowStyle == null) ? "" : windowStyle.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (ws) {
            case "shoji", "paper" -> Blocks.WHITE_STAINED_GLASS_PANE.getDefaultState();
            case "fence", "lattice" -> Blocks.OAK_FENCE.getDefaultState();
            case "bars", "iron_bars", "slit" -> Blocks.IRON_BARS.getDefaultState();
            case "stained" -> Blocks.LIGHT_BLUE_STAINED_GLASS_PANE.getDefaultState();
            case "curtain_wall", "curtain" -> Blocks.GLASS_PANE.getDefaultState();
            default -> (fallback != null ? fallback : Blocks.GLASS_PANE.getDefaultState());
        };
    }

    private static void addTowerOrnaments(List<PlannedBlock> out,
                                          BlockPos origin,
                                          ServerWorld world,
                                          int radius,
                                          int height,
                                          String ornamentProfile,
                                          String paletteId) {
        if (out == null || origin == null || ornamentProfile == null) return;
        String op = ornamentProfile.trim().toLowerCase(java.util.Locale.ROOT);
        int y = Math.max(2, height - 2);

        if (op.contains("cyber") || op.contains("sign")) {
            // Place 4 wall signs on the tower, facing outward (best-effort)
            BlockState sign = Blocks.DARK_OAK_WALL_SIGN.getDefaultState();
            if (paletteId != null && !paletteId.isBlank() && world != null) {
                sign = com.formacraft.server.material.PaletteResolver.pick(world, paletteId, "ROAD_SIGNAGE", origin, 0x70A001L, sign);
                sign = com.formacraft.server.material.PaletteResolver.pick(world, paletteId, "DECOR_DETAIL", origin, 0x70A002L, sign);
            }
            placeFacing(out, origin.add(radius + 1, y, 0), sign, Direction.EAST);
            placeFacing(out, origin.add(-radius - 1, y, 0), sign, Direction.WEST);
            placeFacing(out, origin.add(0, y, radius + 1), sign, Direction.SOUTH);
            placeFacing(out, origin.add(0, y, -radius - 1), sign, Direction.NORTH);
            return;
        }

        if (op.contains("steam") || op.contains("pipe")) {
            // A simple copper pipe column outside + a chimney hint
            BlockState pipe = Blocks.COPPER_BLOCK.getDefaultState();
            BlockPos base = origin.add(radius + 1, 0, 0);
            for (int i = 1; i <= Math.min(height, 10); i++) {
                out.add(new PlannedBlock(base.up(i), pipe));
            }
            out.add(new PlannedBlock(origin.add(radius - 1, height + 1, 0), Blocks.CAMPFIRE.getDefaultState()));
            return;
        }

        if (op.contains("organic") || op.contains("lantern") || op.contains("vine")) {
            BlockState leaf = Blocks.OAK_LEAVES.getDefaultState();
            BlockState lantern = Blocks.LANTERN.getDefaultState();
            if (paletteId != null && !paletteId.isBlank() && world != null) {
                leaf = com.formacraft.server.material.PaletteResolver.pick(world, paletteId, "DECOR_DETAIL", origin, 0x70A003L, leaf);
                lantern = com.formacraft.server.material.PaletteResolver.pick(world, paletteId, "LIGHTING", origin, 0x70A004L, lantern);
                lantern = com.formacraft.server.material.PaletteResolver.pick(world, paletteId, "ROAD_LIGHT", origin, 0x70A005L, lantern);
            }
            out.add(new PlannedBlock(origin.add(radius + 1, y, 0), leaf));
            out.add(new PlannedBlock(origin.add(-radius - 1, y, 0), leaf));
            out.add(new PlannedBlock(origin.add(0, y + 1, radius + 1), lantern));
        }
    }

    private static void placeFacing(List<PlannedBlock> out, BlockPos pos, BlockState state, Direction facing) {
        BlockState st = state;
        if (st.contains(Properties.HORIZONTAL_FACING)) {
            st = st.with(Properties.HORIZONTAL_FACING, facing);
        }
        out.add(new PlannedBlock(pos, st));
    }

    private static boolean getBool(Map<String, Object> extra, String key) {
        if (extra == null) return false;
        Object v = extra.get(key);
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        String s = String.valueOf(v).trim().toLowerCase();
        if (s.isEmpty()) return false;
        return s.equals("true") || s.equals("1") || s.equals("yes") || s.equals("y") || s.equals("on");
    }

    private static String getStr(Map<String, Object> extra, String key, String def) {
        if (extra == null) return def;
        Object v = extra.get(key);
        if (v == null) return def;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? def : s;
    }

    private static void addTopBanners(List<PlannedBlock> out, BlockPos origin, ServerWorld world,
                                      int radius, int height, String bannerColor, String paletteId) {
        int y = Math.max(2, height - 1);
        // Priority: explicit bannerColor > paletteId(BANNER) > red_wall_banner.
        BlockPos pickPos = origin.add(radius + 1, y, 0);
        BlockState banner;
        if (bannerColor != null && !bannerColor.isBlank()) {
            String c = bannerColor.trim().toLowerCase();
            String id = "minecraft:red_wall_banner";
            if (c.matches("^[a-z_]{3,20}$")) id = "minecraft:" + c + "_wall_banner";
            banner = com.formacraft.server.material.PaletteResolver.stateFromId(world, id);
            if (banner == null) banner = com.formacraft.server.material.PaletteResolver.stateFromId(world, "minecraft:red_wall_banner");
        } else if (paletteId != null && !paletteId.isBlank()) {
            long salt = (pickPos.getX() * 31L) ^ (pickPos.getZ() * 17L) ^ (pickPos.getY() * 13L);
            banner = com.formacraft.server.material.PaletteResolver.pick(world, paletteId, "BANNER", pickPos, salt,
                    com.formacraft.server.material.PaletteResolver.stateFromId(world, "minecraft:red_wall_banner"));
        } else {
            banner = com.formacraft.server.material.PaletteResolver.stateFromId(world, "minecraft:red_wall_banner");
        }
        if (banner == null) banner = Blocks.RED_WOOL.getDefaultState();

        // East
        placeBanner(out, origin.add(radius + 1, y, 0), banner, Direction.EAST);
        // West
        placeBanner(out, origin.add(-radius - 1, y, 0), banner, Direction.WEST);
        // South
        placeBanner(out, origin.add(0, y, radius + 1), banner, Direction.SOUTH);
        // North
        placeBanner(out, origin.add(0, y, -radius - 1), banner, Direction.NORTH);
    }

    private static void placeBanner(List<PlannedBlock> out, BlockPos pos, BlockState banner, Direction facing) {
        BlockState st = banner;
        if (st.contains(Properties.HORIZONTAL_FACING)) {
            st = st.with(Properties.HORIZONTAL_FACING, facing);
        }
        out.add(new PlannedBlock(pos, st));
    }

    /**
     * 将字符串 blockId 转为 BlockState（比如 "minecraft:stone_bricks"）
     */
    private BlockState getState(ServerWorld world, String id) {
        if (id == null || id.isEmpty()) {
            return Blocks.STONE.getDefaultState();
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

            // 如果找不到，尝试使用简单的字符串匹配作为回退
        } catch (Exception e) {
            // 如果解析失败，使用回退方案
            return resolveBlockFallback(id);
        }
    }

    /**
     * 回退方案：通过字符串匹配解析常用方块
     */
    private BlockState resolveBlockFallback(String material) {
        if (material == null) return Blocks.STONE.getDefaultState();
        
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
        
        // 默认返回石头
        return Blocks.STONE.getDefaultState();
    }
}
