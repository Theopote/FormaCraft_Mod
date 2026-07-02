package com.formacraft.client.tool;

import com.formacraft.common.logging.FcaLog;
import net.fabricmc.loader.api.FabricLoader;

import java.util.List;

/**
 * 工具插件加载器：从 Fabric entrypoints("formacraft_tools") 加载并注册工具。
 */
public final class ToolPluginLoader {
    private ToolPluginLoader() {}

    private static final FcaLog LOG = FcaLog.of("ToolPluginLoader");

    private static boolean loaded = false;

    public static void loadOnce() {
        if (loaded) return;
        loaded = true;

        try {
            ToolRegistry registry = new ToolRegistry();
            List<ToolEntrypoint> eps = FabricLoader.getInstance().getEntrypoints("formacraft_tools", ToolEntrypoint.class);
            for (ToolEntrypoint ep : eps) {
                if (ep == null) continue;
                try {
                    ep.registerTools(registry);
                } catch (Throwable t) {
                    LOG.warn("tool entrypoint registerTools failed", t);
                }
            }
        } catch (Throwable t) {
            LOG.warn("load formacraft_tools entrypoints failed", t);
        }
    }
}
