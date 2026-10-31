# Python vs Java planning boundary

**Python orchestrator** owns:

- Intent research / RAG injection
- Prompt assembly (`systemPrompt` / `userPrompt` / `userMessage`)
- Schema-level LlmPlan JSON validity and light repair loops
- Bounded concurrency for `/build`

**Java server** owns:

- Geometry compilation (`ComponentPlanCompiler`, routers, typology, assembly)
- Quality gates (Fatal/Error/Warning) and preview / confirm / undo
- Landmark module registry and structure generators
- World mutation via `BlockMutationAccess`

Do **not** add parallel landmark BuildingSpec templates in Python. Prefer LlmPlan + Java registries.
Do **not** upgrade Python semantic “patches” into hard geometry rules that duplicate the compiler.
