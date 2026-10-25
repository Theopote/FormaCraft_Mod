# Style capability acceptance matrix

Updated 2026-10-25. Asset presence and offline fixture coverage do not certify stable end-to-end support.

## Live workflow gate status

The [2026-10-25 player findings](phase1/INGAME_FINDINGS_2026_10_25.md)
record the tested baseline `ff7039cd` and subsequent afternoon observations.
These observations do not certify the dedicated style fixtures below. Fixes
reported in that document require a new recorded game session before promotion.

| Gate | Recorded result | Required next evidence |
| --- | --- | --- |
| Incremental edit avoids whole-building rebuild | Earlier failure; afternoon PATCH guard safely rejected an unsupported payload | Retest the new local `components[]` to BlockPatch path: opening appears at the existing host and unrelated blocks remain unchanged |
| Preview agrees with confirmed build | Suspected mismatches; comparison incomplete | Same ticket, anchor and plan; record preview and actual placed blocks, including terrain skips |
| Undo restores the prior world | Incomplete lakeside Undo; connection-state and water conflicts recorded | Capture pre-build state, build, Undo and compare affected cells, including water and fence connections |
| Assembly routing | Cabin authority conflict observed; fix reported, pending retest | Confirm the cabin plan reaches the assembly executor without authority conflict |
| Fence material and placement | Oak fence became a wall/stone platform; fix reported, pending retest | Confirm linear oak fence blocks and absence of an unsolicited stone pad |
| Pavilion and landmark identity | Open pavilion, pagoda openings and Temple of Heaven proportions remain deficient | Daylight views and block inspection against the requested openings, columns and roof proportions |

Passing offline style tests does not close these shared workflow gates. No new
live pass is claimed by this matrix update.

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
| Chinese_Vernacular_Huizhou | huizhou_vernacular, huizhou_tianjing | Outside manifest: huizhou_tianjing_17.json, huizhou_tianjing_21.json |
| Chinese_Vernacular_Jiangnan_WaterTown | jiangnan_quay | jiangnan_quay_17.json, jiangnan_quay_25.json |
| Chinese_Vernacular_Siheyuan | siheyuan_courtyard | - |
| Chinese_Vernacular_Tulou | tulou_ring | tulou_ring_10.json, tulou_ring_14.json (outside manifest) |
| Cyberpunk | cyberpunk_block | p2_cyberpunk_block_13.json, p2_cyberpunk_block_21.json |
| Dark_Fortress | dark_fortress | p2_dark_fortress_13.json, p2_dark_fortress_21.json |
| Deconstructivism_Zaha | - | - |
| Desert_Adobe | desert_adobe | p2_desert_adobe_13.json, p2_desert_adobe_21.json |
| Dwarven_Hall | dwarven_hall, dwarven_underground_hall | p2_dwarven_hall_13.json, p2_dwarven_hall_21.json; underground program: dwarven_underground_13.json, dwarven_underground_21.json |
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
| Romanesque | romanesque_hall | romanesque_hall_17.json, romanesque_hall_25.json |
| Tudor | tudor_house | tudor_house_17.json, tudor_house_25.json |
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
| 28 manifest styles | Bilingual/refusal cases: test_style_p1_acceptance | Fixture/schema checks: same suite | Shared plan replay and constraint parity; P2 acceptance | Pending live evidence | Pending reviewed screenshots | Pending per-style world evidence |
| Northern siheyuan / Huizhou split | test_siheyuan_courtyard_p0 regional cases | Gray-brick defaults, isolated ornaments, bilingual gable toggle and ambiguous-scope refusal | Two Huizhou dwelling fixtures compile; stepped/capped symmetric end walls, attachment, hollow attic and Patch/Build clipping checked in ConstraintParityTest | Pending | Pending | Gable disable replay preserves all blocks outside upper end planes; world edit chain pending |
| Stilt timber / Water platform fixed sites | Existing manifest bilingual/refusal tests | Explicit PRESERVE elevations and authored access; helper/few-shot equality tests | Five fixtures: pile/deck contact, entrance path and two-cell air clearance, no raw underfloor writes outside piles, clipping parity | Pending terrain/water simulation | Pending | Pending world edits |
| Single elven treehouse | Existing manifest bilingual/refusal tests | Explicit PRESERVE heights and authored timber access; helper/few-shot equality | Two fixtures: trunk/floor contact, three clear walking columns, two-high supported edge guards, landing/entrance clearance and clipping parity | Pending live site/walking/collision checks | Pending | Access-removal and guard-only removal replays check block locality; world editing pending |
| Underground dwarven hall program | Bilingual culture retrieval, refusal and generic classification | Fixed surface/floor heights, explicit excavation, hosted overwrite authorization | Two variants replay into solid stone: room/approach excavation, vault shell/cover, write bounds and clipping parity | Pending live excavation and fluids | Pending | Omitted-excavation control covered; user/world edit chain pending |
| Startup user packs | test_user_style_packs sample integration | Sample defaults/ornament tests | UserStylePackRuntimeTest sample compilation/clipping | Settings clicks and live build pending | Pending | Pending |
| Desert adobe / Prairie earth circulation | Existing manifest bilingual/refusal cases | Fixed dry level site; explicit entry, small windows, hosted stair and guarded landing | Four full compiler replays check ground/upper paths, floor opening, clearance, window adjacency and shared clipping | Pending live collision/fall-protection review | Pending | Window-disable and stair-removal replays compare before/after block unions; missing stair restores floor and breaks upper access; world edits pending |
| Dark fortress tower circulation | Existing manifest bilingual/refusal cases | Fixed dry level site, explicit portals/thresholds and three flights per enlarged tower | Two full compiler replays prove ground/top-platform paths, stair states, clearance holes and shared clipping | Pending live collision/fall-protection review | Pending | Left portal-close and missing-middle-flight controls isolate ground/top access; right tower remains reachable; world editing pending |
| Tulou native blueprint | Bilingual retrieval/refusal and generic classification | Dedicated earthen palette; two radius/floor variants and proportion card | Native compiler/generator replay checks ring floors, courtyard and clipping | Pending live adapter/placement | Pending | Pending world edits |
| Jiangnan / Romanesque / Tudor specialists | Manifest bilingual/refusal tests; Jiangnan generic vs named-town boundary tests | Dedicated palette/genome/profile and proportion cards | Six compiled fixtures; quay clearance, round arcade, exposed frame and clipping tests | Pending | Pending | Arcade/frame removal replays; world editing pending |

## Next acceptance queue

The [upper-tower follow-up](style-dark-fortress-upper-acceptance.md) adds authored
straight flights, turning landings and top platforms to the two dark-fortress
fixtures. Live collision and fall-protection review remain pending.

Dark-fortress ground access is now authored with scoped portals and supported
thresholds; see [ground-access acceptance](style-dark-fortress-access-acceptance.md).
Upper tower access and live-world walking/edit evidence remain pending.

Skywell-window follow-up: explicit inward lattice windows now supplement the
front/rear halls without occupying side-wing corner posts. Dark-fortress
fixtures now have two hollow blackstone defensive tower shells each; occupied
tower access and stairs remain pending. Current warning inventory: 238.

2026-10-25: [warning audit](style-warning-audit.md) inventories every unresolved
warning; no waivers were introduced. [Four-wing skywell examples](style-huizhou-tianjing-acceptance.md)
supplement the rectangular Huizhou dwellings. Live and historical review remain pending.

Tulou now has a dedicated specialist asset bundle outside the style manifest;
see [Tulou acceptance](style-tulou-acceptance.md). Development-client capture
failed on 2026-10-23, so live evidence remains pending.

Regional split validation on 2026-10-22: initial 20-test regional/P1/user-pack
suite passed; 35 adjacent identity/catalog/pack/planning/temple tests passed;
five focused regional culture tests passed after the final material correction.
The regional suite replayed all 101 CI goldens with zero HARD failures and 254
existing SOFT warnings. Live rendering and world placement were not exercised.

Huizhou follow-up: two dedicated dwelling examples now live in the culture card
and CI scenarios (outside the 25-style manifest). The regional/ornament/user-pack
Python run passed 22 tests; after additional refusal/scope cases, 14 focused tests
passed. CI golden replay passed 103 HARD scenarios with 262 SOFT warnings.
`constraintParityTest` passed 96 tests and its isolated `userStylePackTest`
dependency passed four tests. The new full-compiler tests cover both ridge
orientations, stepped/capped symmetric gables, attachment, hollow attic,
gable-disable block locality and shared clipping parity. This is offline block
evidence; live world edits, historical fidelity and screenshots remain pending.

1. Northern siheyuan vs Huizhou: compare live gray-brick courtyard and white-wall/horse-head-gable dwelling. The earlier huizhou_vernacular examples remain rectangular dwellings; huizhou_tianjing_17.json and huizhou_tianjing_21.json now add four-wing skywell compounds with offline open-sky, floor/access reachability and clipping assertions. Windows, drainage, historical fidelity and live acceptance remain pending.
2. Water/stilt: fixed-site pile contact, entrance access and untouched underfloor non-pile columns now have offline assertions. Validate arbitrary bed/bank/water levels, automatic landing passes and live water updates next; see [fixed-site acceptance](style-raised-timber-acceptance.md).
3. Dwarven hall: original above-ground examples remain; two dedicated underground program fixtures now exercise dry-stone excavation and a descending approach. Live fluids, site selection, permissions and world edits remain pending. See [underground acceptance](style-dwarven-underground-acceptance.md).
4. Elven treehouse: single-tree support, guarded straight timber access and a guarded landing now have dedicated fixtures and offline assertions. Live walking/collision and site fitting remain; connected canopy bridges, spiral access and a tree city require additional compositions. See [single-treehouse acceptance](style-treehouse-acceptance.md).
5. Cyberpunk: luminous registry blocks and disable behavior have offline coverage; record actual night rendering and passage visibility.
6. Deconstructivism: declared profile/algorithm does not establish continuous curved geometry.
7. Local edits: replay roof/material/opening changes for each accepted style, then compare unaffected components and actual built blocks.

Architecture style, building program/type, and terrain/structural setting should remain separate review axes. Stadium is a program/type; water placement is a setting. Islamic traditions require distinct scoped assets rather than a single onion-dome label.
