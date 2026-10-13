package com.formacraft.server.compiler;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RoofAuditGateTest {
    @Test
    void structuralShapeMismatchProducesGap() {
        var result = new RoofShapeAudit.Result("r1", "mismatch", "missing_slope_column", List.of(), 1, 1);
        var gap = RoofShapeAudit.toCapabilityGap(result);
        assertTrue(gap.isPresent());
        assertEquals("E_ROOF_SHAPE_FAILED", gap.get().code());
    }

    @Test
    void visualShapeMismatchDoesNotProduceGap() {
        var result = new RoofShapeAudit.Result("r1", "mismatch", "longitudinal_section_shift", List.of(1, 2), 2, 2);
        assertTrue(RoofShapeAudit.toCapabilityGap(result).isEmpty());
    }

    @Test
    void notAssessedShapeDoesNotProduceGap() {
        var result = new RoofShapeAudit.Result("r1", "not_assessed", "complex_roof", List.of(), -1, 0);
        assertTrue(RoofShapeAudit.toCapabilityGap(result).isEmpty());
    }

    @Test
    void structuralSeamMismatchProducesGap() {
        var result = new RoofSeamAudit.Result("r1", "h1", "mismatch", "missing_seam_cell", null, 2, 10);
        var gap = RoofSeamAudit.toCapabilityGap(result);
        assertTrue(gap.isPresent());
        assertEquals("E_ROOF_SEAM_FAILED", gap.get().code());
    }

    @Test
    void notAssessedSeamDoesNotProduceGap() {
        var result = new RoofSeamAudit.Result("r1", "h1", "not_assessed", "unbound_roof", null, 0, 0);
        assertTrue(RoofSeamAudit.toCapabilityGap(result).isEmpty());
    }
}
