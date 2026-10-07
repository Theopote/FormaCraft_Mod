package com.formacraft.common.style;

import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.generation.component.impl.RoofGenerator;
import com.formacraft.common.llm.dto.*;
import com.formacraft.test.PatchTestSnapshot;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class StyleMorphologyTest {
    private Component resolve(String style, String type, Dimensions dims, Map<String,Object> params) {
        return StyleIntentResolver.apply(LlmPlanTestFixtures.builder().styleProfile(style).build(),
                new Component(type,null,new Vec3i(0,10,0),dims,List.of(),params));
    }
    @Test void roofDefaultsProduceDistinctAttachedBlockSilhouettes() {
        var dims = new Dimensions(9,7,4);
        var modern = resolve("现代风格","ROOF",dims,Map.of());
        var hui = resolve("徽派","ROOF",dims,Map.of("horse_head_walls",false));
        var flat = PatchTestSnapshot.blocks(new RoofGenerator().generate(new SemanticComponent("ROOF",null,modern,"Modern_International")));
        var pitched = PatchTestSnapshot.blocks(new RoofGenerator().generate(new SemanticComponent("ROOF",null,hui,"Chinese_Vernacular_Huizhou")));
        assertEquals(63,flat.size());
        assertTrue(flat.keySet().stream().allMatch(p->p.y()==10));
        assertEquals(10,pitched.keySet().stream().mapToInt(Vec3i::y).min().orElseThrow());
        assertEquals(13,pitched.keySet().stream().mapToInt(Vec3i::y).max().orElseThrow());
        assertTrue(pitched.keySet().stream().allMatch(p->p.x()>=0 && p.x()<9 && p.z()>=0 && p.z()<7));
        assertNotNull(pitched.get(new Vec3i(0,11,3)),"Hard gable end must be closed");
        assertNull(pitched.get(new Vec3i(4,11,3)),"Attic must retain empty space");
    }
    @Test void horseHeadWallsAreAttachedSymmetricAndRespectOptOuts() {
        for(var dims:List.of(new Dimensions(9,7,4),new Dimensions(8,12,4))) {
            var c=resolve("徽派","ROOF",dims,Map.of("wall_block","minecraft:white_concrete","roof_block","minecraft:deepslate_tiles"));
            var blocks=PatchTestSnapshot.blocks(new RoofGenerator().generate(new SemanticComponent("ROOF",null,c,"Chinese_Vernacular_Huizhou")));
            boolean along=dims.depth()>=dims.width();
            int span=along?dims.width():dims.depth(), length=along?dims.depth():dims.width();
            for(int across=0;across<span;across++) {
                int x=along?across:0,z=along?0:across;
                int top=blocks.keySet().stream().filter(p->p.x()==x && p.z()==z).mapToInt(Vec3i::y).max().orElseThrow();
                for(int y=10;y<=top;y++) {
                    assertNotNull(blocks.get(new Vec3i(x,y,z)));
                    assertEquals(blocks.get(new Vec3i(x,y,z)),blocks.get(new Vec3i(along?across:length-1,y,along?length-1:across)));
                }
                assertTrue(top<=15);
            }
            assertNull(blocks.get(new Vec3i(dims.width()/2,11,dims.depth()/2)));
        }
        assertFalse(resolve("徽派","ROOF",new Dimensions(9,7,4),Map.of("horse_head_walls",false)).params().get("horse_head_walls").equals(true));
        assertNull(resolve("徽派","ROOF",new Dimensions(9,7,4),Map.of("no_complex_decor",true)).params().get("horse_head_walls"));
        assertNull(resolve("徽派","ROOF",new Dimensions(9,7,4),Map.of("roof_type","flat")).params().get("horse_head_walls"));
    }
    @Test void smallHuiHouseDoesNotAcquireCutCornersOrUnrequestedCourtyard() {
        var c=resolve("Chinese_Vernacular_Huizhou","MASS_MAIN",new Dimensions(11,9,5),Map.of());
        assertEquals("none",c.params().get("plan_type"));
        assertFalse(c.features().contains("courtyard"));
        assertEquals("courtyard",resolve("徽派","MASS_MAIN",new Dimensions(18,18,5),Map.of()).params().get("plan_type"));
    }
    @Test void explicitGeometryAndWindowOptOutsSurviveBothStyles() {
        var explicit=Map.<String,Object>of("roof_type","gable","plan_type","l_shape","window_ratio",0.0);
        for(var style:List.of("现代风格","徽派")) {
            var c=resolve(style,"MASS_MAIN",new Dimensions(18,18,5),explicit);
            explicit.forEach((key,value)->assertEquals(value,c.params().get(key)));
            assertEquals("none",resolve(style,"ROOF",new Dimensions(9,7,4),Map.of("roof_type","none")).params().get("roof_type"));
        }
    }
    @Test void slabIsNotTreatedAsAnotherStyledBuilding() {
        var c=resolve("徽派","MASS_MAIN",new Dimensions(18,18,1),Map.of("extrude_mode","plate"));
        assertEquals(Map.of("extrude_mode","plate"),c.params());
        assertTrue(c.features().isEmpty());
    }
    @Test void declaredModernIdentityWinsOverIncidentalLatticeFeature() {
        var c=new Component("ROOF",null,new Vec3i(0,10,0),new Dimensions(9,7,4),List.of("hui_lattice"),Map.of());
        var resolved=StyleIntentResolver.apply(LlmPlanTestFixtures.builder().styleProfile("Modern_International").build(),c);
        assertEquals("flat",resolved.params().get("roof_type"));
    }
}
