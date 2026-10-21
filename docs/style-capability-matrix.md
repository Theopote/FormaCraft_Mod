# Style capability acceptance matrix

Updated 2026-10-22. Asset presence and offline fixture coverage do not certify stable end-to-end support.

## Acceptance axes

| Axis | Required evidence |
| --- | --- |
| Intent | Bilingual positive and whole-style refusal tests; correct host scope for mixed requests |
| Parameters | Explicit defaults/materials and refusal assertions; user instructions override defaults |
| Geometry | Actual compiled blocks, openings, support and clipping tests for the named fixture |
| Preview/build | Recorded game version, seed, prompt/plan, coordinates and preview-to-build comparison |
| Visual identity | Day/night screenshots where relevant and review against explicit distinguishing features |
| Local edits | Before/after plan and world evidence; requested edit applied while unrelated style and geometry remain |

## Asset inventory (not capability certification)

Counts and references below are generated from bundled profiles, culture cards and the pack manifest. A dash means no dedicated card/manifest entry found, not that generation is impossible.

| Profile | Culture cards | Manifest fixtures |
| --- | --- | --- |
| ASIAN | - | - |
| Alpine_Chalet | alpine_chalet_house | alpine_chalet_compact.json, alpine_chalet_large.json |
| Arctic_Igloo | arctic_igloo | p2_arctic_igloo_13.json, p2_arctic_igloo_21.json |
| Art_Deco | art_deco_tower | art_deco_vertical_compact.json, art_deco_vertical_large.json |
| Brutalism | brutalist_civic | brutalist_concrete_compact.json, brutalist_concrete_large.json |
| Chinese_Imperial_Official | mingqing_courtyard, temple_of_heaven | - |
| Chinese_Vernacular_Huizhou | huizhou_vernacular | - |
| Chinese_Vernacular_Jiangnan_WaterTown | - | - |
| Chinese_Vernacular_Siheyuan | siheyuan_courtyard | - |
| Chinese_Vernacular_Tulou | - | - |
| Cyberpunk | cyberpunk_block | p2_cyberpunk_block_13.json, p2_cyberpunk_block_21.json |
| Dark_Fortress | dark_fortress | p2_dark_fortress_13.json, p2_dark_fortress_21.json |
| Deconstructivism_Zaha | - | - |
| Desert_Adobe | desert_adobe | p2_desert_adobe_13.json, p2_desert_adobe_21.json |
| Dwarven_Hall | dwarven_hall | p2_dwarven_hall_13.json, p2_dwarven_hall_21.json |
| Elven_Treehouse | elven_treehouse | p2_elven_treehouse_13.json, p2_elven_treehouse_21.json |
| FUTURISTIC | - | - |
| Fantasy_Elven | - | - |
| Georgian_Brick | georgian_brick_house, georgian_civic_hall | georgian_brick_compact.json, georgian_brick_large.json |
| Gothic_Cathedral | gothic_cathedral, gothic_sacred | - |
| Greco_Roman_Classical | - | - |
| Industrial_Brick_Hall | industrial_brick_hall | industrial_brick_compact.json, industrial_brick_large.json |
| Industrial_Structure | - | - |
| Japanese_Traditional | japanese_rural_inn, japanese_timber_house, japanese_traditional | japanese_timber_compact.json, japanese_timber_large.json |
| Legacy_Classical_Facade | - | - |
| MEDIEVAL | - | - |
| MODERN | - | - |
| Medieval_Castle | castle_compound, medieval_castle, square_tower_five_story | - |
| Mediterranean_Stucco | mediterranean_guesthouse, mediterranean_stucco_house | mediterranean_stucco_compact.json, mediterranean_stucco_large.json |
| Midcentury_House | midcentury_house, midcentury_visitor_center | midcentury_pavilion_compact.json, midcentury_pavilion_large.json |
| Modern_International | golden_gate_bridge, international_glass_office, modern_international, modern_skyscraper | international_glass_compact.json, international_glass_large.json |
| Neoclassical_Civic | neoclassical_civic_hall | neoclassical_civic_compact.json, neoclassical_civic_large.json |
| Prairie_Earth | prairie_earth | p2_prairie_earth_13.json, p2_prairie_earth_21.json |
| Prairie_House | prairie_community_library, prairie_house | prairie_horizontal_compact.json, prairie_horizontal_large.json |
| RUSTIC | - | - |
| Renaissance_Classical | renaissance_palazzo | renaissance_stone_compact.json, renaissance_stone_large.json |
| Scandinavian_Timber | nordic_forest_lodge, scandinavian_timber_house | scandinavian_timber_compact.json, scandinavian_timber_large.json |
| Steampunk | steampunk_hall | p2_steampunk_hall_13.json, p2_steampunk_hall_21.json |
| Stilt_Timber | stilt_timber | p2_stilt_timber_13.json, p2_stilt_timber_21.json |
| Tang_Dynasty_Timber | foguang_temple_hall, tang_study_hall, tang_timber_hall | tang_timber_compact.json, tang_timber_large.json |
| Water_Platform | water_platform | p2_water_platform_13.json, p2_water_platform_21.json |
| Xianxia_Island | xianxia_island | p2_xianxia_island_13.json, p2_xianxia_island_21.json |

## Capability evidence and remaining acceptance

`Covered` below is limited to the named offline suites/fixtures; it is not a claim about arbitrary prompts. Historical baseline results are recorded in style-p1/p2/p3-acceptance.md.

| Scope | Intent | Parameters | Geometry | Preview/build | Visual identity | Local edits |
| --- | --- | --- | --- | --- | --- | --- |
| 25 manifest styles | Bilingual/refusal cases: test_style_p1_acceptance | Fixture/schema checks: same suite | Shared plan replay and constraint parity; P2 acceptance | Pending live evidence | Pending reviewed screenshots | Pending per-style world evidence |
| Northern siheyuan / Huizhou split | test_siheyuan_courtyard_p0 regional cases | Siheyuan gray-brick defaults and ornament isolation assertions | Existing courtyard golden checks enclosure only; distinct Huizhou geometry pending | Pending | Pending | Pending |
| Startup user packs | test_user_style_packs sample integration | Sample defaults/ornament tests | UserStylePackRuntimeTest sample compilation/clipping | Settings clicks and live build pending | Pending | Pending |

## Next acceptance queue

1. Northern siheyuan vs Huizhou: distinguish gray-brick enclosed courtyard from white-wall/horse-head-gable dwelling; add dedicated Huizhou geometry fixtures before asserting that silhouette executes.
2. Water/stilt: test terrain clearance, pile contact, shore access and preservation of existing water/underwater blocks.
3. Dwarven hall: current barrel-vaulted above-ground composition is not underground excavation; test excavation separately.
4. Elven treehouse: current trunk/deck support does not establish connected bridges, spiral access or a tree city.
5. Cyberpunk: luminous registry blocks and disable behavior have offline coverage; record actual night rendering and passage visibility.
6. Deconstructivism: declared profile/algorithm does not establish continuous curved geometry.
7. Local edits: replay roof/material/opening changes for each accepted style, then compare unaffected components and actual built blocks.

Architecture style, building program/type, and terrain/structural setting should remain separate review axes. Stadium is a program/type; water placement is a setting. Islamic traditions require distinct scoped assets rather than a single onion-dome label.
