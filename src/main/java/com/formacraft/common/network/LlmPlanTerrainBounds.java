package com.formacraft.common.network;

import com.formacraft.FormacraftMod;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.Slot;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.model.request.FormaRequest;
import com.formacraft.server.build.PlannedBlock;
import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Footprint / terrain-pad bounds helpers for the LlmPlan preview pipeline.
 */
public final class LlmPlanTerrainBounds {
    private LlmPlanTerrainBounds() {}

    public record Bounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        public int width() {
            return Math.max(1, maxX - minX + 1);
        }

        public int depth() {
            return Math.max(1, maxZ - minZ + 1);
        }

        public int height() {
            return Math.max(1, maxY - minY + 1);
        }

        public Bounds expand(int margin) {
            if (margin <= 0) return this;
            return new Bounds(minX - margin, minY, minZ - margin, maxX + margin, maxY, maxZ + margin);
        }

        public Bounds union(Bounds other) {
            if (other == null) return this;
            return new Bounds(
                    Math.min(minX, other.minX),
                    Math.min(minY, other.minY),
                    Math.min(minZ, other.minZ),
                    Math.max(maxX, other.maxX),
                    Math.max(maxY, other.maxY),
                    Math.max(maxZ, other.maxZ)
            );
        }

        public BlockPos centerAtY(int y) {
            return new BlockPos((minX + maxX) / 2, y, (minZ + maxZ) / 2);
        }
    }

    public static boolean wantsStiltFoundation(FormaRequest req) {
        if (req == null) return false;
        String text = req.getUserMessage();
        if (text == null || text.isBlank()) {
            text = req.getRequestText();
        }
        if (text == null || text.isBlank()) return false;
        String s = text.toLowerCase(Locale.ROOT);
        String[] keywords = new String[] {
                "floating", "cliffside", "cliff", "suspended", "stilt", "stilts",
                "pillar", "pillars", "hover", "airborne", "sky",
                "悬空", "架空", "浮空", "凌空", "吊脚", "高架", "悬崖", "峭壁", "崖边"
        };
        for (String k : keywords) {
            if (s.contains(k)) return true;
        }
        return false;
    }

    public static Bounds computePlannedBlockBounds(List<PlannedBlock> plannedBlocks) {
        if (plannedBlocks == null || plannedBlocks.isEmpty()) return null;
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (PlannedBlock pb : plannedBlocks) {
            if (pb == null || pb.getPos() == null) continue;
            BlockPos p = pb.getPos();
            minX = Math.min(minX, p.getX());
            minY = Math.min(minY, p.getY());
            minZ = Math.min(minZ, p.getZ());
            maxX = Math.max(maxX, p.getX());
            maxY = Math.max(maxY, p.getY());
            maxZ = Math.max(maxZ, p.getZ());
        }
        if (minX == Integer.MAX_VALUE) return null;
        return new Bounds(minX, minY, minZ, maxX, maxY, maxZ);
    }

    public static Bounds computeComponentBounds(LlmPlan plan, BlockPos planOrigin) {
        if (plan == null || plan.components() == null || plan.components().isEmpty() || planOrigin == null) {
            return null;
        }

        Map<String, Slot> slotMap = indexSlots(plan);
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        boolean found = false;

        for (Component c : plan.components()) {
            if (c == null || c.dimensions() == null || c.relativePosition() == null) continue;
            if (!isFootprintComponent(c)) continue;

            Dimensions dims = c.dimensions();
            int width = Math.max(1, dims.width());
            int depth = Math.max(1, dims.depth());
            int height = Math.max(1, dims.height());

            Vec3i slotAnchor = null;
            if (c.slotId() != null) {
                Slot slot = slotMap.get(c.slotId());
                if (slot != null) {
                    slotAnchor = slot.anchor();
                }
            }

            int baseX = c.relativePosition().x() + (slotAnchor != null ? slotAnchor.x() : 0);
            int baseY = c.relativePosition().y() + (slotAnchor != null ? slotAnchor.y() : 0);
            int baseZ = c.relativePosition().z() + (slotAnchor != null ? slotAnchor.z() : 0);

            if (isCenterAnchorComponent(c)) {
                baseX -= width / 2;
                baseZ -= depth / 2;
            }

            int minXWorld = planOrigin.getX() + baseX;
            int minYWorld = planOrigin.getY() + baseY;
            int minZWorld = planOrigin.getZ() + baseZ;
            int maxXWorld = minXWorld + width - 1;
            int maxYWorld = minYWorld + height - 1;
            int maxZWorld = minZWorld + depth - 1;

            minX = Math.min(minX, minXWorld);
            minY = Math.min(minY, minYWorld);
            minZ = Math.min(minZ, minZWorld);
            maxX = Math.max(maxX, maxXWorld);
            maxY = Math.max(maxY, maxYWorld);
            maxZ = Math.max(maxZ, maxZWorld);
            found = true;
        }

        if (!found) return null;
        return new Bounds(minX, minY, minZ, maxX, maxY, maxZ).expand(2);
    }

    public static Bounds chooseTerrainPadBounds(Bounds componentBounds, Bounds blockBounds) {
        if (componentBounds == null) return blockBounds;
        if (blockBounds == null) return componentBounds;

        long compArea = (long) componentBounds.width() * componentBounds.depth();
        long blockArea = (long) blockBounds.width() * blockBounds.depth();
        if (blockArea > compArea * 2L) {
            FormacraftMod.LOGGER.warn(
                    "LlmPlan: planned block bounds {}x{} exceed component footprint {}x{}, clamping terrain pad",
                    blockBounds.width(), blockBounds.depth(), componentBounds.width(), componentBounds.depth());
            return componentBounds;
        }
        return componentBounds.union(blockBounds);
    }

    private static Map<String, Slot> indexSlots(LlmPlan plan) {
        Map<String, Slot> map = new HashMap<>();
        if (plan.layout() == null || plan.layout().slots() == null) return map;
        for (Slot slot : plan.layout().slots()) {
            if (slot != null && slot.slotId() != null) {
                map.put(slot.slotId(), slot);
            }
        }
        return map;
    }

    private static boolean isFootprintComponent(Component component) {
        if (component == null) return false;
        String type = normalizeType(component.componentType());
        if (type.isBlank()) return false;
        if (type.startsWith("MASS_")) return true;
        if ("TOWER".equals(type)) return true;
        if ("PLATFORM".equals(type) || "COURTYARD".equals(type) || "FOUNDATION".equals(type)) return true;
        return type.contains("ROOF");
    }

    private static boolean isCenterAnchorComponent(Component component) {
        if (component == null) return false;
        if (isCornerAnchor(component)) return false;
        String type = normalizeType(component.componentType());
        return type.startsWith("MASS_") || "TOWER".equals(type);
    }

    private static boolean isCornerAnchor(Component component) {
        if (component == null || component.params() == null) return false;
        Object anchorMode = component.params().get("anchor_mode");
        if (anchorMode == null) anchorMode = component.params().get("anchorMode");
        if (anchorMode == null) return false;
        return anchorMode.toString().toLowerCase(Locale.ROOT).contains("corner");
    }

    private static String normalizeType(String type) {
        return type == null ? "" : type.trim().toUpperCase(Locale.ROOT);
    }
}
