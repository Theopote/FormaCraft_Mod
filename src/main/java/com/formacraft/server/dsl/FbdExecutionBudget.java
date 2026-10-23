package com.formacraft.server.dsl;

import com.formacraft.common.dsl.FbdException;
import com.formacraft.common.dsl.FbdLimits;
import net.minecraft.util.math.BlockPos;
import java.util.HashSet;
import java.util.Set;

/** One fresh meter per execution; covers air and duplicate candidate writes. */
public final class FbdExecutionBudget {
    private final FbdLimits limits;
    private final Set<BlockPos> positions = new HashSet<>();
    private long writes;
    private long samples;
    private String node;
    private String instance;
    private String path;

    public FbdExecutionBudget(FbdLimits limits) { this.limits = limits; }

    public void operation(String node, String instance, String path) { this.node = node; this.instance = instance; this.path = path; }

    public void beforeWrite(BlockPos position) {
        if (Thread.currentThread().isInterrupted() || ++writes > limits.writes()) fail("candidate_writes", limits.writes(), writes);
        if (positions.add(position.toImmutable()) && positions.size() > limits.writes()) fail("unique_positions", limits.writes(), positions.size());
    }

    public void sample() {
        if (Thread.currentThread().isInterrupted() || ++samples > limits.samples()) fail("geometry_samples", limits.samples(), samples);
    }

    private void fail(String layer, long limit, long actual) {
        throw FbdException.budget("geometry", node, path, layer, limit, actual);
    }
}
