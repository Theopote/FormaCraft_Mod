package com.formacraft.common.component;

import com.formacraft.common.json.JsonUtil;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;

/** Publishes immutable package directories; never runs legacy autofix or writes archetype sidecars. */
public final class ComponentPackageInstallations {
    private ComponentPackageInstallations() {}
    private static final String SOURCE = "source.fccomponent.json", RECEIPT = "receipt.json";
    public record Receipt(String schema, String localId, String contentRef, String manifestSha256) {}
    @FunctionalInterface interface BeforePublish { void run(Path staging) throws IOException; }

    public static Receipt install(Path source, ComponentPackageIO.Environment environment,
                                  ComponentStorage.SaveContext context, String localId) throws IOException {
        return install(source, environment, context, localId, staging -> {});
    }
    static Receipt install(Path source, ComponentPackageIO.Environment environment,
                           ComponentStorage.SaveContext context, String localId, BeforePublish beforePublish) throws IOException {
        if (context == null || !context.writeGlobal() && context.playerId() == null)
            throw new IOException("A trusted save context is required");
        String id = ComponentIds.validateComponentId(localId);
        if (id.equals("catalog")) throw new IOException("Reserved component ID");
        var verified = ComponentPackageIO.read(source, environment);
        var definition = verified.component();
        if (!definition.isCapturedOrigin() && definition.variantRules == null)
            throw new IOException("Portable installation requires captured origin or explicit author rules");
        Path root = context.writeGlobal() ? ComponentStorage.getGlobalComponentDir() : ComponentStorage.getPlayerComponentDir(context.playerId());
        synchronized (ComponentStorage.class) {
            if (!context.writeGlobal()) {
                Path global = ComponentStorage.getGlobalComponentDir(); Files.createDirectories(global);
                Path players = global.resolve("players"); Files.createDirectories(players);
                if (!players.toRealPath().equals(global.toRealPath().resolve("players"))) throw new IOException("Player library parent redirects");
                Files.createDirectories(root);
                if (!root.toRealPath().equals(players.toRealPath().resolve(context.playerId().toString()))) throw new IOException("Player library redirects");
            }
            Path packages = prepareRoot(root);
            try (var channel = FileChannel.open(packages.resolve(".install.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 var lock = channel.lock()) {
                boolean known = ComponentStorage.loadCatalog(context.worldDir(), context.playerId()).components.stream()
                        .anyMatch(e -> e != null && id.equals(e.id));
                Path target = packages.resolve(id);
                if (known || exists(root, id) || Files.exists(ComponentIds.resolveComponentFile(root, id, ".json"), LinkOption.NOFOLLOW_LINKS)
                        || !context.writeGlobal() && Files.exists(ComponentIds.resolveComponentFile(ComponentStorage.getGlobalComponentDir(), id, ".json"), LinkOption.NOFOLLOW_LINKS)
                        || context.worldDir() != null && Files.exists(ComponentIds.resolveComponentFile(ComponentStorage.getWorldComponentDir(context.worldDir()), id, ".json"), LinkOption.NOFOLLOW_LINKS)
                        || context.playerId() != null && Files.exists(ComponentIds.resolveComponentFile(ComponentStorage.getPlayerComponentDir(context.playerId()), id, ".json"), LinkOption.NOFOLLOW_LINKS))
                    throw new FileAlreadyExistsException(id);
                Path staging = Files.createTempDirectory(packages, ".pending-");
                boolean published = false;
                try {
                    verified.writeTo(staging.resolve(SOURCE));
                    var receipt = new Receipt("formacraft.component.install.v1", id, verified.contentRef(), verified.manifestSha256());
                    Files.writeString(staging.resolve(RECEIPT), JsonUtil.toJson(receipt), StandardOpenOption.CREATE_NEW);
                    beforePublish.run(staging);
                    Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
                    published = true;
                    return receipt;
                } finally {
                    if (!published) cleanup(staging);
                }
            }
        }
    }

    static boolean exists(Path root, String id) { return Files.exists(root.resolve("packages").resolve(id), LinkOption.NOFOLLOW_LINKS); }

    private static Path prepareRoot(Path root) throws IOException {
        Files.createDirectories(root);
        Path packages = root.resolve("packages"); Files.createDirectories(packages);
        if (!packages.toRealPath().equals(root.toRealPath().resolve("packages"))) throw new IOException("Package root redirects outside its identity");
        return packages;
    }

    public static ComponentPackageIO.Verified inspect(Path root, String id) throws IOException {
        id = ComponentIds.validateComponentId(id);
        Path packages = root.resolve("packages"); Path directory = packages.resolve(id);
        if (!packages.toRealPath().equals(root.toRealPath().resolve("packages"))
                || !directory.toRealPath().equals(packages.toRealPath().resolve(id)))
            throw new IOException("Package directory escapes library");
        for (String file : List.of(SOURCE, RECEIPT)) if (!directory.resolve(file).toRealPath().equals(directory.toRealPath().resolve(file)))
            throw new IOException("Package file escapes installation");
        Receipt receipt;
        try (var input = Files.newInputStream(directory.resolve(RECEIPT))) {
            byte[] bytes = input.readNBytes(8193);
            if (bytes.length > 8192) throw new IOException("Oversized install receipt");
            try { receipt = JsonUtil.fromJson(new String(bytes, java.nio.charset.StandardCharsets.UTF_8), Receipt.class); }
            catch (RuntimeException invalid) { throw new IOException("Invalid install receipt", invalid); }
        }
        var verified = ComponentPackageIO.read(directory.resolve(SOURCE), ComponentPackageIO.currentEnvironment());
        if (receipt == null || !"formacraft.component.install.v1".equals(receipt.schema()) || !id.equals(receipt.localId())
                || !verified.contentRef().equals(receipt.contentRef())
                || !verified.manifestSha256().equals(receipt.manifestSha256())) throw new IOException("Install receipt mismatch");
        return verified;
    }

    static ComponentDefinition load(Path root, String id) {
        if (!ComponentIds.isSafeId(id) || !exists(root, id)) return null;
        try {
            var verified = inspect(root, id);
            var definition = verified.component(); definition.id = id;
            definition.archetypeRef = "pkg_" + verified.sha256().substring(0, 48);
            return definition;
        } catch (IOException invalid) {
            com.formacraft.FormacraftMod.LOGGER.warn("Imported component unavailable id={} reason={}", id, invalid.getMessage());
            return null;
        }
    }

    public static List<ComponentCatalog.Entry> entries(Path root) {
        var entries = new ArrayList<ComponentCatalog.Entry>();
        Path packages = root.resolve("packages"); if (!Files.isDirectory(packages)) return entries;
        try {
            if (!packages.toRealPath().equals(root.toRealPath().resolve("packages"))) return entries;
        } catch (IOException unavailable) { return entries; }
        try (var children = Files.list(packages)) {
            for (Path directory : children.sorted().toList()) {
                String id = directory.getFileName().toString();
                if (!ComponentIds.isSafeId(id) || !Files.isDirectory(directory)) continue;
                try {
                    var verified = inspect(root, id); var def = verified.component();
                    var entry = new ComponentCatalog.Entry(); entry.id = id; entry.name = def.name;
                    entry.category = def.category; entry.tags = def.tags; entry.size = def.size;
                    entry.variantRules = def.variantRules; entry.sockets = def.sockets; entry.placementSpec = def.placementSpec;
                    entry.file = "packages/" + id + "/" + SOURCE; entry.packageRef = verified.contentRef();
                    entry.updatedAtMs = Files.getLastModifiedTime(directory).toMillis();
                    entries.add(entry);
                } catch (IOException invalid) { /* Invalid or incomplete installations are not candidates. */ }
            }
        } catch (IOException unavailable) {
            com.formacraft.FormacraftMod.LOGGER.warn("Imported catalog unavailable: {}", unavailable.getMessage());
        }
        return entries;
    }

    static void delete(Path root, String id) throws IOException {
        if (!exists(root, id)) return;
        Path packages = prepareRoot(root);
        try (var channel = FileChannel.open(packages.resolve(".install.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var lock = channel.lock()) {
            if (!packages.resolve(id).toRealPath().equals(packages.toRealPath().resolve(id)))
                throw new IOException("Package directory redirects outside its installed identity");
            Path retired = packages.resolve(".removed-" + UUID.randomUUID());
            Files.move(packages.resolve(id), retired, StandardCopyOption.ATOMIC_MOVE);
            cleanup(retired);
        }
    }
    private static void cleanup(Path directory) throws IOException {
        if (!directory.toRealPath().equals(directory.getParent().toRealPath().resolve(directory.getFileName())))
            throw new IOException("Refusing redirected staging cleanup");
        Files.deleteIfExists(directory.resolve(SOURCE)); Files.deleteIfExists(directory.resolve(RECEIPT)); Files.deleteIfExists(directory);
    }
}
