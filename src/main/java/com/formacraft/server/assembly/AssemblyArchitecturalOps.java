package com.formacraft.server.assembly;

import com.formacraft.common.build.PlannedBlock;
import com.formacraft.common.palette.component.MaterialBlockFamily;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.SlabBlock;
import net.minecraft.block.StairsBlock;
import net.minecraft.block.enums.BlockHalf;
import net.minecraft.block.enums.SlabType;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.Properties;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Mid-level architectural ASSEMBLY operators (arcade, buttress array, drum dome, louver, cantilever).
 * Deterministic GEOMETRY composable ops — not building templates.
 */
public final class AssemblyArchitecturalOps {
    private AssemblyArchitecturalOps() {}

    public static final class InvalidArchitecturalParam extends IllegalArgumentException {
        public InvalidArchitecturalParam(String message) {
            super(message);
        }
    }

    public interface Adapter {
        void put(List<PlannedBlock> out, MetaAssemblyEngine.Context ctx, BlockPos origin, int x, int y, int z, BlockState state);
        BlockState pick(MetaAssemblyEngine.Context ctx, Map<?, ?> op, String overrideKey, String semanticKey, long salt, BlockState fallback);
        int i(Object v, int def);
        double d(Object v, double def);
        boolean bool(Object v, boolean def);
        String str(Object v, String def);
        int clamp(int v, int min, int max);
        double clamp(double v, double min, double max);
    }

    // ─── ARCADE_SERIES ───────────────────────────────────────────────────────

    public static void applyArcadeSeries(List<PlannedBlock> out, MetaAssemblyEngine.Context ctx,
                                         BlockPos origin, Map<String, Object> op, Adapter adapter) {
        int length = requireInRange(adapter.i(op.get("length"), -1), 5, 128, "length");
        int pierHeight = requireInRange(adapter.i(op.get("pierHeight"), -1), 2, 32, "pierHeight");
        int baySpan = requireInRange(adapter.i(op.get("baySpan"), -1), 2, 16, "baySpan");
        if (baySpan > length) {
            throw new InvalidArchitecturalParam("baySpan must be <= length");
        }
        String axis = adapter.str(op.get("axis"), "X").trim().toUpperCase(Locale.ROOT);
        if (!"X".equals(axis) && !"Z".equals(axis)) {
            throw new InvalidArchitecturalParam("axis must be X or Z");
        }
        int pierWidth = adapter.clamp(adapter.i(op.get("pierWidth"), 1), 1, 4);
        int depth = adapter.clamp(adapter.i(op.get("depth"), 2), 1, 8);
        String profile = adapter.str(op.get("archProfile"), "semicircular").trim().toLowerCase(Locale.ROOT);
        int archRise = adapter.i(op.get("archRise"), -1);
        if ("flat_lintel".equals(profile)) {
            archRise = 0;
        } else if (archRise < 0) {
            archRise = Math.max(1, baySpan / 2);
        }
        archRise = adapter.clamp(archRise, 0, 16);
        boolean fillSpandrel = adapter.bool(op.get("fillSpandrel"), true);
        boolean cornice = adapter.bool(op.get("cornice"), true);
        String capitalStyle = adapter.str(op.get("capitalStyle"), "simple").trim().toLowerCase(Locale.ROOT);

        BlockState mat = adapter.pick(ctx, op, "material", "PRIMARY_STRUCTURE", 0xA58001L, Blocks.STONE_BRICKS.getDefaultState());
        BlockState trim = adapter.pick(ctx, op, "trimMaterial", "FACADE_TRIM", 0xA58002L, mat);
        BlockState archMat = adapter.pick(ctx, op, "archMaterial", "PRIMARY_STRUCTURE", 0xA58003L, mat);
        BlockState stairs = familyStairs(mat, Blocks.STONE_BRICK_STAIRS.getDefaultState());
        BlockState slab = familySlab(mat, Blocks.STONE_BRICK_SLAB.getDefaultState());

        int pitch = baySpan + pierWidth;
        int bays = Math.max(1, (length - pierWidth) / pitch);
        int used = bays * baySpan + (bays + 1) * pierWidth;
        int start = -(used / 2);

        int halfDepth = depth / 2;
        int topY = pierHeight + archRise + (fillSpandrel ? 1 : 0);

        for (int bay = 0; bay <= bays; bay++) {
            int pierStart = start + bay * pitch;
            for (int p = 0; p < pierWidth; p++) {
                int along = pierStart + p;
                for (int dy = 0; dy < depth; dy++) {
                    int cross = -halfDepth + dy;
                    for (int y = 0; y < pierHeight; y++) {
                        placeAxis(out, ctx, origin, adapter, axis, along, y, cross, mat);
                    }
                    if (!"none".equals(capitalStyle)) {
                        BlockState cap = "flared".equals(capitalStyle) ? stairs : slab;
                        placeAxis(out, ctx, origin, adapter, axis, along, pierHeight - 1, cross, orientStair(cap, axis, true));
                        placeAxis(out, ctx, origin, adapter, axis, along, 0, cross, orientStair(cap, axis, false));
                    }
                }
            }
        }

        for (int bay = 0; bay < bays; bay++) {
            int archStart = start + bay * pitch + pierWidth;
            for (int s = 0; s < baySpan; s++) {
                double t = baySpan <= 1 ? 0.5 : s / (double) (baySpan - 1);
                int riseY = archY(profile, t, archRise);
                int along = archStart + s;
                for (int dy = 0; dy < depth; dy++) {
                    int cross = -halfDepth + dy;
                    placeAxis(out, ctx, origin, adapter, axis, along, pierHeight + riseY, cross, archMat);
                    if (fillSpandrel) {
                        for (int y = pierHeight + riseY + 1; y <= topY; y++) {
                            placeAxis(out, ctx, origin, adapter, axis, along, y, cross, mat);
                        }
                    }
                }
            }
        }

        if (cornice) {
            BlockState corniceMat = stairs.getBlock() instanceof StairsBlock
                    ? stairs.with(Properties.BLOCK_HALF, BlockHalf.TOP)
                    : trim;
            for (int along = start; along < start + used; along++) {
                for (int dy = -1; dy <= depth; dy++) {
                    int cross = -halfDepth + dy;
                    if (dy >= 0 && dy < depth) continue;
                    Direction outward = corniceOutward(axis, dy < 0);
                    placeAxis(out, ctx, origin, adapter, axis, along, topY + 1, cross,
                            orientStairFacing(corniceMat, outward, true));
                }
                for (int dy = 0; dy < depth; dy++) {
                    int cross = -halfDepth + dy;
                    placeAxis(out, ctx, origin, adapter, axis, along, topY + 1, cross, trim);
                }
            }
        }
    }

    // ─── BUTTRESS_ARRAY ──────────────────────────────────────────────────────

    public static void applyButtressArray(List<PlannedBlock> out, MetaAssemblyEngine.Context ctx,
                                          BlockPos origin, Map<String, Object> op, Adapter adapter) {
        int length = requireInRange(adapter.i(op.get("length"), -1), 5, 128, "length");
        int height = requireInRange(adapter.i(op.get("height"), -1), 4, 64, "height");
        int reach = requireInRange(adapter.i(op.get("reach"), -1), 2, 16, "reach");
        String axis = adapter.str(op.get("axis"), "X").trim().toUpperCase(Locale.ROOT);
        if (!"X".equals(axis) && !"Z".equals(axis)) {
            throw new InvalidArchitecturalParam("axis must be X or Z");
        }
        Direction outward = parseCardinal(adapter.str(op.get("outward"), ""), "outward");
        if (("X".equals(axis) && (outward == Direction.EAST || outward == Direction.WEST))
                || ("Z".equals(axis) && (outward == Direction.NORTH || outward == Direction.SOUTH))) {
            throw new InvalidArchitecturalParam("outward must be perpendicular to axis");
        }
        int width = adapter.clamp(adapter.i(op.get("width"), 1), 1, 3);
        int spacing = adapter.clamp(adapter.i(op.get("spacing"), 5), 3, 16);
        int count = adapter.i(op.get("count"), -1);
        if (count > 0) {
            count = adapter.clamp(count, 2, 24);
        } else {
            count = Math.max(2, (length / spacing) + 1);
            count = adapter.clamp(count, 2, 24);
        }
        String style = adapter.str(op.get("style"), "stepped").trim().toLowerCase(Locale.ROOT);
        int tierCount = adapter.clamp(adapter.i(op.get("tierCount"), 3), 2, 5);
        int pinnacleHeight = adapter.clamp(adapter.i(op.get("pinnacleHeight"), 3), 0, 8);
        int flyerTiers = adapter.clamp(adapter.i(op.get("flyerTiers"), 1), 1, 2);

        BlockState mat = adapter.pick(ctx, op, "material", "PRIMARY_STRUCTURE", 0xA58101L, Blocks.STONE_BRICKS.getDefaultState());
        BlockState trim = adapter.pick(ctx, op, "trimMaterial", "FACADE_TRIM", 0xA58102L, mat);
        BlockState stairs = familyStairs(mat, Blocks.STONE_BRICK_STAIRS.getDefaultState());

        int start = -(length / 2);
        for (int i = 0; i < count; i++) {
            int along = count == 1 ? start
                    : start + (int) Math.round(i * (length - 1) / (double) (count - 1));
            if ("flying".equals(style)) {
                placeFlyingButtress(out, ctx, origin, adapter, axis, along, height, reach, width,
                        outward, flyerTiers, pinnacleHeight, mat, trim);
            } else if ("pier_pinnacle".equals(style)) {
                placeSteppedButtress(out, ctx, origin, adapter, axis, along, height, reach, width,
                        outward, tierCount, mat, stairs);
                for (int py = 0; py < pinnacleHeight; py++) {
                    placeOutward(out, ctx, origin, adapter, axis, along, height + py, reach - 1, width, outward, trim);
                }
            } else {
                placeSteppedButtress(out, ctx, origin, adapter, axis, along, height, reach, width,
                        outward, tierCount, mat, stairs);
            }
        }
    }

    // ─── DRUM_DOME ───────────────────────────────────────────────────────────

    public static void applyDrumDome(List<PlannedBlock> out, MetaAssemblyEngine.Context ctx,
                                     BlockPos origin, Map<String, Object> op, Adapter adapter) {
        int radius = requireInRange(adapter.i(op.get("radius"), -1), 3, 48, "radius");
        int domeHeight = requireInRange(adapter.i(op.get("domeHeight"), -1), 3, 48, "domeHeight");
        int drumHeight = adapter.i(op.get("drumHeight"), -1);
        if (drumHeight < 0) drumHeight = Math.max(2, radius / 3);
        drumHeight = adapter.clamp(drumHeight, 0, 24);
        int thickness = adapter.clamp(adapter.i(op.get("thickness"), 1), 1, 3);
        String profile = adapter.str(op.get("profile"), "hemisphere").trim().toLowerCase(Locale.ROOT);
        int ribCount = adapter.clamp(adapter.i(op.get("ribCount"), 8), 0, 16);
        boolean drumWindows = adapter.bool(op.get("drumWindows"), true);
        int lanternRadius = adapter.i(op.get("lanternRadius"), -1);
        if (lanternRadius < 0) lanternRadius = radius >= 7 ? Math.max(2, radius / 4) : 0;
        lanternRadius = adapter.clamp(lanternRadius, 0, 8);
        int lanternHeight = adapter.clamp(adapter.i(op.get("lanternHeight"), lanternRadius > 0 ? 3 : 0), 0, 12);
        int finialHeight = adapter.clamp(adapter.i(op.get("finialHeight"), lanternRadius > 0 ? 2 : 0), 0, 8);

        BlockState shell = adapter.pick(ctx, op, "shellMaterial", "ROOF_TILE", 0xA58201L, Blocks.COPPER_BLOCK.getDefaultState());
        BlockState rib = adapter.pick(ctx, op, "ribMaterial", "FACADE_TRIM", 0xA58202L, Blocks.DEEPSLATE_BRICKS.getDefaultState());
        BlockState drum = adapter.pick(ctx, op, "drumMaterial", "PRIMARY_STRUCTURE", 0xA58203L, Blocks.STONE_BRICKS.getDefaultState());
        BlockState window = adapter.pick(ctx, op, "windowMaterial", "WINDOW", 0xA58204L, Blocks.GLASS.getDefaultState());

        int r2 = radius * radius;
        int innerR = Math.max(0, radius - thickness);
        int inner2 = innerR * innerR;

        for (int y = 0; y < drumHeight; y++) {
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    int d2 = x * x + z * z;
                    if (d2 > r2 || d2 < inner2) continue;
                    boolean onCardinal = (Math.abs(x) <= 1 && Math.abs(z) >= innerR)
                            || (Math.abs(z) <= 1 && Math.abs(x) >= innerR);
                    if (drumWindows && drumHeight >= 4 && y >= 1 && y <= drumHeight - 2 && onCardinal) {
                        adapter.put(out, ctx, origin, x, y, z, window);
                    } else {
                        adapter.put(out, ctx, origin, x, y, z, drum);
                    }
                }
            }
        }

        int domeBase = drumHeight;
        for (int y = 0; y <= domeHeight; y++) {
            double t = domeHeight == 0 ? 1.0 : y / (double) domeHeight;
            double ringR = domeRingRadius(profile, radius, t);
            if ("octagonal".equals(profile)) {
                placeOctagonRing(out, ctx, origin, adapter, domeBase + y, ringR, thickness, shell);
            } else {
                int rr = Math.max(0, (int) Math.round(ringR));
                int rr2 = rr * rr;
                int ir = Math.max(0, rr - thickness);
                int ir2 = ir * ir;
                for (int x = -rr; x <= rr; x++) {
                    for (int z = -rr; z <= rr; z++) {
                        int d2 = x * x + z * z;
                        if (d2 > rr2 || d2 < ir2) continue;
                        adapter.put(out, ctx, origin, x, domeBase + y, z, shell);
                    }
                }
            }
        }

        if (ribCount > 0) {
            for (int ri = 0; ri < ribCount; ri++) {
                double ang = (2.0 * Math.PI * ri) / ribCount;
                for (int y = 0; y <= domeHeight; y++) {
                    double t = domeHeight == 0 ? 1.0 : y / (double) domeHeight;
                    double ringR = domeRingRadius(profile, radius, t);
                    int x = (int) Math.round(Math.cos(ang) * ringR);
                    int z = (int) Math.round(Math.sin(ang) * ringR);
                    adapter.put(out, ctx, origin, x, domeBase + y, z, rib);
                    // outward bulge
                    int ox = (int) Math.round(Math.cos(ang) * (ringR + 1));
                    int oz = (int) Math.round(Math.sin(ang) * (ringR + 1));
                    adapter.put(out, ctx, origin, ox, domeBase + y, oz, rib);
                }
            }
        }

        int apexY = domeBase + domeHeight;
        if (lanternRadius > 0 && lanternHeight > 0) {
            int lr2 = lanternRadius * lanternRadius;
            int lir = Math.max(0, lanternRadius - 1);
            int lir2 = lir * lir;
            for (int y = 0; y < lanternHeight; y++) {
                for (int x = -lanternRadius; x <= lanternRadius; x++) {
                    for (int z = -lanternRadius; z <= lanternRadius; z++) {
                        int d2 = x * x + z * z;
                        if (d2 > lr2 || d2 < lir2) continue;
                        adapter.put(out, ctx, origin, x, apexY + y, z, drum);
                    }
                }
            }
            for (int fy = 0; fy < finialHeight; fy++) {
                adapter.put(out, ctx, origin, 0, apexY + lanternHeight + fy, 0, rib);
            }
        }
    }

    // ─── LOUVER_SKIN ─────────────────────────────────────────────────────────

    public static void applyLouverSkin(List<PlannedBlock> out, MetaAssemblyEngine.Context ctx,
                                       BlockPos origin, Map<String, Object> op, Adapter adapter) {
        int w = requireInRange(adapter.i(op.get("w"), -1), 3, 96, "w");
        int h = requireInRange(adapter.i(op.get("h"), -1), 3, 96, "h");
        Direction facing = parseCardinal(adapter.str(op.get("facing"), ""), "facing");
        String orientation = adapter.str(op.get("orientation"), "vertical").trim().toLowerCase(Locale.ROOT);
        int spacing = adapter.clamp(adapter.i(op.get("spacing"), 2), 2, 8);
        int finDepth = adapter.clamp(adapter.i(op.get("finDepth"), 2), 1, 4);
        int finThickness = adapter.clamp(adapter.i(op.get("finThickness"), 1), 1, 2);
        double waveAmp = adapter.clamp(adapter.d(op.get("waveAmplitude"), 0.0), 0.0, 4.0);
        double waveFreq = adapter.clamp(adapter.d(op.get("waveFrequency"), 0.25), 0.05, 2.0);
        double wavePhase = adapter.clamp(adapter.d(op.get("wavePhase"), 0.0), 0.0, Math.PI * 2.0);

        BlockState mat = adapter.pick(ctx, op, "material", "FACADE_ACCENT", 0xA58301L, Blocks.IRON_BARS.getDefaultState());
        BlockState frame = adapter.pick(ctx, op, "frameMaterial", "FACADE_TRIM", 0xA58302L, Blocks.SMOOTH_STONE.getDefaultState());
        BlockState backing = null;
        if (op.get("backingMaterial") != null) {
            backing = adapter.pick(ctx, op, "backingMaterial", "WINDOW", 0xA58303L, Blocks.LIGHT_GRAY_STAINED_GLASS.getDefaultState());
        }

        int hx = w / 2;
        // Frame border
        for (int u = 0; u < w; u++) {
            placeFacing(out, ctx, origin, adapter, facing, u - hx, 0, 0, frame);
            placeFacing(out, ctx, origin, adapter, facing, u - hx, h - 1, 0, frame);
        }
        for (int y = 0; y < h; y++) {
            placeFacing(out, ctx, origin, adapter, facing, -hx, y, 0, frame);
            placeFacing(out, ctx, origin, adapter, facing, w - hx - 1, y, 0, frame);
        }

        // Full curtain backing first so gaps between fins are not open
        if (backing != null) {
            for (int u = 1; u < w - 1; u++) {
                for (int y = 1; y < h - 1; y++) {
                    placeFacing(out, ctx, origin, adapter, facing, u - hx, y, -1, backing);
                }
            }
        }

        if ("horizontal".equals(orientation)) {
            for (int y = 1; y < h - 1; y += spacing) {
                for (int u = 1; u < w - 1; u++) {
                    double wave = waveAmp * Math.sin(waveFreq * u + wavePhase);
                    int depth = Math.max(1, (int) Math.round(finDepth + wave));
                    for (int t = 0; t < finThickness; t++) {
                        for (int d = 0; d < depth; d++) {
                            placeFacing(out, ctx, origin, adapter, facing, u - hx, y + t, d, mat);
                        }
                    }
                }
            }
        } else if ("diagrid".equals(orientation)) {
            for (int y = 1; y < h - 1; y++) {
                for (int u = 1; u < w - 1; u++) {
                    if (((u + y) % spacing) != 0 && ((u - y) % spacing) != 0) continue;
                    double wave = waveAmp * Math.sin(waveFreq * (u + y) + wavePhase);
                    int depth = Math.max(1, (int) Math.round(finDepth + wave));
                    for (int d = 0; d < depth; d++) {
                        placeFacing(out, ctx, origin, adapter, facing, u - hx, y, d, mat);
                    }
                }
            }
        } else {
            for (int u = 1; u < w - 1; u += spacing) {
                for (int y = 1; y < h - 1; y++) {
                    double wave = waveAmp * Math.sin(waveFreq * y + wavePhase);
                    int depth = Math.max(1, (int) Math.round(finDepth + wave));
                    for (int t = 0; t < finThickness; t++) {
                        for (int d = 0; d < depth; d++) {
                            placeFacing(out, ctx, origin, adapter, facing, u - hx + t, y, d, mat);
                        }
                    }
                }
            }
        }
    }

    // ─── CANTILEVER_TIER ─────────────────────────────────────────────────────

    public static void applyCantileverTier(List<PlannedBlock> out, MetaAssemblyEngine.Context ctx,
                                           BlockPos origin, Map<String, Object> op, Adapter adapter) {
        int w = requireInRange(adapter.i(op.get("w"), -1), 4, 64, "w");
        int d = requireInRange(adapter.i(op.get("d"), -1), 4, 64, "d");
        int slabThickness = adapter.clamp(adapter.i(op.get("slabThickness"), 2), 1, 4);
        int on = adapter.clamp(adapter.i(op.get("overhangNorth"), 0), 0, 16);
        int os = adapter.clamp(adapter.i(op.get("overhangSouth"), 0), 0, 16);
        int oe = adapter.clamp(adapter.i(op.get("overhangEast"), 0), 0, 16);
        int ow = adapter.clamp(adapter.i(op.get("overhangWest"), 0), 0, 16);
        int parapet = adapter.clamp(adapter.i(op.get("parapetHeight"), 1), 0, 3);
        String supportStyle = adapter.str(op.get("supportStyle"), "recessed_core").trim().toLowerCase(Locale.ROOT);
        int supportHeight = adapter.clamp(adapter.i(op.get("supportHeight"), 4), 2, 24);
        double coreScale = adapter.clamp(adapter.d(op.get("coreScale"), 0.55), 0.3, 0.85);

        BlockState slabMat = adapter.pick(ctx, op, "slabMaterial", "FLOORING", 0xA58401L, Blocks.SMOOTH_STONE.getDefaultState());
        BlockState soffit = adapter.pick(ctx, op, "soffitMaterial", "FLOOR_SLAB", 0xA58402L, Blocks.SMOOTH_QUARTZ.getDefaultState());
        BlockState railing = adapter.pick(ctx, op, "railingMaterial", "FACADE_ACCENT", 0xA58403L, Blocks.IRON_BARS.getDefaultState());
        BlockState support = adapter.pick(ctx, op, "supportMaterial", "STRUCTURAL_COLUMN", 0xA58404L, Blocks.STONE_BRICKS.getDefaultState());

        int x0 = -(w / 2) - ow;
        int x1 = (w - w / 2 - 1) + oe;
        int z0 = -(d / 2) - on;
        int z1 = (d - d / 2 - 1) + os;
        int deckY = supportHeight;

        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                for (int t = 0; t < slabThickness; t++) {
                    BlockState use = (slabThickness >= 2 && t == 0) ? soffit : slabMat;
                    adapter.put(out, ctx, origin, x, deckY + t, z, use);
                }
            }
        }

        if (parapet > 0) {
            for (int x = x0; x <= x1; x++) {
                for (int py = 1; py <= parapet; py++) {
                    adapter.put(out, ctx, origin, x, deckY + slabThickness - 1 + py, z0, railing);
                    adapter.put(out, ctx, origin, x, deckY + slabThickness - 1 + py, z1, railing);
                }
            }
            for (int z = z0; z <= z1; z++) {
                for (int py = 1; py <= parapet; py++) {
                    adapter.put(out, ctx, origin, x0, deckY + slabThickness - 1 + py, z, railing);
                    adapter.put(out, ctx, origin, x1, deckY + slabThickness - 1 + py, z, railing);
                }
            }
        }

        int coreW = Math.max(2, (int) Math.round(w * coreScale));
        int coreD = Math.max(2, (int) Math.round(d * coreScale));
        int cx0 = -(coreW / 2);
        int cx1 = coreW - coreW / 2 - 1;
        int cz0 = -(coreD / 2);
        int cz1 = coreD - coreD / 2 - 1;

        if ("recessed_core".equals(supportStyle)) {
            for (int x = cx0; x <= cx1; x++) {
                for (int z = cz0; z <= cz1; z++) {
                    boolean edge = x == cx0 || x == cx1 || z == cz0 || z == cz1;
                    if (!edge) continue;
                    for (int y = 0; y < supportHeight; y++) {
                        adapter.put(out, ctx, origin, x, y, z, support);
                    }
                }
            }
        } else if ("pilotis".equals(supportStyle)) {
            int step = Math.max(2, Math.min(coreW, coreD) / 2);
            for (int x = cx0; x <= cx1; x += step) {
                for (int z = cz0; z <= cz1; z += step) {
                    for (int y = 0; y < supportHeight; y++) {
                        adapter.put(out, ctx, origin, x, y, z, support);
                    }
                }
            }
        } else if ("diagonal_strut".equals(supportStyle)) {
            // Four corner struts from core base toward overhang midpoints.
            placeStrut(out, ctx, origin, adapter, 0, 0, 0, x0, supportHeight - 1, (z0 + z1) / 2, support);
            placeStrut(out, ctx, origin, adapter, 0, 0, 0, x1, supportHeight - 1, (z0 + z1) / 2, support);
            placeStrut(out, ctx, origin, adapter, 0, 0, 0, (x0 + x1) / 2, supportHeight - 1, z0, support);
            placeStrut(out, ctx, origin, adapter, 0, 0, 0, (x0 + x1) / 2, supportHeight - 1, z1, support);
        }
        // "none" → no supports
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private static int requireInRange(int v, int min, int max, String name) {
        if (v < min || v > max) {
            throw new InvalidArchitecturalParam(name + " must be in " + min + ".." + max);
        }
        return v;
    }

    private static Direction parseCardinal(String raw, String field) {
        String s = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        return switch (s) {
            case "NORTH" -> Direction.NORTH;
            case "SOUTH" -> Direction.SOUTH;
            case "EAST" -> Direction.EAST;
            case "WEST" -> Direction.WEST;
            default -> throw new InvalidArchitecturalParam(field + " must be NORTH|SOUTH|EAST|WEST");
        };
    }

    private static int archY(String profile, double t, int rise) {
        if (rise <= 0) return 0;
        t = Math.max(0.0, Math.min(1.0, t));
        return switch (profile) {
            case "pointed" -> {
                double half = t <= 0.5 ? t * 2.0 : (1.0 - t) * 2.0;
                yield (int) Math.round(rise * Math.sqrt(Math.max(0.0, 1.0 - (1.0 - half) * (1.0 - half))));
            }
            case "horseshoe" -> {
                double u = 2.0 * t - 1.0;
                double base = Math.sqrt(Math.max(0.0, 1.0 - u * u));
                yield (int) Math.round(rise * (base + 0.15 * (1.0 - Math.abs(u))));
            }
            case "ogee" -> {
                double s = t < 0.5
                        ? 2.0 * t * t
                        : 1.0 - 2.0 * (1.0 - t) * (1.0 - t);
                yield (int) Math.round(rise * s);
            }
            case "flat_lintel" -> 0;
            default -> { // semicircular
                double u = 2.0 * t - 1.0;
                yield (int) Math.round(rise * Math.sqrt(Math.max(0.0, 1.0 - u * u)));
            }
        };
    }

    private static double domeRingRadius(String profile, int radius, double t) {
        t = Math.max(0.0, Math.min(1.0, t));
        return switch (profile) {
            case "pointed" -> radius * Math.pow(1.0 - t, 0.65);
            case "onion" -> {
                double bulge = 1.0 + 0.25 * Math.sin(Math.PI * t);
                yield radius * bulge * Math.pow(1.0 - t, 0.55);
            }
            case "saucer" -> radius * Math.sqrt(Math.max(0.0, 1.0 - t * t * 0.85));
            case "octagonal" -> radius * (1.0 - t);
            default -> // hemisphere
                    radius * Math.sqrt(Math.max(0.0, 1.0 - t * t));
        };
    }

    private static void placeOctagonRing(List<PlannedBlock> out, MetaAssemblyEngine.Context ctx, BlockPos origin,
                                         Adapter adapter, int y, double ringR, int thickness, BlockState mat) {
        int r = Math.max(0, (int) Math.round(ringR));
        // Approximate octagon: diamond-clipped square shell band
        for (int x = -r; x <= r; x++) {
            for (int z = -r; z <= r; z++) {
                int manhattan = Math.abs(x) + Math.abs(z);
                int cheb = Math.max(Math.abs(x), Math.abs(z));
                if (cheb > r) continue;
                if (manhattan > (int) Math.round(r * 1.5)) continue;
                boolean outer = cheb >= r - thickness + 1 || manhattan >= (int) Math.round(r * 1.5) - thickness;
                if (outer) adapter.put(out, ctx, origin, x, y, z, mat);
            }
        }
    }

    private static void placeAxis(List<PlannedBlock> out, MetaAssemblyEngine.Context ctx, BlockPos origin,
                                  Adapter adapter, String axis, int along, int y, int cross, BlockState state) {
        if ("Z".equals(axis)) {
            adapter.put(out, ctx, origin, cross, y, along, state);
        } else {
            adapter.put(out, ctx, origin, along, y, cross, state);
        }
    }

    private static void placeOutward(List<PlannedBlock> out, MetaAssemblyEngine.Context ctx, BlockPos origin,
                                     Adapter adapter, String axis, int along, int y, int outDist, int width,
                                     Direction outward, BlockState state) {
        int half = width / 2;
        for (int w = 0; w < width; w++) {
            int a = along - half + w;
            int x = 0, z = 0;
            if ("X".equals(axis)) {
                x = a;
                z = outward == Direction.SOUTH ? outDist : (outward == Direction.NORTH ? -outDist : 0);
            } else {
                z = a;
                x = outward == Direction.EAST ? outDist : (outward == Direction.WEST ? -outDist : 0);
            }
            adapter.put(out, ctx, origin, x, y, z, state);
        }
    }

    private static void placeSteppedButtress(List<PlannedBlock> out, MetaAssemblyEngine.Context ctx, BlockPos origin,
                                            Adapter adapter, String axis, int along, int height, int reach, int width,
                                            Direction outward, int tiers, BlockState mat, BlockState stairs) {
        for (int tier = 0; tier < tiers; tier++) {
            int y0 = (height * tier) / tiers;
            int y1 = (height * (tier + 1)) / tiers;
            int outReach = Math.max(1, reach - tier);
            for (int y = y0; y < y1; y++) {
                for (int r = 0; r < outReach; r++) {
                    placeOutward(out, ctx, origin, adapter, axis, along, y, r + 1, width, outward, mat);
                }
            }
            // stair cap on tier top outer edge
            if (stairs.getBlock() instanceof StairsBlock) {
                BlockState oriented = stairs.with(Properties.HORIZONTAL_FACING, outward.getOpposite())
                        .with(Properties.BLOCK_HALF, BlockHalf.BOTTOM);
                placeOutward(out, ctx, origin, adapter, axis, along, y1 - 1, outReach, width, outward, oriented);
            }
        }
    }

    private static void placeFlyingButtress(List<PlannedBlock> out, MetaAssemblyEngine.Context ctx, BlockPos origin,
                                           Adapter adapter, String axis, int along, int height, int reach, int width,
                                           Direction outward, int flyerTiers, int pinnacle, BlockState mat, BlockState trim) {
        // Outer pier
        for (int y = 0; y < height; y++) {
            placeOutward(out, ctx, origin, adapter, axis, along, y, reach, width, outward, mat);
        }
        for (int py = 0; py < pinnacle; py++) {
            placeOutward(out, ctx, origin, adapter, axis, along, height + py, reach, width, outward, trim);
        }
        // Flyer arches back to wall (outDist 0)
        for (int ft = 0; ft < flyerTiers; ft++) {
            int attachY = height - 2 - ft * Math.max(2, height / (flyerTiers + 1));
            attachY = Math.max(2, attachY);
            int pierY = Math.max(1, attachY - reach / 2);
            int steps = Math.max(1, reach);
            for (int s = 0; s <= steps; s++) {
                double t = s / (double) steps;
                int outDist = (int) Math.round(reach * (1.0 - t));
                int y = (int) Math.round(pierY + (attachY - pierY) * t + 4.0 * (reach / 4.0) * t * (1.0 - t));
                placeOutward(out, ctx, origin, adapter, axis, along, y, Math.max(0, outDist), width, outward, mat);
            }
        }
    }

    private static void placeFacing(List<PlannedBlock> out, MetaAssemblyEngine.Context ctx, BlockPos origin,
                                    Adapter adapter, Direction facing, int u, int y, int depthOut, BlockState state) {
        // u along facade tangent; depthOut positive = outward along facing
        int x, z;
        switch (facing) {
            case NORTH -> { x = u; z = -depthOut; }
            case SOUTH -> { x = u; z = depthOut; }
            case EAST -> { x = depthOut; z = u; }
            case WEST -> { x = -depthOut; z = u; }
            default -> { x = u; z = depthOut; }
        }
        adapter.put(out, ctx, origin, x, y, z, state);
    }

    private static void placeStrut(List<PlannedBlock> out, MetaAssemblyEngine.Context ctx, BlockPos origin,
                                   Adapter adapter, int x0, int y0, int z0, int x1, int y1, int z1, BlockState mat) {
        int dx = x1 - x0, dy = y1 - y0, dz = z1 - z0;
        int steps = Math.max(Math.max(Math.abs(dx), Math.abs(dy)), Math.abs(dz));
        if (steps <= 0) {
            adapter.put(out, ctx, origin, x0, y0, z0, mat);
            return;
        }
        for (int i = 0; i <= steps; i++) {
            double t = i / (double) steps;
            adapter.put(out, ctx, origin,
                    (int) Math.round(x0 + dx * t),
                    (int) Math.round(y0 + dy * t),
                    (int) Math.round(z0 + dz * t),
                    mat);
        }
    }

    private static BlockState familyStairs(BlockState base, BlockState fallback) {
        String id = Registries.BLOCK.getId(base.getBlock()).toString();
        String stairs = MaterialBlockFamily.stairsOrStoneBrick(id);
        BlockState parsed = parseId(stairs);
        return parsed != null ? parsed : fallback;
    }

    private static BlockState familySlab(BlockState base, BlockState fallback) {
        String id = Registries.BLOCK.getId(base.getBlock()).toString();
        String slab = MaterialBlockFamily.slab(id);
        BlockState parsed = parseId(slab);
        return parsed != null ? parsed : fallback;
    }

    private static BlockState parseId(String id) {
        if (id == null || id.isBlank()) return null;
        Identifier ident = Identifier.tryParse(id);
        if (ident == null || !Registries.BLOCK.containsId(ident)) return null;
        return Registries.BLOCK.get(ident).getDefaultState();
    }

    private static BlockState orientStair(BlockState state, String axis, boolean top) {
        Direction face = "Z".equals(axis) ? Direction.EAST : Direction.SOUTH;
        return orientStairFacing(state, face, top);
    }

    /** Cornice side facing: negative cross → north/west, positive → south/east. */
    private static Direction corniceOutward(String axis, boolean negativeCross) {
        if ("Z".equals(axis)) {
            return negativeCross ? Direction.WEST : Direction.EAST;
        }
        return negativeCross ? Direction.NORTH : Direction.SOUTH;
    }

    private static BlockState orientStairFacing(BlockState state, Direction face, boolean top) {
        if (state.getBlock() instanceof StairsBlock) {
            return state.with(Properties.HORIZONTAL_FACING, face)
                    .with(Properties.BLOCK_HALF, top ? BlockHalf.TOP : BlockHalf.BOTTOM);
        }
        if (state.getBlock() instanceof SlabBlock) {
            return state.with(Properties.SLAB_TYPE, top ? SlabType.TOP : SlabType.BOTTOM);
        }
        return state;
    }
}
