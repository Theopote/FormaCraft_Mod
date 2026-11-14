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
        assertTrue(ComponentEmitPass.isSoftSurfaceIncomplete("DOOR", autofillDoor),
                "autofill door incompleteness stays soft");
        assertFalse(ComponentEmitPass.isSoftSurfaceIncomplete("ENTRANCE", requiredEntrance),
                "player-required entrance incompleteness must hard-fail");
        assertTrue(ComponentEmitPass.isUserRequiredComponent(requiredEntrance));
    }
}
