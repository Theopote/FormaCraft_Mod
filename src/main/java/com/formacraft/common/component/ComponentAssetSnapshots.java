package com.formacraft.common.component;

import java.io.IOException;
import java.nio.file.*;

/** Immutable local package locks, keyed by payload AND rights metadata. */
public final class ComponentAssetSnapshots {
    private ComponentAssetSnapshots() {}
    public static String key(ComponentPackageIO.Verified source) { return source.sha256()+"_"+source.manifestSha256(); }
    static Path child(Path parent, String name, boolean create) throws IOException {
        if (create) Files.createDirectories(parent.resolve(name));
        Path child = parent.resolve(name);
        if (!child.toRealPath().equals(parent.toRealPath().resolve(name))) throw new IOException("Asset directory redirects");
        return child;
    }
    public static String save(Path library, ComponentPackageIO.Verified source) throws IOException {
        if (source == null || !source.environmentVerified()) throw new IOException("Snapshot requires verified environment");
        synchronized (ComponentStorage.class) {
            Path root = child(library,"snapshots",true); String key = key(source);
            if (Files.exists(root.resolve(key),LinkOption.NOFOLLOW_LINKS)) { read(library,key); return key; }
            Path staging = Files.createTempDirectory(root,".pending-");
            try {
                source.writeTo(staging.resolve("source.fccomponent.json"));
                Files.move(staging,root.resolve(key),StandardCopyOption.ATOMIC_MOVE);
            } finally {
                if (Files.exists(staging,LinkOption.NOFOLLOW_LINKS)) {
                    Files.deleteIfExists(staging.resolve("source.fccomponent.json")); Files.delete(staging);
                }
            }
            return key;
        }
    }
    public static ComponentPackageIO.Verified read(Path library, String key) throws IOException {
        if (key == null || !key.matches("[0-9a-f]{64}_[0-9a-f]{64}")) throw new IOException("Invalid snapshot identity");
        Path root = child(library,"snapshots",false), directory = child(root,key,false);
        Path source = directory.resolve("source.fccomponent.json");
        if (!source.toRealPath().equals(directory.toRealPath().resolve(source.getFileName()))) throw new IOException("Snapshot file redirects");
        var verified = ComponentPackageIO.read(source,ComponentPackageIO.currentEnvironment());
        if (!key(verified).equals(key)) throw new IOException("Snapshot fingerprint mismatch");
        return verified;
    }
}
