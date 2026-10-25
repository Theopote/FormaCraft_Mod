package com.formacraft.server.assembly;

import com.formacraft.common.build.PlannedBlock;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import java.util.*;
import java.util.function.Consumer;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.patch.BlockPatchTargetResolver;

/** Checks explicit stair occupancy/clearance promises against the final assembly operations. */
public final class AssemblyCirculationConstraints {
    private AssemblyCirculationConstraints() {}
    private static final ThreadLocal<Consumer<List<Flight>>> CAPTURE = new ThreadLocal<>();
    public interface Scope extends AutoCloseable { @Override void close(); }
    public static Scope captureTo(Consumer<List<Flight>> sink) {
        Consumer<List<Flight>> previous = CAPTURE.get();
        CAPTURE.set(Objects.requireNonNull(sink));
        return () -> { if (previous == null) CAPTURE.remove(); else CAPTURE.set(previous); };
    }
    public static void publish(List<Flight> flights) {
        Consumer<List<Flight>> sink = CAPTURE.get();
        if (sink != null) sink.accept(List.copyOf(flights));
    }
    public static Flight shift(Flight flight, BlockPos offset) {
        Set<BlockPos> occupied = new LinkedHashSet<>(), clearance = new LinkedHashSet<>();
        for (BlockPos pos : flight.occupied()) occupied.add(pos.add(offset));
        for (BlockPos pos : flight.clearance()) clearance.add(pos.add(offset));
        return new Flight(occupied, clearance);
    }

    /**
     * Plan-level check: patch list uses last-write-wins for final voxels, but each flight keeps
     * its functional contract (treads/supports + standing headroom). Transition over-carve cells
     * that are not standing headroom may be claimed by a later flight without cancelling this one.
     * Dropping a contract requires omitting that flight from {@code flights} (explicit replace/delete).
     */
    public static void validatePatches(List<BlockPatch> patches, List<Flight> flights) {
        if (flights == null || flights.isEmpty()) return;
        Set<BlockPos> relevant = new HashSet<>();
        for (Flight flight : flights) {
            if (flight == null) continue;
            relevant.addAll(flight.occupied());
            relevant.addAll(flight.clearance());
        }
        Map<BlockPos, BlockPatch> finalPatches = new LinkedHashMap<>();
        for (BlockPatch patch : patches) {
            if (patch == null) continue;
            BlockPos pos = new BlockPos(patch.dx(), patch.dy(), patch.dz());
            if (!relevant.contains(pos)) continue;
            finalPatches.put(pos, patch);
        }
        List<PlannedBlock> finalOps = new ArrayList<>();
        for (var entry : finalPatches.entrySet()) {
            BlockPos pos = entry.getKey();
            BlockPatch patch = entry.getValue();
            BlockState state = BlockPatchTargetResolver.resolve(patch);
            if (state == null) throw new Conflict("STAIR_SYSTEM invalid patch at plan " + pos.toShortString());
            finalOps.add(new PlannedBlock(pos, state));
        }
        validateFunctional(finalOps, flights);
    }

    public static final class Conflict extends IllegalArgumentException {
        private Conflict(String message) { super(message); }
    }

    public record Flight(Set<BlockPos> occupied, Set<BlockPos> clearance) {
        public Flight {
            occupied = Collections.unmodifiableSet(new LinkedHashSet<>(occupied));
            clearance = Collections.unmodifiableSet(new LinkedHashSet<>(clearance));
        }

        /** Standing headroom: clearance cells directly above an occupied cell (dy 1..2). */
        public Set<BlockPos> standingHeadroom() {
            Set<BlockPos> headroom = new LinkedHashSet<>();
            for (BlockPos tread : occupied) {
                for (int dy = 1; dy <= 2; dy++) {
                    BlockPos above = tread.up(dy);
                    if (clearance.contains(above)) headroom.add(above);
                }
            }
            return headroom;
        }
    }

    /** Capture the world coordinates actually emitted by one STAIR_SYSTEM operation. */
    public static Flight capture(List<PlannedBlock> out, int start) {
        Set<BlockPos> occupied = new LinkedHashSet<>(), clearance = new LinkedHashSet<>();
        for (int i = start; i < out.size(); i++) {
            PlannedBlock block = out.get(i);
            BlockPos pos = block.getPos().toImmutable();
            // Last write wins within one emission: a later tread must not also remain clearance.
            if (block.getTargetState().isAir()) {
                occupied.remove(pos);
                clearance.add(pos);
            } else {
                clearance.remove(pos);
                occupied.add(pos);
            }
        }
        return new Flight(occupied, clearance);
    }

    /**
     * Strict emission check: every captured clearance cell must remain air.
     * Used inside a single stair assembly where the emission is self-consistent.
     */
    public static void validate(List<PlannedBlock> out, List<Flight> flights) {
        if (flights == null || flights.isEmpty()) return;
        Map<BlockPos, BlockState> finalStates = finalStates(out, flights);
        for (int i = 0; i < flights.size(); i++) {
            Flight flight = flights.get(i);
            if (flight == null) continue;
            for (BlockPos pos : flight.occupied()) {
                BlockState state = finalStates.get(pos);
                if (state == null || state.isAir()) throw conflict(i, pos, "tread/support removed");
            }
            for (BlockPos pos : flight.clearance()) {
                BlockState state = finalStates.get(pos);
                if (state == null || !state.isAir()) throw conflict(i, pos, "clearance blocked");
            }
        }
    }

    /**
     * Final-building functional check: each remaining flight keeps treads/supports and standing
     * headroom. Non-headroom carve cells may be overwritten by later flights.
     */
    public static void validateFunctional(List<PlannedBlock> out, List<Flight> flights) {
        if (flights == null || flights.isEmpty()) return;
        Map<BlockPos, BlockState> finalStates = finalStates(out, flights);
        for (int i = 0; i < flights.size(); i++) {
            Flight flight = flights.get(i);
            if (flight == null) continue;
            for (BlockPos pos : flight.occupied()) {
                BlockState state = finalStates.get(pos);
                if (state == null || state.isAir()) throw conflict(i, pos, "tread/support removed");
            }
            if (flight.occupied().isEmpty()) {
                // Clearance-only contracts (e.g. portal openings) stay fully binding.
                for (BlockPos pos : flight.clearance()) {
                    BlockState state = finalStates.get(pos);
                    if (state == null || !state.isAir()) throw conflict(i, pos, "clearance blocked");
                }
                continue;
            }
            for (BlockPos pos : flight.standingHeadroom()) {
                BlockState state = finalStates.get(pos);
                if (state == null || !state.isAir()) throw conflict(i, pos, "headroom blocked");
            }
        }
    }

    private static Map<BlockPos, BlockState> finalStates(List<PlannedBlock> out, List<Flight> flights) {
        Set<BlockPos> relevant = new HashSet<>();
        for (Flight flight : flights) {
            if (flight == null) continue;
            relevant.addAll(flight.occupied());
            relevant.addAll(flight.clearance());
        }
        Map<BlockPos, BlockState> finalStates = new HashMap<>();
        for (PlannedBlock block : out) if (relevant.contains(block.getPos()))
            finalStates.put(block.getPos(), block.getTargetState());
        return finalStates;
    }

    /**
     * Union of flight cells for diagnostics only. Does not revoke per-flight contracts —
     * use {@link #validate} / {@link #validatePatches} for functional checks.
     */
    public static Flight union(List<Flight> flights) {
        Set<BlockPos> occupied = new LinkedHashSet<>();
        Set<BlockPos> clearance = new LinkedHashSet<>();
        if (flights == null) return new Flight(occupied, clearance);
        for (Flight flight : flights) {
            if (flight == null) continue;
            occupied.addAll(flight.occupied());
            clearance.addAll(flight.clearance());
        }
        clearance.removeAll(occupied);
        return new Flight(occupied, clearance);
    }

    /** @deprecated Use {@link #union}; name historically implied contract cancellation. */
    @Deprecated
    public static Flight collapse(List<Flight> flights) {
        return union(flights);
    }

    private static Conflict conflict(int flight, BlockPos pos, String reason) {
        return new Conflict("STAIR_SYSTEM flight " + (flight + 1) + " " + reason
            + " at assembly/plan coordinate " + pos.toShortString() + "; separate flights/landings or fix operation order");
    }
}
