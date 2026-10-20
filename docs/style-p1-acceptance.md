# P1 acceptance record

P1 is complete for the local, offline asset and execution scope of this roadmap.
This is not completion of P2 geometry expansion or P3 user-pack loading.

| Requirement | Delivered evidence |
| --- | --- |
| Genomes 6 → 20 | Twenty bundled genomes; Java loads every genome through GenomeStyleProfile and compares all twelve roles to its source JSON without fallback |
| Culture cards → 45+ | Forty-five cards; Java reference validation passes; new primary and program cards have bilingual retrieval and refusal checks |
| Typologies 10 → 16 | Ten native and six compositional entries; compositions have executable component plans and no native-interpreter claim |
| Per-style examples | Fourteen manifest style entries each own two primary plans and matching few-shots |
| Program coverage | Seven additional purpose cards each own a proportion card and two distinct-envelope plans |
| Generic ornament contract | Gothic and Chinese timber JSON declarations use the shared loader; existing negation/scope equivalence tests remain in CI |
| Scoped style mixing | Optional priorities preserve ties, evidence gates, explicit parameters and host boundaries; garden/chapel unit and golden fixtures plus Java compilation/parity |
| Negative regressions | Style refusals, retained proper names, explicit component parameters, untrusted evidence, ornament negation/scope, and duplicated diversity inputs |
| A/B/C diversity | Three authored alternatives for each of three style prompts, with all distance/uniqueness checks passing and duplicate detection locked by a negative test |
| Execution parity | All 45 manifest plans and the mixed golden compile; both regional clipping paths preserve identical patches/materials |

The seven purpose cards are Tang study hall, Japanese rural inn, Nordic forest
lodge, Mediterranean guesthouse, Georgian civic hall, Prairie community library,
and midcentury visitor center. They describe exterior program envelopes; they do
not claim verified interiors, furniture or accessible circulation.

Final checks:

- `python -m eval.ci_gate` from `python_backend/`: all seven steps passed.
- Python unit gate: 340 tests.
- Golden gate: 67 CI scenarios, zero hard failures.
- Diversity gate: existing stadium plus three A/B/C scenarios; authored alternatives,
  not live LLM sampling. Parameters change actual geometry, not only ignored seeds.
- `gradle constraintParityTest --offline`: 58 tests, zero failures/errors.
- `gradle validateCultureCards --offline`: 45 cards passed reference validation.
- `git diff --check`: passed.

Use the repository virtual environment and an installed/cached Gradle runtime.
The Python gate accepts `FORMACRAFT_GRADLE` to select that runtime when the wrapper
cannot download dependencies. No external service is required for this acceptance.

Limits remain explicit: native typology routers require a live ServerWorld;
offline tests call their pure builders. Compositional plans use the actual
component compiler. Registered door/stair/slab roles and their exact runtime
adapter values are checked, while a single plan need not render every palette
role. Voxel interpretations use supported geometry and do not establish visual
or historical fidelity. New fantasy skeletons, new geometry vocabulary, visual
case integration, emissive strategy and user-provided pack loading remain P2/P3.
