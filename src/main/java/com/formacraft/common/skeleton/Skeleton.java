package com.formacraft.common.skeleton;

/**
 * Skeleton generates a topology plan (not blocks).
 */
public interface Skeleton<P extends SkeletonPlan> {
    SkeletonType type();
    P generate(SkeletonParams params);
}


