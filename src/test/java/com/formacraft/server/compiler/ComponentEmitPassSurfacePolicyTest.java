package com.formacraft.server.compiler;

import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.Vec3i;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ComponentEmitPassSurfacePolicyTest {

    @Test
    void ordinaryOpeningsRemainSoftButUserRequiredEntrancesHardFail() {
        Component autofillDoor = new Component(
                "DOOR", "body", new Vec3i(0, 0, 0),
                new Dimensions(2, 1, 3), List.of(),
                Map.of("component_id", "auto_door"));
        Component requiredEntrance = new Component(
                "ENTRANCE", "body", new Vec3i(0, 0, 0),
                new Dimensions(3, 1, 4), List.of("入口"),
                Map.of("component_id", "main_entrance",
                        "requirement_level", "USER_REQUIRED",
                        "constraint_source", "USER_EXPLICIT"));
        Component materialOnlyWindow = new Component(
                "FACADE_WINDOWS", "body", new Vec3i(0, 2, 0),
                new Dimensions(5, 1, 3), List.of(),
                Map.of("component_id", "hall_win",
                        "glass_block", "minecraft:blue_stained_glass",
                        "window_explicit", true,
                        "window_material_explicit", true,
                        "constraint_sources", Map.of("glass_block", "USER_EXPLICIT")));
        Component presenceWindow = new Component(
                "FACADE_WINDOWS", "body", new Vec3i(0, 2, 0),
                new Dimensions(5, 1, 3), List.of(),
                Map.of("component_id", "hall_win_required",
                        "window_presence_explicit", true,
                        "constraint_sources", Map.of("opening_presence", "USER_EXPLICIT"),
                        "constraint_source", "USER_EXPLICIT"));
        assertTrue(ComponentEmitPass.isSoftSurfaceIncomplete("DOOR", autofillDoor),
                "autofill door incompleteness stays soft");
        assertFalse(ComponentEmitPass.isSoftSurfaceIncomplete("ENTRANCE", requiredEntrance),
                "player-required entrance incompleteness must hard-fail");
        assertTrue(ComponentEmitPass.isSoftSurfaceIncomplete("FACADE_WINDOWS", materialOnlyWindow),
                "glass material alone must not hard-fail missing panes");
        assertFalse(ComponentEmitPass.isSoftSurfaceIncomplete("FACADE_WINDOWS", presenceWindow),
                "explicit opening presence must hard-fail missing panes");
        assertTrue(ComponentEmitPass.isUserRequiredComponent(requiredEntrance));
        assertFalse(ComponentEmitPass.isUserRequiredOpeningPresence(materialOnlyWindow));
        assertTrue(ComponentEmitPass.isUserRequiredOpeningPresence(presenceWindow));
    }
}
