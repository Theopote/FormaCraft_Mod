package com.formacraft.server.skeleton.gen;

import com.formacraft.common.geometry.shape.ShapeLibrary;
import com.formacraft.common.geometry.shape.ShapeSpec;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.skeleton.ExecutableSkeletonPlan;
import java.util.*;

/** Elevated island; dimensions are bounded before allocating voxels. */
public final class FloatingMassGenerator implements ISkeletonGenerator {
    @Override public List<BlockPatch> generate(GenerationContext ctx, ExecutableSkeletonPlan plan) {
        int width=dimension(plan,"width",17,5,65);
        int depth=dimension(plan,"depth",17,5,65);
        int height=dimension(plan,"height",9,3,33);
        int elevation=dimension(plan,"elevation",12,1,256);
        String block=String.valueOf(plan.params.getOrDefault("block","minecraft:stone"));
        var voxels=ShapeLibrary.generate(ShapeSpec.fromParams(width,depth,height,Map.of("kind","floating_mass")));
        if(voxels.size()>ctx.maxOps) throw new IllegalArgumentException("E_FLOATING_MASS_BUDGET: refusing partial island");
        var out=new ArrayList<BlockPatch>();
        for(var v:voxels) out.add(new BlockPatch(BlockPatch.PLACE,v.x(),elevation+v.y(),v.z(),block));
        return out;
    }

    private static int dimension(ExecutableSkeletonPlan plan,String key,int fallback,int min,int max) {
        Object value=plan.params.getOrDefault(key,fallback);
        double number;
        try { number=Double.parseDouble(String.valueOf(value)); }
        catch(NumberFormatException e) { throw new IllegalArgumentException("Invalid floating mass "+key,e); }
        if(!Double.isFinite(number)||number!=Math.rint(number)||number<min||number>max)
            throw new IllegalArgumentException("Invalid floating mass "+key+": "+value);
        return (int)number;
    }
}
