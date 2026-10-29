package com.formacraft.server.edit;

import com.formacraft.common.component.transform.BlockStateStringUtil;
import com.formacraft.common.patch.BlockPatch;
import net.minecraft.block.*;
import net.minecraft.util.math.BlockPos;
import java.util.*;
import java.util.function.Predicate;

/** Bounded live-world window sealing. Never synthesizes a building or guesses wall material. */
public final class WindowSealCompiler {
    private WindowSealCompiler() {}

    public static boolean matches(String text) {
        if (text == null) return false;
        text=text.replaceAll("\\s+", "");
        if (text.matches("(?s).*(不要|别|不许|不必|无需)(再)?(去掉|移除|删除|封闭|封好|封上).*")) return false;
        if (!(text.matches("(?s).*(去掉|移除|删除|封闭|封好|封上).*窗.*")
                && text.matches("(?s).*(封|补|填|还原).*"))) {
            return false;
        }
        // Huizhou white-wall lattice OR seal-back of an inserted glass window on any host wall.
        return text.matches("(?s).*(白色|白墙|白.*墙面).*")
                || text.matches("(?s).*(玻璃窗|刚才.*窗|这扇窗|目标窗).*");
    }

    public static List<BlockPatch> compile(Map<BlockPos, BlockState> live, BlockPos origin,
            BlockPos center, Set<BlockPos> roof, Predicate<BlockPos> skyVisible,
            boolean woodOnly, boolean courtyardOnly) {
        Set<BlockPos> remaining = new HashSet<>();
        live.forEach((p,s) -> {
            if (!roof.contains(p) && (s.getBlock() instanceof FenceBlock
                    || !woodOnly && (s.getBlock() instanceof PaneBlock
                    || s.isOf(Blocks.GLASS) || s.getBlock() instanceof StainedGlassBlock))) remaining.add(p);
        });
        List<BlockPatch> result = new ArrayList<>();
        while (!remaining.isEmpty()) {
            BlockPos start = remaining.iterator().next();
            remaining.remove(start);
            Set<BlockPos> group = new HashSet<>(); group.add(start);
            ArrayDeque<BlockPos> queue = new ArrayDeque<>(); queue.add(start);
            while (!queue.isEmpty()) {
                BlockPos p = queue.remove();
                for (BlockPos n : List.of(p.up(),p.down(),p.east(),p.west(),p.north(),p.south()))
                    if (remaining.remove(n)) {group.add(n);queue.add(n);}
            }
            int x0=group.stream().mapToInt(BlockPos::getX).min().orElseThrow(), x1=group.stream().mapToInt(BlockPos::getX).max().orElseThrow();
            int y0=group.stream().mapToInt(BlockPos::getY).min().orElseThrow(), y1=group.stream().mapToInt(BlockPos::getY).max().orElseThrow();
            int z0=group.stream().mapToInt(BlockPos::getZ).min().orElseThrow(), z1=group.stream().mapToInt(BlockPos::getZ).max().orElseThrow();
            // A facade may contain one continuous lattice band rather than separate 2x2 windows.
            if(group.size()>128 || x1-x0>31 || z1-z0>31 || y1-y0>7 || (x1!=x0 && z1!=z0)) continue;
            boolean constantX=x0==x1;
            if (courtyardOnly) {
                int normal=constantX ? Integer.compare(center.getX(),x0) : Integer.compare(center.getZ(),z0);
                if(normal==0) continue;
                boolean inward=false;
                for(BlockPos p:group) for(int d=1;d<=3;d++) {
                    BlockPos probe=p.add(constantX?normal*d:0,0,constantX?0:normal*d);
                    BlockState state=live.get(probe);
                    if(state==null || !state.isAir()) break;
                    if(skyVisible.test(probe)) inward=true;
                }
                if(!inward) continue;
            }
            Map<BlockState,Integer> votes=new HashMap<>();
            Set<BlockPos> border=new HashSet<>();
            boolean above=false,below=false,left=false,right=false;
            for(BlockPos p:group) for(int d=1;d<=2;d++) {
                for(BlockPos n:List.of(p.up(d),p.down(d),constantX?p.north(d):p.west(d),constantX?p.south(d):p.east(d))) {
                    if(group.contains(n) || roof.contains(n) || !border.add(n)) continue;
                    BlockState state=live.get(n);
                    if(!whiteWall(state)) continue;
                    votes.merge(state,1,Integer::sum);
                    // One-high fences qualify only when immediately framed by wall above and below.
                    above|=n.getY()>y1 && (y1!=y0 || d==1);
                    below|=n.getY()<y0 && (y1!=y0 || d==1);
                    left|=constantX?n.getZ()<z0:n.getX()<x0;
                    right|=constantX?n.getZ()>z1:n.getX()>x1;
                }
            }
            if((y1==y0 ? !(above&&below) : !(above&&below || left&&right)) || votes.isEmpty()) continue;
            var sorted=votes.entrySet().stream().sorted(Map.Entry.<BlockState,Integer>comparingByValue().reversed()).toList();
            if(sorted.getFirst().getValue()<4 || sorted.size()>1 && sorted.get(1).getValue().equals(sorted.getFirst().getValue())) continue;
            String material=BlockStateStringUtil.fromState(sorted.getFirst().getKey());
            for(BlockPos p:group) result.add(new BlockPatch(BlockPatch.REPLACE,p.getX()-origin.getX(),p.getY()-origin.getY(),p.getZ()-origin.getZ(),material));
        }
        result.sort(Comparator.comparingInt(BlockPatch::dx).thenComparingInt(BlockPatch::dy).thenComparingInt(BlockPatch::dz));
        return List.copyOf(result);
    }

    private static boolean whiteWall(BlockState s) {
        return s!=null && (s.isOf(Blocks.WHITE_TERRACOTTA) || s.isOf(Blocks.WHITE_CONCRETE)
                || s.isOf(Blocks.QUARTZ_BLOCK) || s.isOf(Blocks.SMOOTH_QUARTZ) || s.isOf(Blocks.CALCITE));
    }
}
