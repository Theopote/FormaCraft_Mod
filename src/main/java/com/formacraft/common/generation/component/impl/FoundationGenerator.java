package com.formacraft.common.generation.component.impl;

import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.generation.component.ComponentGenerator;
import com.formacraft.common.generation.component.util.ComponentFootprintUtil;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.palette.component.PaletteLibrary;
import com.formacraft.common.semantic.SemanticPart;

import java.util.ArrayList;
import java.util.List;

/**
 * FoundationGenerator（基础生成器）
 * 
 * 生成建筑基础/地基
 * 通常是埋在地下的结构
 */
public class FoundationGenerator implements ComponentGenerator {

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
        boolean stilts = c.params()!=null && java.util.Set.of("stilt","stilts","piles","piling")
                .contains(String.valueOf(c.params().get("foundation_type")).toLowerCase(java.util.Locale.ROOT));
        int height = Math.max(1, stilts ? d.height() : Math.min(5, d.height()));

        String styleProfile = semantic.styleProfile();
        if (styleProfile == null || styleProfile.isBlank()) {
            styleProfile = "MEDIEVAL_CLASSIC";
        }

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                for (int z = 0; z < depth; z++) {
                    // Nine discrete load-bearing posts, with the water/air between them untouched.
                    if (stilts && !((x==0 || x==width/2 || x==width-1)
                            && (z==0 || z==depth/2 || z==depth-1))) continue;
                    SemanticPart part = SemanticPart.FOUNDATION;
                    String block = PaletteLibrary.resolveBlock(part, styleProfile, semantic.styleAttributes());
                    if (stilts) {
                        String material=String.valueOf(c.params().getOrDefault("material", "minecraft:oak_log"));
                        block=com.formacraft.common.palette.dynamic.DynamicPaletteResolver.mapMaterialToBlock(material);
                        if(block==null) block=material.contains(":")?material:"minecraft:"+material;
                    }
                    if (block == null || block.isBlank()) {
                        block = PaletteLibrary.resolveBlock(SemanticPart.WALL_BASE, styleProfile, semantic.styleAttributes());
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
}

