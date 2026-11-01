package com.formacraft.common.component;

import com.formacraft.common.component.query.ComponentQueryMatchUtil;
import com.formacraft.common.json.JsonUtil;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ComponentSourceViewTest {
    @TempDir Path temporary;
    private Path root;
    private final ComponentPackageIO.Environment environment = new ComponentPackageIO.Environment("1.21.10", Map.of());
    @BeforeAll static void bootstrap() { com.formacraft.test.MinecraftRegistryTestBootstrap.initialize(); }
    @BeforeEach void setup() { root = temporary.resolve("library"); ComponentStorage.setGlobalComponentDirOverrideForTest(root); }
    @AfterEach void restore() { ComponentStorage.setGlobalComponentDirOverrideForTest(null); }
    private Path pack(String name, boolean secondary) throws Exception {
        var d = new ComponentDefinition(); d.id = "window"; d.name = name; d.sourceOrigin = "CAPTURED";
        d.category = ComponentCategory.WINDOW; d.tags = List.of(secondary ? "suite:bob/other" : "suite:alice/main");
        d.size = new ComponentDefinition.Size(); d.size.w = d.size.h = d.size.d = 1;
        d.anchor = new ComponentDefinition.Anchor(); d.anchor.facing = "SOUTH";
        var b = new ComponentDefinition.BlockEntry(); b.block = "minecraft:stone"; d.blocks = List.of(b);
        Path file = temporary.resolve(UUID.randomUUID() + ".fccomponent.json");
        ComponentPackageIO.write(file, d, new ComponentPackageIO.Manifest(secondary ? "bob" : "alice", "1", secondary ? "Bob" : "Alice",
                secondary ? "LicenseRef-Bob" : "LicenseRef-Alice", secondary ? ComponentPackageIO.TrainingPermission.ALLOWED : ComponentPackageIO.TrainingPermission.DENIED,
                "1.21.10", Map.of(), Map.of(), List.of()));
        return file;
    }
    private String installTwo() throws Exception {
        var first = ComponentPackageInstallations.importPackage(pack("Window", false), environment, ComponentStorage.SaveContext.global(null));
        var second = ComponentPackageInstallations.importPackage(pack("Ｂｏｂ－Ｗｉｎｄｏｗ", true), environment, ComponentStorage.SaveContext.global(null));
        assertEquals(first.assetId(), second.assetId()); return first.assetId();
    }
    @Test void aliasesAreSearchableButDoNotBecomeConstraintTagsAndRightsStaySeparate() throws Exception {
        String id = installTwo(); var entry = ComponentStorage.loadCatalog(null).components.getFirst();
        assertEquals(2, entry.sourceCount); assertFalse(entry.sourceMetadataError);
        assertTrue(ComponentQueryMatchUtil.nameSearchMatches("bob window", entry.name, entry.aliases));
        assertEquals(List.of("suite:alice/main"), entry.tags);
        var primary = ComponentSourceView.query(null, null, id, 0);
        var extra = ComponentSourceView.query(null, null, id, 1);
        assertNull(primary.error()); assertNull(extra.error()); assertTrue(primary.primary()); assertFalse(extra.primary());
        assertEquals("Alice", primary.author()); assertEquals("LicenseRef-Alice", primary.license());
        assertEquals(ComponentPackageIO.TrainingPermission.DENIED, primary.trainingPermission());
        assertEquals("Bob", extra.author()); assertEquals(ComponentPackageIO.TrainingPermission.ALLOWED, extra.trainingPermission());
        String json = JsonUtil.toJson(extra);
        assertFalse(json.contains("minecraft:stone")); assertFalse(json.contains(root.toString()));
        assertTrue(json.length() < com.formacraft.common.network.ComponentSourceNetworking.MAX_JSON);
    }
    @Test void privateSourcesCannotBeReadByOtherPlayersAndBadPagesAreUnavailable() throws Exception {
        UUID owner = UUID.randomUUID();
        var ctx = ComponentStorage.SaveContext.forPlayer(null, owner, true, false);
        String id = ComponentPackageInstallations.importPackage(pack("Private", false), environment, ctx).assetId();
        assertNull(ComponentSourceView.query(null, owner, id, 0).error());
        var hidden = ComponentSourceView.query(null, UUID.randomUUID(), id, 0);
        assertNotNull(hidden.error()); assertNull(hidden.author()); assertEquals(0, hidden.total());
        assertNotNull(ComponentSourceView.query(null, owner, id, 1).error());
        assertNotNull(ComponentSourceView.query(null, owner, id, -1).error());
        assertNotNull(ComponentSourceView.query(null, owner, id, 129).error());
        assertNotNull(ComponentSourceView.query(null, owner, "../escape", 0).error());
    }
    @Test void privateFlatEntryShadowingGlobalPackageDoesNotRevealGlobalSources() throws Exception {
        String id = installTwo(); UUID viewer = UUID.randomUUID();
        Path personal = ComponentStorage.getPlayerComponentDir(viewer); Files.createDirectories(personal);
        var entry = new ComponentCatalog.Entry(); entry.id = id; entry.name = "Local legacy record";
        var catalog = new ComponentCatalog(); catalog.components = List.of(entry);
        Files.writeString(personal.resolve("catalog.json"), JsonUtil.toJson(catalog));
        assertNotNull(ComponentSourceView.query(null, viewer, id, 0).error());
        assertNull(ComponentSourceView.query(null, null, id, 0).error());
    }
    @Test void corruptExtraSourcesCannotPublishAliasesOrRightsButPrimaryStillLoads() throws Exception {
        String id = installTwo(); Path sources = root.resolve("packages").resolve(id).resolve("sources"); Path file;
        try (var files = Files.list(sources)) { file = files.findFirst().orElseThrow(); }
        var json = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        json.getAsJsonObject("manifest").addProperty("license", "tampered"); Files.writeString(file, json.toString());
        var entry = ComponentStorage.loadCatalog(null).components.getFirst();
        assertTrue(entry.sourceMetadataError); assertTrue(entry.aliases.isEmpty()); assertNull(entry.sourceCount);
        assertNotNull(ComponentSourceView.query(null, null, id, 0).error());
        assertNotNull(ComponentStorage.loadComponent(null, id));
        assertEquals(ComponentPackageIO.TrainingPermission.DENIED, ComponentPackageInstallations.inspect(root, id).manifest().trainingPermission());
    }
    @Test void boundedNamesNeverSplitSurrogatePairsAndLegacySearchRemainsCompatible() {
        assertEquals("a".repeat(63), ComponentSourceView.bounded("a".repeat(63) + "😀suffix", 64));
        assertTrue(ComponentQueryMatchUtil.nameSearchMatches("窗", "雕花窗", null));
        assertFalse(ComponentQueryMatchUtil.nameSearchMatches(" ", "Window", List.of("Other")));
    }
}
