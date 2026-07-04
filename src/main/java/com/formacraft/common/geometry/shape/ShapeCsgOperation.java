package com.formacraft.common.geometry.shape;

/**
 * CSG 操作项：union / subtract / intersect + 形体规格。
 */
public record ShapeCsgOperation(CsgOp op, ShapeSpec spec) {}
