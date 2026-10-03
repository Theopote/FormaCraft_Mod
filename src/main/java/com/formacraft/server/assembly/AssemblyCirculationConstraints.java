package com.formacraft.server.assembly;

import com.formacraft.common.build.PlannedBlock;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import java.util.*;

/** Checks explicit stair occupancy/clearance promises against the final assembly operations. */
public final class AssemblyCirculationConstraints {
    private AssemblyCirculationConstraints() {}

    public static final class Conflict extends IllegalArgumentException {
        private Conflict(String message) { super(message); }
    }

    public record Flight(Set<BlockPos> occupied, Set<BlockPos> clearance) {
        public Flight {
            occupied = Collections.unmodifiableSet(new LinkedHashSet<>(occupied));
            clearance = Collections.unmodifiableSet(new LinkedHashSet<>(clearance));
        }
    }

    /** Capture the world coordinates actually emitted by one STAIR_SYSTEM operation. */
    public static Flight capture(List<PlannedBlock> out, int start) {
        Set<BlockPos> occupied = new LinkedHashSet<>(), clearance = new LinkedHashSet<>();
        for (int i = start; i < out.size(); i++) {
            PlannedBlock block = out.get(i);
            (block.getTargetState().isAir() ? clearance : occupied).add(block.getPos().toImmutable());
        }
        return new Flight(occupied, clearance);
    }

    public static void validate(List<PlannedBlock> out, List<Flight> flights) {
        if (flights.isEmpty()) return;
        Set<BlockPos> relevant = new HashSet<>();
        for (Flight flight : flights) {
            relevant.addAll(flight.occupied()); relevant.addAll(flight.clearance());
        }
        Map<BlockPos, BlockState> finalStates = new HashMap<>();
        for (PlannedBlock block : out) if (relevant.contains(block.getPos()))
            finalStates.put(block.getPos(), block.getTargetState());
        for (int i = 0; i < flights.size(); i++) {
            Flight flight = flights.get(i);
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

    private static Conflict conflict(int flight, BlockPos pos, String reason) {
        return new Conflict("STAIR_SYSTEM flight " + (flight + 1) + " " + reason
            + " at world " + pos.toShortString() + "; separate flights/landings or fix operation order");
    }
}
