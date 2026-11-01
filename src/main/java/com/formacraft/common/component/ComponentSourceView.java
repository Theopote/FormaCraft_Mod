package com.formacraft.common.component;

import java.nio.file.Path;
import java.util.*;

/** Read-only source metadata for the viewer's effective catalog entry. Never returns disk paths or blocks. */
public final class ComponentSourceView {
    private ComponentSourceView() {}
    public record Page(String assetId, int index, int total, boolean primary, String name, String namespace,
                       String version, String author, String license, ComponentPackageIO.TrainingPermission trainingPermission,
                       String contentRef, String error) {}

    public static Page query(Path world, UUID viewer, String requestedId, int index) {
        String id = ComponentIds.tryNormalizeLoadId(requestedId);
        if (id == null || !ComponentIds.isSafeId(id) || index < 0 || index >= 129) return unavailable(requestedId, index);
        try {
            var entry = ComponentStorage.loadCatalog(world, viewer).components.stream()
                    .filter(e -> e != null && id.equals(e.id)).findFirst().orElse(null);
            if (entry == null || entry.packageRef == null) return unavailable(id, index);
            var roots = new ArrayList<Path>();
            if (viewer != null) roots.add(ComponentStorage.getPlayerComponentDir(viewer));
            roots.add(ComponentStorage.getGlobalComponentDir());
            if (world != null) roots.add(ComponentStorage.getWorldComponentDir(world));
            for (Path root : roots) {
                ComponentPackageIO.Verified primary;
                try { primary = ComponentPackageInstallations.inspect(root, id); }
                catch (java.io.IOException missing) { continue; }
                if (!entry.packageRef.equals(primary.contentRef())) continue;
                var sources = ComponentPackageInstallations.sources(root, id);
                if (index >= sources.size()) return unavailable(id, index);
                var source = sources.get(index); var manifest = source.manifest();
                return new Page(id, index, sources.size(), index == 0, bounded(source.component().name, 256),
                        manifest.namespace(), manifest.version(), manifest.author(), manifest.license(),
                        manifest.trainingPermission(), source.contentRef(), null);
            }
        } catch (java.io.IOException | RuntimeException invalid) { /* No existence or filesystem details disclosed. */ }
        return unavailable(id, index);
    }
    private static Page unavailable(String id, int index) {
        return new Page(bounded(id, 64), index, 0, false, null, null, null, null, null, null, null, "来源不可用，请刷新构件库后重试");
    }
    static String bounded(String text, int max) {
        if (text == null) return "";
        if (text.length() <= max) return text;
        int end = max;
        if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) end--;
        return text.substring(0, end);
    }
}
