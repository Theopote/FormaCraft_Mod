package com.formacraft.common.generation.component.util;

import com.formacraft.common.patch.BlockPatch;
import java.util.ArrayList;
import java.util.List;

/** Hollow voxel roof surfaces; rise and material remain explicit design inputs. */
public final class SpecialtyRoofSurfaces {
    private SpecialtyRoofSurfaces() {}

    public static List<BlockPatch> generate(RoofTypeKind kind, int bx, int by, int bz,
            int width, int depth, int height, String material, ComponentFootprintMask footprint, int overhang) {
        if (width < 5 || depth < 5 || height < 3)
            throw new IllegalArgumentException("E_ROOF_GEOMETRY_INVALID: specialty roof requires footprint >=5x5 and height >=3");
        if (kind == RoofTypeKind.STEEP_THATCH && height < Math.ceil((Math.min(width, depth)-1)*.6)+1)
            throw new IllegalArgumentException("E_ROOF_GEOMETRY_INVALID: steep_thatch requires rise/run >=1.2; increase roof_height or reduce span");
        if (kind != RoofTypeKind.ONION_DOME && kind != RoofTypeKind.GAMBREL
                && kind != RoofTypeKind.BARREL_VAULT && kind != RoofTypeKind.STEEP_THATCH)
            throw new IllegalArgumentException("Unsupported specialty roof: " + kind);
        var out = new ArrayList<BlockPatch>();
        if (kind == RoofTypeKind.ONION_DOME) {
            double cx=(width-1)/2.0, cz=(depth-1)/2.0;
            double thickness=1.25/Math.min(cx,cz);
            for (int y=0; y<height; y++) {
                double radius=onionRadius(y/(double)(height-1));
                double previous=onionRadius(Math.max(0,y-1)/(double)(height-1));
                double next=onionRadius(Math.min(height-1,y+1)/(double)(height-1));
                double inner=Math.max(0,Math.min(radius,Math.min(previous,next))-thickness);
                for (int x=0; x<width; x++) for (int z=0; z<depth; z++) {
                    if (!allowed(footprint,overhang,x,z)) continue;
                    double distance=Math.hypot((x-cx)/cx,(z-cz)/cz);
                    boolean tip=y==height-1 && Math.abs(x-cx)<=.5 && Math.abs(z-cz)<=.5;
                    if (tip || distance>=inner && distance<=radius+thickness*.25)
                        out.add(new BlockPatch(BlockPatch.PLACE,bx+x,by+y,bz+z,material));
                }
            }
        } else {
            boolean alongDepth=depth>=width;
            int span=alongDepth?width:depth;
            for (int x=0;x<width;x++) for (int z=0;z<depth;z++) {
                if (!allowed(footprint,overhang,x,z)) continue;
                int axis=alongDepth?x:z;
                double u=Math.min(axis,span-1-axis)/(double)Math.max(1,(span-1)/2);
                u=Math.min(1,u);
                double rise=switch(kind) {
                    case GAMBREL -> u<=.35 ? u/.35*.65 : .65+(u-.35)/.65*.35;
                    case BARREL_VAULT -> Math.sqrt(Math.max(0,1-Math.pow(1-u,2)));
                    default -> u;
                };
                out.add(new BlockPatch(BlockPatch.PLACE,bx+x,by+(int)Math.round(rise*(height-1)),bz+z,material));
            }
        }
        return out;
    }

    private static double onionRadius(double t) {
        return Math.min(1,(.65+.9*Math.sin(Math.PI*t))*Math.pow(1-t,.75));
    }

    private static boolean allowed(ComponentFootprintMask footprint,int overhang,int x,int z) {
        return footprint==null || footprint.shouldPlaceRoof(x-overhang,z-overhang,overhang);
    }
}
