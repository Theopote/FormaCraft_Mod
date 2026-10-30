package com.formacraft.common.component;

import com.formacraft.common.component.variant.CaptureAdaptationSettings;
import com.formacraft.common.json.JsonUtil;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ComponentPackageIOTest {
    @Test void externalArchetypePathIsRejectedBeforeExport() {
        var def = asset(); def.archetypeRef = "../../escape";
        Path file = directory.resolve("unsafe.json");
        assertThrows(IOException.class, () -> ComponentPackageIO.write(file, def, manifest()));
        assertFalse(Files.exists(file));
    }
    @TempDir Path directory;
    private ComponentPackageIO.Manifest manifest() {
        return new ComponentPackageIO.Manifest("alice", "1.0.0", "Alice", "LicenseRef-Test",
                ComponentPackageIO.TrainingPermission.DENIED, "1.21.10", Map.of(), Map.of(), List.of("alice/older_column@0.9"));
    }
    private ComponentPackageIO.Environment environment() { return new ComponentPackageIO.Environment("1.21.10", Map.of()); }
    private ComponentDefinition asset() {
        var def = new ComponentDefinition(); def.id = "column"; def.name = "Column";
        def.category = ComponentCategory.COLUMN; def.sourceOrigin = "CAPTURED";
        def.tags = List.of("suite:alice/courtyard", "role:column");
        def.size = new ComponentDefinition.Size(); def.size.w = def.size.d = 1; def.size.h = 5;
        def.anchor = new ComponentDefinition.Anchor(); def.anchor.facing = "SOUTH";
        def.blocks = new ArrayList<>();
        for (int y = 0; y < 5; y++) {
            var block = new ComponentDefinition.BlockEntry(); block.dy = y; block.block = "minecraft:oak_planks";
            def.blocks.add(block);
        }
        var settings = new CaptureAdaptationSettings(); settings.modes[1] = CaptureAdaptationSettings.Mode.REPEAT;
        settings.setFixedEdge(1, true, 2); def.variantRules = settings.toRules(def);
        return def;
    }

    @Test void roundTripPreservesRulesTagsRightsAndIndependentVerifiedCopies() throws Exception {
        var def = asset(); String original = JsonUtil.toJson(def);
        Path file = directory.resolve("column.fccomponent.json");
        ComponentPackageIO.write(file, def, manifest());
        var verified = ComponentPackageIO.read(file, environment());
        assertEquals("alice/column@1.0.0", verified.assetKey());
        assertTrue(verified.contentRef().endsWith(verified.sha256())); assertEquals(64, verified.sha256().length());
        assertEquals(ComponentPackageIO.TrainingPermission.DENIED, verified.manifest().trainingPermission());
        assertEquals(original, JsonUtil.toJson(verified.component()));
        verified.component().variantRules.scaling.axes.get("Y").fixedEnd = 3;
        assertEquals(2, verified.component().variantRules.scaling.axes.get("Y").fixedEnd);
        assertEquals(original, JsonUtil.toJson(def));
        try (var files = Files.list(directory)) { assertEquals(1, files.count()); }
    }

    @Test void existingExportTargetIsNeverOverwritten() throws Exception {
        Path file = directory.resolve("existing.json"); Files.writeString(file, "keep");
        assertThrows(FileAlreadyExistsException.class, () -> ComponentPackageIO.write(file, asset(), manifest()));
        assertEquals("keep", Files.readString(file));
    }

    @Test void alteredPayloadAndUnknownSchemaAreRejected() throws Exception {
        Path file = directory.resolve("tampered.json"); ComponentPackageIO.write(file, asset(), manifest());
        var json = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        json.addProperty("componentJson", json.get("componentJson").getAsString().replace("oak_planks", "stone"));
        Files.writeString(file, json.toString());
        assertTrue(assertThrows(IOException.class, () -> ComponentPackageIO.read(file, environment())).getMessage().contains("hash"));
        json.addProperty("schema", "future.package.v9"); Files.writeString(file, json.toString());
        assertTrue(assertThrows(IOException.class, () -> ComponentPackageIO.read(file, environment())).getMessage().contains("schema"));
    }

    @Test void namespaceOwnerMappingSupportsModIdsDifferentFromBlockNamespaces() throws Exception {
        var def = asset(); def.blocks.getFirst().block = "ornament:carved_wood";
        var modded = new ComponentPackageIO.Manifest("alice", "1", "Alice", "LicenseRef-Test", null,
                "1.21.10", Map.of("decor_mod", "2.0"), Map.of("ornament", "decor_mod"), List.of());
        Path file = directory.resolve("modded.json"); ComponentPackageIO.write(file, def, modded);
        assertThrows(IOException.class, () -> ComponentPackageIO.read(file, environment()));
        assertThrows(IOException.class, () -> ComponentPackageIO.read(file,
                new ComponentPackageIO.Environment("1.21.10", Map.of("decor_mod", "1.0"))));
        var verified = ComponentPackageIO.read(file, new ComponentPackageIO.Environment("1.21.10", Map.of("decor_mod", "2.0")));
        assertEquals(ComponentPackageIO.TrainingPermission.UNSPECIFIED, verified.manifest().trainingPermission());
        assertEquals("ornament:carved_wood", verified.component().blocks.getFirst().block);
        assertThrows(IOException.class, () -> ComponentPackageIO.read(file,
                new ComponentPackageIO.Environment("1.21.11", Map.of("decor_mod", "2.0"))));
    }

    @Test void invalidIdsAndUndeclaredDependenciesFailBeforeCreatingTarget() throws Exception {
        Path file = directory.resolve("invalid.json"); var def = asset(); def.id = "../escape";
        assertThrows(IOException.class, () -> ComponentPackageIO.write(file, def, manifest())); assertFalse(Files.exists(file));
        def.id = "column"; def.blocks.getFirst().block = "unknown:wood";
        assertThrows(IOException.class, () -> ComponentPackageIO.write(file, def, manifest())); assertFalse(Files.exists(file));
    }

    @Test void oversizedFilesAndBlockCountsAreRejected() throws Exception {
        Path file = directory.resolve("oversized.json"); Files.write(file, new byte[ComponentPackageIO.MAX_PACKAGE_BYTES + 1]);
        assertTrue(assertThrows(IOException.class, () -> ComponentPackageIO.read(file, environment())).getMessage().contains("byte limit"));
        var def = asset(); def.blocks = Collections.nCopies(ComponentIds.MAX_BLOCKS + 1, def.blocks.getFirst());
        Path target = directory.resolve("too_many_blocks.json");
        assertThrows(IOException.class, () -> ComponentPackageIO.write(target, def, manifest())); assertFalse(Files.exists(target));
    }

    @Test void invalidUtf8InMetadataIsNotSilentlyReplaced() throws Exception {
        Path file = directory.resolve("bad_encoding.json"); ComponentPackageIO.write(file, asset(), manifest());
        String json = Files.readString(file); int author = json.indexOf("Alice"); assertTrue(author >= 0);
        var bytes = new java.io.ByteArrayOutputStream();
        bytes.write(json.substring(0, author).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        bytes.write(new byte[]{(byte) 0xc3, 0x28});
        bytes.write(json.substring(author + 5).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Files.write(file, bytes.toByteArray());
        assertThrows(IOException.class, () -> ComponentPackageIO.read(file, environment()));
    }

    @Test void unknownTrainingGrantIsRejectedInsteadOfInventingPermission() throws Exception {
        Path file = directory.resolve("bad_permission.json"); ComponentPackageIO.write(file, asset(), manifest());
        var json = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        json.getAsJsonObject("manifest").addProperty("trainingPermission", "SOMETHING_NEW");
        Files.writeString(file, json.toString());
        assertTrue(assertThrows(IOException.class, () -> ComponentPackageIO.read(file, environment())).getMessage().contains("training"));
    }
}
