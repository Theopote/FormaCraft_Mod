package com.formacraft.server.compiler;

import com.formacraft.common.generation.component.util.HostedPlateOccupancy;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.Vec3i;
import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Explicit exterior circulation must remain clear through its deck railing. */
final class DeckRailingAccessCompiler {
    private DeckRailingAccessCompiler() {}

    static void apply(List<Component> parts) {
        if (parts == null || parts.isEmpty()) return;
        for (int i = 0; i < parts.size(); i++) {
            var rail = parts.get(i);
            if (!isRailing(rail) || rail.dimensions() == null || rail.relativePosition() == null) continue;
            var clear = new HashSet<String>();
            var r = rail.relativePosition();
            var d = rail.dimensions();
            for (var stair : parts) {
                if (stair.params() == null || !Boolean.TRUE.equals(stair.params().get("exterior"))
                        || !Objects.equals(stair.slotId(), rail.slotId())
                        || !Objects.equals(stair.params().get("host_id"), rail.params().get("host_id"))) {
                    continue;
                }
                for (int x = 0; x < d.width(); x++) {
                    for (int z = 0; z < d.depth(); z++) {
                        for (int y = 0; y < d.height(); y++) {
                            if (PatchOccupancyIndex.withinExteriorStairEnvelope(
                                    stair, new BlockPos(r.x() + x, r.y() + y, r.z() + z))) {
                                clear.add(x + "," + y + "," + z);
                            }
                        }
                    }
                }
            }
            // Thin bridge rail carpets block deck headroom — keep a walk corridor through the centre.
            clearBridgeWalkCorridor(clear, rail, parts);
            if (clear.isEmpty()) continue;
            var p = new HashMap<String, Object>(rail.params());
            p.put("compiler_clear_railing_cells", clear);
            parts.set(i, new Component(rail.componentType(), rail.slotId(), r, d, rail.features(), p));
        }
    }

    private static boolean isRailing(Component rail) {
        if (rail == null || rail.params() == null || !"DECOR_DETAIL".equals(rail.componentType())) {
            return false;
        }
        if ("railing".equals(rail.params().get("role")) || Boolean.TRUE.equals(rail.params().get("railing"))) {
            return true;
        }
        Object id = rail.params().get("component_id");
        if (id == null) return false;
        String lower = String.valueOf(id).toLowerCase(Locale.ROOT);
        return lower.contains("rail") || lower.contains("parapet") || lower.contains("护栏");
    }

    private static void clearBridgeWalkCorridor(Set<String> clear, Component rail, List<Component> parts) {
        Object hostId = rail.params().get("host_id");
        if (hostId == null) hostId = rail.params().get("hostId");
        Component deck = null;
        if (hostId != null) {
            for (Component c : parts) {
                if (c == null || c.params() == null) continue;
                if (!hostId.equals(c.params().get("component_id"))) continue;
                if (c.params().get("extrude_mode") != null
                        && "plate".equalsIgnoreCase(String.valueOf(c.params().get("extrude_mode")))) {
                    try {
                        if (HostedPlateOccupancy.isBridgePlate(c.params())) {
                            deck = c;
                            break;
                        }
                    } catch (RuntimeException ignored) {
                        // unsupported plate_role — ignore
                    }
                }
                String id = String.valueOf(c.params().get("component_id")).toLowerCase(Locale.ROOT);
                if (id.contains("bridge") || id.contains("deck") || id.contains("span")) {
                    deck = c;
                    break;
                }
            }
        }
        // Only bridge-hosted rail carpets — never carve ordinary balcony / adobe guards.
        if (deck == null) return;
        Dimensions d = rail.dimensions();
        if (d == null) return;
        int midX = d.width() / 2;
        int midZ = d.depth() / 2;
        for (int y = 0; y < Math.max(1, d.height()); y++) {
            if (d.width() <= d.depth()) {
                // Corridor along depth (long axis).
                for (int z = 0; z < d.depth(); z++) {
                    clear.add(midX + "," + y + "," + z);
                    if (d.width() >= 2) clear.add((midX + (midX == 0 ? 1 : -1)) + "," + y + "," + z);
                }
            } else {
                for (int x = 0; x < d.width(); x++) {
                    clear.add(x + "," + y + "," + midZ);
                    if (d.depth() >= 2) clear.add(x + "," + y + "," + (midZ + (midZ == 0 ? 1 : -1)));
                }
            }
        }
    }
}
