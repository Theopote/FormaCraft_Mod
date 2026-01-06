package com.formacraft.common.style.presets;

import com.formacraft.common.style.SemanticStyleProfile;
import com.formacraft.common.style.SemanticStyleProfileRegistry;

/**
 * 默认风格配置
 * 
 * 初始化所有预设风格
 */
public final class DefaultStyleProfiles {

    private DefaultStyleProfiles() {}

    /**
     * 初始化默认风格配置
     * 
     * 在 mod 初始化时调用
     */
    public static void bootstrap() {
        // 注册中世纪城堡风格
        SemanticStyleProfileRegistry.register(MedievalCastleProfile.create());

        // 创建并注册默认风格（作为 fallback）
        SemanticStyleProfile defaultProfile = createDefaultProfile();
        SemanticStyleProfileRegistry.register(defaultProfile);
    }

    /**
     * 创建默认风格配置（作为 fallback）
     */
    private static SemanticStyleProfile createDefaultProfile() {
        SemanticStyleProfile style = new SemanticStyleProfile("DEFAULT");

        // 使用简单的默认映射
        // 实际使用时，应该从配置文件加载或使用更完善的默认值
        // 这里先提供一个基础版本

        return style;
    }
}

