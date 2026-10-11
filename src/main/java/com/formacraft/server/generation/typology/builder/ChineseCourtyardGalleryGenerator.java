package com.formacraft.server.generation.typology.builder;

import com.formacraft.common.build.PlannedBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import java.util.*;

/** An open timber ring with covered connectors, not a historic corridor reconstruction. */
public final class ChineseCourtyardGalleryGenerator {
    private ChineseCourtyardGalleryGenerator() {}
    public record Ring(int minX,int maxX,int minZ,int maxZ,int halfWidth) {}

    public static Ring resolve(List<CourtyardCompoundBuilder.Part> parts,int roofMargin,int clearWidth) {
        if(clearWidth!=3&&clearWidth!=5) throw new IllegalArgumentException("回廊净宽支持3或5格");
        int half=(clearWidth+2)/2;
        int x0=Integer.MIN_VALUE,x1=Integer.MAX_VALUE,z0=Integer.MIN_VALUE,z1=Integer.MAX_VALUE;
        for(var p:parts) {
            // The room centers identify the four enclosure sides, independent of entrance rotation.
            int cx=(p.minX()+p.maxX())/2,cz=(p.minZ()+p.maxZ())/2;
            if(Math.abs(cx)>Math.abs(cz)) {
                if(cx<0) x0=Math.max(x0,p.maxX()+roofMargin+half+1);
                else x1=Math.min(x1,p.minX()-roofMargin-half-1);
            } else {
                if(cz<0) z0=Math.max(z0,p.maxZ()+roofMargin+half+1);
                else z1=Math.min(z1,p.minZ()-roofMargin-half-1);
            }
        }
        if(x0==Integer.MIN_VALUE||x1==Integer.MAX_VALUE||z0==Integer.MIN_VALUE||z1==Integer.MAX_VALUE
                ||x1-x0<2*half+3||z1-z0<2*half+3)
            throw new IllegalArgumentException("院心空间不足以容纳实体回廊与露天庭院，请增大院落或缩小出檐/廊宽");
        return new Ring(x0,x1,z0,z1,half);
    }

    public static List<PlannedBlock> generate(Ring ring,List<BlockPos> doors,List<CourtyardCompoundBuilder.Part> parts,
                                             BlockPos origin,int pathWidth,BlockState floor,BlockState pillar,
                                             BlockState beam,BlockState roof) {
        Set<BlockPos> cells=new LinkedHashSet<>(),connectors=new HashSet<>(),centerline=new HashSet<>();
        int h=ring.halfWidth();
        for(int x=ring.minX()-h;x<=ring.maxX()+h;x++) for(int z=ring.minZ()-h;z<=ring.maxZ()+h;z++) {
            if(x>=ring.minX()+h+1&&x<=ring.maxX()-h-1&&z>=ring.minZ()+h+1&&z<=ring.maxZ()-h-1) continue;
            cells.add(new BlockPos(x,0,z));
            if(x==ring.minX()||x==ring.maxX()||z==ring.minZ()||z==ring.maxZ()) centerline.add(new BlockPos(x,0,z));
        }
        for(BlockPos door:doors) {
            int x=door.getX(),z=door.getZ();
            boolean horizontal=x<ring.minX()||x>ring.maxX();
            int end=horizontal?Math.max(ring.minX(),Math.min(ring.maxX(),x)):Math.max(ring.minZ(),Math.min(ring.maxZ(),z));
            int start=horizontal?x:z;
            for(int run=Math.min(start,end);run<=Math.max(start,end);run++) for(int cross=-h;cross<=h;cross++) {
                BlockPos p=horizontal?new BlockPos(run,0,z+cross):new BlockPos(x+cross,0,run);
                if(inRoom(p,parts)) continue;
                cells.add(p);connectors.add(p);
            }
        }
        var out=new ArrayList<PlannedBlock>();
        int axial=Math.max(1,pathWidth/2)+1;
        for(BlockPos p:cells) {
            BlockPos placed=origin.add(p);
            out.add(new PlannedBlock(placed,floor));
            for(int y=1;y<=4;y++) out.add(new PlannedBlock(placed.up(y),Blocks.AIR.getDefaultState()));
            boolean edge=!cells.contains(p.north())||!cells.contains(p.south())||!cells.contains(p.east())||!cells.contains(p.west());
            boolean corner=(p.getX()==ring.minX()-h||p.getX()==ring.maxX()+h)
                    &&(p.getZ()==ring.minZ()-h||p.getZ()==ring.maxZ()+h);
            boolean column=edge&&(corner||Math.floorMod(p.getX(),4)==0&&Math.abs(p.getZ())>axial
                    ||Math.floorMod(p.getZ(),4)==0&&Math.abs(p.getX())>axial);
            if(connectors.contains(p)||Math.abs(p.getX())<=axial||Math.abs(p.getZ())<=axial) column=false;
            if(column) for(int y=1;y<=3;y++) out.add(new PlannedBlock(placed.up(y),pillar));
            if(edge) {
                var state=beam;
                if(state.contains(Properties.AXIS)) state=state.with(Properties.AXIS,
                        !cells.contains(p.north())||!cells.contains(p.south())?net.minecraft.util.math.Direction.Axis.X:net.minecraft.util.math.Direction.Axis.Z);
                out.add(new PlannedBlock(placed.up(4),state));
            }
            out.add(new PlannedBlock(placed.up(5),roof));
            if(centerline.contains(p)&&!connectors.contains(p)) out.add(new PlannedBlock(placed.up(6),roof));
        }
        return out;
    }

    private static boolean inRoom(BlockPos p,List<CourtyardCompoundBuilder.Part> parts) {
        for(var part:parts) if(p.getX()>=part.minX()&&p.getX()<=part.maxX()&&p.getZ()>=part.minZ()&&p.getZ()<=part.maxZ()) return true;
        return false;
    }
}
