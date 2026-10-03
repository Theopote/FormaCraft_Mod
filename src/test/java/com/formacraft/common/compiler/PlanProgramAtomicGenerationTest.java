package com.formacraft.common.compiler;

import com.formacraft.common.skeleton.*;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class PlanProgramAtomicGenerationTest {
    @BeforeAll static void initialize() { MinecraftRegistryTestBootstrap.initialize(); }
    private final ExecutableSkeletonPlan skeleton = new ExecutableSkeletonPlan(SkeletonType.LINEAR_PATH);
    private final List<BlockPatch> valid = List.of(new BlockPatch("place", 0, 0, 0, "minecraft:stone"));
    @Test void cumulativeBudgetStopsBeforeThirdSkeletonAndRejectsPartialOutput() {
        var calls = new AtomicInteger();
        var failure = assertThrows(PlanProgramCompiler.SkeletonCompilationFailure.class, () ->
            PlanProgramCompiler.mergeSkeletonPatches(List.of(skeleton,skeleton,skeleton), plan -> {
                calls.incrementAndGet(); return valid;
            },1));
        assertEquals(2,calls.get()); assertTrue(failure.getCause().getMessage().contains("cumulative"));
    }
    @Test void exactBudgetCountsDuplicatePositionsAndRemove() {
        var remove = new BlockPatch("remove",0,0,0,null); var calls = new AtomicInteger();
        assertEquals(List.of(valid.getFirst(),remove),PlanProgramCompiler.mergeSkeletonPatches(List.of(skeleton,skeleton),
            plan -> calls.incrementAndGet()==1 ? valid : List.of(remove),2));
        assertThrows(PlanProgramCompiler.SkeletonCompilationFailure.class, () ->
            PlanProgramCompiler.mergeSkeletonPatches(List.of(skeleton),plan->List.of(valid.getFirst(),valid.getFirst()),1));
    }
    @Test void budgetFailureDoesNotReduceRetryBudget() {
        assertThrows(PlanProgramCompiler.SkeletonCompilationFailure.class, () ->
            PlanProgramCompiler.mergeSkeletonPatches(List.of(skeleton),plan->valid,0));
        assertEquals(valid,PlanProgramCompiler.mergeSkeletonPatches(List.of(skeleton),plan->valid,1));
    }
    @Test void secondFailureRejectsPartialOutputAndStopsLaterGeneration() {
        var calls = new AtomicInteger();
        var failure = assertThrows(PlanProgramCompiler.SkeletonCompilationFailure.class, () ->
            PlanProgramCompiler.mergeSkeletonPatches(List.of(skeleton, skeleton, skeleton), plan -> {
                if (calls.incrementAndGet() == 2) throw new IllegalStateException("test failure"); return valid;
            }));
        assertEquals(2, calls.get()); assertTrue(failure.getMessage().contains("skeleton 2"));
        assertEquals("test failure", failure.getCause().getMessage());
    }
    @Test void emptyNullOrIllegalSkeletonOutputIsNotSilentlySkipped() {
        for (var invalid : Arrays.asList(null, List.<BlockPatch>of(), List.of(new BlockPatch("place", 0, 0, 0, "minecraft:no_such_block")))) {
            var calls = new AtomicInteger();
            assertThrows(PlanProgramCompiler.SkeletonCompilationFailure.class, () ->
                PlanProgramCompiler.mergeSkeletonPatches(List.of(skeleton, skeleton), plan -> calls.incrementAndGet() == 1 ? valid : invalid));
        }
    }
    @Test void successfulRetryDoesNotRetainEarlierPatches() {
        assertThrows(PlanProgramCompiler.SkeletonCompilationFailure.class, () ->
            PlanProgramCompiler.mergeSkeletonPatches(List.of(skeleton), plan -> List.of()));
        assertEquals(valid, PlanProgramCompiler.mergeSkeletonPatches(List.of(skeleton), plan -> valid));
    }
    @Test void successfulSkeletonOrderAndRemoveArePreserved() {
        var calls = new AtomicInteger(); var remove = new BlockPatch("remove", 0, 0, 0, null);
        var result = PlanProgramCompiler.mergeSkeletonPatches(List.of(skeleton, skeleton), plan -> calls.incrementAndGet() == 1 ? valid : List.of(remove));
        assertEquals(List.of(valid.getFirst(), remove), result);
    }
}
