package com.formacraft.common.component;

import com.formacraft.common.component.socket.ComponentSocket;
import com.formacraft.common.component.socket.OpeningRegion;
import com.formacraft.common.component.socket.SizeConstraint;
import com.formacraft.common.component.socket.SocketContext;
import com.formacraft.common.component.socket.SocketMask;
import com.formacraft.common.component.socket.SocketRole;
import com.formacraft.common.component.transform.ComponentTransform;
import com.formacraft.common.component.transform.Mirror;
import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.generation.component.ComponentGeneratorRegistry;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.server.generation.component.adaptor.RoutedGenerationResult;
import com.formacraft.server.generation.component.adaptor.UnifiedGeneratorRouter;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class CapturedAdaptRound17Test {
    @BeforeAll
    static void bootstrap() {
        com.formacraft.test.MinecraftRegistryTestBootstrap.initialize();
    }

    @Test
    void openingRegionRestrictsAuthorizedCarveBelowSizeMax() {
        ComponentDefinition host = wallHost("host");
        // size.max allows 4x3 but OpeningRegion only authorizes 2x3 at socket origin
        host.sockets = List.of(openingSocket("opening", 2, 3, 4, 3));
        var placement = host.socketPlacements.getFirst();
        placement.openingRegion = OpeningRegion.box(2, 3, 1);

        for (var b : host.blocks) b.carveRole = "WALL_FILL";
        ComponentSocket socket = host.sockets.getFirst();
        ComponentTransform t = new ComponentTransform(Direction.SOUTH, Mirror.NONE);
        ResolvedComponentGeometry hostGeo = ComponentGeometryResolver.emit(
                host, Direction.SOUTH, t, 0, 0, 0, false, null, 1L);
        assertTrue(hostGeo.ok());

        Set<BlockPos> authorized = PlayerComponentExpander.authorizedSocketCarveCells(
                host, socket, "opening", Direction.SOUTH, t, 0, 0, 0, Direction.SOUTH);
        assertEquals(6, authorized.size());

        // Request 2x3 at origin — ok
        ResolvedCarveVolume ok = PlayerComponentExpander.resolveCarveVolume(
                host, socket, new SocketMask(2, 3, 1), new BlockPos(1, 0, 0),
                Direction.SOUTH, t, 0, 0, 0, Direction.SOUTH, hostGeo.patches(), "opening");
        assertTrue(ok.ok(), ok.message());

        // Request offset +2 within size.max but outside OpeningRegion — fail
        ResolvedCarveVolume bad = PlayerComponentExpander.resolveCarveVolume(
                host, socket, new SocketMask(2, 3, 1), new BlockPos(3, 0, 0),
                Direction.SOUTH, t, 0, 0, 0, Direction.SOUTH, hostGeo.patches(), "opening");
        assertFalse(bad.ok());
        assertTrue(bad.message().contains("CARVE_OUTSIDE_AUTHORIZED_SOCKET"), bad.message());
    }

    @Test
    void groupRequestOnEnvelopeTypeConflictsLikeComponentRequest() {
        Component c = new Component(
                "FACADE_WINDOWS",
                null,
                new Vec3i(0, 0, 0),
                new Dimensions(8, 4, 1),
                List.of("group_request:{\"group_id\":\"x\"}"),
                Map.of()
        );
        SemanticComponent semantic = new SemanticComponent(
                "FACADE_WINDOWS", null, c, null, null, null);
        RoutedGenerationResult r = UnifiedGeneratorRouter.generateResult(semantic, null);
        assertTrue(r.isHardFail());
        assertTrue(r.diagnostics().stream()
                .anyMatch(d -> "component_request_envelope_conflict".equals(d.code())));
    }

    @Test
    void explicitLibraryRequestSkipsThrowingDefaultGeneratorOnEnvelopeConflict() {
        Component facade = new Component(
                "FACADE_WINDOWS",
                null,
                new Vec3i(0, 0, 0),
                new Dimensions(8, 4, 1),
                List.of("component_request:{\"component_id\":\"win\"}"),
                Map.of()
        );
        AtomicBoolean facadeGenRan = new AtomicBoolean(false);
        var previous = ComponentGeneratorRegistry.getGenerator("FACADE_WINDOWS");
        ComponentGeneratorRegistry.register("FACADE_WINDOWS", semantic -> {
            facadeGenRan.set(true);
            throw new IllegalStateException("should_not_run_on_envelope_conflict");
        });
        try {
            RoutedGenerationResult r = UnifiedGeneratorRouter.generateResult(
                    new SemanticComponent("FACADE_WINDOWS", null, facade, null, null, null), null);
            assertTrue(r.isHardFail());
            assertTrue(r.diagnostics().stream()
                    .anyMatch(d -> "component_request_envelope_conflict".equals(d.code())));
            assertFalse(facadeGenRan.get(), "authority-first must not invoke default generator on envelope conflict");
        } finally {
            if (previous != null) {
                ComponentGeneratorRegistry.register("FACADE_WINDOWS", previous);
            }
        }
    }

    @Test
    void requiredTypeWithoutLibraryStillFailsOnGeneratorException() {
        var previous = ComponentGeneratorRegistry.getGenerator("CHIMNEY");
        ComponentGeneratorRegistry.register("CHIMNEY", semantic -> {
            throw new IllegalStateException("chimney_boom");
        });
        try {
            Component c = new Component(
                    "CHIMNEY", null, new Vec3i(0, 0, 0), new Dimensions(1, 4, 1),
                    List.of(), Map.of());
            RoutedGenerationResult r = UnifiedGeneratorRouter.generateResult(
                    new SemanticComponent("CHIMNEY", null, c, null, null, null), null);
            assertTrue(r.isHardFail());
            assertEquals(ComponentGenerationOutcome.Status.GEOMETRY_INVALID, r.status());
            assertTrue(r.diagnostics().stream()
                    .anyMatch(d -> "generator_exception".equals(d.code())));
        } finally {
            if (previous != null) {
                ComponentGeneratorRegistry.register("CHIMNEY", previous);
            }
        }
    }

    @Test
    void openingRegionLocalCellsFromBox() {
        OpeningRegion r = OpeningRegion.box(2, 3, 1);
        assertEquals(6, r.localCells().size());
        assertFalse(r.isEmpty());
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
                b.carveRole = "WALL_FILL";
                def.blocks.add(b);
            }
        }
        def.sockets = List.of(openingSocket("opening", 2, 3, 2, 3));
        var p = new ComponentDefinition.SocketPlacement();
        p.id = "opening";
        p.dx = 1;
        p.dy = 0;
        p.dz = 0;
        p.facing = "SOUTH";
        def.socketPlacements = new ArrayList<>(List.of(p));
        return def;
    }

    private static ComponentSocket openingSocket(String id, int minW, int minH, int maxW, int maxH) {
        return new ComponentSocket(
                id, SocketRole.PROVIDER, null, SocketContext.WALL, null,
                new SizeConstraint(new int[]{minW, minH}, new int[]{maxW, maxH}),
                Set.of("door"));
    }
}
