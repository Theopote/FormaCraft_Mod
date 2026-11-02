package com.formacraft.common.component;

import com.formacraft.common.component.model.ComponentModelApi;
import com.formacraft.common.component.query.ComponentQuery;
import com.formacraft.common.component.transform.*;
import com.formacraft.common.component.variant.CaptureAdaptationSettings;
import com.formacraft.common.json.JsonUtil;
import com.formacraft.common.patch.BlockPatch;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BuildingAssetLocksTest {
    @TempDir Path temporary;
    private Path world;
    private final UUID alice = UUID.randomUUID(), bob = UUID.randomUUID();
    private final ComponentPackageIO.Environment environment = new ComponentPackageIO.Environment("1.21.10",Map.of());
    private ComponentPackageIO.Verified source;
    private String asset;
    @BeforeAll static void bootstrap() { com.formacraft.test.MinecraftRegistryTestBootstrap.initialize(); }
    @BeforeEach void setup() throws Exception {
        ComponentStorage.setGlobalComponentDirOverrideForTest(temporary.resolve("library")); world = Files.createDirectories(temporary.resolve("world"));
        var d = new ComponentDefinition(); d.id = "column"; d.name = "柱"; d.category = ComponentCategory.COLUMN; d.sourceOrigin = "CAPTURED";
        d.size = new ComponentDefinition.Size(); d.size.w = d.size.d = 1; d.size.h = 5;
        d.anchor = new ComponentDefinition.Anchor(); d.anchor.facing = "SOUTH"; d.blocks = new ArrayList<>();
        for (int y = 0; y < 5; y++) { var block = new ComponentDefinition.BlockEntry(); block.dy = y; block.block = "minecraft:stone"; d.blocks.add(block); }
        var settings = new CaptureAdaptationSettings(); settings.modes[1] = CaptureAdaptationSettings.Mode.REPEAT; d.variantRules = settings.toRules(d);
        source = ComponentPackageIO.create(d,new ComponentPackageIO.Manifest("alice","1","Alice","Restricted",ComponentPackageIO.TrainingPermission.DENIED,"1.21.10",Map.of(),Map.of(),List.of()),environment);
        asset = install(source,alice);
    }
    @AfterEach void restore() { ComponentStorage.setGlobalComponentDirOverrideForTest(null); }
    private String install(ComponentPackageIO.Verified pack,UUID player) throws Exception {
        Path file = temporary.resolve(UUID.randomUUID()+".fccomponent.json"); pack.writeTo(file);
        return ComponentPackageInstallations.importPackage(file,environment,ComponentStorage.SaveContext.forPlayer(world,player,true,false)).assetId();
    }
    private record Sample(BuildingAssetCapture.Data assets,List<BlockPatch> patches) {}
    private Sample sample() {
        try (var capture = BuildingAssetCapture.open(world,alice)) {
            var base = ComponentStorage.loadComponent(world,asset,alice); var query = new ComponentQuery(); query.geometry.openingHeight = 7;
            var fitted = ComponentModelApi.fitToQuery(base,query,null); assertNotNull(fitted);
            var patches = ComponentDefinitionCompiler.compile(fitted,Direction.SOUTH,new ComponentTransform(Direction.EAST,Mirror.X),2,3,4,true,"DEFAULT",99);
            return new Sample(capture.data(),patches);
        }
    }
    private String save(Sample sample) throws Exception {
        return BuildingAssetLocks.save(world,alice,"minecraft:overworld",19,10,70,20,"{\"plan\":\"test\"}",sample.assets(),sample.patches());
    }
    private Path directory(String id) { return world.resolve("formacraft/building_assets/players").resolve(alice.toString()).resolve("locks").resolve(id); }
    @Test void recordsOnlyEmittedVisibleAssetsAndRetainsExactFitTransformAndSeed() throws Exception {
        var sample = sample(); assertEquals(1,sample.assets().sources().size()); assertEquals(1,sample.assets().uses().size());
        var use = sample.assets().uses().getFirst(); assertEquals(asset,use.assetId()); assertEquals("EAST",use.facing()); assertEquals("X",use.mirror());
        assertEquals(99,use.seed()); assertEquals(2,use.x()); assertTrue(use.materialRequested()); assertFalse(use.materialApplied());
        var fitted = JsonUtil.fromJson(sample.assets().shapes().get(use.shape()),ComponentDefinition.class); assertEquals(7,fitted.size.h);
        String id = save(sample); var loaded = BuildingAssetLocks.load(world,alice,id,"minecraft:overworld");
        assertEquals(sample.patches(),loaded.patches()); assertEquals(19,loaded.manifest().seed()); assertEquals(70,loaded.manifest().originY());
        assertEquals(source.manifestSha256(),loaded.manifest().dependencies().get(use.snapshot()).manifestSha256());
        assertThrows(UnsupportedOperationException.class,() -> loaded.manifest().dependencies().clear());
        assertThrows(UnsupportedOperationException.class,() -> loaded.patches().clear());
        assertArrayEquals(source.toBytes(),ComponentPackageInstallations.inspect(ComponentStorage.getPlayerComponentDir(alice),asset).toBytes());
    }
    @Test void loadingAndFittingCandidatesDoesNotPublishUnusedDependencies() {
        try (var capture = BuildingAssetCapture.open(world,alice)) {
            var base = ComponentStorage.loadComponent(world,asset,alice); var query = new ComponentQuery(); query.geometry.openingHeight = 7;
            assertNotNull(ComponentModelApi.fitToQuery(base,query,null)); assertTrue(capture.data().uses().isEmpty());
            assertThrows(java.io.IOException.class,() -> BuildingAssetLocks.save(world,alice,"minecraft:overworld",1,0,0,0,"{}",capture.data(),List.of(new BlockPatch("place",0,0,0,"minecraft:stone"))));
            assertFalse(Files.exists(world.resolve("formacraft/building_assets")));
        }
    }
    @Test void scopesAreNestedAndExceptionSafeAndRepeatedUsesShareSourceAndShape() {
        var definition = ComponentStorage.loadComponent(world,asset,alice);
        try (var outer = BuildingAssetCapture.open(world,alice)) {
            ComponentDefinitionCompiler.compile(definition,0,0,0,Direction.SOUTH,Mirror.NONE,false,"DEFAULT",1);
            try (var inner = BuildingAssetCapture.open(world,bob)) {
                ComponentDefinitionCompiler.compile(definition,0,0,0,Direction.SOUTH,Mirror.NONE,false,"DEFAULT",1);
                assertTrue(inner.data().uses().isEmpty());
            }
            assertThrows(IllegalStateException.class,() -> {
                try (var ignored = BuildingAssetCapture.open(world,alice)) { throw new IllegalStateException("test"); }
            });
            ComponentDefinitionCompiler.compile(definition,5,0,0,Direction.SOUTH,Mirror.NONE,false,"DEFAULT",2);
            assertEquals(2,outer.data().uses().size()); assertEquals(1,outer.data().sources().size()); assertEquals(1,outer.data().shapes().size());
        }
        var uncaptured = ComponentDefinitionCompiler.compile(definition,0,0,0,Direction.SOUTH,Mirror.NONE,false,"DEFAULT",1); assertFalse(uncaptured.isEmpty());
        try (var fresh = BuildingAssetCapture.open(world,alice)) { assertTrue(fresh.data().uses().isEmpty()); }
    }
    @Test void deletedLibraryAssetStillReplaysAndRestoreOnlyPublishesPrivateDependencies() throws Exception {
        var sample = sample(); String id = save(sample);
        ComponentLibraryOperations.delete(ComponentStorage.SaveContext.forPlayer(world,alice,true,false),asset,source.contentRef());
        assertNull(ComponentStorage.loadComponent(world,asset,alice));
        assertEquals(sample.patches(),BuildingAssetLocks.load(world,alice,id,"minecraft:overworld").patches());
        var restored = BuildingAssetLocks.restore(world,alice,id,"minecraft:overworld"); assertTrue(restored.complete());
        String actual = restored.aliases().get(asset); assertNotNull(actual); assertNotEquals(asset,actual);
        assertNotNull(ComponentStorage.loadComponent(world,actual,alice)); assertNull(ComponentStorage.loadComponent(world,actual,bob));
        assertEquals(ComponentPackageIO.TrainingPermission.DENIED,ComponentPackageInstallations.inspect(ComponentStorage.getPlayerComponentDir(alice),actual).manifest().trainingPermission());
        assertEquals(actual,BuildingAssetLocks.restore(world,alice,id,"minecraft:overworld").aliases().get(asset));
    }
    @Test void deletionAfterCompileCannotInvalidateTheCapturedImmutableSourceBeforePublication() throws Exception {
        var sample = sample(); ComponentLibraryOperations.delete(ComponentStorage.SaveContext.forPlayer(world,alice,true,false),asset,source.contentRef());
        String id = save(sample);
        assertEquals(sample.patches(),BuildingAssetLocks.load(world,alice,id,"minecraft:overworld").patches());
    }
    @Test void samePayloadWithDifferentRightsRemainsTwoLocksAndRestoresAsIndependentSources() throws Exception {
        var original = source.manifest();
        var alternate = ComponentPackageIO.create(source.component(),new ComponentPackageIO.Manifest(original.namespace(),original.version(),original.author(),"Alternate",
                ComponentPackageIO.TrainingPermission.ALLOWED,original.minecraftVersion(),original.requiredMods(),original.blockNamespaceOwners(),original.derivedFrom()),environment);
        Path file = temporary.resolve("alternate.fccomponent.json"); alternate.writeTo(file);
        String other = ComponentPackageInstallations.importPackage(file,environment,ComponentStorage.SaveContext.global(world)).assetId();
        BuildingAssetCapture.Data data; var patches = new ArrayList<BlockPatch>();
        try (var capture = BuildingAssetCapture.open(world,alice)) {
            for (String id : List.of(asset,other)) patches.addAll(ComponentDefinitionCompiler.compile(ComponentStorage.loadComponent(world,id,alice),
                    patches.size(),0,0,Direction.SOUTH,Mirror.NONE,false,"DEFAULT",1));
            data = capture.data();
        }
        assertEquals(2,data.sources().size()); assertEquals(source.sha256(),alternate.sha256());
        String id = save(new Sample(data,patches));
        ComponentLibraryOperations.delete(ComponentStorage.SaveContext.forPlayer(world,alice,true,true),asset,source.contentRef());
        ComponentLibraryOperations.delete(ComponentStorage.SaveContext.forPlayer(world,alice,true,true),other,alternate.contentRef());
        var restored = BuildingAssetLocks.restore(world,alice,id,"minecraft:overworld"); assertTrue(restored.complete()); assertEquals(2,restored.aliases().size());
        assertEquals(restored.aliases().get(asset),restored.aliases().get(other));
        var sources = ComponentPackageInstallations.sources(ComponentStorage.getPlayerComponentDir(alice),restored.aliases().get(asset));
        assertEquals(Set.of("Restricted","Alternate"),sources.stream().map(s -> s.manifest().license()).collect(java.util.stream.Collectors.toSet()));
        assertEquals(Set.of(ComponentPackageIO.TrainingPermission.ALLOWED,ComponentPackageIO.TrainingPermission.DENIED),sources.stream().map(s -> s.manifest().trainingPermission()).collect(java.util.stream.Collectors.toSet()));
        assertTrue(ComponentStorage.loadCatalog(world,bob).components.isEmpty());
    }
    @Test void ownerDimensionAndPathAreBoundAndUnknownOwnerListDoesNotCreateDirectories() throws Exception {
        String id = save(sample());
        assertThrows(java.io.IOException.class,() -> BuildingAssetLocks.load(world,bob,id,"minecraft:overworld"));
        assertThrows(java.io.IOException.class,() -> BuildingAssetLocks.load(world,alice,id,"minecraft:the_nether"));
        assertThrows(java.io.IOException.class,() -> BuildingAssetLocks.load(world,alice,"../escape","minecraft:overworld"));
        assertTrue(BuildingAssetLocks.list(world,bob,0).isEmpty()); assertEquals(1,BuildingAssetLocks.list(world,alice,0).size());
    }
    @Test void corruptPatchPlanAndSnapshotFailWithoutRecompilingOrChangingLibrary() throws Exception {
        var sample = sample(); String id = save(sample()); Path dir = directory(id);
        byte[] patches = Files.readAllBytes(dir.resolve("patches.json.gz")); Files.write(dir.resolve("patches.json.gz"),new byte[]{0,1});
        assertThrows(java.io.IOException.class,() -> BuildingAssetLocks.load(world,alice,id,"minecraft:overworld")); Files.write(dir.resolve("patches.json.gz"),patches);
        byte[] plan = Files.readAllBytes(dir.resolve("input-plan.json")); Files.write(dir.resolve("input-plan.json"),new byte[]{(byte)0xff});
        assertThrows(java.io.IOException.class,() -> BuildingAssetLocks.load(world,alice,id,"minecraft:overworld")); Files.write(dir.resolve("input-plan.json"),plan);
        Path snapshot = dir.getParent().getParent().resolve("snapshots").resolve(ComponentAssetSnapshots.key(source)).resolve("source.fccomponent.json");
        Files.writeString(snapshot,"broken"); assertThrows(java.io.IOException.class,() -> BuildingAssetLocks.restore(world,alice,id,"minecraft:overworld"));
        assertNotNull(ComponentStorage.loadComponent(world,asset,alice)); assertEquals(sample.patches().size(),7);
    }
    @Test void publicationFailureLeavesNoVisibleLockAndBoundsRejectWithoutTruncation() throws Exception {
        var sample = sample();
        assertThrows(java.io.IOException.class,() -> BuildingAssetLocks.save(world,alice,"minecraft:overworld",1,0,0,0,"{}",sample.assets(),sample.patches(),staging -> { throw new java.io.IOException("fault"); }));
        assertTrue(BuildingAssetLocks.list(world,alice,0).isEmpty());
        assertThrows(java.io.IOException.class,() -> BuildingAssetLocks.save(world,alice,"minecraft:overworld",1,0,0,0,"{}",sample.assets(),List.of(new BlockPatch("unknown",0,0,0,"minecraft:stone"))));
        try (var limited = new BuildingAssetLocks.LimitedInput(new java.io.ByteArrayInputStream(new byte[32]),8)) {
            assertThrows(java.io.IOException.class,limited::readAllBytes);
        }
        try (var limited = new BuildingAssetLocks.LimitedOutput(new java.io.ByteArrayOutputStream(),8)) {
            assertThrows(java.io.IOException.class,() -> limited.write(new byte[9]));
        }
    }
}
