package com.formacraft.common.compiler.postprocess;

import com.formacraft.common.generation.component.util.ComponentFloorCorniceDecorator;
import com.formacraft.common.generation.component.util.ComponentWindowOrderDecorator;
import com.formacraft.common.llm.dto.GlobalConstraints;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.palette.component.Palette;
import com.formacraft.common.palette.component.PaletteLibrary;
import com.formacraft.common.semantic.SemanticPart;
import com.formacraft.FormacraftMod;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 在 perimeter 玻璃窗洞周围装配 Window Order（sill / lintel / sides / pediment）。
 */
public class WindowOrderPostProcessor implements PostProcessor {

    private static final int MAX_SURROUND_PATCHES = 2500;

    @Override
    public List<BlockPatch> process(List<BlockPatch> patches, PostProcessContext context) {
        if (patches == null || patches.isEmpty() || context == null || context.plan() == null) {
            return patches;
        }
        LlmPlan plan = context.plan();
        Map<String, Object> facadeParams = readFacadeWindowParams(plan);
        if (!ComponentWindowOrderDecorator.shouldApply(plan, facadeParams, null)) {
            return patches;
        }
        ComponentWindowOrderDecorator.OrderLevel level =
                ComponentWindowOrderDecorator.resolveLevel(plan, facadeParams, null);
        if (level == ComponentWindowOrderDecorator.OrderLevel.OFF) {
            return patches;
        }

        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;

        Set<Long> glassKeys = new HashSet<>();
        for (BlockPatch patch : patches) {
            if (patch == null || BlockPatch.REMOVE.equals(patch.action())) {
                continue;
            }
            if (!ComponentWindowOrderDecorator.isGlassBlock(patch.targetBlock())) {
                continue;
            }
            minX = Math.min(minX, patch.dx());
            minY = Math.min(minY, patch.dy());
            minZ = Math.min(minZ, patch.dz());
            maxX = Math.max(maxX, patch.dx());
            maxY = Math.max(maxY, patch.dy());
            maxZ = Math.max(maxZ, patch.dz());
            glassKeys.add(pack(patch.dx(), patch.dy(), patch.dz()));
        }

        if (glassKeys.isEmpty() || minX == Integer.MAX_VALUE) {
            return patches;
        }

        Set<long[]> perimeterGlass = new HashSet<>();
        for (Long key : glassKeys) {
            int x = (int) (key >> 42);
            int y = (int) ((key >> 21) & 0x1fffffL);
            int z = (int) (key & 0x1fffffL);
            if (x == minX || x == maxX || z == minZ || z == maxZ) {
                perimeterGlass.add(ComponentWindowOrderDecorator.packCell(x, y, z));
            }
        }
        if (perimeterGlass.isEmpty()) {
            return patches;
        }

        Direction primary = Direction.SOUTH;
        if (plan.globalConstraints() != null && plan.globalConstraints().facing() != null) {
            primary = toDirection(plan.globalConstraints().facing());
        }

        int centerX = (minX + maxX) / 2;
        int centerZ = (minZ + maxZ) / 2;
        List<ComponentWindowOrderDecorator.FacadeOpening> openings = ComponentWindowOrderDecorator.clusterFacadeOpenings(
                perimeterGlass, minX, maxX, minY, maxY, minZ, maxZ, centerX, centerZ, primary);

        String styleProfile = plan.styleProfile() != null ? plan.styleProfile() : "MEDIEVAL_CLASSIC";
        Palette palette = PaletteLibrary.forStyle(styleProfile);
        String trim = palette.pick(SemanticPart.WALL_ACCENT);
        if (trim == null || trim.isBlank()) {
            trim = palette.pick(SemanticPart.DECOR);
        }
        if (trim == null || trim.isBlank()) {
            trim = "minecraft:stone_bricks";
        }
        String stair = ComponentFloorCorniceDecorator.inferStairsBlock(trim);
        String slab = ComponentWindowOrderDecorator.inferSlabBlock(trim);

        List<BlockPatch> surrounds = new ArrayList<>();
        for (ComponentWindowOrderDecorator.FacadeOpening opening : openings) {
            ComponentWindowOrderDecorator.emitWindowOrder(surrounds, opening, level, trim, stair, slab);
            if (surrounds.size() > MAX_SURROUND_PATCHES) {
                break;
            }
        }

        if (surrounds.isEmpty()) {
            return patches;
        }

        List<BlockPatch> merged = new ArrayList<>(patches.size() + surrounds.size());
        merged.addAll(patches);
        merged.addAll(surrounds);
        FormacraftMod.LOGGER.debug("WindowOrderPostProcessor: added {} window surround patches", surrounds.size());
        return merged;
    }

    private static Direction toDirection(GlobalConstraints.Facing facing) {
        return switch (facing) {
            case NORTH -> Direction.NORTH;
            case EAST -> Direction.EAST;
            case WEST -> Direction.WEST;
            default -> Direction.SOUTH;
        };
    }

    private static long pack(int x, int y, int z) {
        return (((long) x) << 42) ^ (((long) y) << 21) ^ (z & 0x1fffffL);
    }

    private static Map<String, Object> readFacadeWindowParams(LlmPlan plan) {
        if (plan == null || plan.components() == null) {
            return null;
        }
        for (com.formacraft.common.llm.dto.Component c : plan.components()) {
            if (c == null || c.params() == null) {
                continue;
            }
            String type = c.componentType() != null ? c.componentType().toUpperCase(java.util.Locale.ROOT) : "";
            if ("FACADE_WINDOWS".equals(type)) {
                return c.params();
            }
        }
        return null;
    }
}
