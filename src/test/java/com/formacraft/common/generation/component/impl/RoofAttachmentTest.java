package com.formacraft.common.generation.component.impl;

import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.test.PatchTestSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RoofAttachmentTest {
    @Test
    void rectangularPitchedRoofsCoverBothEndsAndMirrorEvenAndOddSpans() {
        for (String type : List.of("gable", "hip")) for (var dims : List.of(
                new Dimensions(15,7,4),new Dimensions(8,16,4),new Dimensions(16,8,4))) {
            var c = new Component("ROOF",null,new Vec3i(-4,15,8),dims,List.of(),
                    Map.of("roof_type",type,"overhang",1));
            var blocks = PatchTestSnapshot.blocks(new RoofGenerator().generate(new SemanticComponent("ROOF",null,c,"DEFAULT")));
            var heights = new java.util.HashMap<Vec3i,Integer>();
            for (var p : blocks.keySet()) heights.put(new Vec3i(p.x(),0,p.z()),p.y());
            assertEquals((dims.width()+2)*(dims.depth()+2),heights.size(),"Every expanded roof column must have a surface");
            for (int x=0;x<dims.width()+2;x++) for(int z=0;z<dims.depth()+2;z++) {
                int y = heights.get(new Vec3i(-5+x,0,7+z));
                assertEquals(y,heights.get(new Vec3i(-5+dims.width()+1-x,0,7+z)));
                assertEquals(y,heights.get(new Vec3i(-5+x,0,7+dims.depth()+1-z)));
                if (type.equals("hip") && (x==0 || z==0 || x==dims.width()+1 || z==dims.depth()+1))
                    assertEquals(15,y,"All four hip eaves must meet the attachment plane");
            }
            assertEquals(18,heights.values().stream().mapToInt(Integer::intValue).max().orElseThrow());
            if (type.equals("gable")) {
                boolean ridgeAlongDepth = dims.depth() >= dims.width();
                int cx = (dims.width()+2)/2, cz = (dims.depth()+2)/2;
                assertEquals(18,heights.get(new Vec3i(-5+cx,0,7+cz)));
                assertEquals(15,heights.get(new Vec3i(ridgeAlongDepth ? -5 : -5+cx,0,ridgeAlongDepth ? 7+cz : 7)));
            }
        }
    }

    @Test
    void flatPlateStaysAtAttachmentPlaneRegardlessOfPitchBudget() {
        for (int height : List.of(1, 4)) {
            Map<Vec3i, String> roof = generate(height, Map.of("roof_type", "flat"));
            assertEquals(9 * 7, roof.size());
            for (int x = -4; x < 5; x++) {
                for (int z = 8; z < 15; z++) {
                    assertNotNull(roof.get(new Vec3i(x, 15, z)), "missing flat plate cell");
                }
            }
            assertTrue(roof.keySet().stream().allMatch(p -> p.y() == 15));
        }
    }

    @Test
    void pitchedRoofRespectsDimensionsHeightInBlockLayers() {
        Map<Vec3i, String> roof = generate(4, Map.of("roof_type", "gable"));
        assertEquals(15, roof.keySet().stream().mapToInt(Vec3i::y).min().orElseThrow());
        assertEquals(18, roof.keySet().stream().mapToInt(Vec3i::y).max().orElseThrow());
    }

    @Test
    void explicitRoofHeightOverridesDimensionsHeight() {
        Map<Vec3i, String> roof = generate(2, Map.of("roof_type", "gable", "roof_height", 5));
        assertEquals(19, roof.keySet().stream().mapToInt(Vec3i::y).max().orElseThrow());
    }

    private static Map<Vec3i, String> generate(int height, Map<String, Object> params) {
        Component component = new Component("ROOF", null, new Vec3i(-4, 15, 8),
                new Dimensions(9, 7, height), List.of(), params);
        return PatchTestSnapshot.blocks(new RoofGenerator().generate(
                new SemanticComponent("ROOF", null, component, "DEFAULT")));
    }
}
