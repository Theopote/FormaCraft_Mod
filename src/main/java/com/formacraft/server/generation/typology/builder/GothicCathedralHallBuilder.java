package com.formacraft.server.generation.typology.builder;

import com.formacraft.server.generation.structure.util.StructureSpecParsers;
import com.formacraft.common.logging.FcaLog;
import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.common.model.build.BuildingStyle;
import com.formacraft.common.style.profile.DetailPreferences;
import com.formacraft.common.style.profile.StyleProfile;
import com.formacraft.common.style.profile.StyleProfileRegistry;
import com.formacraft.common.build.GeneratedStructure;
import com.formacraft.common.build.PlannedBlock;
import com.formacraft.common.typology.TypologyParamResolver;
import com.formacraft.server.material.PaletteResolver;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parametric Gothic cathedral hall builder (typology: {@code gothic_cathedral_hall}).
 * 中殿 + 侧廊 + 飞扶壁 + 双塔尖顶 + 玫瑰窗。
 */
public final class GothicCathedralHallBuilder {

    public static final String TYPOLOGY_ID = "gothic_cathedral_hall";

    private static final FcaLog LOG = FcaLog.of("GothicCathedralHallBuilder");

    private GothicCathedralHallBuilder() {}

    public static GeneratedStructure fromBuildingSpec(BuildingSpec spec, BlockPos origin, ServerWorld world) {
        return generate(TypologyParamResolver.fromBuildingSpec(spec, TYPOLOGY_ID), origin, world, spec);
    }

    public static GeneratedStructure generate(
            Map<String, Object> params,
            BlockPos origin,
            ServerWorld world,
            BuildingSpec specHint
    ) {
        BuildingSpec spec = specHint != null ? specHint : new BuildingSpec();
        LinkedHashMap<String, Object> merged = new LinkedHashMap<>();
        if (spec.getExtra() != null) {
            merged.putAll(spec.getExtra());
        }
        if (params != null) {
            merged.putAll(params);
        }
        spec.setExtra(merged);
        int width=clampOdd(getIntExtra(spec,"width",spec.getFootprint()!=null?spec.getFootprint().getWidth():25),17,101);
        int depth=clampOdd(getIntExtra(spec,"depth",spec.getFootprint()!=null?spec.getFootprint().getDepth():45),25,151);
        BlockPos corner=cornerOrigin(origin,resolveEntranceFacing(spec),width,depth,
                getStringExtra(spec,"anchorMode","center"));
        var generated=generateFromSpec(spec, corner, world);
        var blocks=new ArrayList<PlannedBlock>(generated.getBlocks());
        if(getBooleanExtra(spec,"includeApse",false) || getBooleanExtra(spec,"includeChoir",false))
            addLiturgicalEnd(blocks,corner,resolveEntranceFacing(spec),width,depth,spec);
        return new GeneratedStructure(null,origin,generated.getDescription(),blocks);
    }

    private static GeneratedStructure generateFromSpec(BuildingSpec spec, BlockPos origin, ServerWorld world) {
        List<PlannedBlock> blocks = new ArrayList<>();

        BuildingStyle style = (spec != null && spec.getStyle() != null) ? spec.getStyle() : BuildingStyle.DEFAULT;
        StyleProfile profile = (spec != null) ? StyleProfileRegistry.resolve(spec) : StyleProfileRegistry.forStyle(style);
        DetailPreferences details = profile != null ? profile.details() : null;

        String paletteId = getStringExtra(spec, "paletteId", null);
        if ((paletteId == null || paletteId.isBlank()) && details != null && details.paletteId != null && !details.paletteId.isBlank()) {
            paletteId = details.paletteId.trim();
        }

        Direction entrance = resolveEntranceFacing(spec);

        int w = clampOdd(getIntExtra(spec, "width", spec != null && spec.getFootprint() != null ? spec.getFootprint().getWidth() : 25), 17, 101);
        int d = clampOdd(getIntExtra(spec, "depth", spec != null && spec.getFootprint() != null ? spec.getFootprint().getDepth() : 45), 25, 151);
        int wallH = clamp(getIntExtra(spec, "wallHeight", 16), 12, 40);
        int towerH = clamp(getIntExtra(spec, "towerHeight", (int) Math.round(wallH * 1.25)), wallH + 4, 80);
        int spireH = clamp(getIntExtra(spec, "spireHeight", 10), 6, 26);
        int buttressStep = clamp(getIntExtra(spec, "buttressStep", 5), 4, 8);
        int naveW = Math.max(9, w - 10);
        int naveX0 = (w - naveW) / 2;
        int naveX1 = naveX0 + naveW - 1;

        if ("cruciform".equalsIgnoreCase(getStringExtra(spec, "planVariant", "basilica"))) {
            if (w < 25) throw new IllegalArgumentException("Cruciform Gothic hall requires total width >= 25");
            return generateCruciform(spec, origin, entrance, w, d, wallH);
        }

        // Materials (palette-driven)
        BlockState foundation = Blocks.STONE_BRICKS.getDefaultState();
        BlockState wall = Blocks.STONE_BRICKS.getDefaultState();
        BlockState trim = Blocks.POLISHED_BLACKSTONE.getDefaultState();
        BlockState floor = Blocks.POLISHED_ANDESITE.getDefaultState();
        BlockState window = Blocks.RED_STAINED_GLASS_PANE.getDefaultState();
        BlockState roofTile = Blocks.DEEPSLATE_TILES.getDefaultState();
        BlockState roofSlab = Blocks.DEEPSLATE_TILE_SLAB.getDefaultState();
        BlockState roofStairs = Blocks.DEEPSLATE_TILE_STAIRS.getDefaultState();
        BlockState lantern = Blocks.LANTERN.getDefaultState();

        if (paletteId != null && !paletteId.isBlank()) {
            foundation = PaletteResolver.pick(world, paletteId, "WALL_FOUNDATION", origin, 0xC701L, foundation);
            wall = PaletteResolver.pick(world, paletteId, "WALL_BASE", origin, 0xC702L, wall);
            trim = PaletteResolver.pick(world, paletteId, "FRAME", origin, 0xC703L, trim);
            trim = PaletteResolver.pick(world, paletteId, "DECOR_DETAIL", origin, 0xC704L, trim);
            floor = PaletteResolver.pick(world, paletteId, "FLOORING", origin, 0xC705L, floor);
            window = PaletteResolver.pick(world, paletteId, "WINDOW", origin, 0xC706L, window);
            roofTile = PaletteResolver.pick(world, paletteId, "ROOF_TILE", origin, 0xC707L, roofTile);
            roofSlab = PaletteResolver.pick(world, paletteId, "FLOOR_SLAB", origin, 0xC708L, roofSlab);
            roofStairs = PaletteResolver.pick(world, paletteId, "ROOF_SLOPE", origin, 0xC709L, roofStairs);
            lantern = PaletteResolver.pick(world, paletteId, "LIGHTING", origin, 0xC70AL, lantern);
            lantern = PaletteResolver.pick(world, paletteId, "ROAD_LIGHT", origin, 0xC70BL, lantern);
        }

        // Clear (best-effort)
        int pad = 0; // Never excavate unbuilt terrain around the footprint.
        for (int x = -pad; x <= w - 1 + pad; x++) {
            for (int z = -pad; z <= d - 1 + pad; z++) {
                for (int y = 0; y <= Math.max(towerH + spireH + 12, wallH + 4 + gothicRoofRise(spec, (naveW+1)/2)); y++) {
                    blocks.add(new PlannedBlock(local(origin, entrance, x, y, z), Blocks.AIR.getDefaultState()));
                }
            }
        }

        // 1) Foundation slab + floor
        fillRect(blocks, origin, entrance, 0, 0, 0, w - 1, 1, d - 1, foundation);
        fillRect(blocks, origin, entrance, 0, 2, 0, w - 1, 2, d - 1, floor);

        // 2) Nave walls (tall) + hollow interior
        int y0 = 3;
        int y1 = y0 + wallH;
        for (int y = y0; y <= y1; y++) {
            // outer shell
            ringRect(blocks, origin, entrance, naveX0, y, 0, naveX1, y, d - 1, wall);
            // reinforce corners with trim
            put(blocks, origin, entrance, naveX0, y, 0, trim, null);
            put(blocks, origin, entrance, naveX1, y, 0, trim, null);
            put(blocks, origin, entrance, naveX0, y, d - 1, trim, null);
            put(blocks, origin, entrance, naveX1, y, d - 1, trim, null);
        }
        fillRect(blocks, origin, entrance, naveX0 + 1, y0, 1, naveX1 - 1, y1, d - 2, Blocks.AIR.getDefaultState());

        // 3) Side aisles walls (lower) to increase “vertical contrast”
        int aisleY1 = y0 + Math.max(8, wallH - 6);
        for (int y = y0; y <= aisleY1; y++) {
            ringRect(blocks, origin, entrance, 0, y, 0, w - 1, y, d - 1, wall);
        }
        fillRect(blocks, origin, entrance, 1, y0, 1, w - 2, aisleY1, d - 2, Blocks.AIR.getDefaultState());

        // One bay grid: supports occupy bay boundaries, openings occupy bay centers.
        if (getBooleanExtra(spec, "includeWindows", true)) {
            boolean tracery = getBooleanExtra(spec, "includeWindowTracery", true);
            int end = !"none".equals(towerArrangement(spec))
                    ? d - clampOdd(Math.max(7, w/5),7,15) - 3 : d-4;
            for (int boundary=4;boundary+buttressStep<=end;boundary+=buttressStep) {
                int center=boundary+buttressStep/2;
                int halfWindow=buttressStep>=7?2:1;
                for(int x:new int[]{0,w-1})
                    addPointedWindow(blocks,origin,entrance,x,center,5,
                            Math.min(6,aisleY1-5),halfWindow,false,window,trim,tracery);
                int highBottom=aisleY1+4, highHeight=y1-highBottom;
                if(highHeight>=2) for(int x:new int[]{naveX0,naveX1})
                    addPointedWindow(blocks,origin,entrance,x,center,highBottom,
                            highHeight,halfWindow,false,window,trim,false);
            }
        }

        if (getBooleanExtra(spec,"includeEntranceSteps",true)) {
            int halfStep=Math.min(6,Math.max(4,naveW/2-1));
            // A broad landing at floor level, then three supported descending treads.
            for(int x=w/2-halfStep;x<=w/2+halfStep;x++) for(int run=0;run<=3;run++) {
                int z=d+run, y=run==0?2:3-run;
                for(int yy=0;yy<y;yy++) put(blocks,origin,entrance,x,yy,z,foundation,null);
                put(blocks,origin,entrance,x,y,z,run==0?floor:withFacingIfPossible(Blocks.STONE_BRICK_STAIRS.getDefaultState(),Direction.NORTH),null);
                for(int yy=y+1;yy<=8;yy++) put(blocks,origin,entrance,x,yy,z,Blocks.AIR.getDefaultState(),null);
            }
        }

        // 5) Entrance portal opening (local SOUTH)
        int mx = w / 2;
        int zFront = d - 1;
        int doorW = 3;
        int doorH = 6;
        for (int dx = -doorW / 2; dx <= doorW / 2; dx++) {
            for (int dy = 0; dy < doorH; dy++) {
                put(blocks, origin, entrance, mx + dx, y0 + dy, zFront, Blocks.AIR.getDefaultState(), null);
            }
        }
        // Pointed portal: the tip is an opening, framed on the facade and projecting one block.
        for(int z:new int[]{zFront,zFront+1}) {
            for(int y=y0;y<y0+doorH;y++) {
                put(blocks,origin,entrance,mx-2,y,z,trim,null);
                put(blocks,origin,entrance,mx+2,y,z,trim,null);
            }
            put(blocks,origin,entrance,mx,y0+doorH,z,Blocks.AIR.getDefaultState(),null);
            put(blocks,origin,entrance,mx-1,y0+doorH,z,trim,null);
            put(blocks,origin,entrance,mx+1,y0+doorH,z,trim,null);
            put(blocks,origin,entrance,mx,y0+doorH+1,z,trim,null);
        }

        // 6) Rose window on front facade (local SOUTH)
        // Keep the complete rose above the portal and within the nave facade.
        int roseRadius = Math.max(2, Math.min(Math.min(7, w / 4), (wallH - doorH - 2) / 2));
        if (getBooleanExtra(spec, "includeRoseWindow", true))
            addRoseWindow(blocks, origin, entrance, w, y0 + doorH + 2 + roseRadius, zFront, roseRadius, window, trim);

        // 7) Roof (steep gable)
        int roofBaseY = y1 + 1;
        addGableRoof(blocks, origin, entrance, naveX0 - 1, roofBaseY, -1, naveX1 + 1, roofBaseY, d, gothicRoofRise(spec, (naveW+1)/2), roofStairs, roofSlab, roofTile, wall);

        // Lower aisle roofs rise toward the nave, keeping the tall central volume visible.
        for (int x = -1; x < naveX0; x++) {
            int y = aisleY1 + 1 + Math.max(0, x) * 3 / Math.max(1, naveX0 - 1);
            if(x>=0) for(int z:new int[]{0,d-1}) for(int sealY=aisleY1+1;sealY<y;sealY++) {
                put(blocks,origin,entrance,x,sealY,z,wall,null);
                put(blocks,origin,entrance,w-1-x,sealY,z,wall,null);
            }
            for (int z = -1; z <= d; z++) {
                put(blocks, origin, entrance, x, y, z, withFacingIfPossible(roofStairs, Direction.EAST), null);
                put(blocks, origin, entrance, w - 1 - x, y, z, withFacingIfPossible(roofStairs, Direction.WEST), null);
            }
        }

        // 8) Flying buttresses (best-effort): outer piers + diagonal braces
        if (getBooleanExtra(spec, "includeButtresses", true))
            addFlyingButtresses(blocks, origin, entrance, w, d, y0 + 2, y1 - 2, buttressStep, wall, trim, roofStairs, naveX0, naveX1);

        // 9) Front twin towers + spires
        int tw = clampOdd(Math.max(7, w / 5), 7, 15);
        int tLeftX0 = 1;
        int tLeftX1 = tLeftX0 + tw - 1;
        int tRightX1 = w - 2;
        int tRightX0 = tRightX1 - tw + 1;
        int tZ0 = d - tw - 2;
        int tZ1 = d - 2;
        if (!"none".equals(towerArrangement(spec))) {
        buildTowerWithSpire(blocks, origin, entrance, tLeftX0, tZ0, tLeftX1, tZ1, y0, towerH, spireH, wall, trim, roofStairs, roofSlab);
        if ("twin".equals(towerArrangement(spec))) buildTowerWithSpire(blocks, origin, entrance, tRightX0, tZ0, tRightX1, tZ1, y0, towerH, spireH, wall, trim, roofStairs, roofSlab);
        }

        // Opt-in interior skeleton; the cruciform crossing clears these local bays.
        if (getBooleanExtra(spec, "includeRibVault", false))
            addNaveRibs(blocks, origin, entrance, naveX0+1, naveX1-1, d, y1, buttressStep, trim);

        // a bit of lighting in entrance
        put(blocks, origin, entrance, mx - 5, y0 + 2, zFront + 1, lantern, null);
        put(blocks, origin, entrance, mx + 5, y0 + 2, zFront + 1, lantern, null);

        String desc = "Gothic Cathedral Hall (gothic_cathedral_hall)";
        return new GeneratedStructure(null, origin, desc, blocks);
    }

    /** Total width includes the projecting transept; crossing roofs form one envelope. */
    private static GeneratedStructure generateCruciform(BuildingSpec spec, BlockPos origin,
                                                        Direction entrance, int w, int d, int wallH) {
        int bodyW = w - 8, offset = 4;
        var params = new LinkedHashMap<String, Object>(spec.getExtra());
        params.put("width", bodyW);
        params.put("planVariant", "basilica");
        params.put("anchorMode", "corner");
        params.put("facing", "SOUTH");
        params.remove("layout");
        var baseSpec = new BuildingSpec();
        baseSpec.setStyle(spec.getStyle());
        var base = generate(params, BlockPos.ORIGIN, null, baseSpec);
        List<PlannedBlock> blocks = new ArrayList<>();
        for (var block : base.getBlocks()) {
            var p = block.getPos();
            put(blocks, origin, entrance, p.getX()+offset, p.getY(), p.getZ(), block.getTargetState(), null);
        }
        int naveW = Math.max(9, bodyW-10), nx0=(w-naveW)/2, nx1=nx0+naveW-1;
        int centerZ=d/3, half= Math.max(4, naveW/2), z0=centerZ-half, z1=centerZ+half;
        int top=wallH+3, roofBase=top+1, rise=gothicRoofRise(spec, (naveW+1)/2);
        // Reuse resolved materials from the longitudinal hall, including its palette.
        var states = new java.util.HashMap<BlockPos, BlockState>();
        for (var block : base.getBlocks()) states.put(block.getPos(), block.getTargetState());
        BlockState wall=states.get(new BlockPos(0,4,0));
        BlockState floor=states.get(new BlockPos(bodyW/2,2,2));
        BlockState foundation=states.get(new BlockPos(bodyW/2,0,2));
        BlockState tile=Blocks.DEEPSLATE_TILES.getDefaultState();
        BlockState stair=states.get(new BlockPos((bodyW-naveW)/2-1,roofBase,2));
        BlockState ridge=states.get(new BlockPos(bodyW/2,roofBase+rise,2));
        fillRect(blocks,origin,entrance,0,0,z0,w-1,1,z1,foundation);
        fillRect(blocks,origin,entrance,0,2,z0,w-1,2,z1,floor);
        for(int y=3;y<=top;y++) ringRect(blocks,origin,entrance,0,y,z0,w-1,y,z1,wall);
        fillRect(blocks,origin,entrance,1,3,z0+1,w-2,top,z1-1,Blocks.AIR.getDefaultState());
        // Open the longitudinal hall through both crossing faces.
        fillRect(blocks,origin,entrance,nx0+1,3,z0,nx1-1,top,z1,Blocks.AIR.getDefaultState());
        if(getBooleanExtra(spec,"includeWindows",true)) for(int x:new int[]{0,w-1})
            addPointedWindow(blocks,origin,entrance,x,centerZ,6,Math.min(9,wallH-4),2,false,
                    states.getOrDefault(new BlockPos(0,5,4+clamp(getIntExtra(spec,"buttressStep",5),4,8)/2-1),
                            Blocks.RED_STAINED_GLASS_PANE.getDefaultState()),
                    states.getOrDefault(new BlockPos(0,4,4+clamp(getIntExtra(spec,"buttressStep",5),4,8)/2),
                            Blocks.POLISHED_BLACKSTONE.getDefaultState()),
                    getBooleanExtra(spec,"includeWindowTracery",true));
        // At the crossing, use the higher of the two slopes and remove lower roof remnants.
        for(int x=-1;x<=w;x++) for(int z=z0-1;z<=z1+1;z++) {
            boolean onNave=x>=nx0-1 && x<=nx1+1;
            int transverseInset=Math.min(z-(z0-1),(z1+1)-z);
            int transverseY=roofBase+(int)Math.round((double)transverseInset*rise/(half+1));
            int longitudinalY=onNave ? roofBase+(int)Math.round((double)Math.min(x-(nx0-1),(nx1+1)-x)*rise/((naveW+1)/2)) : roofBase;
            int roofY=Math.max(transverseY,longitudinalY);
            for(int y=roofBase;y<=roofBase+rise;y++) put(blocks,origin,entrance,x,y,z,Blocks.AIR.getDefaultState(),null);
            boolean longitudinal=onNave && longitudinalY>=transverseY;
            Direction slope=longitudinal ? (x<=w/2?Direction.EAST:Direction.WEST) : (z<=centerZ?Direction.SOUTH:Direction.NORTH);
            // Seal only the vertical step between adjacent slope columns; leave the attic empty.
            int previousY=longitudinal
                    ? roofBase+(int)Math.round((double)Math.max(0,Math.min(x-(nx0-1),(nx1+1)-x)-1)*rise/((naveW+1)/2))
                    : roofBase+(int)Math.round((double)Math.max(0,transverseInset-1)*rise/(half+1));
            for(int y=Math.max(roofBase,previousY);y<roofY;y++)
                put(blocks,origin,entrance,x,y,z,tile,null);
            // Gables belong on the wall plane, never on the overhanging roof edge.
            if(x==0 || x==w-1) for(int y=roofBase;y<previousY;y++)
                put(blocks,origin,entrance,x,y,z,wall,null);
            put(blocks,origin,entrance,x,roofY,z,roofY==roofBase+rise?ridge:withFacingIfPossible(stair,slope),null);
        }
        return new GeneratedStructure(null,origin,"Gothic cruciform hall",blocks);
    }

    /** Discrete pointed arch: two lights below, a small upper glass eye, and a complete frame. */
    private static void addPointedWindow(List<PlannedBlock> blocks, BlockPos origin, Direction entrance,
                                         int fixed, int center, int bottom, int height, int halfWidth,
                                         boolean alongX, BlockState glass, BlockState frame, boolean tracery) {
        for(int dy=0;dy<height;dy++) {
            int radius=dy>=height-2?Math.max(0,halfWidth-(dy-(height-3))):halfWidth;
            for(int u=-halfWidth-1;u<=halfWidth+1;u++) {
                BlockState state;
                if(Math.abs(u)>radius) state=frame;
                else if(tracery && u==0 && dy<height-3) state=frame;
                else state=glass;
                put(blocks,origin,entrance,alongX?center+u:fixed,bottom+dy,
                        alongX?fixed:center+u,state,null);
            }
        }
        for(int u=-halfWidth-1;u<=halfWidth+1;u++)
            put(blocks,origin,entrance,alongX?center+u:fixed,bottom-1,
                    alongX?fixed:center+u,frame,null);
        put(blocks,origin,entrance,alongX?center:fixed,bottom+height,
                alongX?fixed:center,frame,null);
    }

    private static void addRoseWindow(List<PlannedBlock> blocks, BlockPos origin, Direction entrance,
                                      int w, int yCenter, int z, int r,
                                      BlockState glass, BlockState frame) {
        int cx = w / 2;
        int r2 = r * r;
        int rInner2 = Math.max(0, (r - 1) * (r - 1));
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                int d2 = dx * dx + dy * dy;
                if (d2 > r2) continue;
                BlockState s = (d2 >= rInner2) ? frame : glass;
                put(blocks, origin, entrance, cx + dx, yCenter + dy, z, s, null);
            }
        }
        // simple mullion cross
        if (r >= 3) {
            for (int dy = -r; dy <= r; dy++) put(blocks, origin, entrance, cx, yCenter + dy, z, frame, null);
            for (int dx = -r; dx <= r; dx++) put(blocks, origin, entrance, cx + dx, yCenter, z, frame, null);
        }
    }

    private static void addFlyingButtresses(List<PlannedBlock> blocks, BlockPos origin, Direction entrance,
                                           int w, int d, int yFrom, int yTo, int step,
                                           BlockState wall, BlockState trim, BlockState stairs,
                                           int naveX0, int naveX1) {
        // Thin rising arch above the aisle roof, joined to a solid pier and the nave wall.
        for(int z=4;z<=d-5;z+=step) {
            for(int side=0;side<2;side++) {
                int outer=side==0?1:w-2, inner=side==0?naveX0:naveX1;
                for(int y=3;y<=yTo+2;y++) put(blocks,origin,entrance,outer,y,z,wall,null);
                int span=Math.abs(inner-outer), previous=yTo;
                for(int i=0;i<=span;i++) {
                    double t=(double)i/Math.max(1,span);
                    int y=yTo+(int)Math.round(2*Math.sin(t*Math.PI/2));
                    int x=outer+(side==0?i:-i);
                    for(int yy=Math.min(previous,y);yy<=y;yy++) put(blocks,origin,entrance,x,yy,z,trim,null);
                    previous=y;
                }
            }
        }
    }

    private static void buildTowerWithSpire(List<PlannedBlock> blocks, BlockPos origin, Direction entrance,
                                           int x0, int z0, int x1, int z1, int y0,
                                           int towerH, int spireH,
                                           BlockState wall, BlockState trim,
                                           BlockState stairs, BlockState slab) {
        int y1 = y0 + towerH;
        for (int y = y0; y <= y1; y++) {
            ringRect(blocks, origin, entrance, x0, y, z0, x1, y, z1, wall);
            put(blocks, origin, entrance, x0, y, z0, trim, null);
            put(blocks, origin, entrance, x1, y, z0, trim, null);
            put(blocks, origin, entrance, x0, y, z1, trim, null);
            put(blocks, origin, entrance, x1, y, z1, trim, null);
        }
        fillRect(blocks, origin, entrance, x0 + 1, y0, z0 + 1, x1 - 1, y1, z1 - 1, Blocks.AIR.getDefaultState());

        // Height-based taper: width must not prematurely terminate a tall spire.
        int half=(x1-x0)/2, cx=(x0+x1)/2, cz=(z0+z1)/2;
        int yBase=y1+1;
        fillRect(blocks,origin,entrance,x0,y1,z0,x1,y1,z1,trim);
        for(int i=0;i<spireH;i++) {
            int radius=(int)Math.ceil((double)half*(spireH-1-i)/Math.max(1,spireH-1));
            ringRect(blocks,origin,entrance,cx-radius,yBase+i,cz-radius,cx+radius,yBase+i,cz+radius,trim);
            put(blocks,origin,entrance,cx,yBase+i,cz,trim,null);
        }
        put(blocks,origin,entrance,cx,yBase+spireH,cz,slab,null);
    }

    /** A clipped terminal volume within the declared footprint, plus an open choir aisle. */
    private static void addLiturgicalEnd(List<PlannedBlock> blocks, BlockPos origin, Direction facing,
                                        int width, int depth, BuildingSpec spec) {
        int bodyW=width-("cruciform".equalsIgnoreCase(getStringExtra(spec,"planVariant","basilica"))?8:0);
        int naveW=Math.max(9,bodyW-10), left=(width-naveW)/2, right=left+naveW-1;
        int center=width/2, top=3+clamp(getIntExtra(spec,"wallHeight",16),12,40);
        int rise=gothicRoofRise(spec,(naveW+1)/2), base=top+1;
        var states=new java.util.HashMap<BlockPos,BlockState>();
        for(var b:blocks) states.put(b.getPos(),b.getTargetState());
        BlockState wall=states.get(local(origin,facing,(width-bodyW)/2,4,0));
        BlockState foundation=states.get(local(origin,facing,center,0,2));
        BlockState floor=states.get(local(origin,facing,center,2,2));
        BlockState tile=states.getOrDefault(local(origin,facing,left+1,
                base+(int)Math.round((double)rise/((naveW+1)/2)),2),Blocks.DEEPSLATE_TILES.getDefaultState());
        if(getBooleanExtra(spec,"includeApse",false)) {
            // The rear four rows narrow symmetrically; reserve the total depth, never extend the anchor bounds.
            for(int z=-1;z<=5;z++) for(int x=-1;x<=width;x++) {
                for(int y=0;y<=base+rise;y++) put(blocks,origin,facing,x,y,z,Blocks.AIR.getDefaultState(),null);
                int inset=Math.max(0,3-z), lo=left+inset, hi=right-inset;
                if(z<0 || x<lo || x>hi) continue;
                put(blocks,origin,facing,x,0,z,foundation,null);
                put(blocks,origin,facing,x,1,z,foundation,null);
                put(blocks,origin,facing,x,2,z,floor,null);
                int roofY=base+(int)Math.round((double)Math.min(x-(left-1),(right+1)-x)*rise/((naveW+1)/2));
                int previous=base+(int)Math.round((double)Math.max(0,Math.min(x-(left-1),(right+1)-x)-1)*rise/((naveW+1)/2));
                boolean boundary=z==0 || x==lo || x==hi;
                if(boundary) for(int y=3;y<roofY;y++) put(blocks,origin,facing,x,y,z,wall,null);
                for(int y=previous;y<=roofY;y++) put(blocks,origin,facing,x,y,z,tile,null);
            }
            if(naveW>=11 && getBooleanExtra(spec,"includeWindows",true))
                addPointedWindow(blocks,origin,facing,0,center,6,5,1,true,
                        Blocks.RED_STAINED_GLASS_PANE.getDefaultState(),Blocks.POLISHED_BLACKSTONE.getDefaultState(),
                        getBooleanExtra(spec,"includeWindowTracery",true));
        }
        if(getBooleanExtra(spec,"includeChoir",false)) {
            // Paired choir stalls face the center, leaving a three-block clear longitudinal route.
            for(int z=6;z<=7;z++) {
                put(blocks,origin,facing,left+1,3,z,withFacingIfPossible(Blocks.OAK_STAIRS.getDefaultState(),Direction.WEST),null);
                put(blocks,origin,facing,right-1,3,z,withFacingIfPossible(Blocks.OAK_STAIRS.getDefaultState(),Direction.EAST),null);
            }
        }
    }

    /** Open pointed transverse ribs, supported by paired interior shafts. No solid vault infill. */
    private static void addNaveRibs(List<PlannedBlock> blocks, BlockPos origin, Direction entrance,
                                    int left, int right, int depth, int top, int step, BlockState material) {
        int center=(left+right)/2, half=Math.max(1,center-left), spring=top-5;
        for(int z=4;z<=depth-12;z+=step) {
            for(int x:new int[]{left,right}) for(int y=3;y<=spring;y++)
                put(blocks,origin,entrance,x,y,z,material,null);
            for(int x=left;x<=right;x++) {
                int inset=Math.min(x-left,right-x);
                int y=spring+(int)Math.round(5.0*inset/half);
                int previous=spring+(int)Math.round(5.0*Math.max(0,inset-1)/half);
                for(int yy=previous;yy<=y;yy++) put(blocks,origin,entrance,x,yy,z,material,null);
            }
        }
    }

    private static String towerArrangement(BuildingSpec spec) {
        if(!getBooleanExtra(spec,"includeTowers",true)) return "none";
        String value=getStringExtra(spec,"towerArrangement","twin").toLowerCase(java.util.Locale.ROOT);
        if(!java.util.Set.of("none","single","twin").contains(value))
            throw new IllegalArgumentException("Unsupported Gothic tower arrangement: "+value);
        return value;
    }

    private static void addGableRoof(List<PlannedBlock> blocks, BlockPos origin, Direction entrance,
                                     int x0, int y0, int z0, int x1, int y1, int z1,
                                     int h, BlockState stairs, BlockState slab, BlockState cap, BlockState gableWall) {
        int half = Math.max(1, (x1 - x0) / 2);
        for (int x = x0; x <= x1; x++) {
            int inset = Math.min(x - x0, x1 - x);
            int y = y0 + (int) Math.round((double) inset * h / half);
            BlockState material = inset == half ? slab : stairs;
            Direction slope = x <= (x0+x1)/2 ? Direction.EAST : Direction.WEST;
            for (int z = z0; z <= z1; z++) {
                if ((z == z0+1 || z == z1-1) && x>x0 && x<x1)
                    for (int sealY = y0; sealY < y; sealY++)
                        put(blocks, origin, entrance, x, sealY, z, gableWall, null);
                int previousY = y0 + (int) Math.round((double) Math.max(0, inset - 1) * h / half);
                for (int sealY = previousY; sealY < y; sealY++)
                    put(blocks, origin, entrance, x, sealY, z, cap, null);
                put(blocks, origin, entrance, x, y, z, withFacingIfPossible(material, slope), null);
            }
        }
    }

    // --- primitives + rotation helpers (same conventions as other generators) ---

    private static void fillRect(List<PlannedBlock> blocks, BlockPos origin, Direction entrance,
                                 int x0, int y0, int z0, int x1, int y1, int z1, BlockState s) {
        for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) for (int y = y0; y <= y1; y++) put(blocks, origin, entrance, x, y, z, s, null);
    }

    private static void ringRect(List<PlannedBlock> blocks, BlockPos origin, Direction entrance,
                                 int x0, int y, int z0, int x1, int y1, int z1, BlockState s) {
        int yy = y;
        for (int x = x0; x <= x1; x++) {
            put(blocks, origin, entrance, x, yy, z0, s, null);
            put(blocks, origin, entrance, x, yy, z1, s, null);
        }
        for (int z = z0; z <= z1; z++) {
            put(blocks, origin, entrance, x0, yy, z, s, null);
            put(blocks, origin, entrance, x1, yy, z, s, null);
        }
    }

    private static void put(List<PlannedBlock> blocks, BlockPos origin, Direction entrance,
                            int x, int y, int z, BlockState state, Direction face) {
        if (state == null) return;
        BlockState s = state;
        if (face != null) {
            s = withFacingIfPossible(s, rotateDir(face, entrance));
        } else {
            s = rotateState(s, entrance);
        }
        blocks.add(new PlannedBlock(local(origin, entrance, x, y, z), s));
    }

    /** Mouse anchor is the body bottom center; attached stairs do not move that center. */
    public static BlockPos cornerOrigin(BlockPos anchor, Direction facing, int width, int depth, String mode) {
        if ("corner".equalsIgnoreCase(mode) || "bottom_left".equalsIgnoreCase(mode)) return anchor;
        return local(anchor,facing,-width/2,0,-depth/2);
    }

    private static BlockPos local(BlockPos origin, Direction entrance, int x, int y, int z) {
        BlockPos p = origin.add(x, y, z);
        return rotatePos(p, origin, entrance);
    }

    private static BlockPos rotatePos(BlockPos p, BlockPos base, Direction entrance) {
        if (entrance == null || entrance == Direction.SOUTH) return p;
        int dx = p.getX() - base.getX();
        int dy = p.getY() - base.getY();
        int dz = p.getZ() - base.getZ();
        return switch (entrance) {
            case NORTH -> base.add(-dx, dy, -dz);
            case EAST -> base.add(dz, dy, -dx);
            case WEST -> base.add(-dz, dy, dx);
            default -> p;
        };
    }

    private static Direction rotateDir(Direction local, Direction entrance) {
        if (entrance == null || entrance == Direction.SOUTH || local == null) return local;
        if (!local.getAxis().isHorizontal()) return local;
        return switch (entrance) {
            case NORTH -> (local == Direction.NORTH) ? Direction.SOUTH
                    : (local == Direction.SOUTH) ? Direction.NORTH
                    : (local == Direction.EAST) ? Direction.WEST
                    : Direction.EAST;
            case EAST -> (local == Direction.SOUTH) ? Direction.EAST
                    : (local == Direction.NORTH) ? Direction.WEST
                    : (local == Direction.EAST) ? Direction.NORTH
                    : Direction.SOUTH;
            case WEST -> (local == Direction.SOUTH) ? Direction.WEST
                    : (local == Direction.NORTH) ? Direction.EAST
                    : (local == Direction.EAST) ? Direction.SOUTH
                    : Direction.NORTH;
            default -> local;
        };
    }

    private static BlockState rotateState(BlockState s, Direction entrance) {
        if (s == null) return null;
        BlockState out = s;
        try {
            if (out.contains(Properties.HORIZONTAL_FACING)) {
                Direction f = out.get(Properties.HORIZONTAL_FACING);
                out = out.with(Properties.HORIZONTAL_FACING, rotateDir(f, entrance));
            }
            if (out.contains(Properties.FACING)) {
                Direction f = out.get(Properties.FACING);
                out = out.with(Properties.FACING, rotateDir(f, entrance));
            }
            if (out.contains(Properties.AXIS)) {
                if (entrance == Direction.EAST || entrance == Direction.WEST) {
                    var axis = out.get(Properties.AXIS);
                    if (axis == Direction.Axis.X) out = out.with(Properties.AXIS, Direction.Axis.Z);
                    else if (axis == Direction.Axis.Z) out = out.with(Properties.AXIS, Direction.Axis.X);
                }
            }
        } catch (Throwable ex) { LOG.debug("best-effort step failed", ex); }
        return out;
    }

    private static BlockState withFacingIfPossible(BlockState s, Direction facing) {
        if (s == null || facing == null) return s;
        try {
            if (s.contains(Properties.HORIZONTAL_FACING) && facing.getAxis().isHorizontal()) return s.with(Properties.HORIZONTAL_FACING, facing);
            if (s.contains(Properties.FACING)) return s.with(Properties.FACING, facing);
        } catch (Throwable ex) { LOG.debug("best-effort step failed", ex); }
        return s;
    }

    private static Direction resolveEntranceFacing(BuildingSpec spec) {
        if (spec == null || spec.getExtra() == null) return Direction.SOUTH;
        try {
            Object layoutObj = spec.getExtra().get("layout");
            Object ef = layoutObj instanceof Map<?, ?> m ? m.get("entranceFacing") : null;
            if (ef == null) ef = spec.getExtra().get("facing");
            if (ef != null) {
                String value = String.valueOf(ef).trim().toUpperCase(java.util.Locale.ROOT);
                return switch (value) {
                    case "N", "NORTH", "北", "朝北" -> Direction.NORTH;
                    case "S", "SOUTH", "南", "朝南" -> Direction.SOUTH;
                    case "E", "EAST", "东", "朝东" -> Direction.EAST;
                    case "W", "WEST", "西", "朝西" -> Direction.WEST;
                    default -> Direction.SOUTH;
                };
            }
        } catch (Throwable ex) { LOG.debug("best-effort step failed", ex); }
        return Direction.SOUTH;
    }

    private static String getStringExtra(BuildingSpec spec, String key, String def) {
        if (spec == null || spec.getExtra() == null) return def;
        Object v = spec.getExtra().get(key);
        if (v == null) return def;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? def : s;
    }

    private static boolean getBooleanExtra(BuildingSpec spec, String key, boolean fallback) {
        if (spec == null || spec.getExtra() == null) return fallback;
        Object value = spec.getExtra().get(key);
        if (value instanceof Boolean flag) return flag;
        if (value instanceof String text) {
            if ("false".equalsIgnoreCase(text)) return false;
            if ("true".equalsIgnoreCase(text)) return true;
        }
        return fallback;
    }

    private static int getIntExtra(BuildingSpec spec, String key, int def) {
        return StructureSpecParsers.extraInt(spec, key, def);
    }

    // Default rise/run 1.2 is a stylized steep Gothic roof, not a historical universal.
    // Explicit rise overrides the style default; span changes must not flatten the roof.
    private static int gothicRoofRise(BuildingSpec spec, int halfSpan) {
        return clamp(getIntExtra(spec, "roofRise", (int)Math.ceil(halfSpan*1.2)), 3, 80);
    }

    private static int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }
    private static int clampOdd(int v, int min, int max) {
        int x = clamp(v, min, max);
        return (x % 2 == 0) ? (x + 1 <= max ? x + 1 : x - 1) : x;
    }
}


