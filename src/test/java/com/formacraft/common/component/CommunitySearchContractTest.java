package com.formacraft.common.component;

import com.formacraft.common.component.community.*;
import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CommunitySearchContractTest {
    private static JsonObject request() {
        return JsonParser.parseString("""
                {"contract":"formacraft.community.search.v1","requestId":"request","needId":"window","query":{}}
                """).getAsJsonObject();
    }
    private static CommunitySearchContract.Request decode(String value) throws Exception {
        return CommunitySearchContract.decode(value.getBytes(StandardCharsets.UTF_8));
    }
    private static CommunitySearchContract.Request decode(JsonObject value) throws Exception { return decode(value.toString()); }
    private static void failure(String code, org.junit.jupiter.api.function.Executable operation) {
        assertEquals(code, assertThrows(CommunityPackages.Failure.class, operation).code());
    }

    @Test void proposedExamplePreservesAllConditionsButMockRefusesToDropThem() throws Exception {
        var parsed = CommunitySearchContract.decode(Files.readAllBytes(Path.of("docs/community-examples/search-request.json")));
        assertEquals(7, parsed.query().targetSize().y());
        assertEquals(Set.of("pointed_arch"), parsed.query().visualIntent().morphology());
        assertEquals("complete", parsed.environment().completeness());
        failure("unsupported_constraint", () -> CommunitySearchContract.search(new OfflineCommunityIndex("test", Clock.systemUTC()), parsed));
        assertThrows(UnsupportedOperationException.class, () -> parsed.fields().clear());
        assertThrows(UnsupportedOperationException.class, () -> parsed.query().softTags().clear());
    }

    @Test void rejectsDuplicatePropertiesCommentsTrailingDataAndMalformedUnicode() throws Exception {
        String valid = request().toString();
        for (String bad : List.of(valid.replace("\"query\":{}", "\"query\":{},\"query\":{}"),
                valid.replace("\"query\":{}", "\"query\":{\"role\":\"a\",\"role\":\"b\"}"),
                valid + " {}", "/*comment*/" + valid, valid.replace("\"query\":{}", "\"query\":{\"role\":'window'}"),
                valid.replace("\"query\":{}", "\"query\":{\"role\":\"\\uD800\"}"), "\ufeff" + valid))
            failure("invalid_request", () -> decode(bad));
        byte[] bytes = valid.getBytes(StandardCharsets.UTF_8); bytes[0] = (byte) 0xff;
        failure("invalid_request", () -> CommunitySearchContract.decode(bytes));
    }

    @Test void limitsBytesDepthAndNodesBeforeContractExecution() {
        failure("request_too_large", () -> CommunitySearchContract.decode(new byte[CommunitySearchContract.MAX_REQUEST_BYTES + 1]));
        String deep = "{\"query\":" + "[".repeat(18) + "0" + "]".repeat(18) + "}";
        failure("invalid_request", () -> decode(deep));
        String wide = "{\"query\":[" + "0,".repeat(4100) + "0]}";
        failure("invalid_request", () -> decode(wide));
    }

    @Test void rejectsUnknownFieldsAtEveryObjectBoundaryAndUnsupportedContract() {
        var root = request(); root.addProperty("contract", "future"); failure("unsupported_contract", () -> decode(root));
        for (String json : List.of("{\"mystery\":true}", "{\"visualIntent\":{\"script\":\"x\"}}",
                "{\"targetSize\":{\"worldPosition\":1}}", "{\"suitePreference\":{\"fallback\":true}}")) {
            var bad = request(); bad.add("query", JsonParser.parseString(json));
            failure("unsupported_constraint", () -> decode(bad));
        }
        var bad = request(); bad.add("environment", JsonParser.parseString("{\"token\":\"secret\"}"));
        failure("unsupported_constraint", () -> decode(bad));
    }

    @Test void rejectsCoercionFractionsNullArraysAndInconsistentFailurePolicies() {
        for (String field : List.of("\"pageSize\":\"10\"", "\"pageSize\":1.5", "\"pageSize\":0", "\"pageSize\":2147483648", "\"fields\":null")) {
            String bad = request().toString().replace("\"query\":{}", "\"query\":{}," + field);
            failure("invalid_request", () -> decode(bad));
        }
        for (String query : List.of("{\"required\":\"true\"}", "{\"hardTags\":null}", "{\"hardTags\":[\"a\",\"a\"]}",
                "{\"exactTypeKey\":12}", "{\"exactTypeKey\":\"Column\"}")) {
            var bad = request(); bad.add("query", JsonParser.parseString(query)); failure("invalid_request", () -> decode(bad));
        }
        var bad = request(); bad.add("query", JsonParser.parseString("{\"required\":false,\"failurePolicy\":\"report_required_failure\"}"));
        failure("unsupported_constraint", () -> decode(bad));
    }

    @Test void positiveBlockCoordinatesRequireAllAxesAndKnownFrame() throws Exception {
        var root = request();
        root.add("query", JsonParser.parseString("{\"targetSize\":{\"frame\":\"component_xyz\",\"unit\":\"block\",\"x\":null,\"y\":7,\"z\":1}}"));
        assertNull(decode(root).query().targetSize().x());
        var size = root.getAsJsonObject("query").getAsJsonObject("targetSize");
        size.addProperty("x", 0); failure("invalid_request", () -> decode(root));
        size.remove("x"); failure("invalid_request", () -> decode(root));
        size.add("x", JsonNull.INSTANCE); size.addProperty("frame", "world_xyz"); failure("unsupported_constraint", () -> decode(root));
    }

    @Test void queryFingerprintNormalizesOrderingAndDefaultsButKeepsEnvironmentAndProjection() throws Exception {
        var a = request(); a.add("query", JsonParser.parseString("{\"hardTags\":[\"a\",\"b\"]}"));
        var b = request(); b.add("query", JsonParser.parseString("{\"hardTags\":[\"b\",\"a\"],\"sizePolicy\":\"fixed\",\"required\":true}"));
        b.addProperty("requestId", "different"); b.addProperty("needId", "other"); b.addProperty("cursor", "opaque"); b.addProperty("pageSize", 2);
        assertEquals(decode(a).queryFingerprint(), decode(b).queryFingerprint());
        b.add("fields", JsonParser.parseString("[\"identity\"]")); assertNotEquals(decode(a).queryFingerprint(), decode(b).queryFingerprint());
        b.remove("fields"); b.add("environment", JsonParser.parseString("{\"minecraftVersion\":\"1.21.10\",\"installedMods\":{},\"completeness\":\"partial\"}"));
        assertNotEquals(decode(a).queryFingerprint(), decode(b).queryFingerprint());
        failure("unsupported_capability", () -> CommunitySearchContract.search(new OfflineCommunityIndex("test", Clock.systemUTC()), decode(b)));
    }

    @Test void aggregateTagAndModLimitsAndUnicodeScalarBoundsAreEnforced() throws Exception {
        var root = request(); var query = root.getAsJsonObject("query");
        var tags = new JsonArray(); for (int i = 0; i < 64; i++) tags.add("tag" + i);
        query.add("hardTags", tags); query.add("softTags", JsonParser.parseString("[\"extra\"]"));
        failure("invalid_request", () -> decode(root)); query.remove("softTags");
        query.addProperty("keywords", "😀".repeat(256)); assertEquals(512, decode(root).query().keywords().length());
        query.addProperty("keywords", "😀".repeat(257)); failure("invalid_request", () -> decode(root));
        query.remove("keywords"); var env = new JsonObject(); env.addProperty("minecraftVersion", "1.21.10"); env.addProperty("completeness", "complete");
        var mods = new JsonObject(); for (int i = 0; i < 129; i++) mods.addProperty("mod" + i, "1"); env.add("installedMods", mods); root.add("environment", env);
        failure("invalid_request", () -> decode(root));
    }

    @Test void bridgePaginationBindsProjectionAndDoesNotMixInternalCursors() throws Exception {
        var index = new OfflineCommunityIndex("test", Clock.systemUTC());
        for (int i = 0; i < 2; i++) {
            var def = new ComponentDefinition(); def.id = "column"; def.category = ComponentCategory.COLUMN;
            def.size = new ComponentDefinition.Size(); def.size.w = def.size.h = def.size.d = 1;
            def.anchor = new ComponentDefinition.Anchor(); def.anchor.facing = "SOUTH";
            var block = new ComponentDefinition.BlockEntry(); block.block = i == 0 ? "minecraft:stone" : "minecraft:glass"; def.blocks = List.of(block);
            var env = new ComponentPackageIO.Environment("1.21.10", Map.of());
            var manifest = new ComponentPackageIO.Manifest("author", "1", "Author", "LicenseRef-Test", null, "1.21.10", Map.of(), Map.of(), List.of());
            var pack = ComponentPackageIO.create(def, manifest, env); byte[] bytes = pack.toBytes();
            var ref = new CommunityPackages.ReleaseRef("test", "res", "rel_" + i, CommunityPackages.sha256(bytes), pack.sha256(), pack.manifestSha256());
            index.add(new CommunityPackages.Descriptor(ref, bytes.length, ComponentPackageIO.SCHEMA, ComponentContentIdentity.fingerprints(def)), bytes);
        }
        var root = request(); root.addProperty("pageSize", 1);
        var first = CommunitySearchContract.search(index, decode(root)); assertNotNull(first.nextCursor());
        root.addProperty("cursor", first.nextCursor());
        assertEquals(1, CommunitySearchContract.search(index, decode(root)).items().size());
        root.add("fields", JsonParser.parseString("[\"identity\"]"));
        failure("cursor_mismatch", () -> CommunitySearchContract.search(index, decode(root)));
        failure("cursor_mismatch", () -> index.search(new OfflineCommunityIndex.Query(null, Set.of()), 1, first.nextCursor()));
    }
}
