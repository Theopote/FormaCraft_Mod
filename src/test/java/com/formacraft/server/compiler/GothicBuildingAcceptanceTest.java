package com.formacraft.server.compiler;
import com.formacraft.server.generation.typology.builder.GothicCathedralHallBuilder;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class GothicBuildingAcceptanceTest {
    @Test void cruciformApseMustPreserveAllTranseptExteriorWalls() {
        for (String facing : List.of("SOUTH", "NORTH", "EAST", "WEST")) {
            var anchor = new BlockPos(39, 105, -1037);
            var structure = GothicCathedralHallBuilder.generate(Map.of(
                    "width", 35, "depth", 45, "wallHeight", 16,
                    "facing", facing, "planVariant", "cruciform",
                    "includeApse", true, "includeChoir", true,
                    "includeRibVault", true, "includeTowers", false), anchor, null, null);
            var corner = GothicCathedralHallBuilder.cornerOrigin(anchor,
                    net.minecraft.util.math.Direction.valueOf(facing), 35, 45, "center");
            var states = new HashMap<BlockPos, BlockState>();
            for (var block : structure.getBlocks()) states.put(block.getPos(), block.getTargetState());
            for (int x : List.of(0, 34)) for (int z = 7; z <= 23; z++) for (int y = 3; y <= 19; y++)
                assertFalse(states.get(position(corner, facing, x, y, z)).isAir(),
                        "transept wall lost: " + facing + " " + x + "," + y + "," + z);
            for (int x = 12; x <= 22; x++) for (int y = 3; y <= 19; y++)
                assertFalse(states.get(position(corner, facing, x, y, 0)).isAir(), "rear wall lost");
            for (int z = 1; z < 44; z++) for (int y = 3; y <= 5; y++)
                assertTrue(states.get(position(corner, facing, 17, y, z)).isAir(), "central route blocked");
        }
    }
    private static com.formacraft.common.build.GeneratedStructure generateCorner(Map<String,Object> params,
            BlockPos origin, net.minecraft.server.world.ServerWorld world, com.formacraft.common.model.build.BuildingSpec spec) {
        var copy=new HashMap<String,Object>(params); copy.put("anchorMode","corner");
        return GothicCathedralHallBuilder.generate(copy,origin,world,spec);
    }
    @Test void mouseAnchorCentersBodyAndEntranceStairsRemainSupportedAcrossFacings() {
        for(String facing:List.of("SOUTH","NORTH","EAST","WEST")) for(String variant:List.of("basilica","cruciform")) {
            var anchor=new BlockPos(100,64,-80);
            var structure=GothicCathedralHallBuilder.generate(Map.of("width",35,"depth",45,"facing",facing,
                    "planVariant",variant,"includeTowers",false),anchor,null,null);
            assertEquals(anchor,structure.getOrigin());
            var corner=GothicCathedralHallBuilder.cornerOrigin(anchor,net.minecraft.util.math.Direction.valueOf(facing),35,45,"center");
            var states=new HashMap<BlockPos,BlockState>();
            for(var block:structure.getBlocks()) states.put(block.getPos(),block.getTargetState());
            assertEquals(anchor,position(corner,facing,17,0,22));
            assertFalse(states.get(anchor).isAir(),"bottom center foundation");
            for(int run=0;run<=3;run++) {
                int y=run==0?2:3-run;
                for(int x=13;x<=21;x++) {
                    assertFalse(states.get(position(corner,facing,x,y,45+run)).isAir(),"broad tread");
                    for(int yy=0;yy<y;yy++) assertFalse(states.get(position(corner,facing,x,yy,45+run)).isAir(),"solid support");
                    if(run>0 || Math.abs(x-17)<=1) assertTrue(states.get(position(corner,facing,x,y+1,45+run)).isAir(),"head clearance");
                }
            }
        }
    }
    @Test void facetedApseAndChoirKeepTheirFloorRoofAndCentralConnection() {
        for(String facing:List.of("SOUTH","NORTH","EAST","WEST")) for(String variant:List.of("basilica","cruciform")) {
            var anchor=new BlockPos(100,64,-80);
            var structure=GothicCathedralHallBuilder.generate(Map.of("width",35,"depth",45,"facing",facing,
                    "planVariant",variant,"includeTowers",false,"includeApse",true,"includeChoir",true),anchor,null,null);
            var corner=GothicCathedralHallBuilder.cornerOrigin(anchor,net.minecraft.util.math.Direction.valueOf(facing),35,45,"center");
            var states=new HashMap<BlockPos,BlockState>();
            for(var b:structure.getBlocks()) states.put(b.getPos(),b.getTargetState());
            int nave=variant.equals("cruciform")?17:25, left=(35-nave)/2;
            assertTrue(states.get(position(corner,facing,left,2,0)).isAir(),"clipped rear corner");
            for(int z=1;z<44;z++) for(int y=3;y<=5;y++)
                assertTrue(states.get(position(corner,facing,17,y,z)).isAir(),"apse and choir must connect to nave");
            for(int z=0;z<=5;z++) for(int x=left+Math.max(0,3-z);x<=34-left-Math.max(0,3-z);x++) {
                assertFalse(states.get(position(corner,facing,x,2,z)).isAir(),"terminal floor complete");
                boolean roof=false;
                for(int y=20;y<=40;y++) {
                    var state=states.get(position(corner,facing,x,y,z));
                    roof|=state!=null&&!state.isAir();
                }
                assertTrue(roof,"every terminal column has roof");
            }
            assertTrue(states.get(position(corner,facing,left+1,3,6)).getBlock().getTranslationKey().contains("oak_stairs"));
            assertTrue(states.get(position(corner,facing,17,22,4)).isAir(),"attic not filled solid");
        }
    }
    @org.junit.jupiter.api.BeforeAll static void bootstrap() { com.formacraft.test.MinecraftRegistryTestBootstrap.initialize(); }
    @Test void completeHallPreservesPortalNaveRoseAndTwinTowersAcrossSizesAndFacings() {
        for(int width:List.of(17,25,35)) for(String facing:List.of("SOUTH","NORTH","EAST","WEST")) {
            int depth=35, wallHeight=width==17?12:16;
            var origin=new BlockPos(20,50,-10);
            var building=generateCorner(Map.of("width",width,"depth",depth,
                    "wallHeight",wallHeight,"facing",facing),origin,null,null);
            var finalStates=new HashMap<BlockPos,BlockState>();
            for(var block:building.getBlocks()) finalStates.put(block.getPos(),block.getTargetState());
            int center=width/2;
            for(int z=2;z<depth;z++) for(int y=3;y<=4;y++) {
                var state=finalStates.get(position(origin,facing,center,y,z));
                assertNotNull(state); assertTrue(state.isAir(),"portal-to-nave route must be open: "+width+" "+facing+" z="+z);
            }
            for(int x=center-1;x<=center+1;x++) for(int y=3;y<9;y++)
                assertTrue(finalStates.get(position(origin,facing,x,y,depth-1)).isAir(),"door opening must reach floor and stay clear");
            assertTrue(finalStates.entrySet().stream().anyMatch(e->e.getValue().getBlock().getTranslationKey().contains("stained_glass")),"rose/cathedral glazing must survive final writes");
            int naveWidth=Math.max(9,width-10), naveStart=(width-naveWidth)/2;
            int roofStart=naveStart-1, roofEnd=naveStart+naveWidth;
            for(int x=roofStart;x<=roofEnd;x++) for(int z=0;z<depth;z++) {
                boolean covered=false;
                for(int y=wallHeight+4;y<=Math.max(wallHeight+30,wallHeight+4+(int)Math.ceil((naveWidth+1)/2*1.2));y++) {
                    var state=finalStates.get(position(origin,facing,x,y,z));
                    if(state!=null && !state.isAir()) covered=true;
                }
                assertTrue(covered,"every nave roof column must be covered: x="+x+" z="+z);
            }
            for(int x:List.of(0,width-1)) for(int z=0;z<depth;z++) {
                boolean covered=false;
                for(int y=12;y<=wallHeight+3;y++) {
                    var state=finalStates.get(position(origin,facing,x,y,z));
                    if(state!=null && !state.isAir()) covered=true;
                }
                assertTrue(covered,"both aisles require roofs");
            }
            int radius=Math.min(Math.min(7,width/4),(wallHeight-8)/2);
            int roseY=11+radius;
            var roseGlass=finalStates.get(position(origin,facing,center+(radius>=3?1:0),roseY+(radius>=3?1:0),depth-1));
            assertNotNull(roseGlass);
            assertTrue(roseGlass.getBlock().getTranslationKey().contains("stained_glass"),"front rose glass must not be erased by its mullions");
            for(int x:List.of(1,width-2))
                assertFalse(finalStates.get(position(origin,facing,x,20,depth-3)).isAir(),"both front tower bodies must remain");
        }
    }
    @Test void interiorRibsAreSupportedRotatedOptionalAndKeepTheCentralRouteOpen() {
        for(String facing:List.of("SOUTH","NORTH","EAST","WEST")) for(boolean enabled:List.of(false,true)) {
            var origin=new BlockPos(20,50,-10);
            var structure=generateCorner(Map.of("width",35,"depth",45,
                    "wallHeight",16,"facing",facing,"includeTowers",false,"includeButtresses",false,
                    "includeRibVault",enabled),origin,null,null);
            var states=new HashMap<BlockPos,BlockState>();
            for(var b:structure.getBlocks()) states.put(b.getPos(),b.getTargetState());
            for(int y=3;y<=14;y++) assertEquals(enabled,!states.get(position(origin,facing,6,y,4)).isAir());
            assertEquals(enabled,!states.get(position(origin,facing,17,19,4)).isAir());
            for(int z=2;z<45;z++) for(int y=3;y<=5;y++)
                assertTrue(states.get(position(origin,facing,17,y,z)).isAir(),"central circulation");
            assertTrue(states.get(position(origin,facing,17,16,4)).isAir(),"rib underside stays open");
            assertTrue(states.get(position(origin,facing,17,19,5)).isAir(),"no solid vault infill between bays");
        }
    }

    @Test void optionalElementsCanBeRemovedWithoutBlockingTheHall() {
        var origin=BlockPos.ORIGIN;
        var building=generateCorner(Map.of("width",25,"depth",35,"wallHeight",16,
                "includeRoseWindow",false,"includeButtresses",false,"includeTowers",false),origin,null,null);
        var states=new HashMap<BlockPos,BlockState>();
        for(var block:building.getBlocks()) states.put(block.getPos(),block.getTargetState());
        assertTrue(states.get(new BlockPos(1,20,32)).isAir(),"disabled tower body must be absent");
        assertTrue(states.get(new BlockPos(1,8,4)).isAir(),"disabled buttress pier must be absent");
        assertFalse(states.get(new BlockPos(13,16,34)).getBlock().getTranslationKey().contains("glass"),"disabled rose must retain the facade wall");
        for(int z=2;z<35;z++) for(int y=3;y<=4;y++) assertTrue(states.get(new BlockPos(12,y,z)).isAir());
    }
    @Test void cruciformFootprintCrossingAndRoofSurviveFinalWrites() {
        for(int width:List.of(25,35)) for(String facing:List.of("SOUTH","NORTH","EAST","WEST")) {
            int depth=45, top=19, centerZ=depth/3, half=Math.max(4,Math.max(9,width-18)/2);
            var origin=new BlockPos(20,50,-10);
            var structure=generateCorner(Map.of("width",width,"depth",depth,
                    "wallHeight",16,"facing",facing,"planVariant","cruciform",
                    "includeTowers",false,"includeButtresses",false),origin,null,null);
            var states=new HashMap<BlockPos,BlockState>();
            for(var b:structure.getBlocks()) states.put(b.getPos(),b.getTargetState());
            assertNull(states.get(position(origin,facing,0,2,2)),"unbuilt corners must preserve terrain");
            for(int x=1;x<width-1;x++) {
                assertFalse(states.get(position(origin,facing,x,2,centerZ)).isAir(),"transept floor");
                for(int y=3;y<=top;y++) assertTrue(states.get(position(origin,facing,x,y,centerZ)).isAir(),"crossing must stay open");
            }
            for(int x=0;x<width;x++) for(int z=centerZ-half;z<=centerZ+half;z++) {
                boolean covered=false;
                for(int y=top+1;y<=top+1+(int)Math.ceil((half+1)*1.2);y++) {
                    var state=states.get(position(origin,facing,x,y,z));
                    if(state!=null&&!state.isAir()) covered=true;
                }
                assertTrue(covered,"transept/crossing roof coverage");
            }
            for(int z=2;z<depth;z++) for(int y=3;y<=4;y++)
                assertTrue(states.get(position(origin,facing,width/2,y,z)).isAir(),"longitudinal route");
        }
    }
    @Test void roofsLeaveAtticsOpenUseWallGablesAndPreserveSurroundingTerrain() {
        for(String variant:List.of("basilica","cruciform")) {
            int width=35, depth=45, bodyW=variant.equals("cruciform")?27:35;
            int naveW=bodyW-10, half=(naveW+1)/2, rise=(int)Math.ceil(half*1.2);
            var building=generateCorner(Map.of("width",width,"depth",depth,
                    "wallHeight",16,"planVariant",variant,"includeTowers",false,
                    "includeButtresses",false),BlockPos.ORIGIN,null,null);
            var states=new HashMap<BlockPos,BlockState>();
            for(var b:building.getBlocks()) states.put(b.getPos(),b.getTargetState());
            assertNull(states.get(new BlockPos(-2,0,2)),"clear must not dig the surroundings");
            assertTrue(states.get(new BlockPos(width/2,21,depth/3)).isAir(),"crossing attic must remain hollow");
            assertEquals(net.minecraft.block.Blocks.STONE_BRICKS,states.get(new BlockPos(width/2,21,depth-1)).getBlock(),"longitudinal gable uses wall material");
            assertFalse(states.containsKey(new BlockPos(width/2,21,depth)),"gable must not become a roof-material curtain at the overhang");
            assertTrue(rise>=half,"default Gothic slope stays steep as span increases");
            assertFalse(states.get(new BlockPos(width/2,20+rise,2)).isAir(),"ridge matches span-based rise");
            if(variant.equals("cruciform")) {
                assertEquals(net.minecraft.block.Blocks.STONE_BRICKS,states.get(new BlockPos(0,21,depth/3)).getBlock());
                for(int x=0;x<width;x++) for(int z=depth/3-half+1;z<=depth/3+half-1;z++)
                    assertFalse(states.get(new BlockPos(x,2,z)).isAir(),"no holes in occupied foundation floor");
            }
        }
    }
    @Test void aisleRoofEndWallsCloseTrianglesWithoutFillingTheInterior() {
        for(String variant:List.of("basilica","cruciform")) {
            int width=35, bodyWidth=variant.equals("cruciform")?27:35, offset=(width-bodyWidth)/2;
            int naveStart=(bodyWidth-(bodyWidth-10))/2, aisleTop=13;
            var result=generateCorner(Map.of("width",width,"depth",45,
                    "wallHeight",16,"planVariant",variant,"includeTowers",false,
                    "includeButtresses",false),BlockPos.ORIGIN,null,null);
            var states=new HashMap<BlockPos,BlockState>();
            for(var b:result.getBlocks()) states.put(b.getPos(),b.getTargetState());
            for(int x=0;x<naveStart;x++) {
                int roofY=aisleTop+1+x*3/(naveStart-1);
                for(int sideX:List.of(offset+x,width-1-offset-x))
                    for(int y=aisleTop+1;y<roofY;y++) {
                        for(int z:List.of(0,44)) assertEquals(net.minecraft.block.Blocks.STONE_BRICKS,
                                states.get(new BlockPos(sideX,y,z)).getBlock(),"side aisle end triangle must be walled");
                        assertTrue(states.get(new BlockPos(sideX,y,2)).isAir(),"side roof interior remains hollow");
                    }
            }
        }
    }
    @Test void pointedWindowLightsFramesAndBayBoundariesSurviveAcrossFacings() {
        for(int step:List.of(4,5,7,8)) for(String facing:List.of("SOUTH","NORTH","EAST","WEST")) {
            var origin=new BlockPos(10,60,-20);
            var building=generateCorner(Map.of("width",35,"depth",45,"wallHeight",16,
                    "facing",facing,"buttressStep",step,"includeTowers",false),origin,null,null);
            var states=new HashMap<BlockPos,BlockState>();
            for(var b:building.getBlocks()) states.put(b.getPos(),b.getTargetState());
            int center=4+step/2;
            for(int x:List.of(0,34)) {
                for(int z:List.of(center-1,center+1))
                    assertTrue(states.get(position(origin,facing,x,6,z)).getBlock().getTranslationKey().contains("stained_glass"),"two separate lower lights");
                assertEquals(net.minecraft.block.Blocks.POLISHED_BLACKSTONE,states.get(position(origin,facing,x,6,center)).getBlock(),"lower mullion");
                assertTrue(states.get(position(origin,facing,x,10,center)).getBlock().getTranslationKey().contains("stained_glass"),"upper pointed glass tip");
                assertFalse(states.get(position(origin,facing,x,10,center-1)).getBlock().getTranslationKey().contains("glass"),"opening narrows at top");
                assertFalse(states.get(position(origin,facing,x,6,4)).getBlock().getTranslationKey().contains("glass"),"support bay boundary is not a window");
            }
            assertFalse(states.get(position(origin,facing,1,8,4)).isAir(),"buttress remains on bay boundary");
            assertTrue(states.get(position(origin,facing,17,9,44)).isAir(),"pointed portal tip is open");
            assertEquals(net.minecraft.block.Blocks.POLISHED_BLACKSTONE,states.get(position(origin,facing,15,6,45)).getBlock(),"portal frame has actual depth");
        }
    }
    @Test void traceryAndFacadeWindowOptoutsAreIndependentOfRoseWindow() {
        for(boolean windows:List.of(true,false)) {
            var building=generateCorner(Map.of("width",35,"depth",45,"wallHeight",16,
                    "planVariant","cruciform","includeTowers",false,"includeWindowTracery",false,
                    "includeWindows",windows),BlockPos.ORIGIN,null,null);
            var states=new HashMap<BlockPos,BlockState>();
            for(var b:building.getBlocks()) states.put(b.getPos(),b.getTargetState());
            assertEquals(windows,states.get(new BlockPos(4,6,6)).getBlock().getTranslationKey().contains("stained_glass"),"plain window has no lower mullion; optout restores wall");
            assertEquals(windows,states.get(new BlockPos(0,7,15)).getBlock().getTranslationKey().contains("stained_glass"),"same choice reaches transept end");
            assertTrue(states.values().stream().anyMatch(v->v.getBlock().getTranslationKey().contains("stained_glass")),"rose remains independent");
        }
    }
    @Test void towerConfigurationsAndFullSpireHeightsAreRealGeometry() {
        for(String arrangement:List.of("none","single","twin")) for(int spire:List.of(6,10,20)) {
            var result=generateCorner(Map.of("width",35,"depth",45,"wallHeight",16,
                    "towerHeight",24,"spireHeight",spire,"towerArrangement",arrangement,
                    "includeButtresses",false),BlockPos.ORIGIN,null,null);
            var states=new HashMap<BlockPos,BlockState>();
            for(var b:result.getBlocks()) states.put(b.getPos(),b.getTargetState());
            int left=4,right=30,z=39,base=28;
            for(int y=base;y<=base+spire;y++) {
                assertEquals(!arrangement.equals("none"),!states.get(new BlockPos(left,y,z)).isAir(),"left spire reaches requested height continuously");
                assertEquals(arrangement.equals("twin"),!states.get(new BlockPos(right,y,z)).isAir(),"right spire only exists for twin arrangement");
            }
            for(int y=3;y<=8;y++) assertTrue(states.get(new BlockPos(17,y,44)).isAir(),"tower configuration preserves entrance");
        }
    }
    @Test void flyingArchIsSupportedAboveTheAisleAndLeavesAirBelow() {
        var result=generateCorner(Map.of("width",35,"depth",45,"wallHeight",16,
                "includeTowers",false),BlockPos.ORIGIN,null,null);
        var states=new HashMap<BlockPos,BlockState>();
        for(var b:result.getBlocks()) states.put(b.getPos(),b.getTargetState());
        for(int x:List.of(1,33)) for(int y=3;y<=19;y++) assertFalse(states.get(new BlockPos(x,y,4)).isAir(),"pier reaches floor");
        for(int x=1;x<=5;x++) {
            int y=17+(int)Math.round(2*Math.sin((double)(x-1)/4*Math.PI/2));
            assertFalse(states.get(new BlockPos(x,y,4)).isAir(),"arch meets pier and nave at both ends");
        }
        assertTrue(states.get(new BlockPos(3,15,4)).isAir(),"arch is not a solid triangle");
    }
    private BlockPos position(BlockPos origin,String facing,int x,int y,int z) {
        return switch(facing) {
            case "NORTH" -> origin.add(-x,y,-z);
            case "EAST" -> origin.add(z,y,-x);
            case "WEST" -> origin.add(-z,y,x);
            default -> origin.add(x,y,z);
        };
    }
}
