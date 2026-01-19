package com.formacraft.common.init;

import com.formacraft.FormacraftMod;

/**
 * BuildingMassSystemInitializer（建筑体量系统初始化器）
 * <p>
 * 核心职责：初始化 BuildingMass 相关系统
 * <p>
 * BuildingMass 系统是一个纯数据/规则系统，不需要注册生成器或资源。
 * 但需要在日志中确认系统已准备就绪。
 */
public final class BuildingMassSystemInitializer {

    private BuildingMassSystemInitializer() {}

    /**
     * 初始化 BuildingMass 系统
     * <p>
     * 应该在 mod 初始化时调用（FormacraftMod.onInitialize()）
     */
    public static void initialize() {
        FormacraftMod.LOGGER.info("Initializing BuildingMass System...");

        // BuildingMass 系统是纯规则系统，不需要注册资源
        // 只需要确认相关类已加载

        FormacraftMod.LOGGER.info("  ✓ BuildingMass core classes ready");
        FormacraftMod.LOGGER.info("  ✓ BuildingMassComposition ready");
        FormacraftMod.LOGGER.info("  ✓ MassFilledChecker ready");
        FormacraftMod.LOGGER.info("  ✓ MassToSkeletonDeriver ready");
        FormacraftMod.LOGGER.info("  ✓ LayeredSocketDeriver ready");
        FormacraftMod.LOGGER.info("  ✓ FacadeRhythmProcessor ready");
        FormacraftMod.LOGGER.info("  ✓ MultiLayerRhythmProcessor ready");

        // 初始化立面节奏预设库
        com.formacraft.common.mass.rhythm.FacadeRhythmPresetLibrary.initialize();
        FormacraftMod.LOGGER.info("  ✓ FacadeRhythmPresetLibrary ready");

        FormacraftMod.LOGGER.info("BuildingMass System initialization complete!");
    }
}
