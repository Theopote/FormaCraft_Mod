package com.formacraft.common.component;

import com.formacraft.common.json.JsonUtil;
import com.formacraft.common.component.variant.ComponentVariantApplier;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ComponentTypePersistenceTest {
    @TempDir Path temporary;
    private Path root;
    private final UUID player = UUID.randomUUID();
    private final ComponentPackageIO.Environment environment = new ComponentPackageIO.Environment("1.21.10", Map.of());
    private static final byte[] PNG = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/p1sAAAAASUVORK5CYII=");
    @BeforeAll static void bootstrap() { com.formacraft.test.MinecraftRegistryTestBootstrap.initialize(); }
    @BeforeEach void setup() { root = temporary.resolve("library"); ComponentStorage.setGlobalComponentDirOverrideForTest(root); }
    @AfterEach void restore() { ComponentStorage.setGlobalComponentDirOverrideForTest(null); }
    private ComponentStorage.SaveContext context() { return ComponentStorage.SaveContext.forPlayer(null, player, false, false); }
    private ComponentDefinition definition(int count) {
        var d = new ComponentDefinition(); d.id = "temporary"; d.name = "Decorative frame"; d.type = ComponentTypes.custom("飞檐挂落");
        d.category = ComponentCategory.GENERIC; d.sourceOrigin = "CAPTURED";
        d.size = new ComponentDefinition.Size(); d.size.w = d.size.d = 10; d.size.h = Math.max(1, (count + 99) / 100);
        d.anchor = new ComponentDefinition.Anchor(); d.anchor.facing = "SOUTH"; d.blocks = new ArrayList<>();
        for (int i = 0; i < count; i++) { var b = new ComponentDefinition.BlockEntry(); b.dx = i % 10; b.dz = i / 10 % 10; b.dy = i / 100; b.block = "minecraft:stone"; d.blocks.add(b); }
        return d;
    }
    @Test void largeCaptureUploadsInChunksAndPreservesTypeAuthoritativeOwnerAndPng() throws Exception {
        var d = definition(600); d.sourceOrigin = "LIBRARY";
        String json = JsonUtil.toJson(d); assertTrue(json.length() > 32767);
        byte[] upload = ComponentCapturePersistence.encodeUpload(json, PNG); var sessions = new ComponentImportSessions(); var token = UUID.randomUUID();
        sessions.beginCapture(player, token, upload.length, context());
        for (int offset = 0; offset < upload.length; offset += ComponentImportSessions.CHUNK_BYTES)
            sessions.append(player, token, offset, Arrays.copyOfRange(upload, offset, Math.min(upload.length, offset + ComponentImportSessions.CHUNK_BYTES)));
        var preview = sessions.preview(player, token, environment); assertEquals(600, preview.blocks());
        assertEquals(player.toString(), preview.author()); assertEquals(ComponentPackageIO.TrainingPermission.UNSPECIFIED, preview.trainingPermission());
        assertFalse(Files.exists(root)); var installed = sessions.confirm(player, token, context());
        var stored = ComponentStorage.loadComponent(null, installed.assetId()); assertEquals(d.type, stored.type); assertTrue(stored.isCapturedOrigin());
        assertArrayEquals(PNG, Files.readAllBytes(root.resolve(installed.assetId() + ".png")));
    }
    @Test void classificationOnlyChangesReuseContentAndRemainDiscoverableAsSourceTypeAliases() throws Exception {
        var d = definition(1); var first = ComponentCapturePersistence.save(d, player, context(), environment, Map.of());
        var next = definition(1); next.type = ComponentTypes.custom("组合花架");
        var second = ComponentCapturePersistence.save(next, player, context(), environment, Map.of());
        assertEquals(first.imported().assetId(), second.imported().assetId());
        assertEquals(ComponentPackageInstallations.ImportStatus.SOURCE_ADDED, second.imported().status());
        var entry = ComponentStorage.loadCatalog(null).components.getFirst(); assertEquals(d.type, entry.type);
        assertEquals(List.of(next.type), entry.typeAliases);
        var sources = ComponentPackageInstallations.sources(root, entry.id); assertEquals(next.type, sources.getLast().component().type);
    }
    @Test void malformedClassificationIsRejectedBeforeSavingOrExporting() {
        var d = definition(1); d.type = ComponentTypes.preset("formacraft:window").type();
        assertTrue(com.formacraft.common.component.validate.ComponentValidator.validate(d).hasErrors());
        assertThrows(IOException.class, () -> ComponentCapturePersistence.save(d, player, context(), environment, Map.of()));
        var manifest = new ComponentPackageIO.Manifest("alice", "1", "Alice", "Test", ComponentPackageIO.TrainingPermission.DENIED, "1.21.10", Map.of(), Map.of(), List.of());
        assertThrows(IOException.class, () -> ComponentPackageIO.write(temporary.resolve("invalid.fccomponent.json"), d, manifest));
        assertFalse(Files.exists(root));
    }
    @Test void variantCopiesKeepClassificationAndThumbnailHeadersAreBounded() throws Exception {
        var d = definition(1); var clone = ComponentVariantApplier.cloneWithBlocksPublic(d, d.blocks);
        assertEquals(d.type, clone.type);
        assertThrows(IOException.class, () -> ComponentCapturePersistence.encodeUpload(JsonUtil.toJson(d), new byte[] {1, 2, 3}));
        byte[] invalid = PNG.clone(); java.nio.ByteBuffer.wrap(invalid).putInt(16, 2000);
        assertThrows(IOException.class, () -> ComponentCapturePersistence.encodeUpload(JsonUtil.toJson(d), invalid));
        var sessions = new ComponentImportSessions(); var token = UUID.randomUUID();
        sessions.beginCapture(player, token, 2, context()); sessions.append(player, token, 0, new byte[] {(byte) 0xc3, 0x28});
        assertThrows(IOException.class, () -> sessions.preview(player, token, environment)); assertEquals(0, sessions.pending());
        assertFalse(Files.exists(root));
    }
}
