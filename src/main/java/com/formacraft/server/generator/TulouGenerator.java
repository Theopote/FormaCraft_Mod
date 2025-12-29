package com.formacraft.server.generator;

import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.common.model.build.BuildingStyle;
import com.formacraft.common.skeleton.radial.RadialPlan;
import com.formacraft.common.skeleton.radial.RadialPrimitive;
import com.formacraft.common.skeleton.radial.RadialPrimitiveKind;
import com.formacraft.common.skeleton.radial.RadialRole;
import com.formacraft.server.build.GeneratedStructure;
import com.formacraft.server.build.PlannedBlock;
import com.formacraft.server.material.PaletteResolver;
import com.formacraft.server.skeleton.radial.RadialPrimitiveInterpreter;
import com.formacraft.common.style.profile.DetailPreferences;
import com.formacraft.common.style.profile.StyleProfile;
import com.formacraft.common.style.profile.StyleProfileRegistry;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.enums.BlockHalf;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.server.world.ServerWorld;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 福建土楼（永定等）参数化生成器
 * <p>
 * 约定：
 * - footprint.shape = "circle"
 * - footprint.radius = 外半径（单位：方块）
 * - extra.landmark = "tulou"（用于稳定路由）
 * - extra.ringThickness（可选）：环形居住带厚度（默认 4）
 * - floors 控制层数（默认 3）
 */
public class TulouGenerator implements StructureGenerator {

    @Override
    public GeneratedStructure generate(BuildingSpec spec, BlockPos origin, ServerWorld world) {
        List<PlannedBlock> blocks = new ArrayList<>();
        String paletteId = getStringExtra(spec, "paletteId", null);

        int radius = 10;
        if (spec != null && spec.getFootprint() != null && "circle".equalsIgnoreCase(spec.getFootprint().getShape())) {
            radius = Math.max(6, spec.getFootprint().getRadius());
        }

        int floors = spec != null ? Math.max(1, spec.getFloors()) : 3;
        if (floors <= 0) floors = 3;

        // --- parameters (extra.*) ---
        // ringThickness 优先级最高；其次 courtyardRadius/courtyardRatio
        int ringThicknessExtra = getIntExtra(spec, "ringThickness");
        int courtyardRadiusExtra = getIntExtra(spec, "courtyardRadius");
        double courtyardRatio = getDoubleExtra(spec);

        int ringThickness = (ringThicknessExtra > 0) ? ringThicknessExtra : Math.min(6, Math.max(3, radius / 3));
        ringThickness = Math.max(3, Math.min(8, ringThickness));

        int innerRadius;
        if (ringThicknessExtra > 0) {
            innerRadius = radius - ringThickness;
        } else if (courtyardRadiusExtra > 0) {
            innerRadius = courtyardRadiusExtra;
        } else if (!Double.isNaN(courtyardRatio)) {
            double r = Math.max(0.25, Math.min(0.75, courtyardRatio));
            innerRadius = (int) Math.round(radius * r);
        } else {
            innerRadius = radius - ringThickness;
        }
        // clamp inner radius and sync thickness
        innerRadius = Math.max(3, Math.min(radius - 3, innerRadius));
        ringThickness = Math.max(3, Math.min(8, radius - innerRadius));

        int floorHeight = 4;
        int wallHeight = floors * floorHeight + 2;

        // --- materials (spec.materials 优先；否则给土楼友好的默认值) ---
        BlockState wall = getStateOrDefault(world, spec != null && spec.getMaterials() != null ? spec.getMaterials().getWall() : null,
                Blocks.MUD_BRICKS.getDefaultState());
        BlockState foundation = getStateOrDefault(world, spec != null && spec.getMaterials() != null ? spec.getMaterials().getFoundation() : null,
                Blocks.STONE_BRICKS.getDefaultState());
        BlockState floor = getStateOrDefault(world, spec != null && spec.getMaterials() != null ? spec.getMaterials().getFloor() : null,
                Blocks.SPRUCE_PLANKS.getDefaultState());
        BlockState roof = getStateOrDefault(world, spec != null && spec.getMaterials() != null ? spec.getMaterials().getRoof() : null,
                Blocks.DEEPSLATE_TILES.getDefaultState());
        BlockState window = getStateOrDefault(world, spec != null && spec.getMaterials() != null ? spec.getMaterials().getWindow() : null,
                Blocks.GLASS_PANE.getDefaultState());

        // Style-driven detail hints (cross-style): window/eaves/ornament
        BuildingStyle style = (spec != null && spec.getStyle() != null) ? spec.getStyle() : BuildingStyle.ASIAN;
        StyleProfile profile = (spec != null) ? StyleProfileRegistry.resolve(spec) : StyleProfileRegistry.forStyle(style);
        DetailPreferences details = (profile != null) ? profile.details() : null;
        if ((paletteId == null || paletteId.isBlank()) && details != null && details.paletteId != null && !details.paletteId.isBlank()) {
            paletteId = details.paletteId.trim();
        }
        String effWindowStyle = resolveEffectiveWindowStyle(spec, details, style);
        window = resolveWindowBlock(effWindowStyle, window);
        String eavesProfile = (details != null) ? details.eavesProfile : null;
        String ornamentProfile = (details != null) ? details.ornamentProfile : null;

        // palette overrides (fallback to current material choices if palette part missing)
        if (paletteId != null && !paletteId.isBlank()) {
            wall = PaletteResolver.pick(world, paletteId, "WALL_BASE", origin, 0x71A0L, wall);
            foundation = PaletteResolver.pick(world, paletteId, "WALL_FOUNDATION", origin, 0x71A1L, foundation);
            floor = PaletteResolver.pick(world, paletteId, "FLOORING", origin, 0x71A2L, floor);
            roof = PaletteResolver.pick(world, paletteId, "ROOF_TILE", origin, 0x71A3L, roof);
            window = PaletteResolver.pick(world, paletteId, "WINDOW", origin, 0x71A4L, window);
        }

        BlockState pillar = Blocks.DARK_OAK_LOG.getDefaultState();
        BlockState railing = Blocks.DARK_OAK_FENCE.getDefaultState();
        BlockState air = Blocks.AIR.getDefaultState();

        // 细节等级：aesthetic（默认更观赏性）/ refined（更精致）
        String detailLevel = getStringExtra(spec, "detailLevel", "aesthetic").toLowerCase();
        boolean refined = detailLevel.contains("refined") || detailLevel.contains("精致") || detailLevel.contains("detail");

        // 百叶窗/木窗扇：extra.windowShutter=true 时，在外墙窗带生成 trapdoor 百叶（并在内侧补玻璃）
        boolean windowShutter = getBoolExtra(spec, "windowShutter", false);
        boolean windowShutterOpen = getBoolExtra(spec, "windowShutterOpen", refined);
        // 默认深色（更稳重更观赏），用户可通过 extra.windowShutterBlock 覆盖（如 minecraft:oak_trapdoor）
        String shutterBlockId = getStringExtra(spec, "windowShutterBlock", "minecraft:dark_oak_trapdoor");

        // 门朝向：extra.doorFacing = NORTH/SOUTH/EAST/WEST（默认 SOUTH）
        Direction doorFacing = parseFacing(getStringExtra(spec, "doorFacing", "SOUTH"));

        // 观赏性默认增强用的装饰材质
        BlockState wallAccent = Blocks.PACKED_MUD.getDefaultState();
        BlockState band = Blocks.DARK_OAK_PLANKS.getDefaultState();
        BlockState courtyardPave = Blocks.STONE_BRICKS.getDefaultState();
        BlockState courtyardPaveAlt = Blocks.MOSSY_STONE_BRICKS.getDefaultState();

        // --- origin 作为圆心（更符合“直径为X”的直觉） ---

        // --- clear volume (避免与山体冲突) ---
        int clearR = radius + 2;
        for (int x = -clearR; x <= clearR; x++) {
            for (int z = -clearR; z <= clearR; z++) {
                int d2 = x * x + z * z;
                if (d2 > (clearR * clearR)) continue;
                for (int y = 0; y <= wallHeight + 8; y++) {
                    blocks.add(new PlannedBlock(origin.add(x, y, z), air));
                }
            }
        }

        // --- foundation ring (y=0) ---
        RadialPlan basePlan = new RadialPlan();
        int outerFillR = radius - 1;
        int innerFillR = innerRadius + 1;
        // 居住环带承托：annulus fill
        basePlan.add(new RadialPrimitive(RadialPrimitiveKind.ANNULUS_FILL, RadialRole.BASE, outerFillR, innerFillR, 0, 0));
        // 内院夯土：disk fill
        basePlan.add(new RadialPrimitive(RadialPrimitiveKind.DISK_FILL, RadialRole.COURTYARD, innerRadius - 1, 0, 0, 0));
        // 外圈/内圈地基 ring outline
        basePlan.add(new RadialPrimitive(RadialPrimitiveKind.RING_OUTLINE, RadialRole.BASE, radius, 0, 0, 0));
        basePlan.add(new RadialPrimitive(RadialPrimitiveKind.RING_OUTLINE, RadialRole.BASE, innerRadius, 0, 0, 0));
        EnumMap<RadialRole, BlockState> basePalette = new EnumMap<>(RadialRole.class);
        basePalette.put(RadialRole.BASE, foundation);
        basePalette.put(RadialRole.COURTYARD, Blocks.COARSE_DIRT.getDefaultState());
        blocks.addAll(new RadialPrimitiveInterpreter(basePalette).interpret(basePlan, origin, world));

        // --- outer wall + windows ---
        for (int y = 1; y <= wallHeight; y++) {
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    int d2 = x * x + z * z;
                    // outer shell thickness 1
                    if (d2 <= radius * radius && d2 >= (radius - 1) * (radius - 1)) {
                        // 分层线脚：每层楼板上沿/檐口下沿做一圈木线
                        boolean isBand = (y % floorHeight == 0) || (y == wallHeight - 1);
                        // 夯土层次：偶尔掺一圈更深色的“夯土带”
                        boolean isRammedLayer = (y % 6 == 0);

                        boolean isWindowBand = (y % floorHeight == 2) && y >= 2 && y <= wallHeight - 2;
                        // refined 窗更密
                        int effectiveWindowMask = refined ? 3 : 7;
                        // windowStyle broad density knobs (cross-style)
                        String ws = (effWindowStyle == null) ? "" : effWindowStyle.trim().toLowerCase(java.util.Locale.ROOT);
                        if (ws.contains("slit") || ws.contains("bars")) effectiveWindowMask = refined ? 7 : 15;
                        else if (ws.contains("curtain")) effectiveWindowMask = refined ? 1 : 3;
                        boolean windowSlot = (((x * 31) ^ (z * 17)) & effectiveWindowMask) == 0;

                        BlockState base = wall;
                        if (isRammedLayer) base = wallAccent;
                        BlockState s = base;
                        if (isBand) s = band;
                        if (isWindowBand && windowSlot && !isBand) {
                            if (windowShutter) {
                                Direction out = outwardFacing(x, z);
                                BlockState shutter = getStateOrDefault(world, shutterBlockId, Blocks.OAK_TRAPDOOR.getDefaultState());
                                shutter = withIfPresent(shutter, Properties.HORIZONTAL_FACING, out);
                                shutter = withIfPresent(shutter, Properties.OPEN, windowShutterOpen);
                                shutter = withIfPresent(shutter, Properties.BLOCK_HALF, BlockHalf.TOP);
                                // 外侧放百叶
                                blocks.add(new PlannedBlock(origin.add(x, y, z), shutter));
                                // 内侧补玻璃（更观赏性）
                                int ix = x - out.getOffsetX();
                                int iz = z - out.getOffsetZ();
                                blocks.add(new PlannedBlock(origin.add(ix, y, iz), window));
                                continue;
                            } else {
                                s = window;
                            }
                        }
                        if (paletteId != null && !paletteId.isBlank()) {
                            // Per-block texture: allow subtle variation while staying deterministic
                            long salt = ((long) x * 73471L) ^ ((long) z * 91213L) ^ ((long) y * 193L);
                            if (s == wall || s == wallAccent || s == band) {
                                s = PaletteResolver.pick(world, paletteId, "WALL_BASE", origin.add(x, y, z), salt, s);
                            } else if (s == window) {
                                s = PaletteResolver.pick(world, paletteId, "WINDOW", origin.add(x, y, z), salt, s);
                            }
                        }
                        blocks.add(new PlannedBlock(origin.add(x, y, z), s));
                    }
                }
            }
        }

        // --- inner arcade posts + railings (每层一圈) ---
        for (int f = 0; f < floors; f++) {
            int yBase = 1 + f * floorHeight;
            int yRail = yBase + 1;
            for (int x = -innerRadius; x <= innerRadius; x++) {
                for (int z = -innerRadius; z <= innerRadius; z++) {
                    int d2 = x * x + z * z;
                    // inner edge: one-block thickness at radius=innerRadius
                    if (d2 <= innerRadius * innerRadius && d2 >= (innerRadius - 1) * (innerRadius - 1)) {
                        // posts every ~3 blocks
                        if (((x + z) % 3) == 0) {
                            blocks.add(new PlannedBlock(origin.add(x, yBase, z), pillar));
                            blocks.add(new PlannedBlock(origin.add(x, yBase + 1, z), pillar));
                        } else {
                            blocks.add(new PlannedBlock(origin.add(x, yRail, z), railing));
                        }
                    }
                }
            }
        }

        // --- floors: ring area only, keep courtyard empty ---
        RadialPlan floorPlan = new RadialPlan();
        for (int f = 0; f < floors; f++) {
            int y = 1 + f * floorHeight;
            floorPlan.add(new RadialPrimitive(RadialPrimitiveKind.ANNULUS_FILL, RadialRole.FLOOR, outerFillR, innerFillR, y, y));
        }
        EnumMap<RadialRole, BlockState> floorPalette = new EnumMap<>(RadialRole.class);
        floorPalette.put(RadialRole.FLOOR, floor);
        blocks.addAll(new RadialPrimitiveInterpreter(floorPalette).interpret(floorPlan, origin, world));

        // --- vertical ladder shaft near inner ring (简化楼梯) ---
        // 位置：门的顺时针 90°（避免与大门冲突）
        Direction ladderPosDir = doorFacing.rotateYClockwise();
        int ladderX = (ladderPosDir == Direction.EAST ? innerRadius : (ladderPosDir == Direction.WEST ? -innerRadius : 0));
        int ladderZ = (ladderPosDir == Direction.SOUTH ? innerRadius : (ladderPosDir == Direction.NORTH ? -innerRadius : 0));
        Direction ladderFacing = ladderPosDir.getOpposite(); // 面向内院
        for (int y = 1; y <= wallHeight - 1; y++) {
            blocks.add(new PlannedBlock(origin.add(ladderX, y, ladderZ), Blocks.LADDER.getDefaultState().with(Properties.HORIZONTAL_FACING, ladderFacing)));
        }
        // 预留洞口：每层楼面打一格空气
        for (int f = 1; f < floors; f++) {
            int y = 1 + f * floorHeight;
            // 洞口放在楼梯“内侧”一格
            int hx = ladderX + (ladderPosDir == Direction.EAST ? -1 : (ladderPosDir == Direction.WEST ? 1 : 0));
            int hz = ladderZ + (ladderPosDir == Direction.SOUTH ? -1 : (ladderPosDir == Direction.NORTH ? 1 : 0));
            blocks.add(new PlannedBlock(origin.add(hx, y, hz), air));
        }

        // --- entrance door (configurable facing) ---
        int doorX = (doorFacing == Direction.EAST ? radius : (doorFacing == Direction.WEST ? -radius : 0));
        int doorZ = (doorFacing == Direction.SOUTH ? radius : (doorFacing == Direction.NORTH ? -radius : 0));
        // 清空门洞
        blocks.add(new PlannedBlock(origin.add(doorX, 1, doorZ), air));
        blocks.add(new PlannedBlock(origin.add(doorX, 2, doorZ), air));
        BlockState door = Blocks.OAK_DOOR.getDefaultState().with(Properties.HORIZONTAL_FACING, doorFacing);
        blocks.add(new PlannedBlock(origin.add(doorX, 1, doorZ), door.with(Properties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER)));
        blocks.add(new PlannedBlock(origin.add(doorX, 2, doorZ), door.with(Properties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER)));
        // 门楼略做加厚
        BlockState gateCap = Blocks.DARK_OAK_PLANKS.getDefaultState();
        blocks.add(new PlannedBlock(origin.add(doorX, 3, doorZ), gateCap));
        blocks.add(new PlannedBlock(origin.add(doorX, 4, doorZ), gateCap));
        // 门两侧加一点“门框”
        int sideX = (doorFacing == Direction.NORTH || doorFacing == Direction.SOUTH) ? 1 : 0;
        int sideZ = (doorFacing == Direction.EAST || doorFacing == Direction.WEST) ? 1 : 0;
        blocks.add(new PlannedBlock(origin.add(doorX + sideX, 1, doorZ + sideZ), wall));
        blocks.add(new PlannedBlock(origin.add(doorX - sideX, 1, doorZ - sideZ), wall));

        // Ornament near entrance (cross-style, best-effort)
        if (ornamentProfile != null && !ornamentProfile.isBlank()) {
            addEntranceOrnaments(blocks, origin, world, doorFacing, doorX, doorZ, ornamentProfile, details, paletteId);
        }

        // 门前台阶 + 门楼（更观赏性）
        // 在门外侧方向扩展一个小门厅平台与台阶
        int fx = (doorFacing == Direction.EAST ? 1 : (doorFacing == Direction.WEST ? -1 : 0));
        int fz = (doorFacing == Direction.SOUTH ? 1 : (doorFacing == Direction.NORTH ? -1 : 0));
        int px = (doorFacing == Direction.NORTH || doorFacing == Direction.SOUTH) ? 1 : 0;
        int pz = (doorFacing == Direction.EAST || doorFacing == Direction.WEST) ? 1 : 0;

        BlockState stair = Blocks.STONE_BRICK_STAIRS.getDefaultState().with(Properties.HORIZONTAL_FACING, doorFacing);
        BlockState slab = Blocks.STONE_BRICK_SLAB.getDefaultState();
        BlockState lantern = Blocks.LANTERN.getDefaultState();
        // NOTE: 当前 yarn/mappings 下 Blocks.CHAIN 可能不可用，用铁栏杆作为“挂链”替代
        BlockState chain = Blocks.IRON_BARS.getDefaultState();

        // 平台（门外 1~3 格）
        for (int o = 1; o <= 3; o++) {
            int bx = doorX + fx * o;
            int bz = doorZ + fz * o;
            blocks.add(new PlannedBlock(origin.add(bx, 0, bz), slab));
            // 稍微加宽
            blocks.add(new PlannedBlock(origin.add(bx + px, 0, bz + pz), slab));
            blocks.add(new PlannedBlock(origin.add(bx - px, 0, bz - pz), slab));
        }
        // 台阶（门外第一格）
        blocks.add(new PlannedBlock(origin.add(doorX + fx, 0, doorZ + fz), stair));

        // 门楼立柱（门外 2 格处两根）
        int gx = doorX + fx * 2;
        int gz = doorZ + fz * 2;
        for (int y = 1; y <= 4; y++) {
            blocks.add(new PlannedBlock(origin.add(gx + px, y, gz + pz), pillar));
            blocks.add(new PlannedBlock(origin.add(gx - px, y, gz - pz), pillar));
        }
        // 横梁/匾额
        blocks.add(new PlannedBlock(origin.add(gx, 4, gz), band));
        blocks.add(new PlannedBlock(origin.add(gx, 3, gz), Blocks.OCHRE_FROGLIGHT.getDefaultState())); // “匾额”发光点缀
        // 灯笼（两侧）
        blocks.add(new PlannedBlock(origin.add(gx + px, 5, gz + pz), chain));
        blocks.add(new PlannedBlock(origin.add(gx - px, 5, gz - pz), chain));
        blocks.add(new PlannedBlock(origin.add(gx + px, 4, gz + pz), lantern));
        blocks.add(new PlannedBlock(origin.add(gx - px, 4, gz - pz), lantern));

        // 内院铺装与景观（更观赏性）
        // - 中心井
        blocks.add(new PlannedBlock(origin.add(0, 0, 0), Blocks.STONE_BRICKS.getDefaultState()));
        blocks.add(new PlannedBlock(origin.add(0, 1, 0), Blocks.COBBLESTONE_WALL.getDefaultState()));
        blocks.add(new PlannedBlock(origin.add(0, 2, 0), chain));
        blocks.add(new PlannedBlock(origin.add(0, 3, 0), lantern));
        // - 从门到内院中心的“主路”
        int start = innerRadius - 1;
        for (int t = start; t >= 1; t--) {
            int wx = (doorFacing == Direction.EAST ? t : (doorFacing == Direction.WEST ? -t : 0));
            int wz = (doorFacing == Direction.SOUTH ? t : (doorFacing == Direction.NORTH ? -t : 0));
            // 以中轴为主，稍微加宽 3 格
            for (int w = -1; w <= 1; w++) {
                int sx = (doorFacing == Direction.NORTH || doorFacing == Direction.SOUTH) ? w : 0;
                int sz = (doorFacing == Direction.EAST || doorFacing == Direction.WEST) ? w : 0;
                BlockState p = (((t + w) & 3) == 0) ? courtyardPaveAlt : courtyardPave;
                blocks.add(new PlannedBlock(origin.add(wx + sx, 0, wz + sz), p));
            }
        }
        // - 内院随机铺装（不超过半径，避免太密）
        int paveR = Math.max(3, innerRadius - 2);
        for (int x = -paveR; x <= paveR; x++) {
            for (int z = -paveR; z <= paveR; z++) {
                int d2 = x * x + z * z;
                if (d2 <= paveR * paveR) {
                    if ((((x * 13) ^ (z * 7)) & 7) == 0) {
                        blocks.add(new PlannedBlock(origin.add(x, 0, z), courtyardPaveAlt));
                    }
                }
            }
        }

        // --- roof: inward sloping ring roof (简化) ---
        int roofBaseY = wallHeight + 1;
        int roofSteps = Math.min(10, ringThickness + 4);
        for (int i = 0; i < roofSteps; i++) {
            int outerR = radius + 1 - i;
            int innerR = Math.max(1, innerRadius - 1 + i);
            if (innerR >= outerR - 1) break;
            int y = roofBaseY + i;
            for (int x = -outerR; x <= outerR; x++) {
                for (int z = -outerR; z <= outerR; z++) {
                    int d2 = x * x + z * z;
                    if (d2 <= outerR * outerR && d2 >= innerR * innerR) {
                        BlockPos p = origin.add(x, y, z);
                        BlockState rr = roof;
                        if (paletteId != null && !paletteId.isBlank()) {
                            long salt = ((long) x * 91213L) ^ ((long) z * 73471L) ^ ((long) y * 97L);
                            rr = PaletteResolver.pick(world, paletteId, "ROOF_TILE", p, salt, roof);
                        }
                        blocks.add(new PlannedBlock(p, rr));
                    }
                }
            }
        }
        // 檐口：在屋顶基线外再做一圈（更像土楼厚重屋檐）
        int eaveR = radius + 2;
        if (eavesProfile != null && !eavesProfile.isBlank()) {
            String ep = eavesProfile.trim().toLowerCase(java.util.Locale.ROOT);
            if (ep.contains("flying")) eaveR += 1;
        }
        for (int x = -eaveR; x <= eaveR; x++) {
            for (int z = -eaveR; z <= eaveR; z++) {
                int d2 = x * x + z * z;
                if (d2 <= eaveR * eaveR && d2 >= (eaveR - 1) * (eaveR - 1)) {
                    BlockPos p = origin.add(x, roofBaseY, z);
                    BlockState rr = roof;
                    if (paletteId != null && !paletteId.isBlank()) {
                        long salt = ((long) x * 7127L) ^ ((long) z * 9137L) ^ ((long) roofBaseY * 31L);
                        rr = PaletteResolver.pick(world, paletteId, "ROOF_TILE", p, salt, roof);
                    }
                    blocks.add(new PlannedBlock(p, rr));
                }
            }
        }

        // Eaves profiles (cross-style, best-effort)
        if (eavesProfile != null && !eavesProfile.isBlank()) {
            String ep = eavesProfile.trim().toLowerCase(java.util.Locale.ROOT);
            if (ep.contains("neon")) {
                BlockState light = Blocks.SEA_LANTERN.getDefaultState();
                if (paletteId != null && !paletteId.isBlank()) {
                    light = PaletteResolver.pick(world, paletteId, "ROAD_LIGHT", origin, 0x7E1001L, light);
                    light = PaletteResolver.pick(world, paletteId, "LIGHTING", origin, 0x7E1002L, light);
                }
                for (int x = -eaveR; x <= eaveR; x += 6) {
                    blocks.add(new PlannedBlock(origin.add(x, roofBaseY, eaveR), light));
                    blocks.add(new PlannedBlock(origin.add(x, roofBaseY, -eaveR), light));
                }
                for (int z = -eaveR; z <= eaveR; z += 6) {
                    blocks.add(new PlannedBlock(origin.add(eaveR, roofBaseY, z), light));
                    blocks.add(new PlannedBlock(origin.add(-eaveR, roofBaseY, z), light));
                }
            } else if (ep.contains("organic") || ep.contains("vine")) {
                BlockState leaf = Blocks.OAK_LEAVES.getDefaultState();
                for (int x = -eaveR; x <= eaveR; x += 5) {
                    blocks.add(new PlannedBlock(origin.add(x, roofBaseY - 1, eaveR + 1), leaf));
                    blocks.add(new PlannedBlock(origin.add(x, roofBaseY - 1, -eaveR - 1), leaf));
                }
            } else if (ep.contains("battlement")) {
                BlockState crenel = Blocks.STONE_BRICK_WALL.getDefaultState();
                int by = roofBaseY + 1;
                for (int x = -eaveR; x <= eaveR; x++) {
                    if ((x & 1) == 0) {
                        blocks.add(new PlannedBlock(origin.add(x, by, eaveR), crenel));
                        blocks.add(new PlannedBlock(origin.add(x, by, -eaveR), crenel));
                    }
                }
                for (int z = -eaveR; z <= eaveR; z++) {
                    if ((z & 1) == 0) {
                        blocks.add(new PlannedBlock(origin.add(eaveR, by, z), crenel));
                        blocks.add(new PlannedBlock(origin.add(-eaveR, by, z), crenel));
                    }
                }
            }
        }

        // refined：再加一圈屋脊/兽吻式点缀（轻量）
        if (refined) {
            int topY = roofBaseY + Math.min(roofSteps - 1, 6);
            int ridgeR = Math.max(2, innerRadius - 1);
            BlockState ridge = Blocks.DEEPSLATE_BRICK_WALL.getDefaultState();
            for (int x = -ridgeR; x <= ridgeR; x++) {
                for (int z = -ridgeR; z <= ridgeR; z++) {
                    int d2 = x * x + z * z;
                    if (d2 <= ridgeR * ridgeR && d2 >= (ridgeR - 1) * (ridgeR - 1)) {
                        if ((((x * 9) ^ (z * 5)) & 3) == 0) {
                            blocks.add(new PlannedBlock(origin.add(x, topY, z), ridge));
                        }
                    }
                }
            }
        }

        // -----------------------------
        // ArchetypeScoring (v1 lightweight)
        // -----------------------------
        // 目标：提供“像不像”的可观测指标，便于后续自动纠偏/二次修正
        double shapeScore = 0.95; // 轮廓：强制圆环 -> 高分
        double ratioScore = clamp01(1.0 - Math.abs((floors - 3) * 0.12)); // 3 层附近更像常见土楼
        double signatureScore = 0.9; // 内院+环带+门楼+屋檐 -> 高分
        double overall = clamp01(shapeScore * 0.4 + ratioScore * 0.3 + signatureScore * 0.3);

        String desc = String.format(
                "Tulou (ASIAN, diameter≈%d, floors=%d, facing=%s, score=%.2f[shape=%.2f,ratio=%.2f,sig=%.2f])",
                radius * 2, floors, doorFacing.asString(), overall, shapeScore, ratioScore, signatureScore
        );
        return new GeneratedStructure(null, origin, desc, blocks);
    }

    private static int getIntExtra(BuildingSpec spec, String key) {
        if (spec == null) return -1;
        Map<String, Object> extra = spec.getExtra();
        if (extra == null) return -1;
        Object v = extra.get(key);
        if (v == null) return -1;
        if (v instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(v));
        } catch (Exception ignored) {
            return -1;
        }
    }

    private static double getDoubleExtra(BuildingSpec spec) {
        if (spec == null) return Double.NaN;
        Map<String, Object> extra = spec.getExtra();
        if (extra == null) return Double.NaN;
        Object v = extra.get("courtyardRatio");
        if (v == null) return Double.NaN;
        if (v instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(String.valueOf(v));
        } catch (Exception ignored) {
            return Double.NaN;
        }
    }

    private static boolean getBoolExtra(BuildingSpec spec, String key, boolean def) {
        if (spec == null) return def;
        Map<String, Object> extra = spec.getExtra();
        if (extra == null) return def;
        Object v = extra.get(key);
        if (v == null) return def;
        if (v instanceof Boolean b) return b;
        String s = String.valueOf(v).trim().toLowerCase();
        if (s.isEmpty()) return def;
        if (s.equals("true") || s.equals("1") || s.equals("yes") || s.equals("y") || s.equals("on")) return true;
        if (s.equals("false") || s.equals("0") || s.equals("no") || s.equals("n") || s.equals("off")) return false;
        return def;
    }

    private static Direction outwardFacing(int x, int z) {
        int ax = Math.abs(x);
        int az = Math.abs(z);
        if (ax >= az) {
            return x >= 0 ? Direction.EAST : Direction.WEST;
        }
        return z >= 0 ? Direction.SOUTH : Direction.NORTH;
    }

    private static <T extends Comparable<T>> BlockState withIfPresent(BlockState s, net.minecraft.state.property.Property<T> p, T v) {
        if (s == null) return null;
        if (p == null) return s;
        if (!s.contains(p)) return s;
        return s.with(p, v);
    }

    private static double clamp01(double v) {
        if (v < 0.0) return 0.0;
        return Math.min(v, 1.0);
    }

    private static String resolveEffectiveWindowStyle(BuildingSpec spec, DetailPreferences details, BuildingStyle style) {
        String ws = (spec != null && spec.getStyleOptions() != null) ? spec.getStyleOptions().getWindowStyle() : null;
        if (ws == null || ws.isBlank()) ws = (details != null) ? details.windowStyle : null;
        if (ws == null || ws.isBlank()) {
            ws = switch (style) {
                case ASIAN -> "fence";
                case MEDIEVAL -> "bars";
                default -> "pane";
            };
        }
        return ws;
    }

    private static BlockState resolveWindowBlock(String windowStyle, BlockState fallback) {
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

    private static void addEntranceOrnaments(List<PlannedBlock> blocks,
                                             BlockPos c,
                                             ServerWorld world,
                                             Direction doorFacing,
                                             int doorX,
                                             int doorZ,
                                             String ornamentProfile,
                                             DetailPreferences details,
                                             String paletteId) {
        if (blocks == null || c == null || ornamentProfile == null || doorFacing == null) return;
        String op = ornamentProfile.trim().toLowerCase(java.util.Locale.ROOT);
        int fx = (doorFacing == Direction.EAST ? 1 : (doorFacing == Direction.WEST ? -1 : 0));
        int fz = (doorFacing == Direction.SOUTH ? 1 : (doorFacing == Direction.NORTH ? -1 : 0));
        int px = (doorFacing == Direction.NORTH || doorFacing == Direction.SOUTH) ? 1 : 0;
        int pz = (doorFacing == Direction.EAST || doorFacing == Direction.WEST) ? 1 : 0;

        // Base position slightly outside the door
        BlockPos base = c.add(doorX + fx * 2, 3, doorZ + fz * 2);

        if (op.contains("chinese") || op.contains("plaque")) {
            BlockState sign = Blocks.DARK_OAK_WALL_SIGN.getDefaultState();
            if (paletteId != null && !paletteId.isBlank() && world != null) {
                sign = PaletteResolver.pick(world, paletteId, "ROAD_SIGNAGE", base, 0x7E1003L, sign);
                sign = PaletteResolver.pick(world, paletteId, "DECOR_DETAIL", base, 0x7E1004L, sign);
            }
            sign = withIfPresent(sign, Properties.HORIZONTAL_FACING, doorFacing);
            blocks.add(new PlannedBlock(base, sign));
            blocks.add(new PlannedBlock(base.add(px, 0, pz), Blocks.DARK_OAK_SLAB.getDefaultState()));
            blocks.add(new PlannedBlock(base.add(-px, 0, -pz), Blocks.DARK_OAK_SLAB.getDefaultState()));
            return;
        }

        if (op.contains("banner")) {
            BlockState b = resolveWallBannerState(details != null ? details.bannerColor : null);
            if ((details == null || details.bannerColor == null || details.bannerColor.isBlank())
                    && paletteId != null && !paletteId.isBlank() && world != null) {
                b = PaletteResolver.pick(world, paletteId, "BANNER", base, 0x7E1005L, b);
            }
            b = withIfPresent(b, Properties.HORIZONTAL_FACING, doorFacing);
            blocks.add(new PlannedBlock(base.add(px, -1, pz), b));
            blocks.add(new PlannedBlock(base.add(-px, -1, -pz), b));
            return;
        }

        if (op.contains("cyber") || op.contains("sign")) {
            BlockState plate = Blocks.CYAN_STAINED_GLASS.getDefaultState();
            BlockState light = Blocks.SEA_LANTERN.getDefaultState();
            if (paletteId != null && !paletteId.isBlank() && world != null) {
                plate = PaletteResolver.pick(world, paletteId, "ROAD_SIGNAGE", base, 0x7E1006L, plate);
                plate = PaletteResolver.pick(world, paletteId, "DECOR_DETAIL", base, 0x7E1007L, plate);
                light = PaletteResolver.pick(world, paletteId, "LIGHTING", base.up(), 0x7E1008L, light);
                light = PaletteResolver.pick(world, paletteId, "ROAD_LIGHT", base.up(), 0x7E1009L, light);
            }
            blocks.add(new PlannedBlock(base, plate));
            blocks.add(new PlannedBlock(base.up(), light));
            return;
        }

        if (op.contains("steam") || op.contains("pipe")) {
            BlockState pipe = Blocks.COPPER_BLOCK.getDefaultState();
            for (int y = 0; y < 5; y++) blocks.add(new PlannedBlock(base.down(2).up(y), pipe));
            blocks.add(new PlannedBlock(base.up(2), Blocks.CAMPFIRE.getDefaultState()));
            return;
        }

        if (op.contains("organic") || op.contains("lantern") || op.contains("vine")) {
            BlockState leaf = Blocks.OAK_LEAVES.getDefaultState();
            BlockState lantern = Blocks.LANTERN.getDefaultState();
            if (paletteId != null && !paletteId.isBlank() && world != null) {
                leaf = PaletteResolver.pick(world, paletteId, "DECOR_DETAIL", base, 0x7E1010L, leaf);
                lantern = PaletteResolver.pick(world, paletteId, "LIGHTING", base.up(), 0x7E1011L, lantern);
                lantern = PaletteResolver.pick(world, paletteId, "ROAD_LIGHT", base.up(), 0x7E1012L, lantern);
            }
            blocks.add(new PlannedBlock(base, leaf));
            blocks.add(new PlannedBlock(base.up(), lantern));
        }
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

    private static String getStringExtra(BuildingSpec spec, String key, String def) {
        if (spec == null) return def;
        Map<String, Object> extra = spec.getExtra();
        if (extra == null) return def;
        Object v = extra.get(key);
        if (v == null) return def;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? def : s;
    }

    private static Direction parseFacing(String s) {
        String v = (s == null ? "" : s).trim().toUpperCase();
        return switch (v) {
            case "N", "NORTH", "北", "朝北" -> Direction.NORTH;
            case "E", "EAST", "东", "朝东" -> Direction.EAST;
            case "W", "WEST", "西", "朝西" -> Direction.WEST;
            default -> Direction.SOUTH;
        };
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
}


