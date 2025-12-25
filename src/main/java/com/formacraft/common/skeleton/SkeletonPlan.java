package com.formacraft.common.skeleton;

/**
 * Marker interface for skeleton outputs.
 * Concrete skeleton families should define their own plan types.
 */
public interface SkeletonPlan {
    SkeletonType type();
}


