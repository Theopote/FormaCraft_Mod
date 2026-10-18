package com.formacraft.server.generation.typology.builder;

import com.formacraft.common.llm.dto.Vec3i;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Immutable circulation anchors produced by the actual palace Builder —
 * not inferred from request params like {@code tiers}/{@code tierHeight}.
 * <p>
 * Shared by generate-time occupancy gates and merge-time PreviewPlan validation.
 */
public final class PalaceCirculationContract {

    /**
     * Inclusive origin-relative AABB for a functional space (floor interior / wing interior).
     * Standing goals must resolve inside this region — not on nearby roofs or terraces.
     */
    public record GoalRegion(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        public GoalRegion {
            if (maxX < minX || maxY < minY || maxZ < minZ) {
                throw new IllegalArgumentException("empty GoalRegion");
            }
        }

        public boolean contains(int x, int y, int z) {
            return x >= minX && x <= maxX
                    && y >= minY && y <= maxY
                    && z >= minZ && z <= maxZ;
        }

        public boolean contains(Vec3i p) {
            return p != null && contains(p.x(), p.y(), p.z());
        }

        public boolean containsXZ(int x, int z) {
            return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
        }
    }

    /** Origin-relative standable goal on an actually generated floor interior. */
    public record FloorGoal(int tierIndex, int floorY, Vec3i preferredAnchor, GoalRegion interior) {
        public FloorGoal {
            preferredAnchor = Objects.requireNonNull(preferredAnchor, "preferredAnchor");
            interior = Objects.requireNonNull(interior, "interior");
        }
    }

    /**
     * Origin-relative wing sample (Potala side halls) with interior region and keep-link mouth.
     */
    public record WingGoal(int wingIndex, Vec3i relativeAnchor, GoalRegion interior, Vec3i linkMouth) {
        public WingGoal {
            relativeAnchor = Objects.requireNonNull(relativeAnchor, "relativeAnchor");
            interior = Objects.requireNonNull(interior, "interior");
            linkMouth = Objects.requireNonNull(linkMouth, "linkMouth");
        }
    }

    /**
     * One inter-tier flight as emitted: lower start, upper end, ordered tread cells + facings.
     * Coordinates are origin-relative (after entrance rotation into world frame of patches).
     */
    public record FlightAnchor(
            int fromTier,
            int toTier,
            Vec3i lowerStart,
            Vec3i upperEnd,
            int expectedTreads,
            List<Vec3i> treadCells,
            List<Direction> treadFacings
    ) {
        public FlightAnchor {
            lowerStart = Objects.requireNonNull(lowerStart, "lowerStart");
            upperEnd = Objects.requireNonNull(upperEnd, "upperEnd");
            treadCells = treadCells == null ? List.of() : List.copyOf(treadCells);
            treadFacings = treadFacings == null ? List.of() : List.copyOf(treadFacings);
        }
    }

    private final Direction entranceFacing;
    private final Vec3i lowerDoorRel;
    private final Vec3i approachExterior;
    private final List<FloorGoal> floors;
    private final List<WingGoal> wings;
    private final List<FlightAnchor> flights;
    private final int expectedDoorLeaves;
    private final int expectedStairTreads;
    private final boolean potala;
    private final int actualTier;

    public PalaceCirculationContract(
            Direction entranceFacing,
            Vec3i lowerDoorRel,
            Vec3i approachExterior,
            List<FloorGoal> floors,
            List<WingGoal> wings,
            List<FlightAnchor> flights,
            int expectedDoorLeaves,
            int expectedStairTreads,
            boolean potala,
            int actualTier
    ) {
        this.entranceFacing = entranceFacing == null ? Direction.SOUTH : entranceFacing;
        this.lowerDoorRel = Objects.requireNonNull(lowerDoorRel, "lowerDoorRel");
        this.approachExterior = Objects.requireNonNull(approachExterior, "approachExterior");
        this.floors = floors == null ? List.of() : List.copyOf(floors);
        this.wings = wings == null ? List.of() : List.copyOf(wings);
        this.flights = flights == null ? List.of() : List.copyOf(flights);
        this.expectedDoorLeaves = Math.max(0, expectedDoorLeaves);
        this.expectedStairTreads = Math.max(0, expectedStairTreads);
        this.potala = potala;
        this.actualTier = Math.max(0, actualTier);
    }

    public Direction entranceFacing() { return entranceFacing; }
    public Vec3i lowerDoorRel() { return lowerDoorRel; }
    public Vec3i approachExterior() { return approachExterior; }
    public List<FloorGoal> floors() { return floors; }
    public List<WingGoal> wings() { return wings; }
    public List<FlightAnchor> flights() { return flights; }
    public int expectedDoorLeaves() { return expectedDoorLeaves; }
    public int expectedStairTreads() { return expectedStairTreads; }
    public boolean potala() { return potala; }
    public int actualTier() { return actualTier; }

    /** Empty sentinel — merge validation falls back to limited heuristics. */
    public static PalaceCirculationContract empty() {
        return new PalaceCirculationContract(
                Direction.SOUTH,
                new Vec3i(0, 0, 0),
                new Vec3i(0, 0, 1),
                List.of(),
                List.of(),
                List.of(),
                0,
                0,
                false,
                0);
    }

    public boolean hasAnchors() {
        return !floors.isEmpty() && lowerDoorRel != null;
    }

    /** Copy with same anchors (for re-frozen plans). */
    public PalaceCirculationContract copy() {
        return new PalaceCirculationContract(
                entranceFacing,
                lowerDoorRel,
                approachExterior,
                new ArrayList<>(floors),
                new ArrayList<>(wings),
                new ArrayList<>(flights),
                expectedDoorLeaves,
                expectedStairTreads,
                potala,
                actualTier);
    }
}
