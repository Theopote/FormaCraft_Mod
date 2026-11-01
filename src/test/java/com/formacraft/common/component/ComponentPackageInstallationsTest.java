package com.formacraft.common.component;

import com.formacraft.common.component.variant.CaptureAdaptationSettings;
import com.formacraft.common.json.JsonUtil;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ComponentPackageInstallationsTest {
    @Test void flatCatalogAndInstalledEntriesCoexistWithoutStaleImportedIndexRecords() throws Exception {
        ComponentPackageInstallations.install(source, environment, global(), "column");
        var flatDef = asset(); flatDef.id = "flat_column";
        Files.writeString(root.resolve("flat_column.json"), JsonUtil.toJson(flatDef));
        var catalog = ComponentStorage.loadCatalog(null);
        var flat = new ComponentCatalog.Entry(); flat.id = "flat_column"; flat.name = "Flat column";
        flat.category = ComponentCategory.COLUMN; flat.file = "flat_column.json";
        catalog.components.add(flat); ComponentStorage.saveCatalog(null, catalog);
        assertEquals(2, ComponentStorage.loadCatalog(null).components.size());
        assertNotNull(ComponentStorage.loadComponent(null, "flat_column"));
        assertEquals(1, JsonUtil.fromJson(Files.readString(root.resolve("catalog.json")), ComponentCatalog.class).components.size());
        assertTrue(ComponentStorage.deleteComponentWithContext(global(), "column"));
        assertEquals(List.of("flat_column"), ComponentStorage.loadCatalog(null).components.stream().map(e -> e.id).toList());
    }
    @TempDir Path temporary;
    private Path root, source;
    private final ComponentPackageIO.Environment environment = new ComponentPackageIO.Environment("1.21.10", Map.of());
    @BeforeAll static void bootstrap() { com.formacraft.test.MinecraftRegistryTestBootstrap.initialize(); }
    @BeforeEach void setup() throws Exception {
        root = temporary.resolve("library"); source = temporary.resolve("column.fccomponent.json");
        ComponentStorage.setGlobalComponentDirOverrideForTest(root);
        ComponentPackageIO.write(source, asset(), new ComponentPackageIO.Manifest("alice", "1.0", "Alice", "LicenseRef-Test",
                ComponentPackageIO.TrainingPermission.DENIED, "1.21.10", Map.of(), Map.of(), List.of("alice/source@0.9")));
    }
    @AfterEach void restore() { ComponentStorage.setGlobalComponentDirOverrideForTest(null); }
    private ComponentDefinition asset() {
        var def = new ComponentDefinition(); def.id = "column"; def.name = "Column"; def.sourceOrigin = "CAPTURED";
        def.category = ComponentCategory.COLUMN; def.tags = List.of("suite:alice/courtyard");
        def.size = new ComponentDefinition.Size(); def.size.w = def.size.d = 1; def.size.h = 5;
        def.anchor = new ComponentDefinition.Anchor(); def.anchor.facing = "SOUTH"; def.blocks = new ArrayList<>();
        for (int y = 0; y < 5; y++) { var b = new ComponentDefinition.BlockEntry(); b.dy = y; b.block = "minecraft:stone"; def.blocks.add(b); }
        var settings = new CaptureAdaptationSettings(); settings.modes[1] = CaptureAdaptationSettings.Mode.REPEAT;
        settings.setFixedEdge(1, true, 2); def.variantRules = settings.toRules(def); return def;
    }
    private ComponentStorage.SaveContext global() { return ComponentStorage.SaveContext.global(null); }

    @Test void installedAliasIsDiscoverableAndOriginalRightsAndHashAreRetained() throws Exception {
        var receipt = ComponentPackageInstallations.install(source, environment, global(), "alice_column");
        Files.delete(source); // Installation uses a verified snapshot, not the external source path.
        var def = ComponentStorage.loadComponent(null, "alice_column"); assertNotNull(def);
        assertEquals("alice_column", def.id); assertTrue(def.archetypeRef.startsWith("pkg_"));
        assertTrue(def.isCapturedOrigin()); assertEquals(2, def.variantRules.scaling.axes.get("Y").fixedEnd);
        var entry = ComponentStorage.loadCatalog(null).components.getFirst(); assertEquals(receipt.contentRef(), entry.packageRef);
        var stored = ComponentPackageInstallations.inspect(root, "alice_column");
        assertEquals("column", stored.component().id); assertEquals("alice/column@1.0", stored.assetKey());
        assertEquals(ComponentPackageIO.TrainingPermission.DENIED, stored.manifest().trainingPermission());
        assertEquals(receipt.manifestSha256(), stored.manifestSha256());
        assertFalse(Files.exists(root.resolve("alice_column.json")));
        ComponentStorage.saveCatalog(null, ComponentStorage.loadCatalog(null));
        var flat = JsonUtil.fromJson(Files.readString(root.resolve("catalog.json")), ComponentCatalog.class);
        assertTrue(flat.components.isEmpty()); assertEquals(1, ComponentStorage.loadCatalog(null).components.size());
    }

    @Test void privateInstallationsAreVisibleOnlyToTheirPlayerAndCannotShadowGlobal() throws Exception {
        UUID player = UUID.randomUUID(); var personal = ComponentStorage.SaveContext.forPlayer(null, player, true, false);
        ComponentPackageInstallations.install(source, environment, personal, "private_column");
        assertNull(ComponentStorage.loadComponent(null, "private_column"));
        assertNotNull(ComponentStorage.loadComponent(null, "private_column", player));
        assertTrue(ComponentStorage.loadCatalog(null).components.isEmpty());
        ComponentPackageInstallations.install(source, environment, global(), "global_column");
        assertThrows(FileAlreadyExistsException.class, () -> ComponentPackageInstallations.install(source, environment, personal, "global_column"));
    }

    @Test void conflictsNeverOverwriteInstalledOrOrphanedFlatFiles() throws Exception {
        ComponentPackageInstallations.install(source, environment, global(), "column");
        String before = Files.readString(root.resolve("packages/column/source.fccomponent.json"));
        assertThrows(FileAlreadyExistsException.class, () -> ComponentPackageInstallations.install(source, environment, global(), "column"));
        assertEquals(before, Files.readString(root.resolve("packages/column/source.fccomponent.json")));
        Files.writeString(root.resolve("orphan.json"), "keep");
        assertThrows(FileAlreadyExistsException.class, () -> ComponentPackageInstallations.install(source, environment, global(), "orphan"));
        assertEquals("keep", Files.readString(root.resolve("orphan.json")));
        Path world = temporary.resolve("world"); Path legacy = ComponentStorage.getWorldComponentDir(world);
        Files.createDirectories(legacy); Files.writeString(legacy.resolve("legacy.json"), "old world asset");
        assertThrows(FileAlreadyExistsException.class, () -> ComponentPackageInstallations.install(source, environment,
                ComponentStorage.SaveContext.global(world), "legacy"));
        assertEquals("old world asset", Files.readString(legacy.resolve("legacy.json")));
    }

    @Test void failureBeforePublicationLeavesNoVisibleAssetAndCanBeRetried() throws Exception {
        assertThrows(IOException.class, () -> ComponentPackageInstallations.install(source, environment, global(), "column",
                staging -> { throw new IOException("simulated publication failure"); }));
        assertTrue(ComponentStorage.loadCatalog(null).components.isEmpty());
        try (var files = Files.list(root.resolve("packages"))) {
            assertTrue(files.noneMatch(p -> p.getFileName().toString().startsWith(".pending-")));
        }
        ComponentPackageInstallations.install(source, environment, global(), "column");
        assertNotNull(ComponentStorage.loadComponent(null, "column"));
    }

    @Test void metadataTamperingIsDetectedAndCannotBeHiddenByPayloadHash() throws Exception {
        ComponentPackageInstallations.install(source, environment, global(), "column");
        Path installed = root.resolve("packages/column/source.fccomponent.json");
        var json = JsonParser.parseString(Files.readString(installed)).getAsJsonObject();
        json.getAsJsonObject("manifest").addProperty("trainingPermission", "ALLOWED"); Files.writeString(installed, json.toString());
        assertThrows(IOException.class, () -> ComponentPackageInstallations.inspect(root, "column"));
        assertNull(ComponentStorage.loadComponent(null, "column"));
        assertTrue(ComponentStorage.loadCatalog(null).components.isEmpty());
    }

    @Test void normalSaveCannotOverwritePackageAndDeleteRetiresIt() throws Exception {
        ComponentPackageInstallations.install(source, environment, global(), "column");
        assertThrows(IllegalArgumentException.class, () -> ComponentStorage.saveComponentWithContext(global(), asset()));
        assertTrue(ComponentStorage.deleteComponentWithContext(global(), "column"));
        assertNull(ComponentStorage.loadComponent(null, "column")); assertTrue(ComponentStorage.loadCatalog(null).components.isEmpty());
    }

    @Test void invalidContextAndCompatibilityFailBeforeCreatingLibrary() {
        assertThrows(IOException.class, () -> ComponentPackageInstallations.install(source, environment, null, "column"));
        assertThrows(IOException.class, () -> ComponentPackageInstallations.install(source,
                new ComponentPackageIO.Environment("wrong", Map.of()), global(), "column"));
        assertFalse(Files.exists(root));
    }

    private Path anotherPackage(ComponentDefinition definition, ComponentPackageIO.TrainingPermission permission) throws Exception {
        Path file = temporary.resolve(UUID.randomUUID() + ".fccomponent.json");
        ComponentPackageIO.write(file, definition, new ComponentPackageIO.Manifest("bob", "1.0", "Bob", "LicenseRef-Bob",
                permission, "1.21.10", Map.of(), Map.of(), List.of()));
        return file;
    }
    @Test void ordinaryImportGeneratesOpaqueStableIdentityAndRepeatedImportReusesIt() throws Exception {
        var first = ComponentPackageInstallations.importPackage(source, environment, global());
        var again = ComponentPackageInstallations.importPackage(source, environment, global());
        assertEquals(ComponentPackageInstallations.ImportStatus.NEW_ASSET, first.status());
        assertTrue(first.assetId().matches("asset_[0-9a-f]{32}"));
        assertEquals(first.assetId(), again.assetId());
        assertEquals(ComponentPackageInstallations.ImportStatus.REUSED, again.status());
        assertEquals(1, ComponentStorage.loadCatalog(null).components.size());
        assertEquals("Column", ComponentStorage.loadComponent(null, first.assetId()).name);
        assertEquals(1, ComponentPackageInstallations.sources(root, first.assetId()).size());
        assertEquals(1, ComponentPackageInstallations.sources(root, " " + first.assetId() + " ").size());
    }
    @Test void sameNameDifferentStructureIsNeverOverwritten() throws Exception {
        var first = ComponentPackageInstallations.importPackage(source, environment, global());
        var changed = asset(); changed.blocks.getFirst().block = "minecraft:gold_block";
        var next = ComponentPackageInstallations.importPackage(anotherPackage(changed, ComponentPackageIO.TrainingPermission.DENIED), environment, global());
        assertEquals(ComponentPackageInstallations.ImportStatus.NEW_ASSET, next.status());
        assertNotEquals(first.assetId(), next.assetId());
        assertEquals(2, ComponentStorage.loadCatalog(null).components.size());
        assertEquals("minecraft:stone", ComponentStorage.loadComponent(null, first.assetId()).blocks.getFirst().block);
        assertEquals("minecraft:gold_block", ComponentStorage.loadComponent(null, next.assetId()).blocks.getFirst().block);
    }
    @Test void renamedIdenticalContentAddsIndependentSourceWithoutChangingPrimaryRights() throws Exception {
        var first = ComponentPackageInstallations.importPackage(source, environment, global());
        byte[] original = Files.readAllBytes(root.resolve("packages").resolve(first.assetId()).resolve("source.fccomponent.json"));
        var renamed = asset(); renamed.id = "bobs_column"; renamed.name = "Another column";
        Collections.reverse(renamed.blocks);
        Path alternate = anotherPackage(renamed, ComponentPackageIO.TrainingPermission.ALLOWED);
        var second = ComponentPackageInstallations.importPackage(alternate, environment, global());
        assertEquals(first.assetId(), second.assetId());
        assertEquals(ComponentPackageInstallations.ImportStatus.SOURCE_ADDED, second.status());
        assertEquals(ComponentPackageInstallations.ImportStatus.REUSED, ComponentPackageInstallations.importPackage(alternate, environment, global()).status());
        assertEquals(1, ComponentStorage.loadCatalog(null).components.size());
        var sources = ComponentPackageInstallations.sources(root, first.assetId());
        assertEquals(2, sources.size());
        assertEquals(ComponentPackageIO.TrainingPermission.DENIED, sources.getFirst().manifest().trainingPermission());
        assertEquals(ComponentPackageIO.TrainingPermission.ALLOWED, sources.getLast().manifest().trainingPermission());
        assertEquals("Another column", sources.getLast().component().name);
        assertArrayEquals(original, Files.readAllBytes(root.resolve("packages").resolve(first.assetId()).resolve("source.fccomponent.json")));
        assertTrue(ComponentStorage.deleteComponentWithContext(global(), first.assetId()));
        assertTrue(ComponentStorage.loadCatalog(null).components.isEmpty());
        assertFalse(Files.exists(root.resolve("packages").resolve(first.assetId())));
    }
    @Test void sameStructureDifferentRulesCreatesSeparateConfiguration() throws Exception {
        var first = ComponentPackageInstallations.importPackage(source, environment, global());
        var changed = asset(); changed.variantRules.scaling.axes.get("Y").fixedEnd = 1;
        var second = ComponentPackageInstallations.importPackage(anotherPackage(changed, ComponentPackageIO.TrainingPermission.DENIED), environment, global());
        assertEquals(ComponentPackageInstallations.ImportStatus.NEW_CONFIGURATION, second.status());
        assertNotEquals(first.assetId(), second.assetId());
        assertEquals(first.fingerprints().structure(), second.fingerprints().structure());
        assertEquals(2, ComponentStorage.loadComponent(null, first.assetId()).variantRules.scaling.axes.get("Y").fixedEnd);
        assertEquals(1, ComponentStorage.loadComponent(null, second.assetId()).variantRules.scaling.axes.get("Y").fixedEnd);
    }
    @Test void privateDeduplicationDoesNotMutateGlobalOrAnotherPlayersLibrary() throws Exception {
        var shared = ComponentPackageInstallations.importPackage(source, environment, global());
        UUID player = UUID.randomUUID(), other = UUID.randomUUID();
        var personal = ComponentStorage.SaveContext.forPlayer(null, player, true, false);
        var privateResult = ComponentPackageInstallations.importPackage(source, environment, personal);
        assertNotEquals(shared.assetId(), privateResult.assetId());
        assertEquals(privateResult.assetId(), ComponentPackageInstallations.importPackage(source, environment, personal).assetId());
        assertEquals(1, ComponentPackageInstallations.sources(root, shared.assetId()).size());
        assertNull(ComponentStorage.loadComponent(null, privateResult.assetId(), other));
        assertNotNull(ComponentStorage.loadComponent(null, privateResult.assetId(), player));
    }
    @Test void ordinaryImportCanReuseAnExistingExplicitAlias() throws Exception {
        ComponentPackageInstallations.install(source, environment, global(), "legacy_alias");
        var result = ComponentPackageInstallations.importPackage(source, environment, global());
        assertEquals("legacy_alias", result.assetId());
        assertEquals(ComponentPackageInstallations.ImportStatus.REUSED, result.status());
        assertEquals(1, ComponentStorage.loadCatalog(null).components.size());
    }
    @Test void concurrentOrdinaryImportsPublishOnlyOneAsset() throws Exception {
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var tasks = pool.invokeAll(List.of(
                    (java.util.concurrent.Callable<ComponentPackageInstallations.ImportResult>) () -> ComponentPackageInstallations.importPackage(source, environment, global()),
                    () -> ComponentPackageInstallations.importPackage(source, environment, global())));
            assertEquals(tasks.getFirst().get().assetId(), tasks.getLast().get().assetId());
            assertEquals(1, ComponentStorage.loadCatalog(null).components.size());
        } finally { pool.shutdownNow(); }
    }
    @Test void corruptAdditionalSourceCannotGrantRightsOrBeSilentlyReplaced() throws Exception {
        var first = ComponentPackageInstallations.importPackage(source, environment, global());
        Path alternate = anotherPackage(asset(), ComponentPackageIO.TrainingPermission.ALLOWED);
        ComponentPackageInstallations.importPackage(alternate, environment, global());
        Path sources = root.resolve("packages").resolve(first.assetId()).resolve("sources");
        Path record;
        try (var files = Files.list(sources)) { record = files.findFirst().orElseThrow(); }
        var json = JsonParser.parseString(Files.readString(record)).getAsJsonObject();
        json.getAsJsonObject("manifest").addProperty("license", "changed");
        Files.writeString(record, json.toString());
        assertThrows(IOException.class, () -> ComponentPackageInstallations.sources(root, first.assetId()));
        assertThrows(IOException.class, () -> ComponentPackageInstallations.importPackage(alternate, environment, global()));
        assertNotNull(ComponentStorage.loadComponent(null, first.assetId()));
        assertEquals(ComponentPackageIO.TrainingPermission.DENIED, ComponentPackageInstallations.inspect(root, first.assetId()).manifest().trainingPermission());
        assertEquals("changed", JsonParser.parseString(Files.readString(record)).getAsJsonObject().getAsJsonObject("manifest").get("license").getAsString());
    }
    @Test void ordinaryImportRejectsInvalidContextAndEnvironmentBeforeWriting() {
        assertThrows(IOException.class, () -> ComponentPackageInstallations.importPackage(source, environment, null));
        assertThrows(IOException.class, () -> ComponentPackageInstallations.importPackage(source,
                new ComponentPackageIO.Environment("wrong", Map.of()), global()));
        assertFalse(Files.exists(root));
    }
}
