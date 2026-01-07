package com.formacraft.client.preview;

import com.formacraft.common.layout.LayoutSite;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * LayoutSitePreviewState（站点预览状态）
 * 
 * 核心功能：存储当前预览的站点列表
 */
public final class LayoutSitePreviewState {
    private static final List<LayoutSite> SITES = new ArrayList<>();
    private static boolean enabled = false;

    private LayoutSitePreviewState() {}

    /**
     * 设置站点列表
     */
    public static void setSites(List<LayoutSite> sites) {
        SITES.clear();
        if (sites != null) {
            SITES.addAll(sites);
        }
        enabled = true;
    }

    /**
     * 清除站点列表
     */
    public static void clear() {
        SITES.clear();
        enabled = false;
    }

    /**
     * 是否启用预览
     */
    public static boolean isEnabled() {
        return enabled;
    }

    /**
     * 获取站点列表（只读）
     */
    public static List<LayoutSite> getSites() {
        return Collections.unmodifiableList(SITES);
    }
}

