package com.formacraft.server.compiler;

import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.server.assembly.AssemblyCompileDiagnostics;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class LoggedHuizhouCompilerTest {
    @BeforeAll static void initialize() { MinecraftRegistryTestBootstrap.initialize(); }
    @AfterEach void clear() { AssemblyCompileDiagnostics.clear(); }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"huizhou","pavilions"})
    void loggedCompoundPlanCompilesWithoutConflict(String fixture) throws Exception {
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper()
                .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,false);
        LlmPlan plan;
        try(var in=getClass().getResourceAsStream("/regressions/live-20261025-"+fixture+".json")) {
            plan=mapper.readValue(in,LlmPlan.class);
        }
        AssemblyCompileDiagnostics.clear();
        var blocks=ComponentPlanCompiler.compile(plan,BlockPos.ORIGIN,null,null,false);
        assertNull(AssemblyCompileDiagnostics.get());
        assertFalse(blocks.isEmpty());
    }
}
