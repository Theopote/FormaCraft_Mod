package com.formacraft.common.component;

import com.formacraft.common.component.socket.ComponentSocket;
import com.formacraft.common.component.socket.SizeConstraint;
import com.formacraft.common.component.socket.SocketContext;
import com.formacraft.common.component.socket.SocketMask;
import com.formacraft.common.component.socket.SocketRole;
import com.formacraft.common.component.transform.ComponentTransform;
import com.formacraft.common.component.transform.Mirror;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.server.generation.component.adaptor.RoutedGenerationResult;
import com.formacraft.server.generation.component.adaptor.UnifiedGeneratorRouter;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CapturedAdaptRound15Test {
    @BeforeAll
    static void bootstrap() {
        com.formacraft.test.MinecraftRegistryTestBootstrap.initialize();
    }

    @Test
    void routedHardFailDoesNotCommitPatches() {
        RoutedGenerationResult fail = RoutedGenerationResult.failure(
                ComponentGenerationOutcome.Status.LOAD_FAILED, "load_failed", "missing");
        assertTrue(fail.isHardFail());
        assertFalse(fail.commitAllowed());
        assertTrue(fail.patches().isEmpty());
    }

    @Test
    void partialSuccessIsCommitAllowedAndDegraded() {
        ComponentGenerationOutcome o = ComponentGenerationOutcome.partialSuccess(
                List.of(new BlockPatch(BlockPatch.PLACE, 0, 0, 0, "minecraft:stone")),
                "degraded_optional_mount", "host only");
        assertTrue(o.commitAllowed());
        assertTrue(o.degraded());
        assertFalse(o.ok());
        assertFalse(o.isHardFail());
        RoutedGenerationResult r = RoutedGenerationResult.from(o);
        assertTrue(r.commitAllowed());
        assertTrue(r.degraded());
    }

    @Test
    void requiredComponentTypesDetected() {
        assertTrue(UnifiedGeneratorRouter.isRequiredComponentType("MASS_MAIN"));
        assertTrue(UnifiedGeneratorRouter.isRequiredComponentType("ROOF"));
        assertTrue(UnifiedGeneratorRouter.isRequiredComponentType("ENTRANCE"));
        assertFalse(UnifiedGeneratorRouter.isRequiredComponentType("DECOR_DETAIL"));
        assertFalse(UnifiedGeneratorRouter.isRequiredComponentType("DECOR_TRIM"));
    }

    @Test
    void carveRoleStructureProtectsStoneBricks() {
        var be = new ComponentDefinition.BlockEntry();
        be.block = "minecraft:stone_bricks";
        be.carveRole = "STRUCTURE";
        assertEquals(PlayerComponentExpander.CarveRole.STRUCTURE,
                PlayerComponentExpander.resolveCarveRole(be, be.block));
        assertTrue(PlayerComponentExpander.resolveCarveRole(be, be.block).protectedFromCarve());

        be.carveRole = "WALL_FILL";
        assertFalse(PlayerComponentExpander.resolveCarveRole(be, be.block).protectedFromCarve());
    }

    @Test
    void blankBlockIsUnknownProtected_ordinaryWallIsFill() {
        assertEquals(PlayerComponentExpander.CarveRole.UNKNOWN,
                PlayerComponentExpander.resolveCarveRole(null, ""));
        assertTrue(PlayerComponentExpander.resolveCarveRole(null, "").protectedFromCarve());
        assertEquals(PlayerComponentExpander.CarveRole.WALL_FILL,
                PlayerComponentExpander.resolveCarveRole(null, "minecraft:stone_bricks"));
        assertFalse(PlayerComponentExpander.resolveCarveRole(null, "minecraft:stone_bricks").protectedFromCarve());
        var unknown = new ComponentDefinition.BlockEntry();
        unknown.carveRole = "UNKNOWN";
        unknown.block = "minecraft:stone_bricks";
        assertTrue(PlayerComponentExpander.resolveCarveRole(unknown, unknown.block).protectedFromCarve());
    }

    @Test
    void structureTaggedStoneBrickBlocksCarve() {
        ComponentDefinition host = wallHost("host");
        host.blocks.getFirst().carveRole = "STRUCTURE";
        host.blocks.getFirst().block = "minecraft:stone_bricks";
        ComponentSocket socket = openingSocket("opening", 2, 3);
        SocketMask mask = new SocketMask(2, 3, 1);
        ComponentTransform t = new ComponentTransform(Direction.SOUTH, Mirror.NONE);
        ResolvedComponentGeometry hostGeo = ComponentGeometryResolver.emit(
                host, Direction.SOUTH, t, 0, 0, 0, false, null, 1L);
        assertTrue(hostGeo.ok());
        ResolvedCarveVolume vol = PlayerComponentExpander.resolveCarveVolume(
                host, socket, mask, BlockPos.ORIGIN,
                Direction.SOUTH, t, 0, 0, 0, Direction.SOUTH, hostGeo.patches(), "opening");
        assertFalse(vol.ok());
        assertTrue(vol.message().contains("protected"));
    }

    @Test
    void wallFillAllowsCarveInsideOpening() {
        ComponentDefinition host = wallHost("host");
        for (var b : host.blocks) b.carveRole = "WALL_FILL";
        ComponentSocket socket = openingSocket("opening", 2, 3);
        SocketMask mask = new SocketMask(2, 3, 1);
        ComponentTransform t = new ComponentTransform(Direction.SOUTH, Mirror.NONE);
        ResolvedComponentGeometry hostGeo = ComponentGeometryResolver.emit(
                host, Direction.SOUTH, t, 0, 0, 0, false, null, 1L);
        ResolvedCarveVolume vol = PlayerComponentExpander.resolveCarveVolume(
                host, socket, mask, new BlockPos(1, 0, 0),
                Direction.SOUTH, t, 0, 0, 0, Direction.SOUTH, hostGeo.patches(), "opening");
        assertTrue(vol.ok(), vol.message());
        assertEquals(6, vol.cells().size());
    }

    @Test
    void carveAabbContainsLegalCellsOnFourFacings() {
        SocketMask mask = new SocketMask(2, 3, 1);
        BlockPos origin = BlockPos.ORIGIN;
        Direction from = Direction.SOUTH;
        for (Direction facing : List.of(Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST)) {
            ComponentTransform t = new ComponentTransform(facing, Mirror.NONE);
            int[] aabb = PlayerComponentExpander.carveOpeningAabb(
                    mask, origin, from, t, 0, 0, 0, facing);
            List<BlockPatch> removes = PlayerComponentExpander.emitCarveRemoves(
                    mask, origin, from, t, 0, 0, 0, facing);
            for (BlockPatch p : removes) {
                assertTrue(PlayerComponentExpander.insideInclusiveAabb(
                        new BlockPos(p.dx(), p.dy(), p.dz()), aabb),
                        "facing=" + facing + " cell=" + p.dx() + "," + p.dy() + "," + p.dz());
            }
        }
    }

    @Test
    void emptyGeometryRejectedMatchesHardFailFamily() {
        ComponentDefinition def = wallHost("empty");
        def.blocks = List.of();
        var preview = com.formacraft.server.patch.ComponentPatchPreviewBuilder.fromComponentDefinitionResult(
                def, Direction.SOUTH, Mirror.NONE, false, null, 1L);
        assertEquals(ComponentGenerationOutcome.Status.TEMPLATE_MISSING, preview.status());
        assertTrue(preview.isHardFail());
    }

    private static ComponentDefinition wallHost(String id) {
        var def = new ComponentDefinition();
        def.id = id;
        def.sourceOrigin = ComponentDefinition.ORIGIN_CAPTURED;
        def.anchor = new ComponentDefinition.Anchor();
        def.anchor.facing = "SOUTH";
        def.size = new ComponentDefinition.Size();
        def.size.w = 5;
        def.size.h = 3;
        def.size.d = 1;
        def.blocks = new ArrayList<>();
        for (int x = 0; x < 5; x++) {
            for (int y = 0; y < 3; y++) {
                var b = new ComponentDefinition.BlockEntry();
                b.dx = x;
                b.dy = y;
                b.block = "minecraft:stone_bricks";
                def.blocks.add(b);
            }
        }
        return def;
    }

    private static ComponentSocket openingSocket(String id, int w, int h) {
        return new ComponentSocket(
                id, SocketRole.PROVIDER, null, SocketContext.WALL, null,
                new SizeConstraint(new int[]{w, h}, new int[]{w, h}),
                Set.of("door"));
    }
}
