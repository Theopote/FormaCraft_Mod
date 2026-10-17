package com.formacraft.server.compiler;

import com.formacraft.common.llm.dto.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HostedBridgePlatformEndpointTest {
    @Test void independentPlatformIsAnEndpointButNotAnInteriorGeometryHost() {
        var platform = platform(Map.of("component_id","quay","extrude_mode","plate","plate_role","platform"));
        assertEquals(platform,HostedPlateDesignValidator.findBridgeEndpoint("quay",List.of(platform)).orElseThrow());
        assertTrue(HostedPlateDesignValidator.findHostMass("quay",List.of(platform)).isEmpty());
    }
    @Test void interiorPlatesAndPlateHostChainsAreNotStandaloneBridgeEndpoints() {
        for (var params : List.of(
                Map.<String,Object>of("component_id","quay","extrude_mode","plate","plate_role","interior"),
                Map.<String,Object>of("component_id","quay","extrude_mode","plate","plate_role","platform","host_id","other"))) {
            assertTrue(HostedPlateDesignValidator.findBridgeEndpoint("quay",List.of(platform(params))).isEmpty());
        }
    }
    private Component platform(Map<String,Object> params) {
        return new Component("MASS_SECONDARY","s",new Vec3i(0,0,0),new Dimensions(4,6,1),List.of(),params);
    }
}
