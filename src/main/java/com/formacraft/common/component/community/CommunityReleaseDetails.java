package com.formacraft.common.component.community;

import com.formacraft.common.component.*;
import com.formacraft.common.json.JsonUtil;
import com.google.gson.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;
import static com.formacraft.common.component.community.CommunityJson.*;

/** Minimal read-only release profile. Platform identity remains explicitly unknown. */
public final class CommunityReleaseDetails {
    public static final String CONTRACT = "formacraft.community.release-detail.v1", PROFILE = "offline-release.v1";
    public static final int MAX_BYTES = 128 * 1024;
    private final CommunityPackages.Descriptor descriptor;
    private final ComponentPackageIO.Manifest manifest;
    private final String componentId;
    private final boolean available;
    private CommunityReleaseDetails(CommunityPackages.Descriptor descriptor, ComponentPackageIO.Manifest manifest, String componentId, boolean available) {
        this.descriptor = descriptor; this.manifest = manifest; this.componentId = componentId; this.available = available;
    }
    public CommunityPackages.Descriptor descriptor() { return descriptor; }
    public ComponentPackageIO.Manifest manifest() { return manifest; }
    public String componentId() { return componentId; }
    public boolean available() { return available; }

    static CommunityReleaseDetails create(CommunityPackages.Descriptor descriptor, ComponentPackageIO.Verified pack, boolean available) throws IOException {
        if (!descriptor.releaseRef().payloadSha256().equals(pack.sha256()) || !descriptor.releaseRef().manifestSha256().equals(pack.manifestSha256())
                || !descriptor.fingerprints().equals(ComponentContentIdentity.fingerprints(pack.component()))) throw new CommunityPackages.Failure("object_changed");
        return new CommunityReleaseDetails(descriptor, pack.manifest(), pack.component().id, available);
    }
    public void verify(CommunityPackages.Download download) throws IOException {
        var pack = download.verified();
        if (!descriptor.equals(download.descriptor()) || !manifest.equals(pack.manifest()) || !componentId.equals(pack.component().id))
            throw new CommunityPackages.Failure("release_metadata_mismatch");
    }
    public byte[] toBytes() throws IOException {
        var root = new JsonObject(); root.addProperty("contract", CONTRACT); root.addProperty("profile", PROFILE);
        var immutable = new JsonObject(); immutable.add("descriptor", descriptorJson(descriptor));
        immutable.add("sourceManifest", JsonUtil.get().toJsonTree(manifest)); immutable.addProperty("componentId", componentId);
        immutable.add("platformPublisher", JsonNull.INSTANCE); root.add("immutableRelease", immutable);
        var status = new JsonObject(); status.addProperty("delivery", available ? "available" : "unavailable"); root.add("currentStatus", status);
        return encode(root, MAX_BYTES);
    }
    public static CommunityReleaseDetails decode(byte[] bytes, CommunityPackages.Descriptor selected) throws IOException {
        if (bytes != null && bytes.length > MAX_BYTES) throw new CommunityPackages.Failure("response_too_large");
        try {
            var root = read(bytes, MAX_BYTES); exact(root, "contract", "profile", "immutableRelease", "currentStatus");
            equal(root, "contract", CONTRACT); equal(root, "profile", PROFILE);
            var immutable = object(root.get("immutableRelease")); exact(immutable, "descriptor", "sourceManifest", "componentId", "platformPublisher");
            var descriptor = descriptor(object(immutable.get("descriptor")));
            if (!descriptor.equals(selected)) throw new CommunityPackages.Failure("object_changed");
            if (!immutable.get("platformPublisher").isJsonNull()) throw new IOException("Unsupported publisher proof");
            String componentId = text(immutable.get("componentId"), 128);
            if (!ComponentIds.isSafeId(componentId)) throw new IOException("Invalid component identity");
            var manifest = manifest(object(immutable.get("sourceManifest")));
            var status = object(root.get("currentStatus")); exact(status, "delivery"); String delivery = text(status.get("delivery"), 16);
            if (!Set.of("available", "unavailable").contains(delivery)) throw new IOException("Unsupported status");
            return new CommunityReleaseDetails(descriptor, manifest, componentId, delivery.equals("available"));
        } catch (CommunityPackages.Failure failure) {
            if (failure.code().equals("object_changed")) throw failure;
            throw new CommunityPackages.Failure("invalid_response", failure);
        } catch (IOException | RuntimeException invalid) { throw new CommunityPackages.Failure("invalid_response", invalid); }
    }
    static JsonObject descriptorJson(CommunityPackages.Descriptor descriptor) { return JsonUtil.get().toJsonTree(descriptor).getAsJsonObject(); }
    static CommunityPackages.Descriptor descriptor(JsonObject root) throws IOException {
        exact(root, "releaseRef", "byteLength", "packageSchema", "fingerprints");
        var ref = object(root.get("releaseRef")); exact(ref, "authorityId", "resourceId", "releaseId", "packageSha256", "payloadSha256", "manifestSha256");
        var release = new CommunityPackages.ReleaseRef(text(ref.get("authorityId"), 128), text(ref.get("resourceId"), 128), text(ref.get("releaseId"), 128),
                text(ref.get("packageSha256"), 64), text(ref.get("payloadSha256"), 64), text(ref.get("manifestSha256"), 64));
        var prints = object(root.get("fingerprints")); exact(prints, "structure", "configuration");
        return new CommunityPackages.Descriptor(release, integer(root.get("byteLength"), 1, ComponentPackageIO.MAX_PACKAGE_BYTES), text(root.get("packageSchema"), 128),
                new ComponentContentIdentity.Fingerprints(text(prints.get("structure"), 128), text(prints.get("configuration"), 128)));
    }
    private static ComponentPackageIO.Manifest manifest(JsonObject root) throws IOException {
        exact(root, "namespace", "version", "author", "license", "trainingPermission", "minecraftVersion", "requiredMods", "blockNamespaceOwners", "derivedFrom");
        String namespace = text(root.get("namespace"), 128), version = text(root.get("version"), 64);
        if (!ComponentIds.isSafeId(namespace) || !version.matches("[a-zA-Z0-9][a-zA-Z0-9._+\\-]{0,63}")) throw new IOException("Invalid source identity");
        var mods = stringMap(object(root.get("requiredMods")), 128, 128);
        var owners = stringMap(object(root.get("blockNamespaceOwners")), 128, 128);
        for (String mod : mods.keySet()) if (!ComponentIds.isSafeId(mod) || mod.equals("minecraft")) throw new IOException("Invalid dependency");
        for (var owner : owners.entrySet())
            if (!owner.getKey().matches("[a-z0-9_.-]{1,64}") || owner.getKey().equals("minecraft") || !mods.containsKey(owner.getValue())) throw new IOException("Invalid namespace owner");
        var derivations = root.getAsJsonArray("derivedFrom"); if (derivations.size() > 64) throw new IOException("Source limit");
        List<String> sources = new ArrayList<>(); for (var source : derivations) sources.add(rawText(source, 2048));
        return new ComponentPackageIO.Manifest(namespace, version, rawText(root.get("author"), 256), rawText(root.get("license"), 2048),
                ComponentPackageIO.TrainingPermission.valueOf(text(root.get("trainingPermission"), 16)), text(root.get("minecraftVersion"), 64), mods, owners, sources);
    }
    static Map<String, String> stringMap(JsonObject root, int count, int width) throws IOException {
        if (root.size() > count) throw new IOException("Map limit");
        var values = new TreeMap<String, String>();
        for (var entry : root.entrySet()) { if (entry.getKey().length() > 128) throw new IOException("Key limit"); values.put(entry.getKey(), rawText(entry.getValue(), width)); }
        return Collections.unmodifiableMap(values);
    }
    static String rawText(JsonElement value, int max) throws IOException {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IOException("String required");
        String text = value.getAsString(); if (text.isBlank() || text.length() > max) throw new IOException("Text limit"); return text;
    }
    static void exact(JsonObject value, String... fields) throws IOException { if (!value.keySet().equals(Set.of(fields))) throw new IOException("Unexpected fields"); }
    private static void equal(JsonObject root, String key, String value) throws IOException { if (!value.equals(text(root.get(key), 128))) throw new IOException("Unsupported contract"); }
    static byte[] encode(JsonObject root, int max) throws IOException {
        ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).encode(CharBuffer.wrap(root.toString()));
        if (encoded.remaining() > max) throw new CommunityPackages.Failure("response_too_large");
        byte[] bytes = new byte[encoded.remaining()]; encoded.get(bytes); return bytes;
    }
}
