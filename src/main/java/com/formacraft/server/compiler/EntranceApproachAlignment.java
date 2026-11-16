package com.formacraft.server.compiler;

import com.formacraft.common.generation.component.util.ComponentParamParsers;
import com.formacraft.common.llm.dto.*;
import java.util.*;

/** Resolve entrance paving after the door frame is final; body-owned entry plazas need an unambiguous link. */
final class EntranceApproachAlignment {
    private EntranceApproachAlignment() {}

    static int apply(List<Component> components, LlmPlan plan, Map<String, Slot> slots, Set<String> assemblySlots) {
        int changed = 0;
        for (int i = 0; i < components.size(); i++) {
            Component paving = components.get(i);
            if (paving == null || !"PAVING".equals(ComponentPlanTypes.normalizeType(paving.componentType()))
                    || paving.dimensions() == null || paving.relativePosition() == null) continue;
            String slot = ComponentPlanTypes.slotKey(paving);
            if (assemblySlots != null && assemblySlots.contains(slot)) continue;
            // Generic satellite alignment may infer an owner; that is not an authored entry link.
            String host = ComponentPlanTypes.getParamString(paving.params(), "host_id", "hostId");
            if (host == null) continue;
            if (host.equals(ComponentPlanTypes.getParamString(paving.params(), "compiler_inferred_host_id"))
                    && ComponentPlanTypes.getParamString(paving.params(), "entrance_id", "entranceId") == null) continue;
            Component entrance = resolveEntrance(paving, components, host, slot);
            if (entrance != null) {
                Component aligned = align(paving, entrance, plan, slots);
                if (aligned != paving) { components.set(i, aligned); changed++; }
            }
        }
        return changed;
    }

    private static Component resolveEntrance(Component paving, List<Component> components, String host, String slot) {
        String selected = ComponentPlanTypes.getParamString(paving.params(), "entrance_id", "entranceId");
        String role = ComponentPlanTypes.getParamString(paving.params(), "role");
        boolean entryPlaza = isEntryPlazaRole(role) || paving.features() != null
                && paving.features().stream().anyMatch(EntranceApproachAlignment::isEntryPlazaRole);
        Component match = null;
        for (Component entrance : components) {
            if (entrance == null || !"ENTRANCE".equals(ComponentPlanTypes.normalizeType(entrance.componentType()))
                    || !slot.equals(ComponentPlanTypes.slotKey(entrance))) continue;
            String id = ComponentPlanTypes.getParamString(entrance.params(), "component_id");
            if (id == null || selected != null && !selected.equals(id)) continue;
            boolean directHost = host.equals(id);
            boolean sameBody = host.equals(ComponentPlanTypes.getParamString(entrance.params(), "host_id", "hostId"));
            if (!directHost && !(sameBody && (selected != null || entryPlaza))) continue;
            // Multiple entrances require an authored link, never a nearest-door guess.
            if (match != null) return null;
            match = entrance;
        }
        return match;
    }

    private static boolean isEntryPlazaRole(String value) {
        if (value == null) return false;
        return Set.of("entry_plaza", "entrance_plaza").contains(value.trim().toLowerCase(Locale.ROOT));
    }

    private static Component align(Component paving, Component entrance, LlmPlan plan, Map<String, Slot> slots) {
        Vec3i origin = com.formacraft.common.generation.component.util.ComponentFootprintUtil.resolveLegacyCornerOrigin(entrance);
        Dimensions door = entrance.dimensions(), pad = paving.dimensions();
        if (origin == null || door == null) return paving;
        GlobalConstraints.Facing facing = ComponentSatelliteAligner.resolveSlotFacing(plan, slots, entrance.slotId());
        String authoredFace = ComponentPlanTypes.getParamString(entrance.params(), "facing");
        if (authoredFace != null) {
            try { facing = GlobalConstraints.Facing.valueOf(authoredFace.trim().toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException invalid) { return paving; }
        }
        boolean side = facing == GlobalConstraints.Facing.EAST || facing == GlobalConstraints.Facing.WEST;
        int canopy = Math.min(Math.max(0, (side ? door.width() : door.depth()) - 1),
                Math.max(0, ComponentParamParsers.intParam(entrance.params(), 0, "canopy_depth", "canopyDepth")));
        int x = origin.x() + door.width() / 2 - pad.width() / 2;
        int z = origin.z() + door.depth() / 2 - pad.depth() / 2;
        // Facade names follow the existing entrance convention (SOUTH is the min-Z wall).
        switch (facing) {
            case SOUTH -> z = origin.z() + canopy - pad.depth();
            case NORTH -> z = origin.z() + door.depth() - canopy;
            case EAST -> x = origin.x() + canopy - pad.width();
            case WEST -> x = origin.x() + door.width() - canopy;
        }
        var params = new HashMap<String, Object>();
        if (paving.params() != null) params.putAll(paving.params());
        params.putIfAbsent("entrance_id", ComponentPlanTypes.getParamString(entrance.params(), "component_id"));
        params.put("anchor_mode", "min_corner");
        // RoadGenerator fills height layers; its last layer is immediately below the opening.
        int y = origin.y() - Math.max(1, pad.height());
        return new Component(paving.componentType(), paving.slotId(), new Vec3i(x, y, z), pad, paving.features(), params);
    }
}
