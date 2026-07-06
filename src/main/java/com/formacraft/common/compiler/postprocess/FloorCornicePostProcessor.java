package com.formacraft.common.compiler.postprocess;

import com.formacraft.common.patch.BlockPatch;

import java.util.List;

/**
 * @deprecated Use {@link DetailRulePostProcessor}; kept for tests and direct references.
 */
@Deprecated
public class FloorCornicePostProcessor implements PostProcessor {

    private final DetailRulePostProcessor delegate = new DetailRulePostProcessor();

    @Override
    public List<BlockPatch> process(List<BlockPatch> patches, PostProcessContext context) {
        return delegate.process(patches, context);
    }
}
