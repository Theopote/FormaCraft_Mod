package com.formacraft.common.component;

import com.formacraft.common.component.query.*;
import com.formacraft.common.component.variant.CaptureAdaptationSettings;
import com.formacraft.common.component.transform.Mirror;
import com.formacraft.common.json.JsonUtil;
import com.formacraft.common.llm.dto.*;
import com.formacraft.server.patch.ComponentPatchPreviewBuilder;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real temporary catalog -> retrieval -> fit -> coordination -> shared geometry emitter. */
class ComponentLibraryAcceptanceTest {
    @TempDir Path library;
    private final Map<Path, String> originals = new LinkedHashMap<>();

    @BeforeAll static void bootstrap() { com.formacraft.test.MinecraftRegistryTestBootstrap.initialize(); }
    @BeforeEach void installTemporaryCatalog() throws Exception {
        ComponentStorage.setGlobalComponentDirOverrideForTest(library);
        var catalog = new ComponentCatalog(); catalog.components = new ArrayList<>();
        for (var role : List.of("window", "railing")) {
            for (boolean adaptable : new boolean[]{true, false}) {
                var def = asset(role, adaptable);
                Path file = library.resolve(def.id + ".json");
                String json = JsonUtil.toJson(def); Files.writeString(file, json); originals.put(file, json);
                var entry = new ComponentCatalog.Entry(); entry.id = def.id; entry.name = def.name;
                entry.category = def.category; entry.tags = def.tags; entry.size = def.size;
                entry.variantRules = def.variantRules; entry.file = def.id + ".json";
                catalog.components.add(entry);
            }
        }
        Files.writeString(library.resolve("catalog.json"), JsonUtil.toJson(catalog));
    }
    @AfterEach void restoreLibrary() {
        ComponentStorage.setGlobalComponentDirOverrideForTest(null);
        ComponentRetriever.clearViewerPlayer();
    }

    private ComponentDefinition asset(String role, boolean adaptable) {
        var def = new ComponentDefinition();
        def.id = "accept_" + role + (adaptable ? "_a" : "_other"); def.name = def.id;
        def.category = role.equals("window") ? ComponentCategory.WINDOW : ComponentCategory.RAILING;
        def.sourceOrigin = "CAPTURED"; def.culturalStyle = "GOTHIC"; def.archetypeRef = def.id;
        def.geometryArchetype = role.equals("window") ? "FRAME" : "LINEAR";
        def.tags = List.of("role:" + role, "gothic", "suite:demo/" + (adaptable ? "courtyard" : role));
        def.size = new ComponentDefinition.Size(); def.size.w = adaptable ? 5 : 6; def.size.h = 3; def.size.d = 1;
        def.anchor = new ComponentDefinition.Anchor(); def.anchor.facing = "SOUTH";
        def.blocks = new ArrayList<>();
        for (int x = 0; x < def.size.w; x++) for (int y = 0; y < 3; y++) {
            var b = new ComponentDefinition.BlockEntry(); b.dx = x; b.dy = y;
            b.block = x == 0 ? "minecraft:oak_planks" : x == def.size.w - 1 ? "minecraft:gold_block"
                    : role.equals("window") && y == 1 ? "minecraft:glass" : "minecraft:stone";
            def.blocks.add(b);
        }
        var settings = new CaptureAdaptationSettings();
        if (adaptable) settings.modes[0] = CaptureAdaptationSettings.Mode.REPEAT;
        def.variantRules = settings.toRules(def);
        return def;
    }
    private ComponentQuery query(String role, int width) {
        var q = new ComponentQuery(); q.semantic.role = role;
        q.constraints.mustHave = Set.of("role:" + role);
        q.style.styleProfile = "Gothic";
        q.geometry.openingWidth = width; q.geometry.openingHeight = 3; q.geometry.targetDepth = 1;
        return q;
    }
    private Component request(String role, int width) {
        return new Component("DECOR_DETAIL", "house", new Vec3i(0, 0, 0), new Dimensions(width, 3, 1),
                List.of("component_request:" + JsonUtil.toJson(Map.of("component_query", query(role, width)))),
                Map.of("component_id", role, "building_id", "acceptance_house"));
    }

    @Test void realCatalogCoordinatesDifferentRolesAndEmitsTheChosenAdaptedGeometry() throws Exception {
        for (String role : List.of("window", "railing")) {
            var independent = ComponentRetriever.retrieveBestResolved(query(role, 6), new Random(7));
            assertTrue(independent.ok(), independent.message());
            assertEquals("accept_" + role + "_other", independent.sourceId());
        }
        var window = request("window", 6); var rail = request("railing", 6);
        try (var scope = ComponentLibraryBatchContext.open(List.of(window, rail), 7)) {
            assertEquals("demo/courtyard", scope.plans().get("building:acceptance_house").suite());
            for (var request : List.of(window, rail)) {
                var resolved = PlayerComponentExpander.resolveSingleRequest(request, null, Map.of());
                assertTrue(resolved.ok(), resolved.message());
                var def = resolved.definition(); assertEquals("accept_" + request.params().get("component_id") + "_a", def.id);
                assertEquals(6, def.size.w); assertTrue(def.isCapturedOrigin());
                assertTrue(def.tags.contains("suite:demo/courtyard"));
                assertEquals(3, def.blocks.stream().filter(b -> b.dx == 0 && b.block.equals("minecraft:oak_planks")).count());
                assertEquals(3, def.blocks.stream().filter(b -> b.dx == 5 && b.block.equals("minecraft:gold_block")).count());
                var geometry = ComponentGeometryResolver.emit(def, 0, 0, 0, Direction.EAST, Mirror.NONE, false, null, 7);
                assertTrue(geometry.ok(), geometry.message()); assertEquals(18, geometry.patches().size());
                var preview = ComponentPatchPreviewBuilder.fromComponentDefinition(def, Direction.EAST, Mirror.NONE, false, null, 7);
                assertEquals(geometry.patches(), preview);
            }
        }
        for (var original : originals.entrySet()) assertEquals(original.getValue(), Files.readString(original.getKey()));
    }

    @Test void impossibleQueryRetainsAdaptFailureThroughTheRealPreparationPath() {
        var window = request("window", 2); var rail = request("railing", 6);
        try (var scope = ComponentLibraryBatchContext.open(List.of(window, rail), 7)) {
            assertFalse(scope.plans().get("building:acceptance_house").complete());
            assertEquals(ComponentResolveResult.Status.ADAPT_FAILED,
                    PlayerComponentExpander.resolveSingleRequest(window, null, Map.of()).status());
        }
    }

    @Test void authorRulesProduceConcreteCapabilitiesAndAdaptationRetainsCaptureProvenance() {
        var original = asset("window", true);
        var metadata = ComponentMetadata.fromComponent(original, null);
        assertEquals(Boolean.TRUE, metadata.variantCapability.segmentRepeat);
        assertEquals(Boolean.FALSE, ComponentMetadata.fromComponent(asset("window", false), null).variantCapability.segmentRepeat);
        var fitted = com.formacraft.common.component.model.ComponentModelApi.fitToQuery(original, query("window", 6), null);
        assertNotNull(fitted); assertTrue(fitted.isCapturedOrigin());
        assertEquals(5, original.size.w); assertTrue(original.isCapturedOrigin());
    }
}
