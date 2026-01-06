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

        // 使用 DefaultPalettes 中的基础映射作为默认风格
        // 这里提供一个基础版本，实际使用时可以从配置文件加载
        // 注意：默认风格不包含几何修饰器，只提供基本的材质映射
        // 如果需要几何修饰，应该使用具体的风格配置（如 MEDIEVAL_CASTLE）

        return style;
    }
}

