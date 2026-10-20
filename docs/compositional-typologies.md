# Compositional typology routing

The existing registry distinguishes `typology_first` from `compositional_only`.
Research, plan prompts, architectural enrichment, and migrated-module repair now
honor that distinction instead of treating every typology as a native STRUCTURE.

| Typology | Linked packs | Execution |
| --- | --- | --- |
| timber_gable_house | Japanese timber, Scandinavian timber, Alpine chalet | Timber mass with hosted gable roof, windows, entrance |
| masonry_palazzo | Renaissance, Georgian brick, neoclassical civic | Masonry mass, hip roof, repeated facade openings |
| industrial_brick_hall | Industrial brick | Brick hall, gable roof, mullion facade |
| concrete_civic_block | Brutalism, Modern International | Civic/office mass and flat roof; openings supplied by the style profile |
| stucco_tile_house | Mediterranean stucco | Low single-story stucco mass, warm tiled hip roof, restrained openings |
| flat_roof_pavilion | Prairie house, midcentury house | Low single-story mass, flat roof plate, hosted pane openings |

Each entry links existing culture/proportion cards and two plans per linked pack.
Component IDs and host bindings remain explicit. Registry defaults are planning
guidance, not an additional geometry executor. The existing component compiler
generates blocks, and user geometry/material requirements retain precedence.

Research returns the structural typology ID with compositional recommended
components and no landmark module/reference. Both compact and expanded prompts
request composition. Alignment rejects fake STRUCTURE/MODULE plans or missing
MASS_MAIN/ROOF and checks the typology hint. Module repair does not manufacture a
nonexistent native interpreter for these entries.

An explicitly named non-migrated preset retains priority over a generic
compositional culture card. Architectural enrichment preserves its MODULE-only
recommendation; native typology-first landmarks retain their existing policy.

Python regressions cover bilingual discovery, profile finalization, prompts,
repair, invalid-route rejection, and full plan normalization. The manifest-driven
Java tests read the same registry, assert the absence of native routing claims,
compile the linked primary, program-variant and diversity fixtures, validate
materials, and compare region clipping.
Native interpreter tests remain separate; this batch does not add native builders.
