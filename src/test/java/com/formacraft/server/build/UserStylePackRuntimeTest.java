package com.formacraft.server.build;

import com.formacraft.common.style.*;
import com.formacraft.common.style.catalog.StyleProfileCatalogRegistry;
import com.formacraft.common.typology.StructuralTypologyRegistry;
import com.formacraft.common.palette.PaletteRegistry;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.server.compiler.ComponentPlanCompiler;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import com.google.gson.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

@org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="formacraft.stylePacksDir",matches=".+")
class UserStylePackRuntimeTest {
    @TempDir Path temporary;
    @BeforeAll static void bootstrap() { com.formacraft.test.MinecraftRegistryTestBootstrap.initialize(); }

    @Test void userAliasesProfilesPalettesGenomesAndTypologiesReachActualRegistries() {
        assertEquals("lake_hall",StyleIdentityRegistry.canonical("湖畔木屋"));
        assertNotNull(StyleProfileCatalogRegistry.get("lakeside timber"));
        assertNotNull(PaletteRegistry.get("lake_palette"));
        assertEquals("minecraft:spruce_planks",StyleGenomeRegistry.load("lake_hall").palette.wall);
        assertEquals("compositional_only",StructuralTypologyRegistry.getById("lake_house").routingPolicy());
        assertNull(StructuralTypologyRegistry.resolveInterpreterId("lake_house"));
        assertNotNull(StyleProfileCatalogRegistry.get("Alpine_Chalet"));
    }

    @Test void twoUserPlansCompileAndKeepMaterialAndClippingParity() throws Exception {
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
        var origin=new BlockPos(100,64,-40);
        for(String fixture:List.of("lake_small","lake_large")) {
            String json;
            try(var input=UserStylePacks.open("/assets/formacraft/llmplan_examples/"+fixture+".json")) {
                assertNotNull(input);
                json=JsonParser.parseString(new String(input.readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject().get("plan").toString();
            }
            var plan=mapper.readValue(json,LlmPlan.class);
            var patches=ComponentPlanCompiler.compile(plan,origin,null,null,false);
            assertFalse(patches.isEmpty(),()->String.valueOf(com.formacraft.server.assembly.AssemblyCompileDiagnostics.get()));
            assertTrue(patches.stream().anyMatch(p->p.targetBlock().equals("minecraft:spruce_planks")));
            var min=origin.add(0,-64,-64); var max=origin.add(8,128,128);
            var bounds=new BuildConstraints(min,max,null,List.of());
            var expected=patches.stream().filter(p->bounds.allow(origin.add(p.dx(),p.dy(),p.dz()))).toList();
            assertFalse(expected.isEmpty()); assertTrue(expected.size()<patches.size());
            assertEquals(expected,com.formacraft.server.patch.ServerPatchFilter.filter(origin,patches,List.of(),null,true,min,max).accepted);
            var snapshot=new com.formacraft.common.tool.ToolConstraintSnapshot(
                new com.formacraft.common.buildcontext.SelectionBox(min,max),null,List.of(),List.of(),
                com.formacraft.common.tool.ToolConstraintSnapshot.SymmetryConstraint.none());
            assertEquals(expected,com.formacraft.common.patch.filter.PatchFilterPipeline.createDefault().apply(patches,origin,
                new com.formacraft.common.patch.filter.PatchFilterContext(snapshot)));
        }
    }

    private Path copyPack(String name) throws Exception {
        var root=temporary.resolve(name); var folder=root.resolve("lake");
        Path source=Path.of("docs/examples/style-packs/lake");
        try(var paths=Files.walk(source)) {
            for(var path:paths.toList()) {
                Path target=folder.resolve(source.relativize(path));
                if(Files.isDirectory(path)) Files.createDirectories(target); else Files.copy(path,target);
            }
        }
        return root;
    }

    private void reject(String name,String relative,Consumer<JsonObject> change) throws Exception {
        Path root=copyPack(name); var path=root.resolve("lake").resolve(relative);
        var data=JsonParser.parseString(Files.readString(path)).getAsJsonObject(); change.accept(data);
        Files.writeString(path,data.toString());
        var result=UserStylePacks.discover(root);
        assertTrue(result.assets().isEmpty(),name); assertTrue(result.accepted().isEmpty(),name);
        assertEquals(1,result.errors().size(),name);
    }

    @Test void invalidPacksNeverPartiallyCommitOrReplaceBuiltinIds() throws Exception {
        reject("schema","style-pack.json",d->d.addProperty("schema","unknown"));
        reject("path","style-pack.json",d->d.getAsJsonArray("files").add("../outside.json"));
        reject("duplicate","style-pack.json",d->d.getAsJsonArray("files").add(d.getAsJsonArray("files").get(0)));
        reject("collision","style_profiles/style_profile_catalog_v1.json",d->d.getAsJsonObject("profiles").add("Modern_International",new JsonObject()));
        reject("reference","culture_cards/lake_hall.json",d->d.addProperty("styleId","missing"));
        reject("assembly_reference","culture_cards/lake_hall.json",d->{var refs=new JsonArray();refs.add("../../outside.json");d.add("exampleRefs",refs);});
        reject("roles","style_genomes/lake_hall.json",d->d.getAsJsonObject("palette").remove("door"));
        reject("native","structural_typologies/structural_typologies_v1.json",d->d.getAsJsonArray("typologies").get(0).getAsJsonObject().addProperty("interpreterId","custom_code"));
        reject("alias","style_profiles/style_identity_catalog_v1.json",d->d.getAsJsonObject("lexical_aliases").getAsJsonArray("lake_hall").add("Industrial"));
        reject("unknown_identity","style_profiles/style_identity_catalog_v1.json",d->{var names=new JsonArray();names.add("missing user profile");d.getAsJsonObject("lexical_aliases").add("lake_missing",names);});
        reject("visual_terms","style_visual_cases/catalog_v1.json",d->d.getAsJsonArray("cases").get(0).getAsJsonObject().getAsJsonObject("feature_groups").addProperty("roof","not a term array"));
    }

    @Test void failedDiscoveryDoesNotChangeTheActiveStartupSnapshot() throws Exception {
        reject("invalid","style-pack.json",d->d.addProperty("schema","invalid"));
        assertEquals("lake_hall",StyleIdentityRegistry.canonical("lakeside timber"));
        try(var input=UserStylePacks.open("/assets/formacraft/style_genomes/lake_hall.json")) { assertNotNull(input); }
    }
}
