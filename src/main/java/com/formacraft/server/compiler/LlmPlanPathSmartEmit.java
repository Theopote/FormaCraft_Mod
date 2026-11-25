package com.formacraft.server.compiler;

import com.formacraft.common.build.PlannedBlock;
import com.formacraft.common.component.transform.BlockStateStringUtil;
import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.generation.component.util.ComponentMaterialResolver;
import com.formacraft.common.generation.component.util.PathStripUtil;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.semantic.SemanticPart;
import com.formacraft.server.assembly.AssemblyCompileDiagnostics;
import com.formacraft.server.road.RoadPlanner;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * World-aware PATH/ROAD emit for LlmPlan (GEOMETRY).
 * Invoked only through {@link com.formacraft.server.generation.GenerationHub} /
 * {@link com.formacraft.server.generation.component.adaptor.UnifiedGeneratorRouter}
 * under {@code COMPONENTS} authority — never as a pre-hub bypass (Phase 01.6).
 * <p>
 * When world + from/to are available, prefer {@link RoadPlanner}; empty A* falls back to
 * world-free polyline via the component registry (soft warning, no redesign).
 */
public final class LlmPlanPathSmartEmit {
    private LlmPlanPathSmartEmit() {}

    public static boolean isPathLike(Component c) {
        if (c == null || c.componentType() == null) return false;
        String t = ComponentPlanTypes.normalizeType(c.componentType());
        return "PATH".equals(t) || "ROAD".equals(t) || "CONNECTOR".equals(t)
                || "BRIDGE".equals(t) || "BRIDGE_CONNECTOR".equals(t) || "PAVING".equals(t);
    }

    /**
     * @return patches in slot-local coords when A* succeeds; empty when skip or A* failed
     *         (caller keeps GenerationHub polyline/AABB).
     */
    public static Optional<List<BlockPatch>> tryEmit(
            SemanticComponent semantic,
            ServerWorld world,
            BlockPos globalAnchor,
            Vec3i slotAnchor
    ) {
        if (semantic == null || world == null || globalAnchor == null) return Optional.empty();
        Component c = semantic.source();
        if (!isPathLike(c)) return Optional.empty();
        PathStripUtil.Endpoints endpoints = PathStripUtil.resolveEndpoints(c);
        if (endpoints == null || endpoints.points().size() < 2) return Optional.empty();

        Vec3i from = endpoints.points().getFirst();
        Vec3i to = endpoints.points().getLast();
        int sax = slotAnchor == null ? 0 : slotAnchor.x();
        int say = slotAnchor == null ? 0 : slotAnchor.y();
        int saz = slotAnchor == null ? 0 : slotAnchor.z();

        BlockPos start = globalAnchor.add(sax + from.x(), say + from.y(), saz + from.z());
        BlockPos end = globalAnchor.add(sax + to.x(), say + to.y(), saz + to.z());

        String surfaceId = resolveSurfaceBlock(semantic, c);
        String edgeId = resolveEdgeBlock(semantic, c, surfaceId);
        BlockState road = parseState(surfaceId, Blocks.GRAVEL.getDefaultState());
        BlockState border = parseState(edgeId, Blocks.COBBLESTONE.getDefaultState());

        RoadPlanner.Config cfg = new RoadPlanner.Config(
                endpoints.width(),
                2,
                1,
                12000,
                12,
                2,
                6,
                road,
                border,
                endpoints.width() >= 3,
                Blocks.OAK_PLANKS.getDefaultState(),
                Blocks.OAK_FENCE.getDefaultState()
        );

        List<PlannedBlock> planned = RoadPlanner.build(world, start, end, cfg);
        if (planned == null || planned.isEmpty()) {
            AssemblyCompileDiagnostics.addSoftWarning(
                    "PATH_ASTAR_EMPTY",
                    "地形寻路未找到连通路径，回退折线铺装",
                    c.componentType(),
                    ComponentPlanTypes.paramIdentity(c)
            );
            return Optional.empty();
        }

        List<BlockPatch> out = new ArrayList<>(planned.size());
        for (PlannedBlock pb : planned) {
            if (pb == null || pb.getPos() == null || pb.getTargetState() == null) continue;
            BlockPos pos = pb.getPos();
            int dx = pos.getX() - globalAnchor.getX() - sax;
            int dy = pos.getY() - globalAnchor.getY() - say;
            int dz = pos.getZ() - globalAnchor.getZ() - saz;
            BlockState state = pb.getTargetState();
            String action = state.isAir() ? BlockPatch.REMOVE : BlockPatch.PLACE;
            String blockId = state.isAir() ? "minecraft:air" : serializeState(state);
            out.add(new BlockPatch(action, dx, dy, dz, blockId));
        }
        return out.isEmpty() ? Optional.empty() : Optional.of(out);
    }

    /** Preserve BlockState properties (facing/half/…) — never strip to bare block id. */
    static String serializeState(BlockState state) {
        if (state == null || state.isAir()) return "minecraft:air";
        return BlockStateStringUtil.fromState(state);
    }

    private static String resolveSurfaceBlock(SemanticComponent semantic, Component c) {
        String type = ComponentPlanTypes.normalizeType(c.componentType());
        boolean road = "ROAD".equals(type) || "PAVING".equals(type);
        String[] keys = road
                ? new String[]{"road_block", "path_block", "floor_block", "material"}
                : new String[]{"path_block", "floor_block", "material", "road_block"};
        String block = ComponentMaterialResolver.resolve(
                semantic, SemanticPart.ROAD_SURFACE, "minecraft:gravel", keys);
        return block != null && !block.isBlank() ? block : "minecraft:gravel";
    }

    private static String resolveEdgeBlock(SemanticComponent semantic, Component c, String surface) {
        String block = ComponentMaterialResolver.resolve(
                semantic, SemanticPart.ROAD_EDGE, null,
                "border_block", "edge_block", "road_block", "path_block", "material");
        if (block == null || block.isBlank()) return "minecraft:cobblestone";
        return block;
    }

    private static BlockState parseState(String id, BlockState fallback) {
        if (id == null || id.isBlank()) return fallback;
        String raw = id.trim().toLowerCase(Locale.ROOT);
        if (!raw.contains(":")) raw = "minecraft:" + raw;
        int bracket = raw.indexOf('[');
        if (bracket >= 0) raw = raw.substring(0, bracket);
        try {
            Identifier ident = Identifier.tryParse(raw);
            if (ident == null) return fallback;
            var block = Registries.BLOCK.get(ident);
            if (block == null || block == Blocks.AIR) return fallback;
            return block.getDefaultState();
        } catch (Exception e) {
            return fallback;
        }
    }
}
