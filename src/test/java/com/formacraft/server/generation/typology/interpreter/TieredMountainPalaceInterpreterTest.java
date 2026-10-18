package com.formacraft.server.generation.typology.interpreter;

import com.formacraft.common.component.ComponentGenerationOutcome;
import com.formacraft.common.component.GenerationDiagnostic;
import com.formacraft.common.typology.TypologyGenerationException;
import com.formacraft.common.typology.TypologyPatchBridge;
import com.formacraft.server.generation.component.adaptor.ComponentExecutorId;
import com.formacraft.server.generation.component.adaptor.ComponentPipelineStepId;
import com.formacraft.server.generation.component.adaptor.RoutedGenerationResult;
import com.formacraft.server.generation.typology.builder.TieredMountainPalaceBuilder;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TieredMountainPalaceInterpreterTest {

    @BeforeAll
    static void init() {
        MinecraftRegistryTestBootstrap.initialize();
    }

    @AfterEach
    void clearAnchor() {
        TypologyPatchBridge.clearPlanWorldAnchor();
    }

    @Test
    void generateDetailedFailureIsNotEmptySuccess() {
        Map<String, Object> params = baseParams();
        params.put("forceFailCirculation", true);
        var result = TieredMountainPalaceBuilder.generateDetailed(
                params, new BlockPos(0, 64, 0), null, null);
        assertFalse(result.success());
        assertTrue(result.structure().getBlocks().isEmpty());
        assertTrue(result.structure().getDescription().contains("status=FAILED"));
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.code().startsWith("STAIR_GAP")));
    }

    @Test
    void failedResultMapsToTypologyGenerationExceptionContract() {
        var result = TieredMountainPalaceBuilder.generateDetailed(
                baseParamsWithForceFail(), new BlockPos(100, 64, 200), null, null);
        assertFalse(result.success());
        TypologyGenerationException ex = assertThrows(TypologyGenerationException.class, () -> {
            if (!result.success()) {
                throw new TypologyGenerationException(
                        TieredMountainPalaceBuilder.TYPOLOGY_ID,
                        "CIRCULATION_FAILED",
                        "tiered_mountain_palace generation failed",
                        result.diagnostics().stream().map(Object::toString).toList());
            }
        });
        assertEquals("CIRCULATION_FAILED", ex.code());
        assertEquals(TieredMountainPalaceBuilder.TYPOLOGY_ID, ex.typologyId());
        assertFalse(ex.diagnostics().isEmpty());
    }

    @Test
    void typologyGenerationExceptionBecomesRoutedHardFailNotSilentEmpty() {
        TypologyGenerationException boom = new TypologyGenerationException(
                TieredMountainPalaceBuilder.TYPOLOGY_ID,
                "CIRCULATION_FAILED",
                "circulation failed: STAIR_GAP",
                List.of("STAIR_GAP:forced_test_failure"));
        RoutedGenerationResult routed = new RoutedGenerationResult(
                ComponentGenerationOutcome.Status.GEOMETRY_INVALID,
                List.of(),
                List.of(
                        new GenerationDiagnostic(boom.code(), boom.getMessage()),
                        new GenerationDiagnostic("TYPOLOGY_DIAG", boom.diagnostics().getFirst())
                ),
                ComponentExecutorId.COMPONENT_PIPELINE,
                ComponentPipelineStepId.TYPOLOGY,
                null,
                "TEST:TYPOLOGY"
        );
        assertTrue(routed.isHardFail());
        assertFalse(routed.commitAllowed());
        assertTrue(routed.patches().isEmpty());
        assertTrue(routed.diagnostics().stream().anyMatch(d -> "CIRCULATION_FAILED".equals(d.code())));
    }

    private static Map<String, Object> baseParams() {
        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("baseWidth", 25);
        extras.put("baseDepth", 21);
        extras.put("tiers", 3);
        extras.put("tierInset", 2);
        extras.put("tierHeight", 5);
        extras.put("platformHeight", 3);
        extras.put("facing", "SOUTH");
        return extras;
    }

    private static Map<String, Object> baseParamsWithForceFail() {
        Map<String, Object> extras = baseParams();
        extras.put("forceFailCirculation", true);
        return extras;
    }
}
