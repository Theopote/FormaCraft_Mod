package com.formacraft.common.generation.component.impl;

import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.generation.component.ComponentGenerator;
import com.formacraft.common.generation.component.util.ComponentFootprintUtil;
import com.formacraft.common.llm.dto.CapabilityGap;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.palette.dynamic.ExplicitMaterialPolicy;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.palette.component.PaletteLibrary;
import com.formacraft.common.semantic.SemanticPart;
import com.formacraft.server.assembly.AssemblyCompileDiagnostics;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * FoundationGenerator（基础生成器）
 *
 * 声明高度必须与输出高度一致（GEOMETRY）。超过资源上限时明确失败，不静默截断。
 * 显式 foundation_block / material 优先于风格调色板，并保留合法 BlockState 属性。
 */
public class FoundationGenerator implements ComponentGenerator {

    /** Resource / world safety bound — not an aesthetic plinth default. */
    public static final int MAX_FOUNDATION_HEIGHT = 64;

    @Override
    public List<BlockPatch> generate(SemanticComponent semantic) {
        List<BlockPatch> out = new ArrayList<>();

        Component c = semantic.source();
        if (c == null || c.dimensions() == null || c.relativePosition() == null) {
            return out;
        }

        Dimensions d = c.dimensions();
        Vec3i rp = ComponentFootprintUtil.resolveMinCornerOrigin(c);
        if (rp == null) {
            return out;
        }

        int width = Math.max(1, d.width());
        int depth = Math.max(1, d.depth());
        boolean stilts = isStilts(c.params());
        int requestedHeight = Math.max(1, d.height());
        if (requestedHeight > MAX_FOUNDATION_HEIGHT) {
            AssemblyCompileDiagnostics.set(new CapabilityGap(
                    "E_FOUNDATION_HEIGHT_LIMIT",
                    "Foundation height " + requestedHeight + " exceeds resource limit "
                            + MAX_FOUNDATION_HEIGHT + "; refusing silent truncation.",
                    "components[]",
                    List.of("Reduce foundation height to ≤ " + MAX_FOUNDATION_HEIGHT,
                            "Or split into staged structural supports.")));
            return List.of();
        }
        int height = requestedHeight;

        String styleProfile = semantic.styleProfile();
        if (styleProfile == null || styleProfile.isBlank()) {
            styleProfile = "MEDIEVAL_CLASSIC";
        }

        String block = resolveFoundationBlock(c, styleProfile, semantic);
        if (block == null) {
            return List.of();
        }

        var footprint = stilts
                ? com.formacraft.common.generation.component.util.ResolvedFootprint.resolve(c, width, depth)
                : null;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                for (int z = 0; z < depth; z++) {
                    if (stilts) {
                        boolean rectangular = footprint == null
                                || com.formacraft.common.generation.component.util.OpenFramePolicy
                                .isRectangularFootprint(footprint, width, depth);
                        boolean post = rectangular
                                ? com.formacraft.common.generation.component.util.OpenFramePolicy
                                .isStiltPost(x, z, width, depth)
                                : com.formacraft.common.generation.component.util.OpenFramePolicy
                                .isPost(footprint, x, z, width, depth, c.params());
                        if (!post) continue;
                    }

                    out.add(new BlockPatch(
                            BlockPatch.PLACE,
                            rp.x() + x,
                            rp.y() + y,
                            rp.z() + z,
                            block
                    ));
                }
            }
        }

        return out;
    }

    private static boolean isStilts(Map<String, Object> params) {
        if (params == null) return false;
        Object ft = params.get("foundation_type");
        if (ft == null) ft = params.get("foundationType");
        if (ft == null) return false;
        return Set.of("stilt", "stilts", "piles", "piling")
                .contains(String.valueOf(ft).toLowerCase(Locale.ROOT));
    }

    /**
     * Component-authored material first, then style palette.
     * Explicit invalid / non-bearing materials fail with a capability gap (HARD_CONSTRAINT).
     */
    private static String resolveFoundationBlock(Component c, String styleProfile, SemanticComponent semantic) {
        Map<String, Object> params = c.params();
        boolean stilts = isStilts(params);
        if (params != null) {
            for (String key : List.of("foundation_block", "material", "block")) {
                Object raw = params.get(key);
                if (raw == null) continue;
                String text = String.valueOf(raw).trim();
                if (text.isEmpty() || "null".equalsIgnoreCase(text)) continue;
                String resolved = ExplicitMaterialPolicy.resolveFoundationMaterial(text);
                if (resolved != null) {
                    return resolved;
                }
                AssemblyCompileDiagnostics.set(new CapabilityGap(
                        "E_FOUNDATION_MATERIAL_INVALID",
                        "Foundation material is not a valid load-bearing block: " + text,
                        "components[].FOUNDATION.params." + key,
                        List.of("Use a solid registered block id (BlockState properties allowed).",
                                "Air, fluids and unknown ids are not accepted for foundations.")));
                return null;
            }
        }
        if (stilts) {
            return "minecraft:oak_log";
        }
        String block = PaletteLibrary.resolveBlock(
                SemanticPart.FOUNDATION, styleProfile, semantic.styleAttributes());
        if (block == null || block.isBlank()) {
            block = PaletteLibrary.resolveBlock(
                    SemanticPart.WALL_BASE, styleProfile, semantic.styleAttributes());
        }
        if (block == null || block.isBlank()) {
            return "minecraft:stone_bricks";
        }
        String resolved = ExplicitMaterialPolicy.resolveFoundationMaterial(block);
        return resolved != null ? resolved : "minecraft:stone_bricks";
    }
}
