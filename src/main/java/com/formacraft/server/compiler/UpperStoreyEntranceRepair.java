package com.formacraft.server.compiler;

import com.formacraft.FormacraftMod;
import com.formacraft.common.generation.component.util.ComponentFootprintUtil;
import com.formacraft.common.generation.component.util.ComponentParamParsers;
import com.formacraft.common.generation.component.util.MassComponentKinds;
import com.formacraft.common.generation.component.util.ResolvedFloorLayout;
import com.formacraft.common.llm.dto.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Upper-storey wall doors are only legal when they open onto a balcony / terrace / deck.
 * Ground-level entrances stay; floating second-floor street doors are dropped or tagged.
 */
final class UpperStoreyEntranceRepair {
    private UpperStoreyEntranceRepair() {}

    static void apply(List<Component> parts) {
        if (parts == null || parts.isEmpty()) return;
        List<Component> decks = new ArrayList<>();
        for (Component c : parts) {
            if (c == null || c.componentType() == null) continue;
            String t = c.componentType().trim().toUpperCase(Locale.ROOT);
            if ("BALCONY".equals(t) || "TERRACE".equals(t) || "DECK".equals(t)
                    || "PLATFORM".equals(t) || "GALLERY".equals(t)) {
                decks.add(c);
            }
        }
        List<Component> keep = new ArrayList<>(parts.size());
        boolean changed = false;
        for (Component door : parts) {
            if (door == null || !"ENTRANCE".equalsIgnoreCase(door.componentType())) {
                keep.add(door);
                continue;
            }
            if (ComponentSatelliteAligner.isUpperPlatformDoor(door)) {
                keep.add(door);
                continue;
            }
            Component host = findHost(door, parts);
            if (host == null || door.relativePosition() == null || host.dimensions() == null) {
                keep.add(door);
                continue;
            }
            int groundY = hostFloorY(host);
            int secondStoreyY = secondStoreyY(host, groundY);
            int doorY = door.relativePosition().y();
            // Treat anything at/above the second storey band as an upper door.
            if (doorY < secondStoreyY) {
                keep.add(door);
                continue;
            }
            Component deck = touchingDeck(door, host, decks);
            if (deck != null) {
                Map<String, Object> params = new HashMap<>();
                if (door.params() != null) params.putAll(door.params());
                params.put("platform_door", true);
                params.put("canopy_depth", 0);
                if (deck.params() != null && deck.params().get("component_id") != null) {
                    params.put("platform_connection", String.valueOf(deck.params().get("component_id")));
                }
                keep.add(new Component(
                        door.componentType(), door.slotId(), door.relativePosition(),
                        door.dimensions(), door.features(), params));
                FormacraftMod.LOGGER.info(
                        "UpperStoreyEntranceRepair: keep upper door as platform opening onto {}",
                        deck.componentType());
                changed = true;
                continue;
            }
            FormacraftMod.LOGGER.info(
                    "UpperStoreyEntranceRepair: drop upper-storey ENTRANCE without balcony/terrace (y={} host={})",
                    doorY,
                    host.params() == null ? host.componentType() : host.params().get("component_id"));
            changed = true;
        }
        if (changed) {
            parts.clear();
            parts.addAll(keep);
        }
    }

    private static Component findHost(Component door, List<Component> parts) {
        if (door.params() == null) return null;
        Object hostId = door.params().get("host_id");
        if (hostId == null) hostId = door.params().get("hostId");
        for (Component c : parts) {
            if (!MassComponentKinds.isCirculationMass(c) || c.params() == null) continue;
            if (hostId != null && Objects.equals(String.valueOf(hostId), String.valueOf(c.params().get("component_id")))
                    && Objects.equals(door.slotId(), c.slotId())) {
                return c;
            }
        }
        // Same-slot mass fallback when host_id is missing.
        for (Component c : parts) {
            if (!MassComponentKinds.isCirculationMass(c)) continue;
            if (Objects.equals(door.slotId(), c.slotId())) return c;
        }
        return null;
    }

    private static int hostFloorY(Component host) {
        var bounds = ComponentFootprintUtil.bounds(host);
        if (bounds != null) return bounds.minY();
        return host.relativePosition() == null ? 0 : host.relativePosition().y();
    }

    private static int secondStoreyY(Component host, int groundY) {
        int envelope = host.dimensions() == null ? 1 : Math.max(1, host.dimensions().height());
        ResolvedFloorLayout layout = ResolvedFloorLayout.from(host, envelope);
        List<Integer> ys = layout.floorYs();
        if (ys.size() >= 2) {
            return groundY + ys.get(1);
        }
        int fh = Math.max(3, ComponentParamParsers.intParam(host.params(), 4, "floor_height", "floorHeight"));
        int ground = ComponentParamParsers.intParam(host.params(), 0, "ground_clear_height", "groundClearHeight");
        return groundY + (ground > 0 ? ground : fh);
    }

    private static Component touchingDeck(Component door, Component host, List<Component> decks) {
        if (decks.isEmpty() || door.dimensions() == null || door.relativePosition() == null) return null;
        var doorBounds = ComponentFootprintUtil.bounds(door);
        if (doorBounds == null) return null;
        String face = door.params() == null ? "SOUTH"
                : String.valueOf(door.params().getOrDefault("facing", "SOUTH")).toUpperCase(Locale.ROOT);
        // Expand one block outward so a flush balcony still counts as the door's landing.
        int minX = doorBounds.minX();
        int maxX = doorBounds.maxX();
        int minZ = doorBounds.minZ();
        int maxZ = doorBounds.maxZ();
        int minY = doorBounds.minY() - 1;
        int maxY = doorBounds.maxY() + 1;
        switch (face) {
            case "NORTH" -> minZ -= 2;
            case "SOUTH" -> maxZ += 2;
            case "WEST" -> minX -= 2;
            case "EAST" -> maxX += 2;
            default -> maxZ += 2;
        }
        for (Component deck : decks) {
            if (!Objects.equals(door.slotId(), deck.slotId())
                    && deck.params() != null && door.params() != null
                    && door.params().get("host_id") != null
                    && !Objects.equals(String.valueOf(door.params().get("host_id")),
                    String.valueOf(deck.params().getOrDefault("host_id", "")))) {
                // Different hosts — still allow if footprints touch (shared courtyard decks).
            }
            var db = ComponentFootprintUtil.bounds(deck);
            if (db == null) continue;
            boolean overlapXZ = minX < db.maxX() && maxX > db.minX()
                    && minZ < db.maxZ() && maxZ > db.minZ();
            boolean nearY = db.minY() <= maxY && db.maxY() >= minY;
            if (overlapXZ && nearY) return deck;
        }
        return null;
    }
}
