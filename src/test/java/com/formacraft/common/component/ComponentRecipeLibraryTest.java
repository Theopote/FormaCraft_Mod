package com.formacraft.common.component;

import com.formacraft.common.component.variant.CaptureAdaptationSettings;
import com.formacraft.common.json.JsonUtil;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ComponentRecipeLibraryTest {
    @TempDir Path temporary;
    private final UUID alice = UUID.randomUUID(), bob = UUID.randomUUID();
    private final ComponentPackageIO.Environment environment = new ComponentPackageIO.Environment("1.21.10",Map.of());
    @BeforeAll static void bootstrap() { com.formacraft.test.MinecraftRegistryTestBootstrap.initialize(); }
    @BeforeEach void setup() { ComponentStorage.setGlobalComponentDirOverrideForTest(temporary.resolve("library")); }
    @AfterEach void restore() { ComponentStorage.setGlobalComponentDirOverrideForTest(null); }
    private ComponentPackageIO.Verified source(String license) throws Exception {
        var d = new ComponentDefinition(); d.id = "railing"; d.name = "栏杆"; d.sourceOrigin = "CAPTURED"; d.category = ComponentCategory.RAILING;
        d.size = new ComponentDefinition.Size(); d.size.w = 5; d.size.h = d.size.d = 1;
        d.anchor = new ComponentDefinition.Anchor(); d.anchor.facing = "SOUTH";
        d.blocks = new ArrayList<>();
        for (int x = 0; x < 5; x++) { var b = new ComponentDefinition.BlockEntry(); b.dx = x; b.block = "minecraft:stone"; d.blocks.add(b); }
        var settings = new CaptureAdaptationSettings(); settings.modes[0] = CaptureAdaptationSettings.Mode.REPEAT; d.variantRules = settings.toRules(d);
        return ComponentPackageIO.create(d,new ComponentPackageIO.Manifest("alice","1","Alice",license,ComponentPackageIO.TrainingPermission.DENIED,
                "1.21.10",Map.of(),Map.of(),List.of()),environment);
    }
    private String install(ComponentPackageIO.Verified pack,ComponentStorage.SaveContext context) throws Exception {
        Path file = temporary.resolve(UUID.randomUUID()+".fccomponent.json"); pack.writeTo(file);
        return ComponentPackageInstallations.importPackage(file,environment,context).assetId();
    }
    private ComponentRecipeLibrary.Saved save(String id,ComponentPackageIO.Verified pack,int width) throws Exception {
        return ComponentRecipeLibrary.save(null,alice,id,pack.contentRef(),new ComponentRecipeLibrary.Spec("栏杆配方",width,1,1));
    }
    @Test void sizeRecipeUsesAuthorRulesPreservesOriginalAndIsPrivate() throws Exception {
        var pack = source("Restricted"); String id = install(pack,ComponentStorage.SaveContext.global(null));
        byte[] before = Files.readAllBytes(ComponentStorage.getGlobalComponentDir().resolve("packages").resolve(id).resolve("source.fccomponent.json"));
        var saved = save(id,pack,7); var result = ComponentRecipeLibrary.result(alice,saved.assetId());
        assertEquals(7,result.component().size.w); assertEquals(7,result.component().blocks.size());
        assertEquals("FIXED",result.component().variantRules.scaling.axes.get("X").type);
        assertEquals("REPEAT",pack.component().variantRules.scaling.axes.get("X").type);
        assertArrayEquals(before,Files.readAllBytes(ComponentStorage.getGlobalComponentDir().resolve("packages").resolve(id).resolve("source.fccomponent.json")));
        assertEquals("Restricted",result.manifest().license()); assertEquals(ComponentPackageIO.TrainingPermission.DENIED,result.manifest().trainingPermission());
        assertTrue(result.manifest().derivedFrom().contains(pack.contentRef()+"|manifest:"+pack.manifestSha256()));
        assertEquals(1,ComponentRecipeLibrary.entries(alice).size()); assertTrue(ComponentRecipeLibrary.entries(bob).isEmpty());
        assertTrue(ComponentRecipeLibrary.entries(alice).getFirst().recipeSourceRef.startsWith(pack.contentRef()));
        assertTrue(ComponentStorage.loadCatalog(null,alice).components.stream().anyMatch(e -> e.recipe && e.id.equals(saved.assetId())));
        assertFalse(ComponentStorage.loadCatalog(null,bob).components.stream().anyMatch(e -> e.id.equals(saved.assetId())));
        assertNull(ComponentStorage.loadComponent(null,saved.assetId(),bob));
        assertEquals(7,ComponentStorage.loadComponent(null,saved.assetId(),alice).size.w);
        assertNull(ComponentSourceView.query(null,alice,saved.assetId(),0).error());
        var spoof = pack.component(); spoof.id = saved.assetId();
        assertThrows(IllegalArgumentException.class,() -> ComponentStorage.saveComponentWithContext(ComponentStorage.SaveContext.global(null),spoof));
        Path alias = temporary.resolve("alias.fccomponent.json"); pack.writeTo(alias);
        assertThrows(java.io.IOException.class,() -> ComponentPackageInstallations.install(alias,environment,ComponentStorage.SaveContext.global(null),saved.assetId()));
    }
    @Test void namesAreNotRecipeIdentityAndInvalidTargetsPublishNothing() throws Exception {
        var pack = source("Test"); String id = install(pack,ComponentStorage.SaveContext.global(null)); var first = save(id,pack,7);
        var repeated = ComponentRecipeLibrary.save(null,alice,id,pack.contentRef(),new ComponentRecipeLibrary.Spec("Other name",7,1,1));
        assertEquals(first.assetId(),repeated.assetId()); assertEquals(ComponentPackageInstallations.ImportStatus.REUSED,repeated.status()); assertEquals(first.name(),repeated.name());
        var firstResult = ComponentRecipeLibrary.result(alice,first.assetId());
        assertEquals(id,ComponentRecipeLibrary.restore(ComponentStorage.SaveContext.forPlayer(null,alice,true,true),first.assetId()).assetId());
        assertEquals(save(id,pack,8).assetId(),ComponentRecipeLibrary.save(null,alice,first.assetId(),firstResult.contentRef(),new ComponentRecipeLibrary.Spec("Edited favorite",8,1,1)).assetId());
        assertThrows(java.io.IOException.class,() -> ComponentRecipeLibrary.save(null,alice,id,pack.contentRef(),new ComponentRecipeLibrary.Spec("Fixed Y",7,2,1)));
        assertThrows(java.io.IOException.class,() -> save(id,pack,21));
        assertThrows(java.io.IOException.class,() -> ComponentRecipeLibrary.save(null,alice,id,"stale",new ComponentRecipeLibrary.Spec("Stale",7,1,1)));
        assertEquals(2,ComponentRecipeLibrary.entries(alice).size());
    }
    @Test void deletedOriginalStillLoadsReplaysAndRestoresUsingNormalPrivatePermissions() throws Exception {
        var pack = source("Test"); String id = install(pack,ComponentStorage.SaveContext.global(null)); var saved = save(id,pack,7);
        ComponentLibraryOperations.delete(ComponentStorage.SaveContext.forPlayer(null,alice,true,true),id,pack.contentRef());
        assertNull(ComponentStorage.loadComponent(null,id,alice)); ComponentRecipeLibrary.replay(alice,saved.assetId());
        assertEquals(7,ComponentStorage.loadComponent(null,saved.assetId(),alice).size.w);
        var restored = ComponentRecipeLibrary.restore(ComponentStorage.SaveContext.forPlayer(null,alice,true,true),saved.assetId());
        assertEquals(5,ComponentStorage.loadComponent(null,restored.assetId(),alice).size.w);
        assertNull(ComponentStorage.loadComponent(null,restored.assetId(),bob));
        assertArrayEquals(pack.toBytes(),ComponentPackageInstallations.inspect(ComponentStorage.getPlayerComponentDir(alice),restored.assetId()).toBytes());
    }
    @Test void snapshotsBindRightsAndCannotBeEscapedOrTakenFromAnotherPlayer() throws Exception {
        var first = source("First"); var second = source("Second");
        String a = install(first,ComponentStorage.SaveContext.global(null));
        String b = install(second,ComponentStorage.SaveContext.forPlayer(null,alice,true,false));
        var ra = save(a,first,7); var rb = save(b,second,7); assertNotEquals(ra.assetId(),rb.assetId());
        assertEquals("First",ComponentRecipeLibrary.result(alice,ra.assetId()).manifest().license());
        assertEquals("Second",ComponentRecipeLibrary.result(alice,rb.assetId()).manifest().license());
        assertThrows(java.io.IOException.class,() -> ComponentRecipeLibrary.result(bob,ra.assetId()));
        assertThrows(java.io.IOException.class,() -> ComponentAssetSnapshots.read(ComponentStorage.getPlayerComponentDir(alice),"../escape"));
        assertThrows(java.io.IOException.class,() -> ComponentLibraryOperations.export(null,bob,ra.assetId(),"anything",0,"anything"));
    }
    @Test void deletingFavoriteKeepsSharedSnapshotAndOtherRecipeAndExportedResultIsOrdinaryPortableAsset() throws Exception {
        var pack = source("Test"); String id = install(pack,ComponentStorage.SaveContext.global(null));
        var first = save(id,pack,7); var second = save(id,pack,8);
        var result = ComponentRecipeLibrary.result(alice,first.assetId());
        var exported = ComponentLibraryOperations.export(null,alice,first.assetId(),result.contentRef(),0,result.contentRef());
        String imported = install(exported,ComponentStorage.SaveContext.global(null)); assertEquals(7,ComponentStorage.loadComponent(null,imported).size.w);
        ComponentLibraryOperations.delete(ComponentStorage.SaveContext.forPlayer(null,alice,true,false),first.assetId(),result.contentRef());
        assertThrows(java.io.IOException.class,() -> ComponentRecipeLibrary.result(alice,first.assetId()));
        ComponentRecipeLibrary.replay(alice,second.assetId());
        assertArrayEquals(pack.toBytes(),ComponentAssetSnapshots.read(ComponentStorage.getPlayerComponentDir(alice),ComponentAssetSnapshots.key(pack)).toBytes());
    }
    @Test void corruptionIsRejectedAndDoesNotRewriteOriginalOrResult() throws Exception {
        var pack = source("Test"); String id = install(pack,ComponentStorage.SaveContext.global(null)); var saved = save(id,pack,7);
        Path library = ComponentStorage.getPlayerComponentDir(alice);
        Path output = library.resolve("recipes").resolve(saved.assetId()).resolve("result.fccomponent.json"); byte[] resultBytes = Files.readAllBytes(output);
        Files.writeString(output,"broken"); assertThrows(java.io.IOException.class,() -> ComponentRecipeLibrary.result(alice,saved.assetId()));
        assertTrue(ComponentRecipeLibrary.entries(alice).isEmpty()); Files.write(output,resultBytes);
        Path snapshot = library.resolve("snapshots").resolve(ComponentAssetSnapshots.key(pack)).resolve("source.fccomponent.json");
        Files.writeString(snapshot,"broken"); assertThrows(java.io.IOException.class,() -> ComponentRecipeLibrary.replay(alice,saved.assetId()));
        assertArrayEquals(resultBytes,Files.readAllBytes(output)); assertNotNull(ComponentStorage.loadComponent(null,id,alice));
    }
    @Test void snapshotOnlyInspectionCannotBeUsedAsVerifiedLock() throws Exception {
        var pack = source("Test"); Path library = temporary.resolve("snapshots_only"); Files.createDirectories(library);
        assertThrows(java.io.IOException.class,() -> ComponentAssetSnapshots.save(library,ComponentPackageIO.inspectBytes(pack.toBytes())));
        String key = ComponentAssetSnapshots.save(library,pack); assertEquals(key,ComponentAssetSnapshots.save(library,pack));
        assertEquals(pack.manifestSha256(),ComponentAssetSnapshots.read(library,key).manifestSha256());
    }
    @Test void captureWithoutAuthorRulesCannotGainScalingOrMaterialPermissionThroughFavorites() throws Exception {
        var allowed = source("Test"); var definition = allowed.component(); definition.variantRules = null;
        definition.blocks.forEach(b -> b.block = "minecraft:diamond_block");
        var locked = ComponentPackageIO.create(definition,allowed.manifest(),environment);
        String id = install(locked,ComponentStorage.SaveContext.global(null));
        assertThrows(java.io.IOException.class,() -> save(id,locked,7));
        var saved = save(id,locked,5); var result = ComponentRecipeLibrary.result(alice,saved.assetId());
        var patches = ComponentDefinitionCompiler.compile(result.component(),net.minecraft.util.math.Direction.SOUTH,
                new com.formacraft.common.component.transform.ComponentTransform(net.minecraft.util.math.Direction.SOUTH,com.formacraft.common.component.transform.Mirror.NONE),
                0,0,0,true,"DEFAULT",42);
        assertEquals(5,patches.size()); assertTrue(patches.stream().allMatch(p -> "minecraft:diamond_block".equals(p.targetBlock())));
    }
}
