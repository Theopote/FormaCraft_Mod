package com.formacraft.server.generation.structure;

import com.formacraft.common.build.PlannedBlock;
import net.minecraft.block.BlockState;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import java.util.List;
import java.util.Locale;

/** Parametric voxel surfaces; curve and corner lifting are visual approximations. */
public final class ChineseHallRoofGenerator {
    private ChineseHallRoofGenerator() {}

    public record Shape(int overhang, int curve, int cornerLift) {
        public Shape {
            if (overhang < 0 || overhang > 3 || curve < 0 || curve > 100 || cornerLift < 0 || cornerLift > 3)
                throw new IllegalArgumentException("中式屋面参数超出支持范围");
        }
    }

    public static String normalize(String form) {
        return switch (form.trim().toLowerCase(Locale.ROOT)) {
            case "ying_shan", "yingshan", "硬山" -> "ying_shan";
            case "xuan_shan", "xuanshan", "悬山" -> "xuan_shan";
            case "xie_shan", "xieshan", "歇山" -> "xie_shan";
            case "wu_dian", "wudian", "庑殿" -> "wu_dian";
            default -> throw new IllegalArgumentException("不支持的中式屋顶形式：" + form);
        };
    }

    public static void generate(List<PlannedBlock> blocks, int width, int depth, int baseY,
                                String form, int rise, BlockState tile, BlockState stairs, BlockState wall) {
        generate(blocks,width,depth,baseY,form,rise,new Shape(1,0,0),tile,stairs,wall);
    }

    private static int elevation(int distance, int run, int rise, int curve) {
        return (int)Math.floor(rise * Math.pow(distance / (double)run, 1 + curve / 100.0) + 1e-9);
    }

    public static void generate(List<PlannedBlock> blocks, int width, int depth, int baseY,
                                String form, int rise, Shape shape, BlockState tile, BlockState stairs, BlockState wall) {
        form = normalize(form);
        boolean fourSides = form.equals("wu_dian") || form.equals("xie_shan");
        if (shape.cornerLift() > 0 && (!fourSides || shape.overhang() == 0))
            throw new IllegalArgumentException("翘角需要歇山或庑殿屋顶，且 roofOverhang 至少为 1");
        int e = shape.overhang(), xMin = -e, xMax = width - 1 + e;
        int zMin = form.equals("ying_shan") ? 0 : -e;
        int zMax = form.equals("ying_shan") ? depth - 1 : depth - 1 + e;
        int run = (width - 1) / 2 + e;
        if (form.equals("wu_dian")) run = Math.min(run, (depth - 1) / 2 + e);
        int hip = Math.min(3, (depth - 1) / 2 + e);
        int[][] heights = new int[xMax-xMin+1][zMax-zMin+1];
        for (int x=xMin;x<=xMax;x++) for (int z=zMin;z<=zMax;z++) {
            int dx=Math.min(x-xMin,xMax-x),dz=Math.min(z+e,depth-1+e-z);
            int distance=dx;
            if (form.equals("wu_dian") || form.equals("xie_shan") && dz<hip) distance=Math.min(dx,dz);
            int h=elevation(distance,run,rise,shape.curve());
            if (fourSides && shape.cornerLift()>0 && dx<3 && dz<3)
                h+=(int)Math.round(shape.cornerLift()*(3-dx)*(3-dz)/9.0);
            heights[x-xMin][z-zMin]=h;
        }
        for (int x=xMin;x<=xMax;x++) for (int z=zMin;z<=zMax;z++) {
            int dx=Math.min(x-xMin,xMax-x),dz=Math.min(z+e,depth-1+e-z);
            int xi=x-xMin,zi=z-zMin,h=heights[xi][zi],bottom=h;
            if(xi>0) bottom=Math.min(bottom,heights[xi-1][zi]);
            if(xi+1<heights.length) bottom=Math.min(bottom,heights[xi+1][zi]);
            boolean gableEdge=form.equals("xie_shan")&&dz==hip;
            if(zi>0&&(!gableEdge||Math.min(z-1+e,depth+e-z)>=hip))
                bottom=Math.min(bottom,heights[xi][zi-1]);
            if(zi+1<heights[0].length&&(!gableEdge||Math.min(z+1+e,depth-2+e-z)>=hip))
                bottom=Math.min(bottom,heights[xi][zi+1]);
            boolean endSlope=form.equals("wu_dian") || form.equals("xie_shan") && dz<hip;
            Direction facing=endSlope&&dz<dx
                    ? (z<depth/2?Direction.SOUTH:Direction.NORTH)
                    : (x<width/2?Direction.EAST:Direction.WEST);
            boolean lifted=fourSides&&shape.cornerLift()>0&&dx<3&&dz<3;
            BlockState surface=h==rise||lifted?tile
                    : stairs.contains(Properties.HORIZONTAL_FACING)?stairs.with(Properties.HORIZONTAL_FACING,facing):tile;
            for(int y=bottom;y<=h;y++)
                blocks.add(new PlannedBlock(new BlockPos(x,baseY+y,z),y==h?surface:tile));
            boolean gable=form.equals("xie_shan")?dz==hip:!form.equals("wu_dian")&&(z==0||z==depth-1);
            int wallBase=form.equals("xie_shan")?elevation(hip-1,run,rise,shape.curve()):0;
            if(gable&&x>=0&&x<width)
                for(int y=wallBase;y<bottom;y++)
                    blocks.add(new PlannedBlock(new BlockPos(x,baseY+y,z),wall));
        }
    }
}
