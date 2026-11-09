package com.formacraft.common.generation.component.util;

import com.formacraft.common.patch.BlockPatch;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Hollow voxel roof surfaces; rise and material remain explicit design inputs. */
public final class SpecialtyRoofSurfaces {
    private SpecialtyRoofSurfaces() {}

    public static List<BlockPatch> generate(RoofTypeKind kind, int bx, int by, int bz,
            int width, int depth, int height, String material, ComponentFootprintMask footprint, int overhang) {
        return generate(kind, bx, by, bz, width, depth, height, material, footprint, overhang, null);
    }

    public static List<BlockPatch> generate(RoofTypeKind kind, int bx, int by, int bz,
            int width, int depth, int height, String material, ComponentFootprintMask footprint, int overhang,
            Map<String, Object> params) {
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
            double breakRun = 0.35;
            double breakRise = 0.65;
            if (kind == RoofTypeKind.GAMBREL) {
                breakRun = gambrelFraction(params, "gambrel_break_run_fraction", breakRun);
                breakRise = gambrelFraction(params, "gambrel_break_rise_fraction", breakRise);
                if (breakRise <= breakRun) {
                    throw new IllegalArgumentException("E_ROOF_GEOMETRY_INVALID: gambrel_break_rise_fraction must "
                            + "exceed gambrel_break_run_fraction so the lower slope is steeper");
                }
            }
            boolean alongDepth=depth>=width;
            int span=alongDepth?width:depth;
            for (int x=0;x<width;x++) for (int z=0;z<depth;z++) {
                if (!allowed(footprint,overhang,x,z)) continue;
                int axis=alongDepth?x:z;
                double u=Math.min(axis,span-1-axis)/(double)Math.max(1,(span-1)/2);
                u=Math.min(1,u);
                double rise=switch(kind) {
                    case GAMBREL -> u <= breakRun ? u / breakRun * breakRise
                            : breakRise + (u - breakRun) / (1 - breakRun) * (1 - breakRise);
                    case BARREL_VAULT -> Math.sqrt(Math.max(0,1-Math.pow(1-u,2)));
                    default -> u;
                };
                out.add(new BlockPatch(BlockPatch.PLACE,bx+x,by+(int)Math.round(rise*(height-1)),bz+z,material));
            }
        }
        return out;
    }

    private static double gambrelFraction(Map<String, Object> params, String key, double fallback) {
        Object raw = params == null ? null : params.get(key);
        if (raw == null) return fallback;
        String error = "E_ROOF_GEOMETRY_INVALID: " + key + " must be a number between 0 and 1 (exclusive)";
        try {
            double fraction = Double.parseDouble(raw.toString().trim());
            if (fraction > 0 && fraction < 1) return fraction;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(error, e);
        }
        throw new IllegalArgumentException(error);
    }

    private static double onionRadius(double t) {
        return Math.min(1,(.65+.9*Math.sin(Math.PI*t))*Math.pow(1-t,.75));
    }

    private static boolean allowed(ComponentFootprintMask footprint,int overhang,int x,int z) {
        return footprint==null || footprint.shouldPlaceRoof(x-overhang,z-overhang,overhang);
    }
}
