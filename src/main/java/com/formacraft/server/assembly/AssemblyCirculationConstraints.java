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
    public static void validatePatches(List<BlockPatch> patches, List<Flight> flights) {
        if (flights.isEmpty()) return;
        Set<BlockPos> relevant = new HashSet<>();
        for (Flight flight : flights) { relevant.addAll(flight.occupied()); relevant.addAll(flight.clearance()); }
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
        validate(finalOps, flights);
    }

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
            + " at assembly/plan coordinate " + pos.toShortString() + "; separate flights/landings or fix operation order");
    }
}
