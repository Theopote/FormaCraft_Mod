package com.formacraft.server.edit;
import com.formacraft.common.patch.BlockPatch;
import net.minecraft.block.*;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.*;
import java.util.*;
import java.util.regex.Pattern;

/** Positions a small glass opening on the actual wall beside the actual entrance. */
public final class WindowInsertCompiler {
    private WindowInsertCompiler() {}
    public static boolean matches(String raw) {
        String text=raw==null?"":raw.replaceAll("\\s+","");
        return text.contains("正门右") && text.contains("玻璃窗")
                && (text.contains("增加") || text.contains("添加"))
                && !text.matches(".*(不要|别|不许)(增加|添加).*" );
    }
    public static int size(String raw,String axis) {
        var m=Pattern.compile("(\\d+)格"+axis).matcher(raw.replaceAll("\\s+",""));
        return m.find()?Integer.parseInt(m.group(1)):2;
    }
    public record Result(List<BlockPatch> patches,String reason,int entrances) {}
    public static List<BlockPatch> compile(Map<BlockPos,BlockState> live, BlockPos origin,
                                           Set<BlockPos> roof, int width,int height) {
        return analyze(live,origin,roof,width,height).patches();
    }
    public static Result analyze(Map<BlockPos,BlockState> live,BlockPos origin,Set<BlockPos> roof,int width,int height) {
        if(width<1 || height<1 || width>8 || height>8 || width*height>64) return new Result(List.of(),"WINDOW_SIZE_INVALID",0);
        Set<BlockPos> doors=new HashSet<>();
        Map<BlockPos,Direction> normals=new HashMap<>();
        Set<BlockPos> floorEdges=new HashSet<>();
        live.forEach((p,s)->{if(s.getBlock() instanceof DoorBlock && s.get(Properties.DOUBLE_BLOCK_HALF)==DoubleBlockHalf.LOWER) { doors.add(p); normals.put(p,s.get(Properties.HORIZONTAL_FACING)); }});
        // Native entrances can be open doorways without a DoorBlock. Require a floor,
        // two clear head cells, and an actual outside edge; never trust model coordinates.
        if(doors.isEmpty()) for(var e:live.entrySet()) {
            BlockPos p=e.getKey();
            if(!e.getValue().isAir() || !air(live,p.up()) || !solid(live,p.down())) continue;
            for(Direction normal:Direction.Type.HORIZONTAL) {
                boolean floorEdge=!solid(live,p.offset(normal).down()) && solid(live,p.offset(normal.getOpposite()).down());
                boolean shellEdge=air(live,p.offset(normal).up()) && air(live,p.offset(normal.getOpposite()).up())
                        && ceiling(live,p.offset(normal.getOpposite(),3)) && !ceiling(live,p.offset(normal,3));
                if(!floorEdge && !shellEdge)continue;
                Direction across=normal.rotateYClockwise();
                boolean left=false,right=false,lintel=false;
                for(int k=1;k<=3;k++) {
                    left |= solid(live,p.offset(across,k));
                    right |= solid(live,p.offset(across.getOpposite(),k));
                }
                for(int y=2;y<=4;y++) lintel |= solid(live,p.up(y));
                if(left && right && lintel) {
                    doors.add(p);normals.put(p,normal);
                    if(floorEdge) {floorEdges.add(p);break;}
                }
            }
        }
        if(!floorEdges.isEmpty())doors.retainAll(floorEdges);
        var groups=new ArrayList<Set<BlockPos>>();
        while(!doors.isEmpty()) {
            BlockPos seed=doors.iterator().next();doors.remove(seed);
            var group=new HashSet<BlockPos>();group.add(seed);var q=new ArrayDeque<BlockPos>();q.add(seed);
            while(!q.isEmpty()) {
                BlockPos p=q.remove();
                for(Direction dir:Direction.Type.HORIZONTAL) {
                    BlockPos n=p.offset(dir);
                    if(doors.contains(n) && normals.get(n)==normals.get(seed)) {
                        doors.remove(n);group.add(n);q.add(n);
                    }
                }
            }
            groups.add(group);
        }
        if(groups.size()!=1) return new Result(List.of(),groups.isEmpty()?"ENTRANCE_NOT_FOUND":"ENTRANCE_AMBIGUOUS",groups.size());
        var group=groups.getFirst();var seed=group.iterator().next();
        Direction right=normals.get(seed).rotateYCounterclockwise();
        // Old doorway carves may start at the floor block; new ones start above it.
        int sillOffset=solid(live,seed.offset(normals.get(seed).getOpposite()))?1:0;
        BlockPos edge=group.stream().max(Comparator.comparingInt(p->p.getX()*right.getOffsetX()+p.getZ()*right.getOffsetZ())).orElseThrow();
        for(int distance:new int[]{2,1,3,4,5,6}) {
            var cells=new ArrayList<BlockPos>();
            // Keep the bottom floor and the outer corner/jamb intact.
            boolean valid=solid(live,edge.offset(right,distance+width).up(sillOffset));
            for(int x=0;x<width;x++) for(int y=0;y<height;y++) {
                BlockPos p=edge.offset(right,distance+x).up(y+sillOffset);var state=live.get(p);
                if(state==null || !state.isOpaque() || state.getBlock() instanceof PillarBlock || roof.contains(p)
                        || state.getBlock() instanceof StairsBlock || state.getBlock() instanceof SlabBlock) valid=false;
                cells.add(p);
            }
            if(!valid)continue;
            return new Result(cells.stream().map(p->new BlockPatch(BlockPatch.REPLACE,p.getX()-origin.getX(),p.getY()-origin.getY(),p.getZ()-origin.getZ(),"minecraft:glass")).toList(),"READY",1);
        }
        return new Result(List.of(),"WALL_RECTANGLE_UNAVAILABLE",1);
    }
    private static boolean ceiling(Map<BlockPos,BlockState> live,BlockPos p) {
        for(int y=2;y<=16;y++)if(solid(live,p.up(y)))return true;
        return false;
    }
    private static boolean air(Map<BlockPos,BlockState> live,BlockPos p) {
        var state=live.get(p); return state==null || state.isAir();
    }
    private static boolean solid(Map<BlockPos,BlockState> live,BlockPos p) {
        var state=live.get(p); return state!=null && state.isOpaque();
    }
}
