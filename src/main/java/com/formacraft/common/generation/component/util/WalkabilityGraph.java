package com.formacraft.common.generation.component.util;

import com.formacraft.common.llm.dto.Vec3i;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Plan-geometry player standing-node graph over {@link PlannedOccupancy}.
 * Standing uses support + feet/head clearance; edges use surface-height steps (eighths).
 * Not a live-world / VoxelShape pathfinding proof.
 */
public final class WalkabilityGraph {
    /** Hard ceiling for adaptive / ofDepth budgets — prevents city-scale verification hangs. */
    public static final int GLOBAL_MAX_DEPTH = 8192;
    public static final int GLOBAL_MAX_VISITS = 65536;

    public enum ReachResult {
        REACHABLE,
        UNREACHABLE,
        SEARCH_LIMIT
    }

    /** Dual budget: path depth vs total visit work. */
    public record SearchBudget(int maxDepth, int maxVisits) {
        public SearchBudget {
            if (maxDepth < 0) {
                maxDepth = 0;
            } else if (maxDepth > GLOBAL_MAX_DEPTH) {
                maxDepth = GLOBAL_MAX_DEPTH;
            }
            if (maxVisits < 0) {
                maxVisits = 0;
            } else if (maxVisits > GLOBAL_MAX_VISITS) {
                maxVisits = GLOBAL_MAX_VISITS;
            }
        }

        public static SearchBudget ofDepth(int maxDepth) {
            long d = Math.max(0, maxDepth);
            d = Math.min(d, GLOBAL_MAX_DEPTH);
            long visits = Math.max(4096L, 4L * Math.max(1L, d));
            visits = Math.min(visits, GLOBAL_MAX_VISITS);
            return new SearchBudget((int) d, (int) visits);
        }
    }

    /** Standing pose: block cell of support + surface height in eighths. */
    public record StandingPose(int x, int blockY, int z, int surfaceEighths) {
        public StandingPose {
            surfaceEighths = Math.max(0, Math.min(8, surfaceEighths));
        }

        public int absoluteEighths() {
            return blockY * 8 + surfaceEighths;
        }

        public Vec3i blockCell() {
            return new Vec3i(x, blockY, z);
        }
    }

    private WalkabilityGraph() {}

    public static StandingPose poseAt(PlannedOccupancy occ, int x, int blockY, int z) {
        if (occ == null) {
            return null;
        }
        var profile = occ.profileAt(x, blockY, z);
        if (!profile.isSupport()) {
            return null;
        }
        return new StandingPose(x, blockY, z, profile.supportTopEighths());
    }

    public static StandingPose poseAt(PlannedOccupancy occ, Vec3i floorCell) {
        if (floorCell == null) {
            return null;
        }
        return poseAt(occ, floorCell.x(), floorCell.y(), floorCell.z());
    }

    public static boolean canStand(PlannedOccupancy occ, int x, int floorY, int z) {
        if (occ == null) {
            return false;
        }
        return occ.isSupport(x, floorY, z)
                && occ.isBodyClear(x, floorY + 1, z)
                && occ.isBodyClear(x, floorY + 2, z);
    }

    public static boolean canStand(PlannedOccupancy occ, Vec3i floorCell) {
        return floorCell != null && canStand(occ, floorCell.x(), floorCell.y(), floorCell.z());
    }

    public static boolean canStand(PlannedOccupancy occ, StandingPose pose) {
        return pose != null && canStand(occ, pose.x(), pose.blockY(), pose.z());
    }

    public static boolean canTraverse(PlannedOccupancy occ, Vec3i fromFloor, Vec3i toFloor) {
        return canTraverse(occ, poseAt(occ, fromFloor), poseAt(occ, toFloor));
    }

    public static boolean canTraverse(PlannedOccupancy occ, StandingPose from, StandingPose to) {
        if (occ == null || from == null || to == null) {
            return false;
        }
        int dx = Math.abs(to.x() - from.x());
        int dz = Math.abs(to.z() - from.z());
        if (dx + dz != 1) {
            return false;
        }
        if (!canStand(occ, from) || !canStand(occ, to)) {
            return false;
        }
        int dAbs = Math.abs(to.absoluteEighths() - from.absoluteEighths());
        if (dAbs > 8) {
            return false;
        }
        int dBlockY = to.blockY() - from.blockY();
        if (dBlockY == 0) {
            return true;
        }
        if (dBlockY == 1) {
            return occ.isBodyClear(from.x(), from.blockY() + 3, from.z());
        }
        if (dBlockY == -1) {
            return occ.isBodyClear(to.x(), from.blockY() + 2, to.z());
        }
        // |dBlockY| > 1 but absolute eighths still ≤8 (e.g. slab quirks) — reject block jump >1
        return false;
    }

    public static SearchBudget adaptiveBudget(Vec3i start, Vec3i goal, ComponentFootprintUtil.Bounds bounds) {
        long manhattan = 0;
        if (start != null && goal != null) {
            manhattan = Math.abs((long) goal.x() - start.x())
                    + Math.abs((long) goal.y() - start.y())
                    + Math.abs((long) goal.z() - start.z());
        }
        long volumeTerm = 0;
        if (bounds != null) {
            long vol = (long) Math.max(1, bounds.width())
                    * Math.max(1, bounds.height())
                    * Math.max(1, bounds.depth());
            volumeTerm = Math.min((long) Integer.MAX_VALUE / 2, vol / 64L);
        }
        long depth = Math.max(512L, 8L * manhattan + volumeTerm);
        depth = Math.min(depth, GLOBAL_MAX_DEPTH);
        long visits = Math.max(4096L, 4L * depth);
        visits = Math.min(visits, GLOBAL_MAX_VISITS);
        return new SearchBudget((int) depth, (int) visits);
    }

    public static boolean canReach(
            PlannedOccupancy occ,
            Vec3i startFloor,
            Vec3i goalFloor,
            int maxSteps,
            ComponentFootprintUtil.Bounds bounds
    ) {
        return search(occ, startFloor, goalFloor, SearchBudget.ofDepth(maxSteps), bounds)
                == ReachResult.REACHABLE;
    }

    public static boolean canReach(PlannedOccupancy occ, Vec3i startFloor, Vec3i goalFloor, int maxSteps) {
        return canReach(occ, startFloor, goalFloor, maxSteps, null);
    }

    /** Immutable search result with occupancy precision (not VoxelShape collision). */
    public record WalkabilityOutcome(ReachResult result, PlannedOccupancy.WalkabilityPrecision precision) {
        public WalkabilityOutcome {
            Objects.requireNonNull(result);
            if (precision == null) {
                precision = PlannedOccupancy.WalkabilityPrecision.FAST;
            }
        }

        public boolean reachable() {
            return result == ReachResult.REACHABLE;
        }
    }

    public static ReachResult search(
            PlannedOccupancy occ,
            Vec3i startFloor,
            Vec3i goalFloor,
            int maxSteps,
            ComponentFootprintUtil.Bounds bounds
    ) {
        return searchDetailed(occ, startFloor, goalFloor, SearchBudget.ofDepth(maxSteps), bounds).result();
    }

    public static ReachResult search(
            PlannedOccupancy occ,
            Vec3i startFloor,
            Vec3i goalFloor,
            SearchBudget budget,
            ComponentFootprintUtil.Bounds bounds
    ) {
        return searchDetailed(occ, startFloor, goalFloor, budget, bounds).result();
    }

    public static ReachResult search(
            PlannedOccupancy occ,
            StandingPose start,
            StandingPose goal,
            SearchBudget budget,
            ComponentFootprintUtil.Bounds bounds
    ) {
        return searchDetailed(occ, start, goal, budget, bounds).result();
    }

    public static WalkabilityOutcome searchDetailed(
            PlannedOccupancy occ,
            Vec3i startFloor,
            Vec3i goalFloor,
            SearchBudget budget,
            ComponentFootprintUtil.Bounds bounds
    ) {
        StandingPose start = poseAt(occ, startFloor);
        StandingPose goal = poseAt(occ, goalFloor);
        return searchDetailed(occ, start, goal, budget, bounds);
    }

    /**
     * BFS over standing poses. {@code maxVisits} is a hard cap: after dequeue, if
     * {@code visits > maxVisits}, search stops immediately (goal must be dequeued to count
     * as {@link ReachResult#REACHABLE}). Enqueue also refuses when {@code seen.size() >= maxVisits}.
     */
    public static WalkabilityOutcome searchDetailed(
            PlannedOccupancy occ,
            StandingPose start,
            StandingPose goal,
            SearchBudget budget,
            ComponentFootprintUtil.Bounds bounds
    ) {
        PlannedOccupancy.WalkabilityPrecision precision = occ == null
                ? PlannedOccupancy.WalkabilityPrecision.FAST
                : occ.precision();
        if (occ == null || start == null || goal == null || budget == null) {
            return new WalkabilityOutcome(ReachResult.UNREACHABLE, precision);
        }
        if (!canStand(occ, start) || !canStand(occ, goal)) {
            return new WalkabilityOutcome(ReachResult.UNREACHABLE, precision);
        }
        if (bounds != null && (!containsFloor(bounds, start.blockCell()) || !containsFloor(bounds, goal.blockCell()))) {
            return new WalkabilityOutcome(ReachResult.UNREACHABLE, precision);
        }

        ArrayDeque<PoseNode> q = new ArrayDeque<>();
        Set<StandingPose> seen = new HashSet<>();
        q.add(new PoseNode(start, 0));
        seen.add(start);

        boolean hasDeferredFrontier = false;
        int visits = 0;
        int[][] horiz = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

        while (!q.isEmpty()) {
            PoseNode cur = q.removeFirst();
            visits++;
            StandingPose pose = cur.pose();
            int dist = cur.dist();

            // Hard visit stop before goal check / expand — queued goal does not count as REACHABLE.
            if (visits > budget.maxVisits()) {
                boolean pending = !q.isEmpty()
                        || hasDeferredFrontier
                        || hasUnseenNeighbor(occ, bounds, seen, pose, horiz);
                return new WalkabilityOutcome(
                        pending ? ReachResult.SEARCH_LIMIT : ReachResult.UNREACHABLE,
                        precision);
            }

            if (pose.equals(goal)) {
                return new WalkabilityOutcome(ReachResult.REACHABLE, precision);
            }

            if (dist >= budget.maxDepth()) {
                if (hasUnseenNeighbor(occ, bounds, seen, pose, horiz)) {
                    hasDeferredFrontier = true;
                }
                continue;
            }

            for (int[] h : horiz) {
                for (int dBy = -1; dBy <= 1; dBy++) {
                    StandingPose next = poseAt(occ, pose.x() + h[0], pose.blockY() + dBy, pose.z() + h[1]);
                    if (next == null) {
                        continue;
                    }
                    if (bounds != null && !containsFloor(bounds, next.blockCell())) {
                        continue;
                    }
                    if (!canTraverse(occ, pose, next)) {
                        continue;
                    }
                    if (seen.contains(next)) {
                        continue;
                    }
                    if (seen.size() >= budget.maxVisits()) {
                        hasDeferredFrontier = true;
                        continue;
                    }
                    seen.add(next);
                    q.addLast(new PoseNode(next, dist + 1));
                }
            }
        }
        return new WalkabilityOutcome(
                hasDeferredFrontier ? ReachResult.SEARCH_LIMIT : ReachResult.UNREACHABLE,
                precision);
    }

    private static boolean hasUnseenNeighbor(
            PlannedOccupancy occ,
            ComponentFootprintUtil.Bounds bounds,
            Set<StandingPose> seen,
            StandingPose pose,
            int[][] horiz
    ) {
        for (int[] h : horiz) {
            for (int dBy = -1; dBy <= 1; dBy++) {
                StandingPose next = poseAt(occ, pose.x() + h[0], pose.blockY() + dBy, pose.z() + h[1]);
                if (next == null || seen.contains(next)) {
                    continue;
                }
                if (bounds != null && !containsFloor(bounds, next.blockCell())) {
                    continue;
                }
                if (canTraverse(occ, pose, next)) {
                    return true;
                }
            }
        }
        return false;
    }

    private record PoseNode(StandingPose pose, int dist) {
        private PoseNode {
            Objects.requireNonNull(pose);
        }
    }

    private static boolean containsFloor(ComponentFootprintUtil.Bounds bounds, Vec3i floor) {
        if (bounds == null || floor == null) {
            return true;
        }
        return floor.x() >= bounds.minX() && floor.x() < bounds.maxX()
                && floor.z() >= bounds.minZ() && floor.z() < bounds.maxZ()
                && floor.y() >= bounds.minY() && floor.y() < bounds.maxY();
    }

    public static ComponentFootprintUtil.Bounds searchBounds(
            ComponentFootprintUtil.Bounds a,
            Vec3i aOff,
            ComponentFootprintUtil.Bounds b,
            Vec3i bOff,
            ComponentFootprintUtil.Bounds c,
            Vec3i cOff,
            int margin
    ) {
        ComponentFootprintUtil.Bounds u = null;
        u = unionShifted(u, a, aOff);
        u = unionShifted(u, b, bOff);
        u = unionShifted(u, c, cOff);
        if (u == null) {
            return null;
        }
        ComponentFootprintUtil.Bounds padded = new ComponentFootprintUtil.Bounds(
                u.minX(), u.minY() - 1, u.minZ(),
                u.maxX(), u.maxY() + 3, u.maxZ());
        return padded.expandHorizontal(Math.max(0, margin));
    }

    private static ComponentFootprintUtil.Bounds unionShifted(
            ComponentFootprintUtil.Bounds acc,
            ComponentFootprintUtil.Bounds local,
            Vec3i off
    ) {
        if (local == null) {
            return acc;
        }
        int ox = off == null ? 0 : off.x();
        int oy = off == null ? 0 : off.y();
        int oz = off == null ? 0 : off.z();
        ComponentFootprintUtil.Bounds shifted = new ComponentFootprintUtil.Bounds(
                local.minX() + ox, local.minY() + oy, local.minZ() + oz,
                local.maxX() + ox, local.maxY() + oy, local.maxZ() + oz);
        return acc == null ? shifted : acc.union(shifted);
    }
}
