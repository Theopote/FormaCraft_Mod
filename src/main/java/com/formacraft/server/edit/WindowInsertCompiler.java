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
        Direction outward=normals.get(seed);
        // Prefer the player's "right of door", then fall back to the opposite jamb when that
        // strip already holds a punched window (common on small cabins with symmetric glass).
        int sillOffset=solid(live,seed.offset(outward.getOpposite()))?1:0;
        for (Direction along : List.of(outward.rotateYCounterclockwise(), outward.rotateYClockwise())) {
            Result hit = rectangleBeside(live, origin, roof, group, along, sillOffset, width, height);
            if (!hit.patches().isEmpty()) return hit;
        }
        // Last resort: intact WxH on a flanking wall near the door (narrow cabins often already
        // spent the entrance façade on symmetric glass, leaving only the side shells solid).
        for (Direction flank : List.of(outward.rotateYCounterclockwise(), outward.rotateYClockwise())) {
            Result side = rectangleOnFlank(live, origin, roof, group, outward, flank, sillOffset, width, height);
            if (!side.patches().isEmpty()) return side;
        }
        return new Result(List.of(),"WALL_RECTANGLE_UNAVAILABLE",1);
    }

    private static Result rectangleBeside(Map<BlockPos,BlockState> live, BlockPos origin, Set<BlockPos> roof,
                                          Set<BlockPos> group, Direction along, int sillOffset, int width, int height) {
        BlockPos edge=group.stream()
                .max(Comparator.comparingInt(p->p.getX()*along.getOffsetX()+p.getZ()*along.getOffsetZ()))
                .orElseThrow();
        for(int distance:new int[]{2,1,3,4,5,6}) {
            var cells=new ArrayList<BlockPos>();
            boolean valid=true;
            for(int x=0;x<width;x++) for(int y=0;y<height;y++) {
                BlockPos p=edge.offset(along,distance+x).up(y+sillOffset);var state=live.get(p);
                if(!usableWall(state, roof.contains(p))) valid=false;
                cells.add(p);
            }
            if(valid) return ready(cells, origin);
        }
        return new Result(List.of(),"WALL_RECTANGLE_UNAVAILABLE",1);
    }

    private static Result rectangleOnFlank(Map<BlockPos,BlockState> live, BlockPos origin, Set<BlockPos> roof,
                                           Set<BlockPos> group, Direction outward, Direction flank,
                                           int sillOffset, int width, int height) {
        BlockPos jamb=group.stream()
                .max(Comparator.comparingInt(p->p.getX()*flank.getOffsetX()+p.getZ()*flank.getOffsetZ()))
                .orElseThrow();
        // Step onto the flanking façade just past the corner, then walk inward along -outward.
        BlockPos corner=jamb.offset(flank);
        Direction inward=outward.getOpposite();
        for (int distance : new int[]{1, 2, 3, 4, 5, 6}) {
            var cells=new ArrayList<BlockPos>();
            boolean valid=true;
            for (int x=0;x<width;x++) for (int y=0;y<height;y++) {
                BlockPos p=corner.offset(inward, distance+x).up(y+sillOffset);
                // Refuse the outermost corner column itself.
                if (x==0 && distance==0) valid=false;
                var state=live.get(p);
                if (!usableWall(state, roof.contains(p))) valid=false;
                // Must remain an exterior shell cell (air outside the flank normal).
                if (!air(live, p.offset(flank))) valid=false;
                cells.add(p);
            }
            if (valid) return ready(cells, origin);
        }
        return new Result(List.of(),"WALL_RECTANGLE_UNAVAILABLE",1);
    }

    private static boolean usableWall(BlockState state, boolean roofCell) {
        return state!=null && state.isOpaque() && !(state.getBlock() instanceof PillarBlock)
                && !(state.getBlock() instanceof StairsBlock) && !(state.getBlock() instanceof SlabBlock)
                && !roofCell;
    }

    private static Result ready(List<BlockPos> cells, BlockPos origin) {
        return new Result(cells.stream().map(p->new BlockPatch(BlockPatch.REPLACE,
                p.getX()-origin.getX(),p.getY()-origin.getY(),p.getZ()-origin.getZ(),"minecraft:glass")).toList(),"READY",1);
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
