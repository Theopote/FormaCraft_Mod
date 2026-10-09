package com.formacraft.common.generation.component.util;

import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.llm.dto.GlobalConstraints.Facing;
import com.formacraft.common.patch.BlockPatch;
import java.util.*;

/** Explicit window rectangles; style rhythm cannot shrink an authored opening. */
public final class SizedFacadeWindows {
    private SizedFacadeWindows() {}
    public static List<BlockPatch> generate(SemanticComponent semantic,String block,Facing facing,
            boolean wrap,int windowWidth,int windowHeight,int floorHeight,boolean reserveEntrance) {
        var c=semantic.source(); var params=c.params(); var rp=c.relativePosition(); var dims=c.dimensions();
        int width=dims.width(),depth=dims.depth(),height=dims.height();
        String wall=String.valueOf(params.getOrDefault("wall",""));
        String excluded=String.valueOf(params.getOrDefault("excluded_window_axis",""));
        var faces=new LinkedHashSet<Facing>();
        if("front".equalsIgnoreCase(wall)) faces.add(facing);
        else if("left_right".equalsIgnoreCase(wall)) {
            boolean x=facing==Facing.EAST || facing==Facing.WEST;
            faces.add(x?Facing.NORTH:Facing.EAST); faces.add(x?Facing.SOUTH:Facing.WEST);
        } else if(wrap) Collections.addAll(faces,Facing.values());
        else faces.add(facing);
        int sill=Math.max(1,ComponentParamParsers.intParam(params,1,"sill_offset","window_sill","sill_height"));
        int thickness=Math.max(1,ComponentParamParsers.intParam(params,1,"wall_thickness","wallThickness"));
        var out=new ArrayList<BlockPatch>();
        for(var face:faces) {
            boolean xFace=face==Facing.EAST || face==Facing.WEST;
            if((xFace && "x".equals(excluded)) || (!xFace && "z".equals(excluded))) continue;
            int span=xFace?depth:width;
            boolean entrance=face==facing && reserveEntrance;
            int reserve=Math.max(1,ComponentParamParsers.intParam(params,3,"exclude_center_width"));
            int middleStart=(span-reserve)/2,middleEnd=middleStart+reserve;
            var starts=new ArrayList<Integer>();
            if(entrance) { addStarts(starts,1,middleStart,windowWidth); addStarts(starts,middleEnd,span-1,windowWidth); }
            else addStarts(starts,1,span-1,windowWidth);
            for(int floor=0;floor<height;floor+=Math.max(1,floorHeight)) {
                int bottom=floor+sill;
                if(bottom+windowHeight>Math.min(height,floor+floorHeight)) continue;
                for(int start:starts) for(int a=start;a<start+windowWidth;a++) for(int y=bottom;y<bottom+windowHeight;y++) {
                    int x=xFace?(face==Facing.EAST?0:width-1):a;
                    int z=xFace?a:(face==Facing.SOUTH?0:depth-1);
                    int ix=xFace?(face==Facing.EAST?1:-1):0, iz=xFace?0:(face==Facing.SOUTH?1:-1);
                    for(int layer=1;layer<thickness;layer++) out.add(new BlockPatch(BlockPatch.REMOVE,
                            rp.x()+x+ix*layer,rp.y()+y,rp.z()+z+iz*layer,"minecraft:air"));
                    GeneratedSurfaceCapture.record(rp.x()+x,rp.y()+y,rp.z()+z,GeneratedSurfaceCapture.Role.WINDOW);
                    out.add(new BlockPatch(BlockPatch.PLACE,rp.x()+x,rp.y()+y,rp.z()+z,block));
                }
            }
        }
        return out;
    }
    private static void addStarts(List<Integer> starts,int from,int to,int size) {
        int available=to-from,count=(available+2)/(size+2);
        if(count<1) return;
        int occupied=count*size+(count-1)*2,first=from+(available-occupied)/2;
        for(int i=0;i<count;i++) starts.add(first+i*(size+2));
    }
}
