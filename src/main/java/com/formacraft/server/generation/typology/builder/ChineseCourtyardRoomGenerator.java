package com.formacraft.server.generation.typology.builder;

import com.formacraft.common.build.PlannedBlock;
import com.formacraft.server.generation.structure.ChineseHallRoofGenerator;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.state.property.Properties;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

/** Compact courtyard rooms, not a reconstruction of historic official timber construction. */
public final class ChineseCourtyardRoomGenerator {
    private ChineseCourtyardRoomGenerator() {}

    public record Options(String roofType,int rise,int overhang,int curve,int lift,
                          BlockState wall,BlockState roof,BlockState floor,BlockState foundation,
                          BlockState pillar,BlockState beam,BlockState window) {}

    public static Options options(Map<String,Object> params,BlockState wall,BlockState roof,
                                  BlockState floor,BlockState foundation) {
        Set<String> allowed=Set.of("roofType","roofRise","roofOverhang","roofCurve","roofCornerLift",
                "wall_block","roof_block","floor_block","foundation_block","pillar_block","beam_block","window_block");
        for(String key:params.keySet()) if(!allowed.contains(key))
            throw new IllegalArgumentException("不支持的院落分房参数："+key);
        String form=ChineseHallRoofGenerator.normalize(String.valueOf(params.getOrDefault("roofType","ying_shan")));
        int rise=integer(params,"roofRise",0,3,18);
        int e=integer(params,"roofOverhang",1,0,3),curve=integer(params,"roofCurve",0,0,100),lift=integer(params,"roofCornerLift",0,0,3);
        if(lift>0 && (e==0||form.equals("ying_shan")||form.equals("xuan_shan")))
            throw new IllegalArgumentException("院落翘角需要有出檐的歇山或庑殿屋顶");
        return new Options(form,rise,e,curve,lift,material(params,"wall_block",wall),material(params,"roof_block",roof),
                material(params,"floor_block",floor),material(params,"foundation_block",foundation),
                material(params,"pillar_block",Blocks.DARK_OAK_LOG.getDefaultState()),
                material(params,"beam_block",Blocks.DARK_OAK_LOG.getDefaultState()),
                material(params,"window_block",Blocks.OAK_FENCE.getDefaultState()));
    }

    private static int integer(Map<String,Object> params,String key,int fallback,int min,int max) {
        if(!params.containsKey(key)) return fallback;
        try {
            double value=Double.parseDouble(String.valueOf(params.get(key)));
            if(!Double.isFinite(value)||value!=Math.rint(value)||value<min||value>max) throw new NumberFormatException();
            return (int)value;
        } catch(NumberFormatException ex) { throw new IllegalArgumentException("院落参数 "+key+" 必须为 "+min+"–"+max+" 的整数"); }
    }

    public static BlockState material(Map<String,Object> params,String key,BlockState fallback) {
        if(!params.containsKey(key)) return fallback;
        Identifier id=Identifier.tryParse(String.valueOf(params.get(key)));
        if(id==null||!Registries.BLOCK.containsId(id)) throw new IllegalArgumentException("E_MATERIAL_INVALID: "+key+"="+params.get(key));
        BlockState state=Registries.BLOCK.get(id).getDefaultState();
        if(state.isAir()||!state.getFluidState().isEmpty()) throw new IllegalArgumentException("E_MATERIAL_INVALID: "+key+"="+id);
        return state;
    }

    /** Width/depth are the world-axis rectangle, including its shared ground floor. */
    public static List<PlannedBlock> generate(int width, int depth, Direction facing, boolean gate,
                                            BlockPos corner, BlockState wall, BlockState roof,
                                            BlockState floor, BlockState foundation) {
        return generate(width,depth,facing,gate,corner,options(Map.of(),wall,roof,floor,foundation));
    }

    public static List<PlannedBlock> generate(int width,int depth,Direction facing,boolean gate,BlockPos corner,Options options) {
        BlockState wall=options.wall(),roof=options.roof(),floor=options.floor(),foundation=options.foundation();
        if (width < 7 || depth < 7) throw new IllegalArgumentException("院落木构单体宽深至少为7格");
        boolean swapped = facing.getAxis() == Direction.Axis.X;
        int w = swapped ? depth : width, d = swapped ? width : depth;
        int[] axes = TailiangTimberHallBuilder.bayAxis(w, 3);
        BlockState wood = options.pillar();
        BlockState beamX = options.beam().contains(Properties.AXIS)?options.beam().with(Properties.AXIS, Direction.Axis.X):options.beam();
        BlockState beamZ = options.beam().contains(Properties.AXIS)?options.beam().with(Properties.AXIS, Direction.Axis.Z):options.beam();
        var local = new ArrayList<PlannedBlock>();
        // Clear only this body's interior and roof cavity; never a neighboring room's envelope.
        int rise = options.rise()==0?Math.max(3, Math.min(8, (d - 1) / 2)):options.rise();
        for (int x=0;x<w;x++) for (int z=0;z<d;z++) {
            add(local,x,0,z,(x==0||x==w-1||z==0||z==d-1)?foundation:floor);
            for (int y=1;y<=6+rise;y++) add(local,x,y,z,Blocks.AIR.getDefaultState());
            if (x==0||x==w-1||z==0||z==d-1)
                for (int y=1;y<=5;y++) add(local,x,y,z,wall);
        }
        // Three front bays, matching rear columns; long side columns and beams share those walls.
        for (int x:axes) for (int z:new int[]{0,d-1})
            for (int y=1;y<=5;y++) add(local,x,y,z,wood);
        for (int z:new int[]{0,d/2,d-1}) for (int x:new int[]{0,w-1})
            for (int y=1;y<=5;y++) add(local,x,y,z,wood);
        for (int x=0;x<w;x++) for (int z:new int[]{0,d-1}) add(local,x,5,z,beamX);
        for (int z=0;z<d;z++) for (int x:new int[]{0,w-1}) add(local,x,5,z,beamZ);
        BlockState lattice = options.window();
        for (int bay=0;bay<3;bay++) {
            int x=(axes[bay]+axes[bay+1])/2;
            if (x<=axes[bay] || x>=axes[bay+1]) continue;
            for (int z:new int[]{0,d-1}) for (int y=2;y<=3;y++) add(local,x,y,z,lattice);
        }
        for (int z:new int[]{d/4,3*d/4}) for (int x:new int[]{0,w-1})
            if (z>0&&z<d-1&&z!=d/2)
                for (int y=2;y<=3;y++) add(local,x,y,z,lattice);
        // Two-cell opening also supports even-sized rooms and the shared compound axial path.
        int doorEnd=(facing==Direction.NORTH||facing==Direction.EAST)?w-w/2:w/2;
        for (int x=doorEnd-1;x<=doorEnd;x++) {
            int start=gate?0:d-1;
            for (int z=start;z<d;z++) {
                add(local,x,0,z,floor);
                for (int y=1;y<=3;y++) add(local,x,y,z,Blocks.AIR.getDefaultState());
                if (z==0||z==d-1) add(local,x,4,z,beamX);
            }
        }
        // Roof generator slopes along its X axis. Transpose to keep the ridge parallel to the facade.
        var roofBlocks = new ArrayList<PlannedBlock>();
        ChineseHallRoofGenerator.generate(roofBlocks,d,w,6,options.roofType(),rise,
                new ChineseHallRoofGenerator.Shape(options.overhang(),options.curve(),options.lift()),roof,roof,wall);
        for (var block:roofBlocks) {
            var p=block.getPos();
            var state=block.getTargetState();
            if(state.contains(Properties.HORIZONTAL_FACING)) {
                Direction transposed=switch(state.get(Properties.HORIZONTAL_FACING)) {
                    case EAST -> Direction.SOUTH;
                    case WEST -> Direction.NORTH;
                    case NORTH -> Direction.WEST;
                    default -> Direction.EAST;
                };
                state=state.with(Properties.HORIZONTAL_FACING,transposed);
            }
            add(local,p.getZ(),p.getY(),p.getX(),state);
        }
        var out = new ArrayList<PlannedBlock>(local.size());
        BlockRotation rotation=switch(facing) {
            case NORTH -> BlockRotation.CLOCKWISE_180;
            case EAST -> BlockRotation.COUNTERCLOCKWISE_90;
            case WEST -> BlockRotation.CLOCKWISE_90;
            default -> BlockRotation.NONE;
        };
        for (var block:local) {
            var p=block.getPos(); int x=p.getX(), z=p.getZ();
            BlockPos world=switch(facing) {
                case NORTH -> corner.add(w-1-x,p.getY(),d-1-z);
                case EAST -> corner.add(z,p.getY(),w-1-x);
                case WEST -> corner.add(d-1-z,p.getY(),x);
                default -> corner.add(x,p.getY(),z);
            };
            out.add(new PlannedBlock(world,block.getTargetState().rotate(rotation)));
        }
        return out;
    }

    private static void add(List<PlannedBlock> blocks,int x,int y,int z,BlockState state) {
        blocks.add(new PlannedBlock(new BlockPos(x,y,z),state));
    }
}
