package com.formacraft.server.init;

import com.formacraft.common.palette.DefaultPalettes;
import com.formacraft.common.skeleton.SkeletonExecutors;
import com.formacraft.common.style.presets.DefaultStyleProfiles;
import com.formacraft.server.skeleton.gen.SkeletonBuildService;
import com.formacraft.server.skeleton.gen.SkeletonSemanticRegistry;
import com.formacraft.server.skeleton.gen.assembler.ComponentAssemblerRegistry;
import com.formacraft.FormacraftMod;

/**
 * Skeleton 系统初始化器
 * 
 * 统一管理所有 Skeleton/Component/Semantic/Geometry 相关系统的初始化
 */
public final class SkeletonSystemInitializer {

    private SkeletonSystemInitializer() {}

    /**
     * 初始化所有 Skeleton 相关系统
     * 
     * 应该在 mod 初始化时调用（FormacraftMod.onInitialize()）
     */
    public static void initialize() {
        FormacraftMod.LOGGER.info("Initializing Skeleton System...");

        // 1. 初始化默认调色板（SemanticPalette）
        DefaultPalettes.bootstrap();
        FormacraftMod.LOGGER.info("  ✓ DefaultPalettes initialized");

        // 2. 初始化默认风格配置（SemanticStyleProfile）
        DefaultStyleProfiles.bootstrap();
        FormacraftMod.LOGGER.info("  ✓ DefaultStyleProfiles initialized");

        // 3. 注册默认语义生成器（SkeletonSemanticRegistry）
        SkeletonSemanticRegistry.registerDefaults();
        FormacraftMod.LOGGER.info("  ✓ SkeletonSemanticRegistry initialized");

        // 4. 注册默认组件装配器（ComponentAssemblerRegistry）
        ComponentAssemblerRegistry.registerDefaults();
        FormacraftMod.LOGGER.info("  ✓ ComponentAssemblerRegistry initialized");

        // 5. 注册骨架执行门面（Phase 1：common 编译器通过 SkeletonExecutors 调用）
        SkeletonExecutors.register(new SkeletonBuildService());
        FormacraftMod.LOGGER.info("  ✓ SkeletonExecutor registered");

        FormacraftMod.LOGGER.info("Skeleton System initialization complete!");
    }
}

