package com.formacraft.server.build;

import com.formacraft.common.buildcontext.OutlineShape;
import com.formacraft.common.buildcontext.SelectionBox;
import com.formacraft.common.llm.dto.*;
import com.formacraft.common.model.constraint.ProtectedZone;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.patch.filter.*;
import com.formacraft.common.patch.filter.impl.*;
import com.formacraft.common.tool.MirrorSymmetryMode;
import com.formacraft.common.tool.ToolConstraintSnapshot;
import com.formacraft.common.world.MutationRegionPolicy;
import com.formacraft.server.compiler.ComponentPlanCompiler;
import com.formacraft.server.patch.ServerPatchFilter;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Region parity is separate from symmetry expansion and Build-only brush/path inputs. */
class ConstraintParityTest {
    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints={13,21})
    void darkFortressCompilesTwoHollowDefensiveTowerShells(int size) throws Exception {
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
        var plan=mapper.readValue(java.nio.file.Path.of("python_backend/eval/fixtures/plans/p2_dark_fortress_"+size+".json").toFile(),LlmPlan.class);
        var raw=ComponentPlanCompiler.compile(plan,ORIGIN,null,null,false);
        assertFalse(raw.isEmpty());
        var blocks=com.formacraft.test.PatchTestSnapshot.blocks(raw);
        var towers=plan.components().stream().filter(c->"TOWER".equals(c.componentType())).toList();
        assertEquals(2,towers.size());
        for(var tower:towers) {
            var p=tower.relativePosition();int cx=p.x()+2,cz=p.z()+2;
            assertEquals("minecraft:stone_bricks",blocks.get(new Vec3i(cx,0,cz)));
            for(int y=1;y<14;y++) {
                assertNull(blocks.get(new Vec3i(cx,y,cz)),"hollow tower core");
                assertEquals("minecraft:blackstone",blocks.get(new Vec3i(cx-2,y,cz)),"continuous tower shell");
                assertEquals("minecraft:blackstone",blocks.get(new Vec3i(cx+2,y,cz)),"continuous tower shell");
            }
        }
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints={17,21})
    void huizhouTianjingHasOpenSkyAndReachableFourWings(int size) throws Exception {
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
        var tree=mapper.readTree(java.nio.file.Path.of("python_backend/eval/fixtures/plans/huizhou_tianjing_"+size+".json").toFile());
        var plan=mapper.treeToValue(tree,LlmPlan.class);
        var raw=ComponentPlanCompiler.compile(plan,ORIGIN,null,null,false);
        assertFalse(raw.isEmpty(),()->String.valueOf(com.formacraft.server.assembly.AssemblyCompileDiagnostics.get()));
        var blocks=com.formacraft.test.PatchTestSnapshot.blocks(raw);
        int side=(size-7)/2,front=side+1,depth=2*front+7;
        var windowCells=new HashSet<Vec3i>();
        for(var c:plan.components()) if("FACADE_WINDOWS".equals(c.componentType())) {
            var p=c.relativePosition();
            for(int y=p.y();y<p.y()+2;y++) {
                var cell=new Vec3i(p.x(),y,p.z());windowCells.add(cell);
                assertTrue(blocks.getOrDefault(cell,"").startsWith("minecraft:spruce_fence"),"explicit lattice window: "+cell);
            }
        }
        assertEquals(8,windowCells.size());
        var editedTree=tree.deepCopy();
        var editedComponents=(com.fasterxml.jackson.databind.node.ArrayNode)editedTree.get("components");
        for(int i=editedComponents.size()-1;i>=0;i--)
            if("FACADE_WINDOWS".equals(editedComponents.get(i).path("component_type").asText()))editedComponents.remove(i);
        for(var c:editedComponents) if("MASS_MAIN".equals(c.path("component_type").asText()))
            ((com.fasterxml.jackson.databind.node.ObjectNode)c.get("params")).put("window_ratio",0);
        var withoutWindows=com.formacraft.test.PatchTestSnapshot.blocks(ComponentPlanCompiler.compile(
                mapper.treeToValue(editedTree,LlmPlan.class),ORIGIN,null,null,false));
        assertEquals(blocks.keySet(),withoutWindows.keySet(),"window removal preserves the wall envelope");
        for(var cell:blocks.keySet()) {
            if(windowCells.contains(cell))assertEquals("minecraft:white_concrete",withoutWindows.get(cell));
            else assertEquals(blocks.get(cell),withoutWindows.get(cell),"window edit must preserve unrelated blocks: "+cell);
        }
        for(int x=side;x<side+7;x++) for(int z=front;z<front+7;z++) {
            assertTrue(blocks.containsKey(new Vec3i(x,0,z)),"skywell paving");
            for(int y=1;y<16;y++) assertNull(blocks.get(new Vec3i(x,y,z)),"skywell must remain open to sky");
        }
        var start=new Vec3i(size/2,1,0);
        var visited=new HashSet<Vec3i>();var queue=new ArrayDeque<Vec3i>();queue.add(start);
        while(!queue.isEmpty()) {
            var p=queue.removeFirst();
            if(p.x()<0||p.x()>=size||p.z()<0||p.z()>=depth||visited.contains(p))continue;
            if(!blocks.containsKey(new Vec3i(p.x(),0,p.z()))||blocks.containsKey(p)||blocks.containsKey(new Vec3i(p.x(),2,p.z())))continue;
            visited.add(p);
            queue.add(new Vec3i(p.x()+1,1,p.z()));queue.add(new Vec3i(p.x()-1,1,p.z()));
            queue.add(new Vec3i(p.x(),1,p.z()+1));queue.add(new Vec3i(p.x(),1,p.z()-1));
        }
        for(var target:List.of(new Vec3i(size/2,1,front+3),new Vec3i(2,1,front+3),
                new Vec3i(size-3,1,front+3),new Vec3i(size/2,1,depth-front+2)))
            assertTrue(visited.contains(target),"all wings reachable from exterior: "+target);
        var min=ORIGIN.add(0,-4,-4);var max=ORIGIN.add(size/2,32,depth+4);
        var expected=allowed(raw,ORIGIN,new BuildConstraints(min,max,null,List.of()));
        assertFalse(expected.isEmpty());assertTrue(expected.size()<raw.size());
        assertEquals(expected,ServerPatchFilter.filter(ORIGIN,raw,List.of(),null,true,min,max).accepted);
        assertEquals(expected,PatchFilterPipeline.createDefault().apply(raw,ORIGIN,
                new PatchFilterContext(snapshot(new SelectionBox(min,max),null,List.of()))));
    }

    static List<String> stylePlanNames(com.fasterxml.jackson.databind.JsonNode style) {
        var names = new ArrayList<String>();
        for (var name : style.path("plans")) names.add(name.asText());
        for (var variant : style.path("variant_cards"))
            for (var name : variant.path("plans")) names.add(name.asText());
        for (var name : style.path("diversity_plans")) names.add(name.asText());
        return names;
    }

    static java.util.stream.Stream<String> stylePackPlans() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        try (var input = ConstraintParityTest.class.getResourceAsStream(
                "/assets/formacraft/style_profiles/style_pack_manifest_v1.json")) {
            assertNotNull(input);
            var names = new ArrayList<String>();
            for (var style : mapper.readTree(input).get("styles"))
                names.addAll(stylePlanNames(style));
            assertFalse(names.isEmpty());
            assertEquals(names.size(), new HashSet<>(names).size(), "duplicate style fixture names");
            return names.stream();
        }
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("stylePackPlans")
    void authoredStylePackPlansCompileAndPreserveMaterialsAcrossClippers(String fixture) throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var plan = mapper.readValue(java.nio.file.Path.of("python_backend/eval/fixtures/plans", fixture).toFile(), LlmPlan.class);
        List<BlockPatch> raw;
        var first = plan.components().getFirst();
        var typology = com.formacraft.common.typology.TypologyComponentRouter.extractTypologyId(first);
        if (typology != null) {
            // Native routers require a live ServerWorld. Exercise their pure builders here;
            // compositional plans below exercise the complete offline compiler.
            String builderName = java.util.Arrays.stream(typology.split("_"))
                    .map(word -> Character.toUpperCase(word.charAt(0)) + word.substring(1))
                    .collect(java.util.stream.Collectors.joining()) + "Builder";
            var builder = Class.forName("com.formacraft.server.generation.typology.builder." + builderName);
            var generate = builder.getMethod("generate", Map.class, BlockPos.class,
                    net.minecraft.server.world.ServerWorld.class, com.formacraft.common.model.build.BuildingSpec.class);
            var parameters = com.formacraft.common.typology.TypologyParamResolver.merge(typology, first);
            var structure = (com.formacraft.common.build.GeneratedStructure) generate.invoke(null, parameters, ORIGIN, null, null);
            raw = com.formacraft.common.typology.TypologyPatchBridge.toBlockPatches(structure, ORIGIN);
        } else {
            raw = ComponentPlanCompiler.compile(plan, ORIGIN, null, null, false);
        }
        assertFalse(raw.isEmpty(), () -> fixture + ": " + com.formacraft.server.assembly.AssemblyCompileDiagnostics.get());
        try (var input = getClass().getResourceAsStream("/assets/formacraft/style_profiles/style_pack_manifest_v1.json")) {
            for (var style : mapper.readTree(input).get("styles")) {
                if (!stylePlanNames(style).contains(fixture)) continue;
                if (!style.get("typology_id").isNull()) {
                    String typologyId = style.get("typology_id").asText();
                    var definition = com.formacraft.common.typology.StructuralTypologyRegistry.getById(typologyId);
                    assertNotNull(definition, fixture + " missing typology: " + typologyId);
                    if ("compositional_only".equals(definition.routingPolicy())) {
                        assertNull(definition.interpreterId(), fixture + " must not claim a native interpreter");
                        assertNull(typology, fixture + " must not carry native component routing");
                        assertTrue(plan.components().stream().noneMatch(component ->
                                Set.of("STRUCTURE", "MODULE").contains(component.componentType())), fixture);
                    }
                }
                var materials = raw.stream().map(BlockPatch::targetBlock)
                        .map(id -> id.split("\\[")[0]).collect(java.util.stream.Collectors.toSet());
                assertTrue(materials.contains(style.get("expected_wall").asText()), fixture + " wall: " + materials);
                try (var genome = getClass().getResourceAsStream("/assets/formacraft/style_genomes/"
                        + style.get("genome_id").asText() + ".json")) {
                    var palette = mapper.readTree(genome).get("palette");
                    assertEquals(12, palette.size(), fixture + " palette roles");
                    for (var block : palette) {
                        var id = net.minecraft.util.Identifier.of(block.asText());
                        assertTrue(net.minecraft.registry.Registries.BLOCK.containsId(id), fixture + " unregistered material: " + id);
                        assertFalse(net.minecraft.registry.Registries.BLOCK.get(id).getDefaultState().isAir(), fixture + " air material: " + id);
                    }
                    assertInstanceOf(net.minecraft.block.DoorBlock.class, net.minecraft.registry.Registries.BLOCK.get(
                            net.minecraft.util.Identifier.of(palette.get("door").asText())), fixture);
                    assertInstanceOf(net.minecraft.block.StairsBlock.class, net.minecraft.registry.Registries.BLOCK.get(
                            net.minecraft.util.Identifier.of(palette.get("stairs").asText())), fixture);
                    assertInstanceOf(net.minecraft.block.SlabBlock.class, net.minecraft.registry.Registries.BLOCK.get(
                            net.minecraft.util.Identifier.of(palette.get("roof_slab").asText())), fixture);
                    assertTrue(java.util.stream.Stream.of("roof", "stairs", "roof_slab")
                            .anyMatch(role -> materials.contains(palette.get(role).asText())), fixture + " roof: " + materials);
                }
            }
        }
        int minX = raw.stream().mapToInt(BlockPatch::dx).min().orElseThrow();
        int maxX = raw.stream().mapToInt(BlockPatch::dx).max().orElseThrow();
        var min = ORIGIN.add(minX, -512, -512);
        var max = ORIGIN.add(minX + (maxX - minX) / 2, 512, 512);
        var expected = allowed(raw, ORIGIN, new BuildConstraints(min, max, null, List.of()));
        assertFalse(expected.isEmpty(), fixture);
        assertTrue(expected.size() < raw.size(), fixture);
        assertEquals(expected, ServerPatchFilter.filter(ORIGIN, raw, List.of(), null, true, min, max).accepted);
        assertEquals(expected, PatchFilterPipeline.createDefault().apply(raw, ORIGIN,
                new PatchFilterContext(snapshot(new SelectionBox(min, max), null, List.of()))));
    }

    private static final BlockPos ORIGIN = new BlockPos(100, 64, -40);
    @BeforeAll static void bootstrap() { MinecraftRegistryTestBootstrap.initialize(); }

    @ParameterizedTest
    @CsvSource({"jiangnan_quay,17", "jiangnan_quay,25", "romanesque_hall,17", "romanesque_hall,25", "tudor_house,17", "tudor_house,25"})
    void regionalSpecialistsRetainDistinctGeometryAndLocalEdits(String style,int size) throws Exception {
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
        var file=java.nio.file.Path.of("python_backend/eval/fixtures/plans",style+"_"+size+".json").toFile();
        var tree=mapper.readTree(file);
        var plan=mapper.treeToValue(tree,LlmPlan.class);
        var raw=ComponentPlanCompiler.compile(plan,ORIGIN,null,null,false);
        assertFalse(raw.isEmpty());
        var blocks=com.formacraft.test.PatchTestSnapshot.blocks(raw);
        int center=size/2;
        if(style.equals("jiangnan_quay")) {
            for(int x=1;x<size-1;x++) for(int z=-5;z<0;z++) {
                assertEquals("minecraft:stone_bricks",blocks.get(new Vec3i(x,0,z)),"continuous quay surface");
                assertNull(blocks.get(new Vec3i(x,1,z)),"clear walking space above quay");
                assertNull(blocks.get(new Vec3i(x,2,z)),"two-cell walking clearance");
            }
            assertEquals("minecraft:stone_bricks",blocks.get(new Vec3i(center,-1,-6)),"water access step");
            assertNull(blocks.get(new Vec3i(center,0,-8)),"channel space remains unfilled");
        } else if(style.equals("romanesque_hall")) {
            assertEquals("minecraft:stone_bricks",blocks.get(new Vec3i(center,10,-4)),"round arch crown");
            for(int y=1;y<8;y++) assertNull(blocks.get(new Vec3i(center,y,-4)),"clear arcade passage");
            assertEquals("minecraft:stone_bricks",blocks.get(new Vec3i(center-5,3,-4)),"supported arcade pier");
            var components=(com.fasterxml.jackson.databind.node.ArrayNode)tree.get("components");
            for(int i=components.size()-1;i>=0;i--)
                if(components.get(i).path("params").path("component_id").asText().startsWith("arc_")
                        && !components.get(i).path("params").path("component_id").asText().equals("arc_walk")) components.remove(i);
            var edited=com.formacraft.test.PatchTestSnapshot.blocks(ComponentPlanCompiler.compile(mapper.treeToValue(tree,LlmPlan.class),ORIGIN,null,null,false));
            assertNull(edited.get(new Vec3i(center,10,-4)));
            assertTrue(edited.size()<blocks.size());
            for(var block:edited.entrySet()) assertEquals(blocks.get(block.getKey()),block.getValue(),"arcade removal preserves all remaining blocks");
        } else {
            assertTrue(blocks.values().stream().anyMatch(v->v.startsWith("minecraft:dark_oak_log")),"exposed frame");
            ((com.fasterxml.jackson.databind.node.ObjectNode)tree.get("components").get(0).get("params")).put("wall_pattern","smooth");
            var edited=com.formacraft.test.PatchTestSnapshot.blocks(ComponentPlanCompiler.compile(mapper.treeToValue(tree,LlmPlan.class),ORIGIN,null,null,false));
            assertEquals(blocks.keySet(),edited.keySet(),"frame toggle preserves occupied shape");
            int changes=0;
            for(var block:blocks.entrySet()) {
                var after=edited.get(block.getKey());
                if(!block.getValue().equals(after)) {
                    changes++;
                    assertTrue(block.getValue().startsWith("minecraft:dark_oak_log"));
                    assertEquals("minecraft:white_terracotta",after,"only frame infill changes");
                }
            }
            assertTrue(changes>20,"frame toggle must visibly alter the facade");
        }
    }

    @ParameterizedTest
    @CsvSource({"10,2", "14,3"})
    void nativeTulouBlueprintRetainsEarthenRingFloorsAndOpenCourtyard(int radius,int floors) throws Exception {
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
        var plan=mapper.readValue(java.nio.file.Path.of("python_backend/eval/fixtures/plans/tulou_ring_"+radius+".json").toFile(),LlmPlan.class);
        var component=plan.components().getFirst();
        var parent=new com.formacraft.common.model.build.BuildingSpec();
        parent.setStyle(com.formacraft.common.model.build.BuildingStyle.ASIAN);
        parent.setExtra(new HashMap<>(Map.of("styleProfileId",plan.styleProfile(),"paletteId",component.params().get("paletteId"))));
        @SuppressWarnings("unchecked") var blueprint=(Map<String,Object>)component.params().get("blueprint");
        var compiler=new com.formacraft.server.generation.structure.blueprint.TulouBlueprintCompiler();
        assertTrue(compiler.supports(parent,blueprint));
        var nativePlan=(com.formacraft.common.skeleton.compound.GeneratorBackedPlan)compiler.compile(parent,blueprint);
        assertEquals(radius,nativePlan.spec.getFootprint().getRadius());
        assertEquals(floors,nativePlan.spec.getFloors());
        var generator=new com.formacraft.server.generation.structure.TulouGenerator();
        var structure=generator.generate(nativePlan.spec,ORIGIN,null);
        var patches=com.formacraft.common.typology.TypologyPatchBridge.toBlockPatches(structure,ORIGIN);
        assertFalse(patches.isEmpty());
        var blocks=com.formacraft.test.PatchTestSnapshot.blocks(patches);
        assertTrue(blocks.values().stream().filter(v->v.startsWith("minecraft:packed_mud")).count()>100,
                "dedicated earthen palette must reach native wall geometry");
        assertTrue(blocks.values().stream().anyMatch(v->v.startsWith("minecraft:deepslate_tiles")));
        // The native courtyard contains a central well and lantern through y=3.
        for(int y=4;y<=floors*4+7;y++) assertNull(blocks.get(new Vec3i(0,y,0)),"courtyard must stay open above its furniture");
        for(int y=1;y<=floors*4+7;y++) assertNull(blocks.get(new Vec3i(2,y,0)),"courtyard beside the well must stay open to sky");
        for(int floor=0;floor<floors;floor++)
            assertTrue(blocks.getOrDefault(new Vec3i(radius-2,1+floor*4,0),"").startsWith("minecraft:spruce_planks"),
                    "annular floor band must exist at every declared storey");
        var min=ORIGIN.add(0,-8,-radius-8);var max=ORIGIN.add(radius+8,64,radius+8);
        var expected=allowed(patches,ORIGIN,new BuildConstraints(min,max,null,List.of()));
        assertFalse(expected.isEmpty());assertTrue(expected.size()<patches.size());
        assertEquals(expected,ServerPatchFilter.filter(ORIGIN,patches,List.of(),null,true,min,max).accepted);
        assertEquals(expected,PatchFilterPipeline.createDefault().apply(patches,ORIGIN,
                new PatchFilterContext(snapshot(new SelectionBox(min,max),null,List.of()))));
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints={13,21})
    void undergroundDwarvenHallExcavatesSeededRockWithinItsBounds(int width) throws Exception {
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
        var file=java.nio.file.Path.of("python_backend/eval/fixtures/plans/dwarven_underground_"+width+".json");
        var plan=mapper.readValue(file.toFile(),LlmPlan.class);
        assertEquals(GlobalConstraints.TerrainStrategy.PRESERVE,plan.globalConstraints().terrainStrategy());
        var patches=ComponentPlanCompiler.compile(plan,ORIGIN,null,null,false);
        assertFalse(patches.isEmpty(),()->String.valueOf(com.formacraft.server.assembly.AssemblyCompileDiagnostics.get()));
        int depth=width+4, center=width/2;
        var stone=new HashMap<Vec3i,String>();
        for(int x=-1;x<=width;x++) for(int z=-24;z<=depth;z++) for(int y=-22;y<=0;y++)
            stone.put(new Vec3i(x,y,z),"minecraft:stone");
        var world=replayOn(stone,patches);
        for(var patch:patches) {
            assertTrue(patch.dx()>=0 && patch.dx()<width && patch.dz()>=-23 && patch.dz()<depth
                    && patch.dy()>=-21 && patch.dy()<=3,"operation escaped authored envelope: "+patch);
            if(patch.dy()>0) assertTrue(BlockPatch.REMOVE.equals(patch.action()) || "minecraft:air".equals(patch.targetBlock()),
                    "no invented building above the reference surface");
        }
        for(int x=1;x<width-1;x++) for(int z=1;z<depth-1;z++) for(int y=-19;y<=-12;y++) {
            boolean brazier=(x==1 || x==width-2) && z==1 && y<=-18;
            assertEquals(brazier?"minecraft:shroomlight":null,world.get(new Vec3i(x,y,z)),"room rock at "+x+","+y+","+z);
        }
        for(int z=-23;z<=2;z++) {
            int floor=z < -3 ? -(z+23) : -20;
            for(int x=center-1;x<=center+1;x++) assertWalkCell(world,x,floor,z);
        }
        assertNull(world.get(new Vec3i(center,-7,depth/2)),"rock under vault apex must be excavated");
        assertEquals("minecraft:deepslate_tiles",world.get(new Vec3i(center,-6,depth/2)),"vault lining must survive");
        assertEquals("minecraft:stone",world.get(new Vec3i(center,-5,depth/2)),"natural cover must survive");
        assertEquals("minecraft:stone",world.get(new Vec3i(-1,-10,depth/2)),"outside rock must survive");
        var roof=plan.components().get(1);
        var shell=new com.formacraft.common.generation.component.impl.RoofGenerator().generate(
                new com.formacraft.common.compiler.semantic.SemanticComponent("ROOF",null,roof,"Dwarven_Hall"));
        // Gable sealing may deliberately overwrite earlier roof operations; compare
        // the final shell state rather than every intermediate write in the list.
        for(var block:com.formacraft.test.PatchTestSnapshot.blocks(shell).entrySet())
            assertEquals(block.getValue(),world.get(block.getKey()),"vault shell punctured at "+block.getKey());
        var without=mapper.readValue(file.toFile(),LlmPlan.class);
        without.components().removeIf(c->String.valueOf(c.params().get("component_id")).startsWith("vault_excavation_"));
        var unexcavated=replayOn(stone,ComponentPlanCompiler.compile(without,ORIGIN,null,null,false));
        assertEquals("minecraft:stone",unexcavated.get(new Vec3i(center,-7,depth/2)),"control must detect omitted excavation");
        var min=ORIGIN.add(0,-21,-23);var max=ORIGIN.add(center,3,depth-1);
        var expected=allowed(patches,ORIGIN,new BuildConstraints(min,max,null,List.of()));
        assertFalse(expected.isEmpty());assertTrue(expected.size()<patches.size());
        assertEquals(expected,ServerPatchFilter.filter(ORIGIN,patches,List.of(),null,true,min,max).accepted);
        assertEquals(expected,PatchFilterPipeline.createDefault().apply(patches,ORIGIN,
                new PatchFilterContext(snapshot(new SelectionBox(min,max),null,List.of()))));
    }

    private static Map<Vec3i,String> replayOn(Map<Vec3i,String> initial,List<BlockPatch> patches) {
        var result=new HashMap<>(initial);
        for(var patch:patches) {
            var pos=new Vec3i(patch.dx(),patch.dy(),patch.dz());
            if(BlockPatch.REMOVE.equals(patch.action()) || "minecraft:air".equals(patch.targetBlock())) result.remove(pos);
            else result.put(pos,patch.targetBlock());
        }
        return result;
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints={13,21})
    void treehouseTrunkAccessAndAccessRemovalRemainConnectedAndLocal(int width) throws Exception {
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
        var file=java.nio.file.Path.of("python_backend/eval/fixtures/plans/p2_elven_treehouse_"+width+".json");
        var plan=mapper.readValue(file.toFile(),LlmPlan.class);
        assertEquals(GlobalConstraints.TerrainStrategy.PRESERVE,plan.globalConstraints().terrainStrategy());
        var patches=ComponentPlanCompiler.compile(plan,ORIGIN,null,null,false);
        assertFalse(patches.isEmpty(),()->String.valueOf(com.formacraft.server.assembly.AssemblyCompileDiagnostics.get()));
        var blocks=com.formacraft.test.PatchTestSnapshot.blocks(patches);
        int center=width/2;
        for(int y=0;y<13;y++) assertEquals("minecraft:oak_log",blocks.get(new Vec3i(center,y,center)));
        assertNotNull(blocks.get(new Vec3i(center,13,center)),"trunk must contact the floor");
        // The full three-wide run must join the landing, doorway and interior.
        for(int z=-16;z<=2;z++) {
            int floor=z < -3 ? z+16 : 13;
            for(int x=center-1;x<=center+1;x++) assertWalkCell(blocks,x,floor,z);
        }
        for(int step=1;step<13;step++) {
            assertNotNull(blocks.get(new Vec3i(center,step-1,-16+step)),"adjacent treads must share a face");
        }
        assertTreehouseGuards(blocks,center);
        var missingGuard=new HashMap<>(blocks);missingGuard.remove(new Vec3i(center-2,7,-10));
        assertThrows(AssertionError.class,()->assertTreehouseGuards(missingGuard,center));
        var noGuards=mapper.readValue(file.toFile(),LlmPlan.class);
        noGuards.components().removeIf(c->String.valueOf(c.params().get("component_id")).startsWith("tree_access_guard_"));
        var barePatches=ComponentPlanCompiler.compile(noGuards,ORIGIN,null,null,false);
        assertFalse(barePatches.isEmpty());
        var bare=com.formacraft.test.PatchTestSnapshot.blocks(barePatches);
        var guardPositions=new HashSet<>(blocks.keySet());guardPositions.addAll(bare.keySet());
        int guardChanges=0;
        for(var pos:guardPositions) if(!Objects.equals(blocks.get(pos),bare.get(pos))) {
            guardChanges++;
            assertTrue(pos.z()>=-16 && pos.z()<0 && Math.abs(pos.x()-center)==2,
                    "guard-only edit escaped its side columns: "+pos);
        }
        assertTrue(guardChanges>0);
        var blocked=new HashMap<>(blocks);blocked.put(new Vec3i(center,8,-10),"minecraft:stone");
        assertThrows(AssertionError.class,()->assertWalkCell(blocked,center,6,-10));
        var missing=new HashMap<>(blocks);missing.remove(new Vec3i(center,6,-10));
        assertThrows(AssertionError.class,()->assertWalkCell(missing,center,6,-10));
        var noAccess=mapper.readValue(file.toFile(),LlmPlan.class);
        noAccess.components().removeIf(c->String.valueOf(c.params().get("component_id")).startsWith("tree_access_"));
        var withoutPatches=ComponentPlanCompiler.compile(noAccess,ORIGIN,null,null,false);
        assertFalse(withoutPatches.isEmpty());
        var without=com.formacraft.test.PatchTestSnapshot.blocks(withoutPatches);
        var positions=new HashSet<>(blocks.keySet());positions.addAll(without.keySet());
        int changes=0;
        for(var pos:positions) if(!Objects.equals(blocks.get(pos),without.get(pos))) {
            changes++;
            assertTrue(pos.z()<0,"removing the approach must preserve house/trunk: "+pos);
        }
        assertTrue(changes>0);
        var min=ORIGIN.add(center-1,-32,-16);var max=ORIGIN.add(width-1,64,width+3);
        var expected=allowed(patches,ORIGIN,new BuildConstraints(min,max,null,List.of()));
        assertFalse(expected.isEmpty());assertTrue(expected.size()<patches.size());
        assertEquals(expected,ServerPatchFilter.filter(ORIGIN,patches,List.of(),null,true,min,max).accepted);
        assertEquals(expected,PatchFilterPipeline.createDefault().apply(patches,ORIGIN,
                new PatchFilterContext(snapshot(new SelectionBox(min,max),null,List.of()))));
    }

    @ParameterizedTest
    @CsvSource({"stilt_timber,13", "stilt_timber,17", "stilt_timber,21", "water_platform,13", "water_platform,21"})
    void raisedTimberExamplesKeepSupportsClearanceAndAuthoredAccess(String style, int width) throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var plan = mapper.readValue(java.nio.file.Path.of("python_backend/eval/fixtures/plans/p2_" + style + "_" + width + ".json").toFile(), LlmPlan.class);
        assertEquals(GlobalConstraints.TerrainStrategy.PRESERVE, plan.globalConstraints().terrainStrategy());
        var patches = ComponentPlanCompiler.compile(plan, ORIGIN, null, null, false);
        assertFalse(patches.isEmpty(), () -> String.valueOf(com.formacraft.server.assembly.AssemblyCompileDiagnostics.get()));
        var blocks = com.formacraft.test.PatchTestSnapshot.blocks(patches);
        int depth = width + 4;
        String post = "water_platform".equals(style) ? "minecraft:oak_log" : "minecraft:spruce_log";
        var posts = Set.of(new Vec3i(1,0,1), new Vec3i(width-2,0,1),
                new Vec3i(1,0,depth-2), new Vec3i(width-2,0,depth-2));
        for (var column : posts) {
            for (int y=0;y<5;y++) assertEquals(post, blocks.get(new Vec3i(column.x(),y,column.z())));
            assertNotNull(blocks.get(new Vec3i(column.x(),5,column.z())), "post must meet the raised floor");
        }
        for (var component : plan.components()) {
            String id = String.valueOf(component.params().get("component_id"));
            if (!id.startsWith("access_post_") && !id.startsWith("access_canopy_post_")) continue;
            var base = component.relativePosition(); int height = component.dimensions().height();
            for (int y=0;y<height;y++) assertEquals(post,blocks.get(new Vec3i(base.x(),base.y()+y,base.z())));
            assertNotNull(blocks.get(new Vec3i(base.x(),base.y()+height,base.z())), "access post must meet deck/canopy");
        }
        // Fixed bed at -1 and water at 0..3: raw patch writes, including air/removal,
        // must leave every non-pile column under the dwelling untouched.
        for (var patch : patches) {
            assertTrue(patch.dy() >= 0, "fixture must not excavate the synthetic bed");
            if (patch.dx()>=0 && patch.dx()<width && patch.dz()>=0 && patch.dz()<depth && patch.dy()<5) {
                assertTrue(posts.contains(new Vec3i(patch.dx(),0,patch.dz())), "write outside support column: " + patch);
                assertEquals(BlockPatch.PLACE, patch.action());
                assertEquals(post, patch.targetBlock());
            }
        }
        for (int z=-8;z<=2;z++) {
            int floor = "stilt_timber".equals(style) && z < -3 ? z+8 : 5;
            assertWalkCell(blocks,width/2,floor,z);
        }
        var blocked = new HashMap<>(blocks);
        blocked.put(new Vec3i(width/2,6,-2), "minecraft:stone");
        assertThrows(AssertionError.class, () -> assertWalkCell(blocked,width/2,5,-2));
        var gap = new HashMap<>(blocks); gap.remove(new Vec3i(width/2,5,-2));
        assertThrows(AssertionError.class, () -> assertWalkCell(gap,width/2,5,-2));
        var terrain = new com.formacraft.common.compiler.postprocess.TerrainAdaptationPostProcessor(null,null);
        assertSame(patches, terrain.process(patches,
                com.formacraft.common.compiler.postprocess.PostProcessContext.create(plan,ORIGIN)));
        var min=ORIGIN.add(0,-32,-8); var max=ORIGIN.add(width/2,64,depth-1);
        var expected=allowed(patches,ORIGIN,new BuildConstraints(min,max,null,List.of()));
        assertFalse(expected.isEmpty()); assertTrue(expected.size()<patches.size());
        assertEquals(expected,ServerPatchFilter.filter(ORIGIN,patches,List.of(),null,true,min,max).accepted);
        assertEquals(expected,PatchFilterPipeline.createDefault().apply(patches,ORIGIN,
                new PatchFilterContext(snapshot(new SelectionBox(min,max),null,List.of()))));
    }

    private static void assertWalkCell(Map<Vec3i,String> blocks,int x,int floor,int z) {
        assertNotNull(blocks.get(new Vec3i(x,floor,z)), "missing tread at " + x + "," + floor + "," + z);
        assertNull(blocks.get(new Vec3i(x,floor+1,z)), "feet clearance at " + z);
        assertNull(blocks.get(new Vec3i(x,floor+2,z)), "head clearance at " + z);
    }

    private static void assertTreehouseGuards(Map<Vec3i,String> blocks,int center) {
        for(int z=-16;z<0;z++) {
            int floor=z < -3 ? z+16 : 13;
            for(int x:new int[]{center-2,center+2}) {
                assertNotNull(blocks.get(new Vec3i(x,floor,z)),"guard must sit on a tread or landing");
                for(int y=floor+1;y<=floor+2;y++)
                    assertEquals("minecraft:stripped_birch_log",blocks.get(new Vec3i(x,y,z)),"gap in guard at "+x+","+y+","+z);
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"13,17", "21,15"})
    void huizhouGablesCompileAsSteppedWallsAndEditsStayOnEndPlanes(int width, int depth) throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var file = java.nio.file.Path.of("python_backend/eval/fixtures/plans/huizhou_vernacular_" + width + ".json");
        var enabledPlan = mapper.readValue(file.toFile(), LlmPlan.class);
        var enabled = ComponentPlanCompiler.compile(enabledPlan, ORIGIN, null, null, false);
        assertFalse(enabled.isEmpty(), () -> String.valueOf(com.formacraft.server.assembly.AssemblyCompileDiagnostics.get()));
        var on = com.formacraft.test.PatchTestSnapshot.blocks(enabled);
        var disabledPlan = mapper.readValue(file.toFile(), LlmPlan.class);
        disabledPlan.components().get(1).params().put("horse_head_walls", false);
        var disabled = ComponentPlanCompiler.compile(disabledPlan, ORIGIN, null, null, false);
        assertFalse(disabled.isEmpty());
        var off = com.formacraft.test.PatchTestSnapshot.blocks(disabled);
        boolean alongDepth = depth >= width;
        int span = Math.min(width, depth), length = Math.max(width, depth);
        var tops = new ArrayList<Integer>();
        for (int across = 0; across < span; across++) {
            int x = alongDepth ? across : 0, z = alongDepth ? 0 : across;
            int top = on.keySet().stream().filter(p -> p.x() == x && p.z() == z)
                    .mapToInt(Vec3i::y).max().orElseThrow();
            tops.add(top);
            assertEquals("minecraft:deepslate_tiles", on.get(new Vec3i(x, top, z)));
            assertEquals("minecraft:white_concrete", on.get(new Vec3i(x, top - 1, z)));
            for (int y = 5; y <= top; y++) {
                assertNotNull(on.get(new Vec3i(x, y, z)), "gable wall must remain attached");
                assertEquals(on.get(new Vec3i(x, y, z)), on.get(new Vec3i(
                        alongDepth ? across : length - 1, y, alongDepth ? length - 1 : across)));
            }
        }
        assertTrue(new HashSet<>(tops).size() >= 3, "must have multiple terraces");
        assertTrue(tops.get(span / 2) > tops.getFirst());
        for (int i = 0; i < span; i++) assertEquals(tops.get(i), tops.get(span - 1 - i));
        var all = new HashSet<>(on.keySet()); all.addAll(off.keySet());
        int changed = 0;
        for (var pos : all) if (!Objects.equals(on.get(pos), off.get(pos))) {
            changed++;
            assertTrue(pos.y() >= 5 && (alongDepth ? pos.z() == 0 || pos.z() == depth - 1
                    : pos.x() == 0 || pos.x() == width - 1), "edit escaped gable planes: " + pos);
        }
        assertTrue(changed > 0, "disabling gables must change compiled blocks");
        assertNull(on.get(new Vec3i(width / 2, 6, depth / 2)), "attic interior must remain hollow");
        var min = ORIGIN.add(0, -32, 0); var max = ORIGIN.add(width / 2, 32, depth - 1);
        var expected = allowed(enabled, ORIGIN, new BuildConstraints(min, max, null, List.of()));
        assertFalse(expected.isEmpty()); assertTrue(expected.size() < enabled.size());
        assertEquals(expected, ServerPatchFilter.filter(ORIGIN, enabled, List.of(), null, true, min, max).accepted);
        assertEquals(expected, PatchFilterPipeline.createDefault().apply(enabled, ORIGIN,
                new PatchFilterContext(snapshot(new SelectionBox(min, max), null, List.of()))));
    }

    @Test void allP1GenomeRolesReachTheRuntimeAdapterWithoutFallback() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var ids = new HashSet<>(List.of("default", "rustic_wood", "medieval_stone", "mingqing_official",
                "modern_minimal", "futuristic_clean"));
        try (var input = getClass().getResourceAsStream("/assets/formacraft/style_profiles/style_pack_manifest_v1.json")) {
            for (var style : mapper.readTree(input).get("styles")) ids.add(style.get("genome_id").asText());
        }
        assertTrue(ids.size() >= 20);
        for (String id : ids) {
            var genome = com.formacraft.common.style.StyleGenomeRegistry.load(id);
            assertEquals(id, genome.id);
            var adapter = new com.formacraft.common.style.profile.GenomeStyleProfile(genome,
                    com.formacraft.common.model.build.BuildingStyle.DEFAULT).palette();
            var actual = Map.ofEntries(Map.entry("wall", adapter.wall), Map.entry("roof", adapter.roof),
                    Map.entry("floor", adapter.floor), Map.entry("window", adapter.window),
                    Map.entry("foundation", adapter.foundation), Map.entry("trim", adapter.trim),
                    Map.entry("pillar", adapter.pillar), Map.entry("cap", adapter.cap),
                    Map.entry("door", adapter.door), Map.entry("roof_trim", adapter.roofTrim),
                    Map.entry("stairs", adapter.stairs), Map.entry("roof_slab", adapter.roofSlab));
            try (var input = getClass().getResourceAsStream("/assets/formacraft/style_genomes/" + id + ".json")) {
                var expected = mapper.readTree(input).get("palette");
                assertEquals(12, expected.size(), id);
                for (var role : actual.entrySet()) {
                    assertEquals(expected.get(role.getKey()).asText(), role.getValue(), id + ": " + role.getKey());
                    assertTrue(net.minecraft.registry.Registries.BLOCK.containsId(
                            net.minecraft.util.Identifier.of(role.getValue())), id + ": " + role);
                }
            }
        }
    }

    @Test void mixedGardenAndChapelGoldenCompilesWithSeparateRoofBindings() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var file = java.nio.file.Path.of("python_backend/eval/fixtures/plans/mixed_garden_chapel.json").toFile();
        var plan = mapper.readValue(file, LlmPlan.class);
        var expectedTypes = Map.of("garden", "hip", "chapel", "gable");
        for (var roof : plan.components()) {
            if (!"ROOF".equals(roof.componentType())) continue;
            assertEquals(expectedTypes.get(roof.params().get("host_id")), roof.params().get("roof_type"));
        }
        var raw = ComponentPlanCompiler.compile(plan, ORIGIN, null, null, false);
        assertFalse(raw.isEmpty(), () -> String.valueOf(com.formacraft.server.assembly.AssemblyCompileDiagnostics.get()));
        assertTrue(raw.stream().anyMatch(p -> p.dx() < 20));
        assertTrue(raw.stream().anyMatch(p -> p.dx() >= 30));
        var min = ORIGIN.add(-8, -16, -16);
        var max = ORIGIN.add(24, 64, 32);
        var clipped = allowed(raw, ORIGIN, new BuildConstraints(min, max, null, List.of()));
        assertFalse(clipped.isEmpty());
        assertTrue(clipped.size() < raw.size());
        assertEquals(clipped, ServerPatchFilter.filter(ORIGIN, raw, List.of(), null, true, min, max).accepted);
        assertEquals(clipped, PatchFilterPipeline.createDefault().apply(raw, ORIGIN,
                new PatchFilterContext(snapshot(new SelectionBox(min, max), null, List.of()))));
    }

    private ToolConstraintSnapshot snapshot(SelectionBox selection, OutlineShape outline, List<ProtectedZone> zones) {
        return new ToolConstraintSnapshot(selection, outline, List.of(), zones,
                ToolConstraintSnapshot.SymmetryConstraint.none());
    }

    private List<BlockPatch> probes() {
        List<BlockPatch> result = new ArrayList<>();
        for (int x = -3; x <= 3; x++) for (int y = -1; y <= 5; y++) for (int z = -3; z <= 3; z++)
            result.add(new BlockPatch(BlockPatch.REPLACE, x, y, z, "minecraft:blue_stained_glass"));
        return result;
    }

    private List<BlockPatch> allowed(List<BlockPatch> input, BlockPos origin, BuildConstraints constraints) {
        return input.stream().filter(p -> constraints.allow(origin.add(p.dx(), p.dy(), p.dz()))).toList();
    }

    @Test void defaultPipelineAndBuildExposeExactlyTheSameSharedRules() {
        assertEquals(Set.of(MutationRegionPolicy.Constraint.BOUNDS, MutationRegionPolicy.Constraint.OUTLINE,
                MutationRegionPolicy.Constraint.PROTECTED_ZONES), BuildConstraints.sharedRegionConstraints());
        assertEquals(BuildConstraints.sharedRegionConstraints(), PatchFilterPipeline.createDefault().regionConstraints());
        // The inventory detects a removed filter, and the behavior test also sees its escaped cells.
        var missingBounds = new PatchFilterPipeline().add(new ForbiddenZoneFilter()).add(new OutlineClipFilter());
        assertNotEquals(BuildConstraints.sharedRegionConstraints(), missingBounds.regionConstraints());
        var selection = new SelectionBox(ORIGIN.add(-2, 0, -2), ORIGIN.add(2, 4, 2));
        var context = new PatchFilterContext(snapshot(selection, null, List.of()));
        assertEquals(343, missingBounds.apply(probes(), ORIGIN, context).size());
        assertEquals(125, PatchFilterPipeline.createDefault().apply(probes(), ORIGIN, context).size());
    }

    @Test void clippingMatrixAgreesWithIndependentBoundaryCounts() {
        var selection = new SelectionBox(ORIGIN.add(-2, 0, -2), ORIGIN.add(2, 4, 2));
        var circle = new OutlineShape("circle", List.of(), ORIGIN, 2, 64, 68);
        var polygon = new OutlineShape("polygon", List.of(ORIGIN.add(-2, 0, -2), ORIGIN.add(2, 0, -2),
                ORIGIN.add(2, 0, 2), ORIGIN.add(-2, 0, 2)), null, 0, 64, 68);
        var zone = new ProtectedZone(ORIGIN, ORIGIN.add(0, 4, 0));
        var snapshots = List.of(snapshot(null, null, List.of()), snapshot(selection, null, List.of()),
                snapshot(null, circle, List.of()), snapshot(null, polygon, List.of()),
                snapshot(null, null, List.of(zone)), snapshot(selection, circle, List.of(zone)),
                snapshot(new SelectionBox(selection.max(), selection.min()), null, List.of()),
                snapshot(null, new OutlineShape("polygon", List.of(), null, 0, 64, 68), List.of()));
        int[] expected = {343, 125, 65, 80, 338, 60, 125, 0};
        for (int i = 0; i < snapshots.size(); i++) {
            var input = snapshots.get(i);
            var box = input.selection == null ? null : input.selection.normalized();
            var build = new BuildConstraints(box == null ? null : box.min(), box == null ? null : box.max(),
                    input.outline, input.protectedZones);
            var clipped = PatchFilterPipeline.createDefault().apply(probes(), ORIGIN, new PatchFilterContext(input));
            assertEquals(expected[i], clipped.size(), "case " + i);
            assertEquals(allowed(probes(), ORIGIN, build), clipped, "case " + i);
        }
        assertFalse(new BuildConstraints(null, null, null, List.of()).allow(null));
    }

    @Test void serverPatchModesMatchTheirEffectiveBuildRegion() {
        var selection = new SelectionBox(ORIGIN.add(-2, 0, -2), ORIGIN.add(2, 4, 2));
        var outsideOutline = new OutlineShape("circle", List.of(), ORIGIN.add(50, 0, 0), 2, 64, 68);
        // MODIFY_REGION intentionally ignores the outline, even when it is still present in tool state.
        var patch = ServerPatchFilter.filter(ORIGIN, probes(), List.of(), outsideOutline, true,
                selection.max(), selection.min());
        assertEquals(125, patch.accepted.size());
        assertEquals(allowed(probes(), ORIGIN, new BuildConstraints(selection.min(), selection.max(), null, List.of())),
                patch.accepted);
        var outline = new OutlineShape("circle", List.of(), ORIGIN, 2, 64, 68);
        var outlineMode = ServerPatchFilter.filter(ORIGIN, probes(), List.of(), outline, false,
                selection.min(), selection.max());
        assertEquals(65, outlineMode.accepted.size());
        assertEquals(allowed(probes(), ORIGIN, new BuildConstraints(null, null, outline, List.of())), outlineMode.accepted);
    }

    @ParameterizedTest
    @CsvSource({"Modern_International,flat,pane", "Gothic_Cathedral,gable,stained", "Chinese_Imperial_Official,hip,lattice"})
    void sameStylePlanRetainsIdenticalPatchesAndMaterialsAfterBothClippers(String style, String roof, String window) {
        var body = new Component("MASS_MAIN", "body", new Vec3i(0, 0, 0), new Dimensions(13, 11, 10), List.of(),
                Map.of("component_id", "body", "anchor_mode", "min_corner", "roof_type", roof,
                        "window_style", window, "wall_block", "minecraft:stone_bricks",
                        "roof_block", "minecraft:deepslate_tiles", "window_block", "minecraft:blue_stained_glass",
                        "plan_type", "rectangle", "window_ratio", 0.35));
        var plan = LlmPlanTestFixtures.builder().mode(LlmPlan.Mode.build).styleProfile(style)
                .anchor(new Vec3i(0, 0, 0)).layout(new Layout(null, false, List.of()))
                .components(List.of(body)).build();
        var raw = ComponentPlanCompiler.compile(plan, ORIGIN, null, null, false);
        assertFalse(raw.isEmpty(), () -> style + ": " + com.formacraft.server.assembly.AssemblyCompileDiagnostics.get());
        var min = ORIGIN.add(1, 0, 1);
        var max = ORIGIN.add(10, 18, 9);
        var zones = List.of(new ProtectedZone(ORIGIN.add(4, 0, 0), ORIGIN.add(6, 18, 11)));
        var build = new BuildConstraints(min, max, null, zones);
        var expected = allowed(raw, ORIGIN, build);
        assertFalse(expected.isEmpty(), style);
        assertTrue(expected.size() < raw.size(), "fixture must exercise clipping: " + style);
        assertEquals(expected, ServerPatchFilter.filter(ORIGIN, raw, zones, null, true, min, max).accepted);
        assertEquals(expected, PatchFilterPipeline.createDefault().apply(raw, ORIGIN,
                new PatchFilterContext(snapshot(new SelectionBox(min, max), null, zones))));
    }

    @Test void symmetryExpansionIsClippedAfterGenerationIncludingItsMirroredCells() {
        var forbidden = new ProtectedZone(ORIGIN.add(-1, 0, -1), ORIGIN.add(-1, 4, 1));
        var snapshot = new ToolConstraintSnapshot(null, null, List.of(), List.of(forbidden),
                new ToolConstraintSnapshot.SymmetryConstraint(MirrorSymmetryMode.MIRROR_X, null, null));
        var original = new BlockPatch(BlockPatch.PLACE, 1, 2, 0, "minecraft:deepslate_tiles");
        var expanded = new SymmetryFilter().filter(List.of(original), ORIGIN, new PatchFilterContext(snapshot));
        assertEquals(2, expanded.size());
        var build = new BuildConstraints(null, null, null, List.of(forbidden));
        assertEquals(List.of(original), allowed(expanded, ORIGIN, build));
        assertEquals(List.of(original), PatchFilterPipeline.createDefault().apply(List.of(original), ORIGIN,
                new PatchFilterContext(snapshot)));
    }

    @Test void buildOnlyBrushFallbackAndCorridorRemainExplicitAndDoNotDisappearFromBuild() {
        var brushMin = ORIGIN.add(-2, 0, -2); var brushMax = ORIGIN.add(2, 4, 2);
        var brush = new BuildConstraints(null, null, null, List.of(), brushMin, brushMax, null, 0);
        assertEquals(125, allowed(probes(), ORIGIN, brush).size());
        var selected = new BuildConstraints(ORIGIN, ORIGIN.add(1, 4, 1), null, List.of(),
                brushMin, brushMax, null, 0);
        assertEquals(20, allowed(probes(), ORIGIN, selected).size());
        var corridor = new BuildConstraints(null, null, null, List.of(), null, null,
                List.of(ORIGIN.add(-2, 0, 0), ORIGIN.add(2, 0, 0)), 1);
        assertTrue(corridor.allow(ORIGIN.add(0, 3, 1)));
        assertFalse(corridor.allow(ORIGIN.add(0, 3, 2)));
    }
}
