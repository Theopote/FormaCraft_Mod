package com.formacraft.server.compiler;

import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.generation.component.util.ComponentFootprintMask;
import com.formacraft.common.generation.component.util.GeneratedSurfaceCapture;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Slot;
import com.formacraft.common.generation.component.util.ResolvedMassPart;
import com.formacraft.common.generation.component.util.ComponentParamParsers;
import net.minecraft.util.math.BlockPos;
import java.util.*;

/** Independent core coverage expectation for explicitly resolved flat roofs, in plan coordinates. */
final class FlatRoofCoverageValidator {
    record Roof(String source, Set<BlockPos> core, Set<BlockPos> coverage, String host, String part) {
        Roof {
            core = Collections.unmodifiableSet(new LinkedHashSet<>(core));
            coverage = Set.copyOf(coverage);
        }
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
        int overhang = Math.max(0, ComponentParamParsers.intParam(c.params(), "overhang", "overhang_blocks", "eave_overhang"));
        // Flat flying-eave handling matches RoofGenerator's minimum reach.
        if (c.features() != null && c.features().stream().anyMatch(f -> f != null
                && (f.contains("flying_eaves") || f.contains("flying eaves") || f.contains("飞檐")))) overhang = Math.max(2, overhang);
        var coverage = new HashSet<BlockPos>(core);
        for (int x = -overhang; x < width+overhang; x++) for (int z = -overhang; z < depth+overhang; z++)
            if (mask.shouldPlaceRoof(x,z,overhang)) coverage.add(new BlockPos(origin.x()+x,origin.y(),origin.z()+z).add(slotOffset));
        String host = c.params().get("host_id") == null ? null : c.params().get("host_id").toString();
        String part = c.params().get("host_part_id") == null ? host : c.params().get("host_part_id").toString();
        return Optional.of(new Roof(id, core, coverage, host, part));
    }

    /** Bound roof coverage must cover exposed host top cells, not just its own smaller rectangle. */
    static Optional<Missing> checkHost(SemanticComponent body, List<Roof> roofs, BlockPos offset,
                                       List<BlockPatch> patches, Set<BlockPos> clearance) {
        var c = body.source();
        if (c == null || !"MASS_MAIN".equalsIgnoreCase(c.componentType()) || c.params() == null
                || c.params().get("component_id") == null || "plate".equals(c.params().get("extrude_mode"))) return Optional.empty();
        // The current mask does not express per-layer setbacks; don't invent a full-width top.
        if (c.features() != null && c.features().stream().anyMatch(f -> f != null
                && (f.contains("stepped") || f.contains("setback") || f.contains("退台")))) return Optional.empty();
        Object setback = c.params().get("setback_ratio");
        if (setback instanceof Number n && n.doubleValue() > 0) return Optional.empty();
        String host = c.params().get("component_id").toString();
        var bound = roofs.stream().filter(r -> host.equals(r.host())).toList();
        if (bound.isEmpty()) return Optional.empty(); // Pitched and legacy unbound roofs aren't claimed as checked.
        var parts = ResolvedMassPart.resolve(c);
        var masks = new ArrayList<ComponentFootprintMask>();
        for (var part : parts) {
            var params = new HashMap<>(c.params()); params.putAll(part.sourceParams());
            masks.add(ComponentFootprintMask.from(body, params, part.dimensions().width(), part.dimensions().depth()));
        }
        Map<BlockPos, BlockPatch> finalPatches = new HashMap<>();
        for (var patch : patches) if (patch != null) finalPatches.put(new BlockPos(patch.dx(),patch.dy(),patch.dz()),patch);
        for (int i = 0; i < parts.size(); i++) {
            var part = parts.get(i);
            var assigned = bound.stream().filter(r -> part.partId().equals(r.part())).toList();
            if (assigned.isEmpty()) continue; // A child may explicitly request another roof type.
            var roofCells = new HashSet<BlockPos>();
            for (var roof : assigned) roofCells.addAll(roof.coverage());
            int count = 0; BlockPos first = null;
            for (int x = 0; x < part.dimensions().width(); x++) for (int z = 0; z < part.dimensions().depth(); z++) {
                if (!masks.get(i).contains(x,z)) continue;
                int px = part.origin().x()+x, pz = part.origin().z()+z, y = part.bounds().maxY()-1;
                boolean coveredByBody = false;
                for (int j = 0; j < parts.size(); j++) {
                    var other = parts.get(j);
                    if (y+1 >= other.origin().y() && y+1 < other.bounds().maxY()
                            && masks.get(j).contains(px-other.origin().x(),pz-other.origin().z())) { coveredByBody = true; break; }
                }
                var pos = new BlockPos(px,y,pz).add(offset);
                if (coveredByBody || clearance.contains(pos)) continue;
                if (!roofCells.contains(pos) || !GeneratedSurfaceCapture.occupied(finalPatches.get(pos))) {
                    count++; if (first == null) first = pos;
                }
            }
            if (count > 0) return Optional.of(new Missing(part.partId(),first,count));
        }
        return Optional.empty();
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
