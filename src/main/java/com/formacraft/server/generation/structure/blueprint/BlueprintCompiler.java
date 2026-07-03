package com.formacraft.server.generation.structure.blueprint;

import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.common.skeleton.SkeletonPlan;

import java.util.Map;

/**
 * BlueprintCompiler:
 * Turns a semantic blueprint (spec.extra.blueprint) into a SkeletonPlan (often a CompoundPlan).
 *
 * Goal:
 * - Make "LLM outputs blueprint JSON" a first-class entry point.
 * - Keep routing stable: adding new blueprint types should not require touching GeneratorRouter.
 */
public interface BlueprintCompiler {
    /** Stable id for debugging/telemetry (e.g. "castle_v1"). */
    String id();

    /**
     * Best-effort check: does this compiler understand the given blueprint?
     * Must be cheap and side-effect-free.
     */
    boolean supports(BuildingSpec parentSpec, Map<String, Object> blueprint);

    /**
     * Compile blueprint into a plan. Return null if compilation fails (caller should fallback).
     */
    SkeletonPlan compile(BuildingSpec parentSpec, Map<String, Object> blueprint);
}


