package com.formacraft.server.compiler;

import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.LlmPlanTestFixtures;
import com.formacraft.common.llm.dto.Vec3i;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class StackedMassHostBindRepairTest {
    @Test
    void doesNotBindFreeTerraceLandingOntoBridgePlate() {
        var mill = mass("MASS_MAIN", "mill_main", new Vec3i(0, 0, 0), new Dimensions(8, 8, 6), Map.of());
        var landing = mass("MASS_ANCILLARY", "bank_landing", new Vec3i(-6, 1, 6), new Dimensions(2, 5, 1),
                Map.of("extrude_mode", "plate", "plate_role", "terrace"));
        var bridge = mass("MASS_SECONDARY", "bridge_wooden", new Vec3i(-6, 1, 7), new Dimensions(7, 3, 1),
                Map.of("extrude_mode", "plate", "plate_role", "bridge",
                        "host_id", "mill_main", "target_host_id", "bank_landing"));
        LlmPlan out = StackedMassHostBindRepair.apply(
                LlmPlanTestFixtures.builder().components(List.of(mill, landing, bridge)).build());

        Component repairedLanding = out.components().stream()
                .filter(c -> "bank_landing".equals(String.valueOf(c.params().get("component_id"))))
                .findFirst().orElseThrow();
        assertNull(repairedLanding.params().get("host_id"),
                "free terrace endpoints must stay unhosted for findBridgeEndpoint");
        assertEquals(repairedLanding,
                HostedPlateDesignValidator.findBridgeEndpoint("bank_landing", out.components()).orElseThrow());
    }

    @Test
    void stillBindsStackedUpperShellOntoLowerShell() {
        var ground = mass("MASS_MAIN", "tower_ground", new Vec3i(0, 0, 0), new Dimensions(6, 6, 8), Map.of());
        var belfry = mass("MASS_SECONDARY", "tower_belfry", new Vec3i(1, 8, 1), new Dimensions(4, 4, 5), Map.of());
        LlmPlan out = StackedMassHostBindRepair.apply(
                LlmPlanTestFixtures.builder().components(List.of(ground, belfry)).build());
        Component upper = out.components().stream()
                .filter(c -> "tower_belfry".equals(String.valueOf(c.params().get("component_id"))))
                .findFirst().orElseThrow();
        assertEquals("tower_ground", upper.params().get("host_id"));
    }

    private static Component mass(String type, String id, Vec3i pos, Dimensions dims, Map<String, Object> extra) {
        Map<String, Object> params = new HashMap<>(extra);
        params.put("component_id", id);
        return new Component(type, "mill_slot", pos, dims, List.of(), params);
    }
}
