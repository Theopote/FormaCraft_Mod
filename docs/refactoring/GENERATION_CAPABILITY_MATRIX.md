# Generation capability matrix

Trunk: **LlmPlan → ComponentPlanCompiler → BlockPatch → Preview/Confirm**.

| Expression | Owner | Status | Delete when |
|---|---|---|---|
| LlmPlan `components[]` | Java compiler | Trunk | — |
| LlmPlan `plan_program` | Java PlanProgramCompiler | Supported | Merge into components compiler |
| LlmPlan `patch` | Java incremental path | Supported | — |
| Typology interpreter | Java typology | Legacy-compatible | All typology hints migrate to components |
| Assembly / MetaAssembly | Java assembly | Supported for free-form | — |
| FBD DSL | Java + Python repair | Experimental | Feature flag off permanently |
| Skeleton pipeline | Java skeleton | Supported | — |
| BuildingSpec + `GeneratorRouter` | Java structure | Legacy | Zero BuildingSpec C2S / orchestrator `kind=buildingspec` |
| Hardcoded Python landmark BuildingSpec | Python (removed from dispatch) | Retired | Delete unused `_generate_*` bodies |

## Routers

- **`UnifiedGeneratorRouter`**: LlmPlan / component trunk. New capabilities land here.
- **`GeneratorRouter`**: BuildingSpec legacy only. Do not add new registry keys for new product features.
- **`GenerationHub`**: Dispatches between the two; must not invent a third path.

## CI freeze

`scripts/check_structure_generator_freeze.py` fails if `StructureGeneratorRegistry.register` call count grows beyond the baseline in `config/structure-generator-baseline.json`.
