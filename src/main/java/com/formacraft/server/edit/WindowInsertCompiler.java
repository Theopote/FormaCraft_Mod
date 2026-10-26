package com.formacraft.server.edit;
import com.formacraft.common.patch.BlockPatch;
import net.minecraft.block.*;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.state.property.Properties;
import net.minecraft.registry.tag.BlockTags;
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
    public static List<BlockPatch> compile(Map<BlockPos,BlockState> live, BlockPos origin,
                                           Set<BlockPos> roof, int width,int height) {
        if(width<1 || height<1 || width>8 || height>8 || width*height>64) return List.of();
        Set<BlockPos> doors=new HashSet<>();
        live.forEach((p,s)->{if(s.getBlock() instanceof DoorBlock && s.get(Properties.DOUBLE_BLOCK_HALF)==DoubleBlockHalf.LOWER) doors.add(p);});
        var groups=new ArrayList<Set<BlockPos>>();
        while(!doors.isEmpty()) {
            BlockPos seed=doors.iterator().next();doors.remove(seed);
            var group=new HashSet<BlockPos>();group.add(seed);var q=new ArrayDeque<BlockPos>();q.add(seed);
            while(!q.isEmpty()) {
                BlockPos p=q.remove();
                for(Direction dir:Direction.Type.HORIZONTAL) {
                    BlockPos n=p.offset(dir);
                    if(doors.contains(n) && live.get(n).get(Properties.HORIZONTAL_FACING)==live.get(seed).get(Properties.HORIZONTAL_FACING)) {
                        doors.remove(n);group.add(n);q.add(n);
                    }
                }
            }
            groups.add(group);
        }
        if(groups.size()!=1) return List.of();
        var group=groups.getFirst();var seed=group.iterator().next();
        Direction right=live.get(seed).get(Properties.HORIZONTAL_FACING).rotateYCounterclockwise();
        BlockPos edge=group.stream().max(Comparator.comparingInt(p->p.getX()*right.getOffsetX()+p.getZ()*right.getOffsetZ())).orElseThrow();
        for(int distance:new int[]{2,1,3,4,5,6}) {
            var cells=new ArrayList<BlockPos>();boolean valid=true;
            for(int x=0;x<width;x++) for(int y=0;y<height;y++) {
                BlockPos p=edge.offset(right,distance+x).up(y);var state=live.get(p);
                if(state==null || !state.isOpaque() || state.isIn(BlockTags.LOGS) || roof.contains(p)
                        || state.getBlock() instanceof StairsBlock || state.getBlock() instanceof SlabBlock) valid=false;
                cells.add(p);
            }
            if(!valid)continue;
            return cells.stream().map(p->new BlockPatch(BlockPatch.REPLACE,p.getX()-origin.getX(),p.getY()-origin.getY(),p.getZ()-origin.getZ(),"minecraft:glass")).toList();
        }
        return List.of();
    }
}
