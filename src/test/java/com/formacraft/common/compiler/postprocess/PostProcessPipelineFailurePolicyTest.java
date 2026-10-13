package com.formacraft.common.compiler.postprocess;

import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.LlmPlanTestFixtures;
import com.formacraft.common.patch.BlockPatch;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PostProcessPipelineFailurePolicyTest {
    @Test
    void softFailureKeepsPreviousAndContinues() {
        var pipeline = new PostProcessPipeline();
        pipeline.add(new SoftFailProcessor());
        pipeline.add(new MarkProcessor("after"));
        var input = List.of(new BlockPatch(BlockPatch.PLACE, 0, 0, 0, "minecraft:stone"));
        var outcome = pipeline.processWithOutcome(input, context());
        assertFalse(outcome.hardFailure());
        assertEquals(1, outcome.patches().size());
        assertEquals("after", outcome.patches().getFirst().targetBlock());
    }

    @Test
    void hardFailureAbortsWithFlag() {
        var pipeline = new PostProcessPipeline();
        pipeline.add(new HardFailProcessor());
        pipeline.add(new MarkProcessor("should-not-run"));
        var input = List.of(new BlockPatch(BlockPatch.PLACE, 0, 0, 0, "minecraft:stone"));
        var outcome = pipeline.processWithOutcome(input, context());
        assertTrue(outcome.hardFailure());
        assertEquals("HardFailProcessor", outcome.failedProcessor());
        assertEquals("minecraft:stone", outcome.patches().getFirst().targetBlock());
    }

    private static PostProcessContext context() {
        LlmPlan plan = LlmPlanTestFixtures.builder().mode(LlmPlan.Mode.build).styleProfile("DEFAULT").build();
        return PostProcessContext.create(plan, null);
    }

    private static final class SoftFailProcessor implements PostProcessor {
        @Override
        public FailurePolicy failurePolicy() {
            return FailurePolicy.SOFT;
        }

        @Override
        public List<BlockPatch> process(List<BlockPatch> patches, PostProcessContext context) {
            throw new RuntimeException("soft boom");
        }
    }

    private static final class HardFailProcessor implements PostProcessor {
        @Override
        public FailurePolicy failurePolicy() {
            return FailurePolicy.HARD;
        }

        @Override
        public List<BlockPatch> process(List<BlockPatch> patches, PostProcessContext context) {
            throw new RuntimeException("hard boom");
        }
    }

    private record MarkProcessor(String mark) implements PostProcessor {

        @Override
            public List<BlockPatch> process(List<BlockPatch> patches, PostProcessContext context) {
                return List.of(new BlockPatch(BlockPatch.PLACE, 0, 0, 0, mark));
            }
        }
}
