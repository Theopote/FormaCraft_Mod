package com.formacraft.common.generation.component.impl;

import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.generation.component.ComponentGenerator;
import com.formacraft.common.generation.component.util.ComponentFootprintUtil;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.palette.dynamic.DynamicPaletteResolver;
import com.formacraft.common.palette.dynamic.ExplicitMaterialPolicy;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.palette.component.PaletteLibrary;
import com.formacraft.common.semantic.SemanticPart;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * FoundationGenerator（基础生成器）
 *
 * 生成建筑基础/地基。声明高度必须与输出高度一致（GEOMETRY）：
 * 可见台基保持对齐器给出的薄厚度；承重基础按对齐后的完整高度填充，
 * 不再对非桩式基础静默截断到 5 格。
 */
public class FoundationGenerator implements ComponentGenerator {

    /** Resource / world safety bound — not an aesthetic plinth default. */
    private static final int MAX_FOUNDATION_HEIGHT = 64;

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
        // Honor the aligned / authored height. Silent Math.min(5) left voids under raised masses.
        int height = Math.max(1, Math.min(MAX_FOUNDATION_HEIGHT, d.height()));

        String styleProfile = semantic.styleProfile();
        if (styleProfile == null || styleProfile.isBlank()) {
            styleProfile = "MEDIEVAL_CLASSIC";
        }

        String block = resolveFoundationBlock(c, styleProfile, semantic);

        var footprint = stilts
                ? com.formacraft.common.generation.component.util.ResolvedFootprint.resolve(c, width, depth)
                : null;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                for (int z = 0; z < depth; z++) {
                    // Discrete load-bearing posts: 3×3 on rectangular pads; angular rim only
                    // when the footprint itself is non-rectangular (octagon / cut-corner).
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
     * Invalid / unmapped ids fall through to palette (HARD_CONSTRAINT legality).
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
                String mapped = DynamicPaletteResolver.mapMaterialToBlock(text);
                if (mapped != null && !mapped.isBlank()) {
                    return mapped;
                }
                String withNs = text.contains(":") ? text : "minecraft:" + text;
                String canonical = ExplicitMaterialPolicy.canonical(withNs);
                if (canonical != null) {
                    int bracket = canonical.indexOf('[');
                    return bracket > 0 ? canonical.substring(0, bracket) : canonical;
                }
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
        return block != null && !block.isBlank() ? block : "minecraft:stone_bricks";
    }
}
