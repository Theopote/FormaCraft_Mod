package com.formacraft.server.cluster.layout;

import com.formacraft.server.cluster.TerrainFields;
import com.formacraft.server.build.BuildConstraintContext;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * CandidateGenerator (v1):
 * Generates a sorted candidate list for a unit using TerrainFields metrics.
 */
public final class CandidateGenerator {
    private CandidateGenerator() {}

    public static List<Candidate> generate(BuildingUnit unit,
                                           BuildArea area,
                                           TerrainFields fields,
                                           ServerWorld world,
                                           BlockPos clusterOrigin,
                                           int samples,
                                           int maxRange,
                                           int maxBackfillCost) {
        int n = Math.max(50, samples);
        int rangeLimit = Math.max(4, maxRange);
        int costLimit = Math.max(0, maxBackfillCost);

        Random rng = new Random(seed(unit, clusterOrigin));

        // If server has an active outline constraint, sample points directly within it.
        BuildArea area2 = area;
        try {
            var c = BuildConstraintContext.current();
            if (c != null && c.outline != null) {
                area2 = new OutlineBuildArea(c.outline, clusterOrigin, area.halfX, area.halfZ);
            }
        } catch (Throwable ignored) {}

        List<Candidate> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            BlockPos rel = area2.randomRelOrigin(rng);

            for (int rot : new int[]{0, 90, 180, 270}) {
                int w = (rot % 180 == 0) ? unit.width : unit.depth;
                int d = (rot % 180 == 0) ? unit.depth : unit.width;
                if (!area2.canFitRect(rel, w, d)) continue;

                int x0 = clusterOrigin.getX() + rel.getX();
                int z0 = clusterOrigin.getZ() + rel.getZ();
                TerrainFields.FootprintMetrics m = fields.rectMetricsFromMinCorner(world, x0, z0, w, d);

                if (m.buildableBad() > 0) continue;
                if (m.waterHits() > 0) continue;
                if (m.range() > rangeLimit) continue;
                if (costLimit > 0 && m.flattenCost() > costLimit) continue;

                double slopeScore = 1.0 / (1.0 + (m.slopeAvg() * 4.0));
                double costScore = 1.0 / (1.0 + (m.flattenCost() / 20.0));
                double centerBias = 1.0 - area2.normalizedDistanceToCenter(rel);
                double importance = Math.max(0.0, unit.importance / 10.0);
                double score = 0.40 * costScore + 0.25 * slopeScore + 0.25 * centerBias + 0.10 * importance;

                out.add(new Candidate(unit, rel, rot, score, m.flattenCost(), m.slopeAvg(), m.range()));
            }
        }

        out.sort(Comparator.comparingDouble((Candidate c) -> c.score).reversed());
        return out;
    }

    private static long seed(BuildingUnit unit, BlockPos origin) {
        long s = 0;
        if (origin != null) {
            s ^= origin.getX() * 341873128712L;
            s ^= origin.getZ() * 132897987541L;
            s ^= origin.getY() * 17L;
        }
        if (unit != null && unit.id != null) s ^= unit.id.hashCode();
        return s ^ (s >>> 33);
    }
}


