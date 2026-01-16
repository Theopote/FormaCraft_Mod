package com.formacraft.common.mass;

import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3i;

/**
 * MassDefinition（体量定义）
 * <p>
 * 定义单个建筑体量的几何属性，相对于 Plan Domain 的位置和尺寸
 * <p>
 * 这是 Building Mass Assembly 的核心数据结构之一。
 */
public class MassDefinition {
    /** 体量唯一标识 */
    public final String id;

    /** 体量原型 */
    public final MassPrototype prototype;

    /** 体量的边界框（相对于 Plan Domain 的坐标） */
    public final Box bounds;

    /** 在 Domain 内的偏移（相对于 Domain 原点） */
    public final Vec3i offset;

    /** 旋转（0, 90, 180, 270 度，或更精确的角度） */
    public final Rotation rotation;

    /** 层数 */
    public final int floorCount;

    /** 层高（每层的高度） */
    public final double floorHeight;

    public MassDefinition(
            String id,
            MassPrototype prototype,
            Box bounds,
            Vec3i offset,
            Rotation rotation,
            int floorCount,
            double floorHeight
    ) {
        this.id = id != null ? id : "mass_" + System.nanoTime();
        this.prototype = prototype != null ? prototype : MassPrototype.BLOCK;
        this.bounds = bounds;
        this.offset = offset != null ? offset : Vec3i.ZERO;
        this.rotation = rotation != null ? rotation : Rotation.NONE;
        this.floorCount = Math.max(1, floorCount);
        this.floorHeight = floorHeight > 0 ? floorHeight : 3.0;
    }

    /**
     * Rotation（旋转）
     * <p>
     * v1 简化：只支持 90 度倍数旋转
     */
    public enum Rotation {
        /** 无旋转 */
        NONE(0),
        /** 90 度 */
        ROT_90(90),
        /** 180 度 */
        ROT_180(180),
        /** 270 度 */
        ROT_270(270);

        public final int degrees;

        Rotation(int degrees) {
            this.degrees = degrees;
        }
    }
}
