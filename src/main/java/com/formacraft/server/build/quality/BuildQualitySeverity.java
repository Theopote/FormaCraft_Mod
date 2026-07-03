package com.formacraft.server.build.quality;

/** Graded build-quality severity for preview / apply decisions. */
public enum BuildQualitySeverity {
    /** Blocks preview entirely. */
    FATAL,
    /** Preview allowed; apply discouraged by default. */
    ERROR,
    /** Preview and apply allowed with caution. */
    WARNING,
    /** Informational summary for the player. */
    INFO
}
