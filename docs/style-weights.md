# Scoped style priority

`StyleSpec.weight` is optional, finite, and between 0 and 1. Omission means a
priority of 1 for compatibility; zero disables defaults supplied by that spec.
Weights are relative priorities, not percentages, and need not sum to 1.

For researched feature defaults, supported mappings still require source URLs
and sufficient confidence after evidence validation. Among eligible mappings
bound to the same target, the highest weight wins. Conflicting values tied at
the highest weight remain unresolved; input ordering never breaks the tie.
Lower-priority mappings are reported as `lower_weight_default`. Explicit plan
parameters and user contract requirements retain priority over all defaults.
Weight does not create scope bindings or bypass ambiguous ownership.

Hosted catalog enrichment uses the same priority among styles whose scope
matches that host. A component's explicit local style retains priority. Catalog
defaults are authored assets and remain distinct from researched evidence.
Each host resolves independently, so an accent building can retain its own style
even when its weight is lower than a style attached to a different host.

The research prompt requests weights only for user-specified main/accent intent
or ratios. Unspecified priorities remain omitted. There is no numeric blending
of roofs, dimensions, or block palettes. The mixed garden/chapel regression fixture
in `python_backend/eval/fixtures/style_mix_garden_chapel.json` exercises both
enrichment stages without changing positions or dimensions.
