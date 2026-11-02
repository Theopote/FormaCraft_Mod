package com.formacraft.common.component.community;

import com.formacraft.common.component.*;
import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import static com.formacraft.common.component.community.CommunityJson.*;

/** Explicit minimal profile: unknown metadata is never upgraded to verified platform evidence. */
public final class CommunitySearchResponse {
    private CommunitySearchResponse() {}
    public static final int MAX_BYTES = 128 * 1024;
    public static final String CONTRACT = "formacraft.community.search-result.v1", PROFILE = "offline-summary.v1";
    public static final class Decoded {
        private final JsonObject json;
        private final List<CommunityPackages.Descriptor> candidates;
        private Decoded(JsonObject json, List<CommunityPackages.Descriptor> candidates) {
            this.json = json.deepCopy(); this.candidates = List.copyOf(candidates);
        }
        public JsonObject json() { return json.deepCopy(); }
        public List<CommunityPackages.Descriptor> candidates() { return candidates; }
    }

    public static byte[] search(OfflineCommunityIndex index, CommunitySearchContract.Request request, Clock clock) throws IOException {
        var page = CommunitySearchContract.search(index, request, groups ->
                projectItems(request, groups, new JsonArray()).toString().getBytes(StandardCharsets.UTF_8).length <= MAX_BYTES - 4096);
        long remaining = Duration.between(clock.instant(), page.expiresAt()).toMillis();
        if (remaining <= 0) throw new CommunityPackages.Failure("cursor_expired");
        var root = new JsonObject(); root.addProperty("contract", CONTRACT); root.addProperty("profile", PROFILE);
        root.addProperty("requestId", request.requestId()); root.addProperty("needId", request.needId());
        root.addProperty("queryFingerprint", request.queryFingerprint()); root.addProperty("rankingPolicy", "offline-exact.v1");
        var snapshot = new JsonObject(); snapshot.addProperty("id", page.snapshotId());
        snapshot.addProperty("expiresInSeconds", Math.min(300, (remaining + 999) / 1000)); root.add("indexSnapshot", snapshot);
        var truncated = new JsonArray(); root.add("items", projectItems(request, page.items(), truncated));
        root.addProperty("nextCursor", page.nextCursor()); root.add("truncatedFields", truncated);
        byte[] bytes = root.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw new CommunityPackages.Failure("summary_too_large");
        return bytes;
    }
    private static JsonArray projectItems(CommunitySearchContract.Request request, List<OfflineCommunityIndex.Group> groups, JsonArray truncated) {
        var items = new JsonArray();
        for (var group : groups) {
            var source = group.preferredCandidate(); var descriptor = source.descriptor(); var ref = descriptor.releaseRef();
            var item = new JsonObject(); item.addProperty("groupFingerprint", group.configurationFingerprint());
            item.addProperty("matchingSourceCount", group.matchingSourceCount());
            var candidate = new JsonObject(); var release = new JsonObject();
            release.addProperty("authorityId", ref.authorityId()); release.addProperty("resourceId", ref.resourceId()); release.addProperty("releaseId", ref.releaseId());
            release.addProperty("packageSha256", ref.packageSha256()); release.addProperty("payloadSha256", ref.payloadSha256()); release.addProperty("manifestSha256", ref.manifestSha256());
            candidate.add("releaseRef", release); candidate.addProperty("discoveryRevision", 1); candidate.add("platformPublisher", JsonNull.INSTANCE);
            var claim = new JsonObject(); claim.addProperty("namespace", source.sourceClaim().namespace());
            claim.addProperty("componentId", source.sourceClaim().componentId()); claim.addProperty("version", source.sourceClaim().version());
            claim.addProperty("author", source.sourceClaim().author()); candidate.add("sourceClaim", claim);
            var pack = new JsonObject(); pack.addProperty("packageSha256", ref.packageSha256()); pack.addProperty("byteLength", descriptor.byteLength());
            pack.addProperty("mediaType", "application/json"); pack.addProperty("packageSchema", descriptor.packageSchema()); candidate.add("package", pack);
            var fingerprints = new JsonObject(); fingerprints.addProperty("structure", descriptor.fingerprints().structure());
            fingerprints.addProperty("configuration", descriptor.fingerprints().configuration()); candidate.add("fingerprints", fingerprints);
            candidate.addProperty("requiredCapabilitiesStatus", "unknown");
            if (request.fields().contains("identity")) {
                String title = source.title(); if (title == null || title.isBlank()) title = source.sourceClaim().componentId();
                // Display text only. The source package and licensing are never changed.
                title = title.codePoints().filter(c -> !Character.isISOControl(c)).collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
                if (title.isBlank()) title = source.sourceClaim().componentId();
                if (title.codePointCount(0, title.length()) > 256) {
                    title = title.substring(0, title.offsetByCodePoints(0, 256));
                    truncated.add("items[" + items.size() + "].preferredCandidate.title");
                }
                candidate.addProperty("title", title);
            }
            if (request.fields().contains("type")) {
                var type = new JsonObject(); type.addProperty("key", source.typeKey()); type.addProperty("evidence", "declared"); candidate.add("type", type);
            }
            if (request.fields().contains("rights")) {
                var rights = new JsonObject(); rights.addProperty("license", source.license()); rights.addProperty("licenseStatus", "declared");
                rights.addProperty("trainingPermission", source.trainingPermission().name()); candidate.add("rights", rights);
            }
            if (request.fields().contains("evidence")) {
                var validation = new JsonObject(); validation.addProperty("level", "data_validated"); validation.addProperty("runtimeTest", "unknown"); candidate.add("validation", validation);
                var fit = new JsonObject(); fit.addProperty("metadataFit", "unknown"); fit.addProperty("actualFit", "unknown"); candidate.add("fit", fit);
                var compatibility = new JsonObject(); compatibility.addProperty("status", "unknown"); candidate.add("compatibility", compatibility);
            }
            item.add("preferredCandidate", candidate); items.add(item);
        }
        return items;
    }

    public static Decoded decode(byte[] bytes, CommunitySearchContract.Request request, String authorityId) throws IOException {
        if (bytes != null && bytes.length > MAX_BYTES) throw new CommunityPackages.Failure("response_too_large");
        try { return validate(read(bytes, MAX_BYTES), request, authorityId); }
        catch (IOException | RuntimeException invalid) { throw new CommunityPackages.Failure("invalid_response", invalid); }
    }
    private static Decoded validate(JsonObject root, CommunitySearchContract.Request request, String authorityId) throws IOException {
        CommunitySearchContract.requireOfflineCapabilities(request);
        exact(root, "contract", "profile", "requestId", "needId", "queryFingerprint", "rankingPolicy", "indexSnapshot", "items", "nextCursor", "truncatedFields");
        equal(root, "contract", CONTRACT); equal(root, "profile", PROFILE); equal(root, "rankingPolicy", "offline-exact.v1");
        equal(root, "requestId", request.requestId()); equal(root, "needId", request.needId()); equal(root, "queryFingerprint", request.queryFingerprint());
        var snapshot = object(root.get("indexSnapshot")); exact(snapshot, "id", "expiresInSeconds"); text(snapshot.get("id"), 128); integer(snapshot.get("expiresInSeconds"), 1, 300);
        optionalText(root.get("nextCursor"), 256);
        JsonArray items = root.getAsJsonArray("items"); if (items.size() > request.pageSize()) throw new IOException("Page limit");
        Set<String> groups = new HashSet<>(), releases = new HashSet<>(); List<CommunityPackages.Descriptor> descriptors = new ArrayList<>();
        for (JsonElement element : items) {
            var item = object(element); exact(item, "groupFingerprint", "matchingSourceCount", "preferredCandidate");
            String fingerprint = text(item.get("groupFingerprint"), 128); if (!groups.add(fingerprint)) throw new IOException("Duplicate group");
            integer(item.get("matchingSourceCount"), 1, OfflineCommunityIndex.MAX_RELEASES);
            var candidate = object(item.get("preferredCandidate"));
            var fields = new ArrayList<>(List.of("releaseRef", "package", "fingerprints", "sourceClaim", "platformPublisher", "discoveryRevision", "requiredCapabilitiesStatus"));
            if (request.fields().contains("identity")) fields.add("title");
            if (request.fields().contains("type")) fields.add("type");
            if (request.fields().contains("rights")) fields.add("rights");
            if (request.fields().contains("evidence")) fields.addAll(List.of("validation", "fit", "compatibility"));
            exact(candidate, fields.toArray(String[]::new)); integer(candidate.get("discoveryRevision"), 1, 1);
            if (!candidate.get("platformPublisher").isJsonNull()) throw new IOException("Unsupported publisher proof");
            equal(candidate, "requiredCapabilitiesStatus", "unknown");
            var ref = object(candidate.get("releaseRef")); exact(ref, "authorityId", "resourceId", "releaseId", "packageSha256", "payloadSha256", "manifestSha256");
            equal(ref, "authorityId", authorityId);
            var release = new CommunityPackages.ReleaseRef(text(ref.get("authorityId"), 128), text(ref.get("resourceId"), 128), text(ref.get("releaseId"), 128),
                    text(ref.get("packageSha256"), 64), text(ref.get("payloadSha256"), 64), text(ref.get("manifestSha256"), 64));
            if (!releases.add(release.releaseId())) throw new IOException("Duplicate release");
            var pack = object(candidate.get("package")); exact(pack, "packageSha256", "byteLength", "mediaType", "packageSchema");
            equal(pack, "packageSha256", release.packageSha256()); equal(pack, "mediaType", "application/json"); equal(pack, "packageSchema", ComponentPackageIO.SCHEMA);
            var prints = object(candidate.get("fingerprints")); exact(prints, "structure", "configuration"); equal(prints, "configuration", fingerprint);
            descriptors.add(new CommunityPackages.Descriptor(release, integer(pack.get("byteLength"), 1, ComponentPackageIO.MAX_PACKAGE_BYTES), ComponentPackageIO.SCHEMA,
                    new ComponentContentIdentity.Fingerprints(text(prints.get("structure"), 128), fingerprint)));
            var claim = object(candidate.get("sourceClaim")); exact(claim, "namespace", "componentId", "version", "author");
            if (!ComponentIds.isSafeId(text(claim.get("namespace"), 128)) || !ComponentIds.isSafeId(text(claim.get("componentId"), 128))) throw new IOException("Source identity");
            if (!text(claim.get("version"), 64).matches("[a-zA-Z0-9][a-zA-Z0-9._+\\-]{0,63}")) throw new IOException("Source version");
            rawText(claim.get("author"), 256);
            if (candidate.has("title")) text(candidate.get("title"), 256);
            if (candidate.has("type")) {
                var type = object(candidate.get("type")); exact(type, "key", "evidence");
                String key = text(type.get("key"), 128);
                if (!key.matches("(?:formacraft:[a-z0-9_]+|custom:[0-9a-f]{64})")) throw new IOException("Type key");
                if (request.query().exactTypeKey() != null && !request.query().exactTypeKey().equals(key)) throw new IOException("Type mismatch");
                equal(type, "evidence", "declared");
            }
            if (candidate.has("rights")) {
                var rights = object(candidate.get("rights")); exact(rights, "license", "licenseStatus", "trainingPermission");
                rawText(rights.get("license"), 2048); equal(rights, "licenseStatus", "declared"); ComponentPackageIO.TrainingPermission.valueOf(text(rights.get("trainingPermission"), 16));
            }
            if (candidate.has("validation")) {
                var validation = object(candidate.get("validation")); exact(validation, "level", "runtimeTest"); equal(validation, "level", "data_validated"); equal(validation, "runtimeTest", "unknown");
                var fit = object(candidate.get("fit")); exact(fit, "metadataFit", "actualFit"); equal(fit, "metadataFit", "unknown"); equal(fit, "actualFit", "unknown");
                var compatibility = object(candidate.get("compatibility")); exact(compatibility, "status"); equal(compatibility, "status", "unknown");
            }
        }
        Set<String> truncated = strings(root.get("truncatedFields"), 20, 128);
        for (String path : truncated) {
            if (!path.matches("items\\[[0-9]{1,2}\\]\\.preferredCandidate\\.title") || !request.fields().contains("identity")) throw new IOException("Invalid truncation path");
            int index = Integer.parseInt(path.substring(6, path.indexOf(']'))); if (index >= items.size()) throw new IOException("Invalid truncation index");
        }
        if (items.isEmpty() && !root.get("nextCursor").isJsonNull()) throw new IOException("Empty continuation");
        return new Decoded(root, descriptors);
    }
    private static void exact(JsonObject value, String... fields) throws IOException {
        if (!value.keySet().equals(Set.of(fields))) throw new IOException("Unexpected fields");
    }
    private static String rawText(JsonElement value, int max) throws IOException {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IOException("String required");
        String text = value.getAsString();
        if (text.isBlank() || text.length() > max) throw new IOException("Text limit");
        return text;
    }
    private static void equal(JsonObject object, String key, String expected) throws IOException {
        if (!Objects.equals(text(object.get(key), 512), expected)) throw new IOException("Mismatched response context");
    }
}
