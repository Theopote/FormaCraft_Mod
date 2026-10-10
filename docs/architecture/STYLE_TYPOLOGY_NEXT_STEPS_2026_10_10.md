# Style and typology next steps

## Current inspection

Style identity, research intent, scoped feature evidence and hosted enrichment exist.
The research feature compiler had only flat/gable mappings, although RoofGenerator
accepts hip, pyramid, xuanshan and xieshan. This batch adds exact Chinese/English
feature phrases for these existing roof types. It does not infer a roof from a
country, culture, religion or building use. Existing evidence confidence, host
binding and user override guards remain in force. This is parameter mapping,
not a new roof geometry implementation or proof of cultural fidelity.

## Priority sequence

1. Separate building use from structural typology and style in acceptance cases.
   Compare one style across house/shop/public hall and one use across styles.
   Check spatial organization, entries and circulation, not just palette names.
2. Extend executable feature mappings beyond roofs: facade rhythm, openings,
   mass proportions and structural expression. Each mapping needs a supported
   generator, scope binding, user override test and final block assertion.
3. Audit typology coverage against actual interpreters and parameter schemas.
   Mark unsupported uses/structures explicitly rather than selecting a landmark
   or silently replacing them with a house. Unknown types remain compositional.
4. Add multilingual and mixed-building cases with independent host IDs. Current
   Chinese window-count templates are deliberately narrower than global styles.
5. Strengthen research evidence: retrieved URL presence is not verification of
   page contents. Add source text locations and per-feature support checks before
   introducing a trusted persistent style-card cache.
6. Extend final geometry acceptance across roof shapes and typologies. Existing
   simple gable/flat audits do not establish hip/xieshan cultural accuracy.

## This batch validation

Exact feature mappings, insufficient evidence and unsupported broad cultural
phrases are tested offline. Existing scoped-default tests must continue to verify
user roof overrides and unrelated-host isolation. Game visual acceptance remains
necessary. Python backend restart loads these changes; no new JAR is needed.
