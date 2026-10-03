package com.formacraft.server.generation.component.adaptor;

import com.formacraft.common.build.GeneratedStructure;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.component.transform.BlockStateStringUtil;
import com.formacraft.server.assembly.AssemblyCirculationConstraints;
import net.minecraft.util.math.BlockPos;
import java.util.*;

/** Uses the same origin subtraction for structure patches and circulation requirements. */
public final class StructurePatchBridge {
    private StructurePatchBridge() {}
    public record Result(List<BlockPatch> patches, List<AssemblyCirculationConstraints.Flight> circulation) {}
    public static Result convert(GeneratedStructure structure, BlockPos origin,
                                 List<AssemblyCirculationConstraints.Flight> flights) {
        Set<BlockPos> requiredAir = new HashSet<>();
        for (var flight : flights) requiredAir.addAll(flight.clearance());
        List<BlockPatch> patches = new ArrayList<>();
        for (var block : structure.getBlocks()) {
            if (block.getTargetState().isAir() && !requiredAir.contains(block.getPos())) continue;
            var pos = block.getPos().subtract(origin);
            patches.add(new BlockPatch(block.getTargetState().isAir() ? BlockPatch.REMOVE : BlockPatch.PLACE,
                pos.getX(), pos.getY(), pos.getZ(), BlockStateStringUtil.fromState(block.getTargetState())));
        }
        var relativeFlights = flights.stream().map(flight -> AssemblyCirculationConstraints.shift(flight,
            new BlockPos(-origin.getX(), -origin.getY(), -origin.getZ()))).toList();
        AssemblyCirculationConstraints.validatePatches(patches, relativeFlights);
        return new Result(List.copyOf(patches), relativeFlights);
    }
}
