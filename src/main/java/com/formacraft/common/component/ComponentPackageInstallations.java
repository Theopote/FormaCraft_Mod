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
    public enum ImportStatus { NEW_ASSET, NEW_CONFIGURATION, SOURCE_ADDED, REUSED }
    public record ImportResult(ImportStatus status, String assetId, ComponentContentIdentity.Fingerprints fingerprints) {}
    @FunctionalInterface interface BeforePublish { void run(Path staging) throws IOException; }

    public static Receipt install(Path source, ComponentPackageIO.Environment environment,
                                  ComponentStorage.SaveContext context, String localId) throws IOException {
        return install(source, environment, context, localId, staging -> {});
    }
    static Receipt install(Path source, ComponentPackageIO.Environment environment,
                           ComponentStorage.SaveContext context, String localId, BeforePublish beforePublish) throws IOException {
        requireContext(context);
        String id = ComponentIds.validateComponentId(localId);
        if (id.equals("catalog")) throw new IOException("Reserved component ID");
        var verified = ComponentPackageIO.read(source, environment);
        requirePortable(verified);
        synchronized (ComponentStorage.class) {
            Path root = prepareLibrary(context);
            Path packages = prepareRoot(root);
            try (var channel = FileChannel.open(packages.resolve(".install.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 var lock = channel.lock()) {
                return publish(verified, context, root, packages, id, beforePublish);
            }
        }
    }

    /** Ordinary import: names are metadata, IDs are generated and exact content is reused. */
    public static ImportResult importPackage(Path source, ComponentPackageIO.Environment environment,
                                             ComponentStorage.SaveContext context) throws IOException {
        requireContext(context);
        var incoming = ComponentPackageIO.read(source, environment);
        return importVerified(incoming, context);
    }

    static ImportResult importVerified(ComponentPackageIO.Verified incoming, ComponentStorage.SaveContext context) throws IOException {
        requireContext(context);
        requirePortable(incoming);
        ComponentContentIdentity.Fingerprints fingerprints;
        try { fingerprints = ComponentContentIdentity.fingerprints(incoming.component()); }
        catch (RuntimeException invalid) { throw new IOException("Cannot fingerprint component", invalid); }
        synchronized (ComponentStorage.class) {
            Path root = prepareLibrary(context), packages = prepareRoot(root);
            try (var channel = FileChannel.open(packages.resolve(".install.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 var lock = channel.lock()) {
                boolean sameStructure = false;
                try (var children = Files.list(packages)) {
                    for (Path directory : children.sorted().toList()) {
                        String id = directory.getFileName().toString();
                        if (!ComponentIds.isSafeId(id) || !Files.isDirectory(directory)) continue;
                        ComponentPackageIO.Verified existing;
                        try { existing = inspect(root, id); }
                        catch (IOException invalid) { continue; }
                        ComponentContentIdentity.Match match;
                        try { match = ComponentContentIdentity.compare(existing.component(), incoming.component()); }
                        catch (RuntimeException invalid) { continue; }
                        if (match == ComponentContentIdentity.Match.EXACT_CONTENT) {
                            boolean added = addSource(root, id, incoming);
                            return new ImportResult(added ? ImportStatus.SOURCE_ADDED : ImportStatus.REUSED, id, fingerprints);
                        }
                        sameStructure |= match == ComponentContentIdentity.Match.SAME_STRUCTURE;
                    }
                }
                String id = "asset_" + UUID.randomUUID().toString().replace("-", "");
                publish(incoming, context, root, packages, id, staging -> {});
                return new ImportResult(sameStructure ? ImportStatus.NEW_CONFIGURATION : ImportStatus.NEW_ASSET, id, fingerprints);
            }
        }
    }

    private static void requireContext(ComponentStorage.SaveContext context) throws IOException {
        if (context == null || !context.writeGlobal() && context.playerId() == null)
            throw new IOException("A trusted save context is required");
    }
    private static void requirePortable(ComponentPackageIO.Verified verified) throws IOException {
        var definition = verified.component();
        if (!definition.isCapturedOrigin() && definition.variantRules == null)
            throw new IOException("Portable installation requires captured origin or explicit author rules");
    }
    private static Path prepareLibrary(ComponentStorage.SaveContext context) throws IOException {
        Path root = context.writeGlobal() ? ComponentStorage.getGlobalComponentDir() : ComponentStorage.getPlayerComponentDir(context.playerId());
        if (!context.writeGlobal()) {
            Path global = ComponentStorage.getGlobalComponentDir(); Files.createDirectories(global);
            Path players = global.resolve("players"); Files.createDirectories(players);
            if (!players.toRealPath().equals(global.toRealPath().resolve("players"))) throw new IOException("Player library parent redirects");
            Files.createDirectories(root);
            if (!root.toRealPath().equals(players.toRealPath().resolve(context.playerId().toString()))) throw new IOException("Player library redirects");
        }
        return root;
    }
    private static Receipt publish(ComponentPackageIO.Verified verified, ComponentStorage.SaveContext context,
                                   Path root, Path packages, String id, BeforePublish beforePublish) throws IOException {
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

    /** Primary source first; extra sources retain independent licensing and never grant primary permissions. */
    public static List<ComponentPackageIO.Verified> sources(Path root, String id) throws IOException {
        id = ComponentIds.validateComponentId(id);
        var primary = inspect(root, id);
        var result = new ArrayList<ComponentPackageIO.Verified>(); result.add(primary);
        Path directory = root.resolve("packages").resolve(id), sources = directory.resolve("sources");
        if (!Files.exists(sources, LinkOption.NOFOLLOW_LINKS)) return List.copyOf(result);
        if (!sources.toRealPath().equals(directory.toRealPath().resolve("sources"))) throw new IOException("Source directory redirects");
        try (var files = Files.list(sources)) {
            for (Path file : files.sorted().toList()) {
                String name = file.getFileName().toString();
                if (name.startsWith(".pending-source-")) continue;
                if (!name.matches("[0-9a-f]{64}_[0-9a-f]{64}\\.fccomponent\\.json")
                        || !file.toRealPath().equals(sources.toRealPath().resolve(name))) throw new IOException("Invalid source record");
                if (result.size() >= 129) throw new IOException("Too many source records");
                var source = ComponentPackageIO.read(file, ComponentPackageIO.currentEnvironment());
                if (!name.equals(sourceKey(source)) || ComponentContentIdentity.compare(primary.component(), source.component())
                        != ComponentContentIdentity.Match.EXACT_CONTENT) throw new IOException("Source record mismatch");
                result.add(source);
            }
        } catch (RuntimeException invalid) { throw new IOException("Invalid source content", invalid); }
        return List.copyOf(result);
    }

    private static String sourceKey(ComponentPackageIO.Verified source) {
        return source.sha256() + "_" + source.manifestSha256() + ".fccomponent.json";
    }
    private static boolean addSource(Path root, String id, ComponentPackageIO.Verified incoming) throws IOException {
        var current = sources(root, id);
        for (var source : current)
            if (source.contentRef().equals(incoming.contentRef()) && source.manifestSha256().equals(incoming.manifestSha256())) return false;
        if (current.size() >= 129) throw new IOException("Source record limit reached");
        Path directory = root.resolve("packages").resolve(id), sources = directory.resolve("sources");
        Files.createDirectories(sources);
        if (!sources.toRealPath().equals(directory.toRealPath().resolve("sources"))) throw new IOException("Source directory redirects");
        Path staging = sources.resolve(".pending-source-" + UUID.randomUUID());
        try {
            incoming.writeTo(staging);
            Files.move(staging, sources.resolve(sourceKey(incoming)), StandardCopyOption.ATOMIC_MOVE);
        } finally { Files.deleteIfExists(staging); }
        return true;
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
                    if (Files.isRegularFile(root.resolve(id + ".png"))) entry.thumbnail = id + ".png";
                    entry.updatedAtMs = Files.getLastModifiedTime(directory).toMillis();
                    try {
                        var sourceRecords = sources(root, id);
                        entry.sourceCount = sourceRecords.size();
                        var aliases = new LinkedHashMap<String, String>();
                        String primaryName = com.formacraft.common.component.query.ComponentQueryMatchUtil.normalizeTag(ComponentSourceView.bounded(def.name, 64));
                        for (var source : sourceRecords.subList(1, sourceRecords.size())) {
                            String name = ComponentSourceView.bounded(source.component().name, 64);
                            String key = com.formacraft.common.component.query.ComponentQueryMatchUtil.normalizeTag(name);
                            if (!key.isBlank() && !key.equals(primaryName)) aliases.putIfAbsent(key, name);
                        }
                        entry.aliases = List.copyOf(aliases.values());
                    } catch (IOException invalidSources) {
                        entry.sourceMetadataError = true;
                        entry.aliases = List.of();
                    }
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
        Path sources = directory.resolve("sources");
        if (Files.exists(sources, LinkOption.NOFOLLOW_LINKS)) {
            if (!sources.toRealPath().equals(directory.toRealPath().resolve("sources"))) throw new IOException("Refusing redirected source cleanup");
            try (var files = Files.list(sources)) {
                for (Path file : files.toList()) {
                    String name = file.getFileName().toString();
                    if (name.matches("[0-9a-f]{64}_[0-9a-f]{64}\\.fccomponent\\.json") || name.startsWith(".pending-source-"))
                        Files.delete(file);
                }
            }
            Files.delete(sources);
        }
        Files.deleteIfExists(directory.resolve(SOURCE)); Files.deleteIfExists(directory.resolve(RECEIPT)); Files.deleteIfExists(directory);
    }
}
