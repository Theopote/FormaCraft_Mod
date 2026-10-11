package com.formacraft.common.component;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.UUID;
// Path cast used to disambiguate saveComponent(Path, ...) vs SaveContext

import static org.junit.jupiter.api.Assertions.*;

class ComponentStorageSecurityTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        ComponentStorage.setGlobalComponentDirOverrideForTest(
                tempDir.resolve("formacraft").resolve("components"));
    }

    @AfterEach
    void tearDown() {
        ComponentStorage.setGlobalComponentDirOverrideForTest(null);
    }

    @Test
    void invalidIdDoesNotCreateFilesOutsideComponentsDir() {
        ComponentDefinition def = minimalValidDef();
        def.id = "../escape";

        assertThrows(IllegalArgumentException.class,
                () -> ComponentStorage.saveComponent((Path) null, def));

        assertFalse(Files.exists(tempDir.resolve("escape.json")));
        assertFalse(Files.exists(tempDir.resolve("formacraft").resolve("escape.json")));
    }

    @Test
    void validationErrorDoesNotPersist() {
        ComponentDefinition def = new ComponentDefinition();
        def.id = "bad_comp";
        def.schema = "formacraft.component.v1";
        def.name = "Bad";
        def.blocks = null;

        assertThrows(InvalidComponentException.class,
                () -> ComponentStorage.saveComponent((Path) null, def));

        Path expected = ComponentStorage.getGlobalComponentDir().resolve("bad_comp.json");
        assertFalse(Files.exists(expected));
    }

    @Test
    void validComponentPersistsAtomically() {
        ComponentDefinition def = minimalValidDef();
        def.id = "oak_door";

        ComponentStorage.saveComponent((Path) null, def);

        Path file = ComponentStorage.getGlobalComponentDir().resolve("oak_door.json");
        assertTrue(Files.exists(file));
        ComponentDefinition loaded = ComponentStorage.loadComponent(null, "oak_door");
        assertNotNull(loaded);
        assertEquals("oak_door", loaded.id);
    }

    @Test
    void dedicatedNonOpWritesPlayerNamespaceNotGlobal() {
        UUID player = UUID.randomUUID();
        ComponentDefinition def = minimalValidDef();
        def.id = "player_bench";

        ComponentStorage.SaveContext ctx =
                ComponentStorage.SaveContext.forPlayer(null, player, true, false);
        assertFalse(ctx.writeGlobal());

        ComponentStorage.saveComponentWithContext(ctx, def);

        Path globalFile = ComponentStorage.getGlobalComponentDir().resolve("player_bench.json");
        Path playerFile = ComponentStorage.getPlayerComponentDir(player).resolve("player_bench.json");
        assertFalse(Files.exists(globalFile));
        assertTrue(Files.exists(playerFile));

        ComponentDefinition loaded = ComponentStorage.loadComponent(null, "player_bench", player);
        assertNotNull(loaded);
        assertNull(ComponentStorage.loadComponent(null, "player_bench", null));
    }

    @Test
    void playerCannotOverwriteGlobalId() {
        ComponentDefinition global = minimalValidDef();
        global.id = "shared_door";
        ComponentStorage.saveComponent((Path) null, global);

        UUID player = UUID.randomUUID();
        ComponentDefinition conflict = minimalValidDef();
        conflict.id = "shared_door";
        conflict.name = "Hijack";

        ComponentStorage.SaveContext ctx =
                ComponentStorage.SaveContext.forPlayer(null, player, true, false);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> ComponentStorage.saveComponentWithContext(ctx, conflict));
        assertTrue(ex.getMessage().contains("global"));
    }

    private static ComponentDefinition minimalValidDef() {
        ComponentDefinition def = new ComponentDefinition();
        def.schema = "formacraft.component.v1";
        def.id = "tmp";
        def.name = "Tmp";
        def.size = new ComponentDefinition.Size();
        def.size.w = 1;
        def.size.h = 1;
        def.size.d = 1;
        def.anchor = new ComponentDefinition.Anchor();
        def.anchor.dx = 0;
        def.anchor.dy = 0;
        def.anchor.dz = 0;
        def.anchor.facing = "SOUTH";
        def.blocks = new ArrayList<>();
        ComponentDefinition.BlockEntry b = new ComponentDefinition.BlockEntry();
        b.dx = 0;
        b.dy = 0;
        b.dz = 0;
        b.block = "minecraft:oak_planks";
        def.blocks.add(b);
        return def;
    }
}
