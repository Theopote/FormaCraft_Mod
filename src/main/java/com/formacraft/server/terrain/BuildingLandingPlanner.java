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
    private static final int MAX_SUPPORT = 12;
    private static final int MAX_CUT = 6;
    private static final int MAX_AREA = 16384;
    private static final int MAX_EDITS = 200000;

    public interface Ground {
        int surfaceY(int x, int z);
        default int placementY(int x, int z) { return surfaceY(x,z); }
        BlockState state(BlockPos pos);
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
            if (!ComponentFootprintUtil.isMassType(c.componentType()) || c.dimensions() == null) continue;
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
            out.add(new Site(bounds, facing == null ? GlobalConstraints.Facing.NORTH : facing));
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
        int dy = stilt ? 0 : offsets.get(offsets.size()/2);
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
                if (surface <= cached.bottomY() || bottom-surface > MAX_SUPPORT || surface-b.minY() > MAX_CUT)
                    return failure(input, "此处落差过大，地基需要超过12格支撑或6格削坡；建议移到缓坡，或调整选址。");
                boolean pier = Math.floorMod(x-pad.minX(),4)==0 && Math.floorMod(z-pad.minZ(),4)==0
                        || (x==pad.maxX() && Math.floorMod(z-pad.minZ(),4)==0)
                        || (z==pad.maxZ() && Math.floorMod(x-pad.minX(),4)==0)
                        || x==pad.maxX() && z==pad.maxZ();
                // Fill small differences; leave open space between deep load-bearing piers.
                if (bottom-surface <= 3 || pier || strategy == GlobalConstraints.TerrainStrategy.FLATTEN) {
                    for (int y=surface; y<bottom; y++) {
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
            Result access = entranceSteps(input, b, site.facing(), finalBlocks, cached, prep, fill);
            if (access.problem()!=null) return access;
            steps += access.steps();
        }
        List<PlannedBlock> result = new ArrayList<>(prep.values());
        result.addAll(moved); // Building solids and air always win over terrain preparation.
        return new Result(result, dy, null, supports, steps);
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
        int previous = door.getY(), steps = 0;
        for (int distance=1; distance<=MAX_SUPPORT+3; distance++) {
            int x=door.getX()+dx*distance, z=door.getZ()+dz*distance;
            int groundY=ground.surfaceY(x,z)-1;
            int tread = Math.max(previous-1, Math.min(previous+1,groundY));
            // Cross an existing porch at its authored height before descending beyond its edge.
            for (int y=previous+1; y>=tread; y--) {
                BlockState authored = blocks.get(new BlockPos(x,y,z));
                if (authored!=null && !authored.isAir()) { tread=y; break; }
            }
            for (int side=-1; side<=1; side++) {
                int px=x+(alongX?side:0), pz=z+(alongX?0:side);
                int base=ground.surfaceY(px,pz);
                if (tread-base > MAX_SUPPORT || base-tread > MAX_CUT || base<=ground.bottomY())
                    return failure(input,"入口前方落差过大，无法生成接地台阶；建议调整入口朝向或选址。");
                BlockPos p=new BlockPos(px,tread,pz);
                // Stop instead of overwriting another building or authored access structure.
                if (blocks.containsKey(p.up()) && !blocks.get(p.up()).isAir()
                        || blocks.containsKey(p.up(2)) && !blocks.get(p.up(2)).isAir())
                    return failure(input,"入口通路被其他构件阻挡，请调整建筑间距或入口朝向。");
                for (int y=base; y<=tread; y++) if (!add(prep,new BlockPos(px,y,pz),fill))
                    return failure(input,"入口台阶超出允许建造范围，请扩大选区或移动建筑。");
                for (int y=tread+1; y<=Math.max(base-1,tread+2); y++) {
                    BlockPos clear=new BlockPos(px,y,pz);
                    if (!add(prep,clear,Blocks.AIR.getDefaultState()))
                        return failure(input,"入口通路与保护区域冲突，请移动建筑。");
                }
            }
            steps++;
            if (tread==groundY) return new Result(input,0,null,0,steps);
            previous=tread;
        }
        return failure(input,"入口台阶未能连接到地面，请调整入口朝向或选址。");
    }

    private static boolean add(Map<BlockPos,PlannedBlock> out, BlockPos p, BlockState s) {
        if (!BuildConstraintContext.allow(p)) return false;
        out.put(p,new PlannedBlock(p,s));
        return true;
    }
    private static Result failure(List<PlannedBlock> input,String problem) { return new Result(input,0,problem,0,0); }
    private static long key(int x,int z) { return ((long)x<<32) ^ (z & 0xffffffffL); }
}
