package com.formacraft.common.component;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class ComponentImportSessionsTest {
    @TempDir Path temporary;
    private Path root;
    private final UUID player = UUID.randomUUID();
    private final ComponentPackageIO.Environment environment = new ComponentPackageIO.Environment("1.21.10", Map.of());
    @BeforeAll static void bootstrap() { com.formacraft.test.MinecraftRegistryTestBootstrap.initialize(); }
    @BeforeEach void setup() { root = temporary.resolve("library"); ComponentStorage.setGlobalComponentDirOverrideForTest(root); }
    @AfterEach void restore() { ComponentStorage.setGlobalComponentDirOverrideForTest(null); }
    private ComponentStorage.SaveContext context() { return ComponentStorage.SaveContext.forPlayer(null, player, false, false); }
    private byte[] pack(String name, boolean other) throws Exception {
        var d = new ComponentDefinition(); d.id = "window"; d.name = name; d.sourceOrigin = "CAPTURED"; d.category = ComponentCategory.WINDOW;
        d.size = new ComponentDefinition.Size(); d.size.w = d.size.h = d.size.d = 1;
        d.anchor = new ComponentDefinition.Anchor(); d.anchor.facing = "SOUTH";
        var b = new ComponentDefinition.BlockEntry(); b.block = "minecraft:stone"; d.blocks = List.of(b);
        Path file = temporary.resolve(UUID.randomUUID() + ".fccomponent.json");
        ComponentPackageIO.write(file, d, new ComponentPackageIO.Manifest(other ? "bob" : "alice", "1", other ? "Bob" : "Alice", "LicenseRef-Test",
                other ? ComponentPackageIO.TrainingPermission.ALLOWED : ComponentPackageIO.TrainingPermission.DENIED, "1.21.10", Map.of(), Map.of(), List.of()));
        byte[] bytes = Files.readAllBytes(file); Files.delete(file); return bytes;
    }
    private UUID upload(ComponentImportSessions sessions, byte[] bytes, ComponentStorage.SaveContext ctx) throws Exception {
        UUID token = UUID.randomUUID(); sessions.begin(player, token, bytes.length, ctx);
        for (int offset = 0; offset < bytes.length; offset += ComponentImportSessions.CHUNK_BYTES)
            sessions.append(player, token, offset, Arrays.copyOfRange(bytes, offset, Math.min(bytes.length, offset + ComponentImportSessions.CHUNK_BYTES)));
        return token;
    }
    @Test void previewWritesNothingAndConfirmationConsumesExactSnapshotOnce() throws Exception {
        var sessions = new ComponentImportSessions(); var bytes = pack("Window", false); var token = upload(sessions, bytes, context());
        Arrays.fill(bytes, (byte) 0);
        var preview = sessions.preview(player, token, environment);
        assertEquals("Window", preview.name()); assertTrue(preview.global()); assertEquals(1, preview.blocks());
        assertEquals(ComponentPackageIO.TrainingPermission.DENIED, preview.trainingPermission());
        assertFalse(Files.exists(root));
        var installed = sessions.confirm(player, token, context());
        assertEquals(ComponentPackageInstallations.ImportStatus.NEW_ASSET, installed.status());
        assertNotNull(ComponentStorage.loadComponent(null, installed.assetId())); assertEquals(0, sessions.pending());
        assertThrows(IOException.class, () -> sessions.confirm(player, token, context()));
        assertEquals(1, ComponentStorage.loadCatalog(null).components.size());
    }
    @Test void repeatedGeometryReusesAssetAndPreservesIndependentSourceRights() throws Exception {
        var sessions = new ComponentImportSessions(); var first = upload(sessions, pack("Window", false), context());
        sessions.preview(player, first, environment); var installed = sessions.confirm(player, first, context());
        var second = upload(sessions, pack("Other window", true), context());
        sessions.preview(player, second, environment); var reused = sessions.confirm(player, second, context());
        assertEquals(installed.assetId(), reused.assetId()); assertEquals("Window", reused.name());
        assertEquals(ComponentPackageInstallations.ImportStatus.SOURCE_ADDED, reused.status());
        var sources = ComponentPackageInstallations.sources(root, installed.assetId()); assertEquals(2, sources.size());
        assertEquals(ComponentPackageIO.TrainingPermission.DENIED, sources.getFirst().manifest().trainingPermission());
        assertEquals(ComponentPackageIO.TrainingPermission.ALLOWED, sources.getLast().manifest().trainingPermission());
    }
    @Test void playerOwnershipAndTokensCannotBeTransferred() throws Exception {
        var sessions = new ComponentImportSessions(); var token = upload(sessions, pack("Window", false), context());
        UUID another = UUID.randomUUID();
        assertThrows(IOException.class, () -> sessions.preview(another, token, environment));
        assertThrows(IOException.class, () -> sessions.preview(player, UUID.randomUUID(), environment));
        assertEquals(1, sessions.pending());
        sessions.cancel(another, token); sessions.cancel(player, UUID.randomUUID()); assertEquals(1, sessions.pending());
        sessions.cancel(player, token); assertEquals(0, sessions.pending()); assertFalse(Files.exists(root));
    }
    @Test void duplicateOffsetsOverflowAndTruncationAreRejectedWithoutPublishing() throws Exception {
        var sessions = new ComponentImportSessions(); UUID token = UUID.randomUUID();
        sessions.begin(player, token, 2, context()); sessions.append(player, token, 0, new byte[] {1});
        assertThrows(IOException.class, () -> sessions.append(player, token, 0, new byte[] {2})); assertEquals(0, sessions.pending());
        sessions.begin(player, token, 1, context());
        assertThrows(IOException.class, () -> sessions.append(player, token, 0, new byte[] {1, 2}));
        sessions.begin(player, token, 2, context()); sessions.append(player, token, 0, new byte[] {1});
        assertThrows(IOException.class, () -> sessions.preview(player, token, environment));
        assertFalse(Files.exists(root));
    }
    @Test void expiryAndDisconnectReleaseBothUploadsAndVerifiedPreviews() throws Exception {
        var clock = new AtomicLong(); var sessions = new ComponentImportSessions(clock::get);
        var token = upload(sessions, pack("Window", false), context()); sessions.preview(player, token, environment);
        clock.set(120_000_000_000L);
        assertThrows(IOException.class, () -> sessions.confirm(player, token, context())); assertEquals(0, sessions.pending());
        upload(sessions, pack("Window", false), context()); sessions.disconnect(player);
        assertEquals(0, sessions.pending()); assertFalse(Files.exists(root));
    }
    @Test void changedPermissionsRequireNewPreviewAndCannotSilentlyChangeTargetLibrary() throws Exception {
        var sessions = new ComponentImportSessions(); var privateCtx = ComponentStorage.SaveContext.forPlayer(null, player, true, false);
        var token = upload(sessions, pack("Window", false), privateCtx); assertFalse(sessions.preview(player, token, environment).global());
        assertThrows(IOException.class, () -> sessions.confirm(player, token, context()));
        assertEquals(0, sessions.pending()); assertFalse(Files.exists(root));
    }
    @Test void capacityLimitsDoNotAllocateDeclaredFileSizeAndAreReleased() throws Exception {
        var sessions = new ComponentImportSessions();
        for (int i = 0; i < 4; i++) { UUID p = UUID.randomUUID(); sessions.begin(p, UUID.randomUUID(), 1, ComponentStorage.SaveContext.forPlayer(null, p, true, false)); }
        assertThrows(IOException.class, () -> sessions.begin(player, UUID.randomUUID(), 1, context()));
        sessions.clear(); assertEquals(0, sessions.pending());
        for (int i = 0; i < 2; i++) { UUID p = UUID.randomUUID(); sessions.begin(p, UUID.randomUUID(), ComponentPackageIO.MAX_PACKAGE_BYTES, ComponentStorage.SaveContext.forPlayer(null, p, true, false)); }
        assertThrows(IOException.class, () -> sessions.begin(player, UUID.randomUUID(), 1, context()));
        assertThrows(IOException.class, () -> sessions.begin(player, UUID.randomUUID(), ComponentPackageIO.MAX_PACKAGE_BYTES + 1, context()));
        assertFalse(Files.exists(root));
    }
    @Test void invalidEncodingAndIncompatibleEnvironmentNeverCreateLibrary() throws Exception {
        var sessions = new ComponentImportSessions(); var token = upload(sessions, new byte[] {(byte) 0xc3, 0x28}, context());
        assertThrows(IOException.class, () -> sessions.preview(player, token, environment));
        var next = upload(sessions, pack("Window", false), context());
        assertThrows(IOException.class, () -> sessions.preview(player, next, new ComponentPackageIO.Environment("wrong", Map.of())));
        assertEquals(0, sessions.pending()); assertFalse(Files.exists(root));
    }
}
