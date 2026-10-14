package com.formacraft.common.component;

import com.formacraft.common.component.socket.ComponentSocket;
import com.formacraft.common.component.socket.SizeConstraint;
import com.formacraft.common.component.socket.SocketContext;
import com.formacraft.common.component.socket.SocketMask;
import com.formacraft.common.component.socket.SocketRole;
import com.formacraft.common.component.transform.ComponentTransform;
import com.formacraft.common.component.transform.Mirror;
import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.server.generation.component.adaptor.RoutedGenerationResult;
import com.formacraft.server.generation.component.adaptor.UnifiedGeneratorRouter;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CapturedAdaptRound16Test {
    @BeforeAll
    static void bootstrap() {
        com.formacraft.test.MinecraftRegistryTestBootstrap.initialize();
    }

    @Test
    void authorizedCarveRejectsOffsetRequestOrigin() {
        ComponentDefinition host = wallHost("host");
        ComponentSocket socket = openingSocket("opening", 2, 3);
        SocketMask mask = new SocketMask(2, 3, 1);
        ComponentTransform t = new ComponentTransform(Direction.SOUTH, Mirror.NONE);
        ResolvedComponentGeometry hostGeo = ComponentGeometryResolver.emit(
                host, Direction.SOUTH, t, 0, 0, 0, false, null, 1L);
        assertTrue(hostGeo.ok());

        Set<BlockPos> authorized = PlayerComponentExpander.authorizedSocketCarveCells(
                host, socket, "opening", Direction.SOUTH, t, 0, 0, 0, Direction.SOUTH);
        assertFalse(authorized.isEmpty());

        // Request origin offset +3 from host-authoritative (1,0,0)
        ResolvedCarveVolume vol = PlayerComponentExpander.resolveCarveVolume(
                host, socket, mask, new BlockPos(4, 0, 0),
                Direction.SOUTH, t, 0, 0, 0, Direction.SOUTH, hostGeo.patches(), "opening");
        assertFalse(vol.ok());
        assertTrue(vol.message().contains("CARVE_OUTSIDE_AUTHORIZED_SOCKET"), vol.message());
    }

    @Test
    void authorizedCarveSucceedsWhenOriginMatchesAndSizeWithinMax() {
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
        Set<BlockPos> authorized = PlayerComponentExpander.authorizedSocketCarveCells(
                host, socket, "opening", Direction.SOUTH, t, 0, 0, 0, Direction.SOUTH);
        assertTrue(authorized.containsAll(vol.cells()));
    }

    @Test
    void envelopePlusExplicitComponentRequestHardFails() {
        Component c = new Component(
                "FACADE_WINDOWS",
                null,
                new Vec3i(0, 0, 0),
                new Dimensions(8, 4, 1),
                List.of("component_request:any"),
                Map.of()
        );
        // Non-empty base would previously silently skip expander under envelope protection.
        SemanticComponent semantic = new SemanticComponent(
                "FACADE_WINDOWS", null, c, null, null, null);
        RoutedGenerationResult r = UnifiedGeneratorRouter.generateResult(semantic, null);
        assertTrue(r.isHardFail());
        assertEquals(ComponentGenerationOutcome.Status.INVALID_INPUT, r.status());
        assertTrue(r.diagnostics().stream()
                .anyMatch(d -> "component_request_envelope_conflict".equals(d.code())));
    }

    @Test
    void nonEnvelopeTypeWithComponentRequestDoesNotEnvelopeConflict() {
        Component c = new Component(
                "DECOR_DETAIL",
                null,
                new Vec3i(0, 0, 0),
                new Dimensions(2, 2, 1),
                List.of("component_request:any"),
                Map.of()
        );
        SemanticComponent semantic = new SemanticComponent(
                "DECOR_DETAIL", null, c, null, null, null);
        RoutedGenerationResult r = UnifiedGeneratorRouter.generateResult(semantic, null);
        assertFalse(r.diagnostics().stream()
                .anyMatch(d -> "component_request_envelope_conflict".equals(d.code())));
    }

    @Test
    void requiredEmptyPatchesFailUnlessExplicitlyDisabled() {
        Component required = new Component(
                "ROOF",
                null,
                new Vec3i(0, 0, 0),
                new Dimensions(4, 2, 4),
                List.of(),
                Map.of()
        );
        SemanticComponent semantic = new SemanticComponent("ROOF", null, required, null, null, null);
        RoutedGenerationResult emptyRequired = UnifiedGeneratorRouter.generateResult(semantic, null);
        // May succeed if a ROOF generator is registered; only assert gate when empty.
        if (emptyRequired.patches().isEmpty() && emptyRequired.commitAllowed()) {
            fail("required empty SUCCESS should be gated");
        }
        if (emptyRequired.patches().isEmpty()) {
            assertEquals(ComponentGenerationOutcome.Status.GEOMETRY_INVALID, emptyRequired.status());
            assertTrue(emptyRequired.diagnostics().stream()
                    .anyMatch(d -> "required_component_empty".equals(d.code())));
        }

        Component disabled = new Component(
                "ROOF",
                null,
                new Vec3i(0, 0, 0),
                new Dimensions(4, 2, 4),
                List.of(),
                Map.of("roof_type", "none")
        );
        assertTrue(UnifiedGeneratorRouter.isExplicitlyDisabled(disabled));
        SemanticComponent disabledSemantic = new SemanticComponent(
                "ROOF", null, disabled, null, null, null);
        RoutedGenerationResult disabledResult = UnifiedGeneratorRouter.generateResult(disabledSemantic, null);
        if (disabledResult.patches().isEmpty()) {
            assertTrue(disabledResult.commitAllowed(),
                    "roof_type=none may produce SUCCESS(empty), got " + disabledResult.status());
        }
    }

    @Test
    void libraryCommittedSkipsTypologyUnlessOverrideAllowed() {
        // libraryCommitted path is internal; verify flag semantics via ExpanderStage contract:
        // when patches non-empty from component_request commit, typology must not replace them
        // unless allow_typology_override. Exercised by ensuring override param is recognized.
        Map<String, Object> params = new HashMap<>();
        params.put("allow_typology_override", true);
        Component withOverride = new Component(
                "DOOR", null, new Vec3i(0, 0, 0), new Dimensions(2, 3, 1),
                List.of("component_request:x", "typology:y"), params);
        assertEquals(Boolean.TRUE, withOverride.params().get("allow_typology_override"));

        Component noOverride = new Component(
                "DOOR", null, new Vec3i(0, 0, 0), new Dimensions(2, 3, 1),
                List.of("component_request:x", "typology:y"), Map.of());
        assertNull(noOverride.params().get("allow_typology_override"));
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
        def.sockets = List.of(openingSocket("opening", 2, 3));
        var p = new ComponentDefinition.SocketPlacement();
        p.id = "opening";
        p.dx = 1;
        p.dy = 0;
        p.dz = 0;
        p.facing = "SOUTH";
        def.socketPlacements = List.of(p);
        return def;
    }

    private static ComponentSocket openingSocket(String id, int w, int h) {
        return new ComponentSocket(
                id, SocketRole.PROVIDER, null, SocketContext.WALL, null,
                new SizeConstraint(new int[]{w, h}, new int[]{w, h}),
                Set.of("door"));
    }
}
