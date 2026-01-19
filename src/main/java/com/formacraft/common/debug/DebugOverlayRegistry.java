package com.formacraft.common.debug;

import com.formacraft.common.debug.renderer.PlanDebugRenderer;
import com.formacraft.common.debug.renderer.StructuralDebugRenderer;
import com.formacraft.FormacraftMod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * DebugOverlayRegistry（调试覆盖层注册表）
 * <p>
 * 管理所有 DebugOverlayRenderer 的注册和查找
 */
public final class DebugOverlayRegistry {

    private static final List<DebugOverlayRenderer> renderers = new ArrayList<>();
    private static final Map<DebugLayer, List<DebugOverlayRenderer>> renderersByLayer = new HashMap<>();
    private static boolean initialized = false;

    private DebugOverlayRegistry() {}

    /**
     * 初始化并注册所有默认的 Debug Renderer
     */
    public static void initialize() {
        if (initialized) {
            return;
        }

        FormacraftMod.LOGGER.info("Initializing Debug Overlay Renderers...");

        // 注册 PlanDebugRenderer
        register(new PlanDebugRenderer());
        FormacraftMod.LOGGER.info("  ✓ PlanDebugRenderer registered");

        // 注册 StructuralDebugRenderer
        register(new StructuralDebugRenderer());
        FormacraftMod.LOGGER.info("  ✓ StructuralDebugRenderer registered");

        // 未来：注册 SkeletonDebugRenderer
        // register(new SkeletonDebugRenderer());

        initialized = true;
        FormacraftMod.LOGGER.info("Debug Overlay Renderers initialization complete!");
    }

    /**
     * 注册一个 DebugOverlayRenderer
     */
    public static void register(DebugOverlayRenderer renderer) {
        if (renderer == null) {
            return;
        }

        renderers.add(renderer);

        // 根据支持的图层建立索引
        for (DebugLayer layer : DebugLayer.values()) {
            if (renderer.supportsLayer(layer)) {
                renderersByLayer.computeIfAbsent(layer, k -> new ArrayList<>()).add(renderer);
            }
        }
    }

    /**
     * 获取所有注册的 Renderer
     */
    public static List<DebugOverlayRenderer> getAllRenderers() {
        ensureInitialized();
        return new ArrayList<>(renderers);
    }

    /**
     * 获取支持指定图层的所有 Renderer
     */
    public static List<DebugOverlayRenderer> getRenderersForLayer(DebugLayer layer) {
        ensureInitialized();
        return new ArrayList<>(renderersByLayer.getOrDefault(layer, List.of()));
    }

    /**
     * 确保已初始化
     */
    private static void ensureInitialized() {
        if (!initialized) {
            initialize();
        }
    }
}
