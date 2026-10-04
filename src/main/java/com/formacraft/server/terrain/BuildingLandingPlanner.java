package com.formacraft.server.terrain;

import com.formacraft.common.build.PlannedBlock;
import com.formacraft.common.generation.component.util.ComponentFootprintUtil;
import com.formacraft.common.llm.dto.*;
import com.formacraft.common.network.LlmPlanTerrainBounds;
import com.formacraft.server.build.BuildConstraintContext;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;

import java.util.*;

/** Local earthwork for component buildings. Heights are first air above solid ground. */
public final class BuildingLandingPlanner {
    private BuildingLandingPlanner() {}
    private static final int MAX_SUPPORT = 96;
    private static final int MAX_CUT = 32;
    private static final int MAX_AREA = 16384;
    private static final int MAX_EDITS = 200000;

    public interface Ground {
        int surfaceY(int x, int z);
        default int placementY(int x, int z) { return surfaceY(x,z); }
        BlockState state(BlockPos pos);
        default boolean bearing(BlockPos pos) {
            BlockState s=state(pos);
            return !s.isAir() && s.getFluidState().isEmpty() && !s.isReplaceable();
        }
        int bottomY();
    }

    public record Site(LlmPlanTerrainBounds.Bounds body, GlobalConstraints.Facing facing) {}
    public record Result(List<PlannedBlock> blocks, int dy, String problem, int supports, int steps) {}

    public static Ground ground(ServerWorld world) {
        return new Ground() {
            public int placementY(int x, int z) {
                int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
                while (y > world.getBottomY()) {
                    BlockState s = world.getBlockState(new BlockPos(x, y - 1, z));
                    if ((s.isSolidBlock(world, new BlockPos(x, y - 1, z)) || !s.getFluidState().isEmpty())
                            && !s.isIn(net.minecraft.registry.tag.BlockTags.LOGS)
                            && !s.isIn(net.minecraft.registry.tag.BlockTags.LEAVES)) break;
                    y--;
                }
                return y;
            }
            public int surfaceY(int x, int z) {
                int y = placementY(x,z);
                while (y > world.getBottomY()) {
                    BlockPos p = new BlockPos(x,y-1,z);
                    if (world.getBlockState(p).isSolidBlock(world,p)) break;
                    y--;
                }
                return y;
            }
            public BlockState state(BlockPos pos) { return world.getBlockState(pos); }
            public boolean bearing(BlockPos pos) {
                BlockState s=world.getBlockState(pos);
                return Ground.super.bearing(pos) && s.isSolidBlock(world,pos)
                        && !s.isIn(net.minecraft.registry.tag.BlockTags.LOGS)
                        && !s.isIn(net.minecraft.registry.tag.BlockTags.LEAVES);
            }
            public int bottomY() { return world.getBottomY(); }
        };
    }

    public static List<Site> sites(LlmPlan plan, BlockPos origin) {
        if (plan == null || plan.mode() != LlmPlan.Mode.build || plan.components() == null || plan.usesPlanProgramMode())
            return List.of();
        plan = com.formacraft.common.llm.parser.LlmPlanAnchorNormalizer.normalize(plan);
        Map<String, Slot> slots = new HashMap<>();
        if (plan.layout() != null && plan.layout().slots() != null)
            for (Slot s : plan.layout().slots()) if (s != null) slots.put(s.slotId(), s);
        List<Site> out = new ArrayList<>();
        for (Component c : plan.components()) {
            if (!("MASS_MAIN".equalsIgnoreCase(c.componentType()) || "MAIN_MASS".equalsIgnoreCase(c.componentType()))
                    || c.dimensions() == null) continue; // Upper-floor plates are attachments, not independent sites.
            var b = ComponentFootprintUtil.bounds(c);
            if (b == null) continue;
            Slot slot = slots.get(c.slotId());
            Vec3i a = slot != null && slot.anchor() != null ? slot.anchor() : new Vec3i(0, 0, 0);
            BlockPos offset = origin.add(a.x(), a.y(), a.z());
            var bounds = new LlmPlanTerrainBounds.Bounds(offset.getX() + b.minX(), offset.getY() + b.minY(),
                    offset.getZ() + b.minZ(), offset.getX() + b.maxX() - 1,
                    offset.getY() + b.maxY() - 1, offset.getZ() + b.maxZ() - 1);
            var facing = slot != null ? slot.facing() : null;
            if (facing == null && plan.globalConstraints() != null) facing = plan.globalConstraints().facing();
            // Component compiler's legacy facade labels are opposite to Minecraft world directions.
            if(facing==null) facing=GlobalConstraints.Facing.SOUTH;
            facing=switch(facing) {
                case NORTH -> GlobalConstraints.Facing.SOUTH;
                case SOUTH -> GlobalConstraints.Facing.NORTH;
                case EAST -> GlobalConstraints.Facing.WEST;
                case WEST -> GlobalConstraints.Facing.EAST;
            };
            out.add(new Site(bounds, facing));
        }
        return out;
    }

    public static Result prepare(List<PlannedBlock> input, List<Site> sites, Ground ground,
                                 GlobalConstraints.TerrainStrategy strategy, boolean stilt, BlockState fill) {
        if (sites.isEmpty() || strategy == GlobalConstraints.TerrainStrategy.PRESERVE)
            return new Result(input, 0, null, 0, 0);
        long area = sites.stream().mapToLong(s -> (long) s.body().expand(2).width() * s.body().expand(2).depth()).sum();
        if (area > MAX_AREA) return failure(input, "建筑占地过大，建议分批生成或缩小范围。");
        Map<Long, Integer> surfaces = new HashMap<>();
        Map<Long, Integer> placement = new HashMap<>();
        Ground cached = new Ground() {
            public int surfaceY(int x, int z) { return surfaces.computeIfAbsent(key(x,z), k -> ground.surfaceY(x,z)); }
            public int placementY(int x, int z) { return placement.computeIfAbsent(key(x,z), k -> ground.placementY(x,z)); }
            public BlockState state(BlockPos p) { return ground.state(p); }
            public boolean bearing(BlockPos p) { return ground.bearing(p); }
            public int bottomY() { return ground.bottomY(); }
        };
        List<Integer> offsets = new ArrayList<>();
        Map<BlockPos,BlockState> authored = new HashMap<>();
        input.forEach(p -> authored.put(p.getPos(),p.getTargetState()));
        for (Site site : sites) {
            var b = site.body();
            for (int x=b.minX(); x<=b.maxX(); x++) for (int z=b.minZ(); z<=b.maxZ(); z++)
                if (authored.containsKey(new BlockPos(x,b.minY(),z)) && !authored.get(new BlockPos(x,b.minY(),z)).isAir())
                    offsets.add(cached.placementY(x,z)-b.minY());
        }
        if (offsets.isEmpty()) return failure(input,"无法识别建筑底面，请检查主体与地基位置。");
        Collections.sort(offsets);
        int dy = stilt ? 0 : chooseElevation(offsets, sites, cached);
        List<PlannedBlock> moved = LlmPlanTerrainBounds.translateBlocks(input, dy);
        Map<BlockPos, BlockState> finalBlocks = new HashMap<>();
        Map<Long, Integer> bottoms = new HashMap<>(), tops = new HashMap<>();
        for (PlannedBlock pb : moved) finalBlocks.put(pb.getPos(), pb.getTargetState());
        for (var e : finalBlocks.entrySet()) if (!e.getValue().isAir()) {
            BlockPos p = e.getKey();
            bottoms.merge(key(p.getX(),p.getZ()), p.getY(), Math::min);
            tops.merge(key(p.getX(),p.getZ()), p.getY(), Math::max);
        }
        Map<BlockPos, PlannedBlock> prep = new LinkedHashMap<>();
        int supports = 0, steps = 0;
        for (Site site : sites) {
            var original = site.body();
            var b = new LlmPlanTerrainBounds.Bounds(original.minX(), original.minY()+dy, original.minZ(),
                    original.maxX(), original.maxY()+dy, original.maxZ());
            var pad = b.expand(2);
            for (int x=pad.minX(); x<=pad.maxX(); x++) for (int z=pad.minZ(); z<=pad.maxZ(); z++) {
                long k = key(x,z);
                Integer bottom = bottoms.get(k), top = tops.get(k);
                if (bottom == null) continue; // No blanket slab across courtyards / gaps.
                int surface = cached.surfaceY(x,z);
                if (surface-b.minY() > MAX_CUT)
                    return failure(input, "此处没有可连接的地基或填挖量超出范围，请调整建筑占地或选址。");
                boolean pier = Math.floorMod(x-pad.minX(),4)==0 && Math.floorMod(z-pad.minZ(),4)==0
                        || (x==pad.maxX() && Math.floorMod(z-pad.minZ(),4)==0)
                        || (z==pad.maxZ() && Math.floorMod(x-pad.minX(),4)==0)
                        || x==pad.maxX() && z==pad.maxZ();
                // Fill small differences; leave open space between deep load-bearing piers.
                if (bottom-surface <= 3 || pier || strategy == GlobalConstraints.TerrainStrategy.FLATTEN) {
                    boolean braced = bottom-surface > 12 && strategy != GlobalConstraints.TerrainStrategy.FLATTEN
                            && diagonalBrace(prep, x, bottom-1, z, cached, fill);
                    if(!braced && (surface<=cached.bottomY() || bottom-surface>MAX_SUPPORT))
                        return failure(input,"支撑点未能连接到山体或地面，请调整建筑位置。");
                    for (int y=surface; !braced && y<bottom; y++) {
                        if (!add(prep, new BlockPos(x,y,z), fill)) return failure(input, "地基超出当前允许建造范围，请扩大选区或移动建筑。");
                        supports++;
                    }
                }
                // Only clear the occupied column, up to its actual roof, never the inter-building gap.
                for (int y=b.minY(); y<=top; y++) {
                    BlockPos p = new BlockPos(x,y,z);
                    if (!cached.state(p).isAir() && !add(prep,p,Blocks.AIR.getDefaultState()))
                        return failure(input, "建筑占地与保护区域冲突，请移动建筑。");
                }
                if (prep.size()>MAX_EDITS) return failure(input, "地形改动过多，建议缩小建筑或分批生成。");
            }
            if (sites.size()==1) {
                var slope=b.expand(4);
                for(int x=slope.minX();x<=slope.maxX();x++) for(int z=slope.minZ();z<=slope.maxZ();z++) {
                    int distance=Math.max(Math.max(b.minX()-x,x-b.maxX()),Math.max(b.minZ()-z,z-b.maxZ()));
                    if(distance<1) continue;
                    int allowed=b.minY()+distance-1, top=cached.surfaceY(x,z);
                    if(top-allowed>MAX_CUT) continue;
                    for(int y=allowed;y<top;y++) {
                        BlockPos p=new BlockPos(x,y,z);
                        if(!finalBlocks.containsKey(p) && !cached.state(p).isAir()) {
                            if(!add(prep,p,Blocks.AIR.getDefaultState()))
                                return failure(input,"削坡范围与保护区域冲突，请调整建筑位置。");
                        }
                    }
                }
            }
            Ground accessGround=new Ground() {
                public int surfaceY(int x,int z) {
                    int y=cached.surfaceY(x,z);
                    while(y>bottomY()) {
                        PlannedBlock edit=prep.get(new BlockPos(x,y-1,z));
                        if(edit==null||!edit.getTargetState().isAir()) break;
                        y--;
                    }
                    return y;
                }
                public int placementY(int x,int z) {
                    return cached.placementY(x,z)==cached.surfaceY(x,z)?surfaceY(x,z):cached.placementY(x,z);
                }
                public BlockState state(BlockPos p) {
                    PlannedBlock edit=prep.get(p);
                    return edit==null?cached.state(p):edit.getTargetState();
                }
                public int bottomY(){return cached.bottomY();}
            };
            Result access = entranceSteps(input, b, site.facing(), finalBlocks, accessGround, prep, fill);
            if (access.problem()!=null) return access;
            steps += access.steps();
        }
        if(prep.size()>MAX_EDITS) return failure(input,"地形改动过多，请缩小建筑占地或分批生成。");
        List<PlannedBlock> result = new ArrayList<>(prep.values());
        result.addAll(moved); // Building solids and air always win over terrain preparation.
        return new Result(result, dy, null, supports, steps);
    }

    private static int chooseElevation(List<Integer> offsets, List<Site> sites, Ground ground) {
        int low=offsets.getFirst(), high=offsets.getLast();
        int waterMinimum=Integer.MIN_VALUE;
        for (Site site:sites) {
            var b=site.body();
            for(int x=b.minX();x<=b.maxX();x++) for(int z=b.minZ();z<=b.maxZ();z++)
                if(ground.placementY(x,z)>ground.surfaceY(x,z))
                    waterMinimum=Math.max(waterMinimum,ground.placementY(x,z)-b.minY());
        }
        low=Math.max(low,waterMinimum);
        int best=Math.max(low,offsets.get(offsets.size()/2)); double bestCost=Double.POSITIVE_INFINITY;
        for(int candidate=low;candidate<=high;candidate++) {
            double cost=0;
            for(int offset:offsets) {
                int gap=candidate-offset;
                cost+=gap<0?(-gap)*(-gap)*1.2:gap<=3?gap*.6:2+gap*.25;
            }
            // Prefer the supplied elevation only when earthwork costs are comparable.
            cost+=Math.abs(candidate)*.01;
            if(cost<bestCost) {bestCost=cost;best=candidate;}
        }
        return best;
    }

    private static boolean diagonalBrace(Map<BlockPos,PlannedBlock> prep,int x,int y,int z,Ground ground,BlockState fill) {
        List<BlockPos> best=null;
        for(int[] direction:new int[][]{{1,0},{-1,0},{0,1},{0,-1}}) {
            List<BlockPos> path=new ArrayList<>();
            for(int step=0;step<48;step++) {
                BlockPos p=new BlockPos(x+direction[0]*step,y-step,z+direction[1]*step);
                if(ground.bearing(p)) {
                    if(!path.isEmpty() && (best==null || path.size()<best.size())) best=path;
                    break;
                }
                if(!BuildConstraintContext.allow(p) || !BuildConstraintContext.allow(p.up())) break;
                path.add(p);
                if(step>0) path.add(p.up()); // Face-connected diagonal, not isolated corner-touching cubes.
            }
        }
        if(best==null) return false;
        for(BlockPos p:best) add(prep,p,fill);
        return true;
    }

    private static Result entranceSteps(List<PlannedBlock> input, LlmPlanTerrainBounds.Bounds b,
                                        GlobalConstraints.Facing facing, Map<BlockPos,BlockState> blocks,
                                        Ground ground, Map<BlockPos,PlannedBlock> prep, BlockState fill) {
        int dx = facing == GlobalConstraints.Facing.EAST ? 1 : facing == GlobalConstraints.Facing.WEST ? -1 : 0;
        int dz = facing == GlobalConstraints.Facing.SOUTH ? 1 : facing == GlobalConstraints.Facing.NORTH ? -1 : 0;
        boolean alongX = dz != 0;
        int start = alongX ? b.minX()+1 : b.minZ()+1;
        int end = alongX ? b.maxX()-1 : b.maxZ()-1;
        List<BlockPos> candidates = new ArrayList<>();
        for (int v=start; v<=end; v++) {
            int x = alongX ? v : dx>0 ? b.maxX() : b.minX();
            int z = alongX ? dz>0 ? b.maxZ() : b.minZ() : v;
            BlockPos feet = new BlockPos(x,b.minY()+1,z);
            BlockState low = blocks.get(feet), high = blocks.get(feet.up());
            if (low!=null && (low.isAir() || low.getBlock() instanceof net.minecraft.block.DoorBlock)
                    && high!=null && (high.isAir() || high.getBlock() instanceof net.minecraft.block.DoorBlock))
                candidates.add(feet.down());
        }
        if (candidates.isEmpty()) return new Result(input,0,null,0,0); // No doorway (e.g. closed box).
        int center = (start+end)/2;
        BlockPos door = candidates.stream().min(Comparator.comparingInt(p -> Math.abs((alongX?p.getX():p.getZ())-center))).orElseThrow();
        return findAccess(input, door, b, blocks, ground, prep, fill);
    }

    private record AccessNode(BlockPos pos, double cost, AccessNode previous) {}

    private static Result findAccess(List<PlannedBlock> input, BlockPos door, LlmPlanTerrainBounds.Bounds body,
                                     Map<BlockPos,BlockState> blocks, Ground ground,
                                     Map<BlockPos,PlannedBlock> prep,BlockState fill) {
        PriorityQueue<AccessNode> open=new PriorityQueue<>(Comparator.comparingDouble(AccessNode::cost));
        Map<BlockPos,Double> visited=new HashMap<>();
        open.add(new AccessNode(door,0,null));visited.put(door,0.0);
        AccessNode goal=null;
        int expanded=0;
        while(!open.isEmpty() && expanded++<16000) {
            AccessNode node=open.remove(); BlockPos p=node.pos();
            if(node.cost()>visited.getOrDefault(p,Double.POSITIVE_INFINITY)) continue;
            int surface=ground.surfaceY(p.getX(),p.getZ());
            if(node.previous()!=null && p.getY()==surface-1 && ground.placementY(p.getX(),p.getZ())==surface) {
                goal=node;break;
            }
            for(int[] dir:new int[][]{{1,0},{-1,0},{0,1},{0,-1}}) for(int rise=-1;rise<=1;rise++) {
                BlockPos next=p.add(dir[0],rise,dir[1]);
                int x=next.getX(),z=next.getZ(),y=next.getY();
                if(Math.abs(x-door.getX())+Math.abs(z-door.getZ())>48) continue;
                if(x>=body.minX()&&x<=body.maxX()&&z>=body.minZ()&&z<=body.maxZ()) continue;
                int base=ground.surfaceY(x,z);
                if(base<=ground.bottomY()||y-base>MAX_SUPPORT||base-y>MAX_CUT) continue;
                if(blocks.containsKey(next)&&blocks.get(next).isAir()) continue;
                boolean blocked=false;
                for(int h=1;h<=2;h++) {
                    BlockPos head=next.up(h); BlockState planned=blocks.get(head);
                    if(planned!=null&&!planned.isAir()) {blocked=true;break;}
                    if(!BuildConstraintContext.allow(head)) {blocked=true;break;}
                }
                if(blocked) continue;
                for(int py=Math.min(base,y);py<=Math.max(base-1,y+2);py++)
                    if(!BuildConstraintContext.allow(new BlockPos(x,py,z))) {blocked=true;break;}
                if(blocked) continue;
                // Prefer short routes on dry ground, avoiding deep supported walkways and major cuts.
                double cost=node.cost()+1+Math.abs(rise)*.2+Math.max(0,y-base)*.25
                        +Math.max(0,base-y-1)*.8+(ground.placementY(x,z)>base?2:0);
                if(cost>=visited.getOrDefault(next,Double.POSITIVE_INFINITY)) continue;
                visited.put(next,cost);open.add(new AccessNode(next,cost,node));
            }
        }
        if(goal==null) return failure(input,"入口附近未找到可通行的接地路线，请扩大可建范围或调整入口位置。");
        List<BlockPos> route=new ArrayList<>();
        for(AccessNode n=goal;n.previous()!=null;n=n.previous()) route.add(n.pos());
        Collections.reverse(route);
        for(BlockPos p:route) {
            int base=ground.surfaceY(p.getX(),p.getZ());
            for(int y=base;y<=p.getY();y++) {
                BlockPos support=new BlockPos(p.getX(),y,p.getZ());
                if(!blocks.containsKey(support)) add(prep,support,fill);
            }
            for(int y=p.getY()+1;y<=Math.max(base-1,p.getY()+2);y++) {
                BlockPos head=new BlockPos(p.getX(),y,p.getZ());
                if(!blocks.containsKey(head)) add(prep,head,Blocks.AIR.getDefaultState());
            }
        }
        return new Result(input,0,null,0,route.size());
    }

    private static boolean add(Map<BlockPos,PlannedBlock> out, BlockPos p, BlockState s) {
        if (!BuildConstraintContext.allow(p)) return false;
        out.put(p,new PlannedBlock(p,s));
        return true;
    }
    private static Result failure(List<PlannedBlock> input,String problem) { return new Result(input,0,problem,0,0); }
    private static long key(int x,int z) { return ((long)x<<32) ^ (z & 0xffffffffL); }
}
