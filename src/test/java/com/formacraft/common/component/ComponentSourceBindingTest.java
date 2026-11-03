package com.formacraft.common.component;

import com.formacraft.common.component.community.*;
import com.formacraft.common.component.model.ComponentModelApi;
import com.formacraft.common.component.query.ComponentQuery;
import com.formacraft.common.component.transform.*;
import com.formacraft.common.component.variant.CaptureAdaptationSettings;
import com.formacraft.common.json.JsonUtil;
import com.formacraft.common.patch.BlockPatch;
import com.google.gson.*;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ComponentSourceBindingTest {
    @TempDir Path temp;
    private Path world, cache;
    private final UUID alice = UUID.randomUUID(), bob = UUID.randomUUID();
    private final ComponentPackageIO.Environment environment = new ComponentPackageIO.Environment("1.21.10",Map.of());
    private ComponentPackageIO.Verified a, b;
    private String asset, receiptId;
    private byte[] raw;
    private ComponentSourceBinding.Selection selected;
    @BeforeAll static void bootstrap() { com.formacraft.test.MinecraftRegistryTestBootstrap.initialize(); }
    @BeforeEach void setup() throws Exception {
        ComponentStorage.setGlobalComponentDirOverrideForTest(temp.resolve("library"));
        world = Files.createDirectories(temp.resolve("world")); cache = temp.resolve("downloads");
        a = pack("alice", "A license", ComponentPackageIO.TrainingPermission.ALLOWED);
        b = pack("bob", "B restricted", ComponentPackageIO.TrainingPermission.DENIED);
        asset = ComponentPackageInstallations.importVerified(a,context(alice)).assetId();
        receiptId = cache(b,"rel_b"); selected = ComponentSourceBinding.install(cache,receiptId,context(alice));
    }
    @AfterEach void reset() { ComponentStorage.setGlobalComponentDirOverrideForTest(null); }
    private ComponentStorage.SaveContext context(UUID player) { return ComponentStorage.SaveContext.forPlayer(world,player,true,false); }
    private ComponentPackageIO.Verified pack(String publisher,String license,ComponentPackageIO.TrainingPermission training) throws Exception {
        var def = new ComponentDefinition(); def.id = "column"; def.name = publisher; def.category = ComponentCategory.COLUMN; def.sourceOrigin = "CAPTURED";
        def.tags = List.of("style:"+publisher); def.size = new ComponentDefinition.Size(); def.size.w = def.size.d = 1; def.size.h = 5;
        def.anchor = new ComponentDefinition.Anchor(); def.anchor.facing = "SOUTH"; def.blocks = new ArrayList<>();
        for (int y = 0; y < 5; y++) { var block = new ComponentDefinition.BlockEntry(); block.dy = y; block.block = "minecraft:stone"; def.blocks.add(block); }
        var settings = new CaptureAdaptationSettings(); settings.modes[1] = CaptureAdaptationSettings.Mode.REPEAT; def.variantRules = settings.toRules(def);
        return ComponentPackageIO.create(def,new ComponentPackageIO.Manifest(publisher,"1",publisher,license,training,"1.21.10",Map.of(),Map.of(),List.of()),environment);
    }
    private String cache(ComponentPackageIO.Verified pack,String release) throws Exception {
        raw = (" \n"+new String(pack.toBytes(),StandardCharsets.UTF_8)+"\n ").getBytes(StandardCharsets.UTF_8);
        var ref = new CommunityPackages.ReleaseRef("test","res_b",release,CommunityPackages.sha256(raw),pack.sha256(),pack.manifestSha256());
        var descriptor = new CommunityPackages.Descriptor(ref,raw.length,ComponentPackageIO.SCHEMA,ComponentContentIdentity.fingerprints(pack.component()));
        var index = new OfflineCommunityIndex("test",Clock.systemUTC()); index.add(descriptor,raw);
        var details = CommunityReleaseDetails.decode(index.releaseDetails(ref),descriptor);
        return CommunityDownloadCache.save(cache,new CommunitySearchHttpClient.Fetched(details,index.download(ref,environment)),environment,Clock.systemUTC());
    }
    private ComponentQuery height(int height) { var q = new ComponentQuery(); q.geometry.openingHeight = height; q.geometry.tolerance = 0; return q; }
    private List<BlockPatch> compile(ComponentSourceBinding.Prepared prepared,int x) throws Exception {
        return prepared.compile(world,alice,Direction.SOUTH,new ComponentTransform(Direction.EAST,Mirror.X),x,2,3,true,"DEFAULT",19);
    }
    private record Sample(BuildingAssetCapture.Data assets,List<BlockPatch> patches) {}
    private Sample sample() throws Exception {
        try (var capture = BuildingAssetCapture.open(world,alice)) {
            var patches = compile(selected.prepare(world,alice,height(7)),4); return new Sample(capture.data(),patches);
        }
    }
    private String save(Sample sample) throws Exception {
        return BuildingAssetLocks.save(world,alice,"minecraft:overworld",7,0,70,0,"{}",sample.assets(),sample.patches());
    }
    private Path lockVault() { return world.resolve("formacraft/building_assets/players").resolve(alice.toString()); }
    private Path recipeVault() { return ComponentStorage.getPlayerComponentDir(alice); }
    private Path rawFile(Path root) { return root.resolve("objects").resolve(selected.receipt().details().descriptor().releaseRef().packageSha256()).resolve("original.fccomponent.json"); }

    @Test void privateDedupKeepsSelectedSourceWithoutChangingPrimaryMetadataOrRights() throws Exception {
        assertEquals(asset,selected.assetId()); assertEquals(ComponentPackageInstallations.ImportStatus.SOURCE_ADDED,selected.status());
        var primary = ComponentPackageInstallations.inspect(ComponentStorage.getPlayerComponentDir(alice),asset);
        assertEquals(a.contentRef(),primary.contentRef()); assertEquals("A license",primary.manifest().license());
        assertEquals(List.of("style:alice"),primary.component().tags);
        assertEquals("B restricted",selected.source().manifest().license()); assertEquals(ComponentPackageIO.TrainingPermission.DENIED,selected.source().manifest().trainingPermission());
        var definition = selected.prepare(world,alice,height(7)).definition();
        assertEquals(asset,definition.id); assertEquals(7,definition.size.h); assertEquals(List.of("style:bob"),definition.tags);
        assertEquals(2,ComponentPackageInstallations.sources(ComponentStorage.getPlayerComponentDir(alice),asset).size());
        assertTrue(ComponentPackageInstallations.entries(ComponentStorage.getGlobalComponentDir()).isEmpty());
    }

    @Test void interleavedOrdinaryAndSelectedEmissionNeverReplacesThePrimaryCache() throws Exception {
        try (var capture = BuildingAssetCapture.open(world,alice)) {
            var base = ComponentStorage.loadComponent(world,asset,alice);
            ComponentDefinitionCompiler.compile(base,0,0,0,Direction.SOUTH,Mirror.NONE,false,null,1);
            compile(selected.prepare(world,alice,height(7)),10);
            ComponentDefinitionCompiler.compile(base,20,0,0,Direction.SOUTH,Mirror.NONE,false,null,2);
            var data = capture.data(); assertEquals(3,data.uses().size());
            assertEquals(ComponentAssetSnapshots.key(a),data.uses().get(0).snapshot());
            assertEquals(ComponentAssetSnapshots.key(b),data.uses().get(1).snapshot()); assertEquals(receiptId,data.uses().get(1).receiptId());
            assertEquals(ComponentAssetSnapshots.key(a),data.uses().get(2).snapshot()); assertNull(data.uses().get(2).receiptId());
            assertEquals(Set.of(receiptId),data.receipts().keySet()); assertEquals(2,data.sources().size());
        }
    }

    @Test void captureAndLockPinBOriginalBytesAndSurviveRemovedDownloadCacheAndLocalAlias() throws Exception {
        var sample = sample(); assertEquals(Set.of(ComponentAssetSnapshots.key(b)),sample.assets().sources().keySet());
        String id = save(sample);
        assertArrayEquals(raw,Files.readAllBytes(rawFile(lockVault().resolve("community_downloads"))));
        Files.move(cache,temp.resolve("retired_downloads")); ComponentLibraryOperations.delete(context(alice),asset,a.contentRef());
        var locked = BuildingAssetLocks.load(world,alice,id,"minecraft:overworld");
        assertEquals(ComponentAssetSnapshots.key(b),locked.manifest().communityReceipts().get(receiptId));
        assertEquals(sample.patches(),locked.patches());
        var restored = BuildingAssetLocks.restore(world,alice,id,"minecraft:overworld");
        assertTrue(restored.complete()); assertTrue(restored.aliases().isEmpty()); assertEquals(1,restored.selections().size());
        var restoredB = restored.selections().get(0); assertEquals(b.contentRef(),restoredB.source().contentRef());
        assertEquals(7,restoredB.prepare(world,alice,height(7)).definition().size.h); assertNull(ComponentStorage.loadComponent(world,restoredB.assetId(),bob));
    }

    @Test void selectedRecipeUsesBPinsReceiptAndEditingRetainsIt() throws Exception {
        var saved = ComponentRecipeLibrary.saveSelected(world,alice,selected,new ComponentRecipeLibrary.Spec("B favorite",1,7,1));
        var result = ComponentRecipeLibrary.result(alice,saved.assetId());
        assertEquals("B restricted",result.manifest().license()); assertEquals(ComponentPackageIO.TrainingPermission.DENIED,result.manifest().trainingPermission());
        assertTrue(result.manifest().derivedFrom().contains(b.contentRef()+"|manifest:"+b.manifestSha256()));
        assertArrayEquals(raw,Files.readAllBytes(rawFile(recipeVault().resolve("community_downloads"))));
        Files.move(cache,temp.resolve("retired_downloads"));
        var edited = ComponentRecipeLibrary.save(world,alice,saved.assetId(),result.contentRef(),new ComponentRecipeLibrary.Spec("Edited B",1,8,1));
        assertEquals("B restricted",ComponentRecipeLibrary.result(alice,edited.assetId()).manifest().license()); ComponentRecipeLibrary.replay(alice,edited.assetId());
        assertThrows(IOException.class,() -> ComponentRecipeLibrary.result(bob,saved.assetId()));
        var restored = ComponentRecipeLibrary.restoreSelected(context(alice),saved.assetId()); assertEquals(b.contentRef(),restored.source().contentRef());
        assertThrows(IOException.class,() -> ComponentRecipeLibrary.restore(context(alice),saved.assetId()));
        assertEquals(saved.assetId(),ComponentRecipeLibrary.saveSelected(world,alice,restored,new ComponentRecipeLibrary.Spec("Renamed",1,7,1)).assetId());
    }

    @Test void ordinaryFavoriteLoadingStillCarriesPinnedCommunityEvidenceIntoCapture() throws Exception {
        var saved = ComponentRecipeLibrary.saveSelected(world,alice,selected,new ComponentRecipeLibrary.Spec("B favorite",1,7,1));
        try (var capture = BuildingAssetCapture.open(world,alice)) {
            var definition = ComponentStorage.loadComponent(world,saved.assetId(),alice);
            var patches = ComponentDefinitionCompiler.compile(definition,0,0,0,Direction.SOUTH,Mirror.NONE,false,null,3);
            var data = capture.data(); assertEquals(ComponentAssetSnapshots.key(b),data.uses().getFirst().snapshot());
            assertEquals(receiptId,data.uses().getFirst().receiptId());
            String id = save(new Sample(data,patches)); assertEquals(receiptId,BuildingAssetLocks.load(world,alice,id,"minecraft:overworld").manifest().uses().getFirst().receiptId());
        }
    }

    @Test void sameOriginalInDifferentCommunityReleasesGetsDistinctRecipesAndUseBindings() throws Exception {
        String secondId = cache(b,"rel_other"); var other = ComponentSourceBinding.install(cache,secondId,context(alice));
        var one = ComponentRecipeLibrary.saveSelected(world,alice,selected,new ComponentRecipeLibrary.Spec("One",1,7,1));
        var two = ComponentRecipeLibrary.saveSelected(world,alice,other,new ComponentRecipeLibrary.Spec("Two",1,7,1)); assertNotEquals(one.assetId(),two.assetId());
        try (var capture = BuildingAssetCapture.open(world,alice)) {
            var patches = new ArrayList<>(compile(selected.prepare(world,alice,height(7)),0)); patches.addAll(compile(other.prepare(world,alice,height(7)),10));
            String id = save(new Sample(capture.data(),patches)); var restored = BuildingAssetLocks.restore(world,alice,id,"minecraft:overworld");
            assertEquals(receiptId,restored.selections().get(0).receipt().receiptId()); assertEquals(secondId,restored.selections().get(1).receipt().receiptId());
            assertEquals(restored.selections().get(0).assetId(),restored.selections().get(1).assetId()); assertTrue(restored.aliases().isEmpty());
        }
    }

    @Test void wrongActorWorldAndMutatedPreparedDefinitionCannotRecordABinding() throws Exception {
        assertThrows(IOException.class,() -> selected.prepare(world,bob,height(7)));
        assertThrows(IOException.class,() -> selected.prepare(temp.resolve("other_world"),alice,height(7)));
        var prepared = selected.prepare(world,alice,height(7)); prepared.definition().blocks.getFirst().block = "minecraft:glass";
        try (var capture = BuildingAssetCapture.open(world,alice)) {
            assertThrows(IOException.class,() -> compile(prepared,0)); assertTrue(capture.data().uses().isEmpty());
        }
        var valid = selected.prepare(world,alice,height(7));
        try (var sourceScope = ComponentSourceBinding.open(List.of(valid)); var capture = BuildingAssetCapture.open(world,bob)) {
            assertThrows(IllegalStateException.class,() -> ComponentDefinitionCompiler.compile(valid.definition(),0,0,0,Direction.SOUTH,Mirror.NONE,false,null,1));
            assertTrue(capture.data().uses().isEmpty());
        }
    }

    @Test void missingAliasAndInvalidAuthorFitNeverFallBackToAnotherSource() throws Exception {
        var invalid = new ComponentQuery(); invalid.geometry.openingWidth = 2; invalid.geometry.tolerance = 0;
        assertThrows(IOException.class,() -> selected.prepare(world,alice,invalid));
        ComponentLibraryOperations.delete(context(alice),asset,a.contentRef());
        assertThrows(IOException.class,() -> selected.prepare(world,alice,height(7)));
        assertThrows(IOException.class,() -> ComponentRecipeLibrary.saveSelected(world,alice,selected,new ComponentRecipeLibrary.Spec("No fallback",1,7,1)));
        assertTrue(ComponentRecipeLibrary.entries(alice).isEmpty());
    }

    @Test void corruptPinnedEvidenceFailsRatherThanUsingAOrTheOriginalCache() throws Exception {
        String id = save(sample()); Path rawPin = rawFile(lockVault().resolve("community_downloads")); Files.writeString(rawPin,"corrupt");
        assertThrows(IOException.class,() -> BuildingAssetLocks.load(world,alice,id,"minecraft:overworld"));
        var recipe = ComponentRecipeLibrary.saveSelected(world,alice,selected,new ComponentRecipeLibrary.Spec("B recipe",1,7,1));
        Files.writeString(rawFile(recipeVault().resolve("community_downloads")),"corrupt");
        assertThrows(IOException.class,() -> ComponentRecipeLibrary.result(alice,recipe.assetId()));
        assertEquals(a.contentRef(),ComponentPackageInstallations.inspect(ComponentStorage.getPlayerComponentDir(alice),asset).contentRef());
    }

    @Test void malformedOrMissingReceiptUseIsRejectedBeforeALockIsPublished() throws Exception {
        var sample = sample(); var data = sample.assets();
        var missing = new BuildingAssetCapture.Data(data.sources(),data.shapes(),data.uses());
        assertThrows(IOException.class,() -> save(new Sample(missing,sample.patches())));
        assertTrue(BuildingAssetLocks.list(world,alice,0).isEmpty());
        String id = save(sample); Path path = lockVault().resolve("locks").resolve(id).resolve("manifest.json");
        var root = JsonParser.parseString(Files.readString(path)).getAsJsonObject(); root.getAsJsonArray("uses").get(0).getAsJsonObject().addProperty("receiptId","receipt_"+"0".repeat(64));
        Files.writeString(path,root.toString()); assertThrows(IOException.class,() -> BuildingAssetLocks.load(world,alice,id,"minecraft:overworld"));
    }

    @Test void manualPlacementCompilesChosenSourceWithoutUsingTheDeduplicatedPrimary() throws Exception {
        var compiled=SelectedComponentPlacement.compile(world,alice,selected,asset,Direction.EAST,Mirror.X,false,null,42);
        assertEquals(receiptId,compiled.receiptId()); assertEquals(b.contentRef(),compiled.contentRef()); assertEquals(b.manifestSha256(),compiled.manifestSha256());
        assertEquals(5,compiled.patches().size()); assertThrows(UnsupportedOperationException.class,()->compiled.patches().clear());
        assertThrows(IOException.class,()->SelectedComponentPlacement.compile(world,alice,selected,"different",Direction.SOUTH,Mirror.NONE,false,null,1));
        assertThrows(IOException.class,()->SelectedComponentPlacement.compile(world,bob,selected,asset,Direction.SOUTH,Mirror.NONE,false,null,1));
        assertThrows(IOException.class,()->SelectedComponentPlacement.compile(world,alice,selected,asset,Direction.UP,Mirror.NONE,false,null,1));
        assertEquals("alice",ComponentPackageInstallations.inspect(ComponentStorage.getPlayerComponentDir(alice),asset).manifest().author());
    }
    @Test void scopesAreNestedExceptionSafeAndSelectionsDoNotBecomeDefaultSources() throws Exception {
        var prepared = selected.prepare(world,alice,height(7));
        try (var capture = BuildingAssetCapture.open(world,alice)) {
            var outer = ComponentSourceBinding.open(List.of(prepared)); var inner = ComponentSourceBinding.open(List.of());
            assertThrows(IllegalStateException.class,outer::close); inner.close();
            ComponentDefinitionCompiler.compile(prepared.definition(),0,0,0,Direction.SOUTH,Mirror.NONE,false,null,1); outer.close();
            var base = ComponentStorage.loadComponent(world,asset,alice); ComponentDefinitionCompiler.compile(base,10,0,0,Direction.SOUTH,Mirror.NONE,false,null,1);
            assertEquals(receiptId,capture.data().uses().get(0).receiptId()); assertNull(capture.data().uses().get(1).receiptId());
            assertEquals(ComponentAssetSnapshots.key(a),capture.data().uses().get(1).snapshot());
        }
    }
}
