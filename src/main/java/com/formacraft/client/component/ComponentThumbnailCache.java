package com.formacraft.client.component;

import com.formacraft.FormacraftMod;
import com.formacraft.common.component.ComponentIds;
import com.formacraft.common.component.ComponentStorage;
import net.minecraft.client.MinecraftClient;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Full-resolution, bounded cache. Disk reads and rasterization never run in GUI draw calls.
 * Old flat-color PNGs are replaced in memory from the component's actual block definition. */
public final class ComponentThumbnailCache {
    private ComponentThumbnailCache() {}
    private static final Map<String, Cached> CACHE = new LinkedHashMap<>(64, .75f, true);
    private static final Set<Cached> LOADING = Collections.newSetFromMap(new IdentityHashMap<>());
    private static final class Cached {
        final long revision;
        BufferedImage image;
        Cached(long revision) { this.revision = revision; }
    }
    private record Loaded(com.formacraft.common.component.ComponentDefinition definition, BufferedImage fallback) {}
    public static BufferedImage getImage(String componentId, long revision) {
        String id = ComponentIds.tryNormalizeLoadId(componentId);
        if (id == null) return null;
        Cached cached = CACHE.get(id);
        if (cached != null && cached.revision == revision) return cached.image;
        // Bound pending model snapshots, not just the retained images.
        if (LOADING.size() >= 2) return null;
        Cached request = new Cached(revision); CACHE.put(id, request); LOADING.add(request);
        while (CACHE.size() > 96) CACHE.remove(CACHE.keySet().iterator().next());
        MinecraftClient client = MinecraftClient.getInstance();
        UUID player = client.player == null ? null : client.player.getUuid();
        CompletableFuture.supplyAsync(() -> {
            var definition = ComponentStorage.loadComponent(null, id, player);
            if (definition != null) return new Loaded(definition, null);
            var png = ComponentIds.tryResolveComponentFile(ComponentStorage.getGlobalComponentDir(), id, ".png");
            try {
                if (png != null && Files.isRegularFile(png) && Files.size(png) < 2_000_000)
                    return new Loaded(null, ImageIO.read(png.toFile()));
            } catch (Exception e) { FormacraftMod.LOGGER.debug("Thumbnail fallback read failed: {}", id, e); }
            return new Loaded(null, null);
        }).whenComplete((loaded, error) -> client.execute(() -> {
            if (CACHE.get(id) != request) { LOADING.remove(request); return; }
            if (error != null || loaded == null || loaded.definition == null) {
                request.image = loaded == null ? null : loaded.fallback; LOADING.remove(request); return;
            }
            ComponentThumbnailGenerator.generateAsync(loaded.definition).whenComplete((image, renderError) -> client.execute(() -> {
                LOADING.remove(request);
                if (CACHE.get(id) != request) return;
                if (renderError == null) request.image = image;
                else FormacraftMod.LOGGER.warn("Model thumbnail failed: {}", id, renderError);
            }));
        }));
        return null;
    }
    public static void clearCache(String id) { CACHE.remove(id); }
    public static void clearAllCache() { CACHE.clear(); }
}
