package com.formacraft.server.compiler;

import com.formacraft.common.llm.dto.*;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import com.formacraft.server.assembly.AssemblyCompileDiagnostics;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LoggedStiltHutTest {
    @BeforeAll static void initialize() { MinecraftRegistryTestBootstrap.initialize(); }
    @AfterEach void clear() { AssemblyCompileDiagnostics.clear(); }

    @Test void loggedSolidPilingsAreNotExpandedIntoThreeWideCorridors() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper()
                .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,false);
        LlmPlan plan;
        try(var in=getClass().getResourceAsStream("/regressions/stilt-hut-20261025.json")) {
            plan=mapper.readValue(in,LlmPlan.class);
        }
        var prepare=ComponentPlanCompiler.class.getDeclaredMethod("prepareComponents",LlmPlan.class,Map.class,boolean.class);
        prepare.setAccessible(true);
        Object prepared=prepare.invoke(null,plan,Map.of(),false);
        @SuppressWarnings("unchecked") var components=(List<Component>)prepared.getClass().getRecordComponents()[0].getAccessor().invoke(prepared);
        var piles=components.stream().filter(c->"MASS_SECONDARY".equals(c.componentType())
                && c.params()!=null && "stilt_piling".equals(c.params().get("role"))).toList();
        assertEquals(4,piles.size());
        for(var pile:piles) {
            assertEquals(1,pile.dimensions().width());
            assertEquals(1,pile.dimensions().depth());
            assertNotEquals(Boolean.TRUE,pile.params().get("compiler_connector_body"));
        }
        AssemblyCompileDiagnostics.clear();
        var blocks=ComponentPlanCompiler.compile(plan,BlockPos.ORIGIN,null,null,false);
        assertNull(AssemblyCompileDiagnostics.get(),"Logged plan must compile without the phantom piling-wall collision");
        assertFalse(blocks.isEmpty());
    }
}
