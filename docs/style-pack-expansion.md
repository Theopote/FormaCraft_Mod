# First style pack expansion

The shared manifest at `style_profiles/style_pack_manifest_v1.json` links the first
three additions: Tang timber halls, Renaissance palazzi, and Art Deco towers.
Each pack supplies a twelve-role genome, identity aliases, a profile, a semantic
palette, a culture card, a proportion card, and two plans shared with few-shot
examples. The six plans participate in the Python golden gate and the Java
`constraintParityTest` task.

The second batch adds a Brutalism concrete genome and an industrial brick hall
pack, with two compositional plans each. The existing Brutalism identity keeps
its material alternatives, algorithm hints, slit-window metadata, and parapet
preferences; the new twelve-role genome supplies one deterministic fixture
palette. Industrial brick halls have a separate identity from the existing
industrial structural-system profile, preserving that profile's bridge and truss
semantics. Both additions use existing flat/gable roofs and facade profiles.

The archetype catalog now treats plain `粗野主义` as a generic style hint rather
than a proper name for the megastructure module. Explicit `brutalist megastructure`
and `粗野主义巨构` retain module routing.

The third batch adds Japanese timber and Scandinavian timber house genomes,
each with two single-story plans. Japanese plans use broad eaves and a lower
envelope, while Scandinavian plans use a narrower envelope and taller gable.
Both use existing pane windows and ordinary gable geometry. The Japanese
profile retains its existing shoji/torii metadata, but these compositional
fixtures do not establish execution of shoji, torii, or curved gable ornaments.
The industrial structural profile remains separate from these residential packs.

Every manifest plan now also checks that all twelve genome palette roles name
registered, non-air blocks, with actual door, stair, and slab block types for
those specialized roles. Actual wall/roof output and region clipping remain
separate assertions.

The masonry batch adds Mediterranean stucco houses and Georgian brick houses.
The Mediterranean pair uses white concrete, warm terracotta roof surfaces,
single-story envelopes and restrained openings; the Georgian pair uses brick,
dark tiled hip roofs and repeated two-story openings. Both are authored voxel
interpretations using existing hip roofs and facade profiles. They do not add
arched openings, shutters, or a new ornament generator. Georgian brick houses
share the masonry-palazzo composition; Mediterranean houses register the
single-story `stucco_tile_house` composition.

The pavilion batch adds Prairie and midcentury house packs. Both use low,
single-story rectangular masses and existing flat roof plates, with broader
overhangs and a darker palette for Prairie, and taller pane openings and lighter
masonry for midcentury. These are limited voxel interpretations of horizontal
residential language; they do not claim cantilever analysis, ribbon-window
geometry, or faithful reproduction of every historic roof form. The two packs
share the `flat_roof_pavilion` compositional typology.

The P1 completion batch adds Alpine chalet, neoclassical civic, and international
glass-office genomes. The existing Modern International profile retains its
metadata, material alternatives and algorithm hints while gaining all twelve
roles and explicit component defaults. These three packs reuse timber-gable,
masonry-palazzo, and concrete-civic compositions respectively.

Seven additional culture cards describe distinct exterior programs: Tang study
halls, Japanese rural inns, Nordic forest lodges, Mediterranean guesthouses,
Georgian civic halls, Prairie community libraries, and midcentury visitor centers.
Each has bilingual purpose keywords, its own proportion card and two independently
sized plans. The manifest owns these through `variant_cards`; they are not extra
style identities and do not claim verified interiors or furniture.

Three further authored geometry alternatives support A/B/C diversity evaluation.
Their dimensions or native bay parameters actually differ; an ignored seed alone
does not establish diversity. These are offline fixtures, not live LLM samples.
All primary, program-variant and diversity plans participate in Java compilation
and region parity. The mixed garden/chapel golden has a separate Java test.

These are authored Minecraft interpretations. Tang halls reuse
`tailiang_timber_hall`; Art Deco towers reuse `setback_tower`; Renaissance palazzi
compose ordinary mass, roof, entrance, and facade components. No new native
typology or geometry vocabulary is introduced. In particular, Art Deco currently
uses the existing facade cadence rather than a new vertical-strip window
generator; Renaissance ornament remains limited to existing components.

Profile defaults now populate every declared component type before generic
defaults. Explicit component parameters retain priority. Catalog aliases also
inform profile ranking and generic request classification. Whole-style refusals
exclude corresponding culture cards, while detail refusals such as
`不要哥特窗花` do not ban the entire Gothic style. English article stripping
requires a word boundary so `art deco` and proper names retain their first letter.

To extend this batch, add matching assets and two plans, then append the manifest
and CI scenarios. The manifest-driven tests validate identity, retrieval, negative
requests, profile defaults, references, schemas, compositional Java compilation,
native builder output, rendered wall/roof materials, and equivalent region clipping.
Native router integration with a live `ServerWorld` remains a separate check;
these offline tests invoke native builders directly. Region parity does not
establish historical accuracy or full generator parameter equivalence.
