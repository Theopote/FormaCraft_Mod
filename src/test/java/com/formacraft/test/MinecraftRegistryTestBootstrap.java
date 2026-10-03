package com.formacraft.test;

/** Registry initialization must run under fabric-loader-junit, not plain JUnit. */
public final class MinecraftRegistryTestBootstrap {
    private MinecraftRegistryTestBootstrap() {}

    public static synchronized void initialize() {
        net.minecraft.SharedConstants.createGameVersion();
        net.minecraft.Bootstrap.initialize();
    }
}
