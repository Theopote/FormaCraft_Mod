package com.formacraft.common.component;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ComponentLibraryOperationsTest {
    @TempDir Path temporary;
    private final UUID alice = UUID.randomUUID(), bob = UUID.randomUUID();
    private final ComponentPackageIO.Environment environment = new ComponentPackageIO.Environment("1.21.10",Map.of());
    @BeforeAll static void bootstrap() { com.formacraft.test.MinecraftRegistryTestBootstrap.initialize(); }
    @BeforeEach void setup() { ComponentStorage.setGlobalComponentDirOverrideForTest(temporary.resolve("library")); }
    @AfterEach void restore() { ComponentStorage.setGlobalComponentDirOverrideForTest(null); }
    private ComponentPackageIO.Verified pack(String name, String license) throws Exception {
        var d = new ComponentDefinition(); d.id = "window"; d.name = name; d.sourceOrigin = "CAPTURED";
        d.category = ComponentCategory.WINDOW; d.size = new ComponentDefinition.Size(); d.size.w = d.size.h = d.size.d = 1;
        d.anchor = new ComponentDefinition.Anchor(); d.anchor.facing = "SOUTH";
        var block = new ComponentDefinition.BlockEntry(); block.block = "minecraft:stone"; d.blocks = List.of(block);
        return ComponentPackageIO.create(d,new ComponentPackageIO.Manifest("alice","1","Alice",license,
                ComponentPackageIO.TrainingPermission.DENIED,"1.21.10",Map.of(),Map.of(),List.of()),environment);
    }
    private String install(ComponentPackageIO.Verified pack, ComponentStorage.SaveContext context) throws Exception {
        Path path = temporary.resolve(UUID.randomUUID()+".fccomponent.json"); pack.writeTo(path);
        return ComponentPackageInstallations.importPackage(path,environment,context).assetId();
    }
    @Test void exportPreservesPrimaryPayloadAndRightsAndDoesNotWriteLibrary() throws Exception {
        var pack = pack("Window","Restricted"); String id = install(pack,ComponentStorage.SaveContext.global(null));
        var exported = ComponentLibraryOperations.export(null,alice,id,pack.contentRef(),0,pack.contentRef());
        assertArrayEquals(pack.toBytes(),exported.toBytes()); assertEquals(1,ComponentStorage.loadCatalog(null).components.size());
        assertEquals(com.formacraft.common.json.JsonUtil.toJson(ComponentStorage.loadComponent(null,id,alice)),
                com.formacraft.common.json.JsonUtil.toJson(exported.runtimeComponent(id)));
        assertEquals("window",exported.component().id);
        var inspection = ComponentPackageIO.inspectBytes(exported.toBytes());
        assertThrows(java.io.IOException.class,() -> ComponentPackageInstallations.importVerified(inspection,ComponentStorage.SaveContext.global(null)));
        assertThrows(java.io.IOException.class,() -> ComponentLibraryOperations.export(null,alice,id,"stale",0,pack.contentRef()));
        assertThrows(java.io.IOException.class,() -> ComponentLibraryOperations.export(null,alice,"../bad",pack.contentRef(),0,pack.contentRef()));
    }
    @Test void sourceExportBindsManifestEvenWhenPayloadAndPublisherReferenceAreIdentical() throws Exception {
        var primary = pack("Window","First"); var secondary = pack("Window","Second");
        String id = install(primary,ComponentStorage.SaveContext.global(null)); install(secondary,ComponentStorage.SaveContext.global(null));
        assertEquals(primary.contentRef(),secondary.contentRef());
        var page = ComponentSourceView.query(null,alice,id,1);
        var result = ComponentLibraryOperations.export(null,alice,id,primary.contentRef(),1,page.contentRef()+"|manifest:"+page.manifestSha256());
        assertEquals("Second",result.manifest().license());
        assertThrows(java.io.IOException.class,() -> ComponentLibraryOperations.export(null,alice,id,primary.contentRef(),1,primary.contentRef()));
        assertThrows(java.io.IOException.class,() -> ComponentLibraryOperations.export(null,alice,id,primary.contentRef(),1,primary.contentRef()+"|manifest:"+primary.manifestSha256()));
    }
    @Test void ordinaryPlayerCannotDeleteGlobalButOwnerCanDeletePrivateAfterBecomingOp() throws Exception {
        var source = pack("Window","Test"); String global = install(source,ComponentStorage.SaveContext.global(null));
        assertThrows(java.io.IOException.class,() -> ComponentLibraryOperations.delete(ComponentStorage.SaveContext.forPlayer(null,alice,true,false),global,source.contentRef()));
        var privateContext = ComponentStorage.SaveContext.forPlayer(null,alice,true,false);
        String personal = install(source,privateContext);
        assertThrows(java.io.IOException.class,() -> ComponentLibraryOperations.export(null,bob,personal,source.contentRef(),0,source.contentRef()));
        ComponentLibraryOperations.delete(ComponentStorage.SaveContext.forPlayer(null,alice,true,true),personal,source.contentRef());
        assertNull(ComponentPackageInstallations.load(ComponentStorage.getPlayerComponentDir(alice),personal));
        assertNotNull(ComponentPackageInstallations.load(ComponentStorage.getGlobalComponentDir(),global));
    }
    @Test void deletingPrivateShadowDoesNotDeleteSameIdInGlobalLibrary() throws Exception {
        var source = pack("Window","Test");
        Path path = temporary.resolve("source.fccomponent.json"); source.writeTo(path);
        ComponentPackageInstallations.install(path,environment,ComponentStorage.SaveContext.forPlayer(null,alice,true,false),"same");
        ComponentPackageInstallations.install(path,environment,ComponentStorage.SaveContext.global(null),"same");
        ComponentLibraryOperations.delete(ComponentStorage.SaveContext.forPlayer(null,alice,true,true),"same",source.contentRef());
        assertNotNull(ComponentPackageInstallations.load(ComponentStorage.getGlobalComponentDir(),"same"));
        assertNull(ComponentPackageInstallations.load(ComponentStorage.getPlayerComponentDir(alice),"same"));
    }
    @Test void exportSessionsAreCorrelatedContiguousImmutableAndExpire() throws Exception {
        var time = new java.util.concurrent.atomic.AtomicLong(); var sessions = new ComponentExportSessions(time::get);
        var source = pack("Window","Test"); var token = UUID.randomUUID(); sessions.begin(alice,token,source);
        assertThrows(java.io.IOException.class,() -> sessions.next(bob,token,0));
        var first = sessions.next(alice,token,0); assertArrayEquals(source.toBytes(),first.data());
        byte[] mutable = first.data(); mutable[0] = 0; assertNotEquals(0,first.data()[0]);
        assertThrows(java.io.IOException.class,() -> sessions.next(alice,token,0));
        sessions.begin(alice,token,source); time.set(120_000_000_000L);
        assertThrows(java.io.IOException.class,() -> sessions.next(alice,token,0));
        sessions.begin(alice,token,source); sessions.cancel(alice,UUID.randomUUID()); assertNotNull(sessions.next(alice,token,0));
    }
    @Test void largePackageDownloadsAcrossMultipleChunksAndCanBeReimportedWithoutLosingIdentity() throws Exception {
        var original = pack("Large window","Test"); var definition = original.component();
        definition.size.w = 30; definition.size.h = 20;
        definition.blocks = new ArrayList<>();
        for (int x = 0; x < 30; x++) for (int y = 0; y < 20; y++) {
            var block = new ComponentDefinition.BlockEntry(); block.dx = x; block.dy = y; block.block = "minecraft:stone"; definition.blocks.add(block);
        }
        var source = ComponentPackageIO.create(definition,original.manifest(),environment);
        assertTrue(source.toBytes().length > 32767);
        var sessions = new ComponentExportSessions(); var token = UUID.randomUUID(); sessions.begin(alice,token,source);
        var client = new com.formacraft.client.component.ComponentLibraryClientState(System::nanoTime);
        var request = client.begin("asset_large",source.contentRef(),0,source.contentRef(),false);
        token = request.token(); sessions.begin(alice,token,source);
        int offset = 0, chunks = 0;
        while (true) {
            var chunk = sessions.next(alice,token,offset); chunks++;
            var next = client.accept(new com.formacraft.common.network.ComponentLibraryNetworking.Response(token,
                    com.formacraft.common.network.ComponentLibraryNetworking.Reply.CHUNK,chunk.length(),chunk.offset(),chunk.data(),""));
            if (next == null) break; offset = next.offset();
        }
        assertTrue(chunks > 1); var downloaded = ComponentPackageIO.inspectBytes(client.bytes());
        assertEquals(source.contentRef(),downloaded.contentRef()); assertEquals(source.manifestSha256(),downloaded.manifestSha256());
        String first = install(source,ComponentStorage.SaveContext.global(null));
        assertEquals(first,install(downloaded,ComponentStorage.SaveContext.global(null)));
    }
    @Test void exportSessionQuotaAndInvalidOffsetsReleaseResources() throws Exception {
        var sessions = new ComponentExportSessions(); var source = pack("Window","Test"); var token = UUID.randomUUID();
        var players = List.of(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID());
        for (var player : players) sessions.begin(player,token,source);
        assertThrows(java.io.IOException.class,() -> sessions.begin(alice,token,source));
        assertThrows(java.io.IOException.class,() -> sessions.next(players.getFirst(),token,1));
        sessions.begin(alice,token,source); sessions.disconnect(alice);
        assertThrows(java.io.IOException.class,() -> sessions.next(alice,token,0));
        sessions.clear(); sessions.begin(alice,token,source);
    }
}
