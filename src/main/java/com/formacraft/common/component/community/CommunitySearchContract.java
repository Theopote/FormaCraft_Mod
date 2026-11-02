package com.formacraft.common.component.community;

import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.formacraft.common.component.community.CommunityJson.*;

/** v1 request decoding. A decoded condition is never implicitly discarded by an executor. */
public final class CommunitySearchContract {
    private CommunitySearchContract() {}
    public static final String CONTRACT = "formacraft.community.search.v1";
    public static final int MAX_REQUEST_BYTES = 32 * 1024;
    private static final Set<String> DEFAULT_FIELDS = Set.of("identity", "package", "type", "rights", "evidence");
    private static final Set<String> FIELDS = Set.of("identity", "package", "type", "size", "adaptation", "installation", "rights", "evidence");
    public record Size(Integer x, Integer y, Integer z) {}
    public record VisualIntent(Set<String> morphology, Set<String> materialAppearance) {
        public VisualIntent { morphology = Set.copyOf(morphology); materialAppearance = Set.copyOf(materialAppearance); }
    }
    public record Suite(String key, boolean required) {}
    public record Environment(String minecraftVersion, Map<String, String> installedMods, String completeness) {
        public Environment { installedMods = Map.copyOf(installedMods); }
    }
    public record Query(String exactTypeKey, String role, String keywords, Set<String> softTags, Set<String> hardTags,
                        Size targetSize, String sizePolicy, String attachment, VisualIntent visualIntent,
                        Suite suitePreference, boolean required, String failurePolicy) {
        public Query { softTags = Set.copyOf(softTags); hardTags = Set.copyOf(hardTags); }
    }
    /** Constructed only by decoding, so callers cannot bypass contract validation. */
    public static final class Request {
        private final String requestId, needId, cursor;
        private final Query query;
        private final Environment environment;
        private final Set<String> fields;
        private final int pageSize;
        private Request(String requestId, String needId, Query query, Environment environment, Set<String> fields, int pageSize, String cursor) {
            this.requestId = requestId; this.needId = needId; this.query = query; this.environment = environment;
            this.fields = Set.copyOf(fields); this.pageSize = pageSize; this.cursor = cursor;
        }
        public String requestId() { return requestId; }
        public String needId() { return needId; }
        public Query query() { return query; }
        public Environment environment() { return environment; }
        public Set<String> fields() { return fields; }
        public int pageSize() { return pageSize; }
        public String cursor() { return cursor; }
        /** Semantic context excludes routing IDs, page size and cursor; collections are canonicalized. */
        public String queryFingerprint() {
            var root = new JsonObject(); root.add("query", canonicalQuery(query));
            root.add("environment", environmentJson(environment)); root.add("fields", array(fields));
            root.addProperty("contract", CONTRACT); root.addProperty("rankingPolicy", "offline-exact.v1");
            return CommunityPackages.sha256(root.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    public static Request decode(byte[] bytes) throws IOException {
        JsonObject root = read(bytes, MAX_REQUEST_BYTES);
        keys(root, "contract", "requestId", "needId", "query", "environment", "fields", "pageSize", "cursor");
        if (!CONTRACT.equals(text(root.get("contract"), 128))) throw new CommunityPackages.Failure("unsupported_contract");
        String requestId = text(root.get("requestId"), 128), needId = text(root.get("needId"), 128);
        Query query = query(object(root.get("query")));
        Environment environment = absent(root.get("environment")) ? null : environment(object(root.get("environment")));
        Set<String> fields = root.has("fields") ? strings(root.get("fields"), FIELDS.size(), 32) : DEFAULT_FIELDS;
        if (fields.isEmpty() || !FIELDS.containsAll(fields)) throw new CommunityPackages.Failure("unsupported_capability");
        int pageSize = root.has("pageSize") ? integer(root.get("pageSize"), 1, 20) : 10;
        String cursor = optionalText(root.get("cursor"), 256);
        return new Request(requestId, needId, query, environment, fields, pageSize, cursor);
    }

    private static Query query(JsonObject q) throws IOException {
        keys(q, "exactTypeKey", "role", "keywords", "softTags", "hardTags", "targetSize", "sizePolicy", "attachment",
                "visualIntent", "suitePreference", "required", "failurePolicy");
        String type = optionalText(q.get("exactTypeKey"), 128);
        if (type != null && !type.matches("(?:formacraft:[a-z0-9_]+|custom:[0-9a-f]{64})")) throw new CommunityPackages.Failure("invalid_request");
        Set<String> soft = strings(q.get("softTags"), 64, 128), hard = strings(q.get("hardTags"), 64, 128);
        Size size = null;
        if (!absent(q.get("targetSize"))) {
            JsonObject s = object(q.get("targetSize")); keys(s, "frame", "unit", "x", "y", "z");
            if (!"component_xyz".equals(text(s.get("frame"), 32)) || !"block".equals(text(s.get("unit"), 32)))
                throw new CommunityPackages.Failure("unsupported_constraint");
            size = new Size(coordinate(s, "x"), coordinate(s, "y"), coordinate(s, "z"));
        }
        String sizePolicy = q.has("sizePolicy") ? text(q.get("sizePolicy"), 32) : "fixed";
        if (!Set.of("fixed", "negotiable").contains(sizePolicy)) throw new CommunityPackages.Failure("unsupported_constraint");
        String attachment = optionalText(q.get("attachment"), 32);
        if (attachment != null && !Set.of("NONE", "FLOOR", "WALL_SURFACE", "WALL_OPENING", "EDGE", "ROOF_SURFACE", "ROOF_EDGE", "ROOF_RIDGE", "CORNER").contains(attachment))
            throw new CommunityPackages.Failure("unsupported_constraint");
        VisualIntent visual = null;
        if (!absent(q.get("visualIntent"))) {
            JsonObject v = object(q.get("visualIntent")); keys(v, "morphology", "materialAppearance");
            visual = new VisualIntent(strings(v.get("morphology"), 64, 128), strings(v.get("materialAppearance"), 64, 128));
        }
        int tags = soft.size() + hard.size() + (visual == null ? 0 : visual.morphology.size() + visual.materialAppearance.size());
        if (tags > 64) throw new CommunityPackages.Failure("invalid_request");
        Suite suite = null;
        if (!absent(q.get("suitePreference"))) {
            JsonObject s = object(q.get("suitePreference")); keys(s, "key", "required");
            suite = new Suite(text(s.get("key"), 128), bool(s.get("required")));
        }
        boolean required = q.has("required") ? bool(q.get("required")) : true;
        String expectedFailure = required ? "report_required_failure" : "report_optional_omission";
        String policy = q.has("failurePolicy") ? text(q.get("failurePolicy"), 64) : expectedFailure;
        if (!policy.equals(expectedFailure)) throw new CommunityPackages.Failure("unsupported_constraint");
        return new Query(type, optionalText(q.get("role"), 128), optionalText(q.get("keywords"), 256), soft, hard, size,
                sizePolicy, attachment, visual, suite, required, policy);
    }
    private static Integer coordinate(JsonObject root, String key) throws IOException {
        if (!root.has(key)) throw new CommunityPackages.Failure("invalid_request");
        return root.get(key).isJsonNull() ? null : integer(root.get(key), 1, Integer.MAX_VALUE);
    }
    private static Environment environment(JsonObject e) throws IOException {
        keys(e, "minecraftVersion", "installedMods", "completeness");
        String version = text(e.get("minecraftVersion"), 64), completeness = text(e.get("completeness"), 16);
        if (!Set.of("complete", "partial").contains(completeness)) throw new CommunityPackages.Failure("unsupported_constraint");
        JsonObject mods = object(e.get("installedMods"));
        if (mods.size() > 128) throw new CommunityPackages.Failure("invalid_request");
        Map<String, String> values = new TreeMap<>();
        for (var item : mods.entrySet()) {
            if (!item.getKey().matches("[a-z][a-z0-9_-]{0,63}") || item.getKey().equals("minecraft"))
                throw new CommunityPackages.Failure("invalid_request");
            values.put(item.getKey(), text(item.getValue(), 128));
        }
        return new Environment(version, values, completeness);
    }

    /** Internal bridge only: returns an internal page, never claims to serialize a v1 response. */
    public static OfflineCommunityIndex.Page search(OfflineCommunityIndex index, Request request) throws IOException {
        return search(index, request, groups -> true);
    }

    static OfflineCommunityIndex.Page search(OfflineCommunityIndex index, Request request,
            java.util.function.Predicate<List<OfflineCommunityIndex.Group>> fits) throws IOException {
        requireOfflineCapabilities(request);
        Query q = request.query;
        return index.search(new OfflineCommunityIndex.Query(q.exactTypeKey, q.hardTags), request.pageSize, request.cursor, request.queryFingerprint(), fits);
    }

    static void requireOfflineCapabilities(Request request) throws IOException {
        Query q = request.query;
        if (q.role != null || q.targetSize != null || q.attachment != null || q.suitePreference != null || !q.sizePolicy.equals("fixed"))
            throw new CommunityPackages.Failure("unsupported_constraint");
        if (request.environment != null || q.keywords != null || !q.softTags.isEmpty()
                || q.visualIntent != null && (!q.visualIntent.morphology.isEmpty() || !q.visualIntent.materialAppearance.isEmpty())
                || !DEFAULT_FIELDS.containsAll(request.fields)) throw new CommunityPackages.Failure("unsupported_capability");
    }

    private static JsonObject canonicalQuery(Query q) {
        var v = new JsonObject(); v.addProperty("exactTypeKey", q.exactTypeKey); v.addProperty("role", q.role);
        v.addProperty("keywords", q.keywords); v.add("softTags", array(q.softTags)); v.add("hardTags", array(q.hardTags));
        if (q.targetSize == null) v.add("targetSize", JsonNull.INSTANCE);
        else { var s = new JsonObject(); s.addProperty("x", q.targetSize.x); s.addProperty("y", q.targetSize.y); s.addProperty("z", q.targetSize.z); v.add("targetSize", s); }
        v.addProperty("sizePolicy", q.sizePolicy); v.addProperty("attachment", q.attachment);
        if (q.visualIntent == null || q.visualIntent.morphology.isEmpty() && q.visualIntent.materialAppearance.isEmpty()) v.add("visualIntent", JsonNull.INSTANCE);
        else { var i = new JsonObject(); i.add("morphology", array(q.visualIntent.morphology)); i.add("materialAppearance", array(q.visualIntent.materialAppearance)); v.add("visualIntent", i); }
        if (q.suitePreference == null) v.add("suitePreference", JsonNull.INSTANCE);
        else { var s = new JsonObject(); s.addProperty("key", q.suitePreference.key); s.addProperty("required", q.suitePreference.required); v.add("suitePreference", s); }
        v.addProperty("required", q.required); v.addProperty("failurePolicy", q.failurePolicy); return v;
    }
    private static JsonElement environmentJson(Environment e) {
        if (e == null) return JsonNull.INSTANCE;
        var value = new JsonObject(); value.addProperty("minecraftVersion", e.minecraftVersion); value.addProperty("completeness", e.completeness);
        var mods = new JsonObject(); new TreeMap<>(e.installedMods).forEach(mods::addProperty); value.add("installedMods", mods); return value;
    }
    private static JsonArray array(Set<String> values) {
        var array = new JsonArray(); new TreeSet<>(values).forEach(array::add); return array;
    }
}
