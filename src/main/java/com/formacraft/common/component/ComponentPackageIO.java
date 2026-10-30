package com.formacraft.common.component;

import com.formacraft.common.component.validate.ComponentValidator;
import com.formacraft.common.json.JsonUtil;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Data-only offline exchange. Reading never installs an asset or writes to the library. */
public final class ComponentPackageIO {
    private ComponentPackageIO() {}
    public static final String SCHEMA = "formacraft.component.package.v1";
    public static final int MAX_PACKAGE_BYTES = 16 * 1024 * 1024;
    public enum TrainingPermission { ALLOWED, DENIED, UNSPECIFIED }

    public record Manifest(String namespace, String version, String author, String license,
                           TrainingPermission trainingPermission, String minecraftVersion,
                           Map<String, String> requiredMods, Map<String, String> blockNamespaceOwners,
                           List<String> derivedFrom) {
        public Manifest {
            trainingPermission = trainingPermission == null ? TrainingPermission.UNSPECIFIED : trainingPermission;
            requiredMods = requiredMods == null ? Map.of() : Map.copyOf(requiredMods);
            blockNamespaceOwners = blockNamespaceOwners == null ? Map.of() : Map.copyOf(blockNamespaceOwners);
            derivedFrom = derivedFrom == null ? List.of() : List.copyOf(derivedFrom);
        }
    }
    public record Environment(String minecraftVersion, Map<String, String> installedMods) {
        public Environment { installedMods = installedMods == null ? Map.of() : Map.copyOf(installedMods); }
    }
    private record Envelope(String schema, Manifest manifest, String componentJson, String sha256) {}

    public static final class Verified {
        private final Manifest manifest;
        private final String payload, hash, assetKey;
        private Verified(Envelope envelope, String id) {
            manifest = envelope.manifest(); payload = envelope.componentJson(); hash = envelope.sha256();
            assetKey = manifest.namespace() + "/" + id + "@" + manifest.version();
        }
        public Manifest manifest() { return manifest; }
        public String sha256() { return hash; }
        public String assetKey() { return assetKey; }
        public String contentRef() { return assetKey + "#sha256:" + hash; }
        /** Stable across map iteration order, also locks licensing and dependency declarations. */
        public String manifestSha256() {
            var fields = new TreeMap<String, Object>();
            fields.put("namespace", manifest.namespace()); fields.put("version", manifest.version());
            fields.put("author", manifest.author()); fields.put("license", manifest.license());
            fields.put("trainingPermission", manifest.trainingPermission().name());
            fields.put("minecraftVersion", manifest.minecraftVersion());
            fields.put("requiredMods", new TreeMap<>(manifest.requiredMods()));
            fields.put("blockNamespaceOwners", new TreeMap<>(manifest.blockNamespaceOwners()));
            fields.put("derivedFrom", manifest.derivedFrom());
            return hash(JsonUtil.toJson(fields));
        }
        public ComponentDefinition component() { return JsonUtil.fromJson(payload, ComponentDefinition.class); }
        public void writeTo(Path path) throws IOException {
            byte[] bytes = JsonUtil.toJson(new Envelope(SCHEMA, manifest, payload, hash)).getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_PACKAGE_BYTES) throw new IOException("Package exceeds byte limit");
            Files.write(path, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        }
    }

    public static Environment currentEnvironment() throws IOException {
        var mods = new TreeMap<String, String>();
        for (var mod : net.fabricmc.loader.api.FabricLoader.getInstance().getAllMods())
            mods.put(mod.getMetadata().getId(), mod.getMetadata().getVersion().getFriendlyString());
        if (!mods.containsKey("minecraft")) throw new IOException("Minecraft environment unavailable");
        return new Environment(mods.get("minecraft"), mods);
    }

    public static void write(Path path, ComponentDefinition definition, Manifest manifest) throws IOException {
        String payload = JsonUtil.toJson(definition);
        var envelope = new Envelope(SCHEMA, manifest, payload, hash(payload));
        validate(envelope);
        byte[] bytes = JsonUtil.toJson(envelope).getBytes(StandardCharsets.UTF_8);
        require(bytes.length <= MAX_PACKAGE_BYTES, "Package exceeds byte limit");
        Files.write(path, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    public static Verified read(Path path, Environment environment) throws IOException {
        byte[] bytes;
        try (var stream = Files.newInputStream(path)) { bytes = stream.readNBytes(MAX_PACKAGE_BYTES + 1); }
        require(bytes.length <= MAX_PACKAGE_BYTES, "Package exceeds byte limit");
        Envelope envelope;
        String jsonText = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        try {
            var root = JsonParser.parseString(jsonText).getAsJsonObject();
            var metadata = root.get("manifest");
            if (metadata != null && metadata.isJsonObject()) {
                var permission = metadata.getAsJsonObject().get("trainingPermission");
                require(permission == null || permission.isJsonNull() || permission.isJsonPrimitive()
                        && permission.getAsJsonPrimitive().isString()
                        && Set.of("ALLOWED", "DENIED", "UNSPECIFIED").contains(permission.getAsString()),
                        "Unknown training permission");
            }
            envelope = JsonUtil.get().fromJson(root, Envelope.class);
        }
        catch (RuntimeException invalidJson) { throw new IOException("Invalid package JSON", invalidJson); }
        ComponentDefinition definition = validate(envelope);
        require(environment != null, "Runtime environment is required");
        require(envelope.manifest().minecraftVersion().equals(environment.minecraftVersion()), "Minecraft version mismatch");
        for (var dependency : envelope.manifest().requiredMods().entrySet()) {
            require(dependency.getValue().equals(environment.installedMods().get(dependency.getKey())),
                    "Missing or incompatible dependency: " + dependency.getKey());
        }
        return new Verified(envelope, definition.id);
    }

    private static ComponentDefinition validate(Envelope envelope) throws IOException {
        require(envelope != null && SCHEMA.equals(envelope.schema()), "Unsupported package schema");
        Manifest manifest = envelope.manifest();
        require(manifest != null, "Missing manifest");
        require(ComponentIds.isSafeId(manifest.namespace()), "Invalid publisher namespace");
        require(manifest.version() != null && manifest.version().matches("[a-zA-Z0-9][a-zA-Z0-9._+\\-]{0,63}"), "Invalid package version");
        text(manifest.author(), 256, "author"); text(manifest.license(), 2048, "license");
        text(manifest.minecraftVersion(), 64, "minecraftVersion");
        require(manifest.requiredMods().size() <= 128 && manifest.derivedFrom().size() <= 64, "Too many dependencies or sources");
        for (var dependency : manifest.requiredMods().entrySet()) {
            require(ComponentIds.isSafeId(dependency.getKey()) && !dependency.getKey().equals("minecraft"), "Invalid mod dependency ID");
            text(dependency.getValue(), 128, "dependency version");
        }
        require(manifest.blockNamespaceOwners().size() <= 128, "Too many block namespace owners");
        for (var owner : manifest.blockNamespaceOwners().entrySet()) {
            require(owner.getKey().matches("[a-z0-9_.-]{1,64}") && !owner.getKey().equals("minecraft")
                    && manifest.requiredMods().containsKey(owner.getValue()), "Invalid block namespace owner");
        }
        for (String source : manifest.derivedFrom()) text(source, 2048, "source reference");
        String payload = envelope.componentJson();
        require(payload != null && payload.length() <= ComponentIds.MAX_JSON_CHARS, "Missing or oversized component payload");
        require(envelope.sha256() != null && envelope.sha256().matches("[0-9a-f]{64}")
                && envelope.sha256().equals(hash(payload)), "Component hash mismatch");
        ComponentDefinition definition;
        try { definition = JsonUtil.fromJson(payload, ComponentDefinition.class); }
        catch (RuntimeException invalidJson) { throw new IOException("Invalid component JSON", invalidJson); }
        require(definition != null && "formacraft.component.v1".equals(definition.schema) && definition.version == 1,
                "Unsupported component schema or version");
        require(ComponentIds.isSafeId(definition.id), "Invalid component ID");
        require(definition.archetypeRef == null || definition.archetypeRef.isBlank()
                || ComponentIds.tryNormalizeLoadId(definition.archetypeRef) != null, "Invalid archetype reference");
        require(definition.blocks != null && definition.blocks.size() <= ComponentIds.MAX_BLOCKS, "Invalid component block count");
        try {
            require(ComponentValidator.validate(definition).ok(), "Component validation failed");
        } catch (RuntimeException invalidComponent) { throw new IOException("Invalid component structure", invalidComponent); }
        for (var block : definition.blocks) {
            require(block != null && block.block != null, "Missing block state");
            String id = block.block.split("\\[", 2)[0].strip();
            int colon = id.indexOf(':');
            require((colon < 0 ? "minecraft:" + id : id).matches("[a-z0-9_.-]+:[a-z0-9_./-]+"), "Invalid block ID");
            String namespace = colon < 0 ? "minecraft" : id.substring(0, colon);
            String owner = manifest.blockNamespaceOwners().getOrDefault(namespace, namespace);
            require(namespace.equals("minecraft") || manifest.requiredMods().containsKey(owner),
                    "Undeclared block namespace: " + namespace);
        }
        return definition;
    }

    private static void text(String value, int max, String field) throws IOException {
        require(value != null && !value.isBlank() && value.length() <= max, "Invalid " + field);
    }
    private static void require(boolean condition, String message) throws IOException {
        if (!condition) throw new IOException(message);
    }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
