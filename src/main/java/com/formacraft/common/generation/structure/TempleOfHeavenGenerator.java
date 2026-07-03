package com.formacraft.common.generation.structure;

import com.formacraft.common.generation.structure.util.StructureSpecParsers;
import com.formacraft.common.logging.FcaLog;
import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.common.model.build.BuildingStyle;
import com.formacraft.common.skeleton.radial.RadialPlan;
import com.formacraft.common.skeleton.radial.RadialPrimitive;
import com.formacraft.common.skeleton.radial.RadialPrimitiveKind;
import com.formacraft.common.skeleton.radial.RadialRole;
import com.formacraft.common.build.GeneratedStructure;
import com.formacraft.common.build.PlannedBlock;
import com.formacraft.server.material.PaletteResolver;
import com.formacraft.server.skeleton.radial.RadialPrimitiveInterpreter;
import com.formacraft.common.style.profile.DetailPreferences;
import com.formacraft.common.style.profile.StyleProfile;
import com.formacraft.common.style.profile.StyleProfileRegistry;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * TempleOfHeavenGenerator：天坛·祈年殿（强原型）专用生成器（v1）
 *
 * 标志性约束（v1 简化但保持识别性）：
 * - 三层圆形台基（可选 2/3）
 * - 圆殿（柱廊 + 殿身）
 * - 三层屋顶（简化为三段“圆锥/屋檐”）
 */
public class TempleOfHeavenGenerator implements StructureGenerator {

    private static final FcaLog LOG = FcaLog.of("TempleOfHeavenGenerator");

    @Override
    public GeneratedStructure generate(BuildingSpec spec, BlockPos origin, ServerWorld world) {
        String paletteId = getStringExtra(spec, "paletteId", null);
        // StyleProfile palette hint fallback (explicit extra.paletteId wins)
        BuildingStyle style = (spec != null && spec.getStyle() != null) ? spec.getStyle() : BuildingStyle.ASIAN;
        StyleProfile profile = (spec != null) ? StyleProfileRegistry.resolve(spec) : StyleProfileRegistry.forStyle(style);
        DetailPreferences details = profile != null ? profile.details() : null;
        if ((paletteId == null || paletteId.isBlank()) && details != null && details.paletteId != null && !details.paletteId.isBlank()) {
            paletteId = details.paletteId.trim();
        }
        int baseRadius = clamp(getIntExtra(spec, "baseRadius", getCircleRadiusFromFootprint(spec)), 10, 80);
        int tiers = clamp(getIntExtra(spec, "tiers", 3), 2, 3);
        int hallRadius = clamp(getIntExtra(spec, "hallRadius", (int) Math.round(baseRadius * 0.55)), 6, Math.max(6, baseRadius - 3));

        int height = clamp(getIntExtra(spec, "height", spec != null ? spec.getHeight() : 28), 18, 120);
        int hallHeight = clamp(getIntExtra(spec, "hallHeight", (int) Math.round(height * 0.62)), 10, height);

        String detail = getStringExtra(spec, "detailLevel", "aesthetic").toLowerCase();
        boolean refined = detail.contains("refined") || detail.contains("ornate");

        BlockState base = getStateOrDefault(world, getStringExtra(spec, "baseBlock", "minecraft:smooth_quartz"), Blocks.SMOOTH_QUARTZ.getDefaultState());
        BlockState stair = getStateOrDefault(world, getStringExtra(spec, "stairBlock", "minecraft:quartz_stairs"), Blocks.QUARTZ_STAIRS.getDefaultState());
        BlockState trim = getStateOrDefault(world, getStringExtra(spec, "trimBlock", "minecraft:quartz_slab"), Blocks.QUARTZ_SLAB.getDefaultState());
        BlockState pillar = getStateOrDefault(world, getStringExtra(spec, "pillarBlock", "minecraft:white_concrete"), Blocks.WHITE_CONCRETE.getDefaultState());
        BlockState wall = getStateOrDefault(world, getStringExtra(spec, "wallBlock", "minecraft:white_concrete"), Blocks.WHITE_CONCRETE.getDefaultState());
        BlockState roof = getStateOrDefault(world, getStringExtra(spec, "roofBlock", "minecraft:cyan_terracotta"), Blocks.CYAN_TERRACOTTA.getDefaultState());
        BlockState accent = getStateOrDefault(world, getStringExtra(spec, "accentBlock", "minecraft:yellow_terracotta"), Blocks.YELLOW_TERRACOTTA.getDefaultState());

        // Palette overrides (optional): keep existing per-spec block overrides as fallback.
        if (paletteId != null && !paletteId.isBlank()) {
            // Use origin as deterministic selector position for global materials.
            base = PaletteResolver.pick(world, paletteId, "WALL_FOUNDATION", origin, 0x51E0L, base);
            trim = PaletteResolver.pick(world, paletteId, "DECOR_DETAIL", origin, 0x51E1L, trim);
            pillar = PaletteResolver.pick(world, paletteId, "PILLAR", origin, 0x51E2L, pillar);
            wall = PaletteResolver.pick(world, paletteId, "WALL_BASE", origin, 0x51E3L, wall);
            roof = PaletteResolver.pick(world, paletteId, "ROOF_TILE", origin, 0x51E4L, roof);
            accent = PaletteResolver.pick(world, paletteId, "DECOR_DETAIL", origin, 0x51E5L, accent);
        }

        List<PlannedBlock> blocks = new ArrayList<>(Math.max(4000, baseRadius * baseRadius * 20));

        // 1) 三层台基（每层缩半径）
        int y = 0;
        int tierH = refined ? 3 : 2;
        int step = refined ? 3 : 2;
        // Skeleton-driven: Radial primitives for tier disks + trims (+ stairs at bottom tier)
        RadialPlan basePlan = new RadialPlan();
        int yTmp = y;
        for (int t = 0; t < tiers; t++) {
            int rt = Math.max(6, baseRadius - t * (step + 2));
            basePlan.add(new RadialPrimitive(RadialPrimitiveKind.DISK_FILL, RadialRole.BASE, rt, 0, yTmp, yTmp));
            basePlan.add(new RadialPrimitive(RadialPrimitiveKind.RING_OUTLINE, RadialRole.TRIM, rt, 0, yTmp + tierH - 1, yTmp + tierH - 1));
            if (t == 0) {
                basePlan.add(new RadialPrimitive(RadialPrimitiveKind.RING_OUTLINE, RadialRole.ACCENT, rt + 1, 0, yTmp, yTmp));
            }
            yTmp += tierH;
        }
        EnumMap<RadialRole, BlockState> palette = new EnumMap<>(RadialRole.class);
        palette.put(RadialRole.BASE, base);
        palette.put(RadialRole.TRIM, trim);
        palette.put(RadialRole.ACCENT, stair);
        blocks.addAll(new RadialPrimitiveInterpreter(palette).interpret(basePlan, origin, world));
        y = yTmp;

        int hallBaseY = y;

        // 2) 圆殿：柱廊 + 殿身
        // 柱廊：外环柱
        int pillarCount = refined ? 24 : 18;
        placePillars(blocks, origin, hallRadius, hallBaseY, hallBaseY + (refined ? 8 : 6), pillar, pillarCount);

        // 殿身墙体：内环墙（留门洞）
        int wallR = Math.max(4, hallRadius - 3);
        int doorWidth = refined ? 3 : 2;
        Direction entranceSide = resolveEntranceSide(spec);
        String layoutPlan = resolveLayoutPlan(spec);
        ringWithDoor(blocks, origin, wallR, hallBaseY, hallBaseY + (refined ? 8 : 6), wall, doorWidth, entranceSide);

        // 地面
        fillDisk(blocks, origin, wallR - 1, hallBaseY, base);

        // Layout IR: front_back -> emphasize the main axis from entrance to center (best-effort).
        if ("front_back".equals(layoutPlan)) {
            addAxialPath(blocks, origin, baseRadius, trim, entranceSide);
        }

        // 3) 三层屋顶（简化：三段圆锥）
        int roofStartY = hallBaseY + (refined ? 9 : 7);
        int roofTopY = roofStartY + hallHeight;

        // 三段：大/中/小
        int[] segH = refined ? new int[]{8, 7, 6} : new int[]{7, 6, 5};
        int[] segR = new int[]{hallRadius + 2, hallRadius, hallRadius - 2};
        int curY = roofStartY;
        for (int i = 0; i < 3; i++) {
            int hSeg = segH[i];
            int rSeg = Math.max(4, segR[i]);
            buildConicalRoof(blocks, origin, rSeg, curY, hSeg, roof);
            // 檐口装饰
            ring(blocks, origin, rSeg + 1, curY, accent);
            curY += hSeg;
        }

        // 顶部宝顶
        blocks.add(new PlannedBlock(origin.add(0, roofTopY + 1, 0), accent));
        blocks.add(new PlannedBlock(origin.add(0, roofTopY + 2, 0), Blocks.LIGHTNING_ROD.getDefaultState()));

        // v1 scoring：台基/圆殿/三层屋顶是否齐全
        double shapeScore = 0.92; // 圆形台基 + 圆殿
        double ratioScore = clamp01(1.0 - Math.abs((hallHeight / (double) baseRadius) - 1.4) * 0.22);
        double signatureScore = (tiers >= 3 ? 0.95 : 0.85);
        double overall = clamp01(shapeScore * 0.4 + ratioScore * 0.3 + signatureScore * 0.3);

        String desc = String.format(
                "TempleOfHeaven(Qiniandian) (ASIAN, baseR=%d, hallR=%d, tiers=%d, score=%.2f[shape=%.2f,ratio=%.2f,sig=%.2f])",
                baseRadius, hallRadius, tiers, overall, shapeScore, ratioScore, signatureScore
        );
        return new GeneratedStructure(null, origin, desc, blocks);
    }

    // fillDisk/ring now handled by RadialPrimitiveInterpreter for the tier base.

    private static void fillDisk(List<PlannedBlock> blocks, BlockPos origin, int r, int y, BlockState s) {
        int r2 = r * r;
        for (int x = -r; x <= r; x++) {
            for (int z = -r; z <= r; z++) {
                if (x * x + z * z <= r2) {
                    blocks.add(new PlannedBlock(origin.add(x, y, z), s));
                }
            }
        }
    }

    private static void ring(List<PlannedBlock> blocks, BlockPos origin, int r, int y, BlockState s) {
        int r2 = r * r;
        int rIn2 = Math.max(0, (r - 1) * (r - 1));
        for (int x = -r; x <= r; x++) {
            for (int z = -r; z <= r; z++) {
                int d2 = x * x + z * z;
                if (d2 <= r2 && d2 >= rIn2) {
                    blocks.add(new PlannedBlock(origin.add(x, y, z), s));
                }
            }
        }
    }

    private static void placePillars(List<PlannedBlock> blocks, BlockPos origin, int r, int y0, int y1, BlockState s, int count) {
        for (int i = 0; i < count; i++) {
            double ang = (Math.PI * 2.0) * (i / (double) count);
            int x = (int) Math.round(Math.cos(ang) * r);
            int z = (int) Math.round(Math.sin(ang) * r);
            for (int y = y0; y <= y1; y++) {
                blocks.add(new PlannedBlock(origin.add(x, y, z), s));
            }
        }
    }

    private static void ringWithDoor(List<PlannedBlock> blocks, BlockPos origin, int r, int y0, int y1, BlockState s, int doorW, Direction doorSide) {
        // door opening on the specified side: leave opening centered on that axis
        if (doorSide == null) doorSide = Direction.SOUTH;
        int r2 = r * r;
        int rIn2 = Math.max(0, (r - 1) * (r - 1));
        for (int x = -r; x <= r; x++) {
            for (int z = -r; z <= r; z++) {
                int d2 = x * x + z * z;
                if (d2 <= r2 && d2 >= rIn2) {
                    // skip door opening
                    if (isDoorOpeningCell(x, z, r, doorW, doorSide)) continue;
                    for (int y = y0; y <= y1; y++) {
                        blocks.add(new PlannedBlock(origin.add(x, y, z), s));
                    }
                }
            }
        }
        // simple door frame accent
        int fx = (doorSide == Direction.EAST ? 1 : (doorSide == Direction.WEST ? -1 : 0));
        int fz = (doorSide == Direction.SOUTH ? 1 : (doorSide == Direction.NORTH ? -1 : 0));
        int px = (doorSide == Direction.NORTH || doorSide == Direction.SOUTH) ? 1 : 0;
        int pz = (doorSide == Direction.EAST || doorSide == Direction.WEST) ? 1 : 0;
        int ax = fx * (r - 1);
        int az = fz * (r - 1);
        for (int y = y0; y <= y0 + 3; y++) {
            blocks.add(new PlannedBlock(origin.add(ax + (doorW + 1) * px, y, az + (doorW + 1) * pz), Blocks.QUARTZ_PILLAR.getDefaultState()));
            blocks.add(new PlannedBlock(origin.add(ax - (doorW + 1) * px, y, az - (doorW + 1) * pz), Blocks.QUARTZ_PILLAR.getDefaultState()));
        }
        Direction doorFacing = doorSide.getOpposite(); // faces into the hall
        BlockState doorLower = Blocks.OAK_DOOR.getDefaultState()
                .with(Properties.HORIZONTAL_FACING, doorFacing)
                .with(Properties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER);
        BlockState doorUpper = Blocks.OAK_DOOR.getDefaultState()
                .with(Properties.HORIZONTAL_FACING, doorFacing)
                .with(Properties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER);
        blocks.add(new PlannedBlock(origin.add(ax, y0 + 1, az), doorLower));
        blocks.add(new PlannedBlock(origin.add(ax, y0 + 2, az), doorUpper));
    }

    private static boolean isDoorOpeningCell(int x, int z, int r, int doorW, Direction doorSide) {
        // leave a rectangular opening at the ring boundary, centered on the axis for the chosen side
        return switch (doorSide) {
            case NORTH -> (z <= -r + 1) && (Math.abs(x) <= doorW);
            case EAST -> (x >= r - 1) && (Math.abs(z) <= doorW);
            case WEST -> (x <= -r + 1) && (Math.abs(z) <= doorW);
            default -> (z >= r - 1) && (Math.abs(x) <= doorW);
        };
    }

    private static Direction resolveEntranceSide(BuildingSpec spec) {
        // Layout IR: extra.layout.entranceFacing has priority. Default SOUTH.
        try {
            if (spec != null && spec.getExtra() != null) {
                Object layoutObj = spec.getExtra().get("layout");
                if (layoutObj instanceof Map<?, ?> m) {
                    Object ef = m.get("entranceFacing");
                    if (ef != null) {
                        String s = String.valueOf(ef).trim().toUpperCase(java.util.Locale.ROOT);
                        return switch (s) {
                            case "N", "NORTH", "北", "朝北" -> Direction.NORTH;
                            case "E", "EAST", "东", "朝东" -> Direction.EAST;
                            case "W", "WEST", "西", "朝西" -> Direction.WEST;
                            default -> Direction.SOUTH;
                        };
                    }
                }
            }
        } catch (Throwable ex) { LOG.debug("best-effort step failed", ex); }
        return Direction.SOUTH;
    }

    private static String resolveLayoutPlan(BuildingSpec spec) {
        try {
            if (spec != null && spec.getExtra() != null) {
                Object layoutObj = spec.getExtra().get("layout");
                if (layoutObj instanceof Map<?, ?> m) {
                    Object plan = m.get("plan");
                    if (plan == null) return "none";
                    String p = String.valueOf(plan).trim().toLowerCase(java.util.Locale.ROOT);
                    if (p.isEmpty()) return "none";
                    switch (p) {
                        case "none", "no", "false", "0", "off" -> {
                            return "none";
                        }
                        case "front_back", "frontback", "front-back", "front/back", "前后", "前后分区", "前后布局",
                             "前厅后室" -> {
                            return "front_back";
                        }
                        case "left_right", "leftright", "left-right", "left/right", "左右", "左右分区", "左右布局" -> {
                            return "left_right";
                        }
                        case "ring_corridor", "ring", "courtyard_corridor", "gallery", "cloister", "回廊", "环廊",
                             "环形走廊", "围绕中庭", "回字形", "回字布局", "回字走廊" -> {
                            return "ring_corridor";
                        }
                    }
                }
            }
        } catch (Throwable ex) { LOG.debug("best-effort step failed", ex); }
        return "none";
    }

    private static void addAxialPath(List<PlannedBlock> blocks, BlockPos origin, int r, BlockState s, Direction entranceSide) {
        if (entranceSide == null) entranceSide = Direction.SOUTH;
        int fx = (entranceSide == Direction.EAST ? 1 : (entranceSide == Direction.WEST ? -1 : 0));
        int fz = (entranceSide == Direction.SOUTH ? 1 : (entranceSide == Direction.NORTH ? -1 : 0));
        // 3-wide path on the axis from edge to center
        for (int t = r; t >= 0; t--) {
            int x = fx * t;
            int z = fz * t;
            for (int w = -1; w <= 1; w++) {
                int ox = (entranceSide == Direction.NORTH || entranceSide == Direction.SOUTH) ? w : 0;
                int oz = (entranceSide == Direction.EAST || entranceSide == Direction.WEST) ? w : 0;
                blocks.add(new PlannedBlock(origin.add(x + ox, 0, z + oz), s));
            }
        }
    }

    private static void buildConicalRoof(List<PlannedBlock> blocks, BlockPos origin, int baseR, int yStart, int h, BlockState roof) {
        for (int dy = 0; dy < h; dy++) {
            int y = yStart + dy;
            int curR = Math.max(1, baseR - (dy / 2)); // taper
            ring(blocks, origin, curR, y, roof);
            // cap top layer
            if (dy == h - 1) {
                fillDisk(blocks, origin, Math.max(1, curR - 1), y, roof);
            }
        }
    }

    private static int getCircleRadiusFromFootprint(BuildingSpec spec) {
        try {
            if (spec == null || spec.getFootprint() == null) return 18;
            if (!"circle".equalsIgnoreCase(spec.getFootprint().getShape())) return 18;
            int r = spec.getFootprint().getRadius();
            return r > 0 ? r : 18;
        } catch (Exception e) {
            return 18;
        }
    }

    private static int getIntExtra(BuildingSpec spec, String key, int def) {
        return StructureSpecParsers.extraInt(spec, key, def);
    }

    private static String getStringExtra(BuildingSpec spec, String key, String def) {
        if (spec == null) return def;
        Map<String, Object> extra = spec.getExtra();
        if (extra == null) return def;
        Object v = extra.get(key);
        if (v == null) return def;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? def : s;
    }

    private BlockState getStateOrDefault(ServerWorld world, String id, BlockState defaultState) {
        if (id == null || id.isBlank()) return defaultState;
        try {
            var ident = net.minecraft.util.Identifier.tryParse(id);
            if (ident == null) return defaultState;
            return net.minecraft.registry.Registries.BLOCK.get(ident).getDefaultState();
        } catch (Exception e) {
            return defaultState;
        }
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static double clamp01(double v) {
        if (v < 0.0) return 0.0;
        return Math.min(v, 1.0);
    }
}


