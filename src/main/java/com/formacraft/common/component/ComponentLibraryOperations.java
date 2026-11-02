package com.formacraft.common.component;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Resolves exactly the visible installation, never a name or a client filesystem path. */
public final class ComponentLibraryOperations {
    private ComponentLibraryOperations() {}
    private record Located(Path root, ComponentPackageIO.Verified primary, boolean personal) {}
    private static Located locate(Path world, UUID player, String id, String expectedRef) throws IOException {
        if (!ComponentIds.isSafeId(id) || expectedRef == null || expectedRef.isBlank()) throw unavailable();
        var entry = ComponentStorage.loadCatalog(world, player).components.stream()
                .filter(e -> e != null && id.equals(e.id)).findFirst().orElse(null);
        if (entry == null || !expectedRef.equals(entry.packageRef)) throw unavailable();
        var roots = new ArrayList<Path>();
        if (player != null) roots.add(ComponentStorage.getPlayerComponentDir(player));
        roots.add(ComponentStorage.getGlobalComponentDir());
        if (world != null) roots.add(ComponentStorage.getWorldComponentDir(world));
        for (Path root : roots) {
            ComponentPackageIO.Verified primary;
            try { primary = ComponentPackageInstallations.inspect(root, id); }
            catch (IOException missing) { continue; }
            if (expectedRef.equals(primary.contentRef()))
                return new Located(root, primary, player != null && root.equals(ComponentStorage.getPlayerComponentDir(player)));
        }
        throw unavailable();
    }
    public static ComponentPackageIO.Verified export(Path world, UUID player, String id, String expectedRef,
                                                    int sourceIndex, String sourceRef) throws IOException {
        var found = locate(world, player, id, expectedRef);
        if (sourceIndex < 0 || sourceIndex >= 129) throw unavailable();
        var source = sourceIndex == 0 ? found.primary() : ComponentPackageInstallations.sources(found.root(), id)
                .stream().skip(sourceIndex).findFirst().orElseThrow(ComponentLibraryOperations::unavailable);
        if (!Objects.equals(sourceRef, source.contentRef() + "|manifest:" + source.manifestSha256())
                && !(sourceIndex == 0 && Objects.equals(sourceRef, source.contentRef()))) throw unavailable();
        return source;
    }
    public static void delete(ComponentStorage.SaveContext context, String id, String expectedRef) throws IOException {
        if (context == null || context.playerId() == null) throw unavailable();
        synchronized (ComponentStorage.class) {
            var found = locate(context.worldDir(), context.playerId(), id, expectedRef);
            if (!found.personal() && !context.allowOverwriteGlobal()) throw new IOException("没有删除共享构件的权限");
            ComponentPackageInstallations.delete(found.root(), id);
            try { Files.deleteIfExists(ComponentIds.resolveComponentFile(found.root(), id, ".png")); }
            catch (IOException failedThumbnail) {
                com.formacraft.FormacraftMod.LOGGER.warn("Deleted component thumbnail cleanup failed id={}", id, failedThumbnail);
            }
        }
    }
    private static IOException unavailable() { return new IOException("构件或来源已变化，请刷新构件库后重试"); }
}
