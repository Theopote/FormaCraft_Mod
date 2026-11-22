package com.formacraft.server.dsl;

import com.formacraft.common.dsl.FbdCompiler;
import com.formacraft.server.assembly.AssemblySpec;
import com.formacraft.common.build.PlannedBlock;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.component.transform.BlockStateStringUtil;
import net.minecraft.util.math.BlockPos;
import java.util.*;

/** Consumes only the authority's typed compilation, not client-supplied op metadata. */
public final class FbdAssemblyAdapter {
    private FbdAssemblyAdapter() {}

    /** Resolve sequential writes inside ONE program before inter-component validation. */
    public static List<BlockPatch> finalPatches(List<PlannedBlock> blocks) {
        var finalStates = new LinkedHashMap<BlockPos, PlannedBlock>();
        for (var block : blocks) {
            if (block != null && block.getPos() != null && block.getTargetState() != null)
                finalStates.put(block.getPos().toImmutable(), block);
        }
        var patches = new ArrayList<BlockPatch>(finalStates.size());
        for (var block : finalStates.values()) {
            var pos = block.getPos();
            patches.add(new BlockPatch(block.getTargetState().isAir() ? BlockPatch.REMOVE : BlockPatch.PLACE,
                    pos.getX(), pos.getY(), pos.getZ(), BlockStateStringUtil.fromState(block.getTargetState()), block.getSource()));
        }
        return patches;
    }

    public static AssemblySpec spec(FbdCompiler.Compilation compilation) {
        return spec(compilation, null);
    }

    public static AssemblySpec spec(FbdCompiler.Compilation compilation, FbdPlanLowering.SourceContext source) {
        var ops = new ArrayList<Map<String, Object>>();
        for (var command : compilation.commands()) {
            var op = new LinkedHashMap<String, Object>(command.op());
            expandScalarEndpoints(op);
            if (source != null) {
                op.put("_fbd_component", source.componentPath());
                op.put("_fbd_slot", source.slotId());
            }
            op.put("_fbd_node", command.node());
            op.put("_fbd_instance", command.instance());
            op.put("_fbd_path", command.path());
            ops.add(op);
        }
        return AssemblySpec.of(null, null, ops);
    }

    /**
     * FBD emit stays scalar-only; point-taking MetaAssembly ops still expect {@code from}/{@code to} maps.
     * Convert {@code fromX/Y/Z}+{@code toX/Y/Z} without inventing geometry.
     */
    static void expandScalarEndpoints(Map<String, Object> op) {
        if (op == null) return;
        if (!op.containsKey("from") && op.containsKey("fromX")) {
            op.put("from", Map.of("x", op.remove("fromX"), "y", op.remove("fromY"), "z", op.remove("fromZ")));
        }
        if (!op.containsKey("to") && op.containsKey("toX")) {
            op.put("to", Map.of("x", op.remove("toX"), "y", op.remove("toY"), "z", op.remove("toZ")));
        }
        if (op.containsKey("height") && !op.containsKey("h")) op.put("h", op.get("height"));
    }
}
