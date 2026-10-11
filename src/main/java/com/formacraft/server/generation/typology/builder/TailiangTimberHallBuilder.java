package com.formacraft.server.generation.typology.builder;

import com.formacraft.common.build.GeneratedStructure;
import com.formacraft.common.build.PlannedBlock;
import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.common.model.build.BuildingStyle;
import com.formacraft.common.style.profile.DetailPreferences;
import com.formacraft.common.style.profile.StyleProfile;
import com.formacraft.common.style.profile.StyleProfileRegistry;
import com.formacraft.common.typology.TypologyParamResolver;
import com.formacraft.common.typology.TypologyParams;
import com.formacraft.common.typology.detail.ChineseTypologyDetailUtil;
import com.formacraft.server.generation.structure.ChineseHallRoofGenerator;
import com.formacraft.server.generation.structure.util.StructureSpecParsers;
import com.formacraft.server.material.PaletteResolver;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Parametric tailiang timber hall builder (typology: {@code tailiang_timber_hall}).
 */
public final class TailiangTimberHallBuilder {

    public static final String TYPOLOGY_ID = "tailiang_timber_hall";

    private TailiangTimberHallBuilder() {}

    /** Canonical foundation envelope, shared by generation and terrain placement. */
    public record Envelope(int width, int depth, int subDepth, int height) {
        public int outerWidth() { return width + 2 * subDepth; }
        public int outerDepth() { return depth + 2 * subDepth; }
    }

    /** Explicit overall dimensions win; bay counts subdivide the span between end columns. */
    public static int[] bayAxis(int cells, int bays) {
        if (cells - 1 < 2 * bays) throw new IllegalArgumentException(
                "木构尺寸 " + cells + " 格不足以布置 " + bays + " 个开间，请增大尺寸或减少开间数。");
        int[] axis = new int[bays + 1];
        for (int i=0;i<=bays;i++) axis[i]=(int)Math.round((cells-1)*i/(double)bays);
        return axis;
    }

    public static Envelope envelope(Map<String, Object> params) {
        TypologyParams p = new TypologyParams(params);
        int bay = p.intVal("bayWidth", 3, 2, 4);
        return new Envelope(
                clampOdd(p.intVal("width", p.intVal("baysX", 7, 5, 9) * bay, 15, 35), 15, 35),
                clampOdd(p.intVal("depth", p.intVal("baysZ", 4, 3, 5) * bay, 11, 25), 11, 25),
                p.boolVal("includeSubEaves", true) ? p.intVal("subEavesDepth", 3, 2, 5) : 0,
                p.intVal("platformHeight", 2, 1, 4) + p.intVal("hallHeight", 7, 5, 12) + 20);
    }

    public static GeneratedStructure fromBuildingSpec(BuildingSpec spec, BlockPos origin, ServerWorld world) {
        return generate(TypologyParamResolver.fromBuildingSpec(spec, TYPOLOGY_ID), origin, world, spec);
    }

    public static GeneratedStructure generate(
            Map<String, Object> params,
            BlockPos origin,
            ServerWorld world,
            BuildingSpec specHint
    ) {
        TypologyParams p = new TypologyParams(params);
        List<PlannedBlock> blocks = new ArrayList<>(8000);

        BuildingStyle style = (specHint != null && specHint.getStyle() != null)
                ? specHint.getStyle() : BuildingStyle.ASIAN;
        StyleProfile profile = (specHint != null)
                ? StyleProfileRegistry.resolve(specHint)
                : StyleProfileRegistry.forStyle(style);
        DetailPreferences details = profile != null ? profile.details() : null;

        String paletteId = p.strVal("paletteId", null);
        if ((paletteId == null || paletteId.isBlank()) && details != null
                && details.paletteId != null && !details.paletteId.isBlank()) {
            paletteId = details.paletteId.trim();
        }

        Direction entrance = specHint != null
                ? StructureSpecParsers.resolveEntranceFacing(specHint, Direction.SOUTH)
                : facingFromParam(p.strVal("facing", "SOUTH"));

        int baysX = p.intVal("baysX", 7, 5, 9);
        int baysZ = p.intVal("baysZ", 4, 3, 5);
        int bayWidth = p.intVal("bayWidth", 3, 2, 4);
        Envelope envelope = envelope(params);
        int w = envelope.width();
        int d = envelope.depth();
        int platformH = p.intVal("platformHeight", 2, 1, 4);
        int bodyH = p.intVal("hallHeight", 7, 5, 12);
        boolean subEaves = p.boolVal("includeSubEaves", true);
        int subDepth = envelope.subDepth();

        int outerW = w + subDepth * 2;
        int outerD = d + subDepth * 2;
        BlockPos anchor = origin;
        if (!"corner".equalsIgnoreCase(p.strVal("anchorMode", "center"))) {
            origin = rotate(origin.add(-outerW / 2, 0, -outerD / 2), origin, entrance);
        }
        final BlockPos placedOrigin = origin;
        int ox = subDepth;
        int oz = subDepth;
        int[] xAxis = bayAxis(w, baysX), zAxis = bayAxis(d, baysZ);
        var mainColumns = columnPositions(ox, oz, xAxis, zAxis);
        int doorBay = baysX / 2;
        int doorMin = ox + xAxis[doorBay] + 1, doorMax = ox + xAxis[doorBay + 1] - 1;
        var outerColumns = columnPositions(0,0,bayAxis(outerW,Math.max(1,(outerW-1)/bayWidth)),
                bayAxis(outerD,Math.max(1,(outerD-1)/bayWidth)));
        if (p.boolVal("includeEntrance", true)) outerColumns.removeIf(col ->
                col[1] == outerD - 1 && col[0] >= doorMin && col[0] <= doorMax);

        BlockState stone = Blocks.STONE_BRICKS.getDefaultState();
        BlockState pillar = Blocks.DARK_OAK_LOG.getDefaultState();
        BlockState wall = Blocks.RED_TERRACOTTA.getDefaultState();
        BlockState trim = Blocks.DARK_OAK_PLANKS.getDefaultState();
        BlockState bracket = Blocks.SPRUCE_STAIRS.getDefaultState();
        BlockState paint = Blocks.GREEN_TERRACOTTA.getDefaultState();
        BlockState lattice = Blocks.IRON_BARS.getDefaultState();
        BlockState roofTile = Blocks.DEEPSLATE_TILES.getDefaultState();
        BlockState roofStairs = Blocks.DEEPSLATE_BRICK_STAIRS.getDefaultState();
        BlockState roofSlab = Blocks.DEEPSLATE_TILE_SLAB.getDefaultState();

        if (paletteId != null && !paletteId.isBlank()) {
            stone = PaletteResolver.pick(world, paletteId, "WALL_FOUNDATION", origin, 0xF00001L, stone);
            pillar = PaletteResolver.pick(world, paletteId, "PILLAR", origin, 0xF00002L, pillar);
            wall = PaletteResolver.pick(world, paletteId, "WALL_BASE", origin, 0xF00003L, wall);
            trim = PaletteResolver.pick(world, paletteId, "DECOR_DETAIL", origin, 0xF00004L, trim);
            bracket = PaletteResolver.pick(world, paletteId, "ROOF_SLOPE", origin, 0xF00008L, bracket);
            roofTile = PaletteResolver.pick(world, paletteId, "ROOF_TILE", origin, 0xF00005L, roofTile);
            roofStairs = PaletteResolver.pick(world, paletteId, "ROOF_SLOPE", origin, 0xF00006L, roofStairs);
            roofSlab = PaletteResolver.pick(world, paletteId, "FLOOR_SLAB", origin, 0xF00007L, roofSlab);
        }

        stone = explicitMaterial(p,"foundation_block",stone);
        BlockState floor = explicitMaterial(p,"floor_block",stone);
        pillar = explicitMaterial(p,"pillar_block",pillar);
        wall = explicitMaterial(p,"wall_block",wall);
        trim = explicitMaterial(p,"trim_block",trim);
        bracket = explicitMaterial(p,"bracket_block",bracket);
        lattice = explicitMaterial(p,"window_block",lattice);
        paint = explicitMaterial(p,"paint_block",paint);
        if (!p.boolVal("includePainting",true)) paint = null;
        BlockState terraceStair = roofStairs;
        roofTile = explicitMaterial(p,"roof_block",roofTile);
        boolean explicitRoof = p.raw().containsKey("roof_block");
        roofStairs = explicitMaterial(p,"roof_stairs_block",explicitRoof?roofTile:roofStairs);
        roofSlab = explicitMaterial(p,"roof_slab_block",explicitRoof?roofTile:roofSlab);

        int clearH = envelope.height();
        int roofOverhang = p.intVal("roofOverhang", 1, 0, 3);
        int clearMargin = Math.max(2, roofOverhang);
        for (int x = -clearMargin; x < outerW + clearMargin; x++) {
            for (int z = -clearMargin; z < outerD + clearMargin; z++) {
                for (int y = 0; y <= clearH; y++) {
                    put(blocks, origin, entrance, x, y, z, Blocks.AIR.getDefaultState());
                }
            }
        }

        fillRect(blocks, origin, entrance, 0, 0, 0, outerW - 1, platformH - 1, outerD - 1, stone);
        fillRect(blocks, origin, entrance, 0, platformH, 0, outerW - 1, platformH, outerD - 1, floor);
        ringRect(blocks, origin, entrance, 0, platformH, 0, outerW - 1, platformH, outerD - 1, trim);

        int yCol0 = platformH + 1;
        int yCol1 = yCol0 + bodyH - 2;
        int yWallTop = yCol0 + bodyH - 1;
        int bodyTop = platformH + bodyH;
        boolean interiorColumns = p.boolVal("includeInteriorColumns", true);
        boolean timberFrame = p.boolVal("includeTimberFrame", true);
        int frameLevels = p.intVal("frameLevels", Math.min(3, bodyH - 3), 1, 4);
        if (timberFrame && frameLevels > bodyH - 3) throw new IllegalArgumentException(
                "梁架层数 " + frameLevels + " 超出当前堂高可用空间，请增大 hallHeight 或减少 frameLevels。");
        int frameBase = timberFrame ? yCol1 - frameLevels + 1 : yCol1;

        for (int[] col : mainColumns) placeColumn(blocks, origin, entrance, col[0], yCol0, col[1], yCol1, pillar, trim);
        if (subEaves) {
            for (int[] col : outerColumns) placeColumn(blocks,origin,entrance,col[0],yCol0,col[1],yCol1-1,pillar,trim);
            addSubEavesBeams(blocks, origin, entrance, ox, oz, ox + w - 1, oz + d - 1, outerW - 1, outerD - 1, yCol1, trim);
        }

        fillWallsBetweenColumns(blocks, origin, entrance, ox, oz, xAxis, zAxis, yCol0, yWallTop, wall);
        if (p.boolVal("includeWindows", true))
            addLatticeWindows(blocks, origin, entrance, ox, oz, xAxis, zAxis, yCol0, yWallTop, trim, lattice);
        if (p.boolVal("includeEntrance", true))
            carveMainEntrance(blocks, origin, entrance, ox, oz, xAxis, d, yCol0);
        ringRect(blocks, origin, entrance, ox, yCol1, oz, ox+w-1, yCol1, oz+d-1, trim);

        List<PlannedBlock> roofBlocks = new ArrayList<>();
        String roofForm = ChineseHallRoofGenerator.normalize(p.strVal("roofType", "xie_shan"));
        int roofRun = roofForm.equals("wu_dian") ? Math.min((w-1)/2,(d-1)/2)+roofOverhang : (w-1)/2+roofOverhang;
        int roofRise = p.intVal("roofRise", roofRun, 3, 18);
        int roofCurve = p.intVal("roofCurve",0,0,100), roofCornerLift=p.intVal("roofCornerLift",0,0,3);
        ChineseHallRoofGenerator.generate(
                roofBlocks, w, d, bodyTop, roofForm, roofRise,
                new ChineseHallRoofGenerator.Shape(roofOverhang,roofCurve,roofCornerLift), roofTile, roofStairs, wall);
        Map<BlockPos, Integer> roofBottom = new java.util.HashMap<>();
        for (PlannedBlock roofBlock : roofBlocks) {
            BlockPos pos = roofBlock.getPos();
            roofBottom.merge(new BlockPos(ox+pos.getX(),0,oz+pos.getZ()),pos.getY(),Math::min);
        }
        if (subEaves) for (int x=0;x<outerW;x++) for (int z=0;z<outerD;z++)
            if (x<ox||x>=ox+w||z<oz||z>=oz+d)
                roofBottom.merge(new BlockPos(x,0,z),bodyTop,Math::min);
        if (p.boolVal("includeBrackets", true)) addPuzuoAtColumns(blocks, origin, entrance, ox, oz, ox + w - 1, oz + d - 1,
                mainColumns, yCol1, roofBottom, trim, bracket, paint);
        if (subEaves) {
            if (p.boolVal("includeBrackets", true)) addPuzuoAtColumns(blocks, origin, entrance, 0, 0, outerW - 1, outerD - 1,
                    outerColumns, yCol1, roofBottom, trim, bracket, paint);
            int innerRoofY = bodyTop;
            ChineseTypologyDetailUtil.addSubEavesInnerRoof(
                    (pos, state) -> put(blocks, placedOrigin, entrance, pos.getX(), pos.getY(), pos.getZ(), state),
                    ox, oz, ox + w - 1, oz + d - 1,
                    0, 0, outerW - 1, outerD - 1,
                    innerRoofY, roofSlab, roofStairs
            );
        }

        for (PlannedBlock pb : roofBlocks) {
            put(blocks, origin, entrance, ox + pb.getPos().getX(), pb.getPos().getY(), oz + pb.getPos().getZ(), pb.getTargetState());
        }

        // Frames stay below the roof plane; no roof blocks are carved or replaced here.
        if (interiorColumns) {
            for (int xi=1;xi<xAxis.length-1;xi++) for (int zi=1;zi<zAxis.length-1;zi++)
                placeColumn(blocks, origin, entrance, ox+xAxis[xi], yCol0, oz+zAxis[zi],
                        frameBase-1, pillar, trim);
        }
        if (timberFrame) addInteriorFrame(blocks, origin, entrance, ox, oz, w, d, zAxis,
                frameBase, frameLevels, pillar, trim);

        if (p.boolVal("includeEntrance", true) && p.boolVal("includeEntranceSteps", true))
            addTerraceSteps(blocks, origin, entrance, (doorMin+doorMax)/2, outerD, platformH, terraceStair);

        String ref = p.strVal("reference_landmark", "");
        String desc = String.format(
                "木构大殿 tailiang_timber_hall（主体%d×%d格，外台基%d×%d格；开间%d×%d，堂高%d格，台基高%d格，入口%s，锚点%s；"
                        +"屋顶%s，升高%d格，出檐%d格，曲率%d，翘角%d格；副檐%s，内柱%s，梁架%d层，斗拱%s，彩画%s；"
                        +"墙=%s，柱=%s，地面=%s，台基=%s，梁枋=%s，屋面=%s，坡面=%s，副檐材=%s，窗=%s，斗拱材=%s，彩画材=%s；参考=%s）",
                w,d,outerW,outerD,baysX,baysZ,bodyH,platformH,entrance.name(),
                "corner".equalsIgnoreCase(p.strVal("anchorMode","center"))?"corner":"center",
                roofForm,roofRise,roofOverhang,roofCurve,roofCornerLift,
                subEaves,interiorColumns,timberFrame?frameLevels:0,p.boolVal("includeBrackets",true),
                paint!=null&&p.boolVal("includeBrackets",true),
                materialId(wall),materialId(pillar),materialId(floor),materialId(stone),materialId(trim),
                materialId(roofTile),materialId(roofStairs),materialId(roofSlab),materialId(lattice),materialId(bracket),
                paint==null||!p.boolVal("includeBrackets",true)?"禁用":materialId(paint),ref.isBlank()?"-":ref
        );
        com.formacraft.FormacraftMod.LOGGER.info("[ChineseHall] {}",desc);
        return new GeneratedStructure(null, anchor, desc, blocks);
    }

    private static void addSubEavesBeams(List<PlannedBlock> blocks, BlockPos origin, Direction entrance,
                                         int ix0, int iz0, int ix1, int iz1, int ox1, int oz1, int y, BlockState beam) {
        ringRect(blocks, origin, entrance, ix0 - 1, y, iz0 - 1, ix1 + 1, y, iz1 + 1, beam);
        ringRect(blocks, origin, entrance, 0, y, 0, ox1, y, oz1, beam);
    }

    private static BlockState explicitMaterial(TypologyParams params,String key,BlockState fallback) {
        if(!params.raw().containsKey(key)) return fallback;
        String value=params.strVal(key,"");
        net.minecraft.util.Identifier id=net.minecraft.util.Identifier.tryParse(value);
        if(id==null||!net.minecraft.registry.Registries.BLOCK.containsId(id))
            throw new IllegalArgumentException("E_MATERIAL_INVALID: "+key+"="+value);
        BlockState state=net.minecraft.registry.Registries.BLOCK.get(id).getDefaultState();
        if(state.isAir()||!state.getFluidState().isEmpty())
            throw new IllegalArgumentException("E_MATERIAL_INVALID: "+key+"="+value+" 必须为非空气、非流体方块");
        return state;
    }

    private static String materialId(BlockState state) {
        return net.minecraft.registry.Registries.BLOCK.getId(state.getBlock()).toString();
    }

    private static BlockState timberAxis(BlockState timber, Direction.Axis axis, BlockState fallback) {
        return timber.contains(Properties.AXIS) ? timber.with(Properties.AXIS, axis) : fallback;
    }

    private static void addInteriorFrame(List<PlannedBlock> blocks, BlockPos origin, Direction entrance,
                                          int x0, int z0, int w, int d, int[] zs, int baseY, int levels,
                                          BlockState timber, BlockState trim) {
        BlockState crossBeam = timberAxis(timber, Direction.Axis.X, trim);
        BlockState purlin = timberAxis(timber, Direction.Axis.Z, trim);
        BlockState post = timberAxis(timber, Direction.Axis.Y, trim);
        for(int level=0;level<levels;level++) {
            int start=level*2, end=w-1-level*2, y=baseY+level;
            for(int zi=1;zi<zs.length-1;zi++) {
                int z=z0+zs[zi];
                if(level>0) {
                    put(blocks,origin,entrance,x0+start,y-1,z,post);
                    put(blocks,origin,entrance,x0+end,y-1,z,post);
                }
                for(int x=start;x<=end;x++) put(blocks,origin,entrance,x0+x,y,z,crossBeam);
            }
            // Longitudinal links stop inside the enclosure, preserving window and wall columns.
            if (level>0) for(int x:new int[]{start,end}) for(int z=1;z<d-1;z++)
                put(blocks,origin,entrance,x0+x,y,z0+z,purlin);
        }
    }

    private static List<int[]> columnPositions(int x0, int z0, int[] xs, int[] zs) {
        List<int[]> columns = new ArrayList<>();
        for (int x : xs) {
            columns.add(new int[]{x0+x,z0});
            columns.add(new int[]{x0+x,z0+zs[zs.length-1]});
        }
        for (int i=1;i<zs.length-1;i++) {
            columns.add(new int[]{x0,z0+zs[i]});
            columns.add(new int[]{x0+xs[xs.length-1],z0+zs[i]});
        }
        return columns;
    }

    private static void addLatticeWindows(List<PlannedBlock> blocks, BlockPos origin, Direction entrance,
                                          int x0, int z0, int[] xs, int[] zs, int y0, int yTop,
                                          BlockState frame, BlockState infill) {
        int bottom=y0+1, top=Math.min(yTop-1,y0+3);
        for (int i=0;i<xs.length-1;i++) {
            int start=xs[i]+1, end=xs[i+1]-1;
            for(int x=start;x<=end;x++) for(int y=bottom;y<=top;y++) {
                BlockState state=y==bottom || y==top ? frame : infill;
                put(blocks,origin,entrance,x0+x,y,z0,state);
                put(blocks,origin,entrance,x0+x,y,z0+zs[zs.length-1],state);
            }
        }
        for (int i=0;i<zs.length-1;i++) {
            int start=zs[i]+1,end=zs[i+1]-1;
            for(int z=start;z<=end;z++) for(int y=bottom;y<=top;y++) {
                BlockState state=y==bottom || y==top ? frame : infill;
                put(blocks,origin,entrance,x0,y,z0+z,state);
                put(blocks,origin,entrance,x0+xs[xs.length-1],y,z0+z,state);
            }
        }
    }

    private static void carveMainEntrance(List<PlannedBlock> blocks, BlockPos origin, Direction entrance,
                                          int x0, int z0, int[] xs, int depth, int y0) {
        int bay=(xs.length-1)/2;
        for(int x=xs[bay]+1;x<xs[bay+1];x++) for(int y=y0;y<=y0+2;y++)
            put(blocks,origin,entrance,x0+x,y,z0+depth-1,Blocks.AIR.getDefaultState());
    }

    private static void addPuzuoAtColumns(List<PlannedBlock> blocks, BlockPos origin, Direction entrance,
                                          int x0, int z0, int x1, int z1, List<int[]> columns,
                                          int y, Map<BlockPos,Integer> roofBottom,
                                          BlockState timber, BlockState gong, BlockState paint) {
        for (int[] col : columns) {
            int x = col[0];
            int z = col[1];
            boolean corner = ChineseTypologyDetailUtil.isCornerColumn(x, z, x0, z0, x1, z1);
            Direction out = ChineseTypologyDetailUtil.outwardFromRectEdge(x, z, x0, z0, x1, z1);
            addBracketPost(blocks,origin,entrance,x,z,y,roofBottom,timber,null);
            List<Direction> arms = corner ? List.of(out,x==x0?Direction.WEST:Direction.EAST) : List.of(out);
            for (Direction arm : arms) {
                int ax=x+arm.getOffsetX(),az=z+arm.getOffsetZ();
                Integer bottom=roofBottom.get(new BlockPos(ax,0,az));
                if(bottom==null||bottom<=y) continue;
                // One horizontal arm touches the shaft; its cap ends strictly below the roof.
                put(blocks,origin,entrance,ax,y-1,az,withFacing(gong,arm));
                addBracketPost(blocks,origin,entrance,ax,az,y,roofBottom,timber,paint);
            }
        }
    }

    private static void addBracketPost(List<PlannedBlock> blocks,BlockPos origin,Direction entrance,
                                       int x,int z,int y,Map<BlockPos,Integer> roofBottom,
                                       BlockState timber,BlockState paint) {
        Integer bottom=roofBottom.get(new BlockPos(x,0,z));
        if(bottom==null) return;
        for(int level=y;level<bottom;level++)
            put(blocks,origin,entrance,x,level,z,paint!=null&&level==bottom-1?paint:timber);
    }

    private static void addTerraceSteps(List<PlannedBlock> blocks, BlockPos origin, Direction entrance,
                                          int mx, int outerDepth, int platformH, BlockState stair) {
        for (int tier = 0; tier < 3; tier++) {
            int z = outerDepth + tier;
            int y = platformH - tier;
            if (y < 0) break;
            for (int dx = -3 + tier; dx <= 3 - tier; dx++) {
                put(blocks, origin, entrance, mx + dx, y, z, withFacing(stair, Direction.NORTH));
            }
        }
    }

    private static void fillWallsBetweenColumns(List<PlannedBlock> blocks, BlockPos origin, Direction entrance,
                                                int x0, int z0, int[] xs, int[] zs,
                                                int y0, int yTop, BlockState wall) {
        for(int y=y0;y<=yTop;y++) {
            for(int i=0;i<xs.length-1;i++) for(int x=xs[i]+1;x<xs[i+1];x++) {
                put(blocks,origin,entrance,x0+x,y,z0,wall);
                put(blocks,origin,entrance,x0+x,y,z0+zs[zs.length-1],wall);
            }
            for(int i=0;i<zs.length-1;i++) for(int z=zs[i]+1;z<zs[i+1];z++) {
                put(blocks,origin,entrance,x0,y,z0+z,wall);
                put(blocks,origin,entrance,x0+xs[xs.length-1],y,z0+z,wall);
            }
        }
    }

    private static void placeColumn(List<PlannedBlock> blocks, BlockPos origin, Direction entrance,
                                    int x, int y0, int z, int y1,
                                    BlockState pillar, BlockState capital) {
        for (int y = y0; y <= y1; y++) {
            put(blocks, origin, entrance, x, y, z, pillar);
        }
        put(blocks, origin, entrance, x, y1 + 1, z, capital);
    }

    private static void fillRect(List<PlannedBlock> blocks, BlockPos origin, Direction entrance,
                                 int x0, int y0, int z0, int x1, int y1, int z1, BlockState s) {
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                for (int y = y0; y <= y1; y++) {
                    put(blocks, origin, entrance, x, y, z, s);
                }
            }
        }
    }

    private static void ringRect(List<PlannedBlock> blocks, BlockPos origin, Direction entrance,
                                 int x0, int y, int z0, int x1, int y1, int z1, BlockState s) {
        for (int x = x0; x <= x1; x++) {
            put(blocks, origin, entrance, x, y, z0, s);
            put(blocks, origin, entrance, x, y, z1, s);
        }
        for (int z = z0; z <= z1; z++) {
            put(blocks, origin, entrance, x0, y, z, s);
            put(blocks, origin, entrance, x1, y, z, s);
        }
    }

    private static void put(List<PlannedBlock> blocks, BlockPos origin, Direction entrance, int x, int y, int z, BlockState state) {
        if (entrance != null) state = state.rotate(switch (entrance) {
            case NORTH -> net.minecraft.util.BlockRotation.CLOCKWISE_180;
            case EAST -> net.minecraft.util.BlockRotation.COUNTERCLOCKWISE_90;
            case WEST -> net.minecraft.util.BlockRotation.CLOCKWISE_90;
            default -> net.minecraft.util.BlockRotation.NONE;
        });
        blocks.add(new PlannedBlock(rotate(origin.add(x, y, z), origin, entrance), state));
    }

    private static BlockPos rotate(BlockPos local, BlockPos origin, Direction entrance) {
        int lx = local.getX() - origin.getX();
        int ly = local.getY() - origin.getY();
        int lz = local.getZ() - origin.getZ();
        if (entrance == null || entrance == Direction.SOUTH) {
            return origin.add(lx, ly, lz);
        }
        return switch (entrance) {
            case NORTH -> origin.add(-lx, ly, -lz);
            case EAST -> origin.add(lz, ly, -lx);
            case WEST -> origin.add(-lz, ly, lx);
            default -> origin.add(lx, ly, lz);
        };
    }

    private static BlockState withFacing(BlockState state, Direction facing) {
        if (state != null && state.contains(Properties.HORIZONTAL_FACING)) {
            return state.with(Properties.HORIZONTAL_FACING, facing);
        }
        return state;
    }

    private static Direction facingFromParam(String facing) {
        try {
            return Direction.valueOf(facing.trim().toUpperCase());
        } catch (Exception e) {
            return Direction.SOUTH;
        }
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static int clampOdd(int v, int min, int max) {
        v = clamp(v, min, max);
        if (v % 2 == 0) v += 1;
        return clamp(v, min, max);
    }
}
