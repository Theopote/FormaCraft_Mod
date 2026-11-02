package com.formacraft.server.generation.typology.builder;

import com.formacraft.common.typology.TypologyGenerationException;

import java.util.ArrayList;
import java.util.List;

/**
 * Post-generation hard checks for pagoda morphology: floor count, setback, centered finial.
 */
public final class PagodaMorphologyAudit {
    private PagodaMorphologyAudit() {}

    public record Result(
            int expectedFloors,
            int actualFloors,
            int expectedEaveTiers,
            int actualEaveTiers,
            boolean setbackObserved,
            boolean finialCentered,
            List<String> diagnostics
    ) {
        public boolean passed() {
            return diagnostics == null || diagnostics.isEmpty();
        }
    }

    public static Result check(
            String typologyId,
            PagodaVerticalSemantics expected,
            int actualFloors,
            int actualEaveTiers,
            boolean setbackObserved,
            boolean finialCentered,
            boolean hardFail
    ) {
        List<String> diags = new ArrayList<>();
        if (expected != null && expected.floorsExplicit() && actualFloors != expected.floorCount()) {
            diags.add("floor_count expected=" + expected.floorCount() + " actual=" + actualFloors);
        }
        if (expected != null && expected.floorsExplicit() && actualEaveTiers != expected.eaveTiers()) {
            diags.add("eave_tiers expected=" + expected.eaveTiers() + " actual=" + actualEaveTiers);
        }
        if (!setbackObserved) {
            diags.add("setback/taper not observed across storeys");
        }
        if (!finialCentered) {
            diags.add("finial not centered on tower axis");
        }
        Result result = new Result(
                expected == null ? 0 : expected.floorCount(),
                actualFloors,
                expected == null ? 0 : expected.eaveTiers(),
                actualEaveTiers,
                setbackObserved,
                finialCentered,
                List.copyOf(diags));
        if (hardFail && !result.passed()) {
            throw new TypologyGenerationException(
                    typologyId,
                    "E_PAGODA_MORPHOLOGY",
                    "Pagoda morphology failed hard checks: " + String.join("; ", diags),
                    diags);
        }
        return result;
    }
}
