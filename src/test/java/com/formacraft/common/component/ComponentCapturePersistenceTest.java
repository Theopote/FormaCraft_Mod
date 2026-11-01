package com.formacraft.common.component;

import com.formacraft.common.component.variant.CaptureAdaptationSettings;
import com.formacraft.common.json.JsonUtil;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ComponentCapturePersistenceTest {
    @TempDir Path temporary;
    private Path root;
    private final UUID player = UUID.randomUUID();
    private final ComponentPackageIO.Environment environment = new ComponentPackageIO.Environment("1.21.10", Map.of());
    @BeforeAll static void bootstrap() { com.formacraft.test.MinecraftRegistryTestBootstrap.initialize(); }
    @BeforeEach void setup() { root = temporary.resolve("library"); ComponentStorage.setGlobalComponentDirOverrideForTest(root); }
    @AfterEach void restore() { ComponentStorage.setGlobalComponentDirOverrideForTest(null); }
    private ComponentStorage.SaveContext context() { return ComponentStorage.SaveContext.forPlayer(null, player, false, false); }
    private ComponentDefinition sample() {
        var d = new ComponentDefinition(); d.id = "column_same_name"; d.name = "Column";
        d.category = ComponentCategory.COLUMN;
        d.size = new ComponentDefinition.Size(); d.size.w = d.size.d = 1; d.size.h = 3;
        d.anchor = new ComponentDefinition.Anchor(); d.anchor.facing = "SOUTH";
        d.blocks = new ArrayList<>();
        for (int y = 0; y < 3; y++) { var b = new ComponentDefinition.BlockEntry(); b.dy = y; b.block = "minecraft:stone"; d.blocks.add(b); }
        var settings = new CaptureAdaptationSettings(); settings.modes[1] = CaptureAdaptationSettings.Mode.REPEAT;
        d.variantRules = settings.toRules(d);
        return d;
    }
    private ComponentCapturePersistence.Saved save(ComponentDefinition d) throws IOException {
        return ComponentCapturePersistence.save(d, player, context(), environment, Map.of());
    }
    @Test void realCaptureSavesOpaqueIdentityAndRepeatedSubmissionReusesItWithoutMutatingDraft() throws Exception {
        var draft = sample(); draft.archetypeRef = "old_external"; draft.sourceOrigin = "LIBRARY";
        String before = JsonUtil.toJson(draft);
        var first = save(draft); var again = save(draft);
        assertTrue(first.isNew()); assertFalse(again.isNew());
        assertEquals(first.imported().assetId(), again.imported().assetId());
        assertEquals(ComponentPackageInstallations.ImportStatus.REUSED, again.imported().status());
        assertEquals(before, JsonUtil.toJson(draft));
        var installed = ComponentPackageInstallations.inspect(root, first.imported().assetId());
        assertEquals("capture", installed.component().id); assertNull(installed.component().archetypeRef);
        assertTrue(installed.component().isCapturedOrigin());
        assertEquals(ComponentPackageIO.TrainingPermission.UNSPECIFIED, installed.manifest().trainingPermission());
        assertEquals(player.toString(), installed.manifest().author());
        assertEquals(1, ComponentStorage.loadCatalog(null).components.size());
        assertNotNull(ComponentStorage.loadComponent(null, first.imported().assetId()));
    }
    @Test void repeatedNameCannotOverwriteDifferentCaptureOrLegacyFile() throws Exception {
        Files.createDirectories(root); Files.writeString(root.resolve("column_same_name.json"), "keep legacy bytes");
        var first = save(sample()); var changed = sample(); changed.blocks.getFirst().block = "minecraft:gold_block";
        var second = save(changed);
        assertNotEquals(first.imported().assetId(), second.imported().assetId());
        assertEquals("keep legacy bytes", Files.readString(root.resolve("column_same_name.json")));
        assertEquals("minecraft:stone", ComponentStorage.loadComponent(null, first.imported().assetId()).blocks.getFirst().block);
    }
    @Test void differentNameAddsSourceAndAcknowledgesExistingDisplayName() throws Exception {
        var first = save(sample()); var renamed = sample(); renamed.id = "unrelated"; renamed.name = "Another column";
        var second = save(renamed);
        assertEquals(first.imported().assetId(), second.imported().assetId());
        assertEquals(ComponentPackageInstallations.ImportStatus.SOURCE_ADDED, second.imported().status());
        assertEquals("Column", second.displayName()); assertFalse(second.isNew());
        assertEquals(2, ComponentPackageInstallations.sources(root, first.imported().assetId()).size());
    }
    @Test void differentRulesRemainSeparateConfigurations() throws Exception {
        var first = save(sample()); var changed = sample(); changed.variantRules.scaling.axes.get("Y").max = 6;
        var next = save(changed);
        assertEquals(ComponentPackageInstallations.ImportStatus.NEW_CONFIGURATION, next.imported().status());
        assertTrue(next.isNew()); assertNotEquals(first.imported().assetId(), next.imported().assetId());
    }
    @Test void dedicatedNonOpCaptureIsPrivate() throws Exception {
        var ctx = ComponentStorage.SaveContext.forPlayer(null, player, true, false);
        var saved = ComponentCapturePersistence.save(sample(), player, ctx, environment, Map.of());
        assertNull(ComponentStorage.loadComponent(null, saved.imported().assetId()));
        assertNotNull(ComponentStorage.loadComponent(null, saved.imported().assetId(), player));
        assertNull(ComponentStorage.loadComponent(null, saved.imported().assetId(), UUID.randomUUID()));
    }
    @Test void invalidCaptureAndMismatchedPlayerNeverCreateLibrary() {
        assertThrows(IOException.class, () -> ComponentCapturePersistence.save(sample(), UUID.randomUUID(), context(), environment, Map.of()));
        var invalid = sample(); invalid.schema = "unsupported";
        assertThrows(IOException.class, () -> save(invalid));
        assertFalse(Files.exists(root));
    }
    @Test void unresolvedModNamespaceFailsBeforePublication() {
        var d = sample(); d.blocks.getFirst().block = "customnamespace:stone";
        assertThrows(IOException.class, () -> save(d));
        assertFalse(Files.exists(root));
    }
    @Test void installedCaptureCatalogDiscoversThumbnailAndDeleteRemovesIt() throws Exception {
        var saved = save(sample()); String id = saved.imported().assetId();
        ComponentStorage.saveThumbnail(context(), id, new byte[] {1, 2, 3});
        assertEquals(id + ".png", ComponentStorage.loadCatalog(null).components.getFirst().thumbnail);
        assertTrue(ComponentStorage.deleteComponentWithContext(context(), id));
        assertFalse(Files.exists(root.resolve(id + ".png")));
        assertTrue(ComponentStorage.loadCatalog(null).components.isEmpty());
    }
}
