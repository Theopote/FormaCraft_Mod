package com.formacraft.server.compiler;

import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.generation.component.util.ComponentFootprintMask;
import com.formacraft.common.generation.component.util.GeneratedSurfaceCapture;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Slot;
import com.formacraft.common.generation.component.util.ResolvedMassPart;
import net.minecraft.util.math.BlockPos;
import java.util.*;

/** Independent core coverage expectation for explicitly resolved flat roofs, in plan coordinates. */
final class FlatRoofCoverageValidator {
    record Roof(String source, Set<BlockPos> core) {
        Roof { core = Collections.unmodifiableSet(new LinkedHashSet<>(core)); }
    }
    record Missing(String source, BlockPos position, int count) {}
    record Attachment(String host, int expectedY, int actualY) {}

    static Optional<Attachment> attachmentMismatch(SemanticComponent roof, List<Component> components,
                                                    Map<String, Slot> slots, Slot fallback) {
        var c = roof.source();
        if (c == null || c.params() == null || c.relativePosition() == null || !"ROOF".equalsIgnoreCase(c.componentType())
                || !"flat".equalsIgnoreCase(String.valueOf(c.params().getOrDefault("roof_type", c.params().get("roofType")))))
            return Optional.empty();
        Object host = c.params().get("host_id");
        if (host == null) return Optional.empty(); // Legacy geometry inference remains in the compiler.
        for (var body : components) {
            if (!"MASS_MAIN".equalsIgnoreCase(body.componentType()) || body.params() == null
                    || !host.equals(body.params().get("component_id"))) continue;
            var hostSlot = slots.get(body.slotId());
            if (hostSlot == null) hostSlot = fallback;
            int hostOffsetY = hostSlot == null || hostSlot.anchor() == null ? 0 : hostSlot.anchor().y();
            int roofOffsetY = roof.slot() == null || roof.slot().anchor() == null ? 0 : roof.slot().anchor().y();
            String partId = String.valueOf(c.params().getOrDefault("host_part_id", host));
            for (var part : ResolvedMassPart.resolve(body)) if (part.partId().equals(partId)) {
                int expected = part.bounds().maxY()-1+hostOffsetY;
                int actual = c.relativePosition().y()+roofOffsetY;
                return expected == actual ? Optional.empty() : Optional.of(new Attachment(partId, expected, actual));
            }
        }
        return Optional.empty(); // Unknown identities require the existing plan identity validation.
    }

    static Optional<Roof> resolve(SemanticComponent semantic, BlockPos slotOffset) {
        var c = semantic.source();
        if (c == null || !"ROOF".equalsIgnoreCase(c.componentType()) || c.dimensions() == null
                || c.relativePosition() == null || c.params() == null) return Optional.empty();
        Object type = c.params().getOrDefault("roof_type", c.params().get("roofType"));
        if (!"flat".equalsIgnoreCase(String.valueOf(type))) return Optional.empty();
        int width = Math.max(1, c.dimensions().width()), depth = Math.max(1, c.dimensions().depth());
        var mask = ComponentFootprintMask.from(semantic, c.params(), width, depth);
        var origin = c.relativePosition();
        var core = new LinkedHashSet<BlockPos>();
        for (int x = 0; x < width; x++) for (int z = 0; z < depth; z++) if (mask.contains(x,z))
            core.add(new BlockPos(origin.x()+x, origin.y(), origin.z()+z).add(slotOffset));
        String id = String.valueOf(c.params().getOrDefault("component_id", "ROOF@" + c.slotId() + ":" + origin));
        return Optional.of(new Roof(id, core));
    }

    static Optional<Missing> check(List<Roof> roofs, List<BlockPatch> patches, Set<BlockPos> stairClearance) {
        if (roofs.isEmpty()) return Optional.empty();
        Set<BlockPos> expected = new HashSet<>();
        for (var roof : roofs) expected.addAll(roof.core());
        Map<BlockPos, BlockPatch> finalPatches = new HashMap<>();
        for (var patch : patches) {
            if (patch == null) continue;
            var pos = new BlockPos(patch.dx(), patch.dy(), patch.dz());
            if (expected.contains(pos)) finalPatches.put(pos, patch);
        }
        for (var roof : roofs) {
            int count = 0; BlockPos first = null;
            for (var pos : roof.core()) if (!stairClearance.contains(pos)
                    && !GeneratedSurfaceCapture.occupied(finalPatches.get(pos))) {
                count++; if (first == null) first = pos;
            }
            if (count > 0) return Optional.of(new Missing(roof.source(), first, count));
        }
        return Optional.empty();
    }
}
