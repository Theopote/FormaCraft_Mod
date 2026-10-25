package com.formacraft.common.build;

/** Diagnostic provenance only; never grants permission to edit a program or world. */
public record GeometrySource(String componentPath, String slotId, String node, String instance,
                             String path, String producer) {
    public static GeometrySource generated(String producer) {
        return new GeometrySource(null, null, null, null, null, producer);
    }
}
