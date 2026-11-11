package com.formacraft.common.generation.component.util;

/** Supported roof geometry kinds for {@link com.formacraft.common.generation.component.impl.RoofGenerator}. */
public enum RoofTypeKind {
    FLAT,
    GABLE,
    DOUBLE_GABLE,
    HIP,
    XIESHAN,
    XUANSHAN,
    PYRAMID,
    CONE,
    DOME,
    MANSARD,
    SAWTOOTH,
    SHED,
    GAMBREL,
    BARREL_VAULT,
    ONION_DOME,
    STEEP_THATCH,
    JUANPENG,
    SALTBOX,
    JUANPENG_YINGSHAN,
    JUANPENG_XUANSHAN,
    BUTTERFLY,
    CROSS_GABLE,
    HEMISPHERICAL_DOME,
    POINTED_DOME,
    BELLCAST;

    public boolean isDome() {
        return this == DOME || this == HEMISPHERICAL_DOME || this == POINTED_DOME;
    }

    public boolean isProfiledGable() {
        return this == JUANPENG || this == JUANPENG_YINGSHAN || this == JUANPENG_XUANSHAN
                || this == SALTBOX || this == BUTTERFLY || this == CROSS_GABLE || this == BELLCAST;
    }
}
